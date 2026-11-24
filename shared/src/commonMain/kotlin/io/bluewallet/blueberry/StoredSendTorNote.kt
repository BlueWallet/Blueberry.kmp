package io.bluewallet.blueberry

import io.bluewallet.blueberry.storage.Database
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile

/**
 * Notes that job [id] went over Tor, on its stored payment, once it finished with a node holding it. Never at the
 * start, so a run that reached no node never claims Tor. It is the only durable proof a node holds the payment, so a
 * failed write is tried again before giving up.
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
 * The runtime's hook for a node being given job [id]'s transaction. A stored payment (a signed PSBT) is not armed,
 * so its Tor note is written here, as a hot-wallet send's is: the app can die while a node holds the transaction.
 */
internal fun PeersRuntime.broadcastHandedOff(id: String) {
    try {
        persistArmedBroadcast(id, handedOff = true)
    } finally {
        noteHandedOff(id)
    }
}

/** Makes job [id]'s Tor note due once a node was given its transaction; written while its payment is stored. */
private fun PeersRuntime.noteHandedOff(id: String) {
    val session = broadcastStore.sessions().find { it.snapshot.id == id } ?: return
    val txid = session.receipt?.txid ?: return
    if (session.snapshot.handedOff) dueTorNotes.add(txid)
}

/** The runtime's hook for job [id] finishing. The Tor note is settled even when persisting the send throws. */
internal fun PeersRuntime.broadcastDone(id: String) {
    try {
        persistArmedBroadcast(id)
    } finally {
        noteStoredSendBroadcast(id)
    }
}

/**
 * Writes the Tor note of job [id] once it finished with a node holding it. A note whose write still fails stays
 * due and is written again on every wallet refresh until it lands. A first run that ended with no node holding it
 * (every node refused, or none was reached) started with no note, so the one its hand-off wrote is deleted.
 */
internal fun PeersRuntime.noteStoredSendBroadcast(id: String) {
    val held = storedSendHeldTxid(broadcastStore, id)
    val session = broadcastStore.sessions().find { it.snapshot.id == id }
    val txid = session?.receipt?.txid
    when {
        held != null -> dueTorNotes.add(held)
        txid != null && session.snapshot.phase == "error" && !session.snapshot.heldBefore -> dueTorNotes.drop(txid)
    }
}

/**
 * Tor notes of stored payments a node holds that haven't been written yet, and notes of refused first runs that
 * haven't been deleted yet. The note is the only proof that survives a restart that a node holds the payment, so one
 * whose write still fails stays due until a later write lands, and one whose delete fails stays dropped until a
 * later delete lands. A dropped note counts as no record ([recorded]) while its row still stands, and the drop is
 * stored ([TOR_NOTE_DROPS_KEY]) before the delete, so a restart can't bring the note back. One lock covers both sets
 * and the SQL, so a refresh writing a due note can't race a drop and write the note back after it was deleted.
 * Broadcast and wallet-refresh events arrive on different threads.
 */
internal class DueTorNotes(
    private val db: Database,
) {
    private val lock = Mutex()
    private var due = emptySet<String>()

    @Volatile private var dropped = loadDrops(db)

    /** What the key-value entry holds, or null when unknown (its last write failed). */
    private var stored: Set<String>? = dropped

    /** Makes [txid]'s note due (cancelling a pending delete) and settles every pending note. */
    fun add(txid: String) =
        locked {
            due = due + txid
            dropped = dropped - txid
            saveDrops()
            settle()
        }

    /** Settles every pending note: writes the due ones, deletes the dropped ones. A failure stays pending. */
    fun write() = locked { settle() }

    /**
     * No node holds [txid] after all: its note is no longer due, and the one written is deleted while it's open.
     * The drop is stored first, so a delete that fails still holds after a restart.
     */
    fun drop(txid: String) =
        locked {
            due = due - txid
            dropped = dropped + txid
            saveDrops()
            settle()
        }

    /** [txid]'s note was dropped and its delete hasn't landed yet: whatever its row says, it is no record. */
    fun dropPending(txid: String): Boolean = txid in dropped

    /** [txid] has a Tor note no pending drop is about to delete. */
    fun recorded(txid: String): Boolean = !dropPending(txid) && db.broadcastVia.get(txid) != null

    private fun settle() {
        for (txid in due) {
            if (runCatching { writeStoredSendTorNote(db, txid) }.isSuccess) due = due - txid
        }
        for (txid in dropped) {
            if (runCatching { deleteStoredSendTorNote(db, txid) }.isSuccess) dropped = dropped - txid
        }
        saveDrops()
    }

    /** Stores the pending drops when they differ from the entry; a failed write is tried again on the next settle. */
    private fun saveDrops() {
        val drops = dropped
        if (drops == stored) return
        stored = null
        runCatching {
            if (drops.isEmpty()) db.keyValue.delete(TOR_NOTE_DROPS_KEY) else db.keyValue.set(TOR_NOTE_DROPS_KEY, drops.joinToString(","))
            stored = drops
        }
    }

    private fun locked(block: () -> Unit) = runBlocking { lock.withLock { block() } }
}

/** Key-value entry with the txids whose Tor note was dropped but not yet deleted, comma separated. */
internal const val TOR_NOTE_DROPS_KEY = "stored_send_tor_drops"

private fun loadDrops(db: Database): Set<String> =
    runCatching { db.keyValue.get(TOR_NOTE_DROPS_KEY) }
        .getOrNull()
        ?.split(',')
        ?.filter { it.isNotEmpty() }
        ?.toSet()
        .orEmpty()

/** Deletes the Tor note of stored payment [txid] while it is still unconfirmed. */
private fun deleteStoredSendTorNote(
    db: Database,
    txid: String,
) {
    if (unconfirmedSendOpen(db, txid)) db.broadcastVia.delete(txid)
}

private const val ROUTE_WRITE_ATTEMPTS = 3

/** Runs [write], again on a throw, up to [ROUTE_WRITE_ATTEMPTS] times; the last failure is rethrown. */
private fun retryWrite(write: () -> Unit) {
    repeat(ROUTE_WRITE_ATTEMPTS - 1) {
        if (runCatching(write).isSuccess) return
    }
    write()
}
