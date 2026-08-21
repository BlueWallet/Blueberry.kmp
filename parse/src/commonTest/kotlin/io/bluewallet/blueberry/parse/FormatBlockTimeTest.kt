package io.bluewallet.blueberry.parse

import kotlin.test.Test
import kotlin.test.assertEquals
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
}
