package dev.icos.app.ui

import java.util.Calendar
import java.util.Date
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone

internal fun calendarMonthCells(monthKey: String): List<String?> {
    val first = calendarDate("$monthKey-01")
    val offset = (first.get(Calendar.DAY_OF_WEEK) + 5) % 7 // Monday first
    val length = first.getActualMaximum(Calendar.DAY_OF_MONTH)
    val cellCount = ((offset + length + 6) / 7) * 7
    return List(cellCount) { index ->
        val day = index - offset + 1
        if (day in 1..length) String.format(Locale.ROOT, "%s-%02d", monthKey, day) else null
    }
}

internal fun shiftCalendarMonth(
    dayKey: String,
    months: Int,
): String {
    val date = calendarDate(dayKey).apply { add(Calendar.MONTH, months) }
    require(date.get(Calendar.ERA) == GregorianCalendar.AD && date.get(Calendar.YEAR) in 1..9999)
    return String.format(
        Locale.ROOT,
        "%04d-%02d-%02d",
        date.get(Calendar.YEAR),
        date.get(Calendar.MONTH) + 1,
        date.get(Calendar.DAY_OF_MONTH),
    )
}

private fun calendarDate(dayKey: String): GregorianCalendar {
    require(dayKey.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")))
    val (year, month, day) = dayKey.split('-').map(String::toInt)
    require(year in 1..9999)
    return GregorianCalendar(TimeZone.getTimeZone("UTC"), Locale.ROOT).apply {
        gregorianChange = Date(Long.MIN_VALUE)
        clear()
        isLenient = false
        set(year, month - 1, day)
        timeInMillis // Force strict validation before month arithmetic.
    }
}
