package dev.icos.app.domain

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

@RunWith(AndroidJUnit4::class)
class PeriodResolverTest {
    private fun instant(value: String): Long =
        checkNotNull(
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT)
                .apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                    isLenient = false
                }.parse(value),
        ).time

    private fun date(value: String) = instant(value + "T00:00:00Z")

    @Test
    fun calendarPeriodsCrossYearAndLeapMonthWithoutFixedDayArithmetic() {
        val week = PeriodResolver.range(ReportPeriod.WEEK, date("2025-12-31"), "UTC")
        assertEquals(PeriodRange(date("2025-12-29"), date("2026-01-05")), week)
        assertEquals(
            PeriodRange(date("2024-02-01"), date("2024-03-01")),
            PeriodResolver.range(ReportPeriod.MONTH, date("2024-02-29"), "UTC"),
        )
        assertEquals(
            PeriodRange(date("2024-01-01"), date("2025-01-01")),
            PeriodResolver.range(ReportPeriod.YEAR, date("2024-02-29"), "UTC"),
        )
        assertThrows(IllegalArgumentException::class.java) { PeriodResolver.custom(date("2024-03-01"), date("2024-02-29"), "UTC") }
        assertThrows(IllegalArgumentException::class.java) { PeriodResolver.range(ReportPeriod.WEEK, 0, "Invalid/Zone") }
    }

    @Test
    fun configuredWeekMonthAndYearStartsUseCalendarBoundaries() {
        val sundayWeek = PeriodResolver.range(ReportPeriod.WEEK, date("2025-12-31"), "UTC", weekStart = android.icu.util.Calendar.SUNDAY)
        assertEquals(PeriodRange(date("2025-12-28"), date("2026-01-04")), sundayWeek)

        val midMonth = PeriodResolver.range(ReportPeriod.MONTH, date("2025-05-14"), "UTC", monthStart = 15)
        assertEquals(PeriodRange(date("2025-04-15"), date("2025-05-15")), midMonth)
        assertEquals(PeriodRange(date("2025-05-15"), date("2025-06-15")),
            PeriodResolver.range(ReportPeriod.MONTH, date("2025-05-15"), "UTC", monthStart = 15))
        assertEquals(PeriodRange(date("2025-02-28"), date("2025-03-31")),
            PeriodResolver.range(ReportPeriod.MONTH, date("2025-03-01"), "UTC", monthStart = 31))

        assertEquals(PeriodRange(date("2024-04-01"), date("2025-04-01")),
            PeriodResolver.range(ReportPeriod.YEAR, date("2025-03-31"), "UTC", yearStartMonth = android.icu.util.Calendar.APRIL))
        assertEquals(PeriodRange(date("2025-04-01"), date("2026-04-01")),
            PeriodResolver.range(ReportPeriod.YEAR, date("2025-04-01"), "UTC", yearStartMonth = android.icu.util.Calendar.APRIL))
    }

    @Test
    fun configuredStartsRespectLocalCalendarAcrossDaylightSaving() {
        val month = PeriodResolver.range(ReportPeriod.MONTH, instant("2024-04-15T16:00:00Z"), "America/New_York", monthStart = 15)
        assertEquals(instant("2024-04-15T04:00:00Z"), month.startInclusiveMillis)
        assertEquals(instant("2024-05-15T04:00:00Z"), month.endExclusiveMillis)
    }

    @Test
    fun inclusivePickerEndUsesLocalHalfOpenDaysAcrossDst() {
        val spring = PeriodResolver.custom(date("2024-03-10"), date("2024-03-10"), "America/New_York")
        assertEquals(instant("2024-03-10T05:00:00Z"), spring.startInclusiveMillis)
        assertEquals(instant("2024-03-11T04:00:00Z"), spring.endExclusiveMillis)
        assertEquals(23 * 3_600_000L, spring.endExclusiveMillis - spring.startInclusiveMillis)
        assertTrue(spring.startInclusiveMillis in spring)
        assertTrue(spring.endExclusiveMillis - 1 in spring)
        assertFalse(spring.endExclusiveMillis in spring)
        assertFalse(spring.startInclusiveMillis - 1 in spring)
        val fall = PeriodResolver.custom(date("2024-11-03"), date("2024-11-03"), "America/New_York")
        assertEquals(25 * 3_600_000L, fall.endExclusiveMillis - fall.startInclusiveMillis)
        assertEquals(1, PeriodResolver.remainingDays(spring, spring.startInclusiveMillis + 12 * 3_600_000L, "America/New_York"))
        assertEquals(1, PeriodResolver.remainingDays(fall, fall.startInclusiveMillis + 24 * 3_600_000L, "America/New_York"))
        assertEquals(0, PeriodResolver.remainingDays(fall, fall.endExclusiveMillis, "America/New_York"))
    }

    @Test
    fun midnightGapUsesNextValidTimeAndRepeatedMidnightUsesFirstOccurrence() {
        val gap = PeriodResolver.custom(date("2018-11-04"), date("2018-11-04"), "America/Sao_Paulo")
        assertEquals(instant("2018-11-04T03:00:00Z"), gap.startInclusiveMillis)
        assertEquals(instant("2018-11-05T02:00:00Z"), gap.endExclusiveMillis)
        val repeated = PeriodResolver.custom(date("2020-11-01"), date("2020-11-01"), "America/Havana")
        assertEquals(instant("2020-11-01T04:00:00Z"), repeated.startInclusiveMillis)
        assertEquals(instant("2020-11-02T05:00:00Z"), repeated.endExclusiveMillis)
    }
}
