package io.bluewallet.blueberry

import io.bluewallet.blueberry.wallet.sha256Hex
import kotlin.math.min

/** First 6 hex chars of SHA-256(outpoint), used as an RGB color. */
fun utxoCircleRgbHex(outpoint: String): String = sha256Hex(outpoint.encodeToByteArray()).take(6)

fun utxoCircleArgb(outpoint: String): Long = 0xFF000000L or utxoCircleRgbHex(outpoint).toLong(16)

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
