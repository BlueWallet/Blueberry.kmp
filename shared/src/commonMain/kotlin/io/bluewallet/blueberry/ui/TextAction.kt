package io.bluewallet.blueberry.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Header text action — Settings, Back, Cancel. Not a primary pill. */
@Composable
fun TextAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier.height(32.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = BwColors.Action),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
    ) {
        Text(
            text = text,
            fontFamily = BwFontFamily,
            fontSize = BwType.ActionSize,
            fontWeight = BwType.Action,
        )
    }
}
