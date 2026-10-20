package io.bluewallet.blueberry

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
actual fun HomeOverflowButton(
    onClick: () -> Unit,
    modifier: Modifier,
) {
    HomeOverflowDots(onClick = onClick, modifier = modifier)
}
