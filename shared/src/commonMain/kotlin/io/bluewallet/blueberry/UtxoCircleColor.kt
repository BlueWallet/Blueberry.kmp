package io.bluewallet.blueberry

import io.bluewallet.blueberry.wallet.sha256Hex

/**
 * Eleven tones of the app blue, #3788E0. Assigned by [utxoCirclePaletteIndex] so a coin
 * keeps the same color across screens and launches.
 */
internal val UtxoCirclePaletteArgb: LongArray =
    longArrayOf(
        0xFF1D63AFL,
        0xFFA0C5EEL,
        0xFF2B7CD4L,
        0xFF75AEEAL,
        0xFF3788E0L,
        0xFF94BAE3L,
        0xFF196FCCL,
        0xFF6CA2DDL,
        0xFF318BEDL,
        0xFF4796EBL,
        0xFF609EE1L,
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
