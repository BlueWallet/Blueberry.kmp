package io.bluewallet.blueberry

import io.bluewallet.blueberry.parse.isSendMaxAmount
import io.bluewallet.blueberry.parse.parseBtcToSats
import io.bluewallet.blueberry.wallet.SendAmount
import io.bluewallet.blueberry.wallet.isAddressValid

sealed class SendField {
    data object Address : SendField()

    data object Label : SendField()
}

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

/** Amount-step primary label. An empty wallet says so, even when the field still holds a number. */
fun amountContinueLabel(
    input: String,
    walletTotalSats: Long,
    availability: CoinAvailability = CoinAvailability.Spendable,
): String {
    val check = checkSendAmount(input, walletTotalSats)
    return when {
        check is SendAmountCheck.Ok -> "Continue"
        availability == CoinAvailability.AllFrozen -> "Every coin is frozen"
        availability == CoinAvailability.AllSending -> "Every coin is sending"
        availability == CoinAvailability.FrozenOrSending -> "Every coin is frozen or sending"
        walletTotalSats <= 0L -> "No spendable coins left"
        else -> "Enter an amount"
    }
}

/** Why the wallet has nothing to spend, or null when it has spendable coins. */
fun noSpendableNotice(
    walletTotalSats: Long,
    availability: CoinAvailability,
): String? = unavailableNotice(availability) ?: if (walletTotalSats <= 0L) "No spendable coins left." else null

/** Why Review cannot price this payment, or null when [checkSendAmount] is ok. */
fun sendAmountUnavailable(
    input: String,
    walletTotalSats: Long,
    availability: CoinAvailability = CoinAvailability.Spendable,
): String? =
    when (checkSendAmount(input, walletTotalSats)) {
        is SendAmountCheck.Ok -> null
        SendAmountCheck.MoreThanWallet -> "The wallet can no longer cover this amount."
        SendAmountCheck.Empty -> noSpendableNotice(walletTotalSats, availability) ?: "Enter an amount."
    }

/** True when build/send failed because the fixed coin set cannot cover amount + fee. */
fun isInsufficientSelectionError(message: String?): Boolean {
    if (message == null) return false
    return message.contains("insufficient", ignoreCase = true)
}
