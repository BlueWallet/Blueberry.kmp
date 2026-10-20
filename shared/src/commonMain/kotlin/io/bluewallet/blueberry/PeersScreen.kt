@file:OptIn(ExperimentalComposeUiApi::class)

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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import io.bluewallet.blueberry.boot.loadHomeDetailedSync
import io.bluewallet.blueberry.storage.Database
import io.bluewallet.blueberry.ui.BtcAmountText
import io.bluewallet.blueberry.ui.BwColors
import io.bluewallet.blueberry.ui.BwFontFamily
import io.bluewallet.blueberry.ui.BwSpace
import io.bluewallet.blueberry.ui.BwType
import io.bluewallet.blueberry.ui.LocalDarkPalette
import io.bluewallet.blueberry.ui.PlatformScrollEdge
import io.bluewallet.blueberry.ui.alwaysBounceTail
import io.bluewallet.blueberry.ui.dither.DitherSyncGlow
import io.bluewallet.blueberry.ui.platformBottomEdgeClearance
import io.bluewallet.blueberry.ui.platformHeaderClearance
import io.bluewallet.blueberry.ui.rememberReducedMotion
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
    BackHandler(enabled = detailedSync) { setDetailedSync(false) }
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
    val homeVisible = LocalHomeVisible.current
    var listEpoch by remember { mutableStateOf(0) }
    var wasHomeVisible by remember { mutableStateOf(homeVisible) }
    LaunchedEffect(homeVisible) {
        if (homeVisible && !wasHomeVisible) {
            listEpoch++
        }
        wasHomeVisible = homeVisible
    }
    val listState = remember(listEpoch) { LazyListState() }
    val parseBusy = walletTxs.blocksTotal > walletTxs.blocksParsed
    val dockIdle = isSyncDockIdle(unified, parseBusy)
    val alwaysShowSync = LocalAlwaysShowSync.current
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
    val safe = WindowInsets.safeDrawing.asPaddingValues()
    val topChrome = safe.calculateTopPadding() + platformHeaderClearance()
    val glowActive = !dockIdle && !detailedSync
    // Nothing covers home: no pushed screen and no sync sheet.
    val homeInFront = homeVisible && !detailedSync
    val bottomEdge =
        if (glowActive) {
            0.dp
        } else {
            (HomeSyncDockBottomInset - platformBottomEdgeClearance()).coerceAtLeast(0.dp)
        }
    val hazeState = rememberHazeState()
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(BwColors.Paper)
                    .hazeSource(state = hazeState),
        ) {
            DitherSyncGlow(
                active = glowActive,
                animate = !rememberReducedMotion(),
                visible = homeVisible,
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(220.dp),
            )
            PlatformScrollEdge(
                modifier = Modifier.fillMaxSize(),
                top = topChrome,
                bottom = bottomEdge,
                // iOS edge blurs are UIKit overlays; drop them under the sync scrim and pushed screens.
                showEdges = homeInFront,
            ) {
                val dockVisible = showDock && !detailedSync
                LazyColumn(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .windowInsetsPadding(
                                WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
                            ),
                    state = listState,
                    contentPadding =
                        PaddingValues(
                            top = topChrome,
                            bottom = if (dockVisible) 240.dp else safe.calculateBottomPadding() + 24.dp,
                        ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    homeFeed(
                        walletTxs = walletTxs,
                        open =
                            HomeOpen(
                                receive = onOpenReceive,
                                send = onOpenSend,
                                coins = onOpenCoins,
                                tx = onOpenTx,
                            ),
                        dockVisible = dockVisible,
                    )
                    alwaysBounceTail(listState)
                }
            }
        }
        if (homeInFront) {
            HomeStickyHeader(onOpenSettings = onOpenSettings, modifier = Modifier.align(Alignment.TopCenter))
        }
        AnimatedVisibility(
            visible = detailedSync,
            enter = fadeIn(animationSpec = tween(220)),
            exit = fadeOut(animationSpec = tween(180)),
        ) {
            val scrim =
                if (LocalDarkPalette.current) {
                    Color.Black.copy(alpha = 0.78f)
                } else {
                    Color.Black.copy(alpha = 0.36f)
                }
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(scrim)
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
                hazeState = hazeState,
                onClick = { setDetailedSync(true) },
            )
        }
    }
}

@Composable
private fun HomeStickyHeader(
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val top = WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding()
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(top = top, start = BwSpace.ScreenX, end = BwSpace.ScreenX)
                .height(platformHeaderClearance()),
        contentAlignment = Alignment.CenterEnd,
    ) {
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
        verticalArrangement = Arrangement.spacedBy(32.dp),
    ) {
        BtcAmountText(
            sats = walletTxs.balanceSats,
            color = BwColors.Ink,
            fontSize = 42.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.55).sp,
            roundedDigits = true,
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

private class HomeOpen(
    val receive: () -> Unit,
    val send: () -> Unit,
    val coins: () -> Unit,
    val tx: (String) -> Unit,
)

private fun LazyListScope.homeFeed(
    walletTxs: WalletTxsSnapshot,
    open: HomeOpen,
    dockVisible: Boolean,
) {
    if (walletTxs.txs.isEmpty()) {
        item(key = "home-empty") {
            Column(Modifier.fillParentMaxHeight()) {
                HomePinnedChrome(
                    walletTxs = walletTxs,
                    onOpenReceive = open.receive,
                    onOpenSend = open.send,
                    onOpenCoins = open.coins,
                )
                Box(
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(bottom = if (dockVisible) 120.dp else 0.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (walletTxs.blocksTotal > walletTxs.blocksParsed) "Parsing…" else "No transactions",
                        color = BwColors.InkMuted,
                        fontFamily = BwFontFamily,
                        fontSize = BwType.BodySize,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    } else {
        item(key = "home-chrome") {
            HomePinnedChrome(
                walletTxs = walletTxs,
                onOpenReceive = open.receive,
                onOpenSend = open.send,
                onOpenCoins = open.coins,
            )
        }
        items(walletTxs.txs, key = { it.txid }) { tx ->
            Box(Modifier.padding(horizontal = BwSpace.ScreenX)) {
                HomeTxBubble(tx = tx, onClick = { open.tx(tx.txid) })
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
