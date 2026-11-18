package io.bluewallet.blueberry.wallet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BaselineWatchTest {
    @Test
    fun baseline_year_matches_the_measured_wallet() {
        assertEquals(WatchGaps(135, 340), baselineWatchGaps(2019, 2026))
    }

    @Test
    fun baseline_year_stays_fixed_as_the_clock_moves_forward() {
        assertEquals(WatchGaps(135, 340), baselineWatchGaps(2019, 2030))
    }

    @Test
    fun current_year_keeps_the_initial_window() {
        assertEquals(WatchGaps(INITIAL_WATCH_COUNT, INITIAL_WATCH_COUNT), baselineWatchGaps(2026, 2026))
    }

    @Test
    fun one_year_of_history_floors_external_and_scales_internal() {
        assertEquals(WatchGaps(INITIAL_WATCH_COUNT, 67), baselineWatchGaps(2025, 2026))
    }

    @Test
    fun two_years_of_history_scale_both_chains() {
        assertEquals(WatchGaps(54, 113), baselineWatchGaps(2024, 2026))
    }

    @Test
    fun years_before_the_baseline_extrapolate() {
        assertEquals(WatchGaps(298, 796), baselineWatchGaps(2009, 2026))
    }

    @Test
    fun missing_span_keeps_the_initial_window() {
        assertEquals(WatchGaps(INITIAL_WATCH_COUNT, INITIAL_WATCH_COUNT), baselineWatchGaps(2019, 2019))
        assertEquals(WatchGaps(INITIAL_WATCH_COUNT, INITIAL_WATCH_COUNT), baselineWatchGaps(2030, 2026))
    }

    @Test
    fun measured_indexes_do_not_grow_the_baseline_window() {
        val gaps = baselineWatchGaps(2019, 2026)
        val grown =
            growWatchGapsIfNeeded(
                gaps,
                usedExternal = (0..114).toList(),
                usedInternal = (0..319).toList(),
            )
        assertFalse(grown.grew)
        assertEquals(gaps, grown.gaps)
    }

    @Test
    fun one_index_short_of_the_baseline_window_grows() {
        val gaps = baselineWatchGaps(2019, 2026)
        val short = WatchGaps(gaps.external - 1, gaps.internal - 1)
        val grown =
            growWatchGapsIfNeeded(
                short,
                usedExternal = listOf(114),
                usedInternal = listOf(319),
            )
        assertTrue(grown.grew)
        assertEquals(WatchGaps(gaps.external - 1 + GAP_LIMIT, gaps.internal - 1 + GAP_LIMIT), grown.gaps)
    }
}
