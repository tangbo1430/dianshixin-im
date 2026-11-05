package dev.icos.app.ui

import dev.icos.app.data.local.FinanceCategory
import dev.icos.app.data.local.FinanceRecord
import dev.icos.app.data.local.FinanceStatisticEntry
import dev.icos.app.domain.AccountAmount
import dev.icos.app.domain.Direction
import dev.icos.app.domain.Money
import dev.icos.app.domain.RecordInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

class RecordSearchTest {
    private val accountId = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val record =
        FinanceRecord(
            id = UUID.fromString("00000000-0000-0000-0000-000000000002"),
            input =
                RecordInput.Expense(
                    gross = Money(1200, 2, "CNY"),
                    discount = Money(200, 2, "CNY"),
                    allocations = listOf(AccountAmount(accountId, Money(1000, 2, "CNY"))),
                    countInStatistics = true,
                    countInBudget = true,
                ),
            occurredAtMillis =
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT)
                    .apply {
                        timeZone = TimeZone.getTimeZone("UTC")
                    }.parse("2024-03-04T08:15:00Z")!!
                    .time,
            timezone = "Asia/Shanghai",
            remark = "午餐",
            categoryId = UUID.fromString("00000000-0000-0000-0000-000000000003"),
            localRevision = 1,
        )

    @Test
    fun searchesRemarkCategoryAccountDateAndNormalizedAmountsWithIntersection() {
        val accounts = mapOf(accountId to "现金账户")
        val category = "餐饮"
        assertTrue(recordMatchesSearch(record, "午餐 10", accounts, category))
        assertTrue(recordMatchesSearch(record, "12.0", accounts, category))
        assertTrue(recordMatchesSearch(record, "CNY 餐饮", accounts, category))
        assertTrue(recordMatchesSearch(record, "现金账户", accounts, category))
        assertTrue(recordMatchesSearch(record, "2024-03-04", accounts, category))
        assertTrue(recordMatchesSearch(record, "   ", accounts, category))
        assertFalse(recordMatchesSearch(record, "午餐 99", accounts, category))
    }

    @Test
    fun appliesAvailableFiltersAsAnIntersectionAndUsesInclusiveNetAmountRange() {
        val categoryId = record.categoryId.toString()
        val filters =
            RecordFilters(
                type = "EXPENSE",
                fromDate = "2024-03-04",
                throughDate = "2024-03-04",
                currency = "CNY",
                accountId = accountId.toString(),
                categoryId = categoryId,
                minimumAmount = "10",
                maximumAmount = "10.00",
            )
        assertTrue(recordMatchesFilters(record, filters))
        assertFalse(recordMatchesFilters(record, filters.copy(type = "INCOME")))
        assertFalse(recordMatchesFilters(record, filters.copy(accountId = UUID.randomUUID().toString())))
        assertFalse(recordMatchesFilters(record, filters.copy(categoryId = UUID.randomUUID().toString())))
        assertFalse(recordMatchesFilters(record, filters.copy(fromDate = "2024-03-05")))
        assertFalse(recordMatchesFilters(record, filters.copy(currency = "USD")))
        assertFalse(recordMatchesFilters(record, filters.copy(minimumAmount = "10.01")))
        assertTrue(recordFiltersError(filters.copy(currency = "", minimumAmount = "10")) != null)
        assertTrue(recordFiltersError(filters.copy(minimumAmount = "1e2")) != null)
    }

    @Test
    fun dateFiltersCanUseTheSameTimezoneAsTheLedgerSummary() {
        val filters = RecordFilters(fromDate = "2024-03-03", throughDate = "2024-03-03")
        assertFalse(recordMatchesFilters(record, filters))
        assertTrue(recordMatchesFilters(record, filters, "Pacific/Honolulu"))
    }

    @Test
    fun tagFiltersSupportAnyAndAllAndLeaveLegacyTaglessRecordsUnfilteredByDefault() {
        val first = UUID.fromString("00000000-0000-0000-0000-000000000010")
        val second = UUID.fromString("00000000-0000-0000-0000-000000000011")
        val tagged = record.copy(tagIds = listOf(first, second))
        val legacy = record.copy(tagIds = emptyList())
        assertTrue(recordMatchesFilters(legacy, RecordFilters()))
        assertTrue(recordMatchesFilters(tagged, RecordFilters(tagIds = setOf(first.toString(), UUID.randomUUID().toString()))))
        assertFalse(
            recordMatchesFilters(tagged, RecordFilters(tagIds = setOf(first.toString(), UUID.randomUUID().toString()), tagMatch = "ALL")),
        )
        assertTrue(recordMatchesFilters(tagged, RecordFilters(tagIds = setOf(first.toString(), second.toString()), tagMatch = "ALL")))
        assertEquals(1, RecordFilters(tagIds = setOf(first.toString()), tagMatch = "ALL").activeCount())
    }

    @Test
    fun ranksActualContributionsAndHandlesRefundOnlyPeriodsWithoutDividingByZero() {
        val expenseCategory = requireNotNull(record.categoryId)
        val feeCategory = UUID.randomUUID()
        val incomeCategory = UUID.randomUUID()

        fun entry(
            category: UUID,
            direction: Direction,
            amount: Money,
            source: UUID? = record.id,
        ) = FinanceStatisticEntry(
            UUID.randomUUID(),
            source,
            category,
            direction,
            amount,
            record.occurredAtMillis,
            record.timezone,
            record.remark,
        )
        val entries =
            listOf(
                entry(expenseCategory, Direction.EXPENSE, Money(100, 0, "CNY")),
                entry(expenseCategory, Direction.EXPENSE, Money(-60, 0, "CNY"), null),
                entry(feeCategory, Direction.EXPENSE, Money(10, 0, "CNY")),
                entry(incomeCategory, Direction.INCOME, Money(20, 0, "CNY"), null),
                entry(expenseCategory, Direction.EXPENSE, Money(999, 0, "USD")),
            )
        val categories =
            listOf(
                FinanceCategory(expenseCategory, "餐饮", Direction.EXPENSE),
                FinanceCategory(feeCategory, "手续费", Direction.EXPENSE),
                FinanceCategory(incomeCategory, "奖励", Direction.INCOME),
            )
        val statistics = categoryStatistics(entries, categories)
        val expenses = statistics.filter { it.direction == Direction.EXPENSE && it.currency == "CNY" }
        assertEquals(listOf("餐饮", "手续费"), expenses.map { it.name })
        assertEquals(BigDecimal("40"), expenses[0].amount)
        assertEquals(BigDecimal("80.0"), expenses[0].percent)
        assertEquals(entries.take(2), expenses[0].entries)
        assertEquals(BigDecimal("20.0"), expenses[1].percent)
        assertEquals(listOf("CNY", "CNY", "USD"), statistics.filter { it.direction == Direction.EXPENSE }.map { it.currency })
        assertEquals(BigDecimal("100.0"), statistics.single { it.direction == Direction.INCOME }.percent)
        val refundPeriod = categoryStatistics(listOf(entries[1]), categories).single()
        assertEquals(BigDecimal("-60"), refundPeriod.amount)
        assertEquals(null, refundPeriod.percent)
        val balancedPeriod = categoryStatistics(listOf(entries[1], entry(feeCategory, Direction.EXPENSE, Money(60, 0, "CNY"))), categories)
        assertTrue(balancedPeriod.all { it.percent == null })
        assertTrue(
            categoryStatistics(listOf(entries[0], entry(expenseCategory, Direction.EXPENSE, Money(-100, 0, "CNY"))), categories).isEmpty(),
        )
    }

    @Test
    fun sortsByTimeAndCurrencyAmountAndSumsOnlyStatisticsEligibleRecords() {
        val larger =
            record.copy(
                id = UUID.fromString("00000000-0000-0000-0000-000000000004"),
                input =
                    RecordInput.Expense(
                        gross = Money(2000, 2, "CNY"),
                        discount = null,
                        allocations = listOf(AccountAmount(accountId, Money(2000, 2, "CNY"))),
                        countInStatistics = true,
                        countInBudget = true,
                    ),
                occurredAtMillis = record.occurredAtMillis + 1000,
            )
        val usdAccountId = UUID.fromString("00000000-0000-0000-0000-000000000005")
        val usdIncome =
            record.copy(
                id = UUID.fromString("00000000-0000-0000-0000-000000000006"),
                input = RecordInput.Income(Money(1, 2, "USD"), listOf(AccountAmount(usdAccountId, Money(1, 2, "USD"))), true),
                occurredAtMillis = record.occurredAtMillis + 2000,
            )
        val candidates = listOf(record, larger, usdIncome)
        assertEquals(
            listOf(usdIncome.id, larger.id, record.id),
            sortSearchRecords(candidates, SORT_TIME_DESCENDING, "CNY").map { it.id },
        )
        assertEquals(
            listOf(record.id, larger.id, usdIncome.id),
            sortSearchRecords(candidates, SORT_AMOUNT_ASCENDING, "CNY").map { it.id },
        )
        assertEquals(
            listOf(usdIncome.id, larger.id, record.id),
            sortSearchRecords(candidates, SORT_AMOUNT_ASCENDING, "").map { it.id },
        )

        val excludedExpense =
            record.copy(
                id = UUID.fromString("00000000-0000-0000-0000-000000000007"),
                input =
                    RecordInput.Expense(
                        gross = Money(500, 2, "CNY"),
                        discount = null,
                        allocations = listOf(AccountAmount(accountId, Money(500, 2, "CNY"))),
                        countInStatistics = false,
                        countInBudget = false,
                    ),
            )
        val transferTargetId = UUID.fromString("00000000-0000-0000-0000-000000000009")
        val transfer =
            record.copy(
                id = UUID.fromString("00000000-0000-0000-0000-000000000008"),
                input =
                    RecordInput.Transfer(
                        AccountAmount(accountId, Money(100, 2, "CNY")),
                        AccountAmount(transferTargetId, Money(100, 2, "CNY")),
                        AccountAmount(accountId, Money(200, 2, "CNY")),
                        AccountAmount(usdAccountId, Money(300, 2, "USD")),
                    ),
            )
        val statistics = searchStatistics(listOf(record, larger, usdIncome, excludedExpense, transfer))
        assertEquals(5, statistics.count)
        assertEquals(BigDecimal("32.00"), statistics.expenses["CNY"])
        assertEquals(BigDecimal("3.01"), statistics.incomes["USD"])
        assertEquals("匹配 5 笔账单 · 支出 CNY 32 · 收入 USD 3.01", statistics.summaryText())
    }

    @Test
    fun timelineGroupsByStatisticsZoneAndKeepsCurrencyDailyTotalsSeparate() {
        val nextStatisticsDay =
            record.copy(
                id = UUID.fromString("00000000-0000-0000-0000-000000000020"),
                occurredAtMillis =
                    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT)
                        .apply { timeZone = TimeZone.getTimeZone("UTC") }
                        .parse("2024-03-04T16:30:00Z")!!
                        .time,
            )
        val rows =
            recordTimelineRows(
                sortSearchRecords(listOf(record, nextStatisticsDay), SORT_TIME_DESCENDING, ""),
                SORT_TIME_DESCENDING,
                "Asia/Shanghai",
                filtered = false,
            )
        val headers = rows.filterIsInstance<RecordTimelineRow.DayHeader>()
        assertEquals(listOf("2024-03-05", "2024-03-04"), headers.map { it.day })
        assertEquals(BigDecimal("10.00"), headers.last().statistics.expenses["CNY"])
        assertFalse(headers.last().filtered)
    }

    @Test
    fun dailyBillTotalsRespectFiltersExclusionsTransferContributionsAndAmountOrder() {
        val excluded =
            record.copy(
                id = UUID.fromString("00000000-0000-0000-0000-000000000021"),
                input =
                    RecordInput.Expense(
                        Money(700, 2, "CNY"),
                        null,
                        listOf(AccountAmount(accountId, Money(700, 2, "CNY"))),
                        countInStatistics = false,
                        countInBudget = false,
                    ),
            )
        val transfer =
            record.copy(
                id = UUID.fromString("00000000-0000-0000-0000-000000000022"),
                input =
                    RecordInput.Transfer(
                        AccountAmount(accountId, Money(5000, 2, "CNY")),
                        AccountAmount(UUID.fromString("00000000-0000-0000-0000-000000000023"), Money(5000, 2, "CNY")),
                        AccountAmount(accountId, Money(125, 2, "CNY")),
                        AccountAmount(UUID.fromString("00000000-0000-0000-0000-000000000024"), Money(25, 2, "USD")),
                    ),
            )
        val usdIncome =
            record.copy(
                id = UUID.fromString("00000000-0000-0000-0000-000000000025"),
                input = RecordInput.Income(Money(300, 2, "USD"), emptyList(), true),
            )
        val feeChild =
            record.copy(
                id = UUID.fromString("00000000-0000-0000-0000-000000000026"),
                input =
                    RecordInput.Expense(
                        Money(125, 2, "CNY"),
                        null,
                        listOf(AccountAmount(accountId, Money(125, 2, "CNY"))),
                        countInStatistics = true,
                        countInBudget = true,
                    ),
                parentRecordId = transfer.id,
            )
        val filteredRecords = listOf(record, excluded, transfer, feeChild, usdIncome).filter { it.id != usdIncome.id }
        val header =
            recordTimelineRows(filteredRecords, SORT_TIME_DESCENDING, "Asia/Shanghai", filtered = true)
                .filterIsInstance<RecordTimelineRow.DayHeader>()
                .single()
        assertEquals(BigDecimal("11.25"), header.statistics.expenses["CNY"])
        assertEquals(BigDecimal("0.25"), header.statistics.incomes["USD"])
        assertTrue(header.filtered)
        assertEquals(
            "支出 11.25 CNY · 收入 0.25 USD",
            header.statistics.dailySummaryText(),
        )
        val amountSorted = sortSearchRecords(listOf(record, transfer, excluded), SORT_AMOUNT_ASCENDING, "CNY")
        val amountOrdered = recordTimelineRows(amountSorted, SORT_AMOUNT_ASCENDING, "Asia/Shanghai", filtered = true)
        assertTrue(amountOrdered.all { it is RecordTimelineRow.Bill })
        assertEquals(amountSorted.map { it.id }, amountOrdered.map { (it as RecordTimelineRow.Bill).record.id })
        assertEquals(listOf(excluded.id, record.id, transfer.id), amountSorted.map { it.id })
    }
}
