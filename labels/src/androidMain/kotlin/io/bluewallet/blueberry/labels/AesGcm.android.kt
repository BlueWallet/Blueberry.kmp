package io.bluewallet.blueberry.labels

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

private val random = SecureRandom()

internal actual fun secureRandomBytes(size: Int): ByteArray {
    val out = ByteArray(size)
    random.nextBytes(out)
    return out
}

internal actual fun aesBlockEncrypt(
    key: ByteArray,
    block: ByteArray,
): ByteArray {
    val cipher = Cipher.getInstance("AES/ECB/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
    return cipher.doFinal(block)
}

internal actual fun aesCbcEncrypt(
    key: ByteArray,
    iv: ByteArray,
    plaintext: ByteArray,
): ByteArray {
    val cipher = Cipher.getInstance("AES/CBC/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
    return cipher.doFinal(plaintext)
}
