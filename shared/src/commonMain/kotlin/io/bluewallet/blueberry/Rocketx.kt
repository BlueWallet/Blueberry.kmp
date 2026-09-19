package io.bluewallet.blueberry

import io.bluewallet.blueberry.wallet.SendAmount
import io.bluewallet.blueberry.wallet.SendInputUtxo
import io.bluewallet.blueberry.wallet.SignedSendResult
import io.bluewallet.echalote.Abort
import io.bluewallet.echalote.Echalote
import io.bluewallet.echalote.FetchProgressListener

data class PrivateSendSession(
    val destination: String,
    val label: String,
    val feeRateSatPerVb: Double,
    val amountSats: SendAmount,
    val utxos: List<SendInputUtxo>,
    val refundAddress: String,
)

data class RocketxQuote(
    val rateId: String,
    val fromAmount: String,
    val toAmount: String,
    val venue: String,
    val fromTokenId: Int,
    val toTokenId: Int,
    val exchangeId: String,
)

sealed class PrivateSendUi {
    data class Loading(
        val percent: Int = 0,
        val stage: String = "Starting",
    ) : PrivateSendUi()

    data class Error(
        val message: String,
    ) : PrivateSendUi()

    data class Quotes(
        val quotes: List<RocketxQuote>,
    ) : PrivateSendUi()

    data class Deposit(
        val swap: RocketxSwap,
        val signed: SignedSendResult,
        val refundAddress: String,
        val needBtc: String,
        val label: String,
    ) : PrivateSendUi()
}

data class RocketxSwap(
    val address: String,
    val fromAmount: String,
    val toAmount: String,
    val venue: String,
    val partnerFee: String,
    val requestId: String,
    val estSeconds: String,
)

sealed class RocketxQuoteAction {
    data class Choose(
        val quotes: List<RocketxQuote>,
    ) : RocketxQuoteAction()

    data class AutoSwap(
        val quote: RocketxQuote,
    ) : RocketxQuoteAction()

    data object None : RocketxQuoteAction()
}

fun nextAfterQuotes(quotes: List<RocketxQuote>): RocketxQuoteAction =
    when (quotes.size) {
        0 -> RocketxQuoteAction.None
        1 -> RocketxQuoteAction.AutoSwap(quotes[0])
        else -> RocketxQuoteAction.Choose(quotes)
    }

fun swapCoversDestination(
    toAmountBtc: String,
    needBtc: String,
): Boolean {
    val got = parseRocketxBtcToSats(toAmountBtc)
    val need = parseRocketxBtcToSats(needBtc)
    return got != null && need != null && got >= need
}

fun privateSendPayAmount(fromAmountBtc: String): SendAmount.Exact? = parseRocketxBtcToSats(fromAmountBtc)?.let { SendAmount.Exact(it) }

fun expectedToAmountBtc(
    amount: SendAmount,
    selectedSumSats: Long,
): String {
    val sats =
        when (amount) {
            is SendAmount.Exact -> amount.sats
            SendAmount.Max -> selectedSumSats
        }
    return satsToBtcString(sats)
}

fun parseRocketxQuotes(body: String): List<RocketxQuote> {
    val root = parseJson(body)
    val out = ArrayList<RocketxQuote>()
    collectQuotes(root, out)
    return out
}

fun parseRocketxDepositAddress(body: String): String? = firstStringField(parseJson(body), "depositAddress")

fun parseRocketxError(body: String): String? = firstStringField(parseJson(body), "err")

fun rocketxSwapBody(
    quote: RocketxQuote,
    destinationAddress: String,
    refundAddress: String,
): String =
    """{"fromTokenId":${quote.fromTokenId},"toTokenId":${quote.toTokenId},""" +
        """"amount":${quote.fromAmount},"destinationAddress":"${escapeJson(destinationAddress)}",""" +
        """"refundAddress":"${escapeJson(refundAddress)}","rateId":"${escapeJson(quote.rateId)}",""" +
        """"slippage":1,"exchangeId":"${escapeJson(quote.exchangeId)}"}"""

class RocketxClient(
    private val apiKey: String,
    private val http: suspend (
        method: String,
        url: String,
        headers: Map<String, String>,
        body: ByteArray,
        onProgress: FetchProgressListener?,
        abort: Abort?,
    ) -> Pair<Int, String> = { method, url, headers, body, onProgress, abort ->
        val res =
            Echalote.fetch(
                url,
                abort = abort,
                method = method,
                headers = headers,
                body = body,
                onProgress = onProgress,
            )
        res.status to res.body.decodeToString()
    },
) {
    private val abort = Abort()

    fun cancel() {
        abort.abort()
    }

    suspend fun quoteForSend(
        fromAmountBtc: String,
        onProgress: FetchProgressListener? = null,
    ): List<RocketxQuote> = fetchQuotes(rocketxQuotationUrlForSend(fromAmountBtc), onProgress)

    suspend fun quote(
        expectedToAmountBtc: String,
        onProgress: FetchProgressListener? = null,
    ): List<RocketxQuote> {
        val first = fetchQuotes(rocketxQuotationUrl(expectedToAmountBtc), onProgress)
        var hunt = huntAfterQuotes(expectedToAmountBtc, first)
        var probes = 0
        while (first.isNotEmpty() && probes < 8) {
            val send = nextHuntSend(hunt) ?: break
            hunt = huntAfterSend(hunt, send, fetchQuotes(rocketxQuotationUrlForSend(send), onProgress))
            probes++
        }
        return when {
            hunt.best.isNotEmpty() -> hunt.best
            first.isEmpty() -> emptyList()
            else -> error("quote pays out less than requested")
        }
    }

    private suspend fun fetchQuotes(
        url: String,
        onProgress: FetchProgressListener?,
    ): List<RocketxQuote> {
        val (status, text) = http("GET", url, apiHeaders(), ByteArray(0), onProgress, abort)
        if (status !in 200..299) error("quote HTTP $status")
        return parseRocketxQuotes(text)
    }

    suspend fun startSwap(
        quote: RocketxQuote,
        destinationAddress: String,
        refundAddress: String,
        onProgress: FetchProgressListener? = null,
    ): RocketxSwap {
        val body = rocketxSwapBody(quote, destinationAddress, refundAddress)
        val headers = apiHeaders() + ("Content-Type" to "application/json")
        val (status, text) = http("POST", ROCKETX_SWAP_URL, headers, body.encodeToByteArray(), onProgress, abort)
        if (status !in 200..299) error("swap HTTP $status")
        val apiErr = parseRocketxError(text)
        if (apiErr != null) error("swap $apiErr")
        return parseRocketxSwap(text) ?: error("swap missing deposit address")
    }

    private fun apiHeaders(): Map<String, String> =
        mapOf(
            "Accept" to "application/json",
            "x-api-key" to apiKey,
        )
}

private const val ROCKETX_SWAP_URL = "https://api.rocketx.exchange/v1/swap"

internal fun satsToBtcString(sats: Long): String {
    val neg = sats < 0
    val n = if (neg) -sats else sats
    val whole = n / 100_000_000L
    val frac = n % 100_000_000L
    val fracStr = frac.toString().padStart(8, '0').trimEnd('0')
    val body = if (fracStr.isEmpty()) whole.toString() else "$whole.$fracStr"
    return if (neg) "-$body" else body
}

internal fun escapeJson(value: String): String =
    buildString {
        for (ch in value) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                else -> append(ch)
            }
        }
    }
