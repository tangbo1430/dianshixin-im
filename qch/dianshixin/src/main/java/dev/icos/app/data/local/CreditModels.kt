package dev.icos.app.data.local

import dev.icos.app.domain.Money
import java.util.UUID

data class FinanceCreditProfile(
    val id: UUID, val accountId: UUID, val limit: Money, val statementDay: Int,
    val includeStatementDay: Boolean, val repaymentRule: String, val repaymentValue: Int,
    val timezone: String, val enabled: Boolean, val localRevision: Long,
    val rawAvailableCapacity: Money? = null, val currentDebt: Money? = null, val statementAmount: Money? = null,
    val cycleStartInclusive: String? = null, val cycleEndExclusive: String? = null, val statementDate: String? = null, val repaymentDate: String? = null,
)
data class FinanceCreditCapacityEvent(
    val id: UUID, val accountId: UUID, val sourceType: String, val delta: Money,
    val occurredAtMillis: Long, val reason: String,
)
data class FinanceCreditPaymentAllocation(
    val id: UUID, val accountId: UUID, val paymentEventId: UUID, val termId: UUID, val amount: Money,
)
data class FinanceCreditPayment(
    val recordId: UUID, val eventId: UUID, val accountId: UUID, val assetAccountId: UUID,
    val amount: Money, val remainingToAllocate: Money, val occurredAtMillis: Long,
)
data class FinanceInstallmentPlan(
    val id: UUID, val sourceType: String, val accountId: UUID, val principal: Money,
    val interest: Money, val termCount: Int, val principalPosting: String,
    val executor: String, val state: String, val localRevision: Long,
)
data class FinanceInstallmentTerm(
    val id: UUID, val planId: UUID, val termIndex: Int, val dueDate: String,
    val principal: Money, val interest: Money, val state: String, val postingEventId: UUID?,
    val remainingDue: Money? = null,
)
