package co.electriccoin.zcash.ui.common.model.migration

import cash.z.ecc.android.sdk.MigrationSchedule
import cash.z.ecc.android.sdk.MigrationTransferStates
import kotlinx.serialization.Serializable
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * One note-split (preparation) transaction surfaced in the app-side [MigrationPlan]. Mirrors the
 * SDK's [cash.z.ecc.android.sdk.PreparationStep] but carries wall-clock estimates and live status
 * overlaid by [withLiveState].
 *
 * [status] starts [MigrationTransferStatus.PENDING]; once the engine reports [isSent] via live
 * state the overlay advances it to [MigrationTransferStatus.SENT]. [isProved] tracks whether the
 * engine has a proof for this preparation (for the debug "sync" field).
 *
 * Preparations do NOT contribute to [MigrationPlan.totalCount] / [MigrationPlan.completedCount] /
 * [MigrationPlan.isComplete] — those stay crossings-only.
 */
@Serializable
data class MigrationPreparation(
    val id: Long,
    val layer: Int,
    val index: Int,
    val scheduledAtEpochSeconds: Long,
    val dependsOn: List<Long>,
    val status: MigrationTransferStatus,
    val isProved: Boolean = false,
)

/**
 * How many successive migration-engine RUNS the account's current residual balance is estimated
 * to need, for a Keystone account, per `estimate_migration_runs`/`OrchardMigrationSdk
 * .estimateMigrationRunCount()`. The engine caps each run at a fixed number of notes it will
 * migrate (currently 50), so a large enough balance needs several distinct full
 * propose→confirm→sign→execute cycles instead of a single AUTOMATIC pass — this field is how the
 * UI communicates that ("Round X of Y").
 *
 * This is a different concept from Keystone's *within-round* QR/firmware batch-signing limit
 * (see `KeystoneBatchChunking.kt`), which chunks a single round's already-proposed transfers into
 * multiple sign/scan QR exchanges — that mechanism is untouched by this field and invisible to it.
 *
 * Only populated when the estimate is genuinely greater than 1 — a single-round migration (the
 * common case) or a sub-quantum residual balance (estimate of 0) both leave this `null`, exactly
 * as for any non-Keystone account. Always recomputed fresh from the live estimate at the moment
 * it's needed (Review screen entry, or right before `FinalizeMigrationScheduleUseCase` persists
 * the plan) — never a persisted, incrementing campaign counter, which is why `current` is always
 * literally `1` ("this round, from here") rather than tracking progress across rounds.
 */
@Serializable
data class MigrationKeystoneRound(
    val current: Int,
    val total: Int
)

@Serializable
data class MigrationPlan(
    val id: String,
    val createdAtEpochSeconds: Long,
    val transfers: List<MigrationTransfer>,
    val mode: MigrationMode = MigrationMode.AUTOMATIC,
    val keystoneRound: MigrationKeystoneRound? = null,
    val preparations: List<MigrationPreparation> = emptyList(),
) {
    val createdAt: Instant get() = Instant.fromEpochSeconds(createdAtEpochSeconds)
    val nextPending: MigrationTransfer? get() = transfers.firstOrNull { it.status == MigrationTransferStatus.PENDING }
    val isComplete: Boolean get() = transfers.all { it.status == MigrationTransferStatus.SENT }
    val completedCount: Int get() = transfers.count { it.status == MigrationTransferStatus.SENT }
    val totalCount: Int get() = transfers.size
}

/**
 * The single conversion from an SDK [MigrationSchedule] (block-height-denominated) to a persisted,
 * epoch-second-denominated [MigrationPlan]. Both the IMMEDIATE (`MigrationReviewVM`) and AUTOMATIC
 * (`FinalizeMigrationScheduleUseCase`) confirm paths must go through this one function — it used to
 * be reimplemented independently in each, and the two copies had already silently diverged (the
 * IMMEDIATE copy never set `expiryAtEpochSeconds`, defaulting every one of its transfers to the
 * always-expired sentinel) despite both looking correct in isolation. That's exactly the
 * duplication shape that let the anchorHeight/epoch-seconds bug survive one fix in an unfixed
 * sibling copy.
 */
fun MigrationSchedule.toMigrationPlan(
    mode: MigrationMode,
    keystoneRound: MigrationKeystoneRound? = null,
    secondsPerBlock: Long = 75L,
): MigrationPlan {
    val now = Clock.System.now().epochSeconds
    // Preparations carry no per-item anchorHeight. Use the transfers' commit-tip baseline so all
    // height-to-wall-clock estimates share the same reference point. Falls back to the
    // preparations' own broadcastHeight minimum (or 0) when there are no transfers.
    val baseline =
        transfers.minOfOrNull { it.anchorHeight }
            ?: preparations.minOfOrNull { it.broadcastHeight }
            ?: 0L
    return MigrationPlan(
        id = UUID.randomUUID().toString(),
        createdAtEpochSeconds = now,
        transfers =
            transfers.mapIndexed { i, t ->
                MigrationTransfer(
                    index = i,
                    amountZatoshi = t.amountZatoshi,
                    scheduledAtEpochSeconds =
                        now +
                            estimatedSecondsBetweenHeights(
                                t.anchorHeight,
                                t.nextExecutableAfterHeight,
                                secondsPerBlock
                            ),
                    status = MigrationTransferStatus.PENDING,
                    expiryAtEpochSeconds =
                        now + estimatedSecondsBetweenHeights(t.anchorHeight, t.expiryHeight, secondsPerBlock),
                    id = t.id,
                )
            },
        preparations =
            preparations.map { p ->
                MigrationPreparation(
                    id = p.id,
                    layer = p.layer,
                    index = p.index,
                    scheduledAtEpochSeconds =
                        now + estimatedSecondsBetweenHeights(baseline, p.broadcastHeight, secondsPerBlock),
                    dependsOn = p.dependsOn,
                    status = MigrationTransferStatus.PENDING,
                )
            },
        mode = mode,
        keystoneRound = keystoneRound,
    )
}

/**
 * Overrides [MigrationTransfer.status]/[MigrationTransfer.scheduledAtEpochSeconds] from the SDK's
 * live, persisted [MigrationTransferStates] — the cached [MigrationPlan] is a display cache
 * written at propose/commit time, so without this overlay it silently falls behind the engine's
 * actual state (the engine is the single source of truth for the plan).
 * amountZatoshi/createdAtEpochSeconds never change post-commit, so those keep coming from the
 * cache — only the fields the SDK can independently change are overridden here. Live entries whose
 * [cash.z.ecc.android.sdk.MigrationTransferState.isTransfer] is false (preparation transactions)
 * are now consumed by the preparations overlay below, updating [MigrationPlan.preparations]
 * status/isProved/scheduledAtEpochSeconds in the same pass.
 *
 * Correlates by the transfer's real, stable [MigrationTransfer.id] — NOT by [MigrationTransfer.index].
 * The engine assigns real ids in its own funding-note/crossing order, while [MigrationTransfer.index]
 * is this transfer's position in the broadcast-height-sorted array the app displays as "Transfer N".
 * ZIP 318 deliberately shuffles those two orderings apart, so matching by index would silently attach
 * the wrong transfer's live status/schedule to a displayed position (confirmed live — see
 * [co.electriccoin.zcash.ui.screen.migration.progress.MigrationProgressVM]).
 */
fun MigrationPlan.withLiveState(live: MigrationTransferStates?, secondsPerBlock: Long = 75L): MigrationPlan {
    if (live == null) return this
    val now = Clock.System.now().epochSeconds
    // Split by kind so the two overlays are self-evidently independent: a future SDK that
    // reuses ids across kinds could not cross-wire a preparation's live state onto a transfer
    // (or vice versa) even via the last-wins behaviour of associateBy.
    val byTransferId = live.transfers.filter { it.isTransfer }.associateBy { it.id }
    val byPrepId = live.transfers.filter { !it.isTransfer }.associateBy { it.id }
    return copy(
        transfers =
            transfers.map { t ->
                val liveTransfer = byTransferId[t.id] ?: return@map t
                t.copy(
                    status = if (liveTransfer.isSent) MigrationTransferStatus.SENT else MigrationTransferStatus.PENDING,
                    isProved = liveTransfer.isProved,
                    scheduledAtEpochSeconds =
                        now +
                            estimatedSecondsBetweenHeights(
                                live.tipHeight,
                                liveTransfer.scheduledHeight,
                                secondsPerBlock
                            ),
                )
            },
        preparations =
            preparations.map { p ->
                val liveTransfer = byPrepId[p.id] ?: return@map p
                p.copy(
                    status = if (liveTransfer.isSent) MigrationTransferStatus.SENT else MigrationTransferStatus.PENDING,
                    isProved = liveTransfer.isProved,
                    scheduledAtEpochSeconds =
                        now +
                            estimatedSecondsBetweenHeights(
                                live.tipHeight,
                                liveTransfer.scheduledHeight,
                                secondsPerBlock
                            ),
                )
            },
    )
}

/**
 * The write-through counterpart to [withLiveState]: folds the SDK's authoritative "sent" status
 * back into the persisted plan so a saved [MigrationPlan]'s `completedCount`/`nextPending`/
 * `isComplete` actually advance after a broadcast. The send path must persist this after every
 * successful `executeNextPendingTransfer()` — the home banner reads the RAW cached plan (not a
 * live read-time overlay like [withLiveState]), so without this write-through it stays stuck on
 * "0 of N transfers done" even though the SDK already recorded the send.
 *
 * Unlike [withLiveState] this deliberately leaves [MigrationTransfer.scheduledAtEpochSeconds]
 * alone — it's a status-only reconcile, so it never clobbers a user/engine reschedule with a
 * fresh tip-based estimate. Correlates by stable [MigrationTransfer.id], never
 * [MigrationTransfer.index] (ZIP 318 shuffles the two orderings apart — see [withLiveState]), and
 * only ever upgrades PENDING→SENT, so it can't regress a status the cache already advanced past
 * what a momentarily-stale live read reports.
 */
fun MigrationPlan.withLiveStatusOnly(live: MigrationTransferStates?): MigrationPlan {
    if (live == null) return this
    val sentIds = live.transfers.filter { it.isSent }.mapTo(mutableSetOf()) { it.id }
    if (sentIds.isEmpty()) return this
    return copy(
        transfers =
            transfers.map { t ->
                if (t.status != MigrationTransferStatus.SENT && t.id in sentIds) {
                    t.copy(status = MigrationTransferStatus.SENT)
                } else {
                    t
                }
            }
    )
}
