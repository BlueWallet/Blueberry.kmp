package io.bluewallet.blueberry

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import io.bluewallet.blueberry.ui.TextAction
import io.bluewallet.blueberry.ui.TintedPillButton
import io.bluewallet.blueberry.ui.ToPill

internal class SendCoinsActions(
    val onToggle: (String) -> Unit,
    val onSelectAll: () -> Unit,
    val onClear: () -> Unit,
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

@Suppress("LongParameterList")
@Composable
internal fun SendCoinsStep(
    to: SendRecipient,
    utxos: List<WalletUtxoRow>,
    selectedKeys: Set<String>,
    coverage: SendCoverage,
    rate: Double?,
    actions: SendCoinsActions,
) {
    val selected = utxos.filter { it.key in selectedKeys }
    SendStepFrame(
        title = "Coins",
        caption = "Step 3 of 4",
        onBack = actions.onBack,
        bottom = {
            TintedPillButton(
                text = sendCoinsButtonLabel(selected.size, coverage),
                onClick = actions.onContinue,
                enabled = selected.isNotEmpty() && coverage.covered,
                modifier = Modifier.fillMaxWidth(),
            )
        },
    ) {
        ToPill(label = to.label, address = to.address)
        CoverageCard(selected = selected, coverage = coverage, rate = rate)
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${selected.size} of ${utxos.size} coins",
                modifier = Modifier.weight(1f),
                color = BwColors.InkQuiet,
                fontFamily = BwFontFamily,
                fontSize = BwType.LabelSize,
            )
            TextAction(text = "Select all", onClick = actions.onSelectAll)
            TextAction(text = "Clear", onClick = actions.onClear)
        }
        if (utxos.isEmpty()) {
            HelperText("No coins in this wallet yet.")
        } else {
            SendCard {
                for (coin in utxos) {
                    CoinRow(coin = coin, selected = coin.key in selectedKeys, onToggle = { actions.onToggle(coin.key) })
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
            val (status, color) =
                when {
                    selected.isEmpty() -> "Nothing picked" to BwColors.InkQuiet
                    coverage.covered -> "Covered" to BwColors.Success
                    else -> "Need ${formatBtc(coverage.neededSats - coverage.selectedSats)} more" to BwColors.Warning
                }
            Text(status, color = color, fontFamily = BwFontFamily, fontSize = BwType.LabelSize, fontWeight = BwType.Label)
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
