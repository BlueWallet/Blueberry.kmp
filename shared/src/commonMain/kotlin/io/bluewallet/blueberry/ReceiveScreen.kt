package io.bluewallet.blueberry

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.bluewallet.blueberry.bus.Event
import io.bluewallet.blueberry.bus.FiltersProgressPayload
import io.bluewallet.blueberry.headers.nowMillis
import io.bluewallet.blueberry.parse.snapshotReceiveAddress
import io.bluewallet.blueberry.storage.Database
import io.bluewallet.blueberry.ui.BwColors
import io.bluewallet.blueberry.ui.BwFontFamily
import io.bluewallet.blueberry.ui.BwSpace
import io.bluewallet.blueberry.ui.BwType
import io.bluewallet.blueberry.ui.PillButton
import io.bluewallet.blueberry.wallet.compactFilterFrom
import io.bluewallet.blueberry.wallet.createWallet
import io.github.alexzhirkevich.qrose.rememberQrCodePainter
import kotlinx.coroutines.launch
import kotlin.math.max

fun currentReceiveAddress(runtime: PeersRuntime, db: Database): String? {
    val wallet = runtime.wallet ?: runCatching { createWallet(db) }.getOrNull() ?: return null
    val before = wallet.gaps()
    val address = snapshotReceiveAddress(db, wallet)
    val after = wallet.gaps()
    if (after.external != before.external || after.internal != before.internal) {
        val downloaded = db.filters.count()
        val filterFrom = compactFilterFrom(db)
        val tip = db.headers.tip()
        val total =
            if (tip != null && filterFrom != null) max(0, tip.height - filterFrom + 1)
            else downloaded
        runtime.bus.emit(
            Event.FiltersProgress,
            FiltersProgressPayload(nowMillis(), minOf(downloaded, total), total),
        )
    }
    return address
}

@Composable
fun ReceiveScreen(
    runtime: PeersRuntime,
    db: Database,
    onBack: () -> Unit,
) {
    var address by remember(runtime, db) { mutableStateOf(currentReceiveAddress(runtime, db)) }
    var copied by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    DisposableEffect(runtime.walletTxsStore) {
        val off = runtime.walletTxsStore.subscribe {
            scope.launch { address = currentReceiveAddress(runtime, db) }
        }
        onDispose { off() }
    }
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
                text = "Receive",
                color = BwColors.Ink,
                fontFamily = BwFontFamily,
                fontSize = BwType.HeroSize,
                fontWeight = BwType.Hero,
                modifier = Modifier.weight(1f),
            )
            PillButton(text = "Back", onClick = onBack)
        }
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(BwSpace.Gap, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val shown = address
            if (shown != null) {
                Image(
                    painter = rememberQrCodePainter(shown),
                    contentDescription = "Receive address QR code",
                    modifier = Modifier.size(200.dp),
                )
                Text(
                    text = shown,
                    color = BwColors.Ink,
                    fontFamily = BwFontFamily,
                    fontSize = BwType.BodySize,
                    fontWeight = BwType.Body,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            clipboard.setText(AnnotatedString(shown))
                            copied = true
                        },
                )
                if (copied) {
                    Text(
                        text = "Copied",
                        color = BwColors.Success,
                        fontFamily = BwFontFamily,
                        fontSize = BwType.CaptionSize,
                        fontWeight = BwType.Caption,
                    )
                }
            } else {
                Text(
                    text = "No unused address",
                    color = BwColors.InkMuted,
                    fontFamily = BwFontFamily,
                    fontSize = BwType.BodySize,
                    fontWeight = BwType.Body,
                )
            }
        }
    }
}
