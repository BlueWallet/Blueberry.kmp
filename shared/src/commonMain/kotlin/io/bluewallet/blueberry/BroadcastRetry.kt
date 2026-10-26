package io.bluewallet.blueberry

import io.bluewallet.blueberry.broadcast.BROADCAST_CANCELLED
import io.bluewallet.blueberry.broadcast.decodeBroadcastTx
import io.bluewallet.blueberry.bus.BroadcastDonePayload
import io.bluewallet.blueberry.bus.BroadcastRequestPayload
import io.bluewallet.blueberry.bus.BroadcastStopPayload
import io.bluewallet.blueberry.bus.Event
import io.bluewallet.blueberry.wallet.outpointKey
import io.bluewallet.blueberry.wallet.prevoutTxidDisplay

/**
 * Drops a job a node confirmed, once its screen closes. A send no node confirmed stays, so its transaction
 * can still show that status and go again.
 */
fun dismissConfirmedBroadcast(
    store: BroadcastStore,
    id: String,
) {
    val snap = store.get(id) ?: return
    if (snap.phase == "success" && snap.acknowledged) store.dismiss(id)
}

/** Any finished failure can leave the list. A live or sent job stays. */
fun broadcastCanBeForgotten(phase: String?): Boolean = phase == "error"

fun liveBroadcastIds(store: BroadcastStore): Set<String> =
    store
        .sessions()
        .mapNotNull { session ->
            val id = session.snapshot.id
            if (id != null && broadcastJobInFlight(session.snapshot.phase)) id else null
        }.toSet()

const val BROADCAST_NOT_READY = "The Bitcoin network is still starting. Retry in a moment."

/** Hands [req] to the broadcast module. With no module listening, the job fails so its coins come free and Retry shows. */
fun requestBroadcast(
    runtime: PeersRuntime,
    req: BroadcastRequestPayload,
) {
    if (runtime.bus.emit(Event.BroadcastRequest, req)) return
    runtime.broadcastStore.applyDone(BroadcastDonePayload.Error(req.id, BROADCAST_NOT_READY))
}

/** Stop is offered only while the job runs and no node has been given the transaction. */
fun broadcastCanBeStopped(snap: BroadcastSnapshot?): Boolean = snap != null && broadcastJobInFlight(snap.phase) && !snap.handedOff

/** Asks the module to stop [id]. A hand-off that wins the race still finishes as sent. */
fun stopBroadcast(
    runtime: PeersRuntime,
    id: String,
) {
    if (!broadcastCanBeStopped(runtime.broadcastStore.get(id))) return
    if (runtime.bus.emit(Event.BroadcastStop, BroadcastStopPayload(id))) return
    runtime.broadcastStore.applyDone(BroadcastDonePayload.Error(id, BROADCAST_CANCELLED))
}

const val RETRY_COINS_GONE = "Those coins are no longer in the wallet. This send cannot be retried."
const val RETRY_COINS_HELD = "Those coins are in another payment that can still confirm. This send cannot be retried."
const val RETRY_WALLET_LOADING = "The wallet is still loading. Wait, then try again."

/** Why Retry must not publish this transaction again, or null when its inputs are still free. */
fun retryRefusal(
    txHex: String?,
    utxoKeys: Set<String>,
    utxosReady: Boolean,
    reserved: Set<String>,
): String? {
    val inputs = if (utxosReady) txHex?.let(::txInputKeys) else null
    return when {
        !utxosReady -> RETRY_WALLET_LOADING
        inputs == null || inputs.any { it !in utxoKeys } -> RETRY_COINS_GONE
        inputs.any { it in reserved } -> RETRY_COINS_HELD
        else -> null
    }
}

/** [retryRefusal] for job [id]. Its own hold, stored once a node had it, is not another payment's. */
fun retryRefusalFor(
    runtime: PeersRuntime,
    id: String,
    wallet: WalletTxsSnapshot,
): String? {
    val session = runtime.broadcastStore.sessions().find { it.snapshot.id == id }
    return retryRefusal(
        session?.snapshot?.txHex,
        wallet.utxos.map { it.key }.toSet(),
        wallet.utxosReady,
        runtime.reservedOutpoints(setOfNotNull(session?.receipt?.txid)),
    )
}

private fun txInputKeys(txHex: String): Set<String>? =
    runCatching {
        decodeBroadcastTx(txHex)
            .inputs
            .map { input ->
                outpointKey(prevoutTxidDisplay(input.previousOutput.hash), input.previousOutput.index.toInt())
            }.toSet()
    }.getOrNull()
