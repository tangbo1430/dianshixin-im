package dev.icos.app.fileio

import dev.icos.app.data.local.FinanceSnapshot
import dev.icos.app.domain.RecordInput
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.json.JSONObject

/** Explicit P60 subset over an already-fixed canonical snapshot. Dates are inclusive local dates. */
data class AccountExportFilter(
    val fromDate: LocalDate? = null,
    val throughDate: LocalDate? = null,
    val kinds: Set<AccountExportKind> = AccountExportKind.entries.toSet(),
) {
    init {
        require(fromDate == null || throughDate == null || !fromDate.isAfter(throughDate)) { "EXPORT_INVALID_DATE_RANGE" }
        require(kinds.isNotEmpty()) { "EXPORT_EMPTY_TYPE_FILTER" }
    }
}

enum class AccountExportKind(val label: String) {
    EXPENSE("支出"), INCOME("收入"), TRANSFER("转账");

    companion object {
        fun from(input: RecordInput): AccountExportKind = when (input) {
            is RecordInput.Expense -> EXPENSE
            is RecordInput.Income -> INCOME
            is RecordInput.Transfer -> TRANSFER
        }
    }
}

internal data class AccountExportTicket(
    val owner: java.util.UUID,
    val epoch: java.util.UUID,
    val ledger: java.util.UUID,
    val generation: Long,
    val identity: dev.icos.app.data.remote.DeviceIdentity,
)

internal data class AccountExportRequest(
    val ticket: AccountExportTicket,
    val filter: AccountExportFilter,
    val format: AccountExportFormat,
    val nonce: java.util.UUID,
    val pickerLaunched: Boolean = false,
)

enum class AccountExportFormat(val mime: String, val extension: String) {
    CSV("text/csv", "csv"), XLSX("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx")
}

object AccountFileExport {
    internal fun filterRecords(snapshot: FinanceSnapshot, filter: AccountExportFilter) = snapshot.records.filter { record ->
        val kind = AccountExportKind.from(record.input)
        val localDate = Instant.ofEpochMilli(record.occurredAtMillis).atZone(ZoneId.of(record.timezone)).toLocalDate()
        kind in filter.kinds && (filter.fromDate == null || !localDate.isBefore(filter.fromDate)) &&
            (filter.throughDate == null || !localDate.isAfter(filter.throughDate))
    }

    internal fun validateRecordFacts(
        id: String,
        claimState: String,
        hasAttachments: Boolean,
        financialMode: String,
        direction: String,
        countInStatistics: Boolean,
        countInBudget: Boolean,
    ) {
        require(claimState == "NONE") { "LOSSY_EXPORT_UNSUPPORTED_CLAIM:$id" }
        require(!hasAttachments) { "LOSSY_EXPORT_UNSUPPORTED_ATTACHMENT:$id" }
        require(financialMode == "STANDARD") { "LOSSY_EXPORT_UNSUPPORTED_SPECIAL_RECORD:$id" }
        val expectedStatistics = direction in setOf("EXPENSE", "INCOME")
        val expectedBudget = direction == "EXPENSE"
        require(direction in setOf("EXPENSE", "INCOME", "TRANSFER") &&
            countInStatistics == expectedStatistics && countInBudget == expectedBudget) {
            "LOSSY_EXPORT_UNSUPPORTED_STATISTICS_FLAGS:$id"
        }
    }

    fun rows(snapshot: FinanceSnapshot, filter: AccountExportFilter, rawTables: JSONObject): FileExportSnapshot {
        val filtered = filterRecords(snapshot, filter)
        val ids = filtered.mapTo(HashSet()) { it.id.toString() }
        fun rows(name: String) = rawTables.optJSONArray(name)?.let { array -> List(array.length()) { array.getJSONObject(it) } }.orEmpty()
        fun active(row: JSONObject) = row.isNull("deletedAt") && row.isNull("cancelledAt") &&
            row.optString("status") !in setOf("CANCELLED", "REVERSED", "DELETED")
        val ledger = snapshot.ledgerId.toString()
        val rawRecords = rows("records").filter { it.optString("id") in ids && it.optString("ledgerId") == ledger }
        require(rawRecords.map { it.optString("id") }.toSet() == ids) { "EXPORT_CANONICAL_RECORD_MISMATCH" }
        val snapshotKinds = filtered.associate { it.id.toString() to AccountExportKind.from(it.input).name }
        require(rawRecords.all { it.optString("direction") == snapshotKinds[it.optString("id")] }) {
            "EXPORT_CANONICAL_RECORD_MISMATCH"
        }

        // Claims and refunds carry separate settlement facts that the ten-column contract cannot encode.
        val relatedClaims = rows("claim_items").filter { it.optString("originRecordId") in ids }
        val claimItemIds = relatedClaims.mapTo(HashSet()) { it.optString("id") }
        val relatedClaimAllocations = rows("claim_allocations").filter { it.optString("claimItemId") in claimItemIds }
        val relatedSettlementIds = relatedClaimAllocations.mapTo(HashSet()) { it.optString("settlementId") }
        require(relatedClaims.isEmpty() && relatedClaimAllocations.isEmpty() &&
            rows("claim_settlements").none { it.optString("id") in relatedSettlementIds }) {
            "LOSSY_EXPORT_UNSUPPORTED_CLAIM"
        }
        require(rows("refunds").none { it.optString("originRecordId") in ids }) {
            "LOSSY_EXPORT_UNSUPPORTED_REFUND"
        }

        rawRecords.forEach { row ->
            val id = row.optString("id")
            require(row.optString("status") == "POSTED" && row.isNull("deletedAt") && row.isNull("parentRecordId") && row.isNull("templateId") && row.isNull("importBatchId")) {
                "LOSSY_EXPORT_UNSUPPORTED_RECORD_RELATION:$id"
            }
            validateRecordFacts(
                id = id,
                claimState = row.optString("claimState", "NONE"),
                hasAttachments = row.optJSONArray("attachmentIds")?.length()?.let { it > 0 } == true,
                financialMode = row.optString("financialMode", "STANDARD"),
                direction = row.optString("direction"),
                countInStatistics = row.optBoolean("countInStatistics", row.optString("direction") in setOf("EXPENSE", "INCOME")),
                countInBudget = row.optBoolean("countInBudget", row.optString("direction") == "EXPENSE"),
            )
        }
        val rawAllocations = rows("record_allocations").filter { it.optString("recordId") in ids && active(it) }
        require(rawAllocations.isNotEmpty() || ids.isEmpty()) { "EXPORT_CANONICAL_ALLOCATION_MISMATCH" }
        require(rawAllocations.none { !it.isNull("fxSnapshotId") || it.optString("role") in setOf("FEE", "BONUS") }) {
            "LOSSY_EXPORT_UNSUPPORTED_ALLOCATION"
        }
        require(rawAllocations.map { it.optString("recordId") }.toSet().containsAll(ids)) { "EXPORT_CANONICAL_ALLOCATION_MISMATCH" }
        val allocationsByRecord = rawAllocations.groupBy { it.optString("recordId") }
        require(rawRecords.all { row ->
            val roles = allocationsByRecord[row.optString("id")].orEmpty().map { it.optString("role") }
            when (row.optString("direction")) {
                "EXPENSE" -> roles.size == 1 && roles.single() == "PAYMENT"
                "INCOME" -> roles.size == 1 && roles.single() == "RECEIPT"
                "TRANSFER" -> roles.size == 2 && roles.count { it == "FROM" } == 1 && roles.count { it == "TO" } == 1
                else -> false
            }
        }) { "LOSSY_EXPORT_UNSUPPORTED_ALLOCATION" }
        val eventIds = rawRecords.mapNotNullTo(HashSet()) { it.optString("eventId").takeIf(String::isNotBlank) }
        require(rawRecords.all { it.optString("eventId").isNotBlank() }) { "EXPORT_CANONICAL_EVENT_MISMATCH" }
        val rawEvents = rows("financial_events").filter { it.optString("id") in eventIds && it.optString("ledgerId") == ledger }
        require(rawEvents.map { it.optString("id") }.toSet() == eventIds) { "EXPORT_CANONICAL_EVENT_MISMATCH" }
        val recordDirectionByEvent = rawRecords.associate { it.optString("eventId") to it.optString("direction") }
        require(rawEvents.all { event ->
            event.optString("status") == "POSTED" && event.isNull("reversalOf") &&
                event.optString("kind") == recordDirectionByEvent[event.optString("id")]
        }) { "LOSSY_EXPORT_UNSUPPORTED_EVENT_HISTORY" }
        require(rows("financial_events").none { it.optString("recordId") in ids && it.optString("id") !in eventIds }) {
            "LOSSY_EXPORT_UNSUPPORTED_RECORD_RELATION"
        }
        require(rawEvents.none { event ->
            val kind = event.optString("kind")
            val expectedStatistics = kind in setOf("EXPENSE", "INCOME")
            val expectedBudget = kind == "EXPENSE"
            kind !in setOf("EXPENSE", "INCOME", "TRANSFER") ||
                event.optBoolean("countInStatistics", expectedStatistics) != expectedStatistics ||
                event.optBoolean("countInBudget", expectedBudget) != expectedBudget
        } || rawEvents.isEmpty() && rawRecords.isNotEmpty()) { "LOSSY_EXPORT_UNSUPPORTED_STATISTICS_FLAGS" }
        require(rows("event_relations").none { active(it) && (it.optString("parentEventId") in eventIds || it.optString("childEventId") in eventIds) } &&
            rows("record_segments").none { active(it) && it.optString("originRecordId") in ids } &&
            rows("saving_entries").none { active(it) && it.optString("linkedRecordId") in ids } &&
            rows("recurrence_occurrences").none { active(it) && it.optString("recordId") in ids } &&
            rows("installment_plans").none { active(it) && it.optString("sourceRecordId") in ids } &&
            rows("credit_payment_allocations").none { active(it) && it.optString("paymentEventId") in eventIds }) {
            "LOSSY_EXPORT_UNSUPPORTED_RECORD_RELATION"
        }
        require(rows("loan_plans").none { active(it) && it.optString("disbursementEventId") in eventIds } &&
            rows("loan_terms").none { active(it) && (it.optString("postedEventId") in eventIds || it.optString("paidEventId") in eventIds) } &&
            rows("credit_capacity_events").none { active(it) && it.optString("eventId") in eventIds }) {
            "LOSSY_EXPORT_UNSUPPORTED_SPECIAL_EVENT"
        }
        require(filtered.size < TabularFileCore.MAX_ROWS) { "TOO_MANY_ROWS" }
        val fixed = snapshot.copy(records = filtered)
        val accounts = fixed.historicalAccountNames + fixed.allAccounts.associate { it.id to it.name }
        val categories = fixed.categories.associate { it.id to it.name }
        return FinanceFileExportMapper.snapshot(fixed, filtered.maxOfOrNull { it.localRevision } ?: 0L, accounts, categories)
    }
}
