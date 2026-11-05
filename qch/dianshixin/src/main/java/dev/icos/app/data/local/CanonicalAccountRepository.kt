package dev.icos.app.data.local

import dev.icos.app.data.remote.RecordInputJson
import dev.icos.app.domain.Direction
import dev.icos.app.domain.LoanDueState
import dev.icos.app.domain.LoanTerm
import dev.icos.app.domain.Money
import dev.icos.app.domain.PeriodResolver
import dev.icos.app.domain.RecordInput
import dev.icos.app.domain.RemainderAction
import dev.icos.app.domain.RepaymentMethod
import dev.icos.app.domain.ReportPeriod
import dev.icos.app.domain.SavingEntryKind
import dev.icos.app.domain.SavingEntrySummary
import dev.icos.app.domain.SavingInterval
import dev.icos.app.domain.SavingIntervalUnit
import dev.icos.app.domain.SavingKind
import dev.icos.app.domain.SavingPlanInput
import dev.icos.app.domain.SavingPlanSummary
import dev.icos.app.domain.SavingState
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Account-owned read model built directly from the complete canonical DATA3 replica. */
internal object CanonicalAccountRepository {
    data class LedgerOption(
        val id: UUID,
        val name: String,
        val currency: String,
    )

    data class RecurrenceSummary(
        val id: UUID,
        val state: String,
        val frequency: String?,
        val templateId: UUID?,
        val templateName: String?,
        val nextDueAtMillis: Long?,
        val nextDueSlot: Long,
        val startLocalDateTime: String,
        val timezone: String,
        val interval: Int,
        val weekDays: List<Int>,
        val monthDays: List<Int>,
        val endAt: String?,
        val maxCount: Long?,
        val templateRevision: Long?,
        val serverRevision: Long,
        val generatedCount: Long,
        val occurrenceCount: Int,
    )

    data class View(
        val snapshot: FinanceSnapshot,
        val ledgers: List<LedgerOption>,
        val selectedLedgerId: UUID,
        val budget: FinanceBudgetOverview,
        val templates: List<FinanceRecordTemplate>,
        val recurrences: List<RecurrenceSummary>,
    )

    data class CategoryProjection(
        val categories: List<FinanceCategory>,
        val pendingCreations: List<FinanceCategory>,
    )

    fun assetHistory(
        database: DatasetDatabase,
        months: Int,
        zoneId: String,
        asOfMillis: Long,
        includeFuture: Boolean,
    ): List<AssetHistoryPoint> {
        val identity = database.identity().read() ?: error("Dataset identity missing")
        val owner = UUID.fromString(identity.ownerId)
        val epoch = UUID.fromString(identity.epochId)
        val tables = SnapshotImporter.canonicalTables(database)
        val predictions = database.accountMutations().predictions(owner.toString(), epoch.toString())
        val intents = database.accountMutations().intentsInOrder(owner.toString(), epoch.toString())
        applyPredictions(tables, predictions, intents, owner, epoch)
        val accounts =
            tables.array("accounts").objects().map { row ->
                AssetHistoryAccount(
                    row.uuid("id"),
                    row.string("ownerId"),
                    row.string("datasetEpoch"),
                    row.string("currency"),
                    row.string("type"),
                    row.bool("includeInAssets"),
                    row.timestampOrNull("archivedAt"),
                )
            }
        val events = tables.array("financial_events").objects().associateBy { it.string("id") }
        val accountIds = accounts.map { it.id.toString() }.toSet()
        val postings =
            tables.array("account_postings").objects().mapNotNull { row ->
                if (row.string("accountId") !in accountIds || row.stringOrNull("deletedAt") != null) return@mapNotNull null
                val event = events[row.string("eventId")] ?: return@mapNotNull null
                if (event.string("ownerId") != owner.toString() || event.string("datasetEpoch") != epoch.toString()) return@mapNotNull null
                AssetHistoryPosting(
                    UUID.fromString(row.string("accountId")),
                    row.string("ownerId"),
                    row.string("datasetEpoch"),
                    event.timestamp("occurredAt"),
                    row.money("signed"),
                )
            }
        return AssetHistoryCalculator.monthly(
            accounts,
            postings,
            owner.toString(),
            epoch.toString(),
            zoneId,
            asOfMillis,
            months,
            includeFuture,
        )
    }

    fun read(
        database: DatasetDatabase,
        selectedLedgerId: UUID? = null,
        now: Long = System.currentTimeMillis(),
        summary: JSONObject? = null,
        includePredictions: Boolean = true,
        budgetPeriod: ReportPeriod = ReportPeriod.MONTH,
        statisticsZone: String =
            java.util.TimeZone
                .getDefault()
                .id,
    ): View = database.runInTransaction(java.util.concurrent.Callable {
        val identity = database.identity().read() ?: error("Dataset identity missing")
        val owner = UUID.fromString(identity.ownerId)
        val epoch = UUID.fromString(identity.epochId)
        check(identity.serverCursor != null && identity.lastAppliedSeq >= 0)
        val tables = SnapshotImporter.canonicalTables(database)
        if (includePredictions) {
            val mutationDao = database.accountMutations()
            applyPredictions(
                tables,
                mutationDao.predictions(owner.toString(), epoch.toString()),
                mutationDao.intentsInOrder(owner.toString(), epoch.toString()),
                owner,
                epoch,
            )
        }
        readTables(tables, owner, epoch, selectedLedgerId, now, summary, budgetPeriod, statisticsZone)
    })

    /** Reads the selectable category projection, including durable unconfirmed local metadata. */
    fun readCategories(
        database: DatasetDatabase,
        owner: UUID,
        epoch: UUID,
    ): List<FinanceCategory> {
        requireIdentity(database, owner, epoch)
        return readCategoryProjection(database, owner, epoch).categories
    }

    /** Reads canonical rows plus active category predictions; revision zero remains unconfirmed. */
    fun readCategoryProjection(
        database: DatasetDatabase,
        owner: UUID,
        epoch: UUID,
    ): CategoryProjection = database.runInTransaction(java.util.concurrent.Callable {
        requireIdentity(database, owner, epoch)
        val tables = SnapshotImporter.canonicalTables(database, setOf("categories"))
        val dao = database.accountMutations()
        val intents = dao.intentsInOrder(owner.toString(), epoch.toString())
        applyPredictions(tables, dao.predictions(owner.toString(), epoch.toString()).filter { it.aggregateType == "category" }, intents, owner, epoch)
        val categories = canonicalCategories(tables)
        val pendingIds = intents.filter { it.aggregateType == "category" && it.commandType == "metadata.create" &&
            it.state in setOf("QUEUED", "AMBIGUOUS", "ACKED") }.mapTo(HashSet()) { it.aggregateId }
        CategoryProjection(categories, categories.filter { it.localRevision == 0L && it.id.toString() in pendingIds })
    })



    private fun requireIdentity(
        database: DatasetDatabase,
        owner: UUID,
        epoch: UUID,
    ) {
        val identity = database.identity().read() ?: error("Dataset identity missing")
        check(identity.ownerId == owner.toString() && identity.epochId == epoch.toString()) { "Dataset identity mismatch" }
    }


    internal fun applyPredictions(
        tables: JSONObject,
        predictions: List<AccountPredictionEntity>,
        intents: List<AccountMutationIntentEntity>,
        owner: UUID,
        epoch: UUID,
    ) {
        val commands = intents.associateBy { it.opId }
        predictions.forEach { prediction ->
            val command = commands[prediction.opId] ?: return@forEach
            if (!prediction.active || prediction.ownerId != owner.toString() || prediction.epochId != epoch.toString()) return@forEach
            val body = JSONObject(prediction.payloadJson)
            val id = prediction.aggregateId
            when (prediction.aggregateType) {
                "category" -> {
                    when (command.commandType) {
                        "metadata.create" -> {
                            if (tables.array("categories").objects().none { it.string("id") == id }) {
                                tables.getJSONArray("categories").put(body.getJSONObject("entity")
                                    .put("id", id).put("ownerId", owner.toString()).put("datasetEpoch", epoch.toString())
                                    .put("serverRevision", 0).put("deletedAt", JSONObject.NULL))
                            }
                        }
                        "metadata.update" -> {
                            val row = tables.array("categories").objects().single { it.string("id") == id }
                            val changes = body.getJSONObject("changes")
                            changes.keys().forEach { key -> row.put(key, changes.get(key)) }
                        }
                    }
                }

                "account" -> {
                    if (command.commandType == "metadata.create") {
                        val openingEventId = UUID.randomUUID().toString()
                        val ledgers = tables.getJSONArray("ledgers")
                        val defaultLedger =
                            (0 until ledgers.length()).map { ledgers.getJSONObject(it) }.firstOrNull { it.canonicalBoolean("isDefault") }
                                ?: error("Canonical default ledger unavailable")
                        val entity =
                            body
                                .getJSONObject("entity")
                                .put("id", id)
                                .put("ownerId", owner.toString())
                                .put("datasetEpoch", epoch.toString())
                                .put("serverRevision", 0)
                                .put("openingEventId", openingEventId)
                        tables.getJSONArray("accounts").put(entity)
                        val event =
                            JSONObject()
                                .put("id", openingEventId)
                                .put("ownerId", owner.toString())
                                .put("datasetEpoch", epoch.toString())
                                .put("ledgerId", defaultLedger.getString("id"))
                                .put(
                                    "occurredAt",
                                    body.getJSONObject("entity").getString("openingDate") + "T00:00:00Z",
                                ).put("timezone", "UTC")
                                .put("serverRevision", 0)
                        tables.getJSONArray("financial_events").put(event)
                        val opening = body.getJSONObject("entity").getJSONObject("openingBalance")
                        tables.getJSONArray("account_postings").put(
                            JSONObject()
                                .put("id", UUID.randomUUID().toString())
                                .put("ownerId", owner.toString())
                                .put("datasetEpoch", epoch.toString())
                                .put("eventId", openingEventId)
                                .put(
                                    "accountId",
                                    id,
                                ).put("signed", opening)
                                .put("amount", opening)
                                .put("role", "OPENING")
                                .put("deletedAt", JSONObject.NULL),
                        )
                    }
                }

                "budget" -> {
                    when (command.commandType) {
                        "budget.configure" -> applyBudgetConfigure(tables, body, id)
                        "budget.reset" -> applyBudgetReset(tables, body, id)
                    }
                }

                "record" -> {
                    when (command.commandType) {
                        "record.create" -> applyRecordCreate(tables, body.getJSONObject("record"), id, owner, epoch)
                        "record.update" -> applyRecordUpdate(tables, body.getJSONObject("changes"), id, owner, epoch)
                        "record.delete" -> applyRecordDelete(tables, id, owner, epoch)
                    }
                }

                "saving" -> {
                    applySavingPrediction(tables, command.commandType, body, id, owner, epoch)
                }

                "record_template" -> {
                    when (command.commandType) {
                        "metadata.create" -> {
                            check(tables.array("record_templates").objects().none { it.optString("id") == id }) {
                                "UNSUPPORTED_ACCOUNT_TEMPLATE_PREDICTION"
                            }
                            applyTemplateWrite(tables, body.getJSONObject("entity"), id, owner, epoch, null)
                        }

                        "metadata.update" -> {
                            val old =
                                tables
                                    .array(
                                        "record_templates",
                                    ).objects()
                                    .firstOrNull { it.optString("id") == id && it.isNull("deletedAt") }
                                    ?: error("UNSUPPORTED_ACCOUNT_TEMPLATE_PREDICTION")
                            applyTemplateWrite(tables, body.getJSONObject("changes"), id, owner, epoch, old)
                        }

                        "metadata.delete" -> {
                            val old =
                                tables
                                    .array(
                                        "record_templates",
                                    ).objects()
                                    .firstOrNull { it.optString("id") == id && it.isNull("deletedAt") }
                                    ?: error("UNSUPPORTED_ACCOUNT_TEMPLATE_PREDICTION")
                            old.put("deletedAt", "PENDING").put("serverRevision", old.optLong("serverRevision") + 1)
                            listOf("template_allocations", "template_tags").forEach { table ->
                                tables
                                    .array(table)
                                    .objects()
                                    .filter { it.optString("templateId") == id && it.isNull("deletedAt") }
                                    .forEach { it.put("deletedAt", "PENDING").put("serverRevision", it.optLong("serverRevision") + 1) }
                            }
                        }
                    }
                }

                "recurrence" -> {
                    applyRecurrencePrediction(tables, command, body, id, owner, epoch)
                }
            }
        }
    }

    private fun applyRecurrencePrediction(
        tables: JSONObject,
        command: AccountMutationIntentEntity,
        body: JSONObject,
        id: String,
        owner: UUID,
        epoch: UUID,
    ) {
        val plans = tables.getJSONArray("recurrence_plans")
        val planIndex = (0 until plans.length()).firstOrNull { plans.getJSONObject(it).optString("id") == id }
        when (command.commandType) {
            "recurrence.create" -> {
                require(planIndex == null) { "RECURRENCE_ID_ALREADY_EXISTS" }
                val plan = body.getJSONObject("plan")
                val start = plan.getString("startLocalDateTime")
                val planRow =
                    JSONObject()
                        .put("id", id)
                        .put("ownerId", owner.toString())
                        .put("datasetEpoch", epoch.toString())
                        .put(
                            "startLocalDateTime",
                            start,
                        ).put("timezone", plan.getString("timezone"))
                        .put("state", plan.optString("state", "RUNNING"))
                        .put("executor", "SERVER")
                        .put("nextDueSlot", 0)
                        .put("nextDueAt", JSONObject.NULL)
                        .put("generatedCount", 0)
                        .put("currentRuleRevision", 1)
                        .put("serverRevision", 0)
                        .put("deletedAt", JSONObject.NULL)
                plans.put(planRow)
                val segment =
                    JSONObject()
                        .put(
                            "id",
                            UUID.randomUUID().toString(),
                        ).put("ownerId", owner.toString())
                        .put("datasetEpoch", epoch.toString())
                        .put("planId", id)
                        .put("ruleRevision", 1)
                        .put("effectiveFromSlot", 0)
                        .put("anchorLocalDateTime", start)
                        .put("templateId", plan.getString("templateId"))
                        .put(
                            "templateRevision",
                            tables
                                .getJSONArray("record_templates")
                                .objects()
                                .firstOrNull {
                                    it.optString("id") == plan.getString("templateId")
                                }?.optLong("serverRevision")
                                ?: 0L,
                        ).put("frequency", plan.getString("frequency"))
                        .put("intervalStep", plan.getInt("interval"))
                        .put("weekDays", plan.optJSONArray("weekDays") ?: JSONArray())
                        .put("monthDays", plan.optJSONArray("monthDays") ?: JSONArray())
                        .put("endAt", plan.opt("endAt") ?: JSONObject.NULL)
                        .put("maxCount", plan.opt("maxCount") ?: JSONObject.NULL)
                        .put("serverRevision", 0)
                        .put("deletedAt", JSONObject.NULL)
                tables.getJSONArray("recurrence_rule_segments").put(segment)
            }

            "recurrence.update" -> {
                if (planIndex != null) {
                    val plan = plans.getJSONObject(planIndex)
                    val slot = body.getLong("effectiveFromSlot")
                    val segments = tables.getJSONArray("recurrence_rule_segments")
                    val priorIndex =
                        (0 until segments.length())
                            .filter {
                                segments.getJSONObject(it).optString("planId") == id &&
                                    segments.getJSONObject(it).optLong("effectiveFromSlot", Long.MAX_VALUE) <= slot
                            }.maxByOrNull { segments.getJSONObject(it).optLong("effectiveFromSlot") }
                    val prior = priorIndex?.let(segments::getJSONObject) ?: return
                    val changes = body.getJSONObject("changes")
                    val templateId = changes.optString("templateId", prior.getString("templateId"))
                    val templateRevision =
                        tables
                            .getJSONArray("record_templates")
                            .objects()
                            .firstOrNull { it.optString("id") == templateId && it.stringOrNull("deletedAt") == null }
                            ?.optLong("serverRevision") ?: prior.optLong("templateRevision", 0L)
                    val row =
                        JSONObject(prior.toString())
                            .put("id", UUID.randomUUID().toString())
                            .put(
                                "ruleRevision",
                                plan.optLong("currentRuleRevision", 1) + 1,
                            ).put("effectiveFromSlot", slot)
                            .put("anchorLocalDateTime", prior.getString("anchorLocalDateTime"))
                            .put(
                                "templateId",
                                templateId,
                            ).put(
                                "templateRevision",
                                templateRevision,
                            ).put("frequency", changes.optString("frequency", prior.getString("frequency")))
                            .put("intervalStep", changes.optInt("interval", prior.optInt("intervalStep", 1)))
                            .put("weekDays", changes.optJSONArray("weekDays") ?: prior.optJSONArray("weekDays") ?: JSONArray())
                            .put("monthDays", changes.optJSONArray("monthDays") ?: prior.optJSONArray("monthDays") ?: JSONArray())
                            .put(
                                "endAt",
                                changes.opt("endAt")?.takeUnless { it == JSONObject.NULL } ?: prior.opt("endAt") ?: JSONObject.NULL,
                            ).put(
                                "maxCount",
                                changes.opt("maxCount")?.takeUnless { it == JSONObject.NULL } ?: prior.opt("maxCount") ?: JSONObject.NULL,
                            ).put("serverRevision", 0)
                            .put("deletedAt", JSONObject.NULL)
                    for (i in 0 until segments.length()) {
                        val old = segments.getJSONObject(i)
                        if (old.optString("planId") == id && old.optLong("effectiveFromSlot", -1) >= slot) old.put("deletedAt", "PENDING")
                    }
                    segments.put(row)
                    plan.put("currentRuleRevision", row.getLong("ruleRevision")).put("serverRevision", plan.optLong("serverRevision") + 1)
                }
            }

            "recurrence.setState" -> {
                if (planIndex != null) {
                    val plan = plans.getJSONObject(planIndex)
                    plan.put("state", body.getString("state")).put("serverRevision", plan.optLong("serverRevision") + 1)
                    if (body.getString("state") == "ENDED") plan.put("nextDueAt", JSONObject.NULL)
                }
            }

            "recurrence.delete" -> {
                if (planIndex != null) {
                    val plan = plans.getJSONObject(planIndex)
                    plan.put("state", "DELETED").put("deletedAt", "PENDING").put("nextDueAt", JSONObject.NULL).put(
                        "serverRevision",
                        plan.optLong("serverRevision") + 1,
                    )
                }
            }
        }
    }

    private fun applySavingPrediction(
        tables: JSONObject,
        command: String,
        body: JSONObject,
        id: String,
        owner: UUID,
        epoch: UUID,
    ) {
        val plans = tables.getJSONArray("saving_plans")
        val entries = tables.getJSONArray("saving_entries")

        fun planIndex() =
            (0 until plans.length()).firstOrNull {
                plans.getJSONObject(it).optString("id") == id &&
                    plans.getJSONObject(it).isNull("deletedAt")
            }
        when (command) {
            "saving.create" -> {
                val source = body.getJSONObject("plan")
                val row =
                    JSONObject(source.toString())
                        .put("id", id)
                        .put("ownerId", owner.toString())
                        .put("datasetEpoch", epoch.toString())
                        .put("targetCurrency", source.getJSONObject("target").getString("currency"))
                        .put("initialSavedCurrency", source.getJSONObject("initialSaved").getString("currency"))
                        .put("firstAmountCurrency", source.optJSONObject("firstAmount")?.getString("currency") ?: JSONObject.NULL)
                        .put("incrementCurrency", source.optJSONObject("increment")?.getString("currency") ?: JSONObject.NULL)
                        .put("serverRevision", 0)
                        .put("deletedAt", JSONObject.NULL)
                plans.put(row)
            }

            "saving.update" -> {
                val index = planIndex() ?: return
                val row = plans.getJSONObject(index)
                val changes = body.getJSONObject("changes")
                changes.keys().forEach { key -> row.put(key, changes.get(key)) }
                row.put("serverRevision", row.optLong("serverRevision") + 1)
            }

            "saving.entry" -> {
                val index = planIndex() ?: return
                val row = plans.getJSONObject(index)
                val input = body
                val entryId = input.getString("entryId")
                val entry =
                    JSONObject()
                        .put("id", entryId)
                        .put("ownerId", owner.toString())
                        .put("datasetEpoch", epoch.toString())
                        .put("planId", id)
                        .put("kind", input.getString("kind"))
                        .put("amount", input.getJSONObject("amount"))
                        .put("occurredAt", input.getString("occurredAt"))
                        .put("cardIndex", input.opt("cardIndex") ?: JSONObject.NULL)
                        .put("remark", input.optString("remark"))
                        .put("linkedRecordId", JSONObject.NULL)
                        .put("cancelledAt", JSONObject.NULL)
                        .put("serverRevision", 0)
                        .put("deletedAt", JSONObject.NULL)
                val linked = input.optJSONObject("linkedTransfer")
                if (linked != null) {
                    val recordId = linked.getString("recordId")
                    val record = linked.getJSONObject("record")
                    applyRecordCreate(tables, record, recordId, owner, epoch)
                    entry.put("linkedRecordId", recordId)
                    val recordRow = tables.getJSONArray("records").objects().first { it.optString("id") == recordId }
                    entry.put("linkedTransferEventId", recordRow.getString("eventId"))
                }
                entries.put(entry)
                row.put("serverRevision", row.optLong("serverRevision") + 1)
            }

            "saving.cancelEntry" -> {
                val entryId = body.getString("entryId")
                val entry = entries.objects().firstOrNull { it.optString("id") == entryId && it.isNull("deletedAt") } ?: return
                if (!entry.isNull("cancelledAt")) return
                entry
                    .put(
                        "cancelledAt",
                        java.text
                            .SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.ROOT)
                            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                            .format(java.util.Date()),
                    ).put("serverRevision", entry.optLong("serverRevision") + 1)
                entry.stringOrNull("linkedRecordId")?.takeIf(String::isNotBlank)?.let { applyRecordDelete(tables, it, owner, epoch) }
                planIndex()?.let { index ->
                    plans.getJSONObject(index).put(
                        "serverRevision",
                        plans.getJSONObject(index).optLong("serverRevision") + 1,
                    )
                }
            }

            "saving.archive" -> {
                val index = planIndex() ?: return
                plans
                    .getJSONObject(index)
                    .put("state", if (body.getBoolean("archived")) "ARCHIVED" else "ACTIVE")
                    .put("serverRevision", plans.getJSONObject(index).optLong("serverRevision") + 1)
            }

            "saving.delete" -> {
                val index = planIndex() ?: return
                plans
                    .getJSONObject(index)
                    .put("deletedAt", "PENDING")
                    .put("state", "DELETED")
                    .put("serverRevision", plans.getJSONObject(index).optLong("serverRevision") + 1)
                if (body.optString("transferStrategy") == "DELETE_TRANSFERS") {
                    entries
                        .objects()
                        .filter { it.optString("planId") == id && it.isNull("cancelledAt") }
                        .mapNotNull {
                            it
                                .stringOrNull(
                                    "linkedRecordId",
                                )?.takeIf(String::isNotBlank)
                        }.forEach { applyRecordDelete(tables, it, owner, epoch) }
                }
            }
        }
    }

    private fun applyTemplateWrite(
        tables: JSONObject,
        write: JSONObject,
        id: String,
        owner: UUID,
        epoch: UUID,
        old: JSONObject?,
    ) {
        val record = write.getJSONObject("record")
        val input = RecordInputJson.decode(record.getJSONObject("input"))
        require(input !is RecordInput.Expense || input.recordCurrencyAmounts == null) { "UNSUPPORTED_FX" }
        val kind =
            when (input) {
                is RecordInput.Expense -> "EXPENSE"
                is RecordInput.Income -> "INCOME"
                is RecordInput.Transfer -> "TRANSFER"
            }
        val gross =
            when (input) {
                is RecordInput.Expense -> moneyJson(input.gross)
                is RecordInput.Income -> moneyJson(input.amount)
                is RecordInput.Transfer -> JSONObject.NULL
            }
        val discount = (input as? RecordInput.Expense)?.discount?.let(::moneyJson) ?: JSONObject.NULL
        val revision = old?.optLong("serverRevision", 0)?.plus(1) ?: 1L
        val storedInput =
            when (input) {
                is RecordInput.Expense -> {
                    JSONObject()
                        .put("kind", "EXPENSE")
                        .put("gross", moneyJson(input.gross))
                        .put("discount", input.discount?.let(::moneyJson) ?: JSONObject.NULL)
                        .put(
                            "allocations",
                            JSONArray().also { array ->
                                input.allocations.forEach { array.put(templateAllocation(it.accountId, it.amount)) }
                            },
                        ).put("countInStatistics", input.countInStatistics)
                        .put("countInBudget", input.countInBudget)
                }

                is RecordInput.Income -> {
                    JSONObject()
                        .put("kind", "INCOME")
                        .put("amount", moneyJson(input.amount))
                        .put(
                            "allocations",
                            JSONArray().also { array ->
                                input.allocations.forEach { array.put(templateAllocation(it.accountId, it.amount)) }
                            },
                        ).put("countInStatistics", input.countInStatistics)
                }

                is RecordInput.Transfer -> {
                    JSONObject()
                        .put("kind", "TRANSFER")
                        .put(
                            "from",
                            templateAllocation(input.from.accountId, input.from.amount),
                        ).put("to", templateAllocation(input.to.accountId, input.to.amount))
                        .put("fee", input.fee?.let { templateAllocation(it.accountId, it.amount) } ?: JSONObject.NULL)
                        .put("bonus", input.bonus?.let { templateAllocation(it.accountId, it.amount) } ?: JSONObject.NULL)
                }
            }
        val root =
            JSONObject()
                .put("id", id)
                .put("ownerId", owner.toString())
                .put("datasetEpoch", epoch.toString())
                .put("name", write.getString("name"))
                .put("ledgerId", record.getString("ledgerId"))
                .put("categoryId", record.opt("categoryId") ?: JSONObject.NULL)
                .put("feeCategoryId", record.opt("feeCategoryId") ?: JSONObject.NULL)
                .put("bonusCategoryId", record.opt("bonusCategoryId") ?: JSONObject.NULL)
                .put("occurredAt", record.getString("occurredAt"))
                .put("timezone", record.getString("timezone"))
                .put("localDate", record.getString("localDate"))
                .put("remark", record.getString("remark"))
                .put("inputKind", kind)
                .put("gross", gross)
                .put("discount", discount)
                .put("input", storedInput)
                .put("record", JSONObject(record.toString()).put("validityReason", JSONObject.NULL))
                .put("allocations", JSONArray())
                .put("tags", JSONArray())
                .put(
                    "countInStatistics",
                    when (input) {
                        is RecordInput.Expense -> input.countInStatistics
                        is RecordInput.Income -> input.countInStatistics
                        is RecordInput.Transfer -> false
                    },
                ).put("countInBudget", (input as? RecordInput.Expense)?.countInBudget ?: false)
                .put("serverRevision", revision)
                .put("deletedAt", JSONObject.NULL)
        val roots = tables.getJSONArray("record_templates")
        val index = (0 until roots.length()).firstOrNull { roots.getJSONObject(it).optString("id") == id }
        if (index == null) roots.put(root) else roots.put(index, root)
        val allocations = tables.getJSONArray("template_allocations")
        val tags = tables.getJSONArray("template_tags")
        allocations
            .objects()
            .filter {
                it.optString(
                    "templateId",
                ) == id && it.isNull("deletedAt")
            }.forEach { it.put("deletedAt", "PENDING") }
        tags.objects().filter { it.optString("templateId") == id && it.isNull("deletedAt") }.forEach { it.put("deletedAt", "PENDING") }
        val allocationRows =
            when (input) {
                is RecordInput.Expense -> {
                    input.allocations.map { "PAYMENT" to it }
                }

                is RecordInput.Income -> {
                    input.allocations.map { "RECEIPT" to it }
                }

                is RecordInput.Transfer -> {
                    buildList {
                        add("FROM" to input.from)
                        add("TO" to input.to)
                        input.fee?.let { add("FEE" to it) }
                        input.bonus?.let { add("BONUS" to it) }
                    }
                }
            }
        allocationRows.forEach { (role, allocation) ->
            allocations.put(
                JSONObject()
                    .put("id", UUID.randomUUID().toString())
                    .put("ownerId", owner.toString())
                    .put("datasetEpoch", epoch.toString())
                    .put("templateId", id)
                    .put("accountId", allocation.accountId.toString())
                    .put("role", role)
                    .put("amount", moneyJson(allocation.amount))
                    .put("serverRevision", 0)
                    .put("deletedAt", JSONObject.NULL),
            )
        }
        record.getJSONArray("tagIds").let { values ->
            for (i in 0 until values.length()) {
                tags.put(
                    JSONObject()
                        .put("id", UUID.randomUUID().toString())
                        .put("ownerId", owner.toString())
                        .put(
                            "datasetEpoch",
                            epoch.toString(),
                        ).put("templateId", id)
                        .put("tagId", values.getString(i))
                        .put("serverRevision", 0)
                        .put("deletedAt", JSONObject.NULL),
                )
            }
        }
    }

    private fun applyBudgetConfigure(
        tables: JSONObject,
        payload: JSONObject,
        ledgerId: String,
    ) {
        val budgets = tables.getJSONArray("budgets")
        var budget = budgets.objects().firstOrNull { it.string("id") == ledgerId }
        if (budget == null) {
            budget =
                JSONObject()
                    .put(
                        "id",
                        ledgerId,
                    ).put("ledgerId", ledgerId)
                    .put("serverRevision", 0)
                    .put("deviceId", JSONObject.NULL)
                    .put("configs", JSONArray())
            budgets.put(budget)
        }
        budget.put("serverRevision", budget.optLong("serverRevision") + 1)
        val configs = budget.optJSONArray("configs") ?: JSONArray().also { budget.put("configs", it) }
        val period = payload.getString("periodType")
        val scope = payload.getString("scope")
        val key = if (scope == "DEFAULT") payload.getString("effectiveFrom") else payload.getString("periodStart")
        val existing =
            configs.objects().firstOrNull {
                it.string("periodType") == period && it.string("scope") == scope && it.stringOrNull("deletedAt") == null &&
                    (it.stringOrNull(if (scope == "DEFAULT") "effectiveFrom" else "periodStart") == key)
            }
        val config = existing ?: JSONObject().put("id", UUID.randomUUID().toString()).also(configs::put)
        config
            .put("periodType", period)
            .put("scope", scope)
            .put(if (scope == "DEFAULT") "effectiveFrom" else "periodStart", key)
            .put("deletedAt", JSONObject.NULL)
            .put("entries", payload.getJSONArray("entries"))
    }

    private fun applyBudgetReset(
        tables: JSONObject,
        payload: JSONObject,
        ledgerId: String,
    ) {
        val budgets = tables.getJSONArray("budgets")
        var budget = budgets.objects().firstOrNull { it.string("id") == ledgerId }
        if (budget == null && payload.getString("mode") == "CLEAR_CURRENT") {
            budget =
                JSONObject()
                    .put(
                        "id",
                        ledgerId,
                    ).put("ledgerId", ledgerId)
                    .put("serverRevision", 0)
                    .put("deviceId", JSONObject.NULL)
                    .put("configs", JSONArray())
            budgets.put(budget)
        }
        if (budget == null) return
        budget.put("serverRevision", budget.optLong("serverRevision") + 1)
        val configs = budget.optJSONArray("configs") ?: JSONArray().also { budget.put("configs", it) }
        val period = payload.getString("periodType")
        val start = payload.getString("periodStart")
        val config =
            configs.objects().firstOrNull {
                it.string("periodType") == period && it.string("scope") == "CURRENT" && it.stringOrNull("periodStart") == start &&
                    it.isNull("deletedAt")
            }
        if (payload.getString("mode") == "RESET_DEFAULT") {
            config?.put(
                "deletedAt",
                java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.ROOT).format(java.util.Date()),
            )
        } else {
            val entries =
                config?.optJSONArray("entries") ?: JSONArray().also { array ->
                    val ledger = tables.array("ledgers").objects().first { it.string("id") == ledgerId }
                    array.put(
                        JSONObject()
                            .put("categoryId", JSONObject.NULL)
                            .put(
                                "amount",
                                JSONObject().put("unscaled", "0").put("scale", 0).put("currency", ledger.string("baseCurrency")),
                            ),
                    )
                }
            for (i in 0 until entries.length()) {
                val entry = entries.getJSONObject(i)
                val amount = entry.getJSONObject("amount")
                entry.put("amount", JSONObject(amount.toString()).put("unscaled", "0"))
            }
            val current =
                config ?: JSONObject()
                    .put("id", UUID.randomUUID().toString())
                    .put("periodType", period)
                    .put(
                        "scope",
                        "CURRENT",
                    ).put("periodStart", start)
                    .put("deletedAt", JSONObject.NULL)
                    .put("entries", entries)
                    .also(configs::put)
            current.put("entries", entries)
        }
    }

    private fun applyRecordCreate(
        tables: JSONObject,
        record: JSONObject,
        id: String,
        owner: UUID,
        epoch: UUID,
    ) {
        val input = record.getJSONObject("input")
        val kind = input.getString("kind")
        require(kind in setOf("EXPENSE", "INCOME", "TRANSFER")) { "UNSUPPORTED_ACCOUNT_RECORD_PREDICTION" }
        val eventId = UUID.randomUUID().toString()
        val decoded = RecordInputJson.decode(input)
        val effects = decoded.calculate()
        val gross =
            when (decoded) {
                is RecordInput.Expense -> moneyJson(decoded.gross)
                is RecordInput.Income -> moneyJson(decoded.amount)
                is RecordInput.Transfer -> moneyJson(decoded.from.amount)
            }
        val actual =
            when (decoded) {
                is RecordInput.Expense -> decoded.discount?.let(decoded.gross::checkedSubtract) ?: decoded.gross
                is RecordInput.Income -> decoded.amount
                is RecordInput.Transfer -> decoded.from.amount
            }
        val row =
            JSONObject()
                .put("id", id)
                .put("ownerId", owner.toString())
                .put("datasetEpoch", epoch.toString())
                .put("eventId", eventId)
                .put("ledgerId", record.getString("ledgerId"))
                .put("categoryId", record.opt("categoryId") ?: JSONObject.NULL)
                .put("direction", kind)
                .put("status", "POSTED")
                .put("occurredAt", record.getString("occurredAt"))
                .put("remark", record.getString("remark"))
                .put("gross", gross)
                .put("actual", moneyJson(actual))
                .put("discount", input.opt("discount") ?: JSONObject.NULL)
                .put(
                    "countInStatistics",
                    input.canonicalBoolean("countInStatistics"),
                ).put("countInBudget", input.canonicalBoolean("countInBudget"))
                .put("feeCategoryId", record.opt("feeCategoryId") ?: JSONObject.NULL)
                .put(
                    "bonusCategoryId",
                    record.opt("bonusCategoryId") ?: JSONObject.NULL,
                ).put("parentRecordId", JSONObject.NULL)
                .put("templateId", record.opt("templateId") ?: JSONObject.NULL)
                .put("attachmentIds", record.arrayOrEmpty("attachmentIds"))
                .put("claimState", "NONE")
                .put("serverRevision", 0)
                .put("deletedAt", JSONObject.NULL)
        tables.getJSONArray("records").put(row)
        appendPredictedEffects(tables, record, id, eventId, owner, epoch, effects)
        record.optJSONArray("tagIds")?.let { tags ->
            for (index in 0 until tags.length()) {
                tables.getJSONArray("record_tags").put(
                    JSONObject()
                        .put("id", UUID.randomUUID().toString())
                        .put("ownerId", owner.toString())
                        .put("datasetEpoch", epoch.toString())
                        .put("recordId", id)
                        .put("tagId", tags.getString(index))
                        .put("deletedAt", JSONObject.NULL),
                )
            }
        }
    }

    private fun applyRecordUpdate(
        tables: JSONObject,
        patch: JSONObject,
        id: String,
        owner: UUID,
        epoch: UUID,
    ) {
        val records = tables.getJSONArray("records")
        val index =
            (0 until records.length()).firstOrNull {
                records.getJSONObject(it).optString("id") == id &&
                    records.getJSONObject(it).isNull("deletedAt")
            }
                ?: return
        val old = records.getJSONObject(index)
        require(old.optString("status") == "POSTED" && claimState(tables, id) == "NONE" && old.isNull("parentRecordId")) {
            "UNSUPPORTED_ACCOUNT_RECORD_DEPENDENCY"
        }
        val oldEventId = old.getString("eventId")
        reverseRecordAndChildren(tables, old, id, owner, epoch)
        val input = patch.getJSONObject("input")
        require(input.getString("kind") in setOf("EXPENSE", "INCOME", "TRANSFER")) { "UNSUPPORTED_ACCOUNT_RECORD_PREDICTION" }
        val decoded = RecordInputJson.decode(input)
        val effects = decoded.calculate()
        val actual =
            when (decoded) {
                is RecordInput.Expense -> decoded.discount?.let(decoded.gross::checkedSubtract) ?: decoded.gross
                is RecordInput.Income -> decoded.amount
                is RecordInput.Transfer -> decoded.from.amount
            }
        val eventId = UUID.randomUUID().toString()
        val replacement =
            JSONObject(patch.toString())
                .put("id", id)
                .put("ownerId", owner.toString())
                .put("datasetEpoch", epoch.toString())
                .put("eventId", eventId)
                .put("direction", input.getString("kind"))
                .put(
                    "gross",
                    when (decoded) {
                        is RecordInput.Expense -> moneyJson(decoded.gross)
                        is RecordInput.Income -> moneyJson(decoded.amount)
                        is RecordInput.Transfer -> moneyJson(decoded.from.amount)
                    },
                ).put("actual", moneyJson(actual))
                .put("discount", input.opt("discount") ?: JSONObject.NULL)
                .put("feeCategoryId", patch.opt("feeCategoryId") ?: JSONObject.NULL)
                .put(
                    "bonusCategoryId",
                    patch.opt("bonusCategoryId") ?: JSONObject.NULL,
                ).put(
                    "countInStatistics",
                    input.canonicalBoolean("countInStatistics"),
                ).put("countInBudget", input.canonicalBoolean("countInBudget"))
                .put("status", "POSTED")
                .put("claimState", old.optString("claimState", "NONE"))
                .put("serverRevision", old.optLong("serverRevision") + 1)
                .put("deletedAt", JSONObject.NULL)
        // Omitting attachmentIds preserves server references, including attachments newer than this mirror.
        if (!patch.has("attachmentIds") && old.has("attachmentIds") && !old.isNull("attachmentIds")) {
            replacement.put("attachmentIds", old.get("attachmentIds"))
        }
        records.put(index, replacement)
        appendPredictedEffects(tables, replacement, id, eventId, owner, epoch, effects)
        patch.optJSONArray("tagIds")?.let { tags ->
            for (tagIndex in 0 until tags.length()) {
                tables.getJSONArray("record_tags").put(
                    JSONObject()
                        .put("id", UUID.randomUUID().toString())
                        .put("ownerId", owner.toString())
                        .put("datasetEpoch", epoch.toString())
                        .put("recordId", id)
                        .put("tagId", tags.getString(tagIndex))
                        .put("deletedAt", JSONObject.NULL),
                )
            }
        }
    }

    private fun applyRecordDelete(
        tables: JSONObject,
        id: String,
        owner: UUID,
        epoch: UUID,
    ) {
        val records = tables.getJSONArray("records")
        val index =
            (0 until records.length()).firstOrNull {
                records.getJSONObject(it).optString("id") == id &&
                    records.getJSONObject(it).isNull("deletedAt")
            }
                ?: return
        val record = records.getJSONObject(index)
        require(record.optString("status") == "POSTED" && claimState(tables, id) == "NONE" && record.isNull("parentRecordId")) {
            "UNSUPPORTED_ACCOUNT_RECORD_DEPENDENCY"
        }
        reverseRecordAndChildren(tables, record, id, owner, epoch)
        record.put("deletedAt", "PENDING").put("serverRevision", record.optLong("serverRevision") + 1)
    }

    private fun reverseRecordAndChildren(
        tables: JSONObject,
        record: JSONObject,
        id: String,
        owner: UUID,
        epoch: UUID,
    ) {
        val rootEventId = record.getString("eventId")
        val relations =
            tables.array("event_relations").objects().filter {
                it.stringOrNull("parentEventId") == rootEventId && it.stringOrNull("deletedAt") == null
            }
        val children =
            tables.array("records").objects().filter {
                it.stringOrNull("parentRecordId") == id && it.stringOrNull("deletedAt") == null
            }
        require(
            relations.size == children.size && relations.all { it.string("kind") in setOf("FEE", "DISCOUNT") } &&
                children.all { child -> relations.any { it.string("childEventId") == child.string("eventId") } },
        ) {
            "UNSUPPORTED_ACCOUNT_RECORD_DEPENDENCY"
        }
        reverseAndReplaceRecordFacts(tables, record, rootEventId, id, owner, epoch)
        children.forEach { child ->
            reverseAndReplaceRecordFacts(tables, child, child.string("eventId"), child.string("id"), owner, epoch)
            child.put("deletedAt", "PENDING").put("serverRevision", child.optLong("serverRevision") + 1)
        }
        relations.forEach { it.put("deletedAt", "PENDING") }
    }

    private fun reverseAndReplaceRecordFacts(
        tables: JSONObject,
        record: JSONObject,
        eventId: String,
        id: String,
        owner: UUID,
        epoch: UUID,
    ) {
        val reversalEventId = UUID.randomUUID().toString()
        val oldEvent =
            tables.getJSONArray("financial_events").objects().firstOrNull { it.optString("id") == eventId }
                ?: error("Canonical record event unavailable")
        tables.getJSONArray("financial_events").put(
            JSONObject()
                .put("id", reversalEventId)
                .put("ownerId", owner.toString())
                .put("datasetEpoch", epoch.toString())
                .put("ledgerId", record.getString("ledgerId"))
                .put("occurredAt", oldEvent.getString("occurredAt"))
                .put("timezone", oldEvent.optString("timezone", "UTC"))
                .put("serverRevision", 0)
                .put("reversalOf", eventId),
        )
        tables.getJSONArray("account_postings").objects().filter { it.optString("eventId") == eventId && it.isNull("reversalOf") }.forEach { posting ->
            val stored = money(posting.getJSONObject("signed")).checkedNegate()
            tables.getJSONArray("account_postings").put(
                JSONObject()
                    .put("id", UUID.randomUUID().toString())
                    .put("ownerId", owner.toString())
                    .put("datasetEpoch", epoch.toString())
                    .put("eventId", reversalEventId)
                    .put("accountId", posting.getString("accountId"))
                    .put("signed", moneyJson(stored))
                    .put("role", "REVERSAL")
                    .put("reversalOf", posting.getString("id")),
            )
        }
        for (table in listOf("record_allocations", "record_tags")) {
            tables
                .getJSONArray(table)
                .objects()
                .filter { it.optString("recordId") == id && it.isNull("deletedAt") }
                .forEach { it.put("deletedAt", "PENDING") }
        }
        for (table in listOf("stat_contributions", "budget_contributions")) {
            tables.getJSONArray(table).objects().filter { it.optString("eventId") == eventId }.forEach { contribution ->
                val reversedAmount = money(contribution.getJSONObject("amount")).checkedNegate()
                val reversed =
                    JSONObject(contribution.toString())
                        .put("id", UUID.randomUUID().toString())
                        .put("eventId", reversalEventId)
                        .put("amount", moneyJson(reversedAmount))
                tables.getJSONArray(table).put(reversed)
            }
        }
        if (record.stringOrNull("parentRecordId") != null) {
            require(
                tables
                    .array(
                        "records",
                    ).objects()
                    .none { it.stringOrNull("parentRecordId") == id && it.stringOrNull("deletedAt") == null } &&
                    tables.array("event_relations").objects().none {
                        it.stringOrNull("parentEventId") == eventId &&
                            it.stringOrNull("deletedAt") == null
                    },
            ) {
                "UNSUPPORTED_ACCOUNT_RECORD_DEPENDENCY"
            }
        }
    }

    private fun appendPredictedEffects(
        tables: JSONObject,
        record: JSONObject,
        id: String,
        eventId: String,
        owner: UUID,
        epoch: UUID,
        effects: dev.icos.app.domain.RecordEffects,
    ) {
        val input = record.getJSONObject("input")
        val decoded = RecordInputJson.decode(input)
        if (decoded is RecordInput.Transfer) {
            appendPredictedTransfer(tables, record, id, eventId, owner, epoch, decoded)
            return
        }
        tables.getJSONArray("financial_events").put(
            JSONObject()
                .put("id", eventId)
                .put("ownerId", owner.toString())
                .put("datasetEpoch", epoch.toString())
                .put("ledgerId", record.getString("ledgerId"))
                .put("occurredAt", record.getString("occurredAt"))
                .put("timezone", record.getString("timezone"))
                .put("serverRevision", 0),
        )
        val allocations: List<Triple<String, dev.icos.app.domain.AccountAmount, Money>> =
            when (decoded) {
                is RecordInput.Expense -> {
                    decoded.allocations.mapIndexed { index, allocation ->
                        Triple("PAYMENT", allocation, decoded.recordCurrencyAmounts?.get(index) ?: allocation.amount)
                    }
                }

                is RecordInput.Income -> {
                    decoded.allocations.map { Triple("RECEIPT", it, it.amount) }
                }

                is RecordInput.Transfer -> {
                    error("handled transfer")
                }
            }
        allocations.forEach { (role, allocation, recordAmount) ->
            val hasFxAllocation = allocation.amount.currency != recordAmount.currency
            val fxSnapshotId =
                if (hasFxAllocation) {
                    val id = UUID.randomUUID().toString()
                    val rate =
                        java.math
                            .BigDecimal(java.math.BigInteger.valueOf(allocation.amount.unscaled), allocation.amount.scale)
                            .divide(
                                java.math.BigDecimal(java.math.BigInteger.valueOf(recordAmount.unscaled), recordAmount.scale),
                                18,
                                java.math.RoundingMode.HALF_UP,
                            )
                    require(rate.signum() > 0 && rate.precision() <= 38) { "INVALID_FX_RATE" }
                    tables.getJSONArray("fx_snapshots").put(
                        JSONObject()
                            .put("id", id)
                            .put("fromCurrency", recordAmount.currency)
                            .put("toCurrency", allocation.amount.currency)
                            .put("rate", rate.toPlainString())
                            .put("observedAt", record.getString("occurredAt"))
                            .put("source", "INFERRED_FROM_EXPLICIT_AMOUNTS")
                            .put("serverRevision", 1)
                            .put("deletedAt", JSONObject.NULL),
                    )
                    id
                } else {
                    null
                }
            tables.getJSONArray("record_allocations").put(
                JSONObject()
                    .put("id", UUID.randomUUID().toString())
                    .put("ownerId", owner.toString())
                    .put("datasetEpoch", epoch.toString())
                    .put("recordId", id)
                    .put("accountId", allocation.accountId.toString())
                    .put("role", role)
                    .put("accountAmount", moneyJson(allocation.amount))
                    .put("accountAmountUnscaled", allocation.amount.unscaled.toString())
                    .put("accountAmountScale", allocation.amount.scale)
                    .put("accountAmountCurrency", allocation.amount.currency)
                    .put("recordCurrencyAmount", moneyJson(recordAmount))
                    .put("fxSnapshotId", fxSnapshotId ?: JSONObject.NULL)
                    .put("hasFxAllocation", hasFxAllocation)
                    .put("deletedAt", JSONObject.NULL),
            )
        }
        effects.postings.forEach { posting ->
            val account = tables.getJSONArray("accounts").objects().first { it.string("id") == posting.accountId.toString() }
            val stored = liabilityAwareStoredAmount(account.string("type"), posting.amount)
            tables.getJSONArray("account_postings").put(
                JSONObject()
                    .put("id", UUID.randomUUID().toString())
                    .put("ownerId", owner.toString())
                    .put("datasetEpoch", epoch.toString())
                    .put("eventId", eventId)
                    .put("accountId", posting.accountId.toString())
                    .put("signed", moneyJson(stored))
                    .put("role", "RECORD")
                    .put("reversalOf", JSONObject.NULL),
            )
        }
        effects.statistics.forEach { contribution ->
            tables.getJSONArray("stat_contributions").put(
                JSONObject()
                    .put("id", UUID.randomUUID().toString())
                    .put("ownerId", owner.toString())
                    .put(
                        "datasetEpoch",
                        epoch.toString(),
                    ).put("eventId", eventId)
                    .put("originRecordId", id)
                    .put("ledgerId", record.getString("ledgerId"))
                    .put("categoryId", record.opt("categoryId") ?: JSONObject.NULL)
                    .put("direction", contribution.direction.name)
                    .put(
                        "periodDate",
                        record.getString("localDate"),
                    ).put("amount", moneyJson(contribution.amount))
                    .put("deletedAt", JSONObject.NULL),
            )
        }
        val budgetExcluded =
            effects.budget.isNotEmpty() &&
                record.optString("categoryId").takeIf(String::isNotBlank)?.let { isBudgetExcluded(tables, it) } == true
        if (!budgetExcluded) {
            effects.budget.forEach { amount ->
                val categoryId =
                    record.optString("categoryId").takeIf(String::isNotBlank)
                        ?: error("Canonical expense contribution category unavailable")
                tables.getJSONArray("budget_contributions").put(
                    JSONObject()
                        .put("id", UUID.randomUUID().toString())
                        .put("ownerId", owner.toString())
                        .put(
                            "datasetEpoch",
                            epoch.toString(),
                        ).put("eventId", eventId)
                        .put("originRecordId", id)
                        .put("ledgerId", record.getString("ledgerId"))
                        .put("categoryId", categoryId)
                        .put("direction", "EXPENSE")
                        .put("periodDate", record.getString("localDate"))
                        .put("amount", moneyJson(amount))
                        .put("deletedAt", JSONObject.NULL),
                )
            }
        }
    }

    private fun isBudgetExcluded(
        tables: JSONObject,
        categoryId: String,
    ): Boolean {
        val categories = tables.array("categories")
        val category =
            categories.objects().firstOrNull { it.optString("id") == categoryId && it.isNull("deletedAt") }
                ?: error("Canonical record category unavailable")
        if (category.canonicalBoolean("excludeBudget")) return true
        val parentId = category.stringOrNull("parentId")?.takeIf(String::isNotBlank) ?: return false
        return categories
            .objects()
            .firstOrNull { it.optString("id") == parentId && it.isNull("deletedAt") }
            ?.canonicalBoolean("excludeBudget") ?: error("Canonical parent category unavailable")
    }

    private fun appendPredictedTransfer(
        tables: JSONObject,
        record: JSONObject,
        id: String,
        eventId: String,
        owner: UUID,
        epoch: UUID,
        input: RecordInput.Transfer,
    ) {
        val occurred = record.getString("occurredAt")
        val timezone = record.getString("timezone")
        val localDate = record.getString("localDate")
        val ledgerId = record.getString("ledgerId")
        input.fee?.let { allocation ->
            val categoryId =
                record.optString("feeCategoryId").takeIf(String::isNotBlank)
                    ?: error("Canonical transfer fee category unavailable")
            appendPredictedChildRecord(tables, record, id, eventId, owner, epoch, allocation, categoryId, "EXPENSE", "FEE")
        }
        input.bonus?.let { allocation ->
            val categoryId =
                record.optString("bonusCategoryId").takeIf(String::isNotBlank)
                    ?: error("Canonical transfer bonus category unavailable")
            appendPredictedChildRecord(tables, record, id, eventId, owner, epoch, allocation, categoryId, "INCOME", "DISCOUNT")
        }
        val rootEffects = RecordInput.Transfer(input.from, input.to, null, null).calculate()
        tables.getJSONArray("financial_events").put(
            JSONObject()
                .put("id", eventId)
                .put("ownerId", owner.toString())
                .put("datasetEpoch", epoch.toString())
                .put("ledgerId", ledgerId)
                .put("occurredAt", occurred)
                .put("timezone", timezone)
                .put("serverRevision", 0),
        )
        listOf("FROM" to input.from, "TO" to input.to).forEach { (role, allocation) ->
            tables.getJSONArray("record_allocations").put(
                JSONObject()
                    .put("id", UUID.randomUUID().toString())
                    .put("ownerId", owner.toString())
                    .put("datasetEpoch", epoch.toString())
                    .put("recordId", id)
                    .put("accountId", allocation.accountId.toString())
                    .put("role", role)
                    .put("recordCurrencyAmount", moneyJson(allocation.amount))
                    .put("deletedAt", JSONObject.NULL),
            )
        }
        rootEffects.postings.forEach { posting ->
            val account = tables.getJSONArray("accounts").objects().first { it.string("id") == posting.accountId.toString() }
            val stored = liabilityAwareStoredAmount(account.string("type"), posting.amount)
            tables.getJSONArray("account_postings").put(
                JSONObject()
                    .put("id", UUID.randomUUID().toString())
                    .put("ownerId", owner.toString())
                    .put("datasetEpoch", epoch.toString())
                    .put("eventId", eventId)
                    .put("accountId", posting.accountId.toString())
                    .put("signed", moneyJson(stored))
                    .put("role", "RECORD")
                    .put("reversalOf", JSONObject.NULL),
            )
        }
        rootEffects.statistics.forEach { contribution ->
            tables.getJSONArray("stat_contributions").put(
                JSONObject()
                    .put("id", UUID.randomUUID().toString())
                    .put("ownerId", owner.toString())
                    .put("datasetEpoch", epoch.toString())
                    .put("eventId", eventId)
                    .put("originRecordId", id)
                    .put("ledgerId", ledgerId)
                    .put("categoryId", JSONObject.NULL)
                    .put("direction", contribution.direction.name)
                    .put("periodDate", localDate)
                    .put("amount", moneyJson(contribution.amount))
                    .put("deletedAt", JSONObject.NULL),
            )
        }
    }

    private fun appendPredictedChildRecord(
        tables: JSONObject,
        parent: JSONObject,
        parentId: String,
        parentEventId: String,
        owner: UUID,
        epoch: UUID,
        allocation: dev.icos.app.domain.AccountAmount,
        categoryId: String,
        direction: String,
        relationKind: String,
    ) {
        val childId = UUID.randomUUID().toString()
        val childEventId = UUID.randomUUID().toString()
        val occurred = parent.getString("occurredAt")
        val timezone = parent.getString("timezone")
        val localDate = parent.getString("localDate")
        val ledgerId = parent.getString("ledgerId")
        val input =
            if (direction == "EXPENSE") {
                RecordInput.Expense(allocation.amount, null, listOf(allocation), true, true)
            } else {
                RecordInput.Income(allocation.amount, listOf(allocation), true)
            }
        val effects = input.calculate()
        tables.getJSONArray("records").put(
            JSONObject()
                .put("id", childId)
                .put("ownerId", owner.toString())
                .put("datasetEpoch", epoch.toString())
                .put(
                    "eventId",
                    childEventId,
                ).put("ledgerId", ledgerId)
                .put("categoryId", categoryId)
                .put("direction", direction)
                .put("status", "POSTED")
                .put(
                    "occurredAt",
                    occurred,
                ).put(
                    "remark",
                    parent.optString("remark"),
                ).put("gross", moneyJson(allocation.amount))
                .put("actual", moneyJson(allocation.amount))
                .put("discount", JSONObject.NULL)
                .put("countInStatistics", true)
                .put("countInBudget", direction == "EXPENSE")
                .put("parentRecordId", parentId)
                .put("claimState", "NONE")
                .put("serverRevision", 0)
                .put("deletedAt", JSONObject.NULL),
        )
        tables.getJSONArray("financial_events").put(
            JSONObject()
                .put("id", childEventId)
                .put("ownerId", owner.toString())
                .put("datasetEpoch", epoch.toString())
                .put("ledgerId", ledgerId)
                .put("occurredAt", occurred)
                .put("timezone", timezone)
                .put("serverRevision", 0),
        )
        tables.getJSONArray("event_relations").put(
            JSONObject()
                .put("id", UUID.randomUUID().toString())
                .put("ownerId", owner.toString())
                .put("datasetEpoch", epoch.toString())
                .put(
                    "parentEventId",
                    parentEventId,
                ).put("childEventId", childEventId)
                .put("kind", relationKind)
                .put("deletedAt", JSONObject.NULL),
        )
        tables.getJSONArray("record_allocations").put(
            JSONObject()
                .put("id", UUID.randomUUID().toString())
                .put("ownerId", owner.toString())
                .put("datasetEpoch", epoch.toString())
                .put("recordId", childId)
                .put("accountId", allocation.accountId.toString())
                .put(
                    "role",
                    if (direction ==
                        "EXPENSE"
                    ) {
                        "PAYMENT"
                    } else {
                        "RECEIPT"
                    },
                ).put("recordCurrencyAmount", moneyJson(allocation.amount))
                .put("deletedAt", JSONObject.NULL),
        )
        effects.postings.forEach { posting ->
            val account = tables.getJSONArray("accounts").objects().first { it.string("id") == posting.accountId.toString() }
            val stored = liabilityAwareStoredAmount(account.string("type"), posting.amount)
            tables.getJSONArray("account_postings").put(
                JSONObject()
                    .put("id", UUID.randomUUID().toString())
                    .put("ownerId", owner.toString())
                    .put("datasetEpoch", epoch.toString())
                    .put("eventId", childEventId)
                    .put("accountId", posting.accountId.toString())
                    .put("signed", moneyJson(stored))
                    .put("role", "RECORD")
                    .put("reversalOf", JSONObject.NULL),
            )
        }
        effects.statistics.forEach { contribution ->
            tables.getJSONArray("stat_contributions").put(
                JSONObject()
                    .put("id", UUID.randomUUID().toString())
                    .put("ownerId", owner.toString())
                    .put("datasetEpoch", epoch.toString())
                    .put("eventId", childEventId)
                    .put("originRecordId", childId)
                    .put("ledgerId", ledgerId)
                    .put("categoryId", categoryId)
                    .put("direction", contribution.direction.name)
                    .put("periodDate", localDate)
                    .put("amount", moneyJson(contribution.amount))
                    .put("deletedAt", JSONObject.NULL),
            )
        }
        effects.budget.forEach { amount ->
            if (isBudgetExcluded(tables, categoryId)) return@forEach
            tables.getJSONArray("budget_contributions").put(
                JSONObject()
                    .put("id", UUID.randomUUID().toString())
                    .put("ownerId", owner.toString())
                    .put("datasetEpoch", epoch.toString())
                    .put("eventId", childEventId)
                    .put("originRecordId", childId)
                    .put("ledgerId", ledgerId)
                    .put("categoryId", categoryId)
                    .put("direction", "EXPENSE")
                    .put("periodDate", localDate)
                    .put("amount", moneyJson(amount))
                    .put("deletedAt", JSONObject.NULL),
            )
        }
    }

    private fun money(value: JSONObject) = Money(value.getString("unscaled").toLong(), value.getInt("scale"), value.getString("currency"))

    private fun canonicalCategories(tables: JSONObject): List<FinanceCategory> =
        canonicalCategories(tables.array("categories").objects())

    private fun canonicalCategories(rows: List<JSONObject>): List<FinanceCategory> =
        rows
            .filter { it.stringOrNull("deletedAt") == null }
            .map { row ->
                FinanceCategory(
                    row.uuid("id"),
                    row.string("name"),
                    direction(row.string("direction")),
                    row.uuidOrNull("parentId"),
                    row.timestampOrNull("archivedAt"),
                    row.optLong("sortOrder"),
                    row.optLong("serverRevision"),
                    row.uuid("ledgerId"),
                    color = row.stringOrNull("color"),
                    usesCanonicalColor = true,
                    excludeBudget = row.bool("excludeBudget"),
                    purpose = row.stringOrNull("purpose"),
                )
            }.sortedWith(compareBy<FinanceCategory> { it.sortOrder }.thenBy { it.id.toString() })

    internal fun readTables(
        tables: JSONObject,
        owner: UUID,
        epoch: UUID,
        selectedLedgerId: UUID? = null,
        now: Long = System.currentTimeMillis(),
        summary: JSONObject? = null,
        budgetPeriod: ReportPeriod = ReportPeriod.MONTH,
        statisticsZone: String =
            java.util.TimeZone
                .getDefault()
                .id,
    ): View {
        val ledgerRows =
            tables
                .array("ledgers")
                .objects()
                .filter { it.stringOrNull("deletedAt") == null }
                .sortedWith(compareBy<JSONObject> { it.optLong("sortOrder") }.thenBy { it.string("id") })
        val activeLedgerRows = ledgerRows.filter { it.stringOrNull("archivedAt") == null }
        val ledgers =
            activeLedgerRows.map {
                LedgerOption(it.uuid("id"), it.string("name"), it.string("baseCurrency"))
            }
        require(ledgers.isNotEmpty()) { "Canonical dataset has no active ledger" }
        val defaultId = activeLedgerRows.firstOrNull { it.has("isDefault") && it.canonicalBoolean("isDefault") }?.uuid("id")
        val selected =
            ledgers.firstOrNull { it.id == selectedLedgerId }
                ?: ledgers.firstOrNull { it.id == defaultId } ?: ledgers.first()
        val visibility =
            tables
                .array("account_ledger_visibility")
                .objects()
                .filter { it.stringOrNull("deletedAt") == null }
                .groupBy { it.string("accountId") }
        val accounts = tables.array("accounts").objects()
        val selectedAccounts =
            accounts.filter { row ->
                row.stringOrNull("deletedAt") == null && (
                    row.string("visibilityMode") == "ALL" ||
                        visibility[row.string("id")].orEmpty().any { it.string("ledgerId") == selected.id.toString() }
                )
            }
        val accountById = accounts.associateBy { it.string("id") }
        val postings = tables.array("account_postings").objects().filter { it.stringOrNull("deletedAt") == null }
        val balances =
            postings.groupBy { it.string("accountId") }.mapValues { (_, rows) ->
                val currency =
                    accountById[rows.first().string("accountId")]?.string("currency")
                        ?: rows.first().money("signed").currency
                rows.fold(Money(0, 0, currency)) { total, row ->
                    total.checkedAdd(row.money("signed"))
                }
            }
        val allFinanceAccounts =
            accounts.filter { it.stringOrNull("deletedAt") == null }.map { row ->
                val id = row.string("id")
                FinanceAccount(
                    UUID.fromString(id),
                    row.string("name"),
                    row.string("currency"),
                    balances[id] ?: Money(0, 0, row.string("currency")),
                    row.long("serverRevision"),
                    row.timestampOrNull("archivedAt"),
                    row.bool("includeInAssets"),
                    row.bool("selectable"),
                    UUID.fromString(row.string("groupId")),
                    row.long("sortOrder"),
                    row.string("type"),
                    row.string("visibilityMode"),
                    visibility[id].orEmpty().map { it.uuid("ledgerId") }.toSet(),
                )
            }
        val selectedAccountIds = selectedAccounts.mapTo(mutableSetOf()) { it.uuid("id") }
        val financeAccounts = allFinanceAccounts.filter { it.id in selectedAccountIds }
        val allFinanceCategories = canonicalCategories(tables)
        val ledgerCategories = allFinanceCategories.filter { it.ledgerId == selected.id }
        val financeCategories = ledgerCategories.filter { it.archivedAt == null }
        val categoryById = ledgerCategories.associateBy { it.id.toString() }
        val events =
            tables
                .array("financial_events")
                .objects()
                .filter { it.string("ledgerId") == selected.id.toString() }
                .associateBy { it.string("id") }
        val records =
            tables.array("records").objects().filter {
                it.string("ledgerId") == selected.id.toString() && it.stringOrNull("deletedAt") == null
            }
        val allocations =
            tables
                .array("record_allocations")
                .objects()
                .filter {
                    it.stringOrNull("deletedAt") == null
                }.groupBy { it.string("recordId") }
        val tagsByRecord =
            tables
                .array(
                    "record_tags",
                ).objects()
                .filter { it.stringOrNull("deletedAt") == null }
                .groupBy { it.string("recordId") }
        val allTags = tables.array("tags").objects().associateBy { it.string("id") }
        val financeRecords =
            records
                .filter { it.string("status") == "POSTED" }
                .map { row ->
                    val event = events.getValue(row.string("eventId"))
                    val input = input(tables, row, event, allocations[row.string("id")].orEmpty())
                    val tagIds =
                        tagsByRecord[row.string("id")].orEmpty().mapNotNull { link ->
                            val tag = allTags[link.string("tagId")] ?: return@mapNotNull null
                            if (tag.stringOrNull("deletedAt") == null) UUID.fromString(tag.string("id")) else null
                        }
                    FinanceRecord(
                        UUID.fromString(row.string("id")),
                        input,
                        row.timestamp("occurredAt"),
                        event.string("timezone"),
                        row.string("remark"),
                        row.uuidOrNull("categoryId"),
                        row.long("serverRevision"),
                        claimState(tables, row.string("id")),
                        tagIds,
                        accountMutationSupported = accountRecordMutationSupported(tables, row),
                        parentRecordId = row.uuidOrNull("parentRecordId"),
                        attachmentIds = row.attachmentIds(),
                    )
                }.sortedByDescending { it.occurredAtMillis }
        val statisticRows =
            tables
                .array("stat_contributions")
                .objects()
                .filter { it.string("ledgerId") == selected.id.toString() && it.stringOrNull("deletedAt") == null }
        val recordsByEventId = records.associateBy { it.string("eventId") }
        val decodedInputsByRecordId = financeRecords.associateBy { it.id }
        val livePostingsByEventId = postings.filter { it.stringOrNull("reversalOf") == null }.groupBy { it.string("eventId") }
        val statistics =
            statisticRows.mapNotNull { row ->
                val event = events[row.string("eventId")] ?: return@mapNotNull null
                val record = recordsByEventId[event.string("id")]
                val projectionInput =
                    record
                        ?.takeIf {
                            it.string(
                                "status",
                            ) == "POSTED"
                        }?.uuid("id")
                        ?.let { decodedInputsByRecordId[it]?.input }
                val kind =
                    event.stringOrNull("kind") ?: when (projectionInput) {
                        is RecordInput.Expense -> "EXPENSE"
                        is RecordInput.Income -> "INCOME"
                        is RecordInput.Transfer -> "TRANSFER"
                        else -> ""
                    }
                FinanceStatisticEntry(
                    UUID.fromString(event.string("id")),
                    record?.uuidOrNull("id"),
                    row.uuidOrNull("categoryId"),
                    direction(row.string("direction")),
                    row.money("amount"),
                    event.timestamp("occurredAt"),
                    event.string("timezone"),
                    record?.string("remark").orEmpty(),
                    AccountStatisticProjection.project(
                        kind,
                        projectionInput,
                        direction(row.string("direction")),
                        row.money("amount"),
                        livePostingsByEventId[event.string("id")]?.singleOrNull()?.uuid("accountId"),
                    ),
                )
            }
        val summaries =
            statistics
                .groupBy { it.amount.currency }
                .map { (currency, entries) ->
                    val income =
                        entries
                            .filter { it.direction == Direction.INCOME }
                            .fold(Money(0, 0, currency)) { total, row -> total.checkedAdd(row.amount) }
                    val expense =
                        entries
                            .filter { it.direction == Direction.EXPENSE }
                            .fold(Money(0, 0, currency)) { total, row -> total.checkedAdd(row.amount) }
                    val periodStart =
                        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(
                            java.util.Date(
                                PeriodResolver
                                    .range(
                                        dev.icos.app.domain.ReportPeriod.MONTH,
                                        now,
                                        java.util.TimeZone
                                            .getDefault()
                                            .id,
                                    ).startInclusiveMillis,
                            ),
                        )
                    val budgetExpense =
                        tables
                            .array("budget_contributions")
                            .objects()
                            .filter {
                                it.string("ledgerId") == selected.id.toString() &&
                                    it.stringOrNull("deletedAt") == null && it.string("direction") == "EXPENSE" &&
                                    it.string("periodDate") >= periodStart && it.string("periodDate") < nextMonthStart(periodStart) &&
                                    it.money("amount").currency == currency
                            }.fold(Money(0, 0, currency)) { total, row -> total.checkedAdd(row.money("amount")) }
                    FinanceSummary(currency, income, expense, budgetExpense)
                }.sortedBy { it.currency }
        val tagGroups = tables.array("tag_groups").objects().filter { it.stringOrNull("deletedAt") == null }
        val financeTagGroups =
            tagGroups.map {
                FinanceTagGroup(
                    UUID.fromString(it.string("id")),
                    it.string("name"),
                    it.long("sortOrder"),
                    it.bool("isDefault"),
                    it.long("serverRevision"),
                )
            }
        val financeTags =
            allTags.values.filter { it.stringOrNull("deletedAt") == null }.map {
                FinanceTag(
                    UUID.fromString(it.string("id")),
                    UUID.fromString(it.string("groupId")),
                    it.string("name"),
                    it.stringOrNull("color"),
                    it.long("sortOrder"),
                    it.timestampOrNull("archivedAt"),
                    it.long("serverRevision"),
                )
            }
        val groups =
            tables.array("account_groups").objects().filter { it.stringOrNull("deletedAt") == null }.map {
                FinanceAccountGroup(
                    UUID.fromString(it.string("id")),
                    it.string("name"),
                    it.long("sortOrder"),
                    it.bool("isDefault"),
                    it.long("serverRevision"),
                )
            }
        val prefsRows = tables.array("business_preferences").objects()
        require(prefsRows.size <= 1 && prefsRows.all { it.string("id") == owner.toString() && it.stringOrNull("deletedAt") == null }) {
            "Canonical business preferences are invalid"
        }
        val preferences =
            prefsRows.singleOrNull()?.let {
                BusinessPreferencesEntity(
                    1,
                    owner.toString(),
                    epoch.toString(),
                    it.long("serverRevision"),
                    it.bool("refundExcessAsIncome"),
                    it.bool("showRefundsInRecordList"),
                    it.bool("loanPrincipalAsIncome"),
                    it.bool("loanRepaymentAsExpense"),
                    it.bool("loanInterestAsExpense"),
                )
            } ?: BusinessPreferencesEntity(ownerId = owner.toString(), epochId = epoch.toString(), localRevision = 0)
        val snapshot =
            FinanceSnapshot(
                owner,
                epoch,
                selected.id,
                financeAccounts,
                financeCategories,
                financeRecords,
                summaries,
                statisticEntries = statistics,
                businessPreferences = preferences,
                tagGroups = financeTagGroups,
                tags = financeTags,
                accountGroups = groups,
            )
        val range = PeriodResolver.range(budgetPeriod, now, statisticsZone)
        val budget = budgetOverview(tables, selected, range, now, budgetPeriod, statisticsZone)
        val templates = canonicalTemplates(tables, selected.id)
        val claims = claimSettlements(tables, selected.id)
        val refunds = refunds(tables, selected.id)
        val (savingPlans, savingEntries) = savings(tables)
        val loans = loans(tables, selectedAccounts)
        val completeSnapshot =
            snapshot.copy(
                claimSettlements = claims,
                refunds = refunds,
                savingPlans = savingPlans,
                savingEntries = savingEntries,
                loanPlans = loans,
                creditProfiles =
                    tables
                        .arrayOrEmpty("credit_profiles")
                        .objects()
                        .filter {
                            it.stringOrNull("deletedAt") == null &&
                                it.bool("enabled")
                        }.map { row ->
                            val projection =
                                summary?.arrayOrEmpty("creditProfiles")?.objects()?.singleOrNull {
                                    it.string("accountId") ==
                                        row.string("accountId")
                                }
                            FinanceCreditProfile(
                                row.uuid("id"),
                                row.uuid("accountId"),
                                row.money("creditLimit"),
                                row.long("statementDay").toInt(),
                                row.bool("includeStatementDay"),
                                row.string("repaymentRule"),
                                row.long("repaymentValue").toInt(),
                                row.string("timezone"),
                                true,
                                row.long("serverRevision"),
                                projection?.money("rawAvailableCapacity"),
                                projection?.money("currentDebt"),
                                projection?.money("statementAmount"),
                                projection?.stringOrNull("cycleStartInclusive"),
                                projection?.stringOrNull("cycleEndExclusive"),
                                projection?.stringOrNull("statementDate"),
                                projection?.stringOrNull("repaymentDate"),
                            )
                        },
                creditCapacityEvents =
                    tables.arrayOrEmpty("credit_capacity_events").objects().map { row ->
                        FinanceCreditCapacityEvent(
                            row.uuid("id"),
                            row.uuid("accountId"),
                            row.string("sourceType"),
                            row.money("signed"),
                            row.timestamp("occurredAt"),
                            row.string("reason"),
                        )
                    },
                creditPaymentAllocations =
                    tables.arrayOrEmpty("credit_payment_allocations").objects().map { row ->
                        FinanceCreditPaymentAllocation(
                            row.uuid("id"),
                            row.uuid("accountId"),
                            row.uuid("paymentEventId"),
                            row.uuid("termId"),
                            row.money("amount"),
                        )
                    },
                installmentPlans =
                    tables.arrayOrEmpty("installment_plans").objects().map { row ->
                        FinanceInstallmentPlan(
                            row.uuid("id"),
                            row.string("sourceType"),
                            row.uuid("accountId"),
                            row.money("principal"),
                            row.money("interest"),
                            row.long("termCount").toInt(),
                            row.string("principalPosting"),
                            "SERVER",
                            row.string("state"),
                            row.long("serverRevision"),
                        )
                    },
                installmentTerms =
                    tables.arrayOrEmpty("installment_terms").objects().filter { it.string("state") != "SUPERSEDED" }.map { row ->
                        val principal = row.money("principal")
                        val interest = row.money("interest")
                        val allocated =
                            tables
                                .arrayOrEmpty("credit_payment_allocations")
                                .objects()
                                .filter { it.string("termId") == row.string("id") }
                                .fold(Money(0, 0, principal.currency)) { total, allocation -> total.checkedAdd(allocation.money("amount")) }
                        val remaining = principal.checkedAdd(interest).checkedSubtract(allocated)
                        check(remaining.unscaled >= 0) { "Canonical credit allocations exceed term amount" }
                        FinanceInstallmentTerm(
                            row.uuid("id"),
                            row.uuid("planId"),
                            row.long("termIndex").toInt(),
                            row.string("dueDate"),
                            principal,
                            interest,
                            row.string("state"),
                            row.uuidOrNull("postingEventId"),
                            remaining,
                        )
                    },
                creditPayments = CanonicalCreditPayments.read(tables, owner, epoch),
                allAccounts = allFinanceAccounts,
                historicalAccountNames = accounts.associate { it.uuid("id") to it.string("name") },
                allCategories = allFinanceCategories,
                ledgers =
                    ledgerRows.map { row ->
                        FinanceLedger(
                            row.uuid("id"),
                            row.string("name"),
                            row.string("baseCurrency"),
                            row.optLong("sortOrder"),
                            row.bool("isDefault"),
                            row.timestampOrNull("archivedAt"),
                            row.optLong("serverRevision"),
                        )
                    },
            )
        val templateRows = tables.array("record_templates").objects().associateBy { it.string("id") }
        val segments = tables.array("recurrence_rule_segments").objects().groupBy { it.string("planId") }
        val occurrences = tables.array("recurrence_occurrences").objects().groupBy { it.string("planId") }
        val recurrences =
            tables
                .array("recurrence_plans")
                .objects()
                .filter { it.string("state") != "DELETED" && it.stringOrNull("deletedAt") == null }
                .map { plan ->
                    val liveSegments = segments[plan.string("id")].orEmpty().filter { it.stringOrNull("deletedAt") == null }
                    val latestRule =
                        liveSegments
                            .filter { it.long("effectiveFromSlot") <= plan.long("nextDueSlot") }
                            .maxByOrNull { it.long("effectiveFromSlot") }
                    val template = latestRule?.stringOrNull("templateId")?.let(templateRows::get)
                    RecurrenceSummary(
                        id = UUID.fromString(plan.string("id")),
                        state = plan.string("state"),
                        frequency = latestRule?.stringOrNull("frequency"),
                        templateId = latestRule?.uuidOrNull("templateId"),
                        templateName = template?.stringOrNull("name"),
                        nextDueAtMillis = plan.timestampOrNull("nextDueAt"),
                        nextDueSlot = plan.long("nextDueSlot"),
                        startLocalDateTime = plan.string("startLocalDateTime"),
                        timezone = plan.string("timezone"),
                        interval = latestRule?.long("intervalStep")?.toInt() ?: 1,
                        weekDays = latestRule?.arrayOrEmpty("weekDays")?.let { a -> List(a.length()) { a.getInt(it) } }.orEmpty(),
                        monthDays = latestRule?.arrayOrEmpty("monthDays")?.let { a -> List(a.length()) { a.getInt(it) } }.orEmpty(),
                        endAt = latestRule?.stringOrNull("endAt"),
                        maxCount = latestRule?.longOrNull("maxCount"),
                        templateRevision = latestRule?.longOrNull("templateRevision"),
                        serverRevision = plan.long("serverRevision"),
                        generatedCount = plan.long("generatedCount"),
                        occurrenceCount = occurrences[plan.string("id")].orEmpty().count { it.stringOrNull("deletedAt") == null },
                    )
                }
        return View(completeSnapshot, ledgers, selected.id, budget, templates, recurrences)
    }

    private fun canonicalTemplates(
        tables: JSONObject,
        ledgerId: UUID,
    ): List<FinanceRecordTemplate> {
        val allocations =
            tables
                .array("template_allocations")
                .objects()
                .filter {
                    it.stringOrNull("deletedAt") == null
                }.groupBy { it.string("templateId") }
        val tags =
            tables
                .array("template_tags")
                .objects()
                .filter { it.stringOrNull("deletedAt") == null }
                .groupBy { it.string("templateId") }
                .mapValues { (_, rows) -> rows.map { UUID.fromString(it.string("tagId")) } }
        return tables
            .array("record_templates")
            .objects()
            .filter { it.string("ledgerId") == ledgerId.toString() && it.stringOrNull("deletedAt") == null }
            .map { row ->
                val embeddedRecord = row.optJSONObject("record")
                val embeddedInput = embeddedRecord?.optJSONObject("input")
                val inputKind =
                    row.stringOrNull("inputKind")?.takeIf(String::isNotBlank)
                        ?: embeddedInput?.optString("kind")?.takeIf(String::isNotBlank)
                        ?: error("Template input kind unavailable")
                val embeddedAllocations =
                    row.optJSONArray("allocations")?.objects()
                        ?: embeddedInput
                            ?.optJSONArray("allocations")
                            ?.objects()
                            ?.map { allocation ->
                                JSONObject(allocation.toString()).put("role", if (inputKind == "EXPENSE") "PAYMENT" else "RECEIPT")
                            }.orEmpty()
                val rows =
                    if (embeddedAllocations.isNotEmpty()) {
                        embeddedAllocations.map { allocation ->
                            JSONObject()
                                .put("templateId", row.string("id"))
                                .put("accountId", allocation.string("accountId"))
                                .put("amount", allocation.get("amount"))
                                .put("role", allocation.optString("role"))
                        }
                    } else {
                        allocations[row.string("id")].orEmpty()
                    }
                val embeddedTags =
                    row.optJSONArray("tags")?.objects().orEmpty().mapNotNull {
                        it.stringOrNull("tagId")
                            ?: it.stringOrNull("id")
                    }
                val templateTagIds = if (embeddedTags.isNotEmpty()) embeddedTags.map(UUID::fromString) else tags[row.string("id")].orEmpty()
                val occurred =
                    row.stringOrNull("occurredAt") ?: embeddedRecord?.getString("occurredAt") ?: error("Template occurrence unavailable")
                val input =
                    embeddedInput?.let(RecordInputJson::decode) ?: when (inputKind) {
                        "EXPENSE" -> {
                            RecordInputJson.decode(
                                JSONObject()
                                    .put("kind", inputKind)
                                    .put("gross", moneyJson(row.money("gross")))
                                    .put("discount", row.opt("discount") ?: JSONObject.NULL)
                                    .put(
                                        "allocations",
                                        JSONArray().also { array ->
                                            rows.filter { it.string("role") == "PAYMENT" }.forEach { array.put(templateAllocation(it)) }
                                        },
                                    ).put("countInStatistics", row.bool("countInStatistics"))
                                    .put("countInBudget", row.bool("countInBudget")),
                            )
                        }

                        "INCOME" -> {
                            RecordInputJson.decode(
                                JSONObject()
                                    .put("kind", inputKind)
                                    .put("amount", moneyJson(row.money("gross")))
                                    .put(
                                        "allocations",
                                        JSONArray().also { array ->
                                            rows.filter { it.string("role") == "RECEIPT" }.forEach { array.put(templateAllocation(it)) }
                                        },
                                    ).put("countInStatistics", row.bool("countInStatistics")),
                            )
                        }

                        "TRANSFER" -> {
                            RecordInputJson.decode(
                                JSONObject()
                                    .put("kind", inputKind)
                                    .put("from", rows.first { it.string("role") == "FROM" }.let(::templateAllocation))
                                    .put("to", rows.first { it.string("role") == "TO" }.let(::templateAllocation))
                                    .put(
                                        "fee",
                                        rows.firstOrNull { it.string("role") == "FEE" }?.let(::templateAllocation) ?: JSONObject.NULL,
                                    ).put(
                                        "bonus",
                                        rows.firstOrNull { it.string("role") == "BONUS" }?.let(::templateAllocation) ?: JSONObject.NULL,
                                    ),
                            )
                        }

                        else -> {
                            error("Unsupported template input kind")
                        }
                    }
                val embeddedReason =
                    embeddedRecord?.optString("validityReason")?.takeIf { it.isNotBlank() && it != "null" }
                        ?: row.optString("validityReason").takeIf { it.isNotBlank() && it != "null" }
                val ledger = tables.array("ledgers").objects().firstOrNull { it.optString("id") == row.optString("ledgerId") }
                val categoryId = row.uuidOrNull("categoryId")
                val feeCategoryId = row.uuidOrNull("feeCategoryId")
                val bonusCategoryId = row.uuidOrNull("bonusCategoryId")
                val category =
                    categoryId?.let { id ->
                        tables.array("categories").objects().firstOrNull {
                            it.optString("id") ==
                                id.toString()
                        }
                    }
                val feeCategory =
                    feeCategoryId?.let { id ->
                        tables.array("categories").objects().firstOrNull {
                            it.optString("id") ==
                                id.toString()
                        }
                    }
                val bonusCategory =
                    bonusCategoryId?.let { id ->
                        tables.array("categories").objects().firstOrNull {
                            it.optString("id") ==
                                id.toString()
                        }
                    }
                val invalidReason =
                    embeddedReason?.takeIf { it.isNotBlank() && it != "null" }
                        ?: if (ledger == null || ledger.stringOrNull("deletedAt") != null ||
                            ledger.stringOrNull("archivedAt") != null
                        ) {
                            "INVALID_LEDGER"
                        } else if (inputKind != "TRANSFER" &&
                            (
                                category == null || category.stringOrNull("deletedAt") != null ||
                                    category.stringOrNull("archivedAt") != null ||
                                    category.optString("ledgerId") != row.optString("ledgerId") ||
                                    category.optString("direction") != inputKind
                            ) ||
                            inputKind == "TRANSFER" &&
                            (
                                feeCategoryId != null &&
                                    (
                                        feeCategory == null || feeCategory.stringOrNull("deletedAt") != null ||
                                            feeCategory.stringOrNull("archivedAt") != null ||
                                            feeCategory.optString("ledgerId") != row.optString("ledgerId") ||
                                            feeCategory.optString("direction") != "EXPENSE"
                                    ) ||
                                    bonusCategoryId != null &&
                                    (
                                        bonusCategory == null || bonusCategory.stringOrNull("deletedAt") != null ||
                                            bonusCategory.stringOrNull("archivedAt") != null ||
                                            bonusCategory.optString("ledgerId") != row.optString("ledgerId") ||
                                            bonusCategory.optString("direction") != "INCOME"
                                    )
                            )
                        ) {
                            "INVALID_CATEGORY"
                        } else if (templateTagIds.any { tagId ->
                                tables.array("tags").objects().none {
                                    it.optString("id") == tagId.toString() &&
                                        it.stringOrNull("deletedAt") == null &&
                                        it.stringOrNull("archivedAt") == null
                                }
                            }
                        ) {
                            "INVALID_TAG"
                        } else if (rows.any { allocation ->
                                val account =
                                    tables.array("accounts").objects().firstOrNull {
                                        it.optString("id") ==
                                            allocation.optString("accountId")
                                    }
                                account == null || account.stringOrNull(
                                    "deletedAt",
                                ) != null || account.stringOrNull("archivedAt") != null ||
                                    !account.canonicalBoolean("selectable") ||
                                    account.optString("currency") != allocation.money("amount").currency ||
                                    account.optString("visibilityMode") == "SELECTED" &&
                                    tables.array("account_ledger_visibility").objects().none {
                                        it.optString("accountId") ==
                                            allocation.optString("accountId") &&
                                            it.optString("ledgerId") == row.optString("ledgerId") &&
                                            it.stringOrNull("deletedAt") == null
                                    }
                            }
                        ) {
                            "INVALID_ACCOUNT"
                        } else if (runCatching { java.time.ZoneId.of(row.optString("timezone")) }.isFailure) {
                            "INVALID_TIMEZONE"
                        } else {
                            null
                        }
                FinanceRecordTemplate(
                    UUID.fromString(row.string("id")),
                    row.string("name"),
                    input,
                    row.uuidOrNull("categoryId"),
                    canonicalTimestamp(occurred),
                    row.string("timezone"),
                    row.string("remark"),
                    invalidReason = invalidReason,
                    tagIds = templateTagIds,
                    serverRevision = row.long("serverRevision"),
                )
            }
    }

    private fun templateAllocation(
        accountId: UUID,
        amount: Money,
    ) = JSONObject()
        .put("accountId", accountId.toString())
        .put("amount", moneyJson(amount))

    private fun templateAllocation(row: JSONObject) =
        JSONObject()
            .put("accountId", row.string("accountId"))
            .put("amount", moneyJson(row.money("amount")))

    private fun claimSettlements(
        tables: JSONObject,
        ledgerId: UUID,
    ): List<FinanceClaimSettlement> {
        val allocations = tables.array("claim_allocations").objects().groupBy { it.string("settlementId") }
        return tables.array("claim_settlements").objects().filter { it.string("ledgerId") == ledgerId.toString() }.map { row ->
            FinanceClaimSettlement(
                UUID.fromString(row.string("id")),
                row.money("actualAmount"),
                row.uuidOrNull("accountId"),
                row.uuidOrNull("incomeCategoryId"),
                RemainderAction.valueOf(row.string("remainderAction")),
                row.timestamp("settledAt"),
                row.string("timezone"),
                allocations[row.string("id")].orEmpty().map {
                    FinanceClaimAllocation(UUID.fromString(it.string("claimItemId")), it.money("actualAmount"))
                },
                row.string("status"),
                row.long("serverRevision"),
                row.long("createdSeq"),
            )
        }
    }

    private fun refunds(
        tables: JSONObject,
        ledgerId: UUID,
    ): List<FinanceRefundReceipt> =
        tables.array("refunds").objects().filter { it.string("ledgerId") == ledgerId.toString() }.map { row ->
            FinanceRefundReceipt(
                UUID.fromString(row.string("id")),
                UUID.fromString(row.string("originRecordId")),
                row.money("amount"),
                UUID.fromString(row.string("accountId")),
                row.timestamp("refundAt"),
                row.string("timezone"),
                row.string("status"),
                row.long("serverRevision"),
                row.long("createdSeq"),
                row.money("expenseReduction"),
                row.money("excess"),
            )
        }

    private fun savings(tables: JSONObject): Pair<List<SavingPlanSummary>, List<SavingEntrySummary>> {
        val entries = tables.array("saving_entries").objects().filter { it.stringOrNull("deletedAt") == null }
        val plans =
            tables.array("saving_plans").objects().filter { it.stringOrNull("deletedAt") == null }.map { row ->
                val currency = row.string("currency")
                val initial = row.money("initialSaved")
                val related = entries.filter { it.string("planId") == row.string("id") }
                val saved =
                    related
                        .filter { it.stringOrNull("cancelledAt") == null }
                        .fold(initial) { total, entry ->
                            val amount = entry.money("amount")
                            if (entry.string("kind") == "DEPOSIT") total.checkedAdd(amount) else total.checkedSubtract(amount)
                        }
                val kind = SavingKind.valueOf(row.string("kind"))
                val interval = row.optJSONObject("interval")
                val intervalUnit =
                    interval?.optString("unit")?.takeIf(String::isNotBlank)
                        ?: row.stringOrNull("intervalUnit")?.takeIf(String::isNotEmpty)
                val input =
                    SavingPlanInput(
                        kind,
                        row.string("name"),
                        row.stringOrNull("iconRef"),
                        row.money("target"),
                        initial,
                        row.moneyOrNull("firstAmount"),
                        row.moneyOrNull("increment"),
                        intervalUnit?.let {
                            SavingInterval(
                                SavingIntervalUnit.valueOf(it),
                                interval?.optInt("step") ?: row.long("intervalStep").toInt(),
                            )
                        },
                        row.stringOrNull("start") ?: row.stringOrNull("startDate"),
                        row.stringOrNull("end") ?: row.stringOrNull("endDate"),
                        row.bool("allowManualEntries"),
                    )
                SavingPlanSummary(
                    UUID.fromString(row.string("id")),
                    input,
                    SavingState.valueOf(row.string("state")),
                    saved,
                    input.target.checkedSubtract(saved),
                    row.long("serverRevision"),
                )
            }
        val summaries =
            entries.map { row ->
                SavingEntrySummary(
                    UUID.fromString(row.string("id")),
                    UUID.fromString(row.string("planId")),
                    SavingEntryKind.valueOf(row.string("kind")),
                    row.money("amount"),
                    row.timestamp("occurredAt"),
                    row.intOrNull("cardIndex"),
                    row.stringOrNull("remark"),
                    row.uuidOrNull("linkedRecordId"),
                    row.timestampOrNull("cancelledAt"),
                    row.long("serverRevision"),
                )
            }
        return plans to summaries
    }

    private fun loans(
        tables: JSONObject,
        @Suppress("UNUSED_PARAMETER") accounts: List<JSONObject>,
    ): List<FinanceLoanPlan> {
        val terms = tables.array("loan_terms").objects().groupBy { it.string("planId") }
        val rates = tables.array("loan_rate_changes").objects().groupBy { it.string("planId") }
        return tables.array("loan_plans").objects().map { row ->
            val currency = row.money("principal").currency
            val planTerms =
                terms[row.string("id")]
                    .orEmpty()
                    .filter { it.string("state") != "SUPERSEDED" }
                    .map { term ->
                        LoanTerm(
                            term.long("termIndex").toInt(),
                            term.string("dueDate"),
                            term.money("opening"),
                            term.money("principal"),
                            term.money("interest"),
                            term.money("closing"),
                            when (term.string("state")) {
                                "PAID" -> LoanDueState.PAID
                                "POSTED" -> LoanDueState.DUE
                                "NO_DUE" -> LoanDueState.NO_DUE
                                else -> LoanDueState.DUE
                            },
                        )
                    }.sortedBy { it.termIndex }
            val changes =
                rates[row.string("id")].orEmpty().map {
                    FinanceLoanRateChange(
                        it.long("effectiveTerm").toInt(),
                        it.long("oldNumerator"),
                        it.long("oldDenominator"),
                        it.long("newNumerator"),
                        it.long("newDenominator"),
                        it.long("fromRevision"),
                        it.long("toRevision"),
                        it.timestamp("createdAt"),
                    )
                }
            val payments =
                terms[row.string("id")].orEmpty().mapNotNull { term ->
                    val principalPaid = term.moneyOrNull("paidPrincipal") ?: return@mapNotNull null
                    val interestPaid = term.moneyOrNull("paidInterest") ?: return@mapNotNull null
                    FinanceLoanPayment(
                        term.long("termIndex").toInt(),
                        principalPaid,
                        interestPaid,
                        term.timestampOrNull("paidAt"),
                    )
                }
            FinanceLoanPlan(
                UUID.fromString(row.string("id")),
                UUID.fromString(row.string("accountId")),
                row.money("principal"),
                row.long("serverRevision"),
                row.string("status"),
                planTerms,
                row.long("paidTermCount").toInt(),
                row.long("annualRateNumerator"),
                row.long("annualRateDenominator"),
                row.string("repaymentType"),
                row.string("firstDueDate"),
                changes,
                payments,
            )
        }
    }

    private fun budgetOverview(
        tables: JSONObject,
        ledger: LedgerOption,
        range: dev.icos.app.domain.PeriodRange,
        now: Long,
        period: ReportPeriod,
        statisticsZone: String,
    ): FinanceBudgetOverview {
        fun day(millis: Long) =
            java.text
                .SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT)
                .apply { timeZone = java.util.TimeZone.getTimeZone(statisticsZone) }
                .format(java.util.Date(millis))
        val periodStart = day(range.startInclusiveMillis)
        val periodEnd = day(range.endExclusiveMillis)
        val budgetRoots = tables.array("budgets").objects().filter { it.string("ledgerId") == ledger.id.toString() }
        require(budgetRoots.size <= 1) { "Canonical budget root is duplicated" }
        val budgets =
            budgetRoots.singleOrNull()?.also { root ->
                require(root.string("id") == ledger.id.toString()) { "Canonical budget root identity is invalid" }
            }
        val enabled =
            budgets?.let { root ->
                if (!root.has("enabled")) {
                    true
                } else {
                    when (val value = root.get("enabled")) {
                        is Boolean -> {
                            value
                        }

                        is Int -> {
                            when (value) {
                                0 -> false
                                1 -> true
                                else -> error("Invalid canonical budget enabled value")
                            }
                        }

                        is Long -> {
                            when (value) {
                                0L -> false
                                1L -> true
                                else -> error("Invalid canonical budget enabled value")
                            }
                        }

                        else -> {
                            error("Invalid canonical budget enabled value")
                        }
                    }
                }
            } ?: true
        val configs = budgets?.array("configs")?.objects().orEmpty()
        val activeConfigs = configs.filter { it.string("periodType") == period.name && it.stringOrNull("deletedAt") == null }
        val override = activeConfigs.firstOrNull { it.string("scope") == "CURRENT" && it.stringOrNull("periodStart") == periodStart }
        val default =
            activeConfigs
                .filter {
                    it.string("scope") == "DEFAULT" &&
                        (it.stringOrNull("effectiveFrom") ?: "0000-01-01") <= periodStart
                }.maxByOrNull { it.stringOrNull("effectiveFrom") ?: "0000-01-01" }

        fun entries(config: JSONObject?) = config?.array("entries")?.objects().orEmpty()

        fun total(config: JSONObject?) =
            entries(config)
                .filter { it.stringOrNull("categoryId") == null }
                .fold(Money(0, 0, ledger.currency)) { sum, entry -> sum.checkedAdd(entry.money("amount")) }

        fun categories(config: JSONObject?) =
            entries(config)
                .mapNotNull { entry ->
                    entry.stringOrNull("categoryId")?.let { UUID.fromString(it) to entry.money("amount") }
                }.toMap()
        val used =
            tables
                .array("budget_contributions")
                .objects()
                .filter {
                    it.string("ledgerId") == ledger.id.toString() && it.string("direction") == "EXPENSE" &&
                        it.stringOrNull("deletedAt") == null && it.string("periodDate") >= periodStart &&
                        it.string("periodDate") < periodEnd && it.money("amount").currency == ledger.currency
                }.fold(Money(0, 0, ledger.currency)) { total, row -> total.checkedAdd(row.money("amount")) }
        val effectiveRevision =
            budgets?.get("serverRevision")?.let { raw ->
                require(raw is Number) { "Canonical budget revision is invalid" }
                raw.toString().toLongOrNull()?.takeIf { it > 0 } ?: error("Canonical budget revision is invalid")
            } ?: 0L
        return FinanceBudgetOverview(
            period,
            periodStart,
            periodEnd,
            ledger.currency,
            enabled,
            budgets?.uuidOrNull("id"),
            effectiveRevision,
            default?.uuidOrNull("id"),
            effectiveRevision,
            default?.let(::total),
            override?.uuidOrNull("id"),
            if (override != null) effectiveRevision else 0,
            override?.let(::total),
            used,
            PeriodResolver.remainingDays(range, now, statisticsZone),
            emptyList(),
            categories(override ?: default),
            defaultCategoryAmounts = categories(default),
            overrideCategoryAmounts = categories(override),
        )
    }

    internal fun input(
        tables: JSONObject,
        record: JSONObject,
        event: JSONObject,
        rows: List<JSONObject>,
    ): RecordInput {
        fun rowMoney(
            row: JSONObject,
            prefix: String,
        ): Money {
            if (row.has(prefix) && row.opt(prefix) is JSONObject) return row.money(prefix)
            val unscaled = prefix + "Unscaled"
            if (row.has(unscaled)) {
                return Money(
                    row.getString(unscaled).toLong(),
                    row.getInt(prefix + "Scale"),
                    row.getString(prefix + "Currency"),
                )
            }
            val flatPrefix = prefix.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").lowercase()
            return Money(
                row.getString(flatPrefix + "_unscaled").toLong(),
                row.getInt(flatPrefix + "_scale"),
                row.getString(flatPrefix + "_currency"),
            )
        }

        fun allocation(
            row: JSONObject,
            includeRecordCurrencyAmount: Boolean,
        ): JSONObject {
            val accountId = row.string("accountId")

            fun storedMoney(prefix: String): Money? =
                when {
                    row.has(prefix) && !row.isNull(prefix) -> {
                        row.money(prefix)
                    }

                    row.has(prefix + "Unscaled") && !row.isNull(prefix + "Unscaled") -> {
                        Money(
                            row.getString(prefix + "Unscaled").toLong(),
                            row.getInt(prefix + "Scale"),
                            row.getString(prefix + "Currency"),
                        )
                    }

                    else -> {
                        null
                    }
                }
            val accountAmount =
                storedMoney("accountAmount") ?: run {
                    val posting =
                        tables.array("account_postings").objects().firstOrNull {
                            it.string("eventId") == event.string("id") && it.string("accountId") == accountId &&
                                it.stringOrNull("reversalOf") == null
                        } ?: error("Canonical allocation posting unavailable")
                    val account = tables.array("accounts").objects().first { it.string("id") == accountId }
                    val signed = posting.money("signed")
                    val signedFlow = if (account.string("type") == "LIABILITY") signed.checkedNegate() else signed
                    if (record.string("direction") == "EXPENSE" ||
                        row.string("role") in setOf("FROM", "FEE")
                    ) {
                        signedFlow.checkedNegate()
                    } else {
                        signedFlow
                    }
                }
            val result = JSONObject().put("accountId", accountId).put("amount", moneyJson(accountAmount))
            if (includeRecordCurrencyAmount) {
                val recordAmount = storedMoney("recordCurrencyAmount") ?: accountAmount
                if (accountAmount.currency != recordAmount.currency) result.put("recordCurrencyAmount", moneyJson(recordAmount))
            }
            return result
        }
        val allocs = rows.sortedBy { it.string("id") }
        return when (record.string("direction")) {
            "EXPENSE" -> {
                RecordInputJson.decode(
                    JSONObject()
                        .put("kind", "EXPENSE")
                        .put("gross", moneyJson(record.money("gross")))
                        .put("discount", record["discount"])
                        .put(
                            "allocations",
                            JSONArray().also { a ->
                                allocs.filter { it.string("role") == "PAYMENT" }.forEach { row ->
                                    a.put(
                                        allocation(row, includeRecordCurrencyAmount = true).also {
                                            if (!row.canonicalBoolean(
                                                    "hasFxAllocation",
                                                    row.stringOrNull("fxSnapshotId") != null,
                                                )
                                            ) {
                                                it.remove("recordCurrencyAmount")
                                            }
                                        },
                                    )
                                }
                            },
                        ).put("countInStatistics", record.bool("countInStatistics"))
                        .put("countInBudget", record.bool("countInBudget")),
                )
            }

            "INCOME" -> {
                RecordInputJson.decode(
                    JSONObject()
                        .put("kind", "INCOME")
                        .put("amount", moneyJson(record.money("actual")))
                        .put(
                            "allocations",
                            JSONArray().also { a ->
                                allocs
                                    .filter {
                                        it.string("role") ==
                                            "RECEIPT"
                                    }.forEach { a.put(allocation(it, includeRecordCurrencyAmount = false)) }
                            },
                        ).put("countInStatistics", record.bool("countInStatistics")),
                )
            }

            else -> {
                val from = allocs.firstOrNull { it.string("role") == "FROM" } ?: error("Transfer source missing")
                val to = allocs.firstOrNull { it.string("role") == "TO" } ?: error("Transfer destination missing")
                RecordInputJson.decode(
                    JSONObject()
                        .put(
                            "kind",
                            "TRANSFER",
                        ).put(
                            "from",
                            allocation(from, includeRecordCurrencyAmount = false),
                        ).put("to", allocation(to, includeRecordCurrencyAmount = false))
                        .put(
                            "fee",
                            allocs.firstOrNull { it.string("role") == "FEE" }?.let { allocation(it, includeRecordCurrencyAmount = false) }
                                ?: JSONObject.NULL,
                        ).put(
                            "bonus",
                            allocs.firstOrNull { it.string("role") == "BONUS" }?.let { allocation(it, includeRecordCurrencyAmount = false) }
                                ?: JSONObject.NULL,
                        ),
                )
            }
        }
    }

    private fun accountRecordMutationSupported(
        tables: JSONObject,
        row: JSONObject,
    ): Boolean {
        val id = row.string("id")
        val eventId = row.string("eventId")
        if (row.stringOrNull("parentRecordId") != null || row.string("status") != "POSTED" || claimState(tables, id) != "NONE" ||
            row.optJSONArray("attachmentIds")?.length()?.let { it > 0 } == true || row.stringOrNull("templateId") != null
        ) {
            return false
        }

        fun hasLive(
            table: String,
            field: String,
            value: String,
        ): Boolean =
            tables.array(table).objects().any {
                it.stringOrNull(field) == value && it.stringOrNull("deletedAt") == null && it.stringOrNull("cancelledAt") == null &&
                    it.stringOrNull("status") !in setOf("CANCELLED", "REVERSED", "DELETED")
            }
        if (hasLive("refunds", "originRecordId", id) || hasLive("saving_entries", "linkedRecordId", id) ||
            hasLive("recurrence_occurrences", "recordId", id) || hasLive("installment_plans", "sourceRecordId", id)
        ) {
            return false
        }
        if (hasLive("loan_events", "recordId", id) || hasLive("financial_events", "reversalOf", eventId) ||
            hasLive("credit_payment_allocations", "paymentEventId", eventId)
        ) {
            return false
        }
        if (tables
                .array(
                    "financial_events",
                ).objects()
                .any { it.stringOrNull("recordId") == id && it.stringOrNull("id") != eventId }
        ) {
            return false
        }
        return true
    }

    private fun claimState(
        tables: JSONObject,
        id: String,
    ): String =
        tables
            .array("claim_items")
            .objects()
            .firstOrNull { it.string("originRecordId") == id }
            ?.string("status") ?: "NONE"

    private fun direction(value: String) = if (value == "INCOME") Direction.INCOME else Direction.EXPENSE

    private fun JSONArray.objects() = List(length()) { getJSONObject(it) }

    private fun JSONObject.array(key: String) = if (!has(key) || isNull(key)) JSONArray() else getJSONArray(key)

    private fun JSONObject.string(key: String): String = get(key).toString()

    private fun JSONObject.stringOrNull(key: String): String? = if (!has(key) || isNull(key)) null else get(key).toString()

    private fun JSONObject.uuid(key: String) = UUID.fromString(string(key))

    private fun JSONObject.uuidOrNull(key: String): UUID? = stringOrNull(key)?.let(UUID::fromString)

    private fun JSONObject.long(key: String) = get(key).toString().toLong()

    private fun JSONObject.longOrNull(key: String): Long? = if (!has(key) || isNull(key)) null else get(key).toString().toLong()

    private fun JSONObject.arrayOrEmpty(key: String): JSONArray = if (has(key) && !isNull(key)) getJSONArray(key) else JSONArray()

    private fun JSONObject.attachmentIds(): List<UUID> {
        val array = arrayOrEmpty("attachmentIds")
        return List(array.length()) { UUID.fromString(array.getString(it)) }
    }

    private fun JSONObject.bool(key: String) = get(key).toString().let { it == "true" || it == "1" }

    private fun JSONObject.money(key: String) =
        getJSONObject(key).let {
            Money(it.getString("unscaled").toLong(), it.getInt("scale"), it.getString("currency"))
        }

    private fun moneyJson(value: Money) =
        JSONObject().put("unscaled", value.unscaled.toString()).put("scale", value.scale).put("currency", value.currency)

    private fun JSONObject.moneyOrNull(key: String): Money? = if (!has(key) || isNull(key)) null else money(key)

    private fun JSONObject.intOrNull(key: String): Int? = if (!has(key) || isNull(key)) null else get(key).toString().toInt()

    private fun JSONObject.timestamp(key: String): Long = canonicalTimestamp(string(key))

    private fun JSONObject.timestampOrNull(key: String): Long? = stringOrNull(key)?.let(::canonicalTimestamp)

    private fun nextMonthStart(value: String): String {
        val parts = value.split('-')
        val year = parts[0].toInt()
        val month = parts[1].toInt()
        return if (month == 12) {
            "%04d-01-01".format(java.util.Locale.ROOT, year + 1)
        } else {
            "%04d-%02d-01".format(java.util.Locale.ROOT, year, month + 1)
        }
    }

    private fun canonicalTimestamp(value: String): Long {
        val match =
            Regex("^(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2})(?:\\.(\\d+))?(Z|[+-]\\d{2}:?\\d{2})$")
                .matchEntire(value) ?: error("Invalid canonical timestamp")
        val (base, fraction, rawOffset) = match.destructured
        val millis = fraction.take(3).padEnd(3, '0')
        val offset =
            when {
                rawOffset == "Z" -> "+00:00"
                rawOffset.length == 5 -> rawOffset.substring(0, 3) + ":" + rawOffset.substring(3)
                else -> rawOffset
            }
        val normalized = "$base.$millis$offset"
        return java.text
            .SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", java.util.Locale.ROOT)
            .apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
                isLenient = false
            }.parse(normalized)
            ?.time ?: error("Invalid canonical timestamp")
    }
}
