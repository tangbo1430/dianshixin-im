package dev.icos.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorDraftChangesTest {
    @Test
    fun selectingACardPrefillsWithoutMarkingAnUntouchedFormAsEdited() {
        val target =
            dev.icos.app.domain
                .Money(1000, 0, "CNY")
        val zero =
            dev.icos.app.domain
                .Money(0, 0, "CNY")
        val plan =
            dev.icos.app.domain.SavingPlanSummary(
                java.util.UUID.randomUUID(),
                dev.icos.app.domain.SavingPlanInput(
                    dev.icos.app.domain.SavingKind.FLEXIBLE,
                    "旅行",
                    null,
                    target,
                    zero,
                    allowManualEntries = true,
                ),
                dev.icos.app.domain.SavingState.ACTIVE,
                zero,
                target,
                1,
            )
        val draft =
            SavingEntryDraft(
                plan,
                false,
                8,
                dev.icos.app.domain
                    .Money(200, 0, "CNY"),
            )
        org.junit.Assert.assertEquals("8", draft.cardIndex)
        org.junit.Assert.assertEquals("200", draft.amount)
        assertFalse(draft.hasChanges)
        draft.amount = "300"
        assertTrue(draft.hasChanges)
    }

    @Test
    fun unchangedAndRevertedInputsCanCloseButEditedDraftsNeedConfirmation() {
        val account = AccountDraft(defaultCurrency = "USD")
        assertFalse(account.hasChanges)
        account.name = "旅行账户"
        assertTrue(account.hasChanges)
        account.name = ""
        assertFalse(account.hasChanges)

        val saving = SavingPlanDraft()
        assertFalse(saving.hasChanges)
        saving.target = "1000"
        assertTrue(saving.hasChanges)
        saving.target = ""
        assertFalse(saving.hasChanges)
        saving.endDate = "2027-01-01"
        assertTrue(saving.hasChanges)
    }
}
