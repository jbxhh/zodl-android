package co.electriccoin.zcash.work

import cash.z.ecc.android.sdk.OrchardMigrationSdk
import co.electriccoin.zcash.migration.migrationLog
import co.electriccoin.zcash.ui.common.repository.MigrationTransferStateRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The app-scoped fast path: while the process is alive, this drives the migration forward far
 * more precisely than WorkManager alone (no Doze/OEM-scheduler batching) — by calling the EXACT
 * SAME [MigrationDriveOnce.run] the WorkManager worker calls, so there is one decision path,
 * never two. [startIfNotRunning] is safe to call from every trigger point that knows a migration
 * might be active (post-commit, app-open) — it is a no-op if a loop for that account is already
 * running. The worker chain remains the durable background path (survives process death); this
 * driver adds nothing to correctness, only speed, while the app is alive.
 */
interface MigrationLiveDriver {
    fun startIfNotRunning(accountKeyId: String)
}

class MigrationLiveDriverImpl(
    private val migrationDriveOnce: MigrationDriveOnce,
    private val getOrchardMigrationSdk: suspend (String) -> OrchardMigrationSdk?,
    private val migrationTransferStateRepository: MigrationTransferStateRepository,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
) : MigrationLiveDriver {
    /**
     * Atomic per-account start-if-absent, race-free by construction: [ConcurrentHashMap] gives
     * an atomic `putIfAbsent`, and each job's own [Job.invokeOnCompletion] removes exactly the
     * entry it installed (compare-and-remove via the two-arg overload) — no separate suspend-
     * based lock needed. The job is LAZY and `putIfAbsent` happens BEFORE `.start()` (an earlier
     * draft called `.start()` first and only attached its own removal hook afterward, leaving a
     * window where a second, concurrent call could see the map still empty and start a genuinely
     * duplicate, concurrently-running loop for the same account). If we lose the `putIfAbsent`
     * race and the winner is already [Job.isCompleted] by the time we check, we retry
     * immediately (it is safe: the winner is gone, or about to be removed by its own hook); if
     * the winner is still active, this call is a no-op — see [startIfNotRunning] for why no
     * completion hook is attached in that case.
     */
    private val runningJobs = ConcurrentHashMap<String, Job>()

    override fun startIfNotRunning(accountKeyId: String) {
        val candidate = scope.launch(start = CoroutineStart.LAZY) { loop(accountKeyId) }
        val existing = runningJobs.putIfAbsent(accountKeyId, candidate)
        if (existing == null) {
            candidate.invokeOnCompletion { runningJobs.remove(accountKeyId, candidate) }
            candidate.start()
        } else {
            candidate.cancel()
            if (existing.isCompleted) {
                // Already gone by the time we looked — safe to retry immediately.
                startIfNotRunning(accountKeyId)
            } else {
                // A loop for this account is genuinely still active — this call is fully
                // satisfied by it: the running loop re-evaluates the engine's own state on every
                // iteration, so it will itself pick up whatever prompted this call. No completion
                // hook is attached here (a prior draft attached one unconditionally, which forced
                // one extra full loop run after every merely-coincident double call, even when the
                // active loop was going to reach a legitimate Terminal on its own) — if the active
                // loop stops before the account is actually done, the next natural trigger point
                // (app-open, post-commit) or the durable WorkManager chain calls
                // startIfNotRunning again.
                migrationLog("MigrationLiveDriver: already driving $accountKeyId — no-op.")
            }
        }
    }

    private suspend fun loop(accountKeyId: String) {
        migrationLog("MigrationLiveDriver: starting live loop for $accountKeyId")
        try {
            while (true) {
                val sdk =
                    getOrchardMigrationSdk(accountKeyId) ?: run {
                        migrationLog("MigrationLiveDriver: SDK unavailable for $accountKeyId — stopping.")
                        return
                    }
                val result =
                    migrationDriveOnce.run(
                        sdk,
                        accountKeyId,
                        allowForcedBroadcastWindow = true,
                        // true: the live driver has no execution-time ceiling (unlike the worker),
                        // so it is the only safe caller for a forced-broadcast-window wait that can
                        // run for nearly a full privacy buffer.
                        driveByLiveLoop = true,
                    )
                if (result !is DriveOnceResult.LockBusy) {
                    // A step actually ran (ReArmed) or the migration reached a terminal state —
                    // either way, publish one fresh read for every screen/use-case that used to
                    // poll OrchardMigrationSdk.getMigrationTransferStates() on its own: this is a
                    // read the driver's loop was already going to serialize through its own
                    // coroutine, not a second concurrent caller competing for the SDK's
                    // single-threaded DB I/O executor the way an independent screen poll did.
                    // Skipped on LockBusy — another caller is mid-step, nothing changed from THIS
                    // call's perspective, and the winning caller publishes when it finishes.
                    migrationTransferStateRepository.publish(accountKeyId, sdk.getMigrationTransferStates())
                }
                when (result) {
                    is DriveOnceResult.ReArmed -> {
                        // Floor a floorless re-arm value (nextWake's privacy-gap term can be
                        // exactly zero) so this in-process loop never spins tightly — WorkManager
                        // dispatch latency is the accidental brake for the worker path; this loop
                        // has no equivalent brake unless we supply one. EXCEPT already-deliberate
                        // short constants (result.respectAntiSpinFloor == false, e.g.
                        // PREP_FAST_TRACK_REARM) — flooring those defeats their whole purpose
                        // (chaining ready prep-batch broadcasts back-to-back within one tree).
                        val effectiveDelay =
                            if (result.respectAntiSpinFloor) {
                                maxOf(result.delay, MIN_REARM_SECONDS.seconds)
                            } else {
                                result.delay
                            }
                        delay(effectiveDelay)
                    }

                    is DriveOnceResult.LockBusy -> {
                        delay(result.retryDelay)
                    }

                    DriveOnceResult.Terminal -> {
                        migrationLog("MigrationLiveDriver: $accountKeyId reached a terminal state — stopping loop.")
                        return
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            migrationLog("MigrationLiveDriver: loop for $accountKeyId failed (transient) — will resume on next start.", e)
        }
    }
}
