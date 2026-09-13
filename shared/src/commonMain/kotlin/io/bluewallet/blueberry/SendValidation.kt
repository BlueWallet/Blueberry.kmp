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
}

sealed class SendDetailsValidation {
    data class Ok(
        val details: SendDetails,
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
): SendDetailsValidation {
    if (!isAddressValid(address)) return SendDetailsValidation.Invalid(SendField.Address)
    val trimmedLabel = label.trim()
    if (isSendMaxAmount(amount)) {
        if (trimmedLabel.isEmpty()) return SendDetailsValidation.Invalid(SendField.Label)
        return SendDetailsValidation.Ok(SendDetails(address.trim(), SendAmount.Max, trimmedLabel))
    }
    val sats = parseBtcToSats(amount)
    if (sats == null || sats <= 0L || sats > selectedSumSats) {
        return SendDetailsValidation.Invalid(SendField.Amount)
    }
    if (trimmedLabel.isEmpty()) return SendDetailsValidation.Invalid(SendField.Label)
    return SendDetailsValidation.Ok(SendDetails(address.trim(), SendAmount.Exact(sats), trimmedLabel))
}

fun parseFeeRateSatPerVb(input: String): Double? {
    val t = input.trim()
    if (t.isEmpty() || !Regex("^\\d+(\\.\\d+)?$").matches(t)) return null
    val n = t.toDouble()
    return if (n.isFinite() && n > 0.0) n else null
}
