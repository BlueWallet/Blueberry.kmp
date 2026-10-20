package io.bluewallet.blueberry.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import kotlin.math.abs

/** Scale while a [Pressable] is held. Full size is 1. */
internal const val PRESSABLE_PRESSED_SCALE = 0.96f

private const val PRESSABLE_SPRING_DAMPING = 0.72f

internal fun pressableTargetScale(
    pressed: Boolean,
    enabled: Boolean,
): Float = if (pressed && enabled) PRESSABLE_PRESSED_SCALE else 1f

/**
 * Tappable container that scales down while pressed and plays a soft haptic on touch-down.
 * The layout size stays put; only the drawing scales, so the hit target does not shrink.
 * Press is tracked from the initial touch so a parent scroll does not cancel it.
 */
@Composable
fun Pressable(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    role: Role? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = pressableTargetScale(pressed = pressed, enabled = enabled),
        animationSpec =
            spring(
                dampingRatio = PRESSABLE_SPRING_DAMPING,
                stiffness = Spring.StiffnessMediumLow,
            ),
        label = "pressable-scale",
    )
    val haptic = rememberSoftHaptic()
    val actions = remember { PressActions() }
    actions.onClick = onClick
    actions.haptic = haptic
    Box(
        modifier =
            modifier
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }.semantics {
                    if (role != null) this.role = role
                    if (!enabled) disabled()
                    onClick {
                        if (enabled) actions.onClick()
                        enabled
                    }
                }.pressAndClick(enabled = enabled, pressed = { pressed = it }, actions = actions),
        content = content,
    )
}

private class PressActions {
    var onClick: () -> Unit = {}
    var haptic: SoftHaptic = SoftHaptic {}
}

private fun Modifier.pressAndClick(
    enabled: Boolean,
    pressed: (Boolean) -> Unit,
    actions: PressActions,
): Modifier =
    pointerInput(enabled) {
        if (!enabled) return@pointerInput
        val slop = viewConfiguration.touchSlop
        awaitEachGesture {
            val down =
                awaitFirstDown(
                    requireUnconsumed = false,
                    pass = PointerEventPass.Initial,
                )
            pressed(true)
            actions.haptic.perform()
            val start = down.position
            var scrolling = false
            try {
                var tracking = true
                while (tracking) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id }
                    val held = change != null && change.pressed
                    if (change != null && !scrolling && movedPastSlop(start, change.position, slop)) {
                        scrolling = true
                        pressed(false)
                    }
                    if (!held && change != null && !scrolling) actions.onClick()
                    tracking = held
                }
            } finally {
                pressed(false)
            }
        }
    }

private fun movedPastSlop(
    down: Offset,
    up: Offset,
    slop: Float,
): Boolean {
    val delta = up - down
    return abs(delta.x) > slop || abs(delta.y) > slop
}
