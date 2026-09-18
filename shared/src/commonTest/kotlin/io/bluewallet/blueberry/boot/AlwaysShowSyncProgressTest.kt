package io.bluewallet.blueberry.boot

import io.bluewallet.blueberry.storage.createSqliteDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AlwaysShowSyncProgressTest {
    @Test
    fun missing_or_invalid_kv_loads_as_off() {
        val db = createSqliteDatabase(":memory:")
        assertFalse(loadAlwaysShowSyncProgress(db))
        assertEquals(null, db.keyValue.get(ALWAYS_SHOW_SYNC_PROGRESS_KEY))

        db.keyValue.set(ALWAYS_SHOW_SYNC_PROGRESS_KEY, "nope")
        assertFalse(loadAlwaysShowSyncProgress(db))
        db.keyValue.set(ALWAYS_SHOW_SYNC_PROGRESS_KEY, "")
        assertFalse(loadAlwaysShowSyncProgress(db))
        db.keyValue.set(ALWAYS_SHOW_SYNC_PROGRESS_KEY, "2")
        assertFalse(loadAlwaysShowSyncProgress(db))
        db.close()
    }

    @Test
    fun save_load_round_trip() {
        val db = createSqliteDatabase(":memory:")
        saveAlwaysShowSyncProgress(db, false)
        assertEquals("0", db.keyValue.get(ALWAYS_SHOW_SYNC_PROGRESS_KEY))
        assertFalse(loadAlwaysShowSyncProgress(db))

        saveAlwaysShowSyncProgress(db, true)
        assertEquals("1", db.keyValue.get(ALWAYS_SHOW_SYNC_PROGRESS_KEY))
        assertTrue(loadAlwaysShowSyncProgress(db))
        db.close()
    }
}
