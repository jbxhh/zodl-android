package co.electriccoin.zcash.ui.common.repository

import cash.z.ecc.android.sdk.MigrationTransferStates
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The live migration engine's per-transfer state, published by `MigrationLiveDriverImpl`'s own
 * loop after every step it drives. No screen calls `OrchardMigrationSdk.getMigrationTransferStates()`
 * on its own poll anymore — that used to mean the Progress screen's independent read and the live
 * driver's prove/broadcast calls competed for the SDK's single-threaded DB I/O executor
 * (`SdkDispatchers.DATABASE_IO` — a genuinely single OS thread, per its own kdoc: no WAL, so all
 * `RustBackend`/`MigrationRustBackend` calls serialize onto it), producing a long white-screen wait
 * whenever the user opened Progress right as the driver was mid-step (e.g. proving, which blocks
 * that one thread for real wall-clock seconds). Observing this instead costs nothing: [observe]
 * never blocks — it just replays whatever the driver last saw, published from a read the driver was
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
    fun observe(accountKeyId: String): StateFlow<MigrationTransferStates?>

    fun publish(accountKeyId: String, states: MigrationTransferStates?)
}

class MigrationTransferStateRepositoryImpl : MigrationTransferStateRepository {
    private val perAccount = mutableMapOf<String, MutableStateFlow<MigrationTransferStates?>>()

    @Synchronized
    private fun flowFor(accountKeyId: String): MutableStateFlow<MigrationTransferStates?> =
        perAccount.getOrPut(accountKeyId) { MutableStateFlow(null) }

    override fun observe(accountKeyId: String): StateFlow<MigrationTransferStates?> = flowFor(accountKeyId)

    override fun publish(accountKeyId: String, states: MigrationTransferStates?) {
        flowFor(accountKeyId).value = states
    }
}
