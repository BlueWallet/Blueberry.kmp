package io.bluewallet.blueberry

import io.bluewallet.blueberry.bus.PeerSocketKind
import io.bluewallet.blueberry.storage.Database
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.math.max

data class PeerSocketCounts(
    val known: Int = 0,
    val probe: Int = 0,
    val hdr: Int = 0,
    val filt: Int = 0,
    val blk: Int = 0,
)

interface PeerSocketsStore {
    fun get(): PeerSocketCounts

    fun setKnown(known: Int)

    fun applyEvent(
        kind: PeerSocketKind,
        open: Int,
    )

    fun subscribe(listener: () -> Unit): () -> Unit
}

fun formatPeerSockets(counts: PeerSocketCounts): String = "probe ${counts.probe} · hdr ${counts.hdr} · filt ${counts.filt} · blk ${counts.blk}"

fun hydratePeers(
    db: Database,
    store: PeerSocketsStore,
) {
    store.setKnown(db.peers.count())
}

@OptIn(ExperimentalAtomicApi::class)
private class PeerSocketsStoreImpl : PeerSocketsStore {
    private val snapshot = AtomicReference(PeerSocketCounts())
    private val listeners = AtomicReference<List<() -> Unit>>(emptyList())

    private fun emitChange() {
        for (listener in listeners.load()) listener()
    }

    private fun replaceSnapshot(next: (PeerSocketCounts) -> PeerSocketCounts): Boolean {
        while (true) {
            val cur = snapshot.load()
            val updated = next(cur)
            if (updated == cur) return false
            if (snapshot.compareAndSet(cur, updated)) return true
        }
    }

    override fun get() = snapshot.load()

    override fun setKnown(known: Int) {
        val next = max(0, known)
        if (replaceSnapshot { cur -> if (cur.known == next) cur else cur.copy(known = next) }) {
            emitChange()
        }
    }

    override fun applyEvent(
        kind: PeerSocketKind,
        open: Int,
    ) {
        val next = max(0, open)
        val changed =
            replaceSnapshot { cur ->
                val curValue =
                    when (kind) {
                        PeerSocketKind.PROBE -> cur.probe
                        PeerSocketKind.HDR -> cur.hdr
                        PeerSocketKind.FILT -> cur.filt
                        PeerSocketKind.BLK -> cur.blk
                    }
                if (curValue == next) {
                    cur
                } else {
                    when (kind) {
                        PeerSocketKind.PROBE -> cur.copy(probe = next)
                        PeerSocketKind.HDR -> cur.copy(hdr = next)
                        PeerSocketKind.FILT -> cur.copy(filt = next)
                        PeerSocketKind.BLK -> cur.copy(blk = next)
                    }
                }
            }
        if (changed) emitChange()
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

fun createPeerSocketsStore(): PeerSocketsStore = PeerSocketsStoreImpl()
