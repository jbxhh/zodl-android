package co.electriccoin.zcash.ui.common.usecase

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import cash.z.ecc.android.sdk.MigrationState
import cash.z.ecc.android.sdk.OrchardMigrationSdk
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.model.toStorageKeyId
import co.electriccoin.zcash.ui.common.provider.PendingMigrationTorFailureStorageProvider
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import co.electriccoin.zcash.ui.common.repository.MigrationPlanRepository
import co.electriccoin.zcash.ui.screen.home.HomeArgs
import co.electriccoin.zcash.ui.screen.migration.sending.MigrationSendingArgs
import co.electriccoin.zcash.work.MigrationSyncScheduler
import co.electriccoin.zcash.work.WorkIds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.seconds

/**
 * Single source of truth for migration re-entry routing on app launch/foreground — MainActivity's
 * onStart() and RootNavGraph's secretState-driven redirect both delegate here instead of calling
 * the SDK checks directly, so the two never drift out of sync with each other or with this
 * ordering. Cheap and idempotent (NavigationRouter dedupes identical commands).
 *
 * After Task 6 (remove app-open auto-navigation), the ONLY auto-navigation that fires here is the
 * Tor-failure branch: a pending background Tor failure routes to the Sending screen so that
 * MigrationSendingVM's init{} reproduces the exact condition, using its own existing routing to
 * resolve or re-surface the failure.
 *
 * All other migration states (RequiresAttention, ReadyToSend, Overdue, Complete) are now reachable
 * exclusively via the home banner + button (HomeVM.onMigrationMessageClick), preventing the
 * repeated screen hijacking on every launch during a healthy migration.
 *
 * The Lane A/B revival block and the stale-write-ahead-plan clear are NOT navigation — they are
 * retained unchanged.
 */
class CheckMigrationRecoveryUseCase(
    private val getOrchardMigrationSdk: GetOrchardMigrationSdkUseCase,
    private val persistableWalletProvider: PersistableWalletProvider,
    private val navigationRouter: NavigationRouter,
    private val migrationPlanRepository: MigrationPlanRepository,
    private val pendingMigrationTorFailureStorageProvider: PendingMigrationTorFailureStorageProvider,
    private val getSelectedWalletAccount: GetSelectedWalletAccountUseCase,
    private val migrationSyncScheduler: MigrationSyncScheduler,
    private val context: Context,
    /** Extracted for testability — production default checks WorkManager. */
    private val isLaneAActive: suspend () -> Boolean = { isLaneAActiveInWorkManager(context) },
    /** Lane B twin, same testability rationale. */
    private val isLaneBActive: suspend (String) -> Boolean = { isLaneBActiveInWorkManager(context, it) },
) {
    suspend operator fun invoke() {
        // Three independent triggers exist (MainActivity.onStart, RootNavGraph unlock, and any
        // future caller); without a throttle they cascade — each replaceAll builds a fresh Home
        // entry whose composition can re-trigger recovery, observed live as 7 redirects (and 3
        // duplicate catch-up shifts) within 8 seconds. One pass per window is enough: routing is
        // idempotent for the user and isSyncBlocked() protects sync regardless.
        val nowMs = android.os.SystemClock.elapsedRealtime()
        synchronized(CheckMigrationRecoveryUseCase) {
            if (nowMs - lastRunElapsedMs < RUN_THROTTLE_MS) {
                Twig.debug { "MIGRATION_DIAG MigrationRecovery: throttled (ran ${nowMs - lastRunElapsedMs}ms ago)" }
                return
            }
            lastRunElapsedMs = nowMs
        }
        // No wallet YET — on a cold start this fires before the synchronizer initializes, and
        // silently consuming the throttle window here left recovery permanently ineffective
        // (observed live: 1st call = SDK null + throttle stamped, 2nd call 3s later = throttled;
        // both lanes stayed dead after a reinstall). Un-stamp the throttle so the next trigger
        // (foreground/unlock/onStart all re-fire) gets a real attempt once the wallet is up.
        if (persistableWalletProvider.getPersistableWallet() == null) {
            synchronized(CheckMigrationRecoveryUseCase) { lastRunElapsedMs = 0L }
            Twig.debug { "MIGRATION_DIAG MigrationRecovery: SDK not ready — will retry on next trigger." }
            return
        }
        val sdk = getOrchardMigrationSdk()

        // (a) Lane A reconciliation — if a plan exists but the Lane A unique work is absent
        // (ENQUEUED or RUNNING), re-schedule it. This self-heals after process kill, device
        // reboot, or an app upgrade that cleared WorkManager state, without requiring the user to
        // re-enter the migration flow.
        // Gate on the ENGINE's state, not only the app-side plan cache: the cache can be lost
        // (observed live: repository empty while the engine held a run with 8/9 broadcast and the
        // last transfer proved) and the engine is the single source of truth — a live in-progress
        // migration must always have its lanes running.
        val engineInProgress = sdk.getMigrationState() is MigrationState.InProgress
        if (migrationPlanRepository.load() != null || engineInProgress) {
            val accountKeyId = getSelectedWalletAccount().sdkAccount.accountUuid.toStorageKeyId()
            if (!isLaneAActive()) {
                Twig.debug { "MIGRATION_DIAG MigrationRecovery: Lane A absent, re-scheduling." }
                // A short flat first arm: the schedule object carries no plan knowledge — the
                // worker's first run reads the live engine states and computes the precise
                // boundary-driven wake itself (see MigrationSyncWorker).
                migrationSyncScheduler.schedule(accountKeyId, 60.seconds)
            }
            // Lane B revival too — its re-arm only happens at the end of its own run and its due
            // alarms don't survive a package update, so an update mid-plan otherwise kills every
            // future broadcast (see OnMigrationSyncCompletedUseCase; duplicated here because the
            // SYNCED hook needs a synced foreground synchronizer, which a freshly relaunched app
            // may not reach for minutes).
            if (!isLaneBActive(accountKeyId)) {
                Twig.debug { "MIGRATION_DIAG MigrationRecovery: Lane B absent, re-scheduling." }
                co.electriccoin.zcash.work.MigrationScheduler(context).schedule(accountKeyId, 60.seconds)
            }
        }

        if (pendingMigrationTorFailureStorageProvider.get()) {
            // A background attempt failed specifically because of Tor — route through the Sending
            // screen first rather than straight to MigrationProgressArgs/MigrationTorFailureArgs:
            // MigrationSendingVM's init{} always attempts a send immediately on construction,
            // reproducing the exact condition that failed in the background using the current
            // migration Tor setting. If it fails again, MigrationSendingVM's own existing
            // sendOnce() logic already forwards to MigrationTorFailureArgs — no need to duplicate
            // that routing here.
            Twig.debug { "MIGRATION_DIAG MigrationRecovery: pending background Tor failure — redirecting to Sending." }
            navigationRouter.replaceAll(HomeArgs, MigrationSendingArgs)
        } else {
            val migrationState = sdk.getMigrationState()
            if (migrationState == MigrationState.NotStarted && migrationPlanRepository.load() != null) {
                // A stale write-ahead plan: MigrationReviewVM.confirmAutomatic persists the plan just
                // before the irreversible SDK commit (see FinalizeMigrationScheduleUseCase.persistPlan),
                // so if that commit never actually happened — submitNoteSplit()/signAndStoreMigrationSchedule()
                // threw before commit_preparation, leaving the SDK NotStarted — the plan is left behind
                // pointing at a migration that doesn't exist. The SDK state is authoritative, so discard
                // it rather than letting the home banner offer to "resume" a phantom migration. (An
                // actually-committed migration reports InProgress here, not NotStarted, and is left
                // untouched — its saved plan is real and drives the progress screen.)
                Twig.debug { "MIGRATION_DIAG MigrationRecovery: stale write-ahead plan, SDK NotStarted — clearing." }
                migrationPlanRepository.clear()
            }
        }
    }

    companion object {
        private const val RUN_THROTTLE_MS = 10_000L

        @Volatile
        private var lastRunElapsedMs = Long.MIN_VALUE / 2

        /** Tests run in one JVM — reset the shared throttle between them. */
        internal fun resetRunThrottleForTests() {
            lastRunElapsedMs = Long.MIN_VALUE / 2
        }
    }
}

/**
 * Production implementation of the Lane A active check — reads WorkManager unique work state.
 * Extracted so [CheckMigrationRecoveryUseCase] tests can supply a lambda stub instead of
 * needing a real WorkManager context (unit tests can't initialise WorkManager).
 */
internal suspend fun isLaneAActiveInWorkManager(context: Context): Boolean =
    withContext(Dispatchers.IO) {
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(WorkIds.WORK_ID_MIGRATION_SYNC)
            .get()
    }.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }

/** Lane B (broadcast) twin of [isLaneAActiveInWorkManager] — per-account unique work name. */
internal suspend fun isLaneBActiveInWorkManager(context: Context, accountKeyId: String): Boolean =
    withContext(Dispatchers.IO) {
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(co.electriccoin.zcash.work.MigrationScheduler.workId(accountKeyId))
            .get()
    }.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }
