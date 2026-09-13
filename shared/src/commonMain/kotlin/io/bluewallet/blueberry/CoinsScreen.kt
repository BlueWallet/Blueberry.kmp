package io.bluewallet.blueberry

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.bluewallet.blueberry.storage.Database
import io.bluewallet.blueberry.ui.BtcAmountText
import io.bluewallet.blueberry.ui.BwColors
import io.bluewallet.blueberry.ui.BwFontFamily
import io.bluewallet.blueberry.ui.BwSpace
import io.bluewallet.blueberry.ui.BwType
import io.bluewallet.blueberry.ui.HorizontalProgressBar
import io.bluewallet.blueberry.ui.PillButton
import io.bluewallet.blueberry.ui.ScreenHeader
import io.bluewallet.blueberry.ui.TextAction
import kotlinx.coroutines.launch

private val CircleSize = 22.dp

@Composable
fun CoinsScreen(
    runtime: PeersRuntime,
    db: Database,
    onBack: () -> Unit,
) {
    var snap by remember { mutableStateOf(runtime.walletTxsStore.get()) }
    var editingKey by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    DisposableEffect(runtime.walletTxsStore) {
        val off = runtime.walletTxsStore.subscribe { scope.launch { snap = runtime.walletTxsStore.get() } }
        onDispose { off() }
    }
    val rows = coinsRows(snap.utxos)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BwColors.Paper)
            .safeDrawingPadding()
            .padding(horizontal = BwSpace.ScreenX, vertical = BwSpace.ScreenY),
        verticalArrangement = Arrangement.spacedBy(BwSpace.Gap),
    ) {
        ScreenHeader(title = "Coins", onBack = onBack)
        if (rows.isEmpty()) {
            Text(
                text = "No UTXOs",
                color = BwColors.InkMuted,
                fontFamily = BwFontFamily,
                fontSize = BwType.BodySize,
            )
        } else {
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(rows, key = { it.key }) { row ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                editingKey = row.key
                                draft = row.name.orEmpty()
                            }
                            .padding(vertical = BwSpace.Gap),
                        horizontalArrangement = Arrangement.spacedBy(BwSpace.Gap),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(CircleSize)
                                .clip(CircleShape)
                                .background(Color(row.circleArgb)),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            BtcAmountText(sats = row.valueSats, color = BwColors.Ink)
                            val caption = coinsRowCaption(row.ageLabel, row.name)
                            if (caption != null) {
                                Text(
                                    text = caption,
                                    color = BwColors.InkMuted,
                                    fontFamily = BwFontFamily,
                                    fontSize = BwType.CaptionSize,
                                )
                            }
                            HorizontalProgressBar(percent = row.barPercent)
                        }
                        if (row.isChange) {
                            Text(
                                text = "change",
                                color = BwColors.InkSecondary,
                                fontFamily = BwFontFamily,
                                fontSize = BwType.CaptionSize,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(BwColors.Card)
                                    .border(BwSpace.Hairline, BwColors.Border, RoundedCornerShape(50))
                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
            }
        }
    }
    val key = editingKey
    if (key != null) {
        AlertDialog(
            onDismissRequest = { editingKey = null },
            title = {
                Text(
                    text = "Label",
                    color = BwColors.Ink,
                    fontFamily = BwFontFamily,
                    fontWeight = BwType.Value,
                )
            },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("Label") },
                )
            },
            confirmButton = {
                PillButton(
                    text = "Save",
                    onClick = {
                        val wallet = runtime.wallet ?: return@PillButton
                        setUtxoName(db, wallet, runtime.walletTxsStore, key, draft)
                        editingKey = null
                    },
                )
            },
            dismissButton = {
                TextAction(text = "Cancel", onClick = { editingKey = null })
            },
            containerColor = BwColors.Card,
        )
    }
}
