package io.bluewallet.blueberry.wallet

/** Year of the measured wallet. Its highest used indexes are the scale anchor. */
const val BASELINE_SYNC_YEAR = 2019

/** Year when those highest used indexes were measured. */
const val BASELINE_MEASURED_YEAR = 2026

/** Highest external index used by the 2019 baseline wallet, measured November 2026. */
const val BASELINE_EXTERNAL_USED_INDEX = 114

/** Highest internal index used by the 2019 baseline wallet, measured November 2026. */
const val BASELINE_INTERNAL_USED_INDEX = 319

/**
 * Watch window for an import that starts at [startYear].
 *
 * The window scales the [BASELINE_SYNC_YEAR] to [BASELINE_MEASURED_YEAR] rate
 * across [currentYear] - [startYear]. It holds the
 * estimated highest index, then one more slot, then [GAP_LIMIT] empty slots.
 * The last gap stays empty, so the first filter pass does not grow.
 * A start year at or after [currentYear] keeps [INITIAL_WATCH_COUNT].
 */
fun baselineWatchGaps(
    startYear: Int,
    currentYear: Int,
): WatchGaps {
    val years = currentYear - startYear
    val span = BASELINE_MEASURED_YEAR - BASELINE_SYNC_YEAR
    if (years <= 0 || span <= 0) {
        return WatchGaps(INITIAL_WATCH_COUNT, INITIAL_WATCH_COUNT)
    }
    return WatchGaps(
        baselineWatchCount(BASELINE_EXTERNAL_USED_INDEX, years, span),
        baselineWatchCount(BASELINE_INTERNAL_USED_INDEX, years, span),
    )
}

private fun baselineWatchCount(
    highestUsedIndex: Int,
    years: Int,
    span: Int,
): Int {
    val estimated = (highestUsedIndex.toLong() * years.toLong() + span - 1) / span
    val window = estimated + 1L + GAP_LIMIT
    return window.coerceIn(INITIAL_WATCH_COUNT.toLong(), MAX_WATCH_COUNT.toLong()).toInt()
}
