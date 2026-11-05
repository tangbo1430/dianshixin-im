package dev.icos.app.data.local

import dev.icos.app.domain.Money
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Ledger metadata commands persist with canonical CAS and nonfinancial replay markers. */
internal class AccountLedgerCommands(private val database: DatasetDatabase) {
    private val mutations = AccountMutationRepository(database)

    /** Creates a ledger appended at the end of the canonical order. Returns the new ledger id. */
    fun create(deviceId: UUID, name: String, currency: String): UUID {
        val cleaned = requireValidName(name)
        Money(0, 0, currency)
        val newId = UUID.randomUUID()
        database.runInTransaction {
            val ledgers = SnapshotImporter.canonicalTables(database).getJSONArray("ledgers").objects()
            val maxSort = ledgers.filter { it.isNull("deletedAt") }.maxOfOrNull { it.optLong("sortOrder") } ?: -1L
            require(maxSort < Long.MAX_VALUE) { "账本排序值超出范围" }
            val entity = JSONObject()
                .put("name", cleaned).put("iconRef", JSONObject.NULL).put("color", JSONObject.NULL)
                .put("baseCurrency", currency).put("sortOrder", maxSort + 1)
                .put("archivedAt", JSONObject.NULL).put("isDefault", false)
            requireNoPendingLedgerCommands()
            mutations.enqueueCanonicalOnly(deviceId, "ledger", newId, "metadata.create",
                JSONObject().put("entity", entity), JSONObject().put("ledger:$newId", 0))
        }
        return newId
    }

    /** Full DTO updates preserve canonical fields and CAS-guard the previous default. */
    fun update(deviceId: UUID, id: UUID, expectedRevision: Long, name: String, sortOrder: Long, makeDefault: Boolean,
        defaultRevisions: Map<UUID, Long>) {
        require(expectedRevision > 0) { "账本需要先获得服务器确认" }
        val cleaned = requireValidName(name)
        database.runInTransaction {
            val ledgers = SnapshotImporter.canonicalTables(database).getJSONArray("ledgers").objects()
            val ledger = findLedger(ledgers, id)
            require(ledger.getLong("serverRevision") == expectedRevision) { "账本已变化，请刷新后重新编辑。" }
            require(ledger.isNull("iconRef")) { "此账本图标暂不支持编辑，原值已保留。" }
            require(makeDefault || !ledger.canonicalBoolean("isDefault")) { "不能直接取消默认账本。" }
            require(!makeDefault || ledger.isNull("archivedAt")) { "已归档账本不能设为默认。" }
            val versions = JSONObject().put("ledger:$id", expectedRevision)
            if (makeDefault) {
                val otherDefaults = ledgers.filter {
                    it.optString("id") != id.toString() && it.isNull("deletedAt") && it.canonicalBoolean("isDefault")
                }
                require(otherDefaults.size <= 1) { "默认账本状态异常，请先同步。" }
                val currentDefaults = otherDefaults.associate { it.uuid("id") to it.getLong("serverRevision") }
                require(currentDefaults == defaultRevisions && defaultRevisions.values.all { it > 0 }) {
                    "默认账本已变化，请刷新后重新确认。"
                }
                currentDefaults.forEach { (previous, revision) -> versions.put("ledger:$previous", revision) }
            }
            val changes = JSONObject()
                .put("name", cleaned)
                .put("iconRef", JSONObject.NULL)
                .put("color", ledger.opt("color") ?: JSONObject.NULL)
                .put("baseCurrency", ledger.getString("baseCurrency"))
                .put("sortOrder", sortOrder)
                .put("archivedAt", ledger.opt("archivedAt") ?: JSONObject.NULL)
                .put("isDefault", makeDefault)
            requireNoPendingLedgerCommands()
            mutations.enqueueCanonicalOnly(deviceId, "ledger", id, "metadata.update",
                JSONObject().put("changes", changes), versions)
        }
    }

    /** Archives or restores [id]. Archiving the canonical default ledger is rejected. */
    fun archive(deviceId: UUID, id: UUID, expectedRevision: Long, archived: Boolean) {
        require(expectedRevision > 0) { "账本需要先获得服务器确认" }
        database.runInTransaction {
            val ledgers = SnapshotImporter.canonicalTables(database).getJSONArray("ledgers").objects()
            val ledger = findLedger(ledgers, id)
            require(ledger.getLong("serverRevision") == expectedRevision) { "账本已变化，请刷新后重新操作。" }
            require(!archived || !ledger.canonicalBoolean("isDefault")) { "默认账本不能归档。" }
            require(ledger.isNull("archivedAt") == archived) { "账本归档状态已变化，请刷新后重新操作。" }
            requireNoPendingLedgerCommands()
            mutations.enqueueCanonicalOnly(deviceId, "ledger", id, "metadata.archive",
                JSONObject().put("archived", archived).put("includeInAssets", false),
                JSONObject().put("ledger:$id", expectedRevision))
        }
    }

    /** Captures the source revision and a stable fresh target before durable enqueue. */
    fun copy(deviceId: UUID, id: UUID, expectedRevision: Long, name: String): UUID {
        require(expectedRevision > 0) { "账本需要先获得服务器确认" }
        val cleaned = requireValidName(name)
        val newId = UUID.randomUUID()
        database.runInTransaction {
            val ledgers = SnapshotImporter.canonicalTables(database).getJSONArray("ledgers").objects()
            val source = findLedger(ledgers, id)
            require(source.getLong("serverRevision") == expectedRevision) { "账本已变化，请刷新后重新编辑。" }
            require(source.isNull("archivedAt")) { "已归档账本不能复制。" }
            requireNoPendingLedgerCommands()
            val versions = JSONObject()
                .put("ledger:$id", expectedRevision)
                .put("ledger:$newId", 0)
            mutations.enqueueCanonicalOnly(deviceId, "ledger", id, "ledger.copy",
                JSONObject().put("newLedgerId", newId.toString()).put("name", cleaned), versions)
        }
        return newId
    }

    fun delete(deviceId: UUID, ledger: FinanceLedger, confirmation: String) {
        require(ledger.localRevision > 0 && confirmation == ledger.name) { "请输入账本名称确认删除" }
        database.runInTransaction {
            val tables = SnapshotImporter.canonicalTables(database)
            val row = findLedger(tables.getJSONArray("ledgers").objects(), ledger.id)
            require(row.getLong("serverRevision") == ledger.localRevision && row.getString("name") == confirmation) {
                "账本已变化，请关闭后重新确认删除。"
            }
            require(!row.canonicalBoolean("isDefault")) { "默认账本不能删除" }
            val id = ledger.id.toString()
            if (tables.getJSONArray("financial_events").objects().any { it.optString("ledgerId") == id })
                throw FinanceValidationException("账本包含资金历史，不能删除")
            listOf("categories", "account_ledger_visibility", "budgets", "record_templates", "installment_plans", "refunds", "claim_settlements").forEach { table ->
                if (tables.optJSONArray(table)?.objects().orEmpty().any { it.optString("ledgerId") == id && it.isNull("deletedAt") })
                    throw FinanceValidationException("账本仍有分类、预算、模板或业务关联，不能删除")
            }
            requireNoPendingLedgerCommands()
            val identity = database.identity().read() ?: error("Dataset identity missing")
            check(database.accountMutations().intentsInOrder(identity.ownerId, identity.epochId).none {
                it.state in setOf("QUEUED", "AMBIGUOUS", "ACKED")
            }) { "仍有操作等待服务器确认，请先同步再删除账本。" }
            mutations.enqueueCanonicalOnly(deviceId, "ledger", ledger.id, "metadata.delete",
                JSONObject().put("strategy", "DELETE_LEDGER"), JSONObject().put("ledger:${ledger.id}", ledger.localRevision))
        }
    }

    fun reorder(deviceId: UUID, ledgers: List<FinanceLedger>) {
        require(ledgers.size in 1..1000 && ledgers.map { it.id }.distinct().size == ledgers.size) { "账本排序列表无效" }
        database.runInTransaction {
            val rows = SnapshotImporter.canonicalTables(database).getJSONArray("ledgers").objects().filter { it.isNull("deletedAt") }
            require(rows.map { it.getString("id") }.toSet() == ledgers.map { it.id.toString() }.toSet()) { "账本列表已变化，请刷新后重试。" }
            val versions = JSONObject()
            ledgers.forEach { ledger ->
                require(ledger.localRevision > 0 && rows.single { it.getString("id") == ledger.id.toString() }
                    .getLong("serverRevision") == ledger.localRevision) { "账本版本已变化，请刷新后重试。" }
                versions.put("ledger:${ledger.id}", ledger.localRevision)
            }
            requireNoPendingLedgerCommands()
            mutations.enqueueCanonicalOnly(deviceId, "ledger", ledgers.first().id, "metadata.reorder",
                JSONObject().put("orderedIds", JSONArray(ledgers.map { it.id.toString() })), versions)
        }
    }

    internal fun requireNoPendingLedgerCommands() {
        val identity = database.identity().read() ?: error("Dataset identity missing")
        check(database.accountMutations().intentsInOrder(identity.ownerId, identity.epochId).none {
            it.aggregateType in setOf("ledger", "category") && it.state in setOf("QUEUED", "AMBIGUOUS", "ACKED")
        }) { "账本或分类仍有操作等待服务器确认；请先同步原操作。" }
    }

    private fun findLedger(ledgers: List<JSONObject>, id: UUID): JSONObject =
        ledgers.singleOrNull { it.optString("id") == id.toString() && it.isNull("deletedAt") }
            ?: throw IllegalStateException("账本已不可用，请先同步。")

    private fun requireValidName(name: String): String {
        val cleaned = name.trim()
        require(cleaned.isNotEmpty() && cleaned.codePointCount(0, cleaned.length) <= 100 && cleaned.none { it == '\u0000' }) {
            "账本名称需为1到100个字符"
        }
        return cleaned
    }
}

private fun JSONArray.objects() = List(length()) { getJSONObject(it) }
private fun JSONObject.uuid(key: String) = UUID.fromString(getString(key))
