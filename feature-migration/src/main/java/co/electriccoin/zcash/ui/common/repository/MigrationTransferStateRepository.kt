package co.electriccoin.zcash.ui.common.repository

import cash.z.ecc.android.sdk.MigrationTransferStates
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * One atomic live readout of the migration engine: the per-transfer states plus the tip-estimate
 * pair needed to render them (`OrchardMigrationSdk.estimatedChainTip()`/`estimatedSecondsPerBlock()`)
 * — all three are read together from the same call site, at the same instant, so a consumer never
 * pairs a fresh [states] with a stale tip estimate or vice versa.
 *
 * [estimatedTip] follows [cash.z.ecc.android.sdk.OrchardMigrationSdk.estimatedChainTip]'s own
 * "negative means unavailable" convention — callers fall back to `states.tipHeight` in that case,
 * same as before this type existed.
 */
data class MigrationLiveReadout(
    val states: MigrationTransferStates?,
    val estimatedTip: Long,
    val estimatedSecondsPerBlock: Long,
)

/**
 * The live migration engine's readout, published by `MigrationLiveDriverImpl`'s own loop after
 * every step it drives (and periodically during a long re-armed wait — see the driver's own kdoc).
 * No screen calls `OrchardMigrationSdk.getMigrationTransferStates()`/`estimatedChainTip()`/
 * `estimatedSecondsPerBlock()` on its own poll anymore — those used to mean the Progress screen's
 * independent reads and the live driver's prove/broadcast calls competed for the SDK's
 * single-threaded DB I/O executor (`SdkDispatchers.DATABASE_IO` — a genuinely single OS thread, per
 * its own kdoc: no WAL, so all `RustBackend`/`MigrationRustBackend` calls serialize onto it,
 * regardless of which Kotlin DB-mutex lane a given call is on), producing a long white-screen wait
 * whenever the user opened Progress right as the driver was mid-step (e.g. proving, which blocks
 * that one thread for real wall-clock seconds). Observing this instead costs nothing: [observe]
 * never blocks — it just replays whatever the driver last saw, published from reads the driver was
 * already going to do as part of its own already-serialized loop, not a second concurrent caller.
 *
 * Tagged per account, mirroring [PendingMigrationScheduleRepository] — a Zodl and a Keystone
 * account migrating in parallel each get their own independent published value.
 *
 * [observe] returns `null` until the live driver has published at least once for that account this
 * process's lifetime (e.g. before its very first loop iteration completes, or if no migration is
 * `in_progress` so the driver never runs at all) — callers needing a value in that window fall back
 * to one direct SDK read of their own, same as before this repository existed.
 */
interface MigrationTransferStateRepository {
    fun observe(accountKeyId: String): StateFlow<MigrationLiveReadout?>

    fun publish(accountKeyId: String, readout: MigrationLiveReadout)
}

class MigrationTransferStateRepositoryImpl : MigrationTransferStateRepository {
    private val perAccount = mutableMapOf<String, MutableStateFlow<MigrationLiveReadout?>>()

    @Synchronized
    private fun flowFor(accountKeyId: String): MutableStateFlow<MigrationLiveReadout?> =
        perAccount.getOrPut(accountKeyId) { MutableStateFlow(null) }

    override fun observe(accountKeyId: String): StateFlow<MigrationLiveReadout?> = flowFor(accountKeyId)

    override fun publish(accountKeyId: String, readout: MigrationLiveReadout) {
        flowFor(accountKeyId).value = readout
    }
}
