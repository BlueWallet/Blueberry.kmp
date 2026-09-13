package io.bluewallet.blueberry.parse

import fr.acinq.bitcoin.Transaction
import kotlin.math.round

data class TxFee(
    val feeSats: Long,
    val vsize: Int,
)

data class WatchScan(
    val utxos: MutableMap<String, WatchUtxo>,
    val fees: Map<String, TxFee>,
)

fun formatSatPerVb(
    feeSats: Long,
    vsize: Int,
): String {
    if (vsize <= 0) return "—"
    val tenths = round(feeSats * 10.0 / vsize).toLong()
    val whole = tenths / 10
    val frac = kotlin.math.abs(tenths % 10)
    return "$whole.$frac sat/vB"
}

fun feeForTx(
    txid: String,
    txs: List<TxRow>,
    watchScripts: List<ByteArray>,
): TxFee? = scanWatchTxs(txs, watchScripts).fees[txid]

internal fun feeFromKnownInputs(
    tx: Transaction,
    utxos: Map<String, WatchUtxo>,
): TxFee? {
    val feeSats = knownFeeSats(tx, utxos) ?: return null
    val vsize = (tx.weight() + 3) / 4
    return if (vsize <= 0) null else TxFee(feeSats, vsize)
}

private fun knownFeeSats(
    tx: Transaction,
    utxos: Map<String, WatchUtxo>,
): Long? {
    if (tx.isCoinbase() || tx.txIn.isEmpty()) return null
    var inputSum = 0L
    var missing = false
    for (inn in tx.txIn) {
        val value = utxos[prevoutKey(inn)]?.value
        if (value == null) {
            missing = true
            break
        }
        inputSum += value
    }
    val feeSats = inputSum - tx.txOut.sumOf { it.amount.toLong() }
    return if (missing || feeSats < 0L) null else feeSats
}
