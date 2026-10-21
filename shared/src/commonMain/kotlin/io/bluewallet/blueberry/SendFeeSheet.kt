package io.bluewallet.blueberry

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.bluewallet.blueberry.parse.formatBtc
import io.bluewallet.blueberry.ui.BwColors
import io.bluewallet.blueberry.ui.BwFontFamily
import io.bluewallet.blueberry.ui.BwType
import io.bluewallet.blueberry.ui.PillButton
import io.bluewallet.blueberry.ui.ProminentButtonHeight
import io.bluewallet.blueberry.ui.TextAction

internal class SendFeeSheetActions(
    val onDraft: (String) -> Unit,
    val onApply: (Double) -> Unit,
    val onAddCoins: () -> Unit,
    val onDismiss: () -> Unit,
)

/** Bottom sheet for the fee rate. Typing only estimates; Apply hands the rate back for a rebuild. */
@Composable
internal fun SendFeeSheet(
    draft: String,
    check: FeeDraftCheck,
    actions: SendFeeSheetActions,
) {
    val shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(BwColors.Card, shape)
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CardTitle("Network fee")
        OutlinedTextField(
            value = draft,
            onValueChange = actions.onDraft,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            isError = draft.isNotBlank() && check !is FeeDraftCheck.Ok,
            placeholder = { Text("3") },
            suffix = { Text("sat/vB") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )
        when (check) {
            is FeeDraftCheck.Ok -> FeePreview(check.estimate)
            is FeeDraftCheck.Short -> {
                HelperText("These coins are ${formatBtc(check.missingSats)} short at this rate.", error = true)
                TextAction(text = "Add coins", onClick = actions.onAddCoins)
            }
            FeeDraftCheck.Invalid ->
                if (draft.isNotBlank()) HelperText("Enter a rate above 0 sat/vB.", error = true)
        }
        HelperText("Check a fee source you trust. 1 sat/vB can take days.")
        PillButton(
            text = "Apply",
            onClick = { (check as? FeeDraftCheck.Ok)?.let { actions.onApply(it.rate) } },
            modifier = Modifier.fillMaxWidth(),
            enabled = check is FeeDraftCheck.Ok,
            height = ProminentButtonHeight,
        )
        TextAction(text = "Cancel", onClick = actions.onDismiss, modifier = Modifier.align(Alignment.CenterHorizontally))
    }
}

@Composable
private fun FeePreview(estimate: SendFeeEstimate.Ok) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "≈ ${estimate.vsize} vB",
            modifier = Modifier.weight(1f),
            color = BwColors.InkQuiet,
            fontFamily = BwFontFamily,
            fontSize = BwType.LabelSize,
        )
        Text(
            text = "≈ ${formatBtc(estimate.feeSats)}",
            color = BwColors.Ink,
            fontFamily = BwFontFamily,
            fontSize = BwType.BodySize,
            fontWeight = BwType.Body,
        )
    }
    HelperText(
        if (estimate.changeSats > 0) "Change ≈ ${formatBtc(estimate.changeSats)}" else "No change output",
    )
}
