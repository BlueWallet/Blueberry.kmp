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
    private val chg1 = p2wpkhScript(watchKey0("m/84'/0'/0'/1/1"))
    private val chg2 = p2wpkhScript(watchKey0("m/84'/0'/0'/1/2"))
    private val receiveScripts = listOf(ext0, ext1, ext2)
    private val changeScripts = listOf(chg0, chg1, chg2)

    private fun groupsOf(vararg txs: Transaction): Map<String, String> =
        computeCoinGroups(
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
        assertEquals(mapOf(key(receive, 0) to key(receive, 0)), groupsOf(receive))
    }

    @Test
    fun change_joins_cluster_of_the_input_that_funded_it() {
        val spent = coinbaseLikeReceive(ext0, 10_000, prevSalt = 2)
        // Reuses the spent coin's address, so it already shares its cluster.
        val sibling = coinbaseLikeReceive(ext0, 3_000, prevSalt = 3)
        val spend =
            spendOwned(
                inputs = listOf(spent.txid.toString() to 0),
                outputs = listOf(unrelatedScript() to 5_000L, chg0 to 4_500L),
            )
        val groups = groupsOf(spent, sibling, spend)
        assertEquals(setOf(key(sibling, 0), key(spend, 1)), groups.keys)
        assertEquals(groups[key(sibling, 0)], groups[key(spend, 1)])
    }

    @Test
    fun spending_two_groups_merges_them_including_later_change() {
        val a = coinbaseLikeReceive(ext0, 8_000, prevSalt = 4)
        val b = coinbaseLikeReceive(ext1, 7_000, prevSalt = 5)
        val liveA = coinbaseLikeReceive(ext0, 1_000, prevSalt = 6)
        val liveB = coinbaseLikeReceive(ext1, 1_000, prevSalt = 7)
        val merge =
            spendOwned(
                inputs = listOf(a.txid.toString() to 0, b.txid.toString() to 0),
                outputs = listOf(unrelatedScript() to 10_000L, chg0 to 4_500L),
            )
        val groups = groupsOf(a, b, liveA, liveB, merge)
        assertEquals(setOf(key(liveA, 0), key(liveB, 0), key(merge, 1)), groups.keys)
        assertEquals(1, groups.values.toSet().size)
    }

    @Test
    fun same_address_outputs_linked_even_if_never_spent() {
        val first = coinbaseLikeReceive(ext0, 1_000, prevSalt = 8)
        val second = coinbaseLikeReceive(ext0, 2_000, prevSalt = 9)
        val groups = groupsOf(first, second)
        assertEquals(2, groups.size)
        assertEquals(groups[key(first, 0)], groups[key(second, 0)])
    }

    @Test
    fun change_outputs_of_one_spend_share_a_group_through_spent_inputs() {
        val a = coinbaseLikeReceive(ext0, 8_000, prevSalt = 10)
        val b = coinbaseLikeReceive(ext1, 7_000, prevSalt = 11)
        val merge =
            spendOwned(
                inputs = listOf(a.txid.toString() to 0, b.txid.toString() to 0),
                outputs = listOf(unrelatedScript() to 1_000L, chg0 to 4_000L, chg1 to 5_000L),
            )
        val groups = groupsOf(a, b, merge)
        assertEquals(setOf(key(merge, 1), key(merge, 2)), groups.keys)
        assertEquals(groups[key(merge, 1)], groups[key(merge, 2)])
    }

    @Test
    fun wallet_output_on_receive_script_is_not_linked_to_the_spend() {
        val a = coinbaseLikeReceive(ext0, 8_000, prevSalt = 12)
        val spend =
            spendOwned(
                inputs = listOf(a.txid.toString() to 0),
                outputs = listOf(ext2 to 3_000L, chg0 to 4_000L),
            )
        val groups = groupsOf(a, spend)
        assertEquals(setOf(key(spend, 0), key(spend, 1)), groups.keys)
        assertNotEquals(groups[key(spend, 0)], groups[key(spend, 1)])
    }

    @Test
    fun live_coins_on_fresh_scripts_link_only_through_spent_parents() {
        val r1 = coinbaseLikeReceive(ext0, 5_000, prevSalt = 13)
        val r2 = coinbaseLikeReceive(ext1, 6_000, prevSalt = 14)
        val s1 =
            spendOwned(
                inputs = listOf(r1.txid.toString() to 0),
                outputs = listOf(unrelatedScript() to 1_000L, chg0 to 1_500L, chg1 to 2_000L),
            )
        // r2 comes first, so the spent change reaches r2 only through the common-input union.
        val merge =
            spendOwned(
                inputs = listOf(r2.txid.toString() to 0, s1.txid.toString() to 2),
                outputs = listOf(unrelatedScript() to 6_000L, chg2 to 1_500L),
            )
        val groups = groupsOf(r1, r2, s1, merge)
        assertEquals(setOf(key(s1, 1), key(merge, 1)), groups.keys)
        assertEquals(groups[key(s1, 1)], groups[key(merge, 1)])
    }

    @Test
    fun groups_are_deterministic_for_same_history() {
        val a = coinbaseLikeReceive(ext0, 1_000, prevSalt = 15)
        val b = coinbaseLikeReceive(ext0, 2_000, prevSalt = 16)
        val rows =
            listOf(
                TxRow(a.txid.toString(), 100, 0, Transaction.write(a)),
                TxRow(b.txid.toString(), 101, 0, Transaction.write(b)),
            )
        val g1 = computeCoinGroups(rows, listOf(ext0), emptyList())
        val g2 = computeCoinGroups(rows, listOf(ext0), emptyList())
        assertEquals(g1, g2)
        val g3 = computeCoinGroups(rows.reversed(), listOf(ext0), emptyList())
        assertEquals(g1, g3)
    }

    @Test
    fun different_scripts_without_shared_spend_stay_separate() {
        val a = coinbaseLikeReceive(ext0, 1_000, prevSalt = 17)
        val b = coinbaseLikeReceive(ext1, 2_000, prevSalt = 18)
        val groups = groupsOf(a, b)
        assertNotEquals(groups[key(a, 0)], groups[key(b, 0)])
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
