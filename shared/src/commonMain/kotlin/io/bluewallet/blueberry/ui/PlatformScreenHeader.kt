package io.bluewallet.blueberry.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
expect fun PlatformScreenHeader(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
)

/** Body copy at the platform default size: iOS 17pt, Material bodyLarge elsewhere. */
@Composable
expect fun PlatformBodyStyle(): TextStyle

/**
 * Height of the pinned navigation bar, not counting the status bar.
 * Scroll content clears this so a large title can move underneath it.
 */
@Composable
expect fun platformHeaderClearance(): Dp

/**
 * Space below scroll content above the system bottom chrome.
 * iOS also clears the edge blur; Android uses this as nav / gesture breathing room.
 */
@Composable
expect fun platformBottomEdgeClearance(): Dp

/** iOS large title, which scrolls with the content. Material keeps the title in the bar. */
@Composable
expect fun PlatformLargeTitle(
    title: String,
    modifier: Modifier = Modifier,
)

/**
 * iOS scroll-edge blur. [top] and [bottom] are the chrome that stays soft
 * (status bar, pinned bar, home indicator); the blur fades out just past it.
 * Compose scrolling is not a UIScrollView, so this overlays a system blur.
 * Other platforms draw the content unchanged.
 *
 * iOS fades are UIKit overlays, drawn above every Compose sibling. Pass
 * [showEdges] = false while a Compose scrim or pushed screen covers the content.
 */
@Composable
expect fun PlatformScrollEdge(
    modifier: Modifier = Modifier,
    top: Dp = 0.dp,
    bottom: Dp = 0.dp,
    showEdges: Boolean = true,
    content: @Composable () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MaterialScreenHeader(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    TopAppBar(
        modifier = modifier,
        windowInsets = WindowInsets(0, 0, 0, 0),
        colors =
            TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent,
                scrolledContainerColor = Color.Transparent,
                titleContentColor = BwColors.Ink,
                navigationIconContentColor = BwColors.Ink,
            ),
        navigationIcon = {
            HeaderBackButton(onClick = onBack, enabled = enabled)
        },
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            )
        },
    )
}

@Composable
internal fun MaterialBodyStyle(): TextStyle = MaterialTheme.typography.bodyLarge.copy(color = BwColors.Ink)

/** Shared back control. iOS 26 draws liquid glass instead; every other target uses this. */
@Composable
internal fun HeaderBackButton(
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.semantics { contentDescription = "Back" },
    ) {
        BackArrow()
    }
}

@Composable
private fun BackArrow() {
    val color = BwColors.Ink
    Canvas(Modifier.size(24.dp)) {
        val stroke =
            Stroke(
                width = 2.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            )
        val path =
            Path().apply {
                moveTo(size.width * 0.62f, size.height * 0.20f)
                lineTo(size.width * 0.28f, size.height * 0.50f)
                lineTo(size.width * 0.62f, size.height * 0.80f)
                moveTo(size.width * 0.30f, size.height * 0.50f)
                lineTo(size.width * 0.78f, size.height * 0.50f)
            }
        drawPath(path, color, style = stroke)
    }
}
