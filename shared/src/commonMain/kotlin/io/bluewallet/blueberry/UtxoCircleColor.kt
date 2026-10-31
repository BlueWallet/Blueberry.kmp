package io.bluewallet.blueberry

import io.bluewallet.blueberry.wallet.sha256Hex

/**
 * Spec coin palette (11). Assigned by [utxoCirclePaletteIndex] so a coin keeps
 * the same color across screens and launches.
 */
internal val UtxoCirclePaletteArgb: LongArray =
    longArrayOf(
        0xFFBE50DAL,
        0xFF4FD188L,
        0xFF46D3B7L,
        0xFFDDCD2BL,
        0xFFDB539BL,
        0xFFCA52C0L,
        0xFF49CFBBL,
        0xFFD95C53L,
        0xFFCAC759L,
        0xFF51D283L,
        0xFFB74EE0L,
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
