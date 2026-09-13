package io.bluewallet.blueberry.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.unit.sp

/** Light rounded list — sister-project "Estimated fee". */
@Composable
fun StatusList(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = RoundedCornerShape(BwSpace.Radius)
    Box(
        modifier =
            modifier
                .clip(shape)
                .background(BwColors.Card, shape)
                .border(BwSpace.Hairline, BwColors.Border, shape)
                .padding(vertical = 2.dp),
        content = content,
    )
}

@Composable
fun StatusDivider() {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp)
                .height(BwSpace.Hairline)
                .background(BwColors.Border),
    )
}

@Composable
fun StatusRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    secondary: String? = null,
    valueColor: Color = BwColors.Link,
    dotColor: Color = BwColors.Accent,
    valueContent: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(dotColor),
        )
        Text(
            text = label,
            color = BwColors.Ink,
            fontFamily = BwFontFamily,
            fontSize = BwType.BodySize,
            fontWeight = BwType.Body,
            maxLines = 1,
        )
        if (secondary != null) {
            Text(
                text = secondary,
                color = BwColors.InkMuted,
                fontFamily = BwFontFamily,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        } else {
            Box(modifier = Modifier.weight(1f))
        }
        if (valueContent != null) {
            valueContent()
        } else {
            Text(
                text = value,
                color = valueColor,
                fontFamily = BwFontFamily,
                fontSize = BwType.BodySize,
                fontWeight = BwType.Value,
                maxLines = 1,
            )
        }
    }
}
