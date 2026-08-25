package io.bluewallet.blueberry

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.bluewallet.blueberry.ui.BwColors
import io.bluewallet.blueberry.ui.BwFontFamily
import io.bluewallet.blueberry.ui.BwSpace
import io.bluewallet.blueberry.ui.BwType
import io.bluewallet.blueberry.ui.MetricCard
import io.bluewallet.blueberry.ui.PillButton

@Composable
fun SettingsScreen(
    databaseSize: String,
    onClearStorage: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BwColors.Paper)
            .safeDrawingPadding()
            .padding(horizontal = BwSpace.ScreenX, vertical = BwSpace.ScreenY),
        verticalArrangement = Arrangement.spacedBy(BwSpace.Gap),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Settings",
                color = BwColors.Ink,
                fontFamily = BwFontFamily,
                fontSize = BwType.HeroSize,
                fontWeight = BwType.Hero,
                modifier = Modifier.weight(1f),
            )
            PillButton(text = "Back", onClick = onBack)
        }
        MetricCard(
            label = "Storage",
            value = databaseSize,
            caption = "Deletes the local database and restarts onboarding",
            modifier = Modifier.fillMaxWidth(),
            trailing = {
                PillButton(text = "Clear", onClick = onClearStorage)
            },
        )
        ClickMeContent()
    }
}
