package dev.icos.app.ui

import dev.icos.app.domain.SavingInterval
import dev.icos.app.domain.SavingIntervalUnit
import dev.icos.app.domain.SavingKind
import dev.icos.app.domain.SavingPlanInput
import dev.icos.app.domain.validateSavingPlan
import dev.icos.app.domain.validateSavingPlanDatePair

// Edits cannot change the ramp, interval or start date, so those always come from the original plan.
internal fun SavingPlanDraft.toValidatedInput(): SavingPlanInput {
    val existing = original
    val targetAmount = moneyInput(target, currency)
    val initial = if (existing == null) moneyInput(initialSaved, currency) else existing.input.initialSaved
    val first =
        if (existing != null) {
            existing.input.firstAmount
        } else if (kind != SavingKind.FLEXIBLE) {
            firstAmount.takeIf(String::isNotBlank)?.let { moneyInput(it, currency) }
        } else {
            null
        }
    val incrementAmount =
        if (existing != null) {
            existing.input.increment
        } else if (kind == SavingKind.WEEK_52 || kind == SavingKind.DAY_365) {
            increment.takeIf(String::isNotBlank)?.let { moneyInput(it, currency) }
        } else {
            null
        }
    val schedule =
        if (existing != null) {
            existing.input.interval
        } else {
            when (kind) {
                SavingKind.FLEXIBLE -> null
                SavingKind.FIXED -> SavingInterval(intervalUnit, intervalStep.toInt())
                SavingKind.WEEK_52 -> SavingInterval(SavingIntervalUnit.WEEK, 1)
                SavingKind.DAY_365 -> SavingInterval(SavingIntervalUnit.DAY, 1)
            }
        }
    val input =
        SavingPlanInput(
            kind,
            name.trim(),
            null,
            targetAmount,
            initial,
            first,
            incrementAmount,
            schedule,
            if (existing != null) {
                existing.input.startDate
            } else {
                startDate.ifBlank { null }
            },
            endDate.ifBlank { null },
            allowManualEntries,
        )
    // Legacy end-only guest plans remain editable; account writes enforce the wire contract.
    val legacyEndOnly = existing != null && existing.input.startDate == null && existing.input.endDate != null
    if (!legacyEndOnly) validateSavingPlanDatePair(input.startDate, input.endDate)
    validateSavingPlan(input)
    return input
}
