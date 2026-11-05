package dev.icos.app.domain

import dev.icos.app.data.local.FinanceValidationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class SavingEntryRemarkTest {
    @Test fun trimsAndMapsBlankToNull() {
        assertNull(normalizeNewSavingEntryRemark(null))
        assertNull(normalizeNewSavingEntryRemark(" \n\t "))
        assertEquals("午餐\n咖啡", normalizeNewSavingEntryRemark("  午餐\n咖啡 "))
    }

    @Test fun limitsByCodePointNotUtf16Unit() {
        assertEquals("a".repeat(1000), normalizeNewSavingEntryRemark("a".repeat(1000)))
        assertThrows(FinanceValidationException::class.java) { normalizeNewSavingEntryRemark("a".repeat(1001)) }
        assertEquals("😀".repeat(1000), normalizeNewSavingEntryRemark("😀".repeat(1000)))
        assertThrows(FinanceValidationException::class.java) { normalizeNewSavingEntryRemark("😀".repeat(1001)) }
    }

    @Test fun rejectsNulAndUnpairedSurrogate() {
        assertThrows(FinanceValidationException::class.java) { normalizeNewSavingEntryRemark("a\u0000b") }
        assertThrows(FinanceValidationException::class.java) { normalizeNewSavingEntryRemark(String(charArrayOf('\uD83D'))) }
    }
}
