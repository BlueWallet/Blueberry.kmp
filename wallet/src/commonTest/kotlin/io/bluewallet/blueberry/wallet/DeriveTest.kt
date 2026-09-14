package io.bluewallet.blueberry.wallet

import fr.acinq.bitcoin.Bitcoin
import fr.acinq.bitcoin.Block
import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.bitcoin.MnemonicCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeriveTest {
    @Test
    fun abandon_mnemonic_matches_bip84_bip44_bip86_vectors() {
        val wallet = deriveWatchWallet(ABANDON, WatchGaps(2, 1))
        assertEquals(WatchWalletKind.BIP84, wallet.kind)
        assertEquals(12, wallet.addresses.size)
        assertEquals(BLUE_EXTERNAL_0, wallet.hd(AddressScriptType.P2WPKH, 0).address)
        assertEquals("m/84'/0'/0'/0/0", wallet.hd(AddressScriptType.P2WPKH, 0).path)
        assertEquals(BLUE_INTERNAL_0, wallet.hd(AddressScriptType.P2WPKH, 0, change = true).address)
        assertEquals(BIP44_ABANDON_EXT_0, wallet.hd(AddressScriptType.P2PKH, 0).address)
        assertEquals("m/44'/0'/0'/0/0", wallet.hd(AddressScriptType.P2PKH, 0).path)
        assertEquals(BIP86_ABANDON_EXT_0, wallet.hd(AddressScriptType.P2TR, 0).address)
        assertEquals(BIP86_ABANDON_EXT_1, wallet.hd(AddressScriptType.P2TR, 1).address)
        assertEquals(BIP86_ABANDON_INT_0, wallet.hd(AddressScriptType.P2TR, 0, change = true).address)
        assertEquals("m/86'/0'/0'/1/0", wallet.hd(AddressScriptType.P2TR, 0, change = true).path)
    }

    @Test
    fun honey_mnemonic_matches_bluewallet_bip49() {
        val wallet = deriveWatchWallet(HONEY_BIP49, WatchGaps(2, 1))
        assertEquals(BIP49_HONEY_EXT_0, wallet.hd(AddressScriptType.P2SH_P2WPKH, 0).address)
        assertEquals(BIP49_HONEY_EXT_1, wallet.hd(AddressScriptType.P2SH_P2WPKH, 1).address)
        assertEquals(BIP49_HONEY_INT_0, wallet.hd(AddressScriptType.P2SH_P2WPKH, 0, change = true).address)
        assertEquals("m/49'/0'/0'/0/0", wallet.hd(AddressScriptType.P2SH_P2WPKH, 0).path)
    }

    @Test
    fun bluewallet_bip44_account_1_path_matches() {
        val seed = MnemonicCode.toSeed(ABANDON, "")
        val master = DeterministicWallet.generate(seed)
        val ext = master.derivePrivateKey("m/44'/0'/1'/0/0").publicKey
        val intern = master.derivePrivateKey("m/44'/0'/1'/1/0").publicKey
        assertEquals(BIP44_ACCOUNT1_EXT_0, Bitcoin.computeP2PkhAddress(ext, Block.LivenetGenesisBlock.hash))
        assertEquals(BIP44_ACCOUNT1_INT_0, Bitcoin.computeP2PkhAddress(intern, Block.LivenetGenesisBlock.hash))
    }

    @Test
    fun default_mnemonic_watch_is_160_scripts() {
        val wallet = deriveWatchWallet(ABANDON)
        assertEquals(INITIAL_WATCH_COUNT * 2 * 4, wallet.addresses.size)
    }

    @Test
    fun small_gaps_and_zpub_match_mnemonic_bip84_only() {
        val small = deriveWatchWallet(ABANDON, WatchGaps(2, 1))
        assertEquals(12, small.addresses.size)
        val fromMnemonic = deriveWatchWallet(ABANDON, WatchGaps(3, 2))
        val fromZpub = deriveWatchWallet(BLUE_ZPUB, WatchGaps(3, 2))
        assertEquals(5, fromZpub.addresses.size)
        assertEquals(
            fromMnemonic.addresses.filter { it.scriptType == AddressScriptType.P2WPKH }.map { it.address },
            fromZpub.addresses.map { it.address },
        )
        assertEquals(SEEDSIGNER_EXTERNAL_0, deriveWatchWallet(SEEDSIGNER_ZPUB, WatchGaps(1, 0)).addresses[0].address)
        assertEquals(20, deriveWatchWallet(ABANDON, WatchGaps(3, 2)).addresses.size)
        assertEquals(32, deriveWatchWallet(ABANDON, 4).addresses.size)
    }

    @Test
    fun wif_unwraps_four_types() {
        val w = deriveWatchWallet(WIF_BECH32)
        assertEquals(WatchWalletKind.WIF, w.kind)
        assertEquals(4, w.addresses.size)
        assertEquals(ADDR_BECH32, w.addresses.first { it.scriptType == AddressScriptType.P2WPKH }.address)
        assertEquals("1DVNNDU4sooWp6St9baaM8XQC9VYpwVcDi", w.addresses.first { it.scriptType == AddressScriptType.P2PKH }.address)
        assertEquals("3QS6GoKXFCyhTRi7MqQ8vCGp8qxDRyk43J", w.addresses.first { it.scriptType == AddressScriptType.P2SH_P2WPKH }.address)
        assertTrue(
            w.addresses
                .first { it.scriptType == AddressScriptType.P2TR }
                .address
                .startsWith("bc1p"),
        )
        assertEquals(ADDR_LEGACY, deriveWatchWallet(WIF_LEGACY).addresses.first { it.scriptType == AddressScriptType.P2PKH }.address)
        assertEquals(ADDR_P2SH, deriveWatchWallet(WIF_P2SH).addresses.first { it.scriptType == AddressScriptType.P2SH_P2WPKH }.address)
        assertEquals(ADDR_TAPROOT, deriveWatchWallet(WIF_TAPROOT).addresses.first { it.scriptType == AddressScriptType.P2TR }.address)
        val a = deriveWatchWallet(WIF_BECH32, 1)
        val b = deriveWatchWallet(WIF_BECH32, WatchGaps(500, 500))
        assertEquals(a.addresses.map { it.address }, b.addresses.map { it.address })
    }

    @Test
    fun address_watch_is_one_script() {
        val w = deriveWatchWallet(ADDR_BECH32)
        assertEquals(WatchWalletKind.ADDRESS, w.kind)
        assertEquals(1, w.addresses.size)
        assertEquals(ADDR_BECH32, w.addresses[0].address)
        assertEquals("address/0", w.addresses[0].path)
        assertEquals(AddressScriptType.P2WPKH, w.addresses[0].scriptType)
        assertEquals(scriptHex(outputScriptFromAddress(ADDR_BECH32)), scriptHex(w.addresses[0].scriptPubKey))
        assertEquals(AddressScriptType.P2PKH, deriveWatchWallet(ADDR_LEGACY).addresses[0].scriptType)
        assertEquals(AddressScriptType.P2SH_P2WPKH, deriveWatchWallet(ADDR_P2SH).addresses[0].scriptType)
        assertEquals(AddressScriptType.P2TR, deriveWatchWallet(ADDR_TAPROOT).addresses[0].scriptType)
        assertEquals(1, deriveWatchWallet(ADDR_BECH32, 1).addresses.size)
        assertEquals(1, deriveWatchWallet(ADDR_BECH32, WatchGaps(500, 500)).addresses.size)
    }
}
