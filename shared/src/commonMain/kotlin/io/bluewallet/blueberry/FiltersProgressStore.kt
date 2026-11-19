package io.bluewallet.blueberry

import io.bluewallet.blueberry.bus.Event
import io.bluewallet.blueberry.bus.MessageBus
import io.bluewallet.blueberry.headers.nowMillis
import io.bluewallet.blueberry.storage.Database
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.math.min

data class FiltersProgress(
    val downloaded: Int = 0,
    val total: Int = 0,
    val at: Long? = null,
    val etaMs: Long? = null,
    val percent: Int = 100,
)

interface FiltersProgressStore {
    fun get(): FiltersProgress

    fun applyEvent(
        at: Long,
        downloaded: Int,
        total: Int,
    )

    fun subscribe(listener: () -> Unit): () -> Unit
}

private data class FiltersStoreState(
    val progress: FiltersProgress = FiltersProgress(),
    val speed: ProgressSpeed = ProgressSpeed(),
)

@OptIn(ExperimentalAtomicApi::class)
private class FiltersProgressStoreImpl : FiltersProgressStore {
    private val state = AtomicReference(FiltersStoreState())
    private val listeners = AtomicReference<List<() -> Unit>>(emptyList())

    private fun emitChange() {
        for (listener in listeners.load()) listener()
    }

    override fun get() = state.load().progress

    override fun applyEvent(
        at: Long,
        downloaded: Int,
        total: Int,
    ) {
        while (true) {
            val cur = state.load()
            val prev = cur.progress
            val isDone = isFinished(downloaded, total)
            val restarted = isFinished(prev.downloaded, prev.total) && !isDone
            val nextSpeed = cur.speed.next(prev.downloaded, downloaded, at, restarted)
            val nextPercent = progressPercent(downloaded, total)
            val nextEta = if (isDone) 0L else nextSpeed.etaMs(downloaded, total)
            if (
                prev.downloaded == downloaded &&
                prev.total == total &&
                prev.at == at &&
                prev.etaMs == nextEta &&
                prev.percent == nextPercent
            ) {
                return
            }
            val next =
                FiltersStoreState(
                    progress =
                        FiltersProgress(
                            downloaded = downloaded,
                            total = total,
                            at = at,
                            etaMs = nextEta,
                            percent = nextPercent,
                        ),
                    speed = nextSpeed,
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

fun createFiltersProgressStore(): FiltersProgressStore = FiltersProgressStoreImpl()

private fun sessionOrDurableTotal(
    incoming: Int?,
    previous: Int,
    downloaded: Int,
): Int {
    if (incoming != null && incoming > 0) return incoming
    if (previous > 0) return previous
    return downloaded
}

fun hydrateFilters(
    db: Database,
    store: FiltersProgressStore,
    rangeTotal: Int? = null,
    at: Long = nowMillis(),
) {
    val stored = db.filters.count()
    val total = sessionOrDurableTotal(rangeTotal, store.get().total, stored)
    val downloaded = if (total > 0) min(stored, total) else stored
    store.applyEvent(at, downloaded, total)
}

fun bindFilterProgressEvents(
    bus: MessageBus,
    db: Database,
    store: FiltersProgressStore,
): () -> Unit {
    val a = bus.on(Event.FiltersProgress) { hydrateFilters(db, store, it.total, it.at) }
    val b = bus.on(Event.BlocksProgress) { hydrateFilters(db, store, null, it.at) }
    return {
        a()
        b()
    }
}
