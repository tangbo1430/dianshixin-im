package dev.icos.app.ui

import dev.icos.app.data.local.FinanceStatisticEntry
import dev.icos.app.domain.Direction
import dev.icos.app.domain.Money
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal
import java.util.UUID

class AnnualReportTest {
    @Test
    fun monthlyTotalsKeepCurrenciesSeparateAndApplyNegativeAdjustments() {
        val entries =
            listOf(
                entry("CNY", "100.50", Direction.EXPENSE, "2026-03-05T10:00:00Z"),
                entry("CNY", "40", Direction.INCOME, "2026-03-20T10:00:00Z"),
                entry("CNY", "-20.50", Direction.EXPENSE, "2026-03-21T10:00:00Z"),
                entry("USD", "7", Direction.EXPENSE, "2026-12-31T23:30:00Z"),
            )

        val totals = annualMonthTotals(entries, "UTC")

        assertEquals(listOf("CNY", "USD"), totals.keys.toList())
        val cny = totals.getValue("CNY")
        assertEquals(12, cny.size)
        assertEquals(BigDecimal("80.00"), cny[2].expense)
        assertEquals(BigDecimal("40"), cny[2].income)
        assertEquals(0, BigDecimal.ZERO.compareTo(cny[0].expense))
        assertEquals(BigDecimal("7"), totals.getValue("USD")[11].expense)
        assertEquals(0, BigDecimal.ZERO.compareTo(totals.getValue("USD")[0].expense))
    }

    @Test
    fun categorySharesGroupBeyondVisibleSlotsIntoOther() {
        val shares =
            (1..7).map { index ->
                AnnualCategoryShare("分类 $index", "CNY", BigDecimal(index * 10))
            } + AnnualCategoryShare("美元分类", "USD", BigDecimal(99))

        val cny = annualDonutShares(shares, "CNY", visible = 5)

        assertEquals(6, cny.size)
        assertEquals(listOf("分类 1", "分类 2", "分类 3", "分类 4", "分类 5", "其他分类"), cny.map { it.name })
        assertEquals(0, BigDecimal(130).compareTo(cny.last().amount))
        assertEquals(
            0,
            BigDecimal.ZERO.compareTo(annualDonutShares(shares, "EUR").fold(BigDecimal.ZERO) { sum, share -> sum.add(share.amount) }),
        )
    }

    private fun entry(
        currency: String,
        amount: String,
        direction: Direction,
        instant: String,
    ): FinanceStatisticEntry {
        val decimal = BigDecimal(amount)
        return FinanceStatisticEntry(
            eventId = UUID.randomUUID(),
            recordId = null,
            categoryId = null,
            direction = direction,
            amount = Money(decimal.unscaledValue().toLong(), decimal.scale(), currency),
            occurredAtMillis =
                java.time.Instant
                    .parse(instant)
                    .toEpochMilli(),
            timezone = "UTC",
            remark = "",
        )
    }
}
