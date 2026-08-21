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
fun formatBlockTimeLabel(unixSeconds: Long, nowMs: Long): String {
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
