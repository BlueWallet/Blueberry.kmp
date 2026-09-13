package io.bluewallet.blueberry

import io.bluewallet.blueberry.bus.BlocksProgressPayload
import io.bluewallet.blueberry.bus.Event
import io.bluewallet.blueberry.bus.FiltersMatchPayload
import io.bluewallet.blueberry.bus.createMessageBus
import io.bluewallet.blueberry.storage.DownloadedBlock
import io.bluewallet.blueberry.storage.MatchedBlock
import io.bluewallet.blueberry.storage.createSqliteDatabase
import kotlin.test.Test
import kotlin.test.assertEquals

class BlocksHydrateTest {
    @Test
    fun seeds_from_db_and_applies_blocks_progress_from_db_not_payload_counts() {
        val bus = createMessageBus()
        val db = createSqliteDatabase(":memory:")
        db.matchedBlocks.insert(MatchedBlock(1, "11".repeat(32)))
        val store = createBlocksMatchedStore()
        val off = bindBlocksProgressEvents(bus, db, store)
        hydrateBlocks(db, store, 1)
        assertEquals(0, store.get().downloaded)
        assertEquals(1, store.get().matched)
        assertEquals(0, store.get().percent)

        bus.emit(Event.BlocksProgress, BlocksProgressPayload(at = 2000, downloaded = 9, matched = 9))
        assertEquals(0, store.get().downloaded)
        assertEquals(1, store.get().matched)

        db.blocks.insert(DownloadedBlock(1, "11".repeat(32), byteArrayOf(1)))
        bus.emit(Event.BlocksProgress, BlocksProgressPayload(at = 3000, downloaded = 0, matched = 0))
        assertEquals(1, store.get().downloaded)
        assertEquals(1, store.get().matched)
        assertEquals(100, store.get().percent)
        assertEquals(3000, store.get().at)
        off()
        db.close()
    }

    @Test
    fun filters_match_rehydrates_from_db() {
        val bus = createMessageBus()
        val db = createSqliteDatabase(":memory:")
        val store = createBlocksMatchedStore()
        val off = bindBlocksProgressEvents(bus, db, store)
        hydrateBlocks(db, store, 1)
        assertEquals(0, store.get().matched)

        db.matchedBlocks.insert(MatchedBlock(1, "11".repeat(32)))
        bus.emit(Event.FiltersMatch, FiltersMatchPayload(1, "11".repeat(32)))
        assertEquals(0, store.get().downloaded)
        assertEquals(1, store.get().matched)
        off()
        db.close()
    }
}
