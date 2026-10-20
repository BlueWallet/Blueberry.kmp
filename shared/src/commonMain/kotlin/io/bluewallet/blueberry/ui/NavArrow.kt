package io.bluewallet.blueberry.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

enum class NavArrowDirection { Left, Right }

/** 36dp "<" or ">" tap target, announced to screen readers as [label]. Drawn muted and ignores taps when disabled. */
@Composable
fun NavArrow(
    direction: NavArrowDirection,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val color = if (enabled) BwColors.Ink else BwColors.InkMuted.copy(alpha = 0.4f)
    Canvas(
        modifier =
            Modifier
                .size(36.dp)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .semantics { contentDescription = label },
    ) {
        val path =
            Path().apply {
                moveTo(size.width * 0.58f, size.height * 0.28f)
                lineTo(size.width * 0.38f, size.height * 0.50f)
                lineTo(size.width * 0.58f, size.height * 0.72f)
            }
        val flip = if (direction == NavArrowDirection.Right) -1f else 1f
        scale(scaleX = flip, scaleY = 1f) {
            drawPath(path, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}
