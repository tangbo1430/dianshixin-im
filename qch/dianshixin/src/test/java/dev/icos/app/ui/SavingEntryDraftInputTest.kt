package dev.icos.app.ui

import dev.icos.app.data.local.FinanceAccount
import dev.icos.app.data.local.FinanceValidationException
import dev.icos.app.domain.AccountAmount
import dev.icos.app.domain.Money
import dev.icos.app.domain.RecordInput
import dev.icos.app.domain.SavingEntryKind
import dev.icos.app.domain.SavingInterval
import dev.icos.app.domain.SavingIntervalUnit
import dev.icos.app.domain.SavingKind
import dev.icos.app.domain.SavingPlanInput
import dev.icos.app.domain.SavingPlanSummary
import dev.icos.app.domain.SavingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class SavingEntryDraftInputTest {
    private val zero = Money(0, 0, "CNY")
    private val flexible = SavingPlanInput(SavingKind.FLEXIBLE, "灵活", null, Money(10, 0, "CNY"), zero, allowManualEntries = true)
    private val fixed =
        flexible.copy(
            kind = SavingKind.FIXED,
            target = Money(25, 1, "CNY"),
            firstAmount = Money(1, 0, "CNY"),
            interval = SavingInterval(SavingIntervalUnit.DAY, 1),
            startDate = "2026-10-20",
            allowManualEntries = false,
        )

    private fun draft(
        input: SavingPlanInput = flexible,
        withdrawal: Boolean = false,
    ) = SavingEntryDraft(SavingPlanSummary(UUID.randomUUID(), input, SavingState.ACTIVE, zero, input.target, 1), withdrawal)

    @Test
    fun invalidMoneyAndCardTextAreRejectedWithoutChangingRawFields() {
        val entry = draft(fixed)
        assertThrows(FinanceValidationException::class.java) { entry.toValidatedInput(emptyList()) }
        entry.amount = "0"
        assertThrows(FinanceValidationException::class.java) { entry.toValidatedInput(emptyList()) }
        assertEquals("0", entry.amount)
        entry.amount = "0.0000001"
        assertThrows(FinanceValidationException::class.java) { entry.toValidatedInput(emptyList()) }
        entry.amount = "1"
        entry.cardIndex = "not-a-card"
        val invalid = assertThrows(FinanceValidationException::class.java) { entry.toValidatedInput(emptyList()) }
        assertEquals("卡片序号须为整数", invalid.message)
        assertEquals("not-a-card", entry.cardIndex)
        entry.cardIndex = "2147483648"
        assertThrows(FinanceValidationException::class.java) { entry.toValidatedInput(emptyList()) }
    }

    @Test
    fun cardsReuseExactRampFixedRemainderAndOverflowRules() {
        for ((kind, target, last) in listOf(
            Triple(SavingKind.WEEK_52, Money(1846, 3, "CNY"), Money(61, 3, "CNY")),
            Triple(SavingKind.DAY_365, Money(70080, 3, "CNY"), Money(374, 3, "CNY")),
        )) {
            val input =
                fixed.copy(
                    kind = kind,
                    target = target,
                    firstAmount = Money(10, 3, "CNY"),
                    increment = Money(1, 3, "CNY"),
                    interval = SavingInterval(if (kind == SavingKind.WEEK_52) SavingIntervalUnit.WEEK else SavingIntervalUnit.DAY, 1),
                )
            val count = savingCardCount(input)
            assertEquals(if (kind == SavingKind.WEEK_52) 52 else 365, count)
            assertTrue(savingCardAmount(input, count, count).sameValue(last))
            val entry =
                draft(input).apply {
                    cardIndex = count.toString()
                    amount = moneyText(last)
                }
            assertTrue(entry.toValidatedInput(emptyList()).amount.sameValue(last))
            entry.amount = "1"
            assertThrows(FinanceValidationException::class.java) { entry.toValidatedInput(emptyList()) }
        }
        val entry = draft(fixed).apply { amount = "0.5" }
        assertThrows(FinanceValidationException::class.java) { entry.toValidatedInput(emptyList()) }
        entry.cardIndex = "3"
        assertTrue(entry.toValidatedInput(emptyList()).amount.sameValue(Money(5, 1, "CNY")))
        entry.amount = "1"
        assertThrows(FinanceValidationException::class.java) { entry.toValidatedInput(emptyList()) }
        entry.amount = "0.5"
        for (card in listOf("0", "4")) {
            entry.cardIndex = card
            assertThrows(FinanceValidationException::class.java) { entry.toValidatedInput(emptyList()) }
        }
        val manual = draft(fixed.copy(allowManualEntries = true)).apply { amount = "0.5" }
        assertEquals(null, manual.toValidatedInput(emptyList()).cardIndex)
        assertThrows(ArithmeticException::class.java) {
            savingCardCount(fixed.copy(target = Money(3000, 0, "CNY"), firstAmount = Money(1, 6, "CNY")))
        }
    }

    @Test
    fun freeDepositsAndWithdrawalsKeepExistingUncappedAndDateContracts() {
        val deposit = draft().apply { amount = "20" }
        assertEquals(SavingEntryKind.DEPOSIT, deposit.toValidatedInput(emptyList()).kind)
        deposit.cardIndex = "1"
        assertThrows(FinanceValidationException::class.java) { deposit.toValidatedInput(emptyList()) }
        val withdrawal =
            draft(fixed, withdrawal = true).apply {
                amount = "50"
                occurredAtMillis = 0
            }
        val input = withdrawal.toValidatedInput(emptyList())
        assertEquals(SavingEntryKind.WITHDRAWAL, input.kind)
        assertEquals(Money(50, 0, "CNY"), input.amount)
        assertEquals(0L, input.occurredAtMillis)
        withdrawal.cardIndex = "1"
        assertThrows(FinanceValidationException::class.java) { withdrawal.toValidatedInput(emptyList()) }
    }

    @Test
    fun linkedTransferRequiresAvailableDistinctCurrencyMatchedAccountsAndNormalizesOnlyInput() {
        val from = FinanceAccount(UUID.randomUUID(), "转出", "CNY", zero, 1, null, true, true)
        val to = from.copy(id = UUID.randomUUID(), name = "转入")
        val entry =
            draft().apply {
                amount = "2"
                linkTransfer = true
                remark = "  memo  "
            }
        assertThrows(FinanceValidationException::class.java) { entry.toValidatedInput(listOf(from, to)) }
        entry.fromAccountId = from.id
        entry.toAccountId = from.id
        assertThrows(FinanceValidationException::class.java) { entry.toValidatedInput(listOf(from, to)) }
        entry.toAccountId = to.id
        val input = entry.toValidatedInput(listOf(from, to))
        assertEquals("memo", input.remark)
        assertEquals("  memo  ", entry.remark)
        assertEquals(
            RecordInput.Transfer(AccountAmount(from.id, Money(2, 0, "CNY")), AccountAmount(to.id, Money(2, 0, "CNY")), null, null),
            input.linkedTransfer!!.input,
        )
        assertThrows(FinanceValidationException::class.java) { entry.toValidatedInput(listOf(to)) }
        assertThrows(FinanceValidationException::class.java) { entry.toValidatedInput(listOf(from.copy(selectable = false), to)) }
        assertThrows(FinanceValidationException::class.java) { entry.toValidatedInput(listOf(from, to.copy(archivedAt = 1L))) }
        assertThrows(FinanceValidationException::class.java) {
            entry.toValidatedInput(listOf(from, to.copy(currency = "USD", balance = Money(0, 0, "USD"))))
        }
    }
}
