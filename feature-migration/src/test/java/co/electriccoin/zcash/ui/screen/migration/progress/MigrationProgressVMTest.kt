package co.electriccoin.zcash.ui.screen.migration.progress

import co.electriccoin.zcash.ui.common.model.migration.LiveMigrationPreparation
import co.electriccoin.zcash.ui.common.model.migration.LiveMigrationSnapshot
import co.electriccoin.zcash.ui.common.model.migration.LiveMigrationTransfer
import co.electriccoin.zcash.ui.common.model.migration.MigrationTransferAction
import co.electriccoin.zcash.ui.common.model.migration.MigrationTransferBlocker
import co.electriccoin.zcash.ui.design.util.StringResource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Pure-logic coverage for the Migration Progress row rendering and header subtitle.
 *
 * The engine's per-transaction status (`action`/`blocker` from `transaction_statuses`) remains
 * the single source of truth for what STATE a row is in — no app-side "overdue" flag, no
 * countdown-to-deadline, no red/orange attention paint tied to time (decision with Dominik
 * 2026-07-31, preserved by commit `33cff6883`).
 *
 * As of 2026-08-01 (decision with Dominik), a soft, non-deadline-implying per-row TIME HINT is
 * reinstated as the PRIMARY label for all users, with the raw engine status word demoted to a
 * DEBUG-only diagnostic suffix — the inverse of the prior priority — and the header now counts
 * down remaining time (with an explicit overdue clamp) instead of a static total span.
 *
 * Uses top-level internal functions only — no Koin, no Android, no ViewModel required.
 */
class MigrationProgressVMTest {
    private val now: Instant = Instant.fromEpochSeconds(1_000_000L)

    private fun transfer(
        index: Int = 0,
        isSent: Boolean = false,
        isProved: Boolean = false,
        action: MigrationTransferAction? = null,
        blocker: MigrationTransferBlocker? = null,
        minedHeight: Long? = null,
        amountZatoshi: Long = 100_000_000L,
        id: Long = 1L,
    ) = LiveMigrationTransfer(
        id = id,
        index = index,
        amountZatoshi = amountZatoshi,
        scheduledHeight = 1_000L + index,
        scheduledAt = now,
        isSent = isSent,
        isProved = isProved,
        action = action,
        blocker = blocker,
        expiryAt = null,
        minedHeight = minedHeight,
    )

    private fun prep(
        id: Long = 1L,
        isSent: Boolean = false,
        isProved: Boolean = false,
        action: MigrationTransferAction? = null,
        blocker: MigrationTransferBlocker? = null,
    ) = LiveMigrationPreparation(
        id = id,
        layer = 0,
        index = 0,
        scheduledHeight = 1_000L,
        scheduledAt = now,
        isSent = isSent,
        isProved = isProved,
        action = action,
        blocker = blocker,
        dependsOn = emptyList(),
    )

    // ── transferLabel: pure engine-status mapping ─────────────────────────────

    @Test
    fun transferLabel_broadcast_shows_sent() {
        assertEquals("Sent", transferLabel(transfer(isSent = true)).asString())
    }

    @Test
    fun transferLabel_mined_shows_confirmed() {
        assertEquals("Confirmed", transferLabel(transfer(isSent = true, minedHeight = 4_226_000L)).asString())
    }

    @Test
    fun transferLabel_expired_blocker_shows_expired() {
        assertEquals("Expired", transferLabel(transfer(blocker = MigrationTransferBlocker.EXPIRED)).asString())
    }

    @Test
    fun transferLabel_unprovable_anchor_blocker_shows_needs_reschedule() {
        assertEquals(
            "Needs reschedule",
            transferLabel(transfer(blocker = MigrationTransferBlocker.UNPROVABLE_ANCHOR)).asString(),
        )
    }

    @Test
    fun transferLabel_signature_blocker_shows_awaiting_signature() {
        assertEquals("Awaiting signature", transferLabel(transfer(blocker = MigrationTransferBlocker.SIGNATURE)).asString())
    }

    @Test
    fun transferLabel_dependencies_blocker_shows_waiting_for_note_split() {
        assertEquals(
            "Waiting for note split",
            transferLabel(transfer(blocker = MigrationTransferBlocker.DEPENDENCIES)).asString(),
        )
    }

    @Test
    fun transferLabel_anchor_boundary_blocker_shows_waiting_for_anchor_window() {
        assertEquals(
            "Waiting for anchor window",
            transferLabel(transfer(blocker = MigrationTransferBlocker.ANCHOR_BOUNDARY)).asString(),
        )
    }

    @Test
    fun transferLabel_schedule_blocker_shows_scheduled_not_a_countdown() {
        assertEquals("Scheduled", transferLabel(transfer(blocker = MigrationTransferBlocker.SCHEDULE)).asString())
    }

    @Test
    fun transferLabel_ready_to_prove_shows_preparing() {
        assertEquals("Preparing", transferLabel(transfer(action = MigrationTransferAction.PROVE)).asString())
    }

    @Test
    fun transferLabel_ready_to_broadcast_shows_sending_soon() {
        assertEquals("Sending soon", transferLabel(transfer(action = MigrationTransferAction.BROADCAST)).asString())
    }

    @Test
    fun transferLabel_no_status_falls_back_to_waiting() {
        assertEquals("Waiting", transferLabel(transfer()).asString())
    }

    // ── preparationStatusLabel: pure engine-status mapping ────────────────────

    @Test
    fun preparationStatusLabel_sent_shows_sent() {
        assertEquals("Sent", preparationStatusLabel(prep(isSent = true)).asString())
    }

    @Test
    fun preparationStatusLabel_ready_to_prove_shows_preparing() {
        assertEquals("Preparing", preparationStatusLabel(prep(action = MigrationTransferAction.PROVE)).asString())
    }

    @Test
    fun preparationStatusLabel_ready_to_broadcast_shows_sending_soon() {
        assertEquals("Sending soon", preparationStatusLabel(prep(action = MigrationTransferAction.BROADCAST)).asString())
    }

    @Test
    fun preparationStatusLabel_dependencies_shows_waiting_for_previous_split() {
        assertEquals(
            "Waiting for previous split",
            preparationStatusLabel(prep(blocker = MigrationTransferBlocker.DEPENDENCIES)).asString(),
        )
    }

    @Test
    fun preparationStatusLabel_default_shows_waiting() {
        assertEquals("Waiting", preparationStatusLabel(prep()).asString())
    }

    // ── mapping: number/isSent/isAttention/syncLabel presence ─────────────────

    @Test
    fun mapping_preparations_number_and_sent_and_debug_synclabel() {
        val rows =
            mapPreparationsToState(
                listOf(prep(id = 1, isSent = true, isProved = true), prep(id = 2, action = MigrationTransferAction.PROVE)),
                now,
                debugSyncEnabled = true,
            )
        assertEquals(2, rows.size)
        assertEquals(1, rows[0].number)
        assertTrue(rows[0].isSent)
        // statusLabel is now the PRIMARY, all-builds time hint (preparationSyncLabel) — for a
        // sent+proved row it falls back to the status-derived phrase, which is "Sent" here too.
        assertEquals("Sent", rows[0].statusLabel.asString())
        assertNotNull(rows[0].syncLabel, "syncLabel must be non-null when debugSyncEnabled=true")
        // syncLabel is now the DEBUG-only raw engine status word (preparationStatusLabel).
        assertEquals("Sent", rows[0].syncLabel?.asString())
        assertEquals(2, rows[1].number)
        assertFalse(rows[1].isSent)
    }

    @Test
    fun mapping_preparations_synclabel_null_when_debug_disabled() {
        val rows = mapPreparationsToState(listOf(prep(isProved = true)), now, debugSyncEnabled = false)
        assertNull(rows.single().syncLabel)
    }

    @Test
    fun mapping_transfer_attention_only_for_expired_and_unprovable() {
        val unprovable = mapTransfersToState(listOf(transfer(blocker = MigrationTransferBlocker.UNPROVABLE_ANCHOR)), now, false)
        assertTrue(unprovable.single().isAttention)
        val expired = mapTransfersToState(listOf(transfer(blocker = MigrationTransferBlocker.EXPIRED)), now, false)
        assertTrue(expired.single().isAttention)
        // A merely-late-but-healthy transfer (scheduled/ready) is NEVER attention-painted.
        val scheduled = mapTransfersToState(listOf(transfer(blocker = MigrationTransferBlocker.SCHEDULE)), now, false)
        assertFalse(scheduled.single().isAttention)
        val sendingSoon = mapTransfersToState(listOf(transfer(action = MigrationTransferAction.BROADCAST)), now, false)
        assertFalse(sendingSoon.single().isAttention)
    }

    @Test
    fun mapping_transfer_synclabel_presence_follows_debug_flag() {
        val on = mapTransfersToState(listOf(transfer(isProved = true, isSent = true)), now, debugSyncEnabled = true)
        assertNotNull(on.single().syncLabel)
        val off = mapTransfersToState(listOf(transfer(isProved = true, isSent = true)), now, debugSyncEnabled = false)
        assertNull(off.single().syncLabel)
    }

    // ── per-row time hint (PRIMARY as of 2026-08-01): relative time, or a status-derived ─────
    // ── phrase instead of the bare "proved"/"pending" debug jargon words ──────────────────────

    @Test
    fun transferSyncLabel_proved_falls_back_to_status_derived_phrase_not_bare_proved() {
        // No action/blocker set beyond isProved: transferLabel(t) falls through to "Waiting".
        assertEquals("Waiting", transferSyncLabel(transfer(isProved = true), now).asString())
    }

    @Test
    fun transferSyncLabel_proved_with_broadcast_action_shows_sending_soon_not_bare_proved() {
        val t = transfer(isProved = true, action = MigrationTransferAction.BROADCAST)
        assertEquals("Sending soon", transferSyncLabel(t, now).asString())
    }

    @Test
    fun transferSyncLabel_unproved_past_due_falls_back_to_status_derived_phrase_not_bare_pending() {
        val t = transfer(isProved = false, action = MigrationTransferAction.PROVE).copy(scheduledAt = now - 1.minutes)
        assertEquals("Preparing", transferSyncLabel(t, now).asString())
    }

    @Test
    fun transferSyncLabel_future_scheduled_shows_relative_time() {
        val t = transfer(isProved = false).copy(scheduledAt = now + 5.minutes)
        assertEquals("~5 min", transferSyncLabel(t, now).asString())
    }

    @Test
    fun preparationSyncLabel_proved_falls_back_to_status_derived_phrase_not_bare_proved() {
        assertEquals("Waiting", preparationSyncLabel(prep(isProved = true), now).asString())
    }

    @Test
    fun preparationSyncLabel_proved_with_broadcast_action_shows_sending_soon_not_bare_proved() {
        val p = prep(isProved = true, action = MigrationTransferAction.BROADCAST)
        assertEquals("Sending soon", preparationSyncLabel(p, now).asString())
    }

    // ── migrationProgressSubtitle: header static-span vs. remaining-time countdown ───────────

    @Test
    fun migrationProgressSubtitle_not_started_keeps_static_total_span_unchanged() {
        val transfers =
            listOf(
                transfer(index = 0, id = 1).copy(scheduledAt = now),
                transfer(index = 1, id = 2).copy(scheduledAt = now + 10.minutes),
            )
        val snapshot = LiveMigrationSnapshot(transfers = transfers, preparations = emptyList(), tipHeight = 1_000L)
        val subtitle = migrationProgressSubtitle(snapshot, now)
        assertTrue(subtitle.contains("over ~10 min"), subtitle)
        assertTrue(subtitle.contains("2 remaining transfers"), subtitle)
    }

    @Test
    fun migrationProgressSubtitle_in_progress_counts_down_remaining_time() {
        val transfers =
            listOf(
                transfer(index = 0, id = 1, isSent = true).copy(scheduledAt = now - 5.minutes),
                transfer(index = 1, id = 2).copy(scheduledAt = now + 8.minutes),
            )
        val snapshot = LiveMigrationSnapshot(transfers = transfers, preparations = emptyList(), tipHeight = 1_000L)
        val subtitle = migrationProgressSubtitle(snapshot, now)
        assertTrue(subtitle.contains("~8 min remaining"), subtitle)
        assertFalse(subtitle.contains("over ~"), subtitle)
    }

    @Test
    fun migrationProgressSubtitle_overdue_header_never_shows_floored_lying_duration() {
        // Regression test for the bug adversarial (Fable) review caught in an earlier draft:
        // once `now >= lastScheduled` (a normal, expected late-but-healthy engine state, not
        // stuck/broken), formatMigrationDuration's `coerceAtLeast(60L)` floor would make a naive
        // `formatMigrationDuration(remaining)` call print a permanently-lying "~1 min" forever.
        // The header must instead switch to non-time copy without calling formatMigrationDuration
        // on a zero/negative span at all.
        val transfers =
            listOf(
                transfer(index = 0, id = 1, isSent = true).copy(scheduledAt = now - 20.minutes),
                transfer(index = 1, id = 2).copy(scheduledAt = now - 1.minutes),
            )
        val snapshot = LiveMigrationSnapshot(transfers = transfers, preparations = emptyList(), tipHeight = 1_000L)
        val subtitle = migrationProgressSubtitle(snapshot, now)
        assertFalse(subtitle.contains("~1 min"), "must never show the floored, permanently-lying duration: $subtitle")
        assertFalse(subtitle.contains("min remaining"), "must not claim a duration once running late: $subtitle")
        assertTrue(subtitle.contains("Finishing up"), subtitle)
    }

    @Test
    fun migrationProgressSubtitle_complete_shows_all_complete() {
        val transfers = listOf(transfer(index = 0, id = 1, isSent = true))
        val snapshot = LiveMigrationSnapshot(transfers = transfers, preparations = emptyList(), tipHeight = 1_000L)
        assertEquals("All 1 transfers are complete.", migrationProgressSubtitle(snapshot, now))
    }

    @Suppress("UNCHECKED_CAST")
    private fun StringResource.asString(): String =
        when (this) {
            is StringResource.ByString -> {
                value
            }

            else -> {
                val resourcesField = this::class.java.getDeclaredField("resources").also { it.isAccessible = true }
                val parts = resourcesField.get(this) as List<StringResource>
                parts.joinToString(separator = "") { it.asString() }
            }
        }
}

/**
 * Replicates the preparation-mapping logic from [MigrationProgressVM.createState] as a pure
 * top-level function so tests can drive it without constructing the full VM. Any change to the
 * VM's mapping must be reflected here to keep the test honest.
 */
internal fun mapPreparationsToState(
    preparations: List<LiveMigrationPreparation>,
    now: Instant,
    debugSyncEnabled: Boolean,
): List<MigrationProgressPreparationState> =
    preparations.mapIndexed { i, p ->
        MigrationProgressPreparationState(
            number = i + 1,
            // PRIMARY, all builds: the per-row time hint.
            statusLabel = preparationSyncLabel(p, now),
            isSent = p.isSent,
            // DEBUG-only diagnostic suffix: the raw engine status word.
            syncLabel = if (debugSyncEnabled) preparationStatusLabel(p) else null,
        )
    }

/**
 * Replicates the transfer-mapping logic from [MigrationProgressVM.createState] as a pure top-level
 * function so tests can drive it without constructing the full VM.
 */
internal fun mapTransfersToState(
    transfers: List<LiveMigrationTransfer>,
    now: Instant,
    debugSyncEnabled: Boolean,
): List<MigrationProgressTransferState> =
    transfers.map { t ->
        MigrationProgressTransferState(
            index = t.index + 1,
            amount =
                co.electriccoin.zcash.ui.design.util
                    .stringRes(
                        cash.z.ecc.android.sdk.model
                            .Zatoshi(t.amountZatoshi)
                    ),
            // PRIMARY, all builds: the per-row time hint.
            statusLabel = transferSyncLabel(t, now),
            isAttention =
                t.blocker == MigrationTransferBlocker.UNPROVABLE_ANCHOR ||
                    t.blocker == MigrationTransferBlocker.EXPIRED,
            isSent = t.isSent,
            // DEBUG-only diagnostic suffix: the raw engine status word.
            syncLabel = if (debugSyncEnabled) transferLabel(t) else null,
        )
    }
