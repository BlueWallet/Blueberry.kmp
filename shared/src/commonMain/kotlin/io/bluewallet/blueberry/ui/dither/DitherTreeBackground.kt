package io.bluewallet.blueberry.ui.dither

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.ceil

private const val FRAME_NANOS = 33_000_000L
private const val GROW_SECONDS = 2.8f
private const val STILL_TIME = 6f

/**
 * Animation time shared by every DitherTreeBackground in the process, so the
 * tree grows in once per app launch and then keeps looping, even when a screen
 * is left and re-entered.
 */
private object TreeClock {
    var seconds = 0f
}

/**
 * Animated, dithered blueberry tree. Fills its bounds; put content on top.
 *
 * Rendering runs off the main thread at about 30 fps on a buffer one cell
 * per [pixelSize], then scales up with nearest-neighbour filtering.
 * It stops while the screen is not resumed. Pass `animate = false` for a
 * still frame (for example when the system asks for reduced motion).
 */
@Composable
fun DitherTreeBackground(
    modifier: Modifier = Modifier,
    palette: DitherPalette = DitherPalette.Blueberry,
    placement: TreePlacement = TreePlacement.BottomCenter,
    pixelSize: Dp = 3.dp,
    animate: Boolean = true,
) {
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    val cell = with(LocalDensity.current) { pixelSize.toPx() }.coerceAtLeast(1f)
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val running = animate && lifecycle.isAtLeast(Lifecycle.State.RESUMED)

    LaunchedEffect(viewSize, cell, palette, placement, running) {
        if (viewSize == IntSize.Zero) return@LaunchedEffect
        val w = ceil(viewSize.width / cell).toInt().coerceAtLeast(1)
        val h = ceil(viewSize.height / cell).toInt().coerceAtLeast(1)
        val surface = PixelSurface(w, h)
        // Each effect owns its renderer. A cancelled frame keeps drawing on
        // Dispatchers.Default, and resize() would replace the buffers under it.
        val renderer = DitherTreeRenderer()
        renderer.resize(w, h, placement, lightBackdrop = palette.lightBackdrop)
        if (!running) {
            val pixels = withContext(Dispatchers.Default) { renderer.render(STILL_TIME, 1f, 0f, palette) }
            image = surface.update(pixels)
            tick++
            return@LaunchedEffect
        }
        val startedAt = withFrameNanos { it }
        val base = TreeClock.seconds
        var last = 0L
        while (isActive) {
            val now = withFrameNanos { it }
            if (now - last < FRAME_NANOS) continue
            last = now
            val t = base + (now - startedAt) / 1e9f
            TreeClock.seconds = t
            val growth = 1f - (1f - (t / GROW_SECONDS).coerceAtMost(1f)).let { it * it * it }
            val pixels = withContext(Dispatchers.Default) { renderer.render(t, growth, 1f, palette) }
            image = surface.update(pixels)
            tick++
        }
    }

    Canvas(
        modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { viewSize = it },
    ) {
        // Reading tick makes the canvas redraw when a reused bitmap changes.
        if (tick < 0) return@Canvas
        val frame = image ?: return@Canvas
        drawImage(
            image = frame,
            dstSize = IntSize(ceil(frame.width * cell).toInt(), ceil(frame.height * cell).toInt()),
            filterQuality = FilterQuality.None,
        )
    }
}
