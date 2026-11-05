package dev.icos.app.domain

import java.math.BigInteger

enum class RepaymentMethod { EQUAL_PAYMENT, EQUAL_PRINCIPAL, INTEREST_ONLY, FLAT_INTEREST }
enum class Remainder { HEAD, TAIL }
enum class InterestMode { SPREAD, FIRST }
data class PlanTerm(val principal: Money, val interest: Money)
data class LoanSchedule(val terms: List<PlanTerm>, val principalTotal: Money, val interestTotal: Money)
data class SavingProgress(val saved: Money, val remaining: Money)

object PlanCalculator {
    // shortcut: technical cap 1200 terms; raise when product examples or supported plans require longer schedules.
    private const val MAX_TERMS = 1200
    private val zero = BigInteger.ZERO
    private val one = BigInteger.ONE
    private data class Fraction(val n: BigInteger, val d: BigInteger) {
        operator fun plus(o: Fraction) = of(n * o.d + o.n * d, d * o.d)
        operator fun minus(o: Fraction) = of(n * o.d - o.n * d, d * o.d)
        operator fun times(o: Fraction) = of(n * o.n, d * o.d)
        operator fun div(o: Fraction) = of(n * o.d, d * o.n)
        fun pow(p: Int) = of(n.pow(p), d.pow(p))
        fun round(): BigInteger {
            val (q, r) = n.divideAndRemainder(d)
            return if (r.abs() * BigInteger.valueOf(2) >= d.abs()) q + if (n.signum() == d.signum()) one else one.negate() else q
        }
        companion object {
            fun of(n: BigInteger, d: BigInteger): Fraction {
                require(d.signum() != 0)
                val sign = if (d.signum() < 0) -1 else 1
                val nn = if (sign < 0) n.negate() else n
                val dd = d.abs()
                val gcd = nn.abs().gcd(dd)
                return Fraction(nn / gcd, dd / gcd)
            }
            fun integer(n: BigInteger) = of(n, one)
        }
    }

    private fun pow10(scale: Int) = BigInteger.TEN.pow(scale)
    private fun units(m: Money, scale: Int) = BigInteger.valueOf(m.unscaled) * pow10(scale - m.scale)
    private fun exactUnits(m: Money, scale: Int): BigInteger {
        if (scale >= m.scale) return units(m, scale)
        val (value, remainder) = BigInteger.valueOf(m.unscaled).divideAndRemainder(pow10(m.scale - scale))
        if (remainder != BigInteger.ZERO) throw MoneyException("INVALID_MONEY")
        return value
    }
    private fun fraction(m: Money) = Fraction.of(BigInteger.valueOf(m.unscaled), pow10(m.scale))
    private fun sameCurrency(a: Money, b: Money) { if (a.currency != b.currency) throw MoneyException("INVALID_MONEY") }
    private fun valid(count: Int, scale: Int) {
        if (count !in 1..MAX_TERMS) throw MoneyException("INVALID_REQUEST")
        if (scale !in 0..6) throw MoneyException("INVALID_MONEY")
    }
    private fun toMoney(raw: BigInteger, scale0: Int, currency: String): Money {
        var raw = raw
        var scale = scale0
        while (raw.bitLength() > 63 && scale > 0 && raw.remainder(BigInteger.TEN) == zero) {
            raw /= BigInteger.TEN
            scale--
        }
        return try { Money(raw.longValueExact(), scale, currency) } catch (_: ArithmeticException) {
            throw MoneyException("AMOUNT_OVERFLOW")
        }
    }
    private fun rounded(value: Fraction, scale: Int, currency: String) =
        toMoney((value * Fraction.integer(pow10(scale))).round(), scale, currency)

    fun loanSchedule(
        principal: Money, annualNumerator: Long, annualDenominator: Long,
        termCount: Int, method: RepaymentMethod, outputScale: Int,
    ): LoanSchedule {
        principal.requirePositive()
        valid(termCount, outputScale)
        if (annualNumerator < 0 || annualDenominator <= 0) throw MoneyException("INVALID_REQUEST")
        val principalUnits = exactUnits(principal, outputScale)
        val p = fraction(principal)
        val r = Fraction.of(BigInteger.valueOf(annualNumerator), BigInteger.valueOf(annualDenominator) * BigInteger.valueOf(12))
        val oneF = Fraction.integer(one)
        val payment = when {
            method != RepaymentMethod.EQUAL_PAYMENT -> Fraction.integer(zero)
            annualNumerator == 0L -> p / Fraction.integer(BigInteger.valueOf(termCount.toLong()))
            else -> {
                val growth = (oneF + r).pow(termCount)
                p * r * growth / (growth - oneF)
            }
        }
        val flatInterest = p * r
        val basePrincipal = p / Fraction.integer(BigInteger.valueOf(termCount.toLong()))
        var remaining = principalUnits
        val terms = ArrayList<PlanTerm>(termCount)
        var interestTotal = zero
        repeat(termCount) { index ->
            val last = index == termCount - 1
            val interest = when (method) {
                RepaymentMethod.INTEREST_ONLY, RepaymentMethod.FLAT_INTEREST -> rounded(flatInterest, outputScale, principal.currency)
                else -> rounded(Fraction.of(remaining, pow10(outputScale)) * r, outputScale, principal.currency)
            }
            val interestUnits = units(interest, outputScale)
            val principalUnits = if (last) remaining else {
                val raw = when (method) {
                    RepaymentMethod.EQUAL_PAYMENT -> units(rounded(payment, outputScale, principal.currency), outputScale) - interestUnits
                    RepaymentMethod.EQUAL_PRINCIPAL, RepaymentMethod.FLAT_INTEREST ->
                        units(rounded(basePrincipal, outputScale, principal.currency), outputScale)
                    RepaymentMethod.INTEREST_ONLY -> zero
                }
                raw.max(zero).min(remaining)
            }
            remaining -= principalUnits
            interestTotal += interestUnits
            terms += PlanTerm(toMoney(principalUnits, outputScale, principal.currency), interest)
        }
        if (remaining != zero || terms.fold(zero) { s, t -> s + units(t.principal, outputScale) } != principalUnits) {
            throw MoneyException("AMOUNT_OVERFLOW")
        }
        return LoanSchedule(terms, toMoney(principalUnits, outputScale, principal.currency), toMoney(interestTotal, outputScale, principal.currency))
    }

    fun installmentAllocation(
        principal: Money, totalInterest: Money, termCount: Int,
        remainder: Remainder, interestMode: InterestMode, outputScale: Int,
    ): List<PlanTerm> {
        principal.requirePositive()
        sameCurrency(principal, totalInterest)
        valid(termCount, outputScale)
        if (totalInterest.unscaled < 0L) {
            throw MoneyException("INVALID_MONEY")
        }
        val p = exactUnits(principal, outputScale)
        val interest = exactUnits(totalInterest, outputScale)
        val n = BigInteger.valueOf(termCount.toLong())
        val (pq, pr) = p.divideAndRemainder(n)
        val (iq, ir) = interest.divideAndRemainder(n)
        return (0 until termCount).map { index ->
            val residual = if (remainder == Remainder.HEAD) index == 0 else index == termCount - 1
            val pi = pq + if (residual) pr else zero
            val ii = if (interestMode == InterestMode.FIRST) {
                if (index == 0) interest else zero
            } else iq + if (residual) ir else zero
            PlanTerm(toMoney(pi, outputScale, principal.currency), toMoney(ii, outputScale, principal.currency))
        }
    }

    fun savingRamp(first: Money, increment: Money, count: Int): Money {
        first.requirePositive()
        sameCurrency(first, increment)
        if (increment.unscaled < 0L) throw MoneyException("INVALID_ALLOCATION")
        val scale = maxOf(first.scale, increment.scale)
        valid(count, scale)
        val n = BigInteger.valueOf(count.toLong())
        val total = n * units(first, scale) + units(increment, scale) * n * BigInteger.valueOf((count - 1).toLong()) / BigInteger.valueOf(2)
        return toMoney(total, scale, first.currency)
    }

    fun savingProgress(target: Money, initial: Money, deposits: Money, withdrawals: Money): SavingProgress {
        if (deposits.unscaled < 0L || withdrawals.unscaled < 0L) throw MoneyException("INVALID_ALLOCATION")
        sameCurrency(target, deposits)
        sameCurrency(target, withdrawals)
        return savingProgress(
            target, initial,
            listOfNotNull(deposits.takeIf { it.unscaled > 0 }),
            listOfNotNull(withdrawals.takeIf { it.unscaled > 0 }),
        )
    }

    fun savingProgress(
        target: Money,
        initial: Money,
        deposits: List<Money>,
        withdrawals: List<Money>,
    ): SavingProgress {
        target.requirePositive()
        sameCurrency(target, initial)
        if (initial.unscaled < 0L || deposits.any { it.unscaled <= 0L } || withdrawals.any { it.unscaled <= 0L }) {
            throw MoneyException("INVALID_ALLOCATION")
        }
        (deposits + withdrawals).forEach { sameCurrency(target, it) }
        val scale = (listOf(target, initial) + deposits + withdrawals).maxOf { it.scale }
        val saved = deposits.fold(units(initial, scale)) { sum, item -> sum + units(item, scale) } -
            withdrawals.fold(zero) { sum, item -> sum + units(item, scale) }
        val remaining = (units(target, scale) - saved).max(zero)
        return SavingProgress(toMoney(saved, scale, target.currency), toMoney(remaining, scale, target.currency))
    }
}
