package io.bluewallet.blueberry.parse

import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxHash
import fr.acinq.bitcoin.TxIn
import fr.acinq.bitcoin.TxOut
import io.bluewallet.blueberry.wallet.addressFromOutputScript
import io.bluewallet.blueberry.wallet.outpointKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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

    @Test
    fun first_address_label_takes_lowest_labelled_vout() {
        val scripts = (1..4).map { p2wpkh(it) }
        val opReturn = byteArrayOf(0x6a, 0x01, 0x00)
        val tx = txPaying(listOf(scripts[0], opReturn, scripts[1], scripts[2], scripts[3]))
        val labels =
            mapOf(
                address(scripts[1]) to "   ",
                address(scripts[2]) to "  b  ",
                address(scripts[3]) to "a",
            )
        assertEquals("b", firstAddressLabel(tx, labels))
        assertNull(firstAddressLabel(tx, mapOf(address(p2wpkh(9)) to "other")))
        assertNull(firstAddressLabel(byteArrayOf(0x00, 0x01), labels))
    }
}

private fun p2wpkh(seed: Int): ByteArray = byteArrayOf(0x00, 0x14) + ByteArray(20) { seed.toByte() }

private fun address(script: ByteArray): String = addressFromOutputScript(script)!!

private fun txPaying(scripts: List<ByteArray>): ByteArray =
    Transaction.write(
        Transaction(
            2L,
            listOf(TxIn(OutPoint(TxHash(ByteArray(32)), 0L), 0xffffffffL)),
            scripts.map { TxOut(Satoshi(1_000L), it) },
            0L,
        ),
    )
