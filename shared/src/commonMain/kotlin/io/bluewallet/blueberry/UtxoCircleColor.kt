package io.bluewallet.blueberry

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import io.bluewallet.blueberry.wallet.sha256Hex
import kotlin.math.min

/** First 6 hex chars of SHA-256(outpoint). The circle color is this hue, tamed. */
fun utxoCircleRgbHex(outpoint: String): String = sha256Hex(outpoint.encodeToByteArray()).take(6)

/** Hue from the outpoint, held to a muted mid lightness so it sits on the dark cards. */
fun utxoCircleArgb(outpoint: String): Long {
    val rgb = utxoCircleRgbHex(outpoint).toInt(16)
    val hue = rgbHueDegrees((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF)
    return Color.hsl(hue, saturation = 0.22f, lightness = 0.38f).toArgb().toLong() and 0xFFFFFFFFL
}

private fun rgbHueDegrees(
    r: Int,
    g: Int,
    b: Int,
): Float {
    val max = maxOf(r, g, b)
    val delta = (max - minOf(r, g, b)).toFloat()
    if (delta == 0f) return 0f
    val sector =
        when (max) {
            r -> (g - b) / delta + if (g < b) 6f else 0f
            g -> (b - r) / delta + 2f
            else -> (r - g) / delta + 4f
        }
    return sector * 60f
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
