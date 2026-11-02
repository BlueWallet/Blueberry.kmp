package io.bluewallet.blueberry

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.bluewallet.blueberry.ui.BtcAmountText
import io.bluewallet.blueberry.ui.BwColors
import io.bluewallet.blueberry.ui.BwFontFamily
import io.bluewallet.blueberry.ui.BwType
import io.bluewallet.blueberry.ui.Chevron
import kotlinx.coroutines.launch

@Composable
internal fun rememberWalletTxs(runtime: PeersRuntime): WalletTxsSnapshot {
    var snap by remember { mutableStateOf(runtime.walletTxsStore.get()) }
    val scope = rememberCoroutineScope()
    DisposableEffect(runtime.walletTxsStore) {
        val off = runtime.walletTxsStore.subscribe { scope.launch { snap = runtime.walletTxsStore.get() } }
        onDispose { off() }
    }
    return snap
}

/** One Open coin row per unspent wallet output of this transaction. */
@Composable
internal fun OpenCoinRows(
    coins: List<WalletUtxoRow>,
    onOpen: (String) -> Unit,
) {
    if (coins.isEmpty()) return
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier = Modifier.fillMaxWidth().clip(shape).background(BwColors.Card, shape),
    ) {
        coins.forEach { coin ->
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .clickable(role = Role.Button, onClickLabel = "Open coin") { onOpen(coin.key) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.size(20.dp).background(Color(utxoCircleArgb(coin.key)), CircleShape))
                Column(Modifier.weight(1f)) {
                    Text("Open coin", color = BwColors.Ink, fontFamily = BwFontFamily, fontSize = BwType.BodySize, fontWeight = BwType.Body)
                    BtcAmountText(sats = coin.valueSats, fontSize = BwType.CaptionSize, fontWeight = BwType.Caption, color = BwColors.InkQuiet)
                }
                Chevron()
            }
        }
    }
}
