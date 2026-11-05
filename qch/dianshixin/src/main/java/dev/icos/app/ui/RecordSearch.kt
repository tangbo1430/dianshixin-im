package dev.icos.app.ui

import dev.icos.app.data.local.FinanceCategory
import dev.icos.app.data.local.FinanceRecord
import dev.icos.app.data.local.FinanceStatisticEntry
import dev.icos.app.domain.Direction
import dev.icos.app.domain.Money
import dev.icos.app.domain.RecordInput
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

internal fun recordMatchesSearch(
    record: FinanceRecord,
    query: String,
    accountNames: Map<UUID, String>,
    categoryName: String?,
    tagNames: List<String> = emptyList(),
): Boolean {
    val terms =
        query
            .trim()
            .split(Regex("\\s+"))
            .filter(String::isNotEmpty)
            .map(::normalizeSearch)
    if (terms.isEmpty()) return true
    val searchable =
        buildString {
            append(record.remark)
            append(' ')
            append(searchDate(record.occurredAtMillis, record.timezone))
            append(' ')
            append(categoryName.orEmpty())
            tagNames.forEach {
                append(' ')
                append(it)
            }
            when (val input = record.input) {
                is RecordInput.Expense -> {
                    append(" 支出 expense ")
                    appendMoney(input.gross)
                    appendMoney(input.discount?.let(input.gross::checkedSubtract) ?: input.gross)
                    input.discount?.let(::appendMoney)
                    input.allocations.forEach { allocation ->
                        append(' ')
                        append(accountNames[allocation.accountId].orEmpty())
                        appendMoney(allocation.amount)
                    }
                    if (!input.countInStatistics) append(" 不计统计")
                    if (!input.countInBudget) append(" 不计预算")
                }

                is RecordInput.Income -> {
                    append(" 收入 income ")
                    appendMoney(input.amount)
                    input.allocations.forEach { allocation ->
                        append(' ')
                        append(accountNames[allocation.accountId].orEmpty())
                        appendMoney(allocation.amount)
                    }
                    if (!input.countInStatistics) append(" 不计统计")
                }

                is RecordInput.Transfer -> {
                    append(" 转账 transfer ")
                    listOfNotNull(input.from, input.to, input.fee, input.bonus).forEach { allocation ->
                        append(' ')
                        append(accountNames[allocation.accountId].orEmpty())
                        appendMoney(allocation.amount)
                    }
                    if (input.fee != null) append(" 手续费 fee ")
                    if (input.bonus != null) append(" 优惠 bonus ")
                }
            }
        }.let(::normalizeSearch)
    return terms.all(searchable::contains)
}

internal const val SORT_TIME_DESCENDING = "TIME_DESCENDING"
internal const val SORT_AMOUNT_ASCENDING = "AMOUNT_ASCENDING"

internal data class CategoryStatistic(
    val key: String,
    val categoryId: UUID?,
    val name: String,
    val direction: Direction,
    val currency: String,
    val amount: BigDecimal,
    val percent: BigDecimal?,
    val entries: List<FinanceStatisticEntry>,
)

internal fun categoryStatistics(
    entries: List<FinanceStatisticEntry>,
    categories: List<FinanceCategory>,
): List<CategoryStatistic> {
    data class Key(
        val categoryId: UUID?,
        val direction: Direction,
        val currency: String,
    )
    val names = categories.associate { it.id to it.name }
    val grouped = entries.groupBy { Key(it.categoryId, it.direction, it.amount.currency) }
    val amounts =
        grouped
            .mapValues { (_, values) -> values.sumOf { BigDecimal.valueOf(it.amount.unscaled, it.amount.scale) } }
            .filterValues { it.signum() != 0 }
    val totals =
        amounts.entries.groupBy { it.key.direction to it.key.currency }.mapValues { (_, values) ->
            if (values.all { it.value.signum() > 0 }) values.sumOf { it.value } else null
        }
    return amounts
        .map { (key, amount) ->
            CategoryStatistic(
                key = "${key.direction}:${key.currency}:${key.categoryId ?: "uncategorized"}",
                categoryId = key.categoryId,
                name = names[key.categoryId] ?: "未分类",
                direction = key.direction,
                currency = key.currency,
                amount = amount,
                percent =
                    totals.getValue(key.direction to key.currency)?.let {
                        amount.multiply(BigDecimal(100)).divide(it, 1, RoundingMode.HALF_UP)
                    },
                entries = grouped.getValue(key),
            )
        }.sortedWith(
            compareBy<CategoryStatistic> { it.direction.ordinal }
                .thenBy { it.currency }
                .thenByDescending { it.amount }
                .thenBy { it.name }
                .thenBy { it.key },
        )
}

internal data class SearchStatistics(
    val count: Int,
    val expenses: Map<String, BigDecimal>,
    val incomes: Map<String, BigDecimal>,
)

internal sealed interface RecordTimelineRow {
    val key: String

    data class DayHeader(
        val day: String,
        val statistics: SearchStatistics,
        val filtered: Boolean,
    ) : RecordTimelineRow {
        override val key: String = "day:$day"
    }

    data class Bill(
        val record: FinanceRecord,
    ) : RecordTimelineRow {
        override val key: String = "record:${record.id}"
    }
}

internal fun recordTimelineRows(
    records: List<FinanceRecord>,
    sort: String,
    statisticsZone: String,
    filtered: Boolean,
): List<RecordTimelineRow> {
    if (sort == SORT_AMOUNT_ASCENDING) return records.map { RecordTimelineRow.Bill(it) }
    val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone(statisticsZone) }
    val groups = records.groupBy { dayFormat.format(Date(it.occurredAtMillis)) }
    val rows = mutableListOf<RecordTimelineRow>()
    var previousDay: String? = null
    records.forEach { record ->
        val day = dayFormat.format(Date(record.occurredAtMillis))
        if (day != previousDay) {
            rows += RecordTimelineRow.DayHeader(day, searchStatistics(groups.getValue(day)), filtered)
            previousDay = day
        }
        rows += RecordTimelineRow.Bill(record)
    }
    return rows
}

internal fun SearchStatistics.dailySummaryText(): String {
    val currencies = (expenses.keys + incomes.keys).toSortedSet()
    if (currencies.isEmpty()) return "无收支贡献"
    return currencies.joinToString(" · ") { currency ->
        val expense = expenses[currency] ?: BigDecimal.ZERO
        val income = incomes[currency] ?: BigDecimal.ZERO
        buildList {
            if (income.signum() != 0) add("收入 ${ledgerAmount(income, currency)}")
            if (expense.signum() != 0) add("支出 ${ledgerAmount(expense, currency)}")
        }.joinToString(" · ").ifBlank { "收支 0" } + " $currency"
    }
}

internal fun searchStatistics(records: List<FinanceRecord>): SearchStatistics {
    val expenses = sortedMapOf<String, BigDecimal>()
    val incomes = sortedMapOf<String, BigDecimal>()
    val recordsById = records.associateBy { it.id }
    records.forEach { record ->
        if (isCanonicalTransferChildAlreadyCounted(record, recordsById)) return@forEach
        when (val input = record.input) {
            is RecordInput.Expense -> {
                if (input.countInStatistics) {
                    addSearchAmount(expenses, input.discount?.let(input.gross::checkedSubtract) ?: input.gross)
                }
            }

            is RecordInput.Income -> {
                if (input.countInStatistics) addSearchAmount(incomes, input.amount)
            }

            is RecordInput.Transfer -> {
                input.fee?.let { addSearchAmount(expenses, it.amount) }
                input.bonus?.let { addSearchAmount(incomes, it.amount) }
            }
        }
    }
    return SearchStatistics(records.size, expenses, incomes)
}

private fun isCanonicalTransferChildAlreadyCounted(
    record: FinanceRecord,
    recordsById: Map<UUID, FinanceRecord>,
): Boolean {
    val parentId = record.parentRecordId ?: return false
    val parent = recordsById[parentId] ?: return false
    val childAmount =
        when (val child = record.input) {
            is RecordInput.Expense -> {
                if (child.countInStatistics && child.discount == null && child.allocations.size == 1 &&
                    child.allocations.single().amount == child.gross
                ) {
                    child.gross
                } else {
                    return false
                }
            }

            is RecordInput.Income -> {
                if (child.countInStatistics && child.allocations.size == 1 &&
                    child.allocations.single().amount == child.amount
                ) {
                    child.amount
                } else {
                    return false
                }
            }

            is RecordInput.Transfer -> {
                return false
            }
        }
    val parentInput = parent.input as? RecordInput.Transfer ?: return false
    return when (record.input) {
        is RecordInput.Expense -> parentInput.fee?.amount == childAmount
        is RecordInput.Income -> parentInput.bonus?.amount == childAmount
        is RecordInput.Transfer -> false
    }
}

internal fun SearchStatistics.summaryText(): String =
    buildList {
        add("匹配 $count 笔账单")
        expenses.forEach { (currency, amount) -> add("支出 $currency ${amount.stripTrailingZeros().toPlainString()}") }
        incomes.forEach { (currency, amount) -> add("收入 $currency ${amount.stripTrailingZeros().toPlainString()}") }
    }.joinToString(" · ")

internal fun sortSearchRecords(
    records: List<FinanceRecord>,
    sort: String,
    currency: String,
): List<FinanceRecord> {
    if (sort != SORT_AMOUNT_ASCENDING || currency.isEmpty()) return records.sortedWith(::compareNewest)
    return records.sortedWith { first, second ->
        val firstAmount = primaryAmount(first, currency)
        val secondAmount = primaryAmount(second, currency)
        when {
            firstAmount == null && secondAmount != null -> 1
            secondAmount == null && firstAmount != null -> -1
            firstAmount == null -> compareNewest(first, second)
            else -> firstAmount.compareTo(secondAmount).takeIf { it != 0 } ?: compareNewest(first, second)
        }
    }
}

private fun addSearchAmount(
    totals: MutableMap<String, BigDecimal>,
    amount: Money,
) {
    val value = BigDecimal.valueOf(amount.unscaled, amount.scale)
    totals[amount.currency] = (totals[amount.currency] ?: BigDecimal.ZERO).add(value)
}

private fun primaryAmount(
    record: FinanceRecord,
    currency: String,
): BigDecimal? {
    val amount =
        when (val input = record.input) {
            is RecordInput.Expense -> {
                input.discount?.let(input.gross::checkedSubtract) ?: input.gross
            }

            is RecordInput.Income -> {
                input.amount
            }

            is RecordInput.Transfer -> {
                if (input.from.amount.currency == currency) {
                    input.from.amount
                } else {
                    input.to.amount.takeIf { it.currency == currency }
                }
            }
        } ?: return null
    return amount.takeIf { it.currency == currency }?.let { BigDecimal.valueOf(it.unscaled, it.scale) }
}

private fun compareNewest(
    first: FinanceRecord,
    second: FinanceRecord,
): Int =
    second.occurredAtMillis.compareTo(first.occurredAtMillis).takeIf { it != 0 }
        ?: first.id.toString().compareTo(second.id.toString())

internal data class RecordFilters(
    val type: String = "",
    val fromDate: String = "",
    val throughDate: String = "",
    val currency: String = "",
    val accountId: String = "",
    val categoryId: String = "",
    val minimumAmount: String = "",
    val maximumAmount: String = "",
    val tagIds: Set<String> = emptySet(),
    val tagMatch: String = "ANY",
)

internal fun RecordFilters.activeCount(): Int =
    listOf(type, fromDate, throughDate, currency, accountId, categoryId, minimumAmount, maximumAmount).count { it.isNotEmpty() } +
        if (tagIds.isEmpty()) 0 else 1

internal fun recordFiltersError(filters: RecordFilters): String? {
    if (filters.fromDate.isNotEmpty() && filters.throughDate.isNotEmpty() && filters.fromDate > filters.throughDate) {
        return "开始日期不能晚于结束日期"
    }
    if (filters.minimumAmount.isNotEmpty() && parseFilterAmount(filters.minimumAmount) == null) return "最低金额格式无效"
    if (filters.maximumAmount.isNotEmpty() && parseFilterAmount(filters.maximumAmount) == null) return "最高金额格式无效"
    val minimum = filters.minimumAmount.takeIf(String::isNotEmpty)?.let(::parseFilterAmount)
    val maximum = filters.maximumAmount.takeIf(String::isNotEmpty)?.let(::parseFilterAmount)
    if (minimum != null && maximum != null && minimum > maximum) return "最低金额不能大于最高金额"
    if ((minimum != null || maximum != null) && filters.currency.isEmpty()) return "请先选择金额币种"
    return null
}

internal fun recordCurrencies(record: FinanceRecord): List<String> = recordAmounts(record).map { it.currency }.distinct()

internal fun recordMatchesFilters(
    record: FinanceRecord,
    filters: RecordFilters,
    dateZone: String = record.timezone,
): Boolean {
    if (recordFiltersError(filters) != null) return false
    val type =
        when (record.input) {
            is RecordInput.Expense -> "EXPENSE"
            is RecordInput.Income -> "INCOME"
            is RecordInput.Transfer -> "TRANSFER"
        }
    if (filters.type.isNotEmpty() && filters.type != type) return false
    if (filters.categoryId.isNotEmpty() && filters.categoryId != record.categoryId?.toString()) return false
    if (filters.tagIds.isNotEmpty()) {
        val selected = record.tagIds.map(UUID::toString).toSet()
        if (when (filters.tagMatch) {
                "ALL" -> !selected.containsAll(filters.tagIds)
                else -> filters.tagIds.none(selected::contains)
            }
        ) {
            return false
        }
    }
    if (filters.accountId.isNotEmpty() && recordAccountIds(record).none { it.toString() == filters.accountId }) return false

    val day = searchLocalDate(record.occurredAtMillis, dateZone)
    if (filters.fromDate.isNotEmpty() && day < filters.fromDate) return false
    if (filters.throughDate.isNotEmpty() && day > filters.throughDate) return false

    val amounts = recordAmounts(record)
    if (filters.currency.isNotEmpty() && amounts.none { it.currency == filters.currency }) return false
    if (filters.minimumAmount.isEmpty() && filters.maximumAmount.isEmpty()) return true
    val minimum = filters.minimumAmount.takeIf(String::isNotEmpty)?.let(::parseFilterAmount)
    val maximum = filters.maximumAmount.takeIf(String::isNotEmpty)?.let(::parseFilterAmount)
    return amounts
        .filter { it.currency == filters.currency }
        .any { amount ->
            val value = BigDecimal.valueOf(amount.unscaled, amount.scale)
            (minimum == null || value >= minimum) && (maximum == null || value <= maximum)
        }
}

private fun recordAccountIds(record: FinanceRecord) =
    when (val input = record.input) {
        is RecordInput.Expense -> input.allocations.map { it.accountId }
        is RecordInput.Income -> input.allocations.map { it.accountId }
        is RecordInput.Transfer -> listOfNotNull(input.from, input.to, input.fee, input.bonus).map { it.accountId }
    }

private fun recordAmounts(record: FinanceRecord): List<Money> =
    when (val input = record.input) {
        is RecordInput.Expense -> listOf(input.discount?.let(input.gross::checkedSubtract) ?: input.gross)
        is RecordInput.Income -> listOf(input.amount)
        is RecordInput.Transfer -> listOfNotNull(input.from.amount, input.to.amount, input.fee?.amount, input.bonus?.amount)
    }

private val filterAmountPattern = Regex("(?:0|[1-9]\\d*)(?:\\.\\d{1,6})?")

private fun parseFilterAmount(value: String): BigDecimal? = value.takeIf(filterAmountPattern::matches)?.toBigDecimalOrNull()

private fun searchLocalDate(
    value: Long,
    zone: String,
): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
        .apply { timeZone = TimeZone.getTimeZone(zone) }
        .format(Date(value))

private fun StringBuilder.appendMoney(money: Money) {
    val value = BigDecimal.valueOf(money.unscaled, money.scale)
    append(' ')
    append(value.toPlainString())
    append(' ')
    append(value.stripTrailingZeros().toPlainString())
    append(' ')
    append(money.currency)
}

private fun searchDate(
    value: Long,
    zone: String,
): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT)
        .apply {
            timeZone = TimeZone.getTimeZone(zone)
        }.format(Date(value))

private fun normalizeSearch(value: String): String = value.lowercase(Locale.ROOT).replace(",", "").filterNot(Char::isWhitespace)
