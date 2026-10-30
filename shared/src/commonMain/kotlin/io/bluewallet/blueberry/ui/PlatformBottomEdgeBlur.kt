package io.bluewallet.blueberry.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.chrisbanes.haze.HazeState

/**
 * Bottom scroll-edge blur drawn in Compose, so controls placed after it stay on top.
 * Blurs whatever is marked with `hazeSource(hazeState)`. iOS only, like [PlatformScrollEdge].
 */
@Composable
expect fun PlatformBottomEdgeBlur(
    hazeState: HazeState,
    modifier: Modifier = Modifier,
)
