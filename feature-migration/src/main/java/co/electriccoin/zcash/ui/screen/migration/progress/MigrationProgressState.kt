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
 * [number] is 1-based display order (broadcast/schedule order).
 * [statusLabel] is the PRIMARY label shown to ALL users (reinstated 2026-08-01, decision with
 * Dominik) — a soft, non-deadline-implying per-row time hint ("~5 min" or a status-derived phrase
 * like "Preparing" when no honest duration remains), never styled as a countdown-to-deadline.
 * [syncLabel] is non-null only in DEBUG builds — the raw engine status word, demoted to a
 * diagnostic suffix, appended in the UI as "· status $syncLabel" when present (inverse of the
 * pre-2026-08-01 priority).
 */
data class MigrationProgressPreparationState(
    val number: Int,
    val statusLabel: StringResource,
    val isSent: Boolean,
    val syncLabel: StringResource? = null,
)

data class MigrationProgressTransferState(
    val index: Int,
    val amount: StringResource,
    // PRIMARY label shown to ALL users — see [MigrationProgressPreparationState.statusLabel] doc.
    val statusLabel: StringResource,
    // Attention paint (orange) — genuine cannot-heal states only (expired / unprovable anchor),
    // never a merely-late-but-healthy transfer.
    val isAttention: Boolean,
    val isSent: Boolean,
    val fiatAmount: StringResource? = null,
    // Non-null only in DEBUG builds — the raw engine status word, demoted to a diagnostic suffix.
    // See [MigrationProgressPreparationState.syncLabel] doc.
    val syncLabel: StringResource? = null,
)
