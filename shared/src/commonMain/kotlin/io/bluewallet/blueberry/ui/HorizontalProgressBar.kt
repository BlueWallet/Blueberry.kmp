package io.bluewallet.blueberry.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.bluewallet.blueberry.progressFillFraction

/** One ice-blue track, accent fill from the left — same line as the sister bars, laid flat. */
@Composable
fun HorizontalProgressBar(
    percent: Int,
    modifier: Modifier = Modifier,
) {
    val fill = progressFillFraction(percent)
    val shape = RoundedCornerShape(50)
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(shape)
                .background(BwColors.BarTrack),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (fill > 0f) {
            Box(
                modifier =
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(fill)
                        .clip(shape)
                        .background(BwColors.Accent),
            )
        }
    }
}
