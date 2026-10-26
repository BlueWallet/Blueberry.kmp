package io.bluewallet.blueberry

import io.bluewallet.blueberry.bus.BroadcastRequestPayload
import io.bluewallet.blueberry.storage.Database
import io.bluewallet.blueberry.wallet.isSignedTxHex

const val RESEND_NOT_SIGNED = "This payment isn't signed here, so it can only be published from the device that signs it."

/** The latest broadcast job for stored payment [txid], running or finished. */
fun storedSendSession(
    store: BroadcastStore,
    txid: String,
): BroadcastSession? = store.sessions().lastOrNull { it.receipt?.txid == txid }

/** A stored, unconfirmed payment this device signed, so its transaction can be given to nodes again. */
fun storedSendCanGoAgain(
    db: Database,
    txid: String,
): Boolean = storedSignedHex(db, txid) != null

private fun storedSignedHex(
    db: Database,
    txid: String,
): String? {
    if (!unconfirmedSendOpen(db, txid)) return null
    val hex = db.sends.get(txid)?.txHex ?: db.privateSends.get(txid)?.txHex
    return hex?.takeIf(::isSignedTxHex)
}

/**
 * Gives a stored unconfirmed payment to nodes again, from the transaction rather than the closed send screen.
 * The job starts handed off: earlier nodes may hold it, so nothing this run hears makes it unsent.
 * Null when it started or is already running; otherwise why it did not.
 */
fun sendStoredAgain(
    runtime: PeersRuntime,
    db: Database,
    txid: String,
    receipt: BroadcastReceipt,
): String? {
    val store = runtime.broadcastStore
    val existing = storedSendSession(store, txid)
    val hex = storedSignedHex(db, txid)
    val wallet = runtime.walletTxsStore.get()
    val utxoKeys = wallet.utxos.map { it.key }.toSet()
    val blocked =
        when {
            existing != null && broadcastJobInFlight(existing.snapshot.phase) -> return null
            hex == null -> RESEND_NOT_SIGNED
            else -> retryRefusal(hex, utxoKeys, wallet.utxosReady, runtime.reservedOutpoints(txid))
        }
    if (blocked == null && hex != null) {
        val tried = existing?.snapshot?.triedPeers ?: emptySet()
        existing?.snapshot?.id?.let { store.dismiss(it) }
        val id = nextUiBroadcastId()
        store.start(id, hex, tried, existing?.receipt ?: receipt, heldBefore = true)
        requestBroadcast(runtime, BroadcastRequestPayload(id, hex, tried))
    }
    return blocked
}
