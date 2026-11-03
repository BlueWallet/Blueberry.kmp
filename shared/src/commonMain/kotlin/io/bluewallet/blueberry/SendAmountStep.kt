package io.bluewallet.blueberry

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.bluewallet.blueberry.parse.isSendMaxAmount
import io.bluewallet.blueberry.ui.BtcAmountText
import io.bluewallet.blueberry.ui.BwColors
import io.bluewallet.blueberry.ui.BwFontFamily
import io.bluewallet.blueberry.ui.BwSpace
import io.bluewallet.blueberry.ui.BwType
import io.bluewallet.blueberry.ui.Pressable
import io.bluewallet.blueberry.ui.TintedPillButton
import io.bluewallet.blueberry.ui.ToPill
import io.bluewallet.blueberry.ui.numberFontFamily
import io.bluewallet.blueberry.wallet.SendAmount

/** Line under the amount when Max is on. A chosen coin is spent whole; otherwise the next step picks the coins. */
internal fun amountMaxHint(selectedCount: Int): String =
    when (selectedCount) {
        0 -> "Everything you pick next, minus the fee."
        1 -> "This coin, minus the fee."
        else -> "These coins, minus the fee."
    }

/** Suffix after the amount the Max button spends. */
internal fun amountCapSuffix(selectedCount: Int): String =
    when (selectedCount) {
        0 -> " in your wallet"
        1 -> " in this coin"
        else -> " in the selected coins"
    }

/** Shown when the typed amount is above the chosen coins; Continue goes to Coins to add more. */
internal fun amountOverCapMessage(selectedCount: Int): String =
    if (selectedCount == 1) {
        "More than this coin holds. Add coins on the next step."
    } else {
        "More than the selected coins hold. Add coins on the next step."
    }

/** Line under the amount and whether it is an error. Only the wallet limits the amount; a short pick is a note. */
internal fun amountNotice(
    amount: String,
    balance: SendAmountBalance,
): Pair<String, Boolean>? {
    val check = checkSendAmount(amount, balance.walletSats)
    val sats = ((check as? SendAmountCheck.Ok)?.amount as? SendAmount.Exact)?.sats
    val empty = noSpendableNotice(balance.walletSats, balance.availability)
    return when {
        empty != null -> empty to true
        check == SendAmountCheck.MoreThanWallet -> "More than your wallet holds." to true
        balance.selectedCount > 0 && sats != null && sats > balance.capSats -> amountOverCapMessage(balance.selectedCount) to false
        else -> null
    }
}

private val numberPadRows =
    listOf(
        listOf('1', '2', '3'),
        listOf('4', '5', '6'),
        listOf('7', '8', '9'),
        listOf('.', '0', NUMBER_PAD_BACKSPACE),
    )

internal class SendAmountActions(
    val onAmount: (String) -> Unit,
    val onContinue: () -> Unit,
    val onBack: () -> Unit,
    /** False while coins held by other payments are still loading. */
    val ready: Boolean = true,
)

/** What Max spends, the spendable wallet, value held by payments still sending, and why nothing may be spendable. */
internal class SendAmountBalance(
    /** The chosen coins, or the wallet when none are chosen. Shown next to Max. */
    val capSats: Long,
    /** Everything spendable; the typed amount is checked against this. */
    val walletSats: Long,
    val sendingSats: Long,
    val availability: CoinAvailability,
    val selectedCount: Int = 0,
)

@Composable
internal fun SendAmountStep(
    to: SendRecipient,
    amount: String,
    balance: SendAmountBalance,
    actions: SendAmountActions,
) {
    val max = isSendMaxAmount(amount)
    val check = checkSendAmount(amount, balance.walletSats)
    val notice = amountNotice(amount, balance)
    SendStepFrame(
        title = "Amount",
        caption = "Step 2 of 4",
        onBack = actions.onBack,
        bottom = {
            TintedPillButton(
                text = amountContinueLabel(amount, balance.walletSats, balance.availability),
                onClick = actions.onContinue,
                enabled = check is SendAmountCheck.Ok && actions.ready,
                modifier = Modifier.fillMaxWidth(),
            )
        },
    ) {
        ToPill(label = to.label, address = to.address)
        TypedAmount(amount = amount, max = max)
        if (max) HelperText(amountMaxHint(balance.selectedCount), Modifier.fillMaxWidth())
        if (notice != null) HelperText(notice.first, Modifier.fillMaxWidth(), error = notice.second)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                BtcAmountText(sats = balance.capSats, color = BwColors.InkQuiet, fontSize = BwType.LabelSize)
                Text(
                    amountCapSuffix(balance.selectedCount),
                    color = BwColors.InkQuiet,
                    fontFamily = BwFontFamily,
                    fontSize = BwType.LabelSize,
                )
                if (balance.selectedCount == 0 && balance.sendingSats > 0L) {
                    Text(" · ", color = BwColors.InkQuiet, fontFamily = BwFontFamily, fontSize = BwType.LabelSize)
                    BtcAmountText(sats = balance.sendingSats, color = BwColors.InkQuiet, fontSize = BwType.LabelSize)
                    Text(" sending", color = BwColors.InkQuiet, fontFamily = BwFontFamily, fontSize = BwType.LabelSize)
                }
            }
            MaxChip(selected = max, onClick = { actions.onAmount(if (max) "" else "MAX") })
        }
        NumberPad(onKey = { key -> actions.onAmount(numberPadInput(amount, key)) })
    }
}

@Composable
private fun TypedAmount(
    amount: String,
    max: Boolean,
) {
    val quiet = BwColors.InkMuted
    val ink = BwColors.Ink
    Text(
        text =
            buildAnnotatedString {
                if (max) {
                    withStyle(SpanStyle(color = ink)) { append("Max") }
                } else {
                    withStyle(SpanStyle(color = quiet)) { append("B") }
                    withStyle(SpanStyle(color = if (amount.isEmpty()) quiet else ink)) { append(amount.ifEmpty { "0" }) }
                }
            },
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
        textAlign = TextAlign.Center,
        fontFamily = numberFontFamily(BwType.Hero),
        fontWeight = BwType.Hero,
        fontSize = BwType.DisplayLargeSize,
        maxLines = 1,
    )
}

@Composable
private fun MaxChip(
    selected: Boolean,
    onClick: () -> Unit,
) {
    Text(
        text = "Max",
        color = if (selected) BwColors.OnAccent else BwColors.Accent,
        fontFamily = BwFontFamily,
        fontSize = BwType.LabelSize,
        fontWeight = BwType.Label,
        modifier =
            Modifier
                .clip(RoundedCornerShape(50))
                .background(if (selected) BwColors.Accent else BwColors.AccentSoft)
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

@Composable
private fun NumberPad(onKey: (Char) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = BwSpace.Gap),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        for (row in numberPadRows) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                for (key in row) {
                    NumberKey(key = key, onKey = onKey, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun NumberKey(
    key: Char,
    onKey: (Char) -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = if (key == NUMBER_PAD_BACKSPACE) "⌫" else key.toString()
    Pressable(
        onClick = { onKey(key) },
        modifier =
            modifier
                .height(56.dp)
                .semantics { contentDescription = if (key == NUMBER_PAD_BACKSPACE) "Delete" else label },
        role = Role.Button,
    ) {
        Box(
            modifier = Modifier.fillMaxSize().clip(CircleShape).background(BwColors.Card),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                color = BwColors.Ink,
                fontFamily = numberFontFamily(BwType.Value),
                fontWeight = BwType.Value,
                fontSize = BwType.ValueSize,
            )
        }
    }
}
