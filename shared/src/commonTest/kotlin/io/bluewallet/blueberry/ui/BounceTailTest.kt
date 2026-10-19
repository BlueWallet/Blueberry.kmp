package io.bluewallet.blueberry.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class BounceTailTest {
    @Test
    fun short_content_fills_to_one_pixel_past_the_viewport() {
        val occupied = 200 + 40 + 8 * 2
        val tail = bounceTailPx(viewportPx = 800, occupiedPx = occupied, allOthersVisible = true)
        assertEquals(801, occupied + tail)
    }

    @Test
    fun overflowing_content_keeps_a_one_pixel_tail() {
        assertEquals(
            1,
            bounceTailPx(viewportPx = 800, occupiedPx = 200, allOthersVisible = false),
        )
    }

    @Test
    fun empty_viewport_still_has_a_tail() {
        assertEquals(
            1,
            bounceTailPx(viewportPx = 0, occupiedPx = 0, allOthersVisible = true),
        )
    }
}
