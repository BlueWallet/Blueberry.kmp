package io.bluewallet.blueberry

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.bluewallet.blueberry.ui.BwColors
import io.bluewallet.blueberry.ui.BwFontFamily
import io.bluewallet.blueberry.ui.BwType

@Composable
fun HomeSyncDock(
    percent: Int,
    etaMs: Long?,
    listState: LazyListState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fade =
        Brush.verticalGradient(
            0f to Color.Transparent,
            0.22f to Color.Black.copy(alpha = 0.55f),
            0.42f to Color.Black.copy(alpha = 0.92f),
            1f to Color.Black,
        )
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(fade)
                .navigationBarsPadding(),
    ) {
        ListScrollStrip(
            listState = listState,
            modifier = Modifier.fillMaxWidth().height(40.dp),
        )
        HomeSyncBar(
            percent = percent,
            etaMs = etaMs,
            onClick = onClick,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        ListScrollStrip(
            listState = listState,
            modifier = Modifier.fillMaxWidth().height(40.dp),
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
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(24.dp)
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .shadow(24.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
                .clip(shape)
                .background(BwColors.Card, shape)
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SyncMark()
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Private Sync",
                color = BwColors.Ink,
                fontFamily = BwFontFamily,
                fontSize = BwType.BodySize,
                fontWeight = BwType.Body,
            )
            Text(
                text = "Direct to Bitcoin",
                color = BwColors.InkSecondary,
                fontFamily = BwFontFamily,
                fontSize = BwType.CaptionSize,
                fontWeight = BwType.Caption,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = "$percent%",
                color = BwColors.Ink,
                fontFamily = BwFontFamily,
                fontSize = 22.sp,
                fontWeight = BwType.Value,
            )
            if (etaMs != null) {
                Text(
                    text = "ETA ${formatEta(etaMs)}",
                    color = BwColors.InkMuted,
                    fontFamily = BwFontFamily,
                    fontSize = BwType.CaptionSize,
                    fontWeight = BwType.Caption,
                )
            }
        }
    }
}
