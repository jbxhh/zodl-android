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
import co.electriccoin.zcash.ui.design.component.ZashiButtonDefaults
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
        loading = { isLoading -> if (isLoading && state.content == null) CircularScreenProgressIndicator() },
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
            modifier = Modifier
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
                    // When the debug syncLabel is present, append it as "· sync <label>" so the
                    // row composable needs no new parameter — statusLabel is plain text.
                    val rowStatusLabel = if (prep.syncLabel != null) {
                        prep.statusLabel + stringRes(" · sync ") + prep.syncLabel
                    } else {
                        prep.statusLabel
                    }
                    TransferProgressTimelineRow(
                        title = "Split balance ${prep.number}",
                        statusLabel = rowStatusLabel,
                        amount = null,
                        fiatAmount = null,
                        isDone = prep.isSent,
                        isActive = i == firstUnsentPrepIndex,
                        isOverdue = false,
                        isLast = i == state.preparations.lastIndex && state.transfers.isEmpty(),
                    )
                }
                val activeIndex = state.transfers.indexOfFirst { !it.isSent }
                state.transfers.forEachIndexed { i, transfer ->
                    // When the debug syncLabel is present, append it as "· sync <label>" so the
                    // row composable needs no new parameter — statusLabel is plain text.
                    val rowStatus = if (transfer.syncLabel != null) {
                        transfer.statusLabel + stringRes(" · sync ") + transfer.syncLabel
                    } else {
                        transfer.statusLabel
                    }
                    TransferProgressTimelineRow(
                        title = "Transfer ${transfer.index}",
                        statusLabel = rowStatus,
                        amount = transfer.amount,
                        fiatAmount = transfer.fiatAmount,
                        index = transfer.index,
                        isDone = transfer.isSent,
                        isActive = i == activeIndex,
                        isOverdue = transfer.isOverdue,
                        isLast = i == state.transfers.lastIndex,
                    )
                }
            }

            if (state.hasOverdue) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "Sending now will delay your wallet's sync about 10 mins. To protect your privacy, we need to decouple syncing and transaction broadcasting.",
                    style = ZashiTypography.textXs,
                    color = ZashiColors.Text.textTertiary,
                )
            }

            Spacer(Modifier.height(24.dp))

            if (state.isComplete) {
                state.onDone?.let { done ->
                    ZashiButton(
                        state = ButtonState(text = stringRes("Got it"), onClick = done),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else {
                state.onSendNow?.let { send ->
                    ZashiButton(
                        state = ButtonState(text = stringRes("Send now"), onClick = send),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                state.onReschedule?.let { reschedule ->
                    Spacer(Modifier.height(8.dp))
                    ZashiButton(
                        state = ButtonState(text = stringRes("Re-schedule"), onClick = reschedule),
                        modifier = Modifier.fillMaxWidth(),
                        defaultPrimaryColors = ZashiButtonDefaults.secondaryColors(),
                    )
                }
            }

        }
    }
}

@Suppress("LongParameterList")
@Composable
private fun TransferProgressTimelineRow(
    title: String,
    statusLabel: StringResource,
    amount: StringResource?,
    fiatAmount: StringResource?,
    isDone: Boolean,
    isActive: Boolean,
    isOverdue: Boolean,
    isLast: Boolean,
    index: Int = 0,
    @DrawableRes icon: Int? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .padding(bottom = if (isLast) 0.dp else 12.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(24.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            if (!isLast) {
                val connectorColor =
                    if (isDone) ZashiColors.Utility.SuccessGreen.utilitySuccess500 else ZashiColors.Surfaces.strokePrimary
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .padding(top = 24.dp)
                        .width(2.dp)
                        .background(connectorColor)
                )
            }
            val bgColor = when {
                isDone -> ZashiColors.Utility.SuccessGreen.utilitySuccess500
                isOverdue -> ZashiColors.Utility.WarningYellow.utilityOrange500
                isActive -> ZashiColors.Btns.Primary.btnPrimaryBg
                else -> ZashiColors.Surfaces.bgTertiary
            }
            val textColor = when {
                isOverdue || isActive -> ZashiColors.Btns.Primary.btnPrimaryFg
                else -> ZashiColors.Utility.Gray.utilityGray400
            }
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .background(bgColor, CircleShape)
                    .border(2.dp, ZashiColors.Surfaces.bgPrimary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    isDone -> Icon(
                        painter = painterResource(R.drawable.ic_migration_check),
                        contentDescription = null,
                        tint = ZashiColors.Btns.Primary.btnPrimaryFg,
                        modifier = Modifier.size(16.dp),
                    )

                    icon != null -> Icon(
                        painter = painterResource(icon),
                        contentDescription = null,
                        tint = textColor,
                        modifier = Modifier.size(14.dp),
                    )

                    else -> Text(
                        text = "$index",
                        style = ZashiTypography.textXs,
                        fontWeight = FontWeight.SemiBold,
                        color = textColor,
                    )
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
                color = if (isOverdue) ZashiColors.Utility.WarningYellow.utilityOrange500 else ZashiColors.Text.textTertiary,
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
private fun PreviewResume() = ZcashTheme {
    MigrationProgressView(
        state = MigrationProgressState(
            title = stringRes("Resume Migration"),
            subtitle = stringRes("Transfer 3 of 5 was scheduled 6 hours ago but wasn't sent. Send now or reschedule."),
            totalAmount = stringRes("10.458 ZEC"),
            totalFiatAmount = stringRes("$4,053.46"),
            transfers = listOf(
                MigrationProgressTransferState(1, stringRes("1.348 ZEC"), stringRes("Sent 6h ago"), false, true, stringRes("$521.30")),
                MigrationProgressTransferState(2, stringRes("1.052 ZEC"), stringRes("Sent 18 min ago"), false, true, stringRes("$406.86")),
                MigrationProgressTransferState(3, stringRes("2.105 ZEC"), stringRes("Overdue · 6h ago"), true, false, stringRes("$813.74")),
                MigrationProgressTransferState(4, stringRes("1.897 ZEC"), stringRes("~10 hours"), false, false, stringRes("$733.51")),
                MigrationProgressTransferState(5, stringRes("4.056 ZEC"), stringRes("~16 hours"), false, false, stringRes("$1,568.05")),
                MigrationProgressTransferState(5, stringRes("4.056 ZEC"), stringRes("~16 hours"), false, false, stringRes("$1,568.05")),
                MigrationProgressTransferState(5, stringRes("4.056 ZEC"), stringRes("~16 hours"), false, false, stringRes("$1,568.05")),
                MigrationProgressTransferState(5, stringRes("4.056 ZEC"), stringRes("~16 hours"), false, false, stringRes("$1,568.05")),
                MigrationProgressTransferState(5, stringRes("4.056 ZEC"), stringRes("~16 hours"), false, false, stringRes("$1,568.05")),
                MigrationProgressTransferState(5, stringRes("4.056 ZEC"), stringRes("~16 hours"), false, false, stringRes("$1,568.05")),
                MigrationProgressTransferState(5, stringRes("4.056 ZEC"), stringRes("~16 hours"), false, false, stringRes("$1,568.05")),
                MigrationProgressTransferState(5, stringRes("4.056 ZEC"), stringRes("~16 hours"), false, false, stringRes("$1,568.05")),
            ),
            isComplete = false,
            hasOverdue = true,
            onBack = {},
            onSendNow = {},
            onReschedule = {},
        )
    )
}

@PreviewScreens
@Composable
private fun PreviewComplete() = ZcashTheme {
    MigrationProgressView(
        state = MigrationProgressState(
            title = stringRes("Migration Progress"),
            subtitle = stringRes("Your balance splits into 5 transfers over 24 hours. All transfers complete."),
            totalAmount = stringRes("10.458 ZEC"),
            totalFiatAmount = stringRes("$4,053.46"),
            transfers = listOf(
                MigrationProgressTransferState(1, stringRes("1.348 ZEC"), stringRes("Sent 24h ago"), false, true, stringRes("$521.30")),
                MigrationProgressTransferState(2, stringRes("1.052 ZEC"), stringRes("Sent 18h ago"), false, true, stringRes("$406.86")),
                MigrationProgressTransferState(3, stringRes("2.105 ZEC"), stringRes("Sent 12h ago"), false, true, stringRes("$813.74")),
                MigrationProgressTransferState(4, stringRes("1.897 ZEC"), stringRes("Sent 6h ago"), false, true, stringRes("$733.51")),
                MigrationProgressTransferState(5, stringRes("4.056 ZEC"), stringRes("Sent 18 min ago"), false, true, stringRes("$1,568.05")),
            ),
            isComplete = true,
            hasOverdue = false,
            onBack = {},
            onDone = {},
        )
    )
}
