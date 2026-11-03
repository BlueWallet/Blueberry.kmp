package io.bluewallet.blueberry

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val StripGroupPad = 5.dp
private val StripMaxHeight = 66.dp

/** The wallet's coins as the home bubble strip. [highlightedKey] is the row selected under the strip. */
@Composable
internal fun CoinSheetBubbleStrip(
    strip: SheetStrip,
    highlightedKey: String?,
) {
    val ink = SheetPalette
    val marks = StripMarks(strip.openKey, highlightedKey ?: strip.openKey, StripInk(ink.text, ink.glyph, ink.soft))
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(ink.sheet)
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .semantics { contentDescription = strip.caption },
        ) {
            val layout =
                remember(strip.bubbles, maxWidth) {
                    layoutBubbleStrip(bubbles = strip.bubbles, availableWidthDp = maxWidth.value)
                }
            val maxRadius = layout.items.maxOfOrNull { it.radiusDp } ?: 10f
            val stripHeight = (maxRadius.dp * 2 + StripGroupPad * 2).coerceAtMost(StripMaxHeight)
            Canvas(Modifier.fillMaxWidth().height(stripHeight)) {
                drawSheetBubbles(layout, marks)
            }
            if (layout.overflowCount > 0) {
                SheetText(
                    "+${layout.overflowCount}",
                    12.sp,
                    ink.secondary,
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }
        }
        SheetText(strip.caption, 13.sp, ink.soft)
    }
}

private class StripInk(
    val ring: Color,
    val dim: Color,
    val litStroke: Color,
)

private class StripMarks(
    val openKey: String,
    val highlightKey: String,
    val ink: StripInk,
)

private fun DrawScope.drawSheetBubbles(
    layout: BubbleStripLayout,
    marks: StripMarks,
) {
    val cy = size.height / 2f
    var x = 0f
    var i = 0
    while (i < layout.items.size) {
        val placed = placeCluster(layout.items, StripCursor(x, i), cy, marks)
        x = placed.x
        i = placed.next
    }
}

private class StripCursor(
    val x: Float,
    val next: Int,
)

private fun DrawScope.placeCluster(
    items: List<BubbleLayoutItem>,
    cursor: StripCursor,
    cy: Float,
    marks: StripMarks,
): StripCursor {
    val index = cursor.next
    val groupId = items[index].linkedGroupId
    return if (groupId == null) {
        placeSolo(items, cursor, cy, marks)
    } else {
        val end = items.groupEnd(index, groupId)
        val nextX = drawStripGroup(items.subList(index, end), cursor.x, cy, marks)
        StripCursor(if (end < items.size) nextX + 8.dp.toPx() else nextX, end)
    }
}

private fun List<BubbleLayoutItem>.groupEnd(
    start: Int,
    groupId: String,
): Int {
    var end = start + 1
    while (end < size && this[end].linkedGroupId == groupId) end++
    return end
}

private fun DrawScope.placeSolo(
    items: List<BubbleLayoutItem>,
    cursor: StripCursor,
    cy: Float,
    marks: StripMarks,
): StripCursor {
    val index = cursor.next
    val item = items[index]
    val radius = item.radiusDp.dp.toPx()
    val lit = item.key == marks.openKey || item.key == marks.highlightKey
    drawStripDot(StripDot(Offset(cursor.x + radius, cy), radius, item, lit, item.key == marks.highlightKey), marks.ink)
    val gap =
        when {
            index + 1 >= items.size -> 0f
            items[index + 1].linkedGroupId != null -> 8.dp.toPx()
            else -> 5.dp.toPx()
        }
    return StripCursor(cursor.x + radius * 2f + gap, index + 1)
}

private fun DrawScope.drawStripGroup(
    group: List<BubbleLayoutItem>,
    startX: Float,
    cy: Float,
    marks: StripMarks,
): Float {
    val lit = group.any { it.key == marks.openKey }
    val diameters = group.map { it.radiusDp.dp.toPx() * 2f }
    val pad = StripGroupPad.toPx()
    val inner = diameters.sum() + 3.dp.toPx() * (group.size - 1)
    val stadiumW = inner + pad * 2f
    val stadiumH = (diameters.maxOrNull() ?: 0f) + pad * 2f
    val top = cy - stadiumH / 2f
    if (lit) {
        drawRoundRect(marks.ink.litStroke.copy(alpha = 0.08f), Offset(startX, top), Size(stadiumW, stadiumH), CornerRadius(stadiumH / 2f))
    }
    drawRoundRect(
        color = if (lit) marks.ink.litStroke else marks.ink.dim,
        topLeft = Offset(startX, top),
        size = Size(stadiumW, stadiumH),
        cornerRadius = CornerRadius(stadiumH / 2f),
        style =
            Stroke(
                width = 1.dp.toPx(),
                pathEffect = if (lit) null else PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx())),
            ),
    )
    var cx = startX + pad
    group.forEachIndexed { index, item ->
        val radius = item.radiusDp.dp.toPx()
        cx += radius
        val shown = lit || item.key == marks.highlightKey
        drawStripDot(StripDot(Offset(cx, cy), radius, item, shown, item.key == marks.highlightKey), marks.ink)
        cx += radius
        if (index < group.lastIndex) cx += 3.dp.toPx()
    }
    return startX + stadiumW
}

private class StripDot(
    val center: Offset,
    val radius: Float,
    val item: BubbleLayoutItem,
    val lit: Boolean,
    val ringed: Boolean,
)

private fun DrawScope.drawStripDot(
    dot: StripDot,
    ink: StripInk,
) {
    if (dot.lit && dot.item.isFrozen) {
        drawCircle(ink.dim, dot.radius, dot.center, style = Stroke(width = 1.2.dp.toPx()))
    } else {
        val fill = if (dot.lit) Color(dot.item.colorArgb) else ink.dim
        drawCircle(fill, dot.radius, dot.center)
    }
    if (dot.ringed) drawCircle(ink.ring, dot.radius + 2.dp.toPx(), dot.center, style = Stroke(width = 2.dp.toPx()))
}
