package io.bluewallet.blueberry

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.bluewallet.blueberry.ui.BwColors
import io.bluewallet.blueberry.ui.BwFontFamily
import io.bluewallet.blueberry.ui.BwType
import io.bluewallet.blueberry.ui.LocalDarkPalette
import io.bluewallet.blueberry.ui.Pressable
import io.bluewallet.blueberry.ui.numberFontFamily

@Composable
@Suppress("LongMethod")
fun CoinsPanel(
    snap: WalletTxsSnapshot,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val control =
        remember(snap) {
            coinControlSnapshotFrom(snap)
        }
    val syncing = !snap.utxosReady
    val placeholder = syncing && snap.utxos.isEmpty()
    val dimmed = syncing && snap.utxos.isNotEmpty()
    val shape = RoundedCornerShape(28.dp)
    val a11y =
        remember(control) {
            coinControlAccessibilityLabel(
                control.coins.size,
                control.groups.filter { it.linked },
                control.soloCount,
            )
        }
    Pressable(
        onClick = onClick,
        modifier =
            modifier
                .fillMaxWidth()
                .semantics { contentDescription = a11y },
        role = Role.Button,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(BwColors.Card, shape)
                    .padding(horizontal = 20.dp, vertical = 16.dp)
                    .then(if (dimmed) Modifier.alpha(0.5f) else Modifier),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CoinsPanelHeader(control = control, placeholder = placeholder)
            when {
                placeholder -> CoinsPanelPlaceholderStrip()
                control.coins.isEmpty() -> {
                    Text(
                        text = "Receive bitcoin to see your coins here",
                        color = BwColors.InkSecondary,
                        fontFamily = BwFontFamily,
                        fontSize = 12.sp,
                    )
                }
                else -> CoinsPanelBubbleStrip(bubbles = control.bubbles)
            }
            if (!placeholder) {
                val linked = control.groups.filter { it.linked }
                if (linked.any { it.provable }) {
                    CoinsPanelLegend(provable = true, text = "Outlined coins already share on-chain history")
                }
                if (linked.any { !it.provable }) {
                    CoinsPanelLegend(provable = false, text = "Dashed coins are probably linked through change")
                }
            }
        }
    }
}

/** Solid outline for provable groups, dashed for groups joined only by a change guess. */
private fun DrawScope.groupOutline(provable: Boolean): Stroke =
    Stroke(
        width = 1.dp.toPx(),
        pathEffect = if (provable) null else PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx())),
    )

@Composable
private fun CoinsPanelLegend(
    provable: Boolean,
    text: String,
) {
    val dark = LocalDarkPalette.current
    val legendFill = BwColors.Ink.copy(alpha = if (dark) 0.06f else 0.05f)
    val legendStroke =
        if (dark) {
            BwColors.Ink.copy(alpha = 0.28f)
        } else {
            BwColors.Border
        }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(width = 22.dp, height = 12.dp)
                    .clip(RoundedCornerShape(50))
                    .background(legendFill, RoundedCornerShape(50)),
        ) {
            Canvas(Modifier.fillMaxSize()) {
                drawRoundRect(
                    color = legendStroke,
                    style = groupOutline(provable),
                    cornerRadius = CornerRadius(size.height / 2f, size.height / 2f),
                )
            }
        }
        Text(
            text = text,
            color = BwColors.InkSecondary,
            fontFamily = BwFontFamily,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun CoinsPanelHeader(
    control: CoinControlSnapshot,
    placeholder: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = if (placeholder) "—" else formatGrouped(control.coins.size),
                color = BwColors.Ink,
                fontFamily = numberFontFamily(BwType.Value),
                fontSize = 28.sp,
                fontWeight = BwType.Value,
            )
            Text(
                text = if (control.coins.size == 1) "coin" else "coins",
                color = BwColors.InkSecondary,
                fontFamily = BwFontFamily,
                fontSize = 15.sp,
                modifier = Modifier.padding(bottom = 3.dp),
            )
        }
        if (!placeholder && control.coins.isNotEmpty()) {
            val right = coinControlHeaderRight(control.linkedGroupCount, control.soloCount)
            Text(
                text = right,
                color = BwColors.InkSecondary,
                fontFamily = BwFontFamily,
                fontSize = 14.sp,
            )
        }
    }
}

/** Vertical room for the largest bubble plus linked-group stadium padding. */
private val BubbleStripGroupPad = 5.dp
private val BubbleStripMaxHeight = 66.dp

@Composable
private fun CoinsPanelPlaceholderStrip() {
    Row(
        modifier = Modifier.fillMaxWidth().height(40.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(5) {
            Box(
                modifier =
                    Modifier
                        .size(20.dp)
                        .clip(RoundedCornerShape(50))
                        .background(BwColors.InkMuted.copy(alpha = 0.25f)),
            )
        }
    }
}

@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod")
private fun CoinsPanelBubbleStrip(bubbles: List<CoinControlBubble>) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterStart,
    ) {
        val density = LocalDensity.current
        val layout =
            remember(bubbles, maxWidth) {
                layoutBubbleStrip(
                    bubbles = bubbles,
                    availableWidthDp = maxWidth.value,
                )
            }
        val maxRadiusDp = layout.items.maxOfOrNull { it.radiusDp } ?: 10f
        val stripHeight =
            (maxRadiusDp.dp * 2 + BubbleStripGroupPad * 2).coerceAtMost(BubbleStripMaxHeight)
        // Light card + Ink@18% reads as white; use the theme border for a visible outline.
        val dark = LocalDarkPalette.current
        val groupFill = BwColors.Ink.copy(alpha = if (dark) 0.06f else 0.05f)
        val groupStroke =
            if (dark) {
                BwColors.Ink.copy(alpha = 0.18f)
            } else {
                BwColors.Border
            }
        val frozenStroke = BwColors.InkMuted
        Canvas(modifier = Modifier.fillMaxWidth().height(stripHeight)) {
            val cy = size.height / 2f
            var x = 0f
            val gapInGroup = 3.dp.toPx()
            val gapGroups = 8.dp.toPx()
            val gapIndep = 5.dp.toPx()
            val pad = 5.dp.toPx()
            var i = 0
            val items = layout.items
            while (i < items.size) {
                val gid = items[i].linkedGroupId
                if (gid != null) {
                    val start = i
                    while (i < items.size && items[i].linkedGroupId == gid) i++
                    val group = items.subList(start, i)
                    val diameters = group.map { with(density) { it.radiusDp.dp.toPx() } * 2f }
                    val inner = diameters.sum() + gapInGroup * (group.size - 1)
                    val stadiumW = inner + pad * 2f
                    val stadiumH = (diameters.maxOrNull() ?: 0f) + pad * 2f
                    drawRoundRect(
                        color = groupFill,
                        topLeft = Offset(x, cy - stadiumH / 2f),
                        size = Size(stadiumW, stadiumH),
                        cornerRadius = CornerRadius(stadiumH / 2f, stadiumH / 2f),
                    )
                    drawRoundRect(
                        color = groupStroke,
                        topLeft = Offset(x, cy - stadiumH / 2f),
                        size = Size(stadiumW, stadiumH),
                        cornerRadius = CornerRadius(stadiumH / 2f, stadiumH / 2f),
                        style = groupOutline(group.first().provable),
                    )
                    var cx = x + pad
                    for ((idx, item) in group.withIndex()) {
                        val r = with(density) { item.radiusDp.dp.toPx() }
                        cx += r
                        drawCoinCircle(Offset(cx, cy), r, item, frozenStroke)
                        cx += r
                        if (idx < group.lastIndex) cx += gapInGroup
                    }
                    x += stadiumW
                    if (i < items.size) x += gapGroups
                } else {
                    val item = items[i]
                    val r = with(density) { item.radiusDp.dp.toPx() }
                    x += r
                    drawCoinCircle(Offset(x, cy), r, item, frozenStroke)
                    x += r
                    i++
                    if (i < items.size) {
                        x += if (items[i].linkedGroupId != null) gapGroups else gapIndep
                    }
                }
            }
        }
        if (layout.overflowCount > 0) {
            Text(
                text = "+${layout.overflowCount}",
                color = BwColors.InkSecondary,
                fontFamily = BwFontFamily,
                fontSize = 12.sp,
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
    }
}

private fun DrawScope.drawCoinCircle(
    center: Offset,
    radius: Float,
    item: BubbleLayoutItem,
    frozenStroke: Color,
) {
    if (item.isFrozen) {
        drawCircle(
            color = frozenStroke,
            radius = radius,
            center = center,
            style = Stroke(width = 1.2.dp.toPx()),
        )
    } else {
        drawCircle(
            color = Color(item.colorArgb),
            radius = radius,
            center = center,
        )
    }
}
