package dev.icos.app.data.local

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class AccountMetadataChangesTest {
    private val accountId = uuid(1)
    private val groupId = uuid(2)
    private val ledgerOne = uuid(3)
    private val ledgerTwo = uuid(4)

    private fun uuid(value: Long) = UUID(0, value).toString()

    private fun account() = JSONObject()
        .put("id", accountId).put("serverRevision", 7).put("deletedAt", JSONObject.NULL)
        .put("type", "LIABILITY").put("currency", "CNY").put("name", "Card")
        .put("groupId", groupId).put("iconRef", JSONObject.NULL).put("sortOrder", 9)
        .put("hiddenAt", JSONObject.NULL).put("archivedAt", JSONObject.NULL)
        .put("includeInAssets", 1).put("selectable", 1).put("visibilityMode", "SELECTED")
        .put("openingBalance", JSONObject().put("unscaled", "98765").put("scale", 2).put("currency", "CNY"))
        .put("openingDate", "2025-02-03").put("futureUnknownField", "do not copy")

    private fun row(id: String, revision: Long, archivedAt: Any = JSONObject.NULL) = JSONObject()
        .put("id", id).put("serverRevision", revision).put("deletedAt", JSONObject.NULL).put("archivedAt", archivedAt)

    private fun tables() = JSONObject()
        .put("account_groups", JSONArray().put(row(groupId, 11)))
        .put("ledgers", JSONArray()
            .put(row(ledgerOne, 21).put("baseCurrency", "CNY"))
            .put(row(ledgerTwo, 22).put("baseCurrency", "CNY")))
        .put("account_ledger_visibility", JSONArray()
            .put(row(uuid(10), 1).put("accountId", accountId).put("ledgerId", ledgerOne))
            .put(row(uuid(11), 1).put("accountId", accountId).put("ledgerId", ledgerTwo)))

    @Test
    fun preservesNumericBooleansAndSelectedLedgerCasVersions() {
        val (changes, versions) = accountMetadataChanges(tables(), account(), includeInAssets = null, selectable = false)

        assertEquals(true, changes.canonicalBoolean("includeInAssets"))
        assertEquals(false, changes.canonicalBoolean("selectable"))
        assertEquals("SELECTED", changes.getString("visibilityMode"))
        assertEquals(listOf(ledgerOne, ledgerTwo),
            (0 until changes.getJSONArray("ledgerIds").length()).map { changes.getJSONArray("ledgerIds").getString(it) })
        assertEquals(7L, versions.getLong("account:$accountId"))
        assertEquals(11L, versions.getLong("account_group:$groupId"))
        assertEquals(21L, versions.getLong("ledger:$ledgerOne"))
        assertEquals(22L, versions.getLong("ledger:$ledgerTwo"))
    }

    @Test
    fun nullOnlyFieldsAreExplicitAndOpeningOrUnknownFieldsAreNotCopied() {
        val (changes, _) = accountMetadataChanges(tables(), account(), includeInAssets = false, selectable = null)

        assertEquals(false, changes.getBoolean("includeInAssets"))
        assertTrue(changes.isNull("iconRef"))
        assertTrue(changes.isNull("openingBalance"))
        assertTrue(changes.isNull("openingDate"))
        assertFalse(changes.has("futureUnknownField"))
        assertEquals(14, changes.length())
    }

    @Test
    fun editingNameAndGroupPreservesFinancialFieldsAndUsesTargetGroupVersion() {
        val targetGroup = uuid(20)
        val tables = tables().also { it.getJSONArray("account_groups").put(row(targetGroup, 31)) }
        val (changes, versions) = accountMetadataChanges(tables, account(), null, null, "  新账户名  ", UUID.fromString(targetGroup))

        assertEquals("新账户名", changes.getString("name"))
        assertEquals(targetGroup, changes.getString("groupId"))
        assertEquals(31L, versions.getLong("account_group:$targetGroup"))
        assertFalse(versions.has("account_group:$groupId"))
        assertEquals("LIABILITY", changes.getString("type"))
        assertEquals("CNY", changes.getString("currency"))
        assertEquals(9L, changes.getLong("sortOrder"))
        assertTrue(changes.getBoolean("selectable"))
        assertTrue(changes.getBoolean("includeInAssets"))
        assertEquals(2, changes.getJSONArray("ledgerIds").length())
        assertTrue(changes.isNull("openingBalance"))
        assertTrue(runCatching {
            accountMetadataChanges(tables, account(), null, null, " ", UUID.fromString(targetGroup))
        }.isFailure)
    }

    @Test
    fun visibilityUpdateKeepsOnlySelectedLedgerDependenciesAndPreservesOtherMetadata() {
        val (selected, selectedVersions) = accountMetadataChanges(tables(), account(), null, null,
            visibilityMode = "SELECTED", visibleLedgerIds = setOf(UUID.fromString(ledgerTwo)))
        assertEquals("SELECTED", selected.getString("visibilityMode"))
        assertEquals(1, selected.getJSONArray("ledgerIds").length())
        assertEquals(ledgerTwo, selected.getJSONArray("ledgerIds").getString(0))
        assertEquals(22L, selectedVersions.getLong("ledger:$ledgerTwo"))
        assertFalse(selectedVersions.has("ledger:$ledgerOne"))
        assertEquals("Card", selected.getString("name"))
        assertTrue(selected.getBoolean("selectable"))
        val (all, allVersions) = accountMetadataChanges(tables(), account(), null, null,
            visibilityMode = "ALL", visibleLedgerIds = emptySet())
        assertEquals(0, all.getJSONArray("ledgerIds").length())
        assertEquals(2, allVersions.length())
        assertTrue(runCatching {
            accountMetadataChanges(tables(), account(), null, null,
                visibilityMode = "SELECTED", visibleLedgerIds = setOf(UUID(0, 999)))
        }.isFailure)
    }

    @Test
    fun rejectsArchivedSelectableAndInvalidVisibility() {
        val archived = account().put("archivedAt", "2026-10-01T00:00:00Z")
        assertTrue(runCatching {
            accountMetadataChanges(tables(), archived, includeInAssets = null, selectable = true)
        }.isFailure)

        val invalidVisibility = account().put("visibilityMode", "UNKNOWN")
        assertTrue(runCatching {
            accountMetadataChanges(tables(), invalidVisibility, includeInAssets = null, selectable = false)
        }.isFailure)
    }
}
