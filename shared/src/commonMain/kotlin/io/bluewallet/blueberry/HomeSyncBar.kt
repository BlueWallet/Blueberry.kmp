package io.bluewallet.blueberry

import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import io.bluewallet.blueberry.ui.BwColors
import io.bluewallet.blueberry.ui.BwFontFamily
import io.bluewallet.blueberry.ui.BwSpace
import io.bluewallet.blueberry.ui.BwType
import io.bluewallet.blueberry.ui.LocalDarkPalette
import io.bluewallet.blueberry.ui.Pressable

/** Space under the Private Sync card. The bottom edge blur stays inside this inset. */
internal val HomeSyncDockBottomInset = 40.dp

@Composable
fun HomeSyncDock(
    percent: Int,
    etaMs: Long?,
    listState: LazyListState,
    hazeState: HazeState,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
    ) {
        ListScrollStrip(
            listState = listState,
            modifier = Modifier.fillMaxWidth().height(40.dp),
        )
        HomeSyncBar(
            percent = percent,
            etaMs = etaMs,
            hazeState = hazeState,
            onClick = onClick,
            modifier = Modifier.padding(horizontal = BwSpace.ScreenX),
        )
        ListScrollStrip(
            listState = listState,
            modifier = Modifier.fillMaxWidth().height(HomeSyncDockBottomInset),
        )
    }
}

@Composable
private fun ListScrollStrip(
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier.pointerInput(listState) {
                detectVerticalDragGestures { change, dragAmount ->
                    change.consume()
                    listState.dispatchRawDelta(-dragAmount)
                }
            },
    )
}

@Composable
fun HomeSyncBar(
    percent: Int,
    etaMs: Long?,
    hazeState: HazeState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(16.dp)
    val dark = LocalDarkPalette.current
    val fill = if (dark) SyncDockFill else BwColors.Card.copy(alpha = SYNC_DOCK_LIGHT_ALPHA)
    val border = if (dark) SyncDockBorder else SyncDockLightBorder
    val hazeStyle = remember(fill) { syncDockHazeStyle(fill) }
    Pressable(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        role = Role.Button,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .border(1.dp, border, shape)
                    .clip(shape)
                    .hazeEffect(state = hazeState, style = hazeStyle)
                    .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SyncMark()
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "Private Sync",
                    color = BwColors.Ink,
                    style = syncDockTitle,
                )
                Text(
                    text = "Direct to Bitcoin",
                    color = BwColors.InkSecondary,
                    style = syncDockSubtitle,
                )
            }
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "$percent%",
                    color = BwColors.Ink,
                    style = syncDockPercent,
                )
                if (etaMs != null) {
                    Text(
                        text = "ETA ${formatEta(etaMs)}",
                        color = BwColors.InkMuted,
                        style = syncDockSubtitle,
                    )
                }
            }
        }
    }
}

private val syncDockText =
    TextStyle(
        fontFamily = BwFontFamily,
        lineHeightStyle =
            LineHeightStyle(
                alignment = LineHeightStyle.Alignment.Center,
                trim = LineHeightStyle.Trim.Both,
            ),
    )

private val syncDockTitle =
    syncDockText.copy(
        fontSize = BwType.BodySize,
        fontWeight = BwType.Body,
        lineHeight = 18.sp,
    )

private val syncDockSubtitle =
    syncDockText.copy(
        fontSize = BwType.CaptionSize,
        fontWeight = BwType.Caption,
        lineHeight = 14.sp,
    )

private val syncDockPercent =
    syncDockText.copy(
        fontSize = 22.sp,
        fontWeight = BwType.Value,
        lineHeight = 26.sp,
    )

/** Dark palette: fill #2C3840 at 60% over a uniform background blur of 10. */
private val SyncDockFill = Color(0xFF2C3840).copy(alpha = 0.60f)

/** Dark palette: 1px white stroke at 10%. */
private val SyncDockBorder = Color.White.copy(alpha = 0.10f)

/** Light palette: 1px ink stroke at 18%. */
private val SyncDockLightBorder = Color(0xFF0C2550).copy(alpha = 0.18f)

/** Light palette: white card tint, translucent so the blur still shows through. */
private const val SYNC_DOCK_LIGHT_ALPHA = 0.55f

private fun syncDockHazeStyle(fill: Color) =
    HazeStyle(
        backgroundColor = Color.Transparent,
        tint = HazeTint(fill),
        blurRadius = 10.dp,
        noiseFactor = 0f,
        fallbackTint = HazeTint(fill),
    )
