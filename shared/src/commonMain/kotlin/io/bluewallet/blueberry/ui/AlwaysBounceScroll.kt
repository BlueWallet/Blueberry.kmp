package io.bluewallet.blueberry.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val BounceTail = 1.dp

private object AlwaysBounceTailKey

/**
 * Fills the incoming space and scrolls with the platform overscroll.
 * On iOS that is the system rubber-band. The tail keeps a scroll range so the
 * bounce still runs when the content fits on the screen. Children can use
 * [androidx.compose.foundation.layout.ColumnScope.weight].
 */
@Composable
fun AlwaysBounceScroll(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(modifier) {
        val viewport = maxHeight
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            Column(
                modifier = Modifier.fillMaxWidth().height(viewport),
                content = content,
            )
            Spacer(Modifier.height(BounceTail))
        }
    }
}

/**
 * Replaces [verticalScroll] on a container whose height comes from its parent
 * (`fillMaxSize` or `weight`). Content shorter than that height still rubber-bands.
 */
@Composable
fun Modifier.alwaysBounceVerticalScroll(): Modifier {
    val state = rememberScrollState()
    var viewportPx by remember { mutableIntStateOf(0) }
    val minHeight = with(LocalDensity.current) { (viewportPx + 1).toDp() }
    return onSizeChanged { viewportPx = it.height }
        .verticalScroll(state)
        .heightIn(min = minHeight)
}

/** Keeps a [LazyColumn] rubber-banding when every item already fits. */
fun LazyListScope.alwaysBounceTail(state: LazyListState) {
    item(key = AlwaysBounceTailKey) {
        Spacer(Modifier.height(state.alwaysBounceTailHeight()).fillMaxWidth())
    }
}

@Composable
private fun LazyListState.alwaysBounceTailHeight(): Dp {
    val info = layoutInfo
    val viewport = (info.viewportEndOffset - info.viewportStartOffset).coerceAtLeast(0)
    val others = info.visibleItemsInfo.filter { it.key != AlwaysBounceTailKey }
    val otherCount = (info.totalItemsCount - 1).coerceAtLeast(0)
    val occupied =
        others.sumOf { it.size } +
            info.beforeContentPadding +
            info.afterContentPadding +
            info.mainAxisItemSpacing * otherCount
    val px = bounceTailPx(viewport, occupied, others.size == otherCount)
    return with(LocalDensity.current) { px.toDp() }
}

internal fun bounceTailPx(
    viewportPx: Int,
    occupiedPx: Int,
    allOthersVisible: Boolean,
): Int {
    if (viewportPx <= 0 || !allOthersVisible) return 1
    return (viewportPx - occupiedPx + 1).coerceAtLeast(1)
}
