package co.electriccoin.zcash.work

import cash.z.ecc.android.sdk.MigrationBlocker
import cash.z.ecc.android.sdk.MigrationSyncWakeup
import cash.z.ecc.android.sdk.MigrationTransferState
import cash.z.ecc.android.sdk.MigrationTransferStates
import cash.z.ecc.android.sdk.TransferAttemptOutcome
import cash.z.ecc.android.sdk.TransferResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class MigrationDriveOnceTest {
    // ── executeWithRetries ────────────────────────────────────────────────────

    @Test
    fun `a Success on the first attempt does not retry`() =
        runTest {
            var callCount = 0
            val result =
                executeWithRetries(retryDelayMs = 0) {
                    callCount++
                    TransferAttemptOutcome.Executed(TransferResult.Success("txid"))
                }

            assertIs<TransferAttemptOutcome.Executed>(result)
            assertIs<TransferResult.Success>(result.result)
            assertEquals(1, callCount)
        }

    @Test
    fun `a retryable NetworkError retries up to maxAttempts then stops`() =
        runTest {
            var callCount = 0
            val result =
                executeWithRetries(maxAttempts = 3, retryDelayMs = 0) {
                    callCount++
                    TransferAttemptOutcome.Executed(TransferResult.NetworkError(retryable = true))
                }

            assertIs<TransferAttemptOutcome.Executed>(result)
            assertIs<TransferResult.NetworkError>(result.result)
            assertEquals(3, callCount)
        }

    @Test
    fun `a non-retryable NetworkError stops immediately without exhausting maxAttempts`() =
        runTest {
            var callCount = 0
            val result =
                executeWithRetries(maxAttempts = 3, retryDelayMs = 0) {
                    callCount++
                    TransferAttemptOutcome.Executed(TransferResult.NetworkError(retryable = false))
                }

            assertIs<TransferAttemptOutcome.Executed>(result)
            assertIs<TransferResult.NetworkError>(result.result)
            assertEquals(1, callCount)
        }

    @Test
    fun `a NothingDue result stops immediately without retrying`() =
        runTest {
            var callCount = 0
            val result =
                executeWithRetries(maxAttempts = 3, retryDelayMs = 0) {
                    callCount++
                    TransferAttemptOutcome.NothingDue
                }

            assertIs<TransferAttemptOutcome.NothingDue>(result)
            assertEquals(1, callCount)
        }

    @Test
    fun `an AwaitingProof result stops immediately without retrying`() =
        runTest {
            var callCount = 0
            val result =
                executeWithRetries(maxAttempts = 3, retryDelayMs = 0) {
                    callCount++
                    TransferAttemptOutcome.AwaitingProof(1L)
                }

            assertIs<TransferAttemptOutcome.AwaitingProof>(result)
            assertEquals(1, callCount)
        }

    @Test
    fun `a retryable NetworkError that later succeeds stops as soon as it succeeds`() =
        runTest {
            var callCount = 0
            val result =
                executeWithRetries(maxAttempts = 3, retryDelayMs = 0) {
                    callCount++
                    if (callCount < 2) {
                        TransferAttemptOutcome.Executed(TransferResult.NetworkError(retryable = true))
                    } else {
                        TransferAttemptOutcome.Executed(TransferResult.Success("txid"))
                    }
                }

            assertIs<TransferAttemptOutcome.Executed>(result)
            assertIs<TransferResult.Success>(result.result)
            assertEquals(2, callCount)
        }

    // ── decideBroadcastPreflight ──────────────────────────────────────────────

    @Test
    fun `a broadcast run defers inside the quiet gap`() {
        assertEquals(
            BroadcastPreflight.DEFER,
            decideBroadcastPreflight(
                synchronizerSyncing = false,
                nowEpochSeconds = 1000,
                lastNetworkActivityEpochSeconds = 700,
                privacyBufferSeconds = 600,
            )
        )
    }

    @Test
    fun `a broadcast run proceeds when all sources quiet past the gap`() {
        assertEquals(
            BroadcastPreflight.BROADCAST,
            decideBroadcastPreflight(false, 1000, 100, 600)
        )
    }

    @Test
    fun `a broadcast run proceeds when no sync ever happened`() {
        assertEquals(
            BroadcastPreflight.BROADCAST,
            decideBroadcastPreflight(false, 1000, null, 600)
        )
    }

    @Test
    fun `a broadcast run defers while the synchronizer is syncing`() {
        assertEquals(
            BroadcastPreflight.DEFER,
            decideBroadcastPreflight(
                synchronizerSyncing = true,
                nowEpochSeconds = 1000,
                lastNetworkActivityEpochSeconds = null,
                privacyBufferSeconds = 600,
            )
        )
    }

    @Test
    fun `a broadcast run proceeds when the gap exactly elapsed`() {
        // now=1000, last=400, buffer=600 → gap=600, exactly elapsed → BROADCAST
        assertEquals(
            BroadcastPreflight.BROADCAST,
            decideBroadcastPreflight(false, 1000, 400, 600)
        )
    }

    // ── broadcastDueByEstimate (estimated-tip acceleration) ───────────────────

    private fun tx(
        id: Long,
        scheduled: Long,
        isProved: Boolean = true,
        isSent: Boolean = false,
        blocker: MigrationBlocker? = null,
    ) = MigrationTransferState(
        id = id,
        isTransfer = true,
        isSent = isSent,
        isProved = isProved,
        scheduledHeight = scheduled,
        anchorBoundaryHeight = scheduled - 10,
        blocker = blocker,
    )

    private fun states(vararg transfers: MigrationTransferState) =
        MigrationTransferStates(transfers = transfers.toList(), tipHeight = 100L)

    @Test
    fun `a proved unsent transfer due by estimate accelerates the broadcast`() {
        assertTrue(broadcastDueByEstimate(states(tx(1, scheduled = 150)), estimatedTip = 150))
    }

    @Test
    fun `an unproved transfer never accelerates`() {
        assertFalse(broadcastDueByEstimate(states(tx(1, scheduled = 150, isProved = false)), estimatedTip = 200))
    }

    @Test
    fun `a not-yet-due transfer never accelerates`() {
        assertFalse(broadcastDueByEstimate(states(tx(1, scheduled = 150)), estimatedTip = 149))
    }

    @Test
    fun `an unprovable-anchor transfer never accelerates`() {
        assertFalse(
            broadcastDueByEstimate(
                states(tx(1, scheduled = 150, blocker = MigrationBlocker.UNPROVABLE_ANCHOR)),
                estimatedTip = 200,
            )
        )
    }

    @Test
    fun `an unavailable estimate never accelerates`() {
        assertFalse(broadcastDueByEstimate(states(tx(1, scheduled = 150)), estimatedTip = -1))
    }

    // ── computeEngineWakeDelay (the engine-only "when?" projection) ─────────────────────────

    @Test
    fun `the earliest of wakeup and due height wins`() {
        val delay =
            computeEngineWakeDelay(
                states = states(tx(1, scheduled = 300, isProved = false, blocker = MigrationBlocker.ANCHOR_BOUNDARY)),
                wakeups = listOf(MigrationSyncWakeup(height = 250, covers = listOf(1L))),
                est = 100,
                secondsPerBlock = 10,
            )
        // Wakeup at 250 beats the due height 300: (250-100)*10 = 1500s.
        assertEquals(1500.seconds, delay)
    }

    @Test
    fun `a past target floors at the minimum re-arm`() {
        val delay =
            computeEngineWakeDelay(
                states = states(tx(1, scheduled = 90)),
                wakeups = emptyList(),
                est = 100,
                secondsPerBlock = 10,
            )
        assertEquals(MIN_REARM_SECONDS.seconds, delay)
    }

    @Test
    fun `wakeups covering only unprovable transactions are ignored`() {
        val stuck = tx(9, scheduled = 90, isProved = false, blocker = MigrationBlocker.UNPROVABLE_ANCHOR)
        val delay =
            computeEngineWakeDelay(
                states = states(stuck),
                wakeups = listOf(MigrationSyncWakeup(height = 100, covers = listOf(9L))),
                est = 100,
                secondsPerBlock = 10,
            )
        // The stuck tx is the ONLY pending work → nothing relevant → cadence fallback (null).
        assertNull(delay)
    }

    @Test
    fun `a wakeup covering a healthy transaction alongside a stuck one is honored`() {
        val stuck = tx(9, scheduled = 90, isProved = false, blocker = MigrationBlocker.UNPROVABLE_ANCHOR)
        val healthy = tx(10, scheduled = 300, isProved = false, blocker = MigrationBlocker.ANCHOR_BOUNDARY)
        val delay =
            computeEngineWakeDelay(
                states = states(stuck, healthy),
                wakeups = listOf(MigrationSyncWakeup(height = 250, covers = listOf(9L, 10L))),
                est = 100,
                secondsPerBlock = 10,
            )
        assertEquals(1500.seconds, delay)
    }

    @Test
    fun `an unavailable estimate falls back to the cadence`() {
        assertNull(
            computeEngineWakeDelay(
                states = states(tx(1, scheduled = 300)),
                wakeups = emptyList(),
                est = -1,
                secondsPerBlock = 10,
            )
        )
    }

    @Test
    fun `nothing pending falls back to the cadence`() {
        assertNull(
            computeEngineWakeDelay(
                states = states(tx(1, scheduled = 90, isSent = true)),
                wakeups = emptyList(),
                est = 100,
                secondsPerBlock = 10,
            )
        )
    }

    // ── nextWake (engine schedule folded with the app privacy gap) ────────────

    @Test
    fun `a ready-but-gapped broadcast re-arms to the quiet expiry`() {
        // proved, unsent transfer due at est=1000; synced 60s ago, buffer 180s -> 120s remaining.
        val delay =
            nextWake(
                states = states(tx(1, scheduled = 1000)),
                wakeups = emptyList(),
                est = 1000,
                secondsPerBlock = 3,
                lastActivityEpochSeconds = 1_000_000L,
                privacyBufferSeconds = 180L,
                nowEpochSeconds = 1_000_060L,
            )
        assertEquals(120.seconds, delay)
    }

    @Test
    fun `an earlier engine proving wake wins over the gap`() {
        val delay =
            nextWake(
                states = states(tx(1, scheduled = 1000)),
                wakeups = listOf(MigrationSyncWakeup(height = 1100, covers = listOf(4L))),
                est = 1000,
                secondsPerBlock = 1,
                lastActivityEpochSeconds = 1_000_000L,
                privacyBufferSeconds = 600L,
                nowEpochSeconds = 1_000_100L,
            )
        // engine proving wake at 1100: (1100-1000)*1 = 100s < 500s gap remaining -> 100s wins.
        // (tx1 itself is excluded from the engine due-height calc since it's the gapped one.)
        assertEquals(100.seconds, delay)
    }

    @Test
    fun `no broadcast-ready transfer means the gap term does not apply`() {
        val delay =
            nextWake(
                states = states(tx(1, scheduled = 2000, isProved = false, blocker = MigrationBlocker.ANCHOR_BOUNDARY)),
                wakeups = emptyList(),
                est = 1000,
                secondsPerBlock = 3,
                lastActivityEpochSeconds = 1_000_000L,
                privacyBufferSeconds = 180L,
                nowEpochSeconds = 1_000_178L, // if the gap applied, only 2s would remain
            )
        // Unproved -> not broadcast-ready -> gap term is inert; falls through to the engine-only
        // due-height delay (2000-1000)*3 = 3000s, wildly different from the near-elapsed gap.
        assertEquals(3000.seconds, delay)
    }

    @Test
    fun `a gap already elapsed re-arms at zero, not negative`() {
        val delay =
            nextWake(
                states = states(tx(1, scheduled = 1000)),
                wakeups = emptyList(),
                est = 1000,
                secondsPerBlock = 3,
                lastActivityEpochSeconds = 1_000_000L,
                privacyBufferSeconds = 180L,
                nowEpochSeconds = 1_000_500L, // 500s since last activity, buffer only 180s
            )
        assertEquals(0.seconds, delay)
    }

    // ── waitingDisposition (what a genuine engine Waiting resolves to) ────────

    @Test
    fun `all sent means a completion sweep`() {
        assertEquals(WaitingDisposition.COMPLETION_SWEEP, waitingDisposition(allSent = true, hasUnprovableBlocker = false))
    }

    @Test
    fun `an unprovable blocker with unsent work surfaces the blocker`() {
        assertEquals(
            WaitingDisposition.SURFACE_UNPROVABLE,
            waitingDisposition(allSent = false, hasUnprovableBlocker = true)
        )
    }

    @Test
    fun `neither condition just re-arms`() {
        assertEquals(WaitingDisposition.RE_ARM, waitingDisposition(allSent = false, hasUnprovableBlocker = false))
    }

    @Test
    fun `all sent takes priority over an unprovable blocker`() {
        assertEquals(WaitingDisposition.COMPLETION_SWEEP, waitingDisposition(allSent = true, hasUnprovableBlocker = true))
    }
}
