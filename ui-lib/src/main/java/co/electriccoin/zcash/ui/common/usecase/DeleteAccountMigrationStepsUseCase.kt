package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.common.provider.MigrationNotifier
import co.electriccoin.zcash.ui.common.provider.MigrationShiftCounterStorageProvider
import co.electriccoin.zcash.ui.common.provider.PendingMigrationTorFailureStorageProvider
import co.electriccoin.zcash.ui.common.repository.MigrationPlanRepository
import co.electriccoin.zcash.ui.common.repository.PendingKeystoneMigrationPcztsRepository
import co.electriccoin.zcash.ui.common.repository.PendingMigrationScheduleRepository
import co.electriccoin.zcash.ui.common.repository.RestartMigrationScheduleRepository
import co.electriccoin.zcash.work.MigrationScheduler
import co.electriccoin.zcash.work.MigrationSyncScheduler

/**
 * The single seam for stopping an account's migration when that account's migration can never
 * proceed again: the account itself is being deleted (Keystone disconnect), the whole wallet is
 * being reset, or a debug wipe re-tests the flow from scratch. Every step is keyed by
 * [accountKeyId] (the hex storage key derived from the account's UUID via
 * [co.electriccoin.zcash.ui.common.model.toStorageKeyId]), idempotent, and safe to run for an
 * account that is not selected or has already been deleted.
 *
 * Without this the leftovers keep acting on a migration that no longer exists: zombie WorkManager
 * jobs whose account lookup fails forever, a stale app-side plan that blocks
 * `GetHomeMessageUseCase.migrationMessageFor`'s `plan == null` fallback, a Tor-failure flag that
 * routes every launch into the Sending recovery screen, a shift counter that escalates a fresh
 * plan's first transfer prematurely, an unconsumed restart schedule silently used in place of a
 * fresh proposal, and a notification whose tap routes into screens for a migration (or an account)
 * that is gone.
 *
 * Engine-side migration state is deliberately out of scope: the only API for it,
 * [cash.z.ecc.android.sdk.OrchardMigrationSdk.clearMigration], is documented DEBUG ONLY and
 * persists the run as *failed* (RequiresAttention rather than NotStarted). On both production call
 * sites the engine rows are unreachable anyway — the account row is deleted, or the whole wallet
 * database is wiped — so the debug call site keeps making that call itself, ahead of this one.
 */
class DeleteAccountMigrationStepsUseCase(
    private val migrationScheduler: MigrationScheduler,
    private val migrationSyncScheduler: MigrationSyncScheduler,
    private val migrationPlanRepository: MigrationPlanRepository,
    private val pendingMigrationScheduleRepository: PendingMigrationScheduleRepository,
    private val restartMigrationScheduleRepository: RestartMigrationScheduleRepository,
    private val pendingKeystoneMigrationPcztsRepository: PendingKeystoneMigrationPcztsRepository,
    private val pendingMigrationTorFailureStorageProvider: PendingMigrationTorFailureStorageProvider,
    private val migrationShiftCounterStorageProvider: MigrationShiftCounterStorageProvider,
    private val migrationNotifier: MigrationNotifier,
) {
    suspend operator fun invoke(accountKeyId: String) {
        migrationScheduler.cancel(accountKeyId)
        migrationSyncScheduler.cancel(accountKeyId)
        migrationPlanRepository.clear(accountKeyId)
        pendingMigrationTorFailureStorageProvider.store(accountKeyId, false)
        migrationShiftCounterStorageProvider.reset(accountKeyId)
        restartMigrationScheduleRepository.consume(accountKeyId)
        clearInMemoryHandoffsOwnedBy(accountKeyId)
        migrationNotifier.cancel(accountKeyId)
    }

    /**
     * Both hand-off slots hold at most one account's data at a time and expose only an
     * account-agnostic `clear()`, so they are wiped only after confirming this account owns the
     * stored value — otherwise stopping one account's migration would destroy another account's
     * in-flight Keystone sign/scan hand-off. Both reads used here are non-mutating on a mismatch.
     */
    private fun clearInMemoryHandoffsOwnedBy(accountKeyId: String) {
        if (pendingMigrationScheduleRepository.peek(accountKeyId) != null) {
            pendingMigrationScheduleRepository.clear()
        }
        if (pendingKeystoneMigrationPcztsRepository.get(accountKeyId) != null) {
            pendingKeystoneMigrationPcztsRepository.clear()
        }
    }
}
