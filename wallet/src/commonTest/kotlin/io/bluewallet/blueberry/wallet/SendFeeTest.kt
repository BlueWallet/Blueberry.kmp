package io.bluewallet.blueberry.wallet

import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxOut
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Synthetic coins. The fee baked into a signed send is ceil(rate × virtual size) of that exact
 * input list and the outputs that were actually created.
 */
class SendFeeTest {
    private val wallet = deriveWatchWallet(WIF_BECH32)
    private val changeByType = AddressScriptType.entries.associateWith { byType(wallet, it).address }

    @Test
    fun exact_send_fee_matches_signed_vsize_for_every_input_and_output_type() {
        val types = AddressScriptType.entries
        val rates = listOf(1.0, 2.5)
        val n = types.size
        val count = n * n * n * rates.size
        for (i in 0 until count) {
            val inputType = types[i % n]
            val destType = types[(i / n) % n]
            val changeType = types[(i / (n * n)) % n]
            val rate = rates[i / (n * n * n)]
            val utxos = listOf(coin(inputType, 200_000L, salt = inputType.ordinal + 1))
            val result = sign(utxos, SendAmount.Exact(50_000L), rate, destType, changeType)
            assertBakedFee(result, utxos, rate, "$inputType -> $destType change $changeType @ $rate")
            assertTrue(result.changeSats > 546L, result.changeSats.toString())
        }
    }

    @Test
    fun exact_send_fee_matches_signed_vsize_for_every_input_mix() {
        for (mix in AddressScriptType.entries.nonEmptySubsets()) {
            val utxos = mix.mapIndexed { index, type -> coin(type, 80_000L, salt = 20 + index) }
            val result =
                sign(
                    utxos,
                    SendAmount.Exact(20_000L),
                    rate = 3.5,
                    dest = AddressScriptType.P2TR,
                    change = AddressScriptType.P2PKH,
                )
            assertBakedFee(result, utxos, 3.5, mix.joinToString())
            assertEquals(mix.size, Transaction.read(result.txHex).txIn.size)
        }
    }

    @Test
    fun max_send_fee_matches_the_precise_estimate_for_every_input_mix_and_deposit_type() {
        for (mix in AddressScriptType.entries.nonEmptySubsets()) {
            for (dest in AddressScriptType.entries) {
                val utxos = mix.mapIndexed { index, type -> coin(type, 90_000L, salt = 40 + index) }
                val rate = 1.5
                val result = sign(utxos, SendAmount.Max, rate, dest, AddressScriptType.P2WPKH)
                assertBakedFee(result, utxos, rate, "max $mix -> $dest")
                assertEquals(0L, result.changeSats)
                val tx = Transaction.read(result.txHex)
                assertEquals(1, tx.txOut.size)
                assertEquals(
                    utxos.sumOf { it.valueSats } - result.feeSats,
                    tx.txOut[0].amount.toLong(),
                )
            }
        }
    }

    @Test
    fun heavier_input_types_pay_a_higher_fee_for_the_same_send() {
        val rate = 1.0
        val fees =
            AddressScriptType.entries.associateWith { type ->
                val utxos = listOf(coin(type, 100_000L, salt = 70 + type.ordinal))
                sign(utxos, SendAmount.Exact(10_000L), rate, AddressScriptType.P2WPKH, AddressScriptType.P2WPKH).feeSats
            }
        assertTrue(fees.getValue(AddressScriptType.P2TR) < fees.getValue(AddressScriptType.P2WPKH))
        assertTrue(fees.getValue(AddressScriptType.P2WPKH) < fees.getValue(AddressScriptType.P2SH_P2WPKH))
        assertTrue(fees.getValue(AddressScriptType.P2SH_P2WPKH) < fees.getValue(AddressScriptType.P2PKH))
    }

    @Test
    fun a_larger_output_script_increases_the_fee() {
        val utxos = listOf(coin(AddressScriptType.P2WPKH, 100_000L, salt = 80))
        val fees =
            AddressScriptType.entries.associateWith { dest ->
                sign(utxos, SendAmount.Exact(10_000L), 1.0, dest, AddressScriptType.P2WPKH).feeSats
            }
        assertTrue(fees.getValue(AddressScriptType.P2WPKH) < fees.getValue(AddressScriptType.P2SH_P2WPKH))
        assertTrue(fees.getValue(AddressScriptType.P2SH_P2WPKH) < fees.getValue(AddressScriptType.P2PKH))
        assertTrue(fees.getValue(AddressScriptType.P2PKH) < fees.getValue(AddressScriptType.P2TR))
    }

    @Test
    fun fractional_rate_keeps_change_just_above_dust_and_pays_the_precise_fee() {
        val rate = 1.1
        val utxos = listOf(coin(AddressScriptType.P2WPKH, 50_000L, salt = 90))
        val dest = AddressScriptType.P2PKH
        val change = AddressScriptType.P2TR
        val precise =
            estimateSendFeeSats(
                utxos,
                rate,
                listOf(outputScriptFromAddress(destAddress(dest)), outputScriptFromAddress(changeByType.getValue(change))),
            )
        val changeSats = 547L
        val amount = utxos.single().valueSats - precise - changeSats
        val result = sign(utxos, SendAmount.Exact(amount), rate, dest, change)
        assertEquals(changeSats, result.changeSats)
        assertBakedFee(result, utxos, rate, "change just above dust")
    }

    @Test
    fun max_send_just_above_dust_pays_the_precise_fee() {
        val rate = 1.1
        val type = AddressScriptType.P2TR
        val dest = AddressScriptType.P2WPKH
        val probe = listOf(coin(type, 20_000L, salt = 91))
        val precise = estimateSendFeeSats(probe, rate, listOf(outputScriptFromAddress(destAddress(dest))))
        val value = precise + 547L
        val utxos = listOf(coin(type, value, salt = 92))
        val result = sign(utxos, SendAmount.Max, rate, dest, AddressScriptType.P2WPKH)
        assertEquals(precise, result.feeSats)
        val paid =
            Transaction
                .read(result.txHex)
                .txOut
                .single()
                .amount
                .toLong()
        assertEquals(547L, paid)
        assertBakedFee(result, utxos, rate, "max just above dust")
    }

    @Test
    fun leftover_at_dust_is_paid_as_fee_and_still_covers_the_signed_vsize() {
        val rate = 1.0
        val utxos = listOf(coin(AddressScriptType.P2WPKH, 50_000L, salt = 93))
        val dest = AddressScriptType.P2WPKH
        val change = AddressScriptType.P2WPKH
        val precise =
            estimateSendFeeSats(
                utxos,
                rate,
                listOf(outputScriptFromAddress(destAddress(dest)), outputScriptFromAddress(changeByType.getValue(change))),
            )
        val amount = utxos.single().valueSats - precise - 546L
        val result = sign(utxos, SendAmount.Exact(amount), rate, dest, change)
        assertEquals(0L, result.changeSats)
        val tx = Transaction.read(result.txHex)
        val vsize = (tx.weight() + 3) / 4
        assertEquals(utxos.single().valueSats - amount, result.feeSats)
        assertTrue(result.feeSats >= ceil(rate * vsize).toLong())
        assertEquals(1, tx.txOut.size)
    }

    @Test
    fun ten_inputs_fee_matches_signed_vsize_for_a_few_type_mixes() {
        val rate = 2.5
        for ((index, mix) in tenMixes().withIndex()) {
            val utxos = mix.mapIndexed { coinIndex, type -> coin(type, 100_000L, salt = 200 + index * 10 + coinIndex) }
            val result = sign(utxos, SendAmount.Exact(50_000L), rate, AddressScriptType.P2WPKH, AddressScriptType.P2TR)
            assertEquals(10, Transaction.read(result.txHex).txIn.size)
            assertBakedFee(result, utxos, rate, mix.groupingBy { it }.eachCount().toString())
        }
    }

    @Test
    fun ten_outputs_fee_matches_signed_input_weight_for_a_few_script_mixes() {
        val rate = 2.5
        val mix = tenMixes().last()
        val utxos = mix.mapIndexed { index, type -> coin(type, 100_000L, salt = 300 + index) }
        val signed = Transaction.read(sign(utxos, SendAmount.Max, rate, AddressScriptType.P2WPKH, AddressScriptType.P2WPKH).txHex)
        for (outputs in tenMixes()) {
            val scripts = outputs.map { outputScriptFromAddress(destAddress(it)) }
            val weighed =
                signed.copy(
                    txOut = scripts.map { TxOut(Satoshi(0L), it) },
                )
            val vsize = (weighed.weight() + 3) / 4
            assertEquals(
                ceil(rate * vsize).toLong(),
                estimateSendFeeSats(utxos, rate, scripts),
                outputs.groupingBy { it }.eachCount().toString(),
            )
        }
    }

    private fun coin(
        type: AddressScriptType,
        valueSats: Long,
        salt: Int,
    ): SendInputUtxo {
        val addr = byType(wallet, type)
        val fund = testFundingTx(addr.scriptPubKey, valueSats, salt)
        return SendInputUtxo(
            txid = fund.txid,
            vout = 0,
            valueSats = valueSats,
            scriptPubKey = addr.scriptPubKey,
            nonWitnessUtxo = if (type == AddressScriptType.P2PKH) fund.bytes else null,
        )
    }

    private fun sign(
        utxos: List<SendInputUtxo>,
        amount: SendAmount,
        rate: Double,
        dest: AddressScriptType,
        change: AddressScriptType,
    ): BuildSendTxResult =
        buildSignedSendTx(
            BuildSendTxParams(
                secret = WIF_BECH32,
                wallet = wallet,
                utxos = utxos,
                toAddress = destAddress(dest),
                amountSats = amount,
                feeRateSatPerVb = rate,
                changeAddress = changeByType.getValue(change),
            ),
        )

    private fun destAddress(type: AddressScriptType): String =
        when (type) {
            AddressScriptType.P2PKH -> DEST_LEGACY
            AddressScriptType.P2SH_P2WPKH -> BIP49_HONEY_EXT_0
            AddressScriptType.P2WPKH -> BLUE_EXTERNAL_1
            AddressScriptType.P2TR -> BIP86_ABANDON_EXT_0
        }
}

private fun byType(
    wallet: WatchWallet,
    scriptType: AddressScriptType,
): WatchAddress = wallet.addresses.first { it.scriptType == scriptType }

private fun assertBakedFee(
    result: BuildSendTxResult,
    utxos: List<SendInputUtxo>,
    rate: Double,
    label: String,
) {
    val tx = Transaction.read(result.txHex)
    val vsize = (tx.weight() + 3) / 4
    val paid = tx.txOut.sumOf { it.amount.toLong() }
    val inputSum = utxos.sumOf { it.valueSats }
    assertEquals(vsize, result.vsize, label)
    assertEquals(inputSum - paid, result.feeSats, label)
    assertEquals(ceil(rate * vsize).toLong(), result.feeSats, label)
    assertEquals(
        result.feeSats,
        estimateSendFeeSats(utxos, rate, tx.txOut.map { it.publicKeyScript.toByteArray() }),
        label,
    )
}

/** Ten coins or ten outputs: one list per script type, plus one mix of all four. */
private fun tenMixes(): List<List<AddressScriptType>> {
    val each = AddressScriptType.entries.map { type -> List(10) { type } }
    val mixed =
        listOf(
            AddressScriptType.P2PKH,
            AddressScriptType.P2PKH,
            AddressScriptType.P2PKH,
            AddressScriptType.P2SH_P2WPKH,
            AddressScriptType.P2SH_P2WPKH,
            AddressScriptType.P2WPKH,
            AddressScriptType.P2WPKH,
            AddressScriptType.P2WPKH,
            AddressScriptType.P2TR,
            AddressScriptType.P2TR,
        )
    return each + listOf(mixed)
}

private fun List<AddressScriptType>.nonEmptySubsets(): List<List<AddressScriptType>> {
    val out = mutableListOf<List<AddressScriptType>>()
    for (mask in 1 until (1 shl size)) {
        out.add(filterIndexed { index, _ -> mask and (1 shl index) != 0 })
    }
    return out
}
