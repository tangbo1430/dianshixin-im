package dev.icos.app.data.local

import dev.icos.app.domain.AccountAmount
import dev.icos.app.domain.Direction
import dev.icos.app.domain.Money
import dev.icos.app.domain.RecordInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

class AccountStatisticProjectionTest {
    private val a = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val b = UUID.fromString("00000000-0000-0000-0000-000000000002")

    @Test fun expenseSplitUsesExplicitRecordCurrencyAmounts() {
        val input =
            RecordInput.Expense(
                Money(100, 0, "USD"),
                null,
                listOf(AccountAmount(a, Money(9000, 2, "EUR")), AccountAmount(b, Money(2000, 2, "EUR"))),
                true,
                false,
                listOf(Money(60, 0, "USD"), Money(40, 0, "USD")),
            )
        assertEquals(
            mapOf(a to Money(60, 0, "USD"), b to Money(40, 0, "USD")),
            AccountStatisticProjection.project("EXPENSE", input, Direction.EXPENSE, Money(100, 0, "USD")),
        )
    }

    @Test fun transferFeeAndBonusResolveTheirActualAccounts() {
        val input =
            RecordInput.Transfer(
                AccountAmount(a, Money(100, 0, "USD")),
                AccountAmount(b, Money(100, 0, "USD")),
                AccountAmount(a, Money(5, 0, "USD")),
                AccountAmount(b, Money(7, 0, "USD")),
            )
        assertEquals(
            mapOf(a to Money(5, 0, "USD")),
            AccountStatisticProjection.project("TRANSFER", input, Direction.EXPENSE, Money(5, 0, "USD")),
        )
        assertEquals(
            mapOf(b to Money(7, 0, "USD")),
            AccountStatisticProjection.project("TRANSFER", input, Direction.INCOME, Money(7, 0, "USD")),
        )
        val sameAccount = input.copy(fee = AccountAmount(a, Money(5, 0, "USD")), bonus = AccountAmount(a, Money(7, 0, "USD")))
        assertEquals(
            mapOf(a to Money(7, 0, "USD")),
            AccountStatisticProjection.project("TRANSFER", sameAccount, Direction.INCOME, Money(7, 0, "USD")),
        )
    }

    @Test fun adjustmentsKeepTheOriginalNegativeStatisticAndDoNotGuessMissingAccounts() {
        val amount = Money(-30, 1, "CNY")
        assertEquals(mapOf(a to amount), AccountStatisticProjection.project("REFUND", null, Direction.EXPENSE, amount, a))
        assertNull(AccountStatisticProjection.project("CLAIM_SETTLEMENT", null, Direction.EXPENSE, amount))
        assertNull(AccountStatisticProjection.project("FUTURE_KIND", null, Direction.EXPENSE, amount, a))
    }

    @Test fun unknownFamilyAndHistoricalMismatchStayUnattributed() {
        val input = RecordInput.Expense(Money(10, 0, "USD"), null, listOf(AccountAmount(a, Money(10, 0, "USD"))), true, false)
        assertNull(AccountStatisticProjection.project("FUTURE_KIND", input, Direction.EXPENSE, Money(10, 0, "USD")))
        assertNull(AccountStatisticProjection.project("EXPENSE", input, Direction.EXPENSE, Money(9, 0, "USD")))
        assertNull(AccountStatisticProjection.project("EXPENSE", input, Direction.EXPENSE, Money(10, 0, "EUR")))
        assertNull(AccountStatisticProjection.project("EXPENSE", input, Direction.INCOME, Money(10, 0, "USD")))
        val overflow =
            input.copy(
                allocations = listOf(AccountAmount(a, Money(Long.MAX_VALUE, 0, "USD")), AccountAmount(b, Money(Long.MAX_VALUE, 0, "USD"))),
            )
        assertNull(AccountStatisticProjection.project("EXPENSE", overflow, Direction.EXPENSE, Money(10, 0, "USD")))
        val noAccount = RecordInput.Expense(Money(10, 0, "USD"), null, emptyList(), true, false)
        assertEquals(
            emptyMap<UUID, Money>(),
            AccountStatisticProjection.project("EXPENSE", noAccount, Direction.EXPENSE, Money(10, 0, "USD")),
        )
    }
}
