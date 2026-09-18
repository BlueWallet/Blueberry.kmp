package io.bluewallet.blueberry.ui

import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Material3 switch tinted to [BwColors] — same control on Android, iOS, and desktop. */
@Composable
fun BwSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        colors =
            SwitchDefaults.colors(
                checkedThumbColor = BwColors.Ink,
                checkedTrackColor = BwColors.Accent,
                uncheckedThumbColor = BwColors.Ink,
                uncheckedTrackColor = BwColors.BarTrack,
                uncheckedBorderColor = BwColors.BarTrack,
            ),
    )
}
