package io.bluewallet.blueberry

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.bluewallet.blueberry.ui.BwColors
import io.bluewallet.blueberry.ui.BwSpace
import io.bluewallet.blueberry.ui.PlatformLargeTitle
import io.bluewallet.blueberry.ui.PlatformScreenHeader
import io.bluewallet.blueberry.ui.PlatformScrollEdge
import io.bluewallet.blueberry.ui.ProminentButtonHeight
import io.bluewallet.blueberry.ui.StepCaption
import io.bluewallet.blueberry.ui.alwaysBounceVerticalScroll
import io.bluewallet.blueberry.ui.platformBottomEdgeClearance
import io.bluewallet.blueberry.ui.platformHeaderClearance

/**
 * One send step: platform header with back, large title and caption, scrolling body,
 * and the primary action pinned at the bottom over the edge blur.
 */
@Suppress("LongParameterList")
@Composable
internal fun SendStepFrame(
    title: String,
    caption: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    showEdges: Boolean = true,
    bottom: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val safe = WindowInsets.safeDrawing.asPaddingValues()
    val topChrome = safe.calculateTopPadding() + platformHeaderClearance()
    val sides = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
    Box(modifier = modifier.fillMaxSize().background(BwColors.Paper)) {
        PlatformScrollEdge(
            modifier = Modifier.fillMaxSize(),
            top = topChrome,
            bottom = safe.calculateBottomPadding(),
            showEdges = showEdges,
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .imePadding()
                        .alwaysBounceVerticalScroll()
                        .padding(top = topChrome)
                        .windowInsetsPadding(sides)
                        .padding(horizontal = BwSpace.ScreenX)
                        .padding(bottom = platformBottomEdgeClearance() + ProminentButtonHeight + 24.dp),
                verticalArrangement = Arrangement.spacedBy(BwSpace.Gap),
            ) {
                Column {
                    PlatformLargeTitle(title = title)
                    if (caption != null) StepCaption(caption)
                }
                content()
            }
        }
        PlatformScreenHeader(
            title = title,
            onBack = onBack,
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = safe.calculateTopPadding(), start = BwSpace.ScreenX, end = BwSpace.ScreenX),
        )
        Box(
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .imePadding()
                    .windowInsetsPadding(sides)
                    .padding(horizontal = BwSpace.ScreenX, vertical = 8.dp),
        ) {
            bottom()
        }
    }
}
