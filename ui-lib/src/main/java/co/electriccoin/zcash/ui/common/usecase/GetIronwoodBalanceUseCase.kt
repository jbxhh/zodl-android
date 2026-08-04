package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.sdk.extension.ZERO
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * The real, spendable Ironwood balance for the currently selected wallet account — the migration
 * destination pool, read the same way [GetOrchardBalanceUseCase] reads its source pool (raw,
 * un-folded, straight from the synchronizer) rather than [WalletAccount.unified], which folds
 * Orchard + Ironwood together — see [GetOrchardBalanceUseCase]'s doc for why that folding is wrong
 * for a screen that needs to show the two pools separately (Migration Progress's balance tracker
 * card, Figma "PR App Designs Q3'26" node 3480:7638).
 */
class GetIronwoodBalanceUseCase(
    private val synchronizerProvider: SynchronizerProvider,
    private val accountDataSource: AccountDataSource,
) {
    suspend operator fun invoke(): Zatoshi {
        val synchronizer = synchronizerProvider.getSynchronizer()
        val account = accountDataSource.getSelectedAccount()
        val balances = synchronizer.walletBalances.filterNotNull().first()
        return balances[account.sdkAccount.accountUuid]?.ironwood?.available ?: Zatoshi.ZERO
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(): Flow<Zatoshi?> =
        combine(
            synchronizerProvider.synchronizer,
            accountDataSource.selectedAccount
        ) { synchronizer, account ->
            synchronizer to account
        }.flatMapLatest { (synchronizer, account) ->
            if (synchronizer == null || account == null) {
                flowOf(null)
            } else {
                synchronizer.walletBalances.map { balances ->
                    balances?.get(account.sdkAccount.accountUuid)?.ironwood?.available
                }
            }
        }
}
