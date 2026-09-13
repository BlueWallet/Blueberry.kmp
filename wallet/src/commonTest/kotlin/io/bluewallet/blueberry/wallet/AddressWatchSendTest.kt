package io.bluewallet.blueberry.wallet

import fr.acinq.bitcoin.psbt.Psbt
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Single-address watch-only sends. Vectors reuse the BlueWallet WIF primary addresses.
 * A watch-only wallet has no keys, so every send is an unsigned, script-only PSBT.
 */
private fun readPsbt(psbtHex: String): Psbt = Psbt.read(hexToBytes(psbtHex)).right ?: error("failed to parse psbt")

private fun outputScriptHexes(psbt: Psbt): List<String> =
    psbt.global.tx.txOut
        .map { scriptHex(it.publicKeyScript.toByteArray()) }

class AddressWatchSendTest {
    @Test
    fun builds_script_only_psbt_for_nested_p2sh_address() {
        val wallet = deriveWatchWallet(ADDR_P2SH)
        val result =
            buildSend(
                BuildSendTxParams(
                    secret = ADDR_P2SH,
                    wallet = wallet,
                    utxos = listOf(SendInputUtxo("44".repeat(32), 0, 100_000L, wallet.scripts[0])),
                    toAddress = DEST_LEGACY,
                    amountSats = SendAmount.Exact(50_000L),
                    feeRateSatPerVb = 1.0,
                    changeAddress = ADDR_P2SH,
                ),
            ) as PsbtSendResult

        val input = readPsbt(result.psbtHex).inputs[0]
        assertContentEquals(wallet.scripts[0], input.witnessUtxo?.publicKeyScript?.toByteArray())
        assertNull(input.redeemScript)
    }

    @Test
    fun legacy_address_psbt_builds_when_non_witness_utxo_is_attached() {
        val wallet = deriveWatchWallet(ADDR_LEGACY)
        val fund = testFundingTx(wallet.scripts[0], 100_000L, 12)

        val result =
            buildSend(
                BuildSendTxParams(
                    secret = ADDR_LEGACY,
                    wallet = wallet,
                    utxos =
                        listOf(
                            SendInputUtxo(fund.txid, 0, 100_000L, wallet.scripts[0], nonWitnessUtxo = fund.bytes),
                        ),
                    toAddress = DEST_LEGACY,
                    amountSats = SendAmount.Exact(50_000L),
                    feeRateSatPerVb = 1.0,
                    changeAddress = ADDR_LEGACY,
                ),
            ) as PsbtSendResult

        assertNotNull(readPsbt(result.psbtHex).inputs[0].nonWitnessUtxo)
    }

    @Test
    fun returns_unsigned_psbt_overrides_mismatched_change_address_and_refuses_signing() {
        val wallet = deriveWatchWallet(ADDR_BECH32)
        val utxo = SendInputUtxo("11".repeat(32), 0, 100_000L, wallet.scripts[0])
        val params =
            BuildSendTxParams(
                secret = ADDR_BECH32,
                wallet = wallet,
                utxos = listOf(utxo),
                toAddress = DEST_LEGACY,
                amountSats = SendAmount.Exact(50_000L),
                feeRateSatPerVb = 1.0,
                changeAddress = ADDR_TAPROOT,
            )

        val result = buildSend(params) as PsbtSendResult
        assertTrue(result.psbtHex.startsWith("70736274ff"))
        assertTrue(result.changeSats > 0L)

        val psbt = readPsbt(result.psbtHex)
        assertEquals(1, psbt.global.tx.txIn.size)
        assertEquals(2, psbt.global.tx.txOut.size)
        val outScripts = outputScriptHexes(psbt)
        assertTrue(outScripts.contains(scriptHex(outputScriptFromAddress(DEST_LEGACY))))
        assertTrue(outScripts.contains(scriptHex(outputScriptFromAddress(ADDR_BECH32))))
        assertTrue(!outScripts.contains(scriptHex(outputScriptFromAddress(ADDR_TAPROOT))))

        val error =
            assertFailsWith<IllegalArgumentException> {
                buildSignedSendTx(params.copy(changeAddress = ADDR_BECH32))
            }
        assertEquals("signing requires a mnemonic or WIF wallet secret", error.message)
    }

    @Test
    fun canonicalizes_uppercase_bech32_when_adjusting_fractional_fees() {
        val address = ADDR_BECH32.uppercase()
        val wallet = deriveWatchWallet(address)
        val result =
            buildSend(
                BuildSendTxParams(
                    secret = address,
                    wallet = wallet,
                    utxos = listOf(SendInputUtxo("55".repeat(32), 0, 100_000L, wallet.scripts[0])),
                    toAddress = DEST_LEGACY,
                    amountSats = SendAmount.Exact(50_000L),
                    feeRateSatPerVb = 1.1,
                    changeAddress = address,
                ),
            ) as PsbtSendResult

        assertEquals(ceil(1.1 * result.vsize).toLong(), result.feeSats)
        assertTrue(result.changeSats > 0L)
    }

    @Test
    fun rejects_non_max_sends_back_to_the_watched_address() {
        val wallet = deriveWatchWallet(ADDR_BECH32)
        val error =
            assertFailsWith<IllegalArgumentException> {
                buildSend(
                    BuildSendTxParams(
                        secret = ADDR_BECH32,
                        wallet = wallet,
                        utxos = listOf(SendInputUtxo("66".repeat(32), 0, 100_000L, wallet.scripts[0])),
                        toAddress = ADDR_BECH32.uppercase(),
                        amountSats = SendAmount.Exact(10_000L),
                        feeRateSatPerVb = 1.1,
                        changeAddress = ADDR_BECH32,
                    ),
                )
            }
        assertEquals("cannot send back to the watched address", error.message)
    }

    @Test
    fun unsigned_builder_also_rejects_non_max_sends_to_the_watched_address() {
        val wallet = deriveWatchWallet(ADDR_BECH32)
        val error =
            assertFailsWith<IllegalArgumentException> {
                buildUnsignedSendPsbt(
                    BuildSendTxParams(
                        secret = ADDR_BECH32,
                        wallet = wallet,
                        utxos = listOf(SendInputUtxo("77".repeat(32), 0, 100_000L, wallet.scripts[0])),
                        toAddress = ADDR_BECH32,
                        amountSats = SendAmount.Exact(10_000L),
                        feeRateSatPerVb = 1.1,
                        changeAddress = ADDR_BECH32,
                    ),
                )
            }
        assertEquals("cannot send back to the watched address", error.message)
    }

    @Test
    fun omits_non_witness_utxo_from_native_segwit_psbt() {
        val wallet = deriveWatchWallet(ADDR_BECH32)
        val fund = testFundingTx(wallet.scripts[0], 100_000L, 13)
        val result =
            buildSend(
                BuildSendTxParams(
                    secret = ADDR_BECH32,
                    wallet = wallet,
                    utxos =
                        listOf(
                            SendInputUtxo(fund.txid, 0, 100_000L, wallet.scripts[0], nonWitnessUtxo = fund.bytes),
                        ),
                    toAddress = DEST_LEGACY,
                    amountSats = SendAmount.Exact(50_000L),
                    feeRateSatPerVb = 1.0,
                    changeAddress = ADDR_BECH32,
                ),
            ) as PsbtSendResult

        assertNull(readPsbt(result.psbtHex).inputs[0].nonWitnessUtxo)
    }

    @Test
    fun send_max_has_a_single_output_and_zero_change_sats() {
        val wallet = deriveWatchWallet(ADDR_BECH32)
        val result =
            buildSend(
                BuildSendTxParams(
                    secret = ADDR_BECH32,
                    wallet = wallet,
                    utxos = listOf(SendInputUtxo("22".repeat(32), 0, 100_000L, wallet.scripts[0])),
                    toAddress = DEST_LEGACY,
                    amountSats = SendAmount.Max,
                    feeRateSatPerVb = 1.0,
                    changeAddress = ADDR_BECH32,
                ),
            ) as PsbtSendResult

        assertEquals(0L, result.changeSats)
        val psbt = readPsbt(result.psbtHex)
        assertEquals(1, psbt.global.tx.txOut.size)
        assertContentEquals(
            outputScriptFromAddress(DEST_LEGACY),
            psbt.global.tx.txOut[0]
                .publicKeyScript
                .toByteArray(),
        )
    }

    @Test
    fun taproot_address_psbt_keeps_the_watched_script_without_key_metadata() {
        val wallet = deriveWatchWallet(ADDR_TAPROOT)
        val result =
            buildSend(
                BuildSendTxParams(
                    secret = ADDR_TAPROOT,
                    wallet = wallet,
                    utxos = listOf(SendInputUtxo("33".repeat(32), 0, 100_000L, wallet.scripts[0])),
                    toAddress = DEST_LEGACY,
                    amountSats = SendAmount.Exact(50_000L),
                    feeRateSatPerVb = 1.0,
                    changeAddress = ADDR_TAPROOT,
                ),
            ) as PsbtSendResult

        val input = readPsbt(result.psbtHex).inputs[0]
        assertContentEquals(wallet.scripts[0], input.witnessUtxo?.publicKeyScript?.toByteArray())
        assertNull(input.taprootInternalKey)
    }
}
