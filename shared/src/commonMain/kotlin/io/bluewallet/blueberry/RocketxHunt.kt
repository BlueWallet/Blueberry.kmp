package io.bluewallet.blueberry

data class RocketxQuoteHunt(
    val expectedBtc: String,
    val lowFromBtc: String? = null,
    val highFromBtc: String? = null,
    val lastShort: RocketxQuote? = null,
    val best: List<RocketxQuote> = emptyList(),
)

fun huntAfterQuotes(
    expectedBtc: String,
    quotes: List<RocketxQuote>,
): RocketxQuoteHunt {
    val cover = closestCoveringQuotes(quotes, expectedBtc)
    return if (cover.isNotEmpty()) {
        RocketxQuoteHunt(expectedBtc, best = cover)
    } else {
        RocketxQuoteHunt(expectedBtc, lowFromBtc = quotes.firstOrNull()?.fromAmount, lastShort = quotes.firstOrNull())
    }
}

fun nextHuntSend(hunt: RocketxQuoteHunt): String? {
    val low = hunt.lowFromBtc?.let(::parseRocketxBtcToSats)
    val high = hunt.highFromBtc?.let(::parseRocketxBtcToSats)
    return if (high == null) {
        hunt.lastShort?.let { scaledSendAmount(it.fromAmount, it.toAmount, hunt.expectedBtc) }
    } else {
        midpointSend(low, high)
    }
}

private fun midpointSend(
    low: Long?,
    high: Long,
): String? {
    if (low == null || high - low <= HUNT_GAP_SATS) return null
    val mid = (low + high) / 2L
    return if (mid > low && mid < high) satsToBtcString(mid) else null
}

fun huntAfterSend(
    hunt: RocketxQuoteHunt,
    sendBtc: String,
    quotes: List<RocketxQuote>,
): RocketxQuoteHunt {
    val cover = closestCoveringQuotes(quotes, hunt.expectedBtc)
    return if (cover.isNotEmpty()) {
        hunt.copy(highFromBtc = sendBtc, best = closerCover(hunt.best, cover, hunt.expectedBtc))
    } else {
        hunt.copy(lowFromBtc = sendBtc, lastShort = quotes.firstOrNull() ?: hunt.lastShort)
    }
}

internal fun closestCoveringQuotes(
    quotes: List<RocketxQuote>,
    expectedBtc: String,
): List<RocketxQuote> {
    val cover = quotesCoveringReceive(quotes, expectedBtc)
    val bestTo = cover.minOfOrNull { parseRocketxBtcToSats(it.toAmount) ?: Long.MAX_VALUE }
    return if (bestTo == null) emptyList() else cover.filter { parseRocketxBtcToSats(it.toAmount) == bestTo }
}

private fun closerCover(
    current: List<RocketxQuote>,
    incoming: List<RocketxQuote>,
    expectedBtc: String,
): List<RocketxQuote> {
    val need = parseRocketxBtcToSats(expectedBtc)
    val cur = current.firstOrNull()?.toAmount?.let(::parseRocketxBtcToSats)
    val inn = incoming.firstOrNull()?.toAmount?.let(::parseRocketxBtcToSats)
    val currentGap = if (need == null || cur == null) Long.MAX_VALUE else cur - need
    val incomingGap = if (need == null || inn == null) Long.MAX_VALUE else inn - need
    return if (incomingGap < currentGap) incoming else current
}

private const val HUNT_GAP_SATS = 100L
