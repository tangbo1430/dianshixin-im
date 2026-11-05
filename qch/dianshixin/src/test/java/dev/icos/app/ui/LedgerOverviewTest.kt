package dev.icos.app.ui

import dev.icos.app.data.local.FinanceStatisticEntry
import dev.icos.app.domain.Direction
import dev.icos.app.domain.Money
import dev.icos.app.domain.PeriodRange
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal
import java.util.UUID

class LedgerOverviewTest {
    @Test
    fun monthUsesStatisticFactsKeepsCurrenciesSeparateAndIncludesRefundReductions() {
        val facts =
            listOf(
                entry(Direction.INCOME, Money(10000, 2, "CNY"), 1000),
                entry(Direction.EXPENSE, Money(2025, 2, "CNY"), 1500),
                entry(Direction.EXPENSE, Money(-525, 2, "CNY"), 1600),
                entry(Direction.INCOME, Money(350, 2, "USD"), 1999),
                entry(Direction.EXPENSE, Money(999, 0, "CNY"), 999),
                entry(Direction.INCOME, Money(999, 0, "CNY"), 2000),
            )
        val totals = ledgerMonthSummaries(facts, PeriodRange(1000, 2000), "CNY")
        assertEquals(listOf("CNY", "USD"), totals.map { it.currency })
        assertEquals(0, BigDecimal("100").compareTo(totals[0].income))
        assertEquals(0, BigDecimal("15").compareTo(totals[0].expense))
        assertEquals(0, BigDecimal("85").compareTo(totals[0].balance))
        assertEquals(0, BigDecimal("3.50").compareTo(totals[1].income))
        assertEquals(0, totals[1].expense.signum())
    }

    @Test
    fun emptyLedgerShowsZeroInItsOwnCurrency() {
        val totals = ledgerMonthSummaries(emptyList(), PeriodRange(1000, 2000), "JPY")
        assertEquals(listOf(LedgerMonthSummary("JPY", BigDecimal.ZERO, BigDecimal.ZERO)), totals)
    }

    @Test
    fun amountsKeepCurrencyPrecisionAndShowTheActualDirection() {
        assertEquals("−1,234.50 CNY", ledgerRecordAmount(Money(123450, 2, "CNY"), income = false))
        assertEquals("+20.00 CNY", ledgerRecordAmount(Money(-2000, 2, "CNY"), income = false))
        assertEquals("+1,000 JPY", ledgerRecordAmount(Money(1000, 0, "JPY"), income = true))
        assertEquals("1.234567", ledgerAmount(BigDecimal("1.234567"), "CNY"))
    }

    private fun entry(
        direction: Direction,
        amount: Money,
        time: Long,
    ) = FinanceStatisticEntry(
        eventId = UUID.randomUUID(),
        recordId = null,
        categoryId = null,
        direction = direction,
        amount = amount,
        occurredAtMillis = time,
        timezone = "UTC",
        remark = "",
    )
}
