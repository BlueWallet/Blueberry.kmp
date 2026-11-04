package io.bluewallet.blueberry.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Below this the hint can't fit inside the frame (the sheet's keyboard squeezes the QR), so only the frame is drawn. */
private val MIN_HINT_SIZE = 160.dp

/** Stands in for the receive QR while the address is hidden: a dashed scan frame with a hint, never a decodable code. */
@Composable
fun QrPlaceholder(modifier: Modifier = Modifier) {
    val ink = BwColors.InkMuted
    val accent = BwColors.Accent
    BoxWithConstraints(
        // One screen-reader stop that still makes sense when the hint is dropped.
        modifier.clearAndSetSemantics { contentDescription = "QR code hidden until this address is labelled" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            // Scales with the frame so a keyboard-squeezed placeholder doesn't collapse to a dot.
            val inset = size.minDimension * 0.07f
            drawRoundRect(
                color = ink.copy(alpha = 0.6f),
                topLeft = Offset(inset, inset),
                size = Size(size.width - inset * 2, size.height - inset * 2),
                cornerRadius = CornerRadius(10.dp.toPx()),
                style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))),
            )
            // Scanner corner brackets.
            val arm = size.minDimension * 0.16f
            val w = 3.dp.toPx()
            val e = w / 2
            for ((cx, sx) in listOf(e to 1f, size.width - e to -1f)) {
                for ((cy, sy) in listOf(e to 1f, size.height - e to -1f)) {
                    drawLine(accent, Offset(cx, cy), Offset(cx + arm * sx, cy), w, StrokeCap.Round)
                    drawLine(accent, Offset(cx, cy), Offset(cx, cy + arm * sy), w, StrokeCap.Round)
                }
            }
        }
        if (minOf(maxWidth, maxHeight) >= MIN_HINT_SIZE) {
            Text(
                text = "Label this address\nto show its QR",
                modifier = Modifier.padding(28.dp),
                color = BwColors.InkQuiet,
                fontFamily = BwFontFamily,
                fontSize = BwType.CaptionSize,
                fontWeight = BwType.Caption,
                textAlign = TextAlign.Center,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
