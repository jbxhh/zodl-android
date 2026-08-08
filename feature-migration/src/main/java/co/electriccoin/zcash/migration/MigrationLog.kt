package co.electriccoin.zcash.migration

import co.electriccoin.zcash.ui.util.loggable

/**
 * The single logger for all app-side migration code. Plain `MIGRATION` logcat tag with no
 * column padding — watch with `adb logcat -s MIGRATION` (SDK-Rust migration lines keep their
 * `MIGRATION_DIAG` marker under `cash.z.rust.logs`).
 */
val migrationLog = loggable("MIGRATION")
