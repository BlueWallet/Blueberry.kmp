package io.bluewallet.blueberry.parse

import fr.acinq.bitcoin.Transaction

/**
 * Linkage of **unspent** outpoints, as union-find roots (deterministic group ids).
 *
 * [certain] holds links anyone can prove from the chain: inputs spent together and outputs that
 * reuse an address. [likely] adds, per spend, the output a chain observer would pick as change
 * (see [guessChangeVout]); it is always a coarsening of [certain].
 */
data class CoinLinkage(
    val certain: Map<String, String>,
    val likely: Map<String, String>,
)

/**
 * @param changeScripts the wallet's own change chain; used only when an observer's heuristics
 *   cannot tell the outputs apart, so the ambiguous case errs toward warning.
 */
fun computeCoinLinkage(
    txs: List<TxRow>,
    watchScripts: List<ByteArray>,
    changeScripts: List<ByteArray>,
): CoinLinkage {
    val ctx =
        LinkageCtx(
            watch = watchScripts.map(::scriptHex).toSet(),
            change = changeScripts.map(::scriptHex).toSet(),
        )
    for (row in txs.sortedWith(compareBy({ it.height }, { it.txIndex }))) {
        ctx.applyTx(row)
    }
    ctx.unionAddressReuse()
    return CoinLinkage(
        certain = ctx.live.keys.associateWith { ctx.certain.find(it) },
        likely = ctx.live.keys.associateWith { ctx.likely.find(it) },
    )
}

/** Output an observer sees in a spend; [owned] and [walletChange] are wallet-only knowledge. */
internal data class SpendOutput(
    val valueSats: Long,
    val scriptHex: String,
    val owned: Boolean,
    val walletChange: Boolean,
)

/** Wallet input of a spend, with the value and script of the coin it consumes. */
internal data class SpendInput(
    val valueSats: Long,
    val scriptHex: String,
)

/** A payment amount picked by a person: a multiple of 0.0001 BTC. */
private const val ROUND_SATS = 10_000L

/**
 * Vout a chain observer would call change, or null when the spend has none or nothing narrows it.
 *
 * Candidates are narrowed in order, and a step that would leave none is skipped:
 * an output paying an input's own address is change outright; outputs to addresses seen before
 * are payments; an output at least as large as the smallest input is a payment (that input would
 * have been unnecessary); outputs whose script type differs from the inputs' are payments; round
 * amounts are payments. Rules that read the inputs need every input, so they are skipped when
 * [allInputsOwned] is false and the foreign inputs' scripts and values are unknown.
 */
internal fun guessChangeVout(
    inputs: List<SpendInput>,
    allInputsOwned: Boolean,
    outputs: List<SpendOutput>,
    seenScripts: Set<String>,
): Int? {
    if (outputs.size < 2) return null
    val inputScripts = inputs.map { it.scriptHex }.toSet()
    val toInputAddress = if (allInputsOwned) outputs.indexOfFirst { it.scriptHex in inputScripts } else -1
    val candidates =
        if (toInputAddress >= 0) {
            listOf(toInputAddress)
        } else {
            narrowChangeCandidates(inputs, allInputsOwned, outputs, seenScripts)
        }
    return candidates.singleOrNull() ?: candidates.firstOrNull { outputs[it].walletChange }
}

private fun narrowChangeCandidates(
    inputs: List<SpendInput>,
    allInputsOwned: Boolean,
    outputs: List<SpendOutput>,
    seenScripts: Set<String>,
): List<Int> {
    val inputTypes = inputs.map { scriptKind(it.scriptHex) }.toSet()
    val minInput = inputs.minOfOrNull { it.valueSats }
    val steps: List<(SpendOutput) -> Boolean> =
        listOf(
            { it.scriptHex !in seenScripts },
            { !allInputsOwned || inputs.size < 2 || minInput == null || it.valueSats < minInput },
            { !allInputsOwned || inputTypes.size != 1 || scriptKind(it.scriptHex) in inputTypes },
            { it.valueSats % ROUND_SATS != 0L },
        )
    var candidates = outputs.indices.toList()
    for (keep in steps) {
        if (candidates.size == 1) break
        val next = candidates.filter { keep(outputs[it]) }
        if (next.isNotEmpty()) candidates = next
    }
    return candidates
}

private fun scriptKind(scriptHex: String): String =
    when {
        scriptHex.length == 44 && scriptHex.startsWith("0014") -> "p2wpkh"
        scriptHex.length == 68 && scriptHex.startsWith("0020") -> "p2wsh"
        scriptHex.length == 68 && scriptHex.startsWith("5120") -> "p2tr"
        scriptHex.length == 46 && scriptHex.startsWith("a914") -> "p2sh"
        scriptHex.length == 50 && scriptHex.startsWith("76a914") -> "p2pkh"
        else -> "other"
    }

private class LinkageCtx(
    val watch: Set<String>,
    val change: Set<String>,
) {
    val certain = UnionFind()
    val likely = UnionFind()
    val live = mutableMapOf<String, WatchUtxo>()
    private val ownedOutpoints = mutableMapOf<String, SpendInput>()
    private val outpointsByScript = mutableMapOf<String, MutableList<String>>()
    private val seenScripts = mutableSetOf<String>()

    fun applyTx(row: TxRow) {
        val tx = Transaction.read(row.tx)
        val txid = tx.txid.toString()
        val ownedInputs = if (tx.isCoinbase()) emptyList() else tx.txIn.map(::prevoutKey).filter { it in ownedOutpoints }
        val outputs =
            tx.txOut.mapIndexed { vout, o ->
                val script = o.publicKeyScript.toByteArray()
                val h = scriptHex(script)
                val owned = h in watch
                if (owned) {
                    val key = outpointKey(txid, vout)
                    certain.add(key)
                    likely.add(key)
                    ownedOutpoints[key] = SpendInput(o.amount.toLong(), h)
                    outpointsByScript.getOrPut(h) { mutableListOf() }.add(key)
                    live[key] = WatchUtxo(o.amount.toLong(), script, row.height)
                }
                SpendOutput(o.amount.toLong(), h, owned, owned && h in change)
            }
        if (ownedInputs.isNotEmpty()) {
            val anchor = ownedInputs.first()
            for (k in ownedInputs) {
                certain.union(anchor, k)
                likely.union(anchor, k)
            }
            val changeVout =
                guessChangeVout(
                    inputs = ownedInputs.map { ownedOutpoints.getValue(it) },
                    allInputsOwned = ownedInputs.size == tx.txIn.size,
                    outputs = outputs,
                    seenScripts = seenScripts,
                )
            if (changeVout != null && outputs[changeVout].owned) likely.union(anchor, outpointKey(txid, changeVout))
        }
        if (!tx.isCoinbase()) {
            for (inn in tx.txIn) live.remove(prevoutKey(inn))
        }
        for (o in outputs) seenScripts.add(o.scriptHex)
        for (k in ownedInputs) seenScripts.add(ownedOutpoints.getValue(k).scriptHex)
    }

    fun unionAddressReuse() {
        for (keys in outpointsByScript.values) {
            if (keys.size < 2) continue
            val anchor = keys.first()
            for (k in keys.drop(1)) {
                certain.union(anchor, k)
                likely.union(anchor, k)
            }
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
