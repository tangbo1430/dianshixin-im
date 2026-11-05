package dev.icos.app.data.local

import dev.icos.app.data.remote.RecordInputJson
import dev.icos.app.domain.RecordInput
import dev.icos.app.domain.Money
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

class CanonicalPredictionTest {
    private val owner = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val epoch = UUID.fromString("00000000-0000-0000-0000-000000000002")
    private val account = "00000000-0000-0000-0000-000000000010"
    private val ledger = "00000000-0000-0000-0000-000000000011"
    private val category = "00000000-0000-0000-0000-000000000012"
    private val parentCategory = "00000000-0000-0000-0000-000000000019"
    private val record = "00000000-0000-0000-0000-000000000013"
    private val oldEvent = "00000000-0000-0000-0000-000000000014"
    private val opId = UUID.fromString("00000000-0000-0000-0000-000000000020").toString()

    private fun money(value: Long) = JSONObject().put("unscaled", value.toString()).put("scale", 0).put("currency", "CNY")

    private fun tables() = JSONObject().put("accounts", JSONArray().put(JSONObject().put("id", account).put("type", "LIABILITY")))
        .put("categories", JSONArray().put(JSONObject().put("id", category).put("parentId", parentCategory).put("excludeBudget", 0L))
            .put(JSONObject().put("id", parentCategory).put("parentId", JSONObject.NULL).put("excludeBudget", 1L)))
        .put("records", JSONArray().put(JSONObject().put("id", record).put("ownerId", owner.toString()).put("datasetEpoch", epoch.toString())
            .put("ledgerId", ledger).put("eventId", oldEvent).put("direction", "EXPENSE").put("status", "POSTED")
            .put("occurredAt", "2026-01-02T03:04:05Z").put("timezone", "UTC").put("localDate", "2026-01-02")
            .put("remark", "old").put("categoryId", category).put("gross", money(100)).put("actual", money(100))
            .put("discount", JSONObject.NULL).put("countInStatistics", true).put("countInBudget", true)
            .put("parentRecordId", JSONObject.NULL).put("claimState", "NONE").put("serverRevision", 4).put("deletedAt", JSONObject.NULL)))
        .put("financial_events", JSONArray().put(JSONObject().put("id", oldEvent).put("ledgerId", ledger)
            .put("occurredAt", "2026-01-02T03:04:05Z").put("timezone", "UTC")))
        .put("account_postings", JSONArray().put(JSONObject().put("id", "00000000-0000-0000-0000-000000000015")
            .put("eventId", oldEvent).put("accountId", account).put("signed", money(100)).put("reversalOf", JSONObject.NULL)))
        .put("record_allocations", JSONArray().put(JSONObject().put("id", "00000000-0000-0000-0000-000000000016")
            .put("recordId", record).put("accountId", account).put("role", "PAYMENT").put("recordCurrencyAmount", money(100)).put("deletedAt", JSONObject.NULL)))
        .put("record_tags", JSONArray())
        .put("stat_contributions", JSONArray().put(JSONObject().put("id", "00000000-0000-0000-0000-000000000017")
            .put("eventId", oldEvent).put("recordId", record).put("originRecordId", record).put("ledgerId", ledger)
            .put("categoryId", category).put("direction", "EXPENSE").put("amount", money(100)).put("deletedAt", JSONObject.NULL)))
        .put("budget_contributions", JSONArray().put(JSONObject().put("id", "00000000-0000-0000-0000-000000000018")
            .put("eventId", oldEvent).put("recordId", record).put("originRecordId", record).put("ledgerId", ledger)
            .put("categoryId", category).put("direction", "EXPENSE").put("periodDate", "2026-01-02")
            .put("amount", money(100)).put("deletedAt", JSONObject.NULL)))

    private fun apply(commandType: String, inputAmount: Long? = null): JSONObject {
        val body = if (commandType == "record.delete") JSONObject().put("dependencyStrategy", "REJECT") else {
            val input = JSONObject().put("kind", "EXPENSE").put("gross", money(inputAmount!!)).put("discount", JSONObject.NULL)
                .put("allocations", JSONArray().put(JSONObject().put("accountId", account).put("amount", money(inputAmount))))
                .put("countInStatistics", true).put("countInBudget", true)
            JSONObject().put("changes", JSONObject().put("ledgerId", ledger).put("categoryId", category)
                .put("occurredAt", "2026-01-02T03:04:05Z").put("timezone", "UTC").put("localDate", "2026-01-02")
                .put("remark", "updated").put("tagIds", JSONArray()).put("input", input))
        }
        val prediction = AccountPredictionEntity(opId, owner.toString(), epoch.toString(), "record", record, body.toString(), true)
        val intent = AccountMutationIntentEntity(opId, owner.toString(), epoch.toString(), "record", record, commandType, body.toString(), "QUEUED", 1)
        return tables().also { CanonicalAccountRepository.applyPredictions(it, listOf(prediction), listOf(intent), owner, epoch) }
    }

    private fun balance(tables: JSONObject, accountId: String = account): Long = (0 until tables.getJSONArray("account_postings").length())
        .map { tables.getJSONArray("account_postings").getJSONObject(it) }
        .filter { it.optString("accountId") == accountId }
        .sumOf { it.getJSONObject("signed").getString("unscaled").toLong() }

    @Test
    fun accountMetadataCreditAndInstallmentMarkersDoNotPredictFinancialOrCapacityFacts() {
        (listOf("metadata.archive", "metadata.update", "metadata.delete", "metadata.reorder", "credit.configure", "credit.capacityAdjust", "credit.allocatePayment")
            .map { "account" to it } + listOf("installment.create", "installment.update", "installment.delete").map { "installment" to it } +
            listOf("metadata.create", "metadata.update", "metadata.delete", "metadata.reorder").map { "account_group" to it } +
            listOf("metadata.create", "metadata.update", "metadata.archive", "metadata.delete", "metadata.reorder", "ledger.copy").map { "ledger" to it } +
            listOf("metadata.archive", "metadata.delete", "metadata.reorder", "category.move").map { "category" to it } +
            listOf("metadata.create", "metadata.update", "metadata.archive", "metadata.delete", "metadata.reorder").map { "tag" to it } +
            listOf("metadata.create", "metadata.update", "metadata.delete", "metadata.reorder").map { "tag_group" to it }).forEach { (aggregate, command) ->
            val canonical = tables().put("credit_profiles", JSONArray()).put("credit_capacity_events", JSONArray())
            val before = canonical.toString()
            val payload = JSONObject().put("capacityDelta", money(25)).toString()
            val prediction = AccountPredictionEntity(opId, owner.toString(), epoch.toString(), aggregate, account, payload, true)
            val intent = AccountMutationIntentEntity(opId, owner.toString(), epoch.toString(), aggregate, account, command, payload, "ACKED", 1)
            CanonicalAccountRepository.applyPredictions(canonical, listOf(prediction), listOf(intent), owner, epoch)
            assertEquals(before, canonical.toString())
        }
    }

    @Test
    fun accountTemplateCreatePredictionBuildsCanonicalRootAndChildren() {
        val templateId = "00000000-0000-0000-0000-000000000021"
        val tables = tables()
            .put("record_templates", JSONArray())
            .put("template_allocations", JSONArray())
            .put("template_tags", JSONArray())
        val record = JSONObject().put("ledgerId", ledger).put("categoryId", category)
            .put("occurredAt", "2026-01-02T03:04:05Z").put("timezone", "UTC").put("localDate", "2026-01-02")
            .put("remark", "template").put("tagIds", JSONArray()).put("input", JSONObject()
                .put("kind", "EXPENSE").put("gross", money(125)).put("discount", JSONObject.NULL)
                .put("allocations", JSONArray().put(JSONObject().put("accountId", account).put("amount", money(125))))
                .put("countInStatistics", true).put("countInBudget", true))
        val payload = JSONObject().put("entity", JSONObject().put("name", "groceries").put("record", record))
        val prediction = AccountPredictionEntity(opId, owner.toString(), epoch.toString(), "record_template", templateId, payload.toString(), true)
        val intent = AccountMutationIntentEntity(opId, owner.toString(), epoch.toString(), "record_template", templateId, "metadata.create", payload.toString(), "QUEUED", 1)
        CanonicalAccountRepository.applyPredictions(tables, listOf(prediction), listOf(intent), owner, epoch)
        val root = tables.getJSONArray("record_templates").getJSONObject(0)
        assertEquals(templateId, root.getString("id"))
        assertEquals("groceries", root.getString("name"))
        assertEquals(1L, root.getLong("serverRevision"))
        assertEquals(account, tables.getJSONArray("template_allocations").getJSONObject(0).getString("accountId"))
        assertEquals(1, tables.getJSONArray("template_allocations").length())
    }

    @Test
    fun recordUpdateReversesCanonicalPostingAndAppliesLiabilityRuleOnce() {
        val view = apply("record.update", 150)
        assertEquals(150L, balance(view))
        val predicted = (0 until view.getJSONArray("account_postings").length()).map { view.getJSONArray("account_postings").getJSONObject(it) }
            .single { it.optString("role") == "RECORD" }
        assertEquals(150L, predicted.getJSONObject("signed").getString("unscaled").toLong())
        val events = (0 until view.getJSONArray("financial_events").length()).map { view.getJSONArray("financial_events").getJSONObject(it) }
        val reversalEvent = events.single { it.optString("reversalOf") == oldEvent }.getString("id")
        val replacementEvent = events.single { it.optString("id") != oldEvent && it.optString("id") != reversalEvent }.getString("id")
        val newStatistic = (0 until view.getJSONArray("stat_contributions").length()).map { view.getJSONArray("stat_contributions").getJSONObject(it) }
            .single { it.getString("eventId") == replacementEvent }
        assertEquals(150L, newStatistic.getJSONObject("amount").getString("unscaled").toLong())
        val statistics = (0 until view.getJSONArray("stat_contributions").length())
            .map { view.getJSONArray("stat_contributions").getJSONObject(it) }
        assertEquals(150L, statistics.sumOf { it.getJSONObject("amount").getString("unscaled").toLong() })
        val budgetContributions = (0 until view.getJSONArray("budget_contributions").length())
            .map { view.getJSONArray("budget_contributions").getJSONObject(it) }
        assertEquals(0L, budgetContributions.sumOf { it.getJSONObject("amount").getString("unscaled").toLong() })
        assertEquals("EXPENSE", view.getJSONArray("records").getJSONObject(0).getString("direction"))
        assertEquals(reversalEvent, budgetContributions.single { it.getString("eventId") != oldEvent }.getString("eventId"))
    }

    @Test
    fun crossCurrencyExpensePredictionKeepsBothAmountsAndHistoricalReversalFacts() {
        val usdAccount = "00000000-0000-0000-0000-000000000026"
        val tables = tables().put("fx_snapshots", JSONArray())
        tables.getJSONArray("categories").objects().forEach { row ->
            row.put("ledgerId", ledger).put("name", "Expense").put("direction", "EXPENSE").put("excludeBudget", false)
        }
        tables.getJSONArray("accounts").getJSONObject(0)
            .put("visibilityMode", "ALL").put("name", "Cash").put("currency", "CNY")
            .put("serverRevision", 1).put("archivedAt", JSONObject.NULL).put("includeInAssets", true)
            .put("selectable", true).put("groupId", "00000000-0000-0000-0000-000000000040").put("sortOrder", 0)
        tables.getJSONArray("accounts").put(JSONObject().put("id", usdAccount).put("type", "ASSET").put("currency", "USD")
            .put("visibilityMode", "ALL").put("name", "USD").put("serverRevision", 1).put("archivedAt", JSONObject.NULL)
            .put("includeInAssets", true).put("selectable", true).put("groupId", "00000000-0000-0000-0000-000000000040").put("sortOrder", 1))
        val input = JSONObject().put("kind", "EXPENSE").put("gross", JSONObject().put("unscaled", "10000").put("scale", 2).put("currency", "CNY"))
            .put("discount", JSONObject().put("unscaled", "1000").put("scale", 2).put("currency", "CNY"))
            .put("allocations", JSONArray()
                .put(JSONObject().put("accountId", account).put("amount", JSONObject().put("unscaled", "3000").put("scale", 2).put("currency", "CNY")))
                .put(JSONObject().put("accountId", usdAccount).put("amount", JSONObject().put("unscaled", "450").put("scale", 2).put("currency", "USD"))
                    .put("recordCurrencyAmount", JSONObject().put("unscaled", "6000").put("scale", 2).put("currency", "CNY"))))
            .put("countInStatistics", true).put("countInBudget", true)
        val body = JSONObject().put("changes", JSONObject().put("ledgerId", ledger).put("categoryId", category)
            .put("occurredAt", "2026-01-02T03:04:05Z").put("timezone", "UTC").put("localDate", "2026-01-02")
            .put("remark", "fx update").put("tagIds", JSONArray()).put("input", input))
        val prediction = AccountPredictionEntity(opId, owner.toString(), epoch.toString(), "record", record, body.toString(), true)
        val intent = AccountMutationIntentEntity(opId, owner.toString(), epoch.toString(), "record", record, "record.update", body.toString(), "QUEUED", 1)
        CanonicalAccountRepository.applyPredictions(tables, listOf(prediction), listOf(intent), owner, epoch)
        val newRecordAllocations = tables.getJSONArray("record_allocations").objects().filter { it.stringOrNull("deletedAt") == null }
        assertEquals(setOf(3000L, 6000L), newRecordAllocations.map { it.money("recordCurrencyAmount").unscaled }.toSet())
        assertEquals(450L, newRecordAllocations.single { it.string("accountId") == usdAccount }.money("accountAmount").unscaled)
        val newEvent = tables.getJSONArray("records").objects().single { it.string("id") == record }.string("eventId")
        assertEquals(-450L, tables.getJSONArray("account_postings").objects().single { it.string("eventId") == newEvent && it.string("accountId") == usdAccount }.money("signed").unscaled)
        val stat = tables.getJSONArray("stat_contributions").objects().single { it.string("eventId") == newEvent }
        assertEquals(9000L, stat.money("amount").unscaled)
        assertEquals(9000L, tables.getJSONArray("budget_contributions").objects().first { it.string("eventId") == newEvent }.money("amount").unscaled)
        assertEquals("CNY", tables.getJSONArray("fx_snapshots").getJSONObject(0).getString("fromCurrency"))
        assertEquals("USD", tables.getJSONArray("fx_snapshots").getJSONObject(0).getString("toCurrency"))
        assertEquals("0.075000000000000000", tables.getJSONArray("fx_snapshots").getJSONObject(0).getString("rate"))
        val canonicalAllocations = newRecordAllocations.map { row ->
            JSONObject().put("id", row.getString("id")).put("accountId", row.getString("accountId")).put("role", "PAYMENT")
                .put("recordId", row.getString("recordId"))
                .put("accountAmountUnscaled", row.getJSONObject("accountAmount").getString("unscaled"))
                .put("accountAmountScale", row.getJSONObject("accountAmount").getInt("scale"))
                .put("accountAmountCurrency", row.getJSONObject("accountAmount").getString("currency"))
                .put("recordCurrencyAmountUnscaled", row.getJSONObject("recordCurrencyAmount").getString("unscaled"))
                .put("recordCurrencyAmountScale", row.getJSONObject("recordCurrencyAmount").getInt("scale"))
                .put("recordCurrencyAmountCurrency", row.getJSONObject("recordCurrencyAmount").getString("currency"))
                .put("fxSnapshotId", row.opt("fxSnapshotId") ?: JSONObject.NULL).put("hasFxAllocation", row.optBoolean("hasFxAllocation"))
        }
        val viewTables = JSONObject().put("records", tables.getJSONArray("records")).put("financial_events", tables.getJSONArray("financial_events"))
            .put("record_allocations", JSONArray().also { a -> canonicalAllocations.forEach(a::put) })
            .put("account_postings", tables.getJSONArray("account_postings")).put("accounts", tables.getJSONArray("accounts"))
            .put("record_tags", tables.getJSONArray("record_tags")).put("ledgers", JSONArray().put(JSONObject().put("id", ledger).put("name", "Default").put("baseCurrency", "CNY").put("isDefault", true)))
            .put("categories", tables.getJSONArray("categories")).put("account_ledger_visibility", JSONArray())
            .put("stat_contributions", tables.getJSONArray("stat_contributions")).put("budget_contributions", tables.getJSONArray("budget_contributions"))
            .put("tag_groups", JSONArray()).put("tags", JSONArray()).put("account_groups", JSONArray()).put("business_preferences", JSONArray())
            .put("claim_items", JSONArray()).put("claim_settlements", JSONArray()).put("refunds", JSONArray()).put("saving_plans", JSONArray())
            .put("saving_entries", JSONArray()).put("loan_contributions", JSONArray()).put("loan_plans", JSONArray()).put("loan_terms", JSONArray())
            .put("credit_profiles", JSONArray()).put("credit_capacity_events", JSONArray()).put("credit_payment_allocations", JSONArray())
            .put("installment_plans", JSONArray()).put("installment_terms", JSONArray()).put("budgets", JSONArray()).put("budget_configs", JSONArray())
            .put("budget_entries", JSONArray()).put("record_templates", JSONArray()).put("template_allocations", JSONArray()).put("template_tags", JSONArray())
            .put("event_relations", JSONArray()).put("account_postings", tables.getJSONArray("account_postings"))
            .put("recurrence_occurrences", JSONArray()).put("loan_events", JSONArray())
        val snapshot = CanonicalAccountRepository.readTables(viewTables, owner, epoch)
        val hiddenAccountTables = JSONObject(viewTables.toString())
        hiddenAccountTables.getJSONArray("accounts").objects().single { it.getString("id") == usdAccount }
            .put("visibilityMode", "SELECTED")
        val hiddenView = CanonicalAccountRepository.readTables(hiddenAccountTables, owner, epoch).snapshot
        org.junit.Assert.assertFalse(hiddenView.accounts.any { it.id.toString() == usdAccount })
        assertEquals(-450L, hiddenView.allAccounts.single { it.id.toString() == usdAccount }.balance.unscaled)
        assertEquals("SELECTED", hiddenView.allAccounts.single { it.id.toString() == usdAccount }.visibilityMode)
        assertEquals(1, hiddenView.ledgers.size)
        org.junit.Assert.assertTrue(snapshot.snapshot.records.single { it.id.toString() == record }.accountMutationSupported)
        val linkedTables = JSONObject(viewTables.toString()).put("credit_payment_allocations", JSONArray().put(
            JSONObject().put("id", opId).put("accountId", account).put("paymentEventId", newEvent)
                .put("termId", ledger).put("amount", money(1))
        ))
        org.junit.Assert.assertFalse(CanonicalAccountRepository.readTables(linkedTables, owner, epoch)
            .snapshot.records.single { it.id.toString() == record }.accountMutationSupported)
        val restored = snapshot.snapshot.records.single { it.id.toString() == record }.input as dev.icos.app.domain.RecordInput.Expense
        assertEquals(mapOf(
            account to Money(3000, 2, "CNY"),
            usdAccount to Money(450, 2, "USD"),
        ), restored.allocations.associate { it.accountId.toString() to it.amount })
        val restoredRecordAmounts = requireNotNull(restored.recordCurrencyAmounts)
        assertEquals(mapOf(
            account to Money(3000, 2, "CNY"),
            usdAccount to Money(6000, 2, "CNY"),
        ), restored.allocations.mapIndexed { index, allocation -> allocation.accountId.toString() to restoredRecordAmounts[index] }.toMap())
        val deleteBody = JSONObject().put("dependencyStrategy", "REJECT")
        val deletePrediction = AccountPredictionEntity(opId, owner.toString(), epoch.toString(), "record", record, deleteBody.toString(), true)
        val deleteIntent = AccountMutationIntentEntity(opId, owner.toString(), epoch.toString(), "record", record, "record.delete", deleteBody.toString(), "QUEUED", 1)
        CanonicalAccountRepository.applyPredictions(tables, listOf(deletePrediction), listOf(deleteIntent), owner, epoch)
        assertEquals(0L, balance(tables, usdAccount))
    }

    @Test
    fun canonicalIncomeAndTransferIgnoreRecordCurrencyFieldsOnAllocationRows() {
        val tables = tables()
        tables.getJSONArray("accounts").getJSONObject(0).put("type", "ASSET")
        val event = tables.getJSONArray("financial_events").getJSONObject(0)
        val expenseRecord = tables.getJSONArray("records").getJSONObject(0)
        val canonicalAllocation = JSONObject()
            .put("id", "00000000-0000-0000-0000-000000000030")
            .put("recordId", record).put("accountId", account).put("role", "RECEIPT")
            .put("accountAmount", money(100)).put("recordCurrencyAmount", money(100))
            .put("deletedAt", JSONObject.NULL)
        val incomeRecord = JSONObject(expenseRecord.toString()).put("direction", "INCOME").put("actual", money(100))
        val income = CanonicalAccountRepository.input(tables, incomeRecord, event, listOf(canonicalAllocation))
        assertEquals(RecordInput.Income(Money(100, 0, "CNY"), listOf(dev.icos.app.domain.AccountAmount(UUID.fromString(account), Money(100, 0, "CNY"))), true), income)

        val from = JSONObject(canonicalAllocation.toString()).put("role", "FROM")
        val to = JSONObject(canonicalAllocation.toString()).put("id", "00000000-0000-0000-0000-000000000031")
            .put("accountId", "00000000-0000-0000-0000-000000000032").put("role", "TO")
            .put("selectable", true).put("groupId", "00000000-0000-0000-0000-000000000040").put("sortOrder", 0)
        tables.getJSONArray("accounts").put(JSONObject().put("id", "00000000-0000-0000-0000-000000000032").put("type", "ASSET")
            .put("visibilityMode", "ALL").put("name", "Other").put("currency", "CNY").put("serverRevision", 1)
            .put("archivedAt", JSONObject.NULL).put("includeInAssets", true).put("selectable", true)
            .put("groupId", "00000000-0000-0000-0000-000000000040").put("sortOrder", 2))
        tables.getJSONArray("account_postings").getJSONObject(0).put("signed", money(-100))
        tables.getJSONArray("account_postings").put(JSONObject().put("id", "00000000-0000-0000-0000-000000000034")
            .put("eventId", oldEvent).put("accountId", "00000000-0000-0000-0000-000000000032").put("signed", money(100)).put("reversalOf", JSONObject.NULL))
        val transferRecord = JSONObject(expenseRecord.toString()).put("direction", "TRANSFER")
        val transfer = CanonicalAccountRepository.input(tables, transferRecord, event, listOf(from, to)) as RecordInput.Transfer
        assertEquals("CNY", transfer.from.amount.currency)
        assertEquals("CNY", transfer.to.amount.currency)
    }

    @Test
    fun recordDeleteReversesExistingCanonicalPosting() {
        val view = apply("record.delete")
        assertEquals(0L, balance(view))
        assertEquals("PENDING", view.getJSONArray("records").getJSONObject(0).getString("deletedAt"))
    }

    @Test
    fun sameCurrencyTransferPredictionCreatesCanonicalFeeAndDiscountChildren() {
        val source = account
        val destination = "00000000-0000-0000-0000-000000000021"
        val feeAccount = "00000000-0000-0000-0000-000000000022"
        val bonusAccount = "00000000-0000-0000-0000-000000000023"
        val feeCategory = "00000000-0000-0000-0000-000000000024"
        val bonusCategory = "00000000-0000-0000-0000-000000000025"
        val tables = tables()
            .put("records", JSONArray()).put("financial_events", JSONArray()).put("account_postings", JSONArray())
            .put("record_allocations", JSONArray()).put("record_tags", JSONArray()).put("event_relations", JSONArray())
            .put("stat_contributions", JSONArray()).put("budget_contributions", JSONArray())
        tables.getJSONArray("accounts").put(JSONObject().put("id", destination).put("type", "ASSET"))
            .put(JSONObject().put("id", feeAccount).put("type", "LIABILITY"))
            .put(JSONObject().put("id", bonusAccount).put("type", "ASSET"))
        tables.getJSONArray("categories").put(JSONObject().put("id", feeCategory).put("excludeBudget", false).put("parentId", JSONObject.NULL))
            .put(JSONObject().put("id", bonusCategory).put("excludeBudget", false).put("parentId", JSONObject.NULL))
        val transfer = JSONObject().put("kind", "TRANSFER")
            .put("from", JSONObject().put("accountId", source).put("amount", money(100)))
            .put("to", JSONObject().put("accountId", destination).put("amount", money(100)))
            .put("fee", JSONObject().put("accountId", feeAccount).put("amount", money(5)))
            .put("bonus", JSONObject().put("accountId", bonusAccount).put("amount", money(2)))
        val wrapper = JSONObject().put("ledgerId", ledger).put("categoryId", JSONObject.NULL)
            .put("feeCategoryId", feeCategory).put("bonusCategoryId", bonusCategory)
            .put("occurredAt", "2026-01-02T03:04:05Z").put("timezone", "UTC").put("localDate", "2026-01-02")
            .put("remark", "transfer").put("input", transfer)
        val payload = JSONObject().put("record", wrapper)
        val prediction = AccountPredictionEntity(opId, owner.toString(), epoch.toString(), "record", record, payload.toString(), true)
        val intent = AccountMutationIntentEntity(opId, owner.toString(), epoch.toString(), "record", record, "record.create", payload.toString(), "QUEUED", 1)

        CanonicalAccountRepository.applyPredictions(tables, listOf(prediction), listOf(intent), owner, epoch)

        assertEquals(3, tables.getJSONArray("records").length())
        assertEquals(3, tables.getJSONArray("financial_events").length())
        assertEquals(2, tables.getJSONArray("event_relations").length())
        val root = tables.getJSONArray("records").objects().single { it.string("id") == record }
        assertEquals("TRANSFER", root.string("direction"))
        assertEquals(JSONObject.NULL, root.opt("categoryId"))
        val children = tables.getJSONArray("records").objects().filter { it.stringOrNull("parentRecordId") == record }
        assertEquals(setOf("EXPENSE", "INCOME"), children.map { it.string("direction") }.toSet())
        assertEquals(0, tables.getJSONArray("stat_contributions").objects().count { it.string("originRecordId") == record })
        assertEquals(1, tables.getJSONArray("budget_contributions").objects().count { it.money("amount").unscaled == 5L })
        val postings = tables.getJSONArray("account_postings").objects()
        assertEquals(100L, postings.single { it.string("accountId") == source }.money("signed").unscaled)
        assertEquals(100L, postings.single { it.string("accountId") == destination }.money("signed").unscaled)
        assertEquals(5L, postings.single { it.string("accountId") == feeAccount }.money("signed").unscaled)
        assertEquals(2L, postings.single { it.string("accountId") == bonusAccount }.money("signed").unscaled)

        val decoded = RecordInputJson.decode(transfer)
        assertEquals(4, decoded.calculate().postings.size)
        assertEquals(2, decoded.calculate().statistics.size)
        assertEquals(1, decoded.calculate().budget.size)
    }

    @Test
    fun savingProgressOnlyEntryNeverCreatesFinancialFacts() {
        val planId = "00000000-0000-0000-0000-000000000050"
        val entryId = "00000000-0000-0000-0000-000000000051"
        val view = tables().put("saving_plans", JSONArray().put(JSONObject().put("id", planId)
            .put("serverRevision", 3).put("deletedAt", JSONObject.NULL)))
            .put("saving_entries", JSONArray())
        val unchanged = listOf("records", "financial_events", "account_postings", "stat_contributions", "budget_contributions")
            .associateWith { view.getJSONArray(it).toString() }
        val body = JSONObject().put("entryId", entryId).put("kind", "DEPOSIT").put("amount", money(50))
            .put("occurredAt", "2026-01-02T03:04:05Z").put("linkedTransfer", JSONObject.NULL)
        fun applySaving(command: String, payload: JSONObject) {
            val prediction = AccountPredictionEntity(opId, owner.toString(), epoch.toString(), "saving", planId, payload.toString(), true)
            val intent = AccountMutationIntentEntity(opId, owner.toString(), epoch.toString(), "saving", planId, command, payload.toString(), "QUEUED", 1)
            CanonicalAccountRepository.applyPredictions(view, listOf(prediction), listOf(intent), owner, epoch)
        }
        applySaving("saving.entry", body)
        val entry = view.getJSONArray("saving_entries").getJSONObject(0)
        assertEquals(entryId, entry.getString("id"))
        assertEquals(0L, entry.getLong("serverRevision"))
        assertEquals(JSONObject.NULL, entry.get("linkedRecordId"))
        assertEquals(4L, view.getJSONArray("saving_plans").getJSONObject(0).getLong("serverRevision"))
        applySaving("saving.cancelEntry", JSONObject().put("entryId", entryId))
        org.junit.Assert.assertFalse(entry.isNull("cancelledAt"))
        unchanged.forEach { (table, original) -> assertEquals(table, original, view.getJSONArray(table).toString()) }
    }

    @Test
    fun savingLinkedTransferCancellationAndDeleteStrategiesPreserveExactBalances() {
        val planId = "00000000-0000-0000-0000-000000000050"
        val entryId = "00000000-0000-0000-0000-000000000051"
        val linkedRecord = "00000000-0000-0000-0000-000000000052"
        val destination = "00000000-0000-0000-0000-000000000053"
        fun createLinked(): JSONObject {
            val view = tables().put("records", JSONArray()).put("financial_events", JSONArray())
                .put("account_postings", JSONArray()).put("record_allocations", JSONArray())
                .put("record_tags", JSONArray()).put("event_relations", JSONArray())
                .put("stat_contributions", JSONArray()).put("budget_contributions", JSONArray())
                .put("saving_plans", JSONArray().put(JSONObject().put("id", planId).put("serverRevision", 3).put("deletedAt", JSONObject.NULL)))
                .put("saving_entries", JSONArray())
            view.getJSONArray("accounts").put(JSONObject().put("id", destination).put("type", "ASSET"))
            val input = JSONObject().put("kind", "TRANSFER")
                .put("from", JSONObject().put("accountId", account).put("amount", money(50)))
                .put("to", JSONObject().put("accountId", destination).put("amount", money(50)))
                .put("fee", JSONObject.NULL).put("bonus", JSONObject.NULL)
            val transfer = JSONObject().put("ledgerId", ledger).put("categoryId", JSONObject.NULL)
                .put("occurredAt", "2026-01-02T03:04:05Z").put("timezone", "UTC").put("localDate", "2026-01-02")
                .put("remark", "saving").put("tagIds", JSONArray()).put("input", input)
            val body = JSONObject().put("entryId", entryId).put("kind", "DEPOSIT").put("amount", money(50))
                .put("occurredAt", "2026-01-02T03:04:05Z")
                .put("linkedTransfer", JSONObject().put("recordId", linkedRecord).put("record", transfer))
            val prediction = AccountPredictionEntity(opId, owner.toString(), epoch.toString(), "saving", planId, body.toString(), true)
            val intent = AccountMutationIntentEntity(opId, owner.toString(), epoch.toString(), "saving", planId, "saving.entry", body.toString(), "QUEUED", 1)
            CanonicalAccountRepository.applyPredictions(view, listOf(prediction), listOf(intent), owner, epoch)
            return view
        }
        for (command in listOf("saving.cancelEntry", "KEEP_TRANSFERS", "DELETE_TRANSFERS")) {
            val view = createLinked()
            assertEquals(50L, balance(view))
            assertEquals(50L, balance(view, destination))
            assertEquals(0, view.getJSONArray("stat_contributions").length())
            assertEquals(0, view.getJSONArray("budget_contributions").length())
            val body = if (command == "saving.cancelEntry") JSONObject().put("entryId", entryId)
                else JSONObject().put("transferStrategy", command)
            val type = if (command == "saving.cancelEntry") command else "saving.delete"
            val prediction = AccountPredictionEntity(opId, owner.toString(), epoch.toString(), "saving", planId, body.toString(), true)
            val intent = AccountMutationIntentEntity(opId, owner.toString(), epoch.toString(), "saving", planId, type, body.toString(), "QUEUED", 1)
            CanonicalAccountRepository.applyPredictions(view, listOf(prediction), listOf(intent), owner, epoch)
            val retained = command == "KEEP_TRANSFERS"
            assertEquals(if (retained) 50L else 0L, balance(view))
            assertEquals(if (retained) 50L else 0L, balance(view, destination))
            assertEquals(retained, view.getJSONArray("records").getJSONObject(0).isNull("deletedAt"))
            assertEquals(0, view.getJSONArray("stat_contributions").length())
            assertEquals(0, view.getJSONArray("budget_contributions").length())
        }
    }

    @Test
    fun budgetPredictionUsesAggregateLedgerAndKeepsFinancialFactsUnchanged() {
        val view = tables().put("budgets", JSONArray())
            .put("ledgers", JSONArray().put(JSONObject().put("id", ledger).put("baseCurrency", "CNY")))
        val financial = listOf("records", "account_postings", "financial_events", "stat_contributions", "budget_contributions")
            .associateWith { view.getJSONArray(it).toString() }
        fun applyBudget(command: String, body: JSONObject) {
            val prediction = AccountPredictionEntity(opId, owner.toString(), epoch.toString(), "budget", ledger, body.toString(), true)
            val intent = AccountMutationIntentEntity(opId, owner.toString(), epoch.toString(), "budget", ledger, command, body.toString(), "QUEUED", 1)
            CanonicalAccountRepository.applyPredictions(view, listOf(prediction), listOf(intent), owner, epoch)
        }
        applyBudget("budget.configure", JSONObject().put("periodType", "MONTH").put("scope", "CURRENT")
            .put("periodStart", "2026-01-01").put("entries", JSONArray()
                .put(JSONObject().put("categoryId", JSONObject.NULL).put("amount", money(500)))
                .put(JSONObject().put("categoryId", category).put("amount", money(100)))))
        val root = view.getJSONArray("budgets").getJSONObject(0)
        assertEquals(ledger, root.getString("id"))
        assertEquals(ledger, root.getString("ledgerId"))
        assertEquals(1L, root.getLong("serverRevision"))
        val config = root.getJSONArray("configs").getJSONObject(0)
        assertEquals(500L, config.getJSONArray("entries").getJSONObject(0).money("amount").unscaled)
        applyBudget("budget.reset", JSONObject().put("periodType", "MONTH").put("periodStart", "2026-01-01").put("mode", "CLEAR_CURRENT"))
        assertEquals(2, config.getJSONArray("entries").length())
        config.getJSONArray("entries").objects().forEach { assertEquals(0L, it.money("amount").unscaled) }
        applyBudget("budget.reset", JSONObject().put("periodType", "MONTH").put("periodStart", "2026-01-01").put("mode", "RESET_DEFAULT"))
        org.junit.Assert.assertFalse(config.isNull("deletedAt"))
        financial.forEach { (table, before) -> assertEquals(table, before, view.getJSONArray(table).toString()) }
    }

    @Test
    fun canonicalBooleanPreservesSqliteAndWireRepresentations() {
        for (value in listOf<Any>(true, 1, 1L, "true", "1")) {
            assertEquals(true, JSONObject().put("flag", value).canonicalBoolean("flag"))
        }
        for (value in listOf<Any>(false, 0, 0L, "false", "0")) {
            assertEquals(false, JSONObject().put("flag", value).canonicalBoolean("flag", true))
        }
        assertEquals(true, JSONObject().canonicalBoolean("flag", true))
        assertEquals(false, JSONObject().put("flag", JSONObject.NULL).canonicalBoolean("flag"))
    }

    private fun JSONArray.objects() = List(length()) { getJSONObject(it) }
    private fun JSONObject.string(key: String): String = get(key).toString()
    private fun JSONObject.stringOrNull(key: String): String? = if (isNull(key)) null else get(key).toString()
    private fun JSONObject.money(key: String) = getJSONObject(key).let {
        dev.icos.app.domain.Money(it.getString("unscaled").toLong(), it.getInt("scale"), it.getString("currency"))
    }
}
