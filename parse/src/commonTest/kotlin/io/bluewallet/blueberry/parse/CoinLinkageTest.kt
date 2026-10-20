package io.bluewallet.blueberry.parse

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.ScriptWitness
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxHash
import fr.acinq.bitcoin.TxIn
import fr.acinq.bitcoin.TxOut
import io.bluewallet.blueberry.wallet.hexToBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class CoinLinkageTest {
    private val ext0 = p2wpkhScript(watchKey0("m/84'/0'/0'/0/0"))
    private val ext1 = p2wpkhScript(watchKey0("m/84'/0'/0'/0/1"))
    private val ext2 = p2wpkhScript(watchKey0("m/84'/0'/0'/0/2"))
    private val chg0 = p2wpkhScript(watchKey0("m/84'/0'/0'/1/0"))
    private val receiveScripts = listOf(ext0, ext1, ext2)
    private val changeScripts = listOf(chg0)
    private val taprootPayee = byteArrayOf(0x51, 0x20) + ByteArray(32) { 7 }

    private fun linkageOf(vararg txs: Transaction): CoinLinkage =
        computeCoinLinkage(
            txs.mapIndexed { i, tx -> TxRow(tx.txid.toString(), 100 + i, 0, Transaction.write(tx)) },
            receiveScripts + changeScripts,
            changeScripts,
        )

    private fun key(
        tx: Transaction,
        vout: Int,
    ) = outpointKey(tx.txid.toString(), vout)

    @Test
    fun external_receive_never_spent_with_others_is_independent() {
        val receive = coinbaseLikeReceive(ext0, 1000, prevSalt = 1)
        val k = key(receive, 0)
        assertEquals(CoinLinkage(mapOf(k to k), mapOf(k to k)), linkageOf(receive))
    }

    @Test
    fun same_address_outputs_are_certainly_linked() {
        val first = coinbaseLikeReceive(ext0, 1_000, prevSalt = 2)
        val second = coinbaseLikeReceive(ext0, 2_000, prevSalt = 3)
        val l = linkageOf(first, second)
        assertEquals(l.certain[key(first, 0)], l.certain[key(second, 0)])
    }

    @Test
    fun common_inputs_certainly_link_coins_reusing_their_addresses() {
        val a = coinbaseLikeReceive(ext0, 8_000, prevSalt = 4)
        val b = coinbaseLikeReceive(ext1, 7_000, prevSalt = 5)
        val liveA = coinbaseLikeReceive(ext0, 1_000, prevSalt = 6)
        val liveB = coinbaseLikeReceive(ext1, 1_000, prevSalt = 7)
        val merge =
            spendOwned(
                inputs = listOf(a.txid.toString() to 0, b.txid.toString() to 0),
                outputs = listOf(unrelatedScript() to 10_000L),
            )
        val l = linkageOf(a, b, liveA, liveB, merge)
        assertEquals(l.certain[key(liveA, 0)], l.certain[key(liveB, 0)])
    }

    @Test
    fun round_payment_marks_the_other_output_as_likely_change() {
        val spent = coinbaseLikeReceive(ext0, 60_000, prevSalt = 8)
        val sibling = coinbaseLikeReceive(ext0, 3_000, prevSalt = 9)
        val spend =
            spendOwned(
                inputs = listOf(spent.txid.toString() to 0),
                outputs = listOf(unrelatedScript() to 50_000L, chg0 to 9_321L),
            )
        val l = linkageOf(spent, sibling, spend)
        assertEquals(l.likely[key(sibling, 0)], l.likely[key(spend, 1)])
        assertNotEquals(l.certain[key(sibling, 0)], l.certain[key(spend, 1)])
    }

    @Test
    fun round_wallet_change_reads_as_the_payment_so_it_stays_unlinked() {
        val spent = coinbaseLikeReceive(ext0, 100_000, prevSalt = 10)
        val sibling = coinbaseLikeReceive(ext0, 3_000, prevSalt = 11)
        val spend =
            spendOwned(
                inputs = listOf(spent.txid.toString() to 0),
                outputs = listOf(chg0 to 50_000L, unrelatedScript() to 43_210L),
            )
        val l = linkageOf(spent, sibling, spend)
        assertNotEquals(l.likely[key(sibling, 0)], l.likely[key(spend, 0)])
    }

    @Test
    fun output_matching_the_input_script_type_is_likely_change() {
        val spent = coinbaseLikeReceive(ext0, 50_000, prevSalt = 12)
        val sibling = coinbaseLikeReceive(ext0, 3_000, prevSalt = 13)
        val spend =
            spendOwned(
                inputs = listOf(spent.txid.toString() to 0),
                outputs = listOf(taprootPayee to 31_234L, ext2 to 12_345L),
            )
        val l = linkageOf(spent, sibling, spend)
        assertEquals(l.likely[key(sibling, 0)], l.likely[key(spend, 1)])
    }

    @Test
    fun output_larger_than_an_input_is_the_payment_not_change() {
        val a = coinbaseLikeReceive(ext0, 8_000, prevSalt = 14)
        val b = coinbaseLikeReceive(ext1, 7_000, prevSalt = 15)
        val liveA = coinbaseLikeReceive(ext0, 1_000, prevSalt = 16)
        val spend =
            spendOwned(
                inputs = listOf(a.txid.toString() to 0, b.txid.toString() to 0),
                outputs = listOf(chg0 to 9_000L, unrelatedScript() to 5_123L),
            )
        val l = linkageOf(a, b, liveA, spend)
        assertNotEquals(l.likely[key(liveA, 0)], l.likely[key(spend, 0)])
    }

    @Test
    fun output_to_a_previously_seen_address_is_the_payment() {
        val spent = coinbaseLikeReceive(ext0, 50_000, prevSalt = 17)
        val sibling = coinbaseLikeReceive(ext0, 3_000, prevSalt = 18)
        // The wallet once paid this external address, so paying it again reads as a payment.
        val earlier =
            spendOwned(
                inputs = listOf(sibling.txid.toString() to 0),
                outputs = listOf(unrelatedScript() to 2_500L),
            )
        val liveSibling = coinbaseLikeReceive(ext0, 4_000, prevSalt = 19)
        val spend =
            spendOwned(
                inputs = listOf(spent.txid.toString() to 0),
                outputs = listOf(unrelatedScript() to 12_345L, ext2 to 31_234L),
            )
        val l = linkageOf(spent, sibling, earlier, liveSibling, spend)
        assertEquals(l.likely[key(liveSibling, 0)], l.likely[key(spend, 1)])
    }

    @Test
    fun ambiguous_spend_falls_back_to_the_wallet_change_output() {
        val spent = coinbaseLikeReceive(ext0, 50_000, prevSalt = 20)
        val sibling = coinbaseLikeReceive(ext0, 3_000, prevSalt = 21)
        val spend =
            spendOwned(
                inputs = listOf(spent.txid.toString() to 0),
                outputs = listOf(ext2 to 12_345L, chg0 to 31_234L),
            )
        val l = linkageOf(spent, sibling, spend)
        assertEquals(l.likely[key(sibling, 0)], l.likely[key(spend, 1)])
        assertNotEquals(l.likely[key(sibling, 0)], l.likely[key(spend, 0)])
    }

    @Test
    fun groups_are_deterministic_for_same_history() {
        val a = coinbaseLikeReceive(ext0, 1_000, prevSalt = 22)
        val b = coinbaseLikeReceive(ext0, 2_000, prevSalt = 23)
        val rows =
            listOf(
                TxRow(a.txid.toString(), 100, 0, Transaction.write(a)),
                TxRow(b.txid.toString(), 101, 0, Transaction.write(b)),
            )
        val l1 = computeCoinLinkage(rows, listOf(ext0), emptyList())
        assertEquals(l1, computeCoinLinkage(rows, listOf(ext0), emptyList()))
        assertEquals(l1, computeCoinLinkage(rows.reversed(), listOf(ext0), emptyList()))
    }

    @Test
    fun different_scripts_without_shared_spend_stay_separate() {
        val a = coinbaseLikeReceive(ext0, 1_000, prevSalt = 24)
        val b = coinbaseLikeReceive(ext1, 2_000, prevSalt = 25)
        val l = linkageOf(a, b)
        assertNotEquals(l.likely[key(a, 0)], l.likely[key(b, 0)])
    }
}

private fun spendOwned(
    inputs: List<Pair<String, Int>>,
    outputs: List<Pair<ByteArray, Long>>,
): Transaction =
    Transaction(
        2L,
        inputs.map { (txid, vout) ->
            TxIn(
                OutPoint(TxHash(hexToBytes(txid).reversedArray()), vout.toLong()),
                ByteVector.empty,
                0xffffffffL,
                ScriptWitness(listOf(ByteVector(ByteArray(64)), ByteVector(ByteArray(33)))),
            )
        },
        outputs.map { (script, value) -> TxOut(Satoshi(value), script) },
        0L,
    )
