package io.bluewallet.blueberry.onboarding

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.bluewallet.blueberry.ui.BwColors
import io.bluewallet.blueberry.ui.numberFontFamily
import io.bluewallet.blueberry.ui.rememberSoftHaptic
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** How long a fling keeps coasting before the dial snaps to a year. */
internal const val YEAR_FLING_COAST_SECONDS = 0.28f

/** Fastest fling the dial honours, in years per second, so one coast moves a few years at most. */
internal const val YEAR_MAX_FLING_PER_SECOND = 12f

private val YearSlot = 108.dp
private const val YEAR_SPRING_DAMPING = 0.72f
private const val YEAR_SPRING_STIFFNESS = 380f

internal fun settledYearIndex(
    position: Float,
    velocityPerSecond: Float,
    lastIndex: Int,
    coastSeconds: Float = YEAR_FLING_COAST_SECONDS,
): Int {
    if (lastIndex < 0) return 0
    val velocity = velocityPerSecond.coerceIn(-YEAR_MAX_FLING_PER_SECOND, YEAR_MAX_FLING_PER_SECOND)
    val projected =
        (position + velocity * coastSeconds).coerceIn(0f, lastIndex.toFloat())
    return projected.roundToInt().coerceIn(0, lastIndex)
}

/** Index velocity at release. Compose's tracker fits the recent and batched historical samples. */
internal fun yearReleaseVelocity(
    tracker: VelocityTracker,
    slotPx: Float,
): Float {
    val velocity = -tracker.calculateVelocity().x / slotPx
    return if (velocity.isFinite()) velocity else 0f
}

/**
 * Horizontal year dial. The chosen year sits in a fixed frame; dragging
 * slides the years under it, and a fling coasts into the nearest year.
 */
@Suppress("LongParameterList")
@Composable
internal fun YearSlider(
    years: List<Int>,
    selected: Int,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onSettlingChange: (Boolean) -> Unit = {},
) {
    if (years.isEmpty()) return
    val startIndex = years.indexOf(selected).let { if (it < 0) 0 else it }
    val position = remember(years) { Animatable(startIndex.toFloat()) }
    val gestures = remember { Channel<YearGesture>(Channel.UNLIMITED) }
    BindYearGestures(
        years = years,
        position = position,
        gestures = gestures,
        enabled = enabled,
        actions = YearDialActions(onSelect = onSelect, onSettlingChange = onSettlingChange),
    )
    Box(
        modifier
            .fillMaxWidth()
            .height(200.dp)
            .pointerInput(enabled, years) {
                if (!enabled) return@pointerInput
                trackYearDrag(YearSlot.toPx()) { gestures.trySend(it) }
            },
        contentAlignment = Alignment.Center,
    ) {
        val frame = RoundedCornerShape(18.dp)
        Box(
            Modifier
                .width(132.dp)
                .height(64.dp)
                .background(BwColors.Accent.copy(alpha = 0.14f), frame)
                .border(1.5.dp, BwColors.Accent, frame),
        )
        val slotPx = with(LocalDensity.current) { YearSlot.toPx() }
        val cursor = position.value
        years.forEachIndexed { index, year ->
            val distance = index - cursor
            if (abs(distance) > 4.2f) return@forEachIndexed
            YearMark(year = year, distance = distance, slotPx = slotPx)
        }
    }
}

private class YearDialActions(
    val onSelect: (Int) -> Unit,
    val onSettlingChange: (Boolean) -> Unit,
)

@Composable
private fun BindYearGestures(
    years: List<Int>,
    position: Animatable<Float, AnimationVector1D>,
    gestures: Channel<YearGesture>,
    enabled: Boolean,
    actions: YearDialActions,
) {
    val haptic = rememberSoftHaptic()
    val actionsState by rememberUpdatedState(actions)
    val enabledState by rememberUpdatedState(enabled)
    val settling = remember { mutableStateOf(false) }
    LaunchedEffect(years) {
        var settle: Job? = null

        fun markSettling(value: Boolean) {
            settling.value = value
            actionsState.onSettlingChange(value)
        }
        for (gesture in gestures) {
            when (gesture) {
                is YearGesture.Move -> {
                    settle?.cancel()
                    settle?.join()
                    markSettling(false)
                    position.snapTo(
                        (position.value + gesture.delta).coerceIn(0f, years.lastIndex.toFloat()),
                    )
                }
                is YearGesture.Release -> {
                    val snapped =
                        settledYearIndex(
                            position = position.value,
                            velocityPerSecond = gesture.velocity,
                            lastIndex = years.lastIndex,
                        )
                    // Commit before the spring so Continue cannot store a mid-flight year.
                    actionsState.onSelect(years[snapped])
                    markSettling(true)
                    settle =
                        launch {
                            try {
                                position.animateTo(
                                    snapped.toFloat(),
                                    spring(
                                        dampingRatio = YEAR_SPRING_DAMPING,
                                        stiffness = YEAR_SPRING_STIFFNESS,
                                    ),
                                )
                            } finally {
                                markSettling(false)
                            }
                        }
                }
            }
        }
    }
    LaunchedEffect(years) {
        snapshotFlow { settledYearIndex(position.value, velocityPerSecond = 0f, years.lastIndex) }
            .distinctUntilChanged()
            .drop(1)
            .collect { index ->
                if (!enabledState || settling.value) return@collect
                haptic.perform()
                actionsState.onSelect(years[index])
            }
    }
}

@Composable
private fun YearMark(
    year: Int,
    distance: Float,
    slotPx: Float,
) {
    val travel = abs(distance).coerceAtMost(3f)
    val emphasis = (1f - travel / 2.2f).coerceIn(0f, 1f)
    val centered = emphasis > 0.82f
    val weight = if (centered) FontWeight.Bold else FontWeight.Medium
    val size = (17f + 15f * emphasis).sp
    Text(
        text = year.toString(),
        color = if (centered) BwColors.Ink else BwColors.InkMuted,
        fontFamily = numberFontFamily(weight),
        fontWeight = weight,
        fontSize = size,
        lineHeight = size,
        textAlign = TextAlign.Center,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Visible,
        style = TextStyle(fontFeatureSettings = "tnum"),
        modifier =
            Modifier.graphicsLayer {
                translationX = distance * slotPx
                translationY = distance * distance * 12.dp.toPx()
                alpha = 0.2f + 0.8f * (1f - travel / 3f).coerceIn(0f, 1f)
            },
    )
}

private sealed interface YearGesture {
    data class Move(
        val delta: Float,
    ) : YearGesture

    data class Release(
        val velocity: Float,
    ) : YearGesture
}

private suspend fun PointerInputScope.trackYearDrag(
    slotPx: Float,
    send: (YearGesture) -> Unit,
) {
    if (slotPx <= 0f) return
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val pointerId = down.id
        val tracker = VelocityTracker()
        tracker.addPointerInputChange(down)
        var dragging = true
        while (dragging) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == pointerId }
            dragging = change != null && change.pressed
            if (change != null) {
                tracker.addPointerInputChange(change)
                if (change.pressed) {
                    val dragX = change.positionChange().x
                    val indexDelta = -dragX / slotPx
                    if (indexDelta != 0f) send(YearGesture.Move(indexDelta))
                    if (dragX != 0f) change.consume()
                }
            }
        }
        send(YearGesture.Release(yearReleaseVelocity(tracker, slotPx)))
    }
}
