package dev.icos.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.UUID

class RecordTest {
    private val first = UUID(0, 1)
    private val second = UUID(0, 2)
    private val third = UUID(0, 3)

    private fun cny(
        unscaled: Long,
        scale: Int = 2,
    ) = Money(unscaled, scale, "CNY")

    @Test
    fun fullDiscountAllowsZeroContributionsWithoutInventingAPosting() {
        assertEquals(
            RecordEffects(statistics = listOf(StatContribution(Direction.EXPENSE, cny(0))), budget = listOf(cny(0))),
            RecordInput.Expense(cny(100), cny(100), emptyList(), true, true).calculate(),
        )
        assertEquals(
            "INVALID_MONEY",
            assertThrows(MoneyException::class.java) {
                RecordInput.Expense(cny(100), cny(100), listOf(AccountAmount(first, cny(0))), true, true).calculate()
            }.code,
        )
    }

    @Test
    fun explicitCrossCurrencyExpenseUsesAccountPostingAndRecordCurrencyTotal() {
        val usdAccount = Money(450, 2, "USD")
        val recordAmounts = listOf(cny(3000), cny(6000))
        val input = RecordInput.Expense(
            cny(10000), cny(1000),
            listOf(AccountAmount(first, cny(3000)), AccountAmount(third, usdAccount)),
            true, true, recordAmounts,
        )
        val effects = input.calculate()
        assertEquals(listOf(AccountAmount(first, cny(-3000)), AccountAmount(third, Money(-450, 2, "USD"))), effects.postings)
        assertEquals(listOf(StatContribution(Direction.EXPENSE, cny(9000))), effects.statistics)
        assertEquals(listOf(cny(9000)), effects.budget)
        assertThrows(RecordException::class.java) {
            input.copy(recordCurrencyAmounts = listOf(cny(3000), cny(5000))).calculate()
        }
        assertThrows(RecordException::class.java) {
            input.copy(recordCurrencyAmounts = listOf(cny(3000), cny(6000)), allocations = listOf(
                AccountAmount(first, cny(3000)), AccountAmount(third, Money(450, 2, "CNY")),
            )).calculate()
        }
    }

    @Test
    fun mixedScaleSplitChecksFinalSumWithoutChangingPostings() {
        val allocations = listOf(
            AccountAmount(first, cny(999_999_999_999_999_999, 0)),
            AccountAmount(second, cny(1, 1)),
            AccountAmount(third, cny(9, 1)),
        )
        val effects = RecordInput.Expense(cny(1_000_000_000_000_000_000, 0), null, allocations, true, false).calculate()
        assertEquals(listOf(AccountAmount(first, cny(-999_999_999_999_999_999, 0)), AccountAmount(second, cny(-1, 1)), AccountAmount(third, cny(-9, 1))), effects.postings)
        assertEquals(listOf(StatContribution(Direction.EXPENSE, cny(1_000_000_000_000_000_000, 0))), effects.statistics)
    }

    @Test
    fun allocationsAlignScalesAndKeepPostingOrderAndRepresentations() {
        val allocations = listOf(AccountAmount(first, cny(6, 1)), AccountAmount(second, cny(40)))
        val effects = RecordInput.Expense(cny(1, 0), null, allocations, true, false).calculate()
        assertEquals(listOf(AccountAmount(first, cny(-6, 1)), AccountAmount(second, cny(-40))), effects.postings)
        assertEquals(listOf(StatContribution(Direction.EXPENSE, cny(1, 0))), effects.statistics)
        assertEquals(emptyList<Money>(), effects.budget)
        assertEquals(6L, allocations.first().amount.unscaled)
        assertEquals(allocations, RecordInput.Income(cny(1, 0), allocations, false).calculate().postings)
    }

    @Test
    fun bonusUsesIndependentAccountAndCurrencyAfterFee() {
        val fee = AccountAmount(third, Money(2, 0, "USD"))
        val bonus = AccountAmount(third, Money(1, 0, "JPY"))
        assertEquals(
            RecordEffects(
                listOf(
                    AccountAmount(first, cny(-100)),
                    AccountAmount(second, cny(100)),
                    fee.copy(amount = fee.amount.checkedNegate()),
                    bonus,
                ),
                listOf(StatContribution(Direction.EXPENSE, fee.amount), StatContribution(Direction.INCOME, bonus.amount)),
                listOf(fee.amount),
            ),
            RecordInput.Transfer(AccountAmount(first, cny(100)), AccountAmount(second, cny(100)), fee, bonus).calculate(),
        )
    }

    @Test
    fun discountSubtractionChecksBoundedRepresentationBeforeEffects() {
        assertEquals(
            "AMOUNT_OVERFLOW",
            assertThrows(MoneyException::class.java) {
                RecordInput.Expense(cny(Long.MAX_VALUE, 0), cny(1, 6), emptyList(), true, true).calculate()
            }.code,
        )
        assertEquals(
            "INVALID_REQUEST",
            assertThrows(RecordException::class.java) {
                RecordInput.Income(cny(100), listOf(AccountAmount(first, Money(100, 2, "USD"))), true).calculate()
            }.code,
        )
    }
}
