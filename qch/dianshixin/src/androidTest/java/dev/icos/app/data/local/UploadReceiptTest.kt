package dev.icos.app.data.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.icos.app.data.remote.AuthFailure
import dev.icos.app.data.remote.SnapshotProtocol
import dev.icos.app.data.remote.TypedSyncClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class UploadReceiptTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun withReplica(block: (DatasetDatabase, UUID, UUID) -> Unit) {
        val owner = UUID.randomUUID()
        val epoch = UUID.randomUUID()
        val directory = EncryptedDatasetStore.directory(context, owner, epoch)
        try {
            EncryptedDatasetStore.open(context, owner, epoch).use { db ->
                db.identity().update(db.identity().read()!!.copy(serverCursor = "before", lastAppliedSeq = 4, snapshotSchemaVersion = 1))
                SnapshotProtocol.tablesFor(1).forEach { table ->
                    db.openHelper.writableDatabase.execSQL("CREATE TABLE server_$table (id TEXT NOT NULL PRIMARY KEY, owner_id TEXT NOT NULL, epoch TEXT NOT NULL)")
                }
                block(db, owner, epoch)
            }
        } finally {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(EncryptedDatasetStore.alias(owner, epoch)) }
            directory.deleteRecursively()
            directory.parentFile?.delete()
        }
    }

    private fun command(db: DatasetDatabase, owner: UUID, epoch: UUID, kind: String = "record", id: UUID = UUID.randomUUID()): TypedOutboxEntity {
        val command = TypedOutboxEntity(
            opId = UUID.randomUUID().toString(), groupId = UUID.randomUUID().toString(),
            ownerId = owner.toString(), epochId = epoch.toString(), deviceId = UUID.randomUUID().toString(),
            aggregateType = kind, aggregateId = id.toString(), baseVersionsJson = "{}",
            commandType = if (kind == "record") "record.create" else "metadata.update", schemaVersion = 1,
            payloadJson = "{}", createdAt = 1,
        )
        db.typedOutbox().insert(command)
        db.accountMutations().insertIntent(AccountMutationIntentEntity(command.opId, command.ownerId, command.epochId,
            kind, command.aggregateId, command.commandType, "{}", "QUEUED", 1))
        db.accountMutations().insertPrediction(AccountPredictionEntity(command.opId, command.ownerId, command.epochId,
            kind, command.aggregateId, "{}", true))
        return command
    }

    private fun row(owner: UUID, epoch: UUID, id: UUID, revision: Long = 1) = JSONObject()
        .put("id", id.toString()).put("ownerId", owner.toString()).put("datasetEpoch", epoch.toString())
        .put("serverRevision", revision).put("deletedAt", JSONObject.NULL)

    private fun change(entity: String, row: JSONObject, operation: String = "UPSERT") = JSONObject()
        .put("entityType", entity).put("entityId", row.getString("id"))
        .put("revision", row.getLong("serverRevision")).put("operation", operation).put("payload", row)

    private fun result(command: TypedOutboxEntity, seq: Long, vararg changes: JSONObject): JSONObject {
        val versions = JSONObject()
        changes.forEach { versions.put("${it.getString("entityType")}:${it.getString("entityId")}", it.getLong("revision")) }
        return JSONObject().put("opId", command.opId).put("status", "APPLIED").put("seq", seq)
            .put("snapshotRequired", false).put("aggregateVersions", versions).put("changes", JSONArray(changes.toList()))
    }

    private fun apply(db: DatasetDatabase, command: TypedOutboxEntity, result: JSONObject) = SnapshotImporter.applyUploadResults(
        db, listOf(command), JSONObject().put("epoch", command.epochId).put("headSeq", result.getLong("seq")).put("results", JSONArray().put(result)),
    )

    private fun pull(db: DatasetDatabase, owner: UUID, epoch: UUID, seq: Long, group: String, vararg changes: JSONObject) {
        val rows = JSONArray()
        changes.forEachIndexed { index, change -> rows.put(JSONObject(change.toString()).put("seq", seq).put("groupId", group).put("ordinal", index)) }
        SnapshotImporter.applyPullMirror(db, TypedSyncClient.PullPage(epoch, rows, "after-$seq", false, seq), owner)
    }

    private fun assertCompleted(db: DatasetDatabase, command: TypedOutboxEntity) {
        assertEquals("PULLED", db.accountMutations().intent(command.opId)!!.state)
        assertNull(db.accountMutations().activePrediction(command.opId))
        assertNotNull(db.accountMutations().receipt(command.opId))
    }

    @Test
    fun completeRecordReceiptAndReplayPersistOneFinancialEffectWithoutMovingPullCursor() = withReplica { db, owner, epoch ->
        val record = UUID.randomUUID()
        val account = UUID.randomUUID()
        val ledger = UUID.randomUUID()
        val event = UUID.randomUUID()
        val posting = UUID.randomUUID()
        val command = command(db, owner, epoch, id = record)
        val receipt = result(command, 7,
            change("ledger", row(owner, epoch, ledger).put("name", "ledger")),
            change("account", row(owner, epoch, account).put("name", "cash")),
            change("financial_event", row(owner, epoch, event).put("ledgerId", ledger.toString())),
            change("record", row(owner, epoch, record).put("eventId", event.toString()).put("ledgerId", ledger.toString())),
            change("account_posting", row(owner, epoch, posting).put("eventId", event.toString()).put("accountId", account.toString())
                .put("signed", JSONObject().put("unscaled", "-1250").put("scale", 2).put("currency", "CNY"))),
        )
        apply(db, command, receipt)
        assertCompleted(db, command)
        assertEquals("before", db.identity().read()!!.serverCursor)
        assertEquals(4L, db.identity().read()!!.lastAppliedSeq)
        receipt.put("status", "ALREADY_APPLIED")
        apply(db, command, receipt)
        assertCompleted(db, command)
        db.openHelper.readableDatabase.query("SELECT COUNT(*),SUM(CAST(signed_unscaled AS INTEGER)) FROM server_account_postings").use { cursor ->
            assertTrue(cursor.moveToFirst()); assertEquals(1L, cursor.getLong(0)); assertEquals(-1250L, cursor.getLong(1))
        }
        val selected = SnapshotImporter.canonicalTables(db, setOf("accounts", "ledgers"))
        assertEquals(setOf("accounts", "ledgers"), selected.keys().asSequence().toSet())
    }

    @Test
    fun olderPullCannotDowngradeReceiptRowsOrResurrectPhysicalDeletionAndReplayAfterPullIsSafe() = withReplica { db, owner, epoch ->
        val account = UUID.randomUUID()
        val tag = UUID.randomUUID()
        val command = command(db, owner, epoch, "account", account)
        val updated = change("account", row(owner, epoch, account, 3).put("name", "new"))
        val deleted = change("tag", row(owner, epoch, tag, 3).put("deletedAt", "2026-01-01T00:00:00Z"), "DELETE")
        val receipt = result(command, 7, updated, deleted)
        apply(db, command, receipt)
        pull(db, owner, epoch, 5, UUID.randomUUID().toString(),
            change("account", row(owner, epoch, account, 2).put("name", "old")),
            change("tag", row(owner, epoch, tag, 2).put("name", "old tag")))
        db.openHelper.readableDatabase.query("SELECT name,server_revision FROM server_accounts").use { cursor ->
            assertTrue(cursor.moveToFirst()); assertEquals("new", cursor.getString(0)); assertEquals(3L, cursor.getLong(1))
        }
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM server_tags").use { cursor -> assertTrue(cursor.moveToFirst()); assertEquals(0L, cursor.getLong(0)) }
        pull(db, owner, epoch, 6, UUID.randomUUID().toString(), change("tag_group", row(owner, epoch, UUID.randomUUID())))
        pull(db, owner, epoch, 7, command.groupId, updated, deleted)
        receipt.put("status", "ALREADY_APPLIED")
        apply(db, command, receipt)
        assertCompleted(db, command)
        assertEquals(7L, db.identity().read()!!.lastAppliedSeq)
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM sync_receipt_effects").use { cursor -> assertTrue(cursor.moveToFirst()); assertEquals(0L, cursor.getLong(0)) }
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM server_tags").use { cursor -> assertTrue(cursor.moveToFirst()); assertEquals(0L, cursor.getLong(0)) }
    }

    @Test
    fun malformedIdentityRevisionGroupMoneyOrReferenceRollsBackRowsReceiptAndPredictionTogether() = withReplica { db, owner, epoch ->
        val command = command(db, owner, epoch)
        val first = change("record", row(owner, epoch, UUID.fromString(command.aggregateId)).put("remark", "must roll back"))
        val badRows = listOf(
            change("account", row(UUID.randomUUID(), epoch, UUID.randomUUID())),
            change("account", row(owner, UUID.randomUUID(), UUID.randomUUID())),
            change("account", row(owner, epoch, UUID.randomUUID()).put("serverRevision", 2)).put("revision", 1),
            change("account", row(owner, epoch, UUID.randomUUID())).put("groupId", UUID.randomUUID().toString()),
            change("account", row(owner, epoch, UUID.randomUUID())).put("revision", 1.5),
            change("account_posting", row(owner, epoch, UUID.randomUUID()).put("signed", JSONObject().put("unscaled", "9223372036854775808").put("scale", 0).put("currency", "CNY"))),
            change("record", row(owner, epoch, UUID.randomUUID()).put("ledgerId", UUID.randomUUID().toString())),
        )
        badRows.forEach { bad ->
            val failure = assertThrows(Exception::class.java) { apply(db, command, result(command, 7, first, bad)) }
            if (bad.getJSONObject("payload").has("ledgerId")) assertEquals("SNAPSHOT_REQUIRED", (failure as AuthFailure).code)
            assertEquals("QUEUED", db.typedOutbox().byOpId(command.opId)!!.state)
            assertEquals("QUEUED", db.accountMutations().intent(command.opId)!!.state)
            assertNotNull(db.accountMutations().activePrediction(command.opId))
            assertNull(db.accountMutations().receipt(command.opId))
            assertEquals("before", db.identity().read()!!.serverCursor)
            assertEquals(4L, db.identity().read()!!.lastAppliedSeq)
            db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM server_records").use { cursor -> assertTrue(cursor.moveToFirst()); assertEquals(0L, cursor.getLong(0)) }
        }
    }

    @Test
    fun boundedReceiptStaysPredictedUntilExplicitRecoveryOrCompleteReplay() = withReplica { db, owner, epoch ->
        val account = UUID.randomUUID()
        val command = command(db, owner, epoch, "account", account)
        val seq = 8L
        val control = JSONObject().put("entityType", "snapshot_control").put("entityId", command.opId).put("revision", 1)
            .put("operation", "SNAPSHOT_REQUIRED").put("payload", JSONObject().put("seq", seq).put("reason", "ACK_OVER_1_MIB")
                .put("affectedEntityCount", 1).put("affectedIdsSha256", "a".repeat(64))
                .put("capture", JSONObject().put("method", "POST").put("path", "/v1/sync/snapshots").put("reason", "SNAPSHOT_REQUIRED").put("knownHeadSeq", seq)))
        val bounded = result(command, seq).put("snapshotRequired", true).put("snapshotReason", "SNAPSHOT_REQUIRED")
            .put("requiredCapturedSeq", seq).put("changes", JSONArray().put(control))
        assertEquals("SNAPSHOT_REQUIRED", assertThrows(AuthFailure::class.java) { apply(db, command, bounded) }.code)
        assertEquals("APPLIED", db.typedOutbox().byOpId(command.opId)!!.state)
        assertEquals("SNAPSHOT_REQUIRED", db.typedOutbox().byOpId(command.opId)!!.errorCode)
        assertEquals("ACKED", db.accountMutations().intent(command.opId)!!.state)
        assertNotNull(db.accountMutations().activePrediction(command.opId))
        assertNotNull(db.accountMutations().receipt(command.opId))
        assertEquals("SNAPSHOT_REQUIRED", assertThrows(AuthFailure::class.java) { SnapshotImporter.applyAcknowledgedReceipts(db) }.code)
        apply(db, command, result(command, seq, change("account", row(owner, epoch, account).put("name", "canonical"))).put("status", "ALREADY_APPLIED"))
        assertCompleted(db, command)
        apply(db, command, bounded)
        assertCompleted(db, command)
        assertNull(db.typedOutbox().byOpId(command.opId)!!.errorCode)
        assertFalse(JSONObject(db.accountMutations().receipt(command.opId)!!.resultJson).getBoolean("snapshotRequired"))
    }

    @Test
    fun legacyCompleteAckIsMaterializedLocallyBeforeAnotherUploadRound() = withReplica { db, owner, epoch ->
        val account = UUID.randomUUID()
        val command = command(db, owner, epoch, "account", account)
        val receipt = result(command, 9, change("account", row(owner, epoch, account).put("name", "recovered")))
        db.typedOutbox().recordResult(command.opId, command.ownerId, command.epochId, command.deviceId, "APPLIED", receipt.toString(), null, 1)
        db.accountMutations().state(command.opId, "ACKED")
        assertEquals(1, SnapshotImporter.applyAcknowledgedReceipts(db))
        assertCompleted(db, command)
        assertEquals(0, SnapshotImporter.applyAcknowledgedReceipts(db))
        assertEquals(4L, db.identity().read()!!.lastAppliedSeq)
    }

    @Test
    fun offlineCategoryParentChildRenameAndRecordFreezeOnlyAfterCanonicalDependencies() = withReplica { db, owner, epoch ->
        val device = UUID.randomUUID()
        val ledgerId = UUID.randomUUID()
        val account = UUID.randomUUID()
        val seed = command(db, owner, epoch, "account", account)
        apply(db, seed, result(seed, 5,
            change("ledger", row(owner, epoch, ledgerId).put("name", "ledger").put("baseCurrency", "CNY")),
            change("account", row(owner, epoch, account).put("name", "cash").put("currency", "CNY").put("type", "ASSET"))))
        val ledger = FinanceLedger(ledgerId, "ledger", "CNY", 0, true, null, 1)
        val commands = AccountCategoryCommands(db)
        val rootId = commands.create(device, ledger, "A", dev.icos.app.domain.Direction.EXPENSE, null, excludeBudget = true)
        val root = CanonicalAccountRepository.readCategories(db, owner, epoch).single { it.id == rootId }
        val childId = commands.create(device, ledger, "B", dev.icos.app.domain.Direction.EXPENSE, root)
        val child = CanonicalAccountRepository.readCategories(db, owner, epoch).single { it.id == childId }
        commands.rename(device, child, "B renamed", ledger, root)
        val input = dev.icos.app.data.remote.RecordInputJson.decode(JSONObject().put("kind", "EXPENSE")
            .put("gross", JSONObject().put("unscaled", "1250").put("scale", 2).put("currency", "CNY"))
            .put("discount", JSONObject.NULL).put("countInStatistics", true).put("countInBudget", true)
            .put("allocations", JSONArray().put(JSONObject().put("accountId", account.toString())
                .put("amount", JSONObject().put("unscaled", "1250").put("scale", 2).put("currency", "CNY")))))
        AccountMutationRepository(db).saveRecord(device, UUID.randomUUID(), null, ledgerId, input,
            1770000000000, "UTC", "offline", childId, emptyList())
        val queued = db.typedOutbox().all(owner.toString(), epoch.toString()).filter { it.deviceId == device.toString() }
        assertEquals(4, queued.size)
        val store = TypedSyncStore(db)
        assertNull(store.freezeForSend(queued[1]))
        assertNull(store.freezeForSend(queued[2]))
        assertNull(store.freezeForSend(queued[3]))
        val projected = CanonicalAccountRepository.readCategoryProjection(db, owner, epoch)
        assertEquals("B renamed", projected.categories.single { it.id == childId }.name)
        assertEquals(0L, projected.categories.single { it.id == childId }.localRevision)
        val tables = SnapshotImporter.canonicalTables(db)
        CanonicalAccountRepository.applyPredictions(tables, db.accountMutations().predictions(owner.toString(), epoch.toString()),
            db.accountMutations().intentsInOrder(owner.toString(), epoch.toString()), owner, epoch)
        assertEquals("-1250", tables.getJSONArray("account_postings").getJSONObject(0).getJSONObject("signed").getString("unscaled"))
        assertEquals(0, tables.getJSONArray("budget_contributions").length())
        var seq = 6L
        queued.take(3).forEach { pending ->
            val frozen = store.freezeForSend(pending)!!
            val payload = JSONObject(frozen.payloadJson)
            val category = JSONObject((payload.optJSONObject("entity") ?: payload.getJSONObject("changes")).toString())
                .put("id", frozen.aggregateId).put("ownerId", owner.toString()).put("datasetEpoch", epoch.toString())
                .put("serverRevision", if (frozen.commandType == "metadata.create") 1 else 2).put("deletedAt", JSONObject.NULL)
            val receipt = result(frozen, seq++, change("category", category))
            apply(db, frozen, receipt)
            apply(db, frozen, receipt.put("status", "ALREADY_APPLIED"))
        }
        val frozenRecord = store.freezeForSend(queued[3])!!
        assertEquals(2L, JSONObject(frozenRecord.baseVersionsJson).getLong("category:$childId"))
        assertEquals(1L, JSONObject(frozenRecord.baseVersionsJson).getLong("category:$rootId"))
        db.openHelper.writableDatabase.execSQL("UPDATE server_categories SET server_revision=9 WHERE id='$childId'")
        assertEquals(frozenRecord, store.freezeForSend(queued[3]))
        assertEquals(2L, JSONObject(store.byOpId(frozenRecord.opId)!!.baseVersionsJson).getLong("category:$childId"))
    }

    @Test
    fun rejectedCategoryBlocksTransitiveChildrenAndRecordInsideReceiptTransaction() = withReplica { db, owner, epoch ->
        val device = UUID.randomUUID()
        val ledger = UUID.randomUUID()
        val store = TypedSyncStore(db)
        fun enqueue(kind: String, id: UUID, versions: JSONObject): TypedOutboxEntity {
            val op = UUID.randomUUID()
            val body = if (kind == "category") JSONObject().put("entity", JSONObject().put("ledgerId", ledger.toString())
                .put("name", "local").put("direction", "EXPENSE").put("excludeBudget", false)) else JSONObject()
            store.enqueue(TypedCommand(opId = op, ownerId = owner, epochId = epoch, deviceId = device, aggregateType = kind,
                aggregateId = id, baseVersionsJson = versions.toString(), commandType = if (kind == "category") "metadata.create" else "record.create", payloadJson = body.toString()))
            val row = store.byOpId(op.toString())!!
            db.accountMutations().insertIntent(AccountMutationIntentEntity(row.opId, row.ownerId, row.epochId, kind,
                row.aggregateId, row.commandType, row.payloadJson, "QUEUED", 1))
            db.accountMutations().insertPrediction(AccountPredictionEntity(row.opId, row.ownerId, row.epochId, kind, row.aggregateId, row.payloadJson, true))
            return row
        }
        val parent = enqueue("category", UUID.randomUUID(), JSONObject())
        val child = enqueue("category", UUID.randomUUID(), JSONObject().put("category:${parent.aggregateId}", -parent.sequence))
        val record = enqueue("record", UUID.randomUUID(), JSONObject().put("category:${child.aggregateId}", -child.sequence))
        val rejected = JSONObject().put("opId", parent.opId).put("status", "REJECTED")
            .put("error", JSONObject().put("code", "INVALID_REFERENCE"))
        SnapshotImporter.applyUploadResults(db, listOf(parent), JSONObject().put("epoch", epoch.toString()).put("headSeq", 4)
            .put("results", JSONArray().put(rejected)))
        listOf(child, record).forEach { row ->
            assertEquals("BLOCKED_DEPENDENCY", store.byOpId(row.opId)!!.state)
            assertEquals("BLOCKED_DEPENDENCY", db.accountMutations().intent(row.opId)!!.state)
            assertNull(db.accountMutations().activePrediction(row.opId))
            assertNull(store.freezeForSend(row))
        }
        assertEquals(3, db.accountMutations().retryCount(owner.toString(), epoch.toString()))
    }
}
