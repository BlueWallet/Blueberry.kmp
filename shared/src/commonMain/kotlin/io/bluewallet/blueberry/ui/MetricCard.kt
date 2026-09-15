package io.bluewallet.blueberry.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** White metric block — sister-project "Buy price average". */
@Composable
fun MetricCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    caption: String? = null,
    valueColor: Color = BwColors.Ink,
    trailing: @Composable (RowScope.() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(BwSpace.Radius)
    Row(
        modifier =
            modifier
                .clip(shape)
                .background(BwColors.Card, shape)
                .border(BwSpace.Hairline, BwColors.Border, shape)
                .padding(BwSpace.Card),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BwSpace.Gap),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = BwColors.InkSecondary,
                fontFamily = BwFontFamily,
                fontSize = BwType.LabelSize,
                fontWeight = BwType.Label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = value,
                color = valueColor,
                fontFamily = BwFontFamily,
                fontSize = BwType.ValueSize,
                fontWeight = BwType.Value,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (caption != null) {
                Text(
                    text = caption,
                    color = BwColors.InkMuted,
                    fontFamily = BwFontFamily,
                    fontSize = BwType.CaptionSize,
                    fontWeight = BwType.Caption,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) trailing()
    }
}

@Composable
fun ProgressMetricCard(
    label: String,
    value: String,
    percent: Int,
    modifier: Modifier = Modifier,
    caption: String? = null,
    valueColor: Color = BwColors.Ink,
) {
    val shape = RoundedCornerShape(BwSpace.Radius)
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(shape)
                .background(BwColors.Section, shape)
                .border(BwSpace.Hairline, BwColors.Border, shape)
                .padding(BwSpace.Card),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = label,
            color = BwColors.InkSecondary,
            fontFamily = BwFontFamily,
            fontSize = BwType.LabelSize,
            fontWeight = BwType.Label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = value,
            color = valueColor,
            fontFamily = BwFontFamily,
            fontSize = BwType.ValueSize,
            fontWeight = BwType.Value,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (caption != null) {
            Text(
                text = caption,
                color = BwColors.InkMuted,
                fontFamily = BwFontFamily,
                fontSize = BwType.CaptionSize,
                fontWeight = BwType.Caption,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        HorizontalProgressBar(percent = percent)
    }
}
