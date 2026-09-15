package io.bluewallet.blueberry

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.bluewallet.blueberry.ui.BwColors
import io.bluewallet.blueberry.ui.BwFontFamily
import io.bluewallet.blueberry.ui.BwSpace
import io.bluewallet.blueberry.ui.BwType
import io.bluewallet.blueberry.ui.ProgressMetricCard

data class HomeSyncUi(
    val percent: Int,
    val etaMs: Long?,
    val chainAge: String,
    val counts: PeerSocketCounts,
    val headers: HeadersProgress,
    val filters: FiltersProgress,
    val matching: MatchingProgress,
    val blocks: BlocksProgress,
)

@Composable
fun HomeSyncSheet(
    ui: HomeSyncUi,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(28.dp)
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(shape)
                .background(BwColors.Card, shape)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        HomeSyncSheetHeader(percent = ui.percent, etaMs = ui.etaMs, onToggle = onToggle)
        HomeSyncPeers(
            connected =
                connectedPeersCount(
                    ui.counts.probe,
                    ui.counts.hdr,
                    ui.counts.filt,
                    ui.counts.blk,
                ),
            known = ui.counts.known,
        )
        HomeSyncGrid(ui = ui)
        HomeSyncStats(counts = ui.counts)
    }
}

@Composable
private fun HomeSyncSheetHeader(
    percent: Int,
    etaMs: Long?,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SyncMark()
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Blueberry",
                color = BwColors.Ink,
                fontFamily = BwFontFamily,
                fontSize = BwType.BodySize,
                fontWeight = BwType.Body,
            )
            Text(
                text = "Private Sync",
                color = BwColors.InkSecondary,
                fontFamily = BwFontFamily,
                fontSize = BwType.CaptionSize,
                fontWeight = BwType.Caption,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = "$percent%",
                modifier =
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(BwColors.Section)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                color = BwColors.Ink,
                fontFamily = BwFontFamily,
                fontSize = 16.sp,
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

@Composable
private fun HomeSyncPeers(
    connected: Int,
    known: Int,
) {
    Column {
        Text(
            text = formatGrouped(connected),
            color = BwColors.Ink,
            fontFamily = BwFontFamily,
            fontSize = 42.sp,
            fontWeight = BwType.Hero,
        )
        Text(
            text = connectedPeersLabel(known),
            color = BwColors.InkSecondary,
            fontFamily = BwFontFamily,
            fontSize = BwType.CaptionSize,
            fontWeight = BwType.Caption,
        )
    }
}

@Composable
private fun HomeSyncGrid(ui: HomeSyncUi) {
    Column(verticalArrangement = Arrangement.spacedBy(BwSpace.Gap)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(BwSpace.Gap),
        ) {
            ProgressMetricCard(
                label = "Chain tip",
                value = formatGrouped(ui.headers.height),
                caption =
                    if (ui.headers.percent < 100) {
                        progressCaption(
                            ui.headers.downloaded,
                            ui.headers.total,
                            ui.headers.percent,
                            ui.headers.etaMs,
                        )
                    } else {
                        ui.chainAge
                    },
                percent = ui.headers.percent,
                modifier = Modifier.weight(1f),
            )
            ProgressMetricCard(
                label = "Filters DL",
                value = "${ui.filters.percent}%",
                caption = progressCaption(ui.filters.downloaded, ui.filters.total, ui.filters.percent, ui.filters.etaMs),
                percent = ui.filters.percent,
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(BwSpace.Gap),
        ) {
            ProgressMetricCard(
                label = "Filters match",
                value = "${ui.matching.percent}%",
                caption = progressCaption(ui.matching.scanned, ui.matching.total, ui.matching.percent, ui.matching.etaMs),
                percent = ui.matching.percent,
                modifier = Modifier.weight(1f),
            )
            ProgressMetricCard(
                label = "Blocks DL",
                value = "${ui.blocks.percent}%",
                caption = progressCaption(ui.blocks.downloaded, ui.blocks.matched, ui.blocks.percent, ui.blocks.etaMs),
                percent = ui.blocks.percent,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun HomeSyncStats(counts: PeerSocketCounts) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SyncStatChip(label = "probe", value = counts.probe, modifier = Modifier.weight(1f))
        SyncStatChip(label = "hdr", value = counts.hdr, modifier = Modifier.weight(1f))
        SyncStatChip(label = "filt", value = counts.filt, modifier = Modifier.weight(1f))
        SyncStatChip(label = "blk", value = counts.blk, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun SyncStatChip(
    label: String,
    value: Int,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier =
            modifier
                .clip(shape)
                .background(BwColors.Section, shape)
                .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = label,
            color = BwColors.InkMuted,
            fontFamily = BwFontFamily,
            fontSize = BwType.CaptionSize,
            fontWeight = BwType.Caption,
        )
        Text(
            text = value.toString(),
            color = if (value > 0) BwColors.Accent else BwColors.InkMuted,
            fontFamily = BwFontFamily,
            fontSize = BwType.BodySize,
            fontWeight = BwType.Value,
        )
    }
}
