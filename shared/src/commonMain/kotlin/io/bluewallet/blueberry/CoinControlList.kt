package io.bluewallet.blueberry

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.bluewallet.blueberry.parse.formatBtc
import io.bluewallet.blueberry.ui.BtcAmountText
import io.bluewallet.blueberry.ui.BwColors
import io.bluewallet.blueberry.ui.BwFontFamily
import io.bluewallet.blueberry.ui.BwType

/** Frozen coins stay in the weight bar for scale but read as locked under the spendable headline. */
private const val FROZEN_BAR_ALPHA = 0.18f

@Composable
internal fun CoinWeightBar(
    coins: List<CoinControlCoin>,
    selectedKeys: Set<String>,
    modifier: Modifier = Modifier,
) {
    val hasSelection = selectedKeys.isNotEmpty()
    Canvas(modifier = modifier.clip(RoundedCornerShape(8.dp))) {
        val gaps = (coins.size - 1).coerceAtLeast(0)
        val gap = if (gaps == 0) 0f else minOf(2.dp.toPx(), size.width / 2f / gaps)
        val usable = (size.width - gap * gaps).coerceAtLeast(0f)
        val widths = coinWeightBarWidths(coins.map { it.valueSats }, usable, 3.dp.toPx())
        var x = 0f
        for ((index, coin) in coins.withIndex()) {
            val w = widths[index]
            val alpha =
                when {
                    coin.isFrozen -> FROZEN_BAR_ALPHA
                    !hasSelection || coin.key in selectedKeys -> 1f
                    else -> 0.28f
                }
            drawRoundRect(
                color = Color(coin.colorArgb).copy(alpha = alpha),
                topLeft = Offset(x, 0f),
                size = Size(w, size.height),
                cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
            )
            x += w
            if (index < coins.lastIndex) x += gap
        }
    }
}

@Composable
internal fun CoinFilterChips(
    counts: Map<CoinControlFilter, Int>,
    selected: CoinControlFilter,
    onSelect: (CoinControlFilter) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CoinControlFilter.entries.forEach { f ->
            val count = counts[f] ?: 0
            val active = f == selected
            val label =
                when (f) {
                    CoinControlFilter.All -> "All"
                    CoinControlFilter.Labeled -> "Labeled"
                    CoinControlFilter.Change -> "Change"
                    CoinControlFilter.NoLabel -> "No label"
                    CoinControlFilter.Frozen -> "Frozen"
                }
            val shape = RoundedCornerShape(18.dp)
            val bg = if (active) BwColors.AccentSoft else BwColors.Card
            val fg =
                when {
                    active -> BwColors.Accent
                    f == CoinControlFilter.NoLabel -> BwColors.Warning
                    else -> BwColors.InkSecondary
                }
            Text(
                text = "$label $count",
                color = fg,
                fontFamily = BwFontFamily,
                fontSize = 13.sp,
                modifier =
                    Modifier
                        .clip(shape)
                        .background(bg, shape)
                        .semantics { this.selected = active }
                        .clickable(
                            role = Role.Tab,
                            onClickLabel = coinFilterClickLabel(label, count, narrows = f != CoinControlFilter.All),
                        ) { onSelect(f) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
@Suppress("LongMethod")
internal fun CoinGroupSection(
    group: CoinControlGroup,
    selectedKeys: Set<String>,
    onToggle: (CoinControlCoin) -> Unit,
    onOpenSheet: ((CoinControlCoin) -> Unit)? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "${group.title} · ${group.coins.size}",
                color = BwColors.Ink,
                fontFamily = BwFontFamily,
                fontSize = 15.sp,
                fontWeight = BwType.Label,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = coinGroupSubtitle(group),
                    color = BwColors.InkSecondary,
                    fontFamily = BwFontFamily,
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f),
                )
                BtcAmountText(
                    sats = group.totalSats,
                    color = BwColors.InkSecondary,
                    trailingColor = BwColors.InkSecondary,
                    fontSize = 14.sp,
                    fontWeight = BwType.Value,
                    roundedDigits = true,
                )
            }
        }
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(BwColors.Card),
        ) {
            group.coins.forEachIndexed { index, coin ->
                key(coin.key, coin.isFrozen, coin.label, coin.privacyState) {
                    if (index > 0) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(BwColors.InkMuted.copy(alpha = 0.22f)),
                        )
                    }
                    CoinControlRow(
                        coin = coin,
                        selected = coin.key in selectedKeys,
                        onToggle = { onToggle(coin) },
                        onOpenSheet = onOpenSheet?.let { open -> { open(coin) } },
                    )
                }
            }
        }
    }
}

@Composable
@Suppress("LongMethod")
internal fun CoinControlRow(
    coin: CoinControlCoin,
    selected: Boolean,
    onToggle: () -> Unit,
    onOpenSheet: (() -> Unit)?,
) {
    val subtitle = coinRowSubtitle(coin)
    val tag = coinRowTag(coin)
    // The toggleable semantics announce the checked state and the hint; the label carries only the coin.
    val selectA11y =
        if (coin.isFrozen) {
            "${formatBtc(coin.valueSats)}, frozen, not selectable"
        } else {
            formatBtc(coin.valueSats)
        }
    val detailsA11y =
        buildString {
            append(formatBtc(coin.valueSats))
            append(", ")
            if (coin.isFrozen) append("frozen, ")
            append(subtitle)
            append(", ")
            append(coin.ageLabel)
            append(", opens coin details")
        }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(if (selected) BwColors.AccentSoft else Color.Transparent)
                .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier =
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .then(
                        if (coin.isFrozen) {
                            Modifier.semantics {
                                contentDescription = selectA11y
                                disabled()
                            }
                        } else {
                            Modifier
                                .semantics { contentDescription = selectA11y }
                                .toggleable(value = selected, role = Role.Checkbox, onValueChange = { onToggle() })
                        },
                    ).padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CoinSelector(selected = selected, frozen = coin.isFrozen)
            Box(
                modifier =
                    Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Color(coin.colorArgb), CircleShape),
            )
        }
        Row(
            modifier =
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .then(
                        when {
                            onOpenSheet != null ->
                                Modifier
                                    .semantics { contentDescription = detailsA11y }
                                    .clickable(role = Role.Button, onClick = onOpenSheet)
                            !coin.isFrozen -> Modifier.clickable(onClick = onToggle)
                            else -> Modifier
                        },
                    ).padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                BtcAmountText(
                    sats = coin.valueSats,
                    color = BwColors.Ink,
                    fontSize = 17.sp,
                    fontWeight = BwType.Value,
                    roundedDigits = true,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (tag != null) {
                        val (fg, bg) =
                            when (tag) {
                                "Frozen" -> BwColors.InkSecondary to BwColors.Section
                                "Near dust" -> BwColors.Warning to BwColors.Warning.copy(alpha = 0.16f)
                                "Change" -> BwColors.Accent to BwColors.AccentSoft
                                else -> BwColors.InkSecondary to BwColors.Section
                            }
                        Text(
                            text = tag,
                            color = fg,
                            fontFamily = BwFontFamily,
                            fontSize = 11.sp,
                            modifier =
                                Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(bg)
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                    Text(
                        text = subtitle,
                        color =
                            if (subtitle.startsWith("No label")) {
                                BwColors.Warning
                            } else {
                                BwColors.InkSecondary
                            },
                        fontFamily = BwFontFamily,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                text = coin.ageLabel,
                color = BwColors.InkSecondary,
                fontFamily = BwFontFamily,
                fontSize = 13.sp,
            )
        }
    }
}

@Composable
internal fun CoinSelector(
    selected: Boolean,
    frozen: Boolean,
) {
    if (frozen) {
        Box(
            modifier =
                Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(BwColors.Section),
            contentAlignment = Alignment.Center,
        ) {
            FrozenLockIcon(modifier = Modifier.size(12.dp), color = BwColors.InkMuted)
        }
    } else {
        Box(
            modifier =
                Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .then(
                        if (selected) {
                            Modifier.background(BwColors.Accent, CircleShape)
                        } else {
                            Modifier.border(2.dp, BwColors.InkMuted, CircleShape)
                        },
                    ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                CheckmarkIcon(modifier = Modifier.size(12.dp), color = BwColors.OnAccent)
            }
        }
    }
}

@Composable
internal fun CheckmarkIcon(
    modifier: Modifier = Modifier,
    color: Color,
) {
    Canvas(modifier = modifier) {
        val stroke =
            Stroke(
                width = 2.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            )
        val path =
            Path().apply {
                moveTo(size.width * 0.12f, size.height * 0.52f)
                lineTo(size.width * 0.38f, size.height * 0.78f)
                lineTo(size.width * 0.88f, size.height * 0.22f)
            }
        drawPath(path, color, style = stroke)
    }
}

@Composable
internal fun FrozenLockIcon(
    modifier: Modifier = Modifier,
    color: Color,
) {
    Canvas(modifier = modifier) {
        val stroke = 1.4.dp.toPx()
        val w = size.width
        val h = size.height
        val bodyTop = h * 0.42f
        val bodyHeight = h - bodyTop
        val bodyLeft = w * 0.18f
        val bodyWidth = w - bodyLeft * 2f
        val shackleCx = w / 2f
        val shackleR = bodyWidth * 0.32f
        val shackleTop = h * 0.08f
        drawArc(
            color = color,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(shackleCx - shackleR, shackleTop),
            size = Size(shackleR * 2f, shackleR * 2f),
            style = Stroke(width = stroke),
        )
        drawRoundRect(
            color = color,
            topLeft = Offset(bodyLeft, bodyTop),
            size = Size(bodyWidth, bodyHeight),
            cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
        )
    }
}
