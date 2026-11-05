package dev.icos.app.data.remote

/** Completion runs only after a confirmed response; all failures leave the reset fence intact. */
internal class ResetAttempt(
    private val snapshot: AuthState,
) {
    fun run(
        send: (PendingReset) -> Unit,
        complete: (AuthState) -> Unit,
    ) {
        send(snapshot.reset ?: throw AuthFailure("STALE_CREDENTIALS"))
        complete(snapshot)
    }
}
