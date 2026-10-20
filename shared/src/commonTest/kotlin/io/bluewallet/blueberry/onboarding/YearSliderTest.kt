package io.bluewallet.blueberry.onboarding

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

    @Test
    fun velocity_window_averages_recent_travel() {
        val window = YearVelocityWindow(windowMs = 100L)
        window.add(0L, 0.5f)
        window.add(50L, 0.5f)
        // 1.0 index over 50ms → 20 indexes / second
        assertEquals(20f, window.velocityPerSecond(50L), absoluteTolerance = 0.01f)
    }

    @Test
    fun velocity_window_drops_stale_samples_after_a_stop() {
        val window = YearVelocityWindow(windowMs = 100L)
        window.add(0L, 2f)
        window.add(40L, 2f)
        // Finger stopped; only a late quiet sample remains in the window.
        window.add(200L, 0f)
        assertTrue(window.velocityPerSecond(200L) < 0.01f)
    }
}
