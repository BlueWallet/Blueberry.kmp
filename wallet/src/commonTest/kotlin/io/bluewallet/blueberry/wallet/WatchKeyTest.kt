package io.bluewallet.blueberry.wallet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WatchKeyTest {
    @Test
    fun account_key_prefix_picks_the_script() {
        assertEquals(AddressScriptType.P2PKH, parseWatchKey(ABANDON_BIP44_XPUB).scriptType)
        assertEquals(AddressScriptType.P2SH_P2WPKH, parseWatchKey(ABANDON_BIP49_YPUB).scriptType)
        assertEquals(AddressScriptType.P2WPKH, parseWatchKey(BLUE_ZPUB).scriptType)
        assertNull(parseWatchKey(BLUE_ZPUB).origin)
    }

    @Test
    fun bare_key_uses_the_standard_account_path_and_relative_origin() {
        val key = parseWatchKey(ABANDON_BIP44_XPUB)
        assertEquals("m/44'/0'/0'", key.accountPath)
        assertEquals(key.account.fingerprint(), key.bip32Fingerprint())
        assertEquals(listOf(1L, 5L), key.bip32Path(change = true, index = 5).path)
    }

    @Test
    fun hd_script_types_per_secret_kind() {
        assertEquals(HD_SCRIPT_TYPES, parseWalletSecret(ABANDON).hdScriptTypes())
        assertEquals(listOf(AddressScriptType.P2SH_P2WPKH), parseWalletSecret(ABANDON_BIP49_YPUB).hdScriptTypes())
        assertEquals(emptyList(), parseWalletSecret(WIF_BECH32).hdScriptTypes())
    }
}
