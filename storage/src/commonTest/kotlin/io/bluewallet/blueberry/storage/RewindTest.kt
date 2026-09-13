package io.bluewallet.blueberry.storage

import com.ionspin.kotlin.bignum.integer.BigInteger
import io.bluewallet.headers.checkpointSeedRecord
import io.bluewallet.headers.hexToBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RewindTest {
    @Test
    fun rewindAfter_drops_height_dependent_rows_above_ancestor() {
        val db = createSqliteDatabase(":memory:")
        val seed = checkpointSeedRecord()
        db.headers.ensureCheckpoint(checkpointDbRecord())
        val base = db.headers.tip()!!.cumulativeWork
        val h1 = seed.height.toInt() + 1
        val h2 = seed.height.toInt() + 2
        db.headers.append(
            listOf(
                testHeader(h1, "a1", base + BigInteger.ONE),
                testHeader(h2, "a2", base + BigInteger.TWO),
            ),
        )

        db.filterHeaders.append(
            listOf(
                FilterHeaderRecord(h1, hexToBytes("11".repeat(32))),
                FilterHeaderRecord(h2, hexToBytes("22".repeat(32))),
            ),
        )
        db.filters.append(
            listOf(
                FilterRecord(h1, "11".repeat(32), byteArrayOf(1)),
                FilterRecord(h2, "22".repeat(32), byteArrayOf(2)),
            ),
        )
        db.matchedBlocks.insert(MatchedBlock(h2, "22".repeat(32)))
        db.blocks.insert(DownloadedBlock(h2, "22".repeat(32), byteArrayOf(9)))
        db.parsedBlocks.mark(h2)
        db.transactions.upsert(
            StoredTx(
                txid = "aa".repeat(32),
                height = h2,
                txIndex = 0,
                blockHashInternalHex = "22".repeat(32),
                tx = byteArrayOf(7),
                netDeltaSats = 1,
            ),
        )

        db.transaction {
            db.rewindAfter(h1)
            db.headers.replaceAfter(
                h1,
                listOf(testHeader(h2, "b2", base + BigInteger.fromInt(20))),
            )
        }

        assertTrue(
            db.headers
                .tip()!!
                .hashInternalHex
                .endsWith("b2"),
        )
        assertNull(db.filterHeaders.get(h2))
        assertNull(db.filters.get(h2))
        assertNotNull(db.filters.get(h1))
        assertEquals(0, db.matchedBlocks.count())
        assertFalse(db.blocks.has(h2))
        assertFalse(db.parsedBlocks.has(h2))
        assertEquals(emptyList(), db.transactions.list())
        db.close()
    }
}
