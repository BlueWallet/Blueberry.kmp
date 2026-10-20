package io.bluewallet.blueberry.onboarding

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlin.math.abs
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
    fun settle_caps_the_coast_of_a_runaway_fling() {
        // A bogus 500 years/s must not slam the dial into the last year.
        assertEquals(11, settledYearIndex(position = 8f, velocityPerSecond = 500f, lastIndex = 17))
        assertEquals(5, settledYearIndex(position = 8f, velocityPerSecond = -500f, lastIndex = 17))
    }

    @Test
    fun small_drag_with_batched_samples_stays_near_its_year() {
        // A few pixels whose last two samples share a timestamp, as a touch batch can.
        val tracker = VelocityTracker()
        tracker.addPosition(0L, Offset(100f, 0f))
        tracker.addPosition(16L, Offset(97f, 0f))
        tracker.addPosition(16L, Offset(94f, 0f))
        val velocity = yearReleaseVelocity(tracker, slotPx = 300f)
        assertTrue(abs(velocity) < 3f, "velocity $velocity")
        assertEquals(8, settledYearIndex(position = 8.02f, velocityPerSecond = velocity, lastIndex = 17))
    }

    @Test
    fun steady_swipe_flings_forward() {
        val tracker = VelocityTracker()
        for (i in 0..6) tracker.addPosition(i * 16L, Offset(600f - i * 60f, 0f))
        // 60 px every 16 ms over a 300 px slot is about 12.5 years per second, forwards.
        val velocity = yearReleaseVelocity(tracker, slotPx = 300f)
        assertTrue(velocity > 10f && velocity < 15f, "velocity $velocity")
    }
}
