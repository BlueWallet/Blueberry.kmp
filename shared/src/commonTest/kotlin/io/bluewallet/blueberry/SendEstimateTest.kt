package io.bluewallet.blueberry

import io.bluewallet.blueberry.wallet.AddressScriptType
import io.bluewallet.blueberry.wallet.BuildSendTxParams
import io.bluewallet.blueberry.wallet.SendAmount
import io.bluewallet.blueberry.wallet.SendInputUtxo
import io.bluewallet.blueberry.wallet.SignedSendResult
import io.bluewallet.blueberry.wallet.WatchGaps
import io.bluewallet.blueberry.wallet.buildSend
import io.bluewallet.blueberry.wallet.deriveWatchWallet
import io.bluewallet.blueberry.wallet.hd
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val MNEMONIC =
    "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"

class SendEstimateTest {
    private val watch = deriveWatchWallet(MNEMONIC, WatchGaps(3, 3))
    private val dest = watch.hd(AddressScriptType.P2WPKH, 2)

    private fun coin(
        n: Int,
        sats: Long,
        type: AddressScriptType = AddressScriptType.P2WPKH,
    ) = SendInputUtxo("${n}$n".repeat(32), 0, sats, watch.hd(type, n).scriptPubKey)

    private fun assertMatchesBuilder(
        utxos: List<SendInputUtxo>,
        amount: SendAmount,
        rate: Double,
        changeType: AddressScriptType = AddressScriptType.P2WPKH,
    ) {
        val change = watch.hd(changeType, 0, change = true)
        val built =
            buildSend(
                BuildSendTxParams(MNEMONIC, watch, utxos, dest.address, amount, rate, change.address),
            ) as SignedSendResult
        val est = sendFeeEstimate(utxos, amount, rate, dest.scriptPubKey, change.scriptPubKey) as SendFeeEstimate.Ok
        assertEquals(built.feeSats, est.feeSats, "fee")
        assertEquals(built.changeSats, est.changeSats, "change")
        assertEquals(built.vsize, est.vsize, "vsize")
        assertEquals(sendPaidSats(amount, utxos.sumOf { it.valueSats }, built.feeSats, built.changeSats), est.paidSats)
    }

    @Test
    fun estimate_matches_builder_for_every_change_type() {
        for (type in listOf(AddressScriptType.P2SH_P2WPKH, AddressScriptType.P2WPKH, AddressScriptType.P2TR)) {
            assertMatchesBuilder(listOf(coin(0, 100_000)), SendAmount.Exact(40_000), 3.0, type)
        }
        assertMatchesBuilder(listOf(coin(0, 100_000), coin(1, 70_000, AddressScriptType.P2TR)), SendAmount.Exact(120_000), 12.0)
    }

    @Test
    fun estimate_matches_builder_for_max_and_fractional_rates() {
        assertMatchesBuilder(listOf(coin(0, 100_000), coin(1, 5_000)), SendAmount.Max, 1.5)
        assertMatchesBuilder(listOf(coin(0, 100_000)), SendAmount.Exact(12_345), 2.7)
    }

    @Test
    fun change_at_dust_goes_to_the_fee() {
        val utxos = listOf(coin(0, 100_000))
        val toScript = dest.scriptPubKey
        val changeScript = watch.hd(AddressScriptType.P2WPKH, 0, change = true).scriptPubKey
        val feeWithChange = (sendFeeEstimate(utxos, SendAmount.Exact(10_000), 1.0, toScript, changeScript) as SendFeeEstimate.Ok).feeSats
        val atDust = 100_000 - feeWithChange - SEND_DUST_SATS
        val dust = sendFeeEstimate(utxos, SendAmount.Exact(atDust), 1.0, toScript, changeScript) as SendFeeEstimate.Ok
        assertEquals(0, dust.changeSats)
        assertEquals(100_000 - atDust, dust.feeSats)
        val kept = sendFeeEstimate(utxos, SendAmount.Exact(atDust - 1), 1.0, toScript, changeScript) as SendFeeEstimate.Ok
        assertEquals(SEND_DUST_SATS + 1, kept.changeSats)
        assertMatchesBuilder(utxos, SendAmount.Exact(atDust), 1.0)
        assertMatchesBuilder(utxos, SendAmount.Exact(atDust - 1), 1.0)
    }

    @Test
    fun short_fall_reports_the_missing_sats_where_the_builder_throws() {
        val utxos = listOf(coin(0, 50_000))
        val change = watch.hd(AddressScriptType.P2WPKH, 0, change = true)
        val est = sendFeeEstimate(utxos, SendAmount.Exact(50_000), 5.0, dest.scriptPubKey, change.scriptPubKey)
        assertTrue(est is SendFeeEstimate.Short && est.missingSats > 0)
        assertFailsWith<IllegalArgumentException> {
            buildSend(BuildSendTxParams(MNEMONIC, watch, utxos, dest.address, SendAmount.Exact(50_000), 5.0, change.address))
        }
    }

    @Test
    fun coverage_without_a_rate_needs_only_the_amount() {
        val change = placeholderOutputScript(AddressScriptType.P2WPKH)
        val none = sendCoverage(emptyList(), SendAmount.Exact(40_000), null, dest.scriptPubKey, change)
        assertEquals(SendCoverage(0, 40_000, false), none)
        val ok = sendCoverage(listOf(coin(0, 40_000)), SendAmount.Exact(40_000), null, dest.scriptPubKey, change)
        assertTrue(ok.covered)
        val max = sendCoverage(listOf(coin(0, 40_000)), SendAmount.Max, null, dest.scriptPubKey, change)
        assertEquals(SEND_DUST_SATS + 1, max.neededSats)
    }

    @Test
    fun coverage_with_a_rate_adds_the_fee() {
        val change = placeholderOutputScript(AddressScriptType.P2WPKH)
        val utxos = listOf(coin(0, 100_000))
        val needs =
            listOf(1.0, 3.0, 50.0).map { rate ->
                sendCoverage(utxos, SendAmount.Exact(40_000), rate, dest.scriptPubKey, change).neededSats
            }
        assertTrue(needs[0] in 40_001 until needs[1] && needs[1] < needs[2])
        val short = sendCoverage(utxos, SendAmount.Exact(99_990), 3.0, dest.scriptPubKey, change)
        assertFalse(short.covered)
        assertTrue(short.neededSats > 100_000)
        val max = sendCoverage(utxos, SendAmount.Max, 3.0, dest.scriptPubKey, change)
        assertTrue(max.covered && max.neededSats > SEND_DUST_SATS + 1)
    }
}
