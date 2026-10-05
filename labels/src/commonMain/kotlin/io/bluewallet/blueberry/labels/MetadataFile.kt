package io.bluewallet.blueberry.labels

import fr.acinq.bitcoin.Crypto

private val slip15Constant = hexToBytes("0123456789abcdeffedcba9876543210")

/** Hex filename, including `.mtdt`, for the SLIP-0015 metadata file of [accountKey]. */
fun metadataFilename(accountKey: String): String {
    val digest = slip15Digest(accountKey)
    return digest.copyOfRange(0, 32).toHex() + ".mtdt"
}

/**
 * SLIP-0015 metadata file: 12-byte IV, 16-byte GCM tag, then ciphertext.
 * [iv] must be 12 bytes. The default is a fresh random IV.
 */
fun encryptMetadataFile(
    accountKey: String,
    plaintext: ByteArray,
    iv: ByteArray = secureRandomBytes(12),
): ByteArray {
    require(iv.size == 12) { "metadata IV must be 12 bytes" }
    val (ciphertext, tag) = aesGcmEncrypt(slip15CipherKey(accountKey), iv, plaintext)
    return iv + tag + ciphertext
}

/** Decrypts a SLIP-0015 metadata file. Throws if the authentication tag does not match. */
fun decryptMetadataFile(
    accountKey: String,
    file: ByteArray,
): ByteArray {
    require(file.size >= 28) { "metadata file is too short" }
    val iv = file.copyOfRange(0, 12)
    val tag = file.copyOfRange(12, 28)
    val ciphertext = file.copyOfRange(28, file.size)
    return aesGcmDecrypt(slip15CipherKey(accountKey), iv, ciphertext, tag)
}

private fun slip15Digest(accountKey: String): ByteArray = Crypto.hmac512(accountKey.encodeToByteArray(), slip15Constant)

private fun slip15CipherKey(accountKey: String): ByteArray = slip15Digest(accountKey).copyOfRange(32, 64)

private fun ByteArray.toHex(): String =
    joinToString("") { byte ->
        val value = byte.toInt() and 0xff
        val hex = "0123456789abcdef"
        "${hex[value shr 4]}${hex[value and 0x0f]}"
    }

private fun hexToBytes(hex: String): ByteArray =
    ByteArray(hex.length / 2) { index ->
        hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
