package io.bluewallet.blueberry

import io.bluewallet.blueberry.wallet.SendAmount
import io.bluewallet.blueberry.wallet.SendInputUtxo
import io.bluewallet.blueberry.wallet.WalletSecretKind
import io.bluewallet.blueberry.wallet.estimateSendMaxFeeSats
import io.bluewallet.blueberry.wallet.outputScriptFromAddress

fun rocketxGetSats(toAmount: String): Long? = parseRocketxBtcToSats(toAmount)

fun privateSendNeedBtc(amount: SendAmount): String? =
    when (amount) {
        is SendAmount.Exact -> satsToBtcString(amount.sats)
        SendAmount.Max -> null
    }

/** Native-segwit deposit. RocketX returns a bc1q address; vsize depends on the script size, not the address text. */
internal val PRIVATE_SEND_DEPOSIT_SCRIPT: ByteArray =
    outputScriptFromAddress("bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4")

fun estimatePrivateSendMaxFee(
    utxos: List<SendInputUtxo>,
    feeRateSatPerVb: Double,
): Long = estimateSendMaxFeeSats(utxos, feeRateSatPerVb, PRIVATE_SEND_DEPOSIT_SCRIPT)

fun privateSendBuildAmount(
    amount: SendAmount,
    fromBtc: String,
): SendAmount? =
    when (amount) {
        SendAmount.Max -> SendAmount.Max
        is SendAmount.Exact -> privateSendPayAmount(fromBtc)
    }

fun privateSendMaxSignError(
    changeSats: Long,
    paidSats: Long,
    fromBtc: String,
): String? {
    val from = parseRocketxBtcToSats(fromBtc) ?: return "swap missing pay amount"
    return when {
        changeSats > 0L -> "private send max left change"
        paidSats < from -> "deposit below swap amount"
        else -> null
    }
}

fun privateSendCanPay(
    fromBtc: String,
    selectedSumSats: Long,
    feeSats: Long,
): Boolean {
    val pay = parseRocketxBtcToSats(fromBtc) ?: return false
    return pay > 0L && pay + feeSats <= selectedSumSats
}

fun privateSendMaxFromBtc(
    selectedSumSats: Long,
    feeSats: Long,
): String? {
    val send = selectedSumSats - feeSats
    return if (send > 0L) satsToBtcString(send) else null
}

fun privateSendSigningError(kind: WalletSecretKind): String? =
    if (kind == WalletSecretKind.MNEMONIC || kind == WalletSecretKind.WIF) {
        null
    } else {
        "Private send needs a signing wallet"
    }

fun rocketxServiceFeeSats(
    fromAmount: String,
    toAmount: String,
): Long? {
    val pay = parseRocketxBtcToSats(fromAmount)
    val get = parseRocketxBtcToSats(toAmount)
    return if (pay == null || get == null || pay < get) null else pay - get
}

fun rocketxOverpaySats(
    toAmount: String,
    needBtc: String,
): Long? {
    val get = parseRocketxBtcToSats(toAmount)
    val need = parseRocketxBtcToSats(needBtc)
    return if (get == null || need == null || get <= need) null else get - need
}
