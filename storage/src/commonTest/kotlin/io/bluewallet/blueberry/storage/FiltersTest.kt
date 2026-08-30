package io.bluewallet.blueberry.storage

import io.bluewallet.headers.checkpointSeedRecord
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FiltersTest {
    @Test
    fun filter_round_trips_as_blob_bytes() {
        val db = createSqliteDatabase(":memory:")
        val bytes = byteArrayOf(0xde.toByte(), 0xad.toByte(), 0xbe.toByte(), 0xef.toByte())
        db.filters.append(
            listOf(FilterRecord(height = 1, blockHashInternalHex = "11".repeat(32), filter = bytes)),
        )
        assertContentEquals(bytes, db.filters.get(1)!!.filter)
        db.close()
    }

    @Test
    fun wipeFiltersFrom_removes_filters_and_filter_headers_atomically() {
        val db = createSqliteDatabase(":memory:")
        db.filterHeaders.append(
            listOf(
                FilterHeaderRecord(9, ByteArray(32) { 0x09 }),
                FilterHeaderRecord(10, ByteArray(32) { 0x0a }),
                FilterHeaderRecord(11, ByteArray(32) { 0x0b }),
            ),
        )
        db.filters.append(
            listOf(
                FilterRecord(10, "0a".repeat(32), byteArrayOf(1)),
                FilterRecord(11, "0b".repeat(32), byteArrayOf(2)),
            ),
        )

        db.wipeFiltersFrom(10, WipeFiltersFromOptions(prevHeaderHeight = 9))

        assertNull(db.filters.get(10))
        assertNull(db.filters.get(11))
        assertEquals(0, db.filters.count())
        assertNull(db.filterHeaders.get(9))
        assertNull(db.filterHeaders.get(10))
        assertNull(db.filterHeaders.tip())
        db.close()
    }

    @Test
    fun missingRanges_splits_gaps_by_maxSpan() {
        val db = createSqliteDatabase(":memory:")
        db.filters.append(
            listOf(
                FilterRecord(100, "11".repeat(32), byteArrayOf(0x01)),
                FilterRecord(103, "33".repeat(32), byteArrayOf(0x03)),
            ),
        )
        assertEquals(
            listOf(HeightRange(101, 102), HeightRange(104, 104)),
            db.filters.missingRanges(100, 104, 2),
        )
        db.filters.append(
            listOf(
                FilterRecord(101, "12".repeat(32), byteArrayOf(0x02)),
                FilterRecord(102, "13".repeat(32), byteArrayOf(0x04)),
                FilterRecord(104, "14".repeat(32), byteArrayOf(0x05)),
            ),
        )
        assertEquals(emptyList(), db.filters.missingRanges(100, 104, 2))
        assertEquals(5, db.filters.countInRange(100, 104))
        assertEquals(104, db.filters.maxHeight())
        db.filters.deleteFrom(103)
        assertFalse(db.filters.has(103))
        assertEquals(102, db.filters.maxHeight())
        db.close()
    }

    @Test
    fun missingRanges_tip_gap_uses_contiguous_fast_path() {
        val db = createSqliteDatabase(":memory:")
        db.filters.append(
            listOf(
                FilterRecord(100, "11".repeat(32), byteArrayOf(0x01)),
                FilterRecord(101, "22".repeat(32), byteArrayOf(0x02)),
                FilterRecord(102, "33".repeat(32), byteArrayOf(0x03)),
            ),
        )
        // Contiguous through 102; tip advanced to 105 — only the tip gap.
        assertEquals(
            listOf(HeightRange(103, 104), HeightRange(105, 105)),
            db.filters.missingRanges(100, 105, 2),
        )
        // Fast path must not expand backward below `from`.
        assertEquals(
            listOf(HeightRange(105, 106), HeightRange(107, 108), HeightRange(109, 110)),
            db.filters.missingRanges(105, 110, 2),
        )
        db.close()
    }

    @Test
    fun missingRanges_walks_present_heights_leading_and_trailing_gaps() {
        val db = createSqliteDatabase(":memory:")
        db.filters.append(
            listOf(
                FilterRecord(105, "55".repeat(32), byteArrayOf(0x05)),
                FilterRecord(107, "77".repeat(32), byteArrayOf(0x07)),
            ),
        )
        assertEquals(
            listOf(
                HeightRange(100, 102),
                HeightRange(103, 104),
                HeightRange(106, 106),
                HeightRange(108, 110),
            ),
            db.filters.missingRanges(100, 110, 3),
        )
        db.close()
    }

    @Test
    fun completeInRange_detects_tip_gap_and_internal_holes() {
        val db = createSqliteDatabase(":memory:")
        assertFalse(db.filters.completeInRange(100, 102))
        db.filters.append(
            listOf(
                FilterRecord(100, "11".repeat(32), byteArrayOf(0x01)),
                FilterRecord(101, "22".repeat(32), byteArrayOf(0x02)),
                FilterRecord(102, "33".repeat(32), byteArrayOf(0x03)),
            ),
        )
        assertTrue(db.filters.completeInRange(100, 102))
        assertFalse(db.filters.completeInRange(100, 103)) // tip gap
        db.filters.deleteFrom(101)
        db.filters.append(
            listOf(FilterRecord(102, "33".repeat(32), byteArrayOf(0x03))),
        )
        assertFalse(db.filters.completeInRange(100, 102)) // hole at 101
        db.close()
    }

    @Test
    fun completeInRange_is_true_for_filled_span_even_with_hole_below_it() {
        val db = createSqliteDatabase(":memory:")
        db.filters.append(
            listOf(
                FilterRecord(50, "aa".repeat(32), byteArrayOf(0x01)),
                FilterRecord(100, "bb".repeat(32), byteArrayOf(0x02)),
                FilterRecord(101, "cc".repeat(32), byteArrayOf(0x03)),
                FilterRecord(102, "dd".repeat(32), byteArrayOf(0x04)),
            ),
        )
        assertTrue(db.filters.completeInRange(100, 102))
        assertEquals(emptyList(), db.filters.missingRanges(100, 102, 10_000))
        assertFalse(db.filters.completeInRange(50, 102))
        db.close()
    }

    @Test
    fun firstHashMismatch_finds_disagreeing_filter() {
        val db = createSqliteDatabase(":memory:")
        val seed = checkpointSeedRecord()
        db.headers.ensureCheckpoint(checkpointDbRecord())
        val from = seed.height.toInt()
        assertNull(db.filters.firstHashMismatch(from, from))

        db.filters.append(
            listOf(FilterRecord(from, "ff".repeat(32), byteArrayOf(0x02))),
        )
        assertEquals(from, db.filters.firstHashMismatch(from, from))
        db.close()
    }

    @Test
    fun hashAt_returns_stored_block_hash_or_null() {
        val db = createSqliteDatabase(":memory:")
        assertNull(db.filters.hashAt(100))
        db.filters.append(
            listOf(FilterRecord(100, "ab".repeat(32), ByteArray(45 * 1024) { 0x01 })),
        )
        assertEquals("ab".repeat(32), db.filters.hashAt(100))
        db.close()
    }

    @Test
    fun listNeedingMatch_markScanned_countScanned() {
        val db = createSqliteDatabase(":memory:")
        db.filters.append(
            listOf(
                FilterRecord(10, "aa".repeat(32), byteArrayOf(0x01)),
                FilterRecord(11, "bb".repeat(32), byteArrayOf(0x02)),
                FilterRecord(12, "cc".repeat(32), byteArrayOf(0x03)),
            ),
        )
        assertEquals(0, db.filters.countScanned())
        assertEquals(listOf(10, 11, 12), db.filters.listNeedingMatch(10).map { it.height })
        assertEquals(listOf(10), db.filters.listNeedingMatch(1).map { it.height })

        db.filters.markScanned(listOf(10, 12))
        assertEquals(2, db.filters.countScanned())
        assertEquals(listOf(11), db.filters.listNeedingMatch(10).map { it.height })

        db.filters.markScanned(listOf(11))
        assertEquals(emptyList(), db.filters.listNeedingMatch(10))
        assertEquals(3, db.filters.countScanned())
        db.close()
    }

    @Test
    fun markScanned_uses_filters_unscanned_queue_not_fat_row_updates() {
        val db = createSqliteDatabase(":memory:")
        db.filters.append(
            listOf(
                FilterRecord(1, "aa".repeat(32), ByteArray(1000) { 0x01 }),
                FilterRecord(2, "bb".repeat(32), ByteArray(1000) { 0x02 }),
            ),
        )
        db.filters.markScanned(listOf(2))
        assertEquals(1, db.filters.countScanned())
        assertEquals(listOf(1), db.filters.listNeedingMatch(10).map { it.height })
        db.filters.markScanned(listOf(1))
        assertEquals(emptyList(), db.filters.listNeedingMatch(10))
        assertEquals(2, db.filters.countScanned())
        db.close()
    }

    @Test
    fun markUnscannedFrom_requeues_from_height() {
        val db = createSqliteDatabase(":memory:")
        for (h in 1..5) {
            db.filters.append(
                listOf(FilterRecord(h, "ab".repeat(32), byteArrayOf(0x00))),
            )
        }
        db.filters.markScanned(listOf(1, 2, 3, 4, 5))
        assertEquals(5, db.filters.countScanned())

        db.filters.markUnscannedFrom(3)
        assertEquals(listOf(3, 4, 5), db.filters.listNeedingMatch(10).map { it.height })
        assertEquals(2, db.filters.countScanned())

        db.filters.markUnscannedFrom(3) // idempotent
        assertEquals(3, db.filters.listNeedingMatch(10).size)
        db.close()
    }

    @Test
    fun hasUnscanned_ignores_orphan_queue_rows() {
        val db = createSqliteDatabase(":memory:")
        db.filters.markUnscanned(listOf(99))
        assertFalse(db.filters.hasUnscanned())
        assertEquals(emptyList(), db.filters.listNeedingMatch(10))

        db.filters.append(
            listOf(FilterRecord(1, "aa".repeat(32), byteArrayOf(0x01))),
        )
        assertTrue(db.filters.hasUnscanned())
        db.close()
    }
}
