package co.electriccoin.zcash.ui.common.repository

import cash.z.ecc.android.sdk.model.TransactionId
import cash.z.ecc.android.sdk.model.TransactionOverview
import cash.z.ecc.android.sdk.model.TransactionState
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.Zip318Kind
import org.junit.Assert.assertEquals
import org.junit.Test

class TransactionRepositorySentTransactionAmountTest {
    private fun overview(
        sentNoteCount: Int,
        receivedNoteCount: Int,
        totalReceived: Long,
        netValue: Long,
        zip318Kind: Zip318Kind
    ): TransactionOverview =
        TransactionOverview(
            txId = TransactionId.new(ByteArray(32)),
            minedHeight = null,
            expiryHeight = null,
            index = null,
            raw = null,
            isSentTransaction = true,
            netValue = Zatoshi(netValue),
            totalSpent = Zatoshi(netValue),
            totalReceived = Zatoshi(totalReceived),
            feePaid = null,
            isChange = false,
            receivedNoteCount = receivedNoteCount,
            sentNoteCount = sentNoteCount,
            memoCount = 0,
            blockTimeEpochSeconds = null,
            transactionState = TransactionState.Pending,
            isShielding = false,
            spentNoteCount = 0,
            poolCrossingValue = null,
            isTrusted = false,
            zip318Kind = zip318Kind
        )

    @Test
    fun ordinary_sent_transaction_uses_net_value() {
        val overview = overview(sentNoteCount = 1, receivedNoteCount = 0, totalReceived = 0, netValue = 5_000, zip318Kind = Zip318Kind.NOT_CLASSIFIED)
        assertEquals(5_000L, sentTransactionAmount(overview).value)
    }

    @Test
    fun mined_self_transfer_zero_zero_uses_total_received() {
        val overview = overview(sentNoteCount = 0, receivedNoteCount = 0, totalReceived = 500_000, netValue = 1_000, zip318Kind = Zip318Kind.NOT_CLASSIFIED)
        assertEquals(500_000L, sentTransactionAmount(overview).value)
    }

    @Test
    fun pending_migration_transfer_uses_total_received_not_fee() {
        // Reproduces the review-caught bug: sentNoteCount=0, receivedNoteCount=1 does NOT match
        // the (0,0) heuristic, so without the zip318Kind branch this would wrongly return the
        // fee-sized netValue instead of the real crossing amount.
        val overview =
            overview(sentNoteCount = 0, receivedNoteCount = 1, totalReceived = 499_000, netValue = 1_000, zip318Kind = Zip318Kind.TRANSFER)
        assertEquals(499_000L, sentTransactionAmount(overview).value)
    }

    @Test
    fun pending_migration_preparation_uses_total_received_not_fee() {
        val overview =
            overview(sentNoteCount = 0, receivedNoteCount = 3, totalReceived = 299_700, netValue = 300, zip318Kind = Zip318Kind.PREPARATION)
        assertEquals(299_700L, sentTransactionAmount(overview).value)
    }
}
