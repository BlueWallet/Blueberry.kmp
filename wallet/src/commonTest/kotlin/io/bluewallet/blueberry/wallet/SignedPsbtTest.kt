package io.bluewallet.blueberry.wallet

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.bitcoin.KeyPath
import fr.acinq.bitcoin.MnemonicCode
import fr.acinq.bitcoin.OP_PUSHDATA
import fr.acinq.bitcoin.Script
import fr.acinq.bitcoin.ScriptWitness
import fr.acinq.bitcoin.psbt.Input
import fr.acinq.bitcoin.psbt.Psbt
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A watch-only PSBT signed elsewhere comes back, is checked against what we built, finalized and extracted. */
class SignedPsbtTest {
    private class Case(
        val secret: String,
        val type: AddressScriptType,
        val purpose: Int,
    )

    private val p2pkh = Case(ABANDON_BIP44_XPUB, AddressScriptType.P2PKH, 44)
    private val p2shP2wpkh = Case(ABANDON_BIP49_YPUB, AddressScriptType.P2SH_P2WPKH, 49)
    private val p2wpkh = Case(WPKH_BARE_DESCRIPTOR, AddressScriptType.P2WPKH, 84)
    private val p2tr = Case(TR_BARE_DESCRIPTOR, AddressScriptType.P2TR, 86)

    private val master = DeterministicWallet.generate(MnemonicCode.toSeed(ABANDON, ""))

    private fun build(
        case: Case,
        amountSats: Long = 50_000L,
        toChange: Boolean = false,
    ): PsbtSendResult {
        val wallet = deriveWatchWallet(case.secret, WatchGaps(2, 2))
        val change = wallet.hd(case.type, 0, change = true).address
        val coin = wallet.hd(case.type, 0)
        val funding = testFundingTx(coin.scriptPubKey, 100_000L)
        return buildSend(
            BuildSendTxParams(
                secret = case.secret,
                wallet = wallet,
                utxos = listOf(SendInputUtxo(funding.txid, 0, 100_000L, coin.scriptPubKey, funding.bytes)),
                toAddress = if (toChange) change else DEST_LEGACY,
                amountSats = SendAmount.Exact(amountSats),
                feeRateSatPerVb = 2.0,
                changeAddress = change,
            ),
        ) as PsbtSendResult
    }

    private fun signed(
        case: Case,
        psbtHex: String,
        keyIndex: Int = 0,
    ): Psbt {
        val psbt = Psbt.read(hexToBytes(psbtHex)).right!!
        val priv = master.derivePrivateKey(KeyPath("m/${case.purpose}'/0'/0'/0/$keyIndex")).privateKey
        return psbt.sign(priv, 0).right!!.psbt
    }

    private fun Psbt.bytes(): ByteArray = Psbt.write(this).toByteArray()

    @Test
    fun signs_finalizes_and_extracts_each_script_type() {
        for (case in listOf(p2pkh, p2shP2wpkh, p2wpkh, p2tr)) {
            val built = build(case)
            val ok = assertIs<SignedPsbtCheck.Ok>(finalizeSignedPsbt(built.psbtHex, signed(case, built.psbtHex).bytes()), case.type.name)
            val signed = ok.signed
            assertEquals(true, isSignedTxHex(signed.txHex), case.type.name)
            assertEquals(unsignedTxid(hexToBytes(built.unsignedTxHex)), unsignedTxid(hexToBytes(signed.txHex)), case.type.name)
            if (case.type == AddressScriptType.P2WPKH || case.type == AddressScriptType.P2TR) {
                assertEquals(built.txid, signed.txid, case.type.name)
            } else {
                assertNotEquals(built.txid, signed.txid, case.type.name)
            }
            assertEquals(built.feeSats, signed.feeSats, case.type.name)
            assertEquals(50_000L, signed.paidTo(DEST_LEGACY), case.type.name)
        }
    }

    @Test
    fun a_payment_to_our_own_change_address_counts_only_the_payment() {
        val built = build(p2wpkh, amountSats = 30_000L, toChange = true)
        assertTrue(built.changeSats > 0)
        val ok = assertIs<SignedPsbtCheck.Ok>(finalizeSignedPsbt(built.psbtHex, signed(p2wpkh, built.psbtHex).bytes()))
        val change = deriveWatchWallet(p2wpkh.secret, WatchGaps(2, 2)).hd(p2wpkh.type, 0, change = true).address
        assertEquals(30_000L, ok.signed.paidTo(change))
    }

    @Test
    fun survives_an_animated_ur_round_trip() {
        val built = build(p2tr)
        val parts = encodeCryptoPsbtUrFragments(signed(p2tr, built.psbtHex).bytes(), 40)
        val decoder = UrDecoder(PSBT_UR_TYPES)
        val message =
            parts
                .reversed()
                .map { decoder.receive(it) }
                .filterIsInstance<UrReceive.Complete>()
                .first()
                .message
        assertIs<SignedPsbtCheck.Ok>(finalizeSignedPsbt(built.psbtHex, psbtFromUrMessage(message)))
    }

    @Test
    fun keeps_inputs_the_signer_finalized_and_stripped() {
        for (case in listOf(p2shP2wpkh, p2wpkh)) {
            val built = build(case)
            val partial = signed(case, built.psbtHex)
            val (pub, sig) =
                partial.inputs[0]
                    .partialSigs.entries
                    .single()
            val finalized = partial.finalizeWitnessInput(0, ScriptWitness(listOf(sig, pub.value))).right!!
            val input = finalized.inputs[0]
            val stripped =
                finalized.copy(
                    inputs = listOf(Input.FinalizedInputWithoutUtxo(input.scriptWitness!!, input.scriptSig, emptySet(), emptySet(), emptySet(), emptySet(), emptyList())),
                )
            val ok = assertIs<SignedPsbtCheck.Ok>(finalizeSignedPsbt(built.psbtHex, stripped.bytes()), case.type.name)
            assertEquals(unsignedTxid(hexToBytes(built.unsignedTxHex)), unsignedTxid(hexToBytes(ok.signed.txHex)))
        }
    }

    @Test
    fun reads_partial_signatures_from_inputs_the_signer_stripped_of_coins() {
        for (case in listOf(p2pkh, p2shP2wpkh, p2wpkh, p2tr)) {
            val built = build(case)
            val stripped =
                rewriteFirstInput(signed(case, built.psbtHex).bytes()) { entries ->
                    entries.filterNot { it.first[0] == PSBT_IN_NON_WITNESS_UTXO || it.first[0] == PSBT_IN_WITNESS_UTXO }
                }
            val ok = assertIs<SignedPsbtCheck.Ok>(finalizeSignedPsbt(built.psbtHex, stripped), case.type.name)
            assertEquals(true, isSignedTxHex(ok.signed.txHex), case.type.name)
        }
    }

    @Test
    fun reads_final_scripts_the_signer_left_beside_other_fields() {
        for (case in listOf(p2pkh, p2shP2wpkh, p2wpkh)) {
            for (keepCoins in listOf(true, false)) {
                val built = build(case)
                val signedBytes = signed(case, built.psbtHex).bytes()
                val finalized =
                    rewriteFirstInput(signedBytes) { entries ->
                        val partial = entries.single { it.first[0] == PSBT_IN_PARTIAL_SIG }
                        val sig = partial.second
                        val pub = partial.first.copyOfRange(1, partial.first.size)
                        val kept =
                            entries.filter {
                                it.first[0] != PSBT_IN_PARTIAL_SIG &&
                                    (keepCoins || (it.first[0] != PSBT_IN_NON_WITNESS_UTXO && it.first[0] != PSBT_IN_WITNESS_UTXO))
                            }
                        kept + finalScripts(case.type, pub, sig)
                    }
                val ok = assertIs<SignedPsbtCheck.Ok>(finalizeSignedPsbt(built.psbtHex, finalized), "${case.type.name} coins=$keepCoins")
                assertEquals(true, isSignedTxHex(ok.signed.txHex), case.type.name)
            }
        }
    }

    @Test
    fun malformed_raw_input_maps_read_as_null() {
        val magic = byteArrayOf(0x70, 0x73, 0x62, 0x74, 0xff.toByte())
        val hugeWitness = byteArrayOf(0x01, PSBT_IN_FINAL_WITNESS, 0x05, 0xfe.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0x7f, 0x00)
        assertNull(inputSignatures(magic + byteArrayOf(0x00) + hugeWitness, 1))
        assertNull(inputSignatures(magic + byteArrayOf(0x00, 0x01, 0x02), 1))
    }

    /** BIP174 final scriptSig (0x07) / final witness (0x08) entries for a single-sig spend. */
    private fun finalScripts(
        type: AddressScriptType,
        pub: ByteArray,
        sig: ByteArray,
    ): List<Pair<ByteArray, ByteArray>> {
        val witness = byteArrayOf(2, sig.size.toByte()) + sig + byteArrayOf(pub.size.toByte()) + pub
        val redeem = Script.write(Script.pay2wpkh(fr.acinq.bitcoin.PublicKey(ByteVector(pub))))
        return when (type) {
            AddressScriptType.P2WPKH -> listOf(byteArrayOf(PSBT_IN_FINAL_WITNESS) to witness)
            AddressScriptType.P2SH_P2WPKH ->
                listOf(
                    byteArrayOf(PSBT_IN_FINAL_SCRIPTSIG) to Script.write(listOf(OP_PUSHDATA(redeem))),
                    byteArrayOf(PSBT_IN_FINAL_WITNESS) to witness,
                )
            else -> listOf(byteArrayOf(PSBT_IN_FINAL_SCRIPTSIG) to Script.write(listOf(OP_PUSHDATA(sig), OP_PUSHDATA(pub))))
        }
    }

    @Test
    fun rejects_a_psbt_for_a_different_transaction() {
        val other = build(p2wpkh, amountSats = 40_000L)
        val built = build(p2wpkh)
        assertEquals(SignedPsbtCheck.Failed(PSBT_DIFFERENT_TX), finalizeSignedPsbt(built.psbtHex, signed(p2wpkh, other.psbtHex).bytes()))
    }

    @Test
    fun rejects_an_unsigned_psbt() {
        val built = build(p2wpkh)
        assertEquals(SignedPsbtCheck.Failed(PSBT_NOT_SIGNED), finalizeSignedPsbt(built.psbtHex, hexToBytes(built.psbtHex)))
    }

    @Test
    fun rejects_a_signature_from_the_wrong_key() {
        for (case in listOf(p2pkh, p2wpkh, p2tr)) {
            val built = build(case)
            assertEquals(
                SignedPsbtCheck.Failed(PSBT_BAD_SIGNATURES),
                finalizeSignedPsbt(built.psbtHex, signed(case, built.psbtHex, keyIndex = 1).bytes()),
                case.type.name,
            )
        }
    }

    @Test
    fun rejects_bytes_that_are_not_a_psbt() {
        val built = build(p2wpkh)
        assertEquals(SignedPsbtCheck.Failed(PSBT_NOT_A_PSBT), finalizeSignedPsbt(built.psbtHex, "hello".encodeToByteArray()))
    }

    @Test
    fun reads_base64_and_hex_psbt_text() {
        val hex = build(p2wpkh).psbtHex
        val bytes = hexToBytes(hex)
        assertEquals(hex, psbtBytesFromText(hex)?.let(::hexFromBytes))
        assertEquals(hex, psbtBytesFromText(hex.uppercase())?.let(::hexFromBytes))
        val base64 = Base64.encode(bytes)
        assertEquals(hex, psbtBytesFromText("  ${base64.chunked(60).joinToString("\n")}\n")?.let(::hexFromBytes))
        assertNull(psbtBytesFromText("bitcoin:$DEST_LEGACY"))
        assertNull(psbtBytesFromText("cHNidP8"))
        assertNull(psbtBytesFromText(Base64.encode(ByteVector("deadbeefdeadbeef").toByteArray())))
        assertNull(psbtBytesFromText(""))
    }

    private companion object {
        const val PSBT_IN_NON_WITNESS_UTXO: Byte = 0x00
        const val PSBT_IN_WITNESS_UTXO: Byte = 0x01
        const val PSBT_IN_PARTIAL_SIG: Byte = 0x02
        const val PSBT_IN_FINAL_SCRIPTSIG: Byte = 0x07
        const val PSBT_IN_FINAL_WITNESS: Byte = 0x08

        /** Rewrites the first input's key-value map of a raw PSBT, leaving every other byte as it was. */
        fun rewriteFirstInput(
            psbt: ByteArray,
            edit: (List<Pair<ByteArray, ByteArray>>) -> List<Pair<ByteArray, ByteArray>>,
        ): ByteArray {
            var at = 5

            fun compact(): Int {
                val first = psbt[at++].toInt() and 0xff
                val width = mapOf(0xfd to 2, 0xfe to 4).getOrElse(first) { 0 }
                if (width == 0) return first
                var value = 0
                for (i in 0 until width) value = value or ((psbt[at++].toInt() and 0xff) shl (8 * i))
                return value
            }

            fun entries(): List<Pair<ByteArray, ByteArray>> {
                val out = mutableListOf<Pair<ByteArray, ByteArray>>()
                while (true) {
                    val keyLength = compact()
                    if (keyLength == 0) return out
                    val key = psbt.copyOfRange(at, at + keyLength).also { at += keyLength }
                    val valueLength = compact()
                    out += key to psbt.copyOfRange(at, at + valueLength).also { at += valueLength }
                }
            }
            entries()
            val inputStart = at
            val input = entries()

            fun writeCompact(n: Int): ByteArray = if (n < 0xfd) byteArrayOf(n.toByte()) else byteArrayOf(0xfd.toByte(), n.toByte(), (n shr 8).toByte())
            val rewritten =
                edit(input).fold(ByteArray(0)) { acc, (k, v) -> acc + writeCompact(k.size) + k + writeCompact(v.size) + v } + byteArrayOf(0)
            return psbt.copyOfRange(0, inputStart) + rewritten + psbt.copyOfRange(at, psbt.size)
        }
    }
}
