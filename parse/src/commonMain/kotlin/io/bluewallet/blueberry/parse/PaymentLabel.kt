package io.bluewallet.blueberry.parse

import io.bluewallet.blueberry.storage.Database
import io.bluewallet.blueberry.storage.TxPaymentLabelRow
import io.bluewallet.blueberry.wallet.outpointKey

fun savePaymentLabel(
    db: Database,
    txid: String,
    label: String,
    changeVouts: List<Int>,
) {
    val trimmed = label.trim()
    if (trimmed.isEmpty()) throw IllegalArgumentException("payment label is required")
    db.txPaymentLabels.upsert(TxPaymentLabelRow(txid, trimmed))
    val name = "change from: $trimmed"
    for (vout in changeVouts) {
        db.utxoNames.upsert(outpointKey(txid, vout), name)
    }
}

/** Copies a payment's note and change names from [fromTxid] onto [toTxid]. Names already on [toTxid] win. */
fun movePaymentLabel(
    db: Database,
    fromTxid: String,
    toTxid: String,
) {
    if (fromTxid == toTxid) return
    val note = db.txPaymentLabels.get(fromTxid)
    if (note != null && db.txPaymentLabels.get(toTxid) == null) db.txPaymentLabels.upsert(TxPaymentLabelRow(toTxid, note.label))
    val names = db.utxoNames.list()
    val taken = names.map { it.outpoint }.toSet()
    val prefix = "$fromTxid:"
    for (row in names) {
        if (!row.outpoint.startsWith(prefix)) continue
        val key = toTxid + ":" + row.outpoint.removePrefix(prefix)
        if (key !in taken) db.utxoNames.upsert(key, row.name)
    }
}
