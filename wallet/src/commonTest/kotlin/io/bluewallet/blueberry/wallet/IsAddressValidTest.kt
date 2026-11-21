package io.bluewallet.blueberry.wallet

import fr.acinq.bitcoin.Bitcoin
import fr.acinq.bitcoin.Block
import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.bitcoin.MnemonicCode
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IsAddressValidTest {
    @Test
    fun accepts_mainnet_p2wpkh_p2pkh_p2sh_and_taproot() {
        val seed = MnemonicCode.toSeed(ABANDON, "")
        val root = DeterministicWallet.generate(seed)
        val nativeKey = root.derivePrivateKey("m/84'/0'/0'/0/0").publicKey
        val nestedKey = root.derivePrivateKey("m/49'/0'/0'/0/0").publicKey
        val native = Bitcoin.computeP2WpkhAddress(nativeKey, Block.LivenetGenesisBlock.hash)
        val nested = Bitcoin.computeP2ShOfP2WpkhAddress(nestedKey, Block.LivenetGenesisBlock.hash)
        assertTrue(isAddressValid(native))
        assertTrue(isAddressValid(GENESIS_P2PKH))
        assertTrue(isAddressValid(nested))
        assertTrue(isAddressValid(BIP341_TAPROOT))
    }

    @Test
    fun rejects_garbage_testnet_bad_checksum_and_witness_v2() {
        assertFalse(isAddressValid(""))
        assertFalse(isAddressValid("not-an-address"))
        assertFalse(isAddressValid("tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx"))
        assertFalse(isAddressValid("bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4x"))
        assertFalse(isAddressValid("bc1zw508d6qejxtdg4y5r3zarvaryvaxxpcs"))
    }

    @Test
    fun trims_whitespace_around_a_valid_address() {
        assertTrue(isAddressValid("  $BLUE_EXTERNAL_0  "))
    }

    @Test
    fun address_from_output_script_is_null_for_short_or_unknown_scripts() {
        val hash = ByteArray(20) { 7 }
        val p2pkh = byteArrayOf(0x76, 0xa9.toByte(), 0x14) + hash + byteArrayOf(0x88.toByte(), 0xac.toByte())
        val p2sh = byteArrayOf(0xa9.toByte(), 0x14) + hash + byteArrayOf(0x87.toByte())
        assertTrue(addressFromOutputScript(p2pkh)!!.startsWith("1"))
        assertTrue(addressFromOutputScript(p2sh)!!.startsWith("3"))
        assertNull(addressFromOutputScript(byteArrayOf(0x6a, 0x01, 0x00)))
        assertNull(addressFromOutputScript(ByteArray(0)))
    }
}
