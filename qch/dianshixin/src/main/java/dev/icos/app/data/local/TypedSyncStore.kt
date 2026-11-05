package dev.icos.app.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.json.JSONObject
import java.util.UUID

@Dao
internal interface AccountMutationDao {
    @Insert(onConflict = androidx.room.OnConflictStrategy.IGNORE) fun insertIntent(value: AccountMutationIntentEntity)
    @Insert(onConflict = androidx.room.OnConflictStrategy.IGNORE) fun insertPrediction(value: AccountPredictionEntity)
    @Insert(onConflict = androidx.room.OnConflictStrategy.IGNORE) fun insertReceipt(value: AccountWriteReceiptEntity)
    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE) fun upsertReceipt(value: AccountWriteReceiptEntity)
    @Insert(onConflict = androidx.room.OnConflictStrategy.IGNORE) fun insertArchive(value: AccountEpochArchiveEntity)
    @Insert(onConflict = androidx.room.OnConflictStrategy.IGNORE) fun insertApplied(value: AccountAppliedGroupEntity)
    @Insert(onConflict = androidx.room.OnConflictStrategy.IGNORE) fun insert(value: TypedOutboxEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM account_mutation_intent WHERE ownerId=:owner AND epochId=:epoch AND aggregateType='budget' AND aggregateId=:ledgerId AND commandType IN ('budget.configure','budget.reset','budget.setEnabled') AND state IN ('QUEUED','AMBIGUOUS','ACKED'))")
    fun hasPendingBudgetMutation(owner: String, epoch: String, ledgerId: String): Boolean

    @Query("SELECT * FROM account_mutation_intent WHERE ownerId=:owner AND epochId=:epoch AND aggregateType='record_template' AND aggregateId=:id AND state IN ('QUEUED','AMBIGUOUS','ACKED') LIMIT 1")
    fun pendingTemplate(owner: String, epoch: String, id: String): AccountMutationIntentEntity?
    @Query("SELECT * FROM account_mutation_intent WHERE ownerId=:owner AND epochId=:epoch AND aggregateType='recurrence' AND aggregateId=:id AND state IN ('QUEUED','AMBIGUOUS','ACKED') LIMIT 1")
    fun pendingRecurrence(owner: String, epoch: String, id: String): AccountMutationIntentEntity?
    @Query("SELECT * FROM account_mutation_intent WHERE ownerId=:owner AND epochId=:epoch AND aggregateType='category' AND commandType='metadata.create' AND state IN ('QUEUED','AMBIGUOUS','ACKED') ORDER BY createdAt")
    fun pendingCategoryCreations(owner: String, epoch: String): List<AccountMutationIntentEntity>
    @Query("SELECT COUNT(*) FROM account_mutation_intent WHERE ownerId=:owner AND epochId=:epoch AND state IN ('QUEUED','AMBIGUOUS','ACKED')")
    fun pendingCount(owner: String, epoch: String): Int
    @Query("SELECT COUNT(*) FROM account_mutation_intent WHERE ownerId=:owner AND epochId=:epoch AND state IN ('CONFLICT','REJECTED','BLOCKED_DEPENDENCY')")
    fun retryCount(owner: String, epoch: String): Int
    @Query("SELECT * FROM account_mutation_intent WHERE ownerId=:owner AND epochId=:epoch AND aggregateType='recurrence' AND commandType='recurrence.create' AND state IN ('QUEUED','AMBIGUOUS','ACKED') LIMIT 1")
    fun pendingRecurrenceCreate(owner: String, epoch: String): AccountMutationIntentEntity?
    @Query("SELECT * FROM account_mutation_intent WHERE ownerId=:owner AND epochId=:epoch AND state IN ('QUEUED','AMBIGUOUS') ORDER BY createdAt")
    fun queued(owner: String, epoch: String): List<AccountMutationIntentEntity>
    @Query("SELECT * FROM account_mutation_intent WHERE ownerId=:owner AND epochId=:epoch ORDER BY createdAt")
    fun intentsInOrder(owner: String, epoch: String): List<AccountMutationIntentEntity>
    @Query("SELECT * FROM account_mutation_intent WHERE ownerId=:owner AND epochId=:epoch AND state='CONFLICT' ORDER BY createdAt DESC")
    fun conflicts(owner: String, epoch: String): List<AccountMutationIntentEntity>
    @Query("SELECT * FROM account_mutation_intent WHERE ownerId=:owner AND epochId=:epoch AND aggregateType='sync_conflict' AND aggregateId=:conflictId AND commandType='sync.conflict.resolve' AND state='AMBIGUOUS' ORDER BY createdAt DESC LIMIT 1")
    fun pendingConflictResolution(owner: String, epoch: String, conflictId: String): AccountMutationIntentEntity?
    @Query("SELECT * FROM account_mutation_intent WHERE ownerId=:owner AND epochId=:epoch AND aggregateType='sync_conflict' AND aggregateId=:conflictId AND commandType='sync.conflict.resolve' AND state IN ('CONFLICT','REJECTED','RESOLVED_LOCAL','RESOLVED_SERVER','RESOLVED_EDITED','CONFLICT_REVIEW_STALE') ORDER BY createdAt DESC LIMIT 1")
    fun latestConflictResolution(owner: String, epoch: String, conflictId: String): AccountMutationIntentEntity?
    @Query("SELECT * FROM account_prediction WHERE ownerId=:owner AND epochId=:epoch ORDER BY rowid")
    fun allPredictions(owner: String, epoch: String): List<AccountPredictionEntity>
    @Query("SELECT * FROM account_write_receipt WHERE ownerId=:owner AND epochId=:epoch")
    fun receipts(owner: String, epoch: String): List<AccountWriteReceiptEntity>
    @Query("SELECT * FROM account_applied_group WHERE ownerId=:owner AND epochId=:epoch")
    fun applied(owner: String, epoch: String): List<AccountAppliedGroupEntity>
    @Query("SELECT * FROM account_epoch_archive WHERE ownerId=:owner AND epochId=:epoch")
    fun archives(owner: String, epoch: String): List<AccountEpochArchiveEntity>
    @Query("UPDATE account_mutation_intent SET state='ARCHIVED' WHERE ownerId=:owner AND epochId=:epoch")
    fun archiveIntents(owner: String, epoch: String): Int
    @Query("UPDATE typed_sync_outbox SET state='BLOCKED_DEPENDENCY',updatedAt=:now WHERE ownerId=:owner AND epochId=:epoch AND state IN ('QUEUED','AMBIGUOUS')")
    fun archiveOutbox(owner: String, epoch: String, now: Long): Int
    @Query("SELECT * FROM account_mutation_intent WHERE opId=:opId")
    fun intent(opId: String): AccountMutationIntentEntity?
    @Query("UPDATE account_mutation_intent SET state='AMBIGUOUS' WHERE opId=:opId")
    fun markIntentAmbiguous(opId: String): Int
    @Query("UPDATE account_mutation_intent SET state='BLOCKED_DEPENDENCY' WHERE opId=:opId")
    fun blockIntent(opId: String): Int
    @Query("UPDATE account_mutation_intent SET state=:state WHERE opId=:opId")
    fun state(opId: String, state: String): Int
    @Query("UPDATE account_prediction SET active=0 WHERE opId=:opId")
    fun deactivatePrediction(opId: String): Int
    @Query("SELECT * FROM account_prediction WHERE ownerId=:owner AND epochId=:epoch AND active=1 ORDER BY rowid")
    fun predictions(owner: String, epoch: String): List<AccountPredictionEntity>
    @Query("SELECT * FROM account_prediction WHERE opId=:opId AND active=1")
    fun activePrediction(opId: String): AccountPredictionEntity?
    @Query("UPDATE account_prediction SET active=0 WHERE ownerId=:owner AND epochId=:epoch")
    fun deactivateEpochPredictions(owner: String, epoch: String): Int
    @Query("SELECT * FROM account_write_receipt WHERE opId=:opId")
    fun receipt(opId: String): AccountWriteReceiptEntity?
    @Query("SELECT EXISTS(SELECT 1 FROM account_applied_group WHERE groupId=:group AND ownerId=:owner AND epochId=:epoch)")
    fun isApplied(group: String, owner: String, epoch: String): Boolean
    @Query("SELECT * FROM account_applied_group WHERE groupId=:group")
    fun appliedGroup(group: String): AccountAppliedGroupEntity?
    @Query("SELECT EXISTS(SELECT 1 FROM account_epoch_archive WHERE ownerId=:owner AND epochId=:epoch)")
    fun isArchived(owner: String, epoch: String): Boolean
}

internal enum class TypedOutboxState {
    QUEUED,
    SENDING,
    AMBIGUOUS,
    APPLIED,
    ALREADY_APPLIED,
    CONFLICT,
    REJECTED,
    BLOCKED_DEPENDENCY,
}

@Entity(
    tableName = "typed_sync_outbox",
    indices = [
        Index(value = ["ownerId", "epochId", "deviceId", "state", "sequence"]),
        Index(value = ["opId"], unique = true),
    ],
)
internal data class TypedOutboxEntity(
    @PrimaryKey(autoGenerate = true) val sequence: Long = 0,
    val opId: String,
    val groupId: String,
    val ownerId: String,
    val epochId: String,
    val deviceId: String,
    val aggregateType: String,
    val aggregateId: String,
    val baseVersionsJson: String,
    val commandType: String,
    val schemaVersion: Int,
    val payloadJson: String,
    val state: String = TypedOutboxState.QUEUED.name,
    val serverResultJson: String? = null,
    val errorCode: String? = null,
    val createdAt: Long,
    val updatedAt: Long = createdAt,
)

@Dao
internal interface TypedOutboxDao {
    @Insert fun insert(value: TypedOutboxEntity)

    @Query("SELECT * FROM typed_sync_outbox WHERE ownerId=:owner AND epochId=:epoch ORDER BY sequence")
    fun all(owner: String, epoch: String): List<TypedOutboxEntity>

    @Query(
        """SELECT * FROM typed_sync_outbox
        WHERE ownerId = :owner AND epochId = :epoch AND deviceId = :device
          AND commandType <> 'dataset.clearRecords'
          AND state IN ('QUEUED', 'AMBIGUOUS')
        ORDER BY sequence LIMIT :limit""",
    )
    fun pending(owner: String, epoch: String, device: String, limit: Int): List<TypedOutboxEntity>

    @Query("SELECT * FROM typed_sync_outbox WHERE opId = :opId")
    fun byOpId(opId: String): TypedOutboxEntity?

    @Query("UPDATE typed_sync_outbox SET baseVersionsJson=:versions,state='AMBIGUOUS',updatedAt=:now WHERE opId=:opId AND state='QUEUED'")
    fun freeze(opId: String, versions: String, now: Long): Int

    @Query("SELECT * FROM typed_sync_outbox WHERE groupId = :groupId")
    fun byGroupId(groupId: String): TypedOutboxEntity?

    @Query("SELECT o.* FROM typed_sync_outbox o INNER JOIN account_mutation_intent i ON i.opId=o.opId WHERE o.ownerId=:owner AND o.epochId=:epoch AND i.ownerId=:owner AND i.epochId=:epoch AND i.state='ACKED' AND o.state IN ('APPLIED','ALREADY_APPLIED') AND o.commandType<>'dataset.clearRecords' ORDER BY o.sequence")
    fun acknowledgedPendingCanonical(owner: String, epoch: String): List<TypedOutboxEntity>

    @Query("UPDATE typed_sync_outbox SET state='APPLIED',updatedAt=:now WHERE groupId=:groupId AND ownerId=:owner AND epochId=:epoch")
    fun markCanonical(groupId: String, owner: String, epoch: String, now: Long): Int

    @Query(
        """UPDATE typed_sync_outbox SET state = :state, serverResultJson = :result,
        errorCode = :errorCode, updatedAt = :updatedAt WHERE opId = :opId
        AND ownerId = :owner AND epochId = :epoch AND deviceId = :device""",
    )
    fun recordResult(opId: String, owner: String, epoch: String, device: String, state: String, result: String?, errorCode: String?, updatedAt: Long): Int

    @Query("UPDATE typed_sync_outbox SET state='APPLIED',serverResultJson=:result,errorCode=NULL,updatedAt=:now WHERE opId=:opId AND ownerId=:owner AND epochId=:epoch")
    fun acknowledgeOp(opId: String, owner: String, epoch: String, result: String, now: Long): Int
    @Query("UPDATE typed_sync_outbox SET state='BLOCKED_DEPENDENCY',updatedAt=:now WHERE opId=:opId")
    fun blockOp(opId: String, now: Long): Int
    @Query(
        """UPDATE typed_sync_outbox SET state = 'AMBIGUOUS', updatedAt = :updatedAt
        WHERE opId = :opId AND ownerId = :owner AND epochId = :epoch AND deviceId = :device""",
    )
    fun markAmbiguous(opId: String, owner: String, epoch: String, device: String, updatedAt: Long): Int
}

internal data class TypedCommand(
    val opId: UUID = UUID.randomUUID(),
    val groupId: UUID = UUID.randomUUID(),
    val ownerId: UUID,
    val epochId: UUID,
    val deviceId: UUID,
    val aggregateType: String,
    val aggregateId: UUID,
    val baseVersionsJson: String,
    val commandType: String,
    val schemaVersion: Int = 1,
    val payloadJson: String,
)

internal class TypedSyncStore(private val database: DatasetDatabase) {
    private val dao = database.typedOutbox()

    @Transaction
    fun enqueue(command: TypedCommand) {
        require(command.opId != UUID(0, 0) && command.groupId != UUID(0, 0))
        require(command.ownerId != UUID(0, 0) && command.epochId != UUID(0, 0) && command.deviceId != UUID(0, 0))
        require(command.aggregateType.matches(Regex("[a-z][a-z0-9_]{0,63}")))
        require(command.commandType.matches(Regex("[a-z][a-zA-Z0-9_.]{0,127}")))
        require(command.schemaVersion == 1)
        val baseVersions = JSONObject(command.baseVersionsJson)
        require(baseVersions.keys().asSequence().all { key ->
            key.matches(Regex("[a-z][a-z0-9_]*:[0-9a-fA-F-]{36}")) && baseVersions.get(key) is Number &&
                (baseVersions.getLong(key) >= 0 || key.startsWith("category:") && validCategoryDependency(command.ownerId.toString(), command.epochId.toString(), key, baseVersions.getLong(key)))
        })
        JSONObject(command.payloadJson)
        val identity = database.identity().read() ?: error("Dataset identity missing")
        check(identity.ownerId == command.ownerId.toString() && identity.epochId == command.epochId.toString())
        val previous = dao.byOpId(command.opId.toString())
        if (previous != null) {
            check(previous.groupId == command.groupId.toString() && previous.ownerId == command.ownerId.toString() &&
                previous.epochId == command.epochId.toString() && previous.deviceId == command.deviceId.toString() &&
                previous.aggregateType == command.aggregateType && previous.aggregateId == command.aggregateId.toString() &&
                previous.baseVersionsJson == command.baseVersionsJson && previous.commandType == command.commandType &&
                previous.schemaVersion == command.schemaVersion && previous.payloadJson == command.payloadJson)
            return
        }
        val now = System.currentTimeMillis()
        dao.insert(TypedOutboxEntity(
            opId = command.opId.toString(), groupId = command.groupId.toString(), ownerId = command.ownerId.toString(),
            epochId = command.epochId.toString(), deviceId = command.deviceId.toString(), aggregateType = command.aggregateType,
            aggregateId = command.aggregateId.toString(), baseVersionsJson = command.baseVersionsJson,
            commandType = command.commandType, schemaVersion = command.schemaVersion, payloadJson = command.payloadJson, createdAt = now,
        ))
    }

    fun byOpId(opId: String): TypedOutboxEntity? = dao.byOpId(opId)

    /** Negative category revisions are local predecessor sequence tokens, never server revisions. */
    fun captureCategoryDependencies(versions: JSONObject) {
        val identity = database.identity().read() ?: error("Dataset identity missing")
        val intents = database.accountMutations().intentsInOrder(identity.ownerId, identity.epochId).associateBy { it.opId }
        val predecessors = dao.all(identity.ownerId, identity.epochId).filter {
            it.aggregateType == "category" && intents[it.opId]?.state in setOf("QUEUED", "AMBIGUOUS", "ACKED")
        }
        versions.keys().asSequence().filter { it.startsWith("category:") }.toList().forEach { key ->
            predecessors.lastOrNull { "category:${it.aggregateId}" == key }?.let { versions.put(key, -it.sequence) }
        }
    }

    private fun validCategoryDependency(owner: String, epoch: String, key: String, token: Long): Boolean =
        token < 0 && token != Long.MIN_VALUE && dao.all(owner, epoch).any {
            it.sequence == -token && it.aggregateType == "category" && key == "category:${it.aggregateId}"
        }

    /** Resolves only never-sent commands, and durably freezes before the first HTTP attempt. */
    fun freezeForSend(command: TypedOutboxEntity): TypedOutboxEntity? = database.runInTransaction(java.util.concurrent.Callable {
        val identity = database.identity().read() ?: error("Dataset identity missing")
        check(identity.ownerId == command.ownerId && identity.epochId == command.epochId)
        val row = dao.byOpId(command.opId) ?: error("Outbox missing")
        check(row.ownerId == command.ownerId && row.epochId == command.epochId && row.deviceId == command.deviceId)
        if (row.state !in setOf("QUEUED", "AMBIGUOUS", "SENDING", "APPLIED", "ALREADY_APPLIED")) return@Callable null
        val versions = JSONObject(row.baseVersionsJson)
        if (row.state != "QUEUED") {
            check(versions.keys().asSequence().all { versions.getLong(it) >= 0 }) { "Frozen wire contains unresolved dependencies" }
            return@Callable row
        }
        val all = dao.all(row.ownerId, row.epochId).associateBy { it.sequence }
        val tables by lazy { SnapshotImporter.canonicalTables(database, setOf("categories")) }
        var resolvedDependencies = false
        for (key in versions.keys().asSequence().toList()) {
            val token = versions.getLong(key)
            if (token >= 0) continue
            check(key.startsWith("category:") && token != Long.MIN_VALUE)
            val predecessor = all[-token] ?: error("Category dependency missing")
            check(predecessor.sequence < row.sequence && predecessor.aggregateType == "category" && key == "category:${predecessor.aggregateId}")
            val intent = database.accountMutations().intent(predecessor.opId) ?: error("Category dependency intent missing")
            if (intent.state in setOf("CONFLICT", "REJECTED", "BLOCKED_DEPENDENCY", "ARCHIVED")) {
                blockFailedDependencies(database)
                return@Callable null
            }
            if (intent.state != "PULLED") return@Callable null
            val categories = tables.getJSONArray("categories")
            val category = (0 until categories.length()).map { categories.getJSONObject(it) }
                .singleOrNull { it.optString("id") == predecessor.aggregateId && it.isNull("deletedAt") && it.isNull("archivedAt") }
            if (category == null || category.getLong("serverRevision") <= 0) {
                check(dao.blockOp(row.opId, System.currentTimeMillis()) == 1)
                database.accountMutations().blockIntent(row.opId)
                database.accountMutations().deactivatePrediction(row.opId)
                blockFailedDependencies(database)
                return@Callable null
            }
            versions.put(key, category.getLong("serverRevision"))
            resolvedDependencies = true
        }
        check(dao.freeze(row.opId, if (resolvedDependencies) versions.toString() else row.baseVersionsJson, System.currentTimeMillis()) == 1)
        database.accountMutations().intent(row.opId)?.let { check(database.accountMutations().markIntentAmbiguous(row.opId) == 1) }
        dao.byOpId(row.opId) ?: error("Frozen outbox missing")
    })

    companion object {
        fun validateLocalDependencies(command: TypedOutboxEntity, rows: List<TypedOutboxEntity>) {
            val versions = JSONObject(command.baseVersionsJson)
            versions.keys().asSequence().forEach { key ->
                val value = versions.getLong(key)
                if (value < 0) {
                    check(command.state == "QUEUED" && key.startsWith("category:") && value != Long.MIN_VALUE) { "Invalid local dependency" }
                    check(rows.any { predecessor -> predecessor.ownerId == command.ownerId && predecessor.epochId == command.epochId &&
                        predecessor.sequence == -value && predecessor.sequence < command.sequence && predecessor.aggregateType == "category" &&
                        key == "category:${predecessor.aggregateId}" }) { "Missing local dependency" }
                }
            }
        }

        /** Called inside the result transaction so no dangling dependent prediction becomes visible. */
        fun blockFailedDependencies(database: DatasetDatabase) {
            val identity = database.identity().read() ?: error("Dataset identity missing")
            val dao = database.accountMutations()
            val rows = database.typedOutbox().all(identity.ownerId, identity.epochId)
            val bySequence = rows.associateBy { it.sequence }
            val failed = dao.intentsInOrder(identity.ownerId, identity.epochId)
                .filter { it.state in setOf("CONFLICT", "REJECTED", "BLOCKED_DEPENDENCY", "ARCHIVED") }.mapTo(HashSet()) { it.opId }
            rows.filter { it.state == "QUEUED" }.forEach { row ->
                val versions = JSONObject(row.baseVersionsJson)
                if (versions.keys().asSequence().any { key ->
                    val token = versions.getLong(key)
                    token < 0 && token != Long.MIN_VALUE && bySequence[-token]?.opId in failed
                }) {
                    database.typedOutbox().blockOp(row.opId, System.currentTimeMillis())
                    dao.blockIntent(row.opId)
                    dao.deactivatePrediction(row.opId)
                    failed += row.opId
                }
            }
        }
    }

    fun acknowledgeDatasetClear(opId: UUID, owner: UUID, epoch: UUID, result: String) {
        check(dao.acknowledgeOp(opId.toString(), owner.toString(), epoch.toString(), result, System.currentTimeMillis()) == 1)
    }

    fun blockDatasetClear(opId: UUID) {
        check(database.accountMutations().blockIntent(opId.toString()) == 1)
        val row = dao.byOpId(opId.toString()) ?: error("Clear outbox missing")
        if (row.state !in setOf("APPLIED", "ALREADY_APPLIED", "CONFLICT", "REJECTED", "BLOCKED_DEPENDENCY")) {
            check(dao.blockOp(opId.toString(), System.currentTimeMillis()) == 1)
        }
    }

    fun pending(owner: UUID, epoch: UUID, device: UUID, limit: Int = 50): List<TypedOutboxEntity> {
        require(limit in 1..50)
        return dao.pending(owner.toString(), epoch.toString(), device.toString(), limit)
    }
    fun recordResult(command: TypedOutboxEntity, state: TypedOutboxState, result: String?, errorCode: String? = null) {
        require(state in setOf(TypedOutboxState.APPLIED, TypedOutboxState.ALREADY_APPLIED, TypedOutboxState.CONFLICT, TypedOutboxState.REJECTED, TypedOutboxState.BLOCKED_DEPENDENCY))
        check(dao.recordResult(command.opId, command.ownerId, command.epochId, command.deviceId, state.name, result, errorCode, System.currentTimeMillis()) == 1)
    }
    fun markAmbiguous(command: TypedOutboxEntity) {
        check(dao.markAmbiguous(command.opId, command.ownerId, command.epochId, command.deviceId, System.currentTimeMillis()) == 1)
    }
}

internal fun typedSyncMigration12To13(owner: String, epoch: String) = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.query("SELECT ownerId,epochId FROM dataset_identity").use { cursor ->
            check(cursor.count == 1 && cursor.moveToFirst() && cursor.getString(0) == owner && cursor.getString(1) == epoch)
        }
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `typed_sync_outbox` (
                `sequence` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `opId` TEXT NOT NULL, `groupId` TEXT NOT NULL, `ownerId` TEXT NOT NULL,
                `epochId` TEXT NOT NULL, `deviceId` TEXT NOT NULL, `aggregateType` TEXT NOT NULL,
                `aggregateId` TEXT NOT NULL, `baseVersionsJson` TEXT NOT NULL,
                `commandType` TEXT NOT NULL, `schemaVersion` INTEGER NOT NULL,
                `payloadJson` TEXT NOT NULL, `state` TEXT NOT NULL,
                `serverResultJson` TEXT, `errorCode` TEXT, `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL)""",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_typed_sync_outbox_ownerId_epochId_deviceId_state_sequence` ON `typed_sync_outbox` (`ownerId`,`epochId`,`deviceId`,`state`,`sequence`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_typed_sync_outbox_opId` ON `typed_sync_outbox` (`opId`)")
    }
}

internal fun accountMutationAndFileImportMigration14To15(owner: String, epoch: String) = object : Migration(14, 15) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.query("SELECT ownerId,epochId FROM dataset_identity").use { cursor ->
            check(cursor.count == 1 && cursor.moveToFirst() && cursor.getString(0) == owner && cursor.getString(1) == epoch)
        }
        db.execSQL("CREATE TABLE IF NOT EXISTS `account_mutation_intent` (`opId` TEXT NOT NULL PRIMARY KEY, `ownerId` TEXT NOT NULL, `epochId` TEXT NOT NULL, `aggregateType` TEXT NOT NULL, `aggregateId` TEXT NOT NULL, `commandType` TEXT NOT NULL, `payloadJson` TEXT NOT NULL, `state` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `account_prediction` (`opId` TEXT NOT NULL PRIMARY KEY, `ownerId` TEXT NOT NULL, `epochId` TEXT NOT NULL, `aggregateType` TEXT NOT NULL, `aggregateId` TEXT NOT NULL, `payloadJson` TEXT NOT NULL, `active` INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `account_write_receipt` (`opId` TEXT NOT NULL PRIMARY KEY, `ownerId` TEXT NOT NULL, `epochId` TEXT NOT NULL, `status` TEXT NOT NULL, `resultJson` TEXT NOT NULL, `receivedAt` INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `account_epoch_archive` (`epochId` TEXT NOT NULL PRIMARY KEY, `ownerId` TEXT NOT NULL, `archivedAt` INTEGER NOT NULL, `reason` TEXT NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `account_applied_group` (`groupId` TEXT NOT NULL PRIMARY KEY, `ownerId` TEXT NOT NULL, `epochId` TEXT NOT NULL, `seq` INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `file_import_batch` (`id` TEXT NOT NULL PRIMARY KEY, `ownerId` TEXT NOT NULL, `epochId` TEXT NOT NULL, `fileDigest` TEXT NOT NULL, `formatVersion` TEXT NOT NULL, `state` TEXT NOT NULL, `rowCount` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `committedAt` INTEGER)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_file_import_batch_ownerId_epochId` ON `file_import_batch` (`ownerId`, `epochId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_file_import_batch_fileDigest` ON `file_import_batch` (`fileDigest`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `file_import_row` (`batchId` TEXT NOT NULL, `rowNumber` INTEGER NOT NULL, `payloadJson` TEXT NOT NULL, `contentFingerprint` TEXT NOT NULL, `externalId` TEXT, `recordId` TEXT, `state` TEXT NOT NULL, PRIMARY KEY(`batchId`, `rowNumber`), FOREIGN KEY(`batchId`) REFERENCES `file_import_batch`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_file_import_row_batchId` ON `file_import_row` (`batchId`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_file_import_row_recordId` ON `file_import_row` (`recordId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_file_import_row_contentFingerprint` ON `file_import_row` (`contentFingerprint`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_file_import_row_externalId` ON `file_import_row` (`externalId`)")
    }
}

internal fun snapshotSchemaVersion13To14(owner: String, epoch: String) = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.query("SELECT ownerId,epochId FROM dataset_identity").use { cursor ->
            check(cursor.count == 1 && cursor.moveToFirst() && cursor.getString(0) == owner && cursor.getString(1) == epoch)
        }
        db.execSQL("ALTER TABLE dataset_identity ADD COLUMN snapshotSchemaVersion INTEGER NOT NULL DEFAULT 0")
    }
}
