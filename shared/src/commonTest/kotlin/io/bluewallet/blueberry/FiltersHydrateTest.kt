package io.bluewallet.blueberry

import io.bluewallet.blueberry.bus.BlocksProgressPayload
import io.bluewallet.blueberry.bus.Event
import io.bluewallet.blueberry.bus.FiltersProgressPayload
import io.bluewallet.blueberry.bus.createMessageBus
import io.bluewallet.blueberry.storage.FilterRecord
import io.bluewallet.blueberry.storage.createSqliteDatabase
import kotlin.test.Test
import kotlin.test.assertEquals

private fun addFilter(
    db: io.bluewallet.blueberry.storage.Database,
    height: Int,
    nibble: String,
) {
    db.filters.append(
        listOf(
            FilterRecord(
                height = height,
                blockHashInternalHex = nibble.repeat(32),
                filter = byteArrayOf(0xaa.toByte()),
            ),
        ),
    )
}

class FiltersHydrateTest {
    @Test
    fun empty_db_hydrate_is_zeros() {
        val db = createSqliteDatabase(":memory:")
        val store = createFiltersProgressStore()
        hydrateFilters(db, store, 500, 1)
        assertEquals(0, store.get().downloaded)
        assertEquals(500, store.get().total)
        db.close()
    }

    @Test
    fun two_stored_filters_hydrate_downloaded_and_total() {
        val db = createSqliteDatabase(":memory:")
        addFilter(db, 1, "11")
        addFilter(db, 2, "22")
        val store = createFiltersProgressStore()
        hydrateFilters(db, store, null, 1)
        assertEquals(2, store.get().downloaded)
        assertEquals(2, store.get().total)
        db.close()
    }

    @Test
    fun range_total_gt_zero_updates_total_downloaded_stays_from_db() {
        val db = createSqliteDatabase(":memory:")
        addFilter(db, 1, "11")
        val store = createFiltersProgressStore()
        hydrateFilters(db, store, null, 1)
        assertEquals(1, store.get().downloaded)
        assertEquals(1, store.get().total)

        hydrateFilters(db, store, 0, 2)
        assertEquals(1, store.get().downloaded)
        assertEquals(1, store.get().total)

        hydrateFilters(db, store, 200, 3)
        assertEquals(1, store.get().downloaded)
        assertEquals(200, store.get().total)
        assertEquals(3, store.get().at)

        hydrateFilters(db, store, 0, 4)
        assertEquals(1, store.get().downloaded)
        assertEquals(200, store.get().total)
        db.close()
    }

    @Test
    fun downloaded_is_clamped_to_session_total() {
        val db = createSqliteDatabase(":memory:")
        addFilter(db, 1, "11")
        addFilter(db, 2, "22")
        val store = createFiltersProgressStore()
        hydrateFilters(db, store, 1, 1)
        assertEquals(1, store.get().downloaded)
        assertEquals(1, store.get().total)
        db.close()
    }

    @Test
    fun hydrates_from_db_payload_total_gt_zero_only_zeros_do_not_clobber() {
        val bus = createMessageBus()
        val db = createSqliteDatabase(":memory:")
        addFilter(db, 1, "11")
        val store = createFiltersProgressStore()
        val off = bindFilterProgressEvents(bus, db, store)
        hydrateFilters(db, store)
        assertEquals(1, store.get().downloaded)
        assertEquals(1, store.get().total)

        bus.emit(Event.FiltersProgress, FiltersProgressPayload(500, 0, 0))
        assertEquals(1, store.get().downloaded)
        assertEquals(1, store.get().total)

        bus.emit(Event.FiltersProgress, FiltersProgressPayload(1000, 50, 200))
        assertEquals(1, store.get().downloaded)
        assertEquals(200, store.get().total)
        assertEquals(1000, store.get().at)
        assertEquals(0, store.get().percent)
        off()
        db.close()
    }

    @Test
    fun blocks_progress_rehydrates_downloaded_from_db_keeps_session_total() {
        val bus = createMessageBus()
        val db = createSqliteDatabase(":memory:")
        addFilter(db, 1, "11")
        addFilter(db, 2, "22")
        val store = createFiltersProgressStore()
        val off = bindFilterProgressEvents(bus, db, store)
        hydrateFilters(db, store, 2, 1)
        assertEquals(2, store.get().downloaded)
        assertEquals(2, store.get().total)

        db.wipeFiltersFrom(2)
        bus.emit(Event.BlocksProgress, BlocksProgressPayload(at = 2000, downloaded = 9, matched = 9))
        assertEquals(1, store.get().downloaded)
        assertEquals(2, store.get().total)
        assertEquals(2000, store.get().at)
        off()
        db.close()
    }
}
