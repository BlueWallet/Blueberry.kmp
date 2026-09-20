package io.bluewallet.blueberry.parse

import fr.acinq.bitcoin.Transaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TxFeeTest {
    @Test
    fun virtual_size_is_weight_ceiled_to_vbytes() {
        val receive = coinbaseLikeReceive(p2wpkhScript(), 1000)
        val raw = Transaction.write(receive)
        assertEquals((receive.weight() + 3) / 4, txVirtualSize(raw))
        assertNull(txVirtualSize(byteArrayOf(0x01, 0x02)))
    }

    @Test
    fun receive_has_no_fee() {
        val script = p2wpkhScript()
        val receive = coinbaseLikeReceive(script, 1000)
        val txid = receive.txid.toString()
        val rows = listOf(TxRow(txid, 100, 0, Transaction.write(receive)))
        assertNull(feeForTx(txid, rows, listOf(script)))
    }

    @Test
    fun spend_of_our_coins_is_input_minus_outputs() {
        val script = p2wpkhScript()
        val receive = coinbaseLikeReceive(script, 1000)
        val receiveTxid = receive.txid.toString()
        val spend = knownOutpointSpend(receiveTxid, 900)
        val spendTxid = spend.txid.toString()
        val rows =
            listOf(
                TxRow(receiveTxid, 100, 0, Transaction.write(receive)),
                TxRow(spendTxid, 101, 0, Transaction.write(spend)),
            )
        val fee = feeForTx(spendTxid, rows, listOf(script))
        assertEquals(100, fee!!.feeSats)
        assertTrue(fee.vsize > 0)
        val expectedVsize = (spend.weight() + 3) / 4
        assertEquals(expectedVsize, fee.vsize)
        val scan = scanWatchTxs(rows, listOf(script))
        assertNull(scan.fees[receiveTxid])
        assertEquals(100, scan.fees[spendTxid]!!.feeSats)
    }

    @Test
    fun unknown_inputs_have_no_fee() {
        val script = p2wpkhScript()
        val spend = witnessSpend(watchPubkey0())
        val txid = spend.txid.toString()
        val rows = listOf(TxRow(txid, 101, 0, Transaction.write(spend)))
        assertNull(feeForTx(txid, rows, listOf(script)))
    }
}
