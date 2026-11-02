package io.bluewallet.blueberry.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Shared close control. iOS 26 draws liquid glass instead; every other target uses this. */
@Composable
internal fun HeaderCloseButton(
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.semantics { contentDescription = "Close" },
    ) {
        val color = BwColors.Ink
        Canvas(Modifier.size(24.dp)) {
            val stroke =
                Stroke(
                    width = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                )
            val path =
                Path().apply {
                    moveTo(size.width * 0.30f, size.height * 0.30f)
                    lineTo(size.width * 0.70f, size.height * 0.70f)
                    moveTo(size.width * 0.70f, size.height * 0.30f)
                    lineTo(size.width * 0.30f, size.height * 0.70f)
                }
            drawPath(path, color, style = stroke)
        }
    }
}
