package io.bluewallet.blueberry.parse

import java.util.Calendar

private fun pad2(n: Int): String = n.toString().padStart(2, '0')

internal actual fun formatLocalYmdHm(unixSeconds: Long): String {
    val cal = Calendar.getInstance()
    cal.timeInMillis = unixSeconds * 1000L
    val year = cal.get(Calendar.YEAR)
    val month = cal.get(Calendar.MONTH) + 1
    val day = cal.get(Calendar.DAY_OF_MONTH)
    val hour = cal.get(Calendar.HOUR_OF_DAY)
    val minute = cal.get(Calendar.MINUTE)
    return "$year-${pad2(month)}-${pad2(day)} ${pad2(hour)}:${pad2(minute)}"
}
