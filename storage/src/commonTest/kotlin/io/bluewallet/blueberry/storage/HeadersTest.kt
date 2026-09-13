package io.bluewallet.blueberry.storage

import com.ionspin.kotlin.bignum.integer.BigInteger
import io.bluewallet.headers.checkpointSeedRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HeadersTest {
    @Test
    fun ensureCheckpoint_seeds_once_and_rejects_mismatch() {
        val db = createSqliteDatabase(":memory:")
        val seed = checkpointSeedRecord()
        db.headers.ensureCheckpoint(checkpointDbRecord())
        assertEquals(1, db.headers.count())
        assertEquals(seed.height.toInt(), db.headers.tip()!!.height)
        assertTrue(db.headers.tip()!!.cumulativeWork > BigInteger.ZERO)
        db.headers.ensureCheckpoint(checkpointDbRecord())
        assertEquals(1, db.headers.count())
        val ex =
            assertFailsWith<IllegalStateException> {
                db.headers.ensureCheckpoint(
                    checkpointDbRecord().copy(hashInternalHex = "00".repeat(32)),
                )
            }
        assertTrue(ex.message!!.contains("checkpoint mismatch"))
        assertTrue(ex.message!!.contains("Delete blueberry.data/blueberry.sqlite"))
        db.close()
    }

    @Test
    fun append_and_replaceAfter_preserve_cumulative_work() {
        val db = createSqliteDatabase(":memory:")
        val seed = checkpointSeedRecord()
        db.headers.ensureCheckpoint(checkpointDbRecord())
        val base = db.headers.tip()!!.cumulativeWork
        db.headers.append(
            listOf(
                testHeader(seed.height.toInt() + 1, "a1", base + BigInteger.ONE),
                testHeader(seed.height.toInt() + 2, "a2", base + BigInteger.TWO),
            ),
        )
        assertEquals(3, db.headers.count())
        assertEquals(seed.height.toInt() + 2, db.headers.tip()!!.height)
        assertEquals(base + BigInteger.TWO, db.headers.tip()!!.cumulativeWork)
        db.headers.replaceAfter(
            seed.height.toInt(),
            listOf(
                testHeader(seed.height.toInt() + 1, "b1", base + BigInteger.fromInt(10)),
                testHeader(seed.height.toInt() + 2, "b2", base + BigInteger.fromInt(20)),
                testHeader(seed.height.toInt() + 3, "b3", base + BigInteger.fromInt(30)),
            ),
        )
        assertEquals(4, db.headers.count())
        assertTrue(
            db.headers
                .tip()!!
                .hashInternalHex
                .endsWith("b3"),
        )
        assertEquals(base + BigInteger.fromInt(30), db.headers.tip()!!.cumulativeWork)
        assertEquals(
            listOf(seed.height.toInt() + 1, seed.height.toInt() + 2, seed.height.toInt() + 3),
            db.headers
                .loadRange(seed.height.toInt() + 1, seed.height.toInt() + 3)
                .map { it.height },
        )
        assertEquals(
            seed.height.toInt() + 2,
            db.headers.heightForHashInternal(
                testHeader(seed.height.toInt() + 2, "b2", BigInteger.ZERO).hashInternalHex,
            ),
        )
        db.close()
    }

    @Test
    fun minHeight_is_null_when_empty_and_seed_height_after_checkpoint() {
        val db = createSqliteDatabase(":memory:")
        assertNull(db.headers.minHeight())
        db.headers.ensureCheckpoint(checkpointDbRecord())
        assertEquals(checkpointSeedRecord().height.toInt(), db.headers.minHeight())
        db.close()
    }
}
