package io.bluewallet.blueberry

import io.bluewallet.blueberry.parse.formatLocalYmdHm
import kotlin.math.roundToLong

data class PathChipModel(
    val value: String,
    val meaning: String,
    val accent: Boolean,
)

/** Long age for the coin sheet. The list keeps the short `36d` form. */
fun formatCoinAgeLong(
    confirmedAtEpochS: Long?,
    nowEpochS: Long,
): String? {
    if (confirmedAtEpochS == null) return null
    val age = (nowEpochS - confirmedAtEpochS).coerceAtLeast(0)
    val minutes = age / 60
    val hours = age / 3_600
    val days = age / 86_400
    return when {
        hours < 1 -> if (minutes <= 1) "1 minute old" else "$minutes minutes old"
        hours < 24 -> if (hours == 1L) "1 hour old" else "$hours hours old"
        days < 365 -> if (days == 1L) "1 day old" else "$days days old"
        else -> {
            val months = (days / 30.0).roundToLong().coerceAtLeast(12)
            if (months == 1L) "1 month old" else "$months months old"
        }
    }
}

/** `Sep 14`, or `Today 17:38` on the same local day. Unconfirmed is Pending. */
fun formatSheetWhen(
    confirmedAtEpochS: Long?,
    nowEpochS: Long,
): String {
    if (confirmedAtEpochS == null) return "Pending"
    return formatSheetWhenLocal(formatLocalYmdHm(confirmedAtEpochS), formatLocalYmdHm(nowEpochS))
}

/** [then] and [now] are local `YYYY-MM-DD HH:mm`, as returned by [formatLocalYmdHm]. */
internal fun formatSheetWhenLocal(
    then: String,
    now: String,
): String {
    val day = then.substringBefore(' ')
    val clock = then.substringAfter(' ')
    return if (day == now.substringBefore(' ')) {
        "Today $clock"
    } else {
        val month = day.substring(5, 7).toInt()
        val date = day.substring(8, 10).toInt()
        "${MONTH[month - 1]} $date"
    }
}

fun formatPlusBtc(sats: Long): String {
    val whole = sats / 100_000_000L
    val frac = (sats % 100_000_000L).toString().padStart(8, '0')
    return "+₿$whole.$frac"
}

fun formatGroupedInt(value: Int): String = groupDigits(value.toLong())

fun spendSizeLabel(vbytes: Double): String = if (vbytes % 1.0 == 0.0) "${vbytes.toLong()} vB" else "$vbytes vB"

fun percentOfValue(
    cost: Long,
    value: Long,
): Int = if (value <= 0L) 0 else ((cost * 100.0) / value).roundToLong().toInt()

/** Five derivation chips, or null when [path] is not `m/purpose'/cointype'/account'/chain/index`. */
fun derivationChips(path: String?): List<PathChipModel>? {
    val parts = path?.removePrefix("m/")?.split('/').orEmpty()
    if (parts.size < 5) return null
    val account = parts[2].removeSuffix("'").toIntOrNull() ?: 0
    val purpose =
        when (parts[0].removeSuffix("'")) {
            "44" -> "Legacy"
            "49" -> "Nested SegWit"
            "84" -> "Native SegWit"
            "86" -> "Taproot"
            else -> "Purpose"
        }
    val coinType =
        when (parts[1].removeSuffix("'")) {
            "0" -> "Bitcoin"
            "1" -> "Testnet"
            else -> "Coin type"
        }
    val chain =
        when (parts[3]) {
            "0" -> "Receive"
            "1" -> "Change"
            else -> "Chain"
        }
    return listOf(
        PathChipModel(parts[0], purpose, false),
        PathChipModel(parts[1], coinType, false),
        PathChipModel(parts[2], "Account $account", false),
        PathChipModel(parts[3], chain, true),
        PathChipModel(parts[4], "Index", true),
    )
}

private fun groupDigits(value: Long): String {
    val negative = value < 0
    val digits = kotlin.math.abs(value).toString()
    val grouped =
        digits
            .reversed()
            .chunked(3)
            .joinToString(",")
            .reversed()
    return if (negative) "-$grouped" else grouped
}

private val MONTH =
    listOf(
        "Jan",
        "Feb",
        "Mar",
        "Apr",
        "May",
        "Jun",
        "Jul",
        "Aug",
        "Sep",
        "Oct",
        "Nov",
        "Dec",
    )
