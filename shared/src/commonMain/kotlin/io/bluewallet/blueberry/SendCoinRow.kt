package io.bluewallet.blueberry

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.bluewallet.blueberry.ui.BwColors
import io.bluewallet.blueberry.ui.BwSpace

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
