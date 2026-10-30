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
        assertNull(parseFeeRateSatPerVb("0.5"))
        assertNull(parseFeeRateSatPerVb("0.00001"))
        assertEquals(1.0, parseFeeRateSatPerVb("1"))
        assertEquals(1.5, parseFeeRateSatPerVb("1.5"))
        assertEquals(10.0, parseFeeRateSatPerVb("10"))
    }

    @Test
    fun an_empty_wallet_is_not_asked_for_an_amount() {
        assertEquals("No spendable coins left", amountContinueLabel("0.5", 0))
        assertEquals("Enter an amount", amountContinueLabel("", 50_000))
        assertEquals("Continue", amountContinueLabel("0.0001", 50_000))
        assertEquals("Every coin is frozen", amountContinueLabel("0.5", 0, CoinAvailability.AllFrozen))
        assertEquals(ALL_FROZEN_NOTICE, sendAmountUnavailable("0.5", 0, CoinAvailability.AllFrozen))
    }

    @Test
    fun a_wallet_whose_coins_are_all_sending_says_so() {
        assertEquals("Every coin is sending", amountContinueLabel("0.5", 0, CoinAvailability.AllSending))
        assertEquals(ALL_SENDING_NOTICE, sendAmountUnavailable("0.5", 0, CoinAvailability.AllSending))
        assertEquals(ALL_SENDING_NOTICE, noSpendableNotice(0, CoinAvailability.AllSending))
        assertNull(noSpendableNotice(50_000, CoinAvailability.Spendable))
    }

    @Test
    fun review_says_why_an_amount_is_no_longer_payable() {
        assertEquals("No spendable coins left.", sendAmountUnavailable("0.5", 0))
        assertEquals("The wallet can no longer cover this amount.", sendAmountUnavailable("0.5", 1_000))
        assertEquals("Enter an amount.", sendAmountUnavailable("", 50_000))
        assertNull(sendAmountUnavailable("0.0001", 50_000))
    }

    @Test
    fun insufficient_selection_error_detects_build_message() {
        assertTrue(isInsufficientSelectionError("insufficient funds for amount and fee"))
        assertTrue(isInsufficientSelectionError("Insufficient funds"))
        assertFalse(isInsufficientSelectionError("missing send details"))
        assertFalse(isInsufficientSelectionError(null))
    }
}
