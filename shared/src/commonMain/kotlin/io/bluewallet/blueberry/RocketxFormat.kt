package io.bluewallet.blueberry

import io.bluewallet.blueberry.parse.parseBtcToSats
import kotlin.math.round

fun formatRocketxSwap(swap: RocketxSwap): String =
    buildString {
        append(swap.address)
        if (swap.fromAmount.isNotEmpty()) append("\npay ${swap.fromAmount} BTC")
        if (swap.toAmount.isNotEmpty()) append("\nget ${swap.toAmount} BTC")
        if (swap.venue.isNotEmpty()) append("\n${swap.venue}")
        if (swap.partnerFee.isNotEmpty()) append("\nservice fee ${formatRocketxPercent(swap.partnerFee)}")
        if (swap.estSeconds.isNotEmpty()) append("\n${formatRocketxEta(swap.estSeconds)}")
        if (swap.requestId.isNotEmpty()) append("\n${swap.requestId}")
    }

fun formatRocketxPercent(raw: String): String {
    val n = raw.toDoubleOrNull() ?: return raw
    val tenths = round(n * 10.0).toInt()
    val text = if (tenths % 10 == 0) (tenths / 10).toString() else "${tenths / 10}.${tenths % 10}"
    return "$text%"
}

fun formatRocketxEta(seconds: String): String {
    val s = seconds.toDoubleOrNull() ?: return seconds
    val min = round(s / 60.0).toInt()
    return if (min < 1) "about ${s.toInt()} sec" else "about $min min"
}

fun quotesCoveringReceive(
    quotes: List<RocketxQuote>,
    expectedBtc: String,
): List<RocketxQuote> {
    val need = parseRocketxBtcToSats(expectedBtc) ?: return emptyList()
    return quotes.filter { (parseRocketxBtcToSats(it.toAmount) ?: 0L) >= need }
}

fun scaledSendAmount(
    fromAmount: String,
    toAmount: String,
    expectedBtc: String,
): String? {
    val from = parseRocketxBtcToSats(fromAmount)
    return if (from == null) {
        null
    } else {
        scaleSendSats(from, parseRocketxBtcToSats(toAmount) ?: 0L, parseRocketxBtcToSats(expectedBtc) ?: 0L)
            ?.let(::satsToBtcString)
    }
}

internal fun parseRocketxBtcToSats(input: String): Long? {
    val t = input.trim()
    val dot = t.indexOf('.')
    val clipped =
        if (dot >= 0 && t.length > dot + 1 + 8) {
            t.substring(0, dot + 1 + 8)
        } else {
            t
        }
    return parseBtcToSats(clipped)
}

private fun scaleSendSats(
    from: Long,
    got: Long,
    need: Long,
): Long? = if (got <= 0L || need <= 0L) null else (from * need + got - 1L) / got

fun rocketxQuotationUrl(expectedToAmountBtc: String): String = rocketxQuotationBase() + "&expectedToAmount=$expectedToAmountBtc"

fun rocketxQuotationUrlForSend(fromAmountBtc: String): String = rocketxQuotationBase() + "&amount=$fromAmountBtc"

private fun rocketxQuotationBase(): String =
    "https://api.rocketx.exchange/v1/quotation?" +
        "fromToken=null&fromNetwork=BTC&toToken=null&toNetwork=BTC" +
        "&slippage=1&fixedRate=true&includedExchanges=PRIVATE_ONLY"
