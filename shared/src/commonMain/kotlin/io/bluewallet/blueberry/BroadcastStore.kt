package io.bluewallet.blueberry

import io.bluewallet.blueberry.bus.BroadcastDonePayload
import io.bluewallet.blueberry.bus.BroadcastProgressPayload
import io.bluewallet.blueberry.bus.BroadcastRequestPayload
import io.bluewallet.blueberry.bus.Event
import io.bluewallet.blueberry.bus.MessageBus
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

data class BroadcastSnapshot(
    val id: String? = null,
    val txHex: String? = null,
    val phase: String? = null,
    val attempt: Int? = null,
    val maxAttempts: Int? = null,
    val peer: String? = null,
    val detail: String? = null,
    val error: String? = null,
    val percent: Int? = null,
    val stage: String? = null,
    val triedPeers: Set<String> = emptySet(),
    /**
     * A node may hold the transaction from this run, so it can no longer be stopped. Cleared when every
     * node that got it refused it.
     */
    val handedOff: Boolean = false,
    /** False for a success where nodes were given the transaction but none confirmed taking it. */
    val acknowledged: Boolean = true,
)

/** What the transaction list and the broadcast screen need after Send has closed. */
data class BroadcastReceipt(
    val txid: String,
    val destination: String,
    val label: String,
    val paidSats: Long,
    val feeSats: Long,
)

data class BroadcastSession(
    val snapshot: BroadcastSnapshot,
    val receipt: BroadcastReceipt? = null,
)

interface BroadcastStore {
    fun get(id: String): BroadcastSnapshot?

    fun sessions(): List<BroadcastSession>

    fun start(
        id: String,
        txHex: String,
        triedPeers: Set<String> = emptySet(),
        receipt: BroadcastReceipt? = null,
    )

    fun applyProgress(payload: BroadcastProgressPayload)

    fun applyDone(payload: BroadcastDonePayload)

    fun markHandedOff(id: String)

    fun dismiss(id: String)

    fun reset()

    fun subscribe(listener: () -> Unit): () -> Unit
}

@OptIn(ExperimentalAtomicApi::class)
private class BroadcastStoreImpl : BroadcastStore {
    private val state = AtomicReference<Map<String, BroadcastSession>>(emptyMap())
    private val listeners = AtomicReference<List<() -> Unit>>(emptyList())

    private fun update(transform: (Map<String, BroadcastSession>) -> Map<String, BroadcastSession>) {
        updateSessions(state, listeners, transform)
    }

    override fun get(id: String): BroadcastSnapshot? = state.load()[id]?.snapshot

    override fun sessions(): List<BroadcastSession> = state.load().values.toList()

    override fun start(
        id: String,
        txHex: String,
        triedPeers: Set<String>,
        receipt: BroadcastReceipt?,
    ) {
        val session =
            BroadcastSession(
                snapshot = BroadcastSnapshot(id = id, txHex = txHex, phase = "starting", triedPeers = triedPeers),
                receipt = receipt,
            )
        update { it + (id to session) }
    }

    override fun applyProgress(payload: BroadcastProgressPayload) {
        update { map ->
            val cur = map[payload.id] ?: return@update map
            val snap =
                cur.snapshot.copy(
                    phase = payload.phase.wireName,
                    attempt = payload.attempt,
                    maxAttempts = payload.maxAttempts,
                    peer = payload.peer,
                    detail = payload.detail,
                    percent = payload.percent,
                    stage = payload.stage,
                    triedPeers = rememberTriedPeers(cur.snapshot.triedPeers, payload.peer),
                )
            map + (payload.id to cur.copy(snapshot = snap))
        }
    }

    override fun applyDone(payload: BroadcastDonePayload) {
        update { map ->
            val cur = map[payload.id] ?: return@update map
            val snap =
                when (payload) {
                    is BroadcastDonePayload.Ok ->
                        cur.snapshot.copy(
                            phase = "success",
                            peer = payload.peer,
                            error = null,
                            acknowledged = payload.acknowledged,
                            triedPeers = rememberTriedPeers(cur.snapshot.triedPeers, payload.peer),
                        )
                    is BroadcastDonePayload.Error ->
                        when {
                            payload.refused -> cur.snapshot.copy(phase = "error", error = payload.error, handedOff = false)
                            cur.snapshot.handedOff ->
                                cur.snapshot.copy(phase = "success", error = null, detail = payload.error, acknowledged = false)
                            else -> cur.snapshot.copy(phase = "error", error = payload.error)
                        }
                }
            map + (payload.id to cur.copy(snapshot = snap))
        }
    }

    override fun markHandedOff(id: String) {
        update { map ->
            val cur = map[id] ?: return@update map
            if (cur.snapshot.handedOff) return@update map
            map + (id to cur.copy(snapshot = cur.snapshot.copy(handedOff = true)))
        }
    }

    override fun dismiss(id: String) {
        update { it - id }
    }

    override fun reset() {
        state.store(emptyMap())
        emitSessions(listeners)
    }

    override fun subscribe(listener: () -> Unit): () -> Unit {
        while (true) {
            val cur = listeners.load()
            if (listener in cur) break
            if (listeners.compareAndSet(cur, cur + listener)) break
        }
        return {
            while (true) {
                val cur = listeners.load()
                val next = cur - listener
                if (next === cur || listeners.compareAndSet(cur, next)) break
            }
        }
    }
}

@OptIn(ExperimentalAtomicApi::class)
private fun emitSessions(listeners: AtomicReference<List<() -> Unit>>) {
    for (listener in listeners.load()) listener()
}

@OptIn(ExperimentalAtomicApi::class)
private fun updateSessions(
    state: AtomicReference<Map<String, BroadcastSession>>,
    listeners: AtomicReference<List<() -> Unit>>,
    transform: (Map<String, BroadcastSession>) -> Map<String, BroadcastSession>,
) {
    while (true) {
        val cur = state.load()
        val next = transform(cur)
        if (next === cur || state.compareAndSet(cur, next)) {
            if (next !== cur) emitSessions(listeners)
            return
        }
    }
}

fun createBroadcastStore(): BroadcastStore = BroadcastStoreImpl()

fun bindBroadcastEvents(
    bus: MessageBus,
    store: BroadcastStore,
    onHandedOff: (String) -> Unit = {},
    onDone: (String) -> Unit = {},
): () -> Unit {
    val a = bus.on(Event.BroadcastProgress) { store.applyProgress(it) }
    val b =
        bus.on(Event.BroadcastDone) {
            store.applyDone(it)
            onDone(it.id)
        }
    val c =
        bus.on(Event.BroadcastHandedOff) {
            store.markHandedOff(it.id)
            onHandedOff(it.id)
        }
    return {
        a()
        b()
        c()
    }
}

fun rememberTriedPeers(
    tried: Set<String>,
    peer: String?,
): Set<String> {
    if (peer.isNullOrBlank()) return tried
    return tried +
        peer.split(',').map { it.trim() }.filter { it.isNotEmpty() }
}

fun prepareUiBroadcast(
    store: BroadcastStore,
    txHex: String,
    receipt: BroadcastReceipt? = null,
): String? {
    val same = store.sessions().filter { it.snapshot.txHex == txHex }
    val blocked = same.any { broadcastJobInFlight(it.snapshot.phase) || it.snapshot.phase == "success" }
    if (blocked) return null
    val previous = same.lastOrNull { it.snapshot.phase == "error" }
    val tried = previous?.snapshot?.triedPeers ?: emptySet()
    val kept = receipt ?: previous?.receipt
    same.filter { it.snapshot.phase == "error" }.forEach { session ->
        session.snapshot.id?.let { store.dismiss(it) }
    }
    val id = nextUiBroadcastId()
    store.start(id, txHex, tried, kept)
    return id
}

/**
 * Restarts a finished job under the same id so the open screen and list row stay put. A failure restarts,
 * and so does a send no node confirmed; a confirmed send is done. A re-send starts handed off, since a node
 * may already hold it, so it cannot be stopped and a failure still reads as sent.
 */
fun rebroadcast(
    store: BroadcastStore,
    id: String,
): BroadcastRequestPayload? {
    val session = store.sessions().find { it.snapshot.id == id }
    val txHex = session?.snapshot?.txHex
    val phase = session?.snapshot?.phase
    val missing = session == null || txHex == null
    val settled = broadcastJobInFlight(phase) || (phase == "success" && session?.snapshot?.acknowledged != false)
    if (missing || settled) return null
    store.dismiss(id)
    store.start(id, txHex, session.snapshot.triedPeers, session.receipt)
    if (phase == "success") store.markHandedOff(id)
    return BroadcastRequestPayload(id, txHex, session.snapshot.triedPeers)
}

fun enqueueBroadcast(
    store: BroadcastStore,
    txHex: String,
    receipt: BroadcastReceipt? = null,
): BroadcastRequestPayload? {
    val id = prepareUiBroadcast(store, txHex, receipt) ?: return null
    return BroadcastRequestPayload(id, txHex, store.get(id)?.triedPeers ?: emptySet())
}

fun enqueueArmedBroadcast(
    store: BroadcastStore,
    txHex: String,
    receipt: BroadcastReceipt? = null,
    arm: (String) -> Unit,
): BroadcastRequestPayload? {
    val req = enqueueBroadcast(store, txHex, receipt) ?: return null
    arm(req.id)
    return req
}

private var nextBroadcastId = 0

fun nextUiBroadcastId(): String = "ui-${++nextBroadcastId}"
