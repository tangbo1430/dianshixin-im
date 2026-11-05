package dev.icos.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class MoneyTest {
    @Test
    fun exactDecimalArithmeticAndScaleAlignment() {
        val oneTenth = Money(1, 1, "CNY")
        assertEquals(Money(3, 1, "CNY"), oneTenth.checkedAdd(Money(2, 1, "CNY")))
        assertEquals(Money(9, 2, "CNY"), oneTenth.checkedSubtract(Money(1, 2, "CNY")))
        assertTrue(oneTenth.sameValue(Money(100_000, 6, "CNY")))
    }

    @Test
    fun rejectsOverflowButNotScaleAlignmentIntermediates() {
        val max = Money(Long.MAX_VALUE, 0, "CNY")
        assertEquals("AMOUNT_OVERFLOW", assertThrows(MoneyException::class.java) { max.checkedAdd(Money(1, 0, "CNY")) }.code)
        assertEquals("AMOUNT_OVERFLOW", assertThrows(MoneyException::class.java) { Money(Long.MIN_VALUE, 0, "CNY").checkedSubtract(Money(1, 0, "CNY")) }.code)
        assertEquals(max, max.checkedAdd(Money(0, 6, "CNY")))
        assertEquals(Money(0, 0, "CNY"), max.checkedSubtract(max))
        assertEquals("AMOUNT_OVERFLOW", assertThrows(MoneyException::class.java) { max.checkedAdd(Money(1, 6, "CNY")) }.code)
    }

    @Test
    fun negationAndOverflowNormalizationPreserveRustRepresentation() {
        assertEquals(Money(-123, 2, "CNY"), Money(123, 2, "CNY").checkedNegate())
        assertEquals("AMOUNT_OVERFLOW", assertThrows(MoneyException::class.java) { Money(Long.MIN_VALUE, 6, "CNY").checkedNegate() }.code)
        for (sign in listOf(-1L, 1L)) {
            assertEquals(
                Money(sign * 1_000_000_000_000_000_000L, 1, "CNY"),
                Money(sign * 100_000_000_000_000_000L, 0, "CNY").checkedAdd(Money(0, 3, "CNY")),
            )
        }
    }

    @Test
    fun wireValidationAndBusinessSignMatchTheBoundedContract() {
        for (value in listOf("1.5", "1e3", "+1", " 1", "-")) {
            assertEquals("INVALID_MONEY", assertThrows(MoneyException::class.java) { Money.fromWire(value, 2, "CNY") }.code)
        }
        assertEquals("AMOUNT_OVERFLOW", assertThrows(MoneyException::class.java) { Money.fromWire("9223372036854775808", 0, "CNY") }.code)
        assertEquals("INVALID_MONEY", assertThrows(MoneyException::class.java) { Money(1, 7, "CNY") }.code)
        assertEquals("INVALID_MONEY", assertThrows(MoneyException::class.java) { Money(1, 2, "XYZ") }.code)
        assertEquals("INVALID_MONEY", assertThrows(MoneyException::class.java) { Money(0, 2, "CNY").requirePositive() }.code)
        assertEquals("INVALID_MONEY", assertThrows(MoneyException::class.java) { Money(1, 2, "CNY").checkedAdd(Money(1, 2, "USD")) }.code)
        for (value in listOf(Long.MIN_VALUE, -1, 0, 1, Long.MAX_VALUE)) {
            assertEquals(Money(value, 6, "JPY"), Money.fromWire(value.toString(), 6, "JPY"))
        }
    }
}
