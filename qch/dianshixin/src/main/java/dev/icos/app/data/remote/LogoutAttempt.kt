package dev.icos.app.data.remote

import java.util.UUID

/** Private to one logout retry closure: rotating these credentials never signs the device back in. */
internal class LogoutAttempt(
    snapshot: AuthState,
    val opId: UUID,
) {
    private var credentials = snapshot

    @Synchronized
    fun run(
        nowMillis: Long,
        refresh: (AuthState, PendingRefresh) -> AuthSession,
        revoke: (AuthState, UUID) -> Unit,
    ) {
        if (credentials.pending != null || checkNotNull(credentials.session).expiresAtMillis <= nowMillis + 30_000) rotate(refresh)
        try {
            revoke(credentials, opId)
        } catch (failure: AuthFailure) {
            if (failure.code != "SESSION_EXPIRED") throw failure
            rotate(refresh)
            revoke(credentials, opId)
        }
    }

    private fun rotate(refresh: (AuthState, PendingRefresh) -> AuthSession) {
        credentials = credentials.beginRefresh()
        val current = checkNotNull(credentials.session)
        val next = refresh(credentials, checkNotNull(credentials.pending))
        if (next.user.id != current.user.id || next.sessionId != current.sessionId) throw AuthFailure("INVALID_RESPONSE", retryable = true)
        credentials = credentials.replace(next)
    }

    override fun toString() = "LogoutAttempt(redacted)"
}
