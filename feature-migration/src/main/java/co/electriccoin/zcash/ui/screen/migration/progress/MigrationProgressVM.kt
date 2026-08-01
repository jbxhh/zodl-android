package co.electriccoin.zcash.ui.screen.migration.progress

import androidx.lifecycle.ViewModel
import cash.z.ecc.android.sdk.MigrationTransferStates
import cash.z.ecc.android.sdk.ext.convertZatoshiToZec
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.migration.BuildConfig
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.model.LceState
import co.electriccoin.zcash.ui.common.model.guardLoading
import co.electriccoin.zcash.ui.common.model.migration.LiveMigrationPreparation
import co.electriccoin.zcash.ui.common.model.migration.LiveMigrationSnapshot
import co.electriccoin.zcash.ui.common.model.migration.LiveMigrationTransfer
import co.electriccoin.zcash.ui.common.model.migration.MigrationTransferAction
import co.electriccoin.zcash.ui.common.model.migration.MigrationTransferBlocker
import co.electriccoin.zcash.ui.common.model.migration.formatMigrationDuration
import co.electriccoin.zcash.ui.common.model.migration.toSnapshot
import co.electriccoin.zcash.ui.common.model.mutableLce
import co.electriccoin.zcash.ui.common.model.stateIn
import co.electriccoin.zcash.ui.common.model.withLce
import co.electriccoin.zcash.ui.common.repository.ExchangeRateRepository
import co.electriccoin.zcash.ui.common.usecase.ErrorMapperUseCase
import co.electriccoin.zcash.ui.common.usecase.GetOrchardMigrationSdkUseCase
import co.electriccoin.zcash.ui.common.usecase.GetSelectedWalletAccountUseCase
import co.electriccoin.zcash.ui.common.wallet.ExchangeRateState
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.design.util.stringResByDynamicCurrencyNumber
import co.electriccoin.zcash.ui.screen.migration.sending.MigrationSendingArgs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import java.math.BigDecimal
import java.math.MathContext
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class MigrationProgressVM(
    private val getOrchardMigrationSdk: GetOrchardMigrationSdkUseCase,
    private val getSelectedWalletAccount: GetSelectedWalletAccountUseCase,
    private val navigationRouter: NavigationRouter,
    private val exchangeRateRepository: ExchangeRateRepository,
    private val errorStateMapper: ErrorMapperUseCase,
) : ViewModel() {
    private val sendLce = mutableLce<Unit>()

    val state: StateFlow<LceState<MigrationProgressState>> =
        combine(
            exchangeRateRepository.state,
            liveTransferStatesFlow(),
        ) { rate, liveStates ->
            // Everything on this screen derives LIVE from the engine's persisted states — no plan
            // cache to diverge, and no app-side "overdue"/countdown: each row renders purely from
            // the engine's per-transaction status (decision with Dominik 2026-07-31). The measured
            // block rate is still used for the rough total-duration estimate in the header only.
            val secondsPerBlock = getOrchardMigrationSdk()?.estimatedSecondsPerBlock() ?: 75L
            val est = getOrchardMigrationSdk()?.estimatedChainTip() ?: -1L
            liveStates
                ?.toSnapshot(
                    estimatedTip = if (est >= 0) est else liveStates.tipHeight,
                    secondsPerBlock = secondsPerBlock,
                    nowEpochSeconds = Clock.System.now().epochSeconds,
                )?.let { createState(it, rate) }
        }.withLce(sendLce, errorStateMapper::mapToState)
            .stateIn(this)

    // MigrationPlanRepository's per-transfer status/scheduledAt is a display cache, written once
    // at propose/commit time. Polling the SDK's own persisted state directly keeps the displayed
    // schedule true to the engine — the single source of truth for the plan — regardless of what
    // the cache last recorded.
    private fun liveTransferStatesFlow(): Flow<MigrationTransferStates?> =
        flow {
            while (true) {
                val sdk = getOrchardMigrationSdk()
                emit(sdk?.getMigrationTransferStates())
                delay(OVERDUE_RECHECK_INTERVAL)
            }
        }

    fun navigateBack() = navigationRouter.back()

    private fun createState(
        snapshot: LiveMigrationSnapshot,
        exchangeRateState: ExchangeRateState,
    ): MigrationProgressState {
        val now = Clock.System.now()
        val subtitle = migrationProgressSubtitle(snapshot, now)

        val totalZatoshi = snapshot.transfers.sumOf { it.amountZatoshi }
        return MigrationProgressState(
            title = stringRes("Migration Progress"),
            subtitle = stringRes(subtitle),
            totalAmount = stringRes(Zatoshi(totalZatoshi)),
            totalFiatAmount = fiatAmount(Zatoshi(totalZatoshi), exchangeRateState),
            preparations =
                snapshot.preparations.mapIndexed { i, p ->
                    MigrationProgressPreparationState(
                        number = i + 1,
                        // PRIMARY, all builds: a soft, non-deadline-implying time hint (never a
                        // countdown-to-deadline, never "Overdue"/attention-painted — see
                        // preparationSyncLabel doc). Inverse of this field's old priority.
                        statusLabel = preparationSyncLabel(p, now),
                        isSent = p.isSent,
                        // BuildConfig.DEBUG read inline (matching the codebase's other VMs) rather than
                        // injected — a `Boolean` constructor param breaks Koin's `viewModelOf` reflective
                        // resolution (NoDefinitionFoundException at screen open).
                        // DEBUG-only diagnostic suffix: the raw engine status word, demoted from
                        // its old PRIMARY spot.
                        syncLabel = if (BuildConfig.DEBUG) preparationStatusLabel(p) else null,
                    )
                },
            transfers =
                snapshot.transfers.map { t ->
                    MigrationProgressTransferState(
                        index = t.index + 1,
                        amount = stringRes(Zatoshi(t.amountZatoshi)),
                        fiatAmount = fiatAmount(Zatoshi(t.amountZatoshi), exchangeRateState),
                        // PRIMARY, all builds: a soft, non-deadline-implying time hint (never a
                        // countdown-to-deadline, never "Overdue"/attention-painted — see
                        // transferSyncLabel doc). Inverse of this field's old priority.
                        statusLabel = transferSyncLabel(t, now),
                        // Attention paint (orange) ONLY for genuine, cannot-heal-on-its-own states —
                        // never for a merely-late-but-healthy transfer (the old "overdue" false
                        // alarm). Expired and the synthetic unprovable-anchor are the only two.
                        isAttention =
                            t.blocker == MigrationTransferBlocker.UNPROVABLE_ANCHOR ||
                                t.blocker == MigrationTransferBlocker.EXPIRED,
                        isSent = t.isSent,
                        // DEBUG-only diagnostic suffix: the raw engine status word, demoted from
                        // its old PRIMARY spot.
                        syncLabel = if (BuildConfig.DEBUG) transferLabel(t) else null,
                    )
                },
            isComplete = snapshot.isComplete,
            onBack = ::onBack,
            onDone = if (snapshot.isComplete) ::onDone else null,
        )
    }

    private fun fiatAmount(zatoshi: Zatoshi, exchangeRateState: ExchangeRateState): StringResource? {
        val data = exchangeRateState as? ExchangeRateState.Data ?: return null
        val conversion = data.currencyConversion ?: return null
        return stringResByDynamicCurrencyNumber(
            amount =
                zatoshi
                    .convertZatoshiToZec()
                    .multiply(BigDecimal(conversion.priceOfZec), MathContext.DECIMAL128),
            ticker = data.expectedCurrency.symbol,
        )
    }

    private fun onBack() = sendLce.guardLoading { navigationRouter.back() }

    // Privacy buffer bookkeeping (keeping sync paused post-broadcast) is entirely SDK-owned — the
    // SDK notices this transfer was overdue and sets it internally. The actual broadcast, its
    // failure/retry sheet, and re-arming the next window all live on the Sending screen now
    // (see MigrationSendingVM), reused instead of duplicated here.
    private fun onSendNow() = navigationRouter.forward(MigrationSendingArgs)

    // "Reschedule" no longer mutates the plan — a missed-but-unexpired transfer needs NO plan
    // change by design (ZIP 374: the signature does not cover the anchor, so it proves late
    // against its committed boundary and broadcasts late; the engine is the single source of
    private fun onDone() = navigationRouter.backToRoot()

    companion object {
        private val OVERDUE_RECHECK_INTERVAL = 15.seconds
    }
}

/**
 * Header subtitle for the Migration Progress screen.
 *
 * Before any transfer has completed ([LiveMigrationSnapshot.completedCount] == 0), keeps the
 * existing static total-span framing unchanged: "over ~X" spanning the earliest to the latest
 * scheduled moment across preparations AND transfers.
 *
 * Once in progress (`completedCount > 0` — a concrete, checkable condition, decision with Dominik
 * 2026-08-01), the header instead counts down the REMAINING time to the last scheduled moment.
 * This MUST branch explicitly on `remaining > 0` vs `remaining <= 0`: [formatMigrationDuration]
 * floors its input at 60 seconds, so once the migration runs late (`now >= lastScheduled` — a
 * normal, expected state for this engine, not stuck/broken), a naive
 * `formatMigrationDuration(remaining)` call on a negative/zero span would silently floor to a
 * permanently-lying "~1 min" forever. That is exactly the "healthy-but-late state painted as
 * broken" bug class commit `33cff6883` fixed for the per-row labels — this header must not
 * reintroduce it via a different code path, so the `remaining <= 0` branch switches to non-time
 * copy that never calls [formatMigrationDuration] on that value.
 *
 * Top-level and internal for unit-testability without Android, Koin, or a live SDK/ViewModel.
 */
internal fun migrationProgressSubtitle(
    snapshot: LiveMigrationSnapshot,
    now: Instant,
): String {
    // Scheduled moments across preparations AND transfers, used for both the pre-start static
    // total-span estimate and the in-progress remaining-time countdown below.
    val allScheduled = (snapshot.transfers.map { it.scheduledAt } + snapshot.preparations.map { it.scheduledAt })
    val firstScheduled = allScheduled.minOrNull() ?: now
    val lastScheduled = allScheduled.maxOrNull() ?: now
    val remainingCount = snapshot.totalCount - snapshot.completedCount
    return when {
        snapshot.isComplete -> {
            "All ${snapshot.totalCount} transfers are complete."
        }

        // Not yet started (no transfer has completed): keep today's existing static total-span
        // framing unchanged — this task only changes the in-progress copy.
        snapshot.completedCount <= 0 -> {
            val span = (lastScheduled - firstScheduled).inWholeSeconds
            "Your balance splits into ${snapshot.totalCount} transfers over " +
                "${formatMigrationDuration(span)}. There are $remainingCount remaining transfers."
        }

        // In progress: the header now counts down remaining time instead of showing a static total.
        else -> {
            val remaining = (lastScheduled - now).inWholeSeconds
            if (remaining > 0) {
                "Your balance splits into ${snapshot.totalCount} transfers. About " +
                    "${formatMigrationDuration(remaining)} remaining. There are " +
                    "$remainingCount remaining transfers."
            } else {
                // Running late but healthy — never claim a duration here (see doc above).
                "Your balance splits into ${snapshot.totalCount} transfers. Finishing up… " +
                    "There are $remainingCount remaining transfers."
            }
        }
    }
}

/**
 * Raw engine status word for a crossing transfer row, rendered PURELY from the engine's
 * per-transaction status (`ready`/`action`/`blocker` from `transaction_statuses`) — NO
 * wall-clock, NO "overdue", NO countdown. The engine has no notion of "overdue": a proved
 * transfer waiting for the engine to reach its broadcast (proving is prioritised) is a normal
 * state, not a failure. Showing a projected countdown that we then don't strictly honour — and
 * painting late-but-healthy rows "Overdue" — made correct engine execution look broken (decision
 * with Dominik 2026-07-31), so both are gone. Every branch maps 1:1 onto
 * `state.rs::transaction_statuses`.
 *
 * DEBUG-only diagnostic suffix as of 2026-08-01 (decision with Dominik): the friendly per-row
 * time hint from [transferSyncLabel] is now the PRIMARY, all-builds label; this raw word is
 * demoted to a debug diagnostic appended after it.
 *
 * Top-level and internal for unit-testability without Android or Koin.
 */
internal fun transferLabel(t: LiveMigrationTransfer): StringResource =
    when {
        t.isSent && t.minedHeight != null -> stringRes("Confirmed")
        t.isSent -> stringRes("Sent")
        t.blocker == MigrationTransferBlocker.EXPIRED -> stringRes("Expired")
        t.blocker == MigrationTransferBlocker.UNPROVABLE_ANCHOR -> stringRes("Needs reschedule")
        t.blocker == MigrationTransferBlocker.SIGNATURE -> stringRes("Awaiting signature")
        t.blocker == MigrationTransferBlocker.DEPENDENCIES -> stringRes("Waiting for note split")
        t.blocker == MigrationTransferBlocker.ANCHOR_BOUNDARY -> stringRes("Waiting for anchor window")
        t.blocker == MigrationTransferBlocker.SCHEDULE -> stringRes("Scheduled")
        t.action == MigrationTransferAction.PROVE -> stringRes("Preparing")
        t.action == MigrationTransferAction.BROADCAST -> stringRes("Sending soon")
        else -> stringRes("Waiting")
    }

/**
 * Raw engine status word for a preparation row — same pure-status mapping as [transferLabel].
 * Preparations are internal note-split plumbing, so the copy is deliberately plain ("Preparing" /
 * "Sending soon" / "Waiting" / "Sent"). No wall-clock, no overdue.
 *
 * DEBUG-only diagnostic suffix as of 2026-08-01 (see [transferLabel] doc) — [preparationSyncLabel]
 * is now the PRIMARY, all-builds label.
 *
 * Top-level and internal for testability.
 */
internal fun preparationStatusLabel(p: LiveMigrationPreparation): StringResource =
    when {
        p.isSent -> stringRes("Sent")
        p.blocker == MigrationTransferBlocker.SIGNATURE -> stringRes("Awaiting signature")
        p.blocker == MigrationTransferBlocker.DEPENDENCIES -> stringRes("Waiting for previous split")
        p.action == MigrationTransferAction.PROVE -> stringRes("Preparing")
        p.action == MigrationTransferAction.BROADCAST -> stringRes("Sending soon")
        else -> stringRes("Waiting")
    }

/**
 * PRIMARY, all-builds per-row time hint for a preparation row (reintroduced 2026-08-01, decision
 * with Dominik) — a soft, non-deadline-implying estimate, e.g. "~5 min", NEVER styled as a
 * countdown-to-deadline and NEVER triggering "Overdue"/attention-paint (that regression is exactly
 * what commit `33cff6883` fixed; this only changes what's primary vs. debug-suffix).
 *
 * When there IS a meaningful future estimate ([now] is still before [LiveMigrationPreparation.scheduledAt]),
 * this formats the relative time with [formatMigrationDuration]. Otherwise — the row is already
 * proved, or its scheduled moment has passed (the common case for an active/due row) — there is no
 * honest duration left to show, so this falls back to the row's own status-derived phrase (the
 * same text [preparationStatusLabel] renders, e.g. "Preparing" / "Sending soon") instead of the
 * bare, uninformative "proved"/"pending" words that leaked debug jargon in an earlier draft.
 *
 * Top-level and internal for unit-testability.
 */
internal fun preparationSyncLabel(p: LiveMigrationPreparation, now: Instant): StringResource {
    val scheduledAt = p.scheduledAt
    return when {
        p.isProved || scheduledAt <= now -> {
            preparationStatusLabel(p)
        }

        else -> {
            val secondsLeft = (scheduledAt - now).inWholeSeconds
            stringRes(formatMigrationDuration(secondsLeft))
        }
    }
}

/**
 * PRIMARY, all-builds per-row time hint for a transfer row, mirroring [preparationSyncLabel]: a
 * relative estimate via [formatMigrationDuration] while a meaningful future estimate exists,
 * otherwise the row's own status-derived phrase ([transferLabel]'s output) instead of the bare
 * "proved"/"pending" words. Top-level and internal for unit-testability.
 */
internal fun transferSyncLabel(t: LiveMigrationTransfer, now: Instant): StringResource {
    val scheduledAt = t.scheduledAt
    return when {
        t.isProved || scheduledAt <= now -> {
            transferLabel(t)
        }

        else -> {
            val secondsLeft = (scheduledAt - now).inWholeSeconds
            stringRes(formatMigrationDuration(secondsLeft))
        }
    }
}
