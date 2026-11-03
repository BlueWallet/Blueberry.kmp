package io.bluewallet.blueberry

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.bluewallet.blueberry.parse.CoinLink
import io.bluewallet.blueberry.parse.LinkNode
import io.bluewallet.blueberry.parse.LinkStep
import io.bluewallet.blueberry.parse.formatLinkAmount
import io.bluewallet.blueberry.parse.words

private const val MAX_LINK_ROWS = 5
private const val MAX_PATH_NODES = 7

/** Linked coins card: title, bubble strip, link rows, and the path to the selected coin. */
@Composable
internal fun LinkedCoinsCard(
    model: CoinSheetModel,
    selected: String?,
    onSelect: (String) -> Unit,
) {
    SheetCard(radius = 24.dp, modifier = Modifier) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SheetText(model.linkedTitle, 15.sp, SheetPalette.text, FontWeight.Bold, Modifier.weight(1f))
                SheetText(model.linkedAside, 13.sp)
            }
            if (model.syncing) SheetText("Still syncing. Some links may be missing.", 12.sp)
            model.strip?.let { CoinSheetBubbleStrip(it, selected) }
            if (model.links.isEmpty()) {
                NoLinks(model.addressUseCount)
            } else {
                LinkRows(model.links, selected, onSelect)
                model.links.find { it.other == selected }?.let { link ->
                    Box(Modifier.fillMaxWidth().height(1.dp).background(SheetPalette.tile))
                    LinkPath(link, model.unspentKeys)
                }
            }
        }
    }
}

@Composable
private fun NoLinks(addressUseCount: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SheetIconCircle(SheetGlyph.Check, SheetPalette.green, SheetPalette.greenFill, 30.dp)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SheetText("Nothing on-chain ties it to your other coins", 14.sp, SheetPalette.text, FontWeight.Bold)
            val second =
                if (addressUseCount > 1) {
                    "But its address was used $addressUseCount times. See below."
                } else {
                    "Fresh address, never spent alongside anything. The first coin you spend it with becomes its first link."
                }
            SheetText(second, 13.sp)
        }
    }
}

@Composable
private fun LinkRows(
    links: List<CoinLink>,
    selected: String?,
    onSelect: (String) -> Unit,
) {
    var showAll by remember(links.size) { mutableStateOf(false) }
    val shown = if (showAll) links else links.take(MAX_LINK_ROWS)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        shown.forEach { link -> LinkRow(link, link.other == selected) { onSelect(link.other) } }
        if (!showAll && links.size > MAX_LINK_ROWS) {
            SheetText(
                "Show all ${links.size}",
                14.sp,
                SheetPalette.accent,
                FontWeight.Bold,
                Modifier.heightIn(min = 44.dp).clickable(role = Role.Button) { showAll = true }.padding(12.dp),
            )
        }
    }
}

@Composable
private fun LinkRow(
    link: CoinLink,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 60.dp)
                .clip(shape)
                .background(if (selected) SheetPalette.tile else Color.Transparent, shape)
                .border(1.dp, if (selected) SheetPalette.selectedBorder else Color.Transparent, shape)
                .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(16.dp).background(Color(utxoCircleArgb(link.other)), CircleShape))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SheetText(
                link.steps
                    .lastOrNull()
                    ?.title
                    .orEmpty(),
                15.sp,
                SheetPalette.text,
                FontWeight.Bold,
            )
            SheetText(link.summary, 12.sp)
        }
        SheetText(formatLinkAmount(link.valueSat), 14.sp, SheetPalette.soft, FontWeight.Bold)
    }
}

@Composable
private fun LinkPath(
    link: CoinLink,
    unspent: Set<String>,
) {
    var expanded by remember(link.other) { mutableStateOf(false) }
    val steps = link.steps
    val collapse = !expanded && steps.size > MAX_PATH_NODES
    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        SheetText("HOW IT LINKS TO ${steps.lastOrNull()?.title.orEmpty().uppercase()}", 13.sp, weight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
        steps.forEachIndexed { i, step ->
            val hidden = collapse && i >= 3 && i < steps.size - 2
            if (collapse && i == 3) {
                SheetText(
                    "Show ${steps.size - 5} more steps",
                    13.sp,
                    SheetPalette.accent,
                    FontWeight.Bold,
                    Modifier.heightIn(min = 44.dp).clickable(role = Role.Button) { expanded = true }.padding(start = 34.dp, top = 12.dp),
                )
            }
            if (!hidden) PathStep(step, i, steps.size, unspent)
        }
        SheetText(link.note, 13.sp, SheetPalette.soft, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun PathStep(
    step: LinkStep,
    index: Int,
    total: Int,
    unspent: Set<String>,
) {
    val relation = step.relationToNext
    val spoken = "Step ${index + 1} of $total. ${step.title}, ${step.subtitle}." + (relation?.let { " ${it.words().replaceFirstChar(Char::uppercase)}." } ?: "")
    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min).clearAndSetSemantics { contentDescription = spoken },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.width(22.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            PathMarker(step.node, index == 0, unspent)
            if (relation != null) {
                Box(Modifier.width(2.dp).weight(1f).background(if (step.warning) SheetPalette.amber else SheetPalette.grey))
            }
        }
        Column(Modifier.weight(1f).padding(bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            SheetText(step.title, 14.sp, SheetPalette.text, FontWeight.Bold)
            SheetText(step.subtitle, 12.sp)
            if (relation != null) {
                val (fg, bg) = if (step.warning) SheetPalette.amber to SheetPalette.amberFill else SheetPalette.soft to SheetPalette.tile
                val shape = RoundedCornerShape(8.dp)
                SheetText(relation.words(), 12.sp, fg, FontWeight.Bold, Modifier.padding(top = 4.dp).background(bg, shape).padding(horizontal = 8.dp, vertical = 3.dp))
            }
        }
    }
}

@Composable
private fun PathMarker(
    node: LinkNode,
    open: Boolean,
    unspent: Set<String>,
) {
    when {
        node is LinkNode.Tx -> Box(Modifier.size(14.dp).background(SheetPalette.accent, RoundedCornerShape(4.dp)))
        node is LinkNode.Coin && node.outpoint in unspent -> {
            val ring = if (open) Modifier.border(2.dp, SheetPalette.text, CircleShape).padding(3.dp) else Modifier
            Box(ring.size(18.dp).background(Color(utxoCircleArgb(node.outpoint)), CircleShape))
        }
        else -> Box(Modifier.size(14.dp).border(2.dp, SheetPalette.secondary, CircleShape))
    }
}
