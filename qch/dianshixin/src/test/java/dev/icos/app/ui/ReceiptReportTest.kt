package dev.icos.app.ui

import dev.icos.app.data.local.FinanceAccount
import dev.icos.app.data.local.FinanceRecord
import dev.icos.app.data.local.FinanceSnapshot
import dev.icos.app.domain.AccountAmount
import dev.icos.app.domain.Direction
import dev.icos.app.domain.Money
import dev.icos.app.domain.RecordInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

class ReceiptReportTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val owner = UUID.randomUUID()
    private val epoch = UUID.randomUUID()
    private val ledger = UUID.randomUUID()
    private val cash = account("现金")
    private val bank = account("储蓄卡")
    private val usdCash = account("美元现金", "USD")
    private val accounts = listOf(cash, bank, usdCash)

    @Test
    fun receiptTotalsKeepCurrenciesSeparateAndCountTransferFeeWithoutPrincipal() {
        val expense = record(expenseInput(money("100.00"), money("10.00"), cash.id, "90.00"), instant(2026, 5, 18, 10))
        val income = record(incomeInput(money("50.00"), cash.id), instant(2026, 5, 18, 11))
        val usdExpense = record(expenseInput(money("20.00", "USD"), null, usdCash.id, "20.00"), instant(2026, 5, 18, 12))
        val transfer =
            record(
                RecordInput.Transfer(
                    from = AccountAmount(cash.id, money("1000.00")),
                    to = AccountAmount(bank.id, money("500.00")),
                    fee = AccountAmount(cash.id, money("2.00")),
                    bonus = AccountAmount(cash.id, money("1.00")),
                ),
                instant(2026, 5, 18, 13),
            )
        val previousDay = record(expenseInput(money("7.00"), null, cash.id, "7.00"), instant(2026, 5, 17, 10))
        val snapshot = snapshotOf(listOf(expense, income, usdExpense, transfer, previousDay))

        val report = receiptReport(snapshot, expense, ReceiptScope.DAY, "本日", "", zone, ReceiptPaperStyle.PLAIN)

        assertEquals(4, report.recordCount)
        assertEquals(1, report.transferCount)
        assertEquals(2, report.totals.size)
        val cny = report.totals.single { it.currency == "CNY" }
        assertEquals(0, cny.expense.compareTo(BigDecimal("92.00")))
        assertEquals(0, cny.income.compareTo(BigDecimal("51.00")))
        assertEquals(2, cny.expenseCount)
        assertEquals(2, cny.incomeCount)
        val usd = report.totals.single { it.currency == "USD" }
        assertEquals(0, usd.expense.compareTo(BigDecimal("20.00")))
        assertEquals(0, usd.income.signum())
        assertEquals("不计收支", report.rows.single { it.label == "转账" }.amount)
    }

    @Test
    fun receiptDiscountUsesNetAmountAndIncomeStaysSeparateFromExpense() {
        val expense = record(expenseInput(money("0.30"), money("0.10"), cash.id, "0.20"), instant(2026, 5, 18, 10))
        val income = record(incomeInput(money("5.00"), cash.id), instant(2026, 5, 18, 11))
        val snapshot = snapshotOf(listOf(expense, income))

        val report = receiptReport(snapshot, expense, ReceiptScope.LEDGER, "", "", zone, ReceiptPaperStyle.GRID)

        val cny = report.totals.single()
        assertEquals(0, cny.expense.compareTo(BigDecimal("0.20")))
        assertEquals(0, cny.income.compareTo(BigDecimal("5.00")))
        assertEquals(1, cny.expenseCount)
        assertEquals(1, cny.incomeCount)
        assertEquals("-0.20 CNY", report.rows.single { it.direction == Direction.EXPENSE }.amount)
        assertEquals(ReceiptPaperStyle.GRID, report.style)
        assertTrue(report.rows.all { it.detail.startsWith("2026-05-18") })
        assertTrue(report.isCurrent(snapshot))
        assertFalse(report.isCurrent(snapshot.copy(ownerId = UUID.randomUUID())))
        assertFalse(report.isCurrent(snapshot.copy(epochId = UUID.randomUUID())))
        assertFalse(report.isCurrent(snapshot.copy(ledgerId = UUID.randomUUID())))
        assertFalse(report.isCurrent(snapshot.copy(records = emptyList())))

        val changed = expense.copy(input = expenseInput(money("0.90"), null, cash.id, "0.90"))
        val fresh =
            receiptReport(
                snapshot.copy(records = listOf(changed, income)),
                expense,
                ReceiptScope.RECORD,
                "",
                "",
                zone,
                ReceiptPaperStyle.PLAIN,
            )
        assertEquals("-0.90 CNY", fresh.rows.single().amount)

        val reversal = record(incomeInput(money("-5.00"), cash.id), income.occurredAtMillis)
        val balanced =
            receiptReport(snapshotOf(listOf(income, reversal)), income, ReceiptScope.LEDGER, "", "", zone, ReceiptPaperStyle.PLAIN)
        assertEquals(
            0,
            balanced.totals
                .single()
                .income
                .signum(),
        )
        assertEquals(2, balanced.totals.single().incomeCount)
        assertTrue(balanced.rows.any { it.amount == "-5.00 CNY" })
    }

    private fun snapshotOf(records: List<FinanceRecord>) =
        FinanceSnapshot(owner, epoch, ledger, accounts, emptyList(), records, emptyList())

    private fun account(
        name: String,
        currency: String = "CNY",
    ) = FinanceAccount(
        id = UUID.randomUUID(),
        name = name,
        currency = currency,
        balance = Money(0, 2, currency),
        localRevision = 1,
        archivedAt = null,
        includeInAssets = true,
        selectable = true,
    )

    private fun record(
        input: RecordInput,
        occurredAtMillis: Long,
    ) = FinanceRecord(
        id = UUID.randomUUID(),
        input = input,
        occurredAtMillis = occurredAtMillis,
        timezone = zone.id,
        remark = "",
        categoryId = null,
        localRevision = 1,
    )

    private fun expenseInput(
        gross: Money,
        discount: Money?,
        accountId: UUID,
        allocation: String,
    ) = RecordInput.Expense(
        gross = gross,
        discount = discount,
        allocations = listOf(AccountAmount(accountId, money(allocation, gross.currency))),
        countInStatistics = true,
        countInBudget = true,
    )

    private fun incomeInput(
        amount: Money,
        accountId: UUID,
    ) = RecordInput.Income(
        amount = amount,
        allocations = listOf(AccountAmount(accountId, amount)),
        countInStatistics = true,
    )

    private fun money(
        value: String,
        currency: String = "CNY",
    ): Money {
        val decimal = BigDecimal(value)
        return Money(decimal.unscaledValue().longValueExact(), decimal.scale(), currency)
    }

    private fun instant(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
    ): Long =
        LocalDate
            .of(year, month, day)
            .atTime(hour, 0)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
}
