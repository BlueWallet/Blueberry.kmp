package io.bluewallet.blueberry.ui.dither

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import io.bluewallet.blueberry.ui.LocalDarkPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.ceil
import kotlin.math.exp

private const val GLOW_FRAME_NANOS = 33_000_000L
private const val FADE_IN_TAU = 0.45f
private const val FADE_OUT_TAU = 0.8f
private const val OFF_THRESHOLD = 0.004f

/** Fade level and clock, kept across loop restarts so toggling never jumps. */
private class GlowClock {
    var intensity = 0f
    var seconds = 0f

    fun step(
        on: Boolean,
        dt: Float,
    ) {
        val target = if (on) 1f else 0f
        val tau = if (target > intensity) FADE_IN_TAU else FADE_OUT_TAU
        intensity += (target - intensity) * (1f - exp(-dt / tau))
        seconds += dt
    }
}

/**
 * Subtle dithered glow for the bottom of a screen while data is loading.
 *
 * Give it the band's size (for example `fillMaxWidth().height(300.dp)`,
 * aligned to the bottom) and put it behind the content. When [active] turns
 * true the band fades up from the bottom edge (~0.5 s); when it turns false it
 * sinks away (~1 s) and then stops rendering entirely. It also stops while the
 * screen is not resumed. `animate = false` keeps the fade but drops the
 * shimmer, pulse and drift (for reduced motion). Colors follow the app theme.
 */
@Composable
fun DitherSyncGlow(
    active: Boolean,
    modifier: Modifier = Modifier,
    pixelSize: Dp = 3.dp,
    animate: Boolean = true,
) {
    val colors = if (LocalDarkPalette.current) DitherPalette.GlowDark else DitherPalette.GlowLight
    val clock = remember { GlowClock() }
    val wanted by rememberUpdatedState(active)
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    val cell = with(LocalDensity.current) { pixelSize.toPx() }.coerceAtLeast(1f)
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val resumed = lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    val motion = if (animate) 1f else 0f

    LaunchedEffect(viewSize, cell, colors, resumed, motion, active) {
        animateGlow(
            viewSize = viewSize,
            cell = cell,
            resumed = resumed,
            active = active,
            clock = clock,
            colors = colors,
            motion = motion,
            wanted = { wanted },
            publish = { frame ->
                image = frame
                if (frame != null) tick++
            },
        )
    }

    Canvas(
        modifier
            .clipToBounds()
            .onSizeChanged { viewSize = it },
    ) {
        // Reading tick makes the canvas redraw when a reused bitmap changes.
        if (tick < 0) return@Canvas
        val frame = image ?: return@Canvas
        val dst = IntSize(ceil(frame.width * cell).toInt(), ceil(frame.height * cell).toInt())
        drawImage(
            image = frame,
            dstOffset = IntOffset(0, size.height.toInt() - dst.height),
            dstSize = dst,
            filterQuality = FilterQuality.None,
        )
    }
}

@Suppress("LongParameterList")
private suspend fun animateGlow(
    viewSize: IntSize,
    cell: Float,
    resumed: Boolean,
    active: Boolean,
    clock: GlowClock,
    colors: DitherPalette,
    motion: Float,
    wanted: () -> Boolean,
    publish: (ImageBitmap?) -> Unit,
) {
    if (viewSize == IntSize.Zero || !resumed) return
    if (!active && clock.intensity <= 0f) {
        publish(null)
        return
    }
    val w = ceil(viewSize.width / cell).toInt().coerceAtLeast(1)
    val h = ceil(viewSize.height / cell).toInt().coerceAtLeast(1)
    val surface = PixelSurface(w, h)
    // Each run owns its renderer. A cancelled frame keeps drawing on
    // Dispatchers.Default, and resize() would replace the buffers under it.
    val renderer = DitherGlowRenderer()
    renderer.resize(w, h)
    var last = 0L
    while (coroutineContext.isActive) {
        val now = withFrameNanos { it }
        val due = last == 0L || now - last >= GLOW_FRAME_NANOS
        if (due) {
            val dt = if (last == 0L) 0.033f else ((now - last) / 1e9f).coerceAtMost(0.1f)
            last = now
            val keep = clock.advance(wanted(), dt)
            if (keep) {
                val pixels =
                    withContext(Dispatchers.Default) {
                        renderer.render(clock.seconds, clock.intensity, motion, colors)
                    }
                publish(surface.update(pixels))
            } else {
                publish(null)
                break
            }
        }
    }
}

private fun GlowClock.advance(
    on: Boolean,
    dt: Float,
): Boolean {
    step(on, dt)
    if (on || intensity >= OFF_THRESHOLD) return true
    intensity = 0f
    return false
}
