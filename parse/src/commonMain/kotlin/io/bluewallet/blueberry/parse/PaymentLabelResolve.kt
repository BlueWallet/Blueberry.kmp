package io.bluewallet.blueberry.parse

import fr.acinq.bitcoin.Transaction
import io.bluewallet.blueberry.wallet.addressFromOutputScript
import io.bluewallet.blueberry.wallet.outpointKey

fun firstUtxoLabelByTxid(nameByOutpoint: Map<String, String>): Map<String, String> {
    val labeled =
        nameByOutpoint.mapNotNull { (outpoint, name) ->
            val trimmed = name.trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val i = outpoint.lastIndexOf(':')
            if (i <= 0) return@mapNotNull null
            val vout = outpoint.substring(i + 1).toIntOrNull() ?: return@mapNotNull null
            Triple(outpoint.substring(0, i), vout, trimmed)
        }
    return labeled
        .groupBy { it.first }
        .mapValues { (_, rows) -> rows.minBy { it.second }.third }
}

/**
 * Label of the lowest output that pays a labelled address.
 * When no output matches, the label of the first input that spends one.
 * Null for an unreadable transaction or no match.
 */
fun firstAddressLabel(
    tx: ByteArray,
    labelByAddress: Map<String, String>,
    addressByOutpoint: Map<String, String> = emptyMap(),
): String? {
    val parsed = runCatching { Transaction.read(tx) }.getOrNull() ?: return null
    return paidLabel(parsed, labelByAddress) ?: spentLabel(parsed, labelByAddress, addressByOutpoint)
}

private fun paidLabel(
    tx: Transaction,
    labelByAddress: Map<String, String>,
): String? =
    tx.txOut.firstNotNullOfOrNull { out ->
        labelled(addressFromOutputScript(out.publicKeyScript.toByteArray()), labelByAddress)
    }

private fun spentLabel(
    tx: Transaction,
    labelByAddress: Map<String, String>,
    addressByOutpoint: Map<String, String>,
): String? =
    if (addressByOutpoint.isEmpty()) {
        null
    } else {
        tx.txIn.firstNotNullOfOrNull { input ->
            labelled(addressByOutpoint[prevoutKey(input)], labelByAddress)
        }
    }

/** Address paid by each output of [txs], keyed by outpoint. */
fun addressByOutpoint(txs: List<Pair<String, ByteArray>>): Map<String, String> {
    val map = LinkedHashMap<String, String>()
    for ((txid, raw) in txs) {
        val tx = runCatching { Transaction.read(raw) }.getOrNull() ?: continue
        tx.txOut.forEachIndexed { index, output ->
            val address = addressFromOutputScript(output.publicKeyScript.toByteArray()) ?: return@forEachIndexed
            map[outpointKey(txid, index)] = address
        }
    }
    return map
}

private fun labelled(
    address: String?,
    labelByAddress: Map<String, String>,
): String? = address?.let { labelByAddress[it]?.trim() }?.takeIf { it.isNotEmpty() }
