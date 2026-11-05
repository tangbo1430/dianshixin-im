package dev.icos.app.ui

import dev.icos.app.data.local.FinanceAccount
import dev.icos.app.data.local.FinanceBudgetOverview
import dev.icos.app.data.local.FinanceCategory
import dev.icos.app.data.local.FinanceRecord
import dev.icos.app.data.local.FinanceSnapshot
import dev.icos.app.data.local.FinanceStatisticEntry
import dev.icos.app.data.local.FinanceSummary
import dev.icos.app.domain.AccountAmount
import dev.icos.app.domain.Direction
import dev.icos.app.domain.Money
import dev.icos.app.domain.PeriodRange
import dev.icos.app.domain.RecordInput
import dev.icos.app.domain.ReportPeriod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class PendingRecordProjectionTest {
    private val owner = uuid(1)
    private val epoch = uuid(2)
    private val ledger = uuid(3)
    private val asset = uuid(10)
    private val liability = uuid(11)
    private val expenseCategory = uuid(20)
    private val incomeCategory = uuid(21)
    private val feeCategory = uuid(22)
    private val bonusCategory = uuid(23)

    @Test
    fun discountedSplitExpenseProjectsBalancesSummariesAndExactAccountStatistics() {
        val base = snapshot().copy(summaries = listOf(summary("200", "20", "15")))
        val record = record(
            RecordInput.Expense(
                money("100"), money("10"),
                listOf(AccountAmount(asset, money("60")), AccountAmount(liability, money("30"))),
                countInStatistics = true, countInBudget = true,
            ),
        ).copy(localRevision = 42, tagIds = listOf(uuid(30)), remark = "split dinner")
        val projected = projectPendingRecords(base, listOf(pending(record)))

        assertMoney("40", projected.accounts.single { it.id == asset }.balance)
        assertMoney("80", projected.accounts.single { it.id == liability }.balance)
        assertEquals(base.accounts.map { it.localRevision }, projected.accounts.map { it.localRevision })
        assertEquals(base.accounts.map { it.balance }, base.allAccounts.map { it.balance })
        assertMoney("100", base.accounts.first().balance)
        val totals = projected.summaries.single()
        assertMoney("200", totals.income)
        assertMoney("110", totals.expense)
        assertMoney("105", totals.budgetExpense)
        assertMoney("90", totals.balance)
        val entry = projected.statisticEntries.single()
        assertEquals(record.id, entry.eventId)
        assertEquals(record.id, entry.recordId)
        assertEquals(expenseCategory, entry.categoryId)
        assertEquals("split dinner", entry.remark)
        assertMoney("90", entry.amount)
        assertMoney("60", entry.accountAmounts!!.getValue(asset))
        assertMoney("30", entry.accountAmounts!!.getValue(liability))
        val visible = projected.records.single()
        assertEquals(0L, visible.localRevision)
        assertFalse(visible.accountMutationSupported)
        assertEquals(record.tagIds, visible.tagIds)
        assertEquals(record.id, visible.id)
        assertEquals(record.input, visible.input)
        assertMoney("90", projectPendingBudget(budget(), base, listOf(pending(record)), "UTC").used)
    }

    @Test
    fun incomeKeepsSixDecimalPrecisionAndReducesLiabilityDebt() {
        val input = RecordInput.Income(
            money("12.345678"),
            listOf(AccountAmount(asset, money("2.345678")), AccountAmount(liability, money("10"))),
            countInStatistics = true,
        )
        val base = snapshot()
        val saves = listOf(pending(record(input, category = incomeCategory)))
        val projected = projectPendingRecords(base, saves)

        assertMoney("102.345678", projected.accounts.single { it.id == asset }.balance)
        assertMoney("40", projected.accounts.single { it.id == liability }.balance)
        assertMoney("12.345678", projected.summaries.single().income)
        assertMoney("0", projected.summaries.single().expense)
        val entry = projected.statisticEntries.single()
        assertEquals(Direction.INCOME, entry.direction)
        assertEquals(incomeCategory, entry.categoryId)
        assertMoney("2.345678", entry.accountAmounts!!.getValue(asset))
        assertMoney("10", entry.accountAmounts!!.getValue(liability))
        assertMoney("0", projectPendingBudget(budget(), base, saves, "UTC").used)
    }

    @Test
    fun transferProjectsPrincipalFeeBonusAndRenamedPurposeCategories() {
        val base = snapshot(accounts = listOf(account(asset, "1000"), account(liability, "80", type = "LIABILITY")))
        val input = RecordInput.Transfer(
            AccountAmount(asset, money("100")), AccountAmount(liability, money("100")),
            AccountAmount(asset, money("2.50")), AccountAmount(liability, money("0.75")),
        )
        val saves = listOf(pending(record(input, category = null)))
        val projected = projectPendingRecords(base, saves)

        assertMoney("897.50", projected.accounts.single { it.id == asset }.balance)
        assertMoney("-20.75", projected.accounts.single { it.id == liability }.balance)
        val totals = projected.summaries.single()
        assertMoney("2.50", totals.expense)
        assertMoney("0.75", totals.income)
        assertMoney("2.50", totals.budgetExpense)
        val fee = projected.statisticEntries.single { it.direction == Direction.EXPENSE }
        val bonus = projected.statisticEntries.single { it.direction == Direction.INCOME }
        assertEquals(feeCategory, fee.categoryId)
        assertEquals(bonusCategory, bonus.categoryId)
        assertEquals(setOf(asset), fee.accountAmounts!!.keys)
        assertMoney("2.50", fee.accountAmounts!!.getValue(asset))
        assertEquals(setOf(liability), bonus.accountAmounts!!.keys)
        assertMoney("0.75", bonus.accountAmounts!!.getValue(liability))
        assertMoney("2.50", projectPendingBudget(budget(), base, saves, "UTC").used)
    }

    @Test
    fun crossCurrencyExpenseUsesAccountCurrencyForBalancesAndRecordCurrencyForReports() {
        val base = snapshot(accounts = listOf(account(asset, "500"), account(liability, "50", currency = "USD")))
        val input = RecordInput.Expense(
            gross = money("40", "USD"), discount = money("5", "USD"),
            allocations = listOf(AccountAmount(asset, money("175")), AccountAmount(liability, money("10", "USD"))),
            countInStatistics = true, countInBudget = true,
            recordCurrencyAmounts = listOf(money("25", "USD"), money("10", "USD")),
        )
        val saves = listOf(pending(record(input)))
        val projected = projectPendingRecords(base, saves)

        assertMoney("325", projected.accounts.single { it.id == asset }.balance)
        assertMoney("40", projected.accounts.single { it.id == liability }.balance, "USD")
        assertEquals(listOf("USD"), projected.summaries.map { it.currency })
        assertMoney("35", projected.summaries.single().expense, "USD")
        assertMoney("35", projected.summaries.single().budgetExpense, "USD")
        val attribution = projected.statisticEntries.single().accountAmounts!!
        assertMoney("25", attribution.getValue(asset), "USD")
        assertMoney("10", attribution.getValue(liability), "USD")
        val projectedBudget = projectPendingBudget(budget(), base, saves, "UTC")
        assertMoney("0", projectedBudget.used)
        assertEquals(listOf("USD"), projectedBudget.otherCurrencies)
    }

    @Test
    fun crossCurrencyTransferKeepsFeeAndBonusInTheirOwnCurrencies() {
        val base = snapshot(accounts = listOf(account(asset, "100", "USD"), account(liability, "100")))
        val input = RecordInput.Transfer(
            AccountAmount(asset, money("10", "USD")), AccountAmount(liability, money("70")),
            AccountAmount(asset, money("1", "USD")), AccountAmount(liability, money("2")),
        )
        val saves = listOf(pending(record(input, category = null)))
        val projected = projectPendingRecords(base, saves)

        assertMoney("89", projected.accounts.first().balance, "USD")
        assertMoney("172", projected.accounts.last().balance)
        assertEquals(listOf("CNY", "USD"), projected.summaries.map { it.currency })
        assertMoney("2", projected.summaries.first().income)
        assertMoney("0", projected.summaries.first().expense)
        assertMoney("1", projected.summaries.last().expense, "USD")
        assertMoney("1", projected.summaries.last().budgetExpense, "USD")
        val result = projectPendingBudget(budget(), base, saves, "UTC")
        assertMoney("0", result.used)
        assertEquals(listOf("USD"), result.otherCurrencies)
    }

    @Test
    fun statisticsAndBudgetTogglesAreIndependentAndNeverDisableAccountPostings() {
        for ((statistics, countsInBudget) in listOf(false to true, true to false, false to false)) {
            val base = snapshot()
            val saves = listOf(pending(record(expense("5", statistics, countsInBudget))))
            val projected = projectPendingRecords(base, saves)
            assertMoney("95", projected.accounts.first().balance)
            assertEquals(if (statistics) 1 else 0, projected.statisticEntries.size)
            if (statistics || countsInBudget) {
                assertMoney(if (statistics) "5" else "0", projected.summaries.single().expense)
                assertMoney(if (countsInBudget) "5" else "0", projected.summaries.single().budgetExpense)
            } else {
                assertTrue(projected.summaries.isEmpty())
            }
            assertMoney(if (countsInBudget) "5" else "0", projectPendingBudget(budget(), base, saves, "UTC").used)
        }
    }

    @Test
    fun parentAndDirectBudgetExclusionPreserveStatisticsIncludingArchivedParents() {
        val parent = FinanceCategory(uuid(25), "parent", Direction.EXPENSE, ledgerId = ledger, archivedAt = 1)
        for (excludeParent in listOf(false, true)) {
            val child = categories().first().copy(parentId = parent.id, excludeBudget = !excludeParent)
            val base = snapshot().copy(
                categories = listOf(child),
                allCategories = listOf(child, parent.copy(excludeBudget = excludeParent)),
            )
            val saves = listOf(pending(record(expense("5"))))
            val projected = projectPendingRecords(base, saves)
            assertMoney("5", projected.summaries.single().expense)
            assertMoney("0", projected.summaries.single().budgetExpense)
            assertMoney("5", projected.statisticEntries.single().amount)
            assertMoney("0", projectPendingBudget(budget(), base, saves, "UTC").used)
        }
    }

    @Test
    fun transferFeeHonorsItsPurposeCategoryParentBudgetExclusion() {
        val parent = FinanceCategory(uuid(25), "excluded parent", Direction.EXPENSE, excludeBudget = true, ledgerId = ledger)
        val base = snapshot().copy(
            categories = categories().map { if (it.id == feeCategory) it.copy(parentId = parent.id) else it } + parent,
        )
        val input = RecordInput.Transfer(
            AccountAmount(asset, money("10")), AccountAmount(liability, money("10")),
            AccountAmount(asset, money("2")), AccountAmount(asset, money("1")),
        )
        val saves = listOf(pending(record(input, category = null)))
        val projected = projectPendingRecords(base, saves)
        assertMoney("2", projected.summaries.single().expense)
        assertMoney("1", projected.summaries.single().income)
        assertMoney("0", projected.summaries.single().budgetExpense)
        assertMoney("0", projectPendingBudget(budget(), base, saves, "UTC").used)
    }

    @Test
    fun rangeFiltersOnlySummaryContributionsWithInclusiveStartAndExclusiveEnd() {
        val durableEntry = FinanceStatisticEntry(uuid(90), uuid(91), incomeCategory, Direction.INCOME, money("7"), 0, "UTC", "durable")
        val base = snapshot().copy(statisticEntries = listOf(durableEntry), summaries = listOf(summary("7", "0", "0")))
        val saves = listOf(999L, 1000L, 1999L, 2000L).mapIndexed { index, time ->
            pending(record(expense((index + 1).toString()), id = 100L + index, at = time))
        }
        val projected = projectPendingRecords(base, saves, PeriodRange(1000, 2000))

        assertEquals(4, projected.records.size)
        assertEquals(listOf(2000L, 1999L, 1000L, 999L), projected.records.map { it.occurredAtMillis })
        assertEquals(5, projected.statisticEntries.size)
        assertTrue(projected.statisticEntries.contains(durableEntry))
        assertMoney("90", projected.accounts.first().balance)
        assertMoney("7", projected.summaries.single().income)
        assertMoney("5", projected.summaries.single().expense)
        assertMoney("5", projected.summaries.single().budgetExpense)
    }

    @Test
    fun bothAccountListsProjectHiddenAndVisibleBalancesWithoutChangingVisibilityOrRevisions() {
        val visible = account(asset, "100")
        val hidden = account(liability, "50", type = "LIABILITY").copy(visibilityMode = "SELECTED", visibleLedgerIds = setOf(uuid(9)))
        val base = snapshot(accounts = listOf(visible)).copy(allAccounts = listOf(visible, hidden))
        val saves = listOf(pending(record(RecordInput.Transfer(
            AccountAmount(asset, money("10")), AccountAmount(liability, money("10")), null, null,
        ), category = null)))
        val projected = projectPendingRecords(base, saves)

        assertEquals(listOf(asset), projected.accounts.map { it.id })
        assertMoney("90", projected.accounts.single().balance)
        assertEquals(projected.accounts.single(), projected.allAccounts.first())
        assertMoney("40", projected.allAccounts.last().balance)
        assertEquals(hidden.copy(balance = money("40")), projected.allAccounts.last())
        assertTrue(projected.statisticEntries.isEmpty())
        assertTrue(projected.summaries.isEmpty())
    }

    @Test
    fun durableIdsAndRepeatedPendingIdsAreCountedOnceAndOverlayIsIdempotent() {
        val durable = record(expense("10"), id = 90).copy(localRevision = 1)
        val newRecord = record(expense("5"))
        val base = snapshot(accounts = listOf(account(asset, "90"))).copy(
            records = listOf(durable), summaries = listOf(summary("0", "10", "10")),
        )
        val saves = listOf(pending(durable), pending(newRecord), pending(newRecord))
        val projected = projectPendingRecords(base, saves)
        assertEquals(2, projected.records.size)
        assertMoney("85", projected.accounts.single().balance)
        assertMoney("15", projected.summaries.single().expense)
        assertMoney("15", projected.summaries.single().budgetExpense)
        assertEquals(projected, projectPendingRecords(projected, saves))
        assertMoney("15", projectPendingBudget(budget().copy(used = money("10")), base, saves, "UTC").used)
        val committed = base.copy(records = listOf(durable, newRecord.copy(localRevision = 1)))
        assertEquals(committed, projectPendingRecords(committed, saves))
        val committedBudget = budget().copy(used = money("15"))
        assertEquals(committedBudget, projectPendingBudget(committedBudget, committed, saves, "UTC"))
    }

    @Test
    fun ownerEpochAndLedgerIsolationAppliesBeforeDeduplication() {
        val base = snapshot()
        val save = pending(record(expense("5")))
        val foreign = listOf(save.copy(ownerId = uuid(4)), save.copy(epochId = uuid(5)), save.copy(ledgerId = uuid(6)))
        assertEquals(base, projectPendingRecords(base, foreign))
        val budget = budget()
        assertEquals(budget, projectPendingBudget(budget, base, foreign, "UTC"))
        val projected = projectPendingRecords(base, foreign + save)
        assertEquals(1, projected.records.size)
        assertMoney("95", projected.accounts.first().balance)
        assertMoney("5", projectPendingBudget(budget, base, foreign + save, "UTC").used)
    }

    @Test
    fun budgetUsesStatisticsZoneCalendarBoundariesAcrossDstAndPreservesConfiguredLimits() {
        val start = Instant.parse("2026-03-08T08:00:00Z").toEpochMilli()
        val end = Instant.parse("2026-03-15T07:00:00Z").toEpochMilli()
        val base = snapshot()
        val saves = listOf(start - 1, start, end - 1, end).mapIndexed { index, time ->
            pending(record(expense((index + 1).toString()), id = 100L + index, at = time))
        }
        val configured = budget().copy(
            period = ReportPeriod.WEEK, periodStart = "2026-03-08", periodEnd = "2026-03-15",
            enabled = false, used = money("7"), defaultAmount = money("50"), overrideAmount = money("20"),
            categoryAmounts = mapOf(expenseCategory to money("8")),
            defaultCategoryAmounts = mapOf(expenseCategory to money("10")),
            overrideCategoryAmounts = mapOf(expenseCategory to money("8")),
        )
        val projected = projectPendingBudget(configured, base, saves, "America/Los_Angeles")
        assertMoney("12", projected.used)
        assertEquals(configured.copy(used = money("12")), projected)
        assertMoney("20", projected.limit!!)
        assertMoney("8", projected.amounts!!.remaining)
    }

    @Test
    fun budgetKeepsForeignCurrenciesSortedDistinctAndIgnoresOutOfPeriodOrExcludedExpenses() {
        val base = snapshot().copy(categories = categories() + FinanceCategory(uuid(24), "excluded", Direction.EXPENSE, excludeBudget = true))
        val saves = listOf(
            pending(record(expense("3", currency = "USD"), id = 100)),
            pending(record(expense("4", currency = "USD"), id = 101)),
            pending(record(expense("5", currency = "EUR"), id = 102)),
            pending(record(expense("6", currency = "GBP"), id = 103, category = uuid(24))),
            pending(record(expense("7", currency = "JPY"), id = 104, at = -1)),
        )
        val projected = projectPendingBudget(budget().copy(otherCurrencies = listOf("USD", "CAD")), base, saves, "UTC")
        assertMoney("0", projected.used)
        assertEquals(listOf("CAD", "EUR", "USD"), projected.otherCurrencies)
    }

    @Test
    fun canonicalBudgetUsesRecordLocalDatesWhenStatisticsTimezoneDiffers() {
        val base = snapshot()
        fun save(id: Long, amount: String, timestamp: String, timezone: String) = pending(
            record(expense(amount), id = id, at = Instant.parse(timestamp).toEpochMilli()).copy(timezone = timezone),
        )
        val saves = listOf(
            save(100, "2", "2026-01-01" + "T00:30:00Z", "America/Los_Angeles"),
            save(101, "3", "2026-02-01" + "T00:30:00Z", "America/Los_Angeles"),
            save(102, "5", "2025-12-31T16:00:00Z", "Asia/Tokyo"),
            save(103, "7", "2026-01-31T16:00:00Z", "Asia/Tokyo"),
        )
        val configured = budget().copy(periodStart = "2026-01-01", periodEnd = "2026-02-01")
        assertMoney("8", projectPendingBudget(configured, base, saves, "UTC", useRecordTimezone = true).used)
        assertMoney("9", projectPendingBudget(configured, base, saves, "UTC").used)
    }

    @Test
    fun fullDiscountHasNoPostingsOrVisibleStatisticAmount() {
        val base = snapshot()
        val saves = listOf(pending(record(RecordInput.Expense(
            money("5"), money("5"), emptyList(), countInStatistics = true, countInBudget = true,
        ))))
        val projected = projectPendingRecords(base, saves)
        assertEquals(base.accounts, projected.accounts)
        assertEquals(base.allAccounts, projected.allAccounts)
        assertEquals(1, projected.records.size)
        assertTrue(projected.statisticEntries.isEmpty())
        assertMoney("0", projected.summaries.single().expense)
        assertMoney("0", projected.summaries.single().budgetExpense)
        assertMoney("0", projectPendingBudget(budget(), base, saves, "UTC").used)
    }

    @Test
    fun pendingRowsMergeChronologicallyWhileDurableTimestampTiesKeepRepositoryOrder() {
        val durable = listOf(
            record(expense("1"), id = 300, at = 2000),
            record(expense("1"), id = 200, at = 2000),
            record(expense("1"), id = 400, at = 1000),
        ).map { it.copy(localRevision = 1) }
        val durableEntries = durable.map {
            FinanceStatisticEntry(it.id, it.id, expenseCategory, Direction.EXPENSE, money("1"), it.occurredAtMillis, "UTC", "")
        }
        val base = snapshot().copy(records = durable, statisticEntries = durableEntries)
        val saves = listOf(
            pending(record(expense("1"), id = 102, at = 500)),
            pending(record(expense("1"), id = 101, at = 1500)),
            pending(record(expense("1"), id = 500, at = 2500)),
            pending(record(expense("1"), id = 100, at = 2000)),
        )
        val projected = projectPendingRecords(base, saves)
        val expected = listOf(500L, 300L, 200L, 100L, 101L, 400L, 102L).map(::uuid)
        assertEquals(expected, projected.records.map { it.id })
        assertEquals(expected, projected.statisticEntries.map { it.recordId })
        assertEquals(durable, projected.records.filter { it.localRevision == 1L })
    }

    private fun uuid(value: Long): UUID = UUID(0, value)

    private fun money(value: String, currency: String = "CNY"): Money {
        val decimal = BigDecimal(value)
        return Money(decimal.unscaledValue().longValueExact(), decimal.scale(), currency)
    }

    private fun assertMoney(value: String, actual: Money, currency: String = "CNY") {
        assertTrue("Expected $value $currency, got $actual", money(value, currency).sameValue(actual))
    }

    private fun account(id: UUID, balance: String, currency: String = "CNY", type: String = "ASSET") = FinanceAccount(
        id, "account $id", currency, money(balance, currency), 7, null, true, true, type = type,
    )

    private fun categories() = listOf(
        FinanceCategory(expenseCategory, "expense", Direction.EXPENSE, ledgerId = ledger),
        FinanceCategory(incomeCategory, "income", Direction.INCOME, ledgerId = ledger),
        FinanceCategory(feeCategory, "renamed fee", Direction.EXPENSE, ledgerId = ledger, purpose = "TRANSFER_FEE"),
        FinanceCategory(bonusCategory, "renamed bonus", Direction.INCOME, ledgerId = ledger, purpose = "TRANSFER_BONUS"),
    )

    private fun snapshot(accounts: List<FinanceAccount> = listOf(account(asset, "100"), account(liability, "50", type = "LIABILITY"))) = FinanceSnapshot(
        owner, epoch, ledger, accounts, categories(), emptyList(), emptyList(),
    )

    private fun expense(
        value: String,
        statistics: Boolean = true,
        budget: Boolean = true,
        currency: String = "CNY",
    ) = RecordInput.Expense(
        money(value, currency), null,
        if (currency == "CNY") listOf(AccountAmount(asset, money(value))) else emptyList(),
        statistics, budget,
    )

    private fun record(
        input: RecordInput,
        id: Long = 100,
        at: Long = 1500,
        category: UUID? = expenseCategory,
    ) = FinanceRecord(uuid(id), input, at, "UTC", "", category, 0, accountMutationSupported = false)

    private fun pending(record: FinanceRecord) = PendingRecordSave(owner, epoch, ledger, record)

    private fun summary(income: String, expense: String, budget: String) =
        FinanceSummary("CNY", money(income), money(expense), money(budget))

    private fun budget() = FinanceBudgetOverview(
        period = ReportPeriod.MONTH,
        periodStart = "1970-01-01", periodEnd = "1970-02-01", currency = "CNY", enabled = true,
        preferenceId = null, preferenceRevision = 0, templateId = null, templateRevision = 0,
        defaultAmount = null, overrideId = null, overrideRevision = 0, overrideAmount = null,
        used = money("0"), remainingDays = 31, otherCurrencies = emptyList(),
    )
}
