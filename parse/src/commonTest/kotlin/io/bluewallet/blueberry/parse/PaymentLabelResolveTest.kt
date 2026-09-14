package io.bluewallet.blueberry.parse

import io.bluewallet.blueberry.wallet.outpointKey
import kotlin.test.Test
import kotlin.test.assertEquals

class PaymentLabelResolveTest {
    @Test
    fun indexes_first_nonblank_label_per_txid_by_lowest_vout() {
        val txA = "aa".repeat(32)
        val txB = "bb".repeat(32)
        val labels =
            firstUtxoLabelByTxid(
                mapOf(
                    outpointKey(txA, 1) to "later",
                    outpointKey(txA, 0) to "  first  ",
                    outpointKey(txB, 2) to "only",
                    outpointKey(txB, 1) to "   ",
                    "not-an-outpoint" to "skip",
                ),
            )
        assertEquals(mapOf(txA to "first", txB to "only"), labels)
    }
}
