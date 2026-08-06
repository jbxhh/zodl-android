package co.electriccoin.zcash.ui.screen.migration.invalid

import androidx.lifecycle.ViewModel
import cash.z.ecc.android.sdk.AttentionReason
import cash.z.ecc.android.sdk.MigrationState
import cash.z.ecc.android.sdk.MigrationTransferStates
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.model.LceState
import co.electriccoin.zcash.ui.common.model.groupLce
import co.electriccoin.zcash.ui.common.model.migration.MigrationAttentionKind
import co.electriccoin.zcash.ui.common.model.migration.MigrationPlan
import co.electriccoin.zcash.ui.common.model.migration.affectedTransferIndices
import co.electriccoin.zcash.ui.common.model.migration.toMigrationRangeText
import co.electriccoin.zcash.ui.common.model.migration.toUiKind
import co.electriccoin.zcash.ui.common.model.mutableLce
import co.electriccoin.zcash.ui.common.model.stateIn
import co.electriccoin.zcash.ui.common.model.withLce
import co.electriccoin.zcash.ui.common.model.toStorageKeyId
import co.electriccoin.zcash.ui.common.repository.MigrationPlanRepository
import co.electriccoin.zcash.ui.common.repository.RestartMigrationScheduleRepository
import co.electriccoin.zcash.ui.common.usecase.ErrorMapperUseCase
import co.electriccoin.zcash.ui.common.usecase.GetOrchardMigrationSdkUseCase
import co.electriccoin.zcash.ui.common.usecase.GetSelectedWalletAccountUseCase
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.migration.review.MigrationReviewArgs
import co.electriccoin.zcash.ui.common.model.migration.MigrationMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlin.time.Clock

class MigrationTransferInvalidVM(
    private val getOrchardMigrationSdk: GetOrchardMigrationSdkUseCase,
    private val getSelectedWalletAccount: GetSelectedWalletAccountUseCase,
    private val migrationPlanRepository: MigrationPlanRepository,
    private val restartMigrationScheduleRepository: RestartMigrationScheduleRepository,
    private val navigationRouter: NavigationRouter,
    private val errorStateMapper: ErrorMapperUseCase,
) : ViewModel() {

    private val loadLce = mutableLce<Unit>()
    private val restartLce = mutableLce<Unit>()

    // Populated once at load from the SDK's actual MigrationState.RequiresAttention.reason — the
    // real distinction between spec §6.2 (InvalidTransfer) and §6.3 (TransferExpired), replacing
    // the old hasInvalidTransfers()-only boolean that could never tell the two apart. liveStates is
    // read alongside it (same load pass) so affectedTransferIndices() can correlate by the
    // transfer's stable id rather than assuming every not-yet-completed cached transfer is invalid.
    private data class RecoveryInfo(val reason: AttentionReason?, val liveStates: MigrationTransferStates?)

    private val recoveryInfo = MutableStateFlow(RecoveryInfo(null, null))

    init {
        loadLce.execute {
            val sdk = getOrchardMigrationSdk()
            val reason = (sdk.getMigrationState() as? MigrationState.RequiresAttention)?.reason
            val liveStates = sdk.getMigrationTransferStates()
            recoveryInfo.value = RecoveryInfo(reason, liveStates)
        }
    }

    val state: StateFlow<LceState<MigrationTransferInvalidState>> =
        combine(migrationPlanRepository.observe(), recoveryInfo) { plan, info -> buildState(plan, info) }
            .withLce(groupLce(loadLce, restartLce), errorStateMapper::mapToState)
            .stateIn(this)

    private fun buildState(plan: MigrationPlan?, info: RecoveryInfo): MigrationTransferInvalidState {
        val completed = plan?.completedCount ?: 0
        val total = plan?.totalCount ?: 0
        val reason = info.reason
        val kind = reason?.toUiKind() ?: MigrationAttentionKind.TRANSFER_EXPIRED
        val affectedIndices = if (plan != null && reason != null) {
            reason.affectedTransferIndices(plan, info.liveStates, Clock.System.now().epochSeconds)
        } else {
            emptyList()
        }
        // Falls back to the old "everything after the last completed transfer" guess only when the
        // real affected set couldn't be determined (reason not yet loaded, or a stale cache with no
        // matching id) — never silently blank.
        val rangeText = affectedIndices.toMigrationRangeText() ?: run {
            val firstInvalid = completed + 1
            if (total > firstInvalid) "$firstInvalid–$total" else "$firstInvalid"
        }
        return MigrationTransferInvalidState(
            kind = kind,
            completedCount = completed,
            totalCount = total,
            remainingCount = affectedIndices.size.takeIf { it > 0 } ?: (total - completed),
            invalidRange = stringRes(rangeText),
            onContinue = ::onContinue,
            onBack = ::onBack,
        )
    }

    private fun onContinue() = restartLce.execute {
        val sdk = getOrchardMigrationSdk()
        // restartCurrentMigrationStep()'s own doc requires its returned schedule to go through the
        // normal user confirmation flow rather than being discarded — hand it to MigrationReviewVM
        // instead of letting it independently re-propose (see RestartMigrationScheduleRepository's
        // doc for why this is a separate slot from the Keystone sign/scan hand-off).
        val schedule = sdk.restartCurrentMigrationStep()
        val accountKeyId = getSelectedWalletAccount().sdkAccount.accountUuid.toStorageKeyId()
        restartMigrationScheduleRepository.set(accountKeyId, schedule)
        navigationRouter.replace(MigrationReviewArgs(MigrationMode.AUTOMATIC))
    }

    private fun onBack() = navigationRouter.back()
}
