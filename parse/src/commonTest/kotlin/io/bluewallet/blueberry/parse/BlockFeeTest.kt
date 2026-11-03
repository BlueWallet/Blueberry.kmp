package io.bluewallet.blueberry.parse

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BlockFeeTest {
    @Test
    fun subsidy_halves_every_210000_blocks() {
        assertEquals(5_000_000_000L, blockSubsidySats(0))
        assertEquals(5_000_000_000L, blockSubsidySats(209_999))
        assertEquals(2_500_000_000L, blockSubsidySats(210_000))
        assertEquals(0L, blockSubsidySats(-1))
    }

    @Test
    fun fee_rate_is_coinbase_above_subsidy_over_the_other_transactions() {
        val pay = coinbaseLikeReceive(unrelatedScript(), 1_000, prevSalt = 1)
        val vsize = (pay.weight() + 3) / 4
        val fees = vsize * 12L
        val coinbase = coinbaseLikeReceive(unrelatedScript(), blockSubsidySats(0) + fees)
        val block = wrapBlock(coinbase, pay)
        assertEquals("12.0 sat/vB", blockFeeRateLabel(block, 0))
    }

    @Test
    fun short_blob_has_no_fee_rate() {
        assertNull(blockFeeRateLabel(byteArrayOf(0x01, 0x02), 0))
    }
}
