package io.bluewallet.blueberry

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.bluewallet.blueberry.ui.BwColors
import io.bluewallet.blueberry.ui.BwFontFamily
import io.bluewallet.blueberry.ui.BwType

private val PanelRadius = 28.dp

/** Bottom action for coin pickers: privacy notice above the primary button, in one rounded panel. */
@Composable
internal fun CoinActionPanel(
    notice: PrivacyNotice?,
    modifier: Modifier = Modifier,
    button: @Composable () -> Unit,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(PanelRadius))
                .background(BwColors.PanelFill)
                .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (notice != null) PrivacyNoticeText(notice, Modifier.padding(horizontal = 8.dp))
        button()
    }
}

@Composable
private fun PrivacyNoticeText(
    notice: PrivacyNotice,
    modifier: Modifier = Modifier,
) {
    val color =
        when (notice.kind) {
            PrivacyNoticeKind.Hint -> BwColors.InkQuiet
            PrivacyNoticeKind.Safe -> BwColors.Success
            PrivacyNoticeKind.Warning -> BwColors.Warning
        }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(notice.message, color = color, fontFamily = BwFontFamily, fontSize = BwType.LabelSize)
        notice.costLine?.let { Text(it, color = BwColors.Warning, fontFamily = BwFontFamily, fontSize = BwType.CaptionSize) }
    }
}
