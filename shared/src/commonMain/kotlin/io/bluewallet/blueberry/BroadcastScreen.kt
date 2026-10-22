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
import io.bluewallet.blueberry.bus.BroadcastCancelPayload
import io.bluewallet.blueberry.bus.Event
import kotlinx.coroutines.launch

/** The broadcast screen opened from the transaction list, with no send wizard under it. */
@Composable
fun BroadcastScreen(
    runtime: PeersRuntime,
    id: String,
    onClose: () -> Unit,
) {
    var session by remember(id) { mutableStateOf(runtime.broadcastStore.sessions().find { it.snapshot.id == id }) }
    var cancelArmed by remember(id) { mutableStateOf(false) }
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
    val cancelling = cancelArmed && broadcastJobInFlight(snap.phase)
    val status = ownedBroadcastStatus(snap, snap.txHex ?: stored.txid, cancelling)
    val shown = if (retryNote != null && status.tone == BroadcastTone.Failed) status.copy(line = retryNote!!) else status
    SendBroadcastStep(
        receipt = receipt,
        status = shown,
        actions =
            SendBroadcastActions(
                finish =
                    BroadcastFinishActions(
                        onDone = { leave() },
                        onRetry = {
                            val note = retryBroadcast(runtime, id)
                            retryNote = note
                            if (note == null) cancelArmed = false
                        },
                    ),
                onCancel = {
                    if (!broadcastJobInFlight(snap.phase)) return@SendBroadcastActions
                    runtime.broadcastStore.requestStop(id)
                    runtime.bus.emit(Event.BroadcastCancel, BroadcastCancelPayload(id))
                    cancelArmed = true
                },
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
    val session = runtime.broadcastStore.sessions().find { it.snapshot.id == id }
    val wallet = runtime.walletTxsStore.get()
    val blocked =
        retryRefusal(
            session?.snapshot?.txHex,
            wallet.utxos.map { it.key }.toSet(),
            wallet.utxosReady,
            runtime.reservedOutpoints(),
        )
    val req = if (blocked != null) null else rebroadcast(runtime.broadcastStore, id)
    if (blocked == null && req != null) runtime.bus.emit(Event.BroadcastRequest, req)
    return blocked ?: if (req == null) BROADCAST_BUSY else null
}
