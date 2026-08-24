package io.bluewallet.blueberry.parse

fun shortTxid(txid: String, keep: Int = 8): String {
    if (txid.length <= keep * 2) return txid
    return "${txid.substring(0, keep)}…${txid.substring(txid.length - keep)}"
}

/** First 6 hex chars of txid + ":" + vout (Send UTXO list). */
fun shortOutpoint(txid: String, vout: Int): String = "${txid.substring(0, 6)}:$vout"

private val BAR_PARTIAL = arrayOf("", "▏", "▎", "▍", "▌", "▋", "▊", "▉")

/**
 * Fixed-width relative bar: █ full cells, eighth partials, ░ empty track.
 * Max value fills the width; any positive dust keeps at least ▏.
 */
fun utxoValueBar(value: Long, maxValue: Long, width: Int = 30): String {
    if (value <= 0L || maxValue <= 0L || width <= 0) return ""
    if (value >= maxValue) return "█".repeat(width)

    val steps = width * 8L
    var filled = ((value * steps + maxValue / 2) / maxValue).toInt()
    filled = filled.coerceIn(1, steps.toInt())

    val full = filled / 8
    val rem = filled % 8
    val head = "█".repeat(full) + BAR_PARTIAL[rem]
    val used = full + if (rem > 0) 1 else 0
    return head + "░".repeat(maxOf(0, width - used))
}

/** 0..100 relative fill; any positive dust stays at least 1%. */
fun utxoValuePercent(value: Long, maxValue: Long): Int {
    if (value <= 0L || maxValue <= 0L) return 0
    if (value >= maxValue) return 100
    return ((value * 100.0) / maxValue).toInt().coerceIn(1, 99)
}

data class BtcParts(
    val sign: String,
    val whole: String,
    val fracSignificant: String,
    val fracTrailing: String,
)

/** Split sats for trailing-zero styling. All-zero frac peels the whole fraction. */
fun splitBtc(sats: Long, plus: Boolean = false): BtcParts {
    val neg = sats < 0
    val abs = if (neg) -sats else sats
    val whole = (abs / 100_000_000L).toString()
    val frac = (abs % 100_000_000L).toString().padStart(8, '0')
    var end = 8
    while (end > 0 && frac[end - 1] == '0') end--
    val sign = when {
        neg -> "-"
        plus && sats > 0 -> "+"
        else -> ""
    }
    return BtcParts(
        sign = sign,
        whole = whole,
        fracSignificant = frac.substring(0, end),
        fracTrailing = frac.substring(end),
    )
}

data class BtcStyled(
    val significant: String,
    val trailing: String,
)

/** Significant span vs muted span (dot joins trailing when the fraction is all zeros). */
fun styleBtc(sats: Long, plus: Boolean = false): BtcStyled {
    val p = splitBtc(sats, plus)
    return if (p.fracSignificant.isEmpty()) {
        BtcStyled("${p.sign}${p.whole}", ".${p.fracTrailing} BTC")
    } else {
        BtcStyled("${p.sign}${p.whole}.${p.fracSignificant}", "${p.fracTrailing} BTC")
    }
}

fun formatBtc(sats: Long): String {
    val p = splitBtc(sats)
    return "${p.sign}${p.whole}.${p.fracSignificant}${p.fracTrailing} BTC"
}

/** Parse a BTC decimal string to sats; null if not a valid non-negative amount. */
fun parseBtcToSats(input: String): Long? {
    val t = input.trim().replace(Regex("\\s*BTC\\s*$", RegexOption.IGNORE_CASE), "")
    if (t.isEmpty() || !Regex("^\\d+(\\.\\d+)?$").matches(t)) return null
    val parts = t.split('.')
    val whole = parts[0]
    val frac = if (parts.size > 1) parts[1] else ""
    if (frac.length > 8) return null
    return whole.toLong() * 100_000_000L + frac.padEnd(8, '0').toLong()
}

/** True for the send-max token: trim, then case-insensitive exact "max". */
fun isSendMaxAmount(input: String): Boolean = input.trim().lowercase() == "max"

fun formatNetDelta(sats: Long): String {
    val p = splitBtc(sats, plus = true)
    return "${p.sign}${p.whole}.${p.fracSignificant}${p.fracTrailing} BTC"
}
