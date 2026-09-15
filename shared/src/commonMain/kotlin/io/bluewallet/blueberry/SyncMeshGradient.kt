package io.bluewallet.blueberry

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.ceil

private val Bayer4 =
    arrayOf(
        intArrayOf(0, 8, 2, 10),
        intArrayOf(12, 4, 14, 6),
        intArrayOf(3, 11, 1, 9),
        intArrayOf(15, 7, 13, 5),
    )

fun shouldDrawSyncMeshDot(
    col: Int,
    row: Int,
    t: Float,
): Boolean {
    val threshold = 16f * (1f - t.coerceIn(0f, 1f))
    return Bayer4[row and 3][col and 3] < threshold
}

/**
 * Dithered teal wash from [dither.svg]: black → #07232C@80% → #0E485A
 * with a 4×4 Bayer of black 1.5dp dots on a 6dp grid.
 */
@Composable
fun SyncMeshGradient(
    modifier: Modifier = Modifier,
    phase: Float = 0f,
) {
    Canvas(modifier = modifier) {
        val fade =
            Brush.verticalGradient(
                0f to Color.Black,
                0.485577f to Color(0xCC07232C),
                1f to Color(0xFF0E485A),
            )
        drawRect(Color.Black)
        drawRect(brush = fade)
        val step = 6.dp.toPx()
        val radius = 1.5.dp.toPx()
        val dx = (phase % 1f) * step
        val dy = ((phase * 0.37f) % 1f) * step
        val cols = ceil(size.width / step).toInt() + 1
        val rows = ceil(size.height / step).toInt() + 1
        var row = 0
        while (row < rows) {
            var col = 0
            val y = row * step + step / 2f - dy
            val t = (y / size.height).coerceIn(0f, 1f)
            while (col < cols) {
                if (shouldDrawSyncMeshDot(col, row, t)) {
                    val x = col * step + step / 2f - dx
                    drawCircle(Color.Black, radius, Offset(x, y))
                }
                col += 1
            }
            row += 1
        }
    }
}
