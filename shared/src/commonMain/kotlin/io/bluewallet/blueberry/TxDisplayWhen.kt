@file:Suppress("ReturnCount", "ComplexCondition")

package io.bluewallet.blueberry

private val MONTH_SHORT =
    listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

private val MONTH_FULL =
    listOf(
        "January",
        "February",
        "March",
        "April",
        "May",
        "June",
        "July",
        "August",
        "September",
        "October",
        "November",
        "December",
    )

internal fun txClock(time: TxCivilTime): String {
    val hour = time.hour.toString().padStart(2, '0')
    val minute = time.minute.toString().padStart(2, '0')
    return "$hour:$minute"
}

internal fun txDaysBetween(
    from: TxCivilTime,
    to: TxCivilTime,
): Int = civilEpochDay(to) - civilEpochDay(from)

/** Header subtitle: Today, Yesterday, same-year relative, or an older date. */
fun txWhenLabel(
    time: TxCivilTime,
    now: TxCivilTime,
): String {
    val days = txDaysBetween(time, now)
    val clock = txClock(time)
    val month = MONTH_SHORT[time.month - 1]
    return when {
        days <= 0 -> "Today · $clock"
        days == 1 -> "Yesterday · $clock"
        time.year == now.year -> "$month ${time.day} · $days days ago"
        else -> "$month ${time.day}, ${time.year}"
    }
}

/** Row date: clock inside Today and Yesterday, otherwise `Oct 24`. */
fun txListDate(
    time: TxCivilTime?,
    now: TxCivilTime,
): String {
    if (time == null) return ""
    val days = txDaysBetween(time, now)
    return if (days <= 1) txClock(time) else "${MONTH_SHORT[time.month - 1]} ${time.day}"
}

fun txSectionTitle(
    time: TxCivilTime?,
    now: TxCivilTime,
    pending: Boolean,
): String {
    if (pending || time == null) return "Today"
    val days = txDaysBetween(time, now)
    if (days <= 0) return "Today"
    if (days == 1) return "Yesterday"
    val month = MONTH_FULL[time.month - 1]
    return if (time.year == now.year) month else "$month ${time.year}"
}

fun txListSections(
    items: List<TxListItem>,
    now: TxCivilTime,
): List<TxListSection> {
    val sorted =
        items.sortedWith(
            compareBy<TxListItem> { if (it.pending) 0 else 1 }.thenByDescending { it.epochS },
        )
    val grouped = linkedMapOf<String, MutableList<TxListItem>>()
    for (item in sorted) {
        val title = txSectionTitle(item.whenCivil, now, item.pending)
        grouped.getOrPut(title) { mutableListOf() }.add(item)
    }
    return grouped.map { (title, rows) -> TxListSection(title, rows) }
}

/** A live broadcast is happening now, so it belongs in Today even when that day has no stored payment yet. */
fun sectionsWithLiveBroadcasts(
    sections: List<TxListSection>,
    liveCount: Int,
): List<TxListSection> {
    if (liveCount == 0 || sections.any { it.title == "Today" }) return sections
    return listOf(TxListSection("Today", emptyList())) + sections
}

/** `YYYY-MM-DD HH:mm` from [formatLocalYmdHm], or null when it is not that shape. */
fun txCivilFromYmdHm(label: String): TxCivilTime? {
    val trimmed = label.trim()
    if (trimmed.length < 16 || trimmed[4] != '-' || trimmed[7] != '-' || trimmed[10] != ' ') return null
    val year = trimmed.substring(0, 4).toIntOrNull() ?: return null
    val month = trimmed.substring(5, 7).toIntOrNull() ?: return null
    val day = trimmed.substring(8, 10).toIntOrNull() ?: return null
    val hour = trimmed.substring(11, 13).toIntOrNull() ?: return null
    val minute = trimmed.substring(14, 16).toIntOrNull() ?: return null
    if (month !in 1..12 || day !in 1..31 || hour !in 0..23 || minute !in 0..59) return null
    return TxCivilTime(year, month, day, hour, minute)
}

private fun civilEpochDay(time: TxCivilTime): Int {
    val year = time.year - if (time.month <= 2) 1 else 0
    val era = if (year >= 0) year / 400 else (year - 399) / 400
    val yearOfEra = year - era * 400
    val month = if (time.month > 2) time.month - 3 else time.month + 9
    val dayOfYear = (153 * month + 2) / 5 + time.day - 1
    val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
    return era * 146097 + dayOfEra - 719468
}
