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

private const val MAX_SAMPLES = 8

private data class ProgressSample(
    val at: Long,
    val downloaded: Int,
)

private data class HeadersStoreState(
    val progress: HeadersProgress = HeadersProgress(),
    val samples: List<ProgressSample> = emptyList(),
)

private fun addAdvancingSample(
    samples: List<ProgressSample>,
    sample: ProgressSample,
): List<ProgressSample> {
    val last = samples.lastOrNull()
    if (last != null && sample.downloaded <= last.downloaded) return samples
    val next = samples + sample
    return if (next.size > MAX_SAMPLES) next.takeLast(MAX_SAMPLES) else next
}

private fun nextProgressSamples(
    samples: List<ProgressSample>,
    prevDownloaded: Int,
    prevTotal: Int,
    at: Long,
    downloaded: Int,
    total: Int,
): List<ProgressSample> {
    val wasDone = prevTotal > 0 && prevDownloaded >= prevTotal
    val isDone = total > 0 && downloaded >= total
    if (downloaded < prevDownloaded || (wasDone && !isDone)) {
        return listOf(ProgressSample(at, downloaded))
    }
    return addAdvancingSample(samples, ProgressSample(at, downloaded))
}

private fun estimateEtaMs(
    samples: List<ProgressSample>,
    total: Int,
): Long? {
    if (samples.size < 2) return null
    val first = samples.first()
    val last = samples.last()
    val timeDelta = last.at - first.at
    if (timeDelta <= 0) return null
    val rate = (last.downloaded - first.downloaded).toDouble() / timeDelta
    if (total <= last.downloaded) return 0
    if (rate <= 0) return null
    val remaining = (total - last.downloaded).toDouble()
    return kotlin.math.round(remaining / rate).toLong()
}

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
            val nextSamples =
                nextProgressSamples(
                    cur.samples,
                    prev.downloaded,
                    prev.total,
                    at,
                    downloaded,
                    total,
                )
            val nextPercent =
                if (total == 0) 0 else min(100, (100 * downloaded) / total)
            val nextEta =
                if (total > 0 && downloaded >= total) {
                    0L
                } else {
                    estimateEtaMs(nextSamples, total)
                }
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
                    samples = nextSamples,
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
