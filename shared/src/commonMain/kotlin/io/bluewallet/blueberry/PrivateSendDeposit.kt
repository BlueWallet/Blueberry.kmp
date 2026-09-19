package io.bluewallet.blueberry

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import io.bluewallet.blueberry.parse.formatSatPerVb
import io.bluewallet.blueberry.ui.BtcAmountText
import io.bluewallet.blueberry.ui.BwColors
import io.bluewallet.blueberry.ui.BwFontFamily
import io.bluewallet.blueberry.ui.BwSpace
import io.bluewallet.blueberry.ui.BwType
import io.bluewallet.blueberry.ui.PillButton

@Composable
internal fun PrivateSendDeposit(
    state: PrivateSendUi.Deposit,
    broadcast: BroadcastSnapshot,
    onBroadcast: () -> Unit,
) {
    val showBroadcast = broadcast.txHex != null && broadcast.txHex == state.signed.txHex
    val broadcasting =
        showBroadcast &&
            (broadcastJobInFlight(broadcast.phase) || broadcast.phase == "success" || broadcast.phase == "error")
    if (broadcasting) {
        Column(verticalArrangement = Arrangement.spacedBy(BwSpace.Gap)) {
            BroadcastStatus(broadcast)
        }
        return
    }
    val clipboard = LocalClipboardManager.current
    var copiedOrder by remember(state.swap.requestId) { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(BwSpace.Gap),
    ) {
        PrivateSendFacts(
            state = state,
            copiedOrder = copiedOrder,
            onCopyOrder = {
                clipboard.setText(AnnotatedString(state.swap.requestId))
                copiedOrder = true
            },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
        Text(
            text =
                "This will create multi-hop transaction routed through 3rd parties to unlink your source funds from destination",
            color = BwColors.InkSecondary,
            fontFamily = BwFontFamily,
            fontSize = BwType.CaptionSize,
        )
        PillButton(text = "Private Broadcast", onClick = onBroadcast, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun PrivateSendFacts(
    state: PrivateSendUi.Deposit,
    copiedOrder: Boolean,
    onCopyOrder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(BwSpace.Gap),
    ) {
        val amount = rocketxGetSats(state.swap.toAmount)
        if (amount != null) {
            PrivateSendDetail("Amount", sats = amount)
        }
        if (state.label.isNotEmpty()) {
            PrivateSendDetail("Label", state.label)
        }
        if (state.swap.requestId.isNotEmpty()) {
            PrivateSendDetail(
                label = "Order ID",
                value = state.swap.requestId,
                copied = copiedOrder,
                onClick = onCopyOrder,
            )
        }
        PrivateSendDetail("Time to complete", formatRocketxEta(state.swap.estSeconds))
        PrivateSendDetail(
            "Refund",
            "Failed send will be refunded to your address: ${state.refundAddress}",
        )
        PrivateSendDetail(label = "Transaction fee", sats = state.signed.feeSats)
        PrivateSendDetail("Fee rate", formatSatPerVb(state.signed.feeSats, state.signed.vsize))
        val service = rocketxServiceFeeSats(state.swap.fromAmount, state.swap.toAmount)
        if (service != null) {
            PrivateSendDetail("Service fee", sats = service)
        }
        val overpay = rocketxOverpaySats(state.swap.toAmount, state.needBtc)
        if (overpay != null) {
            Text(
                text = "warning: destination is slightly overpaid (by $overpay sat)",
                color = BwColors.Warning,
                fontFamily = BwFontFamily,
                fontSize = BwType.BodySize,
            )
        }
    }
}

@Composable
private fun PrivateSendDetail(
    label: String,
    value: String = "",
    sats: Long? = null,
    copied: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    Column(
        modifier =
            Modifier.fillMaxWidth().then(
                if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
            ),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = label,
            color = BwColors.InkSecondary,
            fontFamily = BwFontFamily,
            fontSize = BwType.LabelSize,
            fontWeight = BwType.Label,
        )
        if (sats != null) {
            BtcAmountText(sats = sats, color = BwColors.Ink, fontSize = BwType.BodySize)
        } else {
            Text(
                text = value,
                color = BwColors.Ink,
                fontFamily = BwFontFamily,
                fontSize = BwType.BodySize,
            )
        }
        if (copied) {
            Text(
                text = "Copied",
                color = BwColors.Success,
                fontFamily = BwFontFamily,
                fontSize = BwType.CaptionSize,
                fontWeight = BwType.Caption,
            )
        }
    }
}
