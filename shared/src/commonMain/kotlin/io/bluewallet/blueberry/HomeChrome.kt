package io.bluewallet.blueberry

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import blueberry.shared.generated.resources.Res
import blueberry.shared.generated.resources.blueberry_mark
import blueberry.shared.generated.resources.receive_icon
import blueberry.shared.generated.resources.send_icon
import io.bluewallet.blueberry.ui.BwColors
import io.bluewallet.blueberry.ui.TintedPillButton
import org.jetbrains.compose.resources.painterResource

@Composable
expect fun HomeOverflowButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
)

@Composable
internal fun HomeOverflowDots(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(BwColors.Card)
                .border(1.dp, BwColors.BarTrack, CircleShape)
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
        TintedPillButton(
            text = "Receive",
            onClick = onReceive,
            modifier = Modifier.weight(1f),
            icon = painterResource(Res.drawable.receive_icon),
        )
        TintedPillButton(
            text = "Send",
            onClick = onSend,
            modifier = Modifier.weight(1f),
            icon = painterResource(Res.drawable.send_icon),
        )
    }
}

@Composable
fun SyncMark(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(Res.drawable.blueberry_mark),
        contentDescription = null,
        modifier = modifier.size(36.dp),
        contentScale = ContentScale.Fit,
    )
}
