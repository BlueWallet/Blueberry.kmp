package io.bluewallet.blueberry.boot

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DatabasePathTest {
    @Test
    fun joins_directory_and_filename() {
        assertEquals("/tmp/data/blueberry.sqlite", blueberrySqlitePath("/tmp/data"))
        assertEquals("/tmp/data/blueberry.sqlite", blueberrySqlitePath("/tmp/data/"))
    }

    @Test
    fun in_memory_paths_are_not_deleted() {
        assertTrue(isInMemorySqlitePath(":memory:"))
        assertTrue(isInMemorySqlitePath(""))
        assertTrue(isInMemorySqlitePath("file:memdb?mode=memory"))
        assertFalse(isInMemorySqlitePath("/tmp/data/blueberry.sqlite"))
    }

    @Test
    fun in_memory_database_size_is_zero() {
        assertEquals(0L, sqliteDatabaseBytes(":memory:"))
        assertEquals(0L, sqliteDatabaseBytes(""))
        assertEquals(0L, sqliteDatabaseBytes("file:memdb?mode=memory"))
    }

    @Test
    fun formatDatabaseGigabytes_uses_decimal_gb() {
        assertEquals("0 GB", formatDatabaseGigabytes(0))
        assertEquals("1 GB", formatDatabaseGigabytes(1_000_000_000))
        assertEquals("1.5 GB", formatDatabaseGigabytes(1_500_000_000))
        assertEquals("0.005 GB", formatDatabaseGigabytes(5_000_000))
        assertEquals("2.048 GB", formatDatabaseGigabytes(2_048_000_000))
    }
}
