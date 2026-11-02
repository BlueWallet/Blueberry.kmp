package io.bluewallet.blueberry.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import io.bluewallet.blueberry.addressGroups
import io.bluewallet.blueberry.shortAddress

/** Hero amount: muted B and trailing zeros, significant digits in Ink. */
@Composable
fun AmountHero(
    sats: Long,
    modifier: Modifier = Modifier,
    size: TextUnit = BwType.DisplaySize,
) {
    BtcAmountText(
        sats = sats,
        modifier = modifier,
        color = BwColors.Ink,
        trailingColor = BwColors.InkMuted,
        fontSize = size,
        fontWeight = BwType.Hero,
        roundedDigits = true,
    )
}

/** "To Concert tickets · bc1q2d…welh". */
@Composable
fun ToPill(
    label: String,
    address: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .background(BwColors.Section, RoundedCornerShape(50))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("To", color = BwColors.InkQuiet, fontFamily = BwFontFamily, fontSize = BwType.LabelSize)
        Text(
            text = listOf(label.trim(), shortAddress(address)).filter { it.isNotEmpty() }.joinToString(" · "),
            color = BwColors.Ink,
            fontFamily = BwFontFamily,
            fontSize = BwType.LabelSize,
            fontWeight = BwType.Label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Address in monospace groups of four, alternating Ink and InkQuiet. */
@Composable
fun AddressGroups(
    address: String,
    modifier: Modifier = Modifier,
) {
    val ink = BwColors.Ink
    val quiet = BwColors.InkQuiet
    Text(
        text =
            buildAnnotatedString {
                addressGroups(address).forEachIndexed { i, group ->
                    if (i > 0) append(' ')
                    withStyle(SpanStyle(color = if (i % 2 == 0) ink else quiet)) { append(group) }
                }
            },
        modifier = modifier,
        fontFamily = FontFamily.Monospace,
        fontSize = BwType.BodySize,
    )
}

/** Small caption under a large title, e.g. "Step 2 of 4". */
@Composable
fun StepCaption(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier,
        color = BwColors.InkQuiet,
        fontFamily = BwFontFamily,
        fontSize = BwType.BodySize,
    )
}
