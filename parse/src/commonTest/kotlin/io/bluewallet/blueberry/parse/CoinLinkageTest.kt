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
import kotlin.test.assertTrue

class CoinLinkageTest {
    @Test
    fun external_receive_never_spent_with_others_is_independent() {
        val script = p2wpkhScript()
        val receive = coinbaseLikeReceive(script, 1000, prevSalt = 1)
        val key = outpointKey(receive.txid.toString(), 0)
        val groups =
            computeCoinGroups(
                listOf(TxRow(receive.txid.toString(), 100, 0, Transaction.write(receive))),
                listOf(script),
            )
        assertEquals(mapOf(key to key), groups)
    }

    @Test
    fun change_joins_group_of_every_wallet_input_spent() {
        val script = p2wpkhScript()
        val receive = coinbaseLikeReceive(script, 10_000, prevSalt = 2)
        val spend =
            spendOwned(
                inputs = listOf(receive.txid.toString() to 0),
                outputs =
                    listOf(
                        unrelatedScript() to 5_000L,
                        script to 4_500L,
                    ),
            )
        val changeKey = outpointKey(spend.txid.toString(), 1)
        val groups =
            computeCoinGroups(
                listOf(
                    TxRow(receive.txid.toString(), 100, 0, Transaction.write(receive)),
                    TxRow(spend.txid.toString(), 101, 0, Transaction.write(spend)),
                ),
                listOf(script),
            )
        assertEquals(1, groups.size)
        assertTrue(groups.containsKey(changeKey))
        // Spent receive is gone from unspent map but linked the change.
        assertEquals(groups[changeKey], groups[changeKey])
    }

    @Test
    fun spending_two_groups_merges_them_including_later_change() {
        val scriptA = p2wpkhScript(watchKey0("m/84'/0'/0'/0/0"))
        val scriptB = p2wpkhScript(watchKey0("m/84'/0'/0'/0/1"))
        val a = coinbaseLikeReceive(scriptA, 8_000, prevSalt = 3)
        val b = coinbaseLikeReceive(scriptB, 7_000, prevSalt = 4)
        val merge =
            spendOwned(
                inputs =
                    listOf(
                        a.txid.toString() to 0,
                        b.txid.toString() to 0,
                    ),
                outputs =
                    listOf(
                        unrelatedScript() to 10_000L,
                        scriptA to 4_500L,
                    ),
            )
        val changeKey = outpointKey(merge.txid.toString(), 1)
        val groups =
            computeCoinGroups(
                listOf(
                    TxRow(a.txid.toString(), 100, 0, Transaction.write(a)),
                    TxRow(b.txid.toString(), 100, 1, Transaction.write(b)),
                    TxRow(merge.txid.toString(), 101, 0, Transaction.write(merge)),
                ),
                listOf(scriptA, scriptB),
            )
        assertEquals(1, groups.size)
        assertTrue(groups.containsKey(changeKey))
    }

    @Test
    fun same_address_outputs_linked_even_if_never_spent() {
        val script = p2wpkhScript()
        val first = coinbaseLikeReceive(script, 1_000, prevSalt = 5)
        val second = coinbaseLikeReceive(script, 2_000, prevSalt = 6)
        val k1 = outpointKey(first.txid.toString(), 0)
        val k2 = outpointKey(second.txid.toString(), 0)
        val groups =
            computeCoinGroups(
                listOf(
                    TxRow(first.txid.toString(), 100, 0, Transaction.write(first)),
                    TxRow(second.txid.toString(), 101, 0, Transaction.write(second)),
                ),
                listOf(script),
            )
        assertEquals(2, groups.size)
        assertEquals(groups[k1], groups[k2])
    }

    @Test
    fun spent_coins_absent_from_result_but_still_connect_groups() {
        val scriptExt = p2wpkhScript(watchKey0("m/84'/0'/0'/0/0"))
        val scriptChange = p2wpkhScript(watchKey0("m/84'/0'/0'/1/0"))
        // Two independent receives on different scripts (no address reuse).
        val r1 = coinbaseLikeReceive(scriptExt, 5_000, prevSalt = 7)
        val r2 = coinbaseLikeReceive(scriptChange, 6_000, prevSalt = 8)
        // Spend only r1 → change on scriptChange (different address).
        val s1 =
            spendOwned(
                inputs = listOf(r1.txid.toString() to 0),
                outputs =
                    listOf(
                        unrelatedScript() to 1_000L,
                        scriptChange to 3_500L,
                    ),
            )
        val change1 = outpointKey(s1.txid.toString(), 1)
        // Later spend change1 + r2 together → merges histories via spent change1.
        val merge =
            spendOwned(
                inputs =
                    listOf(
                        s1.txid.toString() to 1,
                        r2.txid.toString() to 0,
                    ),
                outputs =
                    listOf(
                        unrelatedScript() to 8_000L,
                        scriptExt to 1_000L,
                    ),
            )
        val finalChange = outpointKey(merge.txid.toString(), 1)
        val groups =
            computeCoinGroups(
                listOf(
                    TxRow(r1.txid.toString(), 100, 0, Transaction.write(r1)),
                    TxRow(r2.txid.toString(), 100, 1, Transaction.write(r2)),
                    TxRow(s1.txid.toString(), 101, 0, Transaction.write(s1)),
                    TxRow(merge.txid.toString(), 102, 0, Transaction.write(merge)),
                ),
                listOf(scriptExt, scriptChange),
            )
        assertEquals(setOf(finalChange), groups.keys)
        assertTrue(groups.containsKey(finalChange))
        assertTrue(!groups.containsKey(outpointKey(r1.txid.toString(), 0)))
        assertTrue(!groups.containsKey(change1))
    }

    @Test
    fun groups_are_deterministic_for_same_history() {
        val script = p2wpkhScript()
        val a = coinbaseLikeReceive(script, 1_000, prevSalt = 9)
        val b = coinbaseLikeReceive(script, 2_000, prevSalt = 10)
        val rows =
            listOf(
                TxRow(a.txid.toString(), 100, 0, Transaction.write(a)),
                TxRow(b.txid.toString(), 101, 0, Transaction.write(b)),
            )
        val g1 = computeCoinGroups(rows, listOf(script))
        val g2 = computeCoinGroups(rows, listOf(script))
        assertEquals(g1, g2)
        // Reversed row order (before sort) still same.
        val g3 = computeCoinGroups(rows.reversed(), listOf(script))
        assertEquals(g1, g3)
    }

    @Test
    fun different_scripts_without_shared_spend_stay_separate() {
        val s1 = p2wpkhScript(watchKey0("m/84'/0'/0'/0/0"))
        val s2 = p2wpkhScript(watchKey0("m/84'/0'/0'/0/1"))
        val a = coinbaseLikeReceive(s1, 1_000, prevSalt = 11)
        val b = coinbaseLikeReceive(s2, 2_000, prevSalt = 12)
        val k1 = outpointKey(a.txid.toString(), 0)
        val k2 = outpointKey(b.txid.toString(), 0)
        val groups =
            computeCoinGroups(
                listOf(
                    TxRow(a.txid.toString(), 100, 0, Transaction.write(a)),
                    TxRow(b.txid.toString(), 100, 1, Transaction.write(b)),
                ),
                listOf(s1, s2),
            )
        assertNotEquals(groups[k1], groups[k2])
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
