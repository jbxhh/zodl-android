package co.electriccoin.zcash.work

import android.content.Context
import androidx.annotation.Keep
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import cash.z.ecc.android.sdk.AttentionReason
import cash.z.ecc.android.sdk.MigrationState
import cash.z.ecc.android.sdk.MigrationTransferState
import cash.z.ecc.android.sdk.MigrationTransferStates
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.BuildConfig
import co.electriccoin.zcash.ui.common.provider.LastNetworkActivityStorageProvider
import co.electriccoin.zcash.ui.common.provider.MigrationNotifier
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.usecase.GetOrchardMigrationSdkUseCase
import kotlinx.coroutines.flow.first
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Lane A — the sync (and prove) lane of the two-lane migration scheduler.
 *
 * Plan-fixed wakes (iOS-style): the worker wakes exactly at the anchor-boundary heights (plus a
 * small settle margin) of not-yet-proved, not-yet-sent transactions — the heights the engine's
 * committed plan says a proof becomes possible at. A woken Lane A ALWAYS syncs (sync +
 * finalizeReadyTransfers + reconcile; the proof falls out of the sync when the moment is right).
 * The only reasons not to sync on a wake:
 *  (a) the post-broadcast privacy gate (`isSyncBlocked`), and
 *  (b) the imminent-due step-aside — ONLY when the imminently due transaction is ALREADY proved
 *      (i.e. a broadcast that can actually happen; syncing then would correlate the sync burst
 *      with it, per ZIP 318's sync/broadcast de-correlation).
 * An unproven due transfer never triggers the step-aside — its proof can only come from this
 * lane's own sync, so stepping aside would livelock the plan (observed live pre-rewrite).
 *
 * Cadence survives ONLY as the fallback when live states are unavailable.
 *
 * TODO(final impl per Kris): Lane A may later be adjusted back to a periodic (cadence-driven)
 * sync, consuming the engine's own `sync_wakeup_schedule` (librustzcash #2801, boundary + settle
 * margin + jitter) via FFI once the SDK moves to the rc.3+ engine crates — do not build that now.
 */
@Keep
class MigrationSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params), KoinComponent {

    private val getOrchardMigrationSdk: GetOrchardMigrationSdkUseCase by inject()
    private val synchronizerProvider: SynchronizerProvider by inject()
    private val lastNetworkActivity: LastNetworkActivityStorageProvider by inject()
    private val migrationNotifier: MigrationNotifier by inject()

    override suspend fun doWork(): Result {
        val accountKeyId = inputData.getString(MigrationScheduler.KEY_ACCOUNT_KEY_ID)
            ?: return Result.success()

        val sdk = runCatching { getOrchardMigrationSdk(accountKeyId) }.getOrElse {
            // The wallet isn't up yet — happens deterministically right after an app
            // update/reboot, when WorkManager greedily re-runs the restored job before the
            // synchronizer initializes. Returning success here silently BREAKS the
            // self-rechaining lane (no re-arm ever happens again — observed live: both lanes
            // dead after a reinstall). Retry lets WorkManager back off and re-run until the
            // SDK is reachable, at which point the normal run re-arms the chain. A stale job
            // whose account is permanently gone never reaches this point — the account lookup
            // suspends instead of throwing, and both account-deleting flows (Keystone
            // disconnect, wallet reset) cancel the lanes at the source.
            Twig.debug { "MIGRATION_DIAG LaneA: SDK not ready — retrying via WorkManager backoff." }
            return Result.retry()
        }

        // F3: Lane A must terminate once the migration reaches a terminal state. Unlike Lane B,
        // whose only stop signal is states==null, migrationTransferStates() keeps returning rows
        // for a terminal (Complete / permanently-attention) migration so the Complete screens can
        // still read them for display. So gate on the migration STATE here: if terminal, cancel
        // Lane A's own re-arm (return without scheduling). SyncRequiredBeforeNext is NOT terminal —
        // Lane A's sync is exactly what heals it, so that reason keeps Lane A alive.
        if (shouldLaneAStop(sdk.getMigrationState())) {
            Twig.debug { "MIGRATION_DIAG LaneA: migration terminal — stopping Lane A." }
            MigrationSyncScheduler(applicationContext).cancel(accountKeyId)
            return Result.success()
        }

        // Cache privacy buffer duration to avoid redundant calls.
        val privacyBufferSeconds = sdk.privacySyncBufferDuration().inWholeSeconds

        // Read live states directly from the SDK (NOT the MigrationPlanRepository cache — the
        // engine is the single source of truth; this lane only surfaces it). The estimated chain
        // tip is read immediately after states to minimize drift: it is the right denominator for
        // wall-clock projection (states.tipHeight is the scanned tip, which can be hours stale in
        // a backgrounded wallet).
        val states = sdk.getMigrationTransferStates()
        val est = sdk.estimatedChainTip()

        if (states == null) {
            // No in-progress migration — stop re-arming Lane A entirely.
            Twig.debug { "MIGRATION_DIAG LaneA: no migration in progress, stopping." }
            return Result.success()
        }

        val nowSec = nowEpochSeconds()
        val secondsPerBlock = sdk.estimatedSecondsPerBlock()
        val nextProvedDue = nextProvedDueEpochSeconds(states, est, nowSec, secondsPerBlock)
        val decision = decideLaneARun(
            nowEpochSeconds = nowSec,
            nextProvedDueEpochSeconds = nextProvedDue,
            privacyBufferSeconds = privacyBufferSeconds,
            isGateBlocked = sdk.isSyncBlocked().first(),
        )

        Twig.debug {
            val unproven = states.transfers.filter { !it.isSent && !it.isProved }
            "MIGRATION_DIAG LaneA: run start account=$accountKeyId decision=$decision " +
                "(estimatedTip=$est, nextProvedDueEpoch=$nextProvedDue, now=$nowSec, " +
                "secondsPerBlock=$secondsPerBlock, unproven=[" +
                unproven.joinToString {
                    "${it.id}(boundary=${it.anchorBoundaryHeight ?: "natural"}," +
                        "provableAt=${provableAtHeight(it)})"
                } + "])"
        }
        if (decision == LaneARunDecision.RUN) {
            val burst = synchronizerProvider.getSynchronizerOrNull()?.syncToTip(timeout = LANE_A_SYNC_TIMEOUT)
            Twig.debug { "MIGRATION_DIAG LaneA: syncToTip result=$burst" }
            val proved = sdk.finalizeReadyTransfers()
            Twig.debug { "MIGRATION_DIAG LaneA: proved=$proved" }

            if (sdk.reconcileInvalidations()) {
                // One or more transfer input notes were spent externally — the plan is now
                // invalid. Notify the user, cancel BOTH lanes (Lane B = MigrationScheduler),
                // and do NOT re-arm Lane A.
                migrationNotifier.notifyMigrationPlanInvalid(accountKeyId)
                MigrationScheduler(applicationContext).cancel(accountKeyId)
                MigrationSyncScheduler(applicationContext).cancel(accountKeyId)
                Twig.debug { "MIGRATION_DIAG LaneA: invalidation detected — cancelling both lanes." }
                return Result.success()
            }

            lastNetworkActivity.stampNow()
        }

        // Re-arm Lane A. After a RUN the post-run states are re-read so anything just proved
        // above doesn't anchor the next wake; the wake targets the next unproven, unsent
        // transaction's provable-at height (committed boundary + settle margin). When no unproven
        // work remains, a single completion sweep is armed at the LAST unsent scheduled height +
        // privacy buffer: the run-start terminal check then stops Lane A after the final mine.
        // Cadence remains only for the states-unavailable fallback.
        val reArmDelay: Duration = when (decision) {
            LaneARunDecision.SKIP_GATE_BLOCKED, LaneARunDecision.SKIP_NEAR_DUE ->
                laneASkipReArmDelay(
                    decision = decision,
                    nowEpochSeconds = nowEpochSeconds(),
                    nextProvedDueEpochSeconds = nextProvedDue,
                    privacyBufferSeconds = privacyBufferSeconds,
                )
            LaneARunDecision.RUN -> {
                val postRunStates = sdk.getMigrationTransferStates()
                val postRunEst = sdk.estimatedChainTip()
                val wake = postRunStates?.let { nextBoundaryWake(it, postRunEst, secondsPerBlock) }
                when {
                    wake != null -> {
                        Twig.debug {
                            "MIGRATION_DIAG LaneA: next wake from boundary — tx=${wake.txId} " +
                                "wakeHeight=${wake.wakeHeight} (estimatedTip=$postRunEst) in ${wake.delay}"
                        }
                        wake.delay
                    }
                    postRunStates != null -> {
                        val sweep = completionSweepDelay(postRunStates, postRunEst, secondsPerBlock, privacyBufferSeconds)
                        if (sweep != null) {
                            Twig.debug { "MIGRATION_DIAG LaneA: nothing left to prove — completion sweep in $sweep" }
                            sweep
                        } else {
                            // All transactions sent (awaiting mining) or no estimate — cadence
                            // fallback until the run-start terminal check stops the lane.
                            laneACadence()
                        }
                    }
                    else -> laneACadence() // states unavailable — the only cadence left
                }
            }
        }
        MigrationSyncScheduler(applicationContext).schedule(accountKeyId, reArmDelay)
        Twig.debug { "MIGRATION_DIAG LaneA: decision=$decision, re-arming in $reArmDelay" }

        return Result.success()
    }
}

internal val LANE_A_SYNC_TIMEOUT = 3.minutes

/**
 * Fallback-only cadence: 5 min on testnet, 60 min on mainnet. Used ONLY when live transfer
 * states (or the tip estimate) are unavailable — every regular Lane A wake is computed from the
 * engine's committed anchor boundaries instead (see [nextBoundaryWake]).
 *
 * Uses [BuildConfig.FLAVOR] because the SDK's network id is not cheaply reachable from a static
 * context without a full OrchardMigrationSdk instance. BuildConfig.FLAVOR contains the full
 * combined product flavor string (e.g. "zcashtestnetFoss") so a substring match is reliable.
 */
internal fun laneACadence(): Duration =
    if (BuildConfig.FLAVOR.contains("testnet", ignoreCase = true)) 5.minutes else 60.minutes

/** Returns the current wall-clock time as epoch seconds. Extracted for testability. */
internal fun nowEpochSeconds(): Long = Clock.System.now().epochSeconds

// ── Pure functions (tested) ────────────────────────────────────────────────────

/**
 * F3: whether Lane A should stop re-arming for the given migration [state].
 *
 * Terminal states — the plan can make no further automatic progress, so Lane A's sync+prove loop
 * has nothing left to do:
 * - [MigrationState.Complete] — all transfers confirmed on-chain.
 * - [MigrationState.RequiresAttention] with [AttentionReason.InvalidTransfer] or
 *   [AttentionReason.TransferExpired] — the plan is dead; the app-open router handles it.
 *
 * NON-terminal (Lane A keeps running):
 * - [AttentionReason.SyncRequiredBeforeNext] — Lane A's own sync is exactly what heals this, so
 *   stopping here would strand the migration.
 * - [MigrationState.InProgress] / pre-commit states — the migration is still executing.
 */
internal fun shouldLaneAStop(state: MigrationState): Boolean =
    when (state) {
        is MigrationState.Complete -> true
        is MigrationState.RequiresAttention ->
            when (state.reason) {
                is AttentionReason.InvalidTransfer, is AttentionReason.TransferExpired -> true
                is AttentionReason.SyncRequiredBeforeNext -> false
            }
        else -> false
    }

internal enum class LaneARunDecision { RUN, SKIP_NEAR_DUE, SKIP_GATE_BLOCKED }

/**
 * Decides whether a woken Lane A runs its sync+prove cycle this invocation. Lane A ALWAYS syncs
 * on a wake except for exactly two reasons:
 *
 * 1. [LaneARunDecision.SKIP_GATE_BLOCKED] — isSyncBlocked is true (post-broadcast privacy buffer
 *    is active); running a sync now would correlate the sync burst with the pending broadcast.
 * 2. [LaneARunDecision.SKIP_NEAR_DUE] — a PROVED, unsent transaction's estimated due time minus
 *    the privacy buffer has passed: a broadcast that can actually happen is imminent, so step
 *    aside and let Lane B fire instead of advancing the tip right before it. The proved filter is
 *    load-bearing: an UNPROVEN due transaction never holds Lane A back — its proof can only come
 *    from this lane's own sync (the pre-rewrite unproven step-aside livelocked a live plan).
 * 3. [LaneARunDecision.RUN] — otherwise: syncToTip + finalizeReadyTransfers + reconcile.
 */
internal fun decideLaneARun(
    nowEpochSeconds: Long,
    nextProvedDueEpochSeconds: Long?,
    privacyBufferSeconds: Long,
    isGateBlocked: Boolean,
): LaneARunDecision =
    when {
        isGateBlocked -> LaneARunDecision.SKIP_GATE_BLOCKED
        nextProvedDueEpochSeconds != null &&
            nowEpochSeconds >= nextProvedDueEpochSeconds - privacyBufferSeconds ->
            LaneARunDecision.SKIP_NEAR_DUE
        else -> LaneARunDecision.RUN
    }

/**
 * Settle margin on top of an anchor boundary before Lane A wakes for it: the boundary block must
 * be strictly below the scanned tip for the checkpoint to exist, and a couple of blocks of
 * headroom absorbs propagation/scan jitter. 2 blocks ≈ the engine's WakeupParams margin scaled to
 * the 12-block testnet grid (10 @ the 144-block mainnet grid — revisit with the final cadence
 * impl, see the class kdoc TODO).
 */
internal const val SETTLE_MARGIN_BLOCKS = 2L

/**
 * The block height at which [t] becomes provable: its committed anchor bucket boundary when the
 * engine drew one, otherwise (preparations — natural anchor) its own scheduled height; plus
 * [SETTLE_MARGIN_BLOCKS].
 */
internal fun provableAtHeight(t: MigrationTransferState): Long =
    (t.anchorBoundaryHeight ?: t.scheduledHeight) + SETTLE_MARGIN_BLOCKS

/** The next plan-fixed Lane A wake — see [nextBoundaryWake]. */
internal data class LaneABoundaryWake(
    val delay: Duration,
    /** The unproven, unsent transaction whose provable-at height drives this wake (diagnostics). */
    val txId: Long,
    /** The absolute block height the wake targets ([provableAtHeight] of [txId]). */
    val wakeHeight: Long,
)

/**
 * Computes the next Lane A wake from the engine's committed plan: the minimum [provableAtHeight]
 * over all not-yet-proved, not-yet-sent transactions, converted to a wall-clock delay at the
 * measured block rate. Floor [MIN_LANE_A_BACKOFF_SECONDS] (WorkManager slack / hot-loop guard);
 * deliberately NO upper cap — the wake is plan-fixed, not cadence-bounded.
 *
 * Returns `null` when nothing unproven+unsent remains, or when the tip estimate is unavailable
 * (`est < 0`) — callers fall back to the completion sweep or the cadence, respectively.
 */
internal fun nextBoundaryWake(
    states: MigrationTransferStates,
    est: Long,
    secondsPerBlock: Long,
): LaneABoundaryWake? {
    if (est < 0L) return null
    val next = states.transfers
        .filter { !it.isSent && !it.isProved }
        .minByOrNull { provableAtHeight(it) }
        ?: return null
    val wakeHeight = provableAtHeight(next)
    val delaySeconds = ((wakeHeight - est) * secondsPerBlock).coerceAtLeast(MIN_LANE_A_BACKOFF_SECONDS)
    return LaneABoundaryWake(delaySeconds.seconds, next.id, wakeHeight)
}

/**
 * Epoch seconds at which the earliest PROVED, unsent transaction's broadcast window opens — the
 * only case where the privacy step-aside is meaningful (the broadcast can actually happen).
 * Returns `null` when nothing proved is pending or the tip estimate is unavailable (`est < 0`).
 */
internal fun nextProvedDueEpochSeconds(
    states: MigrationTransferStates,
    est: Long,
    nowEpochSeconds: Long,
    secondsPerBlock: Long,
): Long? {
    if (est < 0L) return null
    return states.transfers
        .filter { it.isProved && !it.isSent }
        .minOfOrNull { t ->
            val blocksRemaining = (t.scheduledHeight - est).coerceAtLeast(0L)
            nowEpochSeconds + blocksRemaining * secondsPerBlock
        }
}

/**
 * Re-arm delay when no unproven work remains but unsent (proved) transactions do: one completion
 * sweep at the LAST unsent scheduled height plus the privacy buffer, so the wake lands after the
 * final broadcast window — its run-start terminal check then stops Lane A once the migration
 * completes. Returns `null` when everything is sent or the tip estimate is unavailable.
 */
internal fun completionSweepDelay(
    states: MigrationTransferStates,
    est: Long,
    secondsPerBlock: Long,
    privacyBufferSeconds: Long,
): Duration? {
    if (est < 0L) return null
    val lastUnsentHeight = states.transfers
        .filter { !it.isSent }
        .maxOfOrNull { it.scheduledHeight }
        ?: return null
    val delaySeconds = ((lastUnsentHeight - est).coerceAtLeast(0L) * secondsPerBlock + privacyBufferSeconds)
        .coerceAtLeast(MIN_LANE_A_BACKOFF_SECONDS)
    return delaySeconds.seconds
}

/**
 * Re-arm delay for the two SKIP decisions:
 * - [LaneARunDecision.SKIP_NEAR_DUE]: wait until after the proved transaction's window closes
 *   (`nextProvedDue + buffer − now`), floored at [MIN_LANE_A_BACKOFF_SECONDS] to prevent
 *   hot-loop spinning when the estimate is already in the past.
 * - [LaneARunDecision.SKIP_GATE_BLOCKED]: wait out the privacy buffer that blocked the gate.
 */
internal fun laneASkipReArmDelay(
    decision: LaneARunDecision,
    nowEpochSeconds: Long,
    nextProvedDueEpochSeconds: Long?,
    privacyBufferSeconds: Long,
): Duration =
    if (decision == LaneARunDecision.SKIP_NEAR_DUE && nextProvedDueEpochSeconds != null) {
        (nextProvedDueEpochSeconds + privacyBufferSeconds - nowEpochSeconds)
            .coerceAtLeast(MIN_LANE_A_BACKOFF_SECONDS).seconds
    } else {
        privacyBufferSeconds.coerceAtLeast(MIN_LANE_A_BACKOFF_SECONDS).seconds
    }

internal const val MIN_LANE_A_BACKOFF_SECONDS = 60L

/**
 * Returns the minimum estimated epoch-second at which the earliest PENDING (not yet sent)
 * transaction will be ready to broadcast, based on `(scheduledHeight − est) * secondsPerBlock`.
 * Deliberately kind- and proof-agnostic (min over `!isSent`, preparations included) — this is
 * Lane B's next-window basis, not a Lane A wake source.
 *
 * Returns `null` when:
 * - [states] has no pending transactions (all sent or empty list)
 * - [est] is the sentinel value `-1` (chain tip unavailable)
 *
 * Blocks remaining is clamped to ≥ 0 so an already-past height gives an offset of 0 instead of a
 * negative result.
 *
 * NOTE: This function considers only the account whose [states] are passed. In a multi-account
 * wallet each account would have its own worker carrying its own accountKeyId; the
 * single-account path (most deployments) is the designed norm and is fully correct here.
 */
internal fun nextEstimatedDueEpochSeconds(
    states: MigrationTransferStates,
    est: Long,
    nowEpochSeconds: Long = Clock.System.now().epochSeconds,
    secondsPerBlock: Long = SECONDS_PER_BLOCK,
): Long? {
    if (est < 0L) return null
    return states.transfers
        .filter { !it.isSent }
        .minOfOrNull { transfer ->
            val blocksRemaining = (transfer.scheduledHeight - est).coerceAtLeast(0L)
            nowEpochSeconds + blocksRemaining * secondsPerBlock
        }
}

private const val SECONDS_PER_BLOCK = 75L
