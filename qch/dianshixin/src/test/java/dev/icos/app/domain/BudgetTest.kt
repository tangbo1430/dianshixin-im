package dev.icos.app.domain

import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class BudgetTest {
    @Test
    fun remainingPreservesOverspendWhileDailyNeverGoesNegativeOrDividesByZero() {
        val limit = Money(100, 0, "CNY")
        val available = budgetAmounts(limit, Money(10, 0, "CNY"), 7)
        assertTrue(available.remaining.sameValue(Money(90, 0, "CNY")))
        assertEquals(BigDecimal("12.857142"), available.dailyAllowance)
        val overspent = budgetAmounts(limit, Money(120, 0, "CNY"), 7)
        assertTrue(overspent.remaining.sameValue(Money(-20, 0, "CNY")))
        assertEquals(BigDecimal.ZERO, overspent.dailyAllowance)
        assertEquals(BigDecimal.ZERO, budgetAmounts(limit, Money(0, 0, "CNY"), 0).dailyAllowance)
        assertTrue(budgetAmounts(Money(0, 0, "CNY"), Money(1, 0, "CNY"), 1).remaining.unscaled < 0)
    }

    @Test
    fun exactMixedScalesAndLargeDailyAmountsDoNotRequireExpandingMoneyMantissas() {
        val amounts = budgetAmounts(Money(100, 0, "CNY"), Money(1, 2, "CNY"), 1)
        assertTrue(amounts.remaining.sameValue(Money(9999, 2, "CNY")))
        assertEquals(BigDecimal("99.99"), amounts.dailyAllowance)
        assertEquals(
            BigDecimal("3074457345618258602.333333"),
            budgetAmounts(Money(Long.MAX_VALUE, 0, "CNY"), Money(0, 0, "CNY"), 3).dailyAllowance,
        )
        assertThrows(MoneyException::class.java) { budgetAmounts(Money(1, 0, "CNY"), Money(1, 0, "USD"), 1) }
        assertThrows(IllegalArgumentException::class.java) { budgetAmounts(Money(-1, 0, "CNY"), Money(0, 0, "CNY"), 1) }
    }
}
