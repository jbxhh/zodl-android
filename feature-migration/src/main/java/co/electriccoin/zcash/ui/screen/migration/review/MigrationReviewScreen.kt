package co.electriccoin.zcash.ui.screen.migration.review

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.model.migration.MigrationKeystoneRound
import co.electriccoin.zcash.ui.common.model.migration.MigrationMode
import co.electriccoin.zcash.ui.design.component.BlankBgScaffold
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.ButtonStyle
import co.electriccoin.zcash.ui.design.component.ZashiButton
import co.electriccoin.zcash.ui.design.component.ZashiSmallTopAppBar
import co.electriccoin.zcash.ui.design.component.ZashiTopAppBarBackNavigation
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.theme.colors.ZashiColors
import co.electriccoin.zcash.ui.design.theme.typography.ZashiTypography
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.design.util.scaffoldPadding
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.common.LceRenderer
import co.electriccoin.zcash.ui.screen.common.WalletHeaderIcons
import co.electriccoin.zcash.ui.screen.common.WalletHeaderIconsState
import co.electriccoin.zcash.ui.screen.migration.component.MigrationFailureBottomSheet
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun MigrationReviewScreen(args: MigrationReviewArgs) {
    val vm = koinViewModel<MigrationReviewVM> { parametersOf(args) }
    val state by vm.state.collectAsStateWithLifecycle()
    // Hoisted outside LceRenderer (matching MigrationProgressScreen) so back-navigation is always
    // available, even when state.content is permanently null (e.g. NothingToMigrate on propose —
    // see the 2026-08-02 stale-banner/dead-end bug: without this, that failure path left the user
    // on a blank screen with no back arrow and no back gesture).
    BackHandler { state.content?.onBack?.invoke() ?: vm.navigateBack() }
    LceRenderer(state) { s ->
        MigrationReviewView(s)
    }
}

@Composable
fun MigrationReviewView(state: MigrationReviewState) {
    BlankBgScaffold(
        topBar = {
            ZashiSmallTopAppBar(
                navigationAction = { ZashiTopAppBarBackNavigation(onBack = state.onBack) },
            )
        }
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .scaffoldPadding(padding),
        ) {
            when (state.mode) {
                MigrationMode.IMMEDIATE -> ImmediateReviewContent(state)
                MigrationMode.AUTOMATIC -> PrivacyReviewContent(state)
            }
        }
    }
    MigrationFailureBottomSheet(state.failureSheet)
}

@Composable
private fun ImmediateReviewContent(state: MigrationReviewState) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Content scrolls in this weighted region so the Confirm button stays pinned to the bottom
        // of the screen (matching PrivacyReviewContent), rather than floating right under the short
        // details card.
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
        ) {
            WalletHeaderIcons(
                state =
                    WalletHeaderIconsState(
                        isKeystone = state.isKeystone,
                        badgeIcon = R.drawable.ic_migration_coins_swap,
                    )
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = "Review Transfer",
                style = ZashiTypography.header6,
                fontWeight = FontWeight.SemiBold,
                color = ZashiColors.Text.textPrimary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text =
                    "Your full Orchard balance will be transferred to Ironwood in a single on-chain transfer. " +
                        "Once confirmed, this transfer cannot be cancelled.",
                style = ZashiTypography.textSm,
                color = ZashiColors.Text.textTertiary,
            )
            Spacer(Modifier.height(24.dp))
            ImmediateDetailsCard(amount = state.totalAmount, fee = state.fee)
        }
        Spacer(Modifier.height(24.dp))
        ZashiButton(
            state =
                ButtonState(
                    text = stringRes("Cancel"),
                    style = ButtonStyle.TERTIARY,
                    onClick = state.onBack,
                ),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        ZashiButton(
            state =
                ButtonState(
                    text = stringRes(if (state.isConfirming) "Signing..." else "Confirm"),
                    isEnabled = !state.isConfirming,
                    isLoading = state.isConfirming,
                    onClick = state.onConfirm,
                ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
internal fun ImmediateDetailsCard(
    amount: StringResource,
    fee: StringResource?,
    // Overridable so callers whose [fee] is a placeholder (no real per-transfer fee field exists
    // on TransferProposal — see MigrationTransferReviewVM) can be honest that it's an estimate,
    // without relabeling the real, exact fee IMMEDIATE mode shows (Proposal.totalFeeRequired()).
    feeLabel: String = "Fee",
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(ZashiColors.Surfaces.bgSecondary, RoundedCornerShape(16.dp)),
    ) {
        ImmediateDetailsRow(label = "Amount", value = amount.getValue())
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(ZashiColors.Surfaces.bgPrimary),
        )
        ImmediateDetailsRow(label = feeLabel, value = fee?.getValue().orEmpty())
    }
}

@Composable
internal fun ImmediateDetailsRow(label: String, value: String) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = ZashiTypography.textSm,
            color = ZashiColors.Text.textTertiary,
        )
        Text(
            text = value,
            style = ZashiTypography.textSm,
            fontWeight = FontWeight.Medium,
            color = ZashiColors.Text.textPrimary,
        )
    }
}

@Composable
private fun PrivacyReviewContent(
    state: MigrationReviewState,
    // Preview-only override so PreviewPrivacyWithPreparationsExpanded can render the split-balance
    // section already open; production call sites always use the collapsed-by-default value.
    initiallyExpanded: Boolean = false,
) {
    // Hoisted here (a real @Composable) rather than inside the LazyColumn content lambda below,
    // which is a plain LazyListScope and cannot call remember {}.
    var isSplitSectionExpanded by remember { mutableStateOf(initiallyExpanded) }
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "Confirm Transfer Plan",
            style = ZashiTypography.header6,
            fontWeight = FontWeight.SemiBold,
            color = ZashiColors.Text.textPrimary,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text =
                "Your balance splits into ${state.transfers.size} transfers over " +
                    "${state.estimatedDuration.getValue()}. Approve once and " +
                    "we'll handle the rest — just keep the app running in the background. Amounts are randomized " +
                    "for privacy. If we miss a window, Zodl will prompt you on next open.",
            style = ZashiTypography.textSm,
            color = ZashiColors.Text.textTertiary,
        )
        Spacer(Modifier.height(24.dp))
        state.keystoneRound?.let { round ->
            Text(
                text = "Round ${round.current} of ${round.total}",
                style = ZashiTypography.textMd,
                fontWeight = FontWeight.SemiBold,
                color = ZashiColors.Text.textPrimary,
            )
            Spacer(Modifier.height(16.dp))
        }
        // Only this list scrolls when it doesn't fit — header and Confirm button stay pinned.
        LazyColumn(modifier = Modifier.weight(1f)) {
            if (state.preparations.size > 1) {
                // Multi-note wallet: collapse the per-preparation "Split balance N" rows into a
                // single "Split Balance (N)" summary row (collapsed by default), tap-to-expand.
                // Otherwise the plan visually grows by one row per split. No amount shown — raw
                // denominations are internal plumbing and confusing.
                item {
                    TransferTimelineRow(
                        title = "Split Balance (${state.preparations.size})",
                        subtitle = stringRes("Ready now"),
                        amount = null,
                        fiatAmount = null,
                        // Figma PR App Designs Q3'26, node 4207-7450: a checkmark, not the
                        // coins-swap glyph — Split Balance is a same-device self-send.
                        icon = co.electriccoin.zcash.migration.R.drawable.ic_migration_check,
                        isFirst = true,
                        isLast = false,
                        trailingIcon =
                            if (isSplitSectionExpanded) {
                                co.electriccoin.zcash.ui.design.R.drawable.ic_chevron_up
                            } else {
                                co.electriccoin.zcash.ui.design.R.drawable.ic_chevron_down
                            },
                        onClick = { isSplitSectionExpanded = !isSplitSectionExpanded },
                    )
                }
                if (isSplitSectionExpanded) {
                    items(state.preparations) { prep ->
                        TransferTimelineRow(
                            title = "Split balance ${prep.number}",
                            subtitle = prep.scheduledLabel,
                            amount = null,
                            fiatAmount = null,
                            icon = co.electriccoin.zcash.migration.R.drawable.ic_migration_check,
                            // Force isFirst = false on every expanded sub-row: the collapsed summary
                            // row above already claims the "first/active" styling slot, so the old
                            // prep.number == 1 logic would light up two rows as active at once.
                            isFirst = false,
                            isLast = false,
                        )
                    }
                }
            } else if (state.preparations.isNotEmpty()) {
                // Exactly one preparation: nothing to collapse, render the single "Split balance 1"
                // row as before.
                items(state.preparations) { prep ->
                    TransferTimelineRow(
                        title = "Split balance ${prep.number}",
                        subtitle = prep.scheduledLabel,
                        amount = null,
                        fiatAmount = null,
                        icon = co.electriccoin.zcash.migration.R.drawable.ic_migration_check,
                        isFirst = prep.number == 1,
                        isLast = false,
                    )
                }
            } else {
                // Single-note wallet (no split needed): keep the original collapsed "Split Balance"
                // row as a fallback so no regression on zero-preparations plans.
                item {
                    TransferTimelineRow(
                        title = "Split Balance",
                        subtitle = stringRes("Ready now"),
                        amount = state.totalAmount,
                        fiatAmount = state.totalFiatAmount,
                        icon = co.electriccoin.zcash.migration.R.drawable.ic_migration_check,
                        isFirst = true,
                        isLast = state.transfers.isEmpty(),
                    )
                }
            }
            items(state.transfers) { transfer ->
                TransferTimelineRow(
                    title = "Transfer ${transfer.index}",
                    subtitle = transfer.scheduledLabel,
                    amount = transfer.amount,
                    fiatAmount = transfer.fiatAmount,
                    index = transfer.index,
                    isFirst = false,
                    isLast = transfer.index == state.transfers.size,
                )
            }
        }
        Spacer(Modifier.height(24.dp))
        ZashiButton(
            state =
                ButtonState(
                    text = stringRes("Cancel"),
                    style = ButtonStyle.TERTIARY,
                    onClick = state.onBack,
                ),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        ZashiButton(
            state =
                ButtonState(
                    text = stringRes(if (state.isConfirming) "Signing..." else "Confirm"),
                    isEnabled = !state.isConfirming,
                    isLoading = state.isConfirming,
                    onClick = state.onConfirm,
                ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Suppress("LongParameterList")
@Composable
private fun TransferTimelineRow(
    title: String,
    subtitle: StringResource,
    amount: StringResource?,
    fiatAmount: StringResource?,
    isFirst: Boolean,
    isLast: Boolean,
    index: Int = 0,
    @DrawableRes icon: Int? = null,
    @DrawableRes trailingIcon: Int? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(enabled = onClick != null) { onClick?.invoke() }
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
                if (isFirst) {
                    // A weighted Box nested inside a Column here doesn't get a usable intrinsic
                    // height from the parent Row's IntrinsicSize.Min pass (weight is only
                    // meaningful once real constraints are known), so the "rest of the line"
                    // segment below the short active stub collapsed to ~0px. Paint both segments
                    // in one fillMaxHeight() Box instead — drawBehind uses the box's actual
                    // resolved size, sidestepping the intrinsic-measurement pass entirely.
                    val activeColor = ZashiColors.Btns.Primary.btnPrimaryBg
                    val inactiveColor = ZashiColors.Surfaces.strokePrimary
                    Box(
                        modifier =
                            Modifier
                                .fillMaxHeight()
                                .padding(top = 24.dp)
                                .width(2.dp)
                                .drawBehind {
                                    val activeHeight = 10.dp.toPx().coerceAtMost(size.height)
                                    drawRect(color = activeColor, size = Size(size.width, activeHeight))
                                    if (size.height > activeHeight) {
                                        drawRect(
                                            color = inactiveColor,
                                            topLeft = Offset(0f, activeHeight),
                                            size = Size(size.width, size.height - activeHeight),
                                        )
                                    }
                                }
                    )
                } else {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxHeight()
                                .padding(top = 24.dp)
                                .width(2.dp)
                                .background(ZashiColors.Surfaces.strokePrimary)
                    )
                }
            }
            Box(
                modifier =
                    Modifier
                        .size(24.dp)
                        .background(
                            if (isFirst) ZashiColors.Btns.Primary.btnPrimaryBg else ZashiColors.Surfaces.bgTertiary,
                            CircleShape
                        ).border(2.dp, ZashiColors.Surfaces.bgPrimary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (icon != null) {
                    Icon(
                        painter = painterResource(icon),
                        contentDescription = null,
                        tint = if (isFirst) ZashiColors.Btns.Primary.btnPrimaryFg else ZashiColors.Text.textTertiary,
                        modifier = Modifier.size(14.dp),
                    )
                } else {
                    Text(
                        text = "$index",
                        style = ZashiTypography.textXs,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isFirst) ZashiColors.Btns.Primary.btnPrimaryFg else ZashiColors.Text.textTertiary,
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
                text = subtitle.getValue(),
                style = ZashiTypography.textXs,
                color = ZashiColors.Text.textTertiary,
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
        trailingIcon?.let { chevron ->
            Spacer(Modifier.width(8.dp))
            Icon(
                painter = painterResource(chevron),
                contentDescription = null,
                tint = ZashiColors.Text.textTertiary,
                modifier =
                    Modifier
                        .align(Alignment.CenterVertically)
                        .size(20.dp),
            )
        }
    }
}

@PreviewScreens
@Composable
private fun PreviewImmediate() =
    ZcashTheme {
        MigrationReviewView(
            state =
                MigrationReviewState(
                    mode = MigrationMode.IMMEDIATE,
                    totalAmount = stringRes("12.458 ZEC"),
                    estimatedDuration = stringRes("~1 min"),
                    transfers =
                        listOf(
                            MigrationReviewTransferState(
                                1,
                                1,
                                stringRes("12.458 ZEC"),
                                stringRes("$4,832.86"),
                                stringRes("Send immediately")
                            ),
                        ),
                    isKeystone = false,
                    fee = stringRes("0.0003 ZEC"),
                    onConfirm = {},
                    onBack = {},
                )
        )
    }

@PreviewScreens
@Composable
private fun PreviewPrivacy() =
    ZcashTheme {
        MigrationReviewView(
            state =
                MigrationReviewState(
                    mode = MigrationMode.AUTOMATIC,
                    totalAmount = stringRes("12.458 ZEC"),
                    estimatedDuration = stringRes("~8 min"),
                    transfers =
                        listOf(
                            MigrationReviewTransferState(1, 5, stringRes("1.348 ZEC"), stringRes("$521.30"), stringRes("~10 mins")),
                            MigrationReviewTransferState(2, 5, stringRes("1.052 ZEC"), stringRes("$406.86"), stringRes("~6 hours")),
                            MigrationReviewTransferState(3, 5, stringRes("2.105 ZEC"), stringRes("$813.74"), stringRes("~12 hours")),
                            MigrationReviewTransferState(4, 5, stringRes("1.897 ZEC"), stringRes("$733.51"), stringRes("~18 hours")),
                            MigrationReviewTransferState(5, 5, stringRes("4.456 ZEC"), stringRes("$1,723.53"), stringRes("~24 hours")),
                        ),
                    onConfirm = {},
                    onBack = {},
                )
        )
    }

@PreviewScreens
@Composable
private fun PreviewPrivacyWithKeystoneRound() =
    ZcashTheme {
        MigrationReviewView(
            state =
                MigrationReviewState(
                    mode = MigrationMode.AUTOMATIC,
                    totalAmount = stringRes("12.458 ZEC"),
                    estimatedDuration = stringRes("~8 min"),
                    transfers =
                        listOf(
                            MigrationReviewTransferState(1, 5, stringRes("1.348 ZEC"), stringRes("$521.30"), stringRes("~10 mins")),
                            MigrationReviewTransferState(2, 5, stringRes("1.052 ZEC"), stringRes("$406.86"), stringRes("~6 hours")),
                            MigrationReviewTransferState(3, 5, stringRes("2.105 ZEC"), stringRes("$813.74"), stringRes("~12 hours")),
                            MigrationReviewTransferState(4, 5, stringRes("1.897 ZEC"), stringRes("$733.51"), stringRes("~18 hours")),
                            MigrationReviewTransferState(5, 5, stringRes("4.456 ZEC"), stringRes("$1,723.53"), stringRes("~24 hours")),
                        ),
                    isKeystone = true,
                    keystoneRound = MigrationKeystoneRound(current = 1, total = 4),
                    onConfirm = {},
                    onBack = {},
                )
        )
    }

@PreviewScreens
@Composable
private fun PreviewPrivacyWithPreparations() =
    ZcashTheme {
        MigrationReviewView(
            state =
                MigrationReviewState(
                    mode = MigrationMode.AUTOMATIC,
                    totalAmount = stringRes("12.458 ZEC"),
                    estimatedDuration = stringRes("~8 min"),
                    preparations =
                        listOf(
                            MigrationReviewPreparationState(1, stringRes("Ready now")),
                            MigrationReviewPreparationState(2, stringRes("Ready now")),
                        ),
                    transfers =
                        listOf(
                            MigrationReviewTransferState(1, 5, stringRes("1.348 ZEC"), stringRes("$521.30"), stringRes("~10 mins")),
                            MigrationReviewTransferState(2, 5, stringRes("1.052 ZEC"), stringRes("$406.86"), stringRes("~6 hours")),
                            MigrationReviewTransferState(3, 5, stringRes("2.105 ZEC"), stringRes("$813.74"), stringRes("~12 hours")),
                            MigrationReviewTransferState(4, 5, stringRes("1.897 ZEC"), stringRes("$733.51"), stringRes("~18 hours")),
                            MigrationReviewTransferState(5, 5, stringRes("4.456 ZEC"), stringRes("$1,723.53"), stringRes("~24 hours")),
                        ),
                    onConfirm = {},
                    onBack = {},
                )
        )
    }

@PreviewScreens
@Composable
private fun PreviewPrivacyWithPreparationsExpanded() =
    ZcashTheme {
        // Renders PrivacyReviewContent directly (rather than via MigrationReviewView) so the
        // split-balance section can be pre-expanded through the preview-only initiallyExpanded flag.
        PrivacyReviewContent(
            state =
                MigrationReviewState(
                    mode = MigrationMode.AUTOMATIC,
                    totalAmount = stringRes("12.458 ZEC"),
                    estimatedDuration = stringRes("~8 min"),
                    preparations =
                        listOf(
                            MigrationReviewPreparationState(1, stringRes("Ready now")),
                            MigrationReviewPreparationState(2, stringRes("Ready now")),
                            MigrationReviewPreparationState(3, stringRes("Ready now")),
                        ),
                    transfers =
                        listOf(
                            MigrationReviewTransferState(1, 5, stringRes("1.348 ZEC"), stringRes("$521.30"), stringRes("~10 mins")),
                            MigrationReviewTransferState(2, 5, stringRes("1.052 ZEC"), stringRes("$406.86"), stringRes("~6 hours")),
                            MigrationReviewTransferState(3, 5, stringRes("2.105 ZEC"), stringRes("$813.74"), stringRes("~12 hours")),
                            MigrationReviewTransferState(4, 5, stringRes("1.897 ZEC"), stringRes("$733.51"), stringRes("~18 hours")),
                            MigrationReviewTransferState(5, 5, stringRes("4.456 ZEC"), stringRes("$1,723.53"), stringRes("~24 hours")),
                        ),
                    onConfirm = {},
                    onBack = {},
                ),
            initiallyExpanded = true,
        )
    }
