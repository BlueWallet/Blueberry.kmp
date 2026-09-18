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
import io.bluewallet.blueberry.boot.AppearancePreference
import io.bluewallet.blueberry.boot.appearanceLabel
import io.bluewallet.blueberry.boot.cycleAppearance
import io.bluewallet.blueberry.boot.loadAlwaysShowSyncProgress
import io.bluewallet.blueberry.boot.loadAppearance
import io.bluewallet.blueberry.boot.saveAlwaysShowSyncProgress
import io.bluewallet.blueberry.storage.Database
import io.bluewallet.blueberry.ui.BwColors
import io.bluewallet.blueberry.ui.BwFontFamily
import io.bluewallet.blueberry.ui.BwSpace
import io.bluewallet.blueberry.ui.BwSwitch
import io.bluewallet.blueberry.ui.BwType
import io.bluewallet.blueberry.ui.MetricCard
import io.bluewallet.blueberry.ui.PillButton
import io.bluewallet.blueberry.ui.ScreenHeader
import io.bluewallet.blueberry.ui.TextAction
import io.bluewallet.blueberry.wallet.WalletSecretKind
import io.bluewallet.blueberry.wallet.parseWalletSecret

@Composable
fun SettingsScreen(
    databaseSize: String,
    secret: String?,
    db: Database,
    onClearStorage: () -> Unit,
    onBack: () -> Unit,
) {
    var confirmClear by remember { mutableStateOf(false) }
    var showSecret by remember { mutableStateOf(false) }
    var alwaysShowSync by remember(db) { mutableStateOf(loadAlwaysShowSyncProgress(db)) }
    var appearance by remember(db) { mutableStateOf(loadAppearance(db)) }
    val onAppearanceChange = LocalAppearanceChange.current
    if (showSecret) {
        SecretScreen(secret = secret, onBack = { showSecret = false })
        return
    }
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(BwColors.Paper)
                .safeDrawingPadding()
                .padding(horizontal = BwSpace.ScreenX, vertical = BwSpace.ScreenY),
        verticalArrangement = Arrangement.spacedBy(BwSpace.Gap),
    ) {
        ScreenHeader(title = "Settings", onBack = onBack)
        ThemeCard(
            appearance = appearance,
            onCycle = {
                val next = cycleAppearance(appearance)
                appearance = next
                onAppearanceChange(next)
            },
        )
        AlwaysShowSyncCard(
            checked = alwaysShowSync,
            onCheckedChange = { on ->
                alwaysShowSync = on
                saveAlwaysShowSyncProgress(db, on)
            },
        )
        SecretCard(secret = secret, onShow = { showSecret = true })
        StorageCard(databaseSize = databaseSize, onClear = { confirmClear = true })
        ClickMeContent()
    }
    if (confirmClear) {
        SettingsClearDialog(
            onDismiss = { confirmClear = false },
            onConfirm = {
                confirmClear = false
                onClearStorage()
            },
        )
    }
}

@Composable
private fun ThemeCard(
    appearance: AppearancePreference,
    onCycle: () -> Unit,
) {
    MetricCard(
        label = "Theme",
        value = appearanceLabel(appearance),
        caption = "Light, dark, or follow the phone",
        modifier = Modifier.fillMaxWidth(),
        trailing = {
            PillButton(text = "Change", onClick = onCycle)
        },
    )
}

@Composable
private fun AlwaysShowSyncCard(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    MetricCard(
        label = "Always show sync progress",
        value = "",
        caption = "Keep Private Sync visible when idle",
        modifier = Modifier.fillMaxWidth(),
        trailing = {
            BwSwitch(checked = checked, onCheckedChange = onCheckedChange)
        },
    )
}

@Composable
private fun SecretCard(
    secret: String?,
    onShow: () -> Unit,
) {
    MetricCard(
        label = "Secret",
        value = secretKindLabel(secret),
        caption = "QR code and text",
        modifier = Modifier.fillMaxWidth(),
        trailing = {
            PillButton(text = "Show", onClick = onShow)
        },
    )
}

@Composable
private fun StorageCard(
    databaseSize: String,
    onClear: () -> Unit,
) {
    MetricCard(
        label = "Storage",
        value = databaseSize,
        caption = "Deletes the local database and restarts onboarding",
        modifier = Modifier.fillMaxWidth(),
        trailing = {
            PillButton(text = "Clear", onClick = onClear)
        },
    )
}

@Composable
private fun SettingsClearDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
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
            PillButton(text = "Clear", onClick = onConfirm)
        },
        dismissButton = {
            TextAction(text = "Cancel", onClick = onDismiss)
        },
        containerColor = BwColors.Card,
    )
}

private fun secretKindLabel(secret: String?): String {
    if (secret == null) return "Missing"
    return when (runCatching { parseWalletSecret(secret).kind }.getOrNull()) {
        WalletSecretKind.MNEMONIC -> "Mnemonic"
        WalletSecretKind.ZPUB -> "zpub"
        WalletSecretKind.WIF -> "WIF"
        WalletSecretKind.ADDRESS -> "Address"
        null -> "Stored"
    }
}
