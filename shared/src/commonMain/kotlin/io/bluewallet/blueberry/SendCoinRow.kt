package io.bluewallet.blueberry

import androidx.compose.foundation.background
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
import androidx.compose.ui.unit.dp
import io.bluewallet.blueberry.ui.BwColors
import io.bluewallet.blueberry.ui.BwFontFamily
import io.bluewallet.blueberry.ui.BwSpace
import io.bluewallet.blueberry.ui.BwType
import io.bluewallet.blueberry.ui.Chevron

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

/** Card row: title, value on the right, then a chevron. Null [onClick] when a parent owns the tap. */
@Composable
internal fun SendChevronRow(
    title: String,
    value: String,
    valueColor: Color,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CardTitle(title, Modifier.weight(1f))
        Text(
            text = value,
            color = valueColor,
            fontFamily = BwFontFamily,
            fontSize = BwType.BodySize,
            fontWeight = BwType.Body,
        )
        Chevron(modifier = Modifier.padding(start = 6.dp))
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
