package io.bluewallet.blueberry

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.bluewallet.blueberry.parse.styleBtc
import io.bluewallet.blueberry.ui.BwColors
import io.bluewallet.blueberry.ui.BwFontFamily
import io.bluewallet.blueberry.ui.BwType
import io.bluewallet.blueberry.ui.numberFontFamily
import kotlin.math.roundToInt

private val ChipShape = RoundedCornerShape(14.dp)
private const val FEE_DASH_ON_DP = 2f
private const val FEE_DASH_OFF_DP = 4f
private const val FLOW_AMOUNT_SP = 12.5f
private const val CHANGE_ARGB = 0xFFAEB4BA
private const val PAYMENT_ARGB = 0xFF3788E0
private const val MUTED_ARGB = 0xFF6E767D

/** Draws the coins and outputs. [description] is the only thing a screen reader hears for the whole graph. */
@Composable
internal fun TxFlowGraph(
    inputs: List<TxFlowNode>,
    outputs: List<TxFlowNode>,
    description: String,
) {
    SubcomposeLayout(
        modifier =
            Modifier
                .fillMaxWidth()
                .clearAndSetSemantics { contentDescription = description },
    ) { constraints ->
        val canvas = if (constraints.hasBoundedWidth) constraints.maxWidth.toDp().value else TX_FLOW_WIDTH_DP
        val preview = txFlowModel(inputs, outputs, widthDp = canvas)
        val nodes = preview.inputs.map { it.node } + preview.outputs.map { it.node }
        val placeables =
            nodes.mapIndexed { index, node ->
                subcompose(index) { FlowChip(node) }.first().measure(Constraints())
            }
        val measured = placeables.map { it.width.toDp().value }
        val widths = measured.iterator()
        val model = txFlowModel(inputs, outputs, widthDp = canvas) { widths.next() }
        val scale = minOf(1f, canvas / model.widthDp)
        val widthPx = model.widthDp.dp.roundToPx()
        val heightPx = model.heightDp.dp.roundToPx()
        val curves =
            subcompose("curves") { FlowCurves(model) }
                .first()
                .measure(Constraints.fixed(widthPx, heightPx))
        val merge = subcompose("merge") { MergeDot(model) }.first().measure(Constraints())
        val chips = model.inputs + model.outputs
        val pins =
            listOf(Triple(curves, 0f, 0f)) +
                placeables.mapIndexed { index, placeable ->
                    Triple(placeable, chips[index].xDp, chips[index].yDp)
                } +
                listOf(Triple(merge, 0f, 0f))
        layout((widthPx * scale).roundToInt(), (heightPx * scale).roundToInt()) {
            pins.forEach { (placeable, xDp, yDp) ->
                placeable.placeWithLayer(
                    (xDp.dp.toPx() * scale).roundToInt(),
                    (yDp.dp.toPx() * scale).roundToInt(),
                ) {
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(0f, 0f)
                }
            }
        }
    }
}

@Composable
private fun FlowCurves(model: TxFlowModel) {
    val glow = BwColors.Ink.copy(alpha = 0.25f)
    Canvas(Modifier.fillMaxSize()) {
        model.curves.forEach { drawCurve(it) }
        drawCircle(
            color = glow,
            radius = 12.dp.toPx(),
            center =
                Offset(
                    model.merge.xDp.dp
                        .toPx(),
                    model.merge.yDp.dp
                        .toPx(),
                ),
        )
    }
}

private fun DrawScope.drawCurve(curve: TxFlowCurve) {
    val path =
        Path().apply {
            moveTo(
                curve.start.xDp.dp
                    .toPx(),
                curve.start.yDp.dp
                    .toPx(),
            )
            val bend = (curve.end.xDp - curve.start.xDp) * 0.55f
            cubicTo(
                (curve.start.xDp + bend).dp.toPx(),
                curve.start.yDp.dp
                    .toPx(),
                (curve.end.xDp - bend).dp.toPx(),
                curve.end.yDp.dp
                    .toPx(),
                curve.end.xDp.dp
                    .toPx(),
                curve.end.yDp.dp
                    .toPx(),
            )
        }
    drawPath(
        path = path,
        color = Color(curve.colorArgb).copy(alpha = curve.alpha),
        style =
            Stroke(
                width = curve.widthDp.dp.toPx(),
                cap = StrokeCap.Round,
                pathEffect = if (curve.dotted) feeDash() else null,
            ),
    )
}

private fun DrawScope.feeDash(): PathEffect = PathEffect.dashPathEffect(floatArrayOf(FEE_DASH_ON_DP.dp.toPx(), FEE_DASH_OFF_DP.dp.toPx()))

@Composable
private fun FlowChip(node: TxFlowNode) {
    val payment = node.role == TxFlowRole.Payment
    val border = if (payment) BwColors.Accent.copy(alpha = 0.55f) else BwColors.Ink.copy(alpha = 0.06f)
    val fill = if (payment) BwColors.AccentSoft else BwColors.InputFill
    val labelColor = if (payment) BwColors.Accent else BwColors.InkQuiet
    Row(
        modifier =
            Modifier
                .height(TX_FLOW_CHIP_HEIGHT_DP.dp)
                .clip(ChipShape)
                .background(fill)
                .border(1.dp, border, ChipShape)
                .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FlowDot(node)
        Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
            FlowAmount(sats = node.sats, color = if (payment) BwColors.Accent else BwColors.Ink)
            Text(
                text = flowChipLabel(node.label),
                color = labelColor,
                fontFamily = BwFontFamily,
                fontSize = 11.sp,
                lineHeight = 12.sp,
                fontWeight = if (payment) BwType.Label else BwType.Body,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Full satoshi amount: B plus eight decimal places. The chip grows to fit it. */
@Composable
private fun FlowAmount(
    sats: Long,
    color: Color,
) {
    val weight = BwType.Label
    val muted = BwColors.InkMuted
    Text(
        text =
            buildAnnotatedString {
                val parts = styleBtc(sats)
                withStyle(SpanStyle(color = muted, fontWeight = weight)) { append("B") }
                withStyle(SpanStyle(color = color, fontWeight = weight)) { append(parts.significant) }
                withStyle(SpanStyle(color = muted, fontWeight = weight)) { append(parts.trailing) }
            },
        fontFamily = numberFontFamily(weight),
        fontWeight = weight,
        fontSize = FLOW_AMOUNT_SP.sp,
        lineHeight = 13.sp,
        maxLines = 1,
        softWrap = false,
    )
}

@Composable
private fun FlowDot(node: TxFlowNode) {
    if (node.role == TxFlowRole.Change) {
        Box(
            Modifier
                .size(10.dp)
                .border(2.dp, Color(CHANGE_ARGB), CircleShape),
        )
        return
    }
    val color = Color(node.colorArgb ?: if (node.role == TxFlowRole.Payment) PAYMENT_ARGB else MUTED_ARGB)
    Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
        if (node.role != TxFlowRole.Fee) {
            Box(
                Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = 0.2f)),
            )
        }
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color),
        )
    }
}

@Composable
private fun MergeDot(model: TxFlowModel) {
    Box(
        modifier =
            Modifier
                .offset((model.merge.xDp - 7f).dp, (model.merge.yDp - 7f).dp)
                .size(14.dp)
                .clip(CircleShape)
                .background(BwColors.Ink)
                .border(3.dp, BwColors.Card, CircleShape),
    )
}
