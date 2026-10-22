package io.bluewallet.blueberry

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.bluewallet.blueberry.parse.formatBtc
import io.bluewallet.blueberry.ui.AddressGroups
import io.bluewallet.blueberry.ui.AmountHero
import io.bluewallet.blueberry.ui.BtcAmountText
import io.bluewallet.blueberry.ui.BwColors
import io.bluewallet.blueberry.ui.BwFontFamily
import io.bluewallet.blueberry.ui.BwSpace
import io.bluewallet.blueberry.ui.BwType
import io.bluewallet.blueberry.ui.Chevron
import io.bluewallet.blueberry.ui.HeaderMenuItem
import io.bluewallet.blueberry.ui.TextAction
import io.bluewallet.blueberry.ui.ToPill
import io.bluewallet.blueberry.wallet.BuildSendResult
import io.bluewallet.blueberry.wallet.PsbtSendResult
import io.bluewallet.blueberry.wallet.SendAmount

/** Coins, address, amount and fee a [ReviewBuild.Built] was signed for. */
internal data class OutpointStamp(
    val txid: String,
    val vout: Int,
    val valueSats: Long,
)

internal data class SendDraftStamp(
    val outpoints: List<OutpointStamp>,
    val address: String,
    val amount: SendAmount,
    val rate: Double,
)

internal sealed class ReviewBuild {
    data object Idle : ReviewBuild()

    data object Building : ReviewBuild()

    data class Built(
        val result: BuildSendResult,
        val draft: SendDraftStamp,
    ) : ReviewBuild()

    data class Failed(
        val message: String,
    ) : ReviewBuild()
}

internal fun ReviewBuild.isShort(): Boolean = this is ReviewBuild.Failed && message.contains("insufficient", ignoreCase = true)

internal class SendReviewActions(
    val onEditFee: () -> Unit,
    val onAddCoins: () -> Unit,
    val onPrivateSend: () -> Unit,
    val onCopyHex: () -> Unit,
    val onSend: () -> Unit,
    val onBack: () -> Unit,
)

internal class SendReviewState(
    val to: SendRecipient,
    val coins: List<WalletUtxoRow>,
    val amount: SendAmount,
    val rate: Double?,
    val build: ReviewBuild,
    val alerts: ReviewAlerts = ReviewAlerts(),
)

internal class ReviewAlerts(
    /** Why RocketX can't be used with this wallet, or null when it can. */
    val privateSendBlock: String? = null,
    val selectionNeedsAck: Boolean = false,
    val sendError: String? = null,
)

@Composable
internal fun SendReviewStep(
    state: SendReviewState,
    actions: SendReviewActions,
    showEdges: Boolean,
) {
    val built = (state.build as? ReviewBuild.Built)?.result
    val summary = built?.let { txSummary(state.coins, state.amount, builtSend(it), state.to.label) }
    SendStepFrame(
        title = "Review",
        caption = "Step 4 of 4",
        onBack = actions.onBack,
        showEdges = showEdges,
        menu =
            if (built != null && built !is PsbtSendResult) {
                listOf(HeaderMenuItem("Copy transaction hex", actions.onCopyHex))
            } else {
                emptyList()
            },
        bottom = { ReviewPrimaryButton(state, actions) },
    ) {
        if (state.to.label.isNotBlank()) ToPill(label = state.to.label, address = "")
        Box(modifier = Modifier.alpha(if (summary == null) 0.4f else 1f)) {
            AmountHero(sats = summary?.paidSats ?: (state.amount as? SendAmount.Exact)?.sats ?: 0L)
        }
        AddressGroups(
            address = state.to.address,
            modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp - BwSpace.Gap),
        )
        ReviewAlertText(state.alerts)
        if (summary != null) CoinsMoveCard(summary)
        FeeCard(state = state, summary = summary, actions = actions)
        val warning = summary?.let { feeShareWarning(it.feeSats, it.paidSats) }
        if (warning != null) {
            Text(warning, color = BwColors.Warning, fontFamily = BwFontFamily, fontSize = BwType.LabelSize)
        }
        PrivateSendRow(
            block = state.alerts.privateSendBlock,
            enabled = state.alerts.privateSendBlock == null && !state.alerts.selectionNeedsAck,
            onClick = actions.onPrivateSend,
        )
        if (summary != null) {
            Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Total leaving your wallet",
                    modifier = Modifier.weight(1f),
                    color = BwColors.InkQuiet,
                    fontFamily = BwFontFamily,
                    fontSize = BwType.BodySize,
                )
                BtcAmountText(sats = summary.totalLeavingSats, fontSize = BwType.BodySize)
            }
        }
    }
}

@Composable
private fun CoinsMoveCard(summary: TxSummary) {
    val description =
        "Spends ${summary.inputs.size} coins totaling ${formatBtc(summary.inputs.sumOf { it.valueSats })}. " +
            summary.outputs.joinToString(" ") { "${it.label}: ${formatBtc(it.sats)}." }
    SendCard(modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = description }) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            CardTitle("How your coins move", Modifier.weight(1f))
            Text(
                text = "${summary.inputs.size} in · ${summary.chainOutputs} out",
                color = BwColors.InkQuiet,
                fontFamily = BwFontFamily,
                fontSize = BwType.LabelSize,
            )
        }
        SectionLabel("From your coins")
        for (input in summary.inputs) {
            SummaryLine(sats = input.valueSats, label = input.title, dot = { CoinDot(input.argb) })
        }
        HorizontalDivider(color = BwColors.Section)
        SectionLabel("To")
        for (output in summary.outputs) {
            SummaryLine(
                sats = output.sats,
                label = output.label,
                emphasized = output.kind == TxOutputKind.Recipient,
                dot = { OutputDot(output.kind) },
            )
        }
    }
}

@Composable
private fun FeeCard(
    state: SendReviewState,
    summary: TxSummary?,
    actions: SendReviewActions,
) {
    SendCard {
        val count = state.coins.size
        SendChevronRow(
            title = "Change coins",
            value = if (count == 1) "1 coin" else "$count coins",
            valueColor = BwColors.InkQuiet,
            onClick = actions.onAddCoins,
        )
        HorizontalDivider(color = BwColors.Section)
        Column(
            modifier = Modifier.fillMaxWidth().clickable(onClick = actions.onEditFee),
            verticalArrangement = Arrangement.spacedBy(BwSpace.Gap),
        ) {
            SendChevronRow(
                title = "Network fee",
                value = state.rate?.let { "${formatFeeRate(it)} sat/vB" } ?: "Set a fee rate",
                valueColor = if (state.rate == null) BwColors.Accent else BwColors.Ink,
            )
            when {
                summary != null ->
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${summary.vsize} vB",
                            modifier = Modifier.weight(1f),
                            color = BwColors.InkQuiet,
                            fontFamily = BwFontFamily,
                            fontSize = BwType.LabelSize,
                        )
                        BtcAmountText(sats = summary.feeSats, fontSize = BwType.BodySize)
                    }
                state.build is ReviewBuild.Building -> HelperText("Building…")
                state.build.isShort() -> {
                    HelperText("These coins are short at this rate.", error = true)
                    TextAction(text = "Add coins", onClick = actions.onAddCoins)
                }
                state.build is ReviewBuild.Failed -> HelperText(state.build.message, error = true)
            }
        }
    }
}

@Composable
private fun PrivateSendRow(
    block: String?,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    SendCard(
        modifier =
            Modifier
                .alpha(if (enabled) 1f else 0.5f)
                .clickable(enabled = enabled, onClick = onClick),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                CardTitle("Send privately with RocketX")
                HelperText(block ?: "Pays the recipient from its own coins.")
            }
            Chevron(modifier = Modifier.padding(start = 6.dp))
        }
    }
}

@Composable
private fun SummaryLine(
    sats: Long,
    label: String?,
    emphasized: Boolean = false,
    dot: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        dot()
        Text(
            text = label.orEmpty(),
            modifier = Modifier.weight(1f),
            color = if (emphasized) BwColors.Accent else BwColors.InkQuiet,
            fontFamily = BwFontFamily,
            fontSize = BwType.LabelSize,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        BtcAmountText(sats = sats, color = if (emphasized) BwColors.Accent else BwColors.Ink, fontSize = BwType.LabelSize)
    }
}

@Composable
private fun OutputDot(kind: TxOutputKind) {
    val base = Modifier.size(12.dp).clip(CircleShape)
    when (kind) {
        TxOutputKind.Recipient -> Box(base.background(BwColors.Accent))
        TxOutputKind.Change -> Box(base.border(2.dp, BwColors.InkQuiet, CircleShape))
        TxOutputKind.Fee -> Box(base.background(BwColors.InkMuted))
    }
}

@Composable
internal fun CardTitle(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier,
        color = BwColors.Ink,
        fontFamily = BwFontFamily,
        fontSize = BwType.BodySize,
        fontWeight = BwType.Label,
    )
}

@Composable
private fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(text = text, modifier = modifier, color = BwColors.InkQuiet, fontFamily = BwFontFamily, fontSize = BwType.CaptionSize)
}
