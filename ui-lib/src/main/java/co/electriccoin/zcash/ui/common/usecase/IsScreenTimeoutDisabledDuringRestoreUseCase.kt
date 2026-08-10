package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.common.model.WalletRestoringState
import co.electriccoin.zcash.ui.common.provider.IsKeepScreenOnDuringRestoreProvider
import co.electriccoin.zcash.ui.common.repository.WalletRepository
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

class IsScreenTimeoutDisabledDuringRestoreUseCase(
    private val walletRepository: WalletRepository,
    private val isKeepScreenOnDuringRestoreProvider: IsKeepScreenOnDuringRestoreProvider,
) {
    fun observe() =
        combine(
            walletRepository.walletRestoringState,
            isKeepScreenOnDuringRestoreProvider.observe()
        ) { restoringState, isKeepScreenOnDuringRestore ->
            isKeepScreenOnDuringRestore == true && restoringState in KEEP_OPEN_STATES
        }.distinctUntilChanged()

    companion object {
        private val KEEP_OPEN_STATES = listOf(WalletRestoringState.RESTORING, WalletRestoringState.RESYNCING)
    }
}
