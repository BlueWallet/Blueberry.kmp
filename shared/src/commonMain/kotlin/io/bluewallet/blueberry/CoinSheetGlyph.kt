package io.bluewallet.blueberry

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate

enum class SheetGlyph {
    Snow,
    Send,
    Check,
    Eye,
    Warn,
    Chevron,
}

/** Line icons for the coin sheet; the repo has no icon set. */
@Composable
internal fun SheetGlyphView(
    glyph: SheetGlyph,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val stroke = Stroke(width = size.minDimension * 0.11f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (glyph) {
            SheetGlyph.Chevron -> polyline(tint, stroke, 0.38f to 0.2f, 0.68f to 0.5f, 0.38f to 0.8f)
            SheetGlyph.Check -> polyline(tint, stroke, 0.2f to 0.52f, 0.42f to 0.74f, 0.8f to 0.3f)
            SheetGlyph.Send -> sendArrow(tint, stroke)
            SheetGlyph.Snow -> snowflake(tint, stroke)
            SheetGlyph.Eye -> eye(tint, stroke)
            SheetGlyph.Warn -> warn(tint, stroke)
        }
    }
}

private fun DrawScope.polyline(
    tint: Color,
    stroke: Stroke,
    vararg points: Pair<Float, Float>,
) {
    val path = Path()
    points.forEachIndexed { i, (x, y) ->
        if (i == 0) path.moveTo(size.width * x, size.height * y) else path.lineTo(size.width * x, size.height * y)
    }
    drawPath(path, tint, style = stroke)
}

private fun DrawScope.sendArrow(
    tint: Color,
    stroke: Stroke,
) {
    polyline(tint, stroke, 0.25f to 0.75f, 0.75f to 0.25f)
    polyline(tint, stroke, 0.35f to 0.25f, 0.75f to 0.25f, 0.75f to 0.65f)
}

private fun DrawScope.snowflake(
    tint: Color,
    stroke: Stroke,
) {
    for (angle in listOf(0f, 60f, 120f)) {
        rotate(angle) { polyline(tint, stroke, 0.5f to 0.1f, 0.5f to 0.9f) }
    }
}

private fun DrawScope.eye(
    tint: Color,
    stroke: Stroke,
) {
    val w = size.width
    val path =
        Path().apply {
            moveTo(w * 0.08f, w * 0.5f)
            quadraticTo(w * 0.5f, w * 0.1f, w * 0.92f, w * 0.5f)
            quadraticTo(w * 0.5f, w * 0.9f, w * 0.08f, w * 0.5f)
        }
    drawPath(path, tint, style = stroke)
    drawCircle(tint, radius = w * 0.12f, center = Offset(w * 0.5f, w * 0.5f))
}

private fun DrawScope.warn(
    tint: Color,
    stroke: Stroke,
) {
    polyline(tint, stroke, 0.5f to 0.2f, 0.5f to 0.58f)
    drawCircle(tint, radius = size.width * 0.07f, center = Offset(size.width * 0.5f, size.height * 0.8f))
}
