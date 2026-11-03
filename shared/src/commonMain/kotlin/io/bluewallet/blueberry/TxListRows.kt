package io.bluewallet.blueberry

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.bluewallet.blueberry.ui.BwColors
import io.bluewallet.blueberry.ui.BwFontFamily
import io.bluewallet.blueberry.ui.Pressable

@Composable
internal fun TxDayHeader(title: String) {
    Text(
        text = title.uppercase(),
        color = TxMoved,
        fontFamily = BwFontFamily,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 8.dp, bottom = 6.dp).semantics { heading() },
    )
}

@Composable
internal fun TxDayCard(
    items: List<TxListItem>,
    onOpen: (String) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(txListCard()),
    ) {
        items.forEachIndexed { index, item ->
            if (index > 0) {
                androidx.compose.foundation.layout.Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 1.dp)
                        .background(txRowDivider()),
                )
            }
            TxDayRow(item, onOpen)
        }
    }
}

@Composable
private fun TxDayRow(
    item: TxListItem,
    onOpen: (String) -> Unit,
) {
    val row = item.row
    Pressable(
        onClick = { onOpen(item.txid) },
        modifier = Modifier.fillMaxWidth(),
        role = Role.Button,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 68.dp).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            TxDirectionIcon(row.glyph, row.pendingGlyph, 28.dp, row.badge)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = row.title,
                    color = if (row.titleMuted) TxFailedTitle else BwColors.Ink,
                    fontFamily = BwFontFamily,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-0.45).sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                TxRowSubtitle(row.subtitle)
            }
            Column(horizontalAlignment = Alignment.End) {
                TxAmountLine(
                    parts = row.amount,
                    tone = txAmountTone(amountDirection(row.glyph), row.struck),
                    fontSize = 16.sp,
                    struck = row.struck,
                )
                if (row.amountSub != null) {
                    Text(
                        text = row.amountSub,
                        color = BwColors.InkSecondary,
                        fontFamily = BwFontFamily,
                        fontSize = 12.sp,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun TxRowSubtitle(subtitle: TxListSubtitle) {
    when (subtitle) {
        is TxListSubtitle.Status ->
            Text(subtitle.text, color = TxAmber, fontFamily = BwFontFamily, fontSize = 13.sp, maxLines = 1)
        is TxListSubtitle.Strip ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TxStripRow(
                    cells = miniCells(subtitle),
                    height = 6.dp,
                    radius = 2.dp,
                    gap = 2.dp,
                    mini = true,
                )
                Text(subtitle.label, color = if (subtitle.pending) TxFrozen else BwColors.InkSecondary, fontFamily = BwFontFamily, fontSize = 13.sp, maxLines = 1)
            }
        is TxListSubtitle.Noted ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(subtitle.lead, color = leadColor(subtitle.tone), fontFamily = BwFontFamily, fontSize = 13.sp, maxLines = 1)
                Text(
                    text = " · ${subtitle.date}",
                    color = BwColors.InkSecondary,
                    fontFamily = BwFontFamily,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        is TxListSubtitle.DateOnly ->
            Text(subtitle.date, color = BwColors.InkSecondary, fontFamily = BwFontFamily, fontSize = 13.sp, maxLines = 1)
    }
}

@Composable
private fun leadColor(tone: TxListLeadTone): Color =
    when (tone) {
        TxListLeadTone.Amber -> TxAmber
        TxListLeadTone.Frozen -> TxFrozen
        TxListLeadTone.Secondary -> BwColors.InkSecondary
    }

private fun miniCells(subtitle: TxListSubtitle.Strip): List<TxStripCell> =
    List(6) { index ->
        when {
            subtitle.pending && index == 0 -> TxStripCell.Pending
            subtitle.pending -> TxStripCell.Empty
            index == 0 -> TxStripCell.Block
            index < subtitle.confirmations -> TxStripCell.Confirmed
            else -> TxStripCell.Empty
        }
    }

private fun amountDirection(glyph: TxGlyph): TxDirection =
    when (glyph) {
        TxGlyph.Received -> TxDirection.In
        TxGlyph.Moved -> TxDirection.Moved
        TxGlyph.Sent, TxGlyph.Failed -> TxDirection.Out
    }
