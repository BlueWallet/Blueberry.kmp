package io.bluewallet.blueberry

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.bluewallet.blueberry.ui.BwColors
import io.bluewallet.blueberry.ui.BwFontFamily
import io.bluewallet.blueberry.ui.BwSpace
import io.bluewallet.blueberry.ui.BwType
import io.bluewallet.blueberry.ui.MetricCard
import io.bluewallet.blueberry.ui.PillButton
import io.bluewallet.blueberry.ui.ScreenHeader
import io.bluewallet.blueberry.ui.TextAction

@Composable
fun SettingsScreen(
    databaseSize: String,
    onClearStorage: () -> Unit,
    onBack: () -> Unit,
) {
    var confirmClear by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BwColors.Paper)
            .safeDrawingPadding()
            .padding(horizontal = BwSpace.ScreenX, vertical = BwSpace.ScreenY),
        verticalArrangement = Arrangement.spacedBy(BwSpace.Gap),
    ) {
        ScreenHeader(title = "Settings", onBack = onBack)
        MetricCard(
            label = "Storage",
            value = databaseSize,
            caption = "Deletes the local database and restarts onboarding",
            modifier = Modifier.fillMaxWidth(),
            trailing = {
                PillButton(text = "Clear", onClick = { confirmClear = true })
            },
        )
        ClickMeContent()
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = {
                Text(
                    text = "Clear database?",
                    color = BwColors.Ink,
                    fontFamily = BwFontFamily,
                    fontWeight = BwType.Value,
                )
            },
            text = {
                Text(
                    text = "This deletes the local database and restarts onboarding.",
                    color = BwColors.InkSecondary,
                    fontFamily = BwFontFamily,
                    fontSize = BwType.BodySize,
                    fontWeight = BwType.Caption,
                )
            },
            confirmButton = {
                PillButton(
                    text = "Clear",
                    onClick = {
                        confirmClear = false
                        onClearStorage()
                    },
                )
            },
            dismissButton = {
                TextAction(text = "Cancel", onClick = { confirmClear = false })
            },
            containerColor = BwColors.Card,
        )
    }
}
