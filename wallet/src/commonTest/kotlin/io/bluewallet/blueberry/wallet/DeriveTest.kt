package io.bluewallet.blueberry.wallet

import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.bitcoin.MnemonicCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeriveTest {
    @Test
    fun abandon_mnemonic_matches_bluewallet_addresses() {
        val wallet = deriveWatchWallet(ABANDON)
        val seed = MnemonicCode.toSeed(ABANDON, "")
        val zpub =
            DeterministicWallet
                .generate(seed)
                .derivePrivateKey(BIP84_ACCOUNT_PATH)
                .extendedPublicKey
                .encode(DeterministicWallet.zpub)
        assertEquals(BLUE_ZPUB, zpub)
        assertEquals(INITIAL_WATCH_COUNT * 2, wallet.addresses.size)
        assertEquals(BLUE_EXTERNAL_0, wallet.addresses[0].address)
        assertEquals(BLUE_EXTERNAL_0_SCRIPT, scriptHex(wallet.addresses[0].scriptPubKey))
        assertEquals("m/84'/0'/0'/0/0", wallet.addresses[0].path)
        assertEquals("m/84'/0'/0'/1/0", wallet.addresses[INITIAL_WATCH_COUNT].path)
        assertEquals(BLUE_INTERNAL_0, wallet.addresses[INITIAL_WATCH_COUNT].address)
    }

    @Test
    fun small_gaps_and_zpub_match_mnemonic() {
        val small = deriveWatchWallet(ABANDON, WatchGaps(2, 1))
        assertEquals(listOf(BLUE_EXTERNAL_0, BLUE_EXTERNAL_1, BLUE_INTERNAL_0), small.addresses.map { it.address })
        val fromMnemonic = deriveWatchWallet(ABANDON, WatchGaps(3, 2))
        val fromZpub = deriveWatchWallet(BLUE_ZPUB, WatchGaps(3, 2))
        assertEquals(BLUE_ZPUB, fromZpub.secret)
        assertEquals(fromMnemonic.addresses.map { it.address }, fromZpub.addresses.map { it.address })
        assertEquals(fromMnemonic.addresses.map { it.path }, fromZpub.addresses.map { it.path })
        assertEquals(fromMnemonic.scripts.map { scriptHex(it) }, fromZpub.scripts.map { scriptHex(it) })
        assertEquals(SEEDSIGNER_EXTERNAL_0, deriveWatchWallet(SEEDSIGNER_ZPUB, WatchGaps(1, 0)).addresses[0].address)
        assertEquals(5, deriveWatchWallet(ABANDON, WatchGaps(3, 2)).addresses.size)
        assertEquals(8, deriveWatchWallet(ABANDON, 4).addresses.size)
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
