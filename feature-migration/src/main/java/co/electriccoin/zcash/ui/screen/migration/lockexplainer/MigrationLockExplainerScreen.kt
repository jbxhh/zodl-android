package co.electriccoin.zcash.ui.screen.migration.lockexplainer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.Spacer
import co.electriccoin.zcash.ui.design.component.ZashiButton
import co.electriccoin.zcash.ui.design.component.ZashiScreenModalBottomSheet
import co.electriccoin.zcash.ui.design.component.rememberScreenModalBottomSheetState
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.theme.colors.ZashiColors
import co.electriccoin.zcash.ui.design.theme.typography.ZashiTypography
import co.electriccoin.zcash.ui.design.util.stringRes
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel

@Serializable
data object MigrationLockExplainerArgs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MigrationLockExplainerScreen() {
    val vm = koinViewModel<MigrationLockExplainerVM>()
    val state by vm.state.collectAsStateWithLifecycle()
    MigrationLockExplainerView(state)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MigrationLockExplainerView(
    state: MigrationLockExplainerState?,
    sheetState: SheetState = rememberScreenModalBottomSheetState(),
) {
    ZashiScreenModalBottomSheet(
        state = state,
        sheetState = sheetState,
    ) { innerState, contentPadding ->
        Column(
            modifier =
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(start = 24.dp, end = 24.dp, bottom = contentPadding.calculateBottomPadding()),
        ) {
            Text(
                text = "What does locking do?",
                style = ZashiTypography.textXl,
                fontWeight = FontWeight.SemiBold,
                color = ZashiColors.Text.textPrimary,
            )
            Spacer(4.dp)
            LockExplainerBullet(
                buildAnnotatedString {
                    append("Locking marks this balance as ")
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append("unspendable") }
                    append(", so it can't compromise your privacy in future transactions.")
                }
            )
            Spacer(16.dp)
            LockExplainerBullet(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append("The lock lives on this Zodl/Keystone instance only.")
                    }
                    append(" Restoring your wallet won't carry it over.")
                }
            )
            Spacer(16.dp)
            LockExplainerBullet(
                buildAnnotatedString {
                    append("A future release will let you ")
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append("unlock this balance.") }
                }
            )
            Spacer(32.dp)
            ZashiButton(
                state =
                    ButtonState(
                        text = stringRes("Got it"),
                        onClick = innerState.onGotIt,
                    ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun LockExplainerBullet(text: androidx.compose.ui.text.AnnotatedString) {
    Text(
        text = text,
        style = ZashiTypography.textSm,
        color = ZashiColors.Text.textTertiary,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@PreviewScreens
@Composable
private fun Preview() =
    ZcashTheme {
        MigrationLockExplainerView(
            state = MigrationLockExplainerState(onGotIt = {}, onBack = {})
        )
    }
