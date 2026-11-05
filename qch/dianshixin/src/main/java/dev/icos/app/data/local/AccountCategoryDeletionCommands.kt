package dev.icos.app.data.local

import dev.icos.app.domain.Direction
import dev.icos.app.domain.Money
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.util.UUID

/** Queues canonical category DELETE_RECORDS after a complete, repeatable local preflight. */
internal class AccountCategoryDeletionCommands(private val database: DatasetDatabase) {
    private val mutations = AccountMutationRepository(database)

    data class AccountImpact(val accountId: UUID, val name: String, val amount: Money)

    data class CategoryRecordDeletePreview(
        val ownerId: String,
        val epochId: String,
        val source: FinanceCategory,
        val recordCount: Int,
        val accountImpacts: List<AccountImpact>,
        internal val sourceRoot: String,
        internal val dependencies: List<CapturedDependency>,
        internal val versions: Map<String, Long>,
    )

    internal data class CapturedDependency(val table: String, val rows: List<String>)

    fun preview(source: FinanceCategory): CategoryRecordDeletePreview = database.runInTransaction(
        java.util.concurrent.Callable {
            val identity = database.identity().read()
                ?: throw FinanceValidationException("账号或账本已变化，请重新操作。")
            val tables = SnapshotImporter.canonicalTables(database)
            requireRequiredTables(tables, identity.snapshotSchemaVersion)
            val inspected = inspect(tables, source)
            CategoryRecordDeletePreview(
                identity.ownerId, identity.epochId, inspected.category, inspected.records.size,
                inspected.impacts, inspected.sourceRow.toString(), inspected.dependencies, inspected.versions,
            )
        },
    )

    fun delete(deviceId: UUID, preview: CategoryRecordDeletePreview, confirmation: String) {
        if (preview.source.localRevision <= 0 || confirmation != preview.source.name) {
            throw FinanceValidationException("请输入分类名称确认删除。")
        }
        database.runInTransaction {
            val identity = database.identity().read()
                ?: throw FinanceValidationException("账号或账本已变化，请重新确认删除。")
            if (identity.ownerId != preview.ownerId || identity.epochId != preview.epochId) {
                throw FinanceValidationException("账号或账本已变化，请重新确认删除。")
            }
            val tables = SnapshotImporter.canonicalTables(database)
            requireRequiredTables(tables, identity.snapshotSchemaVersion)
            val currentSource = activeCategory(tables, preview.source.id)
            if (currentSource.toString() != preview.sourceRoot || currentSource.getString("name") != confirmation ||
                currentSource.getLong("serverRevision") != preview.source.localRevision) {
                throw FinanceValidationException("分类已变化，请关闭后重新确认删除。")
            }
            val fresh = inspect(tables, preview.source)
            if (fresh.dependencies != preview.dependencies || fresh.versions != preview.versions ||
                fresh.records.size != preview.recordCount || fresh.impacts != preview.accountImpacts) {
                throw FinanceValidationException("分类关联资料已变化，请刷新后重新确认删除。")
            }
            requireNoPendingIntents(identity.ownerId, identity.epochId)
            val versions = JSONObject().also { json ->
                preview.versions.toSortedMap().forEach { (key, revision) -> json.put(key, revision) }
            }
            if (versions.length() > MAX_DELETE_VERSIONS) {
                throw FinanceValidationException("分类关联过多，无法安全删除。")
            }
            mutations.enqueueCanonicalOnly(
                deviceId, "category", preview.source.id, "metadata.delete",
                JSONObject().put("strategy", "DELETE_RECORDS"), versions,
            )
        }
    }

    private data class Inspection(
        val sourceRow: JSONObject,
        val category: FinanceCategory,
        val records: List<JSONObject>,
        val dependencies: List<CapturedDependency>,
        val versions: Map<String, Long>,
        val impacts: List<AccountImpact>,
    )

    private fun inspect(tables: JSONObject, supplied: FinanceCategory): Inspection {
        val source = activeCategory(tables, supplied.id)
        require(source.getLong("serverRevision") > 0 && supplied.localRevision > 0 &&
            source.getLong("serverRevision") == supplied.localRevision && source.getString("name") == supplied.name &&
            source.optString("ledgerId") == supplied.ledgerId.toString() &&
            source.optString("direction") == supplied.direction.name && source.uuidOrNull("parentId") == supplied.parentId &&
            source.isNull("archivedAt")) {
            "分类已变化或已归档，请刷新后重新确认删除。"
        }
        val direction = Direction.valueOf(source.getString("direction"))
        require(direction in setOf(Direction.EXPENSE, Direction.INCOME)) { "仅支持删除普通支出或收入分类。" }
        val categories = rows(tables, "categories")
        require(categories.none { it.uuidOrNull("parentId") == supplied.id }) {
            "仅支持删除没有子分类及子分类历史的末级分类。"
        }

        val allRecords = rows(tables, "records")
        val referencedRecords = allRecords.filter { it.uuidOrNull("categoryId") == supplied.id }
        // Tombstoned record references are retained in the capture but do not block deletion.
        val records = referencedRecords.filter { it.isNull("deletedAt") }.sortedBy { it.getString("id") }
        val dependencies = mutableListOf<CapturedDependency>()
        val versions = linkedMapOf<String, Long>()
        addVersion(versions, "category:${supplied.id}", source)
        val parentRow = source.uuidOrNull("parentId")?.let { id ->
            activeCategory(tables, id).also {
                require(it.getString("ledgerId") == source.getString("ledgerId") &&
                    it.getString("direction") == source.getString("direction") && it.isNull("parentId")) {
                    "分类父级关系无效，无法安全删除。"
                }
                addVersion(versions, "category:$id", it)
            }
        }
        val sourceLedgerId = UUID.fromString(source.getString("ledgerId"))
        val sourceLedger = rows(tables, "ledgers").singleOrNull { it.optString("id") == sourceLedgerId.toString() }
            ?: throw FinanceValidationException("分类账本不可用，无法安全删除。")
        require(sourceLedger.isNull("deletedAt") && sourceLedger.isNull("archivedAt")) { "分类账本已归档，无法安全删除。" }
        addVersion(versions, "ledger:$sourceLedgerId", sourceLedger)

        val accountRows = rows(tables, "accounts").associateBy { it.optString("id") }
        val ledgerRows = rows(tables, "ledgers").associateBy { it.optString("id") }
        val tagRows = rows(tables, "tags").associateBy { it.optString("id") }
        val fxRows = rows(tables, "fx_snapshots").associateBy { it.optString("id") }
        val allEvents = rows(tables, "financial_events")
        val allAllocations = rows(tables, "record_allocations")
        val allPostings = rows(tables, "account_postings")
        val allRelations = rows(tables, "event_relations")
        val allRecordTags = rows(tables, "record_tags")
        val allStats = rows(tables, "stat_contributions")
        val allBudgets = rows(tables, "budget_contributions")
        val accountUnits = linkedMapOf<UUID, BigInteger>()
        val involvedAccountIds = linkedSetOf<UUID>()
        val involvedTagIds = linkedSetOf<UUID>()
        val involvedFxIds = linkedSetOf<UUID>()
        val involvedLedgers = linkedSetOf<UUID>()
        val eventIds = linkedSetOf<String>()
        val recordIds = records.map { it.getString("id") }.toSet()

        records.forEach { record ->
            requireOrdinaryRootRecord(tables, record, source)
            val recordId = record.getString("id")
            val eventId = record.getString("eventId")
            addVersion(versions, "record:$recordId", record)
            val ledgerId = UUID.fromString(record.getString("ledgerId"))
            require(record.getString("ledgerId") == source.getString("ledgerId")) {
                "分类关联账单与分类账本不一致，无法安全删除。"
            }
            val ledger = ledgerRows[ledgerId.toString()]
                ?: throw FinanceValidationException("分类关联账本不可用，无法安全删除。")
            require(ledger.isNull("deletedAt") && ledger.isNull("archivedAt")) { "分类关联账本已归档，无法安全删除。" }
            addVersion(versions, "ledger:$ledgerId", ledger)
            involvedLedgers += ledgerId
            eventIds += eventId

            val event = allEvents.singleOrNull { it.optString("id") == eventId }
                ?: throw FinanceValidationException("分类关联资金事件缺失或不唯一，无法安全删除。")
            require(event.isNull("deletedAt") && event.optString("ledgerId") == ledgerId.toString() &&
                event.optString("status") == "POSTED" && event.optString("kind") == record.getString("direction") &&
                event.optString("origin") == "USER" && event.isNull("reversalOf")) { "分类关联资金事件不受支持，无法安全删除。" }

            val allocations = allAllocations.filter { it.optString("recordId") == recordId }
            require(allocations.all { it.isNull("deletedAt") }) {
                "分类关联账单分配不完整或含历史状态，无法安全删除。"
            }
            val allocatedAccountIds = allocations.map { UUID.fromString(it.getString("accountId")) }.toSet()
            allocations.forEach allocationLoop@{ allocation ->
                val fxId = allocation.uuidOrNull("fxSnapshotId") ?: return@allocationLoop
                val fx = fxRows[fxId.toString()]
                    ?: throw FinanceValidationException("分类关联汇率资料缺失，无法安全删除。")
                require(fx.isNull("deletedAt")) { "分类关联汇率资料已删除，无法安全删除。" }
                addVersion(versions, "fx_snapshot:$fxId", fx)
                involvedFxIds += fxId
            }
            allocatedAccountIds.forEach { accountId ->
                val account = accountRows[accountId.toString()]
                    ?: throw FinanceValidationException("分类关联账户不可用，无法安全删除。")
                require(account.isNull("deletedAt") && account.isNull("archivedAt") &&
                    account.optString("ownerId") == event.optString("ownerId") &&
                    account.optString("datasetEpoch") == event.optString("datasetEpoch")) {
                    "分类关联账户已删除、归档或身份不匹配，无法安全删除。"
                }
                if (account.optString("type") in setOf("RECEIVABLE", "PAYABLE")) {
                    throw FinanceValidationException("分类关联账户类型包含客户端无法完整检查的债务业务，暂不能删除。")
                }
                require(account.getString("currency").matches(CURRENCY)) { "分类关联账户币种无效，无法安全删除。" }
                addVersion(versions, "account:$accountId", account)
                involvedAccountIds += accountId
                val postingRows = allPostings.filter { it.optString("eventId") == eventId && it.optString("accountId") == accountId.toString() }
                require(postingRows.isNotEmpty() && postingRows.all { it.isNull("deletedAt") && it.isNull("reversalOf") }) {
                    "分类关联账户分录不完整或存在冲正，无法安全删除。"
                }
                postingRows.forEach { posting ->
                    val amount = parseMoney(posting, "signed")
                    require(amount.currency == account.getString("currency")) { "分类关联账户分录币种不匹配。" }
                    if (amount.unscaled == Long.MIN_VALUE) {
                        throw FinanceValidationException("分类冲正金额超出支持范围，不能表示其负数。")
                    }
                    val aligned = BigInteger.valueOf(amount.unscaled).multiply(BigInteger.TEN.pow(MAX_SCALE - amount.scale))
                    accountUnits[accountId] = (accountUnits[accountId] ?: BigInteger.ZERO).subtract(aligned)
                }
            }
            val postingAccounts = allPostings.filter { it.optString("eventId") == eventId }
            require(postingAccounts.isNotEmpty() == allocatedAccountIds.isNotEmpty() && postingAccounts.all {
                it.optString("accountId") in allocatedAccountIds.map(UUID::toString)
            }) {
                "分类关联账户分录与账单分配不匹配，无法安全删除。"
            }

            val recordCategoryId = record.uuidOrNull("categoryId")
                ?: throw FinanceValidationException("分类关联账单缺少分类引用，无法安全删除。")
            val recordCategory = activeCategory(tables, recordCategoryId)
            addVersion(versions, "category:$recordCategoryId", recordCategory)
            recordCategory.uuidOrNull("parentId")?.let { parent -> addVersion(versions, "category:$parent", activeCategory(tables, parent)) }

            val recordTags = allRecordTags.filter { it.optString("recordId") == recordId }
            require(recordTags.all { it.isNull("deletedAt") }) { "分类关联账单含不完整标签历史，无法安全删除。" }
            recordTags.forEach { link ->
                val tagId = UUID.fromString(link.getString("tagId"))
                val tag = tagRows[tagId.toString()]
                    ?: throw FinanceValidationException("分类关联标签不可用，无法安全删除。")
                require(tag.isNull("deletedAt") && tag.isNull("archivedAt")) { "分类关联标签已删除或归档，无法安全删除。" }
                addVersion(versions, "tag:$tagId", tag)
                involvedTagIds += tagId
            }
        }

        val relations = allRelations.filter { it.optString("parentEventId") in eventIds || it.optString("childEventId") in eventIds }
        require(relations.isEmpty()) { "分类关联账单仍有事件关系，无法安全删除。" }

        val sourceTemplates = rows(tables, "record_templates").filter { row ->
            listOf("categoryId", "feeCategoryId", "bonusCategoryId").any { row.uuidOrNull(it) == supplied.id }
        }
        require(sourceTemplates.isEmpty()) { "分类仍被账单模板历史引用，无法安全删除。" }
        val budgetEntries = rows(tables, "budget_entries").filter { it.uuidOrNull("categoryId") == supplied.id }
        require(budgetEntries.isEmpty()) { "分类仍被预算历史引用，无法安全删除。" }
        val claimAllocations = rows(tables, "claim_allocations").filter { it.uuidOrNull("expenseCategoryId") == supplied.id }
        require(claimAllocations.isEmpty()) { "分类仍被理赔历史引用，无法安全删除。" }
        val incomeSettlements = rows(tables, "claim_settlements").filter { it.uuidOrNull("incomeCategoryId") == supplied.id }
        require(incomeSettlements.isEmpty()) { "分类仍被报销结算历史引用，无法安全删除。" }
        val refunds = rows(tables, "refunds").filter { it.uuidOrNull("expenseCategoryId") == supplied.id }
        require(refunds.isEmpty()) { "分类仍被退款历史引用，无法安全删除。" }

        val claimItems = rows(tables, "claim_items").filter { it.optString("originRecordId") in recordIds }
        require(claimItems.isEmpty()) { "分类关联账单含理赔历史，无法安全删除。" }
        require(rows(tables, "record_segments").none { it.optString("originRecordId") in recordIds }) {
            "分类关联账单含理赔分段历史，无法安全删除。"
        }
        require(rows(tables, "saving_entries").none {
            it.optString("linkedRecordId") in recordIds || it.optString("linkedTransferEventId") in eventIds
        }) { "分类关联账单含储蓄历史，无法安全删除。" }
        require(rows(tables, "recurrence_occurrences").none { it.optString("recordId") in recordIds }) {
            "分类关联账单含周期历史，无法安全删除。"
        }
        require(rows(tables, "installment_plans").none { it.optString("sourceRecordId") in recordIds }) {
            "分类关联账单含分期历史，无法安全删除。"
        }
        require(rows(tables, "loan_plans").none { it.optString("disbursementEventId") in eventIds } &&
            rows(tables, "loan_terms").none { it.optString("paidEventId") in eventIds || it.optString("postedEventId") in eventIds }) {
            "分类关联账单含贷款历史，无法安全删除。"
        }
        require(rows(tables, "credit_payment_allocations").none { it.optString("paymentEventId") in eventIds } &&
            rows(tables, "credit_capacity_events").none { it.optString("sourceId") in recordIds || it.optString("sourceId") in eventIds }) {
            "分类关联账单含信用分期历史，无法安全删除。"
        }
        require(rows(tables, "claim_settlements").none { it.optString("eventId") in eventIds || it.optString("reversalEventId") in eventIds }) {
            "分类关联账单含理赔结算历史，无法安全删除。"
        }
        require(rows(tables, "refunds").none { it.optString("originRecordId") in recordIds }) {
            "分类关联账单含退款历史，无法安全删除。"
        }
        require(rows(tables, "installment_terms").none { it.optString("postingEventId") in eventIds || it.optString("paidEventId") in eventIds }) {
            "分类关联账单含分期历史，无法安全删除。"
        }
        require(allEvents.none { it.optString("reversalOf") in eventIds }) {
            "分类关联资金事件已有冲正，无法安全删除。"
        }

        dependencies += capture("categories", listOfNotNull(source, parentRow))
        dependencies += capture("ledgers", listOf(sourceLedger) + involvedLedgers.mapNotNull { ledgerRows[it.toString()] }.filter {
            it.optString("id") != sourceLedgerId.toString()
        })
        dependencies += capture("records", referencedRecords)
        dependencies += capture("financial_events", allEvents.filter { it.optString("id") in eventIds })
        dependencies += capture("record_allocations", allAllocations.filter { it.optString("recordId") in recordIds })
        dependencies += capture("account_postings", allPostings.filter { it.optString("eventId") in eventIds })
        dependencies += capture("event_relations", relations)
        dependencies += capture("record_tags", allRecordTags.filter { it.optString("recordId") in recordIds })
        dependencies += capture("stat_contributions", allStats.filter { it.optString("originRecordId") in recordIds || it.optString("eventId") in eventIds })
        dependencies += capture("budget_contributions", allBudgets.filter { it.optString("originRecordId") in recordIds || it.optString("eventId") in eventIds })
        dependencies += capture("stat_contributions_category", allStats.filter { it.uuidOrNull("categoryId") == supplied.id })
        dependencies += capture("budget_contributions_category", allBudgets.filter { it.uuidOrNull("categoryId") == supplied.id })
        dependencies += capture("accounts", involvedAccountIds.mapNotNull { accountRows[it.toString()] })
        dependencies += capture("tags", involvedTagIds.mapNotNull { tagRows[it.toString()] })
        dependencies += capture("fx_snapshots", involvedFxIds.mapNotNull { fxRows[it.toString()] })
        dependencies += capture("record_templates", sourceTemplates)
        dependencies += capture("budget_entries", budgetEntries)
        dependencies += capture("claim_allocations", claimAllocations)
        dependencies += capture("claim_settlements_category", incomeSettlements)
        dependencies += capture("refunds", refunds)
        dependencies += capture("claim_items", claimItems)
        dependencies += capture("record_segments", rows(tables, "record_segments").filter { it.optString("originRecordId") in recordIds })
        dependencies += capture("saving_entries", rows(tables, "saving_entries").filter {
            it.optString("linkedRecordId") in recordIds || it.optString("linkedTransferEventId") in eventIds
        })
        dependencies += capture("recurrence_occurrences", rows(tables, "recurrence_occurrences").filter { it.optString("recordId") in recordIds })
        dependencies += capture("installment_plans", rows(tables, "installment_plans").filter { it.optString("sourceRecordId") in recordIds })
        dependencies += capture("loan_plans", rows(tables, "loan_plans").filter { it.optString("disbursementEventId") in eventIds })
        dependencies += capture("loan_terms", rows(tables, "loan_terms").filter { it.optString("paidEventId") in eventIds || it.optString("postedEventId") in eventIds })
        dependencies += capture("credit_payment_allocations", rows(tables, "credit_payment_allocations").filter { it.optString("paymentEventId") in eventIds })
        dependencies += capture("credit_capacity_events", rows(tables, "credit_capacity_events").filter {
            it.optString("sourceId") in recordIds || it.optString("sourceId") in eventIds
        })
        dependencies += capture("claim_settlements", rows(tables, "claim_settlements").filter { it.optString("eventId") in eventIds || it.optString("reversalEventId") in eventIds })
        dependencies += capture("installment_terms", rows(tables, "installment_terms").filter { it.optString("postingEventId") in eventIds || it.optString("paidEventId") in eventIds })
        dependencies += capture("financial_event_reversals", allEvents.filter { it.optString("reversalOf") in eventIds })

        require(versions.size <= MAX_DELETE_VERSIONS) { "分类关联过多，无法安全删除。" }
        val impacts = involvedAccountIds.sortedBy(UUID::toString).map { accountId ->
            val account = accountRows.getValue(accountId.toString())
            var scale = MAX_SCALE
            var units = accountUnits[accountId] ?: BigInteger.ZERO
            while (scale > 0 && units.mod(BigInteger.TEN) == BigInteger.ZERO) {
                units = units.divide(BigInteger.TEN)
                scale -= 1
            }
            val unscaled = try {
                units.longValueExact()
            } catch (failure: ArithmeticException) {
                throw FinanceValidationException("分类冲正金额超出账户支持范围，无法安全预览。", failure)
            }
            AccountImpact(accountId, account.getString("name"), Money(unscaled, scale, account.getString("currency")))
        }
        return Inspection(source, financeCategory(source), records, dependencies, versions.toSortedMap(), impacts)
    }

    private fun requireOrdinaryRootRecord(tables: JSONObject, record: JSONObject, source: JSONObject) {
        val id = UUID.fromString(record.getString("id"))
        val eventId = record.getString("eventId")
        require(record.optString("status") == "POSTED" && record.optString("direction") == source.optString("direction") &&
            record.isNull("parentRecordId") && record.optString("claimState", "NONE") == "NONE" &&
            record.isNull("templateId") && record.stringOrNull("importBatchId") == null &&
            record.stringOrNull("importSource") == null && record.stringOrNull("externalId") == null && (record.optJSONArray("attachmentIds")?.length() ?: 0) == 0) {
            "仅支持删除独立、已入账的普通收支账单。"
        }
        val children = rows(tables, "records").filter { it.optString("parentRecordId") == id.toString() }
        require(children.isEmpty()) { "分类关联账单仍有子账单，无法安全删除。" }
        require(rows(tables, "event_relations").none { it.optString("parentEventId") == eventId || it.optString("childEventId") == eventId }) {
            "分类关联账单仍有事件关系，无法安全删除。"
        }
        fun hasReference(table: String, field: String, value: String): Boolean = rows(tables, table).any { it.optString(field) == value }
        require(!hasReference("refunds", "originRecordId", id.toString()) &&
            !hasReference("saving_entries", "linkedRecordId", id.toString()) &&
            !hasReference("recurrence_occurrences", "recordId", id.toString()) &&
            !hasReference("installment_plans", "sourceRecordId", id.toString()) &&
            !hasReference("record_segments", "originRecordId", id.toString()) &&
            !hasReference("claim_items", "originRecordId", id.toString()) &&
            !hasReference("loan_plans", "disbursementEventId", eventId) &&
            !hasReference("loan_terms", "postedEventId", eventId) && !hasReference("loan_terms", "paidEventId", eventId) &&
            !hasReference("installment_terms", "postingEventId", eventId) && !hasReference("installment_terms", "paidEventId", eventId) &&
            !hasReference("saving_entries", "linkedTransferEventId", eventId) &&
            !hasReference("credit_payment_allocations", "paymentEventId", eventId) &&
            !hasReference("financial_events", "reversalOf", eventId) &&
            !hasReference("claim_settlements", "eventId", eventId) && !hasReference("claim_settlements", "reversalEventId", eventId)) {
            "分类关联账单含储蓄、理赔、退款、周期、贷款、信用或分期业务依赖，无法安全删除。"
        }
    }

    private fun requireNoPendingIntents(ownerId: String, epochId: String) {
        val unresolved = database.accountMutations().intentsInOrder(ownerId, epochId).filter { it.state in PENDING_STATES }
        if (unresolved.isEmpty()) return
        unresolved.forEach { intent ->
            val outbox = database.typedOutbox().byOpId(intent.opId)
                ?: throw FinanceValidationException("账号操作队列不完整，无法安全删除分类。")
            if (outbox.ownerId != intent.ownerId || outbox.epochId != intent.epochId ||
                outbox.aggregateType != intent.aggregateType || outbox.aggregateId != intent.aggregateId ||
                outbox.commandType != intent.commandType || outbox.payloadJson != intent.payloadJson) {
                throw FinanceValidationException("账号操作队列身份不匹配，无法安全删除分类。")
            }
        }
        throw FinanceValidationException("仍有操作等待服务器确认；请先同步原操作后再删除分类。")
    }

    private fun requireRequiredTables(tables: JSONObject, schemaVersion: Int) {
        val requiredTables = dev.icos.app.data.remote.SnapshotProtocol.tablesFor(schemaVersion)
        if (schemaVersion !in dev.icos.app.data.remote.SnapshotProtocol.DATA5_SCHEMA..dev.icos.app.data.remote.SnapshotProtocol.DATA6_SCHEMA ||
            requiredTables.any { tables.optJSONArray(it) == null }) {
            throw FinanceValidationException("本机账本缺少完整关联数据，暂不能安全删除分类。")
        }
    }

    private fun activeCategory(tables: JSONObject, id: UUID): JSONObject = rows(tables, "categories")
        .singleOrNull { it.optString("id") == id.toString() && it.isNull("deletedAt") }
        ?: throw FinanceValidationException("分类已不可用，请先同步。")

    private fun addVersion(versions: MutableMap<String, Long>, key: String, row: JSONObject) {
        val revision = row.optLong("serverRevision", -1)
        if (revision <= 0) throw FinanceValidationException("分类关联资料缺少服务器版本，无法安全删除。")
        versions[key] = revision
    }

    private fun parseMoney(row: JSONObject, field: String): Money {
        val raw = row.optJSONObject(field) ?: throw FinanceValidationException("分类资金记录金额缺失，无法安全删除。")
        return try {
            Money.fromWire(raw.getString("unscaled"), raw.getInt("scale"), raw.getString("currency"))
        } catch (failure: Exception) {
            throw FinanceValidationException("分类资金记录金额无效，无法安全删除。", failure)
        }
    }

    private fun capture(table: String, rows: List<JSONObject>) = CapturedDependency(table, rows.map(JSONObject::toString).sorted())

    private fun rows(tables: JSONObject, table: String): List<JSONObject> = tables.getJSONArray(table).objects()

    private fun financeCategory(row: JSONObject) = FinanceCategory(
        UUID.fromString(row.getString("id")), row.getString("name"), Direction.valueOf(row.getString("direction")),
        row.uuidOrNull("parentId"), null, row.getLong("sortOrder"), row.getLong("serverRevision"),
        UUID.fromString(row.getString("ledgerId")), row.stringOrNull("color"), row.has("color"),
        excludeBudget = row.optBoolean("excludeBudget"), purpose = row.stringOrNull("purpose"),
    )

    private fun JSONArray.objects() = List(length()) { getJSONObject(it) }
    private fun JSONObject.uuidOrNull(key: String): UUID? = if (!has(key) || isNull(key)) null else UUID.fromString(getString(key))
    private fun JSONObject.stringOrNull(key: String): String? = if (!has(key) || isNull(key)) null else get(key).toString().takeIf(String::isNotBlank)
    private fun JSONObject.isActive() = isNull("deletedAt") && (!has("archivedAt") || isNull("archivedAt"))

    private companion object {
        const val MAX_DELETE_VERSIONS = 2048
        const val MAX_SCALE = 6
        val PENDING_STATES = setOf("QUEUED", "AMBIGUOUS", "ACKED")
        val CURRENCY = Regex("[A-Z]{3}")
    }
}
