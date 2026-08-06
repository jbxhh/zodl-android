package co.electriccoin.zcash.ui.common.usecase

import android.content.Context
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.common.provider.LastNetworkActivityStorageProvider
import co.electriccoin.zcash.ui.common.provider.MigrationNotifier
import co.electriccoin.zcash.ui.common.repository.MigrationPlanRepository
import co.electriccoin.zcash.work.MigrationScheduler
import co.electriccoin.zcash.work.MigrationSyncScheduler
import kotlin.time.Duration.Companion.seconds

/**
 * Called on every foreground Status.SYNCED transition while a migration plan is active.
 * Finalizes any transfers whose funding note became witnessed since the last sync, then
 * checks for invalidations — if any, notifies the user and cancels both background lanes
 * (Lane A sync heartbeat and Lane B execution). Always stamps the last-network-activity
 * timestamp so Lane B's privacy-buffer gap calculation is accurate — even on invalidation,
 * the sync itself still happened.
 */
class OnMigrationSyncCompletedUseCase(
    private val getOrchardMigrationSdk: GetOrchardMigrationSdkUseCase,
    private val lastNetworkActivity: LastNetworkActivityStorageProvider,
    private val migrationNotifier: MigrationNotifier,
    private val migrationScheduler: MigrationScheduler,
    private val migrationSyncScheduler: MigrationSyncScheduler,
    private val migrationPlanRepository: MigrationPlanRepository,
    private val context: Context,
) {
    suspend operator fun invoke(accountKeyId: String) {
        val sdk = runCatching { getOrchardMigrationSdk(accountKeyId) }.getOrNull() ?: return
        val proved = sdk.finalizeReadyTransfers()
        val invalidated = sdk.reconcileInvalidations()
        Twig.debug {
            "MIGRATION_DIAG ForegroundHook: SYNCED — proved=$proved, invalidated=$invalidated (account=$accountKeyId)"
        }
        if (invalidated) {
            migrationNotifier.notifyMigrationPlanInvalid(accountKeyId)
            migrationScheduler.cancel(accountKeyId)
            migrationSyncScheduler.cancel(accountKeyId)
            lastNetworkActivity.stampNow()
            return
        }
        // Lane revival: this hook fires exactly when the wallet is provably up and synced — the
        // one moment a dead self-rechaining lane (process killed mid-run, app update consumed the
        // restored job before the SDK was ready) can be safely re-armed. The app-open recovery
        // pass can race a cold start (SDK null); this cannot.
        if (migrationPlanRepository.load(accountKeyId) != null ||
            sdk.getMigrationState() is cash.z.ecc.android.sdk.MigrationState.InProgress
        ) {
            if (!isLaneAActiveInWorkManager(context)) {
                Twig.debug { "MIGRATION_DIAG ForegroundHook: Lane A absent with a live plan — re-arming." }
                migrationSyncScheduler.schedule(accountKeyId, 60.seconds)
            }
            // Lane B has NO other reviver: its re-arm only ever happens at the end of its own run
            // (and its due alarms don't survive a reinstall/update), so a package update mid-plan
            // silently kills all future broadcasts (observed live: the 9th transfer proved and
            // due, with no Lane B job left anywhere to send it).
            if (!isLaneBActiveInWorkManager(context, accountKeyId)) {
                Twig.debug { "MIGRATION_DIAG ForegroundHook: Lane B absent with a live plan — re-arming." }
                migrationScheduler.schedule(accountKeyId, 60.seconds)
            }
        }
        lastNetworkActivity.stampNow()
    }
}
