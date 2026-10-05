package io.bluewallet.blueberry.labels

internal expect fun secureRandomBytes(size: Int): ByteArray

/** Encrypts one 16-byte block with AES and no padding. */
internal expect fun aesBlockEncrypt(
    key: ByteArray,
    block: ByteArray,
): ByteArray

/** AES-CBC with no padding. [plaintext] length must be a multiple of 16, and [iv] is 16 bytes. */
internal expect fun aesCbcEncrypt(
    key: ByteArray,
    iv: ByteArray,
    plaintext: ByteArray,
): ByteArray
