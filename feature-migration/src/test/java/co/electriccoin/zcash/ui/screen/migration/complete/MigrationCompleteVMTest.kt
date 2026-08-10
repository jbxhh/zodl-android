package co.electriccoin.zcash.ui.screen.migration.complete

import androidx.navigation.NavBackStackEntry
import cash.z.ecc.android.sdk.MigrationSummary
import cash.z.ecc.android.sdk.OrchardMigrationSdk
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.UnifiedSpendingKey
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.BaseNavigationCommand
import co.electriccoin.zcash.ui.NavigationCommand
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSource
import co.electriccoin.zcash.ui.common.datasource.ZashiSpendingKeyDataSource
import co.electriccoin.zcash.ui.common.model.KeystoneAccount
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.ZashiAccount
import co.electriccoin.zcash.ui.common.model.migration.MIGRATION_DUST_THRESHOLD_ZATOSHI
import co.electriccoin.zcash.ui.common.provider.HasLockedOrchardDustStorageProvider
import co.electriccoin.zcash.ui.common.provider.HasSeenMigrationCompleteStorageProvider
import co.electriccoin.zcash.ui.common.provider.MigrationNotifier
import co.electriccoin.zcash.ui.common.repository.BiometricRepository
import co.electriccoin.zcash.ui.common.repository.KeystoneProposalRepository
import co.electriccoin.zcash.ui.common.usecase.ErrorMapperUseCase
import co.electriccoin.zcash.ui.common.usecase.GetOrchardBalanceUseCase
import co.electriccoin.zcash.ui.common.usecase.GetOrchardMigrationSdkUseCase
import co.electriccoin.zcash.ui.common.usecase.GetSelectedWalletAccountUseCase
import co.electriccoin.zcash.ui.screen.migration.success.MigrationSuccessArgs
import co.electriccoin.zcash.ui.screen.signkeystonetransaction.SignKeystoneTransactionArgs
import co.electriccoin.zcash.work.MigrationScheduler
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.reflect.KClass
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class MigrationCompleteVMTest {
    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun keystoneAccountWithResidualBalanceClearsPlanInsteadOfMarkingSeen() =
        runTest {
            val seen = mockk<HasSeenMigrationCompleteStorageProvider>(relaxed = true)
            val router = FakeNavigationRouter()
            val vm =
                vm(
                    seen = seen,
                    account = mockk<KeystoneAccount>(relaxed = true),
                    orchardBalanceZatoshi = 500_000L,
                    router = router,
                )

            vm.state.value // force lazy init to run (StateFlow combine below reads loadLce)
            advanceUntilIdle()
            invokeOnDone(vm)
            advanceUntilIdle()

            coVerify(exactly = 0) { seen.store(true) }
            assertEquals(1, router.backToRootCount)
        }

    @Test
    fun keystoneAccountWithZeroResidualBalanceMarksSeenInsteadOfClearing() =
        runTest {
            val seen = mockk<HasSeenMigrationCompleteStorageProvider>(relaxed = true)
            val router = FakeNavigationRouter()
            val vm =
                vm(
                    seen = seen,
                    account = mockk<KeystoneAccount>(relaxed = true),
                    orchardBalanceZatoshi = 0L,
                    router = router,
                )

            advanceUntilIdle()
            invokeOnDone(vm)
            advanceUntilIdle()

            coVerify(exactly = 1) { seen.store(true) }
            assertEquals(1, router.backToRootCount)
        }

    @Test
    fun nonKeystoneAccountWithResidualBalanceMarksSeenInsteadOfClearing() =
        runTest {
            // Scope: hot-wallet multi-round continuation is deferred, so a non-Keystone account always
            // takes the terminal path regardless of residual balance.
            val seen = mockk<HasSeenMigrationCompleteStorageProvider>(relaxed = true)
            val router = FakeNavigationRouter()
            val vm =
                vm(
                    seen = seen,
                    account = mockk<ZashiAccount>(relaxed = true),
                    orchardBalanceZatoshi = 500_000L,
                    router = router,
                )

            advanceUntilIdle()
            invokeOnDone(vm)
            advanceUntilIdle()

            coVerify(exactly = 1) { seen.store(true) }
            assertEquals(1, router.backToRootCount)
        }

    @Test
    fun keystoneAccountWithDustResidualBelowThresholdMarksSeenInsteadOfClearing() =
        runTest {
            // Pins the fix for Task 3's bug: a bare `> 0L` check used to treat *any* nonzero residual
            // as "more rounds needed", incorrectly clearing the plan even for a genuinely-dust residual
            // well below the real completion threshold.
            val seen = mockk<HasSeenMigrationCompleteStorageProvider>(relaxed = true)
            val router = FakeNavigationRouter()
            val vm =
                vm(
                    seen = seen,
                    account = mockk<KeystoneAccount>(relaxed = true),
                    orchardBalanceZatoshi = MIGRATION_DUST_THRESHOLD_ZATOSHI - 1L,
                    router = router,
                )

            advanceUntilIdle()
            invokeOnDone(vm)
            advanceUntilIdle()

            coVerify(exactly = 1) { seen.store(true) }
            assertEquals(1, router.backToRootCount)
        }

    @Test
    fun keystoneAccountWithResidualExactlyAtThresholdMarksSeenInsteadOfClearing() =
        runTest {
            // Boundary check: exactly-at-threshold is still "done" (comparison is strictly `>`).
            val seen = mockk<HasSeenMigrationCompleteStorageProvider>(relaxed = true)
            val router = FakeNavigationRouter()
            val vm =
                vm(
                    seen = seen,
                    account = mockk<KeystoneAccount>(relaxed = true),
                    orchardBalanceZatoshi = MIGRATION_DUST_THRESHOLD_ZATOSHI,
                    router = router,
                )

            advanceUntilIdle()
            invokeOnDone(vm)
            advanceUntilIdle()

            coVerify(exactly = 1) { seen.store(true) }
            assertEquals(1, router.backToRootCount)
        }

    @Test
    fun migrateAnywayForZashiAccountSignsAndSubmitsProposalDirectly() =
        runTest {
            val router = FakeNavigationRouter()
            val proposalDataSource = mockk<ProposalDataSource>(relaxed = true)
            val proposal = mockk<Proposal>(relaxed = true)
            val usk = mockk<UnifiedSpendingKey>(relaxed = true)
            val sdk =
                mockk<OrchardMigrationSdk> {
                    coEvery { proposeImmediateMigration() } returns proposal
                    coEvery { getMigrationSummary() } returns null
                }
            coEvery { proposalDataSource.submitTransaction(proposal, usk) } returns SubmitResult.Success(listOf("txid"))

            val vm =
                vm(
                    account = mockk<ZashiAccount>(relaxed = true),
                    orchardBalanceZatoshi = 500_000L,
                    router = router,
                    getOrchardMigrationSdk = mockk { coEvery { this@mockk() } returns sdk },
                    zashiSpendingKeyDataSource = mockk { coEvery { getZashiSpendingKey() } returns usk },
                    proposalDataSource = proposalDataSource,
                )

            advanceUntilIdle()
            invokeOnMigrateAnyway(vm)
            advanceUntilIdle()

            coVerify(exactly = 1) { proposalDataSource.submitTransaction(proposal, usk) }
            assertEquals(listOf<Any>(MigrationSuccessArgs("txid")), router.forwardedRoutes)
        }

    @Test
    fun migrateAnywayForKeystoneAccountBuildsPcztAndNavigatesToSign() =
        runTest {
            val router = FakeNavigationRouter()
            val proposal = mockk<Proposal>(relaxed = true)
            val sdk =
                mockk<OrchardMigrationSdk> {
                    coEvery { proposeImmediateMigration() } returns proposal
                    coEvery { getMigrationSummary() } returns null
                }
            val keystoneProposalRepository = mockk<KeystoneProposalRepository>(relaxed = true)
            val proposalDataSource = mockk<ProposalDataSource>(relaxed = true)

            val vm =
                vm(
                    account = mockk<KeystoneAccount>(relaxed = true),
                    orchardBalanceZatoshi = 500_000L,
                    router = router,
                    getOrchardMigrationSdk = mockk { coEvery { this@mockk() } returns sdk },
                    keystoneProposalRepository = keystoneProposalRepository,
                    proposalDataSource = proposalDataSource,
                )

            advanceUntilIdle()
            invokeOnMigrateAnyway(vm)
            advanceUntilIdle()

            coVerify(exactly = 1) { keystoneProposalRepository.setMigrationSweepProposal(proposal, Zatoshi(500_000L)) }
            coVerify(exactly = 1) { keystoneProposalRepository.createPCZTFromProposal() }
            coVerify(exactly = 0) { proposalDataSource.submitTransaction(any<Proposal>(), any<UnifiedSpendingKey>()) }
            assertEquals(1, router.forwardedRoutes.count { it == SignKeystoneTransactionArgs })
        }

    @Test
    fun summaryIsReadFromEngineSdkNotTheAppSidePlan() =
        runTest {
            // The Migration Complete summary (amount migrated / transfer count / duration) must come
            // from the engine's persisted migration data via the SDK, not the app-side plan (cleared on
            // completion). Pins that the VM queries the SDK's getMigrationSummary() and never reads the
            // now-obsolete plan for the summary.
            val summary =
                MigrationSummary(
                    totalMigratedZatoshi = 9_779_000_000L,
                    transferCount = 10,
                    firstMinedEpochSeconds = 1_785_281_502L,
                    lastMinedEpochSeconds = 1_785_283_542L,
                )
            val sdk =
                mockk<OrchardMigrationSdk> {
                    coEvery { getMigrationSummary() } returns summary
                }
            val vm =
                vm(
                    account = mockk<KeystoneAccount>(relaxed = true),
                    orchardBalanceZatoshi = 500_000L,
                    router = FakeNavigationRouter(),
                    getOrchardMigrationSdk = mockk { coEvery { this@mockk() } returns sdk },
                )

            vm.state.value // force lazy init to run (loadLce.execute reads the SDK summary)
            advanceUntilIdle()

            coVerify(exactly = 1) { sdk.getMigrationSummary() }
        }

    private fun invokeOnDone(vm: MigrationCompleteVM) {
        val onDone = MigrationCompleteVM::class.java.getDeclaredMethod("onDone")
        onDone.isAccessible = true
        onDone.invoke(vm)
    }

    private fun invokeOnMigrateAnyway(vm: MigrationCompleteVM) {
        val onMigrateAnyway = MigrationCompleteVM::class.java.getDeclaredMethod("onMigrateAnyway")
        onMigrateAnyway.isAccessible = true
        onMigrateAnyway.invoke(vm)
    }

    @Suppress("LongParameterList")
    private fun vm(
        seen: HasSeenMigrationCompleteStorageProvider = mockk(relaxed = true),
        account: WalletAccount,
        orchardBalanceZatoshi: Long,
        router: FakeNavigationRouter,
        // migrationDustThresholdZatoshi() explicitly pinned to the real threshold constant — a
        // relaxed mock's default would answer 0L instead, breaking the boundary tests below that
        // set orchardBalanceZatoshi relative to MIGRATION_DUST_THRESHOLD_ZATOSHI.
        getOrchardMigrationSdk: GetOrchardMigrationSdkUseCase =
            mockk {
                coEvery { this@mockk() } returns
                    mockk(relaxed = true) {
                        coEvery { migrationDustThresholdZatoshi() } returns MIGRATION_DUST_THRESHOLD_ZATOSHI
                    }
            },
        zashiSpendingKeyDataSource: ZashiSpendingKeyDataSource = mockk(relaxed = true),
        biometricRepository: BiometricRepository = mockk(relaxed = true),
        proposalDataSource: ProposalDataSource = mockk(relaxed = true),
        keystoneProposalRepository: KeystoneProposalRepository = mockk(relaxed = true),
        migrationScheduler: MigrationScheduler = mockk(relaxed = true),
        migrationNotifier: MigrationNotifier = mockk(relaxed = true),
    ) = MigrationCompleteVM(
        getOrchardBalance =
            mockk<GetOrchardBalanceUseCase> {
                coEvery { this@mockk() } returns Zatoshi(orchardBalanceZatoshi)
            },
        hasSeenMigrationCompleteStorageProvider = seen,
        hasLockedOrchardDustStorageProvider = mockk<HasLockedOrchardDustStorageProvider>(relaxed = true),
        getSelectedWalletAccount =
            mockk<GetSelectedWalletAccountUseCase> {
                coEvery { this@mockk() } returns account
            },
        navigationRouter = router,
        errorStateMapper = mockk<ErrorMapperUseCase>(relaxed = true),
        getOrchardMigrationSdk = getOrchardMigrationSdk,
        lockOrchardBalance = mockk(relaxed = true),
        zashiSpendingKeyDataSource = zashiSpendingKeyDataSource,
        biometricRepository = biometricRepository,
        proposalDataSource = proposalDataSource,
        keystoneProposalRepository = keystoneProposalRepository,
        migrationScheduler = migrationScheduler,
        migrationNotifier = migrationNotifier,
    )

    private class FakeNavigationRouter : NavigationRouter {
        var backToRootCount = 0
        val forwardedRoutes = mutableListOf<Any>()

        override fun forward(vararg routes: Any) {
            forwardedRoutes.addAll(routes)
        }

        override fun replace(vararg routes: Any) = Unit

        override fun replaceAll(vararg routes: Any) = Unit

        override fun back() = Unit

        override fun backTo(route: KClass<*>) = Unit

        override fun custom(block: (NavBackStackEntry?) -> NavigationCommand?) = Unit

        override fun backToRoot() {
            backToRootCount++
        }

        override fun observePipeline(): Flow<BaseNavigationCommand> = emptyFlow()
    }
}
