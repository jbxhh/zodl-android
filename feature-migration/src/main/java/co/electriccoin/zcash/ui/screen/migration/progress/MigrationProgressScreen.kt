package co.electriccoin.zcash.ui.screen.migration.progress

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.BlankBgScaffold
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.CircularScreenProgressIndicator
import co.electriccoin.zcash.ui.design.component.ZashiButton
import co.electriccoin.zcash.ui.design.component.ZashiSmallTopAppBar
import co.electriccoin.zcash.ui.design.component.ZashiTopAppBarCloseNavigation
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.theme.colors.ZashiColors
import co.electriccoin.zcash.ui.design.theme.typography.ZashiTypography
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.design.util.scaffoldPadding
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.common.LceRenderer
import org.koin.androidx.compose.koinViewModel

@Composable
fun MigrationProgressScreen() {
    val vm = koinViewModel<MigrationProgressVM>()
    val state by vm.state.collectAsStateWithLifecycle()
    BackHandler { state.content?.onBack?.invoke() ?: vm.navigateBack() }
    LceRenderer(
        state = state,
        loading = { isLoading -> if (isLoading) CircularScreenProgressIndicator() },
    ) { MigrationProgressView(it) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MigrationProgressView(state: MigrationProgressState) {
    BlankBgScaffold(
        topBar = {
            ZashiSmallTopAppBar(
                navigationAction = { ZashiTopAppBarCloseNavigation(onBack = state.onBack) },
            )
        }
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .scaffoldPadding(padding),
        ) {
            Text(
                text = state.title.getValue(),
                style = ZashiTypography.header6,
                fontWeight = FontWeight.SemiBold,
                color = ZashiColors.Text.textPrimary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = state.subtitle.getValue(),
                style = ZashiTypography.textSm,
                color = ZashiColors.Text.textTertiary,
            )
            Spacer(Modifier.height(24.dp))

            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                // Render one "Split balance N" row per note-split (preparation) transaction,
                // in broadcast/schedule order. When preparations is empty (single-note wallets),
                // this block renders nothing and the transfers start immediately.
                val firstUnsentPrepIndex = state.preparations.indexOfFirst { !it.isSent }
                state.preparations.forEachIndexed { i, prep ->
                    // statusLabel is the PRIMARY, all-builds time hint. When the DEBUG-only
                    // syncLabel (raw engine status word) is present, append it as a diagnostic
                    // suffix "· status <label>" — inverse of the pre-2026-08-01 format — so the
                    // row composable needs no new parameter.
                    val rowStatusLabel =
                        if (prep.syncLabel != null) {
                            prep.statusLabel + stringRes(" · status ") + prep.syncLabel
                        } else {
                            prep.statusLabel
                        }
                    // Preparation rows never surface an attention state.
                    val rowState =
                        when {
                            prep.isSent -> TransferRowState.DONE
                            i == firstUnsentPrepIndex -> TransferRowState.ACTIVE
                            else -> TransferRowState.IDLE
                        }
                    TransferProgressTimelineRow(
                        title = "Split balance ${prep.number}",
                        statusLabel = rowStatusLabel,
                        amount = null,
                        fiatAmount = null,
                        state = rowState,
                        isLast = i == state.preparations.lastIndex && state.transfers.isEmpty(),
                    )
                }
                val activeIndex = state.transfers.indexOfFirst { !it.isSent }
                state.transfers.forEachIndexed { i, transfer ->
                    // statusLabel is the PRIMARY, all-builds time hint. When the DEBUG-only
                    // syncLabel (raw engine status word) is present, append it as a diagnostic
                    // suffix "· status <label>" — inverse of the pre-2026-08-01 format — so the
                    // row composable needs no new parameter.
                    val rowStatus =
                        if (transfer.syncLabel != null) {
                            transfer.statusLabel + stringRes(" · status ") + transfer.syncLabel
                        } else {
                            transfer.statusLabel
                        }
                    // Priority mirrors the row painter: sent wins, then genuine attention, then active.
                    val rowState =
                        when {
                            transfer.isSent -> TransferRowState.DONE
                            transfer.isAttention -> TransferRowState.ATTENTION
                            i == activeIndex -> TransferRowState.ACTIVE
                            else -> TransferRowState.IDLE
                        }
                    TransferProgressTimelineRow(
                        title = "Transfer ${transfer.index}",
                        statusLabel = rowStatus,
                        amount = transfer.amount,
                        fiatAmount = transfer.fiatAmount,
                        index = transfer.index,
                        state = rowState,
                        isLast = i == state.transfers.lastIndex,
                    )
                }
            }

            // No Send-now / Re-schedule buttons: the engine drives execution and the foreground
            // pass sends silently while this screen is open — the screen is a pure live status
            // view now. The only button is "Got it" on completion; genuine attention states
            // (expired / unprovable anchor) surface via the home banner → reschedule flow.
            Spacer(Modifier.height(24.dp))

            if (state.isComplete) {
                state.onDone?.let { done ->
                    ZashiButton(
                        state = ButtonState(text = stringRes("Got it"), onClick = done),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/**
 * Visual state of a single timeline row, resolved by priority: a sent row always paints as
 * [DONE], then a genuine [ATTENTION] state, then the currently-[ACTIVE] row, else [IDLE].
 */
private enum class TransferRowState { DONE, ATTENTION, ACTIVE, IDLE }

@Suppress("LongParameterList")
@Composable
private fun TransferProgressTimelineRow(
    title: String,
    statusLabel: StringResource,
    amount: StringResource?,
    fiatAmount: StringResource?,
    state: TransferRowState,
    isLast: Boolean,
    index: Int = 0,
    @DrawableRes icon: Int? = null,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .padding(bottom = if (isLast) 0.dp else 12.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxHeight()
                    .width(24.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            if (!isLast) {
                val connectorColor =
                    if (state == TransferRowState.DONE) {
                        ZashiColors.Utility.SuccessGreen.utilitySuccess500
                    } else {
                        ZashiColors.Surfaces.strokePrimary
                    }
                Box(
                    modifier =
                        Modifier
                            .fillMaxHeight()
                            .padding(top = 24.dp)
                            .width(2.dp)
                            .background(connectorColor)
                )
            }
            val bgColor =
                when (state) {
                    TransferRowState.DONE -> ZashiColors.Utility.SuccessGreen.utilitySuccess500
                    TransferRowState.ATTENTION -> ZashiColors.Utility.WarningYellow.utilityOrange500
                    TransferRowState.ACTIVE -> ZashiColors.Btns.Primary.btnPrimaryBg
                    TransferRowState.IDLE -> ZashiColors.Surfaces.bgTertiary
                }
            val textColor =
                when (state) {
                    TransferRowState.ATTENTION, TransferRowState.ACTIVE -> ZashiColors.Btns.Primary.btnPrimaryFg
                    TransferRowState.DONE, TransferRowState.IDLE -> ZashiColors.Utility.Gray.utilityGray400
                }
            Box(
                modifier =
                    Modifier
                        .size(24.dp)
                        .background(bgColor, CircleShape)
                        .border(2.dp, ZashiColors.Surfaces.bgPrimary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    state == TransferRowState.DONE -> {
                        Icon(
                            painter = painterResource(co.electriccoin.zcash.migration.R.drawable.ic_migration_check),
                            contentDescription = null,
                            tint = ZashiColors.Btns.Primary.btnPrimaryFg,
                            modifier = Modifier.size(16.dp),
                        )
                    }

                    icon != null -> {
                        Icon(
                            painter = painterResource(icon),
                            contentDescription = null,
                            tint = textColor,
                            modifier = Modifier.size(14.dp),
                        )
                    }

                    else -> {
                        Text(
                            text = "$index",
                            style = ZashiTypography.textXs,
                            fontWeight = FontWeight.SemiBold,
                            color = textColor,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = ZashiTypography.textSm,
                fontWeight = FontWeight.Medium,
                color = ZashiColors.Text.textPrimary,
            )
            Text(
                text = statusLabel.getValue(),
                style = ZashiTypography.textXs,
                color =
                    if (state == TransferRowState.ATTENTION) {
                        ZashiColors.Utility.WarningYellow.utilityOrange500
                    } else {
                        ZashiColors.Text.textTertiary
                    },
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            amount?.let {
                Text(
                    text = it.getValue(),
                    style = ZashiTypography.textSm,
                    fontWeight = FontWeight.Medium,
                    color = ZashiColors.Text.textPrimary,
                )
            }
            fiatAmount?.let { fiat ->
                Text(
                    text = fiat.getValue(),
                    style = ZashiTypography.textXs,
                    color = ZashiColors.Text.textTertiary,
                )
            }
        }
    }
}

@PreviewScreens
@Composable
private fun PreviewInProgress() =
    ZcashTheme {
        MigrationProgressView(
            state =
                MigrationProgressState(
                    title = stringRes("Migration Progress"),
                    subtitle = stringRes("Your balance splits into 5 transfers over ~24 h. There are 3 remaining transfers."),
                    totalAmount = stringRes("10.458 ZEC"),
                    totalFiatAmount = stringRes("$4,053.46"),
                    transfers =
                        listOf(
                            MigrationProgressTransferState(1, stringRes("1.348 ZEC"), stringRes("Sent"), false, true, stringRes("$521.30")),
                            MigrationProgressTransferState(
                                2,
                                stringRes("1.052 ZEC"),
                                stringRes("Sending soon"),
                                false,
                                false,
                                stringRes("$406.86")
                            ),
                            MigrationProgressTransferState(
                                3,
                                stringRes("2.105 ZEC"),
                                stringRes("Scheduled"),
                                false,
                                false,
                                stringRes("$813.74")
                            ),
                            MigrationProgressTransferState(
                                4,
                                stringRes("1.897 ZEC"),
                                stringRes("Waiting for anchor window"),
                                false,
                                false,
                                stringRes("$733.51")
                            ),
                            MigrationProgressTransferState(
                                5,
                                stringRes("4.056 ZEC"),
                                stringRes("Needs reschedule"),
                                true,
                                false,
                                stringRes("$1,568.05")
                            ),
                        ),
                    isComplete = false,
                    onBack = {},
                )
        )
    }

@PreviewScreens
@Composable
private fun PreviewComplete() =
    ZcashTheme {
        MigrationProgressView(
            state =
                MigrationProgressState(
                    title = stringRes("Migration Progress"),
                    subtitle = stringRes("Your balance splits into 5 transfers over 24 hours. All transfers complete."),
                    totalAmount = stringRes("10.458 ZEC"),
                    totalFiatAmount = stringRes("$4,053.46"),
                    transfers =
                        listOf(
                            MigrationProgressTransferState(
                                1,
                                stringRes("1.348 ZEC"),
                                stringRes("Sent 24h ago"),
                                false,
                                true,
                                stringRes("$521.30")
                            ),
                            MigrationProgressTransferState(
                                2,
                                stringRes("1.052 ZEC"),
                                stringRes("Sent 18h ago"),
                                false,
                                true,
                                stringRes("$406.86")
                            ),
                            MigrationProgressTransferState(
                                3,
                                stringRes("2.105 ZEC"),
                                stringRes("Sent 12h ago"),
                                false,
                                true,
                                stringRes("$813.74")
                            ),
                            MigrationProgressTransferState(
                                4,
                                stringRes("1.897 ZEC"),
                                stringRes("Sent 6h ago"),
                                false,
                                true,
                                stringRes("$733.51")
                            ),
                            MigrationProgressTransferState(
                                5,
                                stringRes("4.056 ZEC"),
                                stringRes("Sent 18 min ago"),
                                false,
                                true,
                                stringRes("$1,568.05")
                            ),
                        ),
                    isComplete = true,
                    onBack = {},
                    onDone = {},
                )
        )
    }
