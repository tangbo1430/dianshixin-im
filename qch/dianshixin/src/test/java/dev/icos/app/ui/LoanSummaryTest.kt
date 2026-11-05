package dev.icos.app.ui

import dev.icos.app.data.local.FinanceLoanPlan
import dev.icos.app.domain.LoanCalculator
import dev.icos.app.domain.LoanPlanInput
import dev.icos.app.domain.LoanDueState
import dev.icos.app.domain.Money
import dev.icos.app.domain.RepaymentMethod
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

class LoanSummaryTest {
    private val terms = LoanCalculator.create(
        LoanPlanInput(
            accountId = UUID.randomUUID(),
            principal = Money(10000, 2, "CNY"),
            annualRateNumerator = 0,
            annualRateDenominator = 1,
            termCount = 3,
            repaymentType = RepaymentMethod.EQUAL_PRINCIPAL,
            firstDueDate = "2026-01-31",
        ),
    ).terms

    @Test
    fun freshLoanShowsItsFullScheduledPrincipal() {
        assertEquals(listOf("2026-01-31", "2026-02-28", "2026-03-31"), terms.map { it.dueDate })
        assertEquals(Money(10000, 2, "CNY"), remainingLoanPrincipal(plan(terms)))
    }

    @Test
    fun firstPaidTermIsExcludedFromRemainingPrincipal() {
        val paidTerms = listOf(terms.first().copy(dueState = LoanDueState.PAID)) + terms.drop(1)
        assertEquals(Money(6667, 2, "CNY"), remainingLoanPrincipal(plan(paidTerms)))
    }

    @Test
    fun fullyPaidLoanHasExactPlanScaleZero() {
        val paidTerms = terms.map { it.copy(dueState = LoanDueState.PAID) }
        assertEquals(Money(0, 2, "CNY"), remainingLoanPrincipal(plan(paidTerms)))
    }

    private fun plan(terms: List<dev.icos.app.domain.LoanTerm>) = FinanceLoanPlan(
        id = UUID.randomUUID(),
        accountId = UUID.randomUUID(),
        principal = Money(10000, 2, "CNY"),
        localRevision = 1,
        state = "ACTIVE",
        terms = terms,
    )
}
