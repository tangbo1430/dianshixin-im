package dev.icos.app.domain

import java.math.BigInteger
import java.util.UUID

class ClaimException : IllegalArgumentException("INVALID_REQUEST") {
    val code: String = "INVALID_REQUEST"
}

enum class RemainderAction { PARTIAL_KEEP, COMPLETE }

enum class ClaimStatus { PENDING, PARTIAL, SETTLED }

data class ClaimReceipt(
    val id: UUID,
    val actualAmount: Money,
    val remainderAction: RemainderAction,
    val cancelled: Boolean,
)

data class ClaimReceiptEffect(
    val receiptId: UUID,
    val processedPrincipal: Money,
    val expenseReduction: Money,
    val excessIncome: Money,
    val closedUnreimbursed: Money,
)

data class ClaimRecovery(
    val status: ClaimStatus,
    val received: Money,
    val netExpense: Money,
    val excessIncome: Money,
    val remainingEligible: Money,
    val effects: List<ClaimReceiptEffect>,
)

/** D-claim-recovery-v1: the service supplies receipts in immutable creation order, including cancellations. */
fun calculateClaimRecovery(
    original: Money,
    receipts: List<ClaimReceipt>,
): ClaimRecovery {
    original.requirePositive()
    val ids = mutableSetOf<UUID>()
    var remaining = original
    var received = original.copy(unscaled = 0)
    var excess = original.copy(unscaled = 0)
    var net = original
    val effects = mutableListOf<ClaimReceiptEffect>()
    receipts.forEach { receipt ->
        receipt.actualAmount.requirePositive()
        if (receipt.id == UUID(0, 0) || !ids.add(receipt.id) || receipt.actualAmount.currency != original.currency) {
            throw ClaimException()
        }
        if (receipt.cancelled) return@forEach
        if (remaining.unscaled == 0L) throw ClaimException()
        val scale = maxOf(net.scale, receipt.actualAmount.scale)

        fun atScale(amount: Money): BigInteger = BigInteger.valueOf(amount.unscaled).multiply(BigInteger.TEN.pow(scale - amount.scale))
        val reduction = if (atScale(receipt.actualAmount) <= atScale(net)) receipt.actualAmount else net
        val income = receipt.actualAmount.checkedSubtract(reduction)
        val processed =
            when (receipt.remainderAction) {
                RemainderAction.PARTIAL_KEEP -> reduction
                RemainderAction.COMPLETE -> remaining
            }
        val closed = processed.checkedSubtract(reduction)
        remaining = remaining.checkedSubtract(processed)
        net = net.checkedSubtract(reduction)
        received = received.checkedAdd(receipt.actualAmount)
        excess = excess.checkedAdd(income)
        effects += ClaimReceiptEffect(receipt.id, processed, reduction, income, closed)
    }
    val status =
        when {
            effects.isEmpty() -> ClaimStatus.PENDING
            remaining.unscaled == 0L -> ClaimStatus.SETTLED
            else -> ClaimStatus.PARTIAL
        }
    return ClaimRecovery(status, received, net, excess, remaining, effects)
}
