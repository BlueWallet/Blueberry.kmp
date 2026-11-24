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
 * Once one of our runs gave nodes it, the job starts handed off: those nodes may hold it, so nothing this run
 * hears makes it unsent. Otherwise it is a first run, and one that reaches no node is not sent.
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
            else -> retryRefusal(hex, utxoKeys, wallet.utxosReady, runtime.reservedOutpoints(setOf(txid)))
        }
    if (blocked == null && hex != null) {
        val tried = existing?.snapshot?.triedPeers ?: emptySet()
        existing?.snapshot?.id?.let { store.dismiss(it) }
        val id = nextUiBroadcastId()
        store.start(id, hex, tried, existing?.receipt ?: receipt, priorRun(db, txid, existing))
        requestBroadcast(runtime, BroadcastRequestPayload(id, hex, tried))
    }
    return blocked
}

/**
 * Notes that job [id] went over Tor, on its stored payment, once it finished with a node holding it. Written here
 * rather than at the start, so a run that reached no node (or that every node refused) never claims Tor.
 * It is the only durable proof a node holds the payment, so a failed write is tried again before giving up.
 */
fun recordStoredSendBroadcast(
    db: Database,
    store: BroadcastStore,
    id: String,
) {
    val session = store.sessions().find { it.snapshot.id == id } ?: return
    val txid = session.receipt?.txid ?: return
    val sent = session.snapshot.phase == "success" && !session.snapshot.refusedByAll
    if (sent && unconfirmedSendOpen(db, txid)) retryWrite { db.broadcastVia.upsert(txid, BROADCAST_VIA_TOR) }
}

private const val ROUTE_WRITE_ATTEMPTS = 3

/** Runs [write], again on a throw, up to [ROUTE_WRITE_ATTEMPTS] times; the last failure is rethrown. */
private fun retryWrite(write: () -> Unit) {
    repeat(ROUTE_WRITE_ATTEMPTS - 1) {
        if (runCatching(write).isSuccess) return
    }
    write()
}

/**
 * What earlier runs of stored payment [txid] got: held only on evidence that one of ours gave nodes it, a job that
 * finished as sent or its Tor record. A signed PSBT is stored before its first run, so a stored row alone is not.
 */
private fun priorRun(
    db: Database,
    txid: String,
    existing: BroadcastSession?,
): PriorRun {
    val snap = existing?.snapshot
    val sent = snap?.phase == "success"
    return when {
        sent && snap.acknowledged -> PriorRun.Acked
        sent || db.broadcastVia.get(txid) != null -> PriorRun.Held
        else -> PriorRun.None
    }
}

/** Payment details may say nodes were given it: its Tor record, or a job of it that finished with a node holding it. */
internal fun openSendPublished(
    viaRecorded: Boolean,
    snap: BroadcastSnapshot?,
): Boolean = viaRecorded || (snap?.phase == "success" && !snap.refusedByAll)
