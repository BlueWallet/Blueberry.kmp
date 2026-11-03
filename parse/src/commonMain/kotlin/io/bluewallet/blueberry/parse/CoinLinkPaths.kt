package io.bluewallet.blueberry.parse

private data class Hop(
    val parent: LinkNode,
    val relation: Relation,
    val warning: Boolean,
)

/**
 * Shortest paths from [origin] to [others], tx neighbours before coins, then id ascending.
 * [others] should already be the unspent coins in [origin]'s likely group.
 */
fun coinLinks(
    graph: CoinLinkGraph,
    origin: String,
    others: Collection<String>,
    copy: LinkCopy = LinkCopy(),
): List<CoinLink> {
    if (origin !in graph.coins) return emptyList()
    val prev = breadth(graph, LinkNode.Coin(origin))
    val links = mutableListOf<CoinLink>()
    for (other in others) {
        val goal = graph.coins[other]
        val steps =
            if (other != origin && goal != null) {
                render(graph, prev, LinkNode.Coin(other), copy)
            } else {
                null
            }
        if (goal != null && steps != null) {
            val reusedNode = steps.firstOrNull { it.warning && it.node is LinkNode.Coin }?.node as? LinkNode.Coin
            val reused =
                reusedNode?.let {
                    derivationAddressName(copy.paths[it.outpoint], graph.coins[it.outpoint]?.isChange == true)
                }
            links.add(
                CoinLink(
                    other = other,
                    valueSat = goal.valueSat,
                    hops = steps.size - 1,
                    summary = linkSummary(steps, reused),
                    steps = steps,
                    note = linkNote(steps, reused),
                ),
            )
        }
    }
    return links.sortedWith(compareBy<CoinLink> { it.hops }.thenByDescending { it.valueSat }.thenBy { it.other })
}

internal fun linkSummary(
    steps: List<LinkStep>,
    reusedAddress: String?,
): String =
    when {
        reusedAddress != null -> "Through reused $reusedAddress"
        steps.size == 3 -> "Created or spent in the same transaction"
        else -> "Through ${steps.count { it.node is LinkNode.Tx }} of your payments"
    }

internal fun linkNote(
    steps: List<LinkStep>,
    reusedAddress: String?,
): String {
    val other = steps.lastOrNull()?.title.orEmpty()
    val tx = steps.firstOrNull { it.node is LinkNode.Tx }?.title ?: "Payment"
    return if (reusedAddress != null) {
        "$reusedAddress received more than once. Anyone tracing this coin's history reaches it and finds $other too."
    } else {
        "“$tx” spent these coins together, so the chain ties them."
    }
}

private fun breadth(
    graph: CoinLinkGraph,
    start: LinkNode,
): Map<LinkNode, Hop?> {
    val prev = mutableMapOf<LinkNode, Hop?>(start to null)
    val queue = ArrayDeque<LinkNode>()
    queue.add(start)
    while (queue.isNotEmpty()) {
        val node = queue.removeFirst()
        for (edge in graph.neighbors[node].orEmpty()) {
            if (edge.to !in prev) {
                prev[edge.to] = Hop(node, edge.relation, edge.warning)
                queue.add(edge.to)
            }
        }
    }
    return prev
}

private fun chain(
    prev: Map<LinkNode, Hop?>,
    goal: LinkNode,
): List<LinkNode>? {
    if (goal !in prev) return null
    val nodes = mutableListOf<LinkNode>()
    var cursor = goal
    while (true) {
        nodes.add(cursor)
        val hop = prev[cursor]
        if (hop == null) break
        cursor = hop.parent
    }
    nodes.reverse()
    return nodes
}

private fun render(
    graph: CoinLinkGraph,
    prev: Map<LinkNode, Hop?>,
    goal: LinkNode,
    copy: LinkCopy,
): List<LinkStep>? {
    val nodes = chain(prev, goal) ?: return null
    return nodes.mapIndexed { index, node ->
        val hop = nodes.getOrNull(index + 1)?.let { prev[it] }
        LinkStep(
            node = node,
            relationToNext = hop?.relation,
            warning = hop?.warning == true,
            title = titleOf(node, index, graph, copy),
            subtitle = subtitleOf(node, graph, copy),
        )
    }
}

private fun titleOf(
    node: LinkNode,
    index: Int,
    graph: CoinLinkGraph,
    copy: LinkCopy,
): String {
    val title =
        when (node) {
            is LinkNode.Tx -> copy.txLabels[node.txid]?.trim()?.takeIf { it.isNotEmpty() } ?: "Payment"
            is LinkNode.Coin -> {
                val label = copy.labels[node.outpoint]?.trim().orEmpty()
                val coin = graph.coins[node.outpoint]
                val txLabel = coin?.let { copy.txLabels[it.txid]?.trim().orEmpty() }.orEmpty()
                when {
                    label.isNotEmpty() -> label
                    coin?.isChange == true && txLabel.isNotEmpty() -> "Change from $txLabel"
                    else -> "No label"
                }
            }
        }
    return if (index == 0 && node is LinkNode.Coin) "This coin" else title
}

private fun subtitleOf(
    node: LinkNode,
    graph: CoinLinkGraph,
    copy: LinkCopy,
): String =
    when (node) {
        is LinkNode.Tx -> {
            val date = copy.txDates[node.txid]?.trim().orEmpty()
            val short = shortTxid(node.txid, 4)
            if (date.isEmpty()) "Your payment · $short" else "Your payment · $date · $short"
        }
        is LinkNode.Coin -> {
            val coin = graph.coins[node.outpoint]
            val name = derivationAddressName(copy.paths[node.outpoint], coin?.isChange == true)
            if (coin == null || coin.spent) {
                "Spent · received at $name"
            } else {
                "${formatLinkAmount(coin.valueSat)} · unspent"
            }
        }
    }

/** `address #9` or `change address #75` from a derivation path. */
fun derivationAddressName(
    path: String?,
    isChange: Boolean,
): String {
    val noun = if (isChange) "change address" else "address"
    val index = path?.substringAfterLast('/')?.substringBefore('\'')?.toIntOrNull()
    return if (index == null) noun else "$noun #$index"
}
