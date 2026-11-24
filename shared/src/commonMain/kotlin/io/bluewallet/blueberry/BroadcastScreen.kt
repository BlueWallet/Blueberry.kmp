@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.bluewallet.blueberry

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.backhandler.BackHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    // Forget is asked again on wallet refreshes too: a refused send's row can be dropped after its failure showed.
    var holdsSeen by remember(id) { mutableStateOf(0) }
    DisposableEffect(runtime, id) {
        val off = watchBroadcastForgettable(runtime, id) { scope.launch { holdsSeen++ } }
        holdsSeen++ // a drop between the first composition and this subscription is not missed
        onDispose { off() }
    }
    val current = session
    val stored = current?.receipt
    val snap = current?.snapshot

    fun leave() {
        dismissConfirmedBroadcast(runtime.broadcastStore, id)
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
    // Asked off the UI thread; until the answer for this phase lands, a job that left "error" shows no Forget.
    var forgettableAsked by remember(id) { mutableStateOf(false) }
    LaunchedEffect(id, snap.phase, holdsSeen) { forgettableAsked = broadcastJobForgettable(runtime, id) }
    val forgettable = forgettableAsked && broadcastCanBeForgotten(snap.phase)
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
                    if (forgettable) {
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

/**
 * [broadcastJobCanBeForgotten] off the caller's dispatcher: a cleared stored-holds cache is filled from SQLite, which
 * the broadcast screen must not do during composition.
 */
suspend fun broadcastJobForgettable(
    runtime: PeersRuntime,
    id: String,
): Boolean = withContext(Dispatchers.Default) { broadcastJobCanBeForgotten(runtime, id) }

/**
 * Calls [onChange] whenever [broadcastJobCanBeForgotten] for job [id] may have changed: the job changed, or the wallet
 * store refreshed while the job had failed. A refused send's row is dropped, and that refresh lands, after its
 * failure did. Returns the unsubscribe.
 */
fun watchBroadcastForgettable(
    runtime: PeersRuntime,
    id: String,
    onChange: () -> Unit,
): () -> Unit {
    val offJob = runtime.broadcastStore.subscribe(onChange)
    val offWallet =
        runtime.walletTxsStore.subscribe {
            if (broadcastCanBeForgotten(runtime.broadcastStore.get(id)?.phase)) onChange()
        }
    return {
        offJob()
        offWallet()
    }
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
