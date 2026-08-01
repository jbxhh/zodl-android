package co.electriccoin.zcash.ui.common.serialization.metadata

import co.electriccoin.zcash.ui.common.model.metadata.AccountMetadataV3
import co.electriccoin.zcash.ui.common.model.metadata.MetadataV3
import co.electriccoin.zcash.ui.common.model.metadata.MigrationTxKindV3
import co.electriccoin.zcash.ui.common.model.metadata.MigrationTxMetadataV3
import co.electriccoin.zcash.ui.common.model.metadata.SwapsMetadataV3
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class MetadataMigrationSerializationTest {
    private val serializer = MetadataSerializer()

    private fun emptyAccount() =
        AccountMetadataV3(
            bookmarked = emptyList(),
            read = emptyList(),
            annotations = emptyList(),
            swaps = SwapsMetadataV3(swapIds = emptyList(), lastUsedAssetHistory = emptySet()),
        )

    private fun roundTrip(metadata: MetadataV3): MetadataV3 {
        val out = ByteArrayOutputStream()
        serializer.serialize(out, metadata)
        return serializer.deserialize(ByteArrayInputStream(out.toByteArray()))
    }

    @Test
    fun `migration records survive a serialize-deserialize round trip`() {
        val now = Instant.ofEpochMilli(1_700_000_000_000L)
        val metadata =
            MetadataV3(
                lastUpdated = now,
                accountMetadata =
                    emptyAccount().copy(
                        migrations =
                            listOf(
                                MigrationTxMetadataV3(txId = "aa", kind = MigrationTxKindV3.PREP_SPLIT, lastUpdated = now),
                                MigrationTxMetadataV3(txId = "bb", kind = MigrationTxKindV3.TRANSFER, lastUpdated = now),
                            )
                    ),
            )

        val restored = roundTrip(metadata)

        assertEquals(metadata.accountMetadata.migrations, restored.accountMetadata.migrations)
    }

    @Test
    fun `a V3 file written before the migrations field decodes to an empty list`() {
        // A pre-existing V3 metadata JSON that predates the "migrations" field must still decode.
        val legacyJson =
            """
            {"version":3,"lastUpdated":1700000000000,"accountMetadata":{"bookmarked":[],"read":[],
            "annotations":[],"swaps":{"swapIds":[],"lastUsedAssetHistory":[]}}}
            """.trimIndent().replace("\n", "")

        val restored = serializer.deserialize(ByteArrayInputStream(legacyJson.toByteArray()))

        assertEquals(emptyList(), restored.accountMetadata.migrations)
    }

    @Test
    fun `an unknown migration kind falls back to TRANSFER instead of failing the whole decode`() {
        val json =
            """
            {"version":3,"lastUpdated":1700000000000,"accountMetadata":{"bookmarked":[],"read":[],
            "annotations":[],"swaps":{"swapIds":[],"lastUsedAssetHistory":[]},
            "migrations":[{"txId":"aa","kind":"someFutureKind","lastUpdated":1700000000000}]}}
            """.trimIndent().replace("\n", "")

        val restored = serializer.deserialize(ByteArrayInputStream(json.toByteArray()))

        assertEquals(1, restored.accountMetadata.migrations.size)
        assertEquals(
            MigrationTxKindV3.TRANSFER,
            restored.accountMetadata.migrations
                .first()
                .kind
        )
    }
}
