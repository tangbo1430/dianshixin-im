package dev.icos.app.fileio

import android.content.Context
import dev.icos.app.data.local.AccountSyncCoordinator
import dev.icos.app.data.local.DatasetDatabase
import dev.icos.app.data.local.EncryptedDatasetStore
import dev.icos.app.data.local.FileImportBatchEntity
import dev.icos.app.data.local.FileImportRowEntity
import dev.icos.app.data.local.SnapshotImporter
import dev.icos.app.data.local.TypedCommand
import dev.icos.app.data.local.TypedOutboxEntity
import dev.icos.app.data.local.TypedOutboxState
import dev.icos.app.data.local.TypedSyncStore
import dev.icos.app.data.remote.AuthClient
import dev.icos.app.data.remote.AuthFailure
import dev.icos.app.data.remote.DeviceSessions
import dev.icos.app.data.remote.TypedSyncClient
import dev.icos.app.domain.AccountAmount
import dev.icos.app.domain.Money
import dev.icos.app.domain.RecordInput
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/** Account import producer. All financial facts are written only by the server import transaction. */
internal class AccountFileImportCoordinator(
    context: Context,
    private val auth: AuthClient,
    private val owner: UUID,
    private val epoch: UUID,
    private val expectedIdentity: dev.icos.app.data.remote.DeviceIdentity? = null,
    private val requestGate: Any? = null,
    private val requestFence: (() -> Unit)? = null,
) {
    data class Preview(
        val parsed: TabularFileCore.ParseResult,
        val mapping: ImportPreview,
        val accountOptions: Map<String, UUID>,
        val categoryOptions: List<dev.icos.app.data.local.FinanceCategory>,
        val tagOptions: Map<String, List<UUID>>,
        val selectedRows: Set<Int>,
        val ownerId: UUID,
        val epochId: UUID,
        val sessionIdentity: dev.icos.app.data.remote.DeviceIdentity,
        val generation: Long,
    )

    private data class Intent(val source: String, val chunks: List<String>, val dependencies: String, val currentOp: String? = null)
    private data class WireRow(val index: Int, val externalId: String?, val wire: JSONObject, val deps: JSONObject)
    private val app = context.applicationContext
    @Volatile private var operationSession: dev.icos.app.data.remote.DeviceIdentity? = null

    fun preview(file: File, zone: ZoneId, snapshot: dev.icos.app.data.local.FinanceSnapshot, generation: Long = -1L): Preview {
        require(snapshot.ownerId == owner && snapshot.epochId == epoch) { "IMPORT_PREVIEW_IDENTITY_MISMATCH" }
        val parsed = file.inputStream().use { TabularFileCore.parse(file.name, it) }
        val authState = auth.snapshot()
        val sessionIdentity = DeviceSessions.identity(authState)
        require(sessionIdentity.owner == owner) { "DATASET_IDENTITY_MISMATCH" }
        val mapping = ImportRowMapper.map(parsed, zone, setOf("CNY"), "CNY", emptyMap(), emptyMap())
        return Preview(parsed, mapping,
            snapshot.accounts.filter { it.selectable && it.archivedAt == null }.associate { it.name to it.id },
            snapshot.categories, snapshot.tags.filter { it.archivedAt == null }.groupBy({ it.name }, { it.id }),
            mapping.candidates.map { it.sourceRow }.toSet(), owner, epoch, sessionIdentity, generation)
    }

    fun remap(preview: Preview, zone: ZoneId, accountBindings: Map<String, UUID>, categoryBindings: Map<String, UUID>): Preview {
        val expectedKinds = preview.mapping.candidates.associate { candidate ->
            "${candidate.primaryCategory}\u0000${candidate.secondaryCategory}\u0000${candidate.kind}" to candidate.kind
        }
        val safeCategories = categoryBindings.filter { (key, id) ->
            preview.categoryOptions.any { it.id == id && it.direction.name == expectedKinds[key] }
        }
        val mapping = ImportRowMapper.map(preview.parsed, zone, setOf("CNY"), "CNY", accountBindings, safeCategories)
        return preview.copy(mapping = mapping)
    }

    fun stage(
        preview: Preview, zone: ZoneId, snapshot: dev.icos.app.data.local.FinanceSnapshot,
        accountBindings: Map<String, UUID>, categoryBindings: Map<String, UUID>, tagBindings: Map<String, UUID>,
        cancelled: () -> Boolean = { false }, progress: (String) -> Unit = {}, expectedGeneration: Long = -1L,
    ): UUID {
        val currentAuth = auth.snapshot()
        DeviceSessions.requireSameIdentity(preview.sessionIdentity, currentAuth)
        operationSession = preview.sessionIdentity
        require(preview.ownerId == owner && preview.epochId == epoch && currentAuth.session?.user?.id == owner &&
            EncryptedDatasetStore.activeAccountEpoch(app, owner) == epoch) { "IMPORT_PREVIEW_IDENTITY_MISMATCH" }
        require(preview.mapping.candidates.all { candidate ->
            candidate.kind == "TRANSFER" || accountBindings.containsKey(candidate.account1.takeIf(String::isNotBlank) ?: "")
        }) { "ACCOUNT_BINDING_REQUIRED:普通收支需要明确选择入账账户" }
        require(preview.mapping.candidates.all { candidate -> candidate.tags.all { name ->
            tagBindings[name]?.let { it in preview.tagOptions[name].orEmpty() } ?: (preview.tagOptions[name]?.size == 1)
        } }) { "TAG_BINDING_REQUIRED:同名标签多个时需明确选择" }
        require(preview.parsed.rows.isNotEmpty()) { "IMPORT_NO_ROWS" }
        require(preview.parsed.errors.isEmpty() && preview.mapping.errors.isEmpty()) { "IMPORT_PREVIEW_ERRORS_MUST_BE_RESOLVED" }
        require(preview.selectedRows == preview.mapping.candidates.map { it.sourceRow }.toSet()) {
            "ACCOUNT_IMPORT_PARTIAL_SELECTION_UNSUPPORTED:服务端批次只能原子提交文件的全部数据行"
        }
        require(preview.mapping.candidates.size == preview.parsed.rows.size) { "IMPORT_PREVIEW_ROWS_INCOMPLETE" }
        require(expectedGeneration < 0L || preview.generation == expectedGeneration) { "IMPORT_PREVIEW_GENERATION_MISMATCH" }
        checkNotCancelled(cancelled)
        DeviceSessions.requireSameIdentity(preview.sessionIdentity, auth.snapshot())
        val database = openDatabase()
        val batchId: UUID
        try {
            val identity = database.identity().read() ?: throw AuthFailure("DATASET_IDENTITY_MISMATCH")
            require(identity.serverCursor != null && identity.snapshotSchemaVersion >= 5) { "ACCOUNT_IMPORT_REQUIRES_DATA5_CANONICAL_SYNC" }
            require(auth.snapshot().session?.user?.id == owner && identity.ownerId == owner.toString() && identity.epochId == epoch.toString()) {
                "DATASET_IDENTITY_MISMATCH"
            }
                checkNotCancelled(cancelled)
                DeviceSessions.requireSameIdentity(preview.sessionIdentity, auth.snapshot())
                val mapped = mapRows(database, preview, zone, snapshot, accountBindings, categoryBindings, tagBindings)
            val deps = JSONObject()
            mapped.forEach { row -> row.deps.keys().forEach { key -> deps.put(key, row.deps.getLong(key)) } }
            val chunkStrings = chunk(mapped)
            require(chunkStrings.size in 1..MAX_CHUNKS) { "IMPORT_TOO_MANY_CHUNKS" }
            require(chunkStrings.sumOf { it.toByteArray(Charsets.UTF_8).size.toLong() } <= MAX_FILE_BYTES) {
                "IMPORT_NORMALIZED_BATCH_TOO_LARGE:规范化暂存数据超过服务端 20 MiB 限制"
            }
            require(chunkStrings.all { raw ->
                val commandEnvelope = JSONObject().put("opId", "0".repeat(36)).put("groupId", "0".repeat(36))
                    .put("aggregateType", "import_batch").put("aggregateId", "0".repeat(36))
                    .put("baseVersions", JSONObject()).put("commandType", "import.append").put("schemaVersion", 1)
                    .put("payload", JSONObject().put("chunkIndex", MAX_CHUNKS - 1).put("chunkDigest", "0".repeat(64))
                        .put("canonicalRowsJson", raw).put("rows", JSONArray(raw)))
                commandEnvelope.toString().toByteArray(Charsets.UTF_8).size < MAX_PUSH_BODY_BYTES
            }) { "IMPORT_CHUNK_ENVELOPE_TOO_LARGE:推送包超过服务端 1 MiB 限制" }
            val expectedSource = if (preview.parsed.rows.first().fields.size == 11) "icos-ledger-v1-ext1" else "icos-ledger-v1"
            require(preview.parsed.rows.all { it.fields.size == preview.parsed.rows.first().fields.size }) { "IMPORT_COLUMN_COUNT_MISMATCH" }
            require(mapped.all { row ->
                if (expectedSource == "icos-ledger-v1") row.externalId == null
                else row.externalId == null || preview.parsed.rows.getOrNull(row.index)?.fields?.getOrNull(10) == row.externalId
            }) { "IMPORT_EXTERNAL_ID_SOURCE_MISMATCH" }
            val source = expectedSource
            batchId = UUID.randomUUID()
            val intent = Intent(source, chunkStrings, deps.toString())
            val now = System.currentTimeMillis()
            val rows = mapped.map { row ->
                FileImportRowEntity(batchId.toString(), row.index, row.wire.toString(), digest(row.wire.toString().toByteArray(Charsets.UTF_8)), row.externalId, null, "PREVIEWED")
            }
            val localBatch = FileImportBatchEntity(batchId.toString(), owner.toString(), epoch.toString(), preview.parsed.sha256,
                encodeIntent(intent), "PREPARED_LOCAL", mapped.size, now, null)
            val payload = JSONObject().put("source", source).put("formatVersion", 1).put("fileDigest", preview.parsed.sha256)
                .put("totalChunks", chunkStrings.size).put("totalRows", mapped.size)
            val prepare = command(database, batchId, "import.prepare", payload, JSONObject().put("import_batch:$batchId", 0))
            database.runInTransaction {
                checkNotCancelled(cancelled)
                DeviceSessions.requireSameIdentity(preview.sessionIdentity, auth.snapshot())
                database.fileImports().insert(localBatch)
                database.fileImports().insertRows(rows)
                TypedSyncStore(database).enqueue(prepare)
            }
        } finally { database.close() }
        progress("已保存完整映射和原始操作标识，正在准备服务端暂存")
        resume(batchId, cancelled, progress, stopAfterPrepare = true)
        return batchId
    }

    /** Resumes the exact persisted command after interruption; never creates a replacement opId for ambiguity. */
    fun resume(batchId: UUID, cancelled: () -> Boolean = { false }, progress: (String) -> Unit = {}, stopAfterPrepare: Boolean = false) {
        while (true) {
            checkNotCancelled(cancelled)
            val database = openDatabase()
            val batch: FileImportBatchEntity
            val intent: Intent
            val canonical: JSONObject
            val nextCommand: TypedOutboxEntity?
            try {
                batch = requireBatch(database, batchId)
                checkNotCancelled(cancelled)
                require(batch.ownerId == owner.toString() && batch.epochId == epoch.toString()) { "DATASET_IDENTITY_MISMATCH" }
                if (batch.state in setOf("COMMITTED", "CANCELLED", "ROLLED_BACK")) return
                intent = decodeIntent(batch.formatVersion)
                canonical = SnapshotImporter.canonicalTables(database)
                nextCommand = intent.currentOp?.let { database.typedOutbox().byOpId(it) }
            } finally { database.close() }

            if (nextCommand != null) {
                val result = sendAndPull(nextCommand, cancelled, progress)
                check(result.optString("opId") == nextCommand.opId && result.optLong("seq", 0) > 0) { "IMPORT_ACK_INVALID" }
                val updated = openDatabase()
                try {
                    val freshBatch = requireBatch(updated, batchId)
                    when (nextCommand.commandType) {
                        "import.prepare" -> {
                            requireServerBatchStatus(updated, batchId, "STAGING")
                            saveIntent(updated, freshBatch, intent.copy(currentOp = null), "STAGING")
                            if (stopAfterPrepare) return
                        }
                        "import.append" -> {
                            val chunkIndex = JSONObject(nextCommand.payloadJson).getInt("chunkIndex")
                            requireCanonicalChunk(updated, batchId, chunkIndex, JSONObject(nextCommand.payloadJson).getString("chunkDigest"))
                            val next = intent.copy(currentOp = null)
                            saveIntent(updated, freshBatch, next, "STAGING")
                        }
                        "import.commit" -> {
                            if (result.optBoolean("snapshotRequired")) {
                                val identity = updated.identity().read() ?: throw AuthFailure("DATASET_IDENTITY_MISMATCH")
                                require(identity.lastAppliedSeq >= result.optLong("requiredCapturedSeq", Long.MAX_VALUE)) { "IMPORT_CANONICAL_SNAPSHOT_NOT_CAUGHT_UP" }
                            }
                            requireServerBatchStatus(updated, batchId, "COMMITTED")
                            updated.fileImports().setBatchState(batchId.toString(), "COMMITTED", System.currentTimeMillis())
                            return
                        }
                        "import.rollback" -> {
                            val batchState = requireBatch(updated, batchId).state
                            val expected = if (batchState == "ROLLING_BACK") "ROLLED_BACK" else "CANCELLED"
                            requireServerBatchStatus(updated, batchId, expected)
                            updated.fileImports().setBatchState(batchId.toString(), expected, System.currentTimeMillis())
                            return
                        }
                    }
                } finally { updated.close() }
                continue
            }

            if (batch.state == "PREPARED_LOCAL") {
                val prepare = findCommand(batchId, "import.prepare")
                    ?: throw AuthFailure("IMPORT_OUTBOX_MISSING", retryable = true)
                persistCurrent(batchId, prepare.opId)
                continue
            }
            if (batch.state == "COMMITTING" || batch.state == "CANCELLING" || batch.state == "ROLLING_BACK") {
                val type = if (batch.state == "COMMITTING") "import.commit" else "import.rollback"
                val operation = intent.currentOp?.let { database.typedOutbox().byOpId(it) }
                    ?.takeIf { it.commandType == type && it.state in setOf("QUEUED", "AMBIGUOUS", "APPLIED", "ALREADY_APPLIED") }
                    ?: findCommand(batchId, type) ?: throw AuthFailure("IMPORT_OUTBOX_MISSING", retryable = true)
                persistCurrent(batchId, operation.opId)
                continue
            }
            require(batch.state == "STAGING") { "INVALID_IMPORT_STATE:${batch.state}" }
            val nextIndex = (intent.chunks.indices).firstOrNull { index ->
                !hasCanonicalChunk(canonical, batchId, index, digest(intent.chunks[index].toByteArray(Charsets.UTF_8)))
            } ?: -1
            if (nextIndex >= 0) {
                val append = makeAppend(batchId, nextIndex, intent)
                // The exact canonicalRowsJson and digest are frozen in outbox before any request.
                val db = openDatabase()
                try {
                    val current = SnapshotImporter.canonicalTables(db)
                    val batchRevision = revision(current, "import_batches", batchId)
                    val versions = JSONObject().put("import_batch:$batchId", batchRevision)
                    val deps = JSONObject(intent.dependencies)
                    deps.keys().asSequence().sorted().forEach { key -> versions.put(key, deps.getLong(key)) }
                    val command = command(db, batchId, "import.append", append, versions)
                    persistAppend(db, batchId, intent, command)
                } finally { db.close() }
                continue
            }
            if (batch.state == "STAGING") return
            prepareCommit(batchId, intent, cancelled, progress)
        }
    }

    fun commit(batchId: UUID, cancelled: () -> Boolean = { false }, progress: (String) -> Unit = {}) {
        val db = openDatabase()
        try {
            checkNotCancelled(cancelled)
            val batch = requireBatch(db, batchId)
            require(batch.ownerId == owner.toString() && batch.epochId == epoch.toString()) { "DATASET_IDENTITY_MISMATCH" }
            require(batch.state == "STAGING") { "INVALID_IMPORT_STATE" }
            val intent = decodeIntent(batch.formatVersion)
            val tables = SnapshotImporter.canonicalTables(db)
            val server = find(tables, "import_batches", batchId)
            require(server.getString("status") == "STAGING") { "IMPORT_SERVER_STATE_MISMATCH" }
            val deps = JSONObject(intent.dependencies)
            deps.keys().asSequence().sorted().forEach { key ->
                val (kind, rawId) = key.split(":", limit = 2)
                require(revision(tables, tableForDependency(kind), UUID.fromString(rawId)) == deps.getLong(key)) { "IMPORT_DEPENDENCY_CHANGED:$key" }
            }
            val rows = db.fileImports().rows(batchId.toString())
            require(rows.size == batch.rowCount && rows.isNotEmpty()) { "IMPORT_PREVIEW_CORRUPT" }
            val manifest = manifestDigest(intent.chunks)
            val payload = JSONObject().put("expectedChunks", intent.chunks.size).put("manifestDigest", manifest)
                .put("mappings", JSONObject()).put("duplicatePolicy", "SKIP_CONFIRMED_EXTERNAL_ID")
            val versions = JSONObject().put("import_batch:$batchId", server.getLong("serverRevision"))
            deps.keys().forEach { key -> versions.put(key, deps.getLong(key)) }
            val command = command(db, batchId, "import.commit", payload, versions)
            checkNotCancelled(cancelled)
            db.runInTransaction {
                TypedSyncStore(db).enqueue(command)
                db.fileImports().updateBatchIntent(batchId.toString(), encodeIntent(intent.copy(currentOp = command.opId.toString())), "COMMITTING", null)
            }
        } finally { db.close() }
        resume(batchId, cancelled, progress)
    }

    fun cancel(batchId: UUID, cancelled: () -> Boolean = { false }, progress: (String) -> Unit = {}) {
        checkNotCancelled(cancelled)
        val initial = openDatabase()
        val localState = try { requireBatch(initial, batchId).state } finally { initial.close() }
        if (localState == "PREPARED_LOCAL") {
            resume(batchId, cancelled, progress, stopAfterPrepare = true)
        }
        checkNotCancelled(cancelled)
        val db = openDatabase()
        try {
            val batch = requireBatch(db, batchId)
            require(batch.state == "STAGING") { "INVALID_IMPORT_STATE:${batch.state}" }
            val tables = SnapshotImporter.canonicalTables(db)
            val server = find(tables, "import_batches", batchId)
            when (server.getString("status")) {
                "STAGING" -> {
                    val intent = decodeIntent(batch.formatVersion)
                    val payload = JSONObject().put("dependencyStrategy", "REJECT")
                    val versions = JSONObject().put("import_batch:$batchId", server.getLong("serverRevision"))
                    val command = command(db, batchId, "import.rollback", payload, versions)
                    db.runInTransaction {
                        checkNotCancelled(cancelled)
                        TypedSyncStore(db).enqueue(command)
                        db.fileImports().updateBatchIntent(batchId.toString(), encodeIntent(intent.copy(currentOp = command.opId.toString())), "CANCELLING", null)
                    }
                }
                "CANCELLED" -> { db.fileImports().setBatchState(batchId.toString(), "CANCELLED", System.currentTimeMillis()); return }
                else -> throw IllegalStateException("INVALID_IMPORT_STATE:${server.getString("status")}")
            }
        } finally { db.close() }
        resume(batchId, cancelled, progress)
    }

    fun rollback(batchId: UUID, cancelled: () -> Boolean = { false }, progress: (String) -> Unit = {}) {
        checkNotCancelled(cancelled)
        val existingDb = openDatabase()
        val existingBatch = try { requireBatch(existingDb, batchId) } finally { existingDb.close() }
        if (existingBatch.state == "ROLLING_BACK") {
            resume(batchId, cancelled, progress)
            return
        }
        val db = openDatabase()
        try {
            val batch = requireBatch(db, batchId)
            require(batch.state == "COMMITTED") { "INVALID_IMPORT_STATE" }
            val tables = SnapshotImporter.canonicalTables(db)
            val server = find(tables, "import_batches", batchId)
            val records = (0 until tables.getJSONArray("records").length()).map { tables.getJSONArray("records").getJSONObject(it) }
                .filter { it.optString("importBatchId") == batchId.toString() && it.isNull("deletedAt") }
            val versions = JSONObject().put("import_batch:$batchId", server.getLong("serverRevision"))
            records.forEach { row -> versions.put("record:${row.getString("id")}", row.getLong("serverRevision")) }
            val intent = decodeIntent(batch.formatVersion)
            val existing = intent.currentOp?.let(db.typedOutbox()::byOpId)?.takeIf { it.commandType == "import.rollback" && it.state in setOf("QUEUED", "AMBIGUOUS", "APPLIED", "ALREADY_APPLIED") }
                ?: db.typedOutbox().all(owner.toString(), epoch.toString()).lastOrNull {
                    it.aggregateId == batchId.toString() && it.commandType == "import.rollback" && it.state in setOf("QUEUED", "AMBIGUOUS", "APPLIED", "ALREADY_APPLIED")
                }
            val newCommand = if (existing == null) command(db, batchId, "import.rollback", JSONObject().put("dependencyStrategy", "REJECT"), versions) else null
            if (existing != null) {
                val frozenVersions = JSONObject(existing.baseVersionsJson)
                versions.keys().forEach { key -> require(frozenVersions.has(key) && frozenVersions.getLong(key) == versions.getLong(key)) { "IMPORT_ROLLBACK_DEPENDENCY_CHANGED:$key" } }
            }
            val opId = existing?.opId ?: newCommand!!.opId.toString()
            db.runInTransaction {
                checkNotCancelled(cancelled)
                if (newCommand != null) TypedSyncStore(db).enqueue(newCommand)
                db.fileImports().updateBatchIntent(batchId.toString(), encodeIntent(intent.copy(currentOp = opId)), "ROLLING_BACK", null)
            }
        } finally { db.close() }
        resume(batchId, cancelled, progress)
    }

    fun history(): List<FileImportBatchEntity> {
        val db = openDatabase()
        return try { db.fileImports().batches(owner.toString(), epoch.toString()) } finally { db.close() }
    }

    private fun prepareCommit(batchId: UUID, intent: Intent, cancelled: () -> Boolean, progress: (String) -> Unit) {
        val db = openDatabase()
        try {
            checkNotCancelled(cancelled)
            val batch = requireBatch(db, batchId)
            require(batch.ownerId == owner.toString() && batch.epochId == epoch.toString()) { "DATASET_IDENTITY_MISMATCH" }
            val tables = SnapshotImporter.canonicalTables(db)
            val server = find(tables, "import_batches", batchId)
            require(server.getString("status") == "STAGING")
            val deps = JSONObject(intent.dependencies)
            deps.keys().asSequence().sorted().forEach { key ->
                val (kind, rawId) = key.split(":", limit = 2)
                require(revision(tables, tableForDependency(kind), UUID.fromString(rawId)) == deps.getLong(key)) { "IMPORT_DEPENDENCY_CHANGED:$key" }
            }
            val payload = JSONObject().put("expectedChunks", intent.chunks.size).put("manifestDigest", manifestDigest(intent.chunks))
                .put("mappings", JSONObject()).put("duplicatePolicy", "SKIP_CONFIRMED_EXTERNAL_ID")
            val versions = JSONObject().put("import_batch:$batchId", server.getLong("serverRevision"))
            deps.keys().forEach { key -> versions.put(key, deps.getLong(key)) }
            val command = command(db, batchId, "import.commit", payload, versions)
            checkNotCancelled(cancelled)
            db.runInTransaction {
                TypedSyncStore(db).enqueue(command)
                db.fileImports().updateBatchIntent(batchId.toString(), encodeIntent(intent.copy(currentOp = command.opId.toString())), "COMMITTING", null)
            }
        } finally { db.close() }
        progress("正在提交整批账单的服务端原子事务")
    }

    private fun sendAndPull(command: TypedOutboxEntity, cancelled: () -> Boolean, progress: (String) -> Unit): JSONObject {
        checkNotCancelled(cancelled)
        require(command.ownerId == owner.toString() && command.epochId == epoch.toString()) { "DATASET_IDENTITY_MISMATCH" }
        val operationGate = requestGate ?: Any()
        val expectedSession = expectedIdentity ?: operationSession ?: DeviceSessions.identity(auth.snapshot())
        DeviceSessions.requireSameIdentity(expectedSession, auth.snapshot())
        val requestFence = {
            checkNotCancelled(cancelled)
            DeviceSessions.requireSameIdentity(expectedSession, auth.snapshot())
            require(EncryptedDatasetStore.activeAccountEpoch(app, owner) == epoch) { "DATASET_EPOCH_CHANGED" }
            this.requestFence?.invoke()
            checkNotCancelled(cancelled)
            DeviceSessions.requireSameIdentity(expectedSession, auth.snapshot())
        }
        val db = openDatabase()
        val result: JSONObject
        try {
            val localIdentity = db.identity().read() ?: throw AuthFailure("DATASET_IDENTITY_MISMATCH")
            require(localIdentity.ownerId == owner.toString() && localIdentity.epochId == epoch.toString()) { "DATASET_EPOCH_CHANGED" }
            requestFence()
            result = TypedSyncClient(auth, db).pushCommand(
                command.deviceId.let(UUID::fromString), command, expectedSession, operationGate, requestFence,
            )
            DeviceSessions.requireSameIdentity(expectedSession, auth.snapshot())
        } finally { db.close() }
        progress("服务器已应答，正在拉取规范账本并核对批次")
        val synced = AccountSyncCoordinator.syncAccount(
            app, auth, owner, onProgress = progress, cancelled = cancelled,
            requestFence = requestFence, requestGate = operationGate, expectedIdentity = expectedSession,
        )
        require(synced.epoch == epoch) { "DATASET_EPOCH_CHANGED" }
        checkNotCancelled(cancelled)
        if (result.optBoolean("snapshotRequired")) {
            val verified = openDatabase()
            try {
                val identity = verified.identity().read() ?: throw AuthFailure("DATASET_IDENTITY_MISMATCH")
                require(identity.lastAppliedSeq >= result.optLong("requiredCapturedSeq", Long.MAX_VALUE)) { "IMPORT_CANONICAL_SNAPSHOT_NOT_CAUGHT_UP" }
            } finally { verified.close() }
        } else requireApplied(result)
        return result
    }

    private fun persistCurrent(batchId: UUID, opId: String) {
        val db = openDatabase()
        try {
            val batch = requireBatch(db, batchId)
            val intent = decodeIntent(batch.formatVersion)
            db.fileImports().updateBatchIntent(batchId.toString(), encodeIntent(intent.copy(currentOp = opId)), batch.state, null)
        } finally { db.close() }
    }

    private fun persistAppend(db: DatasetDatabase, batchId: UUID, intent: Intent, command: TypedCommand) {
        db.runInTransaction {
            TypedSyncStore(db).enqueue(command)
            db.fileImports().updateBatchIntent(batchId.toString(), encodeIntent(intent.copy(currentOp = command.opId.toString())), "STAGING", null)
        }
    }

    private fun saveIntent(db: DatasetDatabase, batch: FileImportBatchEntity, intent: Intent, state: String) {
        db.fileImports().updateBatchIntent(batch.id, encodeIntent(intent), state, null)
    }

    private fun requireServerBatchStatus(db: DatasetDatabase, id: UUID, expected: String) {
        val tables = SnapshotImporter.canonicalTables(db)
        require(find(tables, "import_batches", id).getString("status") == expected) { "IMPORT_SERVER_STATE_MISMATCH" }
    }

    private fun requireCanonicalChunk(db: DatasetDatabase, batchId: UUID, index: Int, digest: String) {
        val chunks = SnapshotImporter.canonicalTables(db).getJSONArray("import_staging_chunks")
        require((0 until chunks.length()).any { i ->
            val row = chunks.getJSONObject(i)
            row.optString("batchId") == batchId.toString() && row.optInt("chunkIndex", -1) == index && row.optString("digest") == digest
        }) { "IMPORT_CANONICAL_CHUNK_MISSING" }
    }

    private fun hasCanonicalChunk(tables: JSONObject, batchId: UUID, index: Int, digest: String): Boolean {
        val chunks = tables.optJSONArray("import_staging_chunks") ?: return false
        return (0 until chunks.length()).any { i ->
            val row = chunks.getJSONObject(i)
            row.optString("batchId") == batchId.toString() && row.optInt("chunkIndex", -1) == index && row.optString("digest") == digest
        }
    }

    private fun findCommand(batchId: UUID, type: String): TypedOutboxEntity? {
        val db = openDatabase()
        return try { db.typedOutbox().all(owner.toString(), epoch.toString()).lastOrNull { it.aggregateId == batchId.toString() && it.commandType == type && it.state in setOf("QUEUED", "AMBIGUOUS", "APPLIED", "ALREADY_APPLIED") } }
        finally { db.close() }
    }

    private fun makeAppend(batchId: UUID, chunkIndex: Int, intent: Intent): JSONObject {
        val raw = intent.chunks[chunkIndex]
        val bytes = raw.toByteArray(Charsets.UTF_8)
        val rows = JSONArray(raw)
        return JSONObject().put("chunkIndex", chunkIndex).put("chunkDigest", digest(bytes))
            .put("canonicalRowsJson", raw).put("rows", rows)
    }

    private fun mapRows(
        db: DatasetDatabase, preview: Preview, zone: ZoneId, snapshot: dev.icos.app.data.local.FinanceSnapshot,
        accountBindings: Map<String, UUID>, categoryBindings: Map<String, UUID>, tagBindings: Map<String, UUID>,
    ): List<WireRow> {
        val indices = preview.parsed.rows.mapIndexed { index, row -> row.number to index }.toMap()
        val accounts = snapshot.accounts.filter { it.selectable && it.archivedAt == null }.associateBy { it.id }
        val categories = snapshot.categories.associateBy { it.id }
        val tags = snapshot.tags.filter { it.archivedAt == null }.associateBy { it.id }
        val tables = SnapshotImporter.canonicalTables(db)
        return preview.mapping.candidates.map { candidate ->
            val rowIndex = indices[candidate.sourceRow] ?: error("IMPORT_ROW_INDEX_MISSING")
            val kind = candidate.kind ?: error("INVALID_TYPE")
            val amount = candidate.amount ?: error("INVALID_AMOUNT")
            require(amount.currency == "CNY") { "UNSUPPORTED_IMPORT_CURRENCY:${amount.currency}" }
            val key = "${candidate.primaryCategory}\u0000${candidate.secondaryCategory}\u0000$kind"
            val category = if (kind == "TRANSFER") null else categoryBindings[key] ?: error("CATEGORY_BINDING_REQUIRED:$key")
            require(category == null || categories[category]?.direction?.name == kind) { "INVALID_CATEGORY_BINDING" }
            fun account(name: String): dev.icos.app.data.local.FinanceAccount {
                val id = accountBindings[name] ?: error("ACCOUNT_BINDING_REQUIRED:$name")
                return accounts[id] ?: error("INVALID_ACCOUNT_BINDING:$name")
            }
            val input = when (kind) {
                "EXPENSE" -> {
                    val name = candidate.account1.takeIf(String::isNotBlank) ?: ""
                    RecordInput.Expense(amount, null, listOf(AccountAmount(account(name).id, amount)), true, true)
                }
                "INCOME" -> {
                    val name = candidate.account1.takeIf(String::isNotBlank) ?: ""
                    RecordInput.Income(amount, listOf(AccountAmount(account(name).id, amount)), true)
                }
                "TRANSFER" -> {
                    val from = account(candidate.account1); val to = account(candidate.account2)
                    require(from.id != to.id && from.currency == amount.currency && to.currency == amount.currency) { "INVALID_TRANSFER_ACCOUNT_BINDING" }
                    RecordInput.Transfer(AccountAmount(from.id, amount), AccountAmount(to.id, amount), null, null)
                }
                else -> error("INVALID_TYPE")
            }
            input.calculate()
            val tagIds = candidate.tags.map { name ->
                val id = tagBindings[name] ?: preview.tagOptions[name]?.singleOrNull() ?: error("TAG_BINDING_REQUIRED:$name")
                require(tags[id]?.name == name) { "INVALID_TAG_BINDING:$name" }; id
            }
            require(tagIds.distinct().size == tagIds.size && tagIds.size <= 50) { "IMPORT_TAG_LIMIT" }
            require(candidate.remark.length <= 4000 && '\u0000' !in candidate.remark) { "IMPORT_REMARK_TOO_LONG" }
            val occurred = Instant.ofEpochMilli(candidate.occurredAtMillis ?: error("INVALID_DATE"))
            val record = JSONObject().put("ledgerId", snapshot.ledgerId.toString()).put("categoryId", category?.toString() ?: JSONObject.NULL)
                .put("occurredAt", DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(occurred.atZone(zone).toOffsetDateTime()))
                .put("timezone", zone.id).put("localDate", occurred.atZone(zone).toLocalDate().toString()).put("remark", candidate.remark)
                .put("tagIds", JSONArray(tagIds.map(UUID::toString))).put("attachmentIds", JSONArray())
                .put("feeCategoryId", JSONObject.NULL).put("bonusCategoryId", JSONObject.NULL).put("input", encodeInput(input))
            val deps = JSONObject()
            addDependency(deps, tables, "ledger", snapshot.ledgerId)
            category?.let { categoryId ->
                addDependency(deps, tables, "category", categoryId)
                val categoryRow = find(tables, "categories", categoryId)
                if (!categoryRow.isNull("parentId")) addDependency(deps, tables, "category", UUID.fromString(categoryRow.getString("parentId")))
            }
            input.calculate().postings.map { it.accountId }.distinct().forEach { addDependency(deps, tables, "account", it) }
            tagIds.forEach { addDependency(deps, tables, "tag", it) }
            val externalId = candidate.externalId?.takeIf(String::isNotBlank)
            require(externalId == null || externalId.length <= 256) { "INVALID_EXTERNAL_ID" }
            WireRow(rowIndex, externalId, JSONObject().put("rowIndex", rowIndex).put("externalId", externalId ?: JSONObject.NULL).put("record", record), deps)
        }
    }

    private fun chunk(rows: List<WireRow>): List<String> {
        val chunks = mutableListOf<MutableList<JSONObject>>()
        var current = mutableListOf<JSONObject>()
        for (row in rows) {
            val trial = JSONArray().also { a -> (current + row.wire).forEach(a::put) }.toString()
            if (trial.toByteArray(Charsets.UTF_8).size > MAX_CHUNK_BYTES) {
                require(current.isNotEmpty()) { "IMPORT_ROW_EXCEEDS_CHUNK_LIMIT:${row.index}" }
                chunks += current; current = mutableListOf(row.wire)
                require(JSONArray().put(row.wire).toString().toByteArray(Charsets.UTF_8).size <= MAX_CHUNK_BYTES) { "IMPORT_ROW_EXCEEDS_CHUNK_LIMIT:${row.index}" }
            } else current += row.wire
        }
        if (current.isNotEmpty()) chunks += current
        return chunks.map { rows -> JSONArray().also { a -> rows.forEach(a::put) }.toString() }
    }

    private fun manifestDigest(chunks: List<String>): String {
        val bytes = ByteArrayOutputStream()
        chunks.forEachIndexed { index, raw ->
            val hash = digest(raw.toByteArray(Charsets.UTF_8))
            bytes.write(byteArrayOf((index ushr 24).toByte(), (index ushr 16).toByte(), (index ushr 8).toByte(), index.toByte()))
            bytes.write(hash.toByteArray(Charsets.US_ASCII))
        }
        return digest(bytes.toByteArray())
    }

    private fun command(db: DatasetDatabase, batchId: UUID, type: String, payload: JSONObject, versions: JSONObject): TypedCommand {
        val id = db.identity().read() ?: error("DATASET_IDENTITY_MISMATCH")
        require(id.ownerId == owner.toString() && id.epochId == epoch.toString()) { "DATASET_IDENTITY_MISMATCH" }
        return TypedCommand(UUID.randomUUID(), UUID.randomUUID(), owner, epoch, auth.snapshot().deviceId,
            "import_batch", batchId, versions.toString(), type, 1, payload.toString())
    }

    private fun addDependency(target: JSONObject, tables: JSONObject, kind: String, id: UUID) {
        target.put("$kind:$id", revision(tables, tableForDependency(kind), id))
    }
    private fun tableForDependency(kind: String) = when (kind) { "ledger" -> "ledgers"; "category" -> "categories"; "account" -> "accounts"; "tag" -> "tags"; else -> error("IMPORT_DEPENDENCY_UNSUPPORTED") }
    private fun revision(tables: JSONObject, table: String, id: UUID): Long = find(tables, table, id).getLong("serverRevision")
    private fun find(tables: JSONObject, table: String, id: UUID): JSONObject =
        (0 until tables.getJSONArray(table).length()).map { tables.getJSONArray(table).getJSONObject(it) }
            .firstOrNull { it.optString("id") == id.toString() && it.isNull("deletedAt") } ?: error("CANONICAL_REFERENCE_UNAVAILABLE:$table:$id")

    private fun encodeInput(input: RecordInput): JSONObject = when (input) {
        is RecordInput.Expense -> JSONObject().put("kind", "EXPENSE").put("gross", money(input.gross)).put("discount", input.discount?.let(::money) ?: JSONObject.NULL)
            .put("allocations", JSONArray().also { a ->
                val recordAmounts = input.recordCurrencyAmounts ?: input.allocations.map { it.amount }
                input.allocations.forEachIndexed { index, allocation ->
                    val encoded = accountAmount(allocation.accountId, allocation.amount)
                    if (recordAmounts[index].currency != allocation.amount.currency) encoded.put("recordCurrencyAmount", money(recordAmounts[index]))
                    a.put(encoded)
                }
            })
            .put("countInStatistics", input.countInStatistics).put("countInBudget", input.countInBudget)
        is RecordInput.Income -> JSONObject().put("kind", "INCOME").put("amount", money(input.amount))
            .put("allocations", JSONArray().also { a -> input.allocations.forEach { a.put(accountAmount(it.accountId, it.amount)) } }).put("countInStatistics", input.countInStatistics)
        is RecordInput.Transfer -> JSONObject().put("kind", "TRANSFER").put("from", accountAmount(input.from.accountId, input.from.amount))
            .put("to", accountAmount(input.to.accountId, input.to.amount)).put("fee", JSONObject.NULL).put("bonus", JSONObject.NULL)
    }
    private fun accountAmount(id: UUID, amount: Money) = JSONObject().put("accountId", id.toString()).put("amount", money(amount))
    private fun money(value: Money) = JSONObject().put("unscaled", value.unscaled.toString()).put("scale", value.scale).put("currency", value.currency)

    private fun encodeIntent(intent: Intent) = JSONObject().put("version", 1).put("source", intent.source)
        .put("chunks", JSONArray(intent.chunks)).put("dependencies", JSONObject(intent.dependencies))
        .put("currentOp", intent.currentOp ?: JSONObject.NULL).toString()
    private fun decodeIntent(raw: String): Intent {
        val value = JSONObject(raw); require(value.optInt("version") == 1)
        return Intent(value.getString("source"), value.getJSONArray("chunks").let { a -> List(a.length()) { a.getString(it) } },
            value.getJSONObject("dependencies").toString(), value.optString("currentOp").takeIf { it.isNotBlank() && it != "null" })
    }

    private fun requireBatch(db: DatasetDatabase, id: UUID) = db.fileImports().batch(id.toString()) ?: error("IMPORT_BATCH_NOT_FOUND")
    private fun openDatabase(): DatasetDatabase {
        val expectedSession = operationSession ?: DeviceSessions.identity(auth.snapshot()).also { operationSession = it }
        DeviceSessions.requireSameIdentity(expectedSession, auth.snapshot())
        require(EncryptedDatasetStore.activeAccountEpoch(app, owner) == epoch) { "DATASET_EPOCH_CHANGED" }
        return EncryptedDatasetStore.openAccount(app, owner, epoch)
    }
    private fun checkNotCancelled(cancelled: () -> Boolean) { if (cancelled() || Thread.currentThread().isInterrupted) throw AuthFailure("IMPORT_CANCELLED") }
    private fun requireApplied(result: JSONObject) {
        if (result.optString("status") !in setOf("APPLIED", "ALREADY_APPLIED") || result.optBoolean("snapshotRequired")) {
            throw AuthFailure(result.optJSONObject("error")?.optString("code") ?: "IMPORT_ACK_REQUIRES_SNAPSHOT", retryable = true)
        }
        require(result.optLong("seq", 0) > 0) { "IMPORT_ACK_INVALID" }
    }
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        const val MAX_CHUNK_BYTES = 192 * 1024
        const val MAX_ROWS = 100_000
        const val MAX_CHUNKS = 20_000
        const val MAX_FILE_BYTES = 20L * 1024 * 1024
        private const val MAX_PUSH_BODY_BYTES = 1024 * 1024
        fun remapPreview(preview: Preview, zone: ZoneId, accountBindings: Map<String, UUID>, categoryBindings: Map<String, UUID>): Preview {
            val expectedKinds = preview.mapping.candidates.associate { candidate ->
                "${candidate.primaryCategory}\u0000${candidate.secondaryCategory}\u0000${candidate.kind}" to candidate.kind
            }
            val safeCategories = categoryBindings.filter { (key, id) ->
                preview.categoryOptions.any { it.id == id && it.direction.name == expectedKinds[key] }
            }
            val mapping = ImportRowMapper.map(preview.parsed, zone, setOf("CNY"), "CNY", accountBindings, safeCategories)
            return preview.copy(mapping = mapping)
        }
    }
}
