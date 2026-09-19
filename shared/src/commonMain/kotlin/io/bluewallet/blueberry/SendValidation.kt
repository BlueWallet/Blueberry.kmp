package io.bluewallet.blueberry

import io.bluewallet.blueberry.parse.isSendMaxAmount
import io.bluewallet.blueberry.parse.parseBtcToSats
import io.bluewallet.blueberry.wallet.SendAmount
import io.bluewallet.blueberry.wallet.isAddressValid

data class SendDetails(
    val toAddress: String,
    val amountSats: SendAmount,
    val paymentLabel: String,
)

sealed class SendField {
    data object Address : SendField()

    data object Amount : SendField()

    data object Label : SendField()

    data object FeeRate : SendField()
}

sealed class SendDetailsValidation {
    data class Ok(
        val details: SendDetails,
        val feeRateSatPerVb: Double,
    ) : SendDetailsValidation()

    data class Invalid(
        val field: SendField,
    ) : SendDetailsValidation()
}

fun validateSendDetails(
    address: String,
    amount: String,
    label: String,
    selectedSumSats: Long,
    feeRate: String,
): SendDetailsValidation {
    val trimmedLabel = label.trim()
    val max = isSendMaxAmount(amount)
    val sats = parseBtcToSats(amount)
    val rate = parseFeeRateSatPerVb(feeRate)
    val field =
        when {
            !isAddressValid(address) -> SendField.Address
            max && trimmedLabel.isEmpty() -> SendField.Label
            !max && (sats == null || sats <= 0L || sats > selectedSumSats) -> SendField.Amount
            !max && trimmedLabel.isEmpty() -> SendField.Label
            rate == null -> SendField.FeeRate
            else -> null
        }
    if (field != null) return SendDetailsValidation.Invalid(field)
    val amountSats = if (max) SendAmount.Max else SendAmount.Exact(sats!!)
    return SendDetailsValidation.Ok(SendDetails(address.trim(), amountSats, trimmedLabel), rate!!)
}

fun parseFeeRateSatPerVb(input: String): Double? {
    val t = input.trim()
    if (t.isEmpty() || !Regex("^\\d+(\\.\\d+)?$").matches(t)) return null
    val n = t.toDouble()
    return if (n.isFinite() && n > 0.0) n else null
}
