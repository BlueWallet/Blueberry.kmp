package io.bluewallet.blueberry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SendValidationTest {
    @Test
    fun fee_rate_must_be_positive_finite() {
        assertNull(parseFeeRateSatPerVb(""))
        assertNull(parseFeeRateSatPerVb("0"))
        assertNull(parseFeeRateSatPerVb("-1"))
        assertEquals(1.5, parseFeeRateSatPerVb("1.5"))
        assertEquals(10.0, parseFeeRateSatPerVb("10"))
    }
}
