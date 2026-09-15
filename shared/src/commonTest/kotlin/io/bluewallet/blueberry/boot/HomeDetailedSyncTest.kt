package io.bluewallet.blueberry.boot

import io.bluewallet.blueberry.storage.createSqliteDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HomeDetailedSyncTest {
    @Test
    fun missing_or_invalid_kv_loads_as_collapsed() {
        val db = createSqliteDatabase(":memory:")
        assertFalse(loadHomeDetailedSync(db))
        assertEquals(null, db.keyValue.get(HOME_DETAILED_SYNC_KEY))

        db.keyValue.set(HOME_DETAILED_SYNC_KEY, "nope")
        assertFalse(loadHomeDetailedSync(db))
        db.keyValue.set(HOME_DETAILED_SYNC_KEY, "")
        assertFalse(loadHomeDetailedSync(db))
        db.keyValue.set(HOME_DETAILED_SYNC_KEY, "2")
        assertFalse(loadHomeDetailedSync(db))
        db.close()
    }

    @Test
    fun save_load_round_trip() {
        val db = createSqliteDatabase(":memory:")
        saveHomeDetailedSync(db, false)
        assertEquals("0", db.keyValue.get(HOME_DETAILED_SYNC_KEY))
        assertFalse(loadHomeDetailedSync(db))

        saveHomeDetailedSync(db, true)
        assertEquals("1", db.keyValue.get(HOME_DETAILED_SYNC_KEY))
        assertTrue(loadHomeDetailedSync(db))
        db.close()
    }
}
