package dev.icos.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class RecordCalendarGridTest {
    @Test
    fun leapFebruaryStartsMondayFirstAndPadsTheFinalWeek() {
        val cells = calendarMonthCells("2024-02")

        assertEquals(35, cells.size)
        assertEquals("2024-02-01", cells[3])
        assertEquals("2024-02-29", cells[31])
        cells.drop(32).forEach(::assertNull)
    }

    @Test
    fun monthNavigationClampsShortMonthsAndCrossesYearsWithoutLenientDates() {
        assertEquals("2024-02-29", shiftCalendarMonth("2024-03-31", -1))
        assertEquals("2023-02-28", shiftCalendarMonth("2023-01-31", 1))
        assertEquals("2024-01-15", shiftCalendarMonth("2023-12-15", 1))
        assertEquals("2023-12-15", shiftCalendarMonth("2024-01-15", -1))
        assertThrows(IllegalArgumentException::class.java) { calendarMonthCells("2024-13") }
        assertThrows(IllegalArgumentException::class.java) { shiftCalendarMonth("2023-02-29", 1) }
    }
}
