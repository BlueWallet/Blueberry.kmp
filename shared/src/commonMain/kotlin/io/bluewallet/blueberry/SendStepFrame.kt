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
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.bluewallet.blueberry.ui.BwColors
import io.bluewallet.blueberry.ui.BwSpace
import io.bluewallet.blueberry.ui.PlatformLargeTitle
import io.bluewallet.blueberry.ui.PlatformScreenHeader
import io.bluewallet.blueberry.ui.PlatformScrollEdge
import io.bluewallet.blueberry.ui.StepCaption
import io.bluewallet.blueberry.ui.alwaysBounceVerticalScroll
import io.bluewallet.blueberry.ui.dismissKeyboardOnTap
import io.bluewallet.blueberry.ui.platformHeaderClearance

private val HeaderToContent = 32.dp

/**
 * One send step: platform header with back, large title and caption, scrolling body,
 * and the primary action pinned below the scroll area so the edge blur never covers it.
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
    val horizontal = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(BwColors.Paper)
                .dismissKeyboardOnTap(),
    ) {
        val density = LocalDensity.current
        var barHeight by remember { mutableStateOf(0.dp) }
        val systemBottom = WindowInsets.systemBars.asPaddingValues().calculateBottomPadding()
        PlatformScrollEdge(
            modifier = Modifier.fillMaxSize().padding(bottom = barHeight + systemBottom),
            top = topChrome,
            showEdges = showEdges,
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .alwaysBounceVerticalScroll()
                        .padding(top = topChrome + HeaderToContent)
                        .windowInsetsPadding(horizontal)
                        .padding(horizontal = BwSpace.ScreenX)
                        .padding(bottom = 24.dp)
                        .windowInsetsPadding(WindowInsets.ime.only(WindowInsetsSides.Bottom)),
                verticalArrangement = Arrangement.spacedBy(BwSpace.Gap),
            ) {
                Column {
                    PlatformLargeTitle(title = title)
                    if (caption != null) StepCaption(caption)
                }
                content()
            }
        }
        SendBottomBar(
            modifier = Modifier.align(Alignment.BottomCenter),
            onHeight = { barHeight = with(density) { it.toDp() } },
            content = bottom,
        )
        PlatformScreenHeader(
            title = title,
            onBack = onBack,
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = safe.calculateTopPadding(), start = BwSpace.ScreenX, end = BwSpace.ScreenX),
        )
    }
}

/** Rides above the keyboard; reports only the button row height so the scroll edge never moves. */
@Composable
private fun SendBottomBar(
    modifier: Modifier,
    onHeight: (Int) -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .imePadding()
                .background(BwColors.Paper)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .onSizeChanged { onHeight(it.height) }
                    .padding(horizontal = BwSpace.ScreenX, vertical = 8.dp),
        ) {
            content()
        }
    }
}
