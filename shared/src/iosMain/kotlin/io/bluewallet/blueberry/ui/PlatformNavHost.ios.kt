package io.bluewallet.blueberry.ui

import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.unveilIn
import androidx.compose.animation.veilOut
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost

// Same motion as navigation-compose 2.9.2's iOS defaults (NavHost.uikit.kt / DefaultNavTransitions):
// 200 ms linear slide, the covered screen moves 30% and is dimmed by black at 10.6% alpha.
private const val IOS_NAV_DURATION_MS = 200
private const val IOS_NAV_PARALLAX = 0.3f
private val IosNavDim = Color.Black.copy(alpha = 0.106f)

private fun <T> iosNavTween() = tween<T>(durationMillis = IOS_NAV_DURATION_MS, easing = LinearEasing)

/**
 * Explicit iOS transitions instead of the library defaults. With the defaults, NavHost dims the
 * covered screen with its own overlay driven by SeekableTransitionState.fraction; since Compose
 * 1.12 that state ends before the recomposition that drops the popped screen, so for one frame
 * the fraction reads 0 and the whole revealed screen is drawn fully dimmed (CMP-10750). Passing
 * any transition turns that overlay off; the dim here is a veil on each screen's own enter/exit
 * animation, which is already at its end value on that frame.
 */
@OptIn(ExperimentalAnimationApi::class)
@Composable
internal actual fun PlatformNavHost(
    navController: NavHostController,
    startDestination: Any,
    modifier: Modifier,
    builder: NavGraphBuilder.() -> Unit,
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
        enterTransition = {
            slideIntoContainer(SlideDirection.Start, animationSpec = iosNavTween())
        },
        exitTransition = {
            slideOutOfContainer(
                SlideDirection.Start,
                animationSpec = iosNavTween(),
                targetOffset = { (it * IOS_NAV_PARALLAX).toInt() },
            ) + veilOut(animationSpec = iosNavTween(), targetColor = IosNavDim)
        },
        popEnterTransition = {
            slideIntoContainer(
                SlideDirection.End,
                animationSpec = iosNavTween(),
                initialOffset = { (it * IOS_NAV_PARALLAX).toInt() },
            ) + unveilIn(animationSpec = iosNavTween(), initialColor = IosNavDim)
        },
        popExitTransition = {
            slideOutOfContainer(SlideDirection.End, animationSpec = iosNavTween())
        },
        builder = builder,
    )
}
