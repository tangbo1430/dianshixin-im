package dev.icos.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class CreditReminderPolicyTest {
    @Test
    fun statementDateClampsAndRepaymentRuleUsesTheSelectedCycle() {
        val february = CreditReminderPolicy.dates(
            today = LocalDate.of(2025, 2, 20),
            statementDay = 31,
            includeStatementDay = true,
            rule = "DAYS_AFTER_STATEMENT",
            value = 7,
        )
        assertEquals(LocalDate.of(2025, 2, 28), february?.statementDate)
        assertEquals(LocalDate.of(2025, 3, 7), february?.dueDate)

        val beforeStatement = CreditReminderPolicy.dates(
            today = LocalDate.of(2025, 2, 27),
            statementDay = 31,
            includeStatementDay = false,
            rule = "DAYS_AFTER_STATEMENT",
            value = 5,
        )
        assertEquals(LocalDate.of(2025, 2, 28), beforeStatement?.statementDate)
        assertEquals(LocalDate.of(2025, 3, 5), beforeStatement?.dueDate)
        val fixed = CreditReminderPolicy.dates(LocalDate.of(2025, 2, 1), 31, true, "FIXED_MONTH_DAY", 10)
        assertEquals(LocalDate.of(2025, 2, 28), fixed?.statementDate)
        assertEquals(LocalDate.of(2025, 3, 10), fixed?.dueDate)
        assertNull(CreditReminderPolicy.dates(LocalDate.of(2025, 2, 1), 31, true, "UNKNOWN", 5))
    }
}
