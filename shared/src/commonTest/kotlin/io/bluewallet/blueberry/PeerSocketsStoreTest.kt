package io.bluewallet.blueberry

import io.bluewallet.blueberry.bus.Event
import io.bluewallet.blueberry.bus.PeerSocketKind
import io.bluewallet.blueberry.bus.PeersSocketsPayload
import io.bluewallet.blueberry.bus.PeersUpdatedPayload
import io.bluewallet.blueberry.bus.createMessageBus
import io.bluewallet.blueberry.storage.PeerWrite
import io.bluewallet.blueberry.storage.createSqliteDatabase
import kotlin.test.Test
import kotlin.test.assertEquals

class PeerSocketsStoreTest {
    @Test
    fun merges_kinds_independently_clamps_open_ignores_noops() {
        val store = createPeerSocketsStore()
        var ticks = 0
        store.subscribe { ticks++ }
        store.applyEvent(PeerSocketKind.PROBE, 2)
        store.applyEvent(PeerSocketKind.FILT, 4)
        store.applyEvent(PeerSocketKind.PROBE, 0)
        assertEquals(
            PeerSocketCounts(known = 0, probe = 0, hdr = 0, filt = 4, blk = 0),
            store.get(),
        )
        store.applyEvent(PeerSocketKind.BLK, -3)
        assertEquals(0, store.get().blk)
        val before = ticks
        store.applyEvent(PeerSocketKind.FILT, 4)
        assertEquals(before, ticks)
    }

    @Test
    fun format_matches_helix3() {
        assertEquals(
            "probe 1 · hdr 2 · filt 3 · blk 4",
            formatPeerSockets(PeerSocketCounts(known = 9, probe = 1, hdr = 2, filt = 3, blk = 4)),
        )
    }

    @Test
    fun seeds_known_from_db_and_applies_bus_updates() {
        val bus = createMessageBus()
        val db = createSqliteDatabase(":memory:")
        db.peers.upsert(
            PeerWrite("1.1.1.1", 8333, 0uL, alive = false, blocksServed = 0, lastProbedAt = null),
        )
        val store = createPeerSocketsStore()
        val unsubs = mutableListOf<() -> Unit>()
        unsubs += bus.on(Event.PeersUpdated) { hydratePeers(db, store) }
        unsubs += bus.on(Event.PeersSockets) { store.applyEvent(it.kind, it.open) }
        hydratePeers(db, store)
        assertEquals(1, store.get().known)

        bus.emit(Event.PeersSockets, PeersSocketsPayload(at = 1, kind = PeerSocketKind.HDR, open = 3))
        assertEquals(3, store.get().hdr)

        db.peers.upsert(
            PeerWrite("9.9.9.9", 8333, 1uL, alive = false, blocksServed = 0, lastProbedAt = null),
        )
        bus.emit(Event.PeersUpdated, PeersUpdatedPayload(at = 2))
        assertEquals(2, store.get().known)

        unsubs.forEach { it() }
        db.close()
    }
}
