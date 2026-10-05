package io.bluewallet.blueberry.labels

import fr.acinq.bitcoin.MnemonicCode
import kotlin.test.Test
import kotlin.test.assertEquals

class AccountKeyTest {
    @Test
    fun slip14_seed_derives_the_published_master_key() {
        val seed = MnemonicCode.toSeed("all all all all all all all all all all all all", "")
        assertEquals(SLIP15_MASTER_KEY, labelingMasterKey(seed).toHex())
    }

    @Test
    fun slip14_account_zero_derives_the_published_account_key() {
        val seed = MnemonicCode.toSeed("all all all all all all all all all all all all", "")
        assertEquals(
            "v5kCxSKLTsnwmgPBeaRyFDWeG9zXouF34L72763zjLrS4LWy8",
            labelingAccountKey(seed),
        )
    }
}

private const val SLIP15_MASTER_KEY = "20c8bf0701213cdcf4c2f56fd0096c1772322d42fb9c4d0ddf6bb122d713d2f3"

private fun ByteArray.toHex(): String =
    joinToString("") { byte ->
        val value = byte.toInt() and 0xff
        val hex = "0123456789abcdef"
        "${hex[value shr 4]}${hex[value and 0x0f]}"
    }
