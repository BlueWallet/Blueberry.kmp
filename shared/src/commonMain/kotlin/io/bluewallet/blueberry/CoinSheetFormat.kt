package io.bluewallet.blueberry

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

/** `Sep 14`, or `Today 17:38` when the UTC day matches [nowEpochS]. Unconfirmed is Pending. */
fun formatSheetWhen(
    confirmedAtEpochS: Long?,
    nowEpochS: Long,
): String {
    if (confirmedAtEpochS == null) return "Pending"
    val then = civilDay(confirmedAtEpochS)
    val now = civilDay(nowEpochS)
    return if (then.year == now.year && then.month == now.month && then.day == now.day) {
        val tod = confirmedAtEpochS.mod(86_400L)
        val hour = (tod / 3_600).toString().padStart(2, '0')
        val minute = ((tod % 3_600) / 60).toString().padStart(2, '0')
        "Today $hour:$minute"
    } else {
        "${MONTH[then.month - 1]} ${then.day}"
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

private data class CivilDay(
    val year: Int,
    val month: Int,
    val day: Int,
)

private fun civilDay(unixSeconds: Long): CivilDay {
    var z = unixSeconds.floorDiv(86_400L) + 719_468
    val era = if (z >= 0) z / 146_097 else (z - 146_096) / 146_097
    val doe = (z - era * 146_097).toInt()
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    val y = yoe + era.toInt() * 400
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val day = doy - (153 * mp + 2) / 5 + 1
    val month = mp + if (mp < 10) 3 else -9
    val year = y + if (month <= 2) 1 else 0
    return CivilDay(year, month, day)
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
