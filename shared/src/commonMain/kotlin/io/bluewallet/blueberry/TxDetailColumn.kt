package io.bluewallet.blueberry

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.bluewallet.blueberry.ui.AddressGroups
import io.bluewallet.blueberry.ui.BwColors
import io.bluewallet.blueberry.ui.BwFontFamily
import io.bluewallet.blueberry.ui.Pressable
import io.bluewallet.blueberry.ui.ToPill

@Suppress("LongParameterList")
internal class TxDetailHost(
    val copied: Boolean,
    val editing: Boolean,
    val draft: String,
    val suggestions: List<String>,
    val privateFacts: List<Pair<String, String>>,
    val onBack: () -> Unit,
    val onCopy: () -> Unit,
    val onEdit: () -> Unit,
    val onDraft: (String) -> Unit,
    val onSave: () -> Unit,
    val onCancel: () -> Unit,
    val onFreeze: (String, Boolean) -> Unit,
    val onOpenCoin: (String) -> Unit,
    val onOpenTx: (String) -> Unit,
    val onExplorer: () -> Unit,
    val sendActions: @Composable () -> Unit,
)

@Composable
internal fun TxDetailColumn(
    screen: TxScreen,
    host: TxDetailHost,
) {
    val detail = screen.detail
    Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
        TxHeroBlock(detail)
        TxStatusCard(detail, host.onOpenTx)
        host.sendActions()
        TxLabelCard(detail, host)
        TxFlowCard(detail, host)
        TxRevealCard(detail, host)
        TxFactsCard(detail.details, host.privateFacts, host.onCopy)
        TxExplorerRow(host.onExplorer)
    }
}

@Composable
private fun TxHeroBlock(detail: TxDetail) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val date = detail.header.subtitle
        if (date.isNotEmpty()) {
            Text(date, color = BwColors.InkQuiet, fontFamily = BwFontFamily, fontSize = 15.sp)
        }
        TxAmountLine(detail.hero.amount, txAmountTone(detail.direction, detail.outcome != TxOutcome.None), 44.sp, detail.outcome != TxOutcome.None)
        if (detail.hero.sub.isNotEmpty()) {
            Text(detail.hero.sub, color = BwColors.InkSecondary, fontFamily = BwFontFamily, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
        val label = detail.hero.toLabel
        if (label != null) {
            val more = if (detail.hero.toMore > 0) " and ${detail.hero.toMore} more" else ""
            ToPill(label = label + more, address = "")
            val address = detail.hero.toAddress
            if (address != null) AddressGroups(address, Modifier.fillMaxWidth())
        }
    }
}

@Composable
internal fun GroupedHash(
    value: String,
    leadingZeros: Int,
) {
    Text(
        text = addressGroups(value).joinToString(" "),
        color = BwColors.Ink,
        fontFamily = BwFontFamily,
        fontSize = 14.sp,
        textAlign = TextAlign.Start,
        modifier = Modifier.semantics { contentDescription = value },
    )
    if (leadingZeros > 0) {
        Text(
            text = "The leading zeros are the proof of work.",
            color = BwColors.InkSecondary,
            fontFamily = BwFontFamily,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun TxStatusHeading(status: TxStatus) {
    val pending = status.cells.any { it == TxStripCell.Pending }
    val blueFg = txStatusBlueFg()
    val blueBg = txStatusBlueBg()
    val iconFg = if (status.failed) TxAmber else blueFg
    val iconBg = if (status.failed) Color(0xFF3A2C17) else blueBg
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(34.dp).clip(CircleShape).background(iconBg), contentAlignment = Alignment.Center) {
            Text(if (pending || status.failed) "◷" else "✓", color = iconFg, fontSize = 16.sp)
        }
        Column(Modifier.weight(1f)) {
            Text(
                status.title,
                color = if (status.failed) TxAmber else BwColors.Ink,
                fontFamily = BwFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                letterSpacing = (-0.45).sp,
            )
            if (status.sub.isNotEmpty()) {
                Text(status.sub, color = BwColors.InkSecondary, fontFamily = BwFontFamily, fontSize = 13.sp)
            }
        }
        val badge = status.badge
        if (badge != null) {
            Text(
                badge,
                color = blueFg,
                fontFamily = BwFontFamily,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.45).sp,
                modifier =
                    Modifier
                        .clip(
                            androidx.compose.foundation.shape
                                .RoundedCornerShape(13.dp),
                        ).background(blueBg)
                        .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
    }
}

@Composable
private fun TxStatusCard(
    detail: TxDetail,
    onOpenTx: (String) -> Unit,
) {
    val status = detail.status
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(
                    androidx.compose.foundation.shape
                        .RoundedCornerShape(24.dp),
                ).background(txRaisedCard())
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TxStatusHeading(status)
        TxStripRow(status.cells, 26.dp, 7.dp, 4.dp, mini = false, modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(status.left, color = BwColors.InkSecondary, fontFamily = BwFontFamily, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            val right = status.right
            if (right != null) {
                Text(right, color = BwColors.InkSecondary, fontFamily = BwFontFamily, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        if (status.note.isNotEmpty()) {
            Text(status.note, color = BwColors.InkQuiet, fontFamily = BwFontFamily, fontSize = 13.sp)
        }
        val replacement = status.replacementTxid
        if (replacement != null) {
            Pressable(onClick = { onOpenTx(replacement) }, role = Role.Button) {
                Text("Open replacement", color = TxAmber, fontFamily = BwFontFamily, fontWeight = FontWeight.Bold)
            }
        }
        if (status.speedUp) {
            Pressable(onClick = {}, role = Role.Button) {
                Text("Speed up", color = TxFrozen, fontFamily = BwFontFamily, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp))
            }
        }
    }
}
