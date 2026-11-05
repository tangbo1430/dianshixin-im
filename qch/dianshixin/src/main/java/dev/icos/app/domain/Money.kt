package dev.icos.app.domain

import java.math.BigDecimal
import java.math.BigInteger

class MoneyException(
    val code: String,
) : IllegalArgumentException(code)

data class Money(
    val unscaled: Long,
    val scale: Int,
    val currency: String,
) {
    init {
        if (scale !in 0..6 || currency !in supportedCurrencies) throw MoneyException("INVALID_MONEY")
    }

    fun requirePositive() {
        if (unscaled <= 0) throw MoneyException("INVALID_MONEY")
    }

    fun checkedAdd(other: Money): Money = combine(other, false)

    fun checkedSubtract(other: Money): Money = combine(other, true)

    fun checkedNegate(): Money {
        if (unscaled == Long.MIN_VALUE) throw MoneyException("AMOUNT_OVERFLOW")
        return copy(unscaled = -unscaled)
    }

    fun sameValue(other: Money): Boolean = currency == other.currency && decimal().compareTo(other.decimal()) == 0

    private fun decimal(): BigDecimal = BigDecimal(BigInteger.valueOf(unscaled), scale)

    private fun combine(
        other: Money,
        subtract: Boolean,
    ): Money {
        if (currency != other.currency) throw MoneyException("INVALID_MONEY")
        var value = if (subtract) decimal().subtract(other.decimal()) else decimal().add(other.decimal())
        while (value.unscaledValue().bitLength() > 63 && value.scale() > 0 &&
            value.unscaledValue().mod(BigInteger.TEN) == BigInteger.ZERO
        ) {
            value = value.setScale(value.scale() - 1)
        }
        try {
            return Money(value.unscaledValue().longValueExact(), value.scale(), currency)
        } catch (_: ArithmeticException) {
            throw MoneyException("AMOUNT_OVERFLOW")
        }
    }

    companion object {
        private val integer = Regex("-?[0-9]+")
        internal val supportedCurrencies: Set<String> =
            requireNotNull(Money::class.java.getResourceAsStream("/iso-4217-currencies.txt")) {
                "Shared currency registry missing"
            }.bufferedReader().use {
                it
                    .readText()
                    .trim()
                    .split(Regex("""\s+"""))
                    .toSet()
            }

        fun fromWire(
            unscaled: String,
            scale: Int,
            currency: String,
        ): Money {
            if (!integer.matches(unscaled)) throw MoneyException("INVALID_MONEY")
            return Money(unscaled.toLongOrNull() ?: throw MoneyException("AMOUNT_OVERFLOW"), scale, currency)
        }
    }
}
