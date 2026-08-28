package io.bluewallet.blueberry.storage

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BlocksAndTxsTest {
    @Test
    fun matched_blocks_insert_is_idempotent() {
        val db = createSqliteDatabase(":memory:")
        assertTrue(
            db.matchedBlocks.insert(MatchedBlock(10, "aa".repeat(32))),
        )
        assertFalse(
            db.matchedBlocks.insert(MatchedBlock(10, "bb".repeat(32))),
        )
        assertEquals(1, db.matchedBlocks.count())
        db.close()
    }

    @Test
    fun blocks_insert_count_has_and_listNeedingDownload() {
        val db = createSqliteDatabase(":memory:")
        db.matchedBlocks.insert(MatchedBlock(10, "aa".repeat(32)))
        db.matchedBlocks.insert(MatchedBlock(11, "bb".repeat(32)))
        db.matchedBlocks.insert(MatchedBlock(12, "cc".repeat(32)))

        assertEquals(
            listOf(10, 11, 12),
            db.matchedBlocks.listNeedingDownload(10).map { it.height },
        )
        assertEquals(0, db.blocks.count())
        assertFalse(db.blocks.has(10))

        assertTrue(
            db.blocks.insert(
                DownloadedBlock(10, "aa".repeat(32), ByteArray(8) { 0xdd.toByte() }),
            ),
        )
        assertFalse(
            db.blocks.insert(
                DownloadedBlock(10, "aa".repeat(32), ByteArray(8) { 0xee.toByte() }),
            ),
        )

        assertEquals(1, db.blocks.count())
        assertTrue(db.blocks.has(10))
        val got = db.blocks.get(10)!!
        assertEquals(10, got.height)
        assertEquals("aa".repeat(32), got.blockHashInternalHex)
        assertContentEquals(ByteArray(8) { 0xdd.toByte() }, got.block)
        assertEquals(
            listOf(11, 12),
            db.matchedBlocks.listNeedingDownload(10).map { it.height },
        )
        assertEquals(
            listOf(11),
            db.matchedBlocks.listNeedingDownload(1).map { it.height },
        )
        db.close()
    }

    @Test
    fun block_insert_if_matched_rejects_stale_hash() {
        val db = createSqliteDatabase(":memory:")
        val canonicalHash = "aa".repeat(32)
        val staleHash = "bb".repeat(32)
        db.matchedBlocks.insert(MatchedBlock(10, canonicalHash))

        assertFalse(
            db.blocks.insertIfMatched(
                DownloadedBlock(10, staleHash, byteArrayOf(0x11)),
            ),
        )
        assertTrue(
            db.blocks.insertIfMatched(
                DownloadedBlock(10, canonicalHash, byteArrayOf(0x22)),
            ),
        )
        assertEquals(canonicalHash, db.blocks.get(10)?.blockHashInternalHex)
        db.close()
    }

    @Test
    fun parse_queue_idempotent_mark_upsert_replace_newest_first_list() {
        val db = createSqliteDatabase(":memory:")
        db.blocks.insert(DownloadedBlock(10, "aa".repeat(32), byteArrayOf(0x11)))
        db.blocks.insert(DownloadedBlock(11, "bb".repeat(32), byteArrayOf(0x22)))
        db.blocks.insert(DownloadedBlock(12, "cc".repeat(32), byteArrayOf(0x33)))

        assertEquals(listOf(10, 11, 12), db.blocks.listNeedingParseHeights(10))
        db.parsedBlocks.mark(11)
        db.parsedBlocks.mark(11)
        assertEquals(1, db.parsedBlocks.count())
        assertEquals(listOf(10, 12), db.blocks.listNeedingParseHeights(10))

        db.transactions.upsert(
            StoredTx("a".repeat(64), 12, 1, "cc".repeat(32), byteArrayOf(0xaa.toByte()), 100),
        )
        db.transactions.upsert(
            StoredTx("b".repeat(64), 10, 0, "aa".repeat(32), byteArrayOf(0xbb.toByte()), 50),
        )
        db.transactions.upsert(
            StoredTx("a".repeat(64), 12, 1, "cc".repeat(32), byteArrayOf(0xaa.toByte()), 999),
        )
        db.transactions.setNetDelta("a".repeat(64), 42)

        val list = db.transactions.list()
        assertEquals(
            listOf(listOf("a", 42L), listOf("b", 50L)),
            list.map { listOf(it.txid[0].toString(), it.netDeltaSats) },
        )
        assertEquals(
            TxSetFingerprint(2, 92, "a".repeat(64)),
            db.transactions.fingerprint(),
        )
        assertContentEquals(
            byteArrayOf(0xbb.toByte()),
            db.transactions.get("b".repeat(64))!!.tx,
        )
        assertNull(db.transactions.get("c".repeat(64)))
        db.close()
    }

    @Test
    fun transaction_fingerprint_is_empty_on_a_fresh_database() {
        val db = createSqliteDatabase(":memory:")
        assertEquals(
            TxSetFingerprint(0, 0, null),
            db.transactions.fingerprint(),
        )
        db.close()
    }

    @Test
    fun get_roundtrips_a_block_larger_than_android_cursor_window() {
        val db = createSqliteDatabase(":memory:")
        val blob = ByteArray(2_500_000) { i -> (i * 31).toByte() }
        assertTrue(
            db.blocks.insert(DownloadedBlock(99, "dd".repeat(32), blob)),
        )
        val got = db.blocks.get(99)!!
        assertEquals(99, got.height)
        assertEquals("dd".repeat(32), got.blockHashInternalHex)
        assertContentEquals(blob, got.block)
        db.close()
    }

    @Test
    fun transactions_minHeight_returns_lowest_stored_height() {
        val db = createSqliteDatabase(":memory:")
        db.transactions.upsert(
            StoredTx("aa".repeat(32), 10, 0, "bb".repeat(32), byteArrayOf(0x00), 1),
        )
        db.transactions.upsert(
            StoredTx("cc".repeat(32), 4, 0, "bb".repeat(32), byteArrayOf(0x00), 1),
        )
        assertEquals(4, db.transactions.minHeight())
        db.close()
    }
}
