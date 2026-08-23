package io.bluewallet.blueberry.storage

import kotlin.io.path.createTempFile
import kotlin.io.path.pathString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PragmaReadbackTest {
    @Test
    fun createSqliteDatabase_applies_expected_pragmas() {
        val path = createTempFile(prefix = "blueberry-storage", suffix = ".sqlite").pathString
        val db = createSqliteDatabase(path) as SqliteDatabase
        try {
            assertEquals("wal", db.pragmaValue("journal_mode").lowercase())
            val synchronous = db.pragmaValue("synchronous")
            assertTrue(
                synchronous == "1" || synchronous.equals("normal", ignoreCase = true),
                "synchronous=$synchronous",
            )
            assertEquals("10000", db.pragmaValue("wal_autocheckpoint"))
            assertEquals("5000", db.pragmaValue("busy_timeout"))
        } finally {
            db.close()
        }
    }
}
