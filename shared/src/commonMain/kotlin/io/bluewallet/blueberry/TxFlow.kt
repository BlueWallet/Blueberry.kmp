package io.bluewallet.blueberry

/** Which side of a transaction flow a node sits on, and how its curve is drawn. */
enum class TxFlowRole { Input, Payment, Change, Fee }

/**
 * One chip in a transaction flow. The graph does not know whether this came from a send review
 * or a confirmed transaction: callers fill in the amount, the label, and the role.
 */
data class TxFlowNode(
    val sats: Long,
    val label: String,
    val role: TxFlowRole,
    /** Coin color for an input dot. Ignored for outputs. */
    val colorArgb: Long? = null,
    /** When set, the chip shows this instead of an amount. Send Review leaves it unset. */
    val headline: String? = null,
    /** Grey ring instead of a filled dot. Change keeps its own ring. */
    val ring: Boolean = false,
    /** Outpoint of the coin row a tap opens. Send Review leaves it unset. */
    val coinKey: String? = null,
)

data class TxFlowPoint(
    val xDp: Float,
    val yDp: Float,
)

data class TxFlowChip(
    val node: TxFlowNode,
    val xDp: Float,
    val yDp: Float,
    val widthDp: Float,
)

data class TxFlowCurve(
    val start: TxFlowPoint,
    val end: TxFlowPoint,
    val widthDp: Float,
    val colorArgb: Long,
    val alpha: Float,
    val dotted: Boolean,
)

data class TxFlowModel(
    val widthDp: Float,
    val heightDp: Float,
    val inputs: List<TxFlowChip>,
    val outputs: List<TxFlowChip>,
    val curves: List<TxFlowCurve>,
    val merge: TxFlowPoint,
    /** Real input count, before extra coins fold into one chip, and the output chip count. */
    val countLabel: String,
)

internal const val TX_FLOW_WIDTH_DP = 326f
internal const val TX_FLOW_CHIP_WIDTH_DP = 120f
internal const val TX_FLOW_CHIP_HEIGHT_DP = 46f

private const val ROW_DP = 56f
private const val MIN_MIDDLE_DP = 36f
private const val SHOWN_INPUTS = 5
private const val INPUT_CAP = 6
private const val MIN_WIDTH_DP = 2f
private const val MAX_EXTRA_WIDTH_DP = 5f
private const val FEE_WIDTH_DP = 1.5f
private const val PAYMENT_ARGB = 0xFF3788E0
private const val CHANGE_ARGB = 0xFFAEB4BA
private const val MUTED_ARGB = 0xFF6E767D

/**
 * Lays out coins on the left and outputs on the right around one merge point.
 * Inputs are drawn largest first. More than six collapse to five chips plus "+n more coins".
 * [chipWidth] is the width each chip needs. A wider canvas does not stretch them, and the
 * canvas grows when the chips plus the middle gap need more than [widthDp].
 */
fun txFlowModel(
    inputs: List<TxFlowNode>,
    outputs: List<TxFlowNode>,
    widthDp: Float = TX_FLOW_WIDTH_DP,
    chipWidth: (TxFlowNode) -> Float = { _ -> TX_FLOW_CHIP_WIDTH_DP },
): TxFlowModel {
    val ordered = inputs.sortedByDescending { it.sats }
    val shown = collapseInputs(ordered)
    val inputWidths = shown.map(chipWidth)
    val outputWidths = outputs.map(chipWidth)
    val requested = if (widthDp.isFinite() && widthDp > 0f) widthDp else TX_FLOW_WIDTH_DP
    val needed = (inputWidths.maxOrNull() ?: 0f) + (outputWidths.maxOrNull() ?: 0f) + MIN_MIDDLE_DP
    val width = maxOf(requested, needed)
    val rows = maxOf(shown.size, outputs.size, 1)
    val height = (rows - 1) * ROW_DP + TX_FLOW_CHIP_HEIGHT_DP
    val total = ordered.sumOf { it.sats }.coerceAtLeast(1L)
    val inputChips = column(shown, inputWidths, height) { 0f }
    val outputChips = column(outputs, outputWidths, height) { chip -> width - chip }
    val merge = TxFlowPoint(width / 2f, height / 2f)
    return TxFlowModel(
        widthDp = width,
        heightDp = height,
        inputs = inputChips,
        outputs = outputChips,
        curves = inputCurves(inputChips, merge, total) + outputCurves(outputChips, merge, total),
        merge = merge,
        countLabel = "${ordered.size} in · ${outputs.size} out",
    )
}

private fun collapseInputs(ordered: List<TxFlowNode>): List<TxFlowNode> {
    if (ordered.size <= INPUT_CAP) return ordered
    val rest = ordered.drop(SHOWN_INPUTS)
    val more =
        TxFlowNode(
            sats = rest.sumOf { it.sats },
            label = "+${rest.size} more coins",
            role = TxFlowRole.Input,
            colorArgb = MUTED_ARGB,
        )
    return ordered.take(SHOWN_INPUTS) + more
}

private fun column(
    nodes: List<TxFlowNode>,
    widths: List<Float>,
    heightDp: Float,
    xDp: (Float) -> Float,
): List<TxFlowChip> {
    if (nodes.isEmpty()) return emptyList()
    val stack = (nodes.size - 1) * ROW_DP + TX_FLOW_CHIP_HEIGHT_DP
    val top = (heightDp - stack) / 2f
    return nodes.mapIndexed { index, node ->
        val width = widths[index]
        TxFlowChip(node, xDp(width), top + index * ROW_DP, width)
    }
}

private fun inputCurves(
    chips: List<TxFlowChip>,
    merge: TxFlowPoint,
    total: Long,
): List<TxFlowCurve> =
    chips.map { chip ->
        curve(
            from = edge(chip, right = true),
            to = merge,
            node = chip.node,
            total = total,
        )
    }

private fun outputCurves(
    chips: List<TxFlowChip>,
    merge: TxFlowPoint,
    total: Long,
): List<TxFlowCurve> =
    chips.map { chip ->
        curve(
            from = merge,
            to = edge(chip, right = false),
            node = chip.node,
            total = total,
        )
    }

private fun edge(
    chip: TxFlowChip,
    right: Boolean,
): TxFlowPoint {
    val x = if (right) chip.xDp + chip.widthDp else chip.xDp
    return TxFlowPoint(x, chip.yDp + TX_FLOW_CHIP_HEIGHT_DP / 2f)
}

private fun curve(
    from: TxFlowPoint,
    to: TxFlowPoint,
    node: TxFlowNode,
    total: Long,
): TxFlowCurve {
    val fee = node.role == TxFlowRole.Fee
    return TxFlowCurve(
        start = from,
        end = to,
        widthDp = if (fee) FEE_WIDTH_DP else strokeWidth(node.sats, total),
        colorArgb = curveColor(node),
        alpha = curveAlpha(node.role),
        dotted = fee,
    )
}

private fun strokeWidth(
    sats: Long,
    total: Long,
): Float {
    val share = (sats.toFloat() / total.toFloat()).coerceIn(0f, 1f)
    return MIN_WIDTH_DP + share * MAX_EXTRA_WIDTH_DP
}

private fun curveAlpha(role: TxFlowRole): Float =
    when (role) {
        TxFlowRole.Input -> 0.9f
        TxFlowRole.Payment -> 1f
        TxFlowRole.Change, TxFlowRole.Fee -> 0.6f
    }

private fun curveColor(node: TxFlowNode): Long =
    when (node.role) {
        TxFlowRole.Input -> node.colorArgb ?: MUTED_ARGB
        TxFlowRole.Payment -> PAYMENT_ARGB
        TxFlowRole.Change -> CHANGE_ARGB
        TxFlowRole.Fee -> MUTED_ARGB
    }
