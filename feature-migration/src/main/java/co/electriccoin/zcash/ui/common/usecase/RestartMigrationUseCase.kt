package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.model.toStorageKeyId
import co.electriccoin.zcash.ui.common.provider.MigrationNotifier
import co.electriccoin.zcash.ui.common.provider.PendingMigrationTorFailureStorageProvider
import co.electriccoin.zcash.ui.common.repository.PendingKeystoneMigrationPcztsRepository
import co.electriccoin.zcash.ui.common.repository.RestartMigrationScheduleRepository
import co.electriccoin.zcash.work.MigrationScheduler

/**
 * Resets the selected account's stuck migration for the user-facing "Restart Migration" flow.
 * Clears the persisted run and every piece of app-side state that references it, so the home
 * banner returns to a clean "Migrate now" and the user re-enters the normal migration flow. This
 * is the production promotion of the former debug-only restart orchestration (see
 * `MigrationDebugActionsImpl.restartMigration()`, which now delegates here); idempotent and safe
 * when nothing is scheduled.
 */
class RestartMigrationUseCase(
    private val accountDataSource: AccountDataSource,
    private val getOrchardMigrationSdk: GetOrchardMigrationSdkUseCase,
    private val migrationScheduler: MigrationScheduler,
    private val pendingMigrationTorFailureStorageProvider: PendingMigrationTorFailureStorageProvider,
    private val restartMigrationScheduleRepository: RestartMigrationScheduleRepository,
    private val pendingKeystoneMigrationPcztsRepository: PendingKeystoneMigrationPcztsRepository,
    private val migrationNotifier: MigrationNotifier,
) {
    suspend operator fun invoke() {
        val accountKeyId =
            accountDataSource
                .getSelectedAccount()
                .sdkAccount.accountUuid
                .toStorageKeyId()
        // Interim: clearMigration() marks the run Failed. Swap to deleteMigration() (→ NotStarted)
        // once the SDK primitive lands — see spec Phase A.
        getOrchardMigrationSdk().clearMigration()
        // Cancel the self-rechaining background worker chain for a run that no longer exists.
        migrationScheduler.cancel(accountKeyId)
        // A leftover Tor-failure flag would keep routing launches into the Sending recovery screen.
        pendingMigrationTorFailureStorageProvider.store(accountKeyId, false)
        // An unconsumed restart schedule would otherwise be reused in place of a fresh proposal.
        restartMigrationScheduleRepository.consume(accountKeyId)
        // A pending Keystone sign/scan hand-off points at the deleted run.
        pendingKeystoneMigrationPcztsRepository.clear()
        // Dismiss any migration notification whose tap routes into the deleted run.
        migrationNotifier.cancel(accountKeyId)
    }
}
