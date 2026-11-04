@file:Suppress("TooManyFunctions", "LongParameterList")

package io.bluewallet.blueberry

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import blueberry.shared.generated.resources.Res
import blueberry.shared.generated.resources.badge_frozen
import blueberry.shared.generated.resources.badge_warning
import blueberry.shared.generated.resources.receive_icon
import blueberry.shared.generated.resources.send_icon
import blueberry.shared.generated.resources.tx_failed
import blueberry.shared.generated.resources.tx_self
import io.bluewallet.blueberry.ui.BwColors
import io.bluewallet.blueberry.ui.LocalDarkPalette
import io.bluewallet.blueberry.ui.numberFontFamily
import io.bluewallet.blueberry.ui.rememberReducedMotion
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

internal val TxBlue = Color(0xFF4A9BEA)
internal val TxAmber = Color(0xFFE0A24A)
internal val TxGlyphMuted = Color(0xFF6E757C)
internal val TxMoved = Color(0xFF8E959C)
internal val TxFrozen = Color(0xFF9CC8F0)
internal val TxFailedTitle = Color(0xFFC9CED3)

@Composable
internal fun txAmountTone(
    direction: TxDirection,
    struck: Boolean,
): Color =
    when {
        struck -> TxGlyphMuted
        direction == TxDirection.In -> TxBlue
        else -> BwColors.Ink
    }

@Composable
internal fun TxAmountLine(
    parts: TxAmountParts,
    tone: Color,
    fontSize: TextUnit,
    struck: Boolean,
) {
    val decoration = if (struck) TextDecoration.LineThrough else null
    Text(
        text =
            buildAnnotatedString {
                val digit = if (struck) TxGlyphMuted else tone
                withStyle(SpanStyle(color = TxGlyphMuted)) {
                    append(parts.sign)
                    append("B")
                }
                withStyle(SpanStyle(color = digit)) { append(parts.significant) }
                withStyle(SpanStyle(color = TxGlyphMuted)) { append(parts.trailingZeros) }
            },
        style =
            TextStyle(
                fontFamily = numberFontFamily(FontWeight.Bold),
                fontWeight = FontWeight.Bold,
                fontSize = fontSize,
                textDecoration = decoration,
            ),
        maxLines = 1,
        softWrap = false,
    )
}

@Composable
internal fun TxDirectionIcon(
    glyph: TxGlyph,
    pending: Boolean,
    size: Dp,
    badge: TxBadge,
) {
    Box(Modifier.size(size)) {
        val pendingRing = pending && (glyph == TxGlyph.Received || glyph == TxGlyph.Sent)
        val ringColor = if (glyph == TxGlyph.Received) TxBlue else BwColors.Ink
        if (pendingRing) {
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(
                    color = ringColor,
                    radius = size.toPx() / 2f - 1.dp.toPx(),
                    style =
                        Stroke(
                            width = 1.5.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f)),
                            cap = StrokeCap.Butt,
                        ),
                )
            }
        }
        val iconSize = if (pendingRing) size * 0.62f else size
        Image(
            painter = painterResource(glyphResource(glyph)),
            contentDescription = null,
            modifier = Modifier.size(iconSize).align(Alignment.Center),
            colorFilter = glyphTint(glyph),
        )
        TxBadgeIcon(badge, Modifier.align(Alignment.BottomEnd).offset(x = 4.dp, y = 4.dp))
    }
}

@Composable
private fun glyphTint(glyph: TxGlyph): ColorFilter? =
    when (glyph) {
        TxGlyph.Received -> ColorFilter.tint(TxBlue)
        TxGlyph.Sent -> ColorFilter.tint(BwColors.Ink)
        TxGlyph.Moved -> null
        TxGlyph.Failed -> ColorFilter.tint(TxAmber)
    }

private fun glyphResource(glyph: TxGlyph): DrawableResource =
    when (glyph) {
        TxGlyph.Received -> Res.drawable.receive_icon
        TxGlyph.Sent -> Res.drawable.send_icon
        TxGlyph.Moved -> Res.drawable.tx_self
        TxGlyph.Failed -> Res.drawable.tx_failed
    }

@Composable
private fun TxBadgeIcon(
    badge: TxBadge,
    modifier: Modifier,
) {
    if (badge == TxBadge.None) return
    val frozen = badge == TxBadge.Frozen
    val ring = txListCard()
    Image(
        painter = painterResource(if (frozen) Res.drawable.badge_frozen else Res.drawable.badge_warning),
        contentDescription = null,
        modifier =
            modifier
                .size(16.dp)
                .drawBehind {
                    drawCircle(color = ring, radius = size.minDimension / 2f + 2.dp.toPx())
                },
        colorFilter = if (frozen) null else ColorFilter.tint(TxAmber),
    )
}

@Composable
internal fun TxStripRow(
    cells: List<TxStripCell>,
    height: Dp,
    radius: Dp,
    gap: Dp,
    mini: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(gap)) {
        cells.forEach { cell ->
            val cellModifier =
                if (mini) {
                    Modifier.size(width = 8.dp, height = 6.dp)
                } else {
                    Modifier.weight(1f).height(height)
                }
            StripCell(cell, radius, mini, cellModifier)
        }
    }
}

private class StripTone(
    val full: Color,
    val mini: Color,
)

private fun stripTone(
    dark: Boolean,
    mini: Boolean,
    onDark: StripTone,
    onLight: StripTone,
): Color {
    val tone = if (dark) onDark else onLight
    return if (mini) tone.mini else tone.full
}

@Composable
internal fun txStatusBlueFg(): Color = if (LocalDarkPalette.current) Color(0xFF9CC8F0) else Color(0xFF1565C0)

@Composable
internal fun txStatusBlueBg(): Color = if (LocalDarkPalette.current) Color(0xFF1C2D3D) else Color(0xFFD6E8FA)

@Composable
private fun StripCell(
    cell: TxStripCell,
    radius: Dp,
    mini: Boolean,
    modifier: Modifier,
) {
    val dark = LocalDarkPalette.current
    val confirmed =
        stripTone(
            dark,
            mini,
            StripTone(Color(0xFF1E4E86), Color(0xFF3D7AB8)),
            StripTone(Color(0xFF8EBAEF), Color(0xFF5C9AE0)),
        )
    val pendingFill = if (dark) Color(0xFF0B1A28) else Color(0xFFE7F1FC)
    val empty =
        stripTone(
            dark,
            mini,
            StripTone(Color(0xFF2E3338), Color(0xFF3A3F44)),
            StripTone(Color(0xFFD5D9E0), Color(0xFFC5CAD3)),
        )
    if (cell == TxStripCell.Pending && !mini) {
        PendingBlockCell(radius, dark, pendingFill, modifier)
        return
    }
    Canvas(
        modifier.clip(
            androidx.compose.foundation.shape
                .RoundedCornerShape(radius),
        ),
    ) {
        val corner = CornerRadius(radius.toPx())
        val dash = PathEffect.dashPathEffect(floatArrayOf(4f, 3f))
        when (cell) {
            TxStripCell.Block -> drawRoundRect(TxBlue, cornerRadius = corner)
            TxStripCell.Confirmed -> drawRoundRect(confirmed, cornerRadius = corner)
            TxStripCell.Pending -> {
                drawRoundRect(pendingFill, cornerRadius = corner)
                drawRoundRect(
                    TxBlue,
                    cornerRadius = corner,
                    style = Stroke(width = 1.5.dp.toPx(), pathEffect = dash),
                )
            }
            TxStripCell.Empty ->
                drawRoundRect(
                    color = empty,
                    cornerRadius = corner,
                    style = Stroke(width = 1.dp.toPx(), pathEffect = dash),
                )
        }
    }
}

/** The next block: a light swims across it, then the fill flashes, then it rests. */
@Composable
private fun PendingBlockCell(
    radius: Dp,
    dark: Boolean,
    fill: Color,
    modifier: Modifier,
) {
    val reduced = rememberReducedMotion()
    val transition = rememberInfiniteTransition(label = "pending-block")
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec =
            infiniteRepeatable(
                animation =
                    keyframes {
                        durationMillis = 1700
                        0f at 0 using LinearEasing
                        0.55f at 780 using FastOutSlowInEasing
                        0.75f at 1100 using LinearEasing
                        1f at 1700 using LinearEasing
                    },
            ),
        label = "pending-sweep",
    )
    Canvas(
        modifier.clip(
            androidx.compose.foundation.shape
                .RoundedCornerShape(radius),
        ),
    ) {
        drawPendingBlock(radius, fill, if (reduced) null else sweep, dark)
    }
}

private fun DrawScope.drawPendingBlock(
    radius: Dp,
    fill: Color,
    sweep: Float?,
    dark: Boolean,
) {
    val corner = CornerRadius(radius.toPx())
    val dash = PathEffect.dashPathEffect(floatArrayOf(4f, 3f))
    drawRoundRect(fill, cornerRadius = corner)
    if (sweep != null) drawPendingSwim(sweep, corner, dark)
    drawRoundRect(
        TxBlue,
        cornerRadius = corner,
        style = Stroke(width = 1.5.dp.toPx(), pathEffect = dash),
    )
}

private fun DrawScope.drawPendingSwim(
    progress: Float,
    corner: CornerRadius,
    dark: Boolean,
) {
    val sweepEnd = 0.55f
    if (progress <= sweepEnd) {
        val travel = progress / sweepEnd
        val band = size.width * 0.9f
        val start = travel * (size.width + band) - band
        drawRoundRect(
            brush =
                Brush.horizontalGradient(
                    0f to Color.Transparent,
                    0.42f to TxBlue.copy(alpha = if (dark) 0.35f else 0.22f),
                    0.5f to Color.White.copy(alpha = if (dark) 0.85f else 0.72f),
                    0.58f to TxBlue.copy(alpha = if (dark) 0.5f else 0.3f),
                    1f to Color.Transparent,
                    startX = start,
                    endX = start + band,
                ),
            cornerRadius = corner,
        )
    }
    val flash =
        when {
            progress < 0.55f -> 0f
            progress < 0.62f -> (progress - 0.55f) / 0.07f
            progress < 0.75f -> 1f - (progress - 0.62f) / 0.13f
            else -> 0f
        }
    if (flash > 0.01f) {
        drawRoundRect(TxBlue.copy(alpha = flash * if (dark) 0.55f else 0.38f), cornerRadius = corner)
    }
}

@Composable
internal fun txRaisedCard(): Color = if (io.bluewallet.blueberry.ui.LocalDarkPalette.current) Color(0xFF121416) else BwColors.Card

/** A fill that sits on a card: darker in dark mode, a gray field in light mode. */
@Composable
internal fun txInsetFill(): Color = if (io.bluewallet.blueberry.ui.LocalDarkPalette.current) Color(0xFF121416) else BwColors.InputFill

@Composable
internal fun txListCard(): Color = if (io.bluewallet.blueberry.ui.LocalDarkPalette.current) Color(0xFF1B1E21) else BwColors.Card

/** Freeze and Open coin sit on a white card, so light mode needs a gray that reads as a button. */
@Composable
internal fun txCoinActionFill(): Color = if (io.bluewallet.blueberry.ui.LocalDarkPalette.current) Color(0xFF1B1E21) else Color(0xFFD5D9E0)

@Composable
internal fun txRowDivider(): Color = if (io.bluewallet.blueberry.ui.LocalDarkPalette.current) Color(0xFF3A4148) else Color(0xFFE4E7EB)

internal fun Modifier.txCircleButton(): Modifier =
    this
        .size(44.dp)
        .clip(CircleShape)
