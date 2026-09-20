package io.bluewallet.blueberry

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.bluewallet.blueberry.ui.BtcAmountText
import io.bluewallet.blueberry.ui.BwColors
import io.bluewallet.blueberry.ui.BwFontFamily
import io.bluewallet.blueberry.ui.BwSpace
import io.bluewallet.blueberry.ui.BwType
import kotlin.math.abs

@Composable
fun Modifier.listRowPanel(onClick: () -> Unit): Modifier {
    val shape = RoundedCornerShape(BwSpace.Radius)
    val card = BwColors.Card
    return this
        .fillMaxWidth()
        .clip(shape)
        .background(card, shape)
        .clickable(onClick = onClick)
        .padding(horizontal = 14.dp, vertical = 14.dp)
}

@Composable
fun HomeTxBubble(
    tx: WalletTxRow,
    onClick: () -> Unit,
) {
    val incoming = tx.netDeltaSats >= 0
    val muted = txListSecondaryMuted(tx.paymentLabel, tx.utxoLabel)
    Row(
        modifier = Modifier.listRowPanel(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(BwColors.Accent),
            contentAlignment = Alignment.Center,
        ) {
            DirectionGlyph(incoming = incoming, color = BwColors.AccentSoft)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = rememberBlockTimeLabel(tx.blockTimeS)?.trim() ?: tx.timeLabel.trim(),
                color = BwColors.Ink,
                fontFamily = BwFontFamily,
                fontSize = BwType.BodySize,
                fontWeight = BwType.Body,
                maxLines = 1,
            )
            Text(
                text = txListSecondary(tx.shortTxid, tx.paymentLabel, tx.utxoLabel),
                color = if (muted) BwColors.InkMuted else BwColors.Ink,
                fontFamily = BwFontFamily,
                fontSize = if (muted) BwType.CaptionSize else BwType.BodySize,
                fontWeight = if (muted) BwType.Caption else BwType.Body,
                maxLines = 1,
            )
        }
        BtcAmountText(
            sats = abs(tx.netDeltaSats),
            color = if (incoming) BwColors.Accent else BwColors.Danger.copy(alpha = 0.7f),
        )
    }
}
