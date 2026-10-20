package io.bluewallet.blueberry.onboarding

import androidx.compose.ui.input.pointer.util.VelocityTracker1D
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
        val velocity = releaseVelocity(0L to 100f, 16L to 97f, 16L to 94f)
        assertTrue(abs(velocity) < 3f, "velocity $velocity")
        assertEquals(8, settledYearIndex(position = 8.02f, velocityPerSecond = velocity, lastIndex = 17))
    }

    @Test
    fun short_flick_reads_its_real_speed() {
        // 6 px in 16 ms over a 300 px slot is 1.25 years per second. Compose's iOS
        // 2D tracker reads this as about 3.1 and coasts to the next year.
        val velocity = releaseVelocity(0L to 100f, 16L to 94f)
        assertTrue(velocity > 1.1f && velocity < 1.4f, "velocity $velocity")
        assertEquals(8, settledYearIndex(position = 8.02f, velocityPerSecond = velocity, lastIndex = 17))
    }

    @Test
    fun steady_swipe_flings_forward() {
        // 60 px every 16 ms over a 300 px slot is about 12.5 years per second, forwards.
        val velocity = releaseVelocity(*Array(7) { i -> i * 16L to 600f - i * 60f })
        assertTrue(velocity > 10f && velocity < 15f, "velocity $velocity")
    }

    @Test
    fun holding_still_before_release_does_not_fling() {
        val velocity = releaseVelocity(0L to 600f, 16L to 540f, 32L to 480f, 300L to 480f)
        assertTrue(velocity == 0f, "velocity $velocity")
    }

    private fun releaseVelocity(vararg samples: Pair<Long, Float>): Float {
        val tracker = VelocityTracker1D(isDataDifferential = false)
        samples.forEach { (time, x) -> tracker.addDataPoint(time, x) }
        return yearReleaseVelocity(tracker, slotPx = 300f)
    }
}
