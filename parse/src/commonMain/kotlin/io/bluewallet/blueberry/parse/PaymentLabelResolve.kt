package io.bluewallet.blueberry.parse

import fr.acinq.bitcoin.Transaction
import io.bluewallet.blueberry.wallet.addressFromOutputScript

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

/** Label of the lowest vout paying a labelled address; null for unreadable txs or no match. */
fun firstAddressLabel(
    tx: ByteArray,
    labelByAddress: Map<String, String>,
): String? {
    val outputs = runCatching { Transaction.read(tx).txOut }.getOrNull() ?: return null
    return outputs.firstNotNullOfOrNull { out ->
        addressFromOutputScript(out.publicKeyScript.toByteArray())
            ?.let { labelByAddress[it]?.trim() }
            ?.takeIf { it.isNotEmpty() }
    }
}
