package co.electriccoin.zcash.ui.common.repository

import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.MigrationSweepTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSource
import co.electriccoin.zcash.ui.common.provider.KeystoneSDKProvider
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals

class KeystoneProposalRepositoryTest {
    @Test
    fun setMigrationSweepProposalPublishesAMigrationSweepTransactionProposal() {
        val repository: KeystoneProposalRepository =
            KeystoneProposalRepositoryImpl(
                accountDataSource = mockk<AccountDataSource>(),
                proposalDataSource = mockk<ProposalDataSource>(),
                keystoneSDKProvider = mockk<KeystoneSDKProvider>(),
            )
        val proposal = mockk<Proposal>()
        val amount = Zatoshi(1234L)

        repository.setMigrationSweepProposal(proposal, amount)

        val stored = repository.transactionProposal.value
        assertEquals(MigrationSweepTransactionProposal(amount, proposal), stored)
    }
}
