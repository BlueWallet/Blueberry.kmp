package io.bluewallet.blueberry.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState

@Suppress("UnusedParameter", "LongParameterList")
@Composable
actual fun PlatformScreenHeader(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    showBarTitle: Boolean,
    trailingText: String?,
    onTrailing: (() -> Unit)?,
) {
    MaterialScreenHeader(
        title = title,
        onBack = onBack,
        modifier = modifier,
        enabled = enabled,
        trailingText = trailingText,
        onTrailing = onTrailing,
    )
}

@Composable
actual fun PlatformBodyStyle(): TextStyle = MaterialBodyStyle()

@Composable
actual fun platformHeaderClearance(): Dp = 64.dp

@Composable
actual fun platformBottomEdgeClearance(): Dp = 0.dp

@Composable
actual fun PlatformLargeTitle(
    title: String,
    modifier: Modifier,
) = Unit

@Suppress("UnusedParameter")
@Composable
actual fun PlatformScrollEdge(
    modifier: Modifier,
    top: Dp,
    bottom: Dp?,
    showEdges: Boolean,
    content: @Composable () -> Unit,
) {
    Box(modifier) { content() }
}

@Suppress("UnusedParameter")
@Composable
actual fun PlatformBottomEdgeBlur(
    hazeState: HazeState,
    modifier: Modifier,
) = Unit
