package co.electriccoin.zcash.ui.screen.migration.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.common.model.migration.MigrationPreparationDetails
import co.electriccoin.zcash.ui.common.model.migration.MigrationPreparationStepDetail
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.ZashiButton
import co.electriccoin.zcash.ui.design.component.ZashiModalBottomSheet
import co.electriccoin.zcash.ui.design.component.rememberModalBottomSheetState
import co.electriccoin.zcash.ui.design.theme.colors.ZashiColors
import co.electriccoin.zcash.ui.design.theme.typography.ZashiTypography
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.design.util.stringRes

/**
 * "Prepare Your Balance" detail sheet — Figma "PR App Designs Q3'26", node 5207:16023
 * (2026-08-03). Shared by MigrationReviewScreen and MigrationProgressScreen: both collapse their
 * multi-step note-split into a single "Split Balance" summary row with a "Show details" link, and
 * both open this exact sheet — see [MigrationPreparationDetails]'s doc for why the two screens
 * still compute their own [MigrationPreparationDetails] independently.
 *
 * [skipPartiallyExpanded] forces the sheet to open fully expanded immediately instead of the M3
 * default half-open "peek" state — with a multi-step breakdown card, the peek height clipped the
 * "Got it" button below the fold, so users had to drag the sheet up (or the content wasn't
 * scrollable, so the button was unreachable at all). The content Column is ALSO scrollable as a
 * second line of defense: even at full expansion, a wallet with enough steps to exceed screen
 * height still needs to scroll to reach the button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MigrationPreparationDetailsBottomSheet(details: MigrationPreparationDetails?) {
    if (details == null) return
    ZashiModalBottomSheet(
        onDismissRequest = details.onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier =
                Modifier
                    .padding(horizontal = 24.dp, vertical = 8.dp)
                    .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = "Prepare Your Balance",
                style = ZashiTypography.header5,
                fontWeight = FontWeight.SemiBold,
                color = ZashiColors.Text.textPrimary,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text =
                    "Your balance needs to be split into transfer-sized notes across ${details.stepCount} steps " +
                        "before your scheduled transfers begin.",
                style = ZashiTypography.textSm,
                color = ZashiColors.Text.textTertiary,
            )
            Spacer(Modifier.height(20.dp))
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(ZashiColors.Surfaces.bgPrimary, RoundedCornerShape(16.dp))
                        .padding(16.dp),
            ) {
                Text(
                    text = "Preparation Steps",
                    style = ZashiTypography.textMd,
                    fontWeight = FontWeight.SemiBold,
                    color = ZashiColors.Text.textPrimary,
                )
                Spacer(Modifier.height(16.dp))
                details.steps.forEachIndexed { i, step ->
                    PreparationStepRow(
                        number = i + 1,
                        step = step,
                        isLast = i == details.steps.lastIndex,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(ZashiColors.Surfaces.bgSecondary, RoundedCornerShape(12.dp))
                        .padding(12.dp),
            ) {
                Text(
                    text = "Amount Being Split",
                    style = ZashiTypography.textXs,
                    color = ZashiColors.Text.textTertiary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = details.totalAmount.getValue(),
                    style = ZashiTypography.textXs,
                    fontWeight = FontWeight.Medium,
                    color = ZashiColors.Text.textPrimary,
                )
            }
            Spacer(Modifier.height(24.dp))
            ZashiButton(
                state = ButtonState(text = stringRes("Got it"), onClick = details.onDismiss),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun PreparationStepRow(
    number: Int,
    step: MigrationPreparationStepDetail,
    isLast: Boolean,
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
                Box(
                    modifier =
                        Modifier
                            .fillMaxHeight()
                            .padding(top = 24.dp)
                            .width(2.dp)
                            .background(ZashiColors.Surfaces.strokePrimary)
                )
            }
            Box(
                modifier =
                    Modifier
                        .size(24.dp)
                        .background(ZashiColors.Surfaces.bgTertiary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (step.isDone) {
                    Icon(
                        painter = painterResource(co.electriccoin.zcash.migration.R.drawable.ic_migration_check),
                        contentDescription = null,
                        tint = ZashiColors.Text.textTertiary,
                        modifier = Modifier.size(14.dp),
                    )
                } else {
                    Text(
                        text = "$number",
                        style = ZashiTypography.textXs,
                        fontWeight = FontWeight.SemiBold,
                        color = ZashiColors.Text.textTertiary,
                    )
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = step.title.getValue(),
                style = ZashiTypography.textSm,
                fontWeight = FontWeight.Medium,
                color = ZashiColors.Text.textPrimary,
            )
            Text(
                text = step.timeLabel.getValue(),
                style = ZashiTypography.textXs,
                color = ZashiColors.Text.textTertiary,
            )
        }
        Text(
            text = step.statusLabel.getValue(),
            style = ZashiTypography.textXs,
            color = ZashiColors.Text.textTertiary,
            modifier = Modifier.align(Alignment.CenterVertically),
        )
    }
}
