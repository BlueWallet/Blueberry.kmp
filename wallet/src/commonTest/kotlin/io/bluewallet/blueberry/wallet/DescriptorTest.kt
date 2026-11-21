package io.bluewallet.blueberry.wallet

import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.bitcoin.MnemonicCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class DescriptorTest {
    private fun rejects(
        message: String,
        input: String,
    ) {
        val error = assertFailsWith<IllegalArgumentException> { parseWalletSecret(input) }
        assertEquals(message, error.message, input)
        assertFalse(error.message!!.contains("pub6"), "message must not echo the key")
    }

    private val account84 =
        DeterministicWallet.generate(MnemonicCode.toSeed(ABANDON, "")).derivePrivateKey(BIP84_ACCOUNT_PATH)

    @Test
    fun taproot_descriptor_with_origin_and_multipath() {
        val parsed = parseWalletSecret(TR_ABANDON_DESCRIPTOR)
        assertEquals(ParsedWalletSecret(WalletSecretKind.DESCRIPTOR, TR_ABANDON_DESCRIPTOR), parsed)
        val key = parseWatchKey(parsed.value)
        assertEquals(AddressScriptType.P2TR, key.scriptType)
        assertEquals(parseWatchKey(ABANDON_BIP44_XPUB).account.depth, key.account.depth)
        assertEquals(DeterministicWallet.ExtendedPublicKey.decode(ABANDON_BIP86_XPUB).second, key.account)
        assertEquals(ABANDON_FINGERPRINT, key.origin!!.fingerprint)
        assertEquals("m/86'/0'/0'", key.accountPath)
        assertEquals(ABANDON_FINGERPRINT, key.bip32Fingerprint())
        assertEquals("m/86'/0'/0'/1/4", key.bip32Path(change = true, index = 4).asString('\''))
    }

    @Test
    fun every_wrapper_maps_to_its_script() {
        assertEquals(AddressScriptType.P2PKH, parseWatchKey(PKH_ABANDON_DESCRIPTOR).scriptType)
        assertEquals(AddressScriptType.P2WPKH, parseWatchKey(WPKH_BARE_DESCRIPTOR).scriptType)
        assertEquals(AddressScriptType.P2TR, parseWatchKey(TR_BARE_DESCRIPTOR).scriptType)
        // ypub inside a descriptor, origin without a path suffix, no checksum.
        val nested = parseWatchKey("sh(wpkh([73c5da0a/49'/0'/0']$ABANDON_BIP49_YPUB))")
        assertEquals(AddressScriptType.P2SH_P2WPKH, nested.scriptType)
        assertEquals("m/49'/0'/0'", nested.accountPath)
    }

    @Test
    fun bare_descriptor_key_has_no_origin_and_uses_standard_path() {
        val key = parseWatchKey(TR_BARE_DESCRIPTOR)
        assertNull(key.origin)
        assertEquals("m/86'/0'/0'", key.accountPath)
        assertEquals(key.account.fingerprint(), key.bip32Fingerprint())
    }

    @Test
    fun two_line_receive_and_change_pair_in_any_order() {
        val pasted = "  $WPKH_ABANDON_CHANGE_DESCRIPTOR \n\n $WPKH_ABANDON_RECEIVE_DESCRIPTOR  "
        val parsed = parseWalletSecret(pasted)
        assertEquals(WalletSecretKind.DESCRIPTOR, parsed.kind)
        assertEquals("$WPKH_ABANDON_CHANGE_DESCRIPTOR\n$WPKH_ABANDON_RECEIVE_DESCRIPTOR", parsed.value)
        val key = parseWatchKey(parsed.value)
        assertEquals("m/84'/0'/0'", key.accountPath)
        assertEquals(AddressScriptType.P2WPKH, key.scriptType)
        assertEquals(parseWatchKey(TR_ABANDON_DESCRIPTOR).scriptType, parseWatchKey("$TR_ABANDON_RECEIVE_DESCRIPTOR\n$TR_ABANDON_CHANGE_DESCRIPTOR").scriptType)
    }

    @Test
    fun checksum_is_optional_but_must_match() {
        assertEquals(WalletSecretKind.DESCRIPTOR, parseWalletSecret(TR_ABANDON_DESCRIPTOR.substringBefore('#')).kind)
        rejects("descriptor checksum does not match", TR_ABANDON_DESCRIPTOR.dropLast(1) + "q")
        rejects("descriptor checksum does not match", TR_ABANDON_DESCRIPTOR.replace("/<0;1>/*", "/<1;0>/*"))
    }

    @Test
    fun rejects_unsupported_shapes() {
        val x = ABANDON_BIP84_XPUB
        rejects("multisig descriptors are not supported", "wsh(multi(1,$x/<0;1>/*,$ABANDON_BIP86_XPUB/<0;1>/*))")
        rejects("multisig descriptors are not supported", "sh(sortedmulti(1,$x,$ABANDON_BIP86_XPUB))")
        rejects("only pkh, sh(wpkh), wpkh and tr descriptors are supported", "wsh(pkh($x))")
        rejects("only pkh, sh(wpkh), wpkh and tr descriptors are supported", "sh(pkh($x))")
        rejects("only pkh, sh(wpkh), wpkh and tr descriptors are supported", "combo($x)")
        rejects("only pkh, sh(wpkh), wpkh and tr descriptors are supported", "addr($BLUE_EXTERNAL_0)")
        rejects("taproot script trees are not supported", "tr($ABANDON_BIP86_XPUB,{pk($x)})")
    }

    @Test
    fun rejects_keys_that_are_not_mainnet_account_xpubs() {
        rejects("descriptors with private keys are not supported", "wpkh(${account84.encode(DeterministicWallet.xprv)})")
        rejects("descriptors with private keys are not supported", "wpkh($WIF_BECH32)")
        rejects("only mainnet descriptor keys are supported", "wpkh(${account84.extendedPublicKey.encode(DeterministicWallet.tpub)})")
        rejects("descriptor key must be an xpub", "wpkh(02${"11".repeat(32)})")
        val master = DeterministicWallet.generate(MnemonicCode.toSeed(ABANDON, "")).extendedPublicKey
        rejects("descriptor key must be account-level (depth 3)", "wpkh(${master.encode(DeterministicWallet.xpub)})")
        rejects("invalid xpub", "wpkh(${x84()}x)")
    }

    private fun x84() = ABANDON_BIP84_XPUB.dropLast(1)

    @Test
    fun rejects_unsupported_paths_and_origins() {
        val x = ABANDON_BIP84_XPUB
        rejects("hardened derivation after the xpub is not supported", "wpkh($x/0'/*)")
        rejects("hardened derivation after the xpub is not supported", "wpkh($x/<0;1>/*h)")
        rejects("descriptor path must be /<0;1>/* or none", "wpkh($x/*)")
        rejects("descriptor path must be /<0;1>/* or none", "wpkh($x/2/*)")
        rejects("key origin path must be the account path", "wpkh([73c5da0a/84'/0']$x)")
        rejects("invalid key origin", "wpkh([73c5da0z/84'/0'/0']$x)")
    }

    @Test
    fun rejects_single_chains_and_mismatched_pairs() {
        val pair = "add the matching /0/* and /1/* descriptors, or use /<0;1>/*"
        rejects(pair, WPKH_ABANDON_RECEIVE_DESCRIPTOR)
        rejects(pair, "$WPKH_ABANDON_RECEIVE_DESCRIPTOR\n$WPKH_ABANDON_RECEIVE_DESCRIPTOR")
        rejects(pair, "$TR_ABANDON_DESCRIPTOR\n$TR_ABANDON_CHANGE_DESCRIPTOR")
        rejects("receive and change descriptors must use the same key", "$TR_ABANDON_RECEIVE_DESCRIPTOR\n$WPKH_ABANDON_CHANGE_DESCRIPTOR")
        rejects(
            "paste one descriptor, or its /0/* and /1/* pair",
            "$TR_ABANDON_RECEIVE_DESCRIPTOR\n$TR_ABANDON_CHANGE_DESCRIPTOR\n$TR_ABANDON_CHANGE_DESCRIPTOR",
        )
    }
}
