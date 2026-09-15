package io.bluewallet.blueberry

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.bluewallet.blueberry.storage.Database
import io.bluewallet.blueberry.ui.BtcAmountText
import io.bluewallet.blueberry.ui.BwColors
import io.bluewallet.blueberry.ui.BwFontFamily
import io.bluewallet.blueberry.ui.BwSpace
import io.bluewallet.blueberry.ui.BwType
import io.bluewallet.blueberry.ui.HorizontalProgressBar
import io.bluewallet.blueberry.ui.ScreenHeader
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
    var sheetRow by remember { mutableStateOf<CoinsRowModel?>(null) }
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    DisposableEffect(runtime.walletTxsStore) {
        val off = runtime.walletTxsStore.subscribe { scope.launch { snap = runtime.walletTxsStore.get() } }
        onDispose { off() }
    }
    val rows = coinsRows(snap.utxos)
    val editing = rows.firstOrNull { it.key == editingKey }
    LaunchedEffect(editing) {
        if (editing != null) sheetRow = editing
    }
    val sheetOpen = editingKey != null
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(BwColors.Paper)
                    .safeDrawingPadding()
                    .padding(horizontal = BwSpace.ScreenX, vertical = BwSpace.ScreenY),
            verticalArrangement = Arrangement.spacedBy(BwSpace.Gap),
        ) {
            ScreenHeader(title = "Coins", subtitle = "UTXO List", onBack = onBack)
            if (rows.isEmpty()) {
                Text(
                    text = "No UTXOs",
                    color = BwColors.InkMuted,
                    fontFamily = BwFontFamily,
                    fontSize = BwType.BodySize,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(rows, key = { it.key }) { row ->
                        Row(
                            modifier =
                                Modifier.listRowPanel(onClick = {
                                    sheetRow = row
                                    editingKey = row.key
                                    draft = row.name.orEmpty()
                                }),
                            horizontalArrangement = Arrangement.spacedBy(BwSpace.Gap),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier =
                                    Modifier
                                        .size(CircleSize)
                                        .clip(CircleShape)
                                        .background(Color(row.circleArgb)),
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                BtcAmountText(sats = row.valueSats, color = BwColors.Ink)
                                val caption = coinsRowCaption(row.address, row.ageLabel, row.name)
                                if (caption != null) {
                                    Text(
                                        text = caption,
                                        color = BwColors.InkMuted,
                                        fontFamily = BwFontFamily,
                                        fontSize = BwType.CaptionSize,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
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
                                    modifier =
                                        Modifier
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
        AnimatedVisibility(
            visible = sheetOpen,
            enter = fadeIn(animationSpec = tween(220)),
            exit = fadeOut(animationSpec = tween(180)),
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.32f))
                        .clickable { editingKey = null },
            )
        }
        AnimatedVisibility(
            visible = sheetOpen,
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            enter =
                slideInVertically(
                    animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing),
                    initialOffsetY = { it },
                ) + fadeIn(animationSpec = tween(200)),
            exit =
                slideOutVertically(
                    animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
                    targetOffsetY = { it },
                ) + fadeOut(animationSpec = tween(160)),
        ) {
            val row = sheetRow ?: return@AnimatedVisibility
            CoinsUtxoSheet(
                row = row,
                draft = draft,
                onDraft = { draft = it },
                onCopy = { clipboard.setText(AnnotatedString(it)) },
                onDone = { save ->
                    if (save) {
                        val wallet = runtime.wallet ?: return@CoinsUtxoSheet
                        setUtxoName(db, wallet, runtime.walletTxsStore, row.key, draft)
                    }
                    editingKey = null
                },
            )
        }
    }
}
