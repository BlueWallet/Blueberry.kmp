package io.bluewallet.blueberry.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
actual fun PlatformScreenHeader(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
) {
    MaterialScreenHeader(
        title = title,
        onBack = onBack,
        modifier = modifier,
        enabled = enabled,
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
    bottom: Dp,
    content: @Composable () -> Unit,
) {
    Box(modifier) { content() }
}
