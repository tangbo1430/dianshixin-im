package dev.icos.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LockPreferenceIntegrityTest {
    private val hashKey = "pin_hash"
    private val saltKey = "pin_salt"
    private val initializedKey = "lock_initialized"

    private fun classify(marker: MarkerRecord?, values: Map<String, *>, fileExists: Boolean) =
        classifyLockPreferences(marker, values, fileExists, hashKey, saltKey, initializedKey)

    @Test fun pristineInstallAndValidExplicitlyDisabledStateAreDistinct() {
        assertEquals(LockPreferenceState.NEVER_CONFIGURED, classify(null, emptyMap<String, Any>(), false))
        val disabledPreferences = mapOf("timeout_minutes" to 5, "privacy_screen" to false, initializedKey to true)
        val validDisabledMarker = MarkerRecord.fromPreferences(disabledPreferences)
        assertEquals(LockPreferenceState.VALID_UNCONFIGURED, classify(validDisabledMarker, disabledPreferences, true))
        assertFalse(validDisabledMarker.matches(emptyMap<String, Any>()))
    }

    @Test fun missingOrBrokenPreferenceFileAndPartialCredentialsFailClosed() {
        val credentials = mapOf(hashKey to "encoded", saltKey to "salt", "failed_attempts" to 2, initializedKey to false)
        val credentialMarker = MarkerRecord.fromPreferences(credentials)
        assertEquals(LockPreferenceState.CORRUPT, classify(null, credentials, true))
        assertEquals(LockPreferenceState.CORRUPT, classify(credentialMarker, emptyMap<String, Any>(), true))
        assertEquals(LockPreferenceState.CORRUPT, classify(MarkerRecord.INVALID, credentials, true))
        val partial = mapOf(hashKey to "encoded", initializedKey to false)
        assertEquals(LockPreferenceState.CORRUPT, classify(MarkerRecord.fromPreferences(partial), partial, true))
    }

    @Test fun truncatedXmlReadAsEmptyIsDetectedByPersistedDigest() {
        val beforeTruncation = mapOf(hashKey to "base64-hash", saltKey to "base64-salt",
            "failed_attempts" to 7, "lockout_elapsed_deadline" to 123456L, initializedKey to false)
        val marker = MarkerRecord.fromPreferences(beforeTruncation)
        // Android may return an empty map when SharedPreferences XML cannot be parsed.
        assertEquals(LockPreferenceState.CORRUPT, classify(marker, emptyMap<String, Any>(), true))
        assertTrue(marker.matches(beforeTruncation))
    }

    @Test fun corruptedMarkerAndOrphanBackupCannotBecomeFreshInstall() {
        assertEquals(LockPreferenceState.CORRUPT, classify(MarkerRecord.INVALID, emptyMap<String, Any>(), true))
        assertEquals(LockPreferenceState.CORRUPT, classify(null, emptyMap<String, Any>(), true))
        assertEquals(LockPreferenceState.CORRUPT,
            classify(MarkerRecord.fromPreferences(mapOf(initializedKey to true)), mapOf(initializedKey to true), false))
    }
}
