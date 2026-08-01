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
        // Rough total-duration estimate for the header only (first→last scheduled moment across
        // preparations AND transfers) — a "the whole thing takes about X" hint, never a per-row
        // deadline.
        val allScheduled = (snapshot.transfers.map { it.scheduledAt } + snapshot.preparations.map { it.scheduledAt })
        val span =
            ((allScheduled.maxOrNull() ?: now) - (allScheduled.minOrNull() ?: now)).inWholeSeconds
        val subtitle =
            if (snapshot.isComplete) {
                "All ${snapshot.totalCount} transfers are complete."
            } else {
                "Your balance splits into ${snapshot.totalCount} transfers over " +
                    "${formatMigrationDuration(span)}. There are " +
                    "${snapshot.totalCount - snapshot.completedCount} remaining transfers."
            }

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
                        statusLabel = preparationStatusLabel(p),
                        isSent = p.isSent,
                        // BuildConfig.DEBUG read inline (matching the codebase's other VMs) rather than
                        // injected — a `Boolean` constructor param breaks Koin's `viewModelOf` reflective
                        // resolution (NoDefinitionFoundException at screen open).
                        syncLabel = if (BuildConfig.DEBUG) preparationSyncLabel(p, now) else null,
                    )
                },
            transfers =
                snapshot.transfers.map { t ->
                    MigrationProgressTransferState(
                        index = t.index + 1,
                        amount = stringRes(Zatoshi(t.amountZatoshi)),
                        fiatAmount = fiatAmount(Zatoshi(t.amountZatoshi), exchangeRateState),
                        statusLabel = transferLabel(t),
                        // Attention paint (orange) ONLY for genuine, cannot-heal-on-its-own states —
                        // never for a merely-late-but-healthy transfer (the old "overdue" false
                        // alarm). Expired and the synthetic unprovable-anchor are the only two.
                        isAttention =
                            t.blocker == MigrationTransferBlocker.UNPROVABLE_ANCHOR ||
                                t.blocker == MigrationTransferBlocker.EXPIRED,
                        isSent = t.isSent,
                        syncLabel = if (BuildConfig.DEBUG) transferSyncLabel(t, now) else null,
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
 * Status label for a crossing transfer row, rendered PURELY from the engine's per-transaction
 * status (`ready`/`action`/`blocker` from `transaction_statuses`) — NO wall-clock, NO "overdue",
 * NO countdown. The engine has no notion of "overdue": a proved transfer waiting for the engine
 * to reach its broadcast (proving is prioritised) is a normal state, not a failure. Showing a
 * projected countdown that we then don't strictly honour — and painting late-but-healthy rows
 * "Overdue" — made correct engine execution look broken (decision with Dominik 2026-07-31), so
 * both are gone. Every branch maps 1:1 onto `state.rs::transaction_statuses`.
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
 * Status label for a preparation row — same pure-status mapping as [transferLabel]. Preparations
 * are internal note-split plumbing, so the copy is deliberately plain ("Preparing" / "Sending
 * soon" / "Waiting" / "Sent"). No wall-clock, no overdue. Top-level and internal for testability.
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
 * DEBUG-only prove-state label for a preparation row, formatted with the same relative formatter
 * as [preparationStatusLabel] so "~X min" / pending text look identical. Returns "proved" when
 * the preparation already has a proof, otherwise a relative scheduled time or "pending".
 * Top-level and internal for unit-testability.
 */
internal fun preparationSyncLabel(p: LiveMigrationPreparation, now: Instant): StringResource {
    if (p.isProved) return stringRes("proved")
    val scheduledAt = p.scheduledAt
    return when {
        scheduledAt <= now -> {
            stringRes("pending")
        }

        else -> {
            val secondsLeft = (scheduledAt - now).inWholeSeconds
            stringRes(formatMigrationDuration(secondsLeft))
        }
    }
}

/**
 * DEBUG-only prove-state label for a transfer row, mirroring [preparationSyncLabel]: returns
 * "proved" when the transfer already has a proof, otherwise a relative scheduled time or "pending".
 * Top-level and internal for unit-testability.
 */
internal fun transferSyncLabel(t: LiveMigrationTransfer, now: Instant): StringResource {
    if (t.isProved) return stringRes("proved")
    val scheduledAt = t.scheduledAt
    return when {
        scheduledAt <= now -> {
            stringRes("pending")
        }

        else -> {
            val secondsLeft = (scheduledAt - now).inWholeSeconds
            stringRes(formatMigrationDuration(secondsLeft))
        }
    }
}
