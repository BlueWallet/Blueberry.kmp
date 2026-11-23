package io.bluewallet.blueberry.wallet

private const val INPUT_CHARSET =
    "0123456789()[],'/*abcdefgh@:$%{}" +
        "IJKLMNOPQRSTUVWXYZ&+-.;<=>?!^_|~" +
        "ijklmnopqrstuvwxyzABCDEFGH`#\"\\ "
private const val CHECKSUM_CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
private val GENERATOR = longArrayOf(0xf5dee51989L, 0xa9fdca3312L, 0x1bab10e32dL, 0x3706b1677aL, 0x644d626ffdL)

private fun polymod(
    c: Long,
    value: Int,
): Long {
    val top = c ushr 35
    var next = ((c and 0x7ffffffffL) shl 5) xor value.toLong()
    for (i in GENERATOR.indices) {
        if ((top ushr i) and 1L == 1L) next = next xor GENERATOR[i]
    }
    return next
}

/** BIP380 checksum of [descriptor] (without `#`), or null when it has a character BIP380 does not allow. */
fun descriptorChecksum(descriptor: String): String? {
    var c = 1L
    var cls = 0
    var clsCount = 0
    for (ch in descriptor) {
        val pos = INPUT_CHARSET.indexOf(ch)
        if (pos < 0) return null
        c = polymod(c, pos and 31)
        cls = cls * 3 + (pos shr 5)
        if (++clsCount == 3) {
            c = polymod(c, cls)
            cls = 0
            clsCount = 0
        }
    }
    if (clsCount > 0) c = polymod(c, cls)
    repeat(8) { c = polymod(c, 0) }
    c = c xor 1L
    return CharArray(8) { j -> CHECKSUM_CHARSET[((c ushr (5 * (7 - j))) and 31L).toInt()] }.concatToString()
}
