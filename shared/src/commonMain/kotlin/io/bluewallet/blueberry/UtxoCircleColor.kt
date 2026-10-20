package io.bluewallet.blueberry

import io.bluewallet.blueberry.wallet.sha256Hex
import kotlin.math.min

/**
 * Spec coin palette (11). Assigned by [utxoCirclePaletteIndex] so a coin keeps
 * the same color across screens and launches.
 */
internal val UtxoCirclePaletteArgb: LongArray =
    longArrayOf(
        0xFF7E4F8AL,
        0xFF4E8064L,
        0xFF4A7D73L,
        0xFF77723FL,
        0xFF8C5070L,
        0xFF7C4F78L,
        0xFF4B7B74L,
        0xFF8A5450L,
        0xFF7F7E52L,
        0xFF4F8263L,
        0xFF7C4E8EL,
    )

/** First 6 hex chars of SHA-256(outpoint). Used as a stable hash seed. */
fun utxoCircleRgbHex(outpoint: String): String = sha256Hex(outpoint.encodeToByteArray()).take(6)

/** Palette index in `0 until 11` from a hash of [outpoint]. */
fun utxoCirclePaletteIndex(outpoint: String): Int {
    val hex = sha256Hex(outpoint.encodeToByteArray())
    val n = hex.take(8).toLong(16)
    return (n % UtxoCirclePaletteArgb.size).toInt()
}

/** Spec coin color for [outpoint]: palette[hash mod 11]. */
fun utxoCircleArgb(outpoint: String): Long = UtxoCirclePaletteArgb[utxoCirclePaletteIndex(outpoint)]

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
