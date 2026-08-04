package co.electriccoin.zcash.work

import android.content.Context
import androidx.annotation.Keep
import androidx.work.CoroutineWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import cash.z.ecc.android.sdk.NetworkPrivacyOptions
import cash.z.ecc.android.sdk.OrchardMigrationSdk
import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.TransferAttemptOutcome
import cash.z.ecc.android.sdk.TransferResult
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.common.model.migration.MigrationPlan
import co.electriccoin.zcash.ui.common.model.migration.withLiveStatusOnly
import co.electriccoin.zcash.ui.common.model.toStorageKeyId
import co.electriccoin.zcash.ui.common.provider.IsMigrationTorEnabledStorageProvider
import co.electriccoin.zcash.ui.common.provider.LastNetworkActivityStorageProvider
import co.electriccoin.zcash.ui.common.provider.MigrationNotifier
import co.electriccoin.zcash.ui.common.provider.MigrationShiftCounterStorageProvider
import co.electriccoin.zcash.ui.common.provider.PendingMigrationTorFailureStorageProvider
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.repository.MigrationPlanRepository
import co.electriccoin.zcash.ui.common.usecase.GetOrchardMigrationSdkUseCase
import co.electriccoin.zcash.ui.common.usecase.GetSelectedWalletAccountUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.time.Instant
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@Keep
class MigrationWorker(
    context: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(context, workerParameters), KoinComponent {

    private val getOrchardMigrationSdk: GetOrchardMigrationSdkUseCase by inject()
    private val getSelectedWalletAccount: GetSelectedWalletAccountUseCase by inject()
    private val migrationPlanRepository: MigrationPlanRepository by inject()
    private val migrationNotifier: MigrationNotifier by inject()
    private val isMigrationTorEnabledStorageProvider: IsMigrationTorEnabledStorageProvider by inject()
    private val pendingMigrationTorFailureStorageProvider: PendingMigrationTorFailureStorageProvider by inject()
    private val synchronizerProvider: SynchronizerProvider by inject()
    private val lastNetworkActivity: LastNetworkActivityStorageProvider by inject()
    private val shiftCounter: MigrationShiftCounterStorageProvider by inject()

    override suspend fun doWork(): Result {
        val accountKeyId = inputData.getString(MigrationScheduler.KEY_ACCOUNT_KEY_ID)
            ?: getSelectedWalletAccount().sdkAccount.accountUuid.toStorageKeyId().also {
                Twig.warn { "MIGRATION_DIAG MigrationWorker: no accountKeyId in inputData — falling back to selected account $it (pre-upgrade job)" }
            }

        val sdk = getOrchardMigrationSdk(accountKeyId) ?: run {
            // Same reasoning as MigrationSyncWorker: a not-yet-initialized wallet right after an
            // app update/reboot must not silently consume (and thereby kill) the self-rechaining
            // lane — retry until the SDK is reachable.
            Twig.debug { "MIGRATION_DIAG LaneB: SDK not ready — retrying via WorkManager backoff." }
            return Result.retry()
        }

        // WorkManager batches jobs with similar due times, so both lanes routinely WAKE IN THE
        // SAME SECOND — a naive "Lane A is RUNNING → defer a full privacy buffer" then re-collides
        // every cycle forever (observed live: five proved, due transfers deferred for 17+ minutes
        // across perfectly synchronized wakes). Lane A's runs take seconds (and a step-aside run
        // does no network work at all), so wait it out briefly and re-check; the quiet-gap check
        // below still guards the case where Lane A actually synced just now.
        var laneARunning = isLaneARunning()
        if (laneARunning) {
            repeat(LANE_A_WAIT_CHECKS) {
                delay(LANE_A_WAIT_STEP)
                laneARunning = isLaneARunning()
                if (!laneARunning) return@repeat
            }
        }

        /*
         * status is a Flow<Status> — timeout if cold; null synchronizer is non-syncing.
         * timeout → assume SYNCING → defer (privacy-safe default; production status is a StateFlow
         * and answers immediately). INITIALIZING is skipped rather than read: the synchronizer now
         * returns from its factory before its own preparation finishes, so its first emission is a
         * placeholder that says nothing about whether a sync source is live — waiting for the first
         * real status (and falling back to SYNCING on timeout) keeps the privacy-safe default.
         */
        val syncing = synchronizerProvider.synchronizer.value?.let { synchronizer ->
            withTimeoutOrNull(STATUS_READ_TIMEOUT) {
                synchronizer.status.first { it != Synchronizer.Status.INITIALIZING }
            } ?: Synchronizer.Status.SYNCING
        } == Synchronizer.Status.SYNCING

        val lastActivity = lastNetworkActivity.get()
        val preflight = decideLaneBPreflight(
            laneARunning = laneARunning,
            synchronizerSyncing = syncing,
            nowEpochSeconds = nowEpochSeconds(),
            lastNetworkActivityEpochSeconds = lastActivity?.epochSecond,
            privacyBufferSeconds = sdk.privacySyncBufferDuration().inWholeSeconds,
        )
        Twig.debug {
            "MIGRATION_DIAG LaneB: run start account=$accountKeyId preflight=$preflight " +
                "(laneARunning=$laneARunning, syncing=$syncing, lastNetworkActivity=$lastActivity)"
        }
        when (preflight) {
            LaneBAction.DEFER_OVERLAP -> {
                // Local delay (spec §5): engine untouched.
                Twig.debug {
                    "MIGRATION_DIAG LaneB: deferring broadcast ${sdk.privacySyncBufferDuration()} — " +
                        "a sync source is live or the quiet gap is unmet."
                }
                MigrationScheduler(applicationContext).schedule(accountKeyId, sdk.privacySyncBufferDuration())
                return Result.success()
            }
            LaneBAction.BROADCAST -> Unit // proceed below
        }

        val plan = migrationPlanRepository.load(accountKeyId)
        val next = plan?.nextPending
        val useTor = isMigrationTorEnabledStorageProvider.get(accountKeyId)

        // Retries within this single worker invocation, same attempt count (3) as
        // MigrationSendingVM.sendOnce()'s foreground loop — but a different trigger: sendOnce()
        // retries while the result is null (still polling for readiness) and stops on any
        // non-null result, while this retries only on a retryable NetworkError and stops
        // immediately on null. So a persistent network error settles into an error state after 3
        // attempts instead of retrying via WorkManager's Result.retry() indefinitely (previously
        // observed: dumpsys jobscheduler showed the same worker restarting and running for the
        // full ~10-minute execution ceiling, repeatedly, for hours).
        // Hard timeout around the whole broadcast attempt: a cold-bootstrapping Tor client can
        // hang the submit indefinitely (observed live: tx stuck in-flight 10+ minutes until the
        // WorkManager execution ceiling killed the worker and nothing re-armed). On timeout the
        // native call may still complete detached — a re-submit of the same tx is safely
        // classified as a duplicate by the SDK (F2 classifier + mined-height probe), so
        // re-arming for another attempt is correct.
        val outcome = withTimeoutOrNull(BROADCAST_ATTEMPT_TIMEOUT) {
            executeWithRetries { sdk.executeNextPendingTransfer(NetworkPrivacyOptions(useTor = useTor), useEstimatedTip = true) }
        } ?: run {
            Twig.debug { "MIGRATION_DIAG LaneB: broadcast attempt timed out after $BROADCAST_ATTEMPT_TIMEOUT — re-arming." }
            scheduleForNextLiveWindow(accountKeyId, sdk, floor = AWAITING_PROOF_REARM_FLOOR)
            return Result.success()
        }
        return when (outcome) {
            is TransferAttemptOutcome.NothingDue -> {
                // Not due yet by estimate: re-arm for the live next window (states-based, like Lane A).
                scheduleForNextLiveWindow(accountKeyId, sdk)
                Twig.debug { "MIGRATION_DIAG LaneB: NothingDue — rescheduled for next live window." }
                Result.success()
            }
            is TransferAttemptOutcome.AwaitingProof -> {
                // The engine only serves proved transactions, so AwaitingProof means the due
                // transaction has no proof yet — a race with the sync lane (Lane A's wake is
                // pending or late), never a plan state. The plan itself stays exactly as the
                // engine committed it (the engine is the single source of truth; missed-but-
                // unexpired transfers need no shift — ZIP 374's signature does not cover the
                // anchor, so they prove late against their committed boundary and broadcast late).
                //
                // Strike counter: counts consecutive awaiting-proof STRIKES on the same transfer
                // with a completed sync in between (storage keys unchanged — historically named
                // "shift" after the deleted reschedule stack).
                val lastActivity: Instant? = lastNetworkActivity.get()
                val lastStrike: Instant? = shiftCounter.lastShiftAt(accountKeyId)
                val syncSince = syncCompletedSince(lastActivity, lastStrike)
                val count = shiftCounter.incrementIfSameTransfer(accountKeyId, outcome.transferId, syncCompletedSinceLastShift = syncSince)
                Twig.debug {
                    "MIGRATION_DIAG LaneB: AwaitingProof for ${outcome.transferId} " +
                        "(strike=$count, syncSinceLastStrike=$syncSince) — converting this run into a sync run"
                }

                // Convert THIS run into a Lane A run: sync + finalize + reconcile, under the same
                // privacy guard Lane A honours (the post-broadcast gate). Sync XOR broadcast per
                // execution — the broadcast already did not happen (nothing proved), and it is
                // re-armed for the next live window below, never attempted in this same run.
                if (sdk.isSyncBlocked().first()) {
                    Twig.debug { "MIGRATION_DIAG LaneB: sync-fallback skipped — post-broadcast privacy gate is active." }
                } else {
                    val burst = synchronizerProvider.getSynchronizerOrNull()?.syncToTip(timeout = LANE_A_SYNC_TIMEOUT)
                    Twig.debug { "MIGRATION_DIAG LaneB: sync-fallback syncToTip result=$burst" }
                    val proved = sdk.finalizeReadyTransfers()
                    Twig.debug { "MIGRATION_DIAG LaneB: sync-fallback proved=$proved" }
                    if (sdk.reconcileInvalidations()) {
                        // F5: the plan is invalid — notify, cancel BOTH lanes, and do NOT re-arm.
                        // The app-open router (CheckMigrationRecoveryUseCase) takes over from here.
                        migrationNotifier.notifyMigrationPlanInvalid(accountKeyId)
                        MigrationScheduler(applicationContext).cancel(accountKeyId)
                        MigrationSyncScheduler(applicationContext).cancel(accountKeyId)
                        Twig.debug { "MIGRATION_DIAG LaneB: sync-fallback reconcile found invalidation — cancelling both lanes." }
                        return Result.success()
                    }
                    lastNetworkActivity.stampNow()
                }

                // F4: escalate only on the TRANSITION to the 3rd counted strike — the counter
                // stays at 3 on subsequent no-sync strikes (nextShiftCount doesn't increment
                // without a sync), so gating on `count == THRESHOLD` alone would re-fire every
                // strike. Requiring `syncSince` means we only escalate the run that actually
                // reached the 3rd counted (sync-completed) strike: even Lane B's own sync
                // repeatedly failed to make the transaction provable — the "sync ran but proof
                // still impossible" alarm.
                if (shouldEscalateShift(syncSince, count)) {
                    // Once only — count == 3 exact equality ensures single notification.
                    // F7: render real "Transfer X of Y" values from the plan instead of 0 of 0.
                    migrationNotifier.notifyManualConfirmationRequired(
                        accountKeyId,
                        (plan?.nextPending?.index?.plus(1)) ?: 1,
                        plan?.totalCount ?: 0,
                    )
                }
                // Floor the re-arm: the unproven transaction is typically due immediately, which
                // otherwise collapses the delay to seconds and hammers the engine (observed live:
                // one run every 5 s). 60 s keeps the loop responsive without the churn.
                scheduleForNextLiveWindow(accountKeyId, sdk, floor = AWAITING_PROOF_REARM_FLOOR)
                Twig.debug {
                    "MIGRATION_DIAG LaneB: awaiting proof for ${outcome.transferId} — " +
                        "broadcast re-armed for the next live window (strike=$count)"
                }
                Result.success()
            }
            is TransferAttemptOutcome.Executed -> when (val result = outcome.result) {
                is TransferResult.Success -> {
                    shiftCounter.reset(accountKeyId)
                    Twig.debug { "MIGRATION_DIAG MigrationWorker: transfer sent — txId=${result.txId}" }
                    // Fold the SDK's authoritative "sent" status back into the persisted plan so the
                    // cached completedCount/nextPending advance — the home banner and the notification
                    // below both read the raw cached plan, so without this write-through they'd report a
                    // stale count (stuck on the first transfer) forever. Keyed by the worker's own
                    // account (inputData), not the currently-selected one.
                    val updatedPlan = migrationPlanRepository.load(accountKeyId)
                        ?.withLiveStatusOnly(sdk.getMigrationTransferStates())
                        ?.also { migrationPlanRepository.save(accountKeyId, it) }
                    if (updatedPlan?.nextPending != null) {
                        val delay = nextDelay(updatedPlan)
                        MigrationScheduler(applicationContext).schedule(accountKeyId, delay)
                        migrationNotifier.notifyTransferComplete(accountKeyId, updatedPlan.completedCount, updatedPlan.totalCount)
                        Twig.debug { "MIGRATION_DIAG MigrationWorker: next transfer scheduled in $delay" }
                    } else {
                        migrationNotifier.notifyMigrationComplete(accountKeyId)
                        Twig.debug { "MIGRATION_DIAG MigrationWorker: migration complete!" }
                    }
                    Result.success()
                }
                is TransferResult.NetworkError -> {
                    // Retries already exhausted (or the failure was non-retryable) inside
                    // executeWithRetries above — settle into an error state now rather than asking
                    // WorkManager for yet another attempt.
                    Twig.debug {
                        "MIGRATION_DIAG MigrationWorker: network error after retries, isTorFailure=${result.isTorFailure}"
                    }
                    if (result.isTorFailure) {
                        // Same reasoning as MigrationSendingVM.sendOnce()'s interactive NetworkError
                        // branch. Persist a flag so app-open reconciliation
                        // (CheckMigrationRecoveryUseCase) routes back through the Sending screen
                        // instead of the generic manual-confirmation path, and surface a distinct
                        // notification so this looks different from any other missed transfer.
                        pendingMigrationTorFailureStorageProvider.store(accountKeyId, true)
                        migrationNotifier.notifyMigrationTorFailure(accountKeyId)
                    } else if (next != null) {
                        // Nothing else re-arms a future attempt for a non-retryable failure — the
                        // user must open the app and act, same as a missed/stalled window.
                        migrationNotifier.notifyManualConfirmationRequired(accountKeyId, next.index + 1, plan.totalCount)
                    }
                    Result.failure()
                }
                TransferResult.InvalidNote -> {
                    // State is now RequiresAttention(InvalidTransfer) — spec §6.2, notes were spent
                    // outside the migration flow. On-launch reconciliation will surface the prompt, but
                    // the user still needs telling since nothing else runs meanwhile.
                    Twig.debug { "MIGRATION_DIAG MigrationWorker: transfer invalid (note spent externally) — user action required on next open." }
                    migrationNotifier.notifyMigrationPlanInvalid(accountKeyId)
                    // F5: this is a terminal migration state — cancel Lane A too (Lane B already
                    // stops re-arming by returning without scheduling).
                    MigrationSyncScheduler(applicationContext).cancel(accountKeyId)
                    Result.success()
                }
                TransferResult.Expired -> {
                    // State is now RequiresAttention(TransferExpired) — spec §6.3, the transfer's
                    // anchor expired before it could broadcast (the app wasn't opened in time). Distinct
                    // user-facing copy from InvalidNote above, even though both branches otherwise
                    // handle identically (no further action possible from the background worker).
                    Twig.debug { "MIGRATION_DIAG MigrationWorker: transfer expired — user action required on next open." }
                    migrationNotifier.notifyTransferExpired(accountKeyId)
                    // F5: terminal migration state — cancel Lane A too (Lane B already stops re-arming).
                    MigrationSyncScheduler(applicationContext).cancel(accountKeyId)
                    Result.success()
                }
            }
        }
    }

    private suspend fun isLaneARunning(): Boolean =
        withContext(Dispatchers.IO) {
            WorkManager.getInstance(applicationContext)
                .getWorkInfosForUniqueWork(WorkIds.WORK_ID_MIGRATION_SYNC).get()
        }.any { it.state == WorkInfo.State.RUNNING }

    /**
     * Schedules the next Lane B run based on live SDK transfer states. Reads the next pending
     * transaction's scheduledHeight from the SDK and computes a block-time-based delay; falls back
     * to the plan-repo scheduledAt estimate when the SDK has no pending states. The states include
     * preparations (kind-agnostic min over `!isSent`) — deliberately matching the engine's own
     * `nextDueTransferNative`, which serves due preparations for broadcast exactly like transfers,
     * so Lane B can never sleep past a due preparation layer.
     */
    private suspend fun scheduleForNextLiveWindow(
        accountKeyId: String,
        sdk: OrchardMigrationSdk,
        floor: Duration = Duration.ZERO,
    ) {
        val states = sdk.getMigrationTransferStates()
        val est = sdk.estimatedChainTip()
        val delay: Duration = if (states != null && est >= 0L) {
            val nextScheduledHeight = states.transfers
                .filter { !it.isSent }
                .minOfOrNull { it.scheduledHeight }
            if (nextScheduledHeight != null) {
                val blocksRemaining = (nextScheduledHeight - est).coerceAtLeast(1L)
                (blocksRemaining * sdk.estimatedSecondsPerBlock()).seconds
            } else {
                // All transfers sent — fall through to plan-repo fallback which will also be empty.
                planRepoDerivedDelay(accountKeyId)
            }
        } else {
            planRepoDerivedDelay(accountKeyId)
        }
        MigrationScheduler(applicationContext).schedule(accountKeyId, maxOf(delay, floor))
        Twig.debug { "MIGRATION_DIAG LaneB: scheduleForNextLiveWindow — delay=${maxOf(delay, floor)}" }
    }

    private suspend fun planRepoDerivedDelay(accountKeyId: String): Duration {
        val plan = migrationPlanRepository.load(accountKeyId)
        val next = plan?.nextPending ?: return 60.seconds
        val remaining = next.scheduledAt - Clock.System.now()
        return if (remaining.isNegative() || remaining < 60.seconds) 60.seconds else remaining
    }

    private fun nextDelay(plan: MigrationPlan): Duration {
        val next = plan.nextPending ?: return 0.seconds
        val remaining = next.scheduledAt - Clock.System.now()
        return if (remaining.isNegative()) 0.seconds else remaining
    }
}

private val STATUS_READ_TIMEOUT = 2.seconds
internal val AWAITING_PROOF_REARM_FLOOR = 60.seconds
private val LANE_A_WAIT_STEP = 5.seconds
private val BROADCAST_ATTEMPT_TIMEOUT = 3.minutes
private const val LANE_A_WAIT_CHECKS = 6
internal const val SHIFT_ESCALATION_THRESHOLD = 3

/**
 * F4: whether an AWAITING_PROOF strike should escalate (notify for manual confirmation).
 *
 * Escalation must fire ONLY on the transition to the [SHIFT_ESCALATION_THRESHOLD]th COUNTED
 * strike. The strike counter only increments when a sync completed since the last strike (spec
 * §2.B.4 case c); on a no-sync strike the counter stays at 3, so gating on `count == THRESHOLD`
 * alone would re-fire the escalation (and its once-only notification) on every subsequent no-sync
 * strike. Requiring [syncSince] restricts firing to the run that actually reached the 3rd counted
 * strike — i.e. "a sync ran between strikes and the transaction STILL cannot be proved" repeated
 * three times. (The name says "shift" for historical reasons — the counter and its storage keys
 * predate the deletion of the reschedule/shift stack.)
 */
internal fun shouldEscalateShift(syncSince: Boolean, count: Int): Boolean =
    syncSince && count == SHIFT_ESCALATION_THRESHOLD

// Same attempt count (3) as MigrationSendingVM.sendOnce()'s foreground retry loop — but not the
// same retry trigger: sendOnce() retries while polling for readiness (result == null) and stops
// on any non-null result; this retries only on a retryable NetworkError and stops on null. Each
// loop is correct for its own context (foreground polls for the transfer becoming ready;
// background rides out a flaky network) — they just happen to share the same attempt budget.
private const val MAX_BROADCAST_ATTEMPTS = 3
private const val BROADCAST_RETRY_DELAY_MS = 1500L

/**
 * Calls [attempt] up to [maxAttempts] times, retrying only while the result is an
 * [TransferAttemptOutcome.Executed] wrapping a retryable [TransferResult.NetworkError] — anything
 * else (NothingDue, AwaitingProof, a non-retryable error, success) short-circuits immediately.
 * Returns null only when [attempt] itself returns null (should not happen with the current SDK
 * contract, but guards against future changes). Top-level and `internal` (rather than a private
 * method on [MigrationWorker]) specifically so it's unit-testable without Koin or WorkManager,
 * neither of which this codebase has test infrastructure for today.
 */
internal suspend fun executeWithRetries(
    maxAttempts: Int = MAX_BROADCAST_ATTEMPTS,
    retryDelayMs: Long = BROADCAST_RETRY_DELAY_MS,
    attempt: suspend () -> TransferAttemptOutcome,
): TransferAttemptOutcome? {
    var result: TransferAttemptOutcome? = null
    for (i in 0 until maxAttempts) {
        if (i > 0) delay(retryDelayMs)
        result = attempt()
        val current = result
        val shouldRetry = current is TransferAttemptOutcome.Executed &&
            current.result is TransferResult.NetworkError &&
            (current.result as TransferResult.NetworkError).retryable
        if (!shouldRetry) break
    }
    return result
}

/**
 * What Lane B should do before calling the SDK's executeNextPendingTransfer.
 *
 * - [LaneBAction.DEFER_OVERLAP] — Lane A is running, OR the privacy quiet gap since the last
 *   network activity has not yet elapsed. Engine untouched; schedule re-arm after the buffer.
 * - [LaneBAction.BROADCAST] — all sources are quiet and the gap has elapsed; proceed to the SDK.
 */
internal enum class LaneBAction { BROADCAST, DEFER_OVERLAP }

/**
 * Pure preflight decision for Lane B.
 *
 * Takes pre-computed scalars so it is unit-testable without Koin, WorkManager or a real SDK.
 *
 * [lastNetworkActivityEpochSeconds] is null when no broadcast has ever been stamped (first run);
 * in that case the gap check is skipped and BROADCAST is returned.
 */
internal fun decideLaneBPreflight(
    laneARunning: Boolean,
    synchronizerSyncing: Boolean,
    nowEpochSeconds: Long,
    lastNetworkActivityEpochSeconds: Long?,
    privacyBufferSeconds: Long,
): LaneBAction {
    if (laneARunning || synchronizerSyncing) return LaneBAction.DEFER_OVERLAP
    if (lastNetworkActivityEpochSeconds != null &&
        nowEpochSeconds - lastNetworkActivityEpochSeconds < privacyBufferSeconds
    ) {
        return LaneBAction.DEFER_OVERLAP
    }
    return LaneBAction.BROADCAST
}

/**
 * Returns true if a completed sync has been observed since the last awaiting-proof strike for
 * this account.
 *
 * A sync is considered "completed since the last strike" when [lastActivity] is non-null (meaning
 * network activity has been stamped) AND it is strictly after [lastShift] (the timestamp of the
 * most recent strike for this transfer — "shift" in the name/storage for historical reasons).
 * If either is null the function returns false:
 * - [lastActivity] null  → no network activity ever recorded → no completed sync observed
 * - [lastShift] null     → no previous strike → treat as "before all time"; if lastActivity is
 *   non-null a sync HAS completed since the beginning, so return true in that case.
 *
 * Exposed as a top-level function so it can be unit-tested in isolation (both providers return
 * [java.time.Instant] which is easy to construct without Android infrastructure).
 */
internal fun syncCompletedSince(lastActivity: Instant?, lastShift: Instant?): Boolean {
    if (lastActivity == null) return false
    // No previous shift means we treat shift time as the epoch (beginning of time) — any
    // recorded activity is "since" then.
    val shiftEpoch = lastShift ?: Instant.EPOCH
    return lastActivity > shiftEpoch
}
