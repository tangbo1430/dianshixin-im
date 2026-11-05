package dev.icos.app.data.remote

import org.json.JSONObject
import java.util.UUID

internal data class AvatarUploadGrant(
    val attachmentId: UUID,
    val path: String,
    val authorization: String,
) {
    override fun toString() = "AvatarUploadGrant(redacted)"
}

internal data class AvatarStatus(
    val attachmentId: UUID,
    val state: String,
    val purpose: String,
    val mime: String?,
    val bytes: Long?,
    val sha256: String?,
)

internal data class AuthUser(
    val id: UUID,
    val nickname: String,
    val avatarId: UUID?,
    val verifiedEmail: String,
)

// Credentials deliberately have no generated toString/copy/component methods.
internal class AuthSession(
    val user: AuthUser,
    val sessionId: UUID,
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: String,
    val expiresAtMillis: Long,
) {
    override fun toString() = "AuthSession(redacted)"
}

internal data class AuthChallenge(
    val id: UUID,
    val expiresAtMillis: Long,
    val retryAfter: Int,
)

/**
 * One server device session. [deviceName] is validated but untrusted plain text and must be rendered
 * as text only; [current] marks this authenticated session, never a physical device identifier.
 */
internal data class DeviceSession(
    val sessionId: UUID,
    val deviceName: String,
    val lastSeenAt: String,
    val lastSeenAtMillis: Long,
    val current: Boolean,
)

/** One GET /v1/me/devices page. [nextCursor] is opaque and present only when [hasMore] is true. */
internal data class DevicePage(
    val items: List<DeviceSession>,
    val nextCursor: String?,
    val hasMore: Boolean,
)

internal data class SyncConflict(
    val conflictId: UUID,
    val opId: UUID,
    val revision: Long,
    val state: String,
    val localCommand: JSONObject,
    val serverVersions: JSONObject,
    val createdAt: String,
    val headSeq: Long,
    val reviewedServerVersions: JSONObject,
) {
    override fun toString() = "SyncConflict(conflictId=$conflictId, opId=$opId, revision=$revision, state=$state)"
}

internal data class SyncConflictPage(
    val items: List<SyncConflict>,
    val nextCursor: String?,
    val hasMore: Boolean,
)

internal data class SyncConflictResolution(
    val conflictId: UUID,
    val state: String,
    val resolutionOpId: UUID,
    val discardedOpId: UUID,
    val commandResult: JSONObject?,
    val headSeq: Long,
)

internal data class AccountDeletionReceipt(
    val jobId: UUID,
    val state: String,
    val progress: Int,
    val errorCode: String?,
    val createdAtMillis: Long,
    val finishedAtMillis: Long?,
) {
    override fun toString() = "AccountDeletionReceipt(redacted)"
}

internal data class AccountDeletionSubmission(
    val receipt: AccountDeletionReceipt,
    val sessionRetired: Boolean,
)

internal class PendingRefresh(
    val opId: UUID,
    val originalRefresh: String,
) {
    override fun toString() = "PendingRefresh(redacted)"
}

internal class PendingReset(
    val opId: UUID,
    val challengeId: UUID,
    val email: String,
) {
    override fun toString() = "PendingReset(redacted)"
}

/** Encrypted durable wire barrier for one ambiguous restore submission or accepted job. */
internal class PendingRestore(
    val owner: UUID,
    val sessionId: UUID,
    val backupId: UUID,
    val epoch: UUID,
    val headSeq: Long,
    val opId: UUID,
    val actionToken: String,
    val requestBody: String,
    val jobId: UUID? = null,
    val committedEpoch: UUID? = null,
    val bootstrapOpId: UUID? = null,
    val bootstrapJobId: UUID? = null,
) {
    fun copyWithJob(value: UUID) = PendingRestore(owner, sessionId, backupId, epoch, headSeq, opId, actionToken, requestBody, value, committedEpoch, bootstrapOpId, bootstrapJobId)
    fun copyWithCommittedEpoch(value: UUID, bootstrapOperation: UUID) = PendingRestore(owner, sessionId, backupId, epoch, headSeq, opId, actionToken, requestBody, jobId, value, bootstrapOperation, bootstrapJobId)
    fun copyWithBootstrapWire(opId: UUID, jobId: UUID?) = PendingRestore(
        owner, sessionId, backupId, epoch, headSeq, this.opId, actionToken, requestBody, this.jobId, committedEpoch, opId, jobId,
    )
    fun copyWithBootstrapJob(value: UUID) = PendingRestore(owner, sessionId, backupId, epoch, headSeq, opId, actionToken, requestBody, jobId, committedEpoch, bootstrapOpId, value)
    override fun toString() = "PendingRestore(redacted)"
}

internal data class RestoreBootstrapWire(
    val owner: UUID,
    val sessionId: UUID,
    val restoreJobId: UUID,
    val committedEpoch: UUID,
    val bootstrapOpId: UUID,
    val bootstrapJobId: UUID? = null,
)

internal class PendingDatasetClear(
    val ownerId: UUID,
    val epochId: UUID,
    val sessionId: UUID,
    val opId: UUID,
    val groupId: UUID,
    val ledgerIdsJson: String,
    val expectedHeadSeq: Long,
    val phase: String,
    val password: String?,
    val actionToken: String?,
    val frozenGroupJson: String?,
    val ackSeq: Long?,
    val requiredCapturedSeq: Long?,
) {
    override fun toString() = "PendingDatasetClear(redacted)"
}

internal class AuthState(
    val deviceId: UUID,
    val generation: UUID = UUID.randomUUID(),
    val session: AuthSession? = null,
    val pending: PendingRefresh? = null,
    val reset: PendingReset? = null,
    val restore: PendingRestore? = null,
    val archivedRestores: List<PendingRestore> = emptyList(),
    val datasetClear: PendingDatasetClear? = null,
) {
    fun beginRefresh(): AuthState {
        val current = checkNotNull(session)
        return if (pending !=
            null
        ) {
            this
        } else {
            return AuthState(deviceId, generation, current, PendingRefresh(UUID.randomUUID(), current.refreshToken), reset, restore, archivedRestores, datasetClear)
        }
    }

    fun matches(snapshot: AuthState): Boolean = deviceId == snapshot.deviceId && generation == snapshot.generation

    fun requireSignIn() {
        if (session != null) throw AuthFailure("STALE_CREDENTIALS")
        if (reset != null) throw AuthFailure("RESET_UNCONFIRMED")
        if (datasetClear != null) throw AuthFailure("CLEAR_REAUTH_UNCONFIRMED")
    }

    fun restoreBarrierFor(owner: UUID): PendingRestore? =
        restore?.takeIf { it.owner == owner } ?: archivedRestores.lastOrNull { it.owner == owner }

    fun hasRestoreBarrierForCurrentSession(): Boolean {
        val current = session ?: return false
        return restore?.let { it.owner == current.user.id && it.sessionId == current.sessionId } == true ||
            archivedRestores.any { it.owner == current.user.id && it.sessionId == current.sessionId }
    }

    fun canResumeRestore(operation: PendingRestore): Boolean =
        restore === operation && session?.user?.id == operation.owner && session.sessionId == operation.sessionId

    fun replace(value: AuthSession?): AuthState {
        if (reset != null) throw AuthFailure("RESET_UNCONFIRMED")
        val current = session
        val clear = datasetClear
        if (clear != null && (value?.user?.id != clear.ownerId || value.sessionId != clear.sessionId)) {
            throw AuthFailure("CLEAR_REAUTH_UNCONFIRMED")
        }
        val sameIdentity = current != null && value != null &&
            current.user.id == value.user.id && current.sessionId == value.sessionId
        val archived = if (!sameIdentity && restore != null) {
            if (archivedRestores.size >= 4) throw AuthFailure("RESTORE_UNCONFIRMED")
            archivedRestores + restore
        } else archivedRestores
        val resumed = if (!sameIdentity && value != null) archived.firstOrNull { it.owner == value.user.id && it.sessionId == value.sessionId } else null
        if (archived.any { it.owner == value?.user?.id } && resumed == null) {
            throw AuthFailure("RESTORE_UNCONFIRMED")
        }
        val archivedNext = if (resumed == null) archived else archived - resumed
        if (archivedNext.size > 4) throw AuthFailure("RESTORE_UNCONFIRMED")
        return AuthState(
            deviceId,
            session = value,
            restore = if (sameIdentity) restore else resumed,
            archivedRestores = archivedNext,
            datasetClear = clear,
        )
    }

    fun beginRestore(operation: PendingRestore): AuthState {
        if (datasetClear != null || restore != null || archivedRestores.any { it.owner == operation.owner && it.sessionId == operation.sessionId }) {
            throw AuthFailure(if (datasetClear != null) "CLEAR_REAUTH_UNCONFIRMED" else "RESTORE_UNCONFIRMED")
        }
        if (session?.user?.id != operation.owner || session.sessionId != operation.sessionId) throw AuthFailure("STALE_CREDENTIALS")
        return AuthState(deviceId, generation, session, pending, reset, operation, archivedRestores, datasetClear)
    }

    fun beginDatasetClear(operation: PendingDatasetClear): AuthState {
        if (session?.user?.id != operation.ownerId || session.sessionId != operation.sessionId || pending != null || reset != null) {
            throw AuthFailure("STALE_CREDENTIALS")
        }
        if (restore != null || archivedRestores.isNotEmpty()) {
            throw AuthFailure("RESTORE_UNCONFIRMED")
        }
        if (datasetClear != null) {
            if (datasetClear.opId != operation.opId || datasetClear.ownerId != operation.ownerId || datasetClear.epochId != operation.epochId ||
                datasetClear.sessionId != operation.sessionId || datasetClear.groupId != operation.groupId ||
                datasetClear.ledgerIdsJson != operation.ledgerIdsJson || datasetClear.expectedHeadSeq != operation.expectedHeadSeq ||
                datasetClear.phase == "ACKED") throw AuthFailure("CLEAR_REAUTH_UNCONFIRMED")
            return this
        }
        return AuthState(deviceId, generation, session, pending, reset, restore, archivedRestores, operation)
    }

    fun updateDatasetClear(snapshot: AuthState, operation: PendingDatasetClear): AuthState {
        val current = datasetClear
        if (!matches(snapshot) || session?.sessionId != operation.sessionId || current?.opId != operation.opId ||
            current.ownerId != operation.ownerId || current.epochId != operation.epochId || current.sessionId != operation.sessionId ||
            current.groupId != operation.groupId || current.ledgerIdsJson != operation.ledgerIdsJson ||
            current.expectedHeadSeq != operation.expectedHeadSeq || current.phase == "ACKED" && operation.phase != "ACKED") {
            throw AuthFailure("STALE_CREDENTIALS")
        }
        val legal = when (current.phase) {
            "REAUTH" -> operation.phase in setOf("REAUTH", "AUTHORIZED")
            "AUTHORIZED" -> operation.phase in setOf("AUTHORIZED", "READY")
            "READY" -> operation.phase in setOf("READY", "ACKED")
            else -> false
        }
        if (!legal || current.actionToken != null && current.actionToken != operation.actionToken ||
            current.frozenGroupJson != null && current.frozenGroupJson != operation.frozenGroupJson ||
            operation.phase == "READY" && (operation.actionToken == null || operation.frozenGroupJson == null) ||
            operation.phase == "ACKED" && (operation.actionToken == null || operation.frozenGroupJson == null ||
                operation.ackSeq == null || operation.ackSeq <= 0 || operation.requiredCapturedSeq == null || operation.requiredCapturedSeq < operation.ackSeq)) {
            throw AuthFailure("STALE_CREDENTIALS")
        }
        return AuthState(deviceId, generation, session, pending, reset, restore, archivedRestores, operation)
    }

    fun authorizeDatasetClear(snapshot: AuthState, operation: PendingDatasetClear): AuthState {
        val current = datasetClear
        if (!matches(snapshot) || session?.sessionId != operation.sessionId || current?.opId != operation.opId ||
            current.ownerId != operation.ownerId || current.epochId != operation.epochId || current.sessionId != operation.sessionId ||
            current.groupId != operation.groupId || current.ledgerIdsJson != operation.ledgerIdsJson ||
            current.expectedHeadSeq != operation.expectedHeadSeq || current.phase !in setOf("REAUTH", "AUTHORIZED") ||
            current.password == null || operation.phase != "AUTHORIZED" || operation.password != current.password ||
            operation.actionToken != null || operation.frozenGroupJson != null || operation.ackSeq != null || operation.requiredCapturedSeq != null) {
            throw AuthFailure("STALE_CREDENTIALS")
        }
        return AuthState(deviceId, generation, session, pending, reset, restore, archivedRestores, operation)
    }

    fun finishDatasetClear(snapshot: AuthState, opId: UUID): AuthState {
        val operation = datasetClear ?: throw AuthFailure("STALE_CREDENTIALS")
        val captured = snapshot.datasetClear
        if (!matches(snapshot) || operation.opId != opId || operation.phase != "ACKED" ||
            captured?.opId != operation.opId || captured.phase != "ACKED" || captured.ackSeq != operation.ackSeq ||
            captured.requiredCapturedSeq != operation.requiredCapturedSeq ||
            session?.user?.id != operation.ownerId || session.sessionId != operation.sessionId) throw AuthFailure("STALE_CREDENTIALS")
        return AuthState(deviceId, generation, session, pending, reset, restore, archivedRestores, null)
    }

    fun updateRestore(operation: PendingRestore): AuthState {
        if (datasetClear != null) throw AuthFailure("CLEAR_REAUTH_UNCONFIRMED")
        val current = restore ?: throw AuthFailure("STALE_CREDENTIALS")
        if (current.owner != operation.owner || current.sessionId != operation.sessionId || current.opId != operation.opId ||
            current.requestBody != operation.requestBody || current.actionToken != operation.actionToken ||
            current.backupId != operation.backupId || current.epoch != operation.epoch || current.headSeq != operation.headSeq ||
            (current.jobId != null && current.jobId != operation.jobId)) throw AuthFailure("STALE_CREDENTIALS")
        return AuthState(deviceId, generation, session, pending, reset, operation, archivedRestores, datasetClear)
    }

    fun bindRestoreBootstrap(operation: PendingRestore, wire: RestoreBootstrapWire): AuthState {
        if (datasetClear != null) throw AuthFailure("CLEAR_REAUTH_UNCONFIRMED")
        val current = restore ?: throw AuthFailure("RESTORE_UNCONFIRMED")
        if (current.opId != operation.opId || current.owner != operation.owner || current.sessionId != operation.sessionId ||
            current.committedEpoch != wire.committedEpoch || current.jobId != wire.restoreJobId ||
            wire.owner != current.owner || wire.sessionId != current.sessionId || wire.bootstrapOpId == UUID(0, 0)) {
            throw AuthFailure("RESTORE_UNCONFIRMED")
        }
        if (current.bootstrapOpId != null && current.bootstrapOpId != wire.bootstrapOpId) throw AuthFailure("STALE_CREDENTIALS")
        if (current.bootstrapJobId != null && current.bootstrapJobId != wire.bootstrapJobId) throw AuthFailure("STALE_CREDENTIALS")
        if (wire.bootstrapJobId == null) return this
        if (current.bootstrapJobId == null && wire.bootstrapJobId != null) {
            if (current.bootstrapOpId != wire.bootstrapOpId) throw AuthFailure("STALE_CREDENTIALS")
            return AuthState(deviceId, generation, session, pending, reset, current.copyWithBootstrapJob(wire.bootstrapJobId), archivedRestores, datasetClear)
        }
        val updated = current.copyWithBootstrapJob(wire.bootstrapJobId ?: throw AuthFailure("RESTORE_UNCONFIRMED"))
        return AuthState(deviceId, generation, session, pending, reset, updated, archivedRestores, datasetClear)
    }

    fun finishRestore(operation: PendingRestore): AuthState {
        if (datasetClear != null) throw AuthFailure("CLEAR_REAUTH_UNCONFIRMED")
        val current = restore ?: throw AuthFailure("STALE_CREDENTIALS")
        if (current.opId != operation.opId || current.sessionId != operation.sessionId || current.owner != operation.owner ||
            current.jobId != operation.jobId || current.committedEpoch != operation.committedEpoch) throw AuthFailure("STALE_CREDENTIALS")
        return AuthState(deviceId, generation, session, pending, reset, null, archivedRestores, datasetClear)
    }

    fun beginReset(operation: PendingReset): AuthState {
        if (datasetClear != null) throw AuthFailure("CLEAR_REAUTH_UNCONFIRMED")
        if (restore != null) throw AuthFailure("RESTORE_UNCONFIRMED")
        if (reset != null) {
            if (reset.email != operation.email) throw AuthFailure("RESET_UNCONFIRMED")
            if (reset.opId == operation.opId) {
                if (reset.challengeId != operation.challengeId) throw AuthFailure("STALE_CREDENTIALS")
                return this
            }
            if (reset.challengeId == operation.challengeId) throw AuthFailure("STALE_CREDENTIALS")
        }
        return AuthState(deviceId, reset = operation, archivedRestores = archivedRestores)
    }

    fun finishReset(snapshot: AuthState): AuthState {
        if (!matches(snapshot) || reset == null || reset.opId != snapshot.reset?.opId) throw AuthFailure("STALE_CREDENTIALS")
        return AuthState(deviceId, generation = UUID.randomUUID(), archivedRestores = archivedRestores)
    }

    fun acceptsRefresh(
        snapshot: AuthState,
        operation: UUID,
    ): Boolean = matches(snapshot) && session != null && pending?.opId == operation && pending.originalRefresh == session.refreshToken

    override fun toString() = "AuthState(redacted)"
}

internal class AuthFailure(
    val code: String,
    val requestId: UUID? = null,
    val retryAfter: Int = 0,
    val retryable: Boolean = false,
    val fields: Map<String, String> = emptyMap(),
) : Exception("Authentication operation unavailable") {
    val userMessage: String get() {
        val text =
            when (code) {
                "UNCONFIGURED" -> "此版本尚未配置认证服务器，仍可使用本机访客账本"
                "LOCAL_STORAGE_UNAVAILABLE" -> "无法读取认证凭据，请保留应用数据并恢复设备密钥"
                "STORAGE_UNAVAILABLE", "AUTH_UNAVAILABLE" -> "服务器认证服务暂不可用，可稍后重试同一请求"
                "RESET_UNCONFIRMED" -> "密码重置尚未确认，请先恢复原重置请求或完成同一邮箱的新重置"
                "INVALID_INPUT" -> "请检查邮箱、12–128个字符的密码和6位数字验证码"
                "AVATAR_INPUT_INVALID" -> "无法读取图片或图片不符合格式与尺寸限制，请重新选择不超过20MB的图片"
                "INVALID_CREDENTIALS" -> "邮箱或密码错误"
                "INVALID_CHALLENGE" -> "验证码无效或已过期，请重新申请"
                "EMAIL_IN_USE" -> "邮箱已用于其他账号"
                "CURRENT_LEGAL_CONSENT_REQUIRED" -> "请先完成当前版本的联网授权，再重试此操作"
                "SESSION_EXPIRED", "SESSION_REVOKED", "IDEMPOTENCY_RECEIPT_EXPIRED" -> "会话已失效，请重新登录"
                "ACCOUNT_DISABLED" -> "账号当前不可用"
                "RATE_LIMITED" -> "请求过于频繁，请等待后重试"
                "NETWORK" -> "网络请求未确认，可重试同一请求"
                "SNAPSHOT_TOO_LARGE" -> "服务器快照超过当前版本支持的64MiB上限，请稍后使用支持更大快照的版本同步"
                "INVALID_RESPONSE" -> "服务器响应未通过校验，可重试同一请求"
                "STALE_CREDENTIALS" -> "账号状态已变化，请重新打开账号面板"
                else -> "认证请求未完成，请稍后重试"
            }
        return text + (requestId?.let { "\n请求编号：$it" } ?: "")
    }
}
