package dev.icos.app.domain

import org.junit.Assert.*
import org.junit.Test

class FxValuationTest {
    @Test
    fun convertsExactBalancesWithPositiveDebtAndNeverChangesInputs() {
        val balances = listOf(ValuationBalance(Money(10000, 2, "CNY"), false), ValuationBalance(Money(100, 2, "USD"), false), ValuationBalance(Money(50, 2, "USD"), true))
        val result = FxValuation.calculate(balances, "CNY", listOf(ValuationQuote("USD", "CNY", "7.1234567", 1)))
        assertEquals(Money(107123457, 6, "CNY"), result.assets)
        assertEquals(Money(3561728, 6, "CNY"), result.liabilities)
        assertEquals(Money(103561729, 6, "CNY"), result.net)
        assertEquals(Money(50, 2, "USD"), balances.last().amount)
    }

    @Test
    fun missingRatesAreIncompleteAndOverflowCannotBecomeZero() {
        val missing = FxValuation.calculate(listOf(ValuationBalance(Money(1, 0, "USD"), false)), "CNY", emptyList())
        assertEquals(setOf("USD"), missing.missingCurrencies)
        assertNull(missing.net)
        val maximum = FxValuation.calculate(listOf(ValuationBalance(Money(Long.MAX_VALUE, 0, "CNY"), false)), "CNY", emptyList())
        assertEquals(Money(Long.MAX_VALUE, 0, "CNY"), maximum.assets)
        val overflow = FxValuation.calculate(listOf(ValuationBalance(Money(Long.MAX_VALUE, 0, "CNY"), false), ValuationBalance(Money(1, 0, "CNY"), false)), "CNY", emptyList())
        assertTrue(overflow.overflow)
        assertNull(overflow.assets)
        assertThrows(IllegalArgumentException::class.java) { ValuationQuote("USD", "CNY", "1e2", 1) }
        assertThrows(IllegalArgumentException::class.java) { ValuationQuote("USD", "CNY", "0", 1) }
    }
}
