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

@Composable
internal fun SendAmountStep(
    to: SendRecipient,
    amount: String,
    walletTotalSats: Long,
    coinsAllFrozen: Boolean,
    actions: SendAmountActions,
) {
    val max = isSendMaxAmount(amount)
    val check = checkSendAmount(amount, walletTotalSats)
    SendStepFrame(
        title = "Amount",
        caption = "Step 2 of 4",
        onBack = actions.onBack,
        bottom = {
            TintedPillButton(
                text = amountContinueLabel(amount, walletTotalSats, coinsAllFrozen),
                onClick = actions.onContinue,
                enabled = check is SendAmountCheck.Ok && actions.ready,
                modifier = Modifier.fillMaxWidth(),
            )
        },
    ) {
        ToPill(label = to.label, address = to.address)
        TypedAmount(amount = amount, max = max)
        if (max) HelperText("Everything you pick next, minus the fee.", Modifier.fillMaxWidth())
        if (coinsAllFrozen) {
            HelperText(ALL_FROZEN_NOTICE, Modifier.fillMaxWidth(), error = true)
        } else if (walletTotalSats <= 0L) {
            HelperText("No spendable coins left.", Modifier.fillMaxWidth(), error = true)
        } else if (check is SendAmountCheck.MoreThanWallet) {
            HelperText("More than your wallet holds.", Modifier.fillMaxWidth(), error = true)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                BtcAmountText(sats = walletTotalSats, color = BwColors.InkQuiet, fontSize = BwType.LabelSize)
                Text(" in your wallet", color = BwColors.InkQuiet, fontFamily = BwFontFamily, fontSize = BwType.LabelSize)
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
