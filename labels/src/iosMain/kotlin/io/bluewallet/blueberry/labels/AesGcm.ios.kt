package io.bluewallet.blueberry.labels

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
import platform.CoreCrypto.kCCOptionECBMode
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
internal actual fun aesBlockEncrypt(
    key: ByteArray,
    block: ByteArray,
): ByteArray {
    val out = ByteArray(block.size)
    memScoped {
        val moved = alloc<ULongVar>()
        val status =
            key.usePinned { keyPin ->
                block.usePinned { inPin ->
                    out.usePinned { outPin ->
                        CCCrypt(
                            kCCEncrypt,
                            kCCAlgorithmAES,
                            kCCOptionECBMode,
                            keyPin.addressOf(0),
                            key.size.convert(),
                            null,
                            inPin.addressOf(0),
                            block.size.convert(),
                            outPin.addressOf(0),
                            out.size.convert(),
                            moved.ptr,
                        )
                    }
                }
            }
        check(status == kCCSuccess && moved.value.toInt() == block.size) { "AES block encrypt failed: $status" }
    }
    return out
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
