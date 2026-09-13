package io.bluewallet.blueberry.wallet

import io.bluewallet.blueberry.storage.Database
import kotlin.math.floor

/** JS `Number.parseInt(v, 10)` subset: leading whitespace, optional `-`/`+`, ASCII digits until non-digit. */
internal fun parseInt10(v: String?): Double? {
    if (v == null) return null
    var i = 0
    while (i < v.length && v[i].isWhitespace()) i++
    if (i >= v.length) return null

    var negative = false
    when (v[i]) {
        '-' -> {
            negative = true
            i++
        }
        '+' -> i++
    }
    if (i >= v.length) return null

    var value = 0.0
    var parsedAny = false
    while (i < v.length) {
        val c = v[i]
        if (c !in '0'..'9') break
        parsedAny = true
        value = value * 10 + (c - '0')
        i++
    }
    if (!parsedAny) return null
    return if (negative) -value else value
}

fun saveWatchGaps(
    db: Database,
    gaps: WatchGaps,
) {
    db.keyValue.set(WATCH_EXTERNAL_KEY, gaps.external.toString())
    db.keyValue.set(WATCH_INTERNAL_KEY, gaps.internal.toString())
}

fun loadWatchGaps(db: Database): WatchGaps {
    fun parse(v: String?): Int {
        val n = parseInt10(v)
        if (n == null || n < 0 || !n.isFinite()) return INITIAL_WATCH_COUNT
        return minOf(floor(n).toInt(), MAX_WATCH_COUNT)
    }
    val extRaw = db.keyValue.get(WATCH_EXTERNAL_KEY)
    val intRaw = db.keyValue.get(WATCH_INTERNAL_KEY)
    val external = parse(extRaw)
    val internal = parse(intRaw)
    if (
        extRaw == null ||
        intRaw == null ||
        extRaw != external.toString() ||
        intRaw != internal.toString()
    ) {
        saveWatchGaps(db, WatchGaps(external, internal))
    }
    return WatchGaps(external, internal)
}

data class GrowWatchGapsResult(
    val gaps: WatchGaps,
    val grew: Boolean,
)

fun growWatchGapsIfNeeded(
    gaps: WatchGaps,
    usedExternal: List<Int>,
    usedInternal: List<Int>,
    gapLimit: Int = GAP_LIMIT,
): GrowWatchGapsResult {
    fun bump(
        n: Int,
        idxs: List<Int>,
    ): Int {
        val start = if (n < gapLimit) 0 else n - gapLimit
        if (idxs.none { it >= start && it < n }) return n
        return minOf(n + gapLimit, MAX_WATCH_COUNT)
    }
    val external = bump(gaps.external, usedExternal)
    val internal = bump(gaps.internal, usedInternal)
    return GrowWatchGapsResult(
        WatchGaps(external, internal),
        external != gaps.external || internal != gaps.internal,
    )
}
