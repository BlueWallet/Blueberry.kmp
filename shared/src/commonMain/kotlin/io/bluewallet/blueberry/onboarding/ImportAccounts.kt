package io.bluewallet.blueberry.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.bluewallet.blueberry.AccountsScan
import io.bluewallet.blueberry.AccountsScanState
import io.bluewallet.blueberry.QrFrameScanner
import io.bluewallet.blueberry.ui.BwColors
import io.bluewallet.blueberry.ui.BwFontFamily
import io.bluewallet.blueberry.ui.BwType
import io.bluewallet.blueberry.ui.PlatformBodyStyle
import io.bluewallet.blueberry.wallet.UrAccount
import io.bluewallet.blueberry.wallet.receiveLabel

/** Camera for Import: a plain QR fills the field; an account export, single or animated, hands over its accounts. */
@Composable
internal fun ImportScanner(
    onText: (String) -> Unit,
    onAccounts: (List<UrAccount>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scan = remember { AccountsScan() }
    var state by remember { mutableStateOf(scan.state) }
    val scanning = state as? AccountsScanState.Scanning
    Column(modifier = modifier.background(BwColors.Paper)) {
        Text(
            text =
                when {
                    scanning?.percent != null -> "Scanning animated QR… ${scanning.percent}%"
                    scanning?.note != null -> scanning.note
                    else -> "Point the camera at a QR code"
                },
            color = BwColors.InkMuted,
            fontFamily = BwFontFamily,
            fontSize = BwType.CaptionSize,
            fontWeight = BwType.Caption,
        )
        QrFrameScanner(
            onText = { text ->
                val next = scan.accept(text)
                state = next
                when (next) {
                    is AccountsScanState.Text -> onText(next.text)
                    is AccountsScanState.Accounts -> onAccounts(next.accounts)
                    is AccountsScanState.Scanning -> Unit
                }
                next !is AccountsScanState.Scanning
            },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
    }
}

/** One row per scanned account; [picked] is highlighted (Continue imports it), and tapping a row imports that row. */
@Composable
internal fun AccountPicker(
    accounts: List<UrAccount>,
    picked: UrAccount,
    enabled: Boolean,
    onPick: (UrAccount) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = "Choose the account to import", style = PlatformBodyStyle())
        for (account in accounts) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(if (account == picked) BwColors.AccentSoft else BwColors.Section, RoundedCornerShape(8.dp))
                        .clickable(enabled = enabled) { onPick(account) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text(text = account.scriptType.receiveLabel(), style = PlatformBodyStyle(), color = BwColors.Ink)
                Text(
                    text = "${account.path.asString('\'')} · ${account.fingerprint.toString(16).padStart(8, '0')}",
                    color = BwColors.InkMuted,
                    fontFamily = BwFontFamily,
                    fontSize = BwType.CaptionSize,
                )
            }
        }
    }
}
