package io.bluewallet.blueberry.parse

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val NOW_MS = 1_700_000_000_000L
private const val NOW_S = NOW_MS / 1000
private const val MONTH_S = 30L * 24 * 3600

class FormatBlockTimeTest {
    @Test
    fun relative_buckets_and_month_boundary() {
        assertEquals("just now".padEnd(16), formatBlockTimeLabel(NOW_S, NOW_MS))
        assertEquals("just now".padEnd(16), formatBlockTimeLabel(NOW_S - 59, NOW_MS))
        assertEquals("1m ago".padEnd(16), formatBlockTimeLabel(NOW_S - 60, NOW_MS))
        assertEquals("1h ago".padEnd(16), formatBlockTimeLabel(NOW_S - 3600, NOW_MS))
        assertEquals("1d ago".padEnd(16), formatBlockTimeLabel(NOW_S - 24 * 3600, NOW_MS))
        assertEquals("30d ago".padEnd(16), formatBlockTimeLabel(NOW_S - MONTH_S, NOW_MS))
    }

    @Test
    fun absolute_past_one_month_is_local_ymd_hm() {
        // 2023-09-15 12:00:00 UTC — older than one month before NOW_MS
        val older = 1_694_779_200L
        val label = formatBlockTimeLabel(older, NOW_MS)
        assertEquals(16, label.length)
        assertTrue(Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}""").matches(label), label)
    }

    @Test
    fun future_clamps_to_just_now() {
        assertEquals("just now".padEnd(16), formatBlockTimeLabel(NOW_S + 3600, NOW_MS))
    }

    @Test
    fun relative_age_uses_seconds_then_minutes_past_an_hour() {
        assertEquals("just now", formatRelativeAge(NOW_S, NOW_MS))
        assertEquals("1 second ago", formatRelativeAge(NOW_S - 1, NOW_MS))
        assertEquals("30 seconds ago", formatRelativeAge(NOW_S - 30, NOW_MS))
        assertEquals("59 seconds ago", formatRelativeAge(NOW_S - 59, NOW_MS))
        assertEquals("1 min ago", formatRelativeAge(NOW_S - 60, NOW_MS))
        assertEquals("7 min ago", formatRelativeAge(NOW_S - 7 * 60, NOW_MS))
        assertEquals("59 min ago", formatRelativeAge(NOW_S - 59 * 60, NOW_MS))
        assertEquals("60 min ago", formatRelativeAge(NOW_S - 3600, NOW_MS))
        assertEquals("90 min ago", formatRelativeAge(NOW_S - 90 * 60, NOW_MS))
        assertEquals("180 min ago", formatRelativeAge(NOW_S - 3 * 3600, NOW_MS))
        assertEquals("1439 min ago", formatRelativeAge(NOW_S - 1439 * 60, NOW_MS))
        assertEquals("24 hours ago", formatRelativeAge(NOW_S - 24 * 3600, NOW_MS))
        assertEquals("25 hours ago", formatRelativeAge(NOW_S - 25 * 3600, NOW_MS))
        assertEquals("28 hours ago", formatRelativeAge(NOW_S - 28 * 3600, NOW_MS))
        assertEquals("720 hours ago", formatRelativeAge(NOW_S - MONTH_S, NOW_MS))
        assertEquals("—", formatRelativeAge(null, NOW_MS))
    }

    @Test
    fun relative_age_future_is_negative_time() {
        assertEquals("-1 second", formatRelativeAge(NOW_S + 1, NOW_MS))
        assertEquals("-30 seconds", formatRelativeAge(NOW_S + 30, NOW_MS))
        assertEquals("-59 seconds", formatRelativeAge(NOW_S + 59, NOW_MS))
        assertEquals("-1 min", formatRelativeAge(NOW_S + 60, NOW_MS))
        assertEquals("-7 min", formatRelativeAge(NOW_S + 7 * 60, NOW_MS))
        assertEquals("-60 min", formatRelativeAge(NOW_S + 3600, NOW_MS))
        assertEquals("-90 min", formatRelativeAge(NOW_S + 90 * 60, NOW_MS))
        assertEquals("-1439 min", formatRelativeAge(NOW_S + 1439 * 60, NOW_MS))
        assertEquals("-24 hours", formatRelativeAge(NOW_S + 24 * 3600, NOW_MS))
        assertEquals("-25 hours", formatRelativeAge(NOW_S + 25 * 3600, NOW_MS))
        assertEquals("-720 hours", formatRelativeAge(NOW_S + MONTH_S, NOW_MS))
        val later = NOW_S + MONTH_S + 1
        val label = formatRelativeAge(later, NOW_MS)
        assertTrue(Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}""").matches(label), label)
    }

    @Test
    fun relative_age_older_than_a_month_is_local_ymd_hm() {
        val older = 1_694_779_200L
        val label = formatRelativeAge(older, NOW_MS)
        assertTrue(Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}""").matches(label), label)
    }

    @Test
    fun next_relative_age_wake_matches_label_boundaries() {
        assertEquals(1000L, msUntilNextRelativeAgeChange(NOW_S, NOW_MS))
        assertEquals(1L, msUntilNextRelativeAgeChange(NOW_S - 1, NOW_MS + 999))
        assertEquals(1000L, msUntilNextRelativeAgeChange(NOW_S - 30, NOW_MS))
        assertEquals(1000L, msUntilNextRelativeAgeChange(NOW_S - 59, NOW_MS))
        assertEquals(60_000L, msUntilNextRelativeAgeChange(NOW_S - 60, NOW_MS))
        assertEquals(1L, msUntilNextRelativeAgeChange(NOW_S - 60, NOW_MS + 59_999))
        assertEquals(60_000L, msUntilNextRelativeAgeChange(NOW_S - 3600, NOW_MS))
        assertEquals(60_000L, msUntilNextRelativeAgeChange(NOW_S - 3 * 3600, NOW_MS))
        assertEquals(3_600_000L, msUntilNextRelativeAgeChange(NOW_S - 24 * 3600, NOW_MS))
        assertEquals(3_600_000L, msUntilNextRelativeAgeChange(NOW_S - 28 * 3600, NOW_MS))
        assertEquals(1000L, msUntilNextRelativeAgeChange(NOW_S + 1, NOW_MS))
        assertEquals(1000L, msUntilNextRelativeAgeChange(NOW_S + 30, NOW_MS))
        assertEquals(1000L, msUntilNextRelativeAgeChange(NOW_S + 60, NOW_MS))
        assertEquals(31_000L, msUntilNextRelativeAgeChange(NOW_S + 90 * 60 + 30, NOW_MS))
        assertEquals(1000L, msUntilNextRelativeAgeChange(NOW_S + 25 * 3600, NOW_MS))
        assertEquals(3_600_000L, msUntilNextRelativeAgeChange(NOW_S + 26 * 3600 - 1, NOW_MS))
        assertNull(msUntilNextRelativeAgeChange(NOW_S - MONTH_S - 1, NOW_MS))
        assertNull(msUntilNextRelativeAgeChange(NOW_S + MONTH_S + 1, NOW_MS))
        assertNull(msUntilNextRelativeAgeChange(null, NOW_MS))
    }

    @Test
    fun next_block_time_wake_matches_label_boundaries() {
        assertEquals(60_000L, msUntilNextBlockTimeLabelChange(NOW_S, NOW_MS))
        assertEquals(1_000L, msUntilNextBlockTimeLabelChange(NOW_S - 59, NOW_MS))
        assertEquals(60_000L, msUntilNextBlockTimeLabelChange(NOW_S - 60, NOW_MS))
        assertEquals(1L, msUntilNextBlockTimeLabelChange(NOW_S - 60, NOW_MS + 59_999))
        assertEquals(3_600_000L, msUntilNextBlockTimeLabelChange(NOW_S - 3600, NOW_MS))
        assertEquals(86_400_000L, msUntilNextBlockTimeLabelChange(NOW_S - 24 * 3600, NOW_MS))
        assertNull(msUntilNextBlockTimeLabelChange(NOW_S - MONTH_S - 1, NOW_MS))
    }
}
