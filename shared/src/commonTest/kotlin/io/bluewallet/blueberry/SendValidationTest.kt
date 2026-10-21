package io.bluewallet.blueberry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SendValidationTest {
    @Test
    fun fee_rate_must_be_positive_finite() {
        assertNull(parseFeeRateSatPerVb(""))
        assertNull(parseFeeRateSatPerVb("0"))
        assertNull(parseFeeRateSatPerVb("-1"))
        assertEquals(1.5, parseFeeRateSatPerVb("1.5"))
        assertEquals(10.0, parseFeeRateSatPerVb("10"))
    }

    @Test
    fun insufficient_selection_error_detects_build_message() {
        assertTrue(isInsufficientSelectionError("insufficient funds for amount and fee"))
        assertTrue(isInsufficientSelectionError("Insufficient funds"))
        assertFalse(isInsufficientSelectionError("missing send details"))
        assertFalse(isInsufficientSelectionError(null))
    }
}
