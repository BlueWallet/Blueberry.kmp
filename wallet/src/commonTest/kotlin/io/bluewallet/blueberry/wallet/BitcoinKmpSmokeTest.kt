package io.bluewallet.blueberry.wallet

import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.bitcoin.MnemonicCode
import kotlin.test.Test
import kotlin.test.assertEquals

class BitcoinKmpSmokeTest {
    @Test
    fun abandon_seed_is_64_bytes_and_zpub_prefix_matches_slip0132() {
        val seed =
            MnemonicCode.toSeed(
                "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
                "",
            )
        assertEquals(64, seed.size)
        assertEquals(0x04b24746, DeterministicWallet.zpub)
    }
}
