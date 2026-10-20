package io.bluewallet.blueberry.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class PressableScaleTest {
    @Test
    fun held_press_scales_down() {
        assertEquals(PRESSABLE_PRESSED_SCALE, pressableTargetScale(pressed = true, enabled = true))
        assertEquals(0.96f, PRESSABLE_PRESSED_SCALE)
    }

    @Test
    fun released_and_disabled_stay_full_size() {
        assertEquals(1f, pressableTargetScale(pressed = false, enabled = true))
        assertEquals(1f, pressableTargetScale(pressed = true, enabled = false))
        assertEquals(1f, pressableTargetScale(pressed = false, enabled = false))
    }
}
