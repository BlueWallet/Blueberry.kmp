package io.bluewallet.blueberry.ui.dither

/** 8×8 Bayer thresholds in (0, 1), row-major. Shared by the tree and the glow. */
internal val BAYER_8X8: FloatArray =
    intArrayOf(
        0,
        32,
        8,
        40,
        2,
        34,
        10,
        42,
        48,
        16,
        56,
        24,
        50,
        18,
        58,
        26,
        12,
        44,
        4,
        36,
        14,
        46,
        6,
        38,
        60,
        28,
        52,
        20,
        62,
        30,
        54,
        22,
        3,
        35,
        11,
        43,
        1,
        33,
        9,
        41,
        51,
        19,
        59,
        27,
        49,
        17,
        57,
        25,
        15,
        47,
        7,
        39,
        13,
        45,
        5,
        37,
        63,
        31,
        55,
        23,
        61,
        29,
        53,
        21,
    ).let { m -> FloatArray(64) { (m[it] + 0.5f) / 64f } }

/** The website's mulberry32 generator, bit for bit (JS Math.imul is Int multiply here). */
internal class Mulberry32(
    seed: Int,
) {
    private var s = seed

    fun next(): Float {
        s += 0x6D2B79F5
        var t = s
        t = (t xor (t ushr 15)) * (t or 1)
        t = t xor (t + (t xor (t ushr 7)) * (t or 61))
        return ((t xor (t ushr 14)).toLong() and 0xFFFFFFFFL).toFloat() / 4294967296f
    }
}
