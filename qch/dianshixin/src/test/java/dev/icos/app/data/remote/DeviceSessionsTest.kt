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

/** Exercises the production device identity, page accumulation and revoke-retry policy (no Android). */
class DeviceSessionsTest {
    private val owner = UUID.randomUUID()
    private val sessionId = UUID.randomUUID()
    private val user = AuthUser(owner, "User", null, "user@example.com")

    private fun session(
        token: String,
        owner: UUID = this.owner,
        sessionId: UUID = this.sessionId,
    ) = AuthSession(AuthUser(owner, "User", null, "user@example.com"), sessionId, "access-$token", "refresh-$token", "date", 1_000_000)

    private fun row(
        index: Int,
        current: Boolean = false,
    ) = DeviceSession(
        sessionId = UUID(0, index.toLong()),
        deviceName = "device-$index",
        lastSeenAt = "2026-01-01T00:00:00.000Z",
        lastSeenAtMillis = index.toLong(),
        current = current,
    )

    private fun page(
        items: List<DeviceSession>,
        nextCursor: String?,
        hasMore: Boolean,
    ) = DevicePage(items, nextCursor, hasMore)

    @Test
    fun rotatedAccessTokenKeepsIdentityButAReplacedAccountOrSessionIsStale() {
        val initial = AuthState(UUID.randomUUID(), session = session("one"))
        val identity = DeviceSessions.identity(initial)
        // A refresh rotates tokens for the SAME owner and session; the pending device work stays valid.
        val rotated = initial.replace(session("two"))
        DeviceSessions.requireSameIdentity(identity, rotated)

        assertThrows(AuthFailure::class.java) { DeviceSessions.requireSameIdentity(identity, AuthState(initial.deviceId)) }
        assertThrows(AuthFailure::class.java) {
            DeviceSessions.requireSameIdentity(identity, AuthState(initial.deviceId, initial.generation, session("two", sessionId = UUID.randomUUID())))
        }
        assertThrows(AuthFailure::class.java) {
            DeviceSessions.requireSameIdentity(identity, AuthState(initial.deviceId, initial.generation, session("two", owner = UUID.randomUUID())))
        }
        assertEquals("SESSION_EXPIRED", assertThrows(AuthFailure::class.java) {
            DeviceSessions.requireSameIdentity(identity, AuthState(initial.deviceId))
        }.code)
    }

    @Test
    fun revokeOperationKeepsItsUuidAndTargetAcrossRetriesAndNeverRebinds() {
        val target = UUID.randomUUID()
        val originalState = AuthState(UUID.randomUUID(), session = session("one"))
        val operation = DeviceSessions.operation(originalState, target)
        val rotatedState = originalState.replace(session("rotated"))
        // A retry after an ambiguous failure reuses the exact operation and target (same receipt key).
        operation.acceptRotation(originalState, rotatedState)
        operation.retainedFor(rotatedState)
        assertTrue(operation.opId != UUID(0, 0))
        assertEquals(target, operation.target)

        // A different target is a different operation, never a silent reuse of the same UUID.
        assertNotEquals(operation.opId, DeviceSessions.operation(originalState, UUID.randomUUID()).opId)
        assertEquals(target, DeviceSessions.operation(originalState, target, operation.opId).target)
        assertEquals("INVALID_INPUT", assertThrows(AuthFailure::class.java) {
            DeviceSessions.operation(originalState, sessionId)
        }.code)

        // Rebinding to another signed-in session or signing out must be rejected, not retried blindly.
        val otherSession = rotatedState.replace(session("x", sessionId = UUID.randomUUID()))
        assertThrows(AuthFailure::class.java) { operation.retainedFor(otherSession) }
        assertThrows(AuthFailure::class.java) { operation.retainedFor(rotatedState.replace(null)) }
        assertThrows(AuthFailure::class.java) { DeviceSessions.operation(AuthState(UUID.randomUUID()), target) }
    }

    @Test
    fun currentSessionRowRoutesToLogoutWhileOtherSessionsAreRevocable() {
        assertNull(DeviceSessions.revokeTarget(row(7, current = true)))
        assertEquals(UUID(0, 8), DeviceSessions.revokeTarget(row(8)))
    }

    @Test
    fun pagesAppendInOrderAndStopAtTheLastPage() {
        val first = page(listOf(row(1), row(2)), nextCursor = "cursor-1", hasMore = true)
        val loaded = DeviceSessions.fresh(first)
        assertTrue(loaded.hasMore)
        assertEquals("cursor-1", loaded.nextCursor)
        assertEquals(listOf(UUID(0, 1), UUID(0, 2)), loaded.items.map { it.sessionId })

        val complete = loaded.append(page(listOf(row(3)), nextCursor = null, hasMore = false))
        assertEquals(listOf(UUID(0, 1), UUID(0, 2), UUID(0, 3)), complete.items.map { it.sessionId })
        assertFalse(complete.hasMore)
        assertNull(complete.nextCursor)

        val empty = DeviceSessions.fresh(page(emptyList(), nextCursor = null, hasMore = false))
        assertTrue(empty.items.isEmpty())
        assertFalse(empty.hasMore)
        assertNull(empty.nextCursor)
    }

    @Test
    fun duplicateOverlappingLoopingMismatchedAndOversizedPagesAreRejected() {
        val loaded = DeviceSessions.fresh(page(listOf(row(1)), nextCursor = "cursor-1", hasMore = true))

        assertThrows(AuthFailure::class.java) { loaded.append(page(listOf(row(2), row(2)), "cursor-2", true)) }
        assertThrows(AuthFailure::class.java) { loaded.append(page(listOf(row(1)), "cursor-2", true)) }
        assertThrows(AuthFailure::class.java) { loaded.append(page(listOf(row(2)), "cursor-1", true)) }
        assertThrows(AuthFailure::class.java) { loaded.append(page(listOf(row(2)), null, true)) }
        assertThrows(AuthFailure::class.java) { loaded.append(page(List(DeviceSessions.MAX_PAGE_ITEMS + 1) { row(it + 2) }, "cursor-2", true)) }
        assertThrows(AuthFailure::class.java) { DeviceSessions.fresh(page(List(DeviceSessions.MAX_PAGE_ITEMS + 1) { row(it + 2) }, null, false)) }
        assertThrows(AuthFailure::class.java) { DeviceSessions.fresh(page(listOf(row(1), row(1)), null, false)) }
        assertThrows(AuthFailure::class.java) { DeviceSessions.fresh(page(listOf(row(1)), "cursor", false)) }
        assertThrows(AuthFailure::class.java) { DeviceSessions.fresh(page(listOf(row(1)), null, false)).append(page(listOf(row(2)), null, false)) }

        val dropped = loaded.without(UUID(0, 1))
        assertTrue(dropped.items.isEmpty())
        assertFalse(dropped.hasMore)
        assertNull(dropped.nextCursor)
    }
}
