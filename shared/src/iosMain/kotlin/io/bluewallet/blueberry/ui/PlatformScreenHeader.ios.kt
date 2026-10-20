package io.bluewallet.blueberry.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val NavigationBarHeight = 44.dp

/** How far the bottom edge blur reaches above the home indicator. */
internal val IosBottomEdgeFeather = 36.dp

@Composable
actual fun PlatformScreenHeader(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(NavigationBarHeight)
                .semantics { contentDescription = title },
    ) {
        IosGlassBackButton(
            onBack = onBack,
            enabled = enabled,
            modifier = Modifier.align(Alignment.CenterStart),
        )
    }
}

@Composable
actual fun platformHeaderClearance(): Dp = NavigationBarHeight

@Composable
actual fun platformBottomEdgeClearance(): Dp = IosBottomEdgeFeather

@Composable
actual fun PlatformLargeTitle(
    title: String,
    modifier: Modifier,
) {
    Text(
        text = title,
        modifier = modifier,
        color = BwColors.Ink,
        fontFamily = BwFontFamily,
        fontSize = 34.sp,
        lineHeight = 41.sp,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
actual fun PlatformBodyStyle(): TextStyle =
    TextStyle(
        fontFamily = BwFontFamily,
        fontSize = 17.sp,
        lineHeight = 22.sp,
        fontWeight = BwDefaultFontWeight,
        color = BwColors.Ink,
    )
