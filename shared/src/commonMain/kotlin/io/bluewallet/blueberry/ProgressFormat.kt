package io.bluewallet.blueberry

import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

fun formatEta(etaMs: Long?): String {
    if (etaMs == null) return "—"
    if (etaMs <= 0) return "done"
    val s = round(etaMs / 1000.0).toInt()
    if (s < 60) return "${s}s"
    val m = s / 60
    val r = s % 60
    return "${m}m ${r}s"
}

fun formatParseProgress(parsed: Int, total: Int, etaMs: Long?): String {
    val progress = "$parsed/$total blocks parsed"
    return if (etaMs == null) progress else "$progress (ETA ${formatEta(etaMs)})"
}

fun progressBar(percent: Int, width: Int = 10): String {
    val clamped = max(0, min(100, percent))
    val filled = round((clamped / 100.0) * width).toInt()
    val cells = CharArray(width) { i -> if (i < filled) '█' else '░' }
    return "[${cells.concatToString()}] $clamped%"
}

/** 0f..1f width of a single horizontal progress line. */
fun progressFillFraction(percent: Int): Float =
    max(0, min(100, percent)) / 100f

fun formatGrouped(n: Int): String {
    val sign = if (n < 0) "-" else ""
    val digits = kotlin.math.abs(n).toString()
    val grouped = buildString {
        digits.reversed().forEachIndexed { i, c ->
            if (i > 0 && i % 3 == 0) append(',')
            append(c)
        }
    }.reversed()
    return sign + grouped
}

fun progressCaption(count: Int, total: Int, percent: Int, etaMs: Long?): String {
    val counts = "${formatGrouped(count)}/${formatGrouped(total)}"
    return when {
        percent >= 100 -> counts
        etaMs != null -> "$counts · ${formatEta(etaMs)}"
        else -> counts
    }
}
