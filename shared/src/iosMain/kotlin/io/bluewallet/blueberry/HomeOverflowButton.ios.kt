package io.bluewallet.blueberry

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.bluewallet.blueberry.ui.IosGlassSymbolButton
import io.bluewallet.blueberry.ui.rememberSupportsIosLiquidGlass

@Composable
actual fun HomeOverflowButton(
    onClick: () -> Unit,
    modifier: Modifier,
) {
    if (rememberSupportsIosLiquidGlass()) {
        IosGlassSymbolButton(
            symbol = "ellipsis",
            accessibilityLabel = "Settings",
            onClick = onClick,
            modifier = modifier,
        )
    } else {
        HomeOverflowDots(onClick = onClick, modifier = modifier)
    }
}
