package io.bluewallet.blueberry

import io.bluewallet.blueberry.wallet.PsbtSendResult
import io.bluewallet.blueberry.wallet.SendAmount
import io.bluewallet.blueberry.wallet.SignedSendResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun utxo(
    n: Int,
    sats: Long,
    name: String? = null,
) = WalletUtxoRow(
    key = "${"$n".repeat(64)}:0",
    txid = "$n".repeat(64),
    vout = 0,
    outpointShort = "",
    valueSats = sats,
    scriptPubKey = byteArrayOf(),
    address = "bc1qaddress$n",
    amountLabel = "",
    height = 1,
    ageLabel = "",
    valueBar = "",
    name = name,
    isChange = false,
)

class SendReviewTest {
    @Test
    fun one_in_two_out_lists_recipient_change_and_fee() {
        val s = txSummary(listOf(utxo(1, 100_000)), SendAmount.Exact(40_000), BuiltSend("t", 500, 59_500, 141, listOf(1)), "Rent")
        assertEquals(listOf(TxOutputKind.Recipient, TxOutputKind.Change, TxOutputKind.Fee), s.outputs.map { it.kind })
        assertEquals(2, s.chainOutputs)
        assertEquals(40_500, s.totalLeavingSats)
        assertEquals(100_000, s.paidSats + s.changeSats + s.feeSats)
    }

    @Test
    fun inputs_are_sorted_largest_first() {
        val s =
            txSummary(
                listOf(utxo(1, 5_000), utxo(2, 90_000, "Salary"), utxo(3, 20_000)),
                SendAmount.Exact(100_000),
                BuiltSend("t", 700, 14_300, 250, listOf(1)),
                "Rent",
            )
        assertEquals(listOf(90_000L, 20_000L, 5_000L), s.inputs.map { it.valueSats })
        assertEquals("Salary", s.inputs.first().title)
        assertEquals(3, s.outputs.size)
        assertEquals(115_000, s.paidSats + s.changeSats + s.feeSats)
    }

    @Test
    fun max_has_no_change_row() {
        val s = txSummary(listOf(utxo(1, 100_000)), SendAmount.Max, BuiltSend("t", 300, 0, 110, emptyList()), "Rent")
        assertEquals(listOf(TxOutputKind.Recipient, TxOutputKind.Fee), s.outputs.map { it.kind })
        assertEquals(99_700, s.paidSats)
        assertEquals(1, s.chainOutputs)
    }

    @Test
    fun fee_warning_only_above_ten_percent() {
        assertNull(feeShareWarning(1_000, 10_000))
        assertEquals("The fee is 25% of what you're sending.", feeShareWarning(2_500, 10_000))
        assertNull(feeShareWarning(100, 0))
    }

    @Test
    fun review_button_names_the_action_it_runs() {
        val stamp = SendDraftStamp(emptyList(), "bc1q", SendAmount.Exact(1), 3.0)
        val signed = ReviewBuild.Built(SignedSendResult("00", "t", 1, 1, 0, emptyList()), stamp)
        val psbt = ReviewBuild.Built(PsbtSendResult("00", "t", 1, 1, 0, emptyList()), stamp)
        assertButton("Enter a fee rate", ReviewButtonAction.EditFee, true, reviewButton(null, ReviewBuild.Idle, false))
        assertButton("Building…", ReviewButtonAction.Send, false, reviewButton(3.0, ReviewBuild.Building, false))
        assertButton("Send via Tor", ReviewButtonAction.Send, true, reviewButton(3.0, signed, false))
        assertButton("Show PSBT to sign", ReviewButtonAction.Send, true, reviewButton(3.0, psbt, false))
        val short = ReviewBuild.Failed("insufficient funds for amount and fee")
        assertButton("Add coins or lower the fee", ReviewButtonAction.AddCoins, true, reviewButton(3.0, short, false))
        assertTrue(short.isShort())
        assertButton("Review the selection", ReviewButtonAction.AddCoins, true, reviewButton(3.0, ReviewBuild.Failed(SELECTION_CHANGED), false))
        assertButton("Waiting for the wallet", ReviewButtonAction.Send, false, reviewButton(3.0, ReviewBuild.Failed(WALLET_NOT_LOADED), false))
        assertButton("Can't send this", ReviewButtonAction.Send, false, reviewButton(3.0, ReviewBuild.Failed("boom"), false))
        assertButton("Review the selection", ReviewButtonAction.AddCoins, true, reviewButton(3.0, signed, true))
    }

    private fun assertButton(
        label: String,
        action: ReviewButtonAction,
        enabled: Boolean,
        button: ReviewButton,
    ) {
        assertEquals(label, button.label)
        assertEquals(action, button.action)
        assertEquals(enabled, button.enabled)
    }

    @Test
    fun fee_draft_needs_a_positive_rate_that_the_coins_cover() {
        val ok = SendFeeEstimate.Ok(1, 2, 3, 4)
        assertEquals(FeeDraftCheck.Invalid, checkFeeDraft("", { ok }))
        assertEquals(FeeDraftCheck.Invalid, checkFeeDraft("0", { ok }))
        assertEquals(FeeDraftCheck.Invalid, checkFeeDraft("fast", { ok }))
        assertEquals(FeeDraftCheck.Short(42), checkFeeDraft("50", { SendFeeEstimate.Short(42) }))
        val applied = checkFeeDraft("2.5", { ok })
        assertTrue(applied is FeeDraftCheck.Ok && applied.rate == 2.5)
    }
}
