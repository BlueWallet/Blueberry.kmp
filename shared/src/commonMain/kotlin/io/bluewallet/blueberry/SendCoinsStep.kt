package io.bluewallet.blueberry

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.bluewallet.blueberry.parse.formatBtc
import io.bluewallet.blueberry.ui.BtcAmountText
import io.bluewallet.blueberry.ui.BwColors
import io.bluewallet.blueberry.ui.BwFontFamily
import io.bluewallet.blueberry.ui.BwType
import io.bluewallet.blueberry.ui.TintedPillButton
import io.bluewallet.blueberry.ui.ToPill

private val CoinGroupGap = 24.dp

internal class SendCoinsActions(
    val onToggle: (String) -> Unit,
    val onClear: () -> Unit,
    val onReplaceSelection: (Set<String>) -> Unit,
    val onContinue: () -> Unit,
    val onBack: () -> Unit,
)

/** Primary button label for the Coins step. */
fun sendCoinsButtonLabel(
    selectedCount: Int,
    coverage: SendCoverage,
): String =
    when {
        selectedCount == 0 -> "Pick coins to pay with"
        !coverage.covered -> "Need ${formatBtc(coverage.neededSats - coverage.selectedSats)} more"
        selectedCount == 1 -> "Continue with 1 coin"
        else -> "Continue with $selectedCount coins"
    }

@Suppress("LongParameterList", "LongMethod")
@Composable
internal fun SendCoinsStep(
    to: SendRecipient,
    utxos: List<WalletUtxoRow>,
    control: CoinControlSnapshot,
    selectedKeys: Set<String>,
    coverage: SendCoverage,
    rate: Double?,
    actions: SendCoinsActions,
) {
    val selected = utxos.filter { it.key in selectedKeys && !it.isFrozen }
    var filter by remember { mutableStateOf(CoinControlFilter.All) }
    val groups = remember(control, filter) { filterCoinControlGroups(control, filter) }
    val visibleKeys = remember(groups) { groups.flatMap { group -> group.coins.map { it.key } }.toSet() }
    LaunchedEffect(control, visibleKeys, filter) {
        val live = control.coins.map { it.key }.toSet()
        val frozen =
            control.coins
                .filter { it.isFrozen }
                .map { it.key }
                .toSet()
        val next = pickerSelection(selectedKeys, live, visibleKeys, frozen, showsAll = filter == CoinControlFilter.All)
        if (next != selectedKeys) actions.onReplaceSelection(next)
    }
    val selectedCoins = control.coins.filter { it.key in selectedKeys && !it.isFrozen }
    val notice = remember(selectedCoins) { privacyNoticeForSelection(selectedCoins) }
    SendStepFrame(
        title = "Select Coins",
        caption = "Step 3 of 4",
        onBack = actions.onBack,
        onClear = if (selectedKeys.isNotEmpty()) actions.onClear else null,
        bottom = {
            CoinActionPanel(notice = notice.takeIf { selectedCoins.isNotEmpty() }) {
                TintedPillButton(
                    text = sendCoinsButtonLabel(selected.size, coverage),
                    onClick = actions.onContinue,
                    enabled = selected.isNotEmpty() && coverage.covered,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    ) {
        ToPill(label = to.label, address = to.address)
        CoverageCard(selected = selected, coverage = coverage, rate = rate)
        Text(
            text = "${selected.size} of ${utxos.count { !it.isFrozen }} coins",
            color = BwColors.InkQuiet,
            fontFamily = BwFontFamily,
            fontSize = BwType.LabelSize,
        )
        when (control.availability) {
            CoinAvailability.NoCoins -> HelperText("No coins in this wallet yet.")
            CoinAvailability.AllFrozen -> HelperText(ALL_FROZEN_NOTICE, error = true)
            CoinAvailability.Spendable -> {}
        }
        if (control.coins.isNotEmpty()) {
            CoinFilterChips(counts = control.filterCounts, selected = filter, onSelect = { filter = it })
            Column(verticalArrangement = Arrangement.spacedBy(CoinGroupGap)) {
                for (group in groups) {
                    key(group.groupId) {
                        CoinGroupSection(
                            group = group,
                            selectedKeys = selectedKeys,
                            onToggle = { coin -> if (!coin.isFrozen) actions.onToggle(coin.key) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CoverageCard(
    selected: List<WalletUtxoRow>,
    coverage: SendCoverage,
    rate: Double?,
) {
    SendCard {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BtcAmountText(sats = coverage.selectedSats, fontSize = BwType.BodySize)
                Text("of", color = BwColors.InkQuiet, fontFamily = BwFontFamily, fontSize = BwType.LabelSize)
                BtcAmountText(sats = coverage.neededSats, color = BwColors.InkQuiet, fontSize = BwType.BodySize)
            }
            if (selected.isNotEmpty()) {
                val (status, color) =
                    if (coverage.covered) {
                        "Covered" to BwColors.Success
                    } else {
                        "Need ${formatBtc(coverage.neededSats - coverage.selectedSats)} more" to BwColors.Warning
                    }
                Text(status, color = color, fontFamily = BwFontFamily, fontSize = BwType.LabelSize, fontWeight = BwType.Label)
            }
        }
        CoverageBar(selected = selected, neededSats = coverage.neededSats)
        HelperText(
            if (rate != null) {
                "Amount plus a fee at ${formatFeeRate(rate)} sat/vB, your last rate. You can change it on Review."
            } else {
                "Fee not included yet. You'll set it on Review."
            },
        )
    }
}

@Composable
private fun CoverageBar(
    selected: List<WalletUtxoRow>,
    neededSats: Long,
) {
    val track = BwColors.BarTrack
    val tick = BwColors.Ink
    val segments = selected.map { Color(utxoCircleArgb(it.key)) to it.valueSats }
    Canvas(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(12.dp)
                .clip(RoundedCornerShape(50)),
    ) {
        drawRect(track)
        val total = maxOf(segments.sumOf { it.second }, neededSats, 1L).toFloat()
        var x = 0f
        for ((color, sats) in segments) {
            val w = size.width * sats / total
            drawRect(color, topLeft = Offset(x, 0f), size = Size(w, size.height))
            x += w
        }
        val tickX = (size.width * neededSats / total).coerceIn(1.dp.toPx(), size.width - 1.dp.toPx())
        drawRect(tick, topLeft = Offset(tickX - 1.dp.toPx(), 0f), size = Size(2.dp.toPx(), size.height))
    }
}
