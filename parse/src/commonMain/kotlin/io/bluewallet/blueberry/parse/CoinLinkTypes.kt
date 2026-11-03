package io.bluewallet.blueberry.parse

/** Why two nodes are tied, as read while walking from the previous node to this one. */
enum class Relation {
    SpentIn,
    WhichSpent,
    IsChangeFrom,
    WhichMadeChange,
    WasPaidBy,
    WhichPaidYou,
    SameAddressAs,
}

fun Relation.words(): String =
    when (this) {
        Relation.SpentIn -> "spent in"
        Relation.WhichSpent -> "which spent"
        Relation.IsChangeFrom -> "is change from"
        Relation.WhichMadeChange -> "which made change"
        Relation.WasPaidBy -> "was paid by"
        Relation.WhichPaidYou -> "which paid you"
        Relation.SameAddressAs -> "same address as"
    }

sealed interface LinkNode {
    data class Coin(
        val outpoint: String,
    ) : LinkNode

    data class Tx(
        val txid: String,
    ) : LinkNode
}

data class CoinGraphCoin(
    val outpoint: String,
    val txid: String,
    val valueSat: Long,
    val scriptHex: String,
    val isChange: Boolean,
    val spent: Boolean,
)

data class CoinGraphTx(
    val txid: String,
    val outputCount: Int,
)

data class CoinLinkEdge(
    val to: LinkNode,
    val relation: Relation,
    val warning: Boolean,
)

data class CoinLinkGraph(
    val coins: Map<String, CoinGraphCoin>,
    val txs: Map<String, CoinGraphTx>,
    val neighbors: Map<LinkNode, List<CoinLinkEdge>>,
) {
    companion object {
        val Empty = CoinLinkGraph(emptyMap(), emptyMap(), emptyMap())
    }
}

data class LinkStep(
    val node: LinkNode,
    val relationToNext: Relation?,
    val warning: Boolean,
    val title: String,
    val subtitle: String,
)

data class CoinLink(
    val other: String,
    val valueSat: Long,
    val hops: Int,
    val summary: String,
    val steps: List<LinkStep>,
    val note: String,
)

/** Labels and dates the graph does not store. Blank values fall back to the spec's titles. */
data class LinkCopy(
    val labels: Map<String, String> = emptyMap(),
    val txLabels: Map<String, String> = emptyMap(),
    val txDates: Map<String, String> = emptyMap(),
    val paths: Map<String, String> = emptyMap(),
)

data class CoinHistory(
    val linkage: CoinLinkage,
    val graph: CoinLinkGraph,
)

/** Eight decimals, always, for a path subtitle. */
fun formatLinkAmount(sats: Long): String {
    val whole = sats / 100_000_000L
    val frac = (kotlin.math.abs(sats % 100_000_000L)).toString().padStart(8, '0')
    val sign = if (sats < 0) "-" else ""
    return "$sign₿$whole.$frac"
}
