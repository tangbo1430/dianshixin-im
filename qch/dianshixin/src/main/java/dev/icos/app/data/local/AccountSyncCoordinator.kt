package dev.icos.app.data.local

import android.content.Context
import android.os.Looper
import dev.icos.app.data.remote.AuthClient
import dev.icos.app.data.remote.AuthFailure
import dev.icos.app.data.remote.DeviceIdentity
import dev.icos.app.data.remote.DeviceSessions
import dev.icos.app.data.remote.TypedSyncClient
import java.util.UUID

internal data class AccountSyncResult(
    val epoch: UUID,
    val bootstrapped: Boolean,
    val appliedPages: Int,
    val appliedChanges: Int,
    val previousEpochArchived: Boolean = false,
    val snapshotCapturedSeq: Long? = null,
)

/**
 * Account-scoped bootstrap and incremental pull for the canonical replica.
 *
 * Every call runs on a caller-owned background executor. The canonical DATA3 dataset is stored as a
 * lossless `server_*` replica inside a per-owner, per-epoch SQLCipher space; bootstrap builds a
 * fresh temporary space, verifies the full snapshot and only then atomically activates it, so a
 * failure always leaves any previously activated space untouched. The guest dataset is never
 * rebound to an account owner and queues/cursors never leave their owning space.
 */
internal object AccountSyncCoordinator {
    private const val PULL_LIMIT = 200
    private const val MAX_PULL_PAGES = 200
    private val RECOVERY_COMMANDS = setOf("claim.mark", "claim.settle", "claim.cancel", "refund.create", "refund.cancel")
    private val RECOVERY_PENDING_INTENT_STATES = setOf("QUEUED", "AMBIGUOUS", "ACKED")
    private val RECOVERY_PENDING_OUTBOX_STATES = setOf("QUEUED", "SENDING", "AMBIGUOUS", "APPLIED", "ALREADY_APPLIED")

    internal fun recoveryMarkersToBackfill(
        ownerId: String,
        epochId: String,
        commands: List<TypedOutboxEntity>,
        intents: List<AccountMutationIntentEntity>,
        predictions: List<AccountPredictionEntity>,
    ): List<AccountPredictionEntity> {
        val commandByOpId = commands.associateBy { it.opId }
        val intentByOpId = intents.associateBy { it.opId }
        val predictionByOpId = predictions.associateBy { it.opId }
        val markers = ArrayList<AccountPredictionEntity>()
        commands.filter { it.commandType in RECOVERY_COMMANDS && it.state in RECOVERY_PENDING_OUTBOX_STATES }.forEach { command ->
            val intent = intentByOpId[command.opId] ?: error("MUTATION_REPLAY_UNCERTAIN")
            check(intent.state in RECOVERY_PENDING_INTENT_STATES ||
                intent.state == "PULLED" && command.state in setOf("APPLIED", "ALREADY_APPLIED")) { "MUTATION_REPLAY_UNCERTAIN" }
            requireFrozenOutboxCommand(command)
            check(command.ownerId == ownerId && command.epochId == epochId &&
                intent.ownerId == ownerId && intent.epochId == epochId && intent.opId == command.opId &&
                intent.aggregateType == command.aggregateType && intent.aggregateId == command.aggregateId &&
                intent.commandType == command.commandType && intent.payloadJson == command.payloadJson) {
                "MUTATION_REPLAY_UNCERTAIN"
            }
        }
        intents.filter { it.commandType in RECOVERY_COMMANDS && it.state in RECOVERY_PENDING_INTENT_STATES }.forEach { intent ->
            val command = commandByOpId[intent.opId] ?: error("MUTATION_REPLAY_UNCERTAIN")
            requireFrozenOutboxCommand(command)
            check(command.state in RECOVERY_PENDING_OUTBOX_STATES && command.ownerId == ownerId && command.epochId == epochId &&
                intent.ownerId == ownerId && intent.epochId == epochId && intent.opId == command.opId &&
                intent.aggregateType == command.aggregateType && intent.aggregateId == command.aggregateId &&
                intent.commandType == command.commandType && intent.payloadJson == command.payloadJson) {
                "MUTATION_REPLAY_UNCERTAIN"
            }
            val existing = predictionByOpId[intent.opId]
            if (existing == null) {
                markers += AccountPredictionEntity(intent.opId, ownerId, epochId, intent.aggregateType, intent.aggregateId, intent.payloadJson, true)
            } else {
                check(existing.active && existing.ownerId == ownerId && existing.epochId == epochId &&
                    existing.aggregateType == intent.aggregateType && existing.aggregateId == intent.aggregateId &&
                    existing.payloadJson == intent.payloadJson) {
                    "MUTATION_REPLAY_UNCERTAIN"
                }
            }
        }
        return markers
    }

    internal fun uncertainMutationCommands(
        commands: List<TypedOutboxEntity>,
        intents: List<AccountMutationIntentEntity>,
        predictions: List<AccountPredictionEntity>,
    ): List<TypedOutboxEntity> {
        val intentByOpId = intents.associateBy { it.opId }
        val activePredictions = predictions.filter { it.active }.associateBy { it.opId }
        val result = ArrayList<TypedOutboxEntity>()
        commands.forEach { command ->
            if (command.commandType == "dataset.clearRecords") return@forEach
            val intent = intentByOpId[command.opId] ?: return@forEach
            val prediction = activePredictions[command.opId] ?: return@forEach
            val deferred = requireFrozenOutboxCommand(command, commands)
            check(prediction.ownerId == command.ownerId && prediction.epochId == command.epochId &&
                prediction.aggregateType == command.aggregateType && prediction.aggregateId == command.aggregateId &&
                prediction.payloadJson == intent.payloadJson && intent.ownerId == command.ownerId && intent.epochId == command.epochId &&
                intent.aggregateType == command.aggregateType && intent.aggregateId == command.aggregateId &&
                intent.commandType == command.commandType && intent.payloadJson == command.payloadJson) {
                "MUTATION_REPLAY_UNCERTAIN"
            }
            if (deferred) {
                check(intent.state == "QUEUED") { "MUTATION_REPLAY_UNCERTAIN" }
                return@forEach
            }
            if (intent.state in setOf("QUEUED", "AMBIGUOUS", "ACKED") &&
                command.state in setOf("QUEUED", "SENDING", "AMBIGUOUS", "APPLIED", "ALREADY_APPLIED")) result += command
        }
        return result
    }

    /** Only never-sent commands may retain local dependency tokens, with their full queue present. */
    private fun requireFrozenOutboxCommand(command: TypedOutboxEntity, localCommands: List<TypedOutboxEntity>? = null): Boolean {
        var deferred = false
        check(runCatching {
            val opId = UUID.fromString(command.opId)
            require(opId != UUID(0, 0))
            require(UUID.fromString(command.groupId) != UUID(0, 0))
            require(UUID.fromString(command.ownerId) != UUID(0, 0))
            require(UUID.fromString(command.epochId) != UUID(0, 0))
            require(UUID.fromString(command.deviceId) != UUID(0, 0))
            require(UUID.fromString(command.aggregateId) != UUID(0, 0))
            require(command.aggregateType.matches(Regex("[a-z][a-z0-9_]{0,63}")))
            require(command.commandType.matches(Regex("[a-z][a-zA-Z0-9_.]{0,127}")) && command.schemaVersion == 1)
            val versions = org.json.JSONObject(command.baseVersionsJson)
            for (key in versions.keys()) {
                require(key.matches(Regex("[a-z][a-z0-9_]*:[0-9a-fA-F-]{36}")) && versions.opt(key) is Number)
                if (versions.getLong(key) < 0) {
                    require(localCommands != null && command.state == "QUEUED")
                    deferred = true
                }
            }
            if (deferred) TypedSyncStore.validateLocalDependencies(command, requireNotNull(localCommands))
            org.json.JSONObject(command.payloadJson)
        }.isSuccess) { "MUTATION_REPLAY_UNCERTAIN" }
        return deferred
    }

    internal fun receiptSequence(resultJson: String): Long {
        val result = runCatching { org.json.JSONObject(resultJson) }.getOrNull()
            ?: throw AuthFailure("MUTATION_REPLAY_UNCERTAIN", retryable = true)
        val status = result.optString("status")
        val seq = result.optLong("seq", -1)
        if (status !in setOf("APPLIED", "ALREADY_APPLIED") || seq <= 0) {
            throw AuthFailure("MUTATION_REPLAY_UNCERTAIN", retryable = true)
        }
        return seq
    }

    internal fun matchingSuccessfulReceipts(
        receiptStatus: String,
        receiptJson: String,
        outboxStatus: String,
        outboxJson: String,
        opId: String,
    ): Boolean {
        val receipt = runCatching { org.json.JSONObject(receiptJson) }.getOrNull()
            ?: throw AuthFailure("MUTATION_REPLAY_UNCERTAIN", retryable = true)
        val outbox = runCatching { org.json.JSONObject(outboxJson) }.getOrNull()
            ?: throw AuthFailure("MUTATION_REPLAY_UNCERTAIN", retryable = true)
        val receiptSeq = receiptSequence(receiptJson)
        val outboxSeq = receiptSequence(outboxJson)
        check(receiptStatus == receipt.optString("status") && outboxStatus == outbox.optString("status") &&
            receipt.optString("opId") == opId && outbox.optString("opId") == opId) {
            "MUTATION_REPLAY_UNCERTAIN"
        }
        return receiptStatus in setOf("APPLIED", "ALREADY_APPLIED") &&
            outboxStatus in setOf("APPLIED", "ALREADY_APPLIED") && receiptSeq == outboxSeq
    }

    internal fun receiptCoveredByWatermark(resultJson: String, lastAppliedSeq: Long): Boolean =
        receiptSequence(resultJson) <= lastAppliedSeq

    fun syncAccount(
        context: Context,
        auth: AuthClient,
        owner: UUID,
        onProgress: (String) -> Unit,
        cancelled: () -> Boolean = { false },
        requestFence: () -> Unit = {},
        requestGate: Any = Any(),
        expectedIdentity: DeviceIdentity? = null,
        allowDatasetClear: Boolean = false,
        forceSnapshot: Boolean = false,
        commitReplica: (() -> Unit) -> Unit = { it() },
    ): AccountSyncResult {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Account sync requires a background thread" }
        val session = auth.snapshot().session ?: throw AuthFailure("SESSION_EXPIRED")
        if (session.user.id != owner) throw AuthFailure("DATASET_IDENTITY_MISMATCH")
        val initialIdentity = DeviceSessions.identity(auth.snapshot())
        if (expectedIdentity != null && expectedIdentity != initialIdentity) throw AuthFailure("STALE_CREDENTIALS")
        val initiallyActiveEpoch = EncryptedDatasetStore.activeAccountEpoch(context, owner)
        var authoritativeEpochForRequests: UUID? = null
        fun beforeRequest() {
            if (cancelled() || Thread.currentThread().isInterrupted) throw AuthFailure("SNAPSHOT_CANCELLED")
            requireSession(auth, initialIdentity)
            if (auth.snapshot().session?.user?.id != owner) throw AuthFailure("DATASET_IDENTITY_MISMATCH")
            val currentEpoch = EncryptedDatasetStore.activeAccountEpoch(context, owner)
            if (currentEpoch != initiallyActiveEpoch && currentEpoch != authoritativeEpochForRequests) throw AuthFailure("DATASET_EPOCH_CHANGED")
            requestFence()
            if (cancelled() || Thread.currentThread().isInterrupted) throw AuthFailure("SNAPSHOT_CANCELLED")
            requireSession(auth, initialIdentity)
        }
        val requestFenceOwner = auth.installSyncRequestFence(requestGate) { beforeRequest() }
        return try {
            val result = auth.withDatasetClearAdmission(initialIdentity, requestGate, { beforeRequest() }, allowsClear = allowDatasetClear) {
                beforeRequest()
                val authoritativeEpoch = auth.bootstrapEpoch(owner, expectedIdentity = initialIdentity, requestGate = requestGate, requestFence = { beforeRequest() }, allowDatasetClear = allowDatasetClear)

                authoritativeEpochForRequests = authoritativeEpoch
                requireSession(auth, initialIdentity)
                if (cancelled()) throw AuthFailure("SNAPSHOT_CANCELLED")
                val activeEpoch = EncryptedDatasetStore.activeAccountEpoch(context, owner)
                if (activeEpoch != null) {
                    beforeRequest()
                    if (activeEpoch != EncryptedDatasetStore.activeAccountEpoch(context, owner)) throw AuthFailure("DATASET_EPOCH_CHANGED")
                }
                val previousEpochArchived = activeEpoch != null && activeEpoch != authoritativeEpoch
                var bootstrapped = activeEpoch != authoritativeEpoch || forceSnapshot
                var snapshotCapturedSeq: Long? = null
                if (previousEpochArchived) {
                    if (cancelled()) throw AuthFailure("SNAPSHOT_CANCELLED")
                    commitReplica { archiveEpoch(context, owner, activeEpoch!!, "DATASET_EPOCH_CHANGED") }
                }
                if (bootstrapped) {
                    if (activeEpoch != null) onProgress("服务器数据集已切换，正在重新同步")
                    snapshotCapturedSeq = bootstrapWithCursorRecovery(context, auth, owner, authoritativeEpoch, initialIdentity, onProgress, cancelled,
                        allowDatasetClear = allowDatasetClear, requestGate = requestGate, requestFence = { beforeRequest() }, commitReplica = commitReplica)
                }
                requireSession(auth, initialIdentity)
                if (cancelled()) throw AuthFailure("SNAPSHOT_CANCELLED")
                val applied = try {
                    pullIncremental(context, auth, owner, authoritativeEpoch, initialIdentity, onProgress, cancelled, requestGate, { beforeRequest() }, allowDatasetClear)
                } catch (failure: AuthFailure) {
                    if (failure.code !in setOf("CURSOR_EXPIRED", "SNAPSHOT_REQUIRED")) throw failure
                    requireSession(auth, initialIdentity)
                    if (cancelled()) throw AuthFailure("SNAPSHOT_CANCELLED")
                    if (auth.bootstrapEpoch(owner, expectedIdentity = initialIdentity, requestGate = requestGate, requestFence = { beforeRequest() }, allowDatasetClear = allowDatasetClear) != authoritativeEpoch) throw AuthFailure("DATASET_EPOCH_CHANGED")
                    onProgress("同步游标已失效，正在重新获取完整快照")
                    snapshotCapturedSeq = bootstrapWithCursorRecovery(context, auth, owner, authoritativeEpoch, initialIdentity, onProgress, cancelled,
                        allowDatasetClear = allowDatasetClear, requestGate = requestGate, requestFence = { beforeRequest() }, commitReplica = commitReplica)
                    bootstrapped = true
                    pullIncremental(context, auth, owner, authoritativeEpoch, initialIdentity, onProgress, cancelled, requestGate, { beforeRequest() }, allowDatasetClear)
                }
                requireSession(auth, initialIdentity)
                AccountSyncResult(authoritativeEpoch, bootstrapped, applied.first, applied.second, previousEpochArchived, snapshotCapturedSeq)
            }
            result
        } finally {
            auth.clearSyncRequestFence(requestFenceOwner)
        }
    }

    internal fun synchronizeCommittedRestore(
        context: Context,
        auth: AuthClient,
        owner: UUID,
        oldEpoch: UUID,
        newEpoch: UUID,
        onProgress: (String) -> Unit,
    ): Boolean {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Account sync requires a background thread" }
        val identity = DeviceSessions.identity(auth.snapshot())
        if (identity.owner != owner) throw AuthFailure("DATASET_IDENTITY_MISMATCH")
        val restoreWire = auth.restoreBootstrapWire()
        if (restoreWire.owner != owner || restoreWire.committedEpoch != newEpoch || restoreWire.restoreJobId == UUID(0, 0))
            throw AuthFailure("RESTORE_UNCONFIRMED")
        val (authoritativeEpoch, _) = auth.bootstrapIdentity(owner, restoreWire)
        requireSession(auth, identity)
        if (authoritativeEpoch != newEpoch || authoritativeEpoch == oldEpoch) throw AuthFailure("DATASET_EPOCH_CHANGED")
        val activeEpoch = EncryptedDatasetStore.activeAccountEpoch(context, owner)
        if (activeEpoch == newEpoch) {
            val database = EncryptedDatasetStore.openAccount(context, owner, newEpoch)
            try {
                val stored = database.identity().read() ?: throw AuthFailure("DATASET_IDENTITY_MISMATCH")
                if (stored.ownerId != owner.toString() || stored.epochId != newEpoch.toString())
                    throw AuthFailure("DATASET_IDENTITY_MISMATCH")
                CanonicalAccountRepository.read(database)
            } finally {
                database.close()
            }
            requireSession(auth, identity)
            if (auth.bootstrapIdentity(owner, restoreWire).first != newEpoch) throw AuthFailure("DATASET_EPOCH_CHANGED")
            onProgress("新 epoch 已完成完整导入与校验")
            return true
        }
        if (activeEpoch != oldEpoch) throw AuthFailure("DATASET_EPOCH_CHANGED")
        archiveEpoch(context, owner, oldEpoch, "RESTORE_COMMITTED")
        onProgress("恢复已提交，正在验证并导入新 epoch 完整快照")
        bootstrapWithCursorRecovery(context, auth, owner, newEpoch, identity, onProgress, { false }, restoreBootstrapWire = restoreWire)
        requireSession(auth, identity)
        if (auth.bootstrapIdentity(owner, restoreWire).first != newEpoch) throw AuthFailure("DATASET_EPOCH_CHANGED")
        return true
    }

    private fun archiveEpoch(context: Context, owner: UUID, epoch: UUID, reason: String) {
        val database = EncryptedDatasetStore.openAccount(context, owner, epoch)
        try {
            val dao = database.accountMutations()
            database.runInTransaction {
                dao.archiveIntents(owner.toString(), epoch.toString())
                dao.archiveOutbox(owner.toString(), epoch.toString(), System.currentTimeMillis())
                dao.deactivateEpochPredictions(owner.toString(), epoch.toString())
                dao.insertArchive(AccountEpochArchiveEntity(owner.toString(), epoch.toString(), System.currentTimeMillis(), reason))
            }
        } finally { database.close() }
    }

    private fun bootstrapWithCursorRecovery(
        context: Context,
        auth: AuthClient,
        owner: UUID,
        expectedEpoch: UUID,
        identity: DeviceIdentity,
        onProgress: (String) -> Unit,
        cancelled: () -> Boolean,
        allowDatasetClear: Boolean = false,
        requestGate: Any? = null,
        requestFence: (() -> Unit)? = null,
        restoreBootstrapWire: dev.icos.app.data.remote.RestoreBootstrapWire? = null,
        commitReplica: (() -> Unit) -> Unit = { it() },
    ): Long {
        return try {
            bootstrapFromSnapshot(context, auth, owner, expectedEpoch, identity, onProgress, cancelled, allowDatasetClear, requestGate, requestFence, restoreBootstrapWire, commitReplica)
        } catch (failure: AuthFailure) {
            if (failure.code != "SNAPSHOT_REQUIRED") throw failure
            requireSession(auth, identity)
            val currentEpoch = if (restoreBootstrapWire != null) auth.bootstrapIdentity(owner, restoreBootstrapWire).first else auth.bootstrapEpoch(owner, expectedIdentity = identity, requestGate = requestGate, requestFence = requestFence, allowDatasetClear = allowDatasetClear)
            if (currentEpoch != expectedEpoch) throw AuthFailure("DATASET_EPOCH_CHANGED")
            onProgress("数据已继续变化，正在重试同一账号快照")
            bootstrapFromSnapshot(context, auth, owner, expectedEpoch, identity, onProgress, cancelled, allowDatasetClear, requestGate, requestFence, restoreBootstrapWire, commitReplica)
        }
    }

    private fun bootstrapFromSnapshot(
        context: Context,
        auth: AuthClient,
        owner: UUID,
        epoch: UUID,
        identity: DeviceIdentity,
        onProgress: (String) -> Unit,
        cancelled: () -> Boolean,
        allowDatasetClear: Boolean = false,
        requestGate: Any? = null,
        requestFence: (() -> Unit)? = null,
        restoreBootstrapWire: dev.icos.app.data.remote.RestoreBootstrapWire? = null,
        commitReplica: (() -> Unit) -> Unit,
    ): Long {
        onProgress("正在准备账号独立账本空间")
        val staged = EncryptedDatasetStore.createAccountStaging(context, owner, epoch)
        var previous: DatasetDatabase? = null
        var activated = false
        var verifiedSnapshot: dev.icos.app.data.remote.SnapshotProtocol.VerifiedSnapshot? = null
        fun currentAuthoritativeEpoch(): UUID = if (restoreBootstrapWire != null) {
            auth.bootstrapIdentity(owner, restoreBootstrapWire).first
        } else {
            auth.bootstrapIdentity(owner, expectedIdentity = identity, requestGate = requestGate,
                requestFence = requestFence, allowDatasetClear = allowDatasetClear).first
        }
        try {
            if (EncryptedDatasetStore.activeAccountEpoch(context, owner) == epoch) {
                previous = EncryptedDatasetStore.openAccount(context, owner, epoch)
            }
            if (previous != null) copyLocalMutationState(previous, staged.database, owner, epoch)
            val sync = TypedSyncClient(auth, staged.database)
            val verified = sync.prepareBootstrap(context.cacheDir, cancelled, onProgress, restoreBootstrapWire,
                expectedIdentity = identity, requestGate = requestGate, requestFence = requestFence, allowDatasetClear = allowDatasetClear)
            verifiedSnapshot = verified
            requireSession(auth, identity)
            if (verified.ownerId != owner || verified.epoch != epoch ||
                (restoreBootstrapWire != null && verified.epoch != restoreBootstrapWire.committedEpoch)) throw AuthFailure("DATASET_EPOCH_CHANGED")
            if (cancelled() || Thread.currentThread().isInterrupted) throw AuthFailure("SNAPSHOT_CANCELLED")
            requireSession(auth, identity)
            if (cancelled() || Thread.currentThread().isInterrupted) throw AuthFailure("SNAPSHOT_CANCELLED")
            if (currentAuthoritativeEpoch() != epoch) throw AuthFailure("DATASET_EPOCH_CHANGED")
            if (cancelled() || Thread.currentThread().isInterrupted) throw AuthFailure("SNAPSHOT_CANCELLED")
            requireSession(auth, identity)
            if (currentAuthoritativeEpoch() != epoch) throw AuthFailure("DATASET_EPOCH_CHANGED")
            onProgress("正在导入服务器完整快照")
            SnapshotImporter.importCanonicalMirror(staged.database, verified, owner, epoch, verified.cursor)
            reconcileSnapshotReceipts(staged.database, owner, epoch)
            requireCompleteReplica(staged.database, verified.entityCounts)
            if (restoreBootstrapWire == null) {
                val requestFence = requestFence ?: throw AuthFailure("CLEAR_REAUTH_UNCONFIRMED")
                val requestGate = requestGate ?: throw AuthFailure("CLEAR_REAUTH_UNCONFIRMED")
                pullStagedToHead(staged.database, auth, owner, epoch, identity, onProgress, cancelled,
                    allowDatasetClear, requestGate, requestFence)
                reconcileSnapshotReceipts(staged.database, owner, epoch)
                if (allowDatasetClear) {
                    val uncertain = uncertainMutationCommands(
                        staged.database.typedOutbox().all(owner.toString(), epoch.toString()),
                        staged.database.accountMutations().intentsInOrder(owner.toString(), epoch.toString()),
                        staged.database.accountMutations().predictions(owner.toString(), epoch.toString()),
                    )
                    if (uncertain.isNotEmpty()) throw AuthFailure("MUTATION_REPLAY_UNCERTAIN", retryable = true)
                } else {
                    replayUncertainMutations(staged.database, auth, owner, epoch, identity, verified.capturedSeq, onProgress, cancelled)
                }
            }
            if (cancelled()) throw AuthFailure("SNAPSHOT_CANCELLED")
            requireSession(auth, identity)
            // The epoch can be retired while the snapshot is imported; never activate a stale space.
            if (currentAuthoritativeEpoch() != epoch) throw AuthFailure("DATASET_EPOCH_CHANGED")
            requireSession(auth, identity)
            val completedIdentity = staged.database.identity().read() ?: throw AuthFailure("DATASET_IDENTITY_MISMATCH")
            val finalCursor = completedIdentity.serverCursor ?: throw AuthFailure("DATASET_IDENTITY_MISMATCH")
            check(completedIdentity.lastAppliedSeq >= verified.capturedSeq)
            // Run the same production projection before selection so malformed canonical money or references cannot activate.
            CanonicalAccountRepository.read(staged.database)
            requireSession(auth, identity)
            if (currentAuthoritativeEpoch() != epoch) throw AuthFailure("DATASET_EPOCH_CHANGED")
            if (cancelled() || Thread.currentThread().isInterrupted) throw AuthFailure("SNAPSHOT_CANCELLED")
            commitReplica {
                if (cancelled() || Thread.currentThread().isInterrupted) throw AuthFailure("SNAPSHOT_CANCELLED")
                EncryptedDatasetStore.activateAccountStaging(
                    context,
                    staged,
                    completedIdentity.lastAppliedSeq,
                    finalCursor,
                )
                activated = true
            }
            return verified.capturedSeq
        } catch (failure: SelectorSyncException) {
            activated = true
            throw failure
        } finally {
            verifiedSnapshot?.close()
            previous?.close()
            if (!activated) {
                runCatching { staged.database.close() }
                runCatching { staged.directory.deleteRecursively() }
            }
        }
    }

    private fun replayUncertainMutations(
        database: DatasetDatabase,
        auth: AuthClient,
        owner: UUID,
        epoch: UUID,
        identity: DeviceIdentity,
        capturedSeq: Long,
        onProgress: (String) -> Unit,
        cancelled: () -> Boolean,
    ) {
        val mutationDao = database.accountMutations()
        val uncertain = uncertainMutationCommands(
            database.typedOutbox().all(owner.toString(), epoch.toString()),
            mutationDao.intentsInOrder(owner.toString(), epoch.toString()),
            mutationDao.predictions(owner.toString(), epoch.toString()),
        )
        val outbox = database.typedOutbox()
        val mutations = mutationDao
        if (uncertain.isNotEmpty()) {
            onProgress("正在使用原操作 ID 对账 ${uncertain.size} 条账号写入")
            val client = TypedSyncClient(auth, database)
            for (page in uncertain.chunked(50)) {
                if (cancelled() || Thread.currentThread().isInterrupted) throw AuthFailure("SNAPSHOT_CANCELLED")
                requireSession(auth, identity)
                val frozenDeviceId = UUID.fromString(page.first().deviceId)
                check(page.all { it.deviceId == frozenDeviceId.toString() }) { "MUTATION_REPLAY_UNCERTAIN" }
                client.pushCommands(frozenDeviceId, page)
            }
            val requiredSeq = uncertain.mapNotNull { command ->
                val resultJson = outbox.byOpId(command.opId)?.serverResultJson
                    ?: throw AuthFailure("MUTATION_REPLAY_UNCERTAIN", retryable = true)
                if (receiptCoveredByWatermark(resultJson, capturedSeq)) null
                else org.json.JSONObject(resultJson).getLong("seq")
            }.maxOrNull()
            if (requiredSeq != null) {
                pullStagedToHead(database, auth, owner, epoch, identity, onProgress, cancelled)
                val current = database.identity().read() ?: throw AuthFailure("DATASET_IDENTITY_MISMATCH")
                if (current.lastAppliedSeq < requiredSeq) throw AuthFailure("MUTATION_REPLAY_UNCERTAIN", retryable = true)
            }
            reconcileSnapshotReceipts(database, owner, epoch)
        }
        val remaining = mutations.predictions(owner.toString(), epoch.toString())
        if (remaining.isNotEmpty()) {
            val commands = outbox.all(owner.toString(), epoch.toString())
            val intents = mutations.intentsInOrder(owner.toString(), epoch.toString())
            remaining.forEach { prediction ->
                val command = commands.singleOrNull { it.opId == prediction.opId }
                    ?: throw AuthFailure("MUTATION_REPLAY_UNCERTAIN", retryable = true)
                val intent = intents.singleOrNull { it.opId == prediction.opId }
                    ?: throw AuthFailure("MUTATION_REPLAY_UNCERTAIN", retryable = true)
                check(intent.state == "QUEUED" && command.state == "QUEUED" && requireFrozenOutboxCommand(command, commands)) {
                    "MUTATION_REPLAY_UNCERTAIN"
                }
            }
            check(uncertainMutationCommands(commands, intents, remaining).isEmpty()) { "MUTATION_REPLAY_UNCERTAIN" }
        }
    }

    private fun reconcileSnapshotReceipts(database: DatasetDatabase, owner: UUID, epoch: UUID) {
        val ownerId = owner.toString()
        val epochId = epoch.toString()
        val outboxDao = database.typedOutbox()
        val dao = database.accountMutations()
        database.runInTransaction {
            val identity = database.identity().read() ?: throw AuthFailure("DATASET_IDENTITY_MISMATCH")
            check(identity.ownerId == ownerId && identity.epochId == epochId)
            outboxDao.all(ownerId, epochId).forEach { outbox ->
                val intent = dao.intent(outbox.opId) ?: return@forEach
                val prediction = dao.activePrediction(outbox.opId) ?: return@forEach
                val receipt = dao.receipt(outbox.opId) ?: return@forEach
                requireFrozenOutboxCommand(outbox)
                check(intent.ownerId == ownerId && intent.epochId == epochId &&
                    intent.ownerId == outbox.ownerId && intent.epochId == outbox.epochId &&
                    intent.aggregateType == outbox.aggregateType && intent.aggregateId == outbox.aggregateId &&
                    intent.commandType == outbox.commandType && intent.payloadJson == outbox.payloadJson &&
                    prediction.ownerId == ownerId && prediction.epochId == epochId &&
                    prediction.aggregateType == outbox.aggregateType && prediction.aggregateId == outbox.aggregateId &&
                    prediction.payloadJson == intent.payloadJson && receipt.ownerId == ownerId && receipt.epochId == epochId &&
                    receipt.status in setOf("APPLIED", "ALREADY_APPLIED") &&
                    outbox.state in setOf("APPLIED", "ALREADY_APPLIED")) {
                    "MUTATION_REPLAY_UNCERTAIN"
                }
                val outboxResult = outbox.serverResultJson ?: throw AuthFailure("MUTATION_REPLAY_UNCERTAIN", retryable = true)
                val receiptSeq = receiptSequence(receipt.resultJson)
                check(matchingSuccessfulReceipts(receipt.status, receipt.resultJson, outbox.state, outboxResult, outbox.opId)) {
                    "MUTATION_REPLAY_UNCERTAIN"
                }
                if (receiptSeq > identity.lastAppliedSeq) return@forEach
                check(outboxDao.markCanonical(outbox.groupId, ownerId, epochId, System.currentTimeMillis()) == 1)
                check(dao.deactivatePrediction(outbox.opId) == 1)
                check(dao.state(outbox.opId, "PULLED") == 1)
                dao.insertApplied(AccountAppliedGroupEntity(outbox.groupId, ownerId, epochId, receiptSeq))
            }
        }
    }

    private fun copyLocalMutationState(source: DatasetDatabase, target: DatasetDatabase, owner: UUID, epoch: UUID) {
        val ownerId = owner.toString()
        val epochId = epoch.toString()
        val outbox = source.typedOutbox().all(ownerId, epochId)
        val mutations = source.accountMutations()
        val intents = mutations.intentsInOrder(ownerId, epochId)
        val predictions = mutations.allPredictions(ownerId, epochId)
        val receipts = mutations.receipts(ownerId, epochId)
        val applied = mutations.applied(ownerId, epochId)
        val archives = mutations.archives(ownerId, epochId)
        val targetDao = target.accountMutations()
        target.runInTransaction {
            val identity = target.identity().read() ?: throw AuthFailure("DATASET_IDENTITY_MISMATCH")
            check(identity.ownerId == ownerId && identity.epochId == epochId) { "DATASET_IDENTITY_MISMATCH" }
            val recoveryMarkers = recoveryMarkersToBackfill(ownerId, epochId, outbox, intents, predictions)
            outbox.forEach(target.typedOutbox()::insert)
            intents.forEach(targetDao::insertIntent)
            predictions.forEach(targetDao::insertPrediction)
            recoveryMarkers.forEach(targetDao::insertPrediction)
            receipts.forEach(targetDao::insertReceipt)
            applied.forEach(targetDao::insertApplied)
            archives.forEach(targetDao::insertArchive)
        }
    }

    private fun pullStagedToHead(
        database: DatasetDatabase,
        auth: AuthClient,
        owner: UUID,
        epoch: UUID,
        sessionIdentity: DeviceIdentity,
        onProgress: (String) -> Unit,
        cancelled: () -> Boolean,
        allowDatasetClear: Boolean = false,
        requestGate: Any? = null,
        requestFence: (() -> Unit)? = null,
    ) {
        val sync = TypedSyncClient(auth, database)
        var pages = 0
        while (pages < MAX_PULL_PAGES) {
            if (cancelled() || Thread.currentThread().isInterrupted) throw AuthFailure("SNAPSHOT_CANCELLED")
            val identity = database.identity().read() ?: throw AuthFailure("DATASET_IDENTITY_MISMATCH")
            val cursor = identity.serverCursor ?: throw AuthFailure("DATASET_IDENTITY_MISMATCH")
            if (cancelled()) throw AuthFailure("SNAPSHOT_CANCELLED")
            val page = if (allowDatasetClear) {
                val gate = requestGate ?: throw AuthFailure("CLEAR_REAUTH_UNCONFIRMED")
                val fence = requestFence ?: throw AuthFailure("CLEAR_REAUTH_UNCONFIRMED")
                sync.pullPage(cursor, PULL_LIMIT, sessionIdentity, gate,
                    requestFence = {
                        if (cancelled() || Thread.currentThread().isInterrupted) throw AuthFailure("SNAPSHOT_CANCELLED")
                        fence()
                        requireSession(auth, sessionIdentity)
                        if (auth.snapshot().session?.user?.id != owner) throw AuthFailure("DATASET_IDENTITY_MISMATCH")
                    }, allowDatasetClear = true)
            } else {
                sync.pullPage(cursor, PULL_LIMIT)
            }
            requireSession(auth, sessionIdentity)
            if (cancelled()) throw AuthFailure("SNAPSHOT_CANCELLED")
            if (page.epoch != epoch) throw AuthFailure("DATASET_EPOCH_CHANGED")
            if (page.changes.length() > 0) {
                pages++
                onProgress("正在补齐快照期间的增量（第 $pages 页，${page.changes.length()} 条）")
                SnapshotImporter.applyPullMirror(database, page, owner)
            } else {
                if (page.hasMore) throw AuthFailure("INVALID_RESPONSE", retryable = true)
                SnapshotImporter.applyPullMirror(database, page, owner)
                val current = database.identity().read() ?: throw AuthFailure("DATASET_IDENTITY_MISMATCH")
                check(current.lastAppliedSeq == page.headSeq) { "Pull ended before the authoritative server head" }
                return
            }
            if (!page.hasMore) {
                val current = database.identity().read() ?: throw AuthFailure("DATASET_IDENTITY_MISMATCH")
                check(current.lastAppliedSeq == page.headSeq) { "Pull ended before the authoritative server head" }
                return
            }
            if (pages == MAX_PULL_PAGES) throw AuthFailure("PULL_PAGE_LIMIT", retryable = true)
        }
        throw AuthFailure("PULL_PAGE_LIMIT", retryable = true)
    }

    private fun pullIncremental(
        context: Context,
        auth: AuthClient,
        owner: UUID,
        epoch: UUID,
        identity: DeviceIdentity,
        onProgress: (String) -> Unit,
        cancelled: () -> Boolean,
        requestGate: Any,
        requestFence: () -> Unit,
        allowDatasetClear: Boolean,
    ): Pair<Int, Int> {
        val database = EncryptedDatasetStore.openAccount(context, owner, epoch)
        var pages = 0
        var changes = 0
        try {
            val sync = TypedSyncClient(auth, database)
            while (pages < MAX_PULL_PAGES) {
                if (cancelled() || Thread.currentThread().isInterrupted) throw AuthFailure("SNAPSHOT_CANCELLED")
                val cursor = database.identity().read()?.serverCursor
                    ?: throw AuthFailure("DATASET_IDENTITY_MISMATCH")
                if (cancelled()) throw AuthFailure("SNAPSHOT_CANCELLED")
                val page = sync.pullPage(
                    cursor, PULL_LIMIT, identity, requestGate,
                    requestFence = {
                        if (cancelled() || Thread.currentThread().isInterrupted) throw AuthFailure("SNAPSHOT_CANCELLED")
                        requestFence()
                        requireSession(auth, identity)
                        if (EncryptedDatasetStore.activeAccountEpoch(context, owner) != epoch) throw AuthFailure("DATASET_EPOCH_CHANGED")
                    },
                    allowDatasetClear = allowDatasetClear,
                )
                requireSession(auth, identity)
                if (cancelled()) throw AuthFailure("SNAPSHOT_CANCELLED")
                if (page.changes.length() == 0) {
                    if (page.hasMore) throw AuthFailure("INVALID_RESPONSE", retryable = true)
                    val current = database.identity().read() ?: throw AuthFailure("DATASET_IDENTITY_MISMATCH")
                    if (current.lastAppliedSeq != page.headSeq) throw AuthFailure("INVALID_RESPONSE", retryable = true)
                    break
                }
                pages++
                changes += page.changes.length()
                onProgress("正在应用增量变更（第 $pages 页，${page.changes.length()} 条）")
                SnapshotImporter.applyPullMirror(database, page, owner)
                if (!page.hasMore) {
                    val current = database.identity().read() ?: throw AuthFailure("DATASET_IDENTITY_MISMATCH")
                    if (current.lastAppliedSeq != page.headSeq) throw AuthFailure("INVALID_RESPONSE", retryable = true)
                    break
                }
                if (pages == MAX_PULL_PAGES) throw AuthFailure("PULL_PAGE_LIMIT", retryable = true)
            }
        } finally {
            database.close()
        }
        return pages to changes
    }

    private fun requireSession(auth: AuthClient, expected: DeviceIdentity) {
        DeviceSessions.requireSameIdentity(expected, auth.snapshot())
    }

    /** The activated replica must hold every canonical table with the snapshot's declared row counts. */
    private fun requireCompleteReplica(database: DatasetDatabase, counts: Map<String, Long>) {
        val db = database.openHelper.writableDatabase
        val mirrorTables = db.query(
            "SELECT name FROM sqlite_master WHERE type='table' AND name GLOB 'server_*'",
        ).use { cursor ->
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(0).removePrefix("server_"))
            }
        }
        val identity = database.identity().read() ?: throw AuthFailure("DATASET_IDENTITY_MISMATCH")
        val schemaTables = dev.icos.app.data.remote.SnapshotProtocol.tablesFor(identity.snapshotSchemaVersion)
        require(mirrorTables == schemaTables)
        counts.forEach { (table, expected) ->
            val actual = db.query("SELECT COUNT(*) FROM `server_$table`").use { cursor ->
                check(cursor.moveToFirst())
                cursor.getLong(0)
            }
            require(actual == expected) { "Canonical replica row count mismatch" }
        }
    }
}
