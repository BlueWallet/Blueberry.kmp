package io.bluewallet.blueberry.labels

import fr.acinq.bitcoin.Base58
import fr.acinq.bitcoin.Base58Check
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.bitcoin.crypto.Digest
import fr.acinq.bitcoin.crypto.hmac

private val labelingPlaintext = hexToBytes("fedcba98765432100123456789abcdeffedcba98765432100123456789abcdef")

/**
 * SLIP-0015 master secret: AES-CBC of a fixed block under the key from `m/10015'/0'`,
 * with encrypt and decrypt confirmation both bound in.
 */
fun labelingMasterKey(seed: ByteArray): ByteArray {
    val privateKey =
        DeterministicWallet
            .generate(seed)
            .derivePrivateKey("m/10015'/0'")
            .privateKey
            .value
            .toByteArray()
    val secret = Crypto.hmac512(privateKey, "Enable labeling?E1D1".encodeToByteArray())
    return aesCbcEncrypt(secret.copyOfRange(0, 32), secret.copyOfRange(32, 48), labelingPlaintext)
}

/** SLIP-0015 account key for mainnet account [account] (default 0), derived from [seed]. */
fun labelingAccountKey(
    seed: ByteArray,
    account: Int = 0,
): String {
    require(account >= 0) { "account must be >= 0" }
    val xpub =
        DeterministicWallet
            .generate(seed)
            .derivePrivateKey("m/44'/0'/$account'")
            .extendedPublicKey
            .encode(false)
    val digest = Digest.sha256().hmac(labelingMasterKey(seed), xpub.encodeToByteArray(), 64)
    return Base58.encode(digest + Base58Check.checksum(digest))
}

private fun hexToBytes(hex: String): ByteArray =
    ByteArray(hex.length / 2) { index ->
        hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
