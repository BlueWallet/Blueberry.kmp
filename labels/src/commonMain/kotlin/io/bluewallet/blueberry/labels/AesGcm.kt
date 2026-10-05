package io.bluewallet.blueberry.labels

internal expect fun secureRandomBytes(size: Int): ByteArray

/** Returns ciphertext and the 16-byte GCM tag. [iv] is 12 bytes and [key] is 16 or 32 bytes. */
internal expect fun aesGcmEncrypt(
    key: ByteArray,
    iv: ByteArray,
    plaintext: ByteArray,
): Pair<ByteArray, ByteArray>

/** Throws [IllegalArgumentException] when the tag does not match. */
internal expect fun aesGcmDecrypt(
    key: ByteArray,
    iv: ByteArray,
    ciphertext: ByteArray,
    tag: ByteArray,
): ByteArray

/** AES-CBC with no padding. [plaintext] length must be a multiple of 16, and [iv] is 16 bytes. */
internal expect fun aesCbcEncrypt(
    key: ByteArray,
    iv: ByteArray,
    plaintext: ByteArray,
): ByteArray
