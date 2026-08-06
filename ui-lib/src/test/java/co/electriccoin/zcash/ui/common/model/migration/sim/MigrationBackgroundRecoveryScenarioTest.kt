package co.electriccoin.zcash.ui.common.model.migration.sim

import android.content.Context
import cash.z.ecc.android.sdk.MigrationState
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.provider.PendingMigrationTorFailureStorageProvider
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import co.electriccoin.zcash.ui.common.repository.MigrationPlanRepository
import co.electriccoin.zcash.ui.common.usecase.CheckMigrationRecoveryUseCase
import co.electriccoin.zcash.ui.common.usecase.GetOrchardMigrationSdkUseCase
import co.electriccoin.zcash.ui.common.usecase.GetSelectedWalletAccountUseCase
import co.electriccoin.zcash.work.MigrationSyncScheduler
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Drives the real [CheckMigrationRecoveryUseCase] — the app-open re-entry router — against the
 * shared stateful [FakeOrchardMigrationSdk] instead of the ad-hoc `mockk` SDK the existing
 * [co.electriccoin.zcash.ui.common.usecase.CheckMigrationRecoveryUseCaseTest] uses. Same use-case,
 * same fake-lambda wiring for the WorkManager Lane-A/B active checks; the difference is that
 * `getMigrationState()` is computed from a real seeded migration plan, so the recovery decision is
 * exercised against a self-consistent in-progress world.
 *
 * What is asserted:
 *  - A HEALTHY in-progress migration with Lane A absent re-arms Lane A (short flat first arm) and
 *    does NOT auto-navigate (Task 6: only a pending Tor failure auto-navigates on app-open).
 *  - Lane A already active → no re-schedule.
 *
 * Scope note: the Lane B revival branch calls `co.electriccoin.zcash.work.MigrationScheduler(context)`
 * directly (not injected), whose init touches AlarmManager and whose `schedule` calls
 * `WorkManager.getInstance` — neither works under a plain unit-test `Context` mock. So these tests
 * hold `isLaneBActive = { true }` to keep that non-injectable path unreached, exactly as the existing
 * CheckMigrationRecoveryUseCaseTest does, and assert the injectable Lane A decision + the
 * FakeSdk-derived state (InProgress) that the Lane B branch would key on. A fuller Lane-B assertion
 * would need MigrationScheduler to become injectable.
 */
class MigrationBackgroundRecoveryScenarioTest {

    @BeforeTest
    fun resetThrottle() {
        CheckMigrationRecoveryUseCase.resetRunThrottleForTests()
    }

    private companion object {
        const val ANCHOR: Long = 4_000_000L
        const val PREP_ID: Long = 1L
        const val TRANSFER_A: Long = 20L
        const val TRANSFER_B: Long = 21L
    }

    /** An in-progress migration: one transfer already sent, one still pending. */
    private fun inProgressDriver(): MigrationSimDriver {
        val driver = MigrationSimDriver()
        driver.seedPlan(
            preparations = listOf(
                MigrationSimDriver.SimPrep(id = PREP_ID, layer = 0, scheduledHeight = ANCHOR - 40L),
            ),
            transfers = listOf(
                MigrationSimDriver.SimTransfer(
                    id = TRANSFER_A, scheduledHeight = ANCHOR + 5L, anchorBoundary = ANCHOR,
                    dependsOn = listOf(PREP_ID),
                ),
                MigrationSimDriver.SimTransfer(
                    id = TRANSFER_B, scheduledHeight = ANCHOR + 15L, anchorBoundary = ANCHOR,
                    dependsOn = listOf(PREP_ID),
                ),
            ),
            startTip = ANCHOR - 40L,
        )
        driver.mine(id = PREP_ID, height = ANCHOR - 2L)
        driver.setTip(ANCHOR + 20L)
        return driver
    }

    private fun useCase(
        driver: MigrationSimDriver,
        navigationRouter: NavigationRouter,
        migrationSyncScheduler: MigrationSyncScheduler,
        pendingMigrationTorFailure: Boolean = false,
        isLaneAActive: suspend () -> Boolean = { true },
        isLaneBActive: suspend (String) -> Boolean = { true },
        migrationPlanRepository: MigrationPlanRepository = mockk(relaxed = true) {
            coEvery { load() } returns mockk(relaxed = true)
        },
    ) = CheckMigrationRecoveryUseCase(
        getOrchardMigrationSdk = mockk<GetOrchardMigrationSdkUseCase> {
            coEvery { this@mockk() } returns driver.sdk
        },
        persistableWalletProvider = mockk<PersistableWalletProvider> {
            coEvery { getPersistableWallet() } returns mockk()
        },
        navigationRouter = navigationRouter,
        migrationPlanRepository = migrationPlanRepository,
        pendingMigrationTorFailureStorageProvider = mockk<PendingMigrationTorFailureStorageProvider> {
            coEvery { get() } returns pendingMigrationTorFailure
        },
        getSelectedWalletAccount = mockk<GetSelectedWalletAccountUseCase>(relaxed = true),
        migrationSyncScheduler = migrationSyncScheduler,
        context = mockk<Context>(relaxed = true),
        isLaneAActive = isLaneAActive,
        isLaneBActive = isLaneBActive,
    )

    @Test
    fun `healthy in-progress migration with Lane A absent re-arms Lane A and does not auto-navigate`() = runTest {
        val driver = inProgressDriver()
        // Precondition sanity: the fake really is InProgress (not Complete/RequiresAttention).
        kotlin.test.assertTrue(driver.sdk.getMigrationState() is MigrationState.InProgress)

        val router = mockk<NavigationRouter>(relaxed = true)
        val syncScheduler = mockk<MigrationSyncScheduler>(relaxed = true)

        useCase(
            driver = driver,
            navigationRouter = router,
            migrationSyncScheduler = syncScheduler,
            isLaneAActive = { false }, // worker absent → recovery must re-arm it
            isLaneBActive = { true }, // keep the non-injectable Lane B path unreached (see kdoc)
        ).invoke()

        // Lane A re-armed with the short flat first arm.
        verify { syncScheduler.schedule(any(), 60.seconds) }
        // A healthy in-progress migration must NOT hijack the screen on app-open (Task 6).
        coVerify(exactly = 0) { router.replaceAll(any()) }
    }

    @Test
    fun `in-progress migration with Lane A already active does not re-schedule Lane A`() = runTest {
        val driver = inProgressDriver()
        val router = mockk<NavigationRouter>(relaxed = true)
        val syncScheduler = mockk<MigrationSyncScheduler>(relaxed = true)

        useCase(
            driver = driver,
            navigationRouter = router,
            migrationSyncScheduler = syncScheduler,
            isLaneAActive = { true },
            isLaneBActive = { true },
        ).invoke()

        verify(exactly = 0) { syncScheduler.schedule(any(), any()) }
        coVerify(exactly = 0) { router.replaceAll(any()) }
    }

    @Test
    fun `a completed migration keeps lanes alone and does not navigate`() = runTest {
        // Drain the plan to Complete, then app-open: the engine is no longer InProgress, so with no
        // saved plan there is nothing to reconcile and nothing to navigate.
        val driver = inProgressDriver()
        val opts = cash.z.ecc.android.sdk.NetworkPrivacyOptions(useTor = false)
        driver.sdk.finalizeReadyTransfers()
        driver.sdk.executeNextPendingTransfer(opts, useEstimatedTip = true)
        driver.sdk.executeNextPendingTransfer(opts, useEstimatedTip = true)
        kotlin.test.assertTrue(driver.sdk.getMigrationState() is MigrationState.Complete)

        val router = mockk<NavigationRouter>(relaxed = true)
        val syncScheduler = mockk<MigrationSyncScheduler>(relaxed = true)

        useCase(
            driver = driver,
            navigationRouter = router,
            migrationSyncScheduler = syncScheduler,
            isLaneAActive = { false },
            isLaneBActive = { true },
            // No saved plan and engine not InProgress → the reconciliation block is skipped entirely.
            migrationPlanRepository = mockk(relaxed = true) { coEvery { load() } returns null },
        ).invoke()

        verify(exactly = 0) { syncScheduler.schedule(any(), any()) }
        coVerify(exactly = 0) { router.replaceAll(any()) }
    }
}
