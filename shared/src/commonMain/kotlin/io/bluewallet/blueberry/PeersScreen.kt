package io.bluewallet.blueberry

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.bluewallet.blueberry.boot.loadAlwaysShowSyncProgress
import io.bluewallet.blueberry.boot.loadHomeDetailedSync
import io.bluewallet.blueberry.storage.Database
import io.bluewallet.blueberry.ui.BtcAmountText
import io.bluewallet.blueberry.ui.BwColors
import io.bluewallet.blueberry.ui.BwFontFamily
import io.bluewallet.blueberry.ui.BwSpace
import io.bluewallet.blueberry.ui.BwType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun PeersScreen(
    db: Database,
    store: PeerSocketsStore,
    headersStore: HeadersProgressStore,
    filtersStore: FiltersProgressStore,
    matchingStore: MatchingProgressStore,
    blocksStore: BlocksMatchedStore,
    walletTxsStore: WalletTxsStore,
    onOpenSettings: () -> Unit,
    onOpenReceive: () -> Unit,
    onOpenSend: () -> Unit,
    onOpenCoins: () -> Unit,
    onOpenTx: (String) -> Unit,
    onDetailedSyncChange: (Boolean) -> Unit,
) {
    var counts by remember { mutableStateOf(store.get()) }
    var headers by remember { mutableStateOf(headersStore.get()) }
    var filters by remember { mutableStateOf(filtersStore.get()) }
    var matching by remember { mutableStateOf(matchingStore.get()) }
    var blocks by remember { mutableStateOf(blocksStore.get()) }
    var walletTxs by remember { mutableStateOf(walletTxsStore.get()) }
    var detailedSync by remember(db) { mutableStateOf(loadHomeDetailedSync(db)) }
    val uiScope = rememberCoroutineScope()

    fun setDetailedSync(value: Boolean) {
        detailedSync = value
        onDetailedSyncChange(value)
    }
    DisposableEffect(store) {
        val off = store.subscribe { uiScope.launch { counts = store.get() } }
        onDispose { off() }
    }
    DisposableEffect(headersStore) {
        val off = headersStore.subscribe { uiScope.launch { headers = headersStore.get() } }
        onDispose { off() }
    }
    DisposableEffect(filtersStore) {
        val off = filtersStore.subscribe { uiScope.launch { filters = filtersStore.get() } }
        onDispose { off() }
    }
    DisposableEffect(matchingStore) {
        val off = matchingStore.subscribe { uiScope.launch { matching = matchingStore.get() } }
        onDispose { off() }
    }
    DisposableEffect(blocksStore) {
        val off = blocksStore.subscribe { uiScope.launch { blocks = blocksStore.get() } }
        onDispose { off() }
    }
    DisposableEffect(walletTxsStore) {
        val off = walletTxsStore.subscribe { uiScope.launch { walletTxs = walletTxsStore.get() } }
        onDispose { off() }
    }
    val unified =
        unifiedSyncPercent(
            headers.percent,
            filters.percent,
            matching.percent,
            blocks.percent,
        )
    val etaMs =
        overallSyncEtaMs(
            percent = unified,
            chainEtaMs = headers.etaMs,
            filtersEtaMs = filters.etaMs,
            matchEtaMs = matching.etaMs,
            blocksEtaMs = blocks.etaMs,
        )
    val syncUi =
        HomeSyncUi(
            percent = unified,
            etaMs = etaMs,
            chainAge = rememberRelativeAge(headers.tipTimeS),
            counts = counts,
            headers = headers,
            filters = filters,
            matching = matching,
            blocks = blocks,
        )
    val listState = rememberLazyListState()
    val parseBusy = walletTxs.blocksTotal > walletTxs.blocksParsed
    val dockIdle = isSyncDockIdle(unified, parseBusy)
    val alwaysShowSync = remember(db) { loadAlwaysShowSyncProgress(db) }
    var showDock by remember {
        mutableStateOf(syncDockShownImmediately(dockIdle, alwaysShowSync))
    }
    LaunchedEffect(dockIdle, alwaysShowSync) {
        if (alwaysShowSync) {
            showDock = true
        } else if (dockIdle) {
            delay(SYNC_DOCK_HIDE_DELAY_MS)
            showDock = false
        } else {
            showDock = true
        }
    }
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(BwColors.Paper),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .safeDrawingPadding(),
        ) {
            HomeTopBar(onOpenSettings = onOpenSettings)
            HomePinnedChrome(
                walletTxs = walletTxs,
                onOpenReceive = onOpenReceive,
                onOpenSend = onOpenSend,
                onOpenCoins = onOpenCoins,
            )
            HomeTxList(
                walletTxs = walletTxs,
                listState = listState,
                onOpenTx = onOpenTx,
                dockVisible = showDock && !detailedSync,
                modifier = Modifier.weight(1f),
            )
        }
        AnimatedVisibility(
            visible = detailedSync,
            enter = fadeIn(animationSpec = tween(220)),
            exit = fadeOut(animationSpec = tween(180)),
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.78f))
                        .clickable { setDetailedSync(false) },
            )
        }
        AnimatedVisibility(
            visible = detailedSync,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
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
            HomeSyncSheet(
                ui = syncUi,
                onToggle = { setDetailedSync(false) },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 640.dp)
                        .navigationBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
        AnimatedVisibility(
            visible = !detailedSync && showDock,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter =
                fadeIn(animationSpec = tween(200)) +
                    slideInVertically(
                        animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
                        initialOffsetY = { it / 3 },
                    ),
            exit =
                fadeOut(animationSpec = tween(150)) +
                    slideOutVertically(
                        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
                        targetOffsetY = { it / 3 },
                    ),
        ) {
            HomeSyncDock(
                percent = unified,
                etaMs = etaMs,
                listState = listState,
                onClick = { setDetailedSync(true) },
            )
        }
    }
}

@Composable
private fun HomeTopBar(onOpenSettings: () -> Unit) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = BwSpace.ScreenX, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(modifier = Modifier.weight(1f))
        HomeOverflowButton(onClick = onOpenSettings)
    }
}

@Composable
private fun HomePinnedChrome(
    walletTxs: WalletTxsSnapshot,
    onOpenReceive: () -> Unit,
    onOpenSend: () -> Unit,
    onOpenCoins: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = BwSpace.ScreenX)
                .padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        BtcAmountText(
            sats = walletTxs.balanceSats,
            color = BwColors.Ink,
            fontSize = 42.sp,
            fontWeight = BwType.Hero,
        )
        HomeActionRow(onReceive = onOpenReceive, onSend = onOpenSend)
        Column {
            HomeSectionLabel("Coins")
            CoinsPanel(utxos = walletTxs.utxos, onClick = onOpenCoins)
        }
        Column {
            HomeSectionLabel("Transactions")
            if (walletTxs.blocksTotal > walletTxs.blocksParsed) {
                Text(
                    text = formatParseProgress(walletTxs.blocksParsed, walletTxs.blocksTotal, walletTxs.etaMs),
                    color = BwColors.InkMuted,
                    fontFamily = BwFontFamily,
                    fontSize = BwType.CaptionSize,
                )
            }
        }
    }
}

@Composable
private fun HomeTxList(
    walletTxs: WalletTxsSnapshot,
    listState: LazyListState,
    onOpenTx: (String) -> Unit,
    dockVisible: Boolean,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        state = listState,
        contentPadding =
            PaddingValues(
                start = BwSpace.ScreenX,
                end = BwSpace.ScreenX,
                top = 8.dp,
                bottom = if (dockVisible) 240.dp else 24.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (walletTxs.txs.isEmpty()) {
            item {
                Text(
                    text = if (walletTxs.blocksTotal > walletTxs.blocksParsed) "Parsing…" else "No transactions",
                    color = BwColors.InkMuted,
                    fontFamily = BwFontFamily,
                    fontSize = BwType.BodySize,
                )
            }
        } else {
            items(walletTxs.txs, key = { it.txid }) { tx ->
                HomeTxBubble(tx = tx, onClick = { onOpenTx(tx.txid) })
            }
        }
    }
}

@Composable
private fun HomeSectionLabel(text: String) {
    Text(
        text = text,
        color = BwColors.InkSecondary,
        fontFamily = BwFontFamily,
        fontSize = 15.sp,
        fontWeight = BwType.Label,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}
