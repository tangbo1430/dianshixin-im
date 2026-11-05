package dev.icos.app.domain

import java.math.BigDecimal
import java.math.RoundingMode
import java.math.BigInteger
import java.util.UUID

class RecordException : IllegalArgumentException("INVALID_REQUEST") {
    val code: String = "INVALID_REQUEST"
}

data class AccountAmount(
    val accountId: UUID,
    val amount: Money,
)

enum class Direction { INCOME, EXPENSE }

data class StatContribution(
    val direction: Direction,
    val amount: Money,
)

data class RecordEffects(
    val postings: List<AccountAmount> = emptyList(),
    val statistics: List<StatContribution> = emptyList(),
    val budget: List<Money> = emptyList(),
)

sealed interface RecordInput {
    data class Expense(
        val gross: Money,
        val discount: Money?,
        val allocations: List<AccountAmount>,
        val countInStatistics: Boolean,
        val countInBudget: Boolean,
        /** Record-currency split amounts aligned by index with account-currency postings. */
        val recordCurrencyAmounts: List<Money>? = null,
    ) : RecordInput

    data class Income(
        val amount: Money,
        val allocations: List<AccountAmount>,
        val countInStatistics: Boolean,
    ) : RecordInput

    data class Transfer(
        val from: AccountAmount,
        val to: AccountAmount,
        val fee: AccountAmount?,
        val bonus: AccountAmount?,
    ) : RecordInput

    fun calculate(): RecordEffects =
        when (this) {
            is Expense -> {
                gross.requirePositive()
                val actual =
                    if (discount == null) {
                        gross
                    } else {
                        if (discount.unscaled < 0 || discount.currency != gross.currency) throw RecordException()
                        gross.checkedSubtract(discount).also { if (it.unscaled < 0) throw RecordException() }
                    }
                val recordAmounts = recordCurrencyAmounts ?: allocations.map { it.amount }
                if (recordAmounts.size != allocations.size) throw RecordException()
                val actualUnits = units(actual)
                val total = recordAmounts.fold(BigInteger.ZERO) { sum, amount ->
                    amount.requirePositive()
                    if (amount.currency != actual.currency) throw RecordException()
                    sum.add(units(amount)).also { if (it.bitLength() > 127) throw MoneyException("AMOUNT_OVERFLOW") }
                }
                if (allocations.isNotEmpty() && total != actualUnits) throw RecordException()
                val postings = allocations.mapIndexed { index, allocation ->
                    allocation.amount.requirePositive()
                    val recordAmount = recordAmounts[index]
                    if (allocation.amount.currency == recordAmount.currency && !allocation.amount.sameValue(recordAmount)) throw RecordException()
                    if (recordAmount.currency != actual.currency) throw RecordException()
                    if (allocation.amount.currency != recordAmount.currency) {
                        val fxRate = inferredExplicitRate(recordAmount, allocation.amount)
                        if (fxRate.signum() <= 0 || fxRate.precision() > 38) throw RecordException()
                    }
                    allocation.copy(amount = allocation.amount.checkedNegate())
                }
                RecordEffects(
                    postings = postings,
                    statistics = if (countInStatistics) listOf(StatContribution(Direction.EXPENSE, actual)) else emptyList(),
                    budget = if (countInBudget) listOf(actual) else emptyList(),
                )
            }

            is Income -> {
                amount.requirePositive()
                RecordEffects(
                    postings = allocate(amount, allocations, Direction.INCOME),
                    statistics = if (countInStatistics) listOf(StatContribution(Direction.INCOME, amount)) else emptyList(),
                )
            }

            is Transfer -> {
                from.amount.requirePositive()
                to.amount.requirePositive()
                if (from.accountId == to.accountId ||
                    (from.amount.currency == to.amount.currency && !from.amount.sameValue(to.amount))
                ) {
                    throw RecordException()
                }
                val postings = mutableListOf(from.copy(amount = from.amount.checkedNegate()), to)
                val statistics = mutableListOf<StatContribution>()
                val budget = mutableListOf<Money>()
                if (fee != null) {
                    fee.amount.requirePositive()
                    postings += fee.copy(amount = fee.amount.checkedNegate())
                    statistics += StatContribution(Direction.EXPENSE, fee.amount)
                    budget += fee.amount
                }
                if (bonus != null) {
                    bonus.amount.requirePositive()
                    postings += bonus
                    statistics += StatContribution(Direction.INCOME, bonus.amount)
                }
                RecordEffects(postings, statistics, budget)
            }
        }
}

private fun units(money: Money): BigInteger =
    BigInteger.valueOf(money.unscaled).multiply(BigInteger.TEN.pow(6 - money.scale))

private fun inferredExplicitRate(recordAmount: Money, accountAmount: Money): BigDecimal {
    val recordValue = BigDecimal(BigInteger.valueOf(recordAmount.unscaled), recordAmount.scale)
    val accountValue = BigDecimal(BigInteger.valueOf(accountAmount.unscaled), accountAmount.scale)
    return accountValue.divide(recordValue, 18, RoundingMode.HALF_UP)
}

private fun allocate(
    actual: Money,
    allocations: List<AccountAmount>,
    direction: Direction,
): List<AccountAmount> {
    // Sum all splits in wide common units before checking the final i64 representation.
    fun units(money: Money): BigInteger =
        BigInteger.valueOf(money.unscaled).multiply(BigInteger.TEN.pow(6 - money.scale))
    var total = BigInteger.ZERO
    val postings =
        allocations.map { allocation ->
            allocation.amount.requirePositive()
            if (allocation.amount.currency != actual.currency) {
                // shortcut: only same-currency splits; add validated FX snapshots for cross-currency allocations in M2.
                throw RecordException()
            }
            total = total.add(units(allocation.amount))
            if (total.bitLength() > 127) throw MoneyException("AMOUNT_OVERFLOW")
            allocation.copy(amount = if (direction == Direction.EXPENSE) allocation.amount.checkedNegate() else allocation.amount)
        }
    var bounded = total
    var scale = 6
    while (bounded.bitLength() > 63 && scale > 0 && bounded.mod(BigInteger.TEN) == BigInteger.ZERO) {
        bounded = bounded.divide(BigInteger.TEN)
        scale -= 1
    }
    if (bounded.bitLength() > 63) throw MoneyException("AMOUNT_OVERFLOW")
    if (allocations.isNotEmpty() && total != units(actual)) throw RecordException()
    return postings
}
