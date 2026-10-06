package io.bluewallet.blueberry

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import io.bluewallet.blueberry.parse.formatSatPerVb
import io.bluewallet.blueberry.ui.BtcAmountText
import io.bluewallet.blueberry.ui.BwCheckbox
import io.bluewallet.blueberry.ui.BwColors
import io.bluewallet.blueberry.ui.BwFontFamily
import io.bluewallet.blueberry.ui.BwSpace
import io.bluewallet.blueberry.ui.BwType
import io.bluewallet.blueberry.ui.PillButton

fun privateSendProvider(): Pair<String, String> = "Provider" to "rocketx.exchange"

sealed class TermsSpan {
    data class Plain(
        val text: String,
    ) : TermsSpan()

    data class Link(
        val text: String,
        val url: String,
    ) : TermsSpan()
}

fun privateSendTermsSpans(): List<TermsSpan> =
    listOf(
        TermsSpan.Plain("I agree to the "),
        TermsSpan.Link(
            "Terms of Use",
            "https://cdn.rocketx.exchange/pd135zq/docs/rocketx-exchange-terms.pdf",
        ),
        TermsSpan.Plain(" and "),
        TermsSpan.Link(
            "Privacy Policy",
            "https://cdn.rocketx.exchange/pd135zq/docs/privacy-policy.pdf",
        ),
    )

fun privateSendTermsSentence(): String =
    privateSendTermsSpans().joinToString(separator = "") { span ->
        when (span) {
            is TermsSpan.Plain -> span.text
            is TermsSpan.Link -> span.text
        }
    }

fun privateBroadcastEnabled(agreedToTerms: Boolean): Boolean = agreedToTerms

@Composable
internal fun PrivateSendDeposit(
    state: PrivateSendUi.Deposit,
    broadcast: BroadcastSnapshot,
    onBroadcast: () -> Unit,
    finish: BroadcastFinishActions,
) {
    val showBroadcast = broadcast.txHex != null && broadcast.txHex == state.signed.txHex
    val broadcasting =
        showBroadcast &&
            (broadcastJobInFlight(broadcast.phase) || broadcast.phase == "success" || broadcast.phase == "error")
    if (broadcasting) {
        Column(verticalArrangement = Arrangement.spacedBy(BwSpace.Gap)) {
            BroadcastStatus(broadcast, finish = finish)
        }
        return
    }
    val clipboard = LocalClipboardManager.current
    var copiedOrder by remember(state.swap.requestId) { mutableStateOf(false) }
    var agreed by remember(state.swap.requestId) { mutableStateOf(false) }
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(BwSpace.Gap),
    ) {
        PrivateSendFacts(
            state = state,
            copiedOrder = copiedOrder,
            onCopyOrder = {
                clipboard.setText(AnnotatedString(state.swap.requestId))
                copiedOrder = true
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text =
                "This will create multi-hop transaction routed through 3rd parties to unlink your source funds from destination",
            color = BwColors.InkSecondary,
            fontFamily = BwFontFamily,
            fontSize = BwType.CaptionSize,
        )
        PrivateSendTermsRow(checked = agreed, onCheckedChange = { agreed = it })
        PillButton(
            text = "Private Broadcast",
            onClick = onBroadcast,
            modifier = Modifier.fillMaxWidth(),
            enabled = privateBroadcastEnabled(agreed),
        )
    }
}

@Composable
private fun PrivateSendTermsRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(BwSpace.Gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BwCheckbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(
            text = termsAgreement(checked, onCheckedChange),
            modifier = Modifier.weight(1f),
            color = BwColors.Ink,
            fontFamily = BwFontFamily,
            fontSize = BwType.BodySize,
        )
    }
}

@Composable
private fun termsAgreement(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
): AnnotatedString {
    val ink = BwColors.Ink
    val link = BwColors.Link
    return buildAnnotatedString {
        privateSendTermsSpans().forEach { span ->
            when (span) {
                is TermsSpan.Plain ->
                    withLink(
                        LinkAnnotation.Clickable(
                            tag = "agree",
                            styles =
                                TextLinkStyles(
                                    SpanStyle(color = ink, textDecoration = TextDecoration.None),
                                ),
                            linkInteractionListener = { onCheckedChange(!checked) },
                        ),
                    ) {
                        append(span.text)
                    }
                is TermsSpan.Link ->
                    withLink(
                        LinkAnnotation.Url(
                            url = span.url,
                            styles =
                                TextLinkStyles(
                                    SpanStyle(color = link, textDecoration = TextDecoration.Underline),
                                ),
                        ),
                    ) {
                        append(span.text)
                    }
            }
        }
    }
}

@Composable
private fun PrivateSendFacts(
    state: PrivateSendUi.Deposit,
    copiedOrder: Boolean,
    onCopyOrder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(BwSpace.Gap),
    ) {
        val provider = privateSendProvider()
        PrivateSendDetail(provider.first, provider.second)
        val amount = rocketxGetSats(state.swap.toAmount)
        if (amount != null) {
            PrivateSendDetail("Amount", sats = amount)
        }
        if (state.label.isNotEmpty()) {
            PrivateSendDetail("Label", state.label)
        }
        if (state.swap.requestId.isNotEmpty()) {
            PrivateSendDetail(
                label = "Order ID",
                value = state.swap.requestId,
                copied = copiedOrder,
                onClick = onCopyOrder,
            )
        }
        PrivateSendDetail("Time to complete", formatRocketxEta(state.swap.estSeconds))
        PrivateSendDetail(
            "Refund",
            "Failed send will be refunded to your address: ${state.refundAddress}",
        )
        PrivateSendDetail(label = "Transaction fee", sats = state.signed.feeSats)
        PrivateSendDetail("Fee rate", formatSatPerVb(state.signed.feeSats, state.signed.vsize))
        val service = rocketxServiceFeeSats(state.swap.fromAmount, state.swap.toAmount)
        if (service != null) {
            PrivateSendDetail("Service fee", sats = service)
        }
        val overpay = rocketxOverpaySats(state.swap.toAmount, state.needBtc)
        if (overpay != null) {
            Text(
                text = "warning: destination is slightly overpaid (by $overpay sat)",
                color = BwColors.Warning,
                fontFamily = BwFontFamily,
                fontSize = BwType.BodySize,
            )
        }
    }
}

@Composable
private fun PrivateSendDetail(
    label: String,
    value: String = "",
    sats: Long? = null,
    copied: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    Column(
        modifier =
            Modifier.fillMaxWidth().then(
                if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
            ),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = label,
            color = BwColors.InkSecondary,
            fontFamily = BwFontFamily,
            fontSize = BwType.LabelSize,
            fontWeight = BwType.Label,
        )
        if (sats != null) {
            BtcAmountText(sats = sats, color = BwColors.Ink, fontSize = BwType.BodySize)
        } else {
            Text(
                text = value,
                color = BwColors.Ink,
                fontFamily = BwFontFamily,
                fontSize = BwType.BodySize,
            )
        }
        if (copied) {
            Text(
                text = "Copied",
                color = BwColors.Success,
                fontFamily = BwFontFamily,
                fontSize = BwType.CaptionSize,
                fontWeight = BwType.Caption,
            )
        }
    }
}
