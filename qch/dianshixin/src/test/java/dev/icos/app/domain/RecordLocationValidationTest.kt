package dev.icos.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RecordLocationValidationTest {
    @Test fun normalizesCoordinatesAndAcceptsInclusiveBounds() {
        assertEquals("12.5" to "-45", RecordCoordinates.validate("012.500", "-45.000"))
        assertEquals("90" to "180", RecordCoordinates.validate("90", "180"))
        assertEquals("-90" to "-180", RecordCoordinates.validate("-90", "-180"))
    }

    @Test fun rejectsOutOfRangeAndNonDecimalCoordinates() {
        listOf(
            "90.0001" to "0", "0" to "180.0001", "NaN" to "0", "Infinity" to "0",
            "1" to "1e100000", "1" to "1.123456789012345678901",
        ).forEach { (lat, lon) -> assertThrows(IllegalArgumentException::class.java) { RecordCoordinates.validate(lat, lon) } }
    }
}
