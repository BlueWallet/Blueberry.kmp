package io.bluewallet.blueberry

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.bluewallet.blueberry.boot.loadHomeDetailedSync
import io.bluewallet.blueberry.storage.Database
import io.bluewallet.blueberry.ui.BtcAmountText
import io.bluewallet.blueberry.ui.BwColors
import io.bluewallet.blueberry.ui.BwFontFamily
import io.bluewallet.blueberry.ui.BwSpace
import io.bluewallet.blueberry.ui.BwType
import io.bluewallet.blueberry.ui.BwWordmark
import io.bluewallet.blueberry.ui.HorizontalProgressBar
import io.bluewallet.blueberry.ui.MetricCard
import io.bluewallet.blueberry.ui.PillButton
import io.bluewallet.blueberry.ui.ProgressMetricCard
import io.bluewallet.blueberry.ui.StatusDivider
import io.bluewallet.blueberry.ui.StatusList
import io.bluewallet.blueberry.ui.StatusRow
import io.bluewallet.blueberry.ui.TextAction
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
    val hideDetailedSync =
        Modifier
            .clip(RoundedCornerShape(BwSpace.Radius))
            .clickable { setDetailedSync(false) }
    DisposableEffect(store) {
        val off =
            store.subscribe {
                uiScope.launch { counts = store.get() }
            }
        onDispose { off() }
    }
    DisposableEffect(headersStore) {
        val off =
            headersStore.subscribe {
                uiScope.launch { headers = headersStore.get() }
            }
        onDispose { off() }
    }
    DisposableEffect(filtersStore) {
        val off =
            filtersStore.subscribe {
                uiScope.launch { filters = filtersStore.get() }
            }
        onDispose { off() }
    }
    DisposableEffect(matchingStore) {
        val off =
            matchingStore.subscribe {
                uiScope.launch { matching = matchingStore.get() }
            }
        onDispose { off() }
    }
    DisposableEffect(blocksStore) {
        val off =
            blocksStore.subscribe {
                uiScope.launch { blocks = blocksStore.get() }
            }
        onDispose { off() }
    }
    DisposableEffect(walletTxsStore) {
        val off =
            walletTxsStore.subscribe {
                uiScope.launch { walletTxs = walletTxsStore.get() }
            }
        onDispose { off() }
    }
    Column(
        modifier =
            Modifier
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
            BwWordmark()
            Spacer(modifier = Modifier.weight(1f))
            TextAction(text = "Settings", onClick = onOpenSettings)
        }
        Text(
            text = "Balance",
            color = BwColors.InkSecondary,
            fontFamily = BwFontFamily,
            fontSize = BwType.LabelSize,
            fontWeight = BwType.Label,
        )
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(if (detailedSync) BwSpace.Gap else 2.dp),
        ) {
            BtcAmountText(
                sats = walletTxs.balanceSats,
                color = BwColors.Ink,
                fontSize = BwType.HeroSize,
                fontWeight = BwType.Hero,
            )
            AnimatedContent(
                targetState = detailedSync,
                modifier = Modifier.fillMaxWidth(),
                transitionSpec = {
                    (fadeIn() + expandVertically()) togetherWith (fadeOut() + shrinkVertically())
                },
                label = "home-sync",
            ) { showDetails ->
                if (showDetails) {
                    Column(verticalArrangement = Arrangement.spacedBy(BwSpace.Gap)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(BwSpace.Gap),
                        ) {
                            ProgressMetricCard(
                                label = "Chain tip",
                                value = "${headers.percent}%",
                                caption = chainTipCaption(headers.height, rememberRelativeAge(headers.tipTimeS)),
                                percent = headers.percent,
                                modifier = Modifier.weight(1f).then(hideDetailedSync),
                            )
                            ProgressMetricCard(
                                label = "Filters DL",
                                value = "${filters.percent}%",
                                caption = progressCaption(filters.downloaded, filters.total, filters.percent, filters.etaMs),
                                percent = filters.percent,
                                modifier = Modifier.weight(1f).then(hideDetailedSync),
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(BwSpace.Gap),
                        ) {
                            ProgressMetricCard(
                                label = "Filters match",
                                value = "${matching.percent}%",
                                caption = progressCaption(matching.scanned, matching.total, matching.percent, matching.etaMs),
                                percent = matching.percent,
                                modifier = Modifier.weight(1f).then(hideDetailedSync),
                            )
                            ProgressMetricCard(
                                label = "Blocks DL",
                                value = "${blocks.percent}%",
                                caption = progressCaption(blocks.downloaded, blocks.matched, blocks.percent, blocks.etaMs),
                                percent = blocks.percent,
                                modifier = Modifier.weight(1f).then(hideDetailedSync),
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(BwSpace.Gap),
                        ) {
                            MetricCard(
                                label = "Peers",
                                value = formatGrouped(counts.known),
                                caption = "known",
                                modifier = Modifier.weight(1f).fillMaxHeight().then(hideDetailedSync),
                            )
                            StatusList(modifier = Modifier.weight(1f).then(hideDetailedSync)) {
                                Column {
                                    StatusRow(label = "probe", value = counts.probe.toString(), dotColor = BwColors.Accent)
                                    StatusDivider()
                                    StatusRow(label = "hdr", value = counts.hdr.toString(), dotColor = BwColors.Link)
                                    StatusDivider()
                                    StatusRow(label = "filt", value = counts.filt.toString(), dotColor = BwColors.Warning)
                                    StatusDivider()
                                    StatusRow(label = "blk", value = counts.blk.toString(), dotColor = BwColors.Success)
                                }
                            }
                        }
                    }
                } else {
                    HorizontalProgressBar(
                        percent =
                            unifiedSyncPercent(
                                headers.percent,
                                filters.percent,
                                matching.percent,
                                blocks.percent,
                            ),
                        modifier = Modifier.clickable { setDetailedSync(true) },
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(BwSpace.Gap),
        ) {
            PillButton(
                text = "Receive",
                onClick = onOpenReceive,
                modifier = Modifier.weight(1f),
            )
            PillButton(
                text = "Send",
                onClick = onOpenSend,
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            text = "Coins",
            color = BwColors.InkSecondary,
            fontFamily = BwFontFamily,
            fontSize = BwType.LabelSize,
            fontWeight = BwType.Label,
        )
        CoinsPanel(utxos = walletTxs.utxos, onClick = onOpenCoins)
        Text(
            text = "Transactions",
            color = BwColors.InkSecondary,
            fontFamily = BwFontFamily,
            fontSize = BwType.LabelSize,
            fontWeight = BwType.Label,
        )
        if (walletTxs.blocksTotal > walletTxs.blocksParsed) {
            Text(
                text = formatParseProgress(walletTxs.blocksParsed, walletTxs.blocksTotal, walletTxs.etaMs),
                color = BwColors.InkMuted,
                fontFamily = BwFontFamily,
                fontSize = BwType.CaptionSize,
            )
        }
        StatusList(modifier = Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                if (walletTxs.txs.isEmpty()) {
                    item {
                        StatusRow(
                            label = if (walletTxs.blocksTotal > walletTxs.blocksParsed) "Parsing…" else "No transactions",
                            value = "—",
                            valueColor = BwColors.InkMuted,
                            dotColor = BwColors.InkMuted,
                        )
                    }
                } else {
                    items(walletTxs.txs.size, key = { walletTxs.txs[it].txid }) { index ->
                        val tx = walletTxs.txs[index]
                        val incoming = tx.netDeltaSats >= 0
                        if (index > 0) StatusDivider()
                        val muted = txListSecondaryMuted(tx.paymentLabel, tx.utxoLabel)
                        StatusRow(
                            label = tx.timeLabel,
                            secondary = txListSecondary(tx.shortTxid, tx.paymentLabel, tx.utxoLabel),
                            secondaryColor = if (muted) BwColors.InkMuted else BwColors.Ink,
                            secondaryFontSize = if (muted) 12.sp else BwType.BodySize,
                            secondaryFontWeight = if (muted) FontWeight.Normal else BwType.Body,
                            onClick = { onOpenTx(tx.txid) },
                            value = tx.netDeltaLabel,
                            valueColor = if (incoming) BwColors.Success else BwColors.Danger,
                            dotColor = if (incoming) BwColors.Success else BwColors.Danger,
                            valueContent = {
                                BtcAmountText(
                                    sats = tx.netDeltaSats,
                                    plus = true,
                                    color = if (incoming) BwColors.Success else BwColors.Danger,
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}
