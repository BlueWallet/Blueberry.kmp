package io.bluewallet.blueberry

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import io.bluewallet.blueberry.ui.BwFontFamily

/** Draws a prepared [CoinMapLayout]. Not interactive: one image with a description. */
@Composable
internal fun CoinMapView(
    layout: CoinMapLayout,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val p = SheetPalette
    Canvas(
        modifier =
            modifier
                .fillMaxWidth()
                .aspectRatio(COIN_MAP_WIDTH / COIN_MAP_HEIGHT)
                .clip(RoundedCornerShape(16.dp))
                .semantics { contentDescription = layout.description },
    ) {
        drawRect(p.sheet)
        val k = size.width / COIN_MAP_WIDTH
        val pen = MapPen(p, k)
        layout.outlines.forEach { drawOutline(it, pen) }
        layout.segments.sortedBy { it.selected }.forEach { drawSegment(it, pen) }
        layout.dots.forEach { drawDot(it, pen) }
        layout.tags.forEach { drawTag(measurer, it.text, Offset(it.x * k, it.y * k), if (it.openGroup) p.soft else p.secondary, pen) }
        if (layout.overflow > 0) {
            drawTag(measurer, "+${layout.overflow}", Offset(size.width - 22f * k, size.height - 14f * k), p.secondary, pen)
        }
    }
}

/** Same footprint as [CoinMapView] while the layout is computed, so nothing jumps when it lands. */
@Composable
internal fun CoinMapPlaceholder() {
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(COIN_MAP_WIDTH / COIN_MAP_HEIGHT)
            .clip(RoundedCornerShape(16.dp))
            .background(SheetPalette.sheet),
    )
}

/** Palette plus the layout-to-pixel scale for one draw pass. */
private class MapPen(
    val p: SheetColors,
    val k: Float,
)

private fun dashed(
    k: Float,
    on: Float,
    off: Float,
): PathEffect = PathEffect.dashPathEffect(floatArrayOf(on * k, off * k))

private fun DrawScope.drawOutline(
    outline: MapOutline,
    pen: MapPen,
) {
    val k = pen.k
    val ink = pen.p.text
    val topLeft = Offset(outline.left * k, outline.top * k)
    val box = Size((outline.right - outline.left) * k, (outline.bottom - outline.top) * k)
    val radius = CornerRadius(36f * k)
    if (outline.openGroup) {
        drawRoundRect(ink.copy(alpha = 0.05f), topLeft, box, radius)
        drawRoundRect(ink.copy(alpha = 0.28f), topLeft, box, radius, style = Stroke(1f * k))
    } else {
        drawRoundRect(ink.copy(alpha = 0.12f), topLeft, box, radius, style = Stroke(1f * k, pathEffect = dashed(k, 3f, 4f)))
    }
}

private fun DrawScope.drawSegment(
    segment: MapSegment,
    pen: MapPen,
) {
    val k = pen.k
    val from = Offset(segment.x1 * k, segment.y1 * k)
    val to = Offset(segment.x2 * k, segment.y2 * k)
    when {
        segment.selected -> drawLine(pen.p.text, from, to, strokeWidth = 2.5f * k)
        segment.openGroup -> drawLine(pen.p.secondary, from, to, strokeWidth = 1.5f * k)
        else -> drawLine(pen.p.grey, from, to, strokeWidth = 1.5f * k, pathEffect = dashed(k, 3f, 4f))
    }
}

private fun DrawScope.drawDot(
    dot: MapDot,
    pen: MapPen,
) {
    val k = pen.k
    val p = pen.p
    val center = Offset(dot.x * k, dot.y * k)
    val r = dot.diameter / 2f * k
    val fill =
        when (dot.role) {
            MapRole.OpenGroup -> Color(dot.colorArgb)
            MapRole.OtherGroup -> p.otherGroup
            MapRole.Separate -> p.grey
        }
    if (dot.frozen) {
        drawCircle(p.glyph, r - 0.75f * k, center, style = Stroke(1.5f * k, pathEffect = dashed(k, 2f, 2f)))
    } else {
        drawCircle(fill, r, center)
    }
    if (dot.open) {
        drawCircle(p.sheet, r + 3f * k, center, style = Stroke(3f * k))
        drawCircle(p.text, r + 4.5f * k, center, style = Stroke(2f * k))
    } else if (dot.selected) {
        drawCircle(p.sheet, r + 1.5f * k, center, style = Stroke(3f * k))
        drawCircle(Color(dot.colorArgb), r + 3.75f * k, center, style = Stroke(1.5f * k))
    }
}

private fun DrawScope.drawTag(
    measurer: TextMeasurer,
    text: String,
    center: Offset,
    color: Color,
    pen: MapPen,
) {
    val k = pen.k
    val style = TextStyle(color = color, fontFamily = BwFontFamily, fontWeight = FontWeight.Bold, fontSize = (11f * k).toSp())
    val measured = measurer.measure(text, style)
    val w = measured.size.width.toFloat()
    val h = measured.size.height.toFloat()
    val padX = 6f * k
    val padY = 2f * k
    val left = (center.x - w / 2f - padX).coerceIn(0f, size.width - w - padX * 2)
    val top = (center.y - h / 2f - padY).coerceIn(0f, size.height - h - padY * 2)
    drawRoundRect(pen.p.sheet, Offset(left, top), Size(w + padX * 2, h + padY * 2), CornerRadius(h))
    drawText(measured, topLeft = Offset(left + padX, top + padY))
}
