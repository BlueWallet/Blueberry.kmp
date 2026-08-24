package io.bluewallet.blueberry

import io.bluewallet.blueberry.bus.BroadcastDonePayload
import io.bluewallet.blueberry.bus.BroadcastProgressPayload
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
)

interface BroadcastStore {
    fun get(): BroadcastSnapshot
    fun start(id: String, txHex: String)
    fun applyProgress(payload: BroadcastProgressPayload)
    fun applyDone(payload: BroadcastDonePayload)
    fun reset()
    fun subscribe(listener: () -> Unit): () -> Unit
}

@OptIn(ExperimentalAtomicApi::class)
private class BroadcastStoreImpl : BroadcastStore {
    private val state = AtomicReference(BroadcastSnapshot())
    private val listeners = AtomicReference<List<() -> Unit>>(emptyList())

    private fun emit() {
        for (listener in listeners.load()) listener()
    }

    override fun get(): BroadcastSnapshot = state.load()

    override fun start(id: String, txHex: String) {
        state.store(BroadcastSnapshot(id = id, txHex = txHex, phase = "starting"))
        emit()
    }

    override fun applyProgress(payload: BroadcastProgressPayload) {
        val cur = state.load()
        if (cur.id != payload.id) return
        state.store(
            cur.copy(
                id = payload.id,
                phase = payload.phase.wireName,
                attempt = payload.attempt,
                maxAttempts = payload.maxAttempts,
                peer = payload.peer,
                detail = payload.detail,
            ),
        )
        emit()
    }

    override fun applyDone(payload: BroadcastDonePayload) {
        val cur = state.load()
        if (cur.id != payload.id) return
        state.store(
            when (payload) {
                is BroadcastDonePayload.Ok -> cur.copy(
                    id = payload.id,
                    phase = "success",
                    peer = payload.peer,
                    error = null,
                )
                is BroadcastDonePayload.Error -> cur.copy(
                    id = payload.id,
                    phase = "error",
                    error = payload.error,
                )
            },
        )
        emit()
    }

    override fun reset() {
        state.store(BroadcastSnapshot())
        emit()
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

fun createBroadcastStore(): BroadcastStore = BroadcastStoreImpl()

fun bindBroadcastEvents(bus: MessageBus, store: BroadcastStore): () -> Unit {
    val a = bus.on(Event.BroadcastProgress) { store.applyProgress(it) }
    val b = bus.on(Event.BroadcastDone) { store.applyDone(it) }
    return {
        a()
        b()
    }
}

fun broadcastJobInFlight(phase: String?): Boolean =
    phase != null && phase != "success" && phase != "error"

sealed class BroadcastEscape {
    data object Ignore : BroadcastEscape()
    data object Cancel : BroadcastEscape()
    data object ForceClose : BroadcastEscape()
}

fun inFlightBroadcastEscape(
    phase: String?,
    id: String?,
    cancelArmedForId: String?,
): BroadcastEscape {
    if (id == null || !broadcastJobInFlight(phase)) return BroadcastEscape.Ignore
    return if (cancelArmedForId == id) BroadcastEscape.ForceClose else BroadcastEscape.Cancel
}

fun prepareUiBroadcast(store: BroadcastStore, txHex: String): String? {
    val snap = store.get()
    if (broadcastJobInFlight(snap.phase)) return null
    if (snap.phase == "success") {
        if (snap.txHex == txHex) return null
        store.reset()
    } else if (snap.phase == "error") {
        store.reset()
    }
    val id = nextUiBroadcastId()
    store.start(id, txHex)
    return id
}

private var nextBroadcastId = 0

fun nextUiBroadcastId(): String = "ui-${++nextBroadcastId}"
