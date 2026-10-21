package io.bluewallet.blueberry

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.bluewallet.blueberry.ui.BtcAmountText
import io.bluewallet.blueberry.ui.BwColors
import io.bluewallet.blueberry.ui.BwFontFamily

/** Frozen value sits outside the spendable headline, so it is named separately. */
@Composable
internal fun FrozenBalancePill(frozenSats: Long) {
    if (frozenSats <= 0L) return
    Row(
        modifier =
            Modifier
                .clip(RoundedCornerShape(50))
                .background(BwColors.Section)
                .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BtcAmountText(
            sats = frozenSats,
            color = BwColors.Ink,
            trailingColor = BwColors.InkSecondary,
            fontSize = 13.sp,
        )
        Text(
            text = " frozen",
            color = BwColors.InkSecondary,
            fontFamily = BwFontFamily,
            fontSize = 13.sp,
        )
    }
}
