package io.bluewallet.blueberry.parse

/** Edges recorded during the linkage walk. Sorted once, so repeated runs share one path. */
internal class LinkGraphBuilder {
    private val coins = mutableMapOf<String, CoinGraphCoin>()
    private val txs = mutableMapOf<String, CoinGraphTx>()
    private val neighbors = mutableMapOf<LinkNode, MutableList<CoinLinkEdge>>()

    fun addCoin(coin: CoinGraphCoin) {
        coins[coin.outpoint] = coin
    }

    fun markSpent(outpoint: String) {
        val coin = coins[outpoint] ?: return
        coins[outpoint] = coin.copy(spent = true)
    }

    fun noteTx(
        txid: String,
        outputCount: Int,
    ) {
        txs.getOrPut(txid) { CoinGraphTx(txid, outputCount) }
    }

    fun linkInput(
        outpoint: String,
        txid: String,
    ) {
        addBoth(LinkNode.Coin(outpoint), LinkNode.Tx(txid), Relation.SpentIn, Relation.WhichSpent, warning = false)
    }

    fun linkChange(
        outpoint: String,
        txid: String,
        changeChain: Boolean,
    ) {
        val forward = if (changeChain) Relation.IsChangeFrom else Relation.WasPaidBy
        val back = if (changeChain) Relation.WhichMadeChange else Relation.WhichPaidYou
        addBoth(LinkNode.Coin(outpoint), LinkNode.Tx(txid), forward, back, warning = false)
    }

    fun linkSameAddress(outpoints: List<String>) {
        val sorted = outpoints.sorted()
        var i = 0
        while (i < sorted.size) {
            var j = i + 1
            while (j < sorted.size) {
                addBoth(
                    LinkNode.Coin(sorted[i]),
                    LinkNode.Coin(sorted[j]),
                    Relation.SameAddressAs,
                    Relation.SameAddressAs,
                    warning = true,
                )
                j++
            }
            i++
        }
    }

    fun build(): CoinLinkGraph {
        val sorted =
            neighbors.mapValues { (_, edges) ->
                edges.sortedWith(compareBy({ nodeRank(it.to) }, { nodeId(it.to) }, { it.relation.ordinal }))
            }
        return CoinLinkGraph(coins.toMap(), txs.toMap(), sorted)
    }

    private fun addBoth(
        a: LinkNode,
        b: LinkNode,
        relationAB: Relation,
        relationBA: Relation,
        warning: Boolean,
    ) {
        neighbors.getOrPut(a) { mutableListOf() }.add(CoinLinkEdge(b, relationAB, warning))
        neighbors.getOrPut(b) { mutableListOf() }.add(CoinLinkEdge(a, relationBA, warning))
    }
}

private fun nodeRank(node: LinkNode): Int = if (node is LinkNode.Tx) 0 else 1

private fun nodeId(node: LinkNode): String =
    when (node) {
        is LinkNode.Tx -> node.txid
        is LinkNode.Coin -> node.outpoint
    }
