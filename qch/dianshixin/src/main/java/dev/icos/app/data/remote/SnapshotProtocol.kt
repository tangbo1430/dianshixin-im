package dev.icos.app.data.remote

import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStreamReader
import java.security.MessageDigest
import java.util.UUID

internal object SnapshotProtocol {
    const val DATA3_SCHEMA = 3
    const val DATA4_SCHEMA = 4
    const val DATA5_SCHEMA = 5
    const val DATA6_SCHEMA = 6
    private const val MAX_BOOTSTRAP_BYTES = 2L * 1024 * 1024 * 1024
    // Canonical projection and some DATA5 import payloads still materialize; retain this cap until those reads stream too.
    internal const val MAX_MATERIALIZED_SNAPSHOT_BYTES = 64L * 1024 * 1024
    internal const val MAX_SNAPSHOT_ROW_BYTES = 1_048_576

    val data1Tables = setOf(
        "ledgers", "tag_groups", "account_groups", "categories", "tags", "accounts",
        "account_ledger_visibility", "budgets", "budget_configs", "budget_entries",
        "business_preferences", "record_templates", "template_allocations", "template_tags",
        "financial_events", "records", "record_allocations", "record_tags", "event_relations",
        "fx_snapshots", "account_postings", "claim_items", "claim_settlements",
        "claim_allocations", "record_segments", "refunds", "saving_plans", "saving_entries",
        "stat_contributions", "budget_contributions",
    )
    val data2Tables = data1Tables + setOf("loan_plans", "loan_terms", "loan_rate_changes")
    val data3Tables = data2Tables + setOf("recurrence_plans", "recurrence_rule_segments", "recurrence_occurrences")
    val data4Tables = data3Tables + setOf(
        "credit_profiles", "credit_capacity_events", "installment_plans", "installment_terms",
        "credit_payment_allocations",
    )
    val data5Tables = data4Tables + setOf("import_batches", "import_rows", "import_staging_chunks")
    val data6Tables = data5Tables + setOf("receivable_payable_operations")

    fun tablesFor(schemaVersion: Int): Set<String> = when (schemaVersion) {
        1 -> data1Tables
        2 -> data2Tables
        DATA3_SCHEMA -> data3Tables
        DATA4_SCHEMA -> data4Tables
        DATA5_SCHEMA -> data5Tables
        DATA6_SCHEMA -> data6Tables
        else -> throw AuthFailure("SNAPSHOT_SCHEMA_UNSUPPORTED")
    }

    data class VerifiedSnapshot(
        val ownerId: UUID,
        val epoch: UUID,
        val capturedSeq: Long,
        val cursor: String,
        val file: File,
        val entityCounts: Map<String, Long>,
        val schemaVersion: Int,
        val plainSha256: String,
    ) : AutoCloseable {
        override fun close() { file.delete() }
    }

    fun verify(file: File, download: SnapshotDownload, expectedOwner: UUID, expectedEpoch: UUID, minimumCapturedSeq: Long): VerifiedSnapshot {
        require(file.isFile && download.bytesWritten == file.length() && download.bytesWritten in 1..MAX_BOOTSTRAP_BYTES)
        if (download.bytesWritten > MAX_MATERIALIZED_SNAPSHOT_BYTES) throw tooLarge()
        val envelope = download.manifest
        val digest = envelope.getString("manifestDigest")
        val manifest = JSONObject(envelope.toString()).apply { remove("manifestDigest") }
        require(manifest.getString("format") == "icos-native-snapshot" && manifest.getInt("formatVersion") == 1)
        val schema = manifest.getInt("schemaVersion")
        val tables = tablesFor(schema)
        require(schema in 1..DATA6_SCHEMA)
        require(UUID.fromString(manifest.getString("ownerId")) == expectedOwner)
        require(UUID.fromString(manifest.getString("sourceEpoch")) == expectedEpoch)
        require(digest.matches(Regex("[0-9a-f]{64}")) && sha256(canonical(manifest)) == digest)
        require(manifest.getLong("plainBytes") == download.bytesWritten)
        val plainHash = manifest.getString("plainSha256")
        require(plainHash.matches(Regex("[0-9a-f]{64}")) && sha256(file) == plainHash)
        val declaredCounts = manifest.getJSONObject("entityCounts")
        require(declaredCounts.keys().asSequence().toSet() == tables)
        val counts = scan(file, expectedOwner, expectedEpoch, minimumCapturedSeq, schema, declaredCounts)
        require(counts.first == manifest.getLong("capturedSeq"))
        return VerifiedSnapshot(expectedOwner, expectedEpoch, counts.first, download.cursor.also { require(it.isNotBlank()) }, file, counts.second, schema, plainHash)
    }

    /** Reopens the verified file and delivers one bounded row at a time to the staging importer. */
    internal fun scanRows(snapshot: VerifiedSnapshot, consume: (String, JSONObject) -> Unit) {
        scan(snapshot.file, snapshot.ownerId, snapshot.epoch, snapshot.capturedSeq, snapshot.schemaVersion, null, consume)
    }

    private fun scan(
        file: File, owner: UUID, epoch: UUID, minimumSeq: Long, schema: Int,
        declaredCounts: JSONObject?, consume: ((String, JSONObject) -> Unit)? = null,
    ): Pair<Long, Map<String, Long>> {
        val expectedTables = tablesFor(schema)
        val counts = linkedMapOf<String, Long>()
        var captured = -1L
        val rootKeys = HashSet<String>()
        JsonReader(InputStreamReader(BufferedInputStream(file.inputStream(), 64 * 1024), Charsets.UTF_8)).use { r ->
            r.isLenient = false
            r.beginObject()
            while (r.hasNext()) {
                val key = r.nextName()
                require(rootKeys.add(key))
                when (key) {
                    "format" -> require(r.nextString() == "icos-native-snapshot")
                    "formatVersion" -> require(r.nextInt() == 1)
                    "schemaVersion" -> require(r.nextInt() == schema)
                    "ownerId" -> require(UUID.fromString(r.nextString()) == owner)
                    "epoch" -> require(UUID.fromString(r.nextString()) == epoch)
                    "capturedSeq" -> { captured = r.nextLong(); require(captured >= minimumSeq) }
                    "tables" -> {
                        r.beginObject()
                        val seen = HashSet<String>()
                        while (r.hasNext()) {
                            val table = r.nextName()
                            require(table in expectedTables && seen.add(table))
                            var count = 0L
                            r.beginArray()
                            while (r.hasNext()) {
                                val row = readValue(r, 0, RowBudget())
                                require(row is JSONObject)
                                require(row.getString("ownerId") == owner.toString() && row.getString("datasetEpoch") == epoch.toString())
                                require(UUID.fromString(row.getString("id")).toString() == row.getString("id"))
                                consume?.invoke(table, row)
                                count = Math.addExact(count, 1L)
                            }
                            r.endArray()
                            if (declaredCounts != null) require(declaredCounts.getLong(table) == count)
                            counts[table] = count
                        }
                        r.endObject()
                        require(seen == expectedTables)
                    }
                    "attachmentManifest" -> {
                        val attachment = readValue(r, 0, RowBudget())
                        require(attachment is JSONArray && attachment.length() == 0)
                    }
                    else -> throw IllegalArgumentException("Unknown snapshot field")
                }
            }
            r.endObject()
            require(r.peek() == JsonToken.END_DOCUMENT)
        }
        require(rootKeys == setOf("format", "formatVersion", "schemaVersion", "ownerId", "epoch", "capturedSeq", "tables", "attachmentManifest"))
        require(captured >= 0 && counts.keys == expectedTables)
        return captured to counts
    }

    private class RowBudget {
        var bytes = 0
        var tokens = 0
        fun add(text: String) {
            bytes = Math.addExact(bytes, text.toByteArray(Charsets.UTF_8).size)
            tokens = Math.addExact(tokens, 1)
            require(bytes <= MAX_SNAPSHOT_ROW_BYTES && tokens <= 65_536)
        }
    }

    private fun readValue(r: JsonReader, depth: Int, budget: RowBudget): Any {
        require(depth <= 64)
        budget.tokens = Math.addExact(budget.tokens, 1)
        require(budget.tokens <= 65_536)
        return when (r.peek()) {
            JsonToken.BEGIN_OBJECT -> JSONObject().also { out ->
                r.beginObject()
                while (r.hasNext()) { val name = r.nextName(); budget.add(name); require(!out.has(name)); out.put(name, readValue(r, depth + 1, budget)) }
                r.endObject()
            }
            JsonToken.BEGIN_ARRAY -> JSONArray().also { out -> r.beginArray(); while (r.hasNext()) out.put(readValue(r, depth + 1, budget)); r.endArray() }
            JsonToken.STRING -> r.nextString().also(budget::add)
            JsonToken.NUMBER -> r.nextString().also(budget::add).let { raw -> raw.toLongOrNull() ?: raw.toDoubleOrNull() ?: error("Invalid number") }
            JsonToken.BOOLEAN -> r.nextBoolean().also { budget.add(it.toString()) }
            JsonToken.NULL -> { r.nextNull(); budget.add("null"); JSONObject.NULL }
            else -> error("Invalid snapshot JSON")
        }
    }

    fun requireReady(status: JSONObject, expectedOwner: UUID, expectedEpoch: UUID): SnapshotReady? {
        if (UUID.fromString(status.getString("epoch")) != expectedEpoch) throw AuthFailure("DATASET_EPOCH_CHANGED")
        return when (status.getString("state")) {
            "QUEUED", "CAPTURING", "VERIFYING" -> null
            "FAILED", "CANCELLED" -> throw AuthFailure("SNAPSHOT_${status.getString("state")}", fields = mapOf("failureCode" to status.optString("failureCode")))
            "READY" -> {
                val manifest = status.getJSONObject("manifest")
                val schema = status.getInt("schemaVersion")
                val tables = tablesFor(schema)
                require(manifest.getInt("schemaVersion") == schema)
                val counts = manifest.getJSONObject("entityCounts")
                require(counts.keys().asSequence().toSet() == tables && counts.keys().asSequence().all { counts.getLong(it) >= 0 })
                val ready = SnapshotReady(UUID.fromString(status.getString("jobId")), UUID.fromString(status.getString("epoch")), status.getLong("capturedSeq"), status.getString("nextCursor"), status.getLong("plainBytes"))
                require(ready.cursor.isNotBlank() && ready.plainBytes in 1..MAX_BOOTSTRAP_BYTES)
                require(manifest.getLong("capturedSeq") == ready.capturedSeq && UUID.fromString(manifest.getString("ownerId")) == expectedOwner)
                require(UUID.fromString(manifest.getString("sourceEpoch")) == expectedEpoch && manifest.getLong("plainBytes") == ready.plainBytes)
                require(status.getString("manifestDigest").matches(Regex("[0-9a-f]{64}")) && sha256(canonical(manifest)) == status.getString("manifestDigest"))
                require(manifest.getString("plainSha256").matches(Regex("[0-9a-f]{64}")) && status.getString("objectDigest").matches(Regex("[0-9a-f]{64}")))
                ready
            }
            else -> throw AuthFailure("INVALID_RESPONSE")
        }
    }

    private fun tooLarge() = AuthFailure("SNAPSHOT_TOO_LARGE", fields = mapOf("maximumBytes" to MAX_MATERIALIZED_SNAPSHOT_BYTES.toString()))
    private fun canonical(value: Any): String = when (value) {
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(value.get(it)) }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
        JSONObject.NULL -> "null"
        is String -> JSONObject.quote(value)
        is Number, is Boolean -> value.toString()
        else -> error("Unsupported JSON value")
    }
    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).hex()
    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").run {
        file.inputStream().buffered().use { input -> val buffer = ByteArray(64 * 1024); while (true) { val n = input.read(buffer); if (n < 0) break; update(buffer, 0, n) } }; digest().hex()
    }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
}

internal data class SnapshotReady(val jobId: UUID, val epoch: UUID, val capturedSeq: Long, val cursor: String, val plainBytes: Long)
