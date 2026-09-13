package io.bluewallet.blueberry.wallet

import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.ScriptFlags
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxHash
import fr.acinq.bitcoin.TxIn
import fr.acinq.bitcoin.TxOut
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * WIF single-key sends. Address vectors come from BlueWallet:
 * segwit-bech32-wallet, legacy-wallet, segwit-p2sh-wallet and taproot-wallet tests.
 */
private val ALL_WIF_TYPES =
    listOf(
        AddressScriptType.P2PKH,
        AddressScriptType.P2SH_P2WPKH,
        AddressScriptType.P2WPKH,
        AddressScriptType.P2TR,
    )

private fun byType(
    wallet: WatchWallet,
    scriptType: AddressScriptType,
): WatchAddress =
    wallet.addresses.firstOrNull { it.scriptType == scriptType }
        ?: error("missing ${scriptType.wireName()}")

/** Classify a signed input from its unlock data alone, as the helix3 test does. */
private fun spentScriptType(txIn: TxIn): AddressScriptType {
    val sigScriptSize = txIn.signatureScript.size()
    val witnessSize = txIn.witness.stack.size
    return when {
        witnessSize == 1 && sigScriptSize == 0 -> AddressScriptType.P2TR
        witnessSize >= 2 && sigScriptSize == 0 -> AddressScriptType.P2WPKH
        witnessSize >= 2 && sigScriptSize > 0 -> AddressScriptType.P2SH_P2WPKH
        sigScriptSize > 0 -> AddressScriptType.P2PKH
        else -> error("unrecognized unlock data")
    }
}

private fun outPointOf(utxo: SendInputUtxo): OutPoint = OutPoint(TxHash(hexToBytes(utxo.txid).reversedArray()), utxo.vout.toLong())

class WifSendTest {
    @Test
    fun signs_native_segwit_send_with_change() {
        val wallet = deriveWatchWallet(WIF_BECH32)
        val recv = byType(wallet, AddressScriptType.P2WPKH)
        assertEquals(ADDR_BECH32, recv.address)
        val fund = testFundingTx(recv.scriptPubKey, 100_000L, 7)

        val result =
            buildSignedSendTx(
                BuildSendTxParams(
                    secret = WIF_BECH32,
                    wallet = wallet,
                    utxos = listOf(SendInputUtxo(fund.txid, 0, 100_000L, recv.scriptPubKey)),
                    toAddress = DEST_LEGACY,
                    amountSats = SendAmount.Exact(90_000L),
                    feeRateSatPerVb = 1.0,
                    changeAddress = recv.address,
                ),
            )

        assertEquals("signed", result.kind)
        val tx = Transaction.read(result.txHex)
        assertEquals(1, tx.txIn.size)
        assertEquals(2, tx.txOut.size)
        assertEquals(AddressScriptType.P2WPKH, spentScriptType(tx.txIn[0]))
        assertEquals(result.vsize.toLong(), result.feeSats)
    }

    @Test
    fun signs_wrapped_segwit_send() {
        val wallet = deriveWatchWallet(WIF_P2SH)
        val recv = byType(wallet, AddressScriptType.P2SH_P2WPKH)
        assertEquals(ADDR_P2SH, recv.address)
        val fund = testFundingTx(recv.scriptPubKey, 300_000L, 8)

        val result =
            buildSignedSendTx(
                BuildSendTxParams(
                    secret = WIF_P2SH,
                    wallet = wallet,
                    utxos = listOf(SendInputUtxo(fund.txid, 0, 300_000L, recv.scriptPubKey)),
                    toAddress = DEST_LEGACY,
                    amountSats = SendAmount.Exact(90_000L),
                    feeRateSatPerVb = 1.0,
                    changeAddress = recv.address,
                ),
            )

        val tx = Transaction.read(result.txHex)
        assertEquals(AddressScriptType.P2SH_P2WPKH, spentScriptType(tx.txIn[0]))
        assertEquals(1, (result.feeSats.toDouble() / result.vsize).roundToInt())
        // The pre-sign size estimate must match the signed transaction, or the fee drifts.
        assertEquals(result.vsize.toLong(), result.feeSats)
    }

    @Test
    fun signs_legacy_p2pkh_send_with_non_witness_utxo() {
        val wallet = deriveWatchWallet(WIF_LEGACY)
        val recv = byType(wallet, AddressScriptType.P2PKH)
        assertEquals(ADDR_LEGACY, recv.address)
        val fund = testFundingTx(recv.scriptPubKey, 100_000L, 9)

        val result =
            buildSignedSendTx(
                BuildSendTxParams(
                    secret = WIF_LEGACY,
                    wallet = wallet,
                    utxos =
                        listOf(
                            SendInputUtxo(fund.txid, 0, 100_000L, recv.scriptPubKey, nonWitnessUtxo = fund.bytes),
                        ),
                    toAddress = DEST_LEGACY,
                    amountSats = SendAmount.Exact(90_000L),
                    feeRateSatPerVb = 1.0,
                    changeAddress = recv.address,
                ),
            )

        val tx = Transaction.read(result.txHex)
        assertEquals(1, tx.txIn.size)
        assertEquals(AddressScriptType.P2PKH, spentScriptType(tx.txIn[0]))
        assertEquals(result.vsize.toLong(), result.feeSats)
    }

    @Test
    fun signs_taproot_key_path_send_max() {
        val wallet = deriveWatchWallet(WIF_TAPROOT)
        val recv = byType(wallet, AddressScriptType.P2TR)
        assertEquals(ADDR_TAPROOT, recv.address)
        val destination = "13HaCAB4jf7FYSZexJxoczyDDnutzZigjS"

        val result =
            buildSignedSendTx(
                BuildSendTxParams(
                    secret = WIF_TAPROOT,
                    wallet = wallet,
                    utxos =
                        listOf(
                            SendInputUtxo(
                                txid = "4dc4c9a03dd7005310a313c5ef1754e5e53888d587073f01a5a662501c12ac3b",
                                vout = 0,
                                valueSats = 10_000L,
                                scriptPubKey = recv.scriptPubKey,
                            ),
                        ),
                    toAddress = destination,
                    amountSats = SendAmount.Max,
                    feeRateSatPerVb = 4.0,
                    changeAddress = recv.address,
                ),
            )

        val tx = Transaction.read(result.txHex)
        assertEquals(1, tx.txIn.size)
        assertEquals(1, tx.txOut.size)
        assertEquals(AddressScriptType.P2TR, spentScriptType(tx.txIn[0]))
        assertEquals(
            64,
            tx.txIn[0]
                .witness.stack[0]
                .size(),
        )
        assertContentEquals(
            outputScriptFromAddress(destination),
            tx.txOut[0].publicKeyScript.toByteArray(),
        )
        assertEquals(ceil(4.0 * result.vsize).toLong(), result.feeSats)
        assertEquals(10_000L - result.feeSats, tx.txOut[0].amount.toLong())
    }

    @Test
    fun signs_mixed_type_utxos_in_one_transaction() {
        val wallet = deriveWatchWallet(WIF_BECH32)
        val utxos =
            ALL_WIF_TYPES.mapIndexed { i, scriptType ->
                val addr = byType(wallet, scriptType)
                val fund = testFundingTx(addr.scriptPubKey, 100_000L, 20 + i)
                SendInputUtxo(
                    txid = fund.txid,
                    vout = 0,
                    valueSats = 100_000L,
                    scriptPubKey = addr.scriptPubKey,
                    nonWitnessUtxo = if (scriptType == AddressScriptType.P2PKH) fund.bytes else null,
                )
            }

        val result =
            buildSend(
                BuildSendTxParams(
                    secret = WIF_BECH32,
                    wallet = wallet,
                    utxos = utxos,
                    toAddress = DEST_LEGACY,
                    amountSats = SendAmount.Exact(200_000L),
                    feeRateSatPerVb = 2.0,
                    changeAddress = byType(wallet, AddressScriptType.P2WPKH).address,
                ),
            )

        val signed = result as SignedSendResult
        val tx = Transaction.read(signed.txHex)
        assertEquals(4, tx.txIn.size)
        assertEquals(2, tx.txOut.size)
        assertEquals(ALL_WIF_TYPES.toSet(), tx.txIn.map { spentScriptType(it) }.toSet())
    }

    @Test
    fun signatures_of_every_input_type_verify_against_the_spent_outputs() {
        val wallet = deriveWatchWallet(WIF_BECH32)
        val utxos =
            ALL_WIF_TYPES.mapIndexed { i, scriptType ->
                val addr = byType(wallet, scriptType)
                val fund = testFundingTx(addr.scriptPubKey, 100_000L, 40 + i)
                SendInputUtxo(
                    txid = fund.txid,
                    vout = 0,
                    valueSats = 100_000L,
                    scriptPubKey = addr.scriptPubKey,
                    nonWitnessUtxo = if (scriptType == AddressScriptType.P2PKH) fund.bytes else null,
                )
            }

        val result =
            buildSignedSendTx(
                BuildSendTxParams(
                    secret = WIF_BECH32,
                    wallet = wallet,
                    utxos = utxos,
                    toAddress = DEST_LEGACY,
                    amountSats = SendAmount.Exact(200_000L),
                    feeRateSatPerVb = 2.0,
                    changeAddress = byType(wallet, AddressScriptType.P2WPKH).address,
                ),
            )

        assertTrue(result.feeSats > 0L)
        val spentOutputs =
            utxos.associate {
                outPointOf(it) to TxOut(Satoshi(it.valueSats), it.scriptPubKey)
            }
        Transaction
            .read(result.txHex)
            .correctlySpends(spentOutputs, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    }

    @Test
    fun legacy_input_without_non_witness_utxo_fails_clearly() {
        val wallet = deriveWatchWallet(WIF_LEGACY)
        val recv = byType(wallet, AddressScriptType.P2PKH)
        val fund = testFundingTx(recv.scriptPubKey, 100_000L, 11)

        val error =
            assertFailsWith<IllegalArgumentException> {
                buildSignedSendTx(
                    BuildSendTxParams(
                        secret = WIF_LEGACY,
                        wallet = wallet,
                        utxos = listOf(SendInputUtxo(fund.txid, 0, 100_000L, recv.scriptPubKey)),
                        toAddress = DEST_LEGACY,
                        amountSats = SendAmount.Exact(50_000L),
                        feeRateSatPerVb = 1.0,
                        changeAddress = recv.address,
                    ),
                )
            }
        assertEquals(
            "legacy p2pkh input requires nonWitnessUtxo (previous transaction)",
            error.message,
        )
    }
}
