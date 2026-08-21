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
}
