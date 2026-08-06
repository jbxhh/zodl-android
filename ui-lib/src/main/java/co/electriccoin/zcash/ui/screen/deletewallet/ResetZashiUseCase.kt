package co.electriccoin.zcash.ui.screen.deletewallet

import cash.z.ecc.android.sdk.CloseableSynchronizer
import cash.z.ecc.android.sdk.WalletCoordinator
import co.electriccoin.zcash.preference.EncryptedPreferenceProvider
import co.electriccoin.zcash.preference.StandardPreferenceProvider
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.model.toStorageKeyId
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.repository.AddressBookRepository
import co.electriccoin.zcash.ui.common.repository.BiometricRepository
import co.electriccoin.zcash.ui.common.repository.BiometricRequest
import co.electriccoin.zcash.ui.common.repository.BiometricsCancelledException
import co.electriccoin.zcash.ui.common.repository.BiometricsFailureException
import co.electriccoin.zcash.ui.common.repository.FlexaRepository
import co.electriccoin.zcash.ui.common.repository.HomeMessageCacheRepository
import co.electriccoin.zcash.ui.common.repository.MetadataRepository
import co.electriccoin.zcash.ui.common.usecase.DeleteAccountMigrationStepsUseCase
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.work.MigrationSyncScheduler
import kotlinx.coroutines.flow.first
import okhttp3.internal.closeQuietly

class ResetZashiUseCase(
    private val walletCoordinator: WalletCoordinator,
    private val flexaRepository: FlexaRepository,
    private val synchronizerProvider: SynchronizerProvider,
    private val standardPreferenceProvider: StandardPreferenceProvider,
    private val encryptedPreferenceProvider: EncryptedPreferenceProvider,
    private val homeMessageCacheRepository: HomeMessageCacheRepository,
    private val biometricRepository: BiometricRepository,
    private val addressBookRepository: AddressBookRepository,
    private val metadataRepository: MetadataRepository,
    private val accountDataSource: AccountDataSource,
    private val deleteAccountMigrationSteps: DeleteAccountMigrationStepsUseCase,
    private val migrationSyncScheduler: MigrationSyncScheduler,
) {
    @Suppress("TooGenericExceptionCaught", "ThrowsCount")
    suspend operator fun invoke(keepFiles: Boolean) {
        try {
            requestBiometrics()
            cancelMigrationWork()
            flexaRepository.disconnect()
            deleteLocalFiles(keepFiles)
            closeSynchronizer()
            clearSDK()
            clearSharedPrefs()
            clearInMemoryData()
        } catch (_: BiometricsFailureException) {
            // do nothing
        } catch (_: BiometricsCancelledException) {
            // do nothing
        }
    }

    private suspend fun requestBiometrics() {
        biometricRepository.requestBiometrics(
            BiometricRequest(
                message =
                    stringRes(
                        R.string.authentication_system_ui_subtitle,
                        stringRes(R.string.authentication_use_case_delete_wallet)
                    )
            )
        )
    }

    /**
     * The reset wipes the migration plans (encrypted prefs) and the engine state (SDK data), but
     * WorkManager's own database, the in-memory hand-offs and the posted notifications survive —
     * without this, the armed Lane A/B jobs would outlive the wallet and hang forever waiting for
     * accounts that no longer exist. Runs BEFORE the synchronizer teardown so the per-account
     * migration state can still be derived from the live account list; reads the StateFlow's
     * current value rather than suspending, so a reset from a broken, never-initialized wallet
     * (accounts null) still proceeds — hence the unconditional Lane A cancel afterwards, whose
     * work id is wallet-global and therefore reachable without any account.
     */
    private suspend fun cancelMigrationWork() {
        accountDataSource.allAccounts.value.orEmpty().forEach { account ->
            deleteAccountMigrationSteps(account.sdkAccount.accountUuid.toStorageKeyId())
        }
        migrationSyncScheduler.cancel("all accounts (wallet reset)")
    }

    private suspend fun closeSynchronizer() {
        (synchronizerProvider.getSynchronizer() as CloseableSynchronizer).closeQuietly()
    }

    private fun deleteLocalFiles(keepFiles: Boolean) {
        if (!keepFiles) {
            addressBookRepository.delete()
            metadataRepository.delete()
        }
    }

    private suspend fun clearSDK() {
        walletCoordinator.deleteSdkDataFlow().first()
    }

    private suspend fun clearSharedPrefs() {
        standardPreferenceProvider().clearPreferences()
        encryptedPreferenceProvider().clearPreferences()
    }

    private fun clearInMemoryData() {
        homeMessageCacheRepository.reset()
    }
}
