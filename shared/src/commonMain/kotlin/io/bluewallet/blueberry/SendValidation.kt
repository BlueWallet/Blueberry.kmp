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

sealed class SendContinueTarget {
    data object OnchainPreview : SendContinueTarget()

    data object PrivateSend : SendContinueTarget()
}

fun sendContinueTarget(privateSend: Boolean): SendContinueTarget = if (privateSend) SendContinueTarget.PrivateSend else SendContinueTarget.OnchainPreview

/** Address step: the first field that blocks Continue, or null when both are filled in. */
fun validateSendAddress(
    address: String,
    label: String,
): SendField? =
    when {
        !isAddressValid(address) -> SendField.Address
        label.isBlank() -> SendField.Label
        else -> null
    }

sealed class SendAmountCheck {
    data object Empty : SendAmountCheck()

    data object MoreThanWallet : SendAmountCheck()

    data class Ok(
        val amount: SendAmount,
    ) : SendAmountCheck()
}

/** Amount step: typed BTC or MAX against everything spendable in the wallet. */
fun checkSendAmount(
    input: String,
    walletTotalSats: Long,
): SendAmountCheck {
    val sats = parseBtcToSats(input)
    return when {
        walletTotalSats <= 0L -> SendAmountCheck.Empty
        isSendMaxAmount(input) -> SendAmountCheck.Ok(SendAmount.Max)
        sats == null || sats <= 0L -> SendAmountCheck.Empty
        sats > walletTotalSats -> SendAmountCheck.MoreThanWallet
        else -> SendAmountCheck.Ok(SendAmount.Exact(sats))
    }
}

const val NUMBER_PAD_BACKSPACE = '<'
private const val BTC_DECIMALS = 8
private const val BTC_WHOLE_DIGITS = 8

/** Applies one in-app number pad key to the typed BTC amount. MAX is replaced by the first key. */
fun numberPadInput(
    current: String,
    key: Char,
): String {
    val typed = if (isSendMaxAmount(current)) "" else current
    val dot = typed.indexOf('.')
    return when {
        key == NUMBER_PAD_BACKSPACE -> typed.dropLast(1)
        key == '.' && dot >= 0 -> typed
        key == '.' -> if (typed.isEmpty()) "0." else "$typed."
        !key.isDigit() -> typed
        dot >= 0 -> if (typed.length - dot - 1 >= BTC_DECIMALS) typed else typed + key
        typed == "0" -> key.toString()
        typed.length >= BTC_WHOLE_DIGITS -> typed
        else -> typed + key
    }
}

fun parseFeeRateSatPerVb(input: String): Double? {
    val t = input.trim()
    if (t.isEmpty() || !Regex("^\\d+(\\.\\d+)?$").matches(t)) return null
    val n = t.toDouble()
    return if (n.isFinite() && n > 0.0) n else null
}
