package dev.icos.app.data.remote

import java.util.UUID

/** Identity of one authenticated server session: access-token rotation keeps it, replacement does not. */
internal data class DeviceIdentity(
    val owner: UUID,
    val sessionId: UUID,
)

internal class RevokeOperation(
    val identity: DeviceIdentity,
    val target: UUID,
    val opId: UUID,
) {
    /** A rotated token for the same owner/session keeps the operation; anything else is stale. */
    fun retainedFor(state: AuthState) {
        if (DeviceSessions.identity(state) != identity) throw AuthFailure("STALE_CREDENTIALS")
    }

    /** Only the refresh performed by this operation may advance its generation fence. */
    fun acceptRotation(before: AuthState, after: AuthState) {
        retainedFor(before)
        val next = DeviceSessions.identity(after)
        if (next != identity) throw AuthFailure("STALE_CREDENTIALS")
    }

    override fun toString() = "RevokeOperation(redacted)"
}

/**
 * Accumulated device pages. The list only grows while a page advances without repeating a cursor or
 * overlapping an already-displayed session, so a looping or duplicated server page is rejected
 * instead of being merged into a wrong combined list.
 */
internal data class DeviceSessionsState(
    val items: List<DeviceSession> = emptyList(),
    val nextCursor: String? = null,
    val hasMore: Boolean = false,
    val seen: List<String> = emptyList(),
) {
    fun append(page: DevicePage): DeviceSessionsState {
        if (!hasMore || nextCursor == null) throw invalid()
        if (page.items.size > DeviceSessions.MAX_PAGE_ITEMS) throw invalid()
        val incoming = page.items.map { it.sessionId }
        if (incoming.toSet().size != incoming.size) throw invalid()
        val known = items.mapTo(HashSet()) { it.sessionId }
        if (incoming.any { it in known }) throw invalid()
        if ((page.nextCursor != null) != page.hasMore) throw invalid()
        if (page.nextCursor != null && page.nextCursor in seen) throw invalid()
        return DeviceSessionsState(
            items + page.items,
            page.nextCursor,
            page.hasMore,
            seen + listOfNotNull(page.nextCursor),
        )
    }

    /** Drops one confirmed revocation locally and invalidates the cursor instead of reusing it. */
    fun without(sessionId: UUID): DeviceSessionsState =
        DeviceSessionsState(items.filterNot { it.sessionId == sessionId })

    private fun invalid() = AuthFailure("INVALID_RESPONSE")
}

/** Pure (Android-free) device identity, pagination and revoke-retry policy. */
internal object DeviceSessions {
    const val MAX_PAGE_ITEMS = 50

    // 50 rows of a 100-scalar device name plus cursor and envelope stays well inside this bound,
    // while the credentials and challenge default of AuthJson.MAX_BYTES stays unchanged.
    const val MAX_RESPONSE_BYTES = 64 * 1024

    fun identity(state: AuthState): DeviceIdentity {
        val session = state.session ?: throw AuthFailure("SESSION_EXPIRED")
        return DeviceIdentity(session.user.id, session.sessionId)
    }

    fun requireSameIdentity(expected: DeviceIdentity, state: AuthState) {
        if (identity(state) != expected) throw AuthFailure("STALE_CREDENTIALS")
    }

    fun fresh(page: DevicePage): DeviceSessionsState {
        if (page.items.size > MAX_PAGE_ITEMS || (page.nextCursor != null) != page.hasMore) throw AuthFailure("INVALID_RESPONSE")
        val ids = page.items.map { it.sessionId }
        if (ids.toSet().size != ids.size) throw AuthFailure("INVALID_RESPONSE")
        return DeviceSessionsState(page.items, page.nextCursor, page.hasMore, listOfNotNull(page.nextCursor))
    }

    /**
     * Builds the operation for a new target. A retry must keep the returned value; re-creating it for
     * the same target is a different operation, and reusing it for another target is never allowed.
     */
    fun operation(
        state: AuthState,
        target: UUID,
        opId: UUID = UUID.randomUUID(),
    ): RevokeOperation {
        val current = state.session ?: throw AuthFailure("SESSION_EXPIRED")
        if (target == current.sessionId) throw AuthFailure("INVALID_INPUT")
        return RevokeOperation(identity(state), target, opId)
    }

    /** The current session keeps the existing logout flow; only other sessions are revocable here. */
    fun revokeTarget(row: DeviceSession): UUID? = if (row.current) null else row.sessionId
}
