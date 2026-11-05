package dev.icos.app.domain

import java.math.BigDecimal
import java.math.RoundingMode

data class BudgetAmounts(
    val remaining: Money,
    val dailyAllowance: BigDecimal,
)

fun budgetAmounts(
    limit: Money,
    used: Money,
    remainingDays: Int,
): BudgetAmounts {
    require(limit.unscaled >= 0 && remainingDays >= 0)
    val remaining = limit.checkedSubtract(used)
    val positive = BigDecimal.valueOf(remaining.unscaled, remaining.scale).max(BigDecimal.ZERO)
    val daily =
        if (remainingDays ==
            0
        ) {
            BigDecimal.ZERO
        } else {
            positive.divide(BigDecimal.valueOf(remainingDays.toLong()), 6, RoundingMode.DOWN)
        }
    return BudgetAmounts(remaining, daily.stripTrailingZeros().let { it.setScale(it.scale().coerceAtLeast(0)) })
}
