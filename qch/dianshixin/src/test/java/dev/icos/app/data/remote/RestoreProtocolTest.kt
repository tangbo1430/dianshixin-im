package dev.icos.app.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.UUID

class RestoreProtocolTest {
    private fun state(owner: UUID, session: UUID) = AuthState(
        UUID.randomUUID(),
        session = AuthSession(AuthUser(owner, "User", null, "user@example.com"), session, "access", "refresh", "date", 1_000_000),
    )

    @Test
    fun sameSubmissionRetryKeepsWireAndRejectsOwnerOrSessionChanges() {
        val owner = UUID.randomUUID()
        val sessionId = UUID.randomUUID()
        val opId = UUID.randomUUID()
        val body = "{\"backupId\":\"fixed\",\"expectedHeadSeq\":27}"
        val operation = RestoreOperation(
            DeviceSessions.identity(state(owner, sessionId)), UUID.randomUUID(), UUID.randomUUID(), 27,
            opId, "opaque-action-token", body,
        )
        val accepted = RestoreProtocol.validateAccepted(RestoreAccepted(UUID.randomUUID(), opId, "QUEUED"), opId)
        assertEquals(opId, accepted.opId)
        operation.retainedFor(state(owner, sessionId))
        assertEquals(opId, operation.opId)
        assertEquals(body, operation.requestBody)
        assertThrows(AuthFailure::class.java) { operation.retainedFor(state(UUID.randomUUID(), sessionId)) }
        assertThrows(AuthFailure::class.java) { operation.retainedFor(state(owner, UUID.randomUUID())) }
        assertThrows(AuthFailure::class.java) {
            RestoreProtocol.validateAccepted(RestoreAccepted(UUID.randomUUID(), UUID.randomUUID(), "QUEUED"), opId)
        }
    }

    @Test
    fun committedRestoreBootstrapContextIsPinnedToRestoreAndSnapshotJobs() {
        val owner = UUID.randomUUID()
        val sessionId = UUID.randomUUID()
        val restoreJob = UUID.randomUUID()
        val committedEpoch = UUID.randomUUID()
        val snapshotOp = UUID.randomUUID()
        val snapshotJob = UUID.randomUUID()
        val wire = RestoreBootstrapWire(owner, sessionId, restoreJob, committedEpoch, snapshotOp, snapshotJob)
        assertEquals(owner, wire.owner)
        assertEquals(sessionId, wire.sessionId)
        assertEquals(restoreJob, wire.restoreJobId)
        assertEquals(committedEpoch, wire.committedEpoch)
        assertEquals(snapshotOp, wire.bootstrapOpId)
        assertEquals(snapshotJob, wire.bootstrapJobId)
        assertNotEquals(owner, wire.copy(owner = UUID.randomUUID()).owner)
        assertNotEquals(sessionId, wire.copy(sessionId = UUID.randomUUID()).sessionId)
        assertNotEquals(restoreJob, wire.copy(restoreJobId = UUID.randomUUID()).restoreJobId)
        assertNotEquals(committedEpoch, wire.copy(committedEpoch = UUID.randomUUID()).committedEpoch)
        assertNotEquals(snapshotOp, wire.copy(bootstrapOpId = UUID.randomUUID()).bootstrapOpId)
        assertNotEquals(snapshotJob, wire.copy(bootstrapJobId = UUID.randomUUID()).bootstrapJobId)
    }

    @Test
    fun committedStatusMustMatchJobAndChangeEpoch() {
        val jobId = UUID.randomUUID()
        val oldEpoch = UUID.randomUUID()
        val newEpoch = UUID.randomUUID()
        val status = RestoreStatus(jobId, UUID.randomUUID(), UUID.randomUUID(), "COMMITTED", 100, null, oldEpoch, 27, newEpoch)
        assertEquals(newEpoch, RestoreProtocol.validateStatus(status, jobId).committedEpoch)
        assertThrows(AuthFailure::class.java) { RestoreProtocol.validateStatus(status.copy(jobId = UUID.randomUUID()), jobId) }
        assertThrows(AuthFailure::class.java) { RestoreProtocol.validateStatus(status.copy(committedEpoch = oldEpoch), jobId) }
        assertThrows(AuthFailure::class.java) { RestoreProtocol.validateStatus(status.copy(progress = 101), jobId) }
    }
}
