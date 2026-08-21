package io.bluewallet.blueberry

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
        modifier = Modifier.fillMaxSize().safeContentPadding().padding(16.dp),
    ) {
        Text("Peers")
        Text(formatPeerSockets(counts))
        Text("${counts.known} known")
        Text("Chain tip")
        Text(progressBar(headers.percent, 10))
        Text("${headers.downloaded}/${headers.total}")
        Text("${headers.height} tip")
        if (headers.percent < 100) {
            Text("ETA ${formatEta(headers.etaMs)}")
        }
        Text("Filters DL")
        Text(progressBar(filters.percent, 10))
        Text("${filters.downloaded}/${filters.total}")
        if (filters.percent < 100) {
            Text("ETA ${formatEta(filters.etaMs)}")
        }
        Text("Filters match")
        Text(progressBar(matching.percent, 10))
        Text("${matching.scanned}/${matching.total}")
        if (matching.percent < 100) {
            val eta =
                if (matching.etaMs != null) formatEta(matching.etaMs)
                else if (matching.total > 0 && matching.scanned < matching.total) "…"
                else formatEta(null)
            Text("ETA $eta")
        }
        Text("Blocks DL")
        Text(progressBar(blocks.percent, 10))
        Text("${blocks.downloaded}/${blocks.matched}")
        if (blocks.percent < 100) {
            Text("ETA ${formatEta(blocks.etaMs)}")
        }
        Text("Balance")
        Text(walletTxs.balanceBtcLabel)
        Text("Transactions")
        if (walletTxs.blocksTotal > walletTxs.blocksParsed) {
            Text(formatParseProgress(walletTxs.blocksParsed, walletTxs.blocksTotal, walletTxs.etaMs))
        }
        if (walletTxs.txs.isEmpty() && walletTxs.blocksTotal <= walletTxs.blocksParsed) {
            Text("—")
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(walletTxs.txs, key = { it.txid }) { tx ->
                    Text("${tx.timeLabel}  ${tx.shortTxid}  ${tx.netDeltaLabel}")
                }
            }
        }
        Button(onClick = onOpenSettings) { Text("Settings") }
    }
}
