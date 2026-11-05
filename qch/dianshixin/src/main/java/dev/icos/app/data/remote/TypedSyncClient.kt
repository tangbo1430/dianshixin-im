package dev.icos.app.data.remote

import dev.icos.app.data.local.DatasetDatabase
import dev.icos.app.data.local.TypedOutboxEntity
import dev.icos.app.data.local.TypedSyncStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal class TypedSyncClient(
    private val auth: AuthClient,
    private val database: DatasetDatabase,
) {
    private val outbox = TypedSyncStore(database)
    var categoryCreationsOnly: Boolean = true
        private set

    private fun noteAppliedCommands(commands: List<TypedOutboxEntity>) {
        if (!categoryCreationsOnly) return
        categoryCreationsOnly = commands.all { command ->
            if (command.aggregateType != "category" || command.commandType != "metadata.create") return@all false
            val stored = database.typedOutbox().byOpId(command.opId) ?: return@all false
            val result = stored.serverResultJson?.let(::JSONObject) ?: return@all false
            if (result.optBoolean("snapshotRequired") || stored.state != "APPLIED" && stored.state != "ALREADY_APPLIED") return@all false
            val changes = result.optJSONArray("changes") ?: return@all false
            changes.length() > 0 && (0 until changes.length()).all { index ->
                val change = changes.getJSONObject(index)
                change.optString("entityType") == "category" && change.optString("entityId") == command.aggregateId &&
                    change.optString("operation") == "UPSERT"
            }
        }
    }

    /** Sends persisted typed commands; a transport ambiguity leaves the same opIds retryable. */
    fun pushPending(
        deviceId: UUID,
        limit: Int = 50,
        expectedIdentity: DeviceIdentity? = null,
        requestGate: Any? = null,
        requestFence: (() -> Unit)? = null,
    ): Int {
        val identity = database.identity().read() ?: error("Dataset identity missing")
        val owner = UUID.fromString(identity.ownerId)
        val epoch = UUID.fromString(identity.epochId)
        val authState = auth.snapshot()
        val session = authState.session ?: throw AuthFailure("SESSION_EXPIRED")
        if (session.user.id != owner || authState.deviceId != deviceId) {
            throw AuthFailure("DATASET_IDENTITY_MISMATCH")
        }
        val expectedIdentity = expectedIdentity ?: DeviceSessions.identity(authState)
        DeviceSessions.requireSameIdentity(expectedIdentity, authState)
        val recovered = synchronized(requestGate ?: this) {
            requestFence?.invoke()
            DeviceSessions.requireSameIdentity(expectedIdentity, auth.snapshot())
            val acknowledged = database.typedOutbox().acknowledgedPendingCanonical(identity.ownerId, identity.epochId)
            dev.icos.app.data.local.SnapshotImporter.applyAcknowledgedReceipts(database).also {
                if (it > 0) noteAppliedCommands(acknowledged)
            }
        }
        val pending = outbox.pending(owner, epoch, deviceId, limit)
        if (pending.isEmpty()) return recovered

        return recovered + pushCommands(deviceId, pending, expectedIdentity, requestGate, requestFence)
    }

    /** Replays one frozen operation and commits its complete canonical ACK locally. */
    fun pushCommand(
        deviceId: UUID,
        command: TypedOutboxEntity,
        expectedIdentity: DeviceIdentity? = null,
        requestGate: Any? = null,
        requestFence: (() -> Unit)? = null,
    ): JSONObject {
        if (command.deviceId != deviceId.toString()) throw AuthFailure("DATASET_IDENTITY_MISMATCH")
        val identity = database.identity().read() ?: throw AuthFailure("DATASET_IDENTITY_MISMATCH")
        if (command.ownerId != identity.ownerId || command.epochId != identity.epochId) throw AuthFailure("DATASET_IDENTITY_MISMATCH")
        val pinnedIdentity = expectedIdentity ?: DeviceSessions.identity(auth.snapshot())
        pushCommands(deviceId, listOf(command), pinnedIdentity, requestGate, requestFence)
        return outbox.byOpId(command.opId)?.serverResultJson?.let(::JSONObject)
            ?: throw AuthFailure("INVALID_RESPONSE", retryable = true)
    }

    /** Replays the exact persisted operations with their original opIds and payloads. */
    fun pushCommands(
        deviceId: UUID,
        commands: List<TypedOutboxEntity>,
        expectedIdentity: DeviceIdentity? = null,
        requestGate: Any? = null,
        requestFence: (() -> Unit)? = null,
    ): Int {
        if (commands.isEmpty()) return 0
        val identity = database.identity().read() ?: error("Dataset identity missing")
        val owner = UUID.fromString(identity.ownerId)
        val epoch = UUID.fromString(identity.epochId)
        val authState = auth.snapshot()
        val session = authState.session ?: throw AuthFailure("SESSION_EXPIRED")
        if (session.user.id != owner || authState.deviceId != deviceId ||
            commands.any { it.deviceId != deviceId.toString() || it.ownerId != owner.toString() || it.epochId != epoch.toString() }
        ) {
            throw AuthFailure("DATASET_IDENTITY_MISMATCH")
        }
        val pinnedIdentity = expectedIdentity ?: DeviceSessions.identity(authState)
        DeviceSessions.requireSameIdentity(pinnedIdentity, authState)
        val remaining = commands.toMutableList()
        var completed = 0
        while (remaining.isNotEmpty()) {
            val ready = synchronized(requestGate ?: this) {
                requestFence?.invoke()
                DeviceSessions.requireSameIdentity(pinnedIdentity, auth.snapshot())
                remaining.mapNotNull { outbox.freezeForSend(it) }
            }
            if (ready.isEmpty()) break
            val groups = JSONArray()
            ready.forEach { groups.put(groupJson(it)) }
            val response = try {
                auth.withDatasetClearAdmission(pinnedIdentity, requestGate ?: Any(), requestFence ?: {}, allowsClear = false) {
                    auth.pushTypedGroups(epoch, groups, pinnedIdentity, requestGate, requestFence)
                }.also { DeviceSessions.requireSameIdentity(pinnedIdentity, auth.snapshot()) }
            } catch (failure: AuthFailure) {
                if (failure.retryable || failure.code == "NETWORK") ready.filter { it.state in setOf("QUEUED", "SENDING", "AMBIGUOUS") }.forEach(outbox::markAmbiguous)
                throw failure
            }
            completed += synchronized(requestGate ?: this) {
                requestFence?.invoke()
                DeviceSessions.requireSameIdentity(pinnedIdentity, auth.snapshot())
                dev.icos.app.data.local.SnapshotImporter.applyUploadResults(database, ready, response).also {
                    noteAppliedCommands(ready)
                }
            }
            val sent = ready.mapTo(HashSet()) { it.opId }
            remaining.removeAll { it.opId in sent }
        }
        return completed
    }

    /** Pull is exposed as validated raw canonical groups; callers must commit rows and cursor atomically. */
    fun pullPage(
        cursor: String?,
        limit: Int = 200,
        expectedIdentity: DeviceIdentity? = null,
        requestGate: Any? = null,
        requestFence: (() -> Unit)? = null,
        allowDatasetClear: Boolean = false,
    ): PullPage {
        val identity = database.identity().read() ?: error("Dataset identity missing")
        val owner = UUID.fromString(identity.ownerId)
        val expectedEpoch = UUID.fromString(identity.epochId)
        val session = auth.snapshot().session ?: throw AuthFailure("SESSION_EXPIRED")
        if (session.user.id != owner) throw AuthFailure("DATASET_IDENTITY_MISMATCH")
        val pinnedIdentity = expectedIdentity ?: DeviceSessions.identity(auth.snapshot())
        DeviceSessions.requireSameIdentity(pinnedIdentity, auth.snapshot())
        val response =
            auth.withDatasetClearAdmission(pinnedIdentity, requestGate ?: Any(), requestFence ?: {}, allowsClear = allowDatasetClear) {
                auth.pullTypedChanges(
                    cursor,
                    limit,
                    expectedIdentity = pinnedIdentity,
                    requestGate = requestGate,
                    requestFence = requestFence,
                    allowDatasetClear = allowDatasetClear,
                )
            }
        val epoch = UUID.fromString(response.getString("epoch"))
        if (epoch != expectedEpoch) throw AuthFailure("DATASET_EPOCH_CHANGED")
        val changes = response.getJSONArray("changes")
        if ((0 until changes.length()).any { index ->
                changes.optJSONObject(index)?.optString("operation") == "SNAPSHOT_REQUIRED"
            }
        ) {
            throw AuthFailure("SNAPSHOT_REQUIRED")
        }
        PullGroupValidator.validate(changes, identity.lastAppliedSeq)
        val nextCursor =
            response.getString("nextCursor").takeIf(String::isNotBlank)
                ?: throw AuthFailure("INVALID_RESPONSE", retryable = true)
        val headSeq = response.getLong("headSeq")
        if (headSeq < identity.lastAppliedSeq || changes.length() > 0 &&
            changes.getJSONObject(changes.length() - 1).getLong("seq") > headSeq
        ) {
            throw AuthFailure("INVALID_RESPONSE")
        }
        return PullPage(epoch, changes, nextCursor, response.optBoolean("hasMore"), headSeq)
    }

    /** Obtains and validates a full DATA3 snapshot; activation is intentionally a separate DB transaction. */
    fun prepareBootstrap(
        cacheDirectory: File,
        cancelled: () -> Boolean = { false },
        progress: (String) -> Unit = {},
        restoreBootstrapWire: RestoreBootstrapWire? = null,
        expectedIdentity: DeviceIdentity? = null,
        requestGate: Any? = null,
        requestFence: (() -> Unit)? = null,
        allowDatasetClear: Boolean = false,
    ): SnapshotProtocol.VerifiedSnapshot {
        val identity = database.identity().read() ?: error("Dataset identity missing")
        val owner = UUID.fromString(identity.ownerId)
        val authState = auth.snapshot()
        val session = authState.session ?: throw AuthFailure("SESSION_EXPIRED")
        if (session.user.id != owner) throw AuthFailure("DATASET_IDENTITY_MISMATCH")
        val bootstrapEpoch =
            try {
                auth.bootstrapIdentity(owner, restoreBootstrapWire, expectedIdentity, requestGate, requestFence, allowDatasetClear).first
            } catch (failure: AuthFailure) {
                if (failure.code != "BOOTSTRAP_REQUIRED") throw failure
                UUID.fromString(failure.fields["epoch"] ?: throw AuthFailure("INVALID_RESPONSE"))
            }
        if (restoreBootstrapWire != null &&
            bootstrapEpoch != restoreBootstrapWire.committedEpoch
        ) {
            throw AuthFailure("DATASET_EPOCH_CHANGED")
        }
        progress("正在请求服务器完整快照")
        val bootstrapRequest =
            if (restoreBootstrapWire != null) {
                val currentWire =
                    if (restoreBootstrapWire.bootstrapJobId !=
                        null
                    ) {
                        auth.restoreBootstrapWire(restoreBootstrapWire.bootstrapJobId)
                    } else {
                        auth.restoreBootstrapWire()
                    }
                val jobId =
                    currentWire.bootstrapJobId ?: UUID.fromString(
                        auth.createBootstrapSnapshot(bootstrapEpoch, currentWire).getString("jobId"),
                    )
                jobId to auth.restoreBootstrapWire(jobId)
            } else {
                val requestId = UUID.randomUUID()
                val job =
                    UUID.fromString(
                        auth
                            .createBootstrapSnapshot(
                                bootstrapEpoch,
                                requestId,
                                expectedIdentity ?: DeviceSessions.identity(auth.snapshot()),
                                requestGate ?: Any(),
                                requestFence ?: {},
                                allowDatasetClear,
                            ).getString("jobId"),
                    )
                job to null
            }
        val jobId = bootstrapRequest.first
        val snapshotWire = bootstrapRequest.second
        var ready: SnapshotReady? = null
        while (ready == null) {
            if (cancelled() || Thread.currentThread().isInterrupted) throw AuthFailure("SNAPSHOT_CANCELLED")
            val status =
                if (snapshotWire != null) {
                    auth.bootstrapSnapshotStatus(jobId, snapshotWire)
                } else {
                    auth.bootstrapSnapshotStatus(
                        jobId,
                        expectedIdentity ?: DeviceSessions.identity(auth.snapshot()),
                        requestGate ?: Any(),
                        requestFence ?: {},
                        allowDatasetClear,
                    )
                }
            ready = SnapshotProtocol.requireReady(status, owner, bootstrapEpoch)
            if (ready == null) {
                progress("服务器正在生成完整快照：${status.optString("state")}")
                Thread.sleep(750)
            }
        }
        if (ready.plainBytes > SnapshotProtocol.MAX_MATERIALIZED_SNAPSHOT_BYTES) {
            throw AuthFailure(
                "SNAPSHOT_TOO_LARGE",
                fields = mapOf("maximumBytes" to SnapshotProtocol.MAX_MATERIALIZED_SNAPSHOT_BYTES.toString()),
            )
        }
        if (ready.jobId != jobId || ready.epoch != bootstrapEpoch) throw AuthFailure("DATASET_EPOCH_CHANGED")
        progress("正在下载并核验服务器快照")
        check(cacheDirectory.isDirectory || cacheDirectory.mkdirs())
        val file = File.createTempFile("sync-bootstrap-", ".json", cacheDirectory)
        var keepVerifiedFile = false
        try {
            val downloaded =
                auth.downloadBootstrapSnapshot(
                    jobId,
                    ready.cursor,
                    file,
                    cancelled,
                    snapshotWire,
                    expectedIdentity = expectedIdentity,
                    requestGate = requestGate,
                    requestFence = requestFence,
                    allowDatasetClear = allowDatasetClear,
                )
            val verified = SnapshotProtocol.verify(file, downloaded, owner, bootstrapEpoch, ready.capturedSeq)
            if (verified.capturedSeq != ready.capturedSeq) throw AuthFailure("INVALID_RESPONSE")
            if (restoreBootstrapWire != null &&
                (verified.ownerId != owner || verified.epoch != restoreBootstrapWire.committedEpoch)
            ) {
                throw AuthFailure("DATASET_EPOCH_CHANGED")
            }
            // The server can retire an epoch while the immutable snapshot is being downloaded.
            val currentHead =
                if (snapshotWire != null) {
                    auth.bootstrapIdentity(owner, snapshotWire).second
                } else {
                    auth
                        .bootstrapIdentity(
                            owner,
                            expectedIdentity = expectedIdentity,
                            requestGate = requestGate,
                            requestFence = requestFence,
                            allowDatasetClear = allowDatasetClear,
                        ).second
                }
            if (currentHead < verified.capturedSeq) throw AuthFailure("DATASET_EPOCH_CHANGED")
            keepVerifiedFile = true
            return verified
        } finally {
            if (!keepVerifiedFile) file.delete()
        }
    }

    private fun groupJson(command: TypedOutboxEntity): JSONObject {
        val versions = JSONObject(command.baseVersionsJson)
        check(versions.keys().asSequence().all { versions.getLong(it) >= 0 }) { "Unresolved local dependency cannot be uploaded" }
        requireSafeRecordAttachmentUpdate(command)
        return JSONObject()
            .put("opId", command.opId)
            .put("groupId", command.groupId)
            .put("aggregateType", command.aggregateType)
            .put("aggregateId", command.aggregateId)
            .put("baseVersions", JSONObject(command.baseVersionsJson))
            .put("commandType", command.commandType)
            .put("schemaVersion", command.schemaVersion)
            .put("payload", JSONObject(command.payloadJson))
    }

    internal data class PullPage(
        val epoch: UUID,
        val changes: JSONArray,
        val nextCursor: String,
        val hasMore: Boolean,
        val headSeq: Long,
    )
}

internal fun requireSafeRecordAttachmentUpdate(command: TypedOutboxEntity) {
    if (command.commandType != "record.update") return
    val attachments = JSONObject(command.payloadJson).getJSONObject("changes").optJSONArray("attachmentIds")
    // shortcut: retain legacy commands for review; safe replacement requires ruling out an already-applied operation.
    check(attachments == null || attachments.length() != 0) {
        "旧版排队修改会清空附件，已暂停发送；原修改仍保留在本机。"
    }
}

internal object PullGroupValidator {
    fun validate(
        changes: JSONArray,
        afterSeq: Long? = null,
    ) {
        val decoded =
            List(changes.length()) { index ->
                val row = changes.optJSONObject(index) ?: throw AuthFailure("INVALID_RESPONSE")
                if (row.opt("payload") !is JSONObject) throw AuthFailure("INVALID_RESPONSE")
                PullCanonicalChange(
                    seq = row.optLong("seq", -1),
                    groupId = row.optString("groupId"),
                    ordinal = row.optInt("ordinal", -1),
                    entityType = row.optString("entityType"),
                    entityId = row.optString("entityId"),
                    revision = row.optLong("revision", -1),
                    operation = row.optString("operation"),
                )
            }
        validate(decoded, afterSeq)
    }

    fun validate(
        changes: List<PullCanonicalChange>,
        afterSeq: Long? = null,
    ) {
        var currentSeq = afterSeq ?: -1L
        var currentGroup: String? = null
        var expectedOrdinal = 0
        val completedGroups = HashSet<String>()
        var first = true
        for (row in changes) {
            val seq = row.seq
            val ordinal = row.ordinal
            val groupId = row.groupId
            if (seq <= 0 || ordinal < 0 || groupId.isBlank()) throw AuthFailure("INVALID_RESPONSE")
            if (seq != currentSeq) {
                if (seq <= currentSeq || ordinal != 0 || !completedGroups.add(groupId)) {
                    throw AuthFailure("INVALID_RESPONSE")
                }
                if (afterSeq != null &&
                    (first && seq != afterSeq + 1 || !first && seq != currentSeq + 1)
                ) {
                    throw AuthFailure("INVALID_RESPONSE")
                }
                currentSeq = seq
                currentGroup = groupId
                expectedOrdinal = 0
                first = false
            }
            if (groupId != currentGroup || ordinal != expectedOrdinal) throw AuthFailure("INVALID_RESPONSE")
            if (!row.entityType.matches(Regex("[a-z][a-z0-9_]{0,63}")) ||
                row.operation !in setOf("UPSERT", "DELETE") ||
                runCatching { UUID.fromString(row.entityId) }.isFailure ||
                row.revision < 0
            ) {
                throw AuthFailure("INVALID_RESPONSE")
            }
            expectedOrdinal++
        }
    }
}

internal data class PullCanonicalChange(
    val seq: Long,
    val groupId: String,
    val ordinal: Int,
    val entityType: String,
    val entityId: String,
    val revision: Long,
    val operation: String,
)
