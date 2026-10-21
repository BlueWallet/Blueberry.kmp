package io.bluewallet.blueberry

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.bluewallet.blueberry.ui.BtcAmountText
import io.bluewallet.blueberry.ui.BwCheckbox
import io.bluewallet.blueberry.ui.BwColors
import io.bluewallet.blueberry.ui.BwFontFamily
import io.bluewallet.blueberry.ui.BwSpace
import io.bluewallet.blueberry.ui.BwType

/** Rounded Card surface used by every send step. */
@Composable
internal fun SendCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(BwSpace.Radius))
                .background(BwColors.Card)
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(BwSpace.Gap),
        content = content,
    )
}

/** Selectable coin: check, colour dot, amount, name or address, age, change tag. */
@Composable
internal fun CoinRow(
    coin: WalletUtxoRow,
    selected: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (selected) BwColors.AccentSoft else Color.Transparent)
                .clickable(onClick = onToggle)
                .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(BwSpace.Gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BwCheckbox(checked = selected, onCheckedChange = { onToggle() })
        CoinDot(utxoCircleArgb(coin.key))
        Column(modifier = Modifier.weight(1f)) {
            BtcAmountText(sats = coin.valueSats, fontSize = BwType.BodySize)
            val title = utxoRowTitle(coin.name, coin.address)?.text
            val caption = listOfNotNull(title, utxoRowAge(coin.ageLabel)).joinToString(" · ")
            if (caption.isNotEmpty()) {
                Text(
                    text = caption,
                    color = BwColors.InkQuiet,
                    fontFamily = BwFontFamily,
                    fontSize = BwType.CaptionSize,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (coin.isChange) ChangeTag()
    }
}

@Composable
internal fun CoinDot(
    argb: Long,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(Color(argb)),
    )
}

@Composable
private fun ChangeTag() {
    Text(
        text = "Change",
        color = BwColors.InkQuiet,
        fontFamily = BwFontFamily,
        fontSize = BwType.CaptionSize,
        modifier =
            Modifier
                .clip(RoundedCornerShape(50))
                .border(BwSpace.Hairline, BwColors.Section, RoundedCornerShape(50))
                .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}
