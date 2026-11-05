package dev.icos.app.domain

import java.math.BigInteger
import java.util.UUID

class RefundException : IllegalArgumentException("INVALID_REQUEST") {
    val code: String = "INVALID_REQUEST"
}

data class RefundReceipt(
    val id: UUID,
    // Amount in the original expense currency; validated FX conversion belongs to the service.
    val amount: Money,
    val cancelled: Boolean,
)

data class RefundEffect(
    val refundId: UUID,
    val expenseReduction: Money,
    val excessAmount: Money,
)

data class RefundRecovery(
    val refunded: Money,
    val netExpense: Money,
    val excessAmount: Money,
    val effects: List<RefundEffect>,
)

/** D-refund-recovery-v1: rebuild in immutable creation order; excess becomes income only when the setting enables it. */
fun calculateRefundRecovery(
    original: Money,
    refunds: List<RefundReceipt>,
): RefundRecovery {
    if (original.unscaled < 0) throw MoneyException("INVALID_MONEY")
    val ids = mutableSetOf<UUID>()
    var refunded = original.copy(unscaled = 0)
    var net = original
    var excess = original.copy(unscaled = 0)
    val effects = mutableListOf<RefundEffect>()
    refunds.forEach { refund ->
        refund.amount.requirePositive()
        if (refund.id == UUID(0, 0) || !ids.add(refund.id) || refund.amount.currency != original.currency) {
            throw RefundException()
        }
        if (refund.cancelled) return@forEach
        val scale = maxOf(net.scale, refund.amount.scale)

        fun atScale(amount: Money): BigInteger = BigInteger.valueOf(amount.unscaled).multiply(BigInteger.TEN.pow(scale - amount.scale))
        val reduction = if (atScale(refund.amount) <= atScale(net)) refund.amount else net
        val refundExcess = refund.amount.checkedSubtract(reduction)
        net = net.checkedSubtract(reduction)
        refunded = refunded.checkedAdd(refund.amount)
        excess = excess.checkedAdd(refundExcess)
        effects += RefundEffect(refund.id, reduction, refundExcess)
    }
    return RefundRecovery(refunded, net, excess, effects)
}
