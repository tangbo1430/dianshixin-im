package dev.icos.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecurrenceDateTimeTest {
    @Test
    fun localStartRoundTripsInZoneOffsetIncludingDstPeriods() {
        val newYork = recurrenceStartMillis("2026-07-01T09:00:00", "America/New_York")
        assertEquals("2026-07-01T09:00:00", newYork?.let { recurrenceLocalDateTime(it, "America/New_York") })
        val shanghai = recurrenceStartMillis("2026-06-01T09:00:00", "Asia/Shanghai")
        assertEquals("2026-06-01T09:00:00", shanghai?.let { recurrenceLocalDateTime(it, "Asia/Shanghai") })
    }

    @Test
    fun unavailableLocalTimesAreRejectedInsteadOfShifted() {
        assertNull(recurrenceStartMillis("2026-03-08T02:30:00", "America/New_York"))
        assertNull(recurrenceStartMillis("2026-06-01T09:00:00", "Mars/Olympus"))
        assertNull(recurrenceStartMillis("not a time", "Asia/Shanghai"))
    }

    @Test
    fun endAtPreviewKeepsItsOwnOffset() {
        // 2026-06-01T00:00:00+08:00 is 2026-05-31T16:00Z, shown as 2026-06-01 01:00 in Asia/Tokyo.
        val tokyo = recurrenceEndText("2026-06-01T00:00:00+08:00", "Asia/Tokyo")
        assertTrue(tokyo != null && tokyo.startsWith("2026年6月1日") && tokyo.endsWith("01:00"))
        assertTrue(recurrenceEndText("2026-06-01T00:00:00+08:00", "+09:00")?.endsWith("01:00") == true)
        assertNull(recurrenceEndText("2026-06-01T00:00:00", "Asia/Tokyo"))
    }
}
