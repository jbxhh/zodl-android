package co.electriccoin.zcash.ui.screen.migration.progress

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.MigrationTransferStates
import cash.z.ecc.android.sdk.NetworkPrivacyOptions
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.model.LceState
import co.electriccoin.zcash.ui.common.model.migration.MigrationTransfer
import co.electriccoin.zcash.ui.common.model.migration.MigrationTransferStatus
import co.electriccoin.zcash.ui.common.model.mutableLce
import co.electriccoin.zcash.ui.common.model.stateIn
import co.electriccoin.zcash.ui.common.model.withLce
import co.electriccoin.zcash.ui.common.model.toStorageKeyId
import co.electriccoin.zcash.ui.common.repository.ExchangeRateRepository
import co.electriccoin.zcash.ui.common.repository.MigrationPlanRepository
import co.electriccoin.zcash.ui.common.usecase.ErrorMapperUseCase
import co.electriccoin.zcash.ui.common.usecase.GetOrchardMigrationSdkUseCase
import co.electriccoin.zcash.ui.common.usecase.GetSelectedWalletAccountUseCase
import co.electriccoin.zcash.ui.common.wallet.ExchangeRateState
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.design.util.stringResByDynamicCurrencyNumber
import co.electriccoin.zcash.ui.common.model.guardLoading
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.BuildConfig
import co.electriccoin.zcash.ui.common.model.migration.MigrationPlan
import co.electriccoin.zcash.ui.common.model.migration.MigrationPreparation
import co.electriccoin.zcash.ui.common.model.migration.formatMigrationDuration
import co.electriccoin.zcash.ui.common.model.migration.withLiveState
import co.electriccoin.zcash.ui.common.provider.IsMigrationTorEnabledStorageProvider
import co.electriccoin.zcash.ui.common.provider.LastNetworkActivityStorageProvider
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.screen.migration.sending.MigrationSendingArgs
import co.electriccoin.zcash.work.LANE_A_SYNC_TIMEOUT
import co.electriccoin.zcash.work.MigrationScheduler
import cash.z.ecc.android.sdk.ext.convertZatoshiToZec
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.MathContext
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class MigrationProgressVM(
    private val getOrchardMigrationSdk: GetOrchardMigrationSdkUseCase,
    private val getSelectedWalletAccount: GetSelectedWalletAccountUseCase,
    private val migrationPlanRepository: MigrationPlanRepository,
    private val navigationRouter: NavigationRouter,
    private val exchangeRateRepository: ExchangeRateRepository,
    private val errorStateMapper: ErrorMapperUseCase,
    private val synchronizerProvider: SynchronizerProvider,
    private val lastNetworkActivity: LastNetworkActivityStorageProvider,
    private val isMigrationTorEnabledStorageProvider: IsMigrationTorEnabledStorageProvider,
    private val context: Context,
) : ViewModel() {

    private val sendLce = mutableLce<Unit>()

    /**
     * Live per-transfer state as the engine itself holds it, cached at VM level.
     *
     * MigrationPlanRepository's per-transfer status/scheduledAt is a display cache, written once at
     * propose/commit time. Polling the SDK's own persisted state directly keeps the displayed
     * schedule true to the engine — the single source of truth for the plan — regardless of what the
     * cache last recorded.
     *
     * The cache lives on the VM, NOT inside the state flow: [state] is shared with
     * `WhileSubscribed`, so leaving the screen for longer than its stop timeout restarts the upstream
     * — and a poll that started from `null` again would flash "scheduled" over transfers already
     * known to be sent and momentarily drop the recovery buttons. The VM outlives those restarts, so
     * the last known value is re-emitted immediately on resubscription instead.
     */
    private val liveTransferStates = MutableStateFlow<MigrationTransferStates?>(null)

    /**
     * Measured block rate used for the height -> wall-clock re-projection, cached at VM level for the
     * same reason as [liveTransferStates]. Seeded with [DEFAULT_SECONDS_PER_BLOCK] so the first frame
     * can be painted from the (fast) plan read alone; the measured value replaces it moments later —
     * the default alone turned minute-scale testnet schedules into "~1 hour" rows (caught live 28.7.).
     */
    private val secondsPerBlock = MutableStateFlow(DEFAULT_SECONDS_PER_BLOCK)

    /**
     * Refresh loop for the two slow SDK inputs above, tied to [state]'s subscription: it is merged
     * into the state flow purely for that lifetime, never for its emissions ([Flow] of [Nothing]).
     * Both inputs are written into the VM-level caches instead of being emitted, so the state flow
     * can paint its first frame from the cached plan alone — a single encrypted-prefs read — while
     * the SDK reads, which have been observed to take seconds behind the migration DB lock, refine
     * it afterwards (MOB-1623).
     */
    private val slowInputRefresh: Flow<Nothing> =
        channelFlow {
            launch {
                while (true) {
                    runCatching { getOrchardMigrationSdk().getMigrationTransferStates() }
                        .onSuccess { states -> liveTransferStates.update { states } }
                        .onFailure { error ->
                            Twig.warn(error) {
                                "MIGRATION_DIAG MigrationProgressVM: live transfer state poll failed (transient)"
                            }
                        }
                    delay(OVERDUE_RECHECK_INTERVAL)
                }
            }
            launch {
                runCatching { getOrchardMigrationSdk().estimatedSecondsPerBlock() }
                    .onSuccess { measured -> secondsPerBlock.update { measured } }
                    .onFailure { error ->
                        Twig.warn(error) {
                            "MIGRATION_DIAG MigrationProgressVM: block rate read failed — keeping the default"
                        }
                    }
            }
            awaitCancellation()
        }

    val state: StateFlow<LceState<MigrationProgressState>> =
        merge(
            combine(
                migrationPlanRepository.observe(),
                exchangeRateRepository.state,
                liveTransferStates,
                secondsPerBlock,
            ) { plan, rate, liveStates, blockSeconds ->
                // Issue 3a: gate the recovery buttons on a GRACED, transfers-only overdue check
                // computed app-side from the live states' SCANNED tip — NOT the raw SDK
                // hasOverdueTransfers() (Rust any_overdue), which is un-graced and includes
                // preparations, so it flashed the buttons the instant a proved tx passed its
                // scheduled height during otherwise-normal execution.
                val reallyOverdue = hasGenuinelyOverdueTransfer(liveStates)
                plan?.let { createState(it.withLiveState(liveStates, blockSeconds), rate, reallyOverdue) }
            },
            slowInputRefresh,
        ).withLce(sendLce, errorStateMapper::mapToState)
            .stateIn(this)

    init {
        // Issue 3b: drive migration forward WHILE the progress screen is foregrounded.
        //
        // Root cause of the stall: on this screen the app is foreground and the main synchronizer
        // follows the chain tip continuously, so Lane B's background preflight sees
        // synchronizerSyncing=true and DEFER_OVERLAPs forever — nothing ever broadcasts while the
        // user watches (every successful E2E previously required backgrounding the app to open a
        // Lane B quiet window). This foreground pass opens that window itself, PRIVACY-PRESERVED:
        // it pauses the main synchronizer, waits out the privacy quiet gap, then broadcasts through
        // the EXACT same pipeline Lane B/Sending use (executeNextPendingTransfer) — never a raw
        // send, and never while a sync source is live. The side effect lives here in init{} (not in
        // the state combine) so it runs once per VM instance rather than re-subscribing.
        foregroundBroadcastLoop()
    }

    /**
     * Issue 3b — the foreground broadcast pass. Periodically, while this VM is alive (i.e. the
     * progress screen is on top), checks whether a transfer is genuinely due AND proved; if so,
     * acquires a privacy-safe broadcast window and broadcasts it via the same SDK pipeline the
     * background Lane B uses. Runs on the VM scope, so it is cancelled automatically when the
     * screen leaves.
     */
    private fun foregroundBroadcastLoop() =
        viewModelScope.launch {
            while (true) {
                runCatching { attemptForegroundBroadcast() }
                    .onFailure { Twig.warn(it) { "MIGRATION_DIAG ProgressBroadcast: pass failed (transient) — retrying next tick" } }
                delay(FOREGROUND_BROADCAST_INTERVAL)
            }
        }

    /**
     * One foreground broadcast attempt, privacy-preserved.
     *
     * 1. Only proceeds when the engine holds a PROVED, unsent transaction whose scheduledHeight has
     *    been reached at the SCANNED tip (a broadcast that can actually happen). An unproven due
     *    transfer is left to Lane A's sync to prove — never force-broadcast here.
     * 2. Respects the SDK's own post-broadcast privacy gate (isSyncBlocked): if active, defers.
     * 3. PAUSES the main synchronizer so no sync source is live, waits out the privacy quiet gap
     *    from the last network activity, then broadcasts through executeNextPendingTransfer — the
     *    identical call Lane B and the Sending screen use. After a successful overdue broadcast the
     *    SDK itself sets the post-broadcast resume-at buffer, which keeps the main sync paused via
     *    isSyncBlocked; we still resume() so the SDK-owned gate — not this manual pause — governs
     *    sync from here on.
     * 4. Re-reads the live transfer states right after the broadcast returns, writing them into
     *    [liveTransferStates] exactly as the poll does (`update { states }`, nullable included — one
     *    consistency rule for the cache). Reads no longer take the migration DB mutex, so a poll
     *    snapshot taken moments before this broadcast's commit would otherwise leave the row stale
     *    for a whole [OVERDUE_RECHECK_INTERVAL] tick — the one staleness window a user actually
     *    watches. Deliberately on the success path inside the `try`, NOT in the `finally`: the
     *    `finally` also runs on cancellation, where a suspending SDK read would throw immediately
     *    and mask nothing useful. A thrown broadcast leaves the refresh to the poll — rare, logged
     *    and retried anyway.
     */
    private suspend fun attemptForegroundBroadcast() {
        val sdk = getOrchardMigrationSdk()
        val states = sdk.getMigrationTransferStates() ?: return
        if (!hasBroadcastableTransfer(states)) {
            return
        }
        if (sdk.isSyncBlocked().first()) {
            Twig.debug { "MIGRATION_DIAG ProgressBroadcast: privacy gate active (isSyncBlocked) — deferring foreground broadcast." }
            return
        }
        val synchronizer = synchronizerProvider.getSynchronizerOrNull()
        // Pause the continuously-syncing foreground synchronizer so the broadcast never overlaps a
        // live sync (privacy). Cast mirrors ResetZashiUseCase — the runtime instance is always a
        // CloseableSynchronizer; a null/incompatible synchronizer simply skips this pass.
        val closeable = synchronizer as? cash.z.ecc.android.sdk.CloseableSynchronizer ?: run {
            Twig.debug { "MIGRATION_DIAG ProgressBroadcast: no pausable synchronizer — skipping foreground broadcast." }
            return
        }
        closeable.pause()
        // Stamp "network activity" at the moment of pause so the quiet gap below is measured from
        // when THIS sync stopped — not from the last SYNCED transition. In the exact state this
        // path targets (the foreground synchronizer catching up continuously and never reaching
        // SYNCED), lastNetworkActivity is stamped only on SYNCED, so it would be stale and the gap
        // would collapse to ~0 → an immediate broadcast right after an ASYNC pause() whose
        // stopPolling() may still be in flight, i.e. sync traffic still adjacent to the broadcast.
        // Stamping here forces the full privacy buffer to elapse after the sync actually stopped,
        // covering the async stop and giving real decorrelation.
        lastNetworkActivity.stampNow()
        Twig.debug { "MIGRATION_DIAG ProgressBroadcast: paused foreground sync to open a broadcast window." }
        try {
            // Wait out the privacy quiet gap since the pause stamp above (same buffer Lane B's
            // preflight enforces) so an observer can't correlate the just-stopped sync with the
            // broadcast. The pause above already removed the live-sync source; this covers the gap.
            val gap = quietGapRemaining(sdk.privacySyncBufferDuration())
            if (gap.isPositive()) {
                Twig.debug { "MIGRATION_DIAG ProgressBroadcast: waiting privacy quiet gap $gap before broadcast." }
                delay(gap)
            }
            val useTor = isMigrationTorEnabledStorageProvider.get()
            val outcome = sdk.executeNextPendingTransfer(NetworkPrivacyOptions(useTor = useTor), useEstimatedTip = false)
            Twig.debug { "MIGRATION_DIAG ProgressBroadcast: foreground broadcast outcome=$outcome" }
            lastNetworkActivity.stampNow()
            runCatching { sdk.getMigrationTransferStates() }
                .onSuccess { states -> liveTransferStates.update { states } }
                .onFailure { error ->
                    Twig.warn(error) {
                        "MIGRATION_DIAG ProgressBroadcast: post-broadcast state refresh failed — next poll covers it"
                    }
                }
        } finally {
            // Hand sync governance back to the SDK-owned isSyncBlocked gate (which, after a
            // successful overdue broadcast, keeps sync paused for the post-broadcast buffer).
            closeable.resume()
            Twig.debug { "MIGRATION_DIAG ProgressBroadcast: resumed foreground sync (SDK gate now governs)." }
        }
    }

    private suspend fun quietGapRemaining(privacyBuffer: kotlin.time.Duration): kotlin.time.Duration {
        val last = lastNetworkActivity.get() ?: return kotlin.time.Duration.ZERO
        val elapsed = (Clock.System.now().epochSeconds - last.epochSecond).seconds
        val remaining = privacyBuffer - elapsed
        return if (remaining.isPositive()) remaining else kotlin.time.Duration.ZERO
    }

    // withLiveState() (correlating by stable transfer id, never array index — see its doc) now
    // lives in MigrationPlan.kt, shared with MigrationAttention.kt's affectedTransferIndices().

    fun navigateBack() = navigationRouter.back()

    private fun createState(
        plan: MigrationPlan,
        exchangeRateState: ExchangeRateState,
        reallyOverdue: Boolean,
    ): MigrationProgressState {
        val now = Clock.System.now()
        val next = plan.nextPending
        val hasOverdue = next != null && reallyOverdue
        val isResume = hasOverdue && plan.completedCount > 0
        val overdueH = if (next != null) overdueHours(next, now) else 0L

        val span = (plan.transfers.maxOfOrNull { it.scheduledAtEpochSeconds } ?: plan.createdAtEpochSeconds) -
            plan.createdAtEpochSeconds
        val subtitle = when {
            plan.isComplete -> "All ${plan.totalCount} transfers are complete."
            isResume -> "Transfer ${plan.completedCount + 1} of ${plan.totalCount} was scheduled ${overdueH}h ago but wasn't sent. Send now or reschedule."
            else -> "Your balance splits into ${plan.totalCount} transfers over " +
                "${formatMigrationDuration(span)}. There are " +
                "${plan.totalCount - plan.completedCount} remaining transfers."
        }

        val totalZatoshi = plan.transfers.sumOf { it.amountZatoshi }
        // Sort preparations by their scheduled time to get a stable broadcast/display order.
        val sortedPreps = plan.preparations.sortedBy { it.scheduledAtEpochSeconds }
        return MigrationProgressState(
            title = stringRes(if (isResume) "Resume Migration" else "Migration Progress"),
            subtitle = stringRes(subtitle),
            totalAmount = stringRes(Zatoshi(totalZatoshi)),
            totalFiatAmount = fiatAmount(Zatoshi(totalZatoshi), exchangeRateState),
            preparations = sortedPreps.mapIndexed { i, p ->
                MigrationProgressPreparationState(
                    number = i + 1,
                    statusLabel = preparationStatusLabel(p, now),
                    isSent = p.status == MigrationTransferStatus.SENT,
                    // BuildConfig.DEBUG read inline (matching the codebase's other VMs) rather than
                    // injected — a `Boolean` constructor param breaks Koin's `viewModelOf` reflective
                    // resolution (NoDefinitionFoundException at screen open). The unit test covers both
                    // branches via the standalone `mapPreparationsToState(..., debugSyncEnabled)` helper.
                    syncLabel = if (BuildConfig.DEBUG) preparationSyncLabel(p, now) else null,
                )
            },
            transfers = plan.transfers.map { t ->
                MigrationProgressTransferState(
                    index = t.index + 1,
                    amount = stringRes(Zatoshi(t.amountZatoshi)),
                    fiatAmount = fiatAmount(Zatoshi(t.amountZatoshi), exchangeRateState),
                    statusLabel = transferLabel(t, now),
                    isOverdue = t.status == MigrationTransferStatus.PENDING && t.isProved && t.scheduledAt <= now,
                    isSent = t.status == MigrationTransferStatus.SENT,
                    syncLabel = if (BuildConfig.DEBUG) transferSyncLabel(t, now) else null,
                )
            },
            isComplete = plan.isComplete,
            hasOverdue = hasOverdue,
            onBack = ::onBack,
            // Figma B4 (Updated Migration Plan — normal in-progress) has no Send Now button at
            // all; it only appears on B8 (Resume Migration) when a transfer is actually overdue.
            onSendNow = if (hasOverdue) { { onSendNow(plan) } } else null,
            onReschedule = if (hasOverdue) ::onReschedule else null,
            onDone = if (plan.isComplete) ::onDone else null,
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
    private fun onSendNow(plan: MigrationPlan) = navigationRouter.forward(MigrationSendingArgs)

    // "Reschedule" no longer mutates the plan — a missed-but-unexpired transfer needs NO plan
    // change by design (ZIP 374: the signature does not cover the anchor, so it proves late
    // against its committed boundary and broadcasts late; the engine is the single source of
    // truth). New semantics: SYNC NOW — run the same sync + finalizeReadyTransfers pass Lane A
    // does, in the foreground, so the missing proof falls out immediately — then let the transfer
    // go out in the next live window (background worker, or next app open).
    private fun onReschedule() = sendLce.execute {
        val sdk = getOrchardMigrationSdk()
        val accountKeyId = getSelectedWalletAccount().sdkAccount.accountUuid.toStorageKeyId()
        if (sdk.isSyncBlocked().first()) {
            // Post-broadcast privacy gate — same guard Lane A honours; the re-arm below still
            // gives the transfer its next window once the gate lifts.
            Twig.debug { "MIGRATION_DIAG MigrationProgressVM.onReschedule: privacy gate active — skipping the foreground sync." }
        } else {
            val burst = synchronizerProvider.getSynchronizerOrNull()?.syncToTip(timeout = LANE_A_SYNC_TIMEOUT)
            Twig.debug { "MIGRATION_DIAG MigrationProgressVM.onReschedule: syncToTip result=$burst" }
            val proved = sdk.finalizeReadyTransfers()
            Twig.debug { "MIGRATION_DIAG MigrationProgressVM.onReschedule: proved=$proved" }
            lastNetworkActivity.stampNow()
        }
        // Re-arm Lane B for the next live window: after the privacy quiet gap from the sync just
        // performed (Lane B's own preflight re-checks the gap from the fresh stamp regardless).
        val reArm = sdk.privacySyncBufferDuration()
        MigrationScheduler(context).schedule(accountKeyId, reArm)
        Twig.debug { "MIGRATION_DIAG MigrationProgressVM.onReschedule: Lane B re-armed in $reArm" }
        navigationRouter.back()
    }

    private fun onDone() = navigationRouter.backToRoot()

    companion object {
        private val OVERDUE_RECHECK_INTERVAL = 15.seconds

        /**
         * Block rate assumed until [MigrationProgressVM.secondsPerBlock] has been measured — the
         * network's nominal target, and what the SDK itself falls back to when it has no samples.
         */
        private const val DEFAULT_SECONDS_PER_BLOCK = 75L

        // How often the foreground broadcast pass (Issue 3b) re-checks for a due, proved transfer.
        // Short enough to advance the migration responsively while watched, long enough not to
        // churn; the SDK's own gates make redundant passes cheap no-ops.
        internal val FOREGROUND_BROADCAST_INTERVAL = 20.seconds

        /**
         * Issue 3a — blocks a genuinely-overdue transfer must be past its scheduled height (at the
         * SCANNED tip) before the Send-now/Reschedule recovery buttons appear.
         *
         * Derivation: the buttons must NOT flash during normal execution, where a proved transfer
         * legitimately sits a few blocks past its scheduled height waiting for the next quiet
         * broadcast window (the privacy buffer). The privacy buffer is 3 min testnet / 10 min
         * mainnet; at the observed ~75 s/block that is ~2–8 blocks. A safe constant of 12 blocks
         * clears the mainnet worst case (~8) plus a small margin for proof/scan jitter, so the
         * buttons only appear when a transfer is overdue BEYOND the normal proof→broadcast latency
         * — i.e. genuinely missed, not merely mid-execution.
         */
        internal const val OVERDUE_GRACE_BLOCKS = 12L
    }
}

/**
 * Issue 3a — the graced, transfers-only overdue predicate that gates the Send-now/Reschedule
 * recovery buttons. A transfer counts as GENUINELY overdue only when it is a real transfer (NOT a
 * preparation — those are internal plumbing the user never reasons about), not yet sent, and its
 * scheduled height plus [MigrationProgressVM.OVERDUE_GRACE_BLOCKS] has been reached at the SCANNED
 * tip ([MigrationTransferStates.tipHeight] — never an estimated tip, which would trip the buttons
 * before a sync could confirm the miss).
 *
 * Top-level and internal so it is unit-testable without Koin/Android — mirrors MigrationSyncWorker's
 * pure decision functions.
 */
internal fun hasGenuinelyOverdueTransfer(states: MigrationTransferStates?): Boolean {
    if (states == null) return false
    return states.transfers.any { t ->
        t.isTransfer && !t.isSent && t.isProved &&
            t.scheduledHeight + MigrationProgressVM.OVERDUE_GRACE_BLOCKS <= states.tipHeight
    }
}

/**
 * Issue 3b — whether the engine currently holds a transaction the foreground pass may broadcast:
 * a PROVED, unsent transaction whose scheduled height has been reached at the SCANNED tip. Proved
 * is load-bearing — an unproven due transfer can only be made broadcastable by Lane A's sync, never
 * force-broadcast here. Kind-agnostic (transfers AND preparations), matching the engine's own
 * next-due serving, so the pass never sleeps past a due preparation layer. Top-level and internal
 * for the same testability reason as [hasGenuinelyOverdueTransfer].
 */
internal fun hasBroadcastableTransfer(states: MigrationTransferStates?): Boolean {
    if (states == null) return false
    return states.transfers.any { t ->
        t.isProved && !t.isSent && t.scheduledHeight <= states.tipHeight
    }
}

/** Elapsed hours since a transfer's scheduled time (floored to 0). */
internal fun overdueHours(t: MigrationTransfer, now: Instant) =
    (now - t.scheduledAt).inWholeHours.coerceAtLeast(0)

/**
 * Status label for a crossing transfer row.
 *
 * PENDING splits into three sub-cases:
 * - proved + past-due  → "Overdue · Xh ago"  (the recovery UI is for this state only)
 * - unproved + past-due → "Awaiting proof"    (calm; Lane A's sync will prove it soon)
 * - future scheduled   → relative "~X min" / "~X h Y min" label
 *
 * Top-level and internal for unit-testability without Android or Koin.
 */
internal fun transferLabel(t: MigrationTransfer, now: Instant): StringResource =
    when (t.status) {
        MigrationTransferStatus.SENT -> {
            val agoMinutes = (now - t.scheduledAt).inWholeMinutes
            when {
                agoMinutes < 1 -> stringRes("Sent recently")
                agoMinutes < 60 -> stringRes("Sent $agoMinutes min ago")
                else -> stringRes("Sent ${agoMinutes / 60}h ago")
            }
        }
        MigrationTransferStatus.PENDING -> {
            val scheduled = t.scheduledAt
            when {
                scheduled <= now && t.isProved -> stringRes("Overdue · ${overdueHours(t, now)}h ago")
                scheduled <= now -> stringRes("Awaiting proof")
                else -> {
                    // Use the shared formatter (Issue 2) so this screen and the Review screen
                    // format identically ("~1 h 15 min") — the old inline branch bucketed to
                    // coarse integer hours ("~1 hours") and dropped the minutes, so 75 min and
                    // 119 min both rendered the same. fineGrained defaults to testnet=true.
                    val secondsLeft = (scheduled - now).inWholeSeconds
                    stringRes(formatMigrationDuration(secondsLeft))
                }
            }
        }
    }

/**
 * Status label for a preparation row. Mirrors [transferLabel]'s Sent and future-relative arms
 * ("Sent recently" / "Sent X min ago" / "Sent Xh ago" / "~X min"), but deliberately does NOT
 * surface an "Overdue" label: preparations are internal note-split plumbing and the
 * overdue/recovery affordance is transfers-only, so a past-due unsent preparation reads simply
 * "Pending". Top-level and internal for unit-testability without Android or Koin.
 */
internal fun preparationStatusLabel(p: MigrationPreparation, now: Instant): StringResource {
    val scheduledAt = Instant.fromEpochSeconds(p.scheduledAtEpochSeconds)
    return when (p.status) {
        MigrationTransferStatus.SENT -> {
            val agoMinutes = (now - scheduledAt).inWholeMinutes
            when {
                agoMinutes < 1 -> stringRes("Sent recently")
                agoMinutes < 60 -> stringRes("Sent $agoMinutes min ago")
                else -> stringRes("Sent ${agoMinutes / 60}h ago")
            }
        }
        MigrationTransferStatus.PENDING -> {
            when {
                scheduledAt <= now -> stringRes("Pending")
                else -> {
                    val secondsLeft = (scheduledAt - now).inWholeSeconds
                    stringRes(formatMigrationDuration(secondsLeft))
                }
            }
        }
    }
}

/**
 * DEBUG-only prove-state label for a preparation row, formatted with the same relative formatter
 * as [preparationStatusLabel] so "~X min" / pending text look identical. Returns "proved" when
 * the preparation already has a proof, otherwise a relative scheduled time or "pending".
 * Top-level and internal for unit-testability.
 */
internal fun preparationSyncLabel(p: MigrationPreparation, now: Instant): StringResource {
    if (p.isProved) return stringRes("proved")
    val scheduledAt = Instant.fromEpochSeconds(p.scheduledAtEpochSeconds)
    return when {
        scheduledAt <= now -> stringRes("pending")
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
internal fun transferSyncLabel(t: MigrationTransfer, now: Instant): StringResource {
    if (t.isProved) return stringRes("proved")
    val scheduledAt = t.scheduledAt
    return when {
        scheduledAt <= now -> stringRes("pending")
        else -> {
            val secondsLeft = (scheduledAt - now).inWholeSeconds
            stringRes(formatMigrationDuration(secondsLeft))
        }
    }
}
