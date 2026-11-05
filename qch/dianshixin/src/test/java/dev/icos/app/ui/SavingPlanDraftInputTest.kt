package dev.icos.app.ui

import dev.icos.app.data.local.FinanceValidationException
import dev.icos.app.domain.Money
import dev.icos.app.domain.SavingInterval
import dev.icos.app.domain.SavingIntervalUnit
import dev.icos.app.domain.SavingKind
import dev.icos.app.domain.SavingPlanInput
import dev.icos.app.domain.SavingPlanSummary
import dev.icos.app.domain.SavingState
import dev.icos.app.domain.validateSavingPlanDatePair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.UUID

class SavingPlanDraftInputTest {
    private val zero = Money(0, 0, "CNY")

    private fun summary(input: SavingPlanInput) =
        SavingPlanSummary(
            UUID.randomUUID(),
            input,
            SavingState.ACTIVE,
            input.initialSaved,
            input.target.checkedSubtract(input.initialSaved),
            1,
        )

    @Test
    fun weeklyRampWithMixedScalesBuildsAValidInput() {
        val draft = SavingPlanDraft()
        draft.kind = SavingKind.WEEK_52
        draft.name = "  周存  "
        draft.target = "3367"
        draft.firstAmount = "1"
        draft.increment = "2.5"
        draft.startDate = "2026-01-05"

        val input = draft.toValidatedInput()

        assertEquals(Money(3367, 0, "CNY"), input.target)
        assertEquals(Money(1, 0, "CNY"), input.firstAmount)
        assertEquals(Money(25, 1, "CNY"), input.increment)
        assertEquals(SavingInterval(SavingIntervalUnit.WEEK, 1), input.interval)
        assertEquals("2026-01-05", input.startDate)
        assertEquals(zero, input.initialSaved)
        assertNull(input.endDate)
        assertEquals("周存", input.name)
        assertEquals("  周存  ", draft.name)
        draft.name = "a".repeat(100) + " "
        assertEquals("a".repeat(100), draft.toValidatedInput().name)
    }

    @Test
    fun switchingKindsIgnoresHiddenRulesWithoutClearingTheDraft() {
        val draft = SavingPlanDraft()
        draft.name = "切换类型"
        draft.target = "10"
        draft.firstAmount = "not-money"
        draft.increment = "not-money"
        draft.intervalStep = "not-an-integer"
        draft.startDate = "2026-01-05"

        val flexible = draft.toValidatedInput()
        assertNull(flexible.firstAmount)
        assertNull(flexible.increment)
        assertNull(flexible.interval)
        assertEquals("2026-01-05", flexible.startDate)
        assertEquals("not-money", draft.firstAmount)
        assertEquals("2026-01-05", draft.startDate)

        draft.kind = SavingKind.FIXED
        draft.firstAmount = "2"
        draft.intervalStep = "1"
        val fixed = draft.toValidatedInput()
        assertEquals(Money(2, 0, "CNY"), fixed.firstAmount)
        assertNull(fixed.increment)
        assertEquals("2026-01-05", fixed.startDate)
        assertEquals("not-money", draft.increment)
    }

    @Test
    fun newFlexibleDeadlineUsesTheVisibleStartDate() {
        val draft =
            SavingPlanDraft().apply {
                name = "灵活"
                target = "10"
                endDate = "2026-02-01"
            }
        val missing = assertThrows(FinanceValidationException::class.java) { draft.toValidatedInput() }
        assertEquals("设置目标日期前，请先选择开始日期", missing.message)
        draft.startDate = "2026-02-01"
        assertEquals(draft.startDate, draft.toValidatedInput().startDate)
        draft.startDate = "2026-02-02"
        assertThrows(FinanceValidationException::class.java) { draft.toValidatedInput() }
        draft.startDate = ""
        draft.endDate = ""
        assertNull(draft.toValidatedInput().startDate)
        assertNull(draft.toValidatedInput().endDate)
    }

    @Test
    fun legacyEndOnlyMetadataRemainsEditableWithoutChangingTheLockedStart() {
        val legacy =
            SavingPlanInput(
                SavingKind.FLEXIBLE,
                "旧计划",
                null,
                Money(10, 0, "CNY"),
                zero,
                endDate = "2026-02-01",
                allowManualEntries = true,
            )
        val draft =
            SavingPlanDraft(summary(legacy)).apply {
                name = "改名"
                target = "20"
                endDate = "2027-02-01"
                startDate = "2026-01-01"
            }
        val input = draft.toValidatedInput()
        assertEquals("改名", input.name)
        assertEquals(Money(20, 0, "CNY"), input.target)
        assertEquals("2027-02-01", input.endDate)
        assertNull(input.startDate)
        draft.endDate = ""
        assertNull(draft.toValidatedInput().endDate)
        val withoutDates = SavingPlanDraft(summary(legacy.copy(endDate = null))).apply { endDate = "2026-02-01" }
        assertThrows(FinanceValidationException::class.java) { withoutDates.toValidatedInput() }
    }

    @Test
    fun sharedDatePairRejectsMissingStartAndReversedOrderButAllowsEqualDates() {
        validateSavingPlanDatePair(null, null)
        validateSavingPlanDatePair("2026-02-01", null)
        validateSavingPlanDatePair("2026-02-01", "2026-02-01")
        validateSavingPlanDatePair("2026-02-01", "2026-02-02")
        assertThrows(FinanceValidationException::class.java) { validateSavingPlanDatePair(null, "2026-02-01") }
        val reversed =
            assertThrows(FinanceValidationException::class.java) {
                validateSavingPlanDatePair("2026-02-02", "2026-02-01")
            }
        assertEquals("目标日期不能早于开始日期", reversed.message)
    }

    @Test
    fun invalidAmountAndTargetMismatchAreRejected() {
        val badAmount = SavingPlanDraft()
        badAmount.kind = SavingKind.WEEK_52
        badAmount.name = "周存"
        badAmount.target = "1e3"
        badAmount.firstAmount = "1"
        badAmount.increment = "2.5"
        badAmount.startDate = "2026-01-05"
        assertThrows(IllegalArgumentException::class.java) { badAmount.toValidatedInput() }

        val mismatch = SavingPlanDraft()
        mismatch.kind = SavingKind.WEEK_52
        mismatch.name = "周存"
        mismatch.target = "3368"
        mismatch.firstAmount = "1"
        mismatch.increment = "2.5"
        mismatch.startDate = "2026-01-05"
        assertThrows(IllegalArgumentException::class.java) { mismatch.toValidatedInput() }
    }

    @Test
    fun editingKeepsLockedRulesAndTakesTheEditableFieldsFromTheDraft() {
        val original =
            summary(
                SavingPlanInput(
                    SavingKind.FIXED,
                    "每月存",
                    null,
                    Money(600000, 2, "CNY"),
                    Money(10000, 2, "CNY"),
                    firstAmount = Money(50000, 2, "CNY"),
                    interval = SavingInterval(SavingIntervalUnit.MONTH, 3),
                    startDate = "2026-02-01",
                    endDate = "2026-12-01",
                    allowManualEntries = true,
                ),
            )
        val draft = SavingPlanDraft(original)
        draft.name = "改名为教育金"
        draft.target = "7000"
        draft.endDate = "2027-02-01"
        draft.allowManualEntries = false
        draft.firstAmount = "1"
        draft.increment = "9"
        draft.intervalUnit = SavingIntervalUnit.WEEK
        draft.intervalStep = "2"
        draft.startDate = "2020-01-01"

        val input = draft.toValidatedInput()

        assertEquals("改名为教育金", input.name)
        assertEquals(Money(7000, 0, "CNY"), input.target)
        assertEquals("2027-02-01", input.endDate)
        assertEquals(false, input.allowManualEntries)
        assertEquals(original.input.initialSaved, input.initialSaved)
        assertEquals(original.input.firstAmount, input.firstAmount)
        assertNull(input.increment)
        assertEquals(SavingInterval(SavingIntervalUnit.MONTH, 3), input.interval)
        assertEquals("2026-02-01", input.startDate)
    }
}
