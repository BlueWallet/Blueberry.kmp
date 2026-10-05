package io.bluewallet.blueberry.labels

private const val BLOCK = 16

/** Returns ciphertext and the 16-byte GCM tag. [iv] is 12 bytes and [key] is 16 or 32 bytes. */
internal fun aesGcmEncrypt(
    key: ByteArray,
    iv: ByteArray,
    plaintext: ByteArray,
): Pair<ByteArray, ByteArray> {
    require(iv.size == 12)
    val hashKey = aesBlockEncrypt(key, ByteArray(BLOCK))
    val j0 = iv + byteArrayOf(0, 0, 0, 1)
    val ciphertext = ctr(plaintext, j0) { aesBlockEncrypt(key, it) }
    return ciphertext to xor(ghash(hashKey, ciphertext), aesBlockEncrypt(key, j0))
}

/** Throws [IllegalArgumentException] when the tag does not match. */
internal fun aesGcmDecrypt(
    key: ByteArray,
    iv: ByteArray,
    ciphertext: ByteArray,
    tag: ByteArray,
): ByteArray {
    require(iv.size == 12)
    require(tag.size == BLOCK)
    val hashKey = aesBlockEncrypt(key, ByteArray(BLOCK))
    val j0 = iv + byteArrayOf(0, 0, 0, 1)
    val expected = xor(ghash(hashKey, ciphertext), aesBlockEncrypt(key, j0))
    var diff = 0
    for (i in expected.indices) diff = diff or (expected[i].toInt() xor tag[i].toInt())
    require(diff == 0) { "metadata authentication failed" }
    return ctr(ciphertext, j0) { aesBlockEncrypt(key, it) }
}

private fun ctr(
    input: ByteArray,
    j0: ByteArray,
    encrypt: (ByteArray) -> ByteArray,
): ByteArray {
    val out = ByteArray(input.size)
    var counter = inc32(j0)
    var offset = 0
    while (offset < input.size) {
        val stream = encrypt(counter)
        val n = minOf(BLOCK, input.size - offset)
        for (i in 0 until n) {
            out[offset + i] = (input[offset + i].toInt() xor stream[i].toInt()).toByte()
        }
        counter = inc32(counter)
        offset += n
    }
    return out
}

private fun ghash(
    hashKey: ByteArray,
    data: ByteArray,
): ByteArray {
    var y = ByteArray(BLOCK)
    var offset = 0
    while (offset < data.size) {
        val block = ByteArray(BLOCK)
        val n = minOf(BLOCK, data.size - offset)
        data.copyInto(block, startIndex = offset, endIndex = offset + n)
        y = gfMul(xor(y, block), hashKey)
        offset += n
    }
    val lengths = ByteArray(BLOCK)
    val bits = data.size.toLong() * 8
    for (i in 0 until 8) lengths[8 + i] = (bits ushr ((7 - i) * 8)).toByte()
    return gfMul(xor(y, lengths), hashKey)
}

private fun gfMul(
    x: ByteArray,
    y: ByteArray,
): ByteArray {
    val z = ByteArray(BLOCK)
    val v = x.copyOf()
    for (i in 0 until BLOCK) {
        val bits = y[i].toInt() and 0xff
        for (bit in 0 until 8) {
            if ((bits and (0x80 ushr bit)) != 0) xorInto(z, v)
            var carry = 0
            for (index in v.indices) {
                val value = v[index].toInt() and 0xff
                v[index] = ((value ushr 1) or carry).toByte()
                carry = (value and 1) shl 7
            }
            if (carry == 0x80) v[0] = (v[0].toInt() xor 0xe1).toByte()
        }
    }
    return z
}

private fun inc32(counter: ByteArray): ByteArray {
    val next = counter.copyOf()
    for (i in 15 downTo 12) {
        val sum = (next[i].toInt() and 0xff) + 1
        next[i] = sum.toByte()
        if (sum <= 0xff) break
    }
    return next
}

private fun xor(
    left: ByteArray,
    right: ByteArray,
): ByteArray {
    val out = left.copyOf()
    xorInto(out, right)
    return out
}

private fun xorInto(
    dst: ByteArray,
    src: ByteArray,
) {
    for (i in dst.indices) dst[i] = (dst[i].toInt() xor src[i].toInt()).toByte()
}
