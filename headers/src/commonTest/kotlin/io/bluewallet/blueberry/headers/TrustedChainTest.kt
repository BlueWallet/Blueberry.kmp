package io.bluewallet.blueberry.headers

import io.bluewallet.blueberry.storage.createSqliteDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TrustedChainTest {
    @Test
    fun loads_checkpoint_without_consensus_re_validation() {
        val db = createSqliteDatabase(":memory:")
        val seed = checkpointSeedRecord()
        db.headers.ensureCheckpoint(checkpointDbRecord())
        val chain = trustedChainFromStored(db.headers.loadAll(), BLUEBERRY_HEADER_CONSENSUS)
        assertEquals(seed.height.toLong(), chain.tipHeight)
        assertEquals(seed.hashDisplay, chain.tipHashDisplay)
        assertEquals(db.headers.tip()!!.cumulativeWork, chain.chainWork)
        assertTrue(TRUSTED_CHAIN_WINDOW > 2016)
        db.close()
    }

    @Test
    fun empty_and_gapped_rows_throw() {
        assertFailsWith<IllegalArgumentException> {
            trustedChainFromStored(emptyList(), BLUEBERRY_HEADER_CONSENSUS)
        }
        val db = createSqliteDatabase(":memory:")
        db.headers.ensureCheckpoint(checkpointDbRecord())
        val tip = db.headers.tip()!!
        val gap =
            listOf(
                tip,
                tip.copy(height = tip.height + 2, hashInternalHex = "ab".repeat(32)),
            )
        val ex =
            assertFailsWith<IllegalStateException> {
                trustedChainFromStored(gap, BLUEBERRY_HEADER_CONSENSUS)
            }
        assertTrue(ex.message!!.contains("trusted chain gap"))
        db.close()
    }

    @Test
    fun internal_hex_reverses_to_display() {
        assertEquals("ddccbbaa", internalHexToDisplayHex("aabbccdd"))
    }
}
