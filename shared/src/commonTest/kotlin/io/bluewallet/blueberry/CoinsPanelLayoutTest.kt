package io.bluewallet.blueberry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CoinsPanelLayoutTest {
    private fun linked(count: Int) =
        List(count) { i ->
            CoinControlBubble(
                key = "l$i",
                valueSats = 1_000L,
                colorArgb = 0xFF3788E0,
                isFrozen = false,
                linkedGroupId = "G",
                provable = true,
            )
        }

    private fun independent(count: Int) =
        List(count) { i ->
            CoinControlBubble(
                key = "i$i",
                valueSats = 1_000L * (i + 1),
                colorArgb = 0xFF3788E0,
                isFrozen = false,
                linkedGroupId = null,
            )
        }

    private fun assertLabelFits(
        layout: BubbleStripLayout,
        width: Float,
    ) {
        val reserved = if (layout.overflowCount > 0) BUBBLE_OVERFLOW_LABEL_DP else 0f
        assertTrue(
            layout.contentWidthDp() + reserved <= width + 0.5f,
            "coins ${layout.contentWidthDp()} + label $reserved overrun $width",
        )
    }

    @Test
    fun linked_cluster_that_still_overflows_counts_in_plus_n() {
        // Many equal linked coins at the 2.5dp floor cannot fit a narrow strip when
        // only independents are droppable — linked coins must enter +N too.
        val bubbles = linked(12)
        val layout = layoutBubbleStrip(bubbles, availableWidthDp = 40f, rMaxDp = 28f, rMinDp = 2.5f)
        assertTrue(layout.overflowCount > 0, "expected linked overflow, got ${layout.overflowCount}")
        assertEquals(bubbles.size, layout.items.size + layout.overflowCount)
        assertLabelFits(layout, 40f)
    }

    @Test
    fun plus_n_label_never_sits_on_the_coins() {
        val bubbles = linked(6) + independent(30)
        for (width in 40..400 step 3) {
            val layout = layoutBubbleStrip(bubbles, availableWidthDp = width.toFloat())
            assertEquals(bubbles.size, layout.items.size + layout.overflowCount)
            assertLabelFits(layout, width.toFloat())
        }
    }

    @Test
    fun strip_that_fits_has_no_overflow() {
        val layout = layoutBubbleStrip(independent(3), availableWidthDp = 400f)
        assertEquals(0, layout.overflowCount)
        assertEquals(3, layout.items.size)
    }
}
