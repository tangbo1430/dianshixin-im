package dev.icos.app.ui

import dev.icos.app.data.local.FinanceInstallmentTerm
import dev.icos.app.domain.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.util.UUID

class InstallmentPostingTotalsTest {
    private fun term(
        planId: UUID,
        index: Int,
        principal: Money,
        interest: Money,
        state: String = "SCHEDULED",
    ) = FinanceInstallmentTerm(UUID.randomUUID(), planId, index, "2026-01-01", principal, interest, state, null)

    @Test
    fun mixedScaleAndCurrenciesStayExact() {
        val plan = UUID.randomUUID()
        val totals =
            installmentMoneyTotals(
                listOf(
                    term(plan, 1, Money(100050, 2, "CNY"), Money(1333, 2, "CNY")),
                    term(plan, 2, Money(100050555, 6, "CNY"), Money(5, 6, "CNY")),
                    term(plan, 3, Money(100, 0, "USD"), Money(1, 0, "USD")),
                ),
            ) { "TERM_BY_TERM" }.associateBy { it.currency }

        assertEquals(0, BigDecimal("1100.550555").compareTo(totals.getValue("CNY").principal))
        assertEquals(0, BigDecimal("13.330005").compareTo(totals.getValue("CNY").interest))
        assertEquals(0, BigDecimal("100").compareTo(totals.getValue("USD").principal))
        assertEquals(0, BigDecimal("1").compareTo(totals.getValue("USD").interest))
    }

    @Test
    fun totalsExceedLongWithoutOverflow() {
        val plan = UUID.randomUUID()
        val largest = Money(Long.MAX_VALUE, 0, "CNY")
        val totals =
            installmentMoneyTotals(listOf(term(plan, 1, largest, Money(0, 0, "CNY")), term(plan, 2, largest, Money(0, 0, "CNY")))) {
                "TERM_BY_TERM"
            }.single()

        assertEquals(0, BigDecimal.valueOf(Long.MAX_VALUE).multiply(BigDecimal.valueOf(2)).compareTo(totals.principal))
    }

    @Test
    fun principalFollowsPostingModeAndUnknownIsNotZero() {
        val upfront = UUID.randomUUID()
        val perTerm = UUID.randomUUID()
        val unknown = UUID.randomUUID()
        val posting = mapOf(upfront to "UPFRONT", perTerm to "TERM_BY_TERM", unknown to "FUTURE_MODE")

        fun totalOf(planId: UUID) =
            installmentMoneyTotals(listOf(term(planId, 1, Money(6000, 2, "CNY"), Money(60, 2, "CNY")))) { posting[it.planId] }.single()

        val upfrontTotal = totalOf(upfront)
        assertEquals(0, BigDecimal.ZERO.compareTo(upfrontTotal.principal))
        assertEquals(0, BigDecimal("0.60").compareTo(upfrontTotal.interest))
        assertFalse(upfrontTotal.principalUnconfirmed)

        assertEquals(0, BigDecimal("60.00").compareTo(totalOf(perTerm).principal))

        val unknownTotal = totalOf(unknown)
        assertTrue(unknownTotal.principalUnconfirmed)
        assertEquals(0, BigDecimal("0.60").compareTo(unknownTotal.interest))
    }
}
