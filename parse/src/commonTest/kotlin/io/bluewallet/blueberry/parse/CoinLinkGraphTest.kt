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
import kotlin.time.measureTime

class CoinLinkGraphTest {
    private val ext0 = p2wpkhScript(watchKey0("m/84'/0'/0'/0/0"))
    private val ext1 = p2wpkhScript(watchKey0("m/84'/0'/0'/0/1"))
    private val chg0 = p2wpkhScript(watchKey0("m/84'/0'/0'/1/0"))
    private val chg1 = p2wpkhScript(watchKey0("m/84'/0'/0'/1/1"))
    private val watch = listOf(ext0, ext1, chg0, chg1)
    private val change = listOf(chg0, chg1)

    @Test
    fun components_match_likely_and_change_is_an_edge() {
        val alone = coinbaseLikeReceive(ext0, 1_000, prevSalt = 1)
        assertPartitions(historyOf(listOf(alone)))

        val first = coinbaseLikeReceive(ext0, 1_000, prevSalt = 2)
        val second = coinbaseLikeReceive(ext0, 2_000, prevSalt = 3)
        assertPartitions(historyOf(listOf(first, second)))

        val spent = coinbaseLikeReceive(ext0, 60_000, prevSalt = 8)
        val sibling = coinbaseLikeReceive(ext0, 3_000, prevSalt = 9)
        val spend =
            spend(
                listOf(spent.txid.toString() to 0),
                listOf(unrelatedScript() to 50_000L, chg0 to 9_321L),
            )
        val linked = historyOf(listOf(spent, sibling, spend))
        assertPartitions(linked)
        val changeKey = outpointKey(spend.txid.toString(), 1)
        val edges = linked.graph.neighbors[LinkNode.Coin(changeKey)].orEmpty()
        assertTrue(edges.any { it.relation == Relation.IsChangeFrom && it.to is LinkNode.Tx })
        assertNotEquals(linked.linkage.certain[outpointKey(sibling.txid.toString(), 0)], linked.linkage.certain[changeKey])
    }

    @Test
    fun same_address_path_is_one_warned_hop() {
        val first = coinbaseLikeReceive(ext0, 2_000, prevSalt = 4)
        val second = coinbaseLikeReceive(ext0, 1_000, prevSalt = 5)
        val history = historyOf(listOf(first, second))
        val origin = outpointKey(first.txid.toString(), 0)
        val other = outpointKey(second.txid.toString(), 0)
        val copy = LinkCopy(paths = mapOf(origin to "m/84'/0'/0'/0/9", other to "m/84'/0'/0'/0/9"))
        val link = coinLinks(history.graph, origin, listOf(other), copy).single()
        assertEquals(1, link.hops)
        assertEquals(Relation.SameAddressAs, link.steps.first().relationToNext)
        assertTrue(link.steps.first().warning)
        assertEquals("This coin", link.steps.first().title)
        assertEquals("Through reused address #9", link.summary)
        assertTrue(link.note.startsWith("address #9 received more than once."))
        assertEquals(link, coinLinks(history.graph, origin, listOf(other), copy).single())
    }

    @Test
    fun large_change_stays_unlinked_while_reused_descendants_share_a_path() {
        val salaryAug = coinbaseLikeReceive(ext0, 120_000, prevSalt = 11)
        val giftJul = coinbaseLikeReceive(ext1, 150_000, prevSalt = 12)
        val salarySept = coinbaseLikeReceive(ext0, 112_400, prevSalt = 13)
        val soldBike = coinbaseLikeReceive(ext1, 60_000, prevSalt = 14)
        val bike =
            spend(
                listOf(salaryAug.txid.toString() to 0, giftJul.txid.toString() to 0),
                listOf(unrelatedScript() to 41_000L, chg0 to 228_343L),
            )
        val coffee =
            spend(
                listOf(soldBike.txid.toString() to 0),
                listOf(unrelatedScript() to 54_500L, chg1 to 5_146L),
            )
        val history = historyOf(listOf(salaryAug, giftJul, salarySept, soldBike, bike, coffee))
        assertPartitions(history)
        val bikeChange = outpointKey(bike.txid.toString(), 1)
        val sept = outpointKey(salarySept.txid.toString(), 0)
        val coffeeChange = outpointKey(coffee.txid.toString(), 1)
        assertNotEquals(history.linkage.likely[bikeChange], history.linkage.likely[sept])
        assertEquals(history.linkage.likely[sept], history.linkage.likely[coffeeChange])
        val copy =
            LinkCopy(
                labels =
                    mapOf(
                        sept to "Salary Sept",
                        outpointKey(salaryAug.txid.toString(), 0) to "Salary Aug",
                        outpointKey(giftJul.txid.toString(), 0) to "Gift Jul",
                        outpointKey(soldBike.txid.toString(), 0) to "Sold bike",
                    ),
                txLabels = mapOf(bike.txid.toString() to "Bike parts", coffee.txid.toString() to "Coffee"),
                paths =
                    mapOf(
                        sept to "m/84'/0'/0'/0/9",
                        outpointKey(salaryAug.txid.toString(), 0) to "m/84'/0'/0'/0/9",
                        outpointKey(giftJul.txid.toString(), 0) to "m/84'/0'/0'/0/4",
                        outpointKey(soldBike.txid.toString(), 0) to "m/84'/0'/0'/0/4",
                    ),
            )
        val link = coinLinks(history.graph, sept, listOf(coffeeChange), copy).single()
        val path = listOf(Relation.SameAddressAs, Relation.SpentIn, Relation.WhichSpent, Relation.SameAddressAs, Relation.SpentIn, Relation.WhichMadeChange, null)
        assertEquals(path, link.steps.map { it.relationToNext })
        assertEquals(listOf(0, 3), link.steps.indices.filter { link.steps[it].warning })
        assertEquals("Through reused address #9", link.summary)
        assertEquals("This coin", link.steps.first().title)
        assertEquals("Salary Aug", link.steps[1].title)
        assertEquals(link, coinLinks(history.graph, sept, listOf(coffeeChange), copy).single())
    }

    @Test
    fun summary_templates_and_a_thousand_tx_history() {
        val twoHop =
            listOf(
                LinkStep(LinkNode.Coin("a:0"), Relation.IsChangeFrom, false, "This coin", "s"),
                LinkStep(LinkNode.Tx("aa"), Relation.WhichSpent, false, "Rent", "s"),
                LinkStep(LinkNode.Coin("b:0"), null, false, "Change", "s"),
            )
        assertEquals("Created or spent in the same transaction", linkSummary(twoHop, null))
        assertEquals("“Rent” spent these coins together, so the chain ties them.", linkNote(twoHop, null))
        val longer = twoHop + LinkStep(LinkNode.Tx("bb"), null, false, "Payment", "s")
        assertEquals("Through 2 of your payments", linkSummary(longer, null))

        val scripts = (0 until 20).map { p2wpkhScript(watchKey0("m/84'/0'/0'/0/$it")) }
        val txs =
            scripts.flatMapIndexed { scriptIndex, script ->
                List(50) { n ->
                    coinbaseLikeReceive(script, 1_000L + scriptIndex * 50 + n, prevSalt = n.toByte())
                }
            }
        val history = historyOf(txs, scripts, emptyList())
        assertEquals(1_000, history.graph.coins.size)
        assertPartitions(history)
        val origin =
            history.graph.coins.values
                .first { !it.spent }
                .outpoint
        val root = history.linkage.likely.getValue(origin)
        val others =
            history.linkage.likely
                .filterValues { it == root }
                .keys
                .filter { it != origin }
        coinLinks(history.graph, origin, others)
        val took = measureTime { coinLinks(history.graph, origin, others) }
        assertTrue(took.inWholeMilliseconds < 50, "paths took ${took.inWholeMilliseconds} ms")
    }

    private fun assertPartitions(history: CoinHistory) {
        val components = history.graph.unspentComponents()
        val likely = history.linkage.likely
        assertEquals(likely.keys, components.keys)
        val keys = likely.keys.toList()
        for (left in keys.indices) {
            for (right in left + 1 until keys.size) {
                val sameLikely = likely[keys[left]] == likely[keys[right]]
                val sameGraph = components[keys[left]] == components[keys[right]]
                assertEquals(sameLikely, sameGraph)
            }
        }
    }

    private fun historyOf(
        txs: List<Transaction>,
        watchScripts: List<ByteArray> = watch,
        changeScripts: List<ByteArray> = change,
    ): CoinHistory {
        val rows = txs.mapIndexed { i, tx -> TxRow(tx.txid.toString(), 100 + i, 0, Transaction.write(tx)) }
        return computeCoinHistory(rows, watchScripts, changeScripts)
    }
}

private fun spend(
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
