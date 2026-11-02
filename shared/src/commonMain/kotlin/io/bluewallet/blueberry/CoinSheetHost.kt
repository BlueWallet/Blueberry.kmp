@file:OptIn(ExperimentalTime::class)

package io.bluewallet.blueberry

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import io.bluewallet.blueberry.storage.Database
import io.bluewallet.blueberry.ui.LocalDarkPalette
import io.bluewallet.blueberry.ui.PlatformModalSheet
import io.bluewallet.blueberry.ui.SheetClose
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** Where the coin sheet can send the user. Each host screen supplies its own navigation. */
class CoinSheetNav(
    val onSend: (String) -> Unit,
    val onOpenTx: (String) -> Unit,
    val onFrozen: (String) -> Unit = {},
)

/** The coin sheet for [outpoint] as the platform's modal sheet; null hides it. */
@Composable
internal fun CoinSheetOverlay(
    runtime: PeersRuntime,
    db: Database,
    outpoint: String?,
    onDismiss: () -> Unit,
    nav: CoinSheetNav,
) {
    // Outlives the sheet, so a label upload started as it closes still finishes.
    val scope = rememberCoroutineScope()
    if (outpoint == null) return
    key(outpoint) {
        val coin =
            remember {
                OpenCoin(outpoint) { text ->
                    runtime.wallet?.let { wallet ->
                        setUtxoName(db, wallet, runtime.walletTxsStore, outpoint, text)
                        uploadLabels(scope, db)
                    }
                }
            }
        val dismiss = {
            coin.flushLabel()
            onDismiss()
        }
        PlatformModalSheet(onDismiss = dismiss, background = SheetPalette.sheet, dark = LocalDarkPalette.current) { close ->
            CoinSheetHost(runtime, db, coin, close, nav)
        }
    }
}

/** One open sheet: its coin, and a label typed but not saved, which is saved however the sheet closes. */
private class OpenCoin(
    val outpoint: String,
    val saveLabel: (String) -> Unit,
) {
    var unsavedLabel: String? = null

    fun flushLabel() {
        unsavedLabel?.let(saveLabel)
        unsavedLabel = null
    }
}

@Composable
private fun CoinSheetHost(
    runtime: PeersRuntime,
    db: Database,
    coin: OpenCoin,
    close: SheetClose,
    nav: CoinSheetNav,
) {
    val outpoint = coin.outpoint
    var loaded by remember { mutableStateOf(false) }
    var model by remember { mutableStateOf<CoinSheetModel?>(null) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    DisposableEffect(runtime.walletTxsStore, outpoint) {
        val gen = intArrayOf(0)
        val refresh = {
            scope.launch {
                val mine = ++gen[0]
                val next = withContext(Dispatchers.Default) { loadCoinSheet(runtime, db, outpoint) }
                if (mine == gen[0]) {
                    model = next
                    loaded = true
                }
            }
        }
        refresh()
        val off = runtime.walletTxsStore.subscribe { refresh() }
        onDispose { off() }
    }
    val actions =
        CoinSheetActions(
            onClose = { close {} },
            onCopy = { clipboard.setText(AnnotatedString(it)) },
            label = CoinLabelActions(onSave = coin.saveLabel, onDraft = { coin.unsavedLabel = it }),
            onFreeze = { frozen ->
                runtime.wallet?.let { wallet ->
                    setUtxoFrozen(db, wallet, runtime.walletTxsStore, outpoint, frozen)
                    if (frozen) nav.onFrozen(outpoint)
                }
            },
            onSend = { close { nav.onSend(outpoint) } },
            onOpenTx = { txid -> close { nav.onOpenTx(txid) } },
        )
    val current = model
    if (current == null) {
        CoinSheetPending(outpoint, actions, missing = loaded)
    } else {
        CoinSheet(model = current, actions = actions)
    }
}

/** Reads only local state: the live snapshot, saved fee rate, labels, and header tip. No network. */
private fun loadCoinSheet(
    runtime: PeersRuntime,
    db: Database,
    outpoint: String,
): CoinSheetModel? {
    val store = runtime.walletTxsStore
    val snap = store.get()
    return buildCoinSheetModel(
        CoinSheetRequest(
            outpoint = outpoint,
            snap = snap,
            control = coinControlSnapshotFrom(snap, loadSendFeeRate(db)),
            labels = db.utxoNames.list().associate { it.outpoint to it.name },
            nowEpochS = Clock.System.now().epochSeconds,
            tipHeight = db.headers.tip()?.height,
            syncing = snap.blocksTotal > snap.blocksParsed,
        ),
    )
}
