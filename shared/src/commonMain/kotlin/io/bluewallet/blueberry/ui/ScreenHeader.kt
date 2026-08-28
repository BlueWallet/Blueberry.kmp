package io.bluewallet.blueberry.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/** Title plus header Back — same text action as home Settings. */
@Composable
fun ScreenHeader(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            color = BwColors.Ink,
            fontFamily = BwFontFamily,
            fontSize = BwType.HeroSize,
            fontWeight = BwType.Hero,
            modifier = Modifier.weight(1f),
        )
        TextAction(text = "Back", onClick = onBack)
    }
}
