package dev.icos.app.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class AuthRecoveryTest {
    private val user = AuthUser(UUID.randomUUID(), "User", null, "A.b+tag@example.com")
    private val sessionId = UUID.randomUUID()

    private fun session(
        token: String,
        expires: Long = 1_000_000,
    ) = AuthSession(user, sessionId, "access-$token", token, "date", expires)

    private fun reset(email: String = user.verifiedEmail) = PendingReset(UUID.randomUUID(), UUID.randomUUID(), email)

    @Test
    fun signedOutLogoutKeepsAmbiguousRefreshAndConfirmedRotationAcrossRetries() {
        val signedIn = AuthState(UUID.randomUUID(), session = session("original", 90_000))
        val signedOut = signedIn.replace(null)
        val operation = UUID.randomUUID()
        val logout = LogoutAttempt(signedIn, operation)
        val refreshes = mutableListOf<PendingRefresh>()
        val refresh: (AuthState, PendingRefresh) -> AuthSession = { snapshot, pending ->
            assertEquals(signedIn.deviceId, snapshot.deviceId)
            refreshes.add(pending)
            if (refreshes.size == 1) throw AuthFailure("NETWORK", retryable = true)
            session("confirmed-rotation")
        }
        assertThrows(AuthFailure::class.java) { logout.run(100_000, refresh) { _, _ -> error("refresh must finish first") } }
        assertThrows(AuthFailure::class.java) {
            logout.run(100_000, refresh) { snapshot, op ->
                assertEquals(operation, op)
                assertEquals("confirmed-rotation", snapshot.session!!.refreshToken)
                throw AuthFailure("STORAGE_UNAVAILABLE", retryable = true)
            }
        }
        logout.run(200_000, refresh) { snapshot, op ->
            assertEquals(operation, op)
            assertEquals("confirmed-rotation", snapshot.session!!.refreshToken)
        }
        assertEquals(2, refreshes.size)
        assertEquals(refreshes[0].opId, refreshes[1].opId)
        assertEquals("original", refreshes[1].originalRefresh)
        assertNull(signedOut.session)
        assertFalse(signedOut.matches(signedIn))
    }

    @Test
    fun logoutRefreshesServerReportedExpiryButNeverTreatsUnauthorizedAsRevocationProof() {
        val snapshot = AuthState(UUID.randomUUID(), session = session("initial"))
        val logout = LogoutAttempt(snapshot, UUID.randomUUID())
        var rotations = 0
        var revocations = 0
        logout.run(10_000, { _, _ ->
            rotations++
            session("rotated")
        }) { actor, _ ->
            if (++revocations == 1) throw AuthFailure("SESSION_EXPIRED")
            assertEquals("rotated", actor.session!!.refreshToken)
        }
        assertEquals(1, rotations)
        assertEquals(2, revocations)
        val revoked = LogoutAttempt(AuthState(snapshot.deviceId, session = session("expired", 0)), UUID.randomUUID())
        val failure =
            assertThrows(AuthFailure::class.java) {
                revoked.run(10_000, { _, _ -> throw AuthFailure("SESSION_REVOKED") }) { _, _ -> error("no usable token") }
            }
        assertEquals("SESSION_REVOKED", failure.code)
    }

    @Test
    fun resetTimeoutSurvivesClientLifetimeAndFencesEverySignInGeneration() {
        val before = AuthState(UUID.randomUUID())
        val operation = reset()
        val fenced = before.beginReset(operation)
        assertThrows(AuthFailure::class.java) {
            ResetAttempt(fenced).run({ throw AuthFailure("NETWORK", retryable = true) }) { error("must not complete") }
        }
        // Reconstruct the envelope's metadata as another client does after VM destruction/restart.
        val reopened =
            AuthState(fenced.deviceId, fenced.generation, reset = PendingReset(operation.opId, operation.challengeId, operation.email))
        assertFalse(reopened.matches(before))
        assertEquals("RESET_UNCONFIRMED", assertThrows(AuthFailure::class.java) { reopened.requireSignIn() }.code)
        assertThrows(AuthFailure::class.java) { reopened.replace(session("server-revoked")) }
        assertThrows(AuthFailure::class.java) { reopened.replace(null) }
    }

    @Test
    fun originalResetRecoveryKeepsOperationAndOnlyConfirmedResultClearsFence() {
        var current = AuthState(UUID.randomUUID()).beginReset(reset())
        val original = current
        val operation = checkNotNull(current.reset)
        val recovered = current.beginReset(PendingReset(operation.opId, operation.challengeId, operation.email))
        assertSame(current, recovered)
        for (code in listOf("IDEMPOTENCY_KEY_REUSED", "IDEMPOTENCY_RECEIPT_EXPIRED", "INVALID_CHALLENGE")) {
            assertThrows(AuthFailure::class.java) {
                ResetAttempt(recovered).run({ throw AuthFailure(code) }) { current = current.finishReset(it) }
            }
            assertSame(original, current)
            assertThrows(AuthFailure::class.java) { current.requireSignIn() }
        }
        ResetAttempt(recovered).run({ operation ->
            assertEquals(original.reset!!.opId, operation.opId)
            assertEquals(original.reset.challengeId, operation.challengeId)
            assertEquals(original.reset.email, operation.email)
        }) { current = current.finishReset(it) }
        current.requireSignIn()
        assertNull(current.reset)
        assertNotEquals(original.generation, current.generation)
    }

    @Test
    fun freshSameEmailResetSupersedesOldFenceWithoutLettingLateCompletionClobberAnotherAccount() {
        val old = AuthState(UUID.randomUUID()).beginReset(reset())
        assertThrows(AuthFailure::class.java) { old.beginReset(reset("other@example.com")) }
        assertThrows(
            AuthFailure::class.java,
        ) { old.beginReset(PendingReset(UUID.randomUUID(), old.reset!!.challengeId, user.verifiedEmail)) }
        var current = old.beginReset(reset())
        val fresh = current
        assertNotEquals(old.generation, fresh.generation)
        assertThrows(AuthFailure::class.java) { current.finishReset(old) }
        ResetAttempt(fresh).run({ assertEquals(user.verifiedEmail, it.email) }) { current = current.finishReset(it) }
        current.requireSignIn()
        val anotherAccount =
            session("another-account").let {
                AuthSession(
                    user.copy(id = UUID.randomUUID()),
                    UUID.randomUUID(),
                    it.accessToken,
                    it.refreshToken,
                    it.expiresAt,
                    it.expiresAtMillis,
                )
            }
        current = current.replace(anotherAccount)
        assertThrows(AuthFailure::class.java) { current.finishReset(old) }
        assertThrows(AuthFailure::class.java) { current.finishReset(fresh) }
        assertSame(anotherAccount, current.session)
    }

    @Test
    fun restoreWireSurvivesRefreshAndSessionSwitchArchivesThenResumesOriginal() {
        val owner = user.id
        val original = AuthState(UUID.randomUUID(), session = session("before"))
        val restore = PendingRestore(
            owner, sessionId, UUID.randomUUID(), UUID.randomUUID(), 17, UUID.randomUUID(), "action-token",
            "{\"backupId\":\"backup\",\"expectedHeadSeq\":17,\"actionToken\":\"action-token\"}",
        )
        val pending = original.beginRestore(restore)
        val rotated = pending.replace(session("after"))
        assertTrue(rotated.hasRestoreBarrierForCurrentSession())
        assertEquals(restore.opId, rotated.restore?.opId)
        assertEquals(restore.requestBody, rotated.restore?.requestBody)

        val otherSession = AuthSession(user.copy(id = UUID.randomUUID()), UUID.randomUUID(), "other-access", "other-refresh", "date", 1_000_000)
        val switched = rotated.replace(otherSession)
        assertSame(restore, switched.archivedRestores.single())
        assertFalse(switched.hasRestoreBarrierForCurrentSession())
        val resumed = switched.replace(session("return"))
        assertTrue(resumed.hasRestoreBarrierForCurrentSession())
        assertEquals(restore.opId, resumed.restore?.opId)
        assertEquals(restore.actionToken, resumed.restore?.actionToken)
    }

    @Test
    fun archivedRestoreForSameOwnerBlocksDifferentSessionAndCannotBeReplaced() {
        val operation = PendingRestore(
            user.id, sessionId, UUID.randomUUID(), UUID.randomUUID(), 0, UUID.randomUUID(), "token",
            "{\"backupId\":\"backup\",\"expectedHeadSeq\":0,\"actionToken\":\"token\"}",
        )
        val signedOut = AuthState(UUID.randomUUID(), session = session("one")).beginRestore(operation).replace(null)
        val newSession = AuthSession(user, UUID.randomUUID(), "new-access", "new-refresh", "date", 1_000_000)
        assertThrows(AuthFailure::class.java) { signedOut.replace(newSession) }
        assertEquals(operation.opId, signedOut.archivedRestores.single().opId)
    }

    @Test
    fun retryableUpstreamStorageFailuresRemainDistinctFromLocalKeyFailures() {
        for (code in listOf("STORAGE_UNAVAILABLE", "AUTH_UNAVAILABLE")) {
            val failure = AuthFailure(code, UUID.randomUUID(), retryable = true)
            assertTrue(failure.retryable)
            assertTrue(failure.userMessage.contains("服务器"))
            assertFalse(failure.userMessage.contains("设备密钥"))
        }
        val local = AuthFailure("LOCAL_STORAGE_UNAVAILABLE")
        assertFalse(local.retryable)
        assertTrue(local.userMessage.contains("设备密钥"))
    }
}
