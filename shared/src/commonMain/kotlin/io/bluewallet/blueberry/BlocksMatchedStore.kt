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

    fun applyEvent(
        at: Long,
        downloaded: Int,
        matched: Int,
    )

    fun subscribe(listener: () -> Unit): () -> Unit
}

private data class BlocksStoreState(
    val progress: BlocksProgress = BlocksProgress(),
    val speed: ProgressSpeed = ProgressSpeed(),
)

@OptIn(ExperimentalAtomicApi::class)
private class BlocksMatchedStoreImpl : BlocksMatchedStore {
    private val state = AtomicReference(BlocksStoreState())
    private val listeners = AtomicReference<List<() -> Unit>>(emptyList())

    private fun emitChange() {
        for (listener in listeners.load()) listener()
    }

    override fun get() = state.load().progress

    override fun applyEvent(
        at: Long,
        downloaded: Int,
        matched: Int,
    ) {
        while (true) {
            val cur = state.load()
            val prev = cur.progress
            val isDone = downloaded >= matched
            val restarted = prev.downloaded >= prev.matched && !isDone
            val nextSpeed = cur.speed.next(prev.downloaded, downloaded, at, restarted)
            val nextPercent = progressPercent(downloaded, matched)
            val nextEta = if (isDone) 0L else nextSpeed.etaMs(downloaded, matched)
            if (
                prev.downloaded == downloaded &&
                prev.matched == matched &&
                prev.at == at &&
                prev.etaMs == nextEta &&
                prev.percent == nextPercent
            ) {
                return
            }
            val next =
                BlocksStoreState(
                    progress =
                        BlocksProgress(
                            downloaded = downloaded,
                            matched = matched,
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
