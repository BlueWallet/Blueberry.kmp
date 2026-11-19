package io.bluewallet.blueberry

import io.bluewallet.blueberry.bus.Event
import io.bluewallet.blueberry.bus.MessageBus
import io.bluewallet.blueberry.headers.nowMillis
import io.bluewallet.blueberry.storage.Database
import io.bluewallet.headers.decodeBlockHeader
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.math.max
import kotlin.math.min

data class HeadersProgress(
    val downloaded: Int = 0,
    val total: Int = 0,
    val height: Int = 0,
    val at: Long? = null,
    val etaMs: Long? = null,
    val percent: Int = 0,
    val tipTimeS: Long? = null,
)

interface HeadersProgressStore {
    fun get(): HeadersProgress

    fun applyEvent(
        at: Long,
        downloaded: Int,
        total: Int,
        height: Int,
        tipTimeS: Long? = null,
    )

    fun subscribe(listener: () -> Unit): () -> Unit
}

private data class HeadersStoreState(
    val progress: HeadersProgress = HeadersProgress(),
    val speed: ProgressSpeed = ProgressSpeed(),
)

@OptIn(ExperimentalAtomicApi::class)
private class HeadersProgressStoreImpl : HeadersProgressStore {
    private val state = AtomicReference(HeadersStoreState())
    private val listeners = AtomicReference<List<() -> Unit>>(emptyList())

    private fun emitChange() {
        for (listener in listeners.load()) listener()
    }

    override fun get() = state.load().progress

    override fun applyEvent(
        at: Long,
        downloaded: Int,
        total: Int,
        height: Int,
        tipTimeS: Long?,
    ) {
        val nextHeight = max(0, height)
        while (true) {
            val cur = state.load()
            val prev = cur.progress
            val isDone = isFinished(downloaded, total)
            val restarted = isFinished(prev.downloaded, prev.total) && !isDone
            val nextSpeed = cur.speed.next(prev.downloaded, downloaded, at, restarted)
            val nextPercent =
                if (total == 0) 0 else min(100, (100 * downloaded) / total)
            val nextEta = if (isDone) 0L else nextSpeed.etaMs(downloaded, total)
            if (
                prev.downloaded == downloaded &&
                prev.total == total &&
                prev.height == nextHeight &&
                prev.at == at &&
                prev.etaMs == nextEta &&
                prev.percent == nextPercent &&
                prev.tipTimeS == tipTimeS
            ) {
                return
            }
            val next =
                HeadersStoreState(
                    progress =
                        HeadersProgress(
                            downloaded = downloaded,
                            total = total,
                            height = nextHeight,
                            at = at,
                            etaMs = nextEta,
                            percent = nextPercent,
                            tipTimeS = tipTimeS,
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

fun createHeadersProgressStore(): HeadersProgressStore = HeadersProgressStoreImpl()

private fun sessionOrDurableTotal(
    incoming: Int?,
    previous: Int,
    downloaded: Int,
): Int {
    if (incoming != null && incoming > 0) return incoming
    if (previous > 0) return previous
    return downloaded
}

fun hydrateHeaders(
    db: Database,
    store: HeadersProgressStore,
    peerTotal: Int? = null,
    at: Long = nowMillis(),
) {
    val tip = db.headers.tip() ?: return
    val minH = db.headers.minHeight() ?: return
    val downloaded = max(0, tip.height - minH)
    val total = sessionOrDurableTotal(peerTotal, store.get().total, downloaded)
    val tipTimeS =
        try {
            decodeBlockHeader(tip.header).timestamp
        } catch (_: Throwable) {
            null
        }
    store.applyEvent(at, downloaded, total, tip.height, tipTimeS)
}

fun bindHeaderProgressEvents(
    bus: MessageBus,
    db: Database,
    store: HeadersProgressStore,
): () -> Unit = bus.on(Event.HeadersProgress) { hydrateHeaders(db, store, it.total, it.at) }
