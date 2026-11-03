package io.bluewallet.blueberry

import io.bluewallet.blueberry.wallet.SendAmount
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private const val DEST = "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu"

class SendStepsValidationTest {
    @Test
    fun address_step_needs_a_valid_address_then_a_label() {
        assertEquals(SendField.Address, validateSendAddress("", "rent"))
        assertEquals(SendField.Address, validateSendAddress("bc1qnope", "rent"))
        assertEquals(SendField.Label, validateSendAddress(DEST, "   "))
        assertNull(validateSendAddress(" $DEST ", "rent"))
    }

    @Test
    fun amount_step_checks_against_the_wallet_total() {
        assertEquals(SendAmountCheck.Empty, checkSendAmount("", 100_000))
        assertEquals(SendAmountCheck.Empty, checkSendAmount("0", 100_000))
        assertEquals(SendAmountCheck.Empty, checkSendAmount("0.001", 0))
        assertEquals(SendAmountCheck.Empty, checkSendAmount("MAX", 0))
        assertEquals(SendAmountCheck.MoreThanWallet, checkSendAmount("0.00100001", 100_000))
        assertEquals(SendAmountCheck.Ok(SendAmount.Exact(100_000)), checkSendAmount("0.001", 100_000))
        assertEquals(SendAmountCheck.Ok(SendAmount.Max), checkSendAmount("MAX", 100_000))
    }

    @Test
    fun number_pad_builds_amounts_up_to_eight_decimals() {
        fun type(keys: String) = keys.fold("") { acc, k -> numberPadInput(acc, k) }
        assertEquals("0.0009", type(".0009"))
        assertEquals("12.5", type("12.5"))
        assertEquals("0.12345678", type("0.123456789"))
        assertEquals("1.5", type("1..5"))
        assertEquals("7", type("07"))
        assertEquals("0", type("00"))
        assertEquals("12345678", type("123456789"))
        assertEquals("1", type("12$NUMBER_PAD_BACKSPACE"))
        assertEquals("", type("$NUMBER_PAD_BACKSPACE"))
        assertEquals("3", numberPadInput("MAX", '3'))
    }

    @Test
    fun amount_cap_copy_names_the_selected_coin() {
        assertEquals(" in your wallet", amountCapSuffix(0))
        assertEquals(" in this coin", amountCapSuffix(1))
        assertEquals(" in the selected coins", amountCapSuffix(2))
        assertEquals("Everything you pick next, minus the fee.", amountMaxHint(0))
        assertEquals("This coin, minus the fee.", amountMaxHint(1))
        assertEquals("More than this coin holds. Add coins on the next step.", amountOverCapMessage(1))
        assertEquals("More than the selected coins hold. Add coins on the next step.", amountOverCapMessage(2))
    }

    @Test
    fun a_short_pick_is_a_note_and_only_the_wallet_is_an_error() {
        val picked = SendAmountBalance(capSats = 651_077, walletSats = 6_707_670, sendingSats = 0, availability = CoinAvailability.Spendable, selectedCount = 1)
        assertNull(amountNotice("0.00651077", picked))
        assertNull(amountNotice("MAX", picked))
        assertEquals("More than this coin holds. Add coins on the next step." to false, amountNotice("0.00698765", picked))
        assertEquals("More than your wallet holds." to true, amountNotice("1", picked))
        val none = SendAmountBalance(capSats = 6_707_670, walletSats = 6_707_670, sendingSats = 0, availability = CoinAvailability.Spendable)
        assertNull(amountNotice("0.00698765", none))
        assertEquals("More than your wallet holds." to true, amountNotice("1", none))
        val empty = SendAmountBalance(capSats = 0, walletSats = 0, sendingSats = 0, availability = CoinAvailability.Spendable)
        assertEquals("No spendable coins left." to true, amountNotice("0.1", empty))
    }
}
