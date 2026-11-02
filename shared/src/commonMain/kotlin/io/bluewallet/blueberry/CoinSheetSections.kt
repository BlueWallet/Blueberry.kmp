package io.bluewallet.blueberry

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun SheetDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(SheetPalette.tile))
}

/** "If you spend it": one row per condition, in spec order. */
@Composable
internal fun SpendCard(rows: List<SpendRowModel>) {
    if (rows.isEmpty()) return
    SheetSectionHeader("If you spend it")
    SheetCard {
        rows.forEachIndexed { i, row ->
            if (i > 0) SheetDivider()
            SpendRow(row)
        }
    }
}

@Composable
private fun SpendRow(row: SpendRowModel) {
    val (glyph, tint, fill) =
        when (row.tone) {
            SpendTone.Green -> Triple(SheetGlyph.Check, SheetPalette.green, SheetPalette.greenFill)
            SpendTone.Amber -> Triple(SheetGlyph.Warn, SheetPalette.amber, SheetPalette.amberFill)
            SpendTone.Neutral -> Triple(SheetGlyph.Eye, SheetPalette.blue, SheetPalette.blueFill)
        }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SheetIconCircle(glyph, tint, fill, 30.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            val titleColor = if (row.tone == SpendTone.Amber) SheetPalette.amber else SheetPalette.text
            SheetText(row.title, 15.sp, titleColor, FontWeight.Bold)
            SheetText(row.subtitle, 13.sp)
        }
    }
}

/** Freeze switch and Send. Both disable once the coin is spent. */
@Composable
internal fun ActionsCard(
    model: CoinSheetModel,
    onFreeze: (Boolean) -> Unit,
    onSend: () -> Unit,
) {
    val spent = model.spentBanner != null
    SheetCard {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 70.dp)
                    .alpha(if (spent) 0.45f else 1f)
                    .toggleable(value = model.frozen, enabled = !spent, role = Role.Switch, onValueChange = onFreeze)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SheetIconCircle(SheetGlyph.Snow, SheetPalette.blue, SheetPalette.blueFill, 36.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SheetText("Freeze", 15.sp, SheetPalette.text, FontWeight.Bold)
                SheetText(if (model.frozen) "Never picked when you send" else "Keep coin selection from using it", 13.sp)
            }
            Switch(
                checked = model.frozen,
                onCheckedChange = null,
                enabled = !spent,
                colors =
                    SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = SheetPalette.accent,
                        checkedBorderColor = SheetPalette.accent,
                        uncheckedThumbColor = Color.White,
                        uncheckedTrackColor = SheetPalette.grey,
                        uncheckedBorderColor = SheetPalette.grey,
                    ),
            )
        }
        SheetDivider()
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 70.dp)
                    .alpha(if (model.sendEnabled) 1f else 0.45f)
                    .clickable(enabled = model.sendEnabled, role = Role.Button, onClick = onSend)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SheetIconCircle(SheetGlyph.Send, SheetPalette.accent, SheetPalette.accentFill, 36.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SheetText(model.sendTitle, 15.sp, SheetPalette.text, FontWeight.Bold)
                SheetText(model.sendSubtitle, 13.sp)
            }
            SheetGlyphView(SheetGlyph.Chevron, SheetPalette.secondary, Modifier.size(16.dp))
        }
    }
}

/** Accent pill used for Open transaction. */
@Composable
internal fun SheetPillAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(22.dp)
    Box(
        modifier =
            modifier
                .heightIn(min = 44.dp)
                .clip(shape)
                .background(SheetPalette.accentFill, shape)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        SheetText(text, 15.sp, SheetPalette.accent, FontWeight.Bold)
    }
}

/** Banner shown when the coin was spent while the sheet was open. */
@Composable
internal fun SpentBanner(
    text: String,
    onOpenTx: (() -> Unit)?,
) {
    SheetCard {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SheetIconCircle(SheetGlyph.Warn, SheetPalette.amber, SheetPalette.amberFill, 30.dp)
            SheetText(text, 15.sp, SheetPalette.amber, FontWeight.Bold, Modifier.weight(1f))
            if (onOpenTx != null) SheetPillAction("Open transaction", onOpenTx)
        }
    }
}
