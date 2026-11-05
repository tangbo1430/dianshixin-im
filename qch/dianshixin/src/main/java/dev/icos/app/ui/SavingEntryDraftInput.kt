package dev.icos.app.ui

import dev.icos.app.data.local.FinanceAccount
import dev.icos.app.data.local.FinanceValidationException
import dev.icos.app.domain.AccountAmount
import dev.icos.app.domain.Money
import dev.icos.app.domain.RecordInput
import dev.icos.app.domain.SavingEntryInput
import dev.icos.app.domain.SavingEntryKind
import dev.icos.app.domain.SavingKind
import dev.icos.app.domain.SavingLinkedTransfer
import dev.icos.app.domain.SavingPlanInput
import dev.icos.app.domain.normalizeNewSavingEntryRemark
import java.util.TimeZone
import java.util.UUID

internal fun SavingEntryDraft.toValidatedInput(visibleAccounts: List<FinanceAccount>): SavingEntryInput {
    val amount = moneyInput(amount, plan.input.target.currency)
    if (amount.unscaled <= 0) throw FinanceValidationException(if (withdrawal) "取出金额必须大于0" else "存入金额必须大于0")
    val index =
        cardIndex.takeIf(String::isNotBlank)?.let {
            it.toIntOrNull() ?: throw FinanceValidationException("卡片序号须为整数")
        }
    if (index != null) {
        if (withdrawal) throw FinanceValidationException("取出不能关联卡片")
        if (plan.input.kind == SavingKind.FLEXIBLE) throw FinanceValidationException("灵活计划不使用卡片序号")
        val count =
            runCatching { savingCardCount(plan.input) }.getOrElse {
                throw FinanceValidationException("计划卡片数量超出范围，无法按序号存入")
            }
        if (index !in 1..count) throw FinanceValidationException("卡片序号须在1–${count}之间")
        val expected = savingCardAmount(plan.input, index, count)
        if (!amount.sameValue(expected)) throw FinanceValidationException("第${index}张应存入 ${moneyText(expected)} ${expected.currency}")
    } else if (!withdrawal && plan.input.kind != SavingKind.FLEXIBLE && !plan.input.allowManualEntries) {
        throw FinanceValidationException("此计划不允许无卡片序号手动存入")
    }
    val remark = normalizeNewSavingEntryRemark(remark)
    val linked =
        if (linkTransfer) {
            val from = fromAccountId ?: throw FinanceValidationException("请选择转出账户")
            val to = toAccountId ?: throw FinanceValidationException("请选择转入账户")
            if (from == to) throw FinanceValidationException("转入和转出账户不能相同")
            val fromAccount =
                visibleAccounts.firstOrNull { it.id == from }
                    ?: throw FinanceValidationException("转出账户不可用或不在当前账本")
            val toAccount =
                visibleAccounts.firstOrNull { it.id == to }
                    ?: throw FinanceValidationException("转入账户不可用或不在当前账本")
            if (!fromAccount.selectable || fromAccount.archivedAt != null) throw FinanceValidationException("转出账户不可用")
            if (!toAccount.selectable || toAccount.archivedAt != null) throw FinanceValidationException("转入账户不可用")
            if (fromAccount.currency != amount.currency || toAccount.currency != amount.currency) {
                throw FinanceValidationException("关联账户币种必须与存钱计划一致")
            }
            val transfer = RecordInput.Transfer(AccountAmount(from, amount), AccountAmount(to, amount), null, null).also { it.calculate() }
            SavingLinkedTransfer(UUID.randomUUID(), transfer, TimeZone.getDefault().id)
        } else {
            null
        }
    return SavingEntryInput(
        if (withdrawal) SavingEntryKind.WITHDRAWAL else SavingEntryKind.DEPOSIT,
        amount,
        occurredAtMillis,
        index,
        remark,
        linked,
    )
}

internal fun savingCardCount(input: SavingPlanInput): Int =
    when (input.kind) {
        SavingKind.WEEK_52 -> {
            52
        }

        SavingKind.DAY_365 -> {
            365
        }

        SavingKind.FIXED -> {
            val first = input.firstAmount ?: input.target
            (
                java.math
                    .BigDecimal(input.target.unscaled)
                    .movePointLeft(input.target.scale)
                    .divide(java.math.BigDecimal(first.unscaled).movePointLeft(first.scale), 0, java.math.RoundingMode.CEILING)
            ).intValueExact()
        }

        SavingKind.FLEXIBLE -> {
            0
        }
    }

internal fun savingCardAmount(
    input: SavingPlanInput,
    number: Int,
    cardCount: Int,
): Money =
    when (input.kind) {
        SavingKind.WEEK_52, SavingKind.DAY_365 -> {
            savingRampCardAmount(input.firstAmount!!, input.increment!!, number)
        }

        SavingKind.FIXED -> {
            if (number == cardCount) {
                val targetValue = java.math.BigDecimal(java.math.BigInteger.valueOf(input.target.unscaled), input.target.scale)
                val firstValue = java.math.BigDecimal(java.math.BigInteger.valueOf(input.firstAmount!!.unscaled), input.firstAmount.scale)
                val lastValue = targetValue.subtract(firstValue.multiply(java.math.BigDecimal.valueOf((cardCount - 1).toLong())))
                val normalized = lastValue.stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }
                Money(normalized.unscaledValue().longValueExact(), normalized.scale(), input.target.currency)
            } else {
                input.firstAmount!!
            }
        }

        else -> {
            input.target
        }
    }

internal fun savingRampCardAmount(
    first: Money,
    increment: Money,
    number: Int,
): Money {
    val total =
        dev.icos.app.domain.PlanCalculator
            .savingRamp(first, increment, number)
    return if (number == 1) {
        total
    } else {
        total.checkedSubtract(
            dev.icos.app.domain.PlanCalculator
                .savingRamp(first, increment, number - 1),
        )
    }
}
