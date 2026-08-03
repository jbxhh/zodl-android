package co.electriccoin.zcash.ui.common.model.migration

import cash.z.ecc.android.sdk.ext.ZcashSdk
import co.electriccoin.zcash.migration.BuildConfig

/**
 * Formats a migration plan's total span so it reflects whatever interval the current
 * [cash.z.ecc.android.sdk.OrchardMigrationSdk] implementation actually schedules transfers at —
 * minutes for the compressed debug cadence, hours for the real one — instead of a hardcoded
 * "~24 hours" that only matched production timing.
 *
 * [fineGrained] (default: testnet builds) keeps minute resolution above one hour ("~1 h 15 min")
 * — the whole testnet plan spans ~1-2 h (12-block buckets), so integer-hour bucketing collapsed
 * most transfers into an identical "~1 hours" label. Mainnet keeps the coarse hour display.
 *
 * Every duration this formats — a transfer's own "sent/due in" hint or the header's total-span/
 * remaining estimate — is floored at [TESTNET_PRIVACY_FLOOR_SECONDS]/[MAINNET_PRIVACY_FLOOR_SECONDS]
 * (2026-08-03, decision with the user): never reveal a migration timing more precise than that,
 * on either side of "now" (an "ago" as well as an upcoming estimate) — a tighter number could help
 * correlate this wallet's broadcasts across the network. [fineGrained] doubles as the network
 * selector for which floor applies, matching its own existing default derivation, so callers that
 * pin [fineGrained] for a deterministic test get a deterministic floor for free.
 */
fun formatMigrationDuration(
    totalSeconds: Long,
    fineGrained: Boolean = isTestnetBuildFlavor(),
): String {
    val floorSeconds = if (fineGrained) TESTNET_PRIVACY_FLOOR_SECONDS else MAINNET_PRIVACY_FLOOR_SECONDS
    val seconds = totalSeconds.coerceAtLeast(floorSeconds)
    val hours = seconds / 3600
    val minutesPastHour = (seconds % 3600) / 60
    return when {
        seconds < 3600 -> "~${seconds / 60} min"
        !fineGrained -> "~$hours ${if (hours == 1L) "hour" else "hours"}"
        minutesPastHour == 0L -> "~$hours h"
        else -> "~$hours h $minutesPastHour min"
    }
}

private const val TESTNET_PRIVACY_FLOOR_SECONDS = 600L // 10 minutes
private const val MAINNET_PRIVACY_FLOOR_SECONDS = 3600L // 1 hour

internal fun isTestnetBuildFlavor(): Boolean = BuildConfig.FLAVOR.contains("testnet", ignoreCase = true)

/**
 * Estimates the wall-clock duration, in seconds, spanned by a block-height difference, using the
 * network's average block time.
 *
 * [cash.z.ecc.android.sdk.TransferProposal]'s `anchorHeight`/`nextExecutableAfterHeight`/
 * `expiryHeight` are block heights, not timestamps — they must never be used directly as (or
 * compared against) epoch seconds. Doing so previously made every scheduled transfer appear
 * decades overdue: a block height (~4.18M) stored as `scheduledAtEpochSeconds` and later compared
 * against a real 2026 `Instant` measures the gap back to the Unix epoch, not to the actual
 * scheduled time.
 */
fun estimatedSecondsBetweenHeights(
    fromHeight: Long,
    toHeight: Long,
    // 75s protocol target as fallback. Pass OrchardMigrationSdk.estimatedSecondsPerBlock()
    // wherever an SDK is in reach — testnet's minimum-difficulty bursts make the constant a
    // large overestimate (observed live: "~1 h" plans coming due within minutes).
    secondsPerBlock: Long = ZcashSdk.BLOCK_INTERVAL_MILLIS / 1000,
): Long = (toHeight - fromHeight) * secondsPerBlock
