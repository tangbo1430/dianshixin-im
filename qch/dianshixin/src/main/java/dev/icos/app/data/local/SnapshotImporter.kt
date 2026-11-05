package dev.icos.app.data.local

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteStatement
import dev.icos.app.data.remote.AuthFailure
import dev.icos.app.data.remote.SnapshotProtocol
import dev.icos.app.data.remote.TypedSyncClient
import dev.icos.app.data.remote.PullGroupValidator
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal object SnapshotImporter {
    private const val MAX_IMPORT_CHUNK_BYTES = 1_048_576
    internal fun canonicalTables(database: DatasetDatabase, selectedTables: Set<String>? = null): JSONObject {
        val db = database.openHelper.readableDatabase
        val schema = database.identity().read()?.snapshotSchemaVersion ?: error("Dataset identity missing")
        return canonicalTables(db, schema, selectedTables)
    }

    internal fun canonicalTables(db: SupportSQLiteDatabase, schema: Int, selectedTables: Set<String>? = null): JSONObject =
        JSONObject().also { tables ->
            SnapshotProtocol.tablesFor(schema).filter { selectedTables == null || it in selectedTables }.sorted().forEach { table ->
                val rows = JSONArray()
                // ACKs can add columns; explicit names avoid SQLCipher's cached SELECT * metadata.
                val columns = tableColumns(db, table)
                db.query("SELECT ${columns.joinToString(",") { "`$it`" }} FROM `server_$table`").use { cursor ->
                    while (cursor.moveToNext()) rows.put(canonicalRow(cursor, columns))
                }
                tables.put(table, rows)
            }
            attachBudgetConfigs(tables)
        }

    private fun attachBudgetConfigs(tables: JSONObject) {
        val roots = tables.optJSONArray("budgets") ?: return
        val configs = tables.optJSONArray("budget_configs") ?: JSONArray()
        val entries = tables.optJSONArray("budget_entries") ?: JSONArray()
        for (i in 0 until roots.length()) {
            val root = roots.getJSONObject(i)
            if (root.optJSONArray("configs") != null) continue
            val rootId = root.getString("id")
            val nestedConfigs = JSONArray()
            for (j in 0 until configs.length()) {
                val config = configs.getJSONObject(j)
                if (config.optString("budgetId") != rootId) continue
                val nestedEntries = JSONArray()
                for (k in 0 until entries.length()) {
                    val entry = entries.getJSONObject(k)
                    if (entry.optString("configId") == config.optString("id")) {
                        nestedEntries.put(JSONObject().put("categoryId", entry.opt("categoryId") ?: JSONObject.NULL)
                            .put("amount", entry.getJSONObject("amount")))
                    }
                }
                val key = config.optString("periodKey")
                val scope = config.optString("scope")
                val projected = JSONObject(config.toString()).put("entries", nestedEntries)
                if (scope == "CURRENT") projected.put("periodStart", key)
                if (scope == "DEFAULT") projected.put("effectiveFrom", key)
                nestedConfigs.put(projected)
            }
            root.put("configs", nestedConfigs)
        }
    }

    fun importCanonicalMirror(database: DatasetDatabase, snapshot: SnapshotProtocol.VerifiedSnapshot, expectedOwner: UUID, expectedEpoch: UUID, cursor: String) {
        require(snapshot.ownerId == expectedOwner && snapshot.epoch == expectedEpoch)
        require(snapshot.cursor == cursor && cursor.isNotBlank())
        database.runInTransaction {
            check(snapshot.file.isFile && snapshot.file.length() > 0)
            check(snapshotFileSha256(snapshot.file) == snapshot.plainSha256)
            val db = database.openHelper.writableDatabase
            val identity = database.identity().read() ?: error("Dataset identity missing")
            check(identity.ownerId == expectedOwner.toString() && identity.epochId == expectedEpoch.toString())
            check(identity.lastAppliedSeq == 0L && identity.serverCursor == null)
            val tables = SnapshotProtocol.tablesFor(snapshot.schemaVersion).sorted()
            tables.forEach { table ->
                db.execSQL("DROP TABLE IF EXISTS `server_$table`")
                db.execSQL("CREATE TABLE `server_$table` (`id` TEXT NOT NULL PRIMARY KEY, `owner_id` TEXT NOT NULL, `epoch` TEXT NOT NULL)")
            }
            val counts = tables.associateWith { 0L }.toMutableMap()
            SnapshotProtocol.scanRows(snapshot) { table, row ->
                validateSnapshotRowIdentity(row, expectedOwner, expectedEpoch)
                check(db.query("SELECT 1 FROM `server_$table` WHERE id=?", arrayOf(row.getString("id"))).use { !it.moveToFirst() }) { "Duplicate canonical id" }
                if (snapshot.schemaVersion >= SnapshotProtocol.DATA5_SCHEMA) {
                    when (table) {
                        "import_batches" -> validateSnapshotImportBatch(row)
                        "import_rows" -> validateSnapshotImportRow(db, row)
                        "import_staging_chunks" -> validateSnapshotImportChunk(db, row)
                    }
                }
                upsertRow(db, table, row, expectedOwner, expectedEpoch)
                counts[table] = Math.addExact(counts[table] ?: 0L, 1L)
            }
            check(counts == snapshot.entityCounts)
            check(snapshotFileSha256(snapshot.file) == snapshot.plainSha256)
            validateSnapshotReferencesSql(db, snapshot.schemaVersion, expectedOwner, expectedEpoch)
            tables.forEach { table ->
                check(db.query("SELECT COUNT(*) FROM `server_$table`").use { it.moveToFirst() && it.getLong(0) == snapshot.entityCounts.getValue(table) })
            }
            database.identity().update(identity.copy(serverCursor = cursor, lastAppliedSeq = snapshot.capturedSeq, snapshotSchemaVersion = snapshot.schemaVersion))
        }
    }

    private fun snapshotFileSha256(file: java.io.File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun validateSnapshotImportBatch(row: JSONObject) {
        require(row.getString("status") in setOf("STAGING", "COMMITTED", "ROLLED_BACK", "CANCELLED"))
        require(row.getInt("formatVersion") > 0 && row.getInt("totalChunks") in 1..20_000 && row.getInt("totalRows") in 1..100_000)
        require(row.getString("source").length in 1..64 && row.getString("fileDigest").matches(Regex("[0-9a-fA-F]{64}")))
    }

    private fun validateSnapshotImportRow(db: SupportSQLiteDatabase, row: JSONObject) {
        val batchId = UUID.fromString(row.getString("batchId")).toString()
        val batch = db.query("SELECT status,total_rows FROM server_import_batches WHERE id=?", arrayOf(batchId)).use { c ->
            require(c.moveToFirst()); c.getString(0) to c.getInt(1)
        }
        require(row.getInt("rowIndex") in 0 until batch.second)
        require(row.getString("disposition") in setOf("PENDING", "DUPLICATE_SKIPPED"))
        val externalId = row.opt("externalId")
        require(externalId === JSONObject.NULL || externalId is String && externalId.length in 1..256)
        val payload = row.opt("payload")
        if (payload === JSONObject.NULL) require(batch.first == "CANCELLED" && row.has("deletedAt") && !row.isNull("deletedAt"))
        else {
            require(payload is JSONObject && payload.toString().toByteArray(Charsets.UTF_8).size <= MAX_IMPORT_CHUNK_BYTES)
            validateStagedRecordShape(payload)
        }
    }

    private fun validateSnapshotImportChunk(db: SupportSQLiteDatabase, row: JSONObject) {
        val batchId = UUID.fromString(row.getString("batchId")).toString()
        val batch = db.query("SELECT status,total_chunks,total_rows FROM server_import_batches WHERE id=?", arrayOf(batchId)).use { c ->
            require(c.moveToFirst()); Triple(c.getString(0), c.getInt(1), c.getInt(2))
        }
        require(row.getInt("chunkIndex") in 0 until batch.second)
        require(row.getString("digest").matches(Regex("[0-9a-fA-F]{64}")))
        val payload = row.opt("payload")
        if (payload === JSONObject.NULL) require(batch.first == "CANCELLED" && row.has("deletedAt") && !row.isNull("deletedAt") && row.getInt("rowCount") > 0)
        else {
            require(payload is JSONArray && payload.toString().toByteArray(Charsets.UTF_8).size <= MAX_IMPORT_CHUNK_BYTES)
            require(row.getInt("rowCount") == payload.length() && payload.length() > 0)
            payload.objects().forEach { staged ->
                require(staged.getInt("rowIndex") in 0 until batch.third)
                val externalId = staged.opt("externalId")
                require(externalId === JSONObject.NULL || externalId is String && externalId.length in 1..256)
                validateStagedRecordShape(staged.getJSONObject("record"))
            }
        }
    }

    private fun validateSnapshotReferencesSql(db: SupportSQLiteDatabase, schema: Int, owner: UUID, epoch: UUID) {
        val relationships = mutableMapOf(
            "categories" to listOf("ledgerId" to "ledgers", "parentId" to "categories"),
            "budgets" to listOf("ledgerId" to "ledgers"),
            "tags" to listOf("groupId" to "tag_groups"),
            "accounts" to listOf("groupId" to "account_groups", "openingEventId" to "financial_events"),
            "account_ledger_visibility" to listOf("accountId" to "accounts", "ledgerId" to "ledgers"),
            "records" to listOf("eventId" to "financial_events", "ledgerId" to "ledgers", "categoryId" to "categories", "parentRecordId" to "records"),
            "record_allocations" to listOf("recordId" to "records", "accountId" to "accounts", "fxSnapshotId" to "fx_snapshots"),
            "record_tags" to listOf("recordId" to "records", "tagId" to "tags"),
            "template_allocations" to listOf("templateId" to "record_templates", "accountId" to "accounts", "fxSnapshotId" to "fx_snapshots"),
            "template_tags" to listOf("templateId" to "record_templates", "tagId" to "tags"),
            "event_relations" to listOf("parentEventId" to "financial_events", "childEventId" to "financial_events"),
            "account_postings" to listOf("eventId" to "financial_events", "accountId" to "accounts", "reversalOf" to "account_postings"),
            "claim_items" to listOf("originRecordId" to "records"),
            "claim_settlements" to listOf("eventId" to "financial_events", "reversalEventId" to "financial_events", "ledgerId" to "ledgers", "accountId" to "accounts", "incomeCategoryId" to "categories"),
            "claim_allocations" to listOf("settlementId" to "claim_settlements", "claimItemId" to "claim_items", "expenseCategoryId" to "categories"),
            "record_segments" to listOf("originRecordId" to "records"),
            "refunds" to listOf("eventId" to "financial_events", "reversalEventId" to "financial_events", "ledgerId" to "ledgers", "originRecordId" to "records", "accountId" to "accounts", "expenseCategoryId" to "categories"),
            "saving_entries" to listOf("planId" to "saving_plans", "linkedRecordId" to "records", "linkedTransferEventId" to "financial_events"),
            "loan_plans" to listOf("accountId" to "accounts", "disbursementEventId" to "financial_events"),
            "loan_terms" to listOf("planId" to "loan_plans", "postedEventId" to "financial_events", "paidEventId" to "financial_events"),
            "loan_rate_changes" to listOf("planId" to "loan_plans"),
            "stat_contributions" to listOf("eventId" to "financial_events", "originRecordId" to "records", "ledgerId" to "ledgers", "categoryId" to "categories"),
            "budget_contributions" to listOf("eventId" to "financial_events", "originRecordId" to "records", "ledgerId" to "ledgers", "categoryId" to "categories"),
            "recurrence_rule_segments" to listOf("planId" to "recurrence_plans", "templateId" to "record_templates"),
            "recurrence_occurrences" to listOf("planId" to "recurrence_plans", "eventId" to "financial_events", "recordId" to "records"),
            "budget_configs" to listOf("budgetId" to "budgets"),
            "budget_entries" to listOf("configId" to "budget_configs", "categoryId" to "categories"),
            "credit_profiles" to listOf("accountId" to "accounts"),
            "credit_capacity_events" to listOf("accountId" to "accounts"),
            "installment_plans" to listOf("sourceRecordId" to "records", "accountId" to "accounts", "ledgerId" to "ledgers", "creationEventId" to "financial_events"),
            "installment_terms" to listOf("planId" to "installment_plans", "postingEventId" to "financial_events", "paidEventId" to "financial_events"),
            "credit_payment_allocations" to listOf("accountId" to "accounts", "paymentEventId" to "financial_events", "termId" to "installment_terms"),
        )
        if (schema >= SnapshotProtocol.DATA5_SCHEMA) {
            relationships["records"] = relationships.getValue("records") + ("importBatchId" to "import_batches")
            relationships["import_rows"] = listOf("batchId" to "import_batches")
            relationships["import_staging_chunks"] = listOf("batchId" to "import_batches")
        }
        if (schema >= SnapshotProtocol.DATA6_SCHEMA) {
            relationships["receivable_payable_operations"] = listOf("eventId" to "financial_events", "accountId" to "accounts", "cashAccountId" to "accounts")
        }
        relationships.forEach { (table, refs) -> if (table in SnapshotProtocol.tablesFor(schema)) refs.forEach { (field, target) ->
            val column = field.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").lowercase()
            val columns = tableColumns(db, table)
            if (table == "budgets") {
                val budgetCount = db.query("SELECT COUNT(*) FROM `server_budgets`").use { it.moveToFirst(); it.getLong(0) }
                if (budgetCount > 0L) {
                    check(column in columns) { "Canonical budget ledger reference is missing" }
                    check(db.query("SELECT COUNT(*) FROM `server_budgets` WHERE ledger_id IS NULL").use { it.moveToFirst() && it.getLong(0) == 0L }) { "Canonical budget ledger reference is missing" }
                }
            }
            if (column in columns) check(db.query("SELECT COUNT(*) FROM `server_$table` s LEFT JOIN `server_$target` t ON t.id=s.`$column` AND t.owner_id=? AND t.epoch=? WHERE s.`$column` IS NOT NULL AND t.id IS NULL", arrayOf(owner.toString(), epoch.toString())).use { it.moveToFirst() && it.getLong(0) == 0L }) { "Canonical reference is outside snapshot" }
        } }
        if (schema >= SnapshotProtocol.DATA5_SCHEMA) validateImportProofSql(db)
    }

    private fun validateImportProofSql(db: SupportSQLiteDatabase) {
        fun hasRows(table: String) = db.query("SELECT EXISTS (SELECT 1 FROM `server_$table`)").use { it.moveToFirst() && it.getInt(0) != 0 }
        if (hasRows("import_batches")) check(db.query("SELECT COUNT(*) FROM server_import_batches WHERE status NOT IN ('STAGING','COMMITTED','ROLLED_BACK','CANCELLED') OR format_version<=0 OR total_chunks NOT BETWEEN 1 AND 20000 OR total_rows NOT BETWEEN 1 AND 100000 OR length(source) NOT BETWEEN 1 AND 64 OR length(file_digest)<>64").use { it.moveToFirst() && it.getLong(0) == 0L })
        if (hasRows("import_rows")) {
            db.query("SELECT r.payload,r.deleted_at,b.status,r.row_index,b.total_rows,r.disposition,r.external_id FROM server_import_rows r JOIN server_import_batches b ON b.id=r.batch_id").use { c ->
                while (c.moveToNext()) {
                    val payload = c.getString(0)
                    if (payload == null) check(c.getString(1) != null && c.getString(2) == "CANCELLED")
                    else {
                        require(payload.toByteArray(Charsets.UTF_8).size <= MAX_IMPORT_CHUNK_BYTES)
                        val row = JSONObject(payload)
                        validateStagedRecordShape(row)
                        check(c.getInt(3) in 0 until c.getInt(4) && c.getString(5) in setOf("PENDING", "DUPLICATE_SKIPPED"))
                        val externalId = c.getString(6)
                        check(externalId == null || externalId.length in 1..256)
                    }
                }
            }
            check(db.query("SELECT COUNT(*) FROM (SELECT batch_id,row_index FROM server_import_rows GROUP BY batch_id,row_index HAVING COUNT(*)>1)").use { it.moveToFirst() && it.getLong(0) == 0L })
        }
        if (hasRows("import_staging_chunks")) {
            db.query("SELECT c.payload,c.deleted_at,b.status,c.chunk_index,b.total_chunks,c.row_count,c.digest FROM server_import_staging_chunks c JOIN server_import_batches b ON b.id=c.batch_id").use { c ->
                while (c.moveToNext()) {
                    check(c.getInt(3) in 0 until c.getInt(4) && c.getString(6).matches(Regex("[0-9a-fA-F]{64}")))
                    val payload = c.getString(0)
                    if (payload == null) check(c.getString(1) != null && c.getString(2) == "CANCELLED" && c.getInt(5) > 0)
                    else {
                        require(payload.toByteArray(Charsets.UTF_8).size <= MAX_IMPORT_CHUNK_BYTES)
                        val stagedRows = JSONArray(payload)
                        require(stagedRows.length() == c.getInt(5) && stagedRows.length() > 0)
                        stagedRows.objects().forEach { staged ->
                            val rowIndex = staged.getInt("rowIndex")
                            require(rowIndex >= 0)
                            validateStagedRecordShape(staged.getJSONObject("record"))
                        }
                    }
                }
            }
            check(db.query("SELECT COUNT(*) FROM (SELECT batch_id,chunk_index FROM server_import_staging_chunks GROUP BY batch_id,chunk_index HAVING COUNT(*)>1)").use { it.moveToFirst() && it.getLong(0) == 0L })
        }
    }

    private fun validateSnapshotReferences(tables: JSONObject, schema: Int, expectedOwner: UUID, expectedEpoch: UUID) {
        val ids = SnapshotProtocol.tablesFor(schema).associateWith { table ->
            val rows = tables.getJSONArray(table)
            buildSet {
                for (index in 0 until rows.length()) {
                    val row = rows.getJSONObject(index)
                    validateSnapshotRowIdentity(row, expectedOwner, expectedEpoch)
                    require(add(UUID.fromString(row.getString("id")).toString()))
                }
            }
        }
        validateSnapshotReferences(tables, schema, ids, includeImportReferences = schema >= SnapshotProtocol.DATA5_SCHEMA)
    }

    internal fun validateSnapshotReferences(tables: JSONObject, schema: Int, ids: Map<String, Set<String>>) =
        validateSnapshotReferences(tables, schema, ids, includeImportReferences = schema >= SnapshotProtocol.DATA5_SCHEMA)

    internal fun validateSnapshotReferences(
        tables: JSONObject,
        schema: Int,
        ids: Map<String, Set<String>>,
        includeImportReferences: Boolean,
    ) {
        val relationships = mutableMapOf(
            "categories" to listOf("ledgerId" to "ledgers", "parentId" to "categories"),
            "budgets" to listOf("ledgerId" to "ledgers"),
            "tags" to listOf("groupId" to "tag_groups"),
            "accounts" to listOf("groupId" to "account_groups", "openingEventId" to "financial_events"),
            "account_ledger_visibility" to listOf("accountId" to "accounts", "ledgerId" to "ledgers"),
            "records" to listOf("eventId" to "financial_events", "ledgerId" to "ledgers", "categoryId" to "categories", "parentRecordId" to "records"),
            "record_allocations" to listOf("recordId" to "records", "accountId" to "accounts", "fxSnapshotId" to "fx_snapshots"),
            "record_tags" to listOf("recordId" to "records", "tagId" to "tags"),
            "template_allocations" to listOf("templateId" to "record_templates", "accountId" to "accounts", "fxSnapshotId" to "fx_snapshots"),
            "template_tags" to listOf("templateId" to "record_templates", "tagId" to "tags"),
            "event_relations" to listOf("parentEventId" to "financial_events", "childEventId" to "financial_events"),
            "account_postings" to listOf("eventId" to "financial_events", "accountId" to "accounts", "reversalOf" to "account_postings"),
            "claim_items" to listOf("originRecordId" to "records"),
            "claim_settlements" to listOf("eventId" to "financial_events", "reversalEventId" to "financial_events", "ledgerId" to "ledgers", "accountId" to "accounts", "incomeCategoryId" to "categories"),
            "claim_allocations" to listOf("settlementId" to "claim_settlements", "claimItemId" to "claim_items", "expenseCategoryId" to "categories"),
            "record_segments" to listOf("originRecordId" to "records"),
            "refunds" to listOf("eventId" to "financial_events", "reversalEventId" to "financial_events", "ledgerId" to "ledgers", "originRecordId" to "records", "accountId" to "accounts", "expenseCategoryId" to "categories"),
            "saving_entries" to listOf("planId" to "saving_plans", "linkedRecordId" to "records", "linkedTransferEventId" to "financial_events"),
            "loan_plans" to listOf("accountId" to "accounts", "disbursementEventId" to "financial_events"),
            "loan_terms" to listOf("planId" to "loan_plans", "postedEventId" to "financial_events", "paidEventId" to "financial_events"),
            "loan_rate_changes" to listOf("planId" to "loan_plans"),
            "stat_contributions" to listOf("eventId" to "financial_events", "originRecordId" to "records", "ledgerId" to "ledgers", "categoryId" to "categories"),
            "budget_contributions" to listOf("eventId" to "financial_events", "originRecordId" to "records", "ledgerId" to "ledgers", "categoryId" to "categories"),
            "recurrence_rule_segments" to listOf("planId" to "recurrence_plans", "templateId" to "record_templates"),
            "recurrence_occurrences" to listOf("planId" to "recurrence_plans", "eventId" to "financial_events", "recordId" to "records"),
            "budget_configs" to listOf("budgetId" to "budgets"),
            "budget_entries" to listOf("configId" to "budget_configs", "categoryId" to "categories"),
            "credit_profiles" to listOf("accountId" to "accounts"),
            "credit_capacity_events" to listOf("accountId" to "accounts"),
            "installment_plans" to listOf("sourceRecordId" to "records", "accountId" to "accounts", "ledgerId" to "ledgers", "creationEventId" to "financial_events"),
            "installment_terms" to listOf("planId" to "installment_plans", "postingEventId" to "financial_events", "paidEventId" to "financial_events"),
            "credit_payment_allocations" to listOf("accountId" to "accounts", "paymentEventId" to "financial_events", "termId" to "installment_terms"),
        )
        if (schema >= SnapshotProtocol.DATA5_SCHEMA && includeImportReferences) {
            relationships["records"] = relationships.getValue("records") + listOf("importBatchId" to "import_batches")
            relationships["import_rows"] = listOf("batchId" to "import_batches")
            relationships["import_staging_chunks"] = listOf("batchId" to "import_batches")
        }
        if (schema >= SnapshotProtocol.DATA6_SCHEMA) {
            relationships["receivable_payable_operations"] = listOf("eventId" to "financial_events", "accountId" to "accounts", "cashAccountId" to "accounts")
        }
        relationships.filterKeys { it in ids }.forEach { (table, refs) -> validateSnapshotRelationships(tables, ids, table, refs) }
        if (schema >= SnapshotProtocol.DATA5_SCHEMA && includeImportReferences) {
            validateImportRows(
                tables.getJSONArray("import_batches"), tables.getJSONArray("import_rows"),
                tables.getJSONArray("import_staging_chunks"),
            )
        }
    }

    internal fun validateImportRows(
        batches: JSONArray,
        rows: JSONArray,
        chunks: JSONArray,
    ) {
        val batchesById = batches.objects().associateBy { UUID.fromString(it.getString("id")).toString() }
        require(batchesById.size == batches.length())
        batchesById.values.forEach { batch ->
            require(batch.getString("status") in setOf("STAGING", "COMMITTED", "ROLLED_BACK", "CANCELLED"))
            require(batch.getInt("formatVersion") > 0 && batch.getInt("totalChunks") in 1..20_000 && batch.getInt("totalRows") in 1..100_000)
            require(batch.getString("source").length in 1..64 && batch.getString("fileDigest").matches(Regex("[0-9a-fA-F]{64}")))
        }
        val rowIndexes = HashSet<Pair<String, Int>>()
        rows.objects().forEach { row ->
            val batchId = UUID.fromString(row.getString("batchId")).toString()
            val batch = batchesById[batchId]
            require(batch != null) { "Import row batch is outside snapshot" }
            val rowIndex = row.getInt("rowIndex")
            require(rowIndex in 0 until batch.getInt("totalRows") && rowIndexes.add(batchId to rowIndex))
            require(row.getString("disposition") in setOf("PENDING", "DUPLICATE_SKIPPED"))
            val externalId = row.opt("externalId")
            require(externalId === JSONObject.NULL || externalId is String && externalId.length in 1..256)
            val payload = row.opt("payload")
            if (payload === JSONObject.NULL) {
                require(batch.getString("status") == "CANCELLED" && row.has("deletedAt") && !row.isNull("deletedAt"))
            } else {
                require(payload is JSONObject && payload.toString().toByteArray(Charsets.UTF_8).size <= MAX_IMPORT_CHUNK_BYTES)
                validateStagedRecordShape(payload)
            }
        }
        val chunkIndexes = HashSet<Pair<String, Int>>()
        chunks.objects().forEach { chunk ->
            val batchId = UUID.fromString(chunk.getString("batchId")).toString()
            val batch = batchesById[batchId]
            require(batch != null) { "Import chunk batch is outside snapshot" }
            val chunkIndex = chunk.getInt("chunkIndex")
            require(chunkIndex in 0 until batch.getInt("totalChunks") && chunkIndexes.add(batchId to chunkIndex))
            val payload = chunk.opt("payload")
            if (payload === JSONObject.NULL) {
                require(batch.getString("status") == "CANCELLED" && chunk.has("deletedAt") && !chunk.isNull("deletedAt"))
                require(chunk.getInt("rowCount") > 0)
            } else {
                require(payload is JSONArray && payload.toString().toByteArray(Charsets.UTF_8).size <= MAX_IMPORT_CHUNK_BYTES)
                require(chunk.getInt("chunkIndex") in 0 until batch.getInt("totalChunks") &&
                    chunk.getInt("rowCount") == payload.length() && payload.length() > 0)
                payload.objects().forEach { stagedRow ->
                    require(stagedRow.getInt("rowIndex") in 0 until batch.getInt("totalRows"))
                    val externalId = stagedRow.opt("externalId")
                    require(externalId === JSONObject.NULL || externalId is String && externalId.length in 1..256)
                    validateStagedRecordShape(stagedRow.getJSONObject("record"))
                }
            }
            require(chunk.getString("digest").matches(Regex("[0-9a-fA-F]{64}")))
        }
    }

    private fun validateSnapshotRelationships(tables: JSONObject, ids: Map<String, Set<String>>, table: String, refs: List<Pair<String, String>>) {
        if (table !in ids) return
        for (index in 0 until tables.getJSONArray(table).length()) {
            val row = tables.getJSONArray(table).getJSONObject(index)
            if (table == "budgets") require(row.has("ledgerId") && !row.isNull("ledgerId")) { "Canonical budget ledger reference is missing" }
            refs.forEach { (field, target) -> if (row.has(field) && !row.isNull(field)) {
                val id = UUID.fromString(row.getString(field)).toString()
                require(id in ids.getValue(target)) { "Canonical reference is outside snapshot" }
            } }
        }
    }

    private fun JSONArray.objects() = List(length()) { getJSONObject(it) }

    internal fun validateSnapshotRowIdentity(row: JSONObject, expectedOwner: UUID, expectedEpoch: UUID) {
        require(row.getString("ownerId") == expectedOwner.toString() && row.getString("datasetEpoch") == expectedEpoch.toString())
        require(UUID.fromString(row.getString("id")).toString() == row.getString("id"))
    }

    private fun validateStagedRecordShape(record: JSONObject) {
        val uuid = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
        fun optionalUuid(field: String) {
            val value = record.opt(field)
            require(value === JSONObject.NULL || value is String && uuid.matches(value))
        }
        require(uuid.matches(record.getString("ledgerId")))
        optionalUuid("categoryId")
        optionalUuid("feeCategoryId")
        optionalUuid("bonusCategoryId")
        val tags = record.optJSONArray("tagIds") ?: JSONArray()
        require(tags.length() <= 1000)
        for (index in 0 until tags.length()) require(uuid.matches(tags.getString(index)))
        val attachments = record.optJSONArray("attachmentIds") ?: JSONArray()
        require(attachments.length() <= 1000)
        for (index in 0 until attachments.length()) require(uuid.matches(attachments.getString(index)))
        val remark = record.getString("remark")
        val occurredAt = record.getString("occurredAt")
        val localDate = record.getString("localDate")
        val timezone = record.getString("timezone")
        require(remark.length <= MAX_IMPORT_CHUNK_BYTES && occurredAt.length <= MAX_IMPORT_CHUNK_BYTES &&
            localDate.length <= MAX_IMPORT_CHUNK_BYTES && timezone.length <= MAX_IMPORT_CHUNK_BYTES)
        val input = record.getJSONObject("input")
        val kind = input.getString("kind")
        val allocations = when (kind) {
            "EXPENSE", "INCOME" -> input.getJSONArray("allocations").also { require(it.length() in 1..1000) }.objects()
            "TRANSFER" -> listOfNotNull(input.optJSONObject("from"), input.optJSONObject("to"), input.optJSONObject("fee"), input.optJSONObject("bonus"))
            else -> error("Unsupported imported record kind")
        }
        allocations.forEach { allocation -> require(uuid.matches(allocation.getString("accountId"))) }
        require(record.opt("templateId") == null || record.isNull("templateId") || uuid.matches(record.getString("templateId")))
    }

    internal fun applyAcknowledgedReceipts(database: DatasetDatabase): Int {
        val identity = database.identity().read() ?: error("Dataset identity missing")
        val commands = database.typedOutbox().acknowledgedPendingCanonical(identity.ownerId, identity.epochId)
        if (commands.isEmpty()) return 0
        val results = JSONArray()
        var head = identity.lastAppliedSeq
        commands.forEach { command ->
            val result = JSONObject(command.serverResultJson ?: database.accountMutations().receipt(command.opId)?.resultJson
                ?: throw AuthFailure("INVALID_RESPONSE", retryable = true))
            head = maxOf(head, integer(result, "seq"))
            results.put(result)
        }
        return applyUploadResults(database, commands, JSONObject().put("epoch", identity.epochId).put("headSeq", head).put("results", results))
    }

    /** Upload receipts are sparse canonical groups, never a contiguous pull watermark. */
    internal fun applyUploadResults(database: DatasetDatabase, commands: List<TypedOutboxEntity>, response: JSONObject): Int {
        if (commands.isEmpty()) return 0
        var snapshotRequired = false
        database.runInTransaction {
            val identity = database.identity().read() ?: error("Dataset identity missing")
            val owner = UUID.fromString(identity.ownerId)
            val epoch = UUID.fromString(identity.epochId)
            if (response.getString("epoch") != identity.epochId) throw AuthFailure("DATASET_EPOCH_CHANGED")
            val head = integer(response, "headSeq")
            require(head >= 0)
            val results = response.getJSONArray("results")
            require(results.length() == commands.size && commands.map { it.opId }.toSet().size == commands.size &&
                commands.map { it.groupId }.toSet().size == commands.size)
            val indexed = LinkedHashMap<String, JSONObject>()
            for (index in 0 until results.length()) {
                val result = results.getJSONObject(index)
                require(indexed.put(result.getString("opId"), result) == null)
            }
            require(indexed.keys == commands.map { it.opId }.toSet())
            val mutations = database.accountMutations()
            val outbox = database.typedOutbox()
            val db = database.openHelper.writableDatabase
            commands.forEach { command ->
                require(command.ownerId == identity.ownerId && command.epochId == identity.epochId)
                require(UUID.fromString(command.opId).toString() == command.opId && UUID.fromString(command.groupId).toString() == command.groupId)
                require(UUID.fromString(command.opId) != UUID(0, 0) && UUID.fromString(command.groupId) != UUID(0, 0))
                require(outbox.byGroupId(command.groupId)?.opId == command.opId)
                val frozen = outbox.byOpId(command.opId) ?: error("Frozen command missing")
                require(frozen.groupId == command.groupId && frozen.ownerId == command.ownerId && frozen.epochId == command.epochId &&
                    frozen.deviceId == command.deviceId && frozen.aggregateType == command.aggregateType && frozen.aggregateId == command.aggregateId &&
                    frozen.baseVersionsJson == command.baseVersionsJson && frozen.commandType == command.commandType &&
                    frozen.schemaVersion == command.schemaVersion && frozen.payloadJson == command.payloadJson)
                val intent = mutations.intent(command.opId)
                if (intent != null) require(intent.ownerId == command.ownerId && intent.epochId == command.epochId &&
                    intent.aggregateType == command.aggregateType && intent.aggregateId == command.aggregateId &&
                    intent.commandType == command.commandType && intent.payloadJson == command.payloadJson)
                mutations.activePrediction(command.opId)?.let { prediction ->
                    require(prediction.ownerId == command.ownerId && prediction.epochId == command.epochId &&
                        prediction.aggregateType == command.aggregateType && prediction.aggregateId == command.aggregateId)
                }
                mutations.receipt(command.opId)?.let { require(it.ownerId == command.ownerId && it.epochId == command.epochId) }
                val result = indexed.getValue(command.opId)
                if (result.has("groupId")) require(result.getString("groupId") == command.groupId)
                val status = TypedOutboxState.valueOf(result.getString("status"))
                require(status in setOf(TypedOutboxState.APPLIED, TypedOutboxState.ALREADY_APPLIED, TypedOutboxState.CONFLICT,
                    TypedOutboxState.REJECTED, TypedOutboxState.BLOCKED_DEPENDENCY))
                val applied = status == TypedOutboxState.APPLIED || status == TypedOutboxState.ALREADY_APPLIED
                val bounded = result.opt("snapshotRequired") == true
                if (applied || bounded) {
                    require(result.opt("snapshotRequired") is Boolean)
                    val seq = integer(result, "seq")
                    require(seq in 1..head)
                    mutations.appliedGroup(command.groupId)?.let { group ->
                        require(group.ownerId == command.ownerId && group.epochId == command.epochId && group.seq == seq)
                    }
                    mutations.receipt(command.opId)?.let { previous ->
                        val previousResult = JSONObject(previous.resultJson)
                        if (previous.status in setOf("APPLIED", "ALREADY_APPLIED")) require(integer(previousResult, "seq") == seq)
                    }
                    if (bounded) {
                        validateBoundedReceipt(result, command, seq)
                        // A later bounded replay must not undo an already materialized receipt or snapshot.
                        if (mutations.isApplied(command.groupId, command.ownerId, command.epochId) ||
                            intent?.state == "PULLED" && seq <= identity.lastAppliedSeq) return@forEach
                        snapshotRequired = true
                    } else {
                        val changes = uploadChanges(result, command, seq, identity.snapshotSchemaVersion)
                        applyCanonicalChanges(db, changes, owner, epoch, identity.snapshotSchemaVersion, receipt = true,
                            coveredSeq = identity.lastAppliedSeq)
                        mutations.insertApplied(AccountAppliedGroupEntity(command.groupId, command.ownerId, command.epochId, seq))
                    }
                }
                val now = System.currentTimeMillis()
                val json = result.toString()
                val state = if (bounded && !applied) "AMBIGUOUS" else status.name
                check(outbox.recordResult(command.opId, command.ownerId, command.epochId, command.deviceId, state, json,
                    if (bounded) "SNAPSHOT_REQUIRED" else result.optJSONObject("error")?.optString("code"), now) == 1)
                mutations.upsertReceipt(AccountWriteReceiptEntity(command.opId, command.ownerId, command.epochId, status.name, json, now))
                if (intent != null) {
                    mutations.state(command.opId, when {
                        bounded && applied -> "ACKED"
                        bounded -> "AMBIGUOUS"
                        applied -> "PULLED"
                        else -> status.name
                    })
                }
                if (!bounded) mutations.deactivatePrediction(command.opId)
            }
            TypedSyncStore.blockFailedDependencies(database)
        }
        if (snapshotRequired) throw AuthFailure("SNAPSHOT_REQUIRED")
        return commands.size
    }

    private fun integer(row: JSONObject, key: String): Long {
        val value = row.get(key)
        require(value is Int || value is Long) { "Invalid canonical integer: $key" }
        return (value as Number).toLong()
    }

    private fun validateBoundedReceipt(result: JSONObject, command: TypedOutboxEntity, seq: Long) {
        require(integer(result, "requiredCapturedSeq") == seq && result.getString("snapshotReason") == "SNAPSHOT_REQUIRED")
        require(result.getJSONObject("aggregateVersions").length() == 0)
        val changes = result.getJSONArray("changes")
        require(changes.length() == 1)
        val control = changes.getJSONObject(0)
        require(control.getString("entityType") == "snapshot_control" && control.getString("operation") == "SNAPSHOT_REQUIRED" &&
            control.getString("entityId") in setOf(command.opId, command.groupId) && integer(control, "revision") == 1L)
        val payload = control.getJSONObject("payload")
        require(integer(payload, "seq") == seq && integer(payload, "affectedEntityCount") >= 0 &&
            payload.getString("affectedIdsSha256").matches(Regex("[0-9a-f]{64}")))
        val capture = payload.getJSONObject("capture")
        require(capture.getString("method") == "POST" && capture.getString("path") == "/v1/sync/snapshots" &&
            capture.getString("reason") == "SNAPSHOT_REQUIRED" && integer(capture, "knownHeadSeq") == seq)
    }

    private fun uploadChanges(result: JSONObject, command: TypedOutboxEntity, seq: Long, schema: Int): JSONArray {
        val versions = result.getJSONObject("aggregateVersions")
        versions.keys().asSequence().forEach { key ->
            val parts = key.split(':')
            require(parts.size == 2 && UUID.fromString(parts[1]).toString() == parts[1])
            tableForEntity(parts[0], schema)
            require(integer(versions, key) >= 0)
        }
        val source = result.getJSONArray("changes")
        val changes = JSONArray()
        val seen = HashSet<String>()
        for (index in 0 until source.length()) {
            val change = source.getJSONObject(index)
            val key = "${change.getString("entityType")}:${change.getString("entityId")}"
            require(seen.add(key) && integer(versions, key) == integer(change, "revision"))
            if (change.has("seq")) require(integer(change, "seq") == seq)
            if (change.has("groupId")) require(change.getString("groupId") == command.groupId)
            if (change.has("ordinal")) require(integer(change, "ordinal") == index.toLong())
            changes.put(JSONObject(change.toString()).put("seq", seq).put("groupId", command.groupId).put("ordinal", index))
        }
        if (command.aggregateType != "record_batch") {
            val root = "${command.aggregateType}:${command.aggregateId}"
            val base = JSONObject(command.baseVersionsJson)
            val expected = if (base.has(root)) integer(base, root) else 0L
            val revision = integer(versions, root)
            require(revision >= expected)
            require(revision == expected || root in seen) { "Canonical aggregate effect is missing" }
            if (command.commandType.endsWith(".create")) require(root in seen && revision > 0)
        }
        PullGroupValidator.validate(changes)
        return changes
    }

    private fun applyCanonicalChanges(
        db: SupportSQLiteDatabase,
        changes: JSONArray,
        owner: UUID,
        epoch: UUID,
        schema: Int,
        receipt: Boolean = false,
        coveredSeq: Long = 0,
    ) {
        // Like server_* mirrors, this index belongs to the dynamic canonical replica rather than Room's local schema.
        // Retain physical-deletion effects until the contiguous pull cursor has crossed them.
        db.execSQL("CREATE TABLE IF NOT EXISTS sync_receipt_effects (owner_id TEXT NOT NULL, epoch TEXT NOT NULL, entity_table TEXT NOT NULL, entity_id TEXT NOT NULL, seq INTEGER NOT NULL, revision INTEGER NOT NULL, PRIMARY KEY(owner_id,epoch,entity_table,entity_id))")
        val upserts = ArrayList<Pair<String, JSONObject>>()
        for (index in 0 until changes.length()) {
            val change = changes.getJSONObject(index)
            val table = tableForEntity(change.getString("entityType"), schema)
            val payload = change.getJSONObject("payload")
            val id = change.getString("entityId")
            val revision = integer(change, "revision")
            val seq = integer(change, "seq")
            require(revision > 0)
            require(payload.getString("id") == id && UUID.fromString(id).toString() == id)
            require(payload.getString("ownerId") == owner.toString() && payload.getString("datasetEpoch") == epoch.toString())
            require(integer(payload, "serverRevision") == revision)
            val deleted = change.getString("operation") == "DELETE"
            if (deleted) require(payload.opt("deletedAt") is String && payload.getString("deletedAt").isNotBlank())
            else require(change.getString("operation") == "UPSERT" && (!payload.has("deletedAt") || payload.isNull("deletedAt")))
            validateCanonicalMoney(payload)
            if (receipt && seq <= coveredSeq) continue
            val newerReceipt = db.query("SELECT seq,revision FROM sync_receipt_effects WHERE owner_id=? AND epoch=? AND entity_table=? AND entity_id=?",
                arrayOf(owner.toString(), epoch.toString(), table, id)).use { cursor ->
                cursor.moveToFirst() && (cursor.getLong(0) >= seq || cursor.getLong(1) > revision)
            }
            if (newerReceipt) continue
            val columns = tableColumns(db, table).toSet()
            if ("server_revision" in columns) {
                val newerRow = db.query("SELECT server_revision FROM `server_$table` WHERE id=? AND owner_id=? AND epoch=?",
                    arrayOf(id, owner.toString(), epoch.toString())).use { cursor -> cursor.moveToFirst() && cursor.getLong(0) >= revision }
                if (newerRow) continue
            }
            if (deleted) {
                if ("deleted_at" in columns) {
                    db.execSQL("UPDATE `server_$table` SET deleted_at=?, server_revision=? WHERE id=? AND owner_id=? AND epoch=?",
                        arrayOf(payload.getString("deletedAt"), revision, id, owner.toString(), epoch.toString()))
                    if (table == "accounts") {
                        val retained = db.query("SELECT 1 FROM server_accounts WHERE id=? AND owner_id=? AND epoch=?",
                            arrayOf(id, owner.toString(), epoch.toString())).use { it.moveToFirst() }
                        if (!retained) upsertRow(db, table, payload, owner, epoch)
                    }
                } else if (table == "accounts") {
                    upsertRow(db, table, payload, owner, epoch)
                } else {
                    db.execSQL("DELETE FROM `server_$table` WHERE id=? AND owner_id=? AND epoch=?", arrayOf(id, owner.toString(), epoch.toString()))
                }
            } else {
                upsertRow(db, table, payload, owner, epoch)
                upserts += table to payload
            }
            if (receipt) db.execSQL("INSERT OR REPLACE INTO sync_receipt_effects(owner_id,epoch,entity_table,entity_id,seq,revision) VALUES(?,?,?,?,?,?)",
                arrayOf<Any>(owner.toString(), epoch.toString(), table, id, seq, revision))
        }
        upserts.forEach { (table, row) -> validatePullReferences(db, table, row, owner, epoch, schema, receipt) }
    }

    private fun validateCanonicalMoney(value: Any) {
        when (value) {
            is JSONObject -> {
                if (value.has("unscaled")) {
                    require(isMoney(value) && value.get("unscaled") is String)
                    val scale = integer(value, "scale")
                    require(scale in 0..6)
                    dev.icos.app.domain.Money.fromWire(value.getString("unscaled"), scale.toInt(), value.getString("currency"))
                } else value.keys().asSequence().forEach { validateCanonicalMoney(value.get(it)) }
            }
            is JSONArray -> for (index in 0 until value.length()) validateCanonicalMoney(value.get(index))
        }
    }

    fun applyPullMirror(database: DatasetDatabase, page: TypedSyncClient.PullPage, expectedOwner: UUID) {
        database.runInTransaction {
            val identity = database.identity().read() ?: error("Dataset identity missing")
            check(identity.ownerId == expectedOwner.toString() && identity.epochId == page.epoch.toString() && identity.serverCursor != null)
            PullGroupValidator.validate(page.changes, identity.lastAppliedSeq)
            val changes = page.changes
            val highestSeq = if (changes.length() == 0) identity.lastAppliedSeq else changes.getJSONObject(changes.length() - 1).getLong("seq")
            val db = database.openHelper.writableDatabase
            applyCanonicalChanges(db, changes, expectedOwner, page.epoch, identity.snapshotSchemaVersion)
            val grouped = List(changes.length()) { index -> changes.getJSONObject(index) }.groupBy { it.getLong("seq") }
            grouped.forEach { (seq, group) ->
                val groupId = group.first().getString("groupId")
                val matching = database.typedOutbox().byGroupId(groupId)
                if (matching != null) {
                    database.typedOutbox().markCanonical(groupId, expectedOwner.toString(), page.epoch.toString(), System.currentTimeMillis())
                    database.accountMutations().deactivatePrediction(matching.opId)
                    database.accountMutations().state(matching.opId, "PULLED")
                }
                database.accountMutations().insertApplied(AccountAppliedGroupEntity(groupId, expectedOwner.toString(), page.epoch.toString(), seq))
            }
            db.execSQL("DELETE FROM sync_receipt_effects WHERE owner_id=? AND epoch=? AND seq<=?",
                arrayOf<Any>(expectedOwner.toString(), page.epoch.toString(), highestSeq))
            database.identity().update(identity.copy(serverCursor = page.nextCursor, lastAppliedSeq = highestSeq))
        }
    }

    private fun validatePullReferences(db: SupportSQLiteDatabase, table: String, row: JSONObject, owner: UUID, epoch: UUID, schema: Int, receipt: Boolean = false) {
        val refs = when (table) {
            "ledgers", "tag_groups", "account_groups", "business_preferences", "fx_snapshots", "import_batches", "saving_plans" -> emptyList()
            "financial_events" -> listOf("ledgerId" to "ledgers", "reversalOf" to "financial_events")
            "record_templates" -> listOf("ledgerId" to "ledgers", "categoryId" to "categories", "feeCategoryId" to "categories", "bonusCategoryId" to "categories")
            "recurrence_plans" -> listOf("templateId" to "record_templates")
            "receivable_payable_operations" -> if (schema >= SnapshotProtocol.DATA6_SCHEMA) listOf("eventId" to "financial_events", "accountId" to "accounts", "cashAccountId" to "accounts") else error("Unsupported canonical table")
            "categories" -> listOf("ledgerId" to "ledgers", "parentId" to "categories")
            "budgets" -> listOf("ledgerId" to "ledgers")
            "tags" -> listOf("groupId" to "tag_groups")
            "accounts" -> listOf("groupId" to "account_groups", "openingEventId" to "financial_events")
            "account_ledger_visibility" -> listOf("accountId" to "accounts", "ledgerId" to "ledgers")
            "records" -> listOf("eventId" to "financial_events", "ledgerId" to "ledgers", "categoryId" to "categories", "parentRecordId" to "records") + if (schema >= SnapshotProtocol.DATA5_SCHEMA) listOf("importBatchId" to "import_batches") else emptyList()
            "record_allocations" -> listOf("recordId" to "records", "accountId" to "accounts", "fxSnapshotId" to "fx_snapshots")
            "record_tags" -> listOf("recordId" to "records", "tagId" to "tags")
            "template_allocations" -> listOf("templateId" to "record_templates", "accountId" to "accounts", "fxSnapshotId" to "fx_snapshots")
            "template_tags" -> listOf("templateId" to "record_templates", "tagId" to "tags")
            "event_relations" -> listOf("parentEventId" to "financial_events", "childEventId" to "financial_events")
            "account_postings" -> listOf("eventId" to "financial_events", "accountId" to "accounts", "reversalOf" to "account_postings")
            "claim_items" -> listOf("originRecordId" to "records")
            "claim_settlements" -> listOf("eventId" to "financial_events", "reversalEventId" to "financial_events", "ledgerId" to "ledgers", "accountId" to "accounts", "incomeCategoryId" to "categories")
            "claim_allocations" -> listOf("settlementId" to "claim_settlements", "claimItemId" to "claim_items", "expenseCategoryId" to "categories")
            "record_segments" -> listOf("originRecordId" to "records")
            "refunds" -> listOf("eventId" to "financial_events", "reversalEventId" to "financial_events", "ledgerId" to "ledgers", "originRecordId" to "records", "accountId" to "accounts", "expenseCategoryId" to "categories")
            "saving_entries" -> listOf("planId" to "saving_plans", "linkedRecordId" to "records", "linkedTransferEventId" to "financial_events")
            "loan_plans" -> listOf("accountId" to "accounts", "disbursementEventId" to "financial_events")
            "loan_terms" -> listOf("planId" to "loan_plans", "postedEventId" to "financial_events", "paidEventId" to "financial_events")
            "loan_rate_changes" -> listOf("planId" to "loan_plans")
            "stat_contributions", "budget_contributions" -> listOf("eventId" to "financial_events", "originRecordId" to "records", "ledgerId" to "ledgers", "categoryId" to "categories")
            "recurrence_rule_segments" -> listOf("planId" to "recurrence_plans", "templateId" to "record_templates")
            "recurrence_occurrences" -> listOf("planId" to "recurrence_plans", "eventId" to "financial_events", "recordId" to "records")
            "budget_configs" -> listOf("budgetId" to "budgets")
            "budget_entries" -> listOf("configId" to "budget_configs", "categoryId" to "categories")
            "credit_profiles", "credit_capacity_events" -> listOf("accountId" to "accounts")
            "installment_plans" -> listOf("sourceRecordId" to "records", "accountId" to "accounts", "ledgerId" to "ledgers", "creationEventId" to "financial_events")
            "installment_terms" -> listOf("planId" to "installment_plans", "postingEventId" to "financial_events", "paidEventId" to "financial_events")
            "credit_payment_allocations" -> listOf("accountId" to "accounts", "paymentEventId" to "financial_events", "termId" to "installment_terms")
            "import_rows", "import_staging_chunks" -> if (schema >= SnapshotProtocol.DATA5_SCHEMA) listOf("batchId" to "import_batches") else error("Unsupported canonical table")
            else -> error("Unsupported canonical table")
        }
        if (table == "budgets") require(row.has("ledgerId") && !row.isNull("ledgerId")) { "Canonical budget ledger reference is missing" }
        refs.forEach { (field, target) -> if (row.has(field) && !row.isNull(field)) {
            val id = UUID.fromString(row.getString(field)).toString()
            val exists = db.query("SELECT EXISTS(SELECT 1 FROM `server_$target` WHERE id=? AND owner_id=? AND epoch=?)", arrayOf(id, owner.toString(), epoch.toString())).use { it.moveToFirst() && it.getInt(0) == 1 }
            if (!exists && receipt) throw AuthFailure("SNAPSHOT_REQUIRED")
            check(exists)
        } }
        if (schema >= SnapshotProtocol.DATA5_SCHEMA && table == "import_rows") {
            val payload = row.opt("payload")
            if (payload === JSONObject.NULL) check(row.has("deletedAt") && !row.isNull("deletedAt"))
            else {
                require(payload is JSONObject && payload.toString().toByteArray(Charsets.UTF_8).size <= MAX_IMPORT_CHUNK_BYTES)
                validatePullImportRecord(payload)
            }
        }
        if (schema >= SnapshotProtocol.DATA5_SCHEMA && table == "import_staging_chunks") {
            val payload = row.opt("payload")
            if (payload === JSONObject.NULL) check(row.has("deletedAt") && !row.isNull("deletedAt"))
            else {
                require(payload is JSONArray && payload.toString().toByteArray(Charsets.UTF_8).size <= MAX_IMPORT_CHUNK_BYTES)
                for (index in 0 until payload.length()) {
                    val staged = payload.getJSONObject(index)
                    validateStagedRecordShape(staged.getJSONObject("record"))
                }
            }
        }
    }

    private fun validatePullImportRecord(record: JSONObject) {
        validateStagedRecordShape(record)
    }
    private data class Cell(val name: String, val value: Any?, val affinity: String)
    private fun canonicalRow(cursor: Cursor, columns: List<String>): JSONObject {
        val values = columns.indices.associate { i -> columns[i] to when (cursor.getType(i)) {
            Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
            Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(i)
            Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(i)
            else -> cursor.getString(i)
        } }.toMutableMap()
        val result = JSONObject()
        values.keys.filter { it.endsWith("_unscaled") }.forEach { key ->
            val prefix = key.removeSuffix("_unscaled"); val raw = values.remove(key); val scale = values.remove("${prefix}_scale"); val currency = values.remove("${prefix}_currency")
            result.put(columnCamel(prefix), if (raw == null || raw === JSONObject.NULL) JSONObject.NULL else JSONObject().put("unscaled", raw.toString()).put("scale", scale).put("currency", currency))
        }
        values.forEach { (name, raw) ->
            if (name in setOf("owner_id", "epoch", "id")) return@forEach
            val value = if (raw is String && (raw.startsWith("[") || raw.startsWith("{"))) runCatching { JSONObject(raw) }.getOrElse { runCatching { JSONArray(raw) }.getOrNull() ?: raw } else raw
            result.put(columnCamel(name), value)
        }
        result.put("id", values["id"]); result.put("ownerId", values["owner_id"]); result.put("datasetEpoch", values["epoch"])
        return result
    }
    private fun columnCamel(value: String): String = value.split('_').let { it.first() + it.drop(1).joinToString("") { p -> p.replaceFirstChar(Char::uppercase) } }
    private fun importTable(db: SupportSQLiteDatabase, table: String, rows: JSONArray, owner: UUID, epoch: UUID) {
        val seen = HashSet<String>(rows.length())
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            require(seen.add(row.getString("id")) && row.getString("ownerId") == owner.toString() && row.getString("datasetEpoch") == epoch.toString())
        }
        db.execSQL("DROP TABLE IF EXISTS `server_$table`")
        db.execSQL("CREATE TABLE `server_$table` (`id` TEXT NOT NULL PRIMARY KEY, `owner_id` TEXT NOT NULL, `epoch` TEXT NOT NULL)")
        for (i in 0 until rows.length()) { val row = rows.getJSONObject(i); flatten(row).forEach { ensureColumn(db, table, it) }; upsertRow(db, table, row, owner, epoch) }
        check(db.query("SELECT COUNT(*) FROM `server_$table`").use { it.moveToFirst() && it.getLong(0) == rows.length().toLong() })
    }
    private fun upsertRow(db: SupportSQLiteDatabase, table: String, row: JSONObject, owner: UUID, epoch: UUID) {
        val cells = flatten(row); cells.forEach { ensureColumn(db, table, it) }
        val names = tableColumns(db, table)
        val statement = db.compileStatement("INSERT OR REPLACE INTO `server_$table` (${names.joinToString(",") { "`$it`" }}) VALUES (${names.joinToString(",") { "?" }})")
        val values = cells.associateBy { it.name }
        names.forEachIndexed { i, name -> bind(statement, i + 1, when (name) { "owner_id" -> owner.toString(); "epoch" -> epoch.toString(); else -> values[name]?.value }) }
        statement.executeInsert()
    }
    private fun ensureColumn(db: SupportSQLiteDatabase, table: String, cell: Cell) {
        val columns = tableColumns(db, table); check(columns.isNotEmpty())
        if (cell.name !in columns) db.execSQL("ALTER TABLE `server_$table` ADD COLUMN `${cell.name}` ${cell.affinity}")
        else require(compatible(columnAffinity(db, table, cell.name), cell.affinity))
    }
    private fun flatten(row: JSONObject): List<Cell> {
        val values = linkedMapOf<String, Cell>()
        row.keys().asSequence().forEach { key ->
            require(key.matches(Regex("[A-Za-z][A-Za-z0-9]*")))
            val raw = row.get(key)
            when {
                key == "ownerId" -> values["owner_id"] = Cell("owner_id", raw.toString(), "TEXT")
                key == "datasetEpoch" -> values["epoch"] = Cell("epoch", raw.toString(), "TEXT")
                raw is JSONObject && isMoney(raw) -> { val name = column(key); values["${name}_unscaled"] = Cell("${name}_unscaled", raw.getString("unscaled"), "TEXT"); values["${name}_scale"] = Cell("${name}_scale", raw.getInt("scale"), "INTEGER"); values["${name}_currency"] = Cell("${name}_currency", raw.getString("currency"), "TEXT") }
                raw is JSONObject || raw is JSONArray -> values[column(key)] = Cell(column(key), raw.toString(), "TEXT")
                raw === JSONObject.NULL -> values[column(key)] = Cell(column(key), null, "TEXT")
                raw is Boolean -> values[column(key)] = Cell(column(key), if (raw) 1 else 0, "INTEGER")
                raw is Int || raw is Long -> values[column(key)] = Cell(column(key), raw, "INTEGER")
                raw is Number -> values[column(key)] = Cell(column(key), raw.toString(), "TEXT")
                raw is String -> values[column(key)] = Cell(column(key), raw, "TEXT")
                else -> error("Unsupported canonical value")
            }
        }
        return values.values.toList()
    }
    private fun bind(statement: SupportSQLiteStatement, index: Int, value: Any?) = when (value) {
        null -> statement.bindNull(index)
        is Int -> statement.bindLong(index, value.toLong())
        is Long -> statement.bindLong(index, value)
        is String -> statement.bindString(index, value)
        else -> error("Unsupported SQLite bind value")
    }
    private fun isMoney(value: JSONObject) = value.length() == 3 && value.has("unscaled") && value.has("scale") && value.has("currency")
    private fun column(value: String) = value.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").lowercase()
    private fun compatible(existing: String, next: String) = existing == next || existing == "TEXT" || next == "TEXT"
    private fun tableColumns(db: SupportSQLiteDatabase, table: String) = db.query("PRAGMA table_info(`server_$table`)").use { c -> buildList { while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("name"))) } }
    private fun columnAffinity(db: SupportSQLiteDatabase, table: String, name: String) = db.query("PRAGMA table_info(`server_$table`)").use { c -> while (c.moveToNext()) if (c.getString(c.getColumnIndexOrThrow("name")) == name) return@use c.getString(c.getColumnIndexOrThrow("type")); error("Canonical column disappeared") }
    internal fun tableForEntity(entity: String, schema: Int): String {
        val tables = SnapshotProtocol.tablesFor(schema)
        return when (entity) {
            "preferences" -> "business_preferences"; "saving" -> "saving_plans"; "saving_entry" -> "saving_entries"; "loan" -> "loan_plans"; "loan_term" -> "loan_terms"; "loan_rate_change" -> "loan_rate_changes"; "recurrence" -> "recurrence_plans"; "recurrence_rule_segment" -> "recurrence_rule_segments"; "recurrence_occurrence" -> "recurrence_occurrences"; "record_template" -> "record_templates"; "category" -> "categories"; "account_ledger_visibility" -> "account_ledger_visibility"; "budget" -> "budgets"; "import_batch" -> "import_batches"; "import_row" -> "import_rows"; "import_staging_chunk" -> "import_staging_chunks"
            else -> entity.takeIf { it in tables } ?: "${entity}s".takeIf { it in tables } ?: error("Unsupported canonical entity")
        }.also { require(it in tables) { "Unsupported canonical entity" } }
    }

}
