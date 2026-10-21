package io.bluewallet.blueberry

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.bluewallet.blueberry.ui.AddressGroups
import io.bluewallet.blueberry.ui.BwColors
import io.bluewallet.blueberry.ui.BwFontFamily
import io.bluewallet.blueberry.ui.BwType
import io.bluewallet.blueberry.ui.TextAction
import io.bluewallet.blueberry.ui.TintedPillButton
import io.bluewallet.blueberry.wallet.isAddressValid
import io.bluewallet.blueberry.wallet.outputScriptFromAddress

internal class SendAddressActions(
    val onAddress: (String) -> Unit,
    val onLabel: (String) -> Unit,
    val onScan: () -> Unit,
    val onContinue: () -> Unit,
    val onBack: () -> Unit,
)

@Composable
internal fun SendAddressStep(
    address: String,
    label: String,
    labelError: Boolean,
    actions: SendAddressActions,
) {
    val valid = isAddressValid(address)
    val typeTag = remember(address, valid) { if (valid) addressTypeLabel(outputScriptFromAddress(address)) else null }
    SendStepFrame(
        title = "Send to",
        caption = "Step 1 of 4",
        onBack = actions.onBack,
        bottom = {
            TintedPillButton(
                text = if (valid) "Continue" else "Enter an address",
                onClick = actions.onContinue,
                enabled = valid,
                modifier = Modifier.fillMaxWidth(),
            )
        },
    ) {
        SendCard {
            OutlinedTextField(
                value = address,
                onValueChange = actions.onAddress,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = address.isNotBlank() && !valid,
                label = { Text("Bitcoin address") },
                placeholder = { Text("bc1…") },
                trailingIcon = { TextAction(text = "Scan", onClick = actions.onScan) },
            )
            when {
                valid -> {
                    AddressGroups(address)
                    if (typeTag != null) TypeTag(typeTag)
                }
                address.isNotBlank() ->
                    HelperText("Not a valid Bitcoin address. Check for a missing or extra character.", error = true)
            }
        }
        SendCard {
            OutlinedTextField(
                value = label,
                onValueChange = actions.onLabel,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = labelError,
                label = { Text("What's it for?") },
                placeholder = { Text("Concert tickets") },
            )
            if (labelError) {
                HelperText("Add a label. It names this payment and your change.", error = true)
            } else {
                HelperText("Stays on this phone and names your change.")
            }
        }
    }
}

@Composable
internal fun HelperText(
    text: String,
    modifier: Modifier = Modifier,
    error: Boolean = false,
) {
    Text(
        text = text,
        modifier = modifier,
        color = if (error) BwColors.Danger else BwColors.InkQuiet,
        fontFamily = BwFontFamily,
        fontSize = BwType.LabelSize,
    )
}

@Composable
private fun TypeTag(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = text,
            color = BwColors.InkQuiet,
            fontFamily = BwFontFamily,
            fontSize = BwType.CaptionSize,
            modifier =
                Modifier
                    .background(BwColors.Section, RoundedCornerShape(50))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}
