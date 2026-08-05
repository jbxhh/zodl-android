package co.electriccoin.zcash.ui.screen.home

import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.model.migration.MigrationAttentionKind
import co.electriccoin.zcash.ui.common.repository.HomeMessageData
import co.electriccoin.zcash.ui.design.util.TickerLocation.HIDDEN
import co.electriccoin.zcash.ui.design.util.asPrivacySensitive
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.home.migration.MigrationBannerPhase
import co.electriccoin.zcash.ui.screen.home.migration.MigrationMessageState
import co.electriccoin.zcash.ui.screen.home.shieldfunds.ShieldFundsMessageState
import co.electriccoin.zcash.ui.util.CURRENCY_TICKER

private const val MAX_PERCENT = 100

class HomeMessageMapper {
    /**
     * Maps [HomeMessageData.Migration] to the home-banner state. Attention states (spec §6.2/§6.3)
     * take priority over the ordinary phases and carry the exact required copy in the title; the
     * ready-to-send subtitle is numbered per the due transfer (spec §6.4).
     */
    fun createState(data: HomeMessageData.Migration, onClick: () -> Unit): MigrationMessageState {
        val plan = data.plan
        val percent =
            if (plan != null && plan.totalCount > 0) {
                (plan.completedCount * MAX_PERCENT) / plan.totalCount
            } else {
                0
            }
        val roundPrefix =
            plan?.keystoneRound?.let { "Round ${it.current} of ${it.total} · " }.orEmpty()
        val banner =
            when (data.attentionKind) {
                MigrationAttentionKind.PLAN_UPDATE -> {
                    MigrationBannerInternalState(
                        phase = MigrationBannerPhase.ATTENTION,
                        title = "Update migration plan",
                        subtitle = "Tap to review the details",
                    )
                }

                MigrationAttentionKind.TRANSFER_EXPIRED -> {
                    val range = data.attentionRangeText
                    MigrationBannerInternalState(
                        phase = MigrationBannerPhase.ATTENTION,
                        title = if (range != null) "Transfer $range expired" else "A transfer expired",
                        subtitle = "Tap to review the details",
                    )
                }

                null -> {
                    when {
                        data.isComplete -> {
                            MigrationBannerInternalState(
                                phase = MigrationBannerPhase.COMPLETE,
                                subtitle = "Tap to review the details",
                            )
                        }

                        data.isReadyToSend -> {
                            MigrationBannerInternalState(
                                phase = MigrationBannerPhase.READY_TO_SEND,
                                subtitle = "Transfer ${(plan?.completedCount ?: 0) + 1} is ready to send",
                            )
                        }

                        plan == null -> {
                            MigrationBannerInternalState(phase = MigrationBannerPhase.REQUIRED)
                        }

                        else -> {
                            MigrationBannerInternalState(
                                phase = MigrationBannerPhase.IN_PROGRESS,
                                subtitle =
                                    "$roundPrefix${plan.completedCount} of ${plan.totalCount} " +
                                        "transfers done ~ $percent% complete",
                            )
                        }
                    }
                }
            }
        return MigrationMessageState(
            phase = banner.phase,
            title = banner.title,
            progressLabel = banner.subtitle,
            progressPercent = percent.toFloat(),
            onClick = onClick,
            onButtonClick = onClick,
        )
    }

    fun createState(
        data: HomeMessageData.ShieldFunds,
        isShieldFundsInfoEnabled: Boolean,
        onClick: () -> Unit,
        onButtonClick: () -> Unit,
    ) = ShieldFundsMessageState(
        subtitle =
            stringRes(
                R.string.home_message_transparent_balance_subtitle,
                stringRes(data.zatoshi, HIDDEN).asPrivacySensitive(),
                CURRENCY_TICKER
            ),
        onClick = onClick.takeIf { isShieldFundsInfoEnabled },
        onButtonClick = onButtonClick,
    )
}

private data class MigrationBannerInternalState(
    val phase: MigrationBannerPhase,
    val title: String? = null,
    val subtitle: String? = null,
)
