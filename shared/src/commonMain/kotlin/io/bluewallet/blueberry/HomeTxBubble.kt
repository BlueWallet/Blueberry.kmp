package io.bluewallet.blueberry

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import blueberry.shared.generated.resources.Res
import blueberry.shared.generated.resources.receive_icon
import blueberry.shared.generated.resources.send_icon
import io.bluewallet.blueberry.ui.BtcAmountText
import io.bluewallet.blueberry.ui.BwColors
import io.bluewallet.blueberry.ui.BwFontFamily
import io.bluewallet.blueberry.ui.BwSpace
import io.bluewallet.blueberry.ui.BwType
import io.bluewallet.blueberry.ui.Pressable
import org.jetbrains.compose.resources.painterResource
import kotlin.math.abs

private val TxRowLetterSpacing = (-0.45).sp

private val TxRowText =
    TextStyle(
        fontFamily = BwFontFamily,
        fontWeight = FontWeight.Medium,
        letterSpacing = TxRowLetterSpacing,
        lineHeightStyle =
            LineHeightStyle(
                alignment = LineHeightStyle.Alignment.Center,
                trim = LineHeightStyle.Trim.Both,
            ),
    )

@Composable
fun Modifier.listRowPanel(): Modifier {
    val shape = RoundedCornerShape(BwSpace.Radius)
    val card = BwColors.Card
    return this
        .fillMaxWidth()
        .clip(shape)
        .background(card, shape)
        .padding(horizontal = 14.dp, vertical = 14.dp)
}

@Composable
fun HomeBroadcastRow(
    row: BroadcastListRow,
    onClick: () -> Unit,
) {
    Pressable(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        role = Role.Button,
    ) {
        Row(
            modifier = Modifier.listRowPanel(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Image(
                painter = painterResource(Res.drawable.send_icon),
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                colorFilter = ColorFilter.tint(BwColors.Ink),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = row.status,
                    color = if (row.status == "Not sent") BwColors.Danger else BwColors.Ink,
                    style = TxRowText.copy(fontSize = BwType.BodySize, lineHeight = BwType.BodySize),
                    maxLines = 1,
                )
                Text(
                    text = row.title,
                    color = BwColors.InkMuted,
                    style = TxRowText.copy(fontSize = BwType.CaptionSize, lineHeight = BwType.CaptionSize),
                    maxLines = 1,
                )
            }
            BtcAmountText(
                sats = row.paidSats,
                color = BwColors.Ink,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = TxRowLetterSpacing,
            )
        }
    }
}

@Composable
fun HomeTxBubble(
    tx: WalletTxRow,
    onClick: () -> Unit,
) {
    val incoming = tx.netDeltaSats >= 0
    // The arrow and the amount share one colour: accent for incoming, ink for outgoing.
    val directionColor = if (incoming) BwColors.Accent else BwColors.Ink
    val muted = txListSecondaryMuted(tx.paymentLabel, tx.utxoLabel, tx.addressLabel)
    Pressable(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        role = Role.Button,
    ) {
        Row(
            modifier = Modifier.listRowPanel(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Image(
                painter = painterResource(if (incoming) Res.drawable.receive_icon else Res.drawable.send_icon),
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                colorFilter = ColorFilter.tint(directionColor),
            )
            Column(
                modifier = Modifier.weight(1f),
                // Trimmed line boxes still include font leading. This leaves an 8dp gap.
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                val secondarySize = if (muted) BwType.CaptionSize else BwType.BodySize
                Text(
                    text = txListDateLabel(rememberBlockTimeLabel(tx.blockTimeS) ?: tx.timeLabel),
                    color = BwColors.Ink,
                    style = TxRowText.copy(fontSize = BwType.BodySize, lineHeight = BwType.BodySize),
                    maxLines = 1,
                )
                Text(
                    text = txListSecondary(tx.shortTxid, tx.paymentLabel, tx.utxoLabel, tx.addressLabel),
                    color = if (muted) BwColors.InkMuted else BwColors.Ink,
                    style = TxRowText.copy(fontSize = secondarySize, lineHeight = secondarySize),
                    maxLines = 1,
                )
            }
            PrivateSendAmount(tx = tx, amountColor = directionColor)
        }
    }
}

@Composable
private fun PrivateSendAmount(
    tx: WalletTxRow,
    amountColor: Color,
) {
    Column(
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (tx.privateSend) {
            Text(
                text = "private send",
                color = BwColors.Accent,
                fontFamily = BwFontFamily,
                fontSize = BwType.CaptionSize,
                fontWeight = BwType.Caption,
                modifier =
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(BwColors.Accent.copy(alpha = 0.16f))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        BtcAmountText(
            sats = abs(tx.netDeltaSats),
            color = amountColor,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = TxRowLetterSpacing,
        )
    }
}
