package io.bluewallet.blueberry

import io.bluewallet.blueberry.wallet.SendAmount
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SendValidationTest {
    @Test
    fun details_require_address_amount_or_max_and_label() {
        val dest = "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu"
        assertTrue(validateSendDetails("not-an-address", "0.001", "rent", 100_000, "1") is SendDetailsValidation.Invalid)
        assertTrue(validateSendDetails(dest, "0", "rent", 100_000, "1") is SendDetailsValidation.Invalid)
        assertTrue(validateSendDetails(dest, "2", "rent", 100_000, "1") is SendDetailsValidation.Invalid)
        assertTrue(validateSendDetails(dest, "0.00050000", "   ", 100_000, "1") is SendDetailsValidation.Invalid)
        assertTrue(validateSendDetails(dest, "MAX", "", 100_000, "1") is SendDetailsValidation.Invalid)
        val ok = validateSendDetails(dest, "0.00050000", "  groceries  ", 100_000, "1") as SendDetailsValidation.Ok
        assertEquals(SendAmount.Exact(50_000L), ok.details.amountSats)
        assertEquals("groceries", ok.details.paymentLabel)
        val max = validateSendDetails(dest, " max ", "empty", 100_000, "10") as SendDetailsValidation.Ok
        assertEquals(SendAmount.Max, max.details.amountSats)
    }

    @Test
    fun details_require_positive_fee_rate() {
        val dest = "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu"
        val bad = validateSendDetails(dest, "0.00050000", "rent", 100_000, "") as SendDetailsValidation.Invalid
        assertEquals(SendField.FeeRate, bad.field)
        val zero = validateSendDetails(dest, "0.00050000", "rent", 100_000, "0") as SendDetailsValidation.Invalid
        assertEquals(SendField.FeeRate, zero.field)
        val ok = validateSendDetails(dest, "0.00050000", "rent", 100_000, "1.5") as SendDetailsValidation.Ok
        assertEquals(1.5, ok.feeRateSatPerVb)
    }

    @Test
    fun fee_rate_must_be_positive_finite() {
        assertNull(parseFeeRateSatPerVb(""))
        assertNull(parseFeeRateSatPerVb("0"))
        assertNull(parseFeeRateSatPerVb("-1"))
        assertEquals(1.5, parseFeeRateSatPerVb("1.5"))
        assertEquals(10.0, parseFeeRateSatPerVb("10"))
    }
}
