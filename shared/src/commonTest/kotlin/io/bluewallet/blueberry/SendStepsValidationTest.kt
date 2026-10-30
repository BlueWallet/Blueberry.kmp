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
}
