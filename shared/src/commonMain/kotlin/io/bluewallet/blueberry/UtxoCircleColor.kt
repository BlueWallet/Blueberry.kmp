package io.bluewallet.blueberry

import io.bluewallet.blueberry.wallet.sha256Hex
import kotlin.math.min
import kotlin.math.roundToInt

/** First 6 hex chars of SHA-256(outpoint). The circle color is this hue, tamed. */
fun utxoCircleRgbHex(outpoint: String): String = sha256Hex(outpoint.encodeToByteArray()).take(6)

/** Hue from the outpoint, held to a muted mid lightness so it sits on the dark cards. */
fun utxoCircleArgb(outpoint: String): Long {
    val hex = utxoCircleRgbHex(outpoint)
    val rgb = tameUtxoRgb(hex.substring(0, 2).toInt(16), hex.substring(2, 4).toInt(16), hex.substring(4, 6).toInt(16))
    return 0xFF000000L or (rgb[0].toLong() shl 16) or (rgb[1].toLong() shl 8) or rgb[2].toLong()
}

private fun tameUtxoRgb(
    r: Int,
    g: Int,
    b: Int,
): IntArray {
    val rf = r / 255f
    val gf = g / 255f
    val bf = b / 255f
    val max = maxOf(rf, gf, bf)
    val minC = minOf(rf, gf, bf)
    val delta = max - minC
    val hue =
        if (delta == 0f) {
            0f
        } else {
            val sector =
                when (max) {
                    rf -> (gf - bf) / delta + if (gf < bf) 6f else 0f
                    gf -> (bf - rf) / delta + 2f
                    else -> (rf - gf) / delta + 4f
                }
            sector / 6f
        }
    return hslToRgb(hue, 0.22f, 0.38f)
}

private fun hslToRgb(
    hue: Float,
    sat: Float,
    light: Float,
): IntArray {
    if (sat == 0f) {
        val gray = (light * 255f).roundToInt()
        return intArrayOf(gray, gray, gray)
    }
    val q = if (light < 0.5f) light * (1f + sat) else light + sat - light * sat
    val p = 2f * light - q
    return intArrayOf(
        hueChannel(p, q, hue + 1f / 3f),
        hueChannel(p, q, hue),
        hueChannel(p, q, hue - 1f / 3f),
    )
}

private fun hueChannel(
    p: Float,
    q: Float,
    tIn: Float,
): Int {
    var t = tIn
    if (t < 0f) t += 1f
    if (t > 1f) t -= 1f
    val c =
        when {
            t < 1f / 6f -> p + (q - p) * 6f * t
            t < 1f / 2f -> q
            t < 2f / 3f -> p + (q - p) * (2f / 3f - t) * 6f
            else -> p
        }
    return (c * 255f).roundToInt().coerceIn(0, 255)
}

fun visibleUtxoCircleCount(
    total: Int,
    maxFit: Int,
): Int {
    if (total <= 0 || maxFit <= 0) return 0
    return min(total, maxFit)
}

/** How many overlapping circles of [diameter] stepping by [step] fit in [available]. */
fun maxUtxoCirclesThatFit(
    available: Float,
    diameter: Float,
    step: Float,
): Int {
    if (available < diameter || diameter <= 0f || step <= 0f) return 0
    return 1 + ((available - diameter) / step).toInt()
}
