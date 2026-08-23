package io.bluewallet.blueberry

import io.bluewallet.blueberry.bus.Event
import io.bluewallet.blueberry.bus.MessageBus
import io.bluewallet.blueberry.headers.nowMillis
import io.bluewallet.blueberry.storage.Database
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.math.round

data class MatchingProgress(
    val scanned: Int = 0,
    val total: Int = 0,
    val at: Long? = null,
    val etaMs: Long? = null,
    val percent: Int = 100,
)

interface MatchingProgressStore {
    fun get(): MatchingProgress
    fun applyEvent(at: Long, scanned: Int, total: Int)
    fun subscribe(listener: () -> Unit): () -> Unit
}

private data class MatchingStoreState(
    val scanned: Int = 0,
    val total: Int = 0,
    val at: Long? = null,
    val originAt: Long? = null,
    val originScanned: Int? = null,
    val seeded: Boolean = false,
    val progress: MatchingProgress = MatchingProgress(),
)

/** ETA from the first real advance — ignore the TUI seed sample. */
@OptIn(ExperimentalAtomicApi::class)
private class MatchingProgressStoreImpl : MatchingProgressStore {
    private val state = AtomicReference(MatchingStoreState())
    private val listeners = AtomicReference<List<() -> Unit>>(emptyList())

    private fun emitChange() {
        for (listener in listeners.load()) listener()
    }

    private fun etaFor(
        originAt: Long,
        originScanned: Int,
        nextScanned: Int,
        nextTotal: Int,
        nextAt: Long,
    ): Long? {
        if (nextTotal > 0 && nextScanned >= nextTotal) return 0
        if (nextScanned <= originScanned) return null
        val dt = nextAt - originAt
        if (dt <= 0) return null
        val rate = (nextScanned - originScanned).toDouble() / dt
        if (rate <= 0) return null
        return round((nextTotal - nextScanned).toDouble() / rate).toLong()
    }

    override fun get() = state.load().progress

    override fun applyEvent(at: Long, scanned: Int, total: Int) {
        while (true) {
            val cur = state.load()
            val nextPercent = progressPercent(scanned, total)

            val wasDone = cur.total > 0 && cur.scanned >= cur.total
            val isDone = total > 0 && scanned >= total

            var nextOriginAt = cur.originAt
            var nextOriginScanned = cur.originScanned
            var nextSeeded = cur.seeded
            val nextEta: Long?
            if (isDone) {
                nextEta = 0
                nextOriginAt = null
                nextOriginScanned = null
            } else if (!cur.seeded) {
                nextEta = null
                nextSeeded = true
            } else {
                if (scanned < cur.scanned || wasDone) {
                    nextOriginAt = null
                    nextOriginScanned = null
                }
                if (nextOriginAt == null) {
                    if (scanned > cur.scanned) {
                        nextOriginAt = at
                        nextOriginScanned = scanned
                    }
                    nextEta = null
                } else {
                    nextEta = etaFor(
                        nextOriginAt,
                        nextOriginScanned!!,
                        scanned,
                        total,
                        at,
                    )
                }
            }

            if (
                cur.scanned == scanned &&
                cur.total == total &&
                cur.at == at &&
                cur.progress.etaMs == nextEta &&
                cur.progress.percent == nextPercent
            ) {
                return
            }

            val next = MatchingStoreState(
                scanned = scanned,
                total = total,
                at = at,
                originAt = nextOriginAt,
                originScanned = nextOriginScanned,
                seeded = nextSeeded,
                progress = MatchingProgress(
                    scanned = scanned,
                    total = total,
                    at = at,
                    etaMs = nextEta,
                    percent = nextPercent,
                ),
            )
            if (state.compareAndSet(cur, next)) {
                emitChange()
                return
            }
        }
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

fun createMatchingProgressStore(): MatchingProgressStore = MatchingProgressStoreImpl()

fun hydrateMatching(
    db: Database,
    store: MatchingProgressStore,
    at: Long = nowMillis(),
) {
    store.applyEvent(at, db.filters.countScanned(), db.filters.count())
}

fun bindMatchingProgressEvents(
    bus: MessageBus,
    db: Database,
    store: MatchingProgressStore,
): () -> Unit {
    val a = bus.on(Event.MatchingProgress) { hydrateMatching(db, store, it.at) }
    val b = bus.on(Event.BlocksProgress) { hydrateMatching(db, store, it.at) }
    return {
        a()
        b()
    }
}
