package dev.icos.app.domain

import android.icu.util.Calendar
import android.icu.util.GregorianCalendar
import android.icu.util.TimeZone
import java.util.Date
import java.util.Locale

enum class ReportPeriod { WEEK, MONTH, YEAR }

data class PeriodRange(
    val startInclusiveMillis: Long,
    val endExclusiveMillis: Long,
) {
    init {
        require(startInclusiveMillis < endExclusiveMillis)
    }

    operator fun contains(instant: Long): Boolean = instant >= startInclusiveMillis && instant < endExclusiveMillis
}

/** Calendar boundaries in the statistics timezone; no fixed 24-hour arithmetic across DST. */
object PeriodResolver {
    private val zones = TimeZone.getAvailableIDs().toSet()

    fun range(
        period: ReportPeriod,
        atMillis: Long,
        zone: String,
        weekStart: Int = Calendar.MONDAY,
        monthStart: Int = 1,
        yearStartMonth: Int = Calendar.JANUARY,
    ): PeriodRange {
        require(weekStart in Calendar.SUNDAY..Calendar.SATURDAY)
        require(monthStart in 1..31)
        require(yearStartMonth in Calendar.JANUARY..Calendar.DECEMBER)
        val day = calendar(zone).apply { timeInMillis = atMillis }
        fun monthBoundary(year: Int, month: Int): Calendar = calendar(zone).apply {
            clear()
            set(year, month, 1, 0, 0, 0)
            set(Calendar.DAY_OF_MONTH, minOf(monthStart, getActualMaximum(Calendar.DAY_OF_MONTH)))
        }
        fun yearBoundary(year: Int): Calendar = calendar(zone).apply {
            clear()
            set(year, yearStartMonth, 1, 0, 0, 0)
        }
        val startCalendar = when (period) {
            ReportPeriod.WEEK -> day.apply { add(Calendar.DAY_OF_MONTH, -((get(Calendar.DAY_OF_WEEK) - weekStart + 7) % 7)) }
            ReportPeriod.MONTH -> {
                val year = day.get(Calendar.YEAR)
                val month = day.get(Calendar.MONTH)
                val nominal = monthBoundary(year, month)
                if (day.timeInMillis >= nominal.timeInMillis) nominal else {
                    val previous = calendar(zone).apply { timeInMillis = day.timeInMillis; add(Calendar.MONTH, -1) }
                    monthBoundary(previous.get(Calendar.YEAR), previous.get(Calendar.MONTH))
                }
            }
            ReportPeriod.YEAR -> {
                val year = day.get(Calendar.YEAR)
                yearBoundary(if (day.get(Calendar.MONTH) >= yearStartMonth) year else year - 1)
            }
        }
        val start = startOfDay(startCalendar, zone)
        val endCalendar = calendar(zone).apply { timeInMillis = start }
        when (period) {
            ReportPeriod.WEEK -> endCalendar.add(Calendar.DAY_OF_MONTH, 7)
            ReportPeriod.MONTH -> endCalendar.add(Calendar.MONTH, 1)
            ReportPeriod.YEAR -> endCalendar.add(Calendar.YEAR, 1)
        }
        val end = when (period) {
            ReportPeriod.WEEK -> startOfDay(endCalendar, zone)
            ReportPeriod.MONTH -> {
                val nextMonth = calendar(zone).apply {
                    timeInMillis = start
                    set(Calendar.DAY_OF_MONTH, 1)
                    add(Calendar.MONTH, 1)
                }
                monthBoundary(nextMonth.get(Calendar.YEAR), nextMonth.get(Calendar.MONTH)).timeInMillis
            }
            ReportPeriod.YEAR -> yearBoundary(endCalendar.get(Calendar.YEAR)).timeInMillis
        }
        return PeriodRange(start, end)
    }

    /** Picker dates are encoded in UTC; the inclusive final date ends at the next local day. */
    fun custom(
        startDayUtc: Long,
        endDayUtc: Long,
        zone: String,
    ): PeriodRange {
        val first = calendar("UTC").apply { timeInMillis = startDayUtc }
        val last = calendar("UTC").apply { timeInMillis = endDayUtc }
        require(startOfDay(first, "UTC") <= startOfDay(last, "UTC"))
        val start = startOfDay(first, zone)
        last.add(Calendar.DAY_OF_MONTH, 1)
        return PeriodRange(start, startOfDay(last, zone))
    }

    fun remainingDays(
        range: PeriodRange,
        atMillis: Long,
        zone: String,
    ): Int {
        if (atMillis >= range.endExclusiveMillis) return 0
        val first = calendar(zone).apply { timeInMillis = atMillis.coerceAtLeast(range.startInclusiveMillis) }
        val end = calendar(zone).apply { timeInMillis = range.endExclusiveMillis }
        return (end.get(Calendar.JULIAN_DAY) - first.get(Calendar.JULIAN_DAY)).coerceAtLeast(0)
    }

    private fun startOfDay(
        day: Calendar,
        zone: String,
    ): Long =
        calendar(zone)
            .apply {
                clear()
                set(day.get(Calendar.YEAR), day.get(Calendar.MONTH), day.get(Calendar.DAY_OF_MONTH), 0, 0, 0)
            }.timeInMillis

    private fun calendar(zone: String): GregorianCalendar {
        require(zone in zones) { "Invalid statistics timezone" }
        return GregorianCalendar(TimeZone.getTimeZone(zone), Locale.ROOT).apply {
            gregorianChange = Date(Long.MIN_VALUE)
            isLenient = true
            // ICU is available on API24, including midnight gaps and repeated midnight.
            setRepeatedWallTimeOption(Calendar.WALLTIME_FIRST)
            setSkippedWallTimeOption(Calendar.WALLTIME_NEXT_VALID)
        }
    }
}
