package io.bluewallet.blueberry.parse

import fr.acinq.bitcoin.Transaction

private fun sortTxRows(txs: List<TxRow>): List<TxRow> =
    txs.sortedWith(compareBy({ it.height }, { it.txIndex }))

private fun applyTxToState(
    tx: Transaction,
    watch: Set<String>,
    utxos: MutableMap<String, WatchUtxo>,
    height: Int,
): Long {
    var delta = 0L
    if (!tx.isCoinbase()) {
        for (inn in tx.txIn) {
            val key = prevoutKey(inn)
            val spent = utxos.remove(key)
            if (spent != null) delta -= spent.value
        }
    }
    tx.txOut.forEachIndexed { vout, o ->
        if (watch.contains(scriptHex(o.publicKeyScript.toByteArray()))) {
            delta += o.amount.toLong()
            utxos[outpointKey(tx.txid.toString(), vout)] = WatchUtxo(
                value = o.amount.toLong(),
                scriptPubKey = o.publicKeyScript.toByteArray(),
                height = height,
            )
        }
    }
    return delta
}

fun prevoutKey(input: fr.acinq.bitcoin.TxIn): String {
    val hashBytes = input.outPoint.hash.value.toByteArray()
    return outpointKey(prevoutTxidDisplay(hashBytes), input.outPoint.index.toInt())
}

fun buildUtxoMap(txs: List<TxRow>, watchScripts: List<ByteArray>): MutableMap<String, WatchUtxo> {
    val watch = watchScripts.map(::scriptHex).toSet()
    val utxos = mutableMapOf<String, WatchUtxo>()
    for (row in sortTxRows(txs)) {
        applyTxToState(Transaction.read(row.tx), watch, utxos, row.height)
    }
    return utxos
}

fun netDeltasForTxs(txs: List<TxRow>, watchScripts: List<ByteArray>): Map<String, Long> {
    val watch = watchScripts.map(::scriptHex).toSet()
    val utxos = mutableMapOf<String, WatchUtxo>()
    val deltas = mutableMapOf<String, Long>()
    for (row in sortTxRows(txs)) {
        deltas[row.txid] = applyTxToState(Transaction.read(row.tx), watch, utxos, row.height)
    }
    return deltas
}

fun balanceFromTxs(txs: List<TxRow>, watchScripts: List<ByteArray>): BalanceSummary {
    val utxos = buildUtxoMap(txs, watchScripts)
    var sats = 0L
    for (utxo in utxos.values) sats += utxo.value
    return BalanceSummary(sats, utxos.size)
}
