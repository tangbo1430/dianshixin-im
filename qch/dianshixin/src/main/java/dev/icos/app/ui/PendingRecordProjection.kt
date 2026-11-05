package dev.icos.app.ui

import dev.icos.app.data.local.AccountStatisticProjection
import dev.icos.app.data.local.FinanceAccount
import dev.icos.app.data.local.FinanceBudgetOverview
import dev.icos.app.data.local.FinanceCategory
import dev.icos.app.data.local.FinanceRecord
import dev.icos.app.data.local.FinanceSnapshot
import dev.icos.app.data.local.FinanceStatisticEntry
import dev.icos.app.data.local.FinanceSummary
import dev.icos.app.data.local.liabilityAwareStoredAmount
import dev.icos.app.domain.Direction
import dev.icos.app.domain.Money
import dev.icos.app.domain.PeriodRange
import dev.icos.app.domain.RecordInput
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone
import java.util.UUID

internal data class PendingRecordSave(
    val ownerId: UUID,
    val epochId: UUID,
    val ledgerId: UUID,
    val record: FinanceRecord,
)

/** Display-only overlay. Records, balances and statistic entries remain complete; only summaries use [range]. */
internal fun projectPendingRecords(
    snapshot: FinanceSnapshot,
    pending: Collection<PendingRecordSave>,
    range: PeriodRange? = null,
): FinanceSnapshot {
    val records = unpersistedRecords(snapshot, pending)
    if (records.isEmpty()) return snapshot
    val categories = projectionCategories(snapshot)
    val accountDeltas = mutableMapOf<UUID, Money>()
    val summaries = snapshot.summaries.associateByTo(linkedMapOf()) { it.currency }
    val entries = mutableListOf<FinanceStatisticEntry>()
    for (record in records) {
        val effects = record.input.calculate()
        for (posting in effects.postings) {
            accountDeltas[posting.accountId] = accountDeltas[posting.accountId]?.checkedAdd(posting.amount) ?: posting.amount
        }
        val kind = when (record.input) {
            is RecordInput.Expense -> "EXPENSE"
            is RecordInput.Income -> "INCOME"
            is RecordInput.Transfer -> "TRANSFER"
        }
        for (statistic in effects.statistics) {
            if (statistic.amount.unscaled != 0L) {
                entries += FinanceStatisticEntry(
                    eventId = record.id,
                    recordId = record.id,
                    categoryId = contributionCategory(record, statistic.direction, categories)?.id,
                    direction = statistic.direction,
                    amount = statistic.amount,
                    occurredAtMillis = record.occurredAtMillis,
                    timezone = record.timezone,
                    remark = record.remark,
                    accountAmounts = AccountStatisticProjection.project(kind, record.input, statistic.direction, statistic.amount),
                )
            }
        }
        if (range != null && !range.contains(record.occurredAtMillis)) continue
        for (statistic in effects.statistics) {
            val summary = summaries[statistic.amount.currency] ?: emptySummary(statistic.amount.currency)
            summaries[summary.currency] = when (statistic.direction) {
                Direction.INCOME -> summary.copy(income = summary.income.checkedAdd(statistic.amount))
                Direction.EXPENSE -> summary.copy(expense = summary.expense.checkedAdd(statistic.amount))
            }
        }
        if (!budgetExcluded(record, categories)) {
            for (amount in effects.budget) {
                val summary = summaries[amount.currency] ?: emptySummary(amount.currency)
                summaries[summary.currency] = summary.copy(budgetExpense = summary.budgetExpense.checkedAdd(amount))
            }
        }
    }
    fun projectAccounts(accounts: List<FinanceAccount>): List<FinanceAccount> = accounts.map { account ->
        val delta = accountDeltas[account.id] ?: return@map account
        account.copy(balance = account.balance.checkedAdd(liabilityAwareStoredAmount(account.type, delta)))
    }
    val accounts = projectAccounts(snapshot.accounts)
    return snapshot.copy(
        records = mergePendingByTime(
            snapshot.records,
            records.map { it.copy(localRevision = 0, accountMutationSupported = false) }
                .sortedWith(compareByDescending<FinanceRecord> { it.occurredAtMillis }.thenBy { it.id.toString() }),
        ) { it.occurredAtMillis },
        accounts = accounts,
        allAccounts = if (snapshot.allAccounts === snapshot.accounts) accounts else projectAccounts(snapshot.allAccounts),
        summaries = summaries.values.sortedBy { it.currency },
        statisticEntries = mergePendingByTime(
            snapshot.statisticEntries,
            entries.sortedWith(
                compareByDescending<FinanceStatisticEntry> { it.occurredAtMillis }
                    .thenBy { it.eventId.toString() }
                    .thenBy { it.direction.ordinal }
                    .thenBy { it.categoryId?.toString().orEmpty() }
                    .thenBy { it.amount.currency },
            ),
        ) { it.occurredAtMillis },
    )
}

/**
 * Apply against the durable [snapshot], independently of the records overlay, to avoid counting a save twice.
 * Canonical contributions use the record's stored local date; guest contributions use statistics-zone instants.
 */
internal fun projectPendingBudget(
    budget: FinanceBudgetOverview,
    snapshot: FinanceSnapshot,
    pending: Collection<PendingRecordSave>,
    statisticsZone: String = TimeZone.getDefault().id,
    useRecordTimezone: Boolean = false,
): FinanceBudgetOverview {
    val records = unpersistedRecords(snapshot, pending)
    if (records.isEmpty()) return budget
    val start = LocalDate.parse(budget.periodStart)
    val end = LocalDate.parse(budget.periodEnd)
    val range = if (useRecordTimezone) null else {
        val zone = ZoneId.of(statisticsZone)
        PeriodRange(start.atStartOfDay(zone).toInstant().toEpochMilli(), end.atStartOfDay(zone).toInstant().toEpochMilli())
    }
    val categories = projectionCategories(snapshot)
    var used = budget.used
    val otherCurrencies = budget.otherCurrencies.toMutableSet()
    for (record in records) {
        val inPeriod = range?.contains(record.occurredAtMillis) ?: run {
            val date = Instant.ofEpochMilli(record.occurredAtMillis).atZone(ZoneId.of(record.timezone)).toLocalDate()
            date >= start && date < end
        }
        if (!inPeriod || budgetExcluded(record, categories)) continue
        for (amount in record.input.calculate().budget) {
            if (amount.currency == budget.currency) used = used.checkedAdd(amount) else otherCurrencies += amount.currency
        }
    }
    // Category amount maps are configured spending limits, not accumulated usage.
    return budget.copy(used = used, otherCurrencies = otherCurrencies.sorted())
}

private fun unpersistedRecords(
    snapshot: FinanceSnapshot,
    pending: Collection<PendingRecordSave>,
): List<FinanceRecord> {
    val candidates = linkedMapOf<UUID, FinanceRecord>()
    for (save in pending) {
        if (save.ownerId == snapshot.ownerId && save.epochId == snapshot.epochId && save.ledgerId == snapshot.ledgerId) {
            candidates.putIfAbsent(save.record.id, save.record)
        }
    }
    if (candidates.isEmpty()) return emptyList()
    for (record in snapshot.records) {
        candidates.remove(record.id)
        if (candidates.isEmpty()) return emptyList()
    }
    return candidates.values.toList()
}

/** Sort additions only; retain the repository's ordering of every durable row, including timestamp ties. */
private inline fun <T> mergePendingByTime(base: List<T>, additions: List<T>, time: (T) -> Long): List<T> {
    if (additions.isEmpty()) return base
    if (base.isEmpty()) return additions
    val merged = ArrayList<T>(base.size + additions.size)
    var next = 0
    for (row in base) {
        val timestamp = time(row)
        while (next < additions.size && time(additions[next]) > timestamp) {
            merged.add(additions[next++])
        }
        merged.add(row)
    }
    while (next < additions.size) merged.add(additions[next++])
    return merged
}

private fun projectionCategories(snapshot: FinanceSnapshot): Map<UUID, FinanceCategory> =
    (snapshot.allCategories.filter { it.ledgerId == snapshot.ledgerId } + snapshot.categories).associateBy { it.id }

private fun contributionCategory(
    record: FinanceRecord,
    direction: Direction,
    categories: Map<UUID, FinanceCategory>,
): FinanceCategory? = if (record.input is RecordInput.Transfer) {
    val purpose = if (direction == Direction.EXPENSE) "TRANSFER_FEE" else "TRANSFER_BONUS"
    categories.values.firstOrNull { it.purpose == purpose }
} else {
    categories[record.categoryId]
}

private fun budgetExcluded(record: FinanceRecord, categories: Map<UUID, FinanceCategory>): Boolean {
    val category = contributionCategory(record, Direction.EXPENSE, categories) ?: return false
    return category.excludeBudget || categories[category.parentId]?.excludeBudget == true
}

private fun emptySummary(currency: String): FinanceSummary {
    val zero = Money(0, 0, currency)
    return FinanceSummary(currency, zero, zero, zero)
}
