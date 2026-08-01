package co.electriccoin.zcash.work

import cash.z.ecc.android.sdk.OrchardMigrationSdk
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class MigrationLiveDriverTest {
    @Test
    fun `starting twice for the same account only ever runs one loop`() =
        runTest {
            var runCallCount = 0
            val driveOnce =
                mockk<MigrationDriveOnce> {
                    coEvery { run(any(), any(), any()) } coAnswers {
                        runCallCount++
                        DriveOnceResult.Terminal // stops the loop on the very first call
                    }
                }
            val sdk = mockk<OrchardMigrationSdk>(relaxed = true)
            val driver =
                MigrationLiveDriverImpl(
                    migrationDriveOnce = driveOnce,
                    getOrchardMigrationSdk = { sdk },
                    scope = this,
                )

            driver.startIfNotRunning("account-1")
            driver.startIfNotRunning("account-1")
            advanceUntilIdle()

            assertEquals(1, runCallCount, "a second start for the SAME account while the first is still active must be a no-op")
        }

    @Test
    fun `the loop re-arms using the ReArmed delay, and stops on Terminal`() =
        runTest {
            var runCallCount = 0
            val driveOnce =
                mockk<MigrationDriveOnce> {
                    coEvery { run(any(), any(), any()) } coAnswers {
                        runCallCount++
                        if (runCallCount < 3) DriveOnceResult.ReArmed(90.seconds) else DriveOnceResult.Terminal
                    }
                }
            val sdk = mockk<OrchardMigrationSdk>(relaxed = true)
            val driver =
                MigrationLiveDriverImpl(
                    migrationDriveOnce = driveOnce,
                    getOrchardMigrationSdk = { sdk },
                    scope = this,
                )

            driver.startIfNotRunning("account-1")
            advanceUntilIdle()

            assertEquals(3, runCallCount, "the loop must call run() again after each returned delay, and stop on Terminal")
        }

    @Test
    fun `a floorless ReArmed delay is floored, not spun on tightly`() =
        runTest {
            var runCallCount = 0
            val driveOnce =
                mockk<MigrationDriveOnce> {
                    coEvery { run(any(), any(), any()) } coAnswers {
                        runCallCount++
                        // 0 seconds is exactly what nextWake's floorless privacy-gap term can
                        // return — the loop must not spin on this.
                        if (runCallCount < 3) DriveOnceResult.ReArmed(kotlin.time.Duration.ZERO) else DriveOnceResult.Terminal
                    }
                }
            val sdk = mockk<OrchardMigrationSdk>(relaxed = true)
            val driver =
                MigrationLiveDriverImpl(
                    migrationDriveOnce = driveOnce,
                    getOrchardMigrationSdk = { sdk },
                    scope = this,
                )

            driver.startIfNotRunning("account-1")
            // Advance by less than 2x the floor — if the loop weren't flooring, all 3 calls would
            // already be done; if it IS flooring at 60s, at most one call should have happened by
            // t=1s.
            advanceTimeBy(1_000)
            assertEquals(1, runCallCount, "the loop must floor a zero/near-zero ReArmed delay, not spin")
            advanceUntilIdle()
            assertEquals(3, runCallCount)
        }

    @Test
    fun `LockBusy waits the given retry delay and tries again`() =
        runTest {
            var runCallCount = 0
            val driveOnce =
                mockk<MigrationDriveOnce> {
                    coEvery { run(any(), any(), any()) } coAnswers {
                        runCallCount++
                        if (runCallCount < 2) DriveOnceResult.LockBusy(5.seconds) else DriveOnceResult.Terminal
                    }
                }
            val sdk = mockk<OrchardMigrationSdk>(relaxed = true)
            val driver =
                MigrationLiveDriverImpl(
                    migrationDriveOnce = driveOnce,
                    getOrchardMigrationSdk = { sdk },
                    scope = this,
                )

            driver.startIfNotRunning("account-1")
            advanceUntilIdle()

            assertEquals(2, runCallCount, "LockBusy must retry using its own retryDelay, not the floored ReArmed floor")
        }
}
