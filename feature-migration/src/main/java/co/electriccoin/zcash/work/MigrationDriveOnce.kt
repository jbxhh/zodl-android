package co.electriccoin.zcash.work

import cash.z.ecc.android.sdk.CloseableSynchronizer
import cash.z.ecc.android.sdk.MigrationAdvanceStep
import cash.z.ecc.android.sdk.MigrationBlocker
import cash.z.ecc.android.sdk.MigrationSyncWakeup
import cash.z.ecc.android.sdk.MigrationTransferState
import cash.z.ecc.android.sdk.MigrationTransferStates
import cash.z.ecc.android.sdk.NetworkPrivacyOptions
import cash.z.ecc.android.sdk.OrchardMigrationSdk
import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.TransferAttemptOutcome
import cash.z.ecc.android.sdk.TransferResult
import co.electriccoin.zcash.migration.BuildConfig
import co.electriccoin.zcash.migration.migrationLog
import co.electriccoin.zcash.ui.common.model.migration.LiveMigrationSnapshot
import co.electriccoin.zcash.ui.common.model.migration.toSnapshot
import co.electriccoin.zcash.ui.common.provider.ApplicationStateProvider
import co.electriccoin.zcash.ui.common.provider.IsMigrationTorEnabledStorageProvider
import co.electriccoin.zcash.ui.common.provider.LastNetworkActivityStorageProvider
import co.electriccoin.zcash.ui.common.provider.MigrationNotifier
import co.electriccoin.zcash.ui.common.provider.PendingMigrationTorFailureStorageProvider
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * What the caller should do after one [MigrationDriveOnce.run] call.
 *  - [ReArmed]: a step ran to completion and already re-armed the WorkManager chain (via
 *    `MigrationScheduler.schedule` inside `reArm`) to [delay] — the CALLER's own next check
 *    (the live driver's loop) should wait the same [delay]; the worker discards this (it's
 *    already re-armed, nothing more to do).
 *  - [LockBusy]: another caller was mid-step; THIS call did nothing (no `reArm` ran). The
 *    worker MUST explicitly self-schedule using [retryDelay] here, or a lost race silently
 *    kills the durable WorkManager chain (nothing else will re-arm it). The live driver just
 *    waits [retryDelay] and tries again.
 *  - [Terminal]: the migration reached `Complete`, or a state that intentionally never
 *    self-schedules (`Rebuild`, `Replan`, `InvalidNote`, `Expired`, `NetworkError`) — all of
 *    these need a user-driven reschedule/re-plan (or are simply done), so nothing here re-arms.
 *    Neither caller should re-arm; the live driver stops.
 */
sealed class DriveOnceResult {
    data class ReArmed(
        val delay: Duration
    ) : DriveOnceResult()

    data class LockBusy(
        val retryDelay: Duration
    ) : DriveOnceResult()

    object Terminal : DriveOnceResult()
}

/**
 * The single "what now, do it, re-arm" function — the shared decision-and-execution logic that
 * both the WorkManager worker (survives process death) and the app-scoped live-process driver
 * (faster while the app is alive) call, so there is exactly one execution path, never two
 * divergent ones. Internally guarded by [DRIVE_LOCK] so an already-in-flight call from one
 * caller is never interrupted by the other (an already-*scheduled-but-not-fired* WorkManager job
 * is superseded for free via `ExistingWorkPolicy.REPLACE` inside `reArm`; only an *actively
 * executing* step needs this lock).
 */
class MigrationDriveOnce(
    private val applicationContext: android.content.Context,
    private val migrationNotifier: MigrationNotifier,
    private val isMigrationTorEnabledStorageProvider: IsMigrationTorEnabledStorageProvider,
    private val pendingMigrationTorFailureStorageProvider: PendingMigrationTorFailureStorageProvider,
    private val synchronizerProvider: SynchronizerProvider,
    private val lastNetworkActivity: LastNetworkActivityStorageProvider,
    private val applicationStateProvider: ApplicationStateProvider,
) {
    /**
     * @param allowForcedBroadcastWindow Only `true` for [MigrationLiveDriver] — the worker (via
     * [MigrationWorker.doWork]) must NEVER pass `true`: forcing a broadcast window can wait
     * nearly a full privacy buffer (up to 10 minutes on mainnet) before broadcasting, which
     * cannot fit inside WorkManager's own ~10-minute `doWork()` execution ceiling. The live
     * driver has no such ceiling (it runs only as long as the app process is alive), so it is
     * the only safe caller for this branch.
     */
    suspend fun run(
        sdk: OrchardMigrationSdk,
        accountKeyId: String,
        allowForcedBroadcastWindow: Boolean = false,
    ): DriveOnceResult {
        if (!DRIVE_LOCK.tryLock()) {
            migrationLog("MigrationDriveOnce: another drive is in progress — skipping, short retry.")
            return DriveOnceResult.LockBusy(SKIP_RETRY_DELAY)
        }
        try {
            // This IS a run — stamp the heartbeat regardless of which caller drove it, so the
            // dead-man's-switch alarm (MigrationTransferDueReceiver) never fires a spurious
            // "step due" notification just because the WORKER specifically didn't fire (the live
            // driver may have driven this account forward instead).
            MigrationWorkerHeartbeat.stampRun(applicationContext, accountKeyId)

            val step = sdk.nextStep()
            if (step == null) {
                migrationLog("MigrationDriveOnce: no migration in progress — nothing to do.")
                return DriveOnceResult.Terminal
            }
            migrationLog("MigrationDriveOnce: run start account=$accountKeyId step=$step")
            return when (step) {
                MigrationAdvanceStep.Complete -> {
                    completeRun(accountKeyId)
                    DriveOnceResult.Terminal
                }

                is MigrationAdvanceStep.Rebuild -> {
                    rebuildRun(sdk, accountKeyId, step.transferId)
                    DriveOnceResult.Terminal
                }

                MigrationAdvanceStep.Replan -> {
                    replanRun(sdk, accountKeyId)
                    DriveOnceResult.Terminal
                }

                is MigrationAdvanceStep.Prove -> {
                    syncRun(sdk, accountKeyId)
                }

                is MigrationAdvanceStep.Broadcast -> {
                    broadcastRun(sdk, accountKeyId, allowForcedBroadcastWindow)
                }

                MigrationAdvanceStep.Waiting -> {
                    waitingRun(sdk, accountKeyId)
                }
            }
        } finally {
            DRIVE_LOCK.unlock()
        }
    }

    /**
     * The engine answers `nextStep` at the SCANNED tip, which can lag wall clock by hours in a
     * backgrounded wallet — nextStep is now Broadcast-authoritative (it checks
     * `next_broadcastable` at the estimated tip internally), so a due broadcast is dispatched
     * straight to [broadcastRun] by [run] and never reaches this function. `waitingRun` only
     * handles a genuine engine `Waiting`: sweep to completion, surface an unprovable blocker, or
     * re-arm.
     */
    private suspend fun waitingRun(sdk: OrchardMigrationSdk, accountKeyId: String): DriveOnceResult {
        val states = sdk.getMigrationTransferStates()
        val allSent = states != null && states.transfers.isNotEmpty() && states.transfers.all { it.isSent }
        val hasUnprovableBlocker = states?.transfers?.any { it.blocker == MigrationBlocker.UNPROVABLE_ANCHOR } == true
        return when (waitingDisposition(allSent, hasUnprovableBlocker)) {
            WaitingDisposition.COMPLETION_SWEEP -> {
                // Everything broadcast, awaiting mining. Mining is only observed by a scan-driven
                // reconcile, so the sweep must be a REAL sync run — a passive wait would never let
                // the engine reach Complete in the background (review M2).
                migrationLog("MigrationDriveOnce: all transactions sent — completion sweep sync run.")
                syncRun(sdk, accountKeyId)
            }

            WaitingDisposition.SURFACE_UNPROVABLE -> {
                surfaceUnprovableBlocker(sdk, accountKeyId, states)
                DriveOnceResult.ReArmed(reArm(sdk, accountKeyId))
            }

            WaitingDisposition.RE_ARM -> {
                DriveOnceResult.ReArmed(reArm(sdk, accountKeyId))
            }
        }
    }

    /**
     * A sync (prove) run: syncToTip + finalizeReadyTransfers + reconcile, gated by the
     * post-broadcast privacy buffer. Nothing broadcasts in this run — sync XOR broadcast per
     * execution. Afterwards the engine is asked again: a ready preparation chains immediately
     * (fast-track), a crossing waits out the quiet gap this sync just opened.
     */
    private suspend fun syncRun(sdk: OrchardMigrationSdk, accountKeyId: String): DriveOnceResult {
        if (sdk.isSyncBlocked().first()) {
            migrationLog("MigrationDriveOnce: sync run blocked by the post-broadcast privacy gate — deferring.")
            return DriveOnceResult.ReArmed(reArm(sdk, accountKeyId, floor = sdk.privacySyncBufferDuration()))
        }
        val burst = synchronizerProvider.getSynchronizerOrNull()?.syncToTip(timeout = SYNC_TIMEOUT)
        migrationLog("MigrationDriveOnce: syncToTip result=$burst")
        val proved = sdk.finalizeReadyTransfers()
        migrationLog("MigrationDriveOnce: proved=$proved")
        if (sdk.reconcileInvalidations()) {
            // The plan is invalid (input notes spent externally) — notify and do NOT re-arm; the
            // app-open router (CheckMigrationRecoveryUseCase) takes over from here.
            migrationNotifier.notifyMigrationPlanInvalid(accountKeyId)
            MigrationScheduler(applicationContext).cancel(accountKeyId)
            migrationLog("MigrationDriveOnce: reconcile found an invalidation — stopping the work chain.")
            return DriveOnceResult.Terminal
        }
        lastNetworkActivity.stampNow()

        return when (val next = sdk.nextStep()) {
            is MigrationAdvanceStep.Broadcast -> {
                val prep = nextDueUnsentIsPreparation(sdk.getMigrationTransferStates(), sdk.estimatedChainTip())
                val chainDelay = if (prep) PREP_FAST_TRACK_REARM else sdk.privacySyncBufferDuration()
                MigrationScheduler(applicationContext).schedule(accountKeyId, chainDelay)
                migrationLog("MigrationDriveOnce: sync done, next=$next — broadcast run in $chainDelay")
                DriveOnceResult.ReArmed(chainDelay)
            }

            MigrationAdvanceStep.Complete -> {
                completeRun(accountKeyId)
                DriveOnceResult.Terminal
            }

            is MigrationAdvanceStep.Rebuild -> {
                rebuildRun(sdk, accountKeyId, next.transferId)
                DriveOnceResult.Terminal
            }

            else -> {
                // Prove again (boundary not yet settled at the new tip) or Waiting.
                migrationLog("MigrationDriveOnce: sync done, next=$next — re-arming.")
                surfaceUnprovableBlocker(sdk, accountKeyId, sdk.getMigrationTransferStates())
                DriveOnceResult.ReArmed(reArm(sdk, accountKeyId))
            }
        }
    }

    @Suppress("ReturnCount", "LongMethod", "CyclomaticComplexMethod")
    private suspend fun broadcastRun(
        sdk: OrchardMigrationSdk,
        accountKeyId: String,
        allowForcedBroadcastWindow: Boolean,
    ): DriveOnceResult {
        val states = sdk.getMigrationTransferStates()
        val est = sdk.estimatedChainTip()
        // Capture the transaction the ENGINE will actually serve (vec/id order among proved+due —
        // review L2), falling back to schedule order when nothing is broadcastable yet, so the
        // fast-track preflight AND the post-send notification attribute the right kind.
        val nextCandidate = engineBroadcastCandidate(states, est) ?: earliestUnsent(states)
        val prepFastTrack =
            nextCandidate != null &&
                !nextCandidate.isTransfer &&
                nextCandidate.isProved &&
                nextCandidate.scheduledHeight <= est

        // status is a Flow<Status> — timeout if cold; null synchronizer is non-syncing.
        // timeout → assume SYNCING → defer (privacy-safe default; production status is a StateFlow
        // and answers immediately).
        val syncing =
            synchronizerProvider.synchronizer.value?.let { synchronizer ->
                withTimeoutOrNull(STATUS_READ_TIMEOUT) { synchronizer.status.first() } ?: Synchronizer.Status.SYNCING
            } == Synchronizer.Status.SYNCING
        val lastActivity = lastNetworkActivity.get()
        val preflight =
            decideBroadcastPreflight(
                synchronizerSyncing = syncing,
                nowEpochSeconds = nowEpochSeconds(),
                lastNetworkActivityEpochSeconds = lastActivity?.epochSecond,
                privacyBufferSeconds = sdk.privacySyncBufferDuration().inWholeSeconds,
                prepFastTrack = prepFastTrack,
            )
        migrationLog(
            "MigrationDriveOnce: broadcast preflight=$preflight " +
                "(syncing=$syncing, prepFastTrack=$prepFastTrack, lastNetworkActivity=$lastActivity)"
        )
        if (preflight == BroadcastPreflight.DEFER) {
            if (syncing && allowForcedBroadcastWindow && applicationStateProvider.isInForeground.first()) {
                // Force a window — ONLY the live driver ever reaches here (allowForcedBroadcastWindow),
                // and only while a FRESH read confirms the app is actually foreground right now (do not
                // trust `syncing` alone as a foreground proxy: the engine's own status is a stale
                // last-tick value that onBackground() does not reset).
                val closeable = synchronizerProvider.getSynchronizerOrNull() as? CloseableSynchronizer
                if (closeable == null) {
                    val deferDelay = sdk.privacySyncBufferDuration()
                    MigrationScheduler(applicationContext).schedule(accountKeyId, deferDelay)
                    migrationLog("MigrationDriveOnce: no pausable synchronizer — deferring broadcast $deferDelay.")
                    return DriveOnceResult.ReArmed(deferDelay)
                }
                closeable.pause()
                lastNetworkActivity.stampNow()
                migrationLog("MigrationDriveOnce: paused foreground sync to open a broadcast window.")
                try {
                    val gap = quietGapRemaining(sdk.privacySyncBufferDuration())
                    if (gap.isPositive()) {
                        migrationLog("MigrationDriveOnce: waiting privacy quiet gap $gap before broadcast.")
                        delay(gap)
                    }
                    // Re-derive the candidate — up to a full privacy buffer may have elapsed during the
                    // wait above, and the engine may now be ready to serve a different transaction than
                    // the one captured before the wait.
                    val freshStates = sdk.getMigrationTransferStates()
                    val freshEst = sdk.estimatedChainTip()
                    val freshCandidate = engineBroadcastCandidate(freshStates, freshEst) ?: earliestUnsent(freshStates)
                    return attemptBroadcast(sdk, accountKeyId, freshCandidate)
                } finally {
                    closeable.resume()
                    migrationLog("MigrationDriveOnce: resumed foreground sync (SDK gate now governs).")
                }
            } else {
                // Quiet-gap-only defer (nothing running to pause, or the worker caller, or genuinely
                // backgrounded) — unchanged from today's behavior.
                val deferDelay = if (prepFastTrack) PREP_FAST_TRACK_REARM else sdk.privacySyncBufferDuration()
                MigrationScheduler(applicationContext).schedule(accountKeyId, deferDelay)
                migrationLog("MigrationDriveOnce: deferring broadcast $deferDelay — a sync source is live or the quiet gap is unmet.")
                return DriveOnceResult.ReArmed(deferDelay)
            }
        }
        return attemptBroadcast(sdk, accountKeyId, nextCandidate)
    }

    /**
     * Moved verbatim (including the exact reasoning comment) from
     * `MigrationProgressVM.quietGapRemaining` — the privacy quiet-gap wait shared by the
     * foreground-forced broadcast window above and the VM's own foreground broadcast pass.
     */
    private suspend fun quietGapRemaining(privacyBuffer: Duration): Duration {
        val last = lastNetworkActivity.get() ?: return Duration.ZERO
        val elapsed = (Clock.System.now().epochSeconds - last.epochSecond).seconds
        val remaining = privacyBuffer - elapsed
        return if (remaining.isPositive()) remaining else Duration.ZERO
    }

    @Suppress("ReturnCount")
    private suspend fun attemptBroadcast(
        sdk: OrchardMigrationSdk,
        accountKeyId: String,
        nextCandidate: MigrationTransferState?,
    ): DriveOnceResult {
        val snapshotBefore = sdk.snapshot()
        val useTor = isMigrationTorEnabledStorageProvider.get(accountKeyId)

        // Hard timeout around the whole broadcast attempt: a cold-bootstrapping Tor client can
        // hang the submit indefinitely (observed live: tx stuck in-flight 10+ minutes until the
        // WorkManager execution ceiling killed the worker and nothing re-armed). On timeout the
        // native call may still complete detached — a re-submit of the same tx is safely
        // classified as a duplicate by the SDK (F2 classifier + mined-height probe), so
        // re-arming for another attempt is correct.
        val outcome =
            withTimeoutOrNull(BROADCAST_ATTEMPT_TIMEOUT) {
                executeWithRetries {
                    sdk.executeNextPendingTransfer(NetworkPrivacyOptions(useTor = useTor), useEstimatedTip = true)
                }
            } ?: run {
                migrationLog("MigrationDriveOnce: broadcast attempt timed out after $BROADCAST_ATTEMPT_TIMEOUT — re-arming.")
                return DriveOnceResult.ReArmed(reArm(sdk, accountKeyId, floor = REARM_FLOOR))
            }
        return when (outcome) {
            is TransferAttemptOutcome.NothingDue -> {
                // The estimate raced ahead of the engine's own due check — re-arm normally.
                val delay = reArm(sdk, accountKeyId)
                migrationLog("MigrationDriveOnce: NothingDue — re-armed for the next window.")
                DriveOnceResult.ReArmed(delay)
            }

            is TransferAttemptOutcome.AwaitingProof -> {
                // Defensive: nextStep said Broadcast, so the engine had a proved transaction — a
                // proof can only have vanished through a concurrent reorg/rescan. Re-arm floored;
                // the next run re-asks the engine from scratch.
                migrationLog("MigrationDriveOnce: AwaitingProof for ${outcome.transferId} despite a Broadcast step — re-arming.")
                DriveOnceResult.ReArmed(reArm(sdk, accountKeyId, floor = REARM_FLOOR))
            }

            is TransferAttemptOutcome.Executed -> {
                handleExecuted(sdk, accountKeyId, outcome.result, snapshotBefore, sentWasPrep = nextCandidate?.isTransfer == false)
            }
        }
    }

    @Suppress("ReturnCount")
    private suspend fun handleExecuted(
        sdk: OrchardMigrationSdk,
        accountKeyId: String,
        result: TransferResult,
        snapshotBefore: LiveMigrationSnapshot?,
        sentWasPrep: Boolean,
    ): DriveOnceResult =
        when (result) {
            is TransferResult.Success -> {
                migrationLog("MigrationDriveOnce: sent — txId=${result.txId}")
                // Everything below reads the engine's post-send state live — there is no cache to
                // write through anymore (the banner reads the same live states).
                val snapshot = sdk.snapshot()
                if (sentWasPrep) {
                    // "Transfer 0 of 11 complete" after a note split confused users (the crossing
                    // count ignores splits) — splits announce their own progress.
                    migrationNotifier.notifyNoteSplitProgress(
                        accountKeyId,
                        completedSplits = snapshot?.preparations?.count { it.isSent } ?: 0,
                        totalSplits = snapshot?.preparations?.size ?: 0,
                    )
                }
                // Single post-send engine read for every decision below (review L5).
                val postStates = sdk.getMigrationTransferStates()
                val anyUnsent = postStates?.transfers?.any { !it.isSent } == true
                if (anyUnsent) {
                    // Prep fast-track: whole ready prep batches go back-to-back — no send spacing
                    // between preparations (one logical tree, in-pool, nothing to de-correlate).
                    // CROSSINGS take the opposite rule: never two sends closer than the privacy
                    // buffer, even in catch-up (a starved worker once fired 5 overdue crossings in
                    // ~51 s — grid spacing means nothing if catch-up collapses it into one
                    // network-timing cluster). The non-fast-track case delegates to reArm — it
                    // targets the EARLIEST relevant moment across engine wake-ups and ALL unsent
                    // heights INCLUDING preparations; an ad-hoc crossing-only delay here used to
                    // sleep past inter-layer prep windows and compress the serial prep tail toward
                    // the crossings' anchor boundaries — the exact tx9 latency condition (review H1).
                    if (nextDueUnsentIsPreparation(postStates, sdk.estimatedChainTip())) {
                        MigrationScheduler(applicationContext).schedule(accountKeyId, PREP_FAST_TRACK_REARM)
                        migrationLog("MigrationDriveOnce: ready preparation next — chaining in $PREP_FAST_TRACK_REARM")
                        if (!sentWasPrep && snapshot != null) {
                            migrationNotifier.notifyTransferComplete(accountKeyId, snapshot.completedCount, snapshot.totalCount)
                        }
                        DriveOnceResult.ReArmed(PREP_FAST_TRACK_REARM)
                    } else {
                        val delay = reArm(sdk, accountKeyId, floor = sdk.privacySyncBufferDuration())
                        if (!sentWasPrep && snapshot != null) {
                            migrationNotifier.notifyTransferComplete(accountKeyId, snapshot.completedCount, snapshot.totalCount)
                        }
                        DriveOnceResult.ReArmed(delay)
                    }
                } else {
                    migrationNotifier.notifyMigrationComplete(accountKeyId)
                    // Everything sent — keep observing until the engine reports Complete (the
                    // completeRun stop). The next wake lands in waitingRun's completion-sweep
                    // branch, which runs a REAL sync so mining is actually observed (review M2).
                    val delay = reArm(sdk, accountKeyId, floor = sdk.privacySyncBufferDuration())
                    migrationLog("MigrationDriveOnce: all transfers sent — completion sweep armed.")
                    DriveOnceResult.ReArmed(delay)
                }
            }

            is TransferResult.NetworkError -> {
                // Retries already exhausted (or the failure was non-retryable) inside
                // executeWithRetries — settle into an error state now rather than asking
                // WorkManager for yet another attempt.
                migrationLog("MigrationDriveOnce: network error after retries, isTorFailure=${result.isTorFailure}")
                if (result.isTorFailure) {
                    // Persist a flag so app-open reconciliation (CheckMigrationRecoveryUseCase)
                    // routes back through the Sending screen instead of the generic
                    // manual-confirmation path, and surface a distinct notification.
                    pendingMigrationTorFailureStorageProvider.store(accountKeyId, true)
                    migrationNotifier.notifyMigrationTorFailure(accountKeyId)
                } else if (snapshotBefore?.nextPending != null) {
                    // Nothing else re-arms a future attempt for a non-retryable failure — the
                    // user must open the app and act, same as a missed/stalled window.
                    migrationNotifier.notifyManualConfirmationRequired(
                        accountKeyId,
                        snapshotBefore.nextPending!!.index + 1,
                        snapshotBefore.totalCount,
                    )
                }
                DriveOnceResult.Terminal
            }

            TransferResult.InvalidNote -> {
                // State is now RequiresAttention(InvalidTransfer) — notes were spent outside the
                // migration flow. On-launch reconciliation surfaces the prompt, but the user still
                // needs telling since nothing else runs meanwhile. No re-arm — terminal until the
                // user acts.
                migrationLog("MigrationDriveOnce: transfer invalid (note spent externally) — user action required on next open.")
                migrationNotifier.notifyMigrationPlanInvalid(accountKeyId)
                DriveOnceResult.Terminal
            }

            TransferResult.Expired -> {
                // State is now RequiresAttention(TransferExpired) — the anchor expired before the
                // broadcast could happen. Distinct copy from InvalidNote, same terminal handling.
                migrationLog("MigrationDriveOnce: transfer expired — user action required on next open.")
                migrationNotifier.notifyTransferExpired(accountKeyId)
                DriveOnceResult.Terminal
            }
        }

    /**
     * The engine wants [transferId] rebuilt (expired today; unprovable-anchor too once the engine
     * change request ships). A rebuild needs a fresh signature, so it is user-driven: surface the
     * attention notification and stop re-arming — the home banner and the app-open router route
     * the user into the invalid/reschedule screen, and recovery re-arms the chain afterwards.
     */
    private suspend fun rebuildRun(sdk: OrchardMigrationSdk, accountKeyId: String, transferId: Long) {
        val snapshot = sdk.snapshot()
        migrationLog("MigrationDriveOnce: engine requests Rebuild{$transferId} — user-driven reschedule required.")
        migrationNotifier.notifyRescheduleRequired(
            accountKeyId,
            (snapshot?.nextPending?.index?.plus(1)) ?: 1,
            snapshot?.totalCount ?: 0,
        )
    }

    /**
     * The engine says the WHOLE plan is dead (past the committed replan threshold — see
     * [MigrationAdvanceStep.Replan]'s doc), not just one transfer. Unlike [rebuildRun] there is no
     * single [transferId][MigrationAdvanceStep.Rebuild.transferId] to point at; the entire plan
     * needs a user-driven re-plan/reschedule. Same terminal handling as a rebuild: surface the
     * attention notification and stop re-arming — the home banner and the app-open router take it
     * from here.
     */
    private suspend fun replanRun(sdk: OrchardMigrationSdk, accountKeyId: String) {
        val snapshot = sdk.snapshot()
        migrationLog("MigrationDriveOnce: engine requests Replan — the whole plan is dead, user-driven reschedule required.")
        migrationNotifier.notifyRescheduleRequired(
            accountKeyId,
            (snapshot?.nextPending?.index?.plus(1)) ?: 1,
            snapshot?.totalCount ?: 0,
        )
    }

    /** All transactions mined — nothing left to fold anywhere; just stop the chain. */
    private suspend fun completeRun(accountKeyId: String) {
        migrationLog("MigrationDriveOnce: migration complete — stopping the work chain. (account=$accountKeyId)")
    }

    /**
     * TODO(remove: engine UnprovableAnchor): the SDK synthesizes
     * [MigrationBlocker.UNPROVABLE_ANCHOR] from the backend's late-dependency guard until the
     * engine change request ships — the engine will then emit `Rebuild` for it and this surfacing
     * collapses into [rebuildRun]. Until then, notify here so the user learns the plan needs a
     * reschedule without waiting for an app open (the home banner shows the same attention state).
     */
    private suspend fun surfaceUnprovableBlocker(sdk: OrchardMigrationSdk, accountKeyId: String, states: MigrationTransferStates?) {
        val stuck = states?.transfers?.firstOrNull { it.blocker == MigrationBlocker.UNPROVABLE_ANCHOR } ?: return
        val snapshot = sdk.snapshot()
        migrationLog("MigrationDriveOnce: transfer ${stuck.id} blocked on an unprovable anchor — user-driven reschedule required.")
        migrationNotifier.notifyRescheduleRequired(
            accountKeyId,
            (snapshot?.nextPending?.index?.plus(1)) ?: 1,
            snapshot?.totalCount ?: 0,
        )
    }

    /**
     * The "when?" half of the loop: one future run at the earliest relevant moment — the engine's
     * next sync wake-up ([OrchardMigrationSdk.syncWakeupSchedule]) or the next unsent
     * transaction's scheduled height, whichever comes first, projected height→wall-clock at the
     * measured block rate. Falls back to a flat cadence when neither is available. Returns the
     * concrete delay it armed, so the caller (`run`) can report [DriveOnceResult.ReArmed] with it.
     */
    private suspend fun reArm(sdk: OrchardMigrationSdk, accountKeyId: String, floor: Duration = Duration.ZERO): Duration {
        val states = sdk.getMigrationTransferStates()
        val wakeups = sdk.syncWakeupSchedule()
        val est = sdk.estimatedChainTip()
        val delay =
            nextWake(
                states,
                wakeups,
                est,
                sdk.estimatedSecondsPerBlock(),
                lastActivityEpochSeconds = lastNetworkActivity.get()?.epochSecond,
                privacyBufferSeconds = sdk.privacySyncBufferDuration().inWholeSeconds,
                nowEpochSeconds = nowEpochSeconds(),
            )
        val armed = maxOf(delay ?: migrationCadence(), floor)
        MigrationScheduler(applicationContext).schedule(accountKeyId, armed)
        // The full "why" of the chosen wake, so timing is diagnosable from logs alone: every
        // engine wake-up height, the next unsent due height, the tip estimate, and the floor.
        migrationLog(
            "MigrationDriveOnce: re-armed in $armed " +
                "(engineWakeups=${wakeups?.map { "${it.height}->${it.covers}" }}, " +
                "nextDue=${states?.transfers?.filter { !it.isSent }?.minOfOrNull { it.scheduledHeight }}, " +
                "estimatedTip=$est, floor=$floor" +
                if (delay == null) ", cadence fallback)" else ")"
        )
        return armed
    }

    companion object {
        /** Process-wide: worker and live driver share this one instance. */
        private val DRIVE_LOCK = Mutex()

        /**
         * How long a caller that lost the lock race waits before trying again. Deliberately
         * larger than a "normal" step's duration: when the winner is a forced broadcast window
         * (up to ~13 minutes: privacy buffer + broadcast timeout), a short retry here would have
         * the worker hammering `MigrationScheduler.schedule()` (WorkManager REPLACE + heartbeat
         * write + alarm re-arm) every few seconds for the whole window.
         */
        private val SKIP_RETRY_DELAY = 30.seconds
    }
}

/** Live snapshot of this SDK's engine states — the worker's plan view (never cached). */
private suspend fun OrchardMigrationSdk.snapshot(): LiveMigrationSnapshot? =
    getMigrationTransferStates()?.let {
        val est = estimatedChainTip()
        it.toSnapshot(
            estimatedTip = if (est >= 0) est else it.tipHeight,
            secondsPerBlock = estimatedSecondsPerBlock(),
            nowEpochSeconds = nowEpochSeconds(),
        )
    }

private val STATUS_READ_TIMEOUT = 2.seconds
internal val SYNC_TIMEOUT = 3.minutes
internal val REARM_FLOOR = 60.seconds
private val BROADCAST_ATTEMPT_TIMEOUT = 3.minutes

/**
 * Re-arm delay for the preparation fast-track: back-to-back scheduling for ready prep batches
 * (WorkManager dispatch latency is the only real gap) and the short retry when a fast-tracked
 * prep only lost its window to a live sync overlap.
 */
internal val PREP_FAST_TRACK_REARM = 1.seconds

internal const val MIN_REARM_SECONDS = 60L

/** Returns the current wall-clock time as epoch seconds. Extracted for testability. */
internal fun nowEpochSeconds(): Long = Clock.System.now().epochSeconds

/**
 * Fallback-only cadence: 5 min on testnet, 60 min on mainnet. Used ONLY when neither the engine's
 * wake-up schedule nor live transfer states (or the tip estimate) are available — every regular
 * wake is computed from the engine's own schedule instead (see [nextWake]).
 *
 * Uses [BuildConfig.FLAVOR] because the SDK's network id is not cheaply reachable from a static
 * context without a full OrchardMigrationSdk instance.
 */
internal fun migrationCadence(): Duration =
    if (BuildConfig.FLAVOR.contains("testnet", ignoreCase = true)) 5.minutes else 60.minutes

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
 * method on [MigrationDriveOnce]) specifically so it's unit-testable without Koin or WorkManager,
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
        val shouldRetry =
            current is TransferAttemptOutcome.Executed &&
                current.result is TransferResult.NetworkError &&
                (current.result as TransferResult.NetworkError).retryable
        if (!shouldRetry) break
    }
    return result
}

/**
 * What a broadcast run should do before calling the SDK's executeNextPendingTransfer.
 *
 * - [BroadcastPreflight.DEFER] — the foreground synchronizer is actively syncing, OR the privacy
 *   quiet gap since the last network activity has not yet elapsed. Engine untouched.
 * - [BroadcastPreflight.BROADCAST] — all sources are quiet and the gap has elapsed.
 */
internal enum class BroadcastPreflight { BROADCAST, DEFER }

/**
 * Pure preflight decision for a broadcast run — takes pre-computed scalars so it is unit-testable
 * without Koin, WorkManager or a real SDK.
 *
 * [lastNetworkActivityEpochSeconds] is null when no activity has ever been stamped (first run);
 * in that case the gap check is skipped and BROADCAST is returned.
 */
internal fun decideBroadcastPreflight(
    synchronizerSyncing: Boolean,
    nowEpochSeconds: Long,
    lastNetworkActivityEpochSeconds: Long?,
    privacyBufferSeconds: Long,
    prepFastTrack: Boolean = false,
): BroadcastPreflight {
    // Preparation fast-track (security split, 2026-07-30): note-split preparations are fully
    // shielded IN-POOL transactions — amounts and spend links hidden, natural recent anchor with
    // the same anonymity set as all ordinary Orchard traffic. The sync/broadcast de-correlation
    // ceremony exists for CROSSINGS (public amount + tiny migration-anchor anonymity set), and
    // during the prep phase no crossing exists to correlate against. So when the next due pending
    // transaction is a preparation, skip the quiet-gap and active-sync defers entirely; the
    // per-execution sync-XOR-broadcast rule still holds (this run never syncs).
    if (prepFastTrack) return BroadcastPreflight.BROADCAST
    if (synchronizerSyncing) return BroadcastPreflight.DEFER
    if (lastNetworkActivityEpochSeconds != null &&
        nowEpochSeconds - lastNetworkActivityEpochSeconds < privacyBufferSeconds
    ) {
        return BroadcastPreflight.DEFER
    }
    return BroadcastPreflight.BROADCAST
}

/**
 * The transaction the engine's `next_broadcastable` will actually serve: the first proved, unsent,
 * due transaction in VEC (id) order — the engine iterates its transactions vector, not the
 * schedule (documented in the engine change request §3). Null when nothing is broadcastable yet.
 */
internal fun engineBroadcastCandidate(states: MigrationTransferStates?, estimatedTip: Long): MigrationTransferState? =
    states
        ?.transfers
        ?.filter { !it.isSent && it.isProved && it.scheduledHeight <= estimatedTip }
        ?.minByOrNull { it.id }

/**
 * The earliest unsent transaction in schedule order (id as tiebreak) — the "what comes next"
 * display/pacing candidate when nothing is broadcastable yet.
 */
internal fun earliestUnsent(states: MigrationTransferStates?): MigrationTransferState? =
    states
        ?.transfers
        ?.filter { !it.isSent }
        ?.sortedWith(compareBy({ it.scheduledHeight }, { it.id }))
        ?.firstOrNull()

/**
 * True when the earliest unsent transaction is a PREPARATION that is already proved and due by
 * the estimated tip — the trigger for the preparation fast-track (see [decideBroadcastPreflight])
 * and for immediate re-chaining after a prep broadcast (no send spacing: the prep tree is one
 * logical unit; within a layer there is no one-at-a-time requirement).
 */
internal fun nextDueUnsentIsPreparation(states: MigrationTransferStates?, estimatedTip: Long): Boolean {
    val next = earliestUnsent(states) ?: return false
    return !next.isTransfer && next.isProved && next.scheduledHeight <= estimatedTip
}

/**
 * Estimated-tip broadcast acceleration predicate: a proved, unsent, non-stuck transaction whose
 * scheduled height the estimated tip has already crossed. The engine's own `nextStep` cannot see
 * past the scanned tip; this is the app-side bridge that keeps a backgrounded wallet broadcasting
 * on time (the actual send still re-verifies through the engine).
 */
internal fun broadcastDueByEstimate(states: MigrationTransferStates, estimatedTip: Long): Boolean {
    if (estimatedTip < 0L) return false
    return states.transfers.any {
        !it.isSent &&
            it.isProved &&
            it.blocker != MigrationBlocker.UNPROVABLE_ANCHOR &&
            it.scheduledHeight <= estimatedTip
    }
}

/**
 * What a genuine engine `Waiting` verdict resolves to (nextStep is Broadcast-authoritative, so a
 * due broadcast never reaches this decision — see [MigrationDriveOnce] `waitingRun`).
 */
internal enum class WaitingDisposition { COMPLETION_SWEEP, SURFACE_UNPROVABLE, RE_ARM }

internal fun waitingDisposition(allSent: Boolean, hasUnprovableBlocker: Boolean): WaitingDisposition =
    when {
        allSent -> WaitingDisposition.COMPLETION_SWEEP
        hasUnprovableBlocker -> WaitingDisposition.SURFACE_UNPROVABLE
        else -> WaitingDisposition.RE_ARM
    }

/**
 * The engine-side "when?" projection: the earliest relevant future height — the engine's next
 * sync wake-up or the next unsent transaction's scheduled height — converted to a wall-clock
 * delay at the measured block rate, floored at [MIN_REARM_SECONDS] (WorkManager slack / hot-loop
 * guard).
 *
 * Wake-ups covering ONLY unprovable-anchor transactions are excluded: the engine keeps emitting
 * immediate wake-ups for them forever (engine change request, GAP 2) and syncing can never produce
 * their proof — honoring them would hot-loop the worker at the floor delay. Returns `null` (cadence
 * fallback) when the tip estimate is unavailable or nothing relevant remains.
 *
 * Engine-only — does not know about the app's privacy quiet gap; see [nextWake], which folds this
 * in with the gap term and is what callers should use.
 */
internal fun computeEngineWakeDelay(
    states: MigrationTransferStates?,
    wakeups: List<MigrationSyncWakeup>?,
    est: Long,
    secondsPerBlock: Long,
): Duration? {
    if (est < 0L) return null
    val unprovable =
        states
            ?.transfers
            ?.filter { it.blocker == MigrationBlocker.UNPROVABLE_ANCHOR }
            ?.map { it.id }
            ?.toSet()
            .orEmpty()
    val nextWakeHeight =
        wakeups
            ?.filter { wakeup -> wakeup.covers.any { it !in unprovable } }
            ?.minOfOrNull { it.height }
    val nextDueHeight =
        states
            ?.transfers
            ?.filter { !it.isSent && it.id !in unprovable }
            ?.minOfOrNull { it.scheduledHeight }
    val target = listOfNotNull(nextWakeHeight, nextDueHeight).minOrNull() ?: return null
    return ((target - est).coerceAtLeast(0L) * secondsPerBlock)
        .coerceAtLeast(MIN_REARM_SECONDS)
        .seconds
}

/**
 * The single re-arm source of truth (spec §5): `min(engine schedule, app privacy-gap expiry)`.
 * The gap is an app concept the clock-free engine cannot express — a proved, unsent transaction
 * that is already due by estimate is broadcast-ready, but if we are inside the post-sync/
 * post-broadcast quiet window, the earliest it can actually go out is `quietUntil`. Re-arming to
 * the engine height alone would ignore that wait; re-arming to the gap alone would ignore a
 * nearer engine wake (e.g. a cheaper prove). Do NOT sync while only the gap is pending — that
 * would reset it and starve the due broadcast (spec §4).
 */
internal fun nextWake(
    states: MigrationTransferStates?,
    wakeups: List<MigrationSyncWakeup>?,
    est: Long,
    secondsPerBlock: Long,
    lastActivityEpochSeconds: Long?,
    privacyBufferSeconds: Long,
    nowEpochSeconds: Long,
): Duration? {
    val broadcastReadyGapped = states != null && broadcastDueByEstimate(states, est)
    val gapDelay =
        if (broadcastReadyGapped && lastActivityEpochSeconds != null) {
            val quietUntil = lastActivityEpochSeconds + privacyBufferSeconds
            (quietUntil - nowEpochSeconds).coerceAtLeast(0L).seconds
        } else {
            null
        }
    // When the gap term applies, it is the precise re-arm for the already-due, broadcast-ready
    // transfer(s) — exclude them from the engine's due-height floor (computeEngineWakeDelay floors
    // an already-due height at MIN_REARM_SECONDS, which could otherwise beat a longer, more
    // precise gap wait and starve it, spec §4).
    val engineStates =
        if (gapDelay != null && states != null) {
            states.copy(
                transfers =
                    states.transfers.filterNot {
                        !it.isSent && it.isProved && it.blocker != MigrationBlocker.UNPROVABLE_ANCHOR && it.scheduledHeight <= est
                    }
            )
        } else {
            states
        }
    val engineDelay = computeEngineWakeDelay(engineStates, wakeups, est, secondsPerBlock)
    return listOfNotNull(engineDelay, gapDelay).minOrNull()
}
