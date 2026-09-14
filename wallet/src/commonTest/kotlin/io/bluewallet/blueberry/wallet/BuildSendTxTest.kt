package io.bluewallet.blueberry.wallet

import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.psbt.Psbt
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private fun abandonWallet() = deriveWatchWallet(ABANDON, WatchGaps(2, 2))

private fun utxoAt(
    wallet: WatchWallet,
    index: Int = 0,
) = SendInputUtxo(
    txid = "11".repeat(32),
    vout = 0,
    valueSats = 100_000L,
    scriptPubKey = wallet.hd(AddressScriptType.P2WPKH, index).scriptPubKey,
)

private fun baseParams(
    secret: String = ABANDON,
    wallet: WatchWallet = abandonWallet(),
    utxos: List<SendInputUtxo> = listOf(utxoAt(wallet)),
    amount: SendAmount = SendAmount.Exact(50_000L),
    feeRate: Double = 1.0,
) = BuildSendTxParams(
    secret = secret,
    wallet = wallet,
    utxos = utxos,
    toAddress = BLUE_EXTERNAL_1,
    amountSats = amount,
    feeRateSatPerVb = feeRate,
    changeAddress = BLUE_INTERNAL_0,
)

private fun parseSigned(txHex: String): Transaction = Transaction.read(txHex)

private fun outputScriptOf(address: String): ByteArray = outputScriptFromAddress(address)

class BuildSendTxTest {
    @Test
    fun signs_p2wpkh_with_change_near_the_requested_fee_rate() {
        val wallet = abandonWallet()
        val feeRate = 10.0
        val amountSats = 50_000L
        val result =
            buildSignedSendTx(
                baseParams(wallet = wallet, utxos = listOf(utxoAt(wallet)), amount = SendAmount.Exact(amountSats), feeRate = feeRate),
            )

        val actualFeerate = result.feeSats.toDouble() / result.vsize
        assertTrue(actualFeerate.roundToInt() >= feeRate)
        assertTrue(actualFeerate <= feeRate + 1)

        val tx = parseSigned(result.txHex)
        assertEquals(1, tx.txIn.size)
        assertEquals(2, tx.txOut.size)
        val internalScript = outputScriptOf(BLUE_INTERNAL_0)
        val externalScript = outputScriptOf(BLUE_EXTERNAL_1)
        assertTrue(tx.txOut.any { it.publicKeyScript.toByteArray().contentEquals(internalScript) })
        val destOut = tx.txOut.first { it.publicKeyScript.toByteArray().contentEquals(externalScript) }
        assertEquals(amountSats, destOut.amount.toLong())
        assertEquals(tx.txid.toString(), result.txid)
        val changeIndex = tx.txOut.indexOfFirst { it.publicKeyScript.toByteArray().contentEquals(internalScript) }
        assertEquals(listOf(changeIndex), result.changeVouts)
    }

    @Test
    fun one_sat_per_vb_yields_fee_equal_to_vsize() {
        val wallet = abandonWallet()
        val result =
            buildSignedSendTx(
                baseParams(wallet = wallet, utxos = listOf(utxoAt(wallet)), amount = SendAmount.Exact(10_000L), feeRate = 1.0),
            )
        assertEquals(result.vsize.toLong(), result.feeSats)
    }

    @Test
    fun fractional_fee_rate_uses_ceil_rate_times_vsize() {
        val wallet = abandonWallet()
        for (feeRate in listOf(0.5, 1.5)) {
            val result =
                buildSignedSendTx(
                    baseParams(wallet = wallet, utxos = listOf(utxoAt(wallet)), amount = SendAmount.Exact(50_000L), feeRate = feeRate),
                )
            assertEquals(ceil(feeRate * result.vsize).toLong(), result.feeSats)
        }
    }

    @Test
    fun send_max_one_output_no_change_fee_ceil_rate_times_vsize() {
        val wallet = abandonWallet()
        val utxo = utxoAt(wallet)
        val feeRate = 1.5
        val result =
            buildSignedSendTx(
                baseParams(wallet = wallet, utxos = listOf(utxo), amount = SendAmount.Max, feeRate = feeRate),
            )

        assertEquals(0L, result.changeSats)
        assertEquals(emptyList(), result.changeVouts)
        assertEquals(ceil(feeRate * result.vsize).toLong(), result.feeSats)

        val tx = parseSigned(result.txHex)
        assertEquals(tx.txid.toString(), result.txid)
        assertEquals(1, tx.txIn.size)
        assertEquals(1, tx.txOut.size)
        val externalScript = outputScriptOf(BLUE_EXTERNAL_1)
        assertTrue(
            tx.txOut[0]
                .publicKeyScript
                .toByteArray()
                .contentEquals(externalScript),
        )
        assertEquals(utxo.valueSats - result.feeSats, tx.txOut[0].amount.toLong())
    }

    @Test
    fun send_max_with_multiple_utxos_uses_all_inputs_and_one_output() {
        val wallet = abandonWallet()
        val a = utxoAt(wallet, 0)
        val b = utxoAt(wallet, 1).copy(txid = "22".repeat(32), valueSats = 80_000L)
        val result =
            buildSignedSendTx(
                baseParams(wallet = wallet, utxos = listOf(a, b), amount = SendAmount.Max, feeRate = 1.0),
            )

        val tx = parseSigned(result.txHex)
        assertEquals(2, tx.txIn.size)
        assertEquals(1, tx.txOut.size)
        assertEquals(0L, result.changeSats)
        assertEquals(a.valueSats + b.valueSats - result.feeSats, tx.txOut[0].amount.toLong())
    }

    @Test
    fun send_max_rejects_dust_leftover() {
        val wallet = abandonWallet()
        val utxo = utxoAt(wallet).copy(valueSats = 600L)
        val error =
            assertFailsWith<IllegalArgumentException> {
                buildSignedSendTx(baseParams(wallet = wallet, utxos = listOf(utxo), amount = SendAmount.Max, feeRate = 1.0))
            }
        assertTrue(error.message.orEmpty().contains("insufficient"))
    }

    @Test
    fun send_max_rejects_uneconomical_utxo() {
        val wallet = abandonWallet()
        val big = utxoAt(wallet, 0)
        val tiny = utxoAt(wallet, 1).copy(txid = "22".repeat(32), valueSats = 30L)
        val error =
            assertFailsWith<IllegalArgumentException> {
                buildSignedSendTx(baseParams(wallet = wallet, utxos = listOf(big, tiny), amount = SendAmount.Max, feeRate = 1.0))
            }
        assertTrue(error.message.orEmpty().contains("uneconomical"))
    }

    @Test
    fun non_max_also_rejects_uneconomical_utxos() {
        val wallet = abandonWallet()
        val big = utxoAt(wallet, 0)
        val tiny = utxoAt(wallet, 1).copy(txid = "22".repeat(32), valueSats = 30L)
        val error =
            assertFailsWith<IllegalArgumentException> {
                buildSignedSendTx(
                    baseParams(wallet = wallet, utxos = listOf(big, tiny), amount = SendAmount.Exact(50_000L), feeRate = 1.0),
                )
            }
        assertTrue(error.message.orEmpty().contains("uneconomical"))
    }

    @Test
    fun self_send_keeps_the_payment_and_reports_real_change() {
        val wallet = abandonWallet()
        val utxo = utxoAt(wallet)
        val feeRate = 1.5

        for (amountSats in listOf(10_000L, 50_000L)) {
            val result =
                buildSignedSendTx(
                    BuildSendTxParams(
                        secret = ABANDON,
                        wallet = wallet,
                        utxos = listOf(utxo),
                        toAddress = BLUE_INTERNAL_0,
                        amountSats = SendAmount.Exact(amountSats),
                        feeRateSatPerVb = feeRate,
                        changeAddress = BLUE_INTERNAL_0,
                    ),
                )

            val tx = parseSigned(result.txHex)
            assertEquals(2, tx.txOut.size)
            val amounts = tx.txOut.map { it.amount.toLong() }
            assertTrue(amounts.contains(amountSats))
            assertEquals(utxo.valueSats - amountSats - result.feeSats, result.changeSats)
            assertEquals(ceil(feeRate * result.vsize).toLong(), result.feeSats)
            assertEquals(1, result.changeVouts.size)
            assertEquals(result.changeSats, tx.txOut[result.changeVouts[0]].amount.toLong())
            assertTrue(tx.txOut[result.changeVouts[0]].amount.toLong() != amountSats)
        }
    }

    @Test
    fun rejects_zpub_empty_utxos_bad_amount_fee_and_insufficient_funds() {
        val wallet = abandonWallet()
        val utxo = utxoAt(wallet)

        fun params(
            secret: String = ABANDON,
            utxos: List<SendInputUtxo> = listOf(utxo),
            amount: SendAmount = SendAmount.Exact(50_000L),
            feeRate: Double = 1.0,
            toAddress: String = BLUE_EXTERNAL_1,
        ) = BuildSendTxParams(
            secret = secret,
            wallet = wallet,
            utxos = utxos,
            toAddress = toAddress,
            amountSats = amount,
            feeRateSatPerVb = feeRate,
            changeAddress = BLUE_INTERNAL_0,
        )

        val zpubError = assertFailsWith<IllegalArgumentException> { buildSignedSendTx(params(secret = BLUE_ZPUB)) }
        assertTrue(zpubError.message.orEmpty().let { it.contains("mnemonic") || it.contains("WIF") })

        val noUtxosError = assertFailsWith<IllegalArgumentException> { buildSignedSendTx(params(utxos = emptyList())) }
        assertTrue(noUtxosError.message.orEmpty().contains("no UTXOs"))

        val amountError = assertFailsWith<IllegalArgumentException> { buildSignedSendTx(params(amount = SendAmount.Exact(0L))) }
        assertTrue(amountError.message.orEmpty().contains("amount"))

        val feeZeroError = assertFailsWith<IllegalArgumentException> { buildSignedSendTx(params(feeRate = 0.0)) }
        assertTrue(feeZeroError.message.orEmpty().contains("fee rate"))

        val feeInfError =
            assertFailsWith<IllegalArgumentException> {
                buildSignedSendTx(params(feeRate = Double.POSITIVE_INFINITY))
            }
        assertTrue(feeInfError.message.orEmpty().contains("fee rate"))

        val insufficientError =
            assertFailsWith<IllegalArgumentException> {
                buildSignedSendTx(params(amount = SendAmount.Exact(200_000L)))
            }
        assertTrue(insufficientError.message.orEmpty().contains("insufficient"))

        val invalidAddressError =
            assertFailsWith<IllegalArgumentException> {
                buildSignedSendTx(params(toAddress = "not-an-address"))
            }
        val invalidMsg = invalidAddressError.message.orEmpty().lowercase()
        assertTrue(invalidMsg.contains("invalid") && invalidMsg.contains("address"))
    }

    @Test
    fun build_send_zpub_returns_unsigned_psbt_mnemonic_returns_signed_tx() {
        val zWallet = deriveWatchWallet(BLUE_ZPUB, WatchGaps(2, 1))
        val zpubParams =
            BuildSendTxParams(
                secret = BLUE_ZPUB,
                wallet = zWallet,
                utxos = listOf(utxoAt(zWallet)),
                toAddress = BLUE_EXTERNAL_1,
                amountSats = SendAmount.Exact(50_000L),
                feeRateSatPerVb = 10.0,
                changeAddress = BLUE_INTERNAL_0,
            )

        val result = buildSend(zpubParams)
        val psbtResult = result as PsbtSendResult
        assertTrue(psbtResult.psbtHex.startsWith("70736274ff"))
        assertTrue(psbtResult.changeSats > 0L)
        assertEquals(listOf(1), psbtResult.changeVouts)
        assertTrue(psbtResult.txid.isNotEmpty())

        val direct = buildUnsignedSendPsbt(zpubParams)
        assertEquals(direct.psbtHex, psbtResult.psbtHex)

        val mWallet = abandonWallet()
        val signed =
            buildSend(
                BuildSendTxParams(
                    secret = ABANDON,
                    wallet = mWallet,
                    utxos = listOf(utxoAt(mWallet)),
                    toAddress = BLUE_EXTERNAL_1,
                    amountSats = SendAmount.Exact(50_000L),
                    feeRateSatPerVb = 1.0,
                    changeAddress = BLUE_INTERNAL_0,
                ),
            )
        val signedResult = signed as SignedSendResult
        parseSigned(signedResult.txHex)
    }

    @Test
    fun build_send_zpub_send_max_returns_single_output_psbt_with_no_change() {
        val zWallet = deriveWatchWallet(BLUE_ZPUB, WatchGaps(2, 1))
        val utxo = utxoAt(zWallet)
        val result =
            buildSend(
                BuildSendTxParams(
                    secret = BLUE_ZPUB,
                    wallet = zWallet,
                    utxos = listOf(utxo),
                    toAddress = BLUE_EXTERNAL_1,
                    amountSats = SendAmount.Max,
                    feeRateSatPerVb = 10.0,
                    changeAddress = BLUE_INTERNAL_0,
                ),
            )

        val psbtResult = result as PsbtSendResult
        assertEquals(0L, psbtResult.changeSats)
        assertEquals(emptyList(), psbtResult.changeVouts)

        val psbt = (Psbt.read(hexToBytes(psbtResult.psbtHex)).right ?: error("failed to parse psbt"))
        assertEquals(1, psbt.global.tx.txIn.size)
        assertEquals(1, psbt.global.tx.txOut.size)
        val externalScript = outputScriptOf(BLUE_EXTERNAL_1)
        assertTrue(
            psbt.global.tx.txOut[0]
                .publicKeyScript
                .toByteArray()
                .contentEquals(externalScript),
        )
        assertEquals(
            utxo.valueSats - psbtResult.feeSats,
            psbt.global.tx.txOut[0]
                .amount
                .toLong(),
        )
    }

    @Test
    fun signs_bip44_and_bip86_inputs_together() {
        val wallet = deriveWatchWallet(ABANDON, WatchGaps(1, 1))
        val p2pkh = wallet.hd(AddressScriptType.P2PKH, 0)
        val p2tr = wallet.hd(AddressScriptType.P2TR, 0)
        val fundLegacy = testFundingTx(p2pkh.scriptPubKey, 80_000L, salt = 1)
        val result =
            buildSignedSendTx(
                baseParams(
                    wallet = wallet,
                    utxos =
                        listOf(
                            SendInputUtxo(
                                txid = fundLegacy.txid,
                                vout = 0,
                                valueSats = 80_000L,
                                scriptPubKey = p2pkh.scriptPubKey,
                                nonWitnessUtxo = fundLegacy.bytes,
                            ),
                            SendInputUtxo(
                                txid = "22".repeat(32),
                                vout = 0,
                                valueSats = 70_000L,
                                scriptPubKey = p2tr.scriptPubKey,
                            ),
                        ),
                    amount = SendAmount.Exact(100_000L),
                    feeRate = 1.0,
                ),
            )
        val tx = parseSigned(result.txHex)
        assertEquals(2, tx.txIn.size)
        assertTrue(result.feeSats > 0L)
    }

    @Test
    fun build_send_zpub_psbt_origin_uses_account_fingerprint_and_relative_path() {
        val zWallet = deriveWatchWallet(BLUE_ZPUB, WatchGaps(2, 1))
        val accountFingerprint =
            DeterministicWallet.ExtendedPublicKey
                .decode(BLUE_ZPUB)
                .second
                .fingerprint()

        val result =
            buildSend(
                BuildSendTxParams(
                    secret = BLUE_ZPUB,
                    wallet = zWallet,
                    utxos = listOf(utxoAt(zWallet)),
                    toAddress = BLUE_EXTERNAL_1,
                    amountSats = SendAmount.Exact(50_000L),
                    feeRateSatPerVb = 10.0,
                    changeAddress = BLUE_INTERNAL_0,
                ),
            )
        val psbtResult = result as PsbtSendResult

        val psbt = (Psbt.read(hexToBytes(psbtResult.psbtHex)).right ?: error("failed to parse psbt"))
        val derivationPaths = psbt.inputs[0].derivationPaths
        assertTrue(derivationPaths.isNotEmpty())
        val origin = derivationPaths.values.first()
        assertEquals(accountFingerprint and 0xffffffffL, origin.masterKeyFingerprint and 0xffffffffL)
        assertEquals(listOf(0L, 0L), origin.keyPath.path)
    }
}
