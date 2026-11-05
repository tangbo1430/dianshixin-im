package dev.icos.app.ui

import dev.icos.app.data.local.FinanceAccount
import dev.icos.app.data.local.FinanceCategory
import dev.icos.app.data.local.FinanceRecord
import dev.icos.app.data.local.FinanceSnapshot
import dev.icos.app.domain.AccountAmount
import dev.icos.app.domain.Direction
import dev.icos.app.domain.Money
import dev.icos.app.domain.RecordInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

class RecordDefaultsTest {
    private val ledger = UUID.randomUUID()
    private val cny = FinanceAccount(UUID.randomUUID(), "CNY account", "CNY", Money(0, 0, "CNY"), 1, null, true, true)
    private val usd = FinanceAccount(UUID.randomUUID(), "USD account", "USD", Money(0, 0, "USD"), 1, null, true, true)
    private val expense = FinanceCategory(UUID.randomUUID(), "expense", Direction.EXPENSE, ledgerId = ledger)
    private val income = FinanceCategory(UUID.randomUUID(), "income", Direction.INCOME, ledgerId = ledger)
    private val snapshot =
        FinanceSnapshot(
            UUID.randomUUID(),
            UUID.randomUUID(),
            ledger,
            listOf(cny, usd),
            listOf(
                income.copy(id = UUID.randomUUID(), archivedAt = 1),
                income.copy(id = UUID.randomUUID(), ledgerId = UUID.randomUUID()),
                expense,
                income,
            ),
            emptyList(),
            emptyList(),
        )

    @Test
    fun preferredAccountAndKindOpenAPristineDraftWithTheCorrectCurrencyAndCategory() {
        val draft = RecordDraft(snapshot, defaultKind = EntryKind.INCOME, defaultAccountId = usd.id)
        assertEquals(EntryKind.INCOME, draft.kind)
        assertEquals(usd.id, draft.accountId)
        assertEquals("USD", draft.currency)
        assertEquals(income.id, draft.categoryId)
        assertEquals("", draft.amount)
        assertFalse(draft.hasChanges)
        assertNull(RecordDraft(snapshot.copy(categories = emptyList()), defaultKind = EntryKind.INCOME).categoryId)
    }

    @Test
    fun unavailableOrOtherLedgerAccountsFallBackWithoutChangingThePreferredId() {
        val allowed = setOf(cny.id, usd.id)
        val cases =
            listOf(
                Triple(snapshot, UUID.randomUUID(), allowed),
                Triple(snapshot.copy(accounts = listOf(cny, usd.copy(archivedAt = 1))), usd.id, allowed),
                Triple(snapshot.copy(accounts = listOf(cny, usd.copy(selectable = false))), usd.id, allowed),
                Triple(snapshot, usd.id, setOf(cny.id)),
            )
        cases.forEach { (current, preferred, allowedIds) ->
            val draft = RecordDraft(current, allowedAccountIds = allowedIds, defaultAccountId = preferred)
            assertEquals(cny.id, draft.accountId)
            assertEquals("CNY", draft.currency)
            assertEquals(expense.id, draft.categoryId)
            assertFalse(draft.hasChanges)
        }
    }

    @Test
    fun transferUsesThePreferredSourceAndADifferentAvailableDestination() {
        val draft = RecordDraft(snapshot, defaultKind = EntryKind.TRANSFER, defaultAccountId = usd.id)
        assertEquals(EntryKind.TRANSFER, draft.kind)
        assertEquals(usd.id, draft.fromId)
        assertEquals(cny.id, draft.toId)
        assertEquals("USD", draft.currency)
        assertNull(draft.categoryId)
        val single = RecordDraft(snapshot.copy(accounts = listOf(usd)), defaultKind = EntryKind.TRANSFER, defaultAccountId = usd.id)
        assertEquals(usd.id, single.fromId)
        assertNull(single.toId)
        assertFalse(draft.hasChanges)
    }

    @Test
    fun categoryPickerKeepsTheCurrentLedgerDirectionAndActiveParentScope() {
        val parent = expense.copy(name = "餐饮")
        val child = expense.copy(id = UUID.randomUUID(), name = "早餐", parentId = parent.id)
        val archived = parent.copy(id = UUID.randomUUID(), archivedAt = 1)
        val hiddenChild = child.copy(id = UUID.randomUUID(), parentId = archived.id)
        val current = snapshot.copy(categories = snapshot.categories.filter { it.id != expense.id } + listOf(parent, child, archived, hiddenChild))
        assertEquals(setOf(parent.id, child.id), recordCategoryChoices(current, Direction.EXPENSE).map { it.id }.toSet())
        assertEquals(listOf(income.id), recordCategoryChoices(current, Direction.INCOME).map { it.id })
    }

    @Test
    fun categorySuggestionsKeepExistingAndArchivedRootsWithoutDuplicateCreation() {
        val categories = listOf(
            expense.copy(name = "餐饮"),
            expense.copy(name = "交通", archivedAt = 1),
            expense.copy(name = "购物", parentId = expense.id),
            income.copy(name = "住房"),
        )
        val names = recordCategorySuggestions(Direction.EXPENSE, categories).map { it.name }
        assertFalse("餐饮" in names)
        assertFalse("交通" in names)
        org.junit.Assert.assertTrue("购物" in names && "住房" in names)
        assertEquals(names.size, names.distinct().size)
        org.junit.Assert.assertTrue(recordCategorySuggestions(Direction.INCOME, categories).any { it.name == "工资" })
    }

    @Test
    fun existingAndCopiedRecordsKeepTheirOriginalTypeCurrencyAndAmount() {
        val amount = Money(200, 0, "CNY")
        val record =
            FinanceRecord(
                UUID.randomUUID(),
                RecordInput.Income(amount, listOf(AccountAmount(cny.id, amount)), true),
                1,
                "UTC",
                "saved",
                income.id,
                7,
            )
        listOf(false, true).forEach { copy ->
            val draft =
                RecordDraft(
                    snapshot,
                    record,
                    copy = copy,
                    defaultKind = EntryKind.TRANSFER,
                    defaultAccountId = usd.id,
                )
            assertEquals(EntryKind.INCOME, draft.kind)
            assertEquals("CNY", draft.currency)
            assertEquals("200", draft.amount)
            assertEquals(cny.id, draft.accountId)
            assertEquals(income.id, draft.categoryId)
            assertFalse(draft.hasChanges)
        }
    }
}
