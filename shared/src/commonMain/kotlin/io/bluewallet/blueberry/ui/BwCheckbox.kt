package io.bluewallet.blueberry.ui

import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Material3 checkbox tinted to [BwColors] — same control on Android, iOS, and desktop. */
@Composable
fun BwCheckbox(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Checkbox(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        colors =
            CheckboxDefaults.colors(
                checkedColor = BwColors.Accent,
                uncheckedColor = BwColors.InkMuted,
                checkmarkColor = BwColors.OnAccent,
            ),
    )
}
