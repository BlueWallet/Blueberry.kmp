package io.bluewallet.blueberry.parse

import fr.acinq.bitcoin.Transaction

/**
 * Union-find linkage over the wallet's own history.
 *
 * Two coins are linked when an observer can already tell from the chain that the same wallet
 * owns both (common-input ownership, change, or address reuse). Only outputs paying one of
 * [changeScripts] join the cluster of the inputs that funded them; a wallet output on a receive
 * script is not attributable to the spender, so it stays in its own group.
 *
 * @return map of **unspent** outpoint key → deterministic group id (union-find root key).
 */
fun computeCoinGroups(
    txs: List<TxRow>,
    watchScripts: List<ByteArray>,
    changeScripts: List<ByteArray>,
): Map<String, String> {
    val ctx =
        LinkageCtx(
            watch = watchScripts.map(::scriptHex).toSet(),
            change = changeScripts.map(::scriptHex).toSet(),
            uf = UnionFind(),
            live = mutableMapOf(),
            ownedScriptByOutpoint = linkedMapOf(),
            outpointsByScript = mutableMapOf(),
        )
    for (row in txs.sortedWith(compareBy({ it.height }, { it.txIndex }))) {
        ctx.applyTx(row)
    }
    ctx.unionAddressReuse()
    return ctx.live.keys.associateWith { ctx.uf.find(it) }
}

private class LinkageCtx(
    val watch: Set<String>,
    val change: Set<String>,
    val uf: UnionFind,
    val live: MutableMap<String, WatchUtxo>,
    val ownedScriptByOutpoint: MutableMap<String, String>,
    val outpointsByScript: MutableMap<String, MutableList<String>>,
) {
    fun applyTx(row: TxRow) {
        val tx = Transaction.read(row.tx)
        val ownedInputs = ownedInputKeys(tx)
        val changeOutputs = mutableListOf<String>()
        tx.txOut.forEachIndexed { vout, o ->
            val script = o.publicKeyScript.toByteArray()
            val scriptH = scriptHex(script)
            if (scriptH !in watch) return@forEachIndexed
            val key = outpointKey(tx.txid.toString(), vout)
            if (scriptH in change) changeOutputs.add(key)
            uf.add(key)
            ownedScriptByOutpoint[key] = scriptH
            outpointsByScript.getOrPut(scriptH) { mutableListOf() }.add(key)
            live[key] = WatchUtxo(o.amount.toLong(), script, row.height)
        }
        if (!tx.isCoinbase()) {
            for (inn in tx.txIn) live.remove(prevoutKey(inn))
        }
        if (ownedInputs.isNotEmpty()) {
            for (k in ownedInputs) uf.add(k)
            val anchor = ownedInputs.first()
            for (k in ownedInputs.drop(1)) uf.union(anchor, k)
            for (k in changeOutputs) uf.union(anchor, k)
        }
    }

    private fun ownedInputKeys(tx: Transaction): List<String> {
        if (tx.isCoinbase()) return emptyList()
        return tx.txIn.mapNotNull { inn ->
            val key = prevoutKey(inn)
            key.takeIf { live.containsKey(it) || ownedScriptByOutpoint.containsKey(it) }
        }
    }

    fun unionAddressReuse() {
        for (keys in outpointsByScript.values) {
            if (keys.size < 2) continue
            val anchor = keys.first()
            for (k in keys.drop(1)) uf.union(anchor, k)
        }
    }
}

internal class UnionFind {
    private val parent = mutableMapOf<String, String>()

    fun add(x: String) {
        if (!parent.containsKey(x)) parent[x] = x
    }

    fun find(x: String): String {
        add(x)
        var root = x
        while (parent[root] != root) root = parent[root]!!
        var cur = x
        while (cur != root) {
            val next = parent[cur]!!
            parent[cur] = root
            cur = next
        }
        return root
    }

    fun union(
        a: String,
        b: String,
    ) {
        val ra = find(a)
        val rb = find(b)
        if (ra == rb) return
        if (ra < rb) parent[rb] = ra else parent[ra] = rb
    }
}
