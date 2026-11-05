package dev.icos.app.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.UUID

class AuthStateTest {
    @Test
    fun ambiguousRefreshKeepsOriginalTokenAndOperationUntilCredentialsAreReplaced() {
        val device = UUID.randomUUID()
        val user = AuthUser(UUID.randomUUID(), "User", null, "A.b+tag@example.com")
        val session = AuthSession(user, UUID.randomUUID(), "access", "original-refresh", "date", 1000)
        val pending = AuthState(device, session = session).beginRefresh()
        assertSame(pending, pending.beginRefresh())
        assertEquals("original-refresh", pending.pending!!.originalRefresh)
        assertTrue(pending.acceptsRefresh(pending, pending.pending.opId))
        assertFalse(pending.acceptsRefresh(pending, UUID.randomUUID()))
        assertFalse(pending.acceptsRefresh(AuthState(UUID.randomUUID(), pending.generation, session), pending.pending.opId))

        val signedOut = pending.replace(null)
        assertEquals(device, signedOut.deviceId)
        assertNotEquals(pending.generation, signedOut.generation)
        assertFalse(signedOut.matches(pending))
        assertFalse(signedOut.acceptsRefresh(pending, pending.pending.opId))
        val nextIdentity =
            signedOut.replace(
                AuthSession(user.copy(id = UUID.randomUUID()), UUID.randomUUID(), "new", "new-refresh", "date", 2000),
            )
        assertFalse(nextIdentity.acceptsRefresh(pending, pending.pending.opId))
        assertEquals(null, nextIdentity.pending)
    }

    @Test
    fun datasetClearAndRestoreAreMutuallyExclusiveAcrossCurrentAndArchivedBarriers() {
        val owner = UUID.randomUUID()
        val sessionId = UUID.randomUUID()
        val session = AuthSession(AuthUser(owner, "User", null, "user@example.com"), sessionId,
            "access", "refresh", "date", Long.MAX_VALUE)
        val restore = PendingRestore(owner, sessionId, UUID.randomUUID(), UUID.randomUUID(), 0,
            UUID.randomUUID(), "action", "{}")
        val clear = PendingDatasetClear(owner, UUID.randomUUID(), sessionId, UUID.randomUUID(), UUID.randomUUID(),
            "[\"${UUID.randomUUID()}\"]", 0, "REAUTH", "long-enough-password", null, null, null, null)

        val clearState = AuthState(UUID.randomUUID(), session = session).beginDatasetClear(clear)
        assertThrows(AuthFailure::class.java) { clearState.beginRestore(restore) }
        assertThrows(AuthFailure::class.java) { clearState.replace(null) }

        val restoreState = AuthState(UUID.randomUUID(), session = session).beginRestore(restore)
        assertThrows(AuthFailure::class.java) { restoreState.beginDatasetClear(clear) }
        val archivedRestoreState = AuthState(UUID.randomUUID(), session = session,
            archivedRestores = listOf(restore))
        assertThrows(AuthFailure::class.java) { archivedRestoreState.beginDatasetClear(clear) }
    }

    @Test
    fun datasetClearRequiresAckWatermarkBeforeFinish() {
        val owner = UUID.randomUUID()
        val sessionId = UUID.randomUUID()
        val user = AuthUser(owner, "User", null, "user@example.com")
        val session = AuthSession(user, sessionId, "access", "refresh", "date", Long.MAX_VALUE)
        val initial = AuthState(UUID.randomUUID(), session = session)
        val reauth = PendingDatasetClear(owner, UUID.randomUUID(), sessionId, UUID.randomUUID(), UUID.randomUUID(),
            "[\"${UUID.randomUUID()}\"]", 4, "REAUTH", "long-enough-password", null, null, null, null)
        val staged = initial.beginDatasetClear(reauth)
        val authorized = PendingDatasetClear(owner, reauth.epochId, sessionId, reauth.opId, reauth.groupId,
            reauth.ledgerIdsJson, 4, "AUTHORIZED", reauth.password, null, null, null, null)
        val authorizedState = staged.authorizeDatasetClear(staged, authorized)
        val token = "action-token"
        val wire = "{\"opId\":\"${reauth.opId}\",\"groupId\":\"${reauth.groupId}\",\"token\":\"$token\"}"
        val ready = PendingDatasetClear(owner, reauth.epochId, sessionId, reauth.opId, reauth.groupId,
            reauth.ledgerIdsJson, 4, "READY", null, token, wire, null, null)
        val readyState = authorizedState.updateDatasetClear(authorizedState, ready)
        val acked = PendingDatasetClear(owner, reauth.epochId, sessionId, reauth.opId, reauth.groupId,
            reauth.ledgerIdsJson, 4, "ACKED", null, token, wire, 9, 11)
        assertThrows(AuthFailure::class.java) { readyState.finishDatasetClear(readyState, reauth.opId) }
        val invalidAck = PendingDatasetClear(owner, reauth.epochId, sessionId, reauth.opId, reauth.groupId,
            reauth.ledgerIdsJson, 4, "ACKED", null, token, wire, 9, 8)
        assertThrows(AuthFailure::class.java) { readyState.updateDatasetClear(readyState, invalidAck) }
        val ackedState = readyState.updateDatasetClear(readyState, acked)
        assertThrows(AuthFailure::class.java) { ackedState.finishDatasetClear(readyState, reauth.opId) }
        assertEquals(null, ackedState.finishDatasetClear(ackedState, reauth.opId).datasetClear)
    }

    @Test
    fun diagnosticsDoNotRenderCredentialsOrServerMessages() {
        val user = AuthUser(UUID.randomUUID(), "User", null, "user@example.com")
        val session = AuthSession(user, UUID.randomUUID(), "private-access", "private-refresh", "date", 0)
        assertEquals("AuthSession(redacted)", session.toString())
        assertEquals("PendingRefresh(redacted)", PendingRefresh(UUID.randomUUID(), session.refreshToken).toString())
        val failure = AuthFailure("SERVER_SUPPLIED_TEXT_NOT_FOR_DISPLAY", UUID.randomUUID())
        assertFalse(failure.userMessage.contains(failure.code))
        assertFalse(failure.toString().contains(failure.code))
    }
}
