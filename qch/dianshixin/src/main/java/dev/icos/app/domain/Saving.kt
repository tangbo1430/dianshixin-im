package dev.icos.app.domain

import dev.icos.app.data.local.FinanceValidationException
import dev.icos.app.data.local.SavingPlanEntity
import java.math.BigDecimal
import java.math.BigInteger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

enum class SavingKind { FLEXIBLE, FIXED, WEEK_52, DAY_365 }

enum class SavingEntryKind { DEPOSIT, WITHDRAWAL }

enum class SavingState { ACTIVE, ARCHIVED, DELETED }

enum class SavingTransferStrategy { KEEP_TRANSFERS, DELETE_TRANSFERS }

enum class SavingIntervalUnit { DAY, WEEK, MONTH, YEAR }

data class SavingInterval(
    val unit: SavingIntervalUnit,
    val step: Int,
) {
    init {
        require(step > 0)
    }
}

data class SavingPlanInput(
    val kind: SavingKind,
    val name: String,
    val iconRef: String?,
    val target: Money,
    val initialSaved: Money,
    val firstAmount: Money? = null,
    val increment: Money? = null,
    val interval: SavingInterval? = null,
    val startDate: String? = null,
    val endDate: String? = null,
    val allowManualEntries: Boolean,
)

sealed interface SavingPatchValue<out T> {
    data object Unchanged : SavingPatchValue<Nothing>

    data class Set<T>(
        val value: T?,
    ) : SavingPatchValue<T>
}

data class SavingPlanPatch(
    val name: SavingPatchValue<String> = SavingPatchValue.Unchanged,
    val iconRef: SavingPatchValue<String> = SavingPatchValue.Unchanged,
    val target: SavingPatchValue<Money> = SavingPatchValue.Unchanged,
    val endDate: SavingPatchValue<String> = SavingPatchValue.Unchanged,
    val allowManualEntries: SavingPatchValue<Boolean> = SavingPatchValue.Unchanged,
)

internal fun <T> SavingPatchValue<T>.orCurrent(current: T): T? =
    when (this) {
        SavingPatchValue.Unchanged -> current
        is SavingPatchValue.Set -> value
    }

data class SavingEntryInput(
    val kind: SavingEntryKind,
    val amount: Money,
    val occurredAtMillis: Long,
    val cardIndex: Int? = null,
    val remark: String? = null,
    val linkedTransfer: SavingLinkedTransfer? = null,
)

data class SavingLinkedTransfer(
    val recordId: UUID,
    val input: RecordInput.Transfer,
    val timezone: String,
    val categoryId: UUID? = null,
    val tagIds: List<UUID> = emptyList(),
)

data class SavingPlanSummary(
    val id: UUID,
    val input: SavingPlanInput,
    val state: SavingState,
    val saved: Money,
    val remaining: Money,
    val localRevision: Long,
)

data class SavingEntrySummary(
    val id: UUID,
    val planId: UUID,
    val kind: SavingEntryKind,
    val amount: Money,
    val occurredAtMillis: Long,
    val cardIndex: Int?,
    val remark: String?,
    val linkedRecordId: UUID?,
    val cancelledAt: Long?,
    val localRevision: Long,
)

internal fun validateSavingPlan(input: SavingPlanInput) {
    require(input.name.trim().isNotEmpty() && input.name.length <= 100 && '\u0000' !in input.name)
    input.target.requirePositive()
    require(input.initialSaved.unscaled >= 0 && input.initialSaved.currency == input.target.currency)
    require(input.firstAmount?.currency == null || input.firstAmount.currency == input.target.currency)
    require(input.increment?.currency == null || input.increment.currency == input.target.currency)
    input.endDate?.let(::validateSavingDate)
    input.startDate?.let(::validateSavingDate)
    if (input.startDate != null && input.endDate != null) require(input.endDate >= input.startDate)
    when (input.kind) {
        SavingKind.FLEXIBLE -> {
            require(input.allowManualEntries && input.firstAmount == null && input.increment == null && input.interval == null)
        }

        SavingKind.FIXED -> {
            require(
                (input.firstAmount?.unscaled ?: 0L) > 0L && input.interval != null && input.startDate != null && input.increment == null,
            )
        }

        SavingKind.WEEK_52, SavingKind.DAY_365 -> {
            require(input.firstAmount != null && input.firstAmount.unscaled > 0)
            require(input.increment != null && input.increment.unscaled >= 0)
            require(input.startDate != null)
            require(
                input.interval ==
                    SavingInterval(if (input.kind == SavingKind.WEEK_52) SavingIntervalUnit.WEEK else SavingIntervalUnit.DAY, 1),
            )
            val count = if (input.kind == SavingKind.WEEK_52) 52 else 365
            require(PlanCalculator.savingRamp(input.firstAmount, input.increment, count).sameValue(input.target))
        }
    }
}

internal fun validateSavingPlanDatePair(
    startDate: String?,
    endDate: String?,
) {
    startDate?.let(::validateSavingDate)
    endDate?.let(::validateSavingDate)
    if (endDate != null && startDate == null) throw FinanceValidationException("设置目标日期前，请先选择开始日期")
    if (startDate != null && endDate != null && endDate < startDate) throw FinanceValidationException("目标日期不能早于开始日期")
}

internal fun validateSavingDate(value: String) {
    require(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(value))
    val format =
        SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply {
            isLenient = false
            timeZone = TimeZone.getTimeZone("UTC")
        }
    require(format.format(format.parse(value) ?: error("Invalid date")) == value)
}

internal fun savingUtcDate(epochMillis: Long): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(epochMillis))

internal fun SavingPlanInput.toSavingEntity(
    id: String,
    owner: String,
    epoch: String,
    now: Long,
) = SavingPlanEntity(
    id,
    owner,
    epoch,
    kind.name,
    name.trim(),
    iconRef,
    target.currency,
    target.unscaled,
    target.scale,
    initialSaved.unscaled,
    initialSaved.scale,
    firstAmount?.unscaled,
    firstAmount?.scale,
    increment?.unscaled,
    increment?.scale,
    interval?.unit?.name,
    interval?.step,
    startDate,
    endDate,
    "ACTIVE",
    allowManualEntries,
    now,
    now,
    1,
    null,
)

internal fun SavingPlanEntity.initialSavedMoney() = Money(initialSavedUnscaled, initialSavedScale, currency)

internal fun SavingPlanEntity.firstAmountMoney() = firstAmountUnscaled?.let { Money(it, firstAmountScale!!, currency) }

internal fun SavingPlanEntity.incrementMoney() = incrementUnscaled?.let { Money(it, incrementScale!!, currency) }

internal fun SavingPlanEntity.toSavingInput() =
    SavingPlanInput(
        SavingKind.valueOf(kind),
        name,
        iconRef,
        Money(targetUnscaled, targetScale, currency),
        initialSavedMoney(),
        firstAmountMoney(),
        incrementMoney(),
        intervalUnit?.let { SavingInterval(SavingIntervalUnit.valueOf(it), intervalStep!!) },
        startDate,
        endDate,
        allowManualEntries,
    )

internal fun Money.decimalForSaving() = BigDecimal(BigInteger.valueOf(unscaled), scale)

internal fun wideSavingMoney(
    value: BigInteger,
    sourceScale: Int,
    currency: String,
): Money {
    var units = value
    var scale = sourceScale
    while (units.bitLength() > 63 && scale > 0 && units.mod(BigInteger.TEN) == BigInteger.ZERO) {
        units /= BigInteger.TEN
        scale--
    }
    return Money(units.longValueExact(), scale, currency)
}
