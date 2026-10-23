package io.bluewallet.blueberry

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.bluewallet.blueberry.parse.shortTxid
import io.bluewallet.blueberry.ui.AmountHero
import io.bluewallet.blueberry.ui.BtcAmountText
import io.bluewallet.blueberry.ui.BwColors
import io.bluewallet.blueberry.ui.BwFontFamily
import io.bluewallet.blueberry.ui.BwSpace
import io.bluewallet.blueberry.ui.BwType
import io.bluewallet.blueberry.ui.PlainPillButton
import io.bluewallet.blueberry.ui.TextAction
import io.bluewallet.blueberry.ui.TintedPillButton
import io.bluewallet.blueberry.ui.ToPill

class BroadcastFinishActions(
    val onDone: () -> Unit,
    val onRetry: () -> Unit,
    /** Set only while the job runs and no node has been given the transaction. */
    val onStop: (() -> Unit)? = null,
)

internal class SendBroadcastActions(
    val finish: BroadcastFinishActions,
    /** Leaves the screen. A running broadcast keeps going. */
    val onClose: () -> Unit,
    /** Set only when this broadcast failed, so it can be dropped from memory. */
    val onForget: (() -> Unit)? = null,
)

internal class SendReceipt(
    val to: SendRecipient,
    val paidSats: Long,
    val feeSats: Long,
    val txid: String,
)

@Composable
internal fun SendBroadcastStep(
    receipt: SendReceipt,
    status: BroadcastStatusUi,
    actions: SendBroadcastActions,
) {
    SendStepFrame(
        title = status.title,
        caption = null,
        onBack = actions.onClose,
        onClose = actions.onClose,
        bottom = {
            when (status.tone) {
                BroadcastTone.Sent -> SentBroadcastActions(actions.finish, status.resend)
                BroadcastTone.Failed -> FailedBroadcastActions(actions)
                BroadcastTone.Working -> WorkingBroadcastActions(actions.finish.onStop)
            }
        },
    ) {
        AmountHero(sats = receipt.paidSats)
        ToPill(label = receipt.to.label, address = receipt.to.address)
        BroadcastStatusCard(status)
        if (status.tone == BroadcastTone.Sent) ReceiptCard(receipt)
    }
}

/** A running job keeps going when the screen closes. Shared by Send and Private Send. */
@Composable
internal fun WorkingBroadcastActions(onStop: (() -> Unit)?) {
    Column(verticalArrangement = Arrangement.spacedBy(BwSpace.Gap)) {
        HelperText(
            "You can close this screen. Sending continues.",
            modifier = Modifier.fillMaxWidth(),
            align = TextAlign.Center,
        )
        if (onStop != null) StopSendingButton(onStop)
    }
}

/** Done, plus Send again while no node has confirmed the transaction. */
@Composable
private fun SentBroadcastActions(
    finish: BroadcastFinishActions,
    resend: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(BwSpace.Gap)) {
        TintedPillButton(text = "Done", onClick = finish.onDone, modifier = Modifier.fillMaxWidth())
        if (resend) SendAgainButton(finish.onRetry)
    }
}

/** Gives the same signed transaction to nodes again. Shared by Send and Private Send. */
@Composable
internal fun SendAgainButton(onRetry: () -> Unit) {
    PlainPillButton(text = "Send again", onClick = onRetry, modifier = Modifier.fillMaxWidth())
}

/** Stops a broadcast before any node has it. Shared by Send and Private Send. */
@Composable
internal fun StopSendingButton(onStop: () -> Unit) {
    TintedPillButton(text = "Stop sending", onClick = onStop, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun FailedBroadcastActions(actions: SendBroadcastActions) {
    val forget = actions.onForget
    Column(verticalArrangement = Arrangement.spacedBy(BwSpace.Gap)) {
        TintedPillButton(text = "Retry", onClick = actions.finish.onRetry, modifier = Modifier.fillMaxWidth())
        if (forget != null) {
            PlainPillButton(
                text = "Forget this transaction",
                onClick = forget,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Status line and caption for a broadcast, shared by Send and Private Send. */
@Composable
internal fun BroadcastStatusCard(status: BroadcastStatusUi) {
    SendCard {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (status.tone == BroadcastTone.Working) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = BwColors.Accent)
            }
            Text(
                text = status.line,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                color =
                    when (status.tone) {
                        BroadcastTone.Sent -> BwColors.Success
                        BroadcastTone.Failed -> BwColors.Danger
                        BroadcastTone.Working -> BwColors.Ink
                    },
                fontFamily = BwFontFamily,
                fontSize = BwType.BodySize,
                fontWeight = BwType.Body,
            )
        }
        if (status.caption != null) HelperText(status.caption)
    }
}

@Composable
private fun ReceiptCard(receipt: SendReceipt) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(receipt.txid) { mutableStateOf(false) }
    SendCard {
        ReceiptLine("Fee") { BtcAmountText(sats = receipt.feeSats, fontSize = BwType.LabelSize) }
        ReceiptLine("Transaction") {
            Text(
                text = shortTxid(receipt.txid),
                color = BwColors.Ink,
                fontFamily = BwFontFamily,
                fontSize = BwType.LabelSize,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextAction(
            text = if (copied) "Copied" else "Copy transaction ID",
            onClick = {
                clipboard.setText(AnnotatedString(receipt.txid))
                copied = true
            },
        )
    }
}

@Composable
private fun ReceiptLine(
    label: String,
    value: @Composable () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = BwColors.InkQuiet, fontFamily = BwFontFamily, fontSize = BwType.LabelSize)
        }
        value()
    }
}
