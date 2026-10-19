package io.bluewallet.blueberry.onboarding

import kotlin.test.Test
import kotlin.test.assertEquals

class YearSliderTest {
    @Test
    fun settle_snaps_to_nearest_year_when_still() {
        assertEquals(10, settledYearIndex(position = 10.2f, velocityPerSecond = 0f, lastIndex = 17))
        assertEquals(11, settledYearIndex(position = 10.5f, velocityPerSecond = 0f, lastIndex = 17))
    }

    @Test
    fun settle_coasts_with_the_fling_then_clamps() {
        assertEquals(
            11,
            settledYearIndex(position = 10.4f, velocityPerSecond = 3f, lastIndex = 17),
        )
        assertEquals(
            0,
            settledYearIndex(position = 0.2f, velocityPerSecond = -8f, lastIndex = 17),
        )
        assertEquals(
            17,
            settledYearIndex(position = 16.6f, velocityPerSecond = 12f, lastIndex = 17),
        )
    }
}
