package io.bluewallet.blueberry.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class ScreenPushTest {
    @Test
    fun offset_is_full_width_toward_the_end() {
        assertEquals(1080, screenPushOffsetPx(1080))
        assertEquals(0, screenPushOffsetPx(0))
        assertEquals(0, screenPushOffsetPx(-40))
    }

    @Test
    fun durations_match_the_approved_push() {
        assertEquals(250, SCREEN_PUSH_DURATION_MS)
        assertEquals(200, SCREEN_PUSH_FADE_MS)
    }
}
