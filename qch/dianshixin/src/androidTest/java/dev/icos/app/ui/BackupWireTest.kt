package dev.icos.app.ui

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class BackupWireTest {
    private fun backup(id: UUID = UUID.randomUUID()) = JSONObject()
        .put("id", id.toString())
        .put("epoch", UUID.randomUUID().toString())
        .put("kind", "MANUAL")
        .put("state", "READY")
        .put("capturedSeq", 12)
        .put("schemaVersion", 6)
        .put("manifest", JSONObject())
        .put("manifestDigest", JSONObject.NULL)
        .put("objectDigest", JSONObject.NULL)
        .put("storedBytes", 1024)
        .put("plainBytes", 2048)
        .put("failureCode", JSONObject.NULL)
        .put("createdAt", "2026-10-19T10:00:00Z")
        .put("protectedUntil", JSONObject.NULL)
        .put("deleteAfter", JSONObject.NULL)

    @Test fun parsesListAndRejectsUnknownOrInvalidProjection() {
        val id = UUID.randomUUID()
        val page = BackupWire.list(JSONObject().put("items", JSONArray().put(backup(id))).put("nextBefore", JSONObject.NULL))
        assertEquals(id, page.first.single().id)
        assertNull(page.second)
        assertThrows(IllegalArgumentException::class.java) { BackupWire.backup(backup().put("ownerId", UUID.randomUUID().toString())) }
        assertThrows(IllegalArgumentException::class.java) { BackupWire.backup(backup().put("state", "RESTORING")) }
    }

    @Test fun parsesPolicyAndRejectsUnknownFieldsAndInvalidRetention() {
        val policy = JSONObject().put("enabled", true).put("dailyTime", "03:00").put("zoneId", "Pacific/Auckland").put("dailyRetention", 30).put("manualRetention", 60).put("version", 4).put("updatedAt", "2026-10-19T10:00:00Z")
        assertEquals(30, BackupWire.policy(policy).dailyRetention)
        assertNull(BackupWire.policy(policy).lastAutoReused)
        assertThrows(IllegalArgumentException::class.java) { BackupWire.policy(JSONObject(policy.toString()).put("ownerId", UUID.randomUUID().toString())) }
        assertThrows(IllegalArgumentException::class.java) { BackupWire.policy(JSONObject(policy.toString()).put("dailyRetention", 366)) }
    }

    @Test fun acceptsBackendPolicyTimeFormatFixture() {
        val fixture = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context.assets
            .open("backup-policy-time-fixture.json").bufferedReader().use { JSONObject(it.readText()) }
        assertEquals(30, BackupWire.policy(fixture).dailyRetention)
        assertEquals("03:00", BackupWire.policy(fixture).dailyTime)
    }

    @Test fun validatesPolicyMutationBeforeOperationCreation() {
        assertTrue(BackupPolicyInput.create(true, "23:59", "UTC", 1, 365) != null)
        assertNull(BackupPolicyInput.create(true, "24:00", "UTC", 1, 365))
        assertNull(BackupPolicyInput.create(true, "12:00", "../bad", 1, 365))
    }
}
