package io.bluewallet.blueberry

import kotlin.math.sqrt

data class BubbleLayoutItem(
    val key: String,
    val radiusDp: Float,
    val colorArgb: Long,
    val isFrozen: Boolean,
    val linkedGroupId: String?,
    val provable: Boolean = true,
)

data class BubbleStripLayout(
    val items: List<BubbleLayoutItem>,
    val overflowCount: Int,
    val scale: Float,
)

private data class BubbleGaps(
    val inGroup: Float = 3f,
    val betweenGroups: Float = 8f,
    val independent: Float = 5f,
    val groupPad: Float = 5f,
)

/**
 * Layout the bubble strip: area-proportional radii, uniform scale to fit, then drop smallest
 * independent coins and report +K overflow.
 */
@Suppress("LongParameterList", "CyclomaticComplexMethod", "NestedBlockDepth", "LoopWithTooManyJumpStatements")
fun layoutBubbleStrip(
    bubbles: List<CoinControlBubble>,
    availableWidthDp: Float,
    rMaxDp: Float = 28f,
    rMinDp: Float = 2.5f,
    overflowLabelDp: Float = BUBBLE_OVERFLOW_LABEL_DP,
): BubbleStripLayout {
    if (bubbles.isEmpty() || availableWidthDp <= 0f) {
        return BubbleStripLayout(emptyList(), 0, 1f)
    }
    val gaps = BubbleGaps()
    val largest = bubbles.maxOf { it.valueSats }.coerceAtLeast(1L)

    fun radiiFor(
        list: List<CoinControlBubble>,
        scale: Float,
    ): List<Float> =
        list.map { b ->
            val r = rMaxDp * sqrt(b.valueSats.toDouble() / largest).toFloat() * scale
            r.coerceAtLeast(rMinDp)
        }

    fun widthOf(
        list: List<CoinControlBubble>,
        scale: Float,
    ): Float = measureBubbleWidth(list, radiiFor(list, scale), gaps)

    var scale = 1f
    val natural = widthOf(bubbles, 1f)
    if (natural > availableWidthDp) {
        scale = (availableWidthDp / natural).coerceIn(0.05f, 1f)
    }

    var visible = bubbles
    var overflow = 0

    // Once anything is dropped, the +N label takes the strip's right end.
    fun budget(): Float = if (overflow > 0) availableWidthDp - overflowLabelDp else availableWidthDp

    // Prefer dropping independent coins, then the smallest linked coin, so a
    // 2.5dp-floored cluster that still overflows is counted in +N instead of clipped.
    while (visible.isNotEmpty() && widthOf(visible, scale) > budget() + 0.5f) {
        val dropIdx =
            visible
                .withIndex()
                .filter { it.value.linkedGroupId == null }
                .minByOrNull { it.value.valueSats }
                ?.index
                ?: visible.withIndex().minByOrNull { it.value.valueSats }?.index
                ?: break
        visible = visible.filterIndexed { i, _ -> i != dropIdx }
        overflow++
    }

    val after = widthOf(visible, scale)
    if (after > budget() && after > 0f) {
        scale = (scale * budget() / after).coerceIn(0.05f, 1f)
    }

    val rs = radiiFor(visible, scale)
    val items =
        visible.mapIndexed { i, b ->
            BubbleLayoutItem(
                key = b.key,
                radiusDp = rs[i],
                colorArgb = b.colorArgb,
                isFrozen = b.isFrozen,
                linkedGroupId = b.linkedGroupId,
                provable = b.provable,
            )
        }
    return BubbleStripLayout(items, overflow, scale)
}

/** Fallback "+N" reservation; the panel passes the measured label width instead. */
const val BUBBLE_OVERFLOW_LABEL_DP = 28f

/** Drawn width of the laid-out coins, using the same gaps as the strip. */
internal fun BubbleStripLayout.contentWidthDp(): Float =
    measureBubbleWidth(
        items.map { CoinControlBubble(it.key, 0L, it.colorArgb, it.isFrozen, it.linkedGroupId, it.provable) },
        items.map { it.radiusDp },
        BubbleGaps(),
    )

@Suppress("NestedBlockDepth")
private fun measureBubbleWidth(
    list: List<CoinControlBubble>,
    rs: List<Float>,
    gaps: BubbleGaps,
): Float {
    if (list.isEmpty()) return 0f
    var w = 0f
    var i = 0
    while (i < list.size) {
        val gid = list[i].linkedGroupId
        if (gid != null) {
            val start = i
            while (i < list.size && list[i].linkedGroupId == gid) i++
            val groupRadii = rs.subList(start, i)
            val inner = groupRadii.sumOf { it.toDouble() }.toFloat() * 2f + gaps.inGroup * (groupRadii.size - 1)
            w += inner + gaps.groupPad * 2f
            if (i < list.size) w += gaps.betweenGroups
        } else {
            w += rs[i] * 2f
            i++
            if (i < list.size) {
                w += if (list[i].linkedGroupId != null) gaps.betweenGroups else gaps.independent
            }
        }
    }
    return w
}
