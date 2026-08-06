package co.electriccoin.zcash.ui.screen.migration.review

import androidx.lifecycle.ViewModel
import cash.z.ecc.android.sdk.MigrationSchedule
import cash.z.ecc.android.sdk.TransferProposal
import cash.z.ecc.android.sdk.TransferResult
import cash.z.ecc.android.sdk.ext.convertZatoshiToZec
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.model.KeystoneAccount
import co.electriccoin.zcash.ui.common.model.LceState
import co.electriccoin.zcash.ui.common.model.toStorageKeyId
import co.electriccoin.zcash.ui.common.model.guardLoading
import co.electriccoin.zcash.ui.common.model.migration.MigrationKeystoneRound
import co.electriccoin.zcash.ui.common.model.migration.MigrationMode
import co.electriccoin.zcash.ui.common.model.migration.MigrationTransferFailureState
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.common.model.migration.estimatedSecondsBetweenHeights
import co.electriccoin.zcash.ui.common.model.migration.formatMigrationDuration
import co.electriccoin.zcash.ui.common.model.migration.migrationFailureMessage
import co.electriccoin.zcash.ui.common.model.groupLce
import co.electriccoin.zcash.ui.common.model.mutableLce
import co.electriccoin.zcash.ui.common.datasource.ZashiSpendingKeyDataSource
import co.electriccoin.zcash.ui.common.model.stateIn
import co.electriccoin.zcash.ui.common.model.withLce
import co.electriccoin.zcash.ui.common.repository.BiometricRepository
import co.electriccoin.zcash.ui.common.repository.BiometricRequest
import co.electriccoin.zcash.ui.common.repository.BiometricsCancelledException
import co.electriccoin.zcash.ui.common.repository.BiometricsFailureException
import co.electriccoin.zcash.ui.common.repository.ExchangeRateRepository
import co.electriccoin.zcash.ui.common.repository.KeystoneProposalRepository
import co.electriccoin.zcash.ui.common.repository.PendingMigrationScheduleRepository
import co.electriccoin.zcash.ui.common.repository.RestartMigrationScheduleRepository
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepository
import co.electriccoin.zcash.ui.common.usecase.ErrorMapperUseCase
import co.electriccoin.zcash.ui.common.usecase.FinalizeMigrationScheduleUseCase
import co.electriccoin.zcash.ui.common.usecase.GetOrchardBalanceUseCase
import co.electriccoin.zcash.ui.common.usecase.GetOrchardMigrationSdkUseCase
import co.electriccoin.zcash.ui.common.usecase.GetSelectedWalletAccountUseCase
import co.electriccoin.zcash.ui.common.usecase.SubmitProposalUseCase
import co.electriccoin.zcash.ui.common.wallet.ExchangeRateState
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.design.util.stringResByDynamicCurrencyNumber
import co.electriccoin.zcash.ui.screen.migration.keystonesign.MigrationKeystoneSignArgs
import co.electriccoin.zcash.ui.screen.signkeystonetransaction.SignKeystoneTransactionArgs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.math.BigDecimal
import java.math.MathContext

class MigrationReviewVM(
    private val args: MigrationReviewArgs,
    private val getOrchardMigrationSdk: GetOrchardMigrationSdkUseCase,
    private val pendingMigrationScheduleRepository: PendingMigrationScheduleRepository,
    private val restartMigrationScheduleRepository: RestartMigrationScheduleRepository,
    private val finalizeMigrationSchedule: FinalizeMigrationScheduleUseCase,
    private val navigationRouter: NavigationRouter,
    private val exchangeRateRepository: ExchangeRateRepository,
    private val getSelectedWalletAccount: GetSelectedWalletAccountUseCase,
    private val getOrchardBalance: GetOrchardBalanceUseCase,
    private val errorStateMapper: ErrorMapperUseCase,
    private val zashiSpendingKeyDataSource: ZashiSpendingKeyDataSource,
    private val biometricRepository: BiometricRepository,
    private val zashiProposalRepository: ZashiProposalRepository,
    private val keystoneProposalRepository: KeystoneProposalRepository,
    private val submitProposal: SubmitProposalUseCase,
) : ViewModel() {

    // proposeImmediateMigration() now returns an ordinary send-max Proposal (bypassing the
    // migration engine entirely — see OrchardMigrationSdk's kdoc), which carries no amount or
    // destination of its own (only totalFeeRequired()/transactionCount()); the amount shown is
    // this account's Orchard balance at propose time (the whole point of a send-max sweep).
    private sealed class ReviewProposal {
        data class Automatic(val schedule: MigrationSchedule, val keystoneRunCount: Int?) : ReviewProposal()
        data class Immediate(val proposal: Proposal, val amountZatoshi: Long) : ReviewProposal()
    }

    // Measured block rate captured at propose time; 75s until then. Drives every
    // height-to-time label on this screen (bursty testnet vs the protocol constant).
    @Volatile
    private var secondsPerBlock: Long = 75L

    private val proposeLce = mutableLce<ReviewProposal>()
    private val confirmLce = mutableLce<Unit>()
    private val isKeystoneAccount = getSelectedWalletAccount.observe().map { it is KeystoneAccount }
    private val failure = MutableStateFlow<TransferResult?>(null)

    init {
        proposeLce.execute {
            val sdk = getOrchardMigrationSdk()
            when (args.mode) {
                MigrationMode.IMMEDIATE -> {
                    val amount = getOrchardBalance().value
                    ReviewProposal.Immediate(sdk.proposeImmediateMigration(), amount)
                }
                MigrationMode.AUTOMATIC -> {
                    // If MigrationTransferInvalidVM.onContinue() already obtained a fresh schedule
                    // via restartCurrentMigrationStep() — whose own doc requires that returned
                    // schedule to go through this normal confirmation flow rather than being
                    // silently re-proposed — reuse that exact schedule instead of calling
                    // proposeMigrationTransfers() again (see RestartMigrationScheduleRepository's
                    // doc: the two calls compute independent guesses over the same balance that
                    // aren't guaranteed to agree). Falls back to a fresh proposal for every
                    // ordinary, non-recovery entry into this screen.
                    val accountKeyId = getSelectedWalletAccount().sdkAccount.accountUuid.toStorageKeyId()
                    val schedule = restartMigrationScheduleRepository.consume(accountKeyId) ?: sdk.proposeMigrationTransfers()
                    // IMMEDIATE has no Keystone branch at all (a documented pre-existing gap —
                    // see MigrationReviewVM.confirmAutomatic()'s Keystone check below), so round
                    // display is AUTOMATIC-only. Stateless preview, called fresh on every Review
                    // entry — never cached.
                    val keystoneRunCount = if (getSelectedWalletAccount() is KeystoneAccount) {
                        sdk.estimateMigrationRunCount()
                    } else {
                        null
                    }
                    secondsPerBlock = sdk.estimatedSecondsPerBlock()
                    logProposedPlan(schedule)
                    ReviewProposal.Automatic(schedule, keystoneRunCount)
                }
            }
        }
    }

    val state: StateFlow<LceState<MigrationReviewState>> =
        combine(
            proposeLce.state, exchangeRateRepository.state, isKeystoneAccount, failure, confirmLce.state
        ) { lce, rate, isKeystone, f, confirmState ->
            lce.success?.let { proposal -> createState(proposal, confirmState.loading, rate, isKeystone, f) }
        }.withLce(groupLce(proposeLce, confirmLce), errorStateMapper::mapToState)
            .stateIn(this)

    private fun createState(
        proposal: ReviewProposal,
        isConfirming: Boolean,
        exchangeRateState: ExchangeRateState,
        isKeystone: Boolean,
        failureResult: TransferResult?,
    ): MigrationReviewState = when (proposal) {
        is ReviewProposal.Automatic -> createAutomaticState(proposal, isConfirming, exchangeRateState, isKeystone, failureResult)
        is ReviewProposal.Immediate -> createImmediateState(proposal, isConfirming, exchangeRateState)
    }

    private fun createAutomaticState(
        proposal: ReviewProposal.Automatic,
        isConfirming: Boolean,
        exchangeRateState: ExchangeRateState,
        isKeystone: Boolean,
        failureResult: TransferResult?,
    ): MigrationReviewState {
        val sched = proposal.schedule
        val total = sched.transfers.sumOf { it.amountZatoshi }
        // From the plan's "now" reference (anchorHeight — every transfer shares the same plan-time
        // tip) to the LAST transfer's height, matching scheduledLabel()'s per-transfer calculation
        // below and MigrationScheduledVM/MigrationProgressVM's createdAt-to-last-scheduled span —
        // NOT firstAtHeight-to-lastAtHeight, which omits the wait before the first transfer and
        // previously made this summary disagree with the per-transfer rows and the other two
        // migration screens (confirmed live: header claimed a shorter span than the last
        // transfer's own "due in ~Nh" label showed).
        val anchorHeight = sched.transfers.minOfOrNull { it.anchorHeight } ?: 0L
        val lastAtHeight = sched.transfers.maxOfOrNull { it.nextExecutableAfterHeight } ?: 0L
        val spanSeconds = estimatedSecondsBetweenHeights(anchorHeight, lastAtHeight, secondsPerBlock)
        return MigrationReviewState(
            mode = args.mode,
            totalAmount = stringRes(Zatoshi(total)),
            totalFiatAmount = fiatAmount(Zatoshi(total), exchangeRateState),
            estimatedDuration = stringRes(formatMigrationDuration(spanSeconds)),
            preparations = sched.preparations.mapIndexed { i, p ->
                MigrationReviewPreparationState(number = i + 1, scheduledLabel = scheduledLabelForPrep(p, sched))
            },
            transfers = sched.transfers.mapIndexed { i, t ->
                MigrationReviewTransferState(
                    index = i + 1,
                    totalCount = sched.transfers.size,
                    amount = stringRes(Zatoshi(t.amountZatoshi)),
                    fiatAmount = fiatAmount(Zatoshi(t.amountZatoshi), exchangeRateState),
                    scheduledLabel = scheduledLabel(t),
                )
            },
            isKeystone = isKeystone,
            keystoneRound = proposal.keystoneRunCount?.takeIf { it > 1 }?.let { MigrationKeystoneRound(current = 1, total = it) },
            isConfirming = isConfirming,
            onConfirm = { proposeLce.guardLoading { onConfirmAutomatic(sched) } },
            onBack = ::onBack,
            failureSheet = failureResult?.let {
                MigrationTransferFailureState(
                    message = migrationFailureMessage(it),
                    onRetry = { failure.value = null; proposeLce.guardLoading { onConfirmAutomatic(sched) } },
                    onDismiss = { failure.value = null },
                )
            },
        )
    }

    // proposeImmediateMigration()'s raw send-max Proposal carries no destination-facing
    // "list of transfers" the way a MigrationSchedule does — this renders it as a single
    // synthetic row so the (shared) review layout still has something to show, using the real
    // fee from Proposal.totalFeeRequired() instead of AUTOMATIC's placeholder.
    private fun createImmediateState(
        proposal: ReviewProposal.Immediate,
        isConfirming: Boolean,
        exchangeRateState: ExchangeRateState,
    ): MigrationReviewState {
        val fee = proposal.proposal.totalFeeRequired()
        return MigrationReviewState(
            mode = args.mode,
            totalAmount = stringRes(Zatoshi(proposal.amountZatoshi)),
            totalFiatAmount = fiatAmount(Zatoshi(proposal.amountZatoshi), exchangeRateState),
            estimatedDuration = stringRes(formatMigrationDuration(0L)),
            transfers = listOf(
                MigrationReviewTransferState(
                    index = 1,
                    totalCount = 1,
                    amount = stringRes(Zatoshi(proposal.amountZatoshi)),
                    fiatAmount = fiatAmount(Zatoshi(proposal.amountZatoshi), exchangeRateState),
                    scheduledLabel = stringRes("Send immediately"),
                )
            ),
            fee = stringRes(fee),
            isConfirming = isConfirming,
            onConfirm = { onConfirmImmediate(proposal.proposal, proposal.amountZatoshi) },
            onBack = ::onBack,
            // Submit failures now surface on the Sending screen (which owns the broadcast) rather
            // than here — this screen only hands the signed proposal off after biometric auth.
            failureSheet = null,
        )
    }

    private fun fiatAmount(zatoshi: Zatoshi, exchangeRateState: ExchangeRateState): StringResource? {
        val data = exchangeRateState as? ExchangeRateState.Data ?: return null
        val conversion = data.currencyConversion ?: return null
        return stringResByDynamicCurrencyNumber(
            amount =
                zatoshi
                    .convertZatoshiToZec()
                    .multiply(BigDecimal(conversion.priceOfZec), MathContext.DECIMAL128),
            ticker = data.expectedCurrency.symbol,
        )
    }

    private fun onConfirmAutomatic(sched: MigrationSchedule) =
        confirmLce.execute {
            try {
                biometricRepository.requestBiometrics(
                    request =
                        BiometricRequest(
                            message =
                                stringRes(
                                    R.string.authentication_system_ui_subtitle,
                                    stringRes(R.string.authentication_use_case_send_funds)
                                )
                        )
                )
            } catch (_: BiometricsFailureException) {
                return@execute
            } catch (_: BiometricsCancelledException) {
                return@execute
            }
            confirmAutomatic(sched)
        }

    private suspend fun confirmAutomatic(sched: MigrationSchedule) {
        if (getSelectedWalletAccount() is KeystoneAccount) {
            // Keystone can't sign in-process — hand the unsigned schedule off to the QR
            // sign/scan detour; FinalizeMigrationScheduleUseCase runs after a successful scan
            // instead (MigrationKeystoneScanVM), not here.
            val accountKeyId = getSelectedWalletAccount().sdkAccount.accountUuid.toStorageKeyId()
            pendingMigrationScheduleRepository.set(accountKeyId, sched)
            navigationRouter.forward(MigrationKeystoneSignArgs(mode = args.mode))
            return
        }
        val sdk = getOrchardMigrationSdk()
        // Note-split is the first step of this confirm action (design spec §7) — a schedule with
        // more than one denomination proposed against raw, unsplit notes exhausts the wallet's
        // balance on the first transfer, leaving every subsequent transfer InsufficientFunds. Per
        // spec §3 the split is a fully shielded self-send and needs no sync-decoupling delay, so
        // proceeding straight to signAndStoreMigrationSchedule below is safe. Under the crate's
        // sign-now/prove-later pipeline that call now signs successfully immediately even though
        // the split's own output isn't mined/witnessed yet.
        //
        // `sched` was proposed at screen init, before any split — proposeMigrationTransfers()'s
        // denomination guess and prepareNoteSplit()'s own (independent) guess over the same
        // balance are not guaranteed to agree. Reusing the stale `sched` here could schedule a
        // transfer for a denomination the split never actually mints, which then silently falls
        // back to an unrelated already-existing note — one the split's own "sweep everything"
        // construction may already be consuming as one of its own inputs (a real double-spend
        // found live on testnet). Re-deriving the schedule from the split's own realized output
        // plan makes every crossing value provably match a note this split actually produces.
        val scheduleToSign = if (sdk.isNoteSplitNeeded()) {
            val proposal = sdk.prepareNoteSplit()
            // Derive the from-split schedule BEFORE submitting the split, not after: submitNoteSplit()
            // signs the split through the SDK's commit_or_reuse, which clears the in-memory
            // migration-plan cache that `proposal.proposalHandle` points at once it commits. Calling
            // proposeMigrationTransfersFromSplit() afterwards then throws "No pending migration
            // proposal for this account — call propose/prepare first", because the plan the handle
            // identifies is already gone. Reading the schedule first (the cache is still populated by
            // prepareNoteSplit()) mirrors MigrationKeystoneSignVM, which likewise derives the
            // from-split schedule before its first commit.
            val scheduleFromSplit = sdk.proposeMigrationTransfersFromSplit(proposal)
            // Write-ahead: persist the plan BEFORE the irreversible submitNoteSplit() (which commits
            // AND broadcasts the split). If the app dies between here and finalizeMigrationSchedule
            // below, re-entry then sees InProgress + a saved plan and resumes (see the guard above),
            // instead of mistaking the committed migration for a fresh start and re-running the split.
            finalizeMigrationSchedule.persistPlan(scheduleFromSplit, args.mode)
            val splitResult = sdk.submitNoteSplit(proposal, zashiSpendingKeyDataSource.getZashiSpendingKey())
            if (splitResult !is TransferResult.Success) {
                failure.value = splitResult
                return
            }
            scheduleFromSplit
        } else {
            // Same write-ahead as the split branch: persist before signAndStoreMigrationSchedule()
            // (the commit for the no-split path) so a crash before finalize is recoverable.
            finalizeMigrationSchedule.persistPlan(sched, args.mode)
            sched
        }
        try {
            sdk.signAndStoreMigrationSchedule(scheduleToSign, zashiSpendingKeyDataSource.getZashiSpendingKey())
            finalizeMigrationSchedule(scheduleToSign, args.mode)
        } catch (e: RuntimeException) {
            val retryable = e.message?.contains("StalePlan") == true ||
                e.message?.contains("BoundaryCheckpointMissing") == true
            if (!retryable) throw e
            // StalePlan: the plan is a planning-time snapshot of wallet note indices; any note
            // received or changed between this screen's propose and the commit (the bursty
            // testnet syncs continuously) shifts them and the engine correctly refuses with
            // "must be re-planned". Same balance, fresh draw — re-propose once and commit that.
            // Retrying the SAME cached schedule can never succeed (observed live: six identical
            // StalePlan failures from the retry button). BoundaryCheckpointMissing: the commit
            // drew an anchor boundary onto a grid height with no retained checkpoint (pre-
            // always-on-retention scan history) — a fresh draw lands on retained boundaries.
            Twig.debug { "MIGRATION_DIAG MigrationReview: StalePlan on commit — re-proposing once and retrying" }
            val fresh = sdk.proposeMigrationTransfers()
            finalizeMigrationSchedule.persistPlan(fresh, args.mode)
            sdk.signAndStoreMigrationSchedule(fresh, zashiSpendingKeyDataSource.getZashiSpendingKey())
            finalizeMigrationSchedule(fresh, args.mode)
        }
    }

    // The IMMEDIATE send-max sweep is, from the wallet's point of view, an ordinary send — so it
    // reuses the exact same submit pipeline every other send does: adopt the proposal as the current
    // MigrationSweepTransactionProposal, then hand off to SubmitProposalUseCase (biometrics + async
    // broadcast + Transaction Progress screen, whose sending/success states already render the
    // migration-sweep "…migrated to Ironwood" copy). No migration-specific screen or handoff.
    private fun onConfirmImmediate(proposal: Proposal, amountZatoshi: Long) =
        confirmLce.execute {
            if (getSelectedWalletAccount() is KeystoneAccount) {
                // Keystone can't sign in-process — adopt the already-built send-max proposal into the
                // app's existing generic external-signer pipeline exactly as an ordinary Keystone
                // send does (one ordinary PCZT, same as any regular Keystone send). Biometrics are
                // requested here because the Keystone branch skips SubmitProposalUseCase (which owns
                // biometrics for the Zashi path) in favour of the QR sign/scan detour.
                try {
                    biometricRepository.requestBiometrics(
                        request =
                            BiometricRequest(
                                message =
                                    stringRes(
                                        R.string.authentication_system_ui_subtitle,
                                        stringRes(R.string.authentication_use_case_send_funds)
                                    )
                            )
                    )
                } catch (_: BiometricsFailureException) {
                    return@execute
                } catch (_: BiometricsCancelledException) {
                    return@execute
                }
                keystoneProposalRepository.setMigrationSweepProposal(proposal, Zatoshi(amountZatoshi))
                // Required before navigating — SignKeystoneTransactionVM's QR encoder is built from
                // the already-created PCZT (createPCZTEncoder() reads KeystoneProposalRepository's
                // cached proposalPczt); it never calls createPCZTFromProposal() itself.
                keystoneProposalRepository.createPCZTFromProposal()
                navigationRouter.forward(SignKeystoneTransactionArgs)
            } else {
                zashiProposalRepository.setMigrationSweepProposal(proposal, Zatoshi(amountZatoshi))
                submitProposal()
            }
        }

    private fun onBack() = proposeLce.guardLoading { navigationRouter.back() }

    // Only ever called for AUTOMATIC (createImmediateState hardcodes its own single-row label
    // instead — a raw send-max Proposal carries no per-transfer schedule to derive one from).
    /** One-shot plan dump at propose time: absolute heights + wall-clock estimates. */
    private fun logProposedPlan(sched: MigrationSchedule) {
        // `anchorHeight` on a PROPOSED transfer is NOT a real commitment-tree anchor — the engine
        // draws anchor boundaries only at COMMIT (commit_preparation), so a proposal carries none.
        // The field holds the plan-time tip as a "now" reference for the height→time estimates
        // below; it is deliberately NOT logged per-transfer as "anchor=" (that read as a real
        // boundary and was misleading). The real per-transfer boundaries are logged post-commit by
        // the Rust `committedPlan:` dump (boundary=Some(...)).
        val referenceTip = sched.transfers.minOfOrNull { it.anchorHeight } ?: return
        Twig.debug {
            buildString {
                appendLine(
                    "MIGRATION_DIAG Plan: ${sched.transfers.size} transfer(s), referenceTip=$referenceTip " +
                        "(anchors are drawn at commit — see committedPlan; times estimated at measured " +
                        "${secondsPerBlock}s/block from the reference tip)"
                )
                var prev = referenceTip
                sched.transfers.forEachIndexed { i, t ->
                    val fromNow = estimatedSecondsBetweenHeights(referenceTip, t.nextExecutableAfterHeight, secondsPerBlock)
                    val gap = estimatedSecondsBetweenHeights(prev, t.nextExecutableAfterHeight, secondsPerBlock)
                    prev = t.nextExecutableAfterHeight
                    appendLine(
                        "MIGRATION_DIAG Plan: transfer[${i + 1}] " +
                            "send=${t.nextExecutableAfterHeight} expiry=${t.expiryHeight} " +
                            "dueIn=${formatMigrationDuration(fromNow, fineGrained = true)} " +
                            "gapFromPrev=${formatMigrationDuration(gap, fineGrained = true)}"
                    )
                }
            }.trimEnd()
        }
    }

    private fun scheduledLabel(t: TransferProposal): StringResource {
        val secondsUntil = estimatedSecondsBetweenHeights(t.anchorHeight, t.nextExecutableAfterHeight, secondsPerBlock)
        return when {
            secondsUntil <= 0 -> stringRes("Ready now")
            // Shares formatMigrationDuration's resolution rules (minute-level on testnet).
            else -> stringRes(formatMigrationDuration(secondsUntil))
        }
    }

    // Preparations carry no per-item anchorHeight; use the transfers' commit-tip baseline
    // (same origin the transfer labels use).
    private fun scheduledLabelForPrep(
        p: cash.z.ecc.android.sdk.PreparationStep,
        sched: MigrationSchedule,
    ): StringResource {
        val baseline = sched.transfers.minOfOrNull { it.anchorHeight } ?: p.broadcastHeight
        val secondsUntil = estimatedSecondsBetweenHeights(baseline, p.broadcastHeight, secondsPerBlock)
        return if (secondsUntil <= 0) stringRes("Ready now") else stringRes(formatMigrationDuration(secondsUntil))
    }
}
