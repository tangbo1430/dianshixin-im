package dev.icos.app.data.local

import dev.icos.app.domain.Direction
import dev.icos.app.domain.Money
import dev.icos.app.domain.RecordInput
import java.util.UUID

/** Resolves one statistic row only when its historical inputs identify exact account amounts. */
internal object AccountStatisticProjection {
    fun project(
        kind: String,
        input: RecordInput?,
        direction: Direction,
        amount: Money,
        adjustmentAccountId: UUID? = null,
    ): Map<UUID, Money>? {
        try {
            if (input == null) {
                if (kind !in setOf("REFUND", "CLAIM_SETTLEMENT")) return null
                val accountId = adjustmentAccountId ?: return null
                return mapOf(accountId to amount)
            }
            val parts =
                when {
                    kind == "EXPENSE" && direction == Direction.EXPENSE && input is RecordInput.Expense -> {
                        val values = input.recordCurrencyAmounts ?: input.allocations.map { it.amount }
                        if (values.size != input.allocations.size || values.any { it.currency != amount.currency }) return null
                        input.allocations.mapIndexed { index, allocation -> allocation.accountId to values[index] }
                    }

                    kind == "INCOME" && direction == Direction.INCOME && input is RecordInput.Income -> {
                        input.allocations.map { it.accountId to it.amount }
                    }

                    kind == "TRANSFER" && input is RecordInput.Transfer -> {
                        val fees = listOfNotNull(input.fee?.let { it.accountId to it.amount })
                        val bonuses = listOfNotNull(input.bonus?.let { it.accountId to it.amount })
                        (if (direction == Direction.EXPENSE) fees else bonuses)
                    }

                    else -> {
                        return null
                    }
                }
            // Every returned map sums to the statistic amount; a positive split never stands in for a negated row.
            if (parts.isEmpty()) {
                val expected =
                    when (input) {
                        is RecordInput.Expense -> input.discount?.let(input.gross::checkedSubtract) ?: input.gross
                        is RecordInput.Income -> input.amount
                        else -> return null
                    }
                return if (expected.currency == amount.currency && expected.sameValue(amount)) emptyMap() else null
            }
            if (parts.any { it.second.currency != amount.currency }) return null
            val sum = parts.fold(Money(0, amount.scale, amount.currency)) { total, part -> total.checkedAdd(part.second) }
            if (!sum.sameValue(amount)) return null
            return parts.groupBy { it.first }.mapValues { (_, values) ->
                values.fold(Money(0, amount.scale, amount.currency)) { total, part -> total.checkedAdd(part.second) }
            }
        } catch (_: RuntimeException) {
            // Optional attribution must not make an otherwise readable ledger unavailable.
            return null
        }
    }
}
