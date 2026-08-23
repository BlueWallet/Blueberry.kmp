package io.bluewallet.blueberry

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import io.bluewallet.blueberry.ui.BtcAmountText
import io.bluewallet.blueberry.ui.BwColors
import io.bluewallet.blueberry.ui.BwFontFamily
import io.bluewallet.blueberry.ui.BwSpace
import io.bluewallet.blueberry.ui.BwType
import io.bluewallet.blueberry.ui.BwWordmark
import io.bluewallet.blueberry.ui.MetricCard
import io.bluewallet.blueberry.ui.ProgressMetricCard
import io.bluewallet.blueberry.ui.TextAction
import io.bluewallet.blueberry.ui.StatusDivider
import io.bluewallet.blueberry.ui.StatusList
import io.bluewallet.blueberry.ui.StatusRow
import io.bluewallet.blueberry.ui.PillButton
import kotlinx.coroutines.launch

@Composable
fun PeersScreen(
    store: PeerSocketsStore,
    headersStore: HeadersProgressStore,
    filtersStore: FiltersProgressStore,
    matchingStore: MatchingProgressStore,
    blocksStore: BlocksMatchedStore,
    walletTxsStore: WalletTxsStore,
    onOpenSettings: () -> Unit,
    onOpenReceive: () -> Unit,
) {
    var counts by remember { mutableStateOf(store.get()) }
    var headers by remember { mutableStateOf(headersStore.get()) }
    var filters by remember { mutableStateOf(filtersStore.get()) }
    var matching by remember { mutableStateOf(matchingStore.get()) }
    var blocks by remember { mutableStateOf(blocksStore.get()) }
    var walletTxs by remember { mutableStateOf(walletTxsStore.get()) }
    val uiScope = rememberCoroutineScope()
    DisposableEffect(store) {
        val off = store.subscribe {
            uiScope.launch { counts = store.get() }
        }
        onDispose { off() }
    }
    DisposableEffect(headersStore) {
        val off = headersStore.subscribe {
            uiScope.launch { headers = headersStore.get() }
        }
        onDispose { off() }
    }
    DisposableEffect(filtersStore) {
        val off = filtersStore.subscribe {
            uiScope.launch { filters = filtersStore.get() }
        }
        onDispose { off() }
    }
    DisposableEffect(matchingStore) {
        val off = matchingStore.subscribe {
            uiScope.launch { matching = matchingStore.get() }
        }
        onDispose { off() }
    }
    DisposableEffect(blocksStore) {
        val off = blocksStore.subscribe {
            uiScope.launch { blocks = blocksStore.get() }
        }
        onDispose { off() }
    }
    DisposableEffect(walletTxsStore) {
        val off = walletTxsStore.subscribe {
            uiScope.launch { walletTxs = walletTxsStore.get() }
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
        BtcAmountText(
            sats = walletTxs.balanceSats,
            color = BwColors.Ink,
            fontSize = BwType.HeroSize,
            fontWeight = BwType.Hero,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(BwSpace.Gap),
        ) {
            ProgressMetricCard(
                label = "Chain tip",
                value = formatGrouped(headers.height),
                caption = progressCaption(headers.downloaded, headers.total, headers.percent, headers.etaMs),
                percent = headers.percent,
                modifier = Modifier.weight(1f),
            )
            ProgressMetricCard(
                label = "Filters DL",
                value = "${filters.percent}%",
                caption = progressCaption(filters.downloaded, filters.total, filters.percent, filters.etaMs),
                percent = filters.percent,
                modifier = Modifier.weight(1f),
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
                modifier = Modifier.weight(1f),
            )
            ProgressMetricCard(
                label = "Blocks DL",
                value = "${blocks.percent}%",
                caption = progressCaption(blocks.downloaded, blocks.matched, blocks.percent, blocks.etaMs),
                percent = blocks.percent,
                modifier = Modifier.weight(1f),
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
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            StatusList(modifier = Modifier.weight(1f)) {
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
                        StatusRow(
                            label = tx.timeLabel,
                            secondary = tx.shortTxid,
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
                onClick = {},
                modifier = Modifier.weight(1f),
            )
        }
    }
}
