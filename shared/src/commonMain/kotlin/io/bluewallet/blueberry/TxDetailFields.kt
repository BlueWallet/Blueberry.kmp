package io.bluewallet.blueberry

import io.bluewallet.blueberry.headers.internalHexToDisplayHex
import io.bluewallet.blueberry.parse.TxFee
import io.bluewallet.blueberry.parse.formatBtc
import io.bluewallet.blueberry.parse.formatSatPerVb
import io.bluewallet.blueberry.storage.StoredTx

data class TxDetailField(
    val label: String,
    val value: String,
)

fun txDetailFields(
    row: WalletTxRow,
    stored: StoredTx?,
    fee: TxFee? = null,
): List<TxDetailField> {
    val fields = mutableListOf(TxDetailField("Amount", row.netDeltaLabel))
    if (fee != null) {
        fields += TxDetailField("Fee", formatBtc(fee.feeSats))
        fields += TxDetailField("Fee rate", formatSatPerVb(fee.feeSats, fee.vsize))
    }
    fields += TxDetailField("Note", row.paymentLabel?.trim().orEmpty())
    fields += TxDetailField("Date", row.timeLabel.trim())
    fields += TxDetailField("Height", row.height.toString())
    fields += TxDetailField("Transaction ID", row.txid)
    if (stored != null) {
        fields += TxDetailField("Block hash", internalHexToDisplayHex(stored.blockHashInternalHex))
        fields += TxDetailField("Index", stored.txIndex.toString())
        fields += TxDetailField("Size", "${stored.tx.size} B")
    }
    return fields
}
