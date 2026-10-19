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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
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
    val projected =
        (position + velocityPerSecond * coastSeconds).coerceIn(0f, lastIndex.toFloat())
    return projected.roundToInt().coerceIn(0, lastIndex)
}

/**
 * Horizontal year dial. The chosen year sits in a fixed frame; dragging
 * slides the years under it, and a fling coasts into the nearest year.
 */
@Composable
internal fun YearSlider(
    years: List<Int>,
    selected: Int,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
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
        onSelect = onSelect,
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

@Composable
private fun BindYearGestures(
    years: List<Int>,
    position: Animatable<Float, AnimationVector1D>,
    gestures: Channel<YearGesture>,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
) {
    val haptic = rememberSoftHaptic()
    val onSelectState by rememberUpdatedState(onSelect)
    val enabledState by rememberUpdatedState(enabled)
    LaunchedEffect(years) {
        var settle: Job? = null
        for (gesture in gestures) {
            when (gesture) {
                is YearGesture.Move -> {
                    settle?.cancel()
                    settle?.join()
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
                    settle =
                        launch {
                            position.animateTo(
                                snapped.toFloat(),
                                spring(dampingRatio = YEAR_SPRING_DAMPING, stiffness = YEAR_SPRING_STIFFNESS),
                            )
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
                if (!enabledState) return@collect
                haptic.perform()
                onSelectState(years[index])
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
        var velocity = 0f
        var lastTime = down.uptimeMillis
        var dragging = true
        while (dragging) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == pointerId }
            dragging = change != null && change.pressed
            if (change != null && change.pressed) {
                val dragX = change.positionChange().x
                val now = change.uptimeMillis
                val dt = (now - lastTime).coerceAtLeast(1L)
                val indexDelta = -dragX / slotPx
                velocity = indexDelta / dt * 1000f
                lastTime = now
                if (indexDelta != 0f) send(YearGesture.Move(indexDelta))
                if (dragX != 0f) change.consume()
            }
        }
        send(YearGesture.Release(velocity))
    }
}
