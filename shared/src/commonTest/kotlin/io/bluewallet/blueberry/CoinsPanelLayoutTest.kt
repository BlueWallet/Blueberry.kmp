package io.bluewallet.blueberry

import kotlin.test.Test
import kotlin.test.assertTrue

class CoinsPanelLayoutTest {
    @Test
    fun linked_cluster_that_still_overflows_counts_in_plus_n() {
        // Many equal linked coins at the 2.5dp floor cannot fit a narrow strip when
        // only independents are droppable — linked coins must enter +N too.
        val bubbles =
            List(12) { i ->
                CoinControlBubble(
                    key = "l$i",
                    valueSats = 1_000L,
                    colorArgb = 0xFF3788E0,
                    isFrozen = false,
                    linkedGroupId = "G",
                    provable = true,
                )
            }
        val layout = layoutBubbleStrip(bubbles, availableWidthDp = 40f, rMaxDp = 28f, rMinDp = 2.5f)
        assertTrue(layout.overflowCount > 0, "expected linked overflow, got ${layout.overflowCount}")
        assertTrue(layout.items.size < bubbles.size)
    }
}
