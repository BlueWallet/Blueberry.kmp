package io.bluewallet.blueberry.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

@Composable
internal actual fun BoxScope.IosEdgeSwipeHandle(
    enabled: Boolean,
    onDismiss: () -> Unit,
) {
    if (!enabled) return
    val dismiss by rememberUpdatedState(onDismiss)
    val density = LocalDensity.current
    val edgeWidthPx = with(density) { EDGE_SWIPE_WIDTH_DP.dp.toPx() }
    val thresholdPx = with(density) { EDGE_SWIPE_THRESHOLD_DP.dp.toPx() }
    Box(
        modifier =
            Modifier
                .align(Alignment.Start)
                .fillMaxHeight()
                .width(EDGE_SWIPE_WIDTH_DP.dp)
                .pointerInput(edgeWidthPx, thresholdPx) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val totalDx =
                            dragDxUntilUp(down.id, down.position.x) ?: return@awaitEachGesture
                        if (edgeSwipeDismisses(down.position.x, totalDx, edgeWidthPx, thresholdPx)) {
                            dismiss()
                        }
                    }
                },
    )
}

private suspend fun AwaitPointerEventScope.dragDxUntilUp(
    pointerId: PointerId,
    startX: Float,
): Float? {
    var totalDx = 0f
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == pointerId } ?: return null
        totalDx = change.position.x - startX
        if (!change.pressed) return totalDx
    }
}
