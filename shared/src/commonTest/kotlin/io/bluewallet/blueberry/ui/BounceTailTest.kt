package io.bluewallet.blueberry.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class BounceTailTest {
    @Test
    fun item_spacing_counts_gaps_not_items() {
        assertEquals(0, bounceItemSpacingPx(itemCount = 0, spacingPx = 8))
        assertEquals(0, bounceItemSpacingPx(itemCount = 1, spacingPx = 8))
        assertEquals(8, bounceItemSpacingPx(itemCount = 2, spacingPx = 8))
        assertEquals(16, bounceItemSpacingPx(itemCount = 3, spacingPx = 8))
    }

    @Test
    fun short_content_fills_to_one_pixel_past_the_viewport() {
        val occupied = 200 + 40 + bounceItemSpacingPx(itemCount = 2, spacingPx = 8)
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
