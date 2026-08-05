package co.electriccoin.zcash.ui.screen.migration.sending

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.NetworkPrivacyOptions
import cash.z.ecc.android.sdk.TransferAttemptOutcome
import cash.z.ecc.android.sdk.TransferResult
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.model.LceState
import co.electriccoin.zcash.ui.common.model.migration.MigrationMode
import co.electriccoin.zcash.ui.common.model.migration.MigrationTransferFailureState
import co.electriccoin.zcash.ui.common.model.migration.migrationFailureMessage
import co.electriccoin.zcash.ui.common.model.migration.withLiveStatusOnly
import co.electriccoin.zcash.ui.common.model.mutableLce
import co.electriccoin.zcash.ui.common.model.stateIn
import co.electriccoin.zcash.ui.common.model.withLce
import co.electriccoin.zcash.ui.common.provider.IsMigrationTorEnabledStorageProvider
import co.electriccoin.zcash.ui.common.provider.PendingMigrationTorFailureStorageProvider
import co.electriccoin.zcash.ui.common.repository.MigrationPlanRepository
import co.electriccoin.zcash.ui.common.repository.PendingMigrationTorFailureDecisionRepository
import co.electriccoin.zcash.ui.common.usecase.ErrorMapperUseCase
import co.electriccoin.zcash.ui.common.usecase.GetOrchardMigrationSdkUseCase
import co.electriccoin.zcash.ui.common.usecase.ScheduleNextMigrationWindowUseCase
import co.electriccoin.zcash.ui.screen.migration.complete.MigrationCompleteArgs
import co.electriccoin.zcash.ui.screen.migration.success.MigrationSuccessArgs
import co.electriccoin.zcash.ui.screen.migration.torfailure.MigrationTorFailureArgs
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext

class MigrationSendingVM(
    private val getOrchardMigrationSdk: GetOrchardMigrationSdkUseCase,
    private val migrationPlanRepository: MigrationPlanRepository,
    private val scheduleNextMigrationWindow: ScheduleNextMigrationWindowUseCase,
    private val navigationRouter: NavigationRouter,
    private val errorStateMapper: ErrorMapperUseCase,
    private val isMigrationTorEnabledStorageProvider: IsMigrationTorEnabledStorageProvider,
    private val pendingMigrationTorFailureDecisionRepository: PendingMigrationTorFailureDecisionRepository,
    private val pendingMigrationTorFailureStorageProvider: PendingMigrationTorFailureStorageProvider,
) : ViewModel() {
    private val sendLce = mutableLce<Unit>()
    private val failure = MutableStateFlow<SendFailure?>(null)

    private sealed interface SendFailure {
        data class Engine(
            val result: TransferResult
        ) : SendFailure

        data object NotReady : SendFailure
    }

    private fun SendFailure.message(): String =
        when (this) {
            is SendFailure.Engine -> migrationFailureMessage(result)
            SendFailure.NotReady -> "This transfer isn't ready to send yet. Please try again in a moment."
        }

    init {
        pendingMigrationTorFailureDecisionRepository.decision
            .filterNotNull()
            .onEach { useTor ->
                pendingMigrationTorFailureDecisionRepository.clear()
                sendLce.execute { sendOnce(useTor) }
            }.launchIn(viewModelScope)
        // If a Tor-failure decision is already sitting here at construction time (e.g. this VM was
        // recreated while one was pending), the collector above fires on its very first emission
        // and is the more specific, more recent user decision — calling send() here too would race
        // it via sendLce.execute()'s cancel-previous-job semantics (MutableLce.execute), silently
        // cancelling one of two legitimate send attempts. Only kick off the default send when there
        // is nothing pending for the collector to react to.
        if (pendingMigrationTorFailureDecisionRepository.decision.value == null) {
            send()
        }
    }

    val state: StateFlow<LceState<MigrationSendingState>> =
        combine(sendLce.state, failure) { _, f ->
            MigrationSendingState(
                failureSheet =
                    f?.let {
                        MigrationTransferFailureState(
                            message = it.message(),
                            onRetry = {
                                failure.value = null
                                send()
                            },
                            onDismiss = {
                                failure.value = null
                                navigationRouter.back()
                            },
                        )
                    },
                // The escape hatch stays disabled while a send is actively in flight (no failure
                // shown yet) — matches TransactionProgressVM.createSendingState()'s
                // onBack = { /* do nothing */ } while sending, wired to a real back action only once
                // there's a failure sheet the user might need to escape from.
                onBack =
                    if (f == null) {
                        {}
                    } else {
                        ::onBack
                    },
            )
        }.withLce(sendLce, errorStateMapper::mapToState)
            .stateIn(this)

    private fun send() = sendLce.execute { sendOnce(useTor = isMigrationTorEnabledStorageProvider.get()) }

    fun onBack() = navigationRouter.back()

    private suspend fun sendOnce(useTor: Boolean) {
        val sdk = getOrchardMigrationSdk() ?: error("MigrationSendingVM: no wallet available to send")
        var outcome: TransferAttemptOutcome? = null
        var attempt = 0
        while ((outcome == null || outcome is TransferAttemptOutcome.NothingDue || outcome is TransferAttemptOutcome.AwaitingProof) &&
            attempt < SEND_MAX_ATTEMPTS
        ) {
            if (attempt > 0) delay(SEND_RETRY_DELAY_MS)
            withContext(NonCancellable) {
                outcome = sdk.executeNextPendingTransfer(NetworkPrivacyOptions(useTor = useTor), useEstimatedTip = true)
                Twig.debug { "MIGRATION_DIAG SendingVM: attempt=${attempt + 1} outcome=$outcome" }
            }
            attempt++
        }
        when (val o = outcome) {
            is TransferAttemptOutcome.Executed -> {
                when (val r = o.result) {
                    is TransferResult.Success -> {
                        // The one unambiguous "problem resolved" signal — clears a pending background Tor
                        // failure (see PendingMigrationTorFailureStorageProvider) so app-open reconciliation
                        // stops re-routing through this screen. Left `true` on every other outcome
                        // (including a renewed Tor failure below), so it keeps re-surfacing until an actual
                        // successful send happens. A no-op if nothing was pending.
                        pendingMigrationTorFailureStorageProvider.store(false)
                        // Re-arms the next window for a resumed/manually-confirmed transfer in a
                        // multi-transfer AUTOMATIC plan; no-ops once the plan is already complete.
                        scheduleNextMigrationWindow()
                        // Write the SDK's authoritative "sent" status back into the persisted plan, so the
                        // home banner's raw cached completedCount/isComplete actually advance (without this
                        // it stays stuck on "0 of N transfers done" even though the send landed). The
                        // isComplete check just below then reads the reconciled plan, not the stale one.
                        val plan =
                            migrationPlanRepository
                                .load()
                                ?.withLiveStatusOnly(sdk.getMigrationTransferStates())
                                ?.also { migrationPlanRepository.save(it) }
                        if (plan?.mode == MigrationMode.AUTOMATIC && plan.isComplete) {
                            // This was the plan's last transfer — one Migration Complete screen covers
                            // both this (foreground, just confirmed) and the background-completion case
                            // (CheckMigrationRecoveryUseCase, on next app open), rather than two.
                            navigationRouter.forward(MigrationCompleteArgs)
                        } else {
                            navigationRouter.forward(MigrationSuccessArgs(r.txId))
                        }
                    }

                    // A NetworkError whose failure is specifically Tor-attributable is routed to its own
                    // sheet (offering "continue without Tor") instead of the generic "Couldn't Send" one,
                    // since the fix (drop Tor) differs from a real network outage.
                    is TransferResult.NetworkError -> {
                        if (r.isTorFailure) {
                            navigationRouter.forward(MigrationTorFailureArgs)
                        } else {
                            failure.value = SendFailure.Engine(r)
                        }
                    }

                    else -> {
                        failure.value = SendFailure.Engine(r)
                    }
                }
            }

            // NothingDue or AwaitingProof after max attempts: the transfer isn't ready yet.
            // The foreground sync + Lane A hook will prove it; the user can retry later.
            else -> {
                failure.value = SendFailure.NotReady
            }
        }
    }

    companion object {
        private const val SEND_MAX_ATTEMPTS = 3
        private const val SEND_RETRY_DELAY_MS = 1500L
    }
}
