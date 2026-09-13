package io.bluewallet.blueberry.parse

private const val MINUTE_S = 60L
private const val HOUR_S = 60 * MINUTE_S
private const val DAY_S = 24 * HOUR_S

/** Approximate calendar month for the relative/absolute cutoff. */
private const val MONTH_S = 30 * DAY_S

/** Width of `YYYY-MM-DD HH:mm` — all time labels are padded to this. */
private const val WIDTH = 16

private fun pad(label: String): String = label.padEnd(WIDTH)

internal expect fun formatLocalYmdHm(unixSeconds: Long): String

/**
 * Human-readable block time for the tx list (fixed-width, left-aligned).
 * ≤ 1 month: relative (`just now` / `Nm ago` / `Nh ago` / `Nd ago`).
 * Older than 1 month: compact local `YYYY-MM-DD HH:mm`.
 */
fun formatBlockTimeLabel(
    unixSeconds: Long,
    nowMs: Long,
): String {
    val ageS = maxOf(0L, nowMs / 1000 - unixSeconds)
    if (ageS <= MONTH_S) {
        if (ageS < MINUTE_S) return pad("just now")
        if (ageS < HOUR_S) return pad("${ageS / MINUTE_S}m ago")
        if (ageS < DAY_S) return pad("${ageS / HOUR_S}h ago")
        return pad("${ageS / DAY_S}d ago")
    }
    return formatLocalYmdHm(unixSeconds)
}

/** Pad `#height` (and any other short fallback) to the time-column width. */
fun padBlockTimeLabel(label: String): String = pad(label)

/**
 * Relative age for the chain-tip card (not padded).
 * Past: seconds, then minutes (including past 60), then hours after a day.
 * Future (header ahead of wall clock): the same units with a leading minus, e.g. `-30 seconds`.
 * Older than a month either way: local date.
 */
fun formatRelativeAge(
    unixSeconds: Long?,
    nowMs: Long,
): String {
    if (unixSeconds == null) return "—"
    val ageS = nowMs / 1000 - unixSeconds
    if (ageS > MONTH_S || ageS < -MONTH_S) return formatLocalYmdHm(unixSeconds)
    if (ageS == 0L) return "just now"
    val abs = if (ageS < 0) -ageS else ageS
    val amount =
        when {
            abs < MINUTE_S -> if (abs == 1L) "1 second" else "$abs seconds"
            abs < DAY_S -> "${abs / MINUTE_S} min"
            else -> {
                val hours = abs / HOUR_S
                if (hours == 1L) "1 hour" else "$hours hours"
            }
        }
    return if (ageS > 0) "$amount ago" else "-$amount"
}

/** Next signed-age boundary that would change [formatRelativeAge]. */
private fun nextAgeBoundary(
    ageS: Long,
    bucket: Long,
): Long =
    if (ageS >= 0) {
        ((ageS / bucket) + 1) * bucket
    } else {
        -(((-ageS) / bucket) * bucket - 1)
    }

/** Milliseconds until [formatRelativeAge] would change, or null if the label is static. */
fun msUntilNextRelativeAgeChange(
    unixSeconds: Long?,
    nowMs: Long,
): Long? {
    if (unixSeconds == null) return null
    val ageS = nowMs / 1000 - unixSeconds
    if (ageS > MONTH_S || ageS < -MONTH_S) return null
    val nextAgeS =
        when {
            ageS > -MINUTE_S && ageS < MINUTE_S -> ageS + 1
            ageS > -DAY_S && ageS < DAY_S -> nextAgeBoundary(ageS, MINUTE_S)
            else -> {
                val next = nextAgeBoundary(ageS, HOUR_S)
                if (next > MONTH_S) MONTH_S + 1 else next
            }
        }
    val nextMs = unixSeconds * 1000 + nextAgeS * 1000
    return maxOf(1L, nextMs - nowMs)
}
