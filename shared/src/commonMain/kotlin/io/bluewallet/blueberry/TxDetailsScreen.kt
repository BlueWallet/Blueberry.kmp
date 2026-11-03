package io.bluewallet.blueberry

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import io.bluewallet.blueberry.headers.nowMillis
import io.bluewallet.blueberry.parse.blockFeeRateLabel
import io.bluewallet.blueberry.parse.blockTxCount
import io.bluewallet.blueberry.storage.Database
import io.bluewallet.blueberry.ui.BwColors
import io.bluewallet.blueberry.ui.BwFontFamily
import io.bluewallet.blueberry.ui.BwSpace
import io.bluewallet.blueberry.ui.BwType
import io.bluewallet.blueberry.ui.PillButton
import io.bluewallet.blueberry.ui.PlainPillButton
import io.bluewallet.blueberry.ui.PlatformScreenHeader
import io.bluewallet.blueberry.ui.PlatformScrollEdge
import io.bluewallet.blueberry.ui.TextAction
import io.bluewallet.blueberry.ui.TintedPillButton
import io.bluewallet.blueberry.ui.alwaysBounceVerticalScroll
import io.bluewallet.blueberry.ui.dismissKeyboardOnTap
import io.bluewallet.blueberry.ui.platformBottomEdgeClearance
import io.bluewallet.blueberry.ui.platformHeaderClearance
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
@Suppress("LongParameterList")
fun TxDetailsScreen(
    runtime: PeersRuntime,
    db: Database,
    txid: String,
    onBack: () -> Unit,
    onShowPsbt: () -> Unit = {},
    coinNav: CoinSheetNav = CoinSheetNav(onSend = {}, onOpenTx = {}),
) {
    var coinKey by remember { mutableStateOf<String?>(null) }
    TxDetailsContent(
        runtime,
        db,
        txid,
        onBack,
        onShowPsbt,
        onOpenCoin = { coinKey = it },
        onOpenTx = { other -> if (other != txid) coinNav.onOpenTx(other) },
    )
    CoinSheetOverlay(
        runtime = runtime,
        db = db,
        outpoint = coinKey,
        onDismiss = { coinKey = null },
        nav =
            CoinSheetNav(
                onSend = coinNav.onSend,
                onOpenTx = { other -> if (other != txid) coinNav.onOpenTx(other) },
                onFrozen = coinNav.onFrozen,
            ),
    )
}

@Composable
@Suppress("LongParameterList", "LongMethod")
private fun TxDetailsContent(
    runtime: PeersRuntime,
    db: Database,
    txid: String,
    onBack: () -> Unit,
    onShowPsbt: () -> Unit,
    onOpenCoin: (String) -> Unit,
    onOpenTx: (String) -> Unit,
) {
    val snap = rememberWalletTxs(runtime)
    var editing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var copied by remember { mutableStateOf(false) }
    var explorer by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val row = snap.txs.find { it.txid == txid }
    val tip = db.headers.tip()?.height
    val blockStats = rememberBlockStats(db, txid, row?.height)
    val screen =
        remember(snap, tip, txid, blockStats) {
            val current = snap.txs.find { it.txid == txid } ?: return@remember null
            txDetailOf(TxQuery(db, runtime.wallet, snap, tip, nowMillis() / 1000), current, blockStats)
        }
    val safe = WindowInsets.safeDrawing.asPaddingValues()
    val topChrome = safe.calculateTopPadding() + platformHeaderClearance()
    Box(Modifier.fillMaxSize().background(BwColors.Paper).dismissKeyboardOnTap()) {
        PlatformScrollEdge(
            modifier = Modifier.fillMaxSize(),
            top = topChrome,
            bottom = safe.calculateBottomPadding(),
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .alwaysBounceVerticalScroll()
                        .padding(top = topChrome + 24.dp)
                        .padding(horizontal = BwSpace.ScreenX)
                        .padding(bottom = platformBottomEdgeClearance())
                        .windowInsetsPadding(WindowInsets.ime.only(WindowInsetsSides.Bottom)),
            ) {
                if (row == null || screen == null) {
                    Text(
                        text = "Transaction not found",
                        color = BwColors.InkMuted,
                        fontFamily = BwFontFamily,
                        fontSize = BwType.BodySize,
                    )
                } else {
                    TxDetailColumn(
                        screen = screen,
                        host =
                            TxDetailHost(
                                copied = copied,
                                editing = editing,
                                draft = draft,
                                suggestions = txLabelSuggestions(snap, screen.detail.direction),
                                privateFacts = privateFacts(db, txid),
                                onBack = onBack,
                                onCopy = {
                                    clipboard.setText(AnnotatedString(txid))
                                    copied = true
                                },
                                onEdit = {
                                    draft = row.paymentLabel.orEmpty()
                                    editing = true
                                },
                                onDraft = { draft = it },
                                onSave = {
                                    setTxNote(db, runtime.wallet, runtime.walletTxsStore, txid, draft)
                                    uploadLabels(scope, db)
                                    editing = false
                                },
                                onCancel = { editing = false },
                                onFreeze = { key, frozen ->
                                    val wallet = runtime.wallet
                                    if (wallet != null && screen.detail.outcome == TxOutcome.None) {
                                        setUtxoFrozen(db, wallet, runtime.walletTxsStore, key, frozen)
                                    }
                                },
                                onOpenCoin = onOpenCoin,
                                onOpenTx = onOpenTx,
                                onExplorer = { explorer = true },
                                sendActions = {
                                    if (screen.detail.outcome == TxOutcome.None && row.height == 0) {
                                        OpenSendActions(
                                            OpenSendHost(db, runtime, scope, onForgotten = onBack, onShowPsbt = onShowPsbt),
                                            row,
                                            snap,
                                        )
                                    }
                                },
                            ),
                    )
                }
            }
        }
        PlatformScreenHeader(
            title = screen?.detail?.header?.title ?: "Transaction",
            onBack = onBack,
            showBarTitle = true,
            trailingText = if (copied) "Copied" else "Copy",
            onTrailing = {
                clipboard.setText(AnnotatedString(txid))
                copied = true
            },
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = safe.calculateTopPadding(), start = BwSpace.ScreenX, end = BwSpace.ScreenX),
        )
    }
    if (explorer) TxExplorerSheet(txid) { explorer = false }
}

@Composable
private fun rememberBlockStats(
    db: Database,
    txid: String,
    height: Int?,
): TxBlockStats? {
    var blockStats by remember(txid) { mutableStateOf<TxBlockStats?>(null) }
    LaunchedEffect(txid, height) {
        val mined = height ?: 0
        blockStats = if (mined <= 0) null else blockStatsOf(db, mined)
    }
    return blockStats
}

private suspend fun blockStatsOf(
    db: Database,
    height: Int,
): TxBlockStats =
    withContext(Dispatchers.Default) {
        val bytes = db.blocks.get(height)?.block
        TxBlockStats(
            txCount = bytes?.let { blockTxCount(it) },
            feeRateLabel = bytes?.let { blockFeeRateLabel(it, height) },
        )
    }

private fun privateFacts(
    db: Database,
    txid: String,
): List<Pair<String, String>> {
    val send = db.privateSends.get(txid) ?: return emptyList()
    val rows = mutableListOf("Private destination" to send.destination, privateSendProvider())
    val order = send.orderId.trim()
    if (order.isNotEmpty()) rows += "Order ID" to order
    return rows
}

private class OpenSendHost(
    val db: Database,
    val runtime: PeersRuntime,
    /** Outlives these actions, which leave as soon as the forgotten row drops out of the wallet. */
    val scope: CoroutineScope,
    val onForgotten: () -> Unit,
    val onShowPsbt: () -> Unit,
)

/**
 * An unconfirmed payment: its latest broadcast status, Send again when this device signed it, Show PSBT
 * when another device signs it, and Forget.
 */
@Composable
private fun OpenSendActions(
    host: OpenSendHost,
    row: WalletTxRow,
    snap: WalletTxsSnapshot,
) {
    val db = host.db
    val runtime = host.runtime
    val txid = row.txid
    val open = remember(txid, snap) { unconfirmedSendOpen(db, txid) }
    val signed = remember(txid, snap) { storedSendCanGoAgain(db, txid) }
    val psbt = remember(txid, snap) { storedSendIsPsbt(db, txid) }
    var confirming by remember(txid) { mutableStateOf(false) }
    var note by remember(txid) { mutableStateOf<String?>(null) }
    var forgetting by remember(txid) { mutableStateOf(false) }
    if (!open || forgetting) return
    val session = LocalBroadcastSessions.current.lastOrNull { it.receipt?.txid == txid }
    val sending = session != null && broadcastJobInFlight(session.snapshot.phase)
    if (confirming) {
        ForgetSendDialog(
            onForget = {
                confirming = false
                forgetting = true
                host.scope.launch {
                    val forgot = withContext(Dispatchers.Default) { abandonUnconfirmedSend(db, runtime, txid) }
                    if (forgot) host.onForgotten() else forgetting = false
                }
            },
            onKeep = { confirming = false },
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(BwSpace.Gap)) {
        session?.let { BroadcastStatusCard(broadcastStatus(it.snapshot)) }
        Text(
            text = note ?: openSendExplainer(signed),
            color = BwColors.InkMuted,
            fontFamily = BwFontFamily,
            fontSize = BwType.LabelSize,
        )
        if (signed && !sending) {
            TintedPillButton(
                text = "Send again",
                onClick = {
                    val destination =
                        db.sends.get(txid)?.destination ?: db.privateSends
                            .get(txid)
                            ?.destination
                            .orEmpty()
                    val receipt = BroadcastReceipt(txid, destination, row.paymentLabel.orEmpty(), -row.netDeltaSats, 0)
                    note = sendStoredAgain(runtime, db, txid, receipt)
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (psbt) {
            TintedPillButton(text = "Show PSBT", onClick = host.onShowPsbt, modifier = Modifier.fillMaxWidth())
        }
        PlainPillButton(
            text = "Forget this payment",
            onClick = { confirming = true },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun openSendExplainer(signed: Boolean): String =
    if (signed) {
        "This payment was given to Bitcoin nodes and may still confirm. Send again gives the same transaction to more nodes. " +
            "Forgetting it does not pull it back, and a new spend of these coins conflicts with it."
    } else {
        "This payment waits to be signed and published from another device. Show PSBT to scan it there. " +
            "Its coins stay locked until it confirms. Forget it only if it will never be published."
    }

@Composable
private fun ForgetSendDialog(
    onForget: () -> Unit,
    onKeep: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onKeep,
        title = {
            Text(
                text = "Forget this payment?",
                color = BwColors.Ink,
                fontFamily = BwFontFamily,
                fontWeight = BwType.Value,
            )
        },
        text = {
            Text(
                text = "The original payment can still confirm and send these coins to its recipient. A new payment with the same coins may lose to it.",
                color = BwColors.InkMuted,
                fontFamily = BwFontFamily,
                fontSize = BwType.BodySize,
            )
        },
        confirmButton = { PillButton(text = "Forget", onClick = onForget) },
        dismissButton = { TextAction(text = "Keep", onClick = onKeep) },
        containerColor = BwColors.Card,
    )
}
