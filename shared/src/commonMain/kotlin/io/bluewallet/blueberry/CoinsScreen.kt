package io.bluewallet.blueberry

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.bluewallet.blueberry.parse.formatBtc
import io.bluewallet.blueberry.storage.Database
import io.bluewallet.blueberry.ui.BtcAmountText
import io.bluewallet.blueberry.ui.BwColors
import io.bluewallet.blueberry.ui.BwFontFamily
import io.bluewallet.blueberry.ui.BwSpace
import io.bluewallet.blueberry.ui.BwType
import io.bluewallet.blueberry.ui.OverlayBackEffects
import io.bluewallet.blueberry.ui.PillButton
import io.bluewallet.blueberry.ui.PlatformScreenHeader
import io.bluewallet.blueberry.ui.PlatformScrollEdge
import io.bluewallet.blueberry.ui.alwaysBounceTail
import io.bluewallet.blueberry.ui.platformBottomEdgeClearance
import io.bluewallet.blueberry.ui.platformHeaderClearance
import kotlinx.coroutines.launch

private val SelectedRow = Color(0xFF1F2A35)
private val PrivacyGreen = Color(0xFF7FC8A9)
private val PrivacyAmber = Color(0xFFE0A24A)
private val NoLabelAmber = Color(0xFFE0A24A)
private val ChangeTagFg = Color(0xFF9CC8F0)
private val ChangeTagBg = Color(0xFF1C2D3D)
private val WarningTagBg = Color(0xFF3A2C17)
private val NeutralControl = Color(0xFF6E757C)
private val Divider = Color(0xFF262A2E)
private val ListCard = Color(0xFF1B1E21)

/** Breathing room under the inline nav title before the summary. */
private val TitleBelowHeaderGap = 12.dp

/** Approximate bottom-bar body height (notice + pill + padding), excluding home indicator. */
private val BottomBarClearance = 140.dp

enum class CoinControlMode {
    /** Home entry: Send opens the send flow with the selection. */
    Standalone,

    /** Send entry: confirm returns keys to the caller. */
    Picker,
}

@Composable
@Suppress("LongMethod", "LongParameterList", "CyclomaticComplexMethod")
fun CoinsScreen(
    runtime: PeersRuntime,
    db: Database,
    onBack: () -> Unit,
    mode: CoinControlMode = CoinControlMode.Standalone,
    initialSelectedKeys: Set<String> = emptySet(),
    onSendWithCoins: (Set<String>) -> Unit = {},
    onConfirmSelection: (Set<String>) -> Unit = {},
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
    val selectedCoins = control.coins.filter { it.key in selectedKeys && !it.isFrozen }
    val notice = remember(selectedCoins) { privacyNoticeForSelection(selectedCoins) }
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
    val safe = WindowInsets.safeDrawing.asPaddingValues()
    val topChrome = safe.calculateTopPadding() + platformHeaderClearance()
    val hasSelection = selectedCoins.isNotEmpty()
    val listBottomPad =
        safe.calculateBottomPadding() + if (hasSelection) BottomBarClearance else 0.dp

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
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    item(key = "coin-control-header") {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            CoinControlSummary(control)
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
                    modifier = Modifier.fillMaxWidth(),
                    notice = notice,
                    selectedCoins = selectedCoins,
                    mode = mode,
                    onPrimary = {
                        val keys = selectedCoins.map { it.key }.toSet()
                        when (mode) {
                            CoinControlMode.Standalone -> onSendWithCoins(keys)
                            CoinControlMode.Picker -> onConfirmSelection(keys)
                        }
                    },
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
            sats = control.totalSats,
            color = BwColors.Ink,
            fontSize = 42.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.55).sp,
            roundedDigits = true,
        )
        val coinsWord = if (control.coins.size == 1) "1 coin" else "${control.coins.size} coins"
        val groupsWord =
            if (control.linkedGroupCount == 1) {
                "1 linked group"
            } else {
                "${control.linkedGroupCount} linked groups"
            }
        val soloWord =
            if (control.soloCount == 1) {
                "1 independent"
            } else {
                "${control.soloCount} independent"
            }
        Text(
            text = "$coinsWord · $groupsWord · $soloWord",
            color = BwColors.InkSecondary,
            fontFamily = BwFontFamily,
            fontSize = 15.sp,
        )
    }
}

@Composable
private fun CoinWeightBar(
    coins: List<CoinControlCoin>,
    selectedKeys: Set<String>,
    modifier: Modifier = Modifier,
) {
    val total = coins.sumOf { it.valueSats }.coerceAtLeast(1L)
    val hasSelection = selectedKeys.isNotEmpty()
    Canvas(modifier = modifier.clip(RoundedCornerShape(8.dp))) {
        val gap = 2.dp.toPx()
        val minW = 3.dp.toPx()
        val usable = (size.width - gap * (coins.size - 1).coerceAtLeast(0)).coerceAtLeast(0f)
        var x = 0f
        for ((index, coin) in coins.withIndex()) {
            val frac = coin.valueSats.toFloat() / total.toFloat()
            val w = (usable * frac).coerceAtLeast(minW)
            val alpha = if (!hasSelection || coin.key in selectedKeys) 1f else 0.28f
            drawRoundRect(
                color = Color(coin.colorArgb).copy(alpha = alpha),
                topLeft = Offset(x, 0f),
                size = Size(w, size.height),
                cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
            )
            x += w
            if (index < coins.lastIndex) x += gap
        }
    }
}

@Composable
private fun CoinFilterChips(
    counts: Map<CoinControlFilter, Int>,
    selected: CoinControlFilter,
    onSelect: (CoinControlFilter) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CoinControlFilter.entries.forEach { f ->
            val count = counts[f] ?: 0
            val active = f == selected
            val label =
                when (f) {
                    CoinControlFilter.All -> "All"
                    CoinControlFilter.Labeled -> "Labeled"
                    CoinControlFilter.Change -> "Change"
                    CoinControlFilter.NoLabel -> "No label"
                    CoinControlFilter.Frozen -> "Frozen"
                }
            val shape = RoundedCornerShape(18.dp)
            val bg = if (active) BwColors.AccentSoft else ListCard
            val fg =
                when {
                    active -> BwColors.Accent
                    f == CoinControlFilter.NoLabel -> NoLabelAmber
                    else -> BwColors.InkSecondary
                }
            Text(
                text = "$label $count",
                color = fg,
                fontFamily = BwFontFamily,
                fontSize = 13.sp,
                modifier =
                    Modifier
                        .clip(shape)
                        .background(bg, shape)
                        .clickable { onSelect(f) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
@Suppress("LongMethod")
private fun CoinGroupSection(
    group: CoinControlGroup,
    selectedKeys: Set<String>,
    onToggle: (CoinControlCoin) -> Unit,
    onOpenSheet: (CoinControlCoin) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "${group.title} · ${group.coins.size}",
                color = BwColors.Ink,
                fontFamily = BwFontFamily,
                fontSize = 15.sp,
                fontWeight = BwType.Label,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text =
                        if (group.linked) {
                            "Already linked on-chain"
                        } else {
                            "No shared history, keep them apart"
                        },
                    color = BwColors.InkSecondary,
                    fontFamily = BwFontFamily,
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f),
                )
                BtcAmountText(
                    sats = group.totalSats,
                    color = BwColors.InkSecondary,
                    trailingColor = BwColors.InkSecondary,
                    fontSize = 14.sp,
                    fontWeight = BwType.Value,
                    roundedDigits = true,
                )
            }
        }
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(ListCard),
        ) {
            group.coins.forEachIndexed { index, coin ->
                key(coin.key, coin.isFrozen, coin.label, coin.privacyState) {
                    if (index > 0) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(Divider),
                        )
                    }
                    CoinControlRow(
                        coin = coin,
                        selected = coin.key in selectedKeys,
                        onToggle = { onToggle(coin) },
                        onOpenSheet = { onOpenSheet(coin) },
                    )
                }
            }
        }
    }
}

@Composable
@Suppress("LongMethod")
private fun CoinControlRow(
    coin: CoinControlCoin,
    selected: Boolean,
    onToggle: () -> Unit,
    onOpenSheet: () -> Unit,
) {
    val subtitle = coinRowSubtitle(coin)
    val tag = coinRowTag(coin)
    val selectA11y =
        buildString {
            append(formatBtc(coin.valueSats))
            append(", ")
            if (coin.isFrozen) {
                append("frozen, locked, not selectable")
            } else {
                append(if (selected) "selected" else "not selected")
                append(", double tap to toggle selection")
            }
        }
    val detailsA11y =
        buildString {
            append(formatBtc(coin.valueSats))
            append(", ")
            append(subtitle)
            append(", ")
            append(coin.ageLabel)
            append(", opens coin details")
        }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(if (selected) SelectedRow else Color.Transparent)
                .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier =
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .then(
                        if (coin.isFrozen) {
                            Modifier
                        } else {
                            Modifier
                                .semantics {
                                    contentDescription = selectA11y
                                    this.selected = selected
                                    toggleableState =
                                        if (selected) ToggleableState.On else ToggleableState.Off
                                }.clickable(role = Role.Checkbox, onClick = onToggle)
                        },
                    ).padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CoinSelector(selected = selected, frozen = coin.isFrozen)
            Box(
                modifier =
                    Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Color(coin.colorArgb), CircleShape),
            )
        }
        Row(
            modifier =
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .semantics { contentDescription = detailsA11y }
                    .clickable(role = Role.Button, onClick = onOpenSheet)
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                BtcAmountText(
                    sats = coin.valueSats,
                    color = BwColors.Ink,
                    fontSize = 17.sp,
                    fontWeight = BwType.Value,
                    roundedDigits = true,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (tag != null) {
                        val (fg, bg) =
                            when (tag) {
                                "Frozen" -> BwColors.InkSecondary to BwColors.Section
                                "Near dust" -> NoLabelAmber to WarningTagBg
                                "Change" -> ChangeTagFg to ChangeTagBg
                                else -> BwColors.InkSecondary to BwColors.Section
                            }
                        Text(
                            text = tag,
                            color = fg,
                            fontFamily = BwFontFamily,
                            fontSize = 11.sp,
                            modifier =
                                Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(bg)
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                    Text(
                        text = subtitle,
                        color =
                            if (subtitle.startsWith("No label")) {
                                NoLabelAmber
                            } else {
                                BwColors.InkSecondary
                            },
                        fontFamily = BwFontFamily,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                text = coin.ageLabel,
                color = BwColors.InkSecondary,
                fontFamily = BwFontFamily,
                fontSize = 13.sp,
            )
        }
    }
}

@Composable
private fun CoinSelector(
    selected: Boolean,
    frozen: Boolean,
) {
    if (frozen) {
        Box(
            modifier =
                Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(BwColors.Section),
            contentAlignment = Alignment.Center,
        ) {
            FrozenLockIcon(modifier = Modifier.size(12.dp))
        }
    } else {
        Box(
            modifier =
                Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .then(
                        if (selected) {
                            Modifier.background(BwColors.Accent, CircleShape)
                        } else {
                            Modifier.border(2.dp, NeutralControl, CircleShape)
                        },
                    ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                CheckmarkIcon(modifier = Modifier.size(12.dp), color = Color.White)
            }
        }
    }
}

@Composable
private fun CheckmarkIcon(
    modifier: Modifier = Modifier,
    color: Color,
) {
    Canvas(modifier = modifier) {
        val stroke =
            Stroke(
                width = 2.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            )
        val path =
            Path().apply {
                moveTo(size.width * 0.12f, size.height * 0.52f)
                lineTo(size.width * 0.38f, size.height * 0.78f)
                lineTo(size.width * 0.88f, size.height * 0.22f)
            }
        drawPath(path, color, style = stroke)
    }
}

@Composable
private fun FrozenLockIcon(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val stroke = 1.4.dp.toPx()
        val w = size.width
        val h = size.height
        val bodyTop = h * 0.42f
        val bodyHeight = h - bodyTop
        val bodyLeft = w * 0.18f
        val bodyWidth = w - bodyLeft * 2f
        val shackleCx = w / 2f
        val shackleR = bodyWidth * 0.32f
        val shackleTop = h * 0.08f
        drawArc(
            color = NeutralControl,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(shackleCx - shackleR, shackleTop),
            size = Size(shackleR * 2f, shackleR * 2f),
            style = Stroke(width = stroke),
        )
        drawRoundRect(
            color = NeutralControl,
            topLeft = Offset(bodyLeft, bodyTop),
            size = Size(bodyWidth, bodyHeight),
            cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
        )
    }
}

@Composable
private fun CoinControlBottomBar(
    notice: PrivacyNotice,
    selectedCoins: List<CoinControlCoin>,
    mode: CoinControlMode,
    onPrimary: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val k = selectedCoins.size
    val total = selectedCoins.sumOf { it.valueSats }
    val noticeColor =
        when (notice.kind) {
            PrivacyNoticeKind.Hint -> BwColors.InkSecondary
            PrivacyNoticeKind.Safe -> PrivacyGreen
            PrivacyNoticeKind.Warning -> PrivacyAmber
        }
    val word = if (k == 1) "coin" else "coins"
    val amount = formatBtc(total).removeSuffix(" BTC")
    val primaryLabel =
        when (mode) {
            CoinControlMode.Picker -> "Use $k $word · $amount"
            CoinControlMode.Standalone -> "Send $k $word · $amount"
        }
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
                color = PrivacyAmber,
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
