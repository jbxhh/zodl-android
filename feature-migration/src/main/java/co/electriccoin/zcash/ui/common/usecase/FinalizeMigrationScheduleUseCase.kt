package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.MigrationSchedule
import co.electriccoin.zcash.migration.migrationLog
import co.electriccoin.zcash.ui.common.model.migration.MigrationMode
import co.electriccoin.zcash.ui.common.model.migration.estimatedSecondsBetweenHeights
import co.electriccoin.zcash.ui.common.model.toStorageKeyId
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.work.MigrationLiveDriver
import co.electriccoin.zcash.work.MigrationScheduler
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Persists a signed [MigrationSchedule] and schedules the background worker for its first
 * transfer. Shared by both the hot-wallet confirm path (MigrationReviewVM, which navigates to
 * MigrationScheduledArgs itself right after calling this) and the post-Keystone-scan path
 * (MigrationScheduledVM, reached already-navigated in its own loading state) so the scheduling
 * logic isn't duplicated. Deliberately does NOT navigate — a caller reached this from within the
 * destination it would navigate to would otherwise self-navigate redundantly.
 *
 * Background delivery is scheduled unconditionally, regardless of whether the user granted the
 * Battery-optimization-exemption permission — declining it only makes background execution less
 * reliable (may be deferred by Doze), it does not disable it. Whatever the OS/system still prevents
 * is caught by [MigrationWorker][co.electriccoin.zcash.work.MigrationWorker]'s own retry-on-not-ready
 * behavior and by on-launch reconciliation
 * ([CheckMigrationRecoveryUseCase][co.electriccoin.zcash.ui.common.usecase.CheckMigrationRecoveryUseCase]),
 * not by a separate notify-only delivery mode.
 */
class FinalizeMigrationScheduleUseCase(
    private val migrationScheduler: MigrationScheduler,
    private val getOrchardMigrationSdk: GetOrchardMigrationSdkUseCase,
    private val getSelectedWalletAccount: GetSelectedWalletAccountUseCase,
    private val synchronizerProvider: SynchronizerProvider,
    private val migrationLiveDriver: MigrationLiveDriver,
) {
    suspend operator fun invoke(sched: MigrationSchedule, mode: MigrationMode) {
        // Measured block rate — the 75s constant grossly overestimates on the bursty testnet,
        // scheduling the first worker run far past the real due heights.
        val secondsPerBlock = getOrchardMigrationSdk()?.estimatedSecondsPerBlock() ?: 75L
        val accountKeyId = getSelectedWalletAccount().sdkAccount.accountUuid.toStorageKeyId()
        logCommittedBoundaries()
        val tipHeight =
            sched.transfers.minOfOrNull { it.anchorHeight }
                ?: (sched.preparations.minOfOrNull { it.broadcastHeight } ?: 0L)
        migrationScheduler.schedule(accountKeyId, delayUntilFirstStep(sched, secondsPerBlock, tipHeight))
        // The engine's anchor-retention floor is session-scoped and was computed BEFORE this plan
        // existed — restart the sync session now, before the chain crosses the plan's first
        // boundary, or that boundary's checkpoint is never created and its transfer can never be
        // proved (see Synchronizer.restartSyncSession; observed live as a permanent
        // AnchorNotFound on the plan's first bucket).
        val restarted = synchronizerProvider.getSynchronizerOrNull()?.restartSyncSession() ?: false
        migrationLog("FinalizeMigrationSchedule: sync-session restart for anchor retention — restarted=$restarted")
        // Must come AFTER the sync-session restart above, never before: starting the live driver
        // first risks it entering syncRun (syncToTip) concurrently with the anchor-retention
        // restart, the exact mechanism that exists to prevent a permanent AnchorNotFound on the
        // plan's first bucket.
        migrationLiveDriver.startIfNotRunning(accountKeyId)
    }

    /**
     * Post-commit discoverability log (Issue 1): re-surface the engine's REAL per-transfer anchor
     * boundaries from the Kotlin side, right after the commit, so `grep MIGRATION_DIAG` shows the
     * true committed anchors without having to read the Rust `committedPlan:` dump.
     *
     * The propose-time app log (MigrationReviewVM.logProposedPlan) deliberately carries no
     * `boundary=` — anchor boundaries are drawn only at COMMIT (commit_preparation), so a proposal
     * has none. This reads them back from [OrchardMigrationSdk.getMigrationTransferStates], which
     * surfaces `anchorBoundaryHeight` per transfer (null for preparations — they anchor to the
     * natural tip, not a drawn grid boundary). Correlates by the stable transfer id (never array
     * index — ZIP 318 shuffles the two orderings apart). Best-effort: a failed/absent read logs a
     * single warning and is otherwise silent, never blocking the commit's scheduling/navigation.
     */
    private suspend fun logCommittedBoundaries() {
        val states = runCatching { getOrchardMigrationSdk()?.getMigrationTransferStates() }.getOrNull()
        if (states == null) {
            migrationLog("committedPlan(app): no live transfer states available post-commit")
            return
        }
        migrationLog(
            buildString {
                appendLine(
                    "committedPlan(app): ${states.transfers.size} transaction(s), scannedTip=${states.tipHeight}"
                )
                states.transfers
                    .sortedBy { it.scheduledHeight }
                    .forEach { t ->
                        appendLine(
                            "MIGRATION_DIAG committedPlan(app): id=${t.id} " +
                                "kind=${if (t.isTransfer) "Transfer" else "Preparation"} " +
                                "scheduled=${t.scheduledHeight} " +
                                "boundary=${t.anchorBoundaryHeight ?: "natural"} " +
                                "proved=${t.isProved} sent=${t.isSent}"
                        )
                    }
            }.trimEnd()
        )
    }

    // The first step (preparation or transfer) is never "ready now" (same anchor/proposal round
    // trip as any other step, per proposeMigrationTransfers()) — the very first WorkManager job
    // must wait for it just like every job scheduled after it, not fire immediately.
    //
    // broadcastHeight/nextExecutableAfterHeight/anchorHeight/expiryHeight are block heights, not
    // timestamps — see estimatedSecondsBetweenHeights for why they must never be used directly as
    // (or against) epoch seconds (this previously made every transfer look ~56 years overdue on a
    // live device).
    //
    // [tipHeight] is the commit-tip baseline (= the min anchorHeight across transfers, which the
    // SDK always draws from the same committed chain tip). Preparations and transfers share this
    // one origin, so the delay to a preparation's broadcastHeight and the delay to a transfer's
    // nextExecutableAfterHeight are comparable block-count deltas from the same point.
    internal fun delayUntilFirstStep(sched: MigrationSchedule, secondsPerBlock: Long, tipHeight: Long): Duration {
        val earliest =
            (
                sched.preparations.map { it.broadcastHeight } +
                    sched.transfers.map { it.nextExecutableAfterHeight }
            ).minOrNull()
                ?: return 0.seconds
        val remaining = estimatedSecondsBetweenHeights(tipHeight, earliest, secondsPerBlock)
        return if (remaining <= 0) 0.seconds else remaining.seconds
    }
}
