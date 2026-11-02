package io.bluewallet.blueberry

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import io.bluewallet.blueberry.ui.BwColors
import io.bluewallet.blueberry.ui.BwFontFamily
import io.bluewallet.blueberry.ui.BwSpace
import io.bluewallet.blueberry.ui.BwType
import io.bluewallet.blueberry.ui.ScannableQr
import io.bluewallet.blueberry.ui.TextAction
import io.bluewallet.blueberry.ui.TintedPillButton
import io.bluewallet.blueberry.ui.ToPill
import io.bluewallet.blueberry.wallet.encodeCryptoPsbtUrFragments
import io.bluewallet.blueberry.wallet.psbtBase64FromHex
import kotlinx.coroutines.delay

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

@Composable
private fun UrQr(psbtHex: String) {
    val parts =
        remember(psbtHex) {
            try {
                encodeCryptoPsbtUrFragments(psbtHex)
            } catch (_: Throwable) {
                emptyList()
            }
        }
    var index by remember(psbtHex) { mutableStateOf(0) }
    LaunchedEffect(parts) {
        if (parts.size <= 1) return@LaunchedEffect
        while (true) {
            delay(1000)
            index = (index + 1) % parts.size
        }
    }
    val part = parts.getOrNull(index) ?: parts.firstOrNull()
    if (part == null) {
        Text("Failed to render PSBT QR", color = BwColors.Danger)
        return
    }
    val clipboard = LocalClipboardManager.current
    var copied by remember(psbtHex) { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(BwSpace.Gap),
    ) {
        ScannableQr(
            data = part,
            contentDescription = "PSBT UR QR",
            modifier = Modifier.fillMaxWidth(0.72f).aspectRatio(1f),
        )
        Text(
            text = if (parts.size > 1) "BC-UR v2 · part ${index + 1}/${parts.size}" else "BC-UR v2 · crypto-psbt",
            color = BwColors.InkQuiet,
            fontFamily = BwFontFamily,
            fontSize = BwType.CaptionSize,
        )
        TextAction(
            text = if (copied) "Copied" else "Copy PSBT",
            onClick = {
                clipboard.setText(AnnotatedString(psbtBase64FromHex(psbtHex)))
                copied = true
            },
        )
    }
}
