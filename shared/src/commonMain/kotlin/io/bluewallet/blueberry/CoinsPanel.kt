package io.bluewallet.blueberry

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import io.bluewallet.blueberry.ui.BwColors
import io.bluewallet.blueberry.ui.BwFontFamily
import io.bluewallet.blueberry.ui.BwType

private val CircleSize = 28.dp
private val CircleStep = 16.dp

@Composable
fun CoinsPanel(
    utxos: List<WalletUtxoRow>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(24.dp)
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 76.dp)
                .clip(shape)
                .background(BwColors.Card, shape)
                .clickable(onClick = onClick)
                .padding(horizontal = 18.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = formatGrouped(utxos.size),
            color = BwColors.Ink,
            fontFamily = BwFontFamily,
            fontSize = 36.sp,
            fontWeight = BwType.Value,
        )
        BoxWithConstraints(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.CenterEnd,
        ) {
            val density = LocalDensity.current
            val maxFit =
                maxUtxoCirclesThatFit(
                    available = constraints.maxWidth.toFloat(),
                    diameter = with(density) { CircleSize.toPx() },
                    step = with(density) { CircleStep.toPx() },
                )
            val n = visibleUtxoCircleCount(utxos.size, maxFit)
            if (n > 0) {
                Box(
                    modifier = Modifier.width(CircleSize + CircleStep * (n - 1)),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    utxos.take(n).forEachIndexed { i, u ->
                        Box(
                            modifier =
                                Modifier
                                    .offset(x = CircleStep * i)
                                    .size(CircleSize)
                                    .zIndex(i.toFloat())
                                    .clip(CircleShape)
                                    .background(Color(utxoCircleArgb(u.key))),
                        )
                    }
                }
            }
        }
    }
}
