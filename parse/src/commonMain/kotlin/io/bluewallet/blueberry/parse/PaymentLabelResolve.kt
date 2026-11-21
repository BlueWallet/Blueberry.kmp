package io.bluewallet.blueberry.parse

import fr.acinq.bitcoin.Transaction
import io.bluewallet.blueberry.wallet.addressFromOutputScript
import io.bluewallet.blueberry.wallet.canonicalBitcoinAddress
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
    return touchLabel(touchAddresses(parsed, addressByOutpoint), labelByAddress)
}

/** Addresses each transaction pays, then addresses its inputs spend. One read of each transaction. */
fun txTouchAddresses(txs: List<Pair<String, ByteArray>>): Map<String, List<String>> {
    val parsed = ArrayList<Pair<String, Transaction>>(txs.size)
    val unreadable = ArrayList<String>()
    val addressByOutpoint = LinkedHashMap<String, String>()
    for ((txid, raw) in txs) {
        val tx = runCatching { Transaction.read(raw) }.getOrNull()
        if (tx == null) {
            unreadable.add(txid)
            continue
        }
        parsed.add(txid to tx)
        tx.txOut.forEachIndexed { index, output ->
            val address = addressFromOutputScript(output.publicKeyScript.toByteArray()) ?: return@forEachIndexed
            addressByOutpoint[outpointKey(txid, index)] = address
        }
    }
    return unreadable.associateWith { emptyList<String>() } +
        parsed.associate { (txid, tx) -> txid to touchAddresses(tx, addressByOutpoint) }
}

fun touchLabel(
    addresses: List<String>,
    labelByAddress: Map<String, String>,
): String? {
    val labels = labelByAddress.mapKeys { canonicalBitcoinAddress(it.key) }
    return addresses.firstNotNullOfOrNull { labelled(it, labels) }
}

private fun touchAddresses(
    tx: Transaction,
    addressByOutpoint: Map<String, String>,
): List<String> {
    val paid = tx.txOut.mapNotNull { addressFromOutputScript(it.publicKeyScript.toByteArray()) }
    if (tx.isCoinbase()) return paid
    val spent =
        tx.txIn.mapNotNull { input ->
            addressByOutpoint[prevoutKey(input)]
                ?: spentScriptFromInput(input)?.let { addressFromOutputScript(it) }
        }
    return paid + spent
}

private fun labelled(
    address: String?,
    labelByAddress: Map<String, String>,
): String? = address?.let { labelByAddress[canonicalBitcoinAddress(it)]?.trim() }?.takeIf { it.isNotEmpty() }
