package io.bluewallet.blueberry.parse

import io.bluewallet.blueberry.storage.Database
import io.bluewallet.blueberry.storage.TxPaymentLabelRow
import io.bluewallet.blueberry.wallet.outpointKey

fun savePaymentLabel(db: Database, txid: String, label: String, changeVouts: List<Int>) {
    val trimmed = label.trim()
    if (trimmed.isEmpty()) throw IllegalArgumentException("payment label is required")
    db.txPaymentLabels.upsert(TxPaymentLabelRow(txid, trimmed))
    val name = "change from: $trimmed"
    for (vout in changeVouts) {
        db.utxoNames.upsert(outpointKey(txid, vout), name)
    }
}
