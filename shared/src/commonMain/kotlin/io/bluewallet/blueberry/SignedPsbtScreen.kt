package io.bluewallet.blueberry

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.unit.dp
import io.bluewallet.blueberry.storage.Database
import io.bluewallet.blueberry.ui.AddressGroups
import io.bluewallet.blueberry.ui.AmountHero
import io.bluewallet.blueberry.ui.BtcAmountText
import io.bluewallet.blueberry.ui.BwColors
import io.bluewallet.blueberry.ui.BwFontFamily
import io.bluewallet.blueberry.ui.BwSpace
import io.bluewallet.blueberry.ui.BwType
import io.bluewallet.blueberry.ui.OverlayBackEffects
import io.bluewallet.blueberry.ui.TextAction
import io.bluewallet.blueberry.ui.TintedPillButton
import io.bluewallet.blueberry.ui.ToPill
import io.bluewallet.blueberry.wallet.SignedPsbt
import io.bluewallet.blueberry.wallet.SignedPsbtCheck
import io.bluewallet.blueberry.wallet.finalizeSignedPsbt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The stored unsigned payment a signed PSBT is scanned for. [storedTxid] keys its `sends` row. */
internal class SignedPsbtTarget(
    val storedTxid: String,
    val to: SendRecipient,
    val psbtHex: String,
)

/**
 * Scan the signed PSBT, review it, then broadcast it. Back goes one step back; [onBack] leaves from the scan.
 * [onDone] gets the broadcast txid, which differs from [SignedPsbtTarget.storedTxid] for legacy and nested inputs.
 */
@Composable
internal fun SignedPsbtFlow(
    runtime: PeersRuntime,
    db: Database,
    target: SignedPsbtTarget,
    onDone: (txid: String) -> Unit,
    onBack: () -> Unit,
) {
    var signed by remember(target.storedTxid) { mutableStateOf<SignedPsbt?>(null) }
    var broadcastId by remember(target.storedTxid) { mutableStateOf<String?>(null) }
    val id = broadcastId
    val review = signed
    when {
        id != null -> {
            val done = { onDone(review?.txid ?: target.storedTxid) }
            OverlayBackEffects(enabled = true, onDismiss = done) {
                BroadcastScreen(runtime = runtime, id = id, onClose = done)
            }
        }
        review != null ->
            OverlayBackEffects(enabled = true, onDismiss = { signed = null }) {
                SignedPsbtReview(runtime, db, target, review, onStarted = { broadcastId = it }, onBack = { signed = null })
            }
        else ->
            OverlayBackEffects(enabled = true, onDismiss = onBack) {
                SignedPsbtScanStep(target, onSigned = { signed = it }, onBack = onBack)
            }
    }
}

@Composable
private fun SignedPsbtScanStep(
    target: SignedPsbtTarget,
    onSigned: (SignedPsbt) -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val scan = remember(target.storedTxid) { SignedPsbtScan() }
    var state by remember(target.storedTxid) { mutableStateOf(scan.state) }
    var checking by remember(target.storedTxid) { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    var checkJob by remember(target.storedTxid) { mutableStateOf<Job?>(null) }

    fun check(psbt: ByteArray) {
        checking = true
        checkJob =
            scope.launch {
                try {
                    // Cancelled by Start over: withContext then throws on return, so a discarded PSBT never lands.
                    val result =
                        withContext(Dispatchers.Default) {
                            runCatching { finalizeSignedPsbt(target.psbtHex, psbt) }
                                .getOrElse { SignedPsbtCheck.Failed("Couldn't check this PSBT: ${it.message ?: it}") }
                        }
                    when (result) {
                        is SignedPsbtCheck.Ok -> onSigned(result.signed)
                        is SignedPsbtCheck.Failed -> {
                            scan.reject(result.reason)
                            state = scan.state
                        }
                    }
                } finally {
                    if (checkJob == coroutineContext[Job]) checking = false
                }
            }
    }

    fun startOver() {
        checkJob?.cancel()
        checkJob = null
        checking = false
        scan.reset()
        state = scan.state
    }

    fun accept(text: String): Boolean {
        if (checking) return false
        val next = scan.accept(text)
        state = next
        if (next is SignedPsbtScanState.Done) check(next.psbt)
        return false
    }

    SendStepFrame(
        title = "Scan signed PSBT",
        caption = "From the wallet that signed it",
        onBack = onBack,
        bottom = {},
    ) {
        SendCard {
            QrFrameScanner(
                onText = ::accept,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(BwSpace.Radius)),
            )
            ScanProgress(state, checking)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(BwSpace.Gap)) {
            TextAction(text = "Paste", onClick = { clipboard.getText()?.text?.let(::accept) })
            TextAction(text = "Start over", onClick = ::startOver)
        }
        HelperText("Show the signed transaction as a QR code on your signing device. Animated QR codes are read frame by frame.")
    }
}

@Composable
private fun ScanProgress(
    state: SignedPsbtScanState,
    checking: Boolean,
) {
    val scanning = state as? SignedPsbtScanState.Scanning
    val percent = if (checking || state is SignedPsbtScanState.Done) 100 else scanning?.percent
    Text(
        text =
            when {
                checking -> "Checking the signatures…"
                percent != null -> "Scanning animated QR… $percent%"
                else -> "Point the camera at the signed PSBT QR"
            },
        color = BwColors.Ink,
        fontFamily = BwFontFamily,
        fontSize = BwType.BodySize,
    )
    if (percent != null) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(BwColors.Section),
        ) {
            Box(modifier = Modifier.fillMaxWidth(percent / 100f).fillMaxHeight().background(BwColors.Accent))
        }
    }
    scanning?.note?.let { HelperText(it, error = true) }
}

@Composable
@Suppress("LongParameterList")
private fun SignedPsbtReview(
    runtime: PeersRuntime,
    db: Database,
    target: SignedPsbtTarget,
    signed: SignedPsbt,
    onStarted: (String) -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var error by remember(signed) { mutableStateOf<String?>(null) }
    var sending by remember(signed) { mutableStateOf(false) }
    val paid = remember(signed) { signed.paidTo(target.to.address) }

    fun broadcast() {
        sending = true
        error = null
        scope.launch {
            val stored = withContext(Dispatchers.Default) { storeSignedPsbt(db, runtime, target.storedTxid, signed) }
            val receipt = BroadcastReceipt(signed.txid, target.to.address, target.to.label, paid, signed.feeSats)
            val sent = if (stored) startSignedPsbtBroadcast(runtime, db, signed, receipt) else SignedPsbtSent.Refused(SIGNED_PSBT_GONE)
            sending = false
            when (sent) {
                is SignedPsbtSent.Started -> onStarted(sent.id)
                is SignedPsbtSent.Refused -> error = sent.reason
            }
        }
    }

    SendStepFrame(
        title = "Review",
        caption = "Signed and ready to broadcast",
        onBack = onBack,
        bottom = {
            TintedPillButton(text = if (sending) "Broadcasting…" else "Broadcast", onClick = ::broadcast, enabled = !sending, modifier = Modifier.fillMaxWidth())
        },
    ) {
        if (target.to.label.isNotBlank()) ToPill(label = target.to.label, address = "")
        AmountHero(sats = paid)
        AddressGroups(address = target.to.address, modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp - BwSpace.Gap))
        SendCard {
            ReviewAmountRow("Network fee", signed.feeSats)
            HorizontalDivider(color = BwColors.Section)
            ReviewAmountRow("Total leaving your wallet", paid + signed.feeSats)
        }
        HelperText("This is the transaction you asked your signing device to sign. Broadcasting sends it over Tor.")
        error?.let { HelperText(it, error = true) }
    }
}

@Composable
private fun ReviewAmountRow(
    title: String,
    sats: Long,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            color = BwColors.InkQuiet,
            fontFamily = BwFontFamily,
            fontSize = BwType.BodySize,
        )
        BtcAmountText(sats = sats, fontSize = BwType.BodySize)
    }
}
