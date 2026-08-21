package io.bluewallet.blueberry.parse

import platform.Foundation.NSCalendar
import platform.Foundation.NSCalendarUnitDay
import platform.Foundation.NSCalendarUnitHour
import platform.Foundation.NSCalendarUnitMinute
import platform.Foundation.NSCalendarUnitMonth
import platform.Foundation.NSCalendarUnitYear
import platform.Foundation.NSDate

private fun pad2(n: Int): String = n.toString().padStart(2, '0')

internal actual fun formatLocalYmdHm(unixSeconds: Long): String {
    val date = NSDate.dateWithTimeIntervalSince1970(unixSeconds.toDouble())
    val cal = NSCalendar.currentCalendar
    val units = NSCalendarUnitYear or NSCalendarUnitMonth or NSCalendarUnitDay or
        NSCalendarUnitHour or NSCalendarUnitMinute
    val c = cal.components(units, fromDate = date)
    return "${c.year.toInt()}-${pad2(c.month.toInt())}-${pad2(c.day.toInt())} " +
        "${pad2(c.hour.toInt())}:${pad2(c.minute.toInt())}"
}
