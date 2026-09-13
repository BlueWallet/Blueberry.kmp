package io.bluewallet.blueberry

import io.bluewallet.blueberry.bus.Event
import io.bluewallet.blueberry.bus.MatchingProgressPayload
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

class MatchingHydrateTest {
    @Test
    fun seeds_from_db_and_applies_matching_progress_from_db_not_payload_counts() {
        val bus = createMessageBus()
        val db = createSqliteDatabase(":memory:")
        addFilter(db, 1, "11")
        addFilter(db, 2, "22")
        db.filters.markScanned(listOf(1))
        val store = createMatchingProgressStore()
        val off = bindMatchingProgressEvents(bus, db, store)
        hydrateMatching(db, store, 1)
        assertEquals(1, store.get().scanned)
        assertEquals(2, store.get().total)
        assertEquals(50, store.get().percent)

        bus.emit(Event.MatchingProgress, MatchingProgressPayload(at = 2000, scanned = 2, total = 2))
        assertEquals(1, store.get().scanned)
        assertEquals(2, store.get().total)
        assertEquals(50, store.get().percent)

        db.filters.markScanned(listOf(2))
        bus.emit(Event.MatchingProgress, MatchingProgressPayload(at = 3000, scanned = 0, total = 0))
        assertEquals(2, store.get().scanned)
        assertEquals(2, store.get().total)
        assertEquals(100, store.get().percent)
        assertEquals(3000, store.get().at)
        off()
        db.close()
    }

    @Test
    fun matching_follows_db_not_caller_counts() {
        val db = createSqliteDatabase(":memory:")
        addFilter(db, 1, "11")
        addFilter(db, 2, "22")
        db.filters.markScanned(listOf(1))
        val store = createMatchingProgressStore()
        hydrateMatching(db, store, 1)
        assertEquals(1, store.get().scanned)
        assertEquals(2, store.get().total)
        db.filters.markScanned(listOf(2))
        hydrateMatching(db, store, 2)
        assertEquals(2, store.get().scanned)
        assertEquals(2, store.get().total)
        db.close()
    }
}
