package io.bluewallet.blueberry.parse

import fr.acinq.bitcoin.Transaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BalanceFromTxsTest {
    @Test
    fun out_of_order_spend_before_receive_still_balances() {
        val pubkey = watchKey0()
        val script = p2wpkhScript(pubkey)
        val receive = coinbaseLikeReceive(script, 1000)
        val spend =
            Transaction(
                2L,
                listOf(
                    fr.acinq.bitcoin.TxIn(
                        fr.acinq.bitcoin.OutPoint(
                            fr.acinq.bitcoin.TxHash(
                                io.bluewallet.blueberry.wallet
                                    .hexToBytes(receive.txid.toString())
                                    .reversedArray(),
                            ),
                            0L,
                        ),
                        fr.acinq.bitcoin.ByteVector.empty,
                        0xffffffffL,
                        fr.acinq.bitcoin.ScriptWitness(
                            listOf(
                                fr.acinq.bitcoin.ByteVector(ByteArray(64)),
                                fr.acinq.bitcoin.ByteVector(pubkey.value.toByteArray()),
                            ),
                        ),
                    ),
                ),
                listOf(fr.acinq.bitcoin.TxOut(fr.acinq.bitcoin.Satoshi(900), unrelatedScript())),
                0L,
            )
        val rows =
            listOf(
                TxRow(spend.txid.toString(), 101, 0, Transaction.write(spend)),
                TxRow(receive.txid.toString(), 100, 0, Transaction.write(receive)),
            )
        assertEquals(BalanceSummary(0, 0), balanceFromTxs(rows, listOf(script)))
        val deltas = netDeltasForTxs(rows, listOf(script))
        assertEquals(1000, deltas[receive.txid.toString()])
        assertEquals(-1000, deltas[spend.txid.toString()])
    }

    @Test
    fun buildUtxoMap_records_height_and_spend_removes_prior_output() {
        val pubkey = watchKey0()
        val script = p2wpkhScript(pubkey)
        val receive =
            Transaction(
                2L,
                listOf(
                    fr.acinq.bitcoin.TxIn(
                        fr.acinq.bitcoin.OutPoint(fr.acinq.bitcoin.TxHash(ByteArray(32)), 0xffffffffL),
                        0xffffffffL,
                    ),
                ),
                listOf(
                    fr.acinq.bitcoin.TxOut(fr.acinq.bitcoin.Satoshi(1000), script),
                    fr.acinq.bitcoin.TxOut(fr.acinq.bitcoin.Satoshi(500), script),
                ),
                0L,
            )
        val map =
            buildUtxoMap(
                listOf(TxRow(receive.txid.toString(), 200, 0, Transaction.write(receive))),
                listOf(script),
            )
        assertEquals(1000, map[outpointKey(receive.txid.toString(), 0)]?.value)
        assertEquals(200, map[outpointKey(receive.txid.toString(), 0)]?.height)
        assertEquals(500, map[outpointKey(receive.txid.toString(), 1)]?.value)
        assertEquals(200, map[outpointKey(receive.txid.toString(), 1)]?.height)

        val spend = knownOutpointSpend(receive.txid.toString())
        val afterSpend =
            buildUtxoMap(
                listOf(
                    TxRow(receive.txid.toString(), 200, 0, Transaction.write(receive)),
                    TxRow(spend.txid.toString(), 201, 0, Transaction.write(spend)),
                ),
                listOf(script),
            )
        assertFalse(afterSpend.containsKey(outpointKey(receive.txid.toString(), 0)))
        assertEquals(500, afterSpend[outpointKey(receive.txid.toString(), 1)]?.value)
        assertEquals(200, afterSpend[outpointKey(receive.txid.toString(), 1)]?.height)
        assertTrue(afterSpend.containsKey(outpointKey(receive.txid.toString(), 1)))
    }
}
