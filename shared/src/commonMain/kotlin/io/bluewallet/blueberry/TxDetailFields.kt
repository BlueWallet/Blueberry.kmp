package io.bluewallet.blueberry

import io.bluewallet.blueberry.headers.internalHexToDisplayHex
import io.bluewallet.blueberry.parse.TxFee
import io.bluewallet.blueberry.parse.formatBtc
import io.bluewallet.blueberry.parse.formatSatPerVb
import io.bluewallet.blueberry.parse.txVirtualSize
import io.bluewallet.blueberry.storage.StoredTx
import io.bluewallet.blueberry.wallet.destinationAddresses
import io.bluewallet.blueberry.wallet.inputAddresses

data class TxDetailField(
    val label: String,
    val value: String,
)

data class TxDetailSources(
    val txBytes: ByteArray? = null,
    val changeScripts: List<ByteArray> = emptyList(),
    val parentTx: (String) -> ByteArray? = { null },
    val privateDestination: String? = null,
    val privateProvider: Pair<String, String>? = null,
    val privateOrderId: String? = null,
)

fun txDetailFields(
    row: WalletTxRow,
    stored: StoredTx?,
    fee: TxFee? = null,
    sources: TxDetailSources = TxDetailSources(),
): List<TxDetailField> {
    val txBytes = sources.txBytes ?: stored?.tx
    val fields = mutableListOf(TxDetailField("Amount", row.netDeltaLabel))
    if (row.netDeltaSats >= 0) {
        val from = inputAddresses(txBytes, sources.parentTx)
        if (from.isNotEmpty()) {
            fields += TxDetailField("From", from.joinToString(", "))
        }
    } else {
        val destinations = destinationAddresses(txBytes, sources.changeScripts)
        if (destinations.isNotEmpty()) {
            fields += TxDetailField("Destination", destinations.joinToString(", "))
        }
    }
    if (sources.privateDestination != null) {
        fields += TxDetailField("Private destination", sources.privateDestination)
    }
    if (sources.privateProvider != null) {
        fields += TxDetailField(sources.privateProvider.first, sources.privateProvider.second)
    }
    val orderId = sources.privateOrderId?.trim().orEmpty()
    if (orderId.isNotEmpty()) {
        fields += TxDetailField("Order ID", orderId)
    }
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
        val vsize = txVirtualSize(stored.tx)
        if (vsize != null) {
            fields += TxDetailField("Virtual size", "$vsize vB")
        }
    }
    return fields
}
