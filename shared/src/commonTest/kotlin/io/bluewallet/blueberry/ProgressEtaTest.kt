package io.bluewallet.blueberry

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ProgressEtaTest {
    private fun steady(
        size: Int,
        gapMs: Long = 1_000,
        step: Int = 100,
    ): List<ProgressSample> = List(size) { i -> ProgressSample(at = i * gapMs, count = step * (i + 1)) }

    private fun ProgressSpeed.read(
        count: Int,
        at: Long,
    ): ProgressSpeed = next(prevCount = last?.count ?: 0, count = count, at = at, restarted = false)

    private fun fed(samples: List<ProgressSample>): ProgressSpeed = samples.fold(ProgressSpeed()) { speed, sample -> speed.read(sample.count, sample.at) }

    @Test
    fun speed_starts_at_the_first_increase() {
        assertEquals(ProgressSpeed(), ProgressSpeed().read(0, 60_000))
        assertEquals(ProgressSpeed(last = ProgressSample(180_000, 25)), ProgressSpeed().read(25, 180_000))
    }

    @Test
    fun speed_is_the_average_of_the_first_seven_gaps() {
        assertNull(fed(steady(7)).perMs)

        val seeded = fed(steady(8))
        assertEquals(List(7) { ProgressGap(ms = 1_000, count = 100) }, seeded.gaps)
        assertEquals(0.1, seeded.perMs!!, 1e-12)
    }

    @Test
    fun each_later_gap_adds_its_work_and_older_work_fades() {
        assertEquals(0.112407, fed(steady(8)).read(1_000, 8_000).perMs!!, 1e-6)
    }

    @Test
    fun a_short_stall_counts_once_by_its_length() {
        assertEquals(0.195181, fed(steady(8, gapMs = 100, step = 25)).read(225, 2_600).perMs!!, 1e-6)
    }

    @Test
    fun a_long_gap_fades_older_work_by_two_seconds_at_most() {
        assertEquals(0.011268, fed(steady(8, gapMs = 5_000)).read(900, 65_000).perMs!!, 1e-6)
    }

    @Test
    fun a_pause_keeps_the_speed() {
        val seeded = fed(steady(8, gapMs = 250, step = 25))
        val paused = seeded.read(225, 61_750)
        assertEquals(seeded.copy(last = ProgressSample(61_750, 225), pauses = 1), paused)

        assertEquals(paused.copy(last = ProgressSample(91_750, 250), pauses = 2), paused.read(250, 91_750))
    }

    @Test
    fun a_third_pause_in_a_row_measures_the_speed_again() {
        val twice = fed(steady(8, gapMs = 250, step = 25)).read(225, 5_000).read(250, 8_250)
        assertEquals(0.1, twice.perMs!!, 1e-12)
        assertEquals(0, twice.read(275, 8_500).pauses)

        val third = twice.read(275, 11_500)
        assertEquals(ProgressSpeed(last = ProgressSample(11_500, 275)), third)

        val slow = (1..7).fold(third) { speed, i -> speed.read(275 + 25 * i, 11_500 + 3_250L * i) }
        assertEquals(25.0 / 3_250, slow.perMs!!, 1e-12)
    }

    @Test
    fun slow_steady_increases_are_not_a_pause() {
        assertEquals(ProgressGap(ms = 6_000, count = 100), fed(steady(8, gapMs = 5_000)).read(900, 41_000).gaps.last())
    }

    @Test
    fun an_increase_at_the_same_time_counts_with_the_next_gap() {
        val seeded = fed(steady(8))
        val same = seeded.next(prevCount = 800, count = 900, at = 7_000, restarted = false)
        assertSame(seeded, same)
        assertEquals(0.112407, same.next(prevCount = 900, count = 1_000, at = 8_000, restarted = false).perMs!!, 1e-6)
    }

    @Test
    fun a_reading_without_a_time_changes_nothing() {
        val seeded = fed(steady(8))
        assertSame(seeded, seeded.next(prevCount = 800, count = 900, at = null, restarted = false))
    }

    @Test
    fun new_work_after_finished_work_drops_the_speed() {
        assertEquals(ProgressSpeed(), fed(steady(8)).next(prevCount = 800, count = 800, at = 9_000, restarted = true))
    }

    @Test
    fun a_count_that_goes_back_keeps_the_speed() {
        val seeded = fed(steady(8))
        assertEquals(seeded.copy(last = null), seeded.next(prevCount = 800, count = 700, at = 8_000, restarted = false))
    }

    @Test
    fun etaMs_is_the_time_left_at_the_speed() {
        assertNull(fed(steady(7)).etaMs(count = 700, total = 10_000))
        val seeded = fed(steady(8))
        assertEquals(92_000L, seeded.etaMs(count = 800, total = 10_000))
        assertEquals(0L, seeded.etaMs(count = 800, total = 800))
        assertEquals(1L, fed(steady(8, gapMs = 1)).etaMs(count = 9, total = 10))
    }

    @Test
    fun smoothEtaMs_shows_the_first_time_at_once() {
        assertEquals(3_000_000L, smoothEtaMs(previous = null, previousAt = null, raw = 3_000_000L, at = 1_000))
        assertEquals(3_000_000L, smoothEtaMs(previous = 0L, previousAt = 1_000, raw = 3_000_000L, at = 2_000))
        assertEquals(3_000_000L, smoothEtaMs(previous = 2_000_000L, previousAt = null, raw = 3_000_000L, at = 2_000))
    }

    @Test
    fun smoothEtaMs_shows_done_at_once() {
        assertEquals(0L, smoothEtaMs(previous = 3_000_000L, previousAt = 1_000, raw = 0L, at = 2_000))
    }

    @Test
    fun smoothEtaMs_keeps_the_time_when_raw_is_missing() {
        assertEquals(3_000_000L, smoothEtaMs(previous = 3_000_000L, previousAt = 1_000, raw = null, at = 2_000))
        assertNull(smoothEtaMs(previous = null, previousAt = null, raw = null, at = 1_000))
        assertNull(smoothEtaMs(previous = 0L, previousAt = 1_000, raw = null, at = 2_000))
    }

    @Test
    fun smoothEtaMs_keeps_the_time_when_the_clock_does_not_move_forward() {
        assertEquals(3_000_000L, smoothEtaMs(previous = 3_000_000L, previousAt = 2_000, raw = 3_600_000L, at = 2_000))
        assertEquals(3_000_000L, smoothEtaMs(previous = 3_000_000L, previousAt = 3_000, raw = 3_600_000L, at = 2_000))
    }

    @Test
    fun smoothEtaMs_counts_down_a_steady_job_without_lag() {
        assertEquals(2_999_000L, smoothEtaMs(previous = 3_000_000L, previousAt = 0, raw = 2_999_000L, at = 1_000))
    }

    @Test
    fun smoothEtaMs_one_second_swing_moves_less_than_two_minutes() {
        assertEquals(3_069_619L, smoothEtaMs(previous = 3_000_000L, previousAt = 0, raw = 3_600_000L, at = 1_000))
    }

    @Test
    fun smoothEtaMs_fifteen_seconds_cover_most_of_a_lasting_change() {
        val shown = smoothEtaMs(previous = 3_000_000L, previousAt = 0, raw = 1_500_000L, at = 15_000)!!
        assertEquals(1_727_732L, shown)
        val left = 3_000_000L - 15_000L
        assertTrue((left - shown) * 100 >= (left - 1_500_000L) * 80)
    }

    @Test
    fun smoothEtaMs_alternating_swing_stays_calm() {
        var shown = 3_000_000L
        for (second in 1..10) {
            val raw = if (second % 2 == 1) 3_600_000L else 3_000_000L
            val next = smoothEtaMs(previous = shown, previousAt = (second - 1) * 1_000L, raw = raw, at = second * 1_000L)!!
            assertTrue(abs(next - shown) < 120_000L)
            shown = next
        }
    }

    @Test
    fun smoothEtaMs_never_shows_done_while_work_remains() {
        assertEquals(1L, smoothEtaMs(previous = 500L, previousAt = 0, raw = 2_000L, at = 1_000))
    }
}
