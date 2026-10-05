package io.bluewallet.blueberry.labels

import commonCryptoSpi.CCCryptorGCMOneshotDecrypt
import commonCryptoSpi.CCCryptorGCMOneshotEncrypt
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreCrypto.CCCrypt
import platform.CoreCrypto.kCCAlgorithmAES
import platform.CoreCrypto.kCCEncrypt
import platform.CoreCrypto.kCCSuccess
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecSuccess
import platform.Security.kSecRandomDefault

@OptIn(ExperimentalForeignApi::class)
internal actual fun secureRandomBytes(size: Int): ByteArray {
    val out = ByteArray(size)
    if (out.isEmpty()) return out
    val status =
        out.usePinned { pinned ->
            SecRandomCopyBytes(kSecRandomDefault, out.size.convert(), pinned.addressOf(0))
        }
    check(status == errSecSuccess) { "SecRandomCopyBytes failed: $status" }
    return out
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun aesGcmEncrypt(
    key: ByteArray,
    iv: ByteArray,
    plaintext: ByteArray,
): Pair<ByteArray, ByteArray> {
    val ciphertext = ByteArray(plaintext.size)
    val tag = ByteArray(16)
    val status =
        key.usePinned { keyPin ->
            iv.usePinned { ivPin ->
                plaintext.usePinned { plainPin ->
                    ciphertext.usePinned { outPin ->
                        tag.usePinned { tagPin ->
                            CCCryptorGCMOneshotEncrypt(
                                kCCAlgorithmAES,
                                keyPin.addressOf(0),
                                key.size.convert(),
                                ivPin.addressOf(0),
                                iv.size.convert(),
                                null,
                                0.convert(),
                                if (plaintext.isEmpty()) null else plainPin.addressOf(0),
                                plaintext.size.convert(),
                                if (ciphertext.isEmpty()) null else outPin.addressOf(0),
                                tagPin.addressOf(0),
                                tag.size.convert(),
                            )
                        }
                    }
                }
            }
        }
    check(status == 0u) { "AES-GCM encrypt failed: $status" }
    return ciphertext to tag
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun aesGcmDecrypt(
    key: ByteArray,
    iv: ByteArray,
    ciphertext: ByteArray,
    tag: ByteArray,
): ByteArray {
    val plaintext = ByteArray(ciphertext.size)
    val status =
        key.usePinned { keyPin ->
            iv.usePinned { ivPin ->
                ciphertext.usePinned { cipherPin ->
                    tag.usePinned { tagPin ->
                        plaintext.usePinned { outPin ->
                            CCCryptorGCMOneshotDecrypt(
                                kCCAlgorithmAES,
                                keyPin.addressOf(0),
                                key.size.convert(),
                                ivPin.addressOf(0),
                                iv.size.convert(),
                                null,
                                0.convert(),
                                if (ciphertext.isEmpty()) null else cipherPin.addressOf(0),
                                ciphertext.size.convert(),
                                if (plaintext.isEmpty()) null else outPin.addressOf(0),
                                tagPin.addressOf(0),
                                tag.size.convert(),
                            )
                        }
                    }
                }
            }
        }
    require(status == 0u) { "metadata authentication failed" }
    return plaintext
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun aesCbcEncrypt(
    key: ByteArray,
    iv: ByteArray,
    plaintext: ByteArray,
): ByteArray {
    val out = ByteArray(plaintext.size)
    memScoped {
        val moved = alloc<ULongVar>()
        val status =
            key.usePinned { keyPin ->
                iv.usePinned { ivPin ->
                    plaintext.usePinned { inPin ->
                        out.usePinned { outPin ->
                            CCCrypt(
                                kCCEncrypt,
                                kCCAlgorithmAES,
                                0u,
                                keyPin.addressOf(0),
                                key.size.convert(),
                                ivPin.addressOf(0),
                                inPin.addressOf(0),
                                plaintext.size.convert(),
                                outPin.addressOf(0),
                                out.size.convert(),
                                moved.ptr,
                            )
                        }
                    }
                }
            }
        check(status == kCCSuccess && moved.value.toInt() == plaintext.size) {
            "AES-CBC encrypt failed: $status"
        }
    }
    return out
}
