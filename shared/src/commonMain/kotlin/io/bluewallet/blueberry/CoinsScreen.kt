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
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.bluewallet.blueberry.parse.formatBtc
import io.bluewallet.blueberry.storage.Database
import io.bluewallet.blueberry.ui.BtcAmountText
import io.bluewallet.blueberry.ui.BwColors
import io.bluewallet.blueberry.ui.BwFontFamily
import io.bluewallet.blueberry.ui.BwSpace
import io.bluewallet.blueberry.ui.OverlayBackEffects
import io.bluewallet.blueberry.ui.PillButton
import io.bluewallet.blueberry.ui.PlatformScreenHeader
import io.bluewallet.blueberry.ui.PlatformScrollEdge
import io.bluewallet.blueberry.ui.alwaysBounceTail
import io.bluewallet.blueberry.ui.platformBottomEdgeClearance
import io.bluewallet.blueberry.ui.platformHeaderClearance
import kotlinx.coroutines.launch

/** Breathing room under the inline nav title before the summary. */
private val TitleBelowHeaderGap = 12.dp

/** Room above and below the weight bar and filter chips, so they read as their own section. */
private val CoinOverviewGap = 32.dp
private val CoinGroupGap = 24.dp

@Composable
@Suppress("LongMethod", "LongParameterList", "CyclomaticComplexMethod")
fun CoinsScreen(
    runtime: PeersRuntime,
    db: Database,
    onBack: () -> Unit,
    initialSelectedKeys: Set<String> = emptySet(),
    onSendWithCoins: (Set<String>) -> Unit = {},
    onFrozenKey: (String) -> Unit = {},
    feeRateSatPerVb: Double? = null,
) {
    var snap by remember { mutableStateOf(runtime.walletTxsStore.get()) }
    var selectedKeys by remember { mutableStateOf(initialSelectedKeys) }
    var filter by remember { mutableStateOf(CoinControlFilter.All) }
    var editingKey by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf("") }
    var sheetRow by remember { mutableStateOf<CoinsRowModel?>(null) }
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    DisposableEffect(runtime.walletTxsStore) {
        val off = runtime.walletTxsStore.subscribe { scope.launch { snap = runtime.walletTxsStore.get() } }
        onDispose { off() }
    }
    val control =
        remember(snap, feeRateSatPerVb) {
            coinControlSnapshotFrom(snap, feeRateSatPerVb)
        }
    val filtered = remember(control, filter) { filterCoinControlGroups(control, filter) }
    val visibleKeys =
        remember(filtered) { filtered.flatMap { group -> group.coins.map { it.key } }.toSet() }
    LaunchedEffect(control, visibleKeys) {
        val live = control.coins.map { it.key }.toSet()
        val frozen =
            control.coins
                .filter { it.isFrozen }
                .map { it.key }
                .toSet()
        val next = pickerSelection(selectedKeys, live, visibleKeys, frozen, showsAll = filter == CoinControlFilter.All)
        if (next != selectedKeys) selectedKeys = next
    }
    val actionCoins = control.coins.filter { it.key in selectedKeys && !it.isFrozen }
    val notice = remember(actionCoins) { privacyNoticeForSelection(actionCoins) }
    val rowsByKey = remember(snap.utxos) { coinsRows(snap.utxos).associateBy { it.key } }
    LaunchedEffect(editingKey) {
        val row = editingKey?.let { rowsByKey[it] }
        if (row != null) {
            sheetRow = row
            draft = row.name.orEmpty()
        }
    }
    val sheetOpen = editingKey != null
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val safe = WindowInsets.safeDrawing.asPaddingValues()
    val topChrome = safe.calculateTopPadding() + platformHeaderClearance()
    val hasSelection = actionCoins.isNotEmpty()
    var bottomBarHeight by remember { mutableStateOf(0.dp) }
    val listBottomPad =
        if (hasSelection) {
            bottomBarHeight
        } else {
            safe.calculateBottomPadding()
        }

    OverlayBackEffects(
        enabled = sheetOpen,
        onDismiss = { editingKey = null },
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(BwColors.Paper),
        ) {
            PlatformScrollEdge(
                modifier = Modifier.fillMaxSize(),
                top = topChrome,
                // Bottom scrim is a fixed 40 dp 60%→0% fade on iOS.
                bottom = platformBottomEdgeClearance(),
                showEdges = !sheetOpen,
            ) {
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
                            top = topChrome + TitleBelowHeaderGap,
                            start = BwSpace.ScreenX,
                            end = BwSpace.ScreenX,
                            bottom = listBottomPad,
                        ),
                    verticalArrangement = Arrangement.spacedBy(CoinGroupGap),
                ) {
                    item(key = "coin-control-header") {
                        Column(
                            modifier = Modifier.padding(bottom = CoinOverviewGap - CoinGroupGap),
                            verticalArrangement = Arrangement.spacedBy(CoinOverviewGap),
                        ) {
                            CoinControlSummary(control)
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(
                                    text = coinControlSummaryLine(control),
                                    color = BwColors.InkSecondary,
                                    fontFamily = BwFontFamily,
                                    fontSize = 15.sp,
                                )
                                CoinWeightBar(
                                    coins = control.coins.sortedByDescending { it.valueSats },
                                    selectedKeys = selectedKeys,
                                    modifier = Modifier.fillMaxWidth().height(16.dp),
                                )
                                CoinFilterChips(
                                    counts = control.filterCounts,
                                    selected = filter,
                                    onSelect = { filter = it },
                                )
                            }
                        }
                    }
                    items(filtered, key = { it.groupId }) { group ->
                        CoinGroupSection(
                            group = group,
                            selectedKeys = selectedKeys,
                            onToggle = { coin ->
                                if (coin.isFrozen) return@CoinGroupSection
                                selectedKeys =
                                    if (coin.key in selectedKeys) {
                                        selectedKeys - coin.key
                                    } else {
                                        selectedKeys + coin.key
                                    }
                            },
                            onOpenSheet = { coin ->
                                editingKey = coin.key
                                draft = coin.label.orEmpty()
                            },
                        )
                    }
                    alwaysBounceTail(listState)
                }
            }
            if (!sheetOpen) {
                PlatformScreenHeader(
                    title = "Coin control",
                    onBack = onBack,
                    showBarTitle = true,
                    trailingText = if (hasSelection) "Clear" else null,
                    onTrailing =
                        if (hasSelection) {
                            { selectedKeys = emptySet() }
                        } else {
                            null
                        },
                    modifier =
                        Modifier
                            .align(Alignment.TopCenter)
                            .padding(
                                top = safe.calculateTopPadding(),
                                start = BwSpace.ScreenX,
                                end = BwSpace.ScreenX,
                            ),
                )
            }
            AnimatedVisibility(
                visible = hasSelection && !sheetOpen,
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                enter =
                    slideInVertically(
                        animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
                        initialOffsetY = { it },
                    ) + fadeIn(animationSpec = tween(180)),
                exit =
                    slideOutVertically(
                        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
                        targetOffsetY = { it },
                    ) + fadeOut(animationSpec = tween(140)),
            ) {
                CoinControlBottomBar(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .onSizeChanged { size ->
                                bottomBarHeight = with(density) { size.height.toDp() }
                            },
                    notice = notice,
                    selectedCoins = actionCoins,
                    onPrimary = { onSendWithCoins(actionCoins.map { it.key }.toSet()) },
                )
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
                            snap = runtime.walletTxsStore.get()
                            uploadLabels(scope, db)
                        }
                        editingKey = null
                    },
                    onToggleFrozen = { frozen ->
                        val wallet = runtime.wallet ?: return@CoinsUtxoSheet
                        if (draft.trim() != row.name.orEmpty()) {
                            setUtxoName(db, wallet, runtime.walletTxsStore, row.key, draft)
                            uploadLabels(scope, db)
                        }
                        setUtxoFrozen(db, wallet, runtime.walletTxsStore, row.key, frozen)
                        snap = runtime.walletTxsStore.get()
                        if (frozen) {
                            selectedKeys = selectedKeys - row.key
                            onFrozenKey(row.key)
                        }
                        editingKey = null
                    },
                )
            }
        }
    }
}

@Composable
private fun CoinControlSummary(control: CoinControlSnapshot) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BtcAmountText(
            sats = control.spendableSats,
            color = BwColors.Ink,
            fontSize = 42.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.55).sp,
            roundedDigits = true,
        )
        FrozenBalancePill(control.frozenSats)
        if (control.availability == CoinAvailability.AllFrozen) {
            Text(
                text = ALL_FROZEN_NOTICE,
                color = BwColors.Warning,
                fontFamily = BwFontFamily,
                fontSize = 15.sp,
            )
        }
    }
}

@Composable
private fun CoinControlBottomBar(
    notice: PrivacyNotice,
    selectedCoins: List<CoinControlCoin>,
    onPrimary: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val k = selectedCoins.size
    val total = selectedCoins.sumOf { it.valueSats }
    val noticeColor =
        when (notice.kind) {
            PrivacyNoticeKind.Hint -> BwColors.InkSecondary
            PrivacyNoticeKind.Safe -> BwColors.Success
            PrivacyNoticeKind.Warning -> BwColors.Warning
        }
    val word = if (k == 1) "coin" else "coins"
    val amount = formatBtc(total).removeSuffix(" BTC")
    val primaryLabel = "Send $k $word · $amount"
    Column(
        modifier =
            modifier
                .background(BwColors.Paper)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                .padding(horizontal = BwSpace.ScreenX, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = notice.message,
            color = noticeColor,
            fontFamily = BwFontFamily,
            fontSize = 14.sp,
        )
        notice.costLine?.let { line ->
            Text(
                text = line,
                color = BwColors.Warning,
                fontFamily = BwFontFamily,
                fontSize = 13.sp,
            )
        }
        PillButton(
            text = primaryLabel,
            onClick = onPrimary,
            enabled = k > 0,
            modifier = Modifier.fillMaxWidth(),
            height = 56.dp,
        )
    }
}
