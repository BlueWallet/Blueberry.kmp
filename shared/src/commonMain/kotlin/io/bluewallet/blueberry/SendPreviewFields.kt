package io.bluewallet.blueberry

import io.bluewallet.blueberry.parse.formatBtc
import io.bluewallet.blueberry.parse.formatSatPerVb
import io.bluewallet.blueberry.wallet.SendAmount

data class SendPreviewField(
    val label: String,
    val value: String,
    val caption: String? = null,
    val sats: Long? = null,
)

fun sendPaidSats(
    amount: SendAmount,
    inputSum: Long,
    feeSats: Long,
    changeSats: Long,
): Long =
    when (amount) {
        is SendAmount.Max -> inputSum - feeSats - changeSats
        is SendAmount.Exact -> amount.sats
    }

data class SendPreviewTotals(
    val feeSats: Long,
    val sizeBytes: Int,
    val vsize: Int,
    val changeSats: Long,
)

fun formatTxSize(
    sizeBytes: Int,
    vsize: Int,
): String = if (sizeBytes == vsize) "$sizeBytes bytes" else "$sizeBytes bytes · $vsize vbytes"

fun sendPreviewFields(
    details: SendDetails,
    inputSum: Long,
    totals: SendPreviewTotals,
): List<SendPreviewField> {
    val paid = sendPaidSats(details.amountSats, inputSum, totals.feeSats, totals.changeSats)
    val fields =
        mutableListOf(
            SendPreviewField("To", details.toAddress),
            SendPreviewField(
                label = "Send",
                value = formatBtc(paid),
                caption = if (details.amountSats is SendAmount.Max) "MAX" else null,
                sats = paid,
            ),
            SendPreviewField(
                label = "Fee",
                value = formatBtc(totals.feeSats),
                caption = formatSatPerVb(totals.feeSats, totals.vsize),
                sats = totals.feeSats,
            ),
            SendPreviewField("Transaction size", formatTxSize(totals.sizeBytes, totals.vsize)),
        )
    if (totals.changeSats > 0) {
        fields += SendPreviewField("Change", formatBtc(totals.changeSats), sats = totals.changeSats)
    }
    fields += SendPreviewField("Label", details.paymentLabel)
    return fields
}
