package dev.icos.app.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AvatarTimestampTest {
    @Test fun attachmentExpiryAcceptsRustRfc3339PrecisionAndOffsets() {
        val seconds = 1_767_225_600_000L
        assertEquals(seconds, AuthJson.attachmentTimestamp("2026-01-01T00:00:00Z"))
        assertEquals(seconds, AuthJson.attachmentTimestamp("2026-01-01T08:00:00+08:00"))
        assertEquals(seconds, AuthJson.attachmentTimestamp("2025-12-31T23:00:00-01:00"))
        listOf(".1Z" to 100L, ".123Z" to 123L, ".123456Z" to 123L, ".123456789Z" to 123L).forEach { (fraction, millis) ->
            assertEquals(seconds + millis, AuthJson.attachmentTimestamp("2026-01-01T00:00:00$fraction"))
        }
    }

    @Test fun malformedAttachmentExpiryIsExplicitInvalidResponse() {
        listOf("", "2026-02-30T00:00:00Z", "2026-01-01T24:00:00Z", "2026-01-01T00:00:00",
            "2026-01-01T00:00:00.Z", "2026-01-01T00:00:00.1234567890Z", "2026-01-01T00:00:00+25:00",
            "2026-01-01T00:00:00Zsuffix", "2026-01-01T00:00:00Z\n", "1970-01-01T00:00:00Z").forEach {
            assertEquals("INVALID_RESPONSE", assertThrows(AuthFailure::class.java) { AuthJson.attachmentTimestamp(it) }.code)
        }
    }

    @Test fun authCredentialTimestampRemainsFixedMilliseconds() {
        assertEquals(1_767_225_600_123L, AuthJson.timestamp("2026-01-01T00:00:00.123Z"))
        listOf("2026-01-01T00:00:00Z", "2026-01-01T00:00:00.123456Z",
            "2026-01-01T08:00:00.123+08:00").forEach {
            assertThrows(IllegalArgumentException::class.java) { AuthJson.timestamp(it) }
        }
    }
}
