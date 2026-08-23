package io.bluewallet.blueberry

import io.bluewallet.blueberry.bus.Event
import io.bluewallet.blueberry.bus.MessageBus
import io.bluewallet.blueberry.headers.nowMillis
import io.bluewallet.blueberry.storage.Database
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

data class BlocksProgress(
    val downloaded: Int = 0,
    val matched: Int = 0,
    val at: Long? = null,
    val etaMs: Long? = null,
    val percent: Int = 100,
)

interface BlocksMatchedStore {
    fun get(): BlocksProgress
    fun applyEvent(at: Long, downloaded: Int, matched: Int)
    fun subscribe(listener: () -> Unit): () -> Unit
}

private const val MAX_SAMPLES = 8

private data class BlockProgressSample(val at: Long, val downloaded: Int)

private data class BlocksStoreState(
    val progress: BlocksProgress = BlocksProgress(),
    val samples: List<BlockProgressSample> = emptyList(),
)

private fun addAdvancingSample(
    samples: List<BlockProgressSample>,
    sample: BlockProgressSample,
): List<BlockProgressSample> {
    val last = samples.lastOrNull()
    if (last != null && sample.downloaded <= last.downloaded) return samples
    val next = samples + sample
    return if (next.size > MAX_SAMPLES) next.takeLast(MAX_SAMPLES) else next
}

private fun nextProgressSamples(
    samples: List<BlockProgressSample>,
    prevDownloaded: Int,
    prevMatched: Int,
    at: Long,
    downloaded: Int,
    matched: Int,
): List<BlockProgressSample> {
    val wasDone = prevDownloaded >= prevMatched
    val isDone = downloaded >= matched
    if (downloaded < prevDownloaded || (wasDone && !isDone)) {
        return listOf(BlockProgressSample(at, downloaded))
    }
    return addAdvancingSample(samples, BlockProgressSample(at, downloaded))
}

private fun estimateEtaMs(samples: List<BlockProgressSample>, matched: Int): Long? {
    if (samples.size < 2) return null
    val first = samples.first()
    val last = samples.last()
    val timeDelta = last.at - first.at
    if (timeDelta <= 0) return null
    val rate = (last.downloaded - first.downloaded).toDouble() / timeDelta
    if (matched <= last.downloaded) return 0
    if (rate <= 0) return null
    val remaining = (matched - last.downloaded).toDouble()
    return kotlin.math.round(remaining / rate).toLong()
}

@OptIn(ExperimentalAtomicApi::class)
private class BlocksMatchedStoreImpl : BlocksMatchedStore {
    private val state = AtomicReference(BlocksStoreState())
    private val listeners = AtomicReference<List<() -> Unit>>(emptyList())

    private fun emitChange() {
        for (listener in listeners.load()) listener()
    }

    override fun get() = state.load().progress

    override fun applyEvent(at: Long, downloaded: Int, matched: Int) {
        while (true) {
            val cur = state.load()
            val prev = cur.progress
            val nextSamples = nextProgressSamples(
                cur.samples,
                prev.downloaded,
                prev.matched,
                at,
                downloaded,
                matched,
            )
            val nextPercent = progressPercent(downloaded, matched)
            val nextEta =
                if (downloaded >= matched) 0L
                else estimateEtaMs(nextSamples, matched)
            if (
                prev.downloaded == downloaded &&
                prev.matched == matched &&
                prev.at == at &&
                prev.etaMs == nextEta &&
                prev.percent == nextPercent
            ) {
                return
            }
            val next = BlocksStoreState(
                progress = BlocksProgress(
                    downloaded = downloaded,
                    matched = matched,
                    at = at,
                    etaMs = nextEta,
                    percent = nextPercent,
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

fun createBlocksMatchedStore(): BlocksMatchedStore = BlocksMatchedStoreImpl()

fun hydrateBlocks(
    db: Database,
    store: BlocksMatchedStore,
    at: Long = nowMillis(),
) {
    store.applyEvent(at, db.blocks.count(), db.matchedBlocks.count())
}

fun bindBlocksProgressEvents(
    bus: MessageBus,
    db: Database,
    store: BlocksMatchedStore,
): () -> Unit {
    val a = bus.on(Event.BlocksProgress) { hydrateBlocks(db, store, it.at) }
    val b = bus.on(Event.FiltersMatch) { hydrateBlocks(db, store) }
    return {
        a()
        b()
    }
}
