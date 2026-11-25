package io.bluewallet.blueberry.wallet

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class UrAccountsTest {
    private fun descriptors(
        type: String,
        hex: String,
    ): List<String> = parseUrAccounts(type, hexToBytes(hex)).map { it.descriptor }

    /** A single-part UR through the decoder, as the camera reads it. */
    private fun scanned(ur: String): List<String> {
        val complete = assertIs<UrReceive.Complete>(UrDecoder(ACCOUNT_UR_TYPES).receive(ur))
        return parseUrAccounts("crypto-account", complete.message).map { it.descriptor }
    }

    @Test
    fun keystone_crypto_account_keeps_payload_order_with_taproot_first() {
        assertEquals(
            listOf(KEYSTONE_TR_DESCRIPTOR, KEYSTONE_WPKH_DESCRIPTOR, KEYSTONE_PKH_DESCRIPTOR, KEYSTONE_SH_WPKH_DESCRIPTOR),
            descriptors("crypto-account", KEYSTONE_ACCOUNT_HEAD + KEYSTONE_TR + KEYSTONE_WPKH + KEYSTONE_PKH + KEYSTONE_SH_WPKH),
        )
    }

    @Test
    fun a_wsh_output_is_skipped_on_its_own() {
        val wsh = KEYSTONE_WPKH.replaceFirst("d90194", "d90191")
        assertEquals(listOf(KEYSTONE_TR_DESCRIPTOR), descriptors("crypto-account", "a2011a73c5da0a0282" + wsh + KEYSTONE_TR))
    }

    @Test
    fun an_output_without_an_hd_key_rejects_the_whole_account() {
        assertEquals(emptyList(), descriptors("crypto-account", "a2011a73c5da0a0282" + KEYSTONE_WPKH + PKH_EC_KEY))
    }

    @Test
    fun keystone_multi_accounts_keeps_only_the_bitcoin_keys() {
        assertEquals(KEYSTONE_MULTI_DESCRIPTORS, descriptors("crypto-multi-accounts", KEYSTONE_MULTI_ACCOUNTS))
    }

    @Test
    fun each_crypto_hdkey_gives_its_account_with_the_origin_fingerprint() {
        assertEquals(KEYSTONE_MULTI_DESCRIPTORS, KEYSTONE_HDKEYS.flatMap { descriptors("crypto-hdkey", it) })
    }

    @Test
    fun sparrow_origins_without_depth_keep_single_sig_and_drop_multisig() {
        assertEquals(SPARROW_DESCRIPTORS, scanned(SPARROW_ACCOUNT_UR))
    }

    @Test
    fun older_bluewallet_vectors() {
        assertEquals(listOf(BLUEWALLET_BIP84_DESCRIPTOR), scanned(BLUEWALLET_BIP84_ACCOUNT_UR))
        assertEquals(
            listOf(KEYSTONE_WPKH_DESCRIPTOR, KEYSTONE_SH_WPKH_DESCRIPTOR, KEYSTONE_PKH_DESCRIPTOR),
            scanned(BLUEWALLET_ABANDON_ACCOUNTS_UR),
        )
    }

    @Test
    fun malformed_cbor_throws() {
        assertFailsWith<IllegalArgumentException> { descriptors("crypto-multi-accounts", KEYSTONE_MULTI_ACCOUNTS.dropLast(40)) }
        assertFailsWith<IllegalArgumentException> { descriptors("crypto-account", "a1") }
    }

    @Test
    fun a_private_key_rejects_the_whole_account() {
        val private = KEYSTONE_WPKH.replaceFirst("02f4", "02f5")
        assertEquals(emptyList(), descriptors("crypto-account", "a2011a73c5da0a0282" + private + KEYSTONE_TR))
    }

    @Test
    fun keys_off_the_mainnet_account_path_are_skipped_one_by_one() {
        val testnet = KEYSTONE_WPKH.replaceFirst("01861854f500f500f5", "01861854f501f500f5")
        val depth4 = KEYSTONE_WPKH.replaceFirst("021a73c5da0a0303", "021a73c5da0a0304")
        val wrongPurpose = KEYSTONE_WPKH.replaceFirst("1854f5", "182cf5")
        for (output in listOf(testnet, depth4, wrongPurpose)) {
            assertEquals(listOf(KEYSTONE_TR_DESCRIPTOR), descriptors("crypto-account", "a2011a73c5da0a0282" + output + KEYSTONE_TR))
        }
    }

    @Test
    fun indefinite_lengths_and_deep_nesting_throw() {
        assertFailsWith<IllegalArgumentException> { descriptors("crypto-account", "bf01ff") }
        assertFailsWith<IllegalArgumentException> { descriptors("crypto-account", "81".repeat(20) + "00") }
    }

    @Test
    fun every_account_imports_as_its_descriptor() {
        val accounts =
            parseUrAccounts("crypto-account", hexToBytes(KEYSTONE_ACCOUNT_HEAD + KEYSTONE_TR + KEYSTONE_WPKH + KEYSTONE_PKH + KEYSTONE_SH_WPKH)) +
                parseUrAccounts("crypto-multi-accounts", hexToBytes(KEYSTONE_MULTI_ACCOUNTS))
        for (account in accounts) {
            val parsed = parseWalletSecret(account.descriptor)
            assertEquals(WalletSecretKind.DESCRIPTOR, parsed.kind)
            val key = parsed.watchKey()!!
            assertEquals(account.scriptType, key.scriptType)
            assertEquals(KeyOrigin(account.fingerprint, account.path), key.origin)
        }
        assertEquals(2, accounts.count { parseWalletSecret(it.descriptor).watchKey()!!.scriptType == AddressScriptType.P2TR })
    }

    @Test
    fun animated_multi_accounts_round_trip_through_the_decoder() {
        val encoder = UrEncoder(hexToBytes(KEYSTONE_MULTI_ACCOUNTS), "crypto-multi-accounts", 400)
        val parts = List(encoder.fragmentsLength * 2) { encoder.nextPart().uppercase() }.shuffled(Random(7))
        val decoder = UrDecoder(ACCOUNT_UR_TYPES)
        val complete = parts.map { decoder.receive(it) }.filterIsInstance<UrReceive.Complete>().first()
        assertEquals("crypto-multi-accounts", decoder.type)
        assertEquals(KEYSTONE_MULTI_DESCRIPTORS, parseUrAccounts(decoder.type!!, complete.message).map { it.descriptor })
    }
}
