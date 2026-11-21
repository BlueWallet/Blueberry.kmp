package io.bluewallet.blueberry.wallet

import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.bitcoin.DeterministicWallet.hardened
import fr.acinq.bitcoin.KeyPath
import fr.acinq.bitcoin.MnemonicCode
import fr.acinq.bitcoin.Script
import fr.acinq.bitcoin.XonlyPublicKey
import fr.acinq.bitcoin.psbt.Psbt
import fr.acinq.bitcoin.psbt.TaprootBip32DerivationPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Unsigned PSBTs from watch keys: BIP32 origin with and without a key origin, taproot fields, change metadata. */
class WatchKeySendTest {
    private fun send(
        secret: String,
        type: AddressScriptType,
    ): Pair<WatchWallet, Psbt> {
        val wallet = deriveWatchWallet(secret, WatchGaps(2, 2))
        val coin = wallet.hd(type, 0)
        val funding = testFundingTx(coin.scriptPubKey, 100_000L)
        val result =
            buildSend(
                BuildSendTxParams(
                    secret = secret,
                    wallet = wallet,
                    utxos = listOf(SendInputUtxo(funding.txid, 0, 100_000L, coin.scriptPubKey, funding.bytes)),
                    toAddress = DEST_LEGACY,
                    amountSats = SendAmount.Exact(50_000L),
                    feeRateSatPerVb = 2.0,
                    changeAddress = wallet.hd(type, 0, change = true).address,
                ),
            ) as PsbtSendResult
        assertEquals(1, result.changeVouts.size)
        val psbt = Psbt.read(hexToBytes(result.psbtHex)).right ?: error("failed to parse psbt")
        return wallet to psbt
    }

    private fun Psbt.changeOutputIndex(wallet: WatchWallet): Int =
        global.tx.txOut.indexOfFirst { out -> wallet.addresses.any { it.change && it.scriptPubKey.contentEquals(out.publicKeyScript.toByteArray()) } }

    private fun path(vararg steps: Long) = KeyPath(steps.toList())

    private val h0 = hardened(0)

    @Test
    fun abandon_master_fingerprint_matches_the_descriptor_origin() {
        assertEquals(ABANDON_FINGERPRINT, DeterministicWallet.generate(MnemonicCode.toSeed(ABANDON, "")).fingerprint())
    }

    @Test
    fun origin_descriptor_uses_master_fingerprint_and_full_path() {
        val (_, psbt) = send("$WPKH_ABANDON_RECEIVE_DESCRIPTOR\n$WPKH_ABANDON_CHANGE_DESCRIPTOR", AddressScriptType.P2WPKH)
        val origin = psbt.inputs[0].derivationPaths.values.single()
        assertEquals(ABANDON_FINGERPRINT, origin.masterKeyFingerprint)
        assertEquals(path(hardened(84), h0, h0, 0, 0), origin.keyPath)
    }

    @Test
    fun bare_xpub_uses_account_fingerprint_and_relative_path() {
        val (wallet, psbt) = send(ABANDON_BIP44_XPUB, AddressScriptType.P2PKH)
        val origin = psbt.inputs[0].derivationPaths.values.single()
        assertEquals(parseWatchKey(ABANDON_BIP44_XPUB).account.fingerprint(), origin.masterKeyFingerprint)
        assertEquals(path(0, 0), origin.keyPath)
        val change = psbt.outputs[psbt.changeOutputIndex(wallet)].derivationPaths.values.single()
        assertEquals(path(1, 0), change.keyPath)
    }

    @Test
    fun ypub_input_and_change_carry_redeem_script() {
        val (wallet, psbt) = send(ABANDON_BIP49_YPUB, AddressScriptType.P2SH_P2WPKH)
        val key = parseWatchKey(ABANDON_BIP49_YPUB)
        assertEquals(Script.pay2wpkh(key.publicKeyAt(false, 0)), psbt.inputs[0].redeemScript)
        val change = psbt.outputs[psbt.changeOutputIndex(wallet)]
        assertEquals(Script.pay2wpkh(key.publicKeyAt(true, 0)), change.redeemScript)
        assertEquals(path(1, 0), change.derivationPaths.values.single().keyPath)
    }

    @Test
    fun taproot_input_and_change_use_tap_fields_only() {
        val (wallet, psbt) = send(TR_ABANDON_DESCRIPTOR, AddressScriptType.P2TR)
        val key = parseWatchKey(TR_ABANDON_DESCRIPTOR)
        val input = psbt.inputs[0]
        val inputKey = XonlyPublicKey(key.publicKeyAt(false, 0))
        assertEquals(inputKey, input.taprootInternalKey)
        assertEquals(
            mapOf(inputKey to TaprootBip32DerivationPath(emptyList(), ABANDON_FINGERPRINT, path(hardened(86), h0, h0, 0, 0))),
            input.taprootDerivationPaths,
        )
        assertTrue(input.derivationPaths.isEmpty())

        val change = psbt.outputs[psbt.changeOutputIndex(wallet)]
        val changeKey = XonlyPublicKey(key.publicKeyAt(true, 0))
        assertEquals(changeKey, change.taprootInternalKey)
        assertEquals(path(hardened(86), h0, h0, 1, 0), change.taprootDerivationPaths.getValue(changeKey).keyPath)
        assertTrue(change.derivationPaths.isEmpty())
    }

    @Test
    fun mnemonic_taproot_input_uses_tap_derivation_with_master_origin() {
        val wallet = deriveWatchWallet(ABANDON, WatchGaps(1, 1))
        val coin = wallet.hd(AddressScriptType.P2TR, 0)
        val psbtHex =
            buildUnsignedSendPsbt(
                BuildSendTxParams(ABANDON, wallet, listOf(SendInputUtxo("55".repeat(32), 0, 90_000L, coin.scriptPubKey)), DEST_LEGACY, SendAmount.Max, 1.0, DEST_LEGACY),
            ).psbtHex
        val input = (Psbt.read(hexToBytes(psbtHex)).right ?: error("failed to parse psbt")).inputs[0]
        assertTrue(input.derivationPaths.isEmpty())
        val tap = input.taprootDerivationPaths.values.single()
        assertEquals(ABANDON_FINGERPRINT, tap.masterKeyFingerprint)
        assertEquals(path(hardened(86), h0, h0, 0, 0), tap.keyPath)
    }

    @Test
    fun payment_output_gets_no_derivation() {
        val (wallet, psbt) = send(BLUE_ZPUB, AddressScriptType.P2WPKH)
        val payment = psbt.outputs.indices.single { it != psbt.changeOutputIndex(wallet) }
        assertTrue(psbt.outputs[payment].derivationPaths.isEmpty())
        assertNull(psbt.outputs[payment].taprootInternalKey)
    }

    @Test
    fun rebuilt_psbt_matches_the_built_one() {
        val secret = TR_ABANDON_DESCRIPTOR
        val wallet = deriveWatchWallet(secret, WatchGaps(2, 2))
        val coin = wallet.hd(AddressScriptType.P2TR, 1)
        val utxo = SendInputUtxo("44".repeat(32), 1, 80_000L, coin.scriptPubKey)
        val built =
            buildUnsignedSendPsbt(
                BuildSendTxParams(secret, wallet, listOf(utxo), DEST_LEGACY, SendAmount.Exact(30_000L), 1.0, wallet.hd(AddressScriptType.P2TR, 1, true).address),
            )
        assertEquals(built.psbtHex, rebuildSendPsbt(secret, wallet, built.unsignedTxHex, listOf(utxo)))
    }
}
