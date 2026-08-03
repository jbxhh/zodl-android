package co.electriccoin.zcash.ui.screen.migration

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.common.model.migration.MigrationAttentionKind
import co.electriccoin.zcash.ui.common.model.migration.MigrationMode
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.theme.typography.ZashiTypography
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.migration.battery.MigrationBatteryState
import co.electriccoin.zcash.ui.screen.migration.battery.MigrationBatteryView
import co.electriccoin.zcash.ui.screen.migration.complete.MigrationCompleteState
import co.electriccoin.zcash.ui.screen.migration.complete.MigrationCompleteView
import co.electriccoin.zcash.ui.screen.migration.howitworks.MigrationHowItWorksState
import co.electriccoin.zcash.ui.screen.migration.howitworks.MigrationHowItWorksView
import co.electriccoin.zcash.ui.screen.migration.invalid.MigrationTransferInvalidState
import co.electriccoin.zcash.ui.screen.migration.invalid.MigrationTransferInvalidView
import co.electriccoin.zcash.ui.screen.migration.notification.MigrationNotificationState
import co.electriccoin.zcash.ui.screen.migration.notification.MigrationNotificationView
import co.electriccoin.zcash.ui.screen.migration.progress.MigrationProgressState
import co.electriccoin.zcash.ui.screen.migration.progress.MigrationProgressTransferState
import co.electriccoin.zcash.ui.screen.migration.progress.MigrationProgressView
import co.electriccoin.zcash.ui.screen.migration.review.MigrationReviewState
import co.electriccoin.zcash.ui.screen.migration.review.MigrationReviewTransferState
import co.electriccoin.zcash.ui.screen.migration.review.MigrationReviewView
import co.electriccoin.zcash.ui.screen.migration.scheduled.MigrationScheduledState
import co.electriccoin.zcash.ui.screen.migration.scheduled.MigrationScheduledView
import co.electriccoin.zcash.ui.screen.migration.sending.MigrationSendingState
import co.electriccoin.zcash.ui.screen.migration.sending.MigrationSendingView
import co.electriccoin.zcash.ui.screen.migration.setup.MigrationSetupState
import co.electriccoin.zcash.ui.screen.migration.setup.MigrationSetupView
import co.electriccoin.zcash.ui.screen.migration.success.MigrationSuccessState
import co.electriccoin.zcash.ui.screen.migration.success.MigrationSuccessView
import co.electriccoin.zcash.ui.screen.signkeystonetransaction.SignKeystoneTransactionState
import co.electriccoin.zcash.ui.screen.signkeystonetransaction.SignKeystoneTransactionView
import co.electriccoin.zcash.ui.screen.signkeystonetransaction.ZashiAccountInfoListItemState

/**
 * Aggregated, side-by-side view of each migration flow's steps for eyeballing against Figma —
 * not a real screen, just wires each screen's own [androidx.compose.ui.tooling.preview.Preview]
 * state into one Row per flow. Reuse this to spot-check the whole flow at once instead of
 * clicking through each screen's individual preview.
 */
@Preview(
    name = "Migration – AUTOMATIC (Privacy) flow",
    widthDp = 3000,
    heightDp = 2000,
    showBackground = true,
    backgroundColor = 0xFFDDDDDDL
)
@Composable
private fun PrivacyFlowPreview() =
    ZcashTheme {
        Column {
            Row(
                modifier = Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                FlowStep("1 · Setup") { MigrationSetupView(previewSetupState(MigrationMode.AUTOMATIC)) }
                FlowStep("2 · How It Works") { MigrationHowItWorksView(previewHowItWorksState()) }
                FlowStep("3 · Battery") { MigrationBatteryView(previewBatteryState()) }
                FlowStep("4 · Notification") { MigrationNotificationView(previewNotificationState()) }
                FlowStep("6 · Confirm Transfer Plan") { MigrationReviewView(previewReviewStateAutomatic()) }
                FlowStep("7 · Scheduled") { MigrationScheduledView(previewScheduledState()) }
            }
            Row(
                modifier = Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                FlowStep("7b · Keystone branch: Sign QR") { SignKeystoneTransactionView(previewKeystoneSignState()) }
                FlowStep("8 · Migration Complete") { MigrationCompleteView(previewCompleteStateWithDust()) }
            }
        }
    }

@Preview(
    name = "Migration – Manual Resume (routine confirm)",
    widthDp = 1400,
    heightDp = 950,
    showBackground = true,
    backgroundColor = 0xFFDDDDDDL
)
@Composable
private fun ManualResumeFlowPreview() =
    ZcashTheme {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            FlowStep("2 · Sending") { MigrationSendingView(MigrationSendingState(failureSheet = null)) }
            FlowStep("3 · Success") { MigrationSuccessView(previewSuccessState()) }
        }
    }

@Preview(
    name = "Migration – Scheduled Recovery (overdue, catch-up)",
    widthDp = 1400,
    heightDp = 950,
    showBackground = true,
    backgroundColor = 0xFFDDDDDDL
)
@Composable
private fun ScheduledRecoveryFlowPreview() =
    ZcashTheme {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            FlowStep("1 · Progress (in progress)") { MigrationProgressView(previewProgressStateInProgress()) }
            FlowStep("2 · Sending") { MigrationSendingView(MigrationSendingState(failureSheet = null)) }
            FlowStep("3 · Success") { MigrationSuccessView(previewSuccessState()) }
        }
    }

@Preview(
    name = "Migration – Invalid Transfer Recovery",
    widthDp = 1400,
    heightDp = 950,
    showBackground = true,
    backgroundColor = 0xFFDDDDDDL
)
@Composable
private fun InvalidTransferRecoveryFlowPreview() =
    ZcashTheme {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            FlowStep("1 · Transfer Invalid") { MigrationTransferInvalidView(previewTransferInvalidState()) }
            FlowStep("2 · Re-propose: Confirm Transfer Plan") { MigrationReviewView(previewReviewStateAutomatic()) }
            FlowStep("3 · Scheduled") { MigrationScheduledView(previewScheduledState()) }
        }
    }

@Preview(name = "Migration – IMMEDIATE flow", widthDp = 2200, heightDp = 950, showBackground = true, backgroundColor = 0xFFDDDDDDL)
@Composable
private fun ImmediateFlowPreview() =
    ZcashTheme {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            FlowStep("1 · Setup") { MigrationSetupView(previewSetupState(MigrationMode.IMMEDIATE)) }
            FlowStep("3 · Review") { MigrationReviewView(previewReviewStateImmediate()) }
            FlowStep("4 · Sending") { MigrationSendingView(MigrationSendingState(failureSheet = null)) }
            FlowStep("5 · Success") { MigrationSuccessView(previewSuccessState()) }
        }
    }

@Composable
private fun FlowStep(label: String, content: @Composable () -> Unit) {
    Column {
        Text(
            text = label,
            style = ZashiTypography.textSm,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        Box(
            modifier =
                Modifier
                    .width(393.dp)
                    .height(852.dp)
                    .border(1.dp, Color.Black)
                    .background(Color.White),
        ) {
            content()
        }
    }
}

// ── Preview state fixtures ──────────────────────────────────────────────────────

private fun previewSetupState(mode: MigrationMode) =
    MigrationSetupState(
        orchardBalance = stringRes("12.458 ZEC"),
        fiatBalance = stringRes("$4,832.86"),
        isKeystone = false,
        mode = mode,
        onModeChange = {},
        onFindOutMore = {},
        onConfirm = {},
        onBack = {},
    )

private fun previewHowItWorksState() =
    MigrationHowItWorksState(
        onContinue = {},
        onBack = {},
    )

private fun previewBatteryState() =
    MigrationBatteryState(
        onAllow = {},
        onSkip = {},
        onAutoSkip = {},
        onBack = {},
    )

private fun previewNotificationState() =
    MigrationNotificationState(
        onAllow = {},
        onSkip = {},
        onAutoSkip = {},
        onBack = {},
    )

private fun previewReviewStateAutomatic() =
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

private fun previewReviewStateImmediate() =
    MigrationReviewState(
        mode = MigrationMode.IMMEDIATE,
        totalAmount = stringRes("12.458 ZEC"),
        estimatedDuration = stringRes("~1 min"),
        transfers =
            listOf(
                MigrationReviewTransferState(1, 1, stringRes("12.458 ZEC"), stringRes("$4,832.86"), stringRes("Send immediately")),
            ),
        isKeystone = false,
        fee = stringRes("0.0003 ZEC"),
        onConfirm = {},
        onBack = {},
    )

private fun previewScheduledState() =
    MigrationScheduledState(
        totalAmount = stringRes("12.45800 ZEC"),
        transfersProgress = stringRes("0 of 5"),
        duration = stringRes("~8 min"),
        onDone = {},
    )

private fun previewSuccessState() =
    MigrationSuccessState(
        onViewTransaction = {},
        onClose = {},
    )

private fun previewKeystoneSignState() =
    SignKeystoneTransactionState(
        barTitle = stringRes("Sign Transaction"),
        title = stringRes("Scan with your Keystone wallet"),
        subtitle = stringRes("After you have signed with Keystone, tap on the Get Signature button below."),
        accountInfo =
            ZashiAccountInfoListItemState(
                icon = co.electriccoin.zcash.ui.design.R.drawable.ic_item_keystone,
                title = stringRes("Keystone"),
                subtitle = stringRes("u1em92t4hc...qzlykpmssd"),
            ),
        badgeText = stringRes("Hardware"),
        generateNextQrCode = {},
        qrData = "zodl-migration-schedule",
        secondaryButton = null,
        positiveButton = ButtonState(stringRes("Get Signature")),
        negativeButton = ButtonState(stringRes("Reject")),
        onBack = {},
    )

private fun previewCompleteStateWithDust() =
    MigrationCompleteState(
        totalTransferred = stringRes("12.458 ZEC"),
        remainingDust = stringRes("0.00031 ZEC"),
        isDustLocked = false,
        transfersProgress = stringRes("5 of 5 sent"),
        duration = stringRes("~24 hours"),
        onDone = {},
        onMigrateAnyway = {},
        onLockBalance = {},
        onHelp = {},
    )

private fun previewProgressStateInProgress() =
    MigrationProgressState(
        title = stringRes("Migration Progress"),
        subtitle = stringRes("Your balance splits into 5 transfers over ~24 h. There are 3 remaining transfers."),
        totalAmount = stringRes("10.858 ZEC"),
        transfers =
            listOf(
                MigrationProgressTransferState(
                    1,
                    stringRes("1.348 ZEC"),
                    stringRes("Sent"),
                    isReadyNow = false,
                    isAttention = false,
                    isSent = true
                ),
                MigrationProgressTransferState(
                    2,
                    stringRes("1.052 ZEC"),
                    stringRes("Ready now"),
                    isReadyNow = true,
                    isAttention = false,
                    isSent = false
                ),
                MigrationProgressTransferState(
                    3,
                    stringRes("2.105 ZEC"),
                    stringRes("~2 h"),
                    isReadyNow = false,
                    isAttention = false,
                    isSent = false
                ),
                MigrationProgressTransferState(
                    4,
                    stringRes("1.897 ZEC"),
                    stringRes("~5 h"),
                    isReadyNow = false,
                    isAttention = false,
                    isSent = false
                ),
                MigrationProgressTransferState(
                    5,
                    stringRes("4.456 ZEC"),
                    stringRes("Needs reschedule"),
                    isReadyNow = false,
                    isAttention = true,
                    isSent = false
                ),
            ),
        isComplete = false,
        onBack = {},
    )

private fun previewTransferInvalidState() =
    MigrationTransferInvalidState(
        kind = MigrationAttentionKind.TRANSFER_EXPIRED,
        completedCount = 2,
        totalCount = 5,
        remainingCount = 3,
        invalidRange = stringRes("3–5"),
        onContinue = {},
        onBack = {},
    )
