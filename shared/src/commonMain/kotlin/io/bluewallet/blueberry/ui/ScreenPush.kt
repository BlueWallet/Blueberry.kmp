package io.bluewallet.blueberry.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import kotlin.math.max

internal const val SCREEN_PUSH_DURATION_MS = 250
internal const val SCREEN_PUSH_FADE_MS = 200

internal fun screenPushOffsetPx(fullWidthPx: Int): Int = max(0, fullWidthPx)

private const val STEP_PUSH_DURATION_MS = 350
private val StepPushEasing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)

/**
 * iOS-style push between screens inside one destination: the new screen slides in from the trailing
 * edge over the old one, which drifts a third of the way out. Back plays it in reverse.
 */
internal fun screenPushTransform(forward: Boolean): ContentTransform {
    val spec = tween<IntOffset>(STEP_PUSH_DURATION_MS, easing = StepPushEasing)
    return if (forward) {
        ContentTransform(slideInHorizontally(spec) { it }, slideOutHorizontally(spec) { -it / 3 }, sizeTransform = null)
    } else {
        ContentTransform(
            slideInHorizontally(spec) { -it / 3 },
            slideOutHorizontally(spec) { it },
            targetContentZIndex = -1f,
            sizeTransform = null,
        )
    }
}

@Composable
fun ScreenPushOverlay(
    visible: Boolean,
    onDismiss: () -> Unit = {},
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier.fillMaxSize(),
        enter =
            slideInHorizontally(
                animationSpec = tween(SCREEN_PUSH_DURATION_MS, easing = FastOutSlowInEasing),
                initialOffsetX = { screenPushOffsetPx(it) },
            ) + fadeIn(animationSpec = tween(SCREEN_PUSH_FADE_MS)),
        exit =
            slideOutHorizontally(
                animationSpec = tween(SCREEN_PUSH_DURATION_MS, easing = FastOutSlowInEasing),
                targetOffsetX = { screenPushOffsetPx(it) },
            ) + fadeOut(animationSpec = tween(SCREEN_PUSH_FADE_MS)),
    ) {
        OverlayBackEffects(enabled = true, onDismiss = onDismiss) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                            onClick = {},
                        ),
            ) {
                content()
            }
        }
    }
}
