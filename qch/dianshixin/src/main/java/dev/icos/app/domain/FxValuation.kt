package dev.icos.app.domain

import java.math.BigDecimal
import java.math.RoundingMode

internal data class ValuationQuote(val sourceCurrency: String, val targetCurrency: String, val rate: String, val capturedAtMillis: Long) {
    init {
        Money(0, 0, sourceCurrency)
        Money(0, 0, targetCurrency)
        require(sourceCurrency != targetCurrency && capturedAtMillis > 0)
        require(rate.length in 1..32 && rate.matches(Regex("[0-9]+(?:\\.[0-9]{1,18})?")))
        require(BigDecimal(rate) > BigDecimal.ZERO && BigDecimal(rate) <= BigDecimal("1000000000000"))
    }
}

internal data class ValuationBalance(val amount: Money, val liability: Boolean)
internal data class BaseCurrencyValuation(val assets: Money?, val liabilities: Money?, val net: Money?, val missingCurrencies: Set<String>, val overflow: Boolean)

internal object FxValuation {
    fun calculate(balances: List<ValuationBalance>, base: String, quotes: List<ValuationQuote>): BaseCurrencyValuation {
        Money(0, 0, base)
        require(quotes.size <= 32 && quotes.map { it.sourceCurrency to it.targetCurrency }.distinct().size == quotes.size)
        val rates = quotes.filter { it.targetCurrency == base }.associateBy { it.sourceCurrency }
        val missing = balances.filter { it.amount.unscaled != 0L && it.amount.currency != base && it.amount.currency !in rates }
            .map { it.amount.currency }.toSortedSet()
        if (missing.isNotEmpty()) return BaseCurrencyValuation(null, null, null, missing, false)
        return try {
            fun total(liability: Boolean): Money {
                val value = balances.filter { it.liability == liability }.fold(BigDecimal.ZERO) { sum, balance ->
                    val original = BigDecimal.valueOf(balance.amount.unscaled, balance.amount.scale)
                    val converted = if (balance.amount.currency == base || balance.amount.unscaled == 0L) original
                        else original.multiply(BigDecimal(checkNotNull(rates[balance.amount.currency]).rate))
                    sum.add(converted)
                }.setScale(6, RoundingMode.HALF_UP).stripTrailingZeros()
                val normalized = value.setScale(value.scale().coerceAtLeast(0))
                return Money(normalized.unscaledValue().longValueExact(), normalized.scale(), base)
            }
            val assets = total(false)
            val liabilities = total(true)
            BaseCurrencyValuation(assets, liabilities, assets.checkedSubtract(liabilities), emptySet(), false)
        } catch (_: ArithmeticException) {
            BaseCurrencyValuation(null, null, null, emptySet(), true)
        } catch (_: MoneyException) {
            BaseCurrencyValuation(null, null, null, emptySet(), true)
        }
    }
}
