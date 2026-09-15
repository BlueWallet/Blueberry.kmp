package io.bluewallet.blueberry

import kotlin.test.Test
import kotlin.test.assertEquals

class DirectionGlyphTest {
    @Test
    fun incoming_points_down_right_outgoing_points_up() {
        assertEquals(-45f, directionGlyphRotationDeg(incoming = true))
        assertEquals(180f, directionGlyphRotationDeg(incoming = false))
    }
}
