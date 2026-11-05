package dev.icos.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.icos.app.data.local.BudgetScope
import dev.icos.app.data.local.CanonicalAccountRepository
import dev.icos.app.data.local.FinanceBudgetOverview
import dev.icos.app.data.local.FinanceRecord
import dev.icos.app.data.local.FinanceRecordTemplate
import dev.icos.app.data.local.FinanceSnapshot
import dev.icos.app.data.local.FinanceValidationException
import dev.icos.app.domain.AccountAmount
import dev.icos.app.domain.Direction
import dev.icos.app.domain.Money
import dev.icos.app.domain.MoneyException
import dev.icos.app.domain.RecordInput
import dev.icos.app.domain.SavingInterval
import dev.icos.app.domain.SavingIntervalUnit
import dev.icos.app.domain.SavingKind
import dev.icos.app.domain.SavingPlanSummary
import java.math.BigDecimal
import java.math.BigInteger
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

internal fun moneyText(value: Money): String = BigDecimal(BigInteger.valueOf(value.unscaled), value.scale).toPlainString()

internal fun accountAssetTotal(
    accounts: List<dev.icos.app.data.local.FinanceAccount>,
    currency: String,
): Money? =
    try {
        val sum =
            accounts
                .filter { it.currency == currency && it.includeInAssets && it.type != "LIABILITY" }
                .fold(BigDecimal.ZERO) { total, account ->
                    total.add(BigDecimal.valueOf(account.balance.unscaled, account.balance.scale))
                }.stripTrailingZeros()
                .let { it.setScale(it.scale().coerceAtLeast(0)) }
        Money(sum.unscaledValue().longValueExact(), sum.scale(), currency)
    } catch (_: ArithmeticException) {
        null
    } catch (_: MoneyException) {
        null
    }

internal data class AccountPosition(
    val assets: Money,
    val liabilities: Money,
    val net: Money,
)

internal fun accountPosition(
    accounts: List<dev.icos.app.data.local.FinanceAccount>,
    currency: String,
): AccountPosition? =
    try {
        fun total(type: String): Money {
            val value =
                accounts
                    .asSequence()
                    .filter {
                        it.currency == currency &&
                            (if (type == "LIABILITY") it.type == "LIABILITY" else it.type != "LIABILITY" && it.includeInAssets)
                    }.fold(BigDecimal.ZERO) { sum, account ->
                        sum + BigDecimal.valueOf(account.balance.unscaled, account.balance.scale)
                    }.stripTrailingZeros()
                    .let { it.setScale(it.scale().coerceAtLeast(0)) }
            return Money(value.unscaledValue().longValueExact(), value.scale(), currency)
        }
        val assets = total("ASSET")
        val liabilities = total("LIABILITY")
        AccountPosition(assets, liabilities, assets.checkedSubtract(liabilities))
    } catch (_: ArithmeticException) {
        null
    } catch (_: MoneyException) {
        null
    }

internal fun moneyInput(
    text: String,
    currency: String,
    signed: Boolean = false,
): Money {
    val raw = text.trim()
    if (raw.length !in 1..32 || !raw.matches(Regex("-?(?:[0-9]+(?:\\.[0-9]{0,6})?|\\.[0-9]{1,6})"))) {
        throw FinanceValidationException("请输入有效金额，最多支持 6 位小数")
    }
    val decimal = BigDecimal(raw)
    val money = Money.fromWire(decimal.unscaledValue().toString(), decimal.scale(), currency.trim().uppercase(Locale.ROOT))
    if (!signed && money.unscaled < 0) throw FinanceValidationException("金额不能为负数")
    return money
}

internal enum class EntryKind(
    val label: String,
) {
    EXPENSE("支出"),
    INCOME("收入"),
    TRANSFER("转账"),
}

internal class AllocationDraft(
    account: UUID?,
    amount: String,
    recordCurrencyAmount: String = amount,
) {
    var accountId by mutableStateOf(account)
    var amount by mutableStateOf(amount)
    var recordCurrencyAmount by mutableStateOf(recordCurrencyAmount)
}

// ViewModel-owned memory survives rotation without putting amounts or remarks into saved-state bundles.
internal class RecordDraft(
    snapshot: FinanceSnapshot,
    record: FinanceRecord? = null,
    val sourceTemplate: FinanceRecordTemplate? = null,
    copy: Boolean = false,
    defaultCurrency: String = "CNY",
    private val allowedAccountIds: Set<UUID> = snapshot.accounts.mapTo(mutableSetOf()) { it.id },
    defaultKind: EntryKind = EntryKind.EXPENSE,
    defaultAccountId: UUID? = null,
) {
    val originalRecord = record.takeUnless { copy }
    var id = record?.id?.takeUnless { copy }
    var expectedRevision = record?.localRevision?.takeUnless { copy }
    var saveQueued by mutableStateOf(false)
    private val oldInput = record?.input ?: sourceTemplate?.input
    private val isTemplateDraft = sourceTemplate != null
    private val activeAccountIds = allowedAccountIds
    private val preferredAccount =
        defaultAccountId.takeIf { oldInput == null && !copy }?.let { id ->
            snapshot.accounts.firstOrNull { it.id == id && it.id in activeAccountIds && it.selectable && it.archivedAt == null }
        }
    var kind by mutableStateOf(
        when (oldInput) {
            is RecordInput.Income -> EntryKind.INCOME
            is RecordInput.Transfer -> EntryKind.TRANSFER
            is RecordInput.Expense -> EntryKind.EXPENSE
            else -> defaultKind
        },
    )
    private val initialAmount =
        when (oldInput) {
            is RecordInput.Expense -> oldInput.gross
            is RecordInput.Income -> oldInput.amount
            is RecordInput.Transfer -> oldInput.from.amount
            else -> null
        }
    var amount by mutableStateOf(initialAmount?.let(::moneyText).orEmpty())
    var currency by mutableStateOf(initialAmount?.currency ?: preferredAccount?.currency ?: defaultCurrency.uppercase(Locale.ROOT))
    var discount by mutableStateOf((oldInput as? RecordInput.Expense)?.discount?.let(::moneyText).orEmpty())
    private val oldAllocations =
        when (oldInput) {
            is RecordInput.Expense -> oldInput.allocations
            is RecordInput.Income -> oldInput.allocations
            else -> emptyList()
        }
    var accountId by mutableStateOf(
        if (oldInput == null ||
            copy
        ) {
            preferredAccount?.id ?: snapshot.accounts
                .firstOrNull {
                    it.id in activeAccountIds && it.currency == currency && it.selectable &&
                        it.archivedAt == null
                }?.id
        } else {
            oldAllocations.firstOrNull()?.accountId
        },
    )
    var split by mutableStateOf(oldAllocations.size > 1 && (!copy || oldAllocations.all { it.accountId in activeAccountIds }))
    val allocations =
        mutableStateListOf<AllocationDraft>().apply {
            addAll(
                oldAllocations.filter { !copy || it.accountId in activeAccountIds }.mapIndexed { index, allocation ->
                    val recordAmount = (oldInput as? RecordInput.Expense)?.recordCurrencyAmounts?.getOrNull(index) ?: allocation.amount
                    AllocationDraft(allocation.accountId, moneyText(allocation.amount), moneyText(recordAmount))
                },
            )
        }
    var fromId by mutableStateOf(
        (oldInput as? RecordInput.Transfer)?.from?.accountId?.takeIf { !copy || it in activeAccountIds }
            ?: preferredAccount?.id
            ?: snapshot.accounts.firstOrNull { it.id in activeAccountIds && it.selectable && it.archivedAt == null }?.id,
    )
    var toId by mutableStateOf(
        (oldInput as? RecordInput.Transfer)?.to?.accountId?.takeIf { !copy || it in activeAccountIds }
            ?: snapshot.accounts
                .filter { it.id in activeAccountIds && it.selectable && it.archivedAt == null }
                .let { accounts -> if (oldInput == null) accounts.firstOrNull { it.id != fromId } else accounts.getOrNull(1) }
                ?.id,
    )
    var toAmount by mutableStateOf(
        (oldInput as? RecordInput.Transfer)
            ?.to
            ?.amount
            ?.let(::moneyText)
            .orEmpty(),
    )
    var fee by mutableStateOf(
        (oldInput as? RecordInput.Transfer)
            ?.fee
            ?.amount
            ?.let(::moneyText)
            .orEmpty(),
    )
    var bonus by mutableStateOf(
        (oldInput as? RecordInput.Transfer)
            ?.bonus
            ?.amount
            ?.let(::moneyText)
            .orEmpty(),
    )
    var feeAccountId by mutableStateOf((oldInput as? RecordInput.Transfer)?.fee?.accountId?.takeIf { !copy || it in activeAccountIds })
    var bonusAccountId by mutableStateOf((oldInput as? RecordInput.Transfer)?.bonus?.accountId?.takeIf { !copy || it in activeAccountIds })
    var countStatistics by mutableStateOf(
        when (oldInput) {
            is RecordInput.Expense -> oldInput.countInStatistics
            is RecordInput.Income -> oldInput.countInStatistics
            else -> true
        },
    )
    var countBudget by mutableStateOf((oldInput as? RecordInput.Expense)?.countInBudget ?: true)
    var categoryId by mutableStateOf(
        record?.categoryId ?: sourceTemplate?.categoryId ?: if (kind == EntryKind.TRANSFER) null else defaultCategory(snapshot),
    )
    var occurredAtMillis by mutableStateOf(record?.occurredAtMillis ?: sourceTemplate?.occurredAtMillis ?: System.currentTimeMillis())
    val timezone = record?.timezone ?: sourceTemplate?.timezone ?: TimeZone.getDefault().id
    var remark by mutableStateOf(record?.remark ?: sourceTemplate?.remark.orEmpty())
    val tagIds =
        mutableStateListOf<UUID>().apply {
            addAll(
                if (copy) {
                    record?.tagIds.orEmpty().filter { id -> snapshot.tags.firstOrNull { it.id == id }?.archivedAt == null }
                } else {
                    record?.tagIds ?: sourceTemplate?.tagIds.orEmpty()
                },
            )
        }

    private fun editValues(): List<Any?> =
        listOf(
            kind,
            amount,
            currency,
            discount,
            accountId,
            split,
            allocations.map { listOf(it.accountId, it.amount, it.recordCurrencyAmount) },
            fromId,
            toId,
            toAmount,
            fee,
            bonus,
            feeAccountId,
            bonusAccountId,
            countStatistics,
            countBudget,
            categoryId,
            occurredAtMillis,
            remark,
            tagIds.toList(),
        )

    private val initialEditValues = editValues()
    val hasChanges: Boolean get() = editValues() != initialEditValues

    private fun defaultCategory(snapshot: FinanceSnapshot): UUID? =
        snapshot.categories
            .firstOrNull {
                it.ledgerId == snapshot.ledgerId && it.archivedAt == null &&
                    it.direction == if (kind == EntryKind.INCOME) Direction.INCOME else Direction.EXPENSE
            }?.id

    fun selectKind(
        value: EntryKind,
        snapshot: FinanceSnapshot,
    ) {
        if (value == kind) return
        kind = value
        categoryId = if (value == EntryKind.TRANSFER) null else defaultCategory(snapshot)
    }

    fun input(snapshot: FinanceSnapshot): RecordInput {
        if (kind == EntryKind.TRANSFER) {
            val from =
                snapshot.accounts.find { it.id == fromId && it.id in activeAccountIds && it.selectable && it.archivedAt == null }
                    ?: throw FinanceValidationException("转出账户不可用，请选择当前账本可用账户")
            val to =
                snapshot.accounts.find { it.id == toId && it.id in activeAccountIds && it.selectable && it.archivedAt == null }
                    ?: throw FinanceValidationException("转入账户不可用，请选择当前账本可用账户")
            if (from.id == to.id) throw FinanceValidationException("转入和转出账户不能相同")
            val source = moneyInput(amount, from.currency)
            val priorTo = (oldInput as? RecordInput.Transfer)?.to
            val destination =
                if (from.currency == to.currency) {
                    priorTo?.takeIf { it.accountId == to.id && it.amount.sameValue(source) }?.amount ?: source
                } else {
                    moneyInput(toAmount, to.currency)
                }

            fun extra(
                text: String,
                account: UUID?,
                fallback: UUID,
            ): AccountAmount? {
                if (text.isBlank()) return null
                val selected =
                    snapshot.accounts.find {
                        it.id == (account ?: fallback) && it.id in activeAccountIds && it.selectable &&
                            it.archivedAt == null
                    }
                        ?: throw FinanceValidationException("请选择当前账本可用的费用或返现账户")
                return AccountAmount(selected.id, moneyInput(text, selected.currency))
            }
            return RecordInput
                .Transfer(
                    AccountAmount(from.id, source),
                    AccountAmount(to.id, destination),
                    extra(fee, feeAccountId, from.id),
                    extra(bonus, bonusAccountId, to.id),
                ).also { it.calculate() }
        }
        val gross = moneyInput(amount, currency)
        val reduction = if (kind == EntryKind.EXPENSE && discount.isNotBlank()) moneyInput(discount, currency) else null
        val actual = reduction?.let(gross::checkedSubtract) ?: gross
        val selected: List<AccountAmount> =
            if (split) {
                if (allocations.isEmpty()) throw FinanceValidationException("请添加组合账户")
                allocations.map {
                    val accountId = it.accountId ?: throw FinanceValidationException("请选择组合账户")
                    if (snapshot.accounts.none { account ->
                            account.id == accountId && account.id in activeAccountIds && account.selectable &&
                                account.archivedAt == null
                        }
                    ) {
                        throw FinanceValidationException("组合账户不可用，请选择当前账本可用账户")
                    }
                    val accountCurrency = snapshot.accounts.first { it.id == accountId }.currency
                    if (kind == EntryKind.INCOME && accountCurrency != currency) throw FinanceValidationException("收入组合账户必须与账单币种一致")
                    AccountAmount(accountId, moneyInput(it.amount, if (kind == EntryKind.EXPENSE) accountCurrency else currency))
                }
            } else if (actual.unscaled == 0L) {
                emptyList()
            } else {
                accountId?.let {
                    if (snapshot.accounts.none { account ->
                            account.id == it && account.id in activeAccountIds && account.selectable &&
                                account.archivedAt == null
                        }
                    ) {
                        throw FinanceValidationException("账户不可用，请选择当前账本可用账户")
                    }
                    listOf(AccountAmount(it, actual))
                } ?: emptyList()
            }
        val hasCrossCurrencyExpenseAllocation =
            kind == EntryKind.EXPENSE &&
                selected.indices.any { index ->
                    val accountId = allocations.getOrNull(index)?.accountId ?: selected[index].accountId
                    accountId != null && snapshot.accounts.first { it.id == accountId }.currency != currency
                }
        val expenseRecordAmounts: List<Money>? =
            if (hasCrossCurrencyExpenseAllocation) {
                this.allocations.mapIndexed { index, draftAllocation ->
                    val accountId = draftAllocation.accountId ?: throw FinanceValidationException("请选择组合账户")
                    val accountCurrency = snapshot.accounts.first { it.id == accountId }.currency
                    if (isTemplateDraft && accountCurrency != currency) throw FinanceValidationException("模板不支持跨币支出分摊")
                    val recordText = if (accountCurrency == currency) draftAllocation.amount else draftAllocation.recordCurrencyAmount
                    moneyInput(recordText, currency)
                }
            } else {
                null
            }
        return when (kind) {
            EntryKind.EXPENSE -> RecordInput.Expense(gross, reduction, selected, countStatistics, countBudget, expenseRecordAmounts)
            EntryKind.INCOME -> RecordInput.Income(gross, selected, countStatistics)
            EntryKind.TRANSFER -> error("handled transfer")
        }.also { it.calculate() }
    }
}

internal class SavingPlanDraft(
    plan: SavingPlanSummary? = null,
) {
    val original = plan
    var kind by mutableStateOf(plan?.input?.kind ?: SavingKind.FLEXIBLE)
    var name by mutableStateOf(plan?.input?.name.orEmpty())
    var currency by mutableStateOf(plan?.input?.target?.currency ?: "CNY")
    var target by mutableStateOf(
        plan
            ?.input
            ?.target
            ?.let(::moneyText)
            .orEmpty(),
    )
    var initialSaved by mutableStateOf(plan?.input?.initialSaved?.let(::moneyText) ?: "0")
    var firstAmount by mutableStateOf(
        plan
            ?.input
            ?.firstAmount
            ?.let(::moneyText)
            .orEmpty(),
    )
    var increment by mutableStateOf(
        plan
            ?.input
            ?.increment
            ?.let(::moneyText)
            .orEmpty(),
    )
    var intervalUnit by mutableStateOf(plan?.input?.interval?.unit ?: SavingIntervalUnit.MONTH)
    var intervalStep by mutableStateOf(
        plan
            ?.input
            ?.interval
            ?.step
            ?.toString() ?: "1",
    )
    var startDate by mutableStateOf(plan?.input?.startDate.orEmpty())
    var endDate by mutableStateOf(plan?.input?.endDate.orEmpty())
    var allowManualEntries by mutableStateOf(plan?.input?.allowManualEntries ?: true)

    private fun editValues(): List<Any?> =
        listOf(
            kind,
            name,
            currency,
            target,
            initialSaved,
            firstAmount,
            increment,
            intervalUnit,
            intervalStep,
            startDate,
            endDate,
            allowManualEntries,
        )

    private val initialEditValues = editValues()
    val hasChanges: Boolean get() = editValues() != initialEditValues
}

internal class SavingEntryDraft(
    val plan: SavingPlanSummary,
    val withdrawal: Boolean,
    initialCardIndex: Int? = null,
    initialAmount: Money? = null,
) {
    var amount by mutableStateOf(initialAmount?.let(::moneyText).orEmpty())
    var cardIndex by mutableStateOf(initialCardIndex?.toString().orEmpty())
    var occurredAtMillis by mutableStateOf(System.currentTimeMillis())
    var remark by mutableStateOf("")
    var linkTransfer by mutableStateOf(false)
    var fromAccountId by mutableStateOf<UUID?>(null)
    var toAccountId by mutableStateOf<UUID?>(null)

    private fun editValues(): List<Any?> = listOf(amount, cardIndex, occurredAtMillis, remark, linkTransfer, fromAccountId, toAccountId)

    private val initialEditValues = editValues()
    val hasChanges: Boolean get() = editValues() != initialEditValues
}

internal class BudgetDraft(
    val expected: FinanceBudgetOverview,
    val scope: BudgetScope,
) {
    var saveQueued by mutableStateOf(false)
    var amount by mutableStateOf((if (scope == BudgetScope.DEFAULT) expected.defaultAmount else expected.limit)?.let(::moneyText).orEmpty())
}

internal class AccountRecurrenceDraft(
    val original: CanonicalAccountRepository.RecurrenceSummary? = null,
    templates: List<FinanceRecordTemplate>,
    defaultZone: String,
) {
    var templateId by mutableStateOf(original?.templateId ?: templates.firstOrNull { it.invalidReason == null }?.id)
    var startLocalDateTime by mutableStateOf(
        original?.startLocalDateTime ?: java.time.LocalDateTime
            .now()
            .withSecond(0)
            .withNano(0)
            .toString(),
    )
    var timezone by mutableStateOf(original?.timezone ?: defaultZone)
    var frequency by mutableStateOf(original?.frequency ?: "MONTHLY")
    var interval by mutableStateOf(original?.interval?.toString() ?: "1")
    var weekDays by mutableStateOf(original?.weekDays?.joinToString(",") ?: "")
    var monthDays by mutableStateOf(original?.monthDays?.joinToString(",") ?: "")

    fun selectFrequency(value: String) {
        if (frequency == value) return
        frequency = value
        when (value) {
            "DAILY" -> {
                weekDays = ""
                monthDays = ""
            }

            "WEEKLY" -> {
                if (weekDays.isBlank()) {
                    weekDays =
                        runCatching {
                            java.time.LocalDateTime
                                .parse(startLocalDateTime)
                                .dayOfWeek.value
                                .toString()
                        }.getOrDefault("1")
                }
                monthDays = ""
            }

            "MONTHLY", "YEARLY" -> {
                weekDays = ""
            }
        }
    }

    var endAt by mutableStateOf(original?.endAt.orEmpty())
    var maxCount by mutableStateOf(original?.maxCount?.toString().orEmpty())
    var effectiveFromSlot by mutableStateOf(original?.nextDueSlot?.toString().orEmpty())
    var confirmTemplateRevision by mutableStateOf(false)
    var state by mutableStateOf(original?.state ?: "RUNNING")
    var mutationQueued by mutableStateOf(false)
}

internal class AccountDraft(
    val original: dev.icos.app.data.local.FinanceAccount? = null,
    defaultCurrency: String = "CNY",
) {
    val createId: UUID = UUID.randomUUID()
    var mutationQueued by mutableStateOf(false)
    var name by mutableStateOf(original?.name.orEmpty())
    var currency by mutableStateOf(original?.currency ?: defaultCurrency)
    var opening by mutableStateOf("0")
    var openingDateMillis by mutableStateOf(System.currentTimeMillis())
    var groupId by mutableStateOf<UUID?>(original?.groupId)
    var groupRevision by mutableStateOf<Long?>(null)
    var accountType by mutableStateOf(original?.type ?: "ASSET")

    private fun editValues(): List<Any?> = listOf(name, currency, opening, openingDateMillis, groupId, accountType)

    private val initialEditValues = editValues()
    val hasChanges: Boolean get() = editValues() != initialEditValues
}

internal class RecordTemplateDraft(
    snapshot: FinanceSnapshot,
    val original: FinanceRecordTemplate? = null,
    initial: FinanceRecordTemplate? = original,
    allowedAccountIds: Set<UUID> = snapshot.accounts.mapTo(mutableSetOf()) { it.id },
) {
    val id = original?.id ?: initial?.id ?: UUID.randomUUID()
    var mutationQueued by mutableStateOf(false)
    var name by mutableStateOf(original?.name ?: initial?.name.orEmpty())
    private val initialName = name
    val hasChanges: Boolean get() = record.hasChanges || name != initialName
    val record = RecordDraft(snapshot, sourceTemplate = initial, allowedAccountIds = allowedAccountIds)
}
