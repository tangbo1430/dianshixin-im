package dev.icos.app.ui

import java.util.UUID

internal enum class SyncErrorCode { NETWORK, SESSION, EPOCH_CHANGED, CONFLICT, INVALID_RESPONSE, STORAGE, PAUSED, UNKNOWN }
internal data class SyncError(val code: SyncErrorCode, val message: String)

internal object SyncControlPolicy {
    fun preferenceName(ownerId: String): String = "account-sync-controls-${UUID.fromString(ownerId)}"
    fun shouldSync(ownerPresent: Boolean, paused: Boolean): Boolean = ownerPresent && !paused

    /** Coalesce a burst after two idle seconds without starving a continuously edited queue. */
    fun automaticSyncDelay(firstRequestAt: Long, now: Long): Long =
        minOf(2_000L, (10_000L - (now - firstRequestAt)).coerceAtLeast(0L))

    fun automaticSyncRetryDelay(failures: Int): Long =
        minOf(30_000L, 5_000L shl failures.coerceIn(0, 3))

    fun localMutationReady(
        activeEpoch: UUID?,
        identityOwnerId: String?,
        identityEpochId: String?,
        initialized: Boolean,
        ownerId: UUID,
    ): Boolean {
        if (activeEpoch == null || identityOwnerId != ownerId.toString() || identityEpochId != activeEpoch.toString()) return false
        return initialized
    }
    fun errorCode(code: String?, storageUnavailable: Boolean = false): SyncErrorCode = when (code) {
        "NETWORK", "AUTH_UNAVAILABLE", "STORAGE_UNAVAILABLE" -> SyncErrorCode.NETWORK
        "SESSION_EXPIRED", "SESSION_REVOKED", "ACCOUNT_DISABLED" -> SyncErrorCode.SESSION
        "DATASET_EPOCH_CHANGED", "CURSOR_EXPIRED", "SNAPSHOT_REQUIRED" -> SyncErrorCode.EPOCH_CHANGED
        "CONFLICT", "CONFLICTING_OPERATION" -> SyncErrorCode.CONFLICT
        "INVALID_RESPONSE" -> SyncErrorCode.INVALID_RESPONSE
        "SNAPSHOT_CANCELLED" -> SyncErrorCode.PAUSED
        else -> if (storageUnavailable) SyncErrorCode.STORAGE else SyncErrorCode.UNKNOWN
    }
    fun mutationCounts(states: List<String>): Pair<Int, Int> =
        states.count { it in setOf("QUEUED", "AMBIGUOUS", "ACKED") } to
            states.count { it in setOf("CONFLICT", "REJECTED", "BLOCKED_DEPENDENCY") }

    fun requiresMetadataReconfirmation(aggregate: String, command: String): Boolean =
        aggregate == "budget" && command == "budget.setEnabled" ||
            aggregate in setOf("ledger", "category", "tag", "tag_group", "account", "account_group") &&
            (command.startsWith("metadata.") || command in setOf("ledger.copy", "category.move"))

    fun previousEpochArchived(active: UUID?, authoritative: UUID): Boolean = active != null && active != authoritative
}
