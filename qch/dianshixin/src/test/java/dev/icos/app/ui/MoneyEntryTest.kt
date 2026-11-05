package dev.icos.app.ui

import dev.icos.app.data.local.FinanceValidationException
import dev.icos.app.domain.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MoneyEntryTest {
    @Test
    fun decimalEntryKeepsExactDigitsAndRejectsOverflowAndScientificNotation() {
        assertEquals(Money(1230, 2, "CNY"), moneyInput("12.30", "cny"))
        assertEquals(Money(-1230, 2, "CNY"), moneyInput("-12.30", "CNY", signed = true))
        assertEquals(Money(Long.MAX_VALUE, 0, "USD"), moneyInput(Long.MAX_VALUE.toString(), "USD"))
        assertThrows(FinanceValidationException::class.java) { moneyInput("-1", "CNY") }
        for (text in listOf("1e3", "0.0000001", "9223372036854775808")) {
            assertThrows(IllegalArgumentException::class.java) { moneyInput(text, "CNY") }
        }
    }

    @Test
    fun amountKeyboardHonorsPrecisionAndEditingOperationsWithoutRounding() {
        assertEquals("0.", amountKeyboardAppend("", ".", 2))
        assertEquals("12.3", amountKeyboardAppend("12.", "3", 2))
        assertEquals(null, amountKeyboardAppend("12.3", "4", 1))
        assertEquals(null, amountKeyboardAppend("12", ".", 0))
        assertEquals("12.30", amountKeyboardAppend("12.3", "0", 2))
        assertEquals("12.3", amountKeyboardDelete("12.30"))
        val overflowText = amountKeyboardAppend("922337203685477580", "8", 6)!!
        assertThrows(IllegalArgumentException::class.java) { moneyInput(overflowText, "CNY") }
        assertEquals(Money(1230, 2, "CNY"), moneyInput("12.30", "CNY"))
    }

    @Test
    fun numericAmountEditAcceptsPartialInputAndRejectsInvalidWholeFieldEdits() {
        assertEquals("", amountNumericEdit("", 2))
        assertEquals("0.", amountNumericEdit("0.", 2))
        assertEquals("12.30", amountNumericEdit("12.30", 2))
        assertEquals("1".repeat(32), amountNumericEdit("1".repeat(32), 6))
        for (value in listOf("12.345", "1.2.3", "1,", "-1", "1e3", "1".repeat(33))) {
            assertEquals(null, amountNumericEdit(value, 2))
        }
        assertEquals(null, amountNumericEdit("1.", 0))
    }

    @Test
    fun annualPercentUsesExactReducedIntegerRatio() {
        assertEquals(21L to 400L, annualPercentRatio("5.25"))
        assertEquals(12L to 1L, annualPercentRatio("1200"))
        assertEquals(1L to 800L, annualPercentRatio("0.125"))
        assertThrows(IllegalArgumentException::class.java) { annualPercentRatio("0") }
        assertThrows(IllegalArgumentException::class.java) { annualPercentRatio("1e2") }
    }

    @Test
    fun loanTermAndPaidPrefixesRespectCoreBounds() {
        assertEquals(1 to 0, loanTermCounts("1", "0"))
        assertEquals(1200 to 1200, loanTermCounts("1200", "1200"))
        for ((terms, paid) in listOf("0" to "0", "1201" to "0", "12" to "-1", "12" to "13")) {
            assertThrows(IllegalArgumentException::class.java) { loanTermCounts(terms, paid) }
        }
        assertEquals(true, canRecordDisbursement(0))
        assertEquals(false, canRecordDisbursement(1))
        assertEquals(true, isIsoDate("2026-10-18"))
        assertEquals(false, isIsoDate("2026-02-30"))
    }
}
