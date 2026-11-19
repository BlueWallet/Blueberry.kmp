package io.bluewallet.blueberry

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

internal data class ProgressSample(
    val at: Long,
    val count: Int,
)

/** Time between two increases of a count, and the increase at its end. */
internal data class ProgressGap(
    val ms: Long,
    val count: Int,
)

private const val SPEED_GAPS = 7

/** A gap longer than both this and [PAUSE_GAPS] usual gaps is a pause. */
private const val PAUSE_MIN_MS = 2_000L
private const val PAUSE_GAPS = 8

/** This many pauses in a row mean the work slowed down for good, so the speed is measured again. */
private const val MAX_PAUSES = 3

/** No single gap fades older work by more than this, so one slow gap cannot replace the history. */
private const val MAX_FADE_MS = 2_000L

private const val SMOOTH_TAU_MS = 8_000.0

internal fun isFinished(
    count: Int,
    total: Int,
): Boolean = total > 0 && count >= total

/**
 * Speed of a count: recent work over recent time. Each gap between increases adds its work and its
 * length, and older work fades with an 8 second time constant: noise stays small, and with short
 * gaps a lasting change shows within about 15 seconds. The first speed is the average of the first
 * [SPEED_GAPS] gaps, and it counts as at least 8 seconds of work. A pause never counts as work.
 * [gaps] holds the last gaps that were not pauses, and [pauses] counts the pauses in a row.
 */
internal data class ProgressSpeed(
    val last: ProgressSample? = null,
    val gaps: List<ProgressGap> = emptyList(),
    val recentCount: Double = 0.0,
    val recentMs: Double = 0.0,
    val pauses: Int = 0,
) {
    val perMs: Double? get() = if (recentMs > 0.0) recentCount / recentMs else null
}

/**
 * The speed after the count moves from [prevCount] to [count]. [restarted] drops the speed (the
 * stores set it when finished work grows again). A count that goes back keeps the speed and ends a
 * row of pauses, and the next gap starts at the next increase. An increase with no time, or no
 * later than the last increase, counts with the next gap.
 */
internal fun ProgressSpeed.next(
    prevCount: Int,
    count: Int,
    at: Long?,
    restarted: Boolean,
): ProgressSpeed =
    when {
        restarted -> ProgressSpeed()
        count < prevCount -> copy(last = null, pauses = 0)
        count == prevCount || at == null -> this
        last == null -> copy(last = ProgressSample(at, count))
        at <= last.at -> this
        isPause(at - last.at) -> paused(ProgressSample(at, count))
        else -> addGap(last, ProgressSample(at, count))
    }

private fun ProgressSpeed.isPause(gapMs: Long): Boolean {
    if (gaps.isEmpty()) return false
    val usualGap = gaps.sumOf { it.ms } / gaps.size
    return gapMs > max(PAUSE_MIN_MS, PAUSE_GAPS * usualGap)
}

private fun ProgressSpeed.paused(sample: ProgressSample): ProgressSpeed =
    when {
        pauses + 1 < MAX_PAUSES -> copy(last = sample, pauses = pauses + 1)
        else -> ProgressSpeed(last = sample)
    }

private fun ProgressSpeed.addGap(
    from: ProgressSample,
    to: ProgressSample,
): ProgressSpeed {
    val gap = ProgressGap(to.at - from.at, to.count - from.count)
    val nextGaps = (gaps + gap).takeLast(SPEED_GAPS)
    return when {
        recentMs > 0.0 -> {
            val keep = exp(-min(gap.ms, MAX_FADE_MS) / SMOOTH_TAU_MS)
            ProgressSpeed(to, nextGaps, keep * recentCount + gap.count, keep * recentMs + gap.ms)
        }
        nextGaps.size < SPEED_GAPS -> ProgressSpeed(to, nextGaps)
        else -> {
            val ms = nextGaps.sumOf { it.ms }
            val weightMs = max(ms.toDouble(), SMOOTH_TAU_MS)
            ProgressSpeed(to, nextGaps, nextGaps.sumOf { it.count } * weightMs / ms, weightMs)
        }
    }
}

/** Time left at this speed. Null until the speed is known. Never 0 while work is left. */
internal fun ProgressSpeed.etaMs(
    count: Int,
    total: Int,
): Long? =
    perMs?.let { speed ->
        if (count >= total) 0L else max(1L, round((total - count) / speed).toLong())
    }

/**
 * The time to show. It counts down with the clock and moves toward [raw] with an 8 second time
 * constant: noise stays small, and a lasting change shows within about 15 seconds. A missing [raw]
 * keeps the shown time. The first time and done (0) show at once.
 */
internal fun smoothEtaMs(
    previous: Long?,
    previousAt: Long?,
    raw: Long?,
    at: Long,
): Long? =
    when {
        raw == null -> previous?.takeIf { it > 0L }
        raw <= 0L -> raw
        previous == null || previous <= 0L || previousAt == null -> raw
        else -> {
            val elapsed = max(0L, at - previousAt)
            val left = previous - elapsed
            val alpha = 1.0 - exp(-elapsed / SMOOTH_TAU_MS)
            max(1L, round(left + alpha * (raw - left)).toLong())
        }
    }
