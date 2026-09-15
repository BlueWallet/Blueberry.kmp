package io.bluewallet.blueberry

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import io.bluewallet.blueberry.ui.BwColors
import io.bluewallet.blueberry.ui.BwFontFamily
import io.bluewallet.blueberry.ui.BwType

@Composable
fun HomeOverflowButton(onClick: () -> Unit) {
    Box(
        modifier =
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(BwColors.Card)
                .border(1.dp, Color(0xFF2A3033), CircleShape)
                .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(3) {
                Box(
                    modifier =
                        Modifier
                            .size(4.dp)
                            .clip(CircleShape)
                            .background(BwColors.Ink),
                )
            }
        }
    }
}

@Composable
fun HomeActionRow(
    onReceive: () -> Unit,
    onSend: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HomeActionButton(
            text = "Receive",
            incoming = true,
            onClick = onReceive,
            modifier = Modifier.weight(1f),
        )
        HomeActionButton(
            text = "Send",
            incoming = false,
            onClick = onSend,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun HomeActionButton(
    text: String,
    incoming: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier =
            modifier
                .height(44.dp)
                .clip(shape)
                .background(BwColors.AccentSoft, shape)
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier =
                    Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(BwColors.Accent),
                contentAlignment = Alignment.Center,
            ) {
                DirectionGlyph(incoming = incoming, color = BwColors.AccentSoft)
            }
            Text(
                text = text,
                color = BwColors.Accent,
                fontFamily = BwFontFamily,
                fontSize = BwType.ActionSize,
                fontWeight = BwType.Action,
            )
        }
    }
}

@Composable
fun DirectionGlyph(
    incoming: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.size(14.dp)) {
        val w = size.width
        val h = size.height
        val path = Path()
        path.moveTo(w * 0.50f, h * 0.86f)
        path.lineTo(w * 0.18f, h * 0.50f)
        path.lineTo(w * 0.38f, h * 0.50f)
        path.lineTo(w * 0.38f, h * 0.16f)
        path.lineTo(w * 0.62f, h * 0.16f)
        path.lineTo(w * 0.62f, h * 0.50f)
        path.lineTo(w * 0.82f, h * 0.50f)
        path.close()
        rotate(if (incoming) 45f else 180f) {
            drawPath(path, color)
        }
    }
}

@Composable
fun SyncMark(modifier: Modifier = Modifier) {
    Box(modifier = modifier.size(36.dp)) {
        val disc = 18.dp
        Box(
            modifier =
                Modifier
                    .align(Alignment.BottomStart)
                    .size(disc)
                    .clip(CircleShape)
                    .background(Color(0xFF00AAE0)),
        )
        Box(
            modifier =
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(disc)
                    .clip(CircleShape)
                    .background(Color(0xFF5EEAD4)),
        )
        Box(
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .size(disc)
                    .clip(CircleShape)
                    .background(Color(0xFF38BDF8)),
        )
    }
}
