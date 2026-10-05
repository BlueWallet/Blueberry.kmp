package io.bluewallet.blueberry.labels

import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.bitcoin.MnemonicCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExtendedFingerprintTest {
    @Test
    fun bluewallet_abandon_extended_fingerprint_starts_with_master_fingerprint() {
        // BlueWallet tests/unit/hd-segwit-bech32-wallet.test.js (also legacy, p2sh, taproot).
        val seed =
            MnemonicCode.toSeed(
                "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
                "",
            )
        val extended = extendedFingerprint(seed).toHex()

        assertEquals(40, extended.length)
        assertTrue(extended.startsWith("73c5da0a"), extended)
    }

    @Test
    fun bip32_extended_fingerprint_starts_with_parent_fingerprint_of_first_child() {
        // BIP32 test vectors 1–4. The first child's parent fingerprint is the master fingerprint.
        val vectors =
            listOf(
                "000102030405060708090a0b0c0d0e0f" to
                    "xpub68Gmy5EdvgibQVfPdqkBBCHxA5htiqg55crXYuXoQRKfDBFA1WEjWgP6LHhwBZeNK1VTsfTFUHCdrfp1bgwQ9xv5ski8PX9rL2dZXvgGDnw",
                "fffcf9f6f3f0edeae7e4e1dedbd8d5d2cfccc9c6c3c0bdbab7b4b1aeaba8a5a29f9c999693908d8a8784817e7b7875726f6c696663605d5a5754514e4b484542" to
                    "xpub69H7F5d8KSRgmmdJg2KhpAK8SR3DjMwAdkxj3ZuxV27CprR9LgpeyGmXUbC6wb7ERfvrnKZjXoUmmDznezpbZb7ap6r1D3tgFxHmwMkQTPH",
                "4b381541583be4423346c643850da4b320e46a87ae3d2a4e6da11eba819cd4acba45d239319ac14f863b8d5ab5a0d0c64d2e8a1e7d1457df2e5a3c51c73235be" to
                    "xpub68NZiKmJWnxxS6aaHmn81bvJeTESw724CRDs6HbuccFQN9Ku14VQrADWgqbhhTHBaohPX4CjNLf9fq9MYo6oDaPPLPxSb7gwQN3ih19Zm4Y",
                "3ddd5602285899a946114506157c7997e5444528f3003f6134712147db19b678" to
                    "xpub69AUMk3qDBi3uW1sXgjCmVjJ2G6WQoYSnNHyzkmdCHEhSZ4tBok37xfFEqHd2AddP56Tqp4o56AePAgCjYdvpW2PU2jbUPFKsav5ut6Ch1m",
            )

        vectors.forEach { (seedHex, childXpub) ->
            val extended = extendedFingerprint(hexToBytes(seedHex)).toHex()
            val parent =
                DeterministicWallet.ExtendedPublicKey
                    .decode(childXpub)
                    .second
                    .parent
            val short = parent.toString(16).padStart(8, '0')

            assertEquals(40, extended.length)
            assertTrue(extended.startsWith(short), extended)
        }
    }
}

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
