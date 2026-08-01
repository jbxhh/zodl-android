package co.electriccoin.zcash.ui.common.model.metadata

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Serializes [MigrationTxKindV3] as its stable string [MigrationTxKindV3.value] and falls back to
 * [MigrationTxKindV3.TRANSFER] on an unknown value rather than throwing — an unrecognized kind from
 * a newer schema must not abort decoding of the whole (otherwise valid) metadata file. Mirrors the
 * defensive shape of `SwapStatusSerializer`.
 */
object MigrationTxKindSerializer : KSerializer<MigrationTxKindV3> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("MigrationTxKind", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: MigrationTxKindV3
    ) {
        encoder.encodeString(value.value)
    }

    override fun deserialize(decoder: Decoder): MigrationTxKindV3 {
        val value = decoder.decodeString()
        return MigrationTxKindV3.entries.firstOrNull { it.value == value } ?: MigrationTxKindV3.TRANSFER
    }
}
