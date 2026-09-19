package io.bluewallet.blueberry.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackNavigationTest {
    @Test
    fun swipe_from_left_edge_far_enough_dismisses() {
        assertTrue(edgeSwipeDismisses(startXPx = 8f, dxPx = 80f, edgeWidthPx = 40f, thresholdPx = 56f))
    }

    @Test
    fun swipe_not_from_edge_does_not_dismiss() {
        assertFalse(edgeSwipeDismisses(startXPx = 200f, dxPx = 200f, edgeWidthPx = 40f, thresholdPx = 56f))
    }

    @Test
    fun short_or_leftward_swipe_does_not_dismiss() {
        assertFalse(edgeSwipeDismisses(startXPx = 8f, dxPx = 20f, edgeWidthPx = 40f, thresholdPx = 56f))
        assertFalse(edgeSwipeDismisses(startXPx = 8f, dxPx = -40f, edgeWidthPx = 40f, thresholdPx = 56f))
    }
}
