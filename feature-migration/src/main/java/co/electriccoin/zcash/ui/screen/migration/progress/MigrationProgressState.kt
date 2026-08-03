package co.electriccoin.zcash.ui.screen.migration.progress

import co.electriccoin.zcash.ui.design.util.StringResource

data class MigrationProgressState(
    val title: StringResource,
    val subtitle: StringResource,
    // Feeds the "Split Balance N" rows shown above the transfer timeline — one row per
    // note-split (preparation) transaction, rendered in broadcast order.
    val totalAmount: StringResource,
    val totalFiatAmount: StringResource? = null,
    val preparations: List<MigrationProgressPreparationState> = emptyList(),
    val transfers: List<MigrationProgressTransferState>,
    val isComplete: Boolean,
    val onBack: () -> Unit,
    val onDone: (() -> Unit)? = null,
)

/**
 * One note-split (preparation) transaction row in the Migration Progress timeline.
 *
 * [number] is 1-based display order (broadcast/schedule order). [statusLabel] is the row's only
 * label (2026-08-03 finalization: the DEBUG-only raw engine status suffix is gone) — a soft,
 * non-deadline-implying per-row time hint ("~5 min" / "in ~5 min" for the last row overall),
 * "Ready now", "Done", or a blocked-row phrase ("Awaiting signature" / "Waiting for previous
 * split"); never styled as a countdown-to-deadline, never "Overdue". [isReadyNow] is true only for
 * the "Ready now" state, which Figma renders in the primary text color instead of the muted gray
 * every other subtitle uses.
 */
data class MigrationProgressPreparationState(
    val number: Int,
    val statusLabel: StringResource,
    val isReadyNow: Boolean,
    val isSent: Boolean,
)

data class MigrationProgressTransferState(
    val index: Int,
    val amount: StringResource,
    // The row's only label — see [MigrationProgressPreparationState.statusLabel] doc.
    val statusLabel: StringResource,
    // True only for the "Ready now" state — primary text color instead of muted gray.
    val isReadyNow: Boolean,
    // Attention paint (orange) — genuine cannot-heal states only (expired / unprovable anchor),
    // never a merely-late-but-healthy transfer.
    val isAttention: Boolean,
    val isSent: Boolean,
    val fiatAmount: StringResource? = null,
)
