package io.bluewallet.blueberry.ui

import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

    @Test
    fun scroll_before_the_press_settles_never_haptics_or_clicks() {
        val inside = decidePress(PressPhase.Pending, PressSignal.MoveInside)
        assertEquals(PressPhase.Pending, inside.phase)
        assertFalse(inside.pressed)
        assertFalse(inside.haptic)
        assertFalse(inside.finished)

        val scroll = decidePress(inside.phase, PressSignal.MoveOutside)
        assertEquals(PressPhase.Scrolling, scroll.phase)
        assertFalse(scroll.pressed)
        assertFalse(scroll.haptic)
        assertFalse(scroll.click)
        assertTrue(scroll.finished)

        val up = decidePress(scroll.phase, PressSignal.Release)
        assertFalse(up.haptic)
        assertFalse(up.click)
    }

    @Test
    fun quick_tap_clicks_and_haptics_without_waiting_to_depress() {
        val tap = decidePress(PressPhase.Pending, PressSignal.Release)
        assertTrue(tap.click)
        assertTrue(tap.haptic)
        assertFalse(tap.pressed)
        assertTrue(tap.finished)
    }

    @Test
    fun settled_hold_depresses_once_then_clicks_without_a_second_haptic() {
        val early = decidePress(PressPhase.Pending, PressSignal.MoveInside)
        assertFalse(early.pressed)

        val held = decidePress(early.phase, PressSignal.Settle)
        assertEquals(PressPhase.Pressed, held.phase)
        assertTrue(held.pressed)
        assertTrue(held.haptic)
        assertFalse(held.click)
        assertFalse(held.finished)

        val again = decidePress(held.phase, PressSignal.Settle)
        assertTrue(again.pressed)
        assertFalse(again.haptic)

        val up = decidePress(held.phase, PressSignal.Release)
        assertTrue(up.click)
        assertFalse(up.haptic)
        assertFalse(up.pressed)
    }

    @Test
    fun scroll_after_the_press_settles_cancels_without_clicking() {
        val held = decidePress(PressPhase.Pending, PressSignal.Settle)
        val scroll = decidePress(held.phase, PressSignal.MoveOutside)
        assertEquals(PressPhase.Scrolling, scroll.phase)
        assertFalse(scroll.pressed)
        assertFalse(scroll.haptic)
        assertFalse(scroll.click)
        assertTrue(scroll.finished)
    }

    @Test
    fun lift_past_the_slop_is_a_scroll_not_a_tap() {
        assertEquals(PressSignal.MoveOutside, pressSignal(pointerMissing = false, pointerUp = true, pastSlop = true))
        val lift = decidePress(PressPhase.Pending, PressSignal.MoveOutside)
        assertFalse(lift.click)
        assertFalse(lift.haptic)
    }

    @Test
    fun a_lost_pointer_does_not_click() {
        assertEquals(PressSignal.Cancel, pressSignal(pointerMissing = true, pointerUp = false, pastSlop = false))
        val lost = decidePress(PressPhase.Pressed, PressSignal.Cancel)
        assertFalse(lost.click)
        assertFalse(lost.haptic)
        assertFalse(lost.pressed)
        assertTrue(lost.finished)
    }

    @Test
    fun slop_is_crossed_on_either_axis() {
        assertFalse(movedPastSlop(Offset.Zero, Offset(8f, 0f), slop = 8f))
        assertTrue(movedPastSlop(Offset.Zero, Offset(8.1f, 0f), slop = 8f))
        assertTrue(movedPastSlop(Offset.Zero, Offset(0f, -9f), slop = 8f))
    }
}
