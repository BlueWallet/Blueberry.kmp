package io.bluewallet.blueberry.labels

import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

private val random = SecureRandom()

internal actual fun secureRandomBytes(size: Int): ByteArray {
    val out = ByteArray(size)
    random.nextBytes(out)
    return out
}

internal actual fun aesGcmEncrypt(
    key: ByteArray,
    iv: ByteArray,
    plaintext: ByteArray,
): Pair<ByteArray, ByteArray> {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
    val combined = cipher.doFinal(plaintext)
    val tagAt = combined.size - 16
    return combined.copyOfRange(0, tagAt) to combined.copyOfRange(tagAt, combined.size)
}

internal actual fun aesGcmDecrypt(
    key: ByteArray,
    iv: ByteArray,
    ciphertext: ByteArray,
    tag: ByteArray,
): ByteArray {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
    return try {
        cipher.doFinal(ciphertext + tag)
    } catch (_: AEADBadTagException) {
        throw IllegalArgumentException("metadata authentication failed")
    }
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
