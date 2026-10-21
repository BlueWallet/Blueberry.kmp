package io.bluewallet.blueberry

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.bluewallet.blueberry.ui.TintedPillButton
import io.bluewallet.blueberry.ui.ToPill

/** Watch-only exit: the unsigned transaction as an animated BC-UR QR to sign elsewhere. */
@Composable
internal fun SendPsbtStep(
    to: SendRecipient,
    psbtHex: String,
    onDone: () -> Unit,
    onBack: () -> Unit,
) {
    SendStepFrame(
        title = "Sign elsewhere",
        caption = "Scan with the wallet that holds the keys",
        onBack = onBack,
        bottom = {
            TintedPillButton(text = "Done", onClick = onDone, modifier = Modifier.fillMaxWidth())
        },
    ) {
        ToPill(label = to.label, address = to.address)
        SendCard { UrQr(psbtHex) }
        HelperText("This wallet can't sign. Sign the PSBT on your signing device, then broadcast it from there.")
    }
}
