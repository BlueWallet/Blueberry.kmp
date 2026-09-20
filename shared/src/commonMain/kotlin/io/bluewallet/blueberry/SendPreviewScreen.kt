package io.bluewallet.blueberry

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.dp
import io.bluewallet.blueberry.ui.BtcAmountText
import io.bluewallet.blueberry.ui.BwColors
import io.bluewallet.blueberry.ui.BwFontFamily
import io.bluewallet.blueberry.ui.BwSpace
import io.bluewallet.blueberry.ui.BwType
import io.bluewallet.blueberry.ui.PillButton
import io.bluewallet.blueberry.ui.ProgressMetricCard
import io.bluewallet.blueberry.ui.ScannableQr
import io.bluewallet.blueberry.wallet.BuildSendResult
import io.bluewallet.blueberry.wallet.PsbtSendResult
import io.bluewallet.blueberry.wallet.SignedSendResult
import io.bluewallet.blueberry.wallet.encodeCryptoPsbtUrFragments
import io.bluewallet.blueberry.wallet.psbtBase64FromHex
import kotlinx.coroutines.delay

private fun previewTotals(preview: BuildSendResult): SendPreviewTotals =
    when (preview) {
        is SignedSendResult ->
            SendPreviewTotals(preview.feeSats, preview.txHex.length / 2, preview.vsize, preview.changeSats)
        is PsbtSendResult -> SendPreviewTotals(preview.feeSats, preview.vsize, preview.vsize, preview.changeSats)
    }

@Composable
internal fun PreviewStep(
    preview: BuildSendResult?,
    details: SendDetails?,
    inputSum: Long,
    broadcast: BroadcastSnapshot,
    actions: PreviewActions,
) {
    val signed = preview as? SignedSendResult
    val psbt = preview as? PsbtSendResult
    val showBroadcast = broadcast.txHex != null && signed != null && broadcast.txHex == signed.txHex
    val broadcasting =
        showBroadcast &&
            (broadcastJobInFlight(broadcast.phase) || broadcast.phase == "success" || broadcast.phase == "error")
    when {
        preview == null || details == null -> Text("Missing preview", color = BwColors.InkMuted)
        broadcasting ->
            BroadcastStatus(
                broadcast = broadcast,
                finish = actions.finish,
            )
        psbt != null -> UrQr(psbt.psbtHex)
        else ->
            SignedPreviewReview(
                details = details,
                inputSum = inputSum,
                totals = previewTotals(preview),
                txHex = (preview as SignedSendResult).txHex,
                onBroadcast = actions.onBroadcast,
            )
    }
}

class BroadcastFinishActions(
    val onDone: () -> Unit,
    val onRetry: () -> Unit,
)

internal class PreviewActions(
    val onBroadcast: () -> Unit,
    val finish: BroadcastFinishActions,
)

@Composable
internal fun BroadcastStatus(
    broadcast: BroadcastSnapshot,
    finish: BroadcastFinishActions,
) {
    Column(verticalArrangement = Arrangement.spacedBy(BwSpace.Gap)) {
        when {
            broadcastJobInFlight(broadcast.phase) -> {
                ProgressMetricCard(
                    label = "Broadcasting via Tor...",
                    value = "${broadcast.percent ?: 0}%",
                    percent = broadcast.percent ?: 0,
                    caption = broadcastTorCaption(broadcast.stage ?: "Starting"),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text =
                        listOfNotNull(
                            broadcast.phase,
                            broadcast.attempt?.let { "attempt $it/${broadcast.maxAttempts ?: "?"}" },
                            broadcast.peer,
                            broadcast.detail,
                        ).joinToString(" · "),
                    color = BwColors.InkMuted,
                    fontFamily = BwFontFamily,
                    fontSize = BwType.BodySize,
                )
            }
            broadcast.phase == "success" -> {
                Text("Broadcast succeeded", color = BwColors.Success, fontFamily = BwFontFamily, fontWeight = BwType.Label)
                Text(broadcast.peer.orEmpty(), color = BwColors.InkMuted)
                PillButton(text = "Done", onClick = finish.onDone, modifier = Modifier.fillMaxWidth())
            }
            else -> {
                Text("Broadcast failed", color = BwColors.Danger, fontFamily = BwFontFamily, fontWeight = BwType.Label)
                Text(broadcast.error.orEmpty(), color = BwColors.InkMuted)
                PillButton(text = "Retry", onClick = finish.onRetry, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun SignedPreviewReview(
    details: SendDetails,
    inputSum: Long,
    totals: SendPreviewTotals,
    txHex: String,
    onBroadcast: () -> Unit,
) {
    val fields = sendPreviewFields(details, inputSum, totals)
    val clipboard = LocalClipboardManager.current
    var copied by remember(txHex) { mutableStateOf<String?>(null) }
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(BwSpace.Gap),
    ) {
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(BwSpace.Gap),
        ) {
            for (field in fields) {
                SendPreviewFieldRow(field)
            }
            ExportCopies(
                copied = copied,
                onCopyTxHex = {
                    clipboard.setText(AnnotatedString(txHex))
                    copied = "tx"
                },
            )
        }
        PillButton(text = "Private Broadcast", onClick = onBroadcast, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun ExportCopies(
    copied: String?,
    onCopyTxHex: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = BwSpace.Gap),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = "Export",
            color = BwColors.InkSecondary,
            fontFamily = BwFontFamily,
            fontSize = BwType.LabelSize,
            fontWeight = BwType.Label,
        )
        ExportLink(text = "Copy tx hex", copied = copied == "tx", onClick = onCopyTxHex)
    }
}

@Composable
private fun ExportLink(
    text: String,
    copied: Boolean,
    onClick: () -> Unit,
) {
    Text(
        text = if (copied) "Copied" else text,
        color = if (copied) BwColors.Success else BwColors.InkMuted,
        fontFamily = BwFontFamily,
        fontSize = BwType.CaptionSize,
        fontWeight = BwType.Caption,
        modifier = Modifier.clickable(onClick = onClick).padding(vertical = BwSpace.Gap),
    )
}

@Composable
private fun SendPreviewFieldRow(field: SendPreviewField) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = field.label,
            color = BwColors.InkSecondary,
            fontFamily = BwFontFamily,
            fontSize = BwType.LabelSize,
            fontWeight = BwType.Label,
        )
        val sats = field.sats
        if (sats != null) {
            BtcAmountText(
                sats = sats,
                color = if (field.label == "Send") BwColors.Ink else BwColors.InkMuted,
                fontSize = if (field.label == "Send") BwType.ValueSize else BwType.BodySize,
            )
        } else {
            Text(
                text = field.value,
                color = BwColors.Ink,
                fontFamily = BwFontFamily,
                fontSize = BwType.BodySize,
                fontWeight = BwType.Body,
                softWrap = true,
            )
        }
        if (field.caption != null) {
            Text(
                text = field.caption,
                color = BwColors.InkMuted,
                fontFamily = BwFontFamily,
                fontSize = BwType.CaptionSize,
                fontWeight = BwType.Caption,
            )
        }
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
            color = BwColors.InkMuted,
            fontFamily = BwFontFamily,
            fontSize = BwType.CaptionSize,
        )
        ExportLink(
            text = "Copy PSBT",
            copied = copied,
            onClick = {
                clipboard.setText(AnnotatedString(psbtBase64FromHex(psbtHex)))
                copied = true
            },
        )
    }
}
