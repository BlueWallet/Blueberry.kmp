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
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
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

/**
 * How long a touch must stay inside the touch slop before it counts as a press.
 * A scroll leaves the slop first, so the row never scales or haptics while the
 * list is moving. A quick tap still clicks on release.
 */
internal const val PRESSABLE_SETTLE_MILLIS = 100L

internal fun pressableTargetScale(
    pressed: Boolean,
    enabled: Boolean,
): Float = if (pressed && enabled) PRESSABLE_PRESSED_SCALE else 1f

/**
 * Tappable container that scales down while pressed and plays a soft haptic once the
 * touch has settled. The layout size stays put; only the drawing scales, so the hit
 * target does not shrink. A drag past the touch slop is a scroll: no scale, no haptic,
 * and no click, including while the finger is still coming down on a list row.
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
            try {
                watchPress(
                    PressWatch(
                        down = down,
                        start = down.position,
                        slop = slop,
                        pressed = pressed,
                        actions = actions,
                    ),
                )
            } finally {
                pressed(false)
            }
        }
    }

private class PressWatch(
    val down: PointerInputChange,
    val start: Offset,
    val slop: Float,
    val pressed: (Boolean) -> Unit,
    val actions: PressActions,
)

private suspend fun AwaitPointerEventScope.watchPress(watch: PressWatch) {
    val settled =
        withTimeoutOrNull(PRESSABLE_SETTLE_MILLIS) {
            trackPress(watch, PressPhase.Pending)
        }
    // Timeout means the finger is still down, still inside the slop.
    if (settled != null) return
    val holding = applyPress(decidePress(PressPhase.Pending, PressSignal.Settle), watch.pressed, watch.actions)
    if (holding.finished) return
    trackPress(watch, holding.phase)
}

private suspend fun AwaitPointerEventScope.trackPress(
    watch: PressWatch,
    initial: PressPhase,
): PressPhase {
    var phase = initial
    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        val change = event.changes.firstOrNull { it.id == watch.down.id }
        val decision =
            applyPress(
                decidePress(
                    phase,
                    pressSignal(
                        pointerMissing = change == null,
                        pointerUp = change?.pressed == false,
                        pastSlop = change != null && movedPastSlop(watch.start, change.position, watch.slop),
                    ),
                ),
                watch.pressed,
                watch.actions,
            )
        phase = decision.phase
        if (decision.finished) return phase
    }
}

private fun applyPress(
    decision: PressDecision,
    pressed: (Boolean) -> Unit,
    actions: PressActions,
): PressDecision {
    pressed(decision.pressed)
    if (decision.haptic) actions.haptic.perform()
    if (decision.click) actions.onClick()
    return decision
}

internal enum class PressPhase { Pending, Pressed, Scrolling }

internal enum class PressSignal { MoveInside, MoveOutside, Settle, Release, Cancel }

internal data class PressDecision(
    val phase: PressPhase,
    val pressed: Boolean,
    val haptic: Boolean,
    val click: Boolean,
    val finished: Boolean,
)

internal fun pressSignal(
    pointerMissing: Boolean,
    pointerUp: Boolean,
    pastSlop: Boolean,
): PressSignal =
    when {
        pointerMissing -> PressSignal.Cancel
        pastSlop -> PressSignal.MoveOutside
        pointerUp -> PressSignal.Release
        else -> PressSignal.MoveInside
    }

internal fun decidePress(
    phase: PressPhase,
    signal: PressSignal,
): PressDecision {
    if (phase == PressPhase.Scrolling || signal == PressSignal.Cancel) {
        return PressDecision(
            phase = PressPhase.Scrolling,
            pressed = false,
            haptic = false,
            click = false,
            finished = true,
        )
    }
    return when (signal) {
        PressSignal.MoveInside ->
            PressDecision(
                phase = phase,
                pressed = phase == PressPhase.Pressed,
                haptic = false,
                click = false,
                finished = false,
            )
        PressSignal.MoveOutside ->
            PressDecision(
                phase = PressPhase.Scrolling,
                pressed = false,
                haptic = false,
                click = false,
                finished = true,
            )
        PressSignal.Settle ->
            PressDecision(
                phase = PressPhase.Pressed,
                pressed = true,
                haptic = phase != PressPhase.Pressed,
                click = false,
                finished = false,
            )
        PressSignal.Release ->
            PressDecision(
                phase = phase,
                pressed = false,
                haptic = phase != PressPhase.Pressed,
                click = true,
                finished = true,
            )
        PressSignal.Cancel -> error("Cancel is handled before the signal switch")
    }
}

internal fun movedPastSlop(
    down: Offset,
    up: Offset,
    slop: Float,
): Boolean {
    val delta = up - down
    return abs(delta.x) > slop || abs(delta.y) > slop
}
