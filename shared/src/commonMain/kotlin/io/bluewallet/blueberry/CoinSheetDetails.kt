package io.bluewallet.blueberry

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Address and Where it came from, collapsed by default. */
@Composable
internal fun DetailsCard(
    address: AddressSheet?,
    origin: OriginSheet?,
    onOpenTx: (String) -> Unit,
) {
    if (address == null && origin == null) return
    SheetCard {
        if (address != null) {
            Accordion("Address", address.collapsed) { AddressBody(address) }
        }
        if (address != null && origin != null) SheetDivider()
        if (origin != null) {
            Accordion("Where it came from", origin.collapsed) { OriginBody(origin) { onOpenTx(origin.txid) } }
        }
    }
}

@Composable
private fun Accordion(
    title: String,
    collapsed: String,
    body: @Composable () -> Unit,
) {
    var open by remember(title) { mutableStateOf(false) }
    val turn by animateFloatAsState(if (open) 90f else 0f)
    Column {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 66.dp)
                    .toggleable(value = open, role = Role.Button) { open = it }
                    .semantics { stateDescription = if (open) "Expanded" else "Collapsed" }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SheetText(title, 15.sp, SheetPalette.text, FontWeight.Bold)
                SheetText(collapsed, 13.sp)
            }
            SheetGlyphView(SheetGlyph.Chevron, SheetPalette.secondary, Modifier.size(16.dp).rotate(turn))
        }
        if (open) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) { body() }
        }
    }
}

@Composable
private fun QuadText(
    text: String,
    trailing: String? = null,
) {
    Text(
        text =
            buildAnnotatedString {
                quadGroups(text).forEachIndexed { i, group ->
                    if (i > 0) append(' ')
                    withStyle(SpanStyle(color = if (i % 2 == 0) SheetPalette.text else SheetPalette.secondary)) { append(group) }
                }
                if (trailing != null) {
                    withStyle(SpanStyle(color = SheetPalette.blue, background = SheetPalette.accentFill, fontWeight = FontWeight.Bold)) {
                        append(" $trailing ")
                    }
                }
            },
        fontFamily = FontFamily.Monospace,
        fontSize = 15.sp,
    )
}

@Composable
private fun AddressBody(address: AddressSheet) {
    QuadText(address.address)
    if (address.chips.isNotEmpty()) {
        SheetText("Derivation path", 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            address.chips.forEach { chip ->
                val fill = if (chip.accent) SheetPalette.blueFill else SheetPalette.tile
                Column(
                    modifier = Modifier.weight(1f).background(fill, RoundedCornerShape(12.dp)).padding(vertical = 8.dp, horizontal = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(chip.value, color = SheetPalette.text, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                    SheetText(chip.meaning, 11.sp)
                }
            }
        }
    }
    if (address.rawPath.isNotEmpty()) {
        Text(address.rawPath, color = SheetPalette.secondary, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    }
}

@Composable
private fun OriginBody(
    origin: OriginSheet,
    onOpenTx: () -> Unit,
) {
    SheetText("Outpoint", 13.sp)
    QuadText(origin.txid, trailing = ":${origin.vout}")
    SheetText(origin.body, 13.sp, SheetPalette.soft)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FactTile("Block", origin.block, Modifier.weight(1f))
        FactTile("Confirmations", origin.confirmations, Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FactTile("Age", origin.age, Modifier.weight(1f))
        FactTile("Spend size", origin.spendSize, Modifier.weight(1f))
    }
    SheetPillAction("Open transaction", onOpenTx, Modifier.fillMaxWidth())
}

@Composable
private fun FactTile(
    label: String,
    value: String,
    modifier: Modifier,
) {
    Column(
        modifier = modifier.background(SheetPalette.tile, RoundedCornerShape(14.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        SheetText(label, 12.sp)
        SheetText(value, 15.sp, SheetPalette.text, FontWeight.Bold)
    }
}
