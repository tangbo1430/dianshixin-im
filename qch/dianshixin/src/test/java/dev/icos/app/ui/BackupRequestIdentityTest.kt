package dev.icos.app.ui

import dev.icos.app.data.remote.DeviceIdentity
import dev.icos.app.data.remote.AuthFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class BackupRequestIdentityTest {
    @Test fun tokenRotationKeepsPendingOperationAndBodyForSameOwnerSessionAndUiGeneration() {
        val owner = UUID.randomUUID()
        val session = UUID.randomUUID()
        val uiGeneration = UUID.randomUUID()
        val opId = UUID.randomUUID()
        val body = "{\"expectedEpoch\":\"epoch\",\"minAcceptedSeq\":19,\"reason\":\"MANUAL\"}"
        val original = BackupRequestIdentity(DeviceIdentity(owner, session), uiGeneration, opId, body)
        val retry = original.copy()

        retry.requireCurrent(DeviceIdentity(owner, session), uiGeneration, opId, body)
        assertEquals(original, retry)
        assertEquals(original.opId, retry.opId)
        assertEquals(original.bodyJson, retry.bodyJson)
        assertTrue(original.toString().contains("redacted"))
    }

    @Test fun ownerSessionOrUiGenerationChangeRejectsPendingOperation() {
        val owner = UUID.randomUUID()
        val identity = DeviceIdentity(owner, UUID.randomUUID())
        val uiGeneration = UUID.randomUUID()
        val operation = BackupRequestIdentity(identity, uiGeneration, UUID.randomUUID(), "{}")

        assertRejected { operation.requireCurrent(DeviceIdentity(UUID.randomUUID(), identity.sessionId), uiGeneration, operation.opId, operation.bodyJson) }
        assertRejected { operation.requireCurrent(DeviceIdentity(owner, UUID.randomUUID()), uiGeneration, operation.opId, operation.bodyJson) }
        assertRejected { operation.requireCurrent(identity, UUID.randomUUID(), operation.opId, operation.bodyJson) }
        assertRejected { operation.requireCurrent(identity, uiGeneration, UUID.randomUUID(), operation.bodyJson) }
        assertRejected { operation.requireCurrent(identity, uiGeneration, operation.opId, "{\"changed\":true}") }
    }

    private fun assertRejected(block: () -> Unit) {
        try {
            block()
            throw AssertionError("expected stale backup identity")
        } catch (_: AuthFailure) {
        }
    }
}
