package io.bluewallet.blueberry.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import io.bluewallet.blueberry.parse.styleBtc

/** Significant digits in [color]; trailing zeros (and " BTC") muted — same split as formatBtc. */
@Composable
fun BtcAmountText(
    sats: Long,
    modifier: Modifier = Modifier,
    plus: Boolean = false,
    color: Color = BwColors.Ink,
    trailingColor: Color = BwColors.InkMuted,
    fontSize: TextUnit = BwType.BodySize,
    fontWeight: FontWeight = BwType.Value,
    maxLines: Int = 1,
) {
    val parts = styleBtc(sats, plus)
    Text(
        text =
            buildAnnotatedString {
                withStyle(SpanStyle(color = color, fontWeight = fontWeight)) {
                    append(parts.significant)
                }
                withStyle(SpanStyle(color = trailingColor, fontWeight = fontWeight)) {
                    append(parts.trailing)
                }
            },
        modifier = modifier,
        fontFamily = BwFontFamily,
        fontSize = fontSize,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}
