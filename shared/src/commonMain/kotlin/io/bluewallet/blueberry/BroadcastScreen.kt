@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.bluewallet.blueberry

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.backhandler.BackHandler
import kotlinx.coroutines.launch

/** The broadcast screen opened from the transaction list, with no send wizard under it. */
@Composable
fun BroadcastScreen(
    runtime: PeersRuntime,
    id: String,
    onClose: () -> Unit,
) {
    var session by remember(id) { mutableStateOf(runtime.broadcastStore.sessions().find { it.snapshot.id == id }) }
    var retryNote by remember(id) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    DisposableEffect(runtime.broadcastStore, id) {
        val off =
            runtime.broadcastStore.subscribe {
                scope.launch { session = runtime.broadcastStore.sessions().find { it.snapshot.id == id } }
            }
        onDispose { off() }
    }
    val current = session
    val stored = current?.receipt
    val snap = current?.snapshot

    fun leave() {
        if (snap?.phase == "success") runtime.broadcastStore.dismiss(id)
        onClose()
    }
    BackHandler { leave() }
    if (stored == null || snap == null) {
        SendStepFrame(title = "Send", caption = null, onBack = onClose, onClose = onClose, bottom = {}) {
            HelperText("This broadcast has finished.")
        }
        return
    }
    val receipt = SendReceipt(SendRecipient(stored.destination, stored.label), stored.paidSats, stored.feeSats, stored.txid)
    val status = ownedBroadcastStatus(snap, snap.txHex ?: stored.txid)
    val retryable = status.tone == BroadcastTone.Failed || status.resend
    val shown = if (retryNote != null && retryable) status.copy(line = retryNote!!) else status
    SendBroadcastStep(
        receipt = receipt,
        status = shown,
        actions =
            SendBroadcastActions(
                finish =
                    BroadcastFinishActions(
                        onDone = { leave() },
                        onRetry = { retryNote = retryBroadcast(runtime, id) },
                        onStop = if (broadcastCanBeStopped(snap)) ({ stopBroadcast(runtime, id) }) else null,
                    ),
                onClose = { leave() },
                onForget =
                    if (broadcastCanBeForgotten(snap.phase)) {
                        {
                            forgetBroadcast(runtime, id)
                            onClose()
                        }
                    } else {
                        null
                    },
            ),
    )
}

/** Null when the same transaction is published again. A string is why Retry stopped. */
private fun retryBroadcast(
    runtime: PeersRuntime,
    id: String,
): String? {
    val blocked = retryRefusalFor(runtime, id, runtime.walletTxsStore.get())
    val req = if (blocked != null) null else rebroadcast(runtime.broadcastStore, id)
    if (blocked == null && req != null) requestBroadcast(runtime, req)
    val alreadyRunning = runtime.broadcastStore.get(id)?.phase != null
    return blocked ?: if (req == null && !alreadyRunning) BROADCAST_BUSY else null
}
