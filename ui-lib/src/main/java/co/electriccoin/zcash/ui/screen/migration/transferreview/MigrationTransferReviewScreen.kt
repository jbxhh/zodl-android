package co.electriccoin.zcash.ui.screen.migration.transferreview

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.electriccoin.zcash.ui.design.component.BlankBgScaffold
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.CircularScreenProgressIndicator
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
import co.electriccoin.zcash.ui.screen.migration.review.ImmediateDetailsCard
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel

data class MigrationTransferReviewState(
    val title: StringResource,
    val body: StringResource,
    val amount: StringResource,
    val fee: StringResource,
    val onConfirm: () -> Unit,
    val onBack: () -> Unit,
)

@Serializable
data object MigrationTransferReviewArgs

@Composable
fun MigrationTransferReviewScreen() {
    val vm = koinViewModel<MigrationTransferReviewVM>()
    val state by vm.state.collectAsStateWithLifecycle()
    LceRenderer(
        state = state,
        loading = { isLoading -> if (isLoading && state.content == null) CircularScreenProgressIndicator() },
    ) { s ->
        BackHandler { s.onBack() }
        MigrationTransferReviewView(s)
    }
}

@Composable
fun MigrationTransferReviewView(state: MigrationTransferReviewState) {
    BlankBgScaffold(
        topBar = {
            ZashiSmallTopAppBar(
                navigationAction = { ZashiTopAppBarBackNavigation(onBack = state.onBack) },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .scaffoldPadding(padding),
        ) {
            Text(
                text = state.title.getValue(),
                style = ZashiTypography.header6,
                fontWeight = FontWeight.SemiBold,
                color = ZashiColors.Text.textPrimary,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = state.body.getValue(),
                style = ZashiTypography.textSm,
                color = ZashiColors.Text.textTertiary,
            )
            Spacer(Modifier.height(24.dp))
            // "Fee (estimated)" — TransferProposal (the SDK model backing this screen) has no real
            // per-transfer fee field to show, unlike IMMEDIATE mode's Proposal.totalFeeRequired().
            // See MigrationTransferReviewVM.TRANSFER_FEE_ESTIMATE_ZATOSHI's kdoc.
            ImmediateDetailsCard(amount = state.amount, fee = state.fee, feeLabel = "Fee (estimated)")
            Spacer(Modifier.weight(1f))
            ZashiButton(
                state = ButtonState(text = stringRes("Confirm"), onClick = state.onConfirm),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@PreviewScreens
@Composable
private fun Preview() = ZcashTheme {
    MigrationTransferReviewView(
        state = MigrationTransferReviewState(
            title = stringRes("Review Transfer 3 of 5"),
            body = stringRes(
                "This transfer sends part of your Orchard balance to Ironwood as part of your " +
                    "scheduled migration.\n\nReview and confirm to send the transaction. Once " +
                    "confirmed, this cannot be undone."
            ),
            amount = stringRes("2.43100 ZEC"),
            fee = stringRes("0.001 ZEC"),
            onConfirm = {},
            onBack = {},
        )
    )
}
