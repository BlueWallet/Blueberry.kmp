package io.bluewallet.blueberry.ui

import androidx.compose.foundation.layout.Column
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
    subtitle: String? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = BwColors.Ink,
                fontFamily = BwFontFamily,
                fontSize = BwType.HeroSize,
                fontWeight = BwType.Hero,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    color = BwColors.InkSecondary,
                    fontFamily = BwFontFamily,
                    fontSize = BwType.CaptionSize,
                    fontWeight = BwType.Caption,
                )
            }
        }
        TextAction(text = "Back", onClick = onBack)
    }
}
