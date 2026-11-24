package io.bluewallet.blueberry

import io.bluewallet.blueberry.storage.Database
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.update

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
    storedSendHeldTxid(store, id)?.let { writeStoredSendTorNote(db, it) }
}

/** The stored payment job [id] gave nodes, once it finished with a node holding it; null otherwise. */
internal fun storedSendHeldTxid(
    store: BroadcastStore,
    id: String,
): String? {
    val session = store.sessions().find { it.snapshot.id == id } ?: return null
    val sent = session.snapshot.phase == "success" && !session.snapshot.refusedByAll
    return session.receipt?.txid?.takeIf { sent }
}

/** Writes the Tor note of stored payment [txid] while it is still unconfirmed; the last failed attempt rethrows. */
internal fun writeStoredSendTorNote(
    db: Database,
    txid: String,
) {
    if (unconfirmedSendOpen(db, txid)) retryWrite { db.broadcastVia.upsert(txid, BROADCAST_VIA_TOR) }
}

/**
 * Writes the Tor note of job [id] once it finished with a node holding it. A note whose write still fails stays
 * due and is written again on every wallet refresh until it lands.
 */
internal fun PeersRuntime.noteStoredSendBroadcast(id: String) {
    storedSendHeldTxid(broadcastStore, id)?.let(dueTorNotes::add)
}

/**
 * Tor notes of stored payments a node holds that haven't been written yet. The note is the only proof that
 * survives a restart that a node holds the payment, so one whose write still fails stays due until a later write lands.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class DueTorNotes(
    private val db: Database,
) {
    private val due = AtomicReference<Set<String>>(emptySet())

    /** Makes [txid]'s note due and writes every due note. */
    fun add(txid: String) {
        due.update { it + txid }
        write()
    }

    /** Writes every due note; a note whose payment is no longer open is dropped as written. */
    fun write() {
        for (txid in due.load()) {
            if (runCatching { writeStoredSendTorNote(db, txid) }.isSuccess) due.update { it - txid }
        }
    }
}

private const val ROUTE_WRITE_ATTEMPTS = 3

/** Runs [write], again on a throw, up to [ROUTE_WRITE_ATTEMPTS] times; the last failure is rethrown. */
private fun retryWrite(write: () -> Unit) {
    repeat(ROUTE_WRITE_ATTEMPTS - 1) {
        if (runCatching(write).isSuccess) return
    }
    write()
}
