package co.electriccoin.zcash.ui.screen.migration.howitworks

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
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
import co.electriccoin.zcash.ui.design.util.scaffoldPadding
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.common.LceRenderer
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel

@Serializable
data object MigrationHowItWorksArgs

@Composable
fun MigrationHowItWorksScreen() {
    val vm = koinViewModel<MigrationHowItWorksVM>()
    val state by vm.state.collectAsStateWithLifecycle()
    LceRenderer(
        state = state,
        loading = { isLoading -> if (isLoading && state.content == null) CircularScreenProgressIndicator() },
    ) { s ->
        BackHandler { s.onBack() }
        MigrationHowItWorksView(s)
    }
}

@Composable
fun MigrationHowItWorksView(state: MigrationHowItWorksState) {
    BlankBgScaffold(
        topBar = {
            ZashiSmallTopAppBar(
                navigationAction = { ZashiTopAppBarBackNavigation(onBack = state.onBack) },
                regularActions = {},
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
                text = "How This Works",
                style = ZashiTypography.header6,
                fontWeight = FontWeight.SemiBold,
                color = ZashiColors.Text.textPrimary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Moving funds between Zcash pools reveals the amount of each transfer. Here's how we " +
                    "protect your privacy during migration.",
                style = ZashiTypography.textSm,
                color = ZashiColors.Text.textTertiary,
            )
            Spacer(Modifier.height(32.dp))
            HowItWorksStep(
                icon = co.electriccoin.zcash.ui.R.drawable.ic_migration_coins_swap,
                title = "Split and schedule",
                description = "Your balance is divided into smaller amounts and spaced out over time, so " +
                    "they’re harder to link together.",
            )
            Spacer(Modifier.height(16.dp))
            HowItWorksStep(
                icon = co.electriccoin.zcash.ui.R.drawable.ic_migration_check_square_broken,
                title = "Approve once",
                description = "Zodl handles the rest, sending each transfer automatically in its scheduled " +
                    "window while the app runs in the background.",
            )
            Spacer(Modifier.height(16.dp))
            HowItWorksStep(
                icon = co.electriccoin.zcash.ui.R.drawable.ic_migration_notif_bell_ringing,
                title = "If something fails",
                description = "We’ll notify you so you can complete it manually.",
            )
            Spacer(Modifier.height(16.dp))
            HowItWorksStep(
                icon = co.electriccoin.zcash.ui.R.drawable.ic_migration_calendar,
                title = "Large balance",
                description = "If your wallet holds large balance or many small notes, migration may run " +
                    "across multiple scheduled rounds.",
            )
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.height(32.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                Icon(
                    painter = painterResource(co.electriccoin.zcash.ui.design.R.drawable.ic_info),
                    contentDescription = null,
                    tint = ZashiColors.Text.textTertiary,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "Choosing this option may require a small amount (less than 0.01 ZEC) to be " +
                        "left in the Orchard pool, and which won’t be transferred.",
                    style = ZashiTypography.textXs,
                    color = ZashiColors.Text.textTertiary,
                )
            }
            Spacer(Modifier.height(20.dp))
            ZashiButton(
                state = ButtonState(text = stringRes("Continue"), onClick = state.onContinue),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun HowItWorksStep(icon: Int, title: String, description: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = ZashiColors.Text.textPrimary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                    append(title)
                }
                append(" — $description")
            },
            style = ZashiTypography.textSm,
            color = ZashiColors.Text.textPrimary,
        )
    }
}

@PreviewScreens
@Composable
private fun Preview() = ZcashTheme {
    MigrationHowItWorksView(
        state = MigrationHowItWorksState(onContinue = {}, onBack = {})
    )
}
