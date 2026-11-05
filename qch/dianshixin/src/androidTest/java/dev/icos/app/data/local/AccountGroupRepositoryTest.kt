package dev.icos.app.data.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.icos.app.domain.Money
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AccountGroupRepositoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private inline fun withDataset(block: (UUID, UUID, LocalFinanceRepository) -> Unit) {
        val owner = UUID.randomUUID()
        val epoch = UUID.randomUUID()
        val directory = EncryptedDatasetStore.directory(context, owner, epoch)
        try {
            LocalFinanceRepository.openDataset(context, owner, epoch).use { block(owner, epoch, it) }
        } finally {
            KeyStore.getInstance("AndroidKeyStore").apply {
                load(null)
                deleteEntry(EncryptedDatasetStore.alias(owner, epoch))
            }
            directory.deleteRecursively()
            directory.parentFile?.delete()
        }
    }

    @Test
    fun relatedPersonMigration17To18PreservesAccountFactsAndRejectsWrongIdentity() {
        val owner = UUID.randomUUID()
        val epoch = UUID.randomUUID()
        val directory = EncryptedDatasetStore.directory(context, owner, epoch)
        val at = 1768449600000L
        try {
            lateinit var accountId: UUID
            lateinit var before: FinanceSnapshot
            LocalFinanceRepository.openDataset(context, owner, epoch).use { repo ->
                accountId = repo.createAccount("亲情迁移账户", "CNY", Money(1250, 2, "CNY"), at)
                before = repo.snapshot()
            }
            EncryptedDatasetStore.open(context, owner, epoch).use { dataset ->
                val db = dataset.openHelper.writableDatabase
                db.execSQL("DROP TABLE `related_person_profile`")
                assertThrows(IllegalStateException::class.java) {
                    relatedPersonMigration17To18(UUID.randomUUID().toString(), epoch.toString()).migrate(db)
                }
                assertEquals(0L, db.query("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='related_person_profile'").use { it.moveToFirst(); it.getLong(0) })
                assertThrows(IllegalStateException::class.java) {
                    relatedPersonMigration17To18(owner.toString(), UUID.randomUUID().toString()).migrate(db)
                }
                assertEquals(0L, db.query("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='related_person_profile'").use { it.moveToFirst(); it.getLong(0) })
                db.execSQL("PRAGMA user_version=17")
            }
            LocalFinanceRepository.openDataset(context, owner, epoch).use { migrated ->
                val after = migrated.snapshot()
                assertEquals(before.ledgerId, after.ledgerId)
                assertEquals(before.accounts.single { it.id == accountId }.balance, after.accounts.single { it.id == accountId }.balance)
                assertEquals(before.accounts.single { it.id == accountId }.localRevision, after.accounts.single { it.id == accountId }.localRevision)
                assertTrue(after.relatedPersonProfiles.isEmpty())
                migrated.saveRelatedPersonProfile(accountId, "GIVEN_TO_RELATIVE", "亲友", Money(300, 2, "CNY"))
            }
            LocalFinanceRepository.openDataset(context, owner, epoch).use { reopened ->
                assertEquals(Money(1250, 2, "CNY"), reopened.snapshot().accounts.single { it.id == accountId }.balance)
                assertEquals(Money(300, 2, "CNY"), reopened.snapshot().relatedPersonProfiles.single().authorizedLimit)
            }
        } finally {
            KeyStore.getInstance("AndroidKeyStore").apply {
                load(null)
                deleteEntry(EncryptedDatasetStore.alias(owner, epoch))
            }
            directory.deleteRecursively()
            directory.parentFile?.delete()
        }
    }

    @Test
    fun createsMovesOrdersAndDeletesGroupsWithoutChangingFinancialFacts() = withDataset { _, _, repo ->
        val at = 1768449600000L
        val default = repo.snapshot().accountGroups.single { it.isDefault }
        val customId = repo.saveAccountGroup(null, "备用", 1)
        val custom = repo.snapshot().accountGroups.single { it.id == customId }
        val first = repo.createAccount("甲", "CNY", Money(100, 0, "CNY"), at, customId)
        val second = repo.createAccount("乙", "CNY", Money(25, 0, "CNY"), at, customId)
        repo.setAccountIncludedInAssets(first, 1, false)
        repo.setAccountArchived(second, 1, true)
        val before = repo.snapshot()
        val originalSums = before.summaries
        assertEquals(2, before.accounts.count { it.groupId == customId })
        assertTrue(before.accounts.first { it.id == second }.archivedAt != null)

        repo.reorderAccountGroups(listOf(customId, default.id), mapOf(customId to custom.localRevision, default.id to default.localRevision))
        val orderedAccounts = repo.snapshot().accounts.filter { it.groupId == customId }.reversed()
        repo.reorderAccounts(customId, orderedAccounts.map { it.id }, orderedAccounts.associate { it.id to it.localRevision })
        val movedSnapshot = repo.snapshot()
        assertEquals(listOf(second, first), movedSnapshot.accounts.filter { it.groupId == customId }.map { it.id })
        assertEquals(originalSums, movedSnapshot.summaries)
        assertEquals(before.accounts.map { it.id to it.balance }, movedSnapshot.accounts.map { it.id to it.balance })

        val activeCustom = movedSnapshot.accountGroups.single { it.id == customId }
        val children = movedSnapshot.accounts.filter { it.groupId == customId }
        repo.deleteAccountGroup(customId, activeCustom.localRevision, children.associate { it.id to it.localRevision })
        val after = repo.snapshot()
        assertEquals(0, after.accounts.count { it.groupId == customId })
        assertEquals(setOf(first, second), after.accounts.filter { it.groupId == default.id }.map { it.id }.toSet())
        assertTrue(after.accounts.first { it.id == first }.archivedAt == null)
        assertTrue(after.accounts.first { it.id == second }.archivedAt != null)
        assertFalse(after.accounts.first { it.id == first }.includeInAssets)
        assertEquals(originalSums, after.summaries)
        assertThrows(FinanceValidationException::class.java) {
            repo.deleteAccountGroup(default.id, after.accountGroups.single { it.id == default.id }.localRevision, emptyMap())
        }
    }

    @Test
    fun rejectsIncompleteReorderAndStaleAffectedAccountAtomically() = withDataset { owner, epoch, repo ->
        val at = 1768449600000L
        val default = repo.snapshot().accountGroups.single { it.isDefault }
        val groupId = repo.saveAccountGroup(null, "备用", 1)
        val account = repo.createAccount("账户", "CNY", Money(5, 0, "CNY"), at, groupId)
        val snapshot = repo.snapshot()
        val group = snapshot.accountGroups.single { it.id == groupId }
        val original = snapshot
        assertThrows(FinanceValidationException::class.java) {
            repo.reorderAccounts(groupId, emptyList(), emptyMap())
        }
        assertEquals(original, repo.snapshot())
        EncryptedDatasetStore.open(context, owner, epoch).use { db ->
            val journalCount = db.finance().mutations().size
            val revisions = mapOf(account to snapshot.accounts.single { it.id == account }.localRevision)
            assertThrows(FinanceValidationException::class.java) {
                repo.reorderAccounts(groupId, listOf(account, account), revisions)
            }
            assertThrows(FinanceValidationException::class.java) {
                repo.reorderAccounts(groupId, listOf(UUID.randomUUID()), revisions)
            }
            assertEquals(journalCount, db.finance().mutations().size)
        }
        assertEquals(original, repo.snapshot())
        EncryptedDatasetStore.open(context, owner, epoch).use { db ->
            val sql = db.openHelper.writableDatabase
            val revisions = mapOf(account to snapshot.accounts.single { it.id == account }.localRevision)
            val journalCount = db.finance().mutations().size
            for ((column, foreignValue) in listOf("ownerId" to "foreign-owner", "epochId" to "foreign-epoch")) {
                sql.execSQL("UPDATE finance_account SET `$column`=? WHERE id=?", arrayOf(foreignValue, account.toString()))
                assertThrows(FinanceValidationException::class.java) {
                    repo.reorderAccounts(groupId, listOf(account), revisions)
                }
                val preserved = db.finance().accounts().single { it.id == account.toString() }
                assertEquals(foreignValue, if (column == "ownerId") preserved.ownerId else preserved.epochId)
                assertEquals(journalCount, db.finance().mutations().size)
                sql.execSQL("UPDATE finance_account SET `$column`=? WHERE id=?", arrayOf(if (column == "ownerId") owner.toString() else epoch.toString(), account.toString()))
            }
        }
        assertEquals(original, repo.snapshot())
        assertThrows(FinanceValidationException::class.java) {
            repo.deleteAccountGroup(groupId, group.localRevision, mapOf(account to 99L))
        }
        assertEquals(original, repo.snapshot())
        assertThrows(FinanceValidationException::class.java) {
            repo.moveAccount(account, UUID.randomUUID(), 1)
        }
        assertEquals(default.id, repo.snapshot().accountGroups.single { it.isDefault }.id)
    }

    @Test
    fun aLegacyTagFinanceGroupCannotBeEditedAsAnAccountGroup() {
        val owner = UUID.randomUUID()
        val epoch = UUID.randomUUID()
        val directory = EncryptedDatasetStore.directory(context, owner, epoch)
        try {
            lateinit var legacyTagId: UUID
            LocalFinanceRepository.openDataset(context, owner, epoch).use { repo ->
                EncryptedDatasetStore.open(context, owner, epoch).use { db ->
                    legacyTagId = UUID.fromString(db.finance().groups().single { it.kind == "TAG" }.id)
                }
            }
            LocalFinanceRepository.openDataset(context, owner, epoch).use { repo ->
                val before = repo.snapshot()
                EncryptedDatasetStore.open(context, owner, epoch).use { db ->
                    val journalCount = db.finance().mutations().size
                    assertThrows(FinanceValidationException::class.java) {
                        repo.saveAccountGroup(legacyTagId, "错误修改", 0, 1)
                    }
                    assertEquals(journalCount, db.finance().mutations().size)
                }
                assertEquals(before, repo.snapshot())
            }
        } finally {
            KeyStore.getInstance("AndroidKeyStore").apply {
                load(null)
                deleteEntry(EncryptedDatasetStore.alias(owner, epoch))
            }
            directory.deleteRecursively()
            directory.parentFile?.delete()
        }
    }

    @Test
    fun migration9To10AddsDefaultAndOrderWithoutChangingAccountFacts() {
        val owner = UUID.randomUUID()
        val epoch = UUID.randomUUID()
        val directory = EncryptedDatasetStore.directory(context, owner, epoch)
        val at = 1768449600000L
        try {
            lateinit var accountId: UUID
            lateinit var before: FinanceSnapshot
            LocalFinanceRepository.openDataset(context, owner, epoch).use { repo ->
                accountId = repo.createAccount("迁移账户", "CNY", Money(35, 0, "CNY"), at)
                repo.setAccountIncludedInAssets(accountId, 1, false)
                repo.setAccountArchived(accountId, 2, true)
                before = repo.snapshot()
            }
            EncryptedDatasetStore.open(context, owner, epoch).use { db ->
                val sql = db.openHelper.writableDatabase
                sql.execSQL("ALTER TABLE `finance_group` DROP COLUMN `sortOrder`")
                sql.execSQL("ALTER TABLE `finance_group` DROP COLUMN `isDefault`")
                sql.execSQL("ALTER TABLE `finance_account` DROP COLUMN `sortOrder`")
                sql.execSQL("PRAGMA user_version = 9")
            }
            LocalFinanceRepository.openDataset(context, owner, epoch).use { migrated ->
                val after = migrated.snapshot()
                assertEquals(before.ledgerId, after.ledgerId)
                assertEquals(before.accounts.single { it.id == accountId }.balance, after.accounts.single { it.id == accountId }.balance)
                assertEquals(before.accounts.single { it.id == accountId }.localRevision, after.accounts.single { it.id == accountId }.localRevision)
                assertEquals(before.accounts.single { it.id == accountId }.groupId, after.accounts.single { it.id == accountId }.groupId)
                assertEquals(before.accounts.single { it.id == accountId }.includeInAssets, after.accounts.single { it.id == accountId }.includeInAssets)
                assertEquals(before.accounts.single { it.id == accountId }.selectable, after.accounts.single { it.id == accountId }.selectable)
                assertEquals(before.accounts.single { it.id == accountId }.archivedAt, after.accounts.single { it.id == accountId }.archivedAt)
                assertEquals(1, after.accountGroups.count { it.isDefault })
                assertEquals(0L, after.accounts.single { it.id == accountId }.sortOrder)
            }
        } finally {
            KeyStore.getInstance("AndroidKeyStore").apply {
                load(null)
                deleteEntry(EncryptedDatasetStore.alias(owner, epoch))
            }
            directory.deleteRecursively()
            directory.parentFile?.delete()
        }
    }

    @Test
    fun emptyRoom9FinanceShapeMigratesThenInitializesExactlyOnce() {
        val owner = UUID.randomUUID()
        val epoch = UUID.randomUUID()
        val directory = EncryptedDatasetStore.directory(context, owner, epoch)
        try {
            LocalFinanceRepository.openDataset(context, owner, epoch).close()
            EncryptedDatasetStore.open(context, owner, epoch).use { db ->
                val sql = db.openHelper.writableDatabase
                sql.execSQL("PRAGMA foreign_keys=OFF")
                listOf(
                    "finance_posting",
                    "finance_statistic",
                    "finance_allocation",
                    "finance_budget",
                    "finance_event",
                    "finance_record",
                    "finance_account",
                    "finance_category",
                    "finance_group",
                    "finance_ledger",
                    "finance_mutation",
                ).forEach { sql.execSQL("DELETE FROM `$it`") }
                sql.execSQL("ALTER TABLE `finance_group` DROP COLUMN `sortOrder`")
                sql.execSQL("ALTER TABLE `finance_group` DROP COLUMN `isDefault`")
                sql.execSQL("ALTER TABLE `finance_account` DROP COLUMN `sortOrder`")
                sql.execSQL("PRAGMA user_version=9")
            }

            repeat(2) {
                LocalFinanceRepository.openDataset(context, owner, epoch).use { repo ->
                    val snapshot = repo.snapshot()
                    assertEquals(1, snapshot.accountGroups.size)
                    assertEquals(1, snapshot.accountGroups.count { it.isDefault })
                    assertTrue(snapshot.accounts.isEmpty())
                    assertTrue(snapshot.records.isEmpty())
                    assertTrue(snapshot.statisticEntries.isEmpty())
                    EncryptedDatasetStore.open(context, owner, epoch).use { db ->
                        assertEquals(1, db.finance().ledgers().size)
                        assertEquals(1, db.finance().groups().count { it.kind == "TAG" })
                        assertEquals(1, db.finance().mutations().count { it.kind == "DATASET_INITIALIZE" })
                        assertTrue(db.finance().budgets().isEmpty())
                        assertTrue(db.finance().postings().isEmpty())
                    }
                }
            }
        } finally {
            KeyStore.getInstance("AndroidKeyStore").apply {
                load(null)
                deleteEntry(EncryptedDatasetStore.alias(owner, epoch))
            }
            directory.deleteRecursively()
            directory.parentFile?.delete()
        }
    }

    @Test
    fun emptyGroupMigrationRejectsResidualFinanceRowsBeforeSchemaWrites() = withDataset { owner, epoch, _ ->
        val cases = listOf(
            Triple("finance_category", "id", "INSERT INTO finance_category VALUES ('residual', '$owner', '$epoch', 'missing-ledger', '残留', 'EXPENSE', 'EXPENSE', 1, 1, 1, NULL)"),
            Triple("finance_record", "id", "INSERT INTO finance_record VALUES ('residual', '$owner', '$epoch', 1, 1, 1, NULL, 'NONE')"),
            Triple("finance_event", "id", "INSERT INTO finance_event VALUES ('residual', '$owner', '$epoch', 'missing-ledger', NULL, 1, 'EXPENSE', 1, 'UTC', '1970-01-01', '', NULL, 1, 1, 1, 0, 'CNY', NULL, NULL, NULL, NULL, 1, 'LOCAL')"),
            Triple("finance_mutation", "opId", "INSERT INTO finance_mutation (opId, ownerId, epochId, aggregateId, localRevision, baseLocalRevision, intentVersion, kind, payload, state, createdAt) VALUES ('residual', '$owner', '$epoch', 'residual', 1, 0, 1, 'TEST', '{}', 'PENDING', 1)"),
        )
        EncryptedDatasetStore.open(context, owner, epoch).use { dataset ->
            val db = dataset.openHelper.writableDatabase
            cases.forEachIndexed { index, (table, keyColumn, insertSql) ->
                db.execSQL("PRAGMA foreign_keys=OFF")
                listOf(
                    "finance_posting", "finance_statistic", "finance_allocation", "finance_budget",
                    "finance_event", "finance_record", "finance_account", "finance_category",
                    "finance_group", "finance_ledger", "finance_mutation",
                ).forEach { db.execSQL("DELETE FROM `$it`") }
                db.execSQL(insertSql)
                db.execSQL("ALTER TABLE `finance_group` DROP COLUMN `sortOrder`")
                db.execSQL("ALTER TABLE `finance_group` DROP COLUMN `isDefault`")
                db.execSQL("ALTER TABLE `finance_account` DROP COLUMN `sortOrder`")
                val beforeCount = db.query("SELECT COUNT(*) FROM `$table`").use { it.moveToFirst(); it.getLong(0) }
                val beforeJournalCount = db.query("SELECT COUNT(*) FROM finance_mutation").use { it.moveToFirst(); it.getLong(0) }
                assertEquals(1L, beforeCount)

                assertThrows(IllegalStateException::class.java) {
                    accountGroupsMigration9To10(owner.toString(), UUID.randomUUID().toString()).migrate(db)
                }
                assertEquals(beforeCount, db.query("SELECT COUNT(*) FROM `$table` WHERE `$keyColumn`='residual'").use { it.moveToFirst(); it.getLong(0) })
                assertThrows(IllegalStateException::class.java) {
                    accountGroupsMigration9To10(owner.toString(), epoch.toString()).migrate(db)
                }

                val afterCount = db.query("SELECT COUNT(*) FROM `$table`").use { it.moveToFirst(); it.getLong(0) }
                assertEquals(beforeCount, afterCount)
                assertEquals(beforeCount, db.query("SELECT COUNT(*) FROM `$table` WHERE `$keyColumn`='residual'").use { it.moveToFirst(); it.getLong(0) })
                assertEquals(beforeJournalCount, db.query("SELECT COUNT(*) FROM finance_mutation").use { it.moveToFirst(); it.getLong(0) })
                for (altered in listOf("finance_group" to "sortOrder", "finance_group" to "isDefault", "finance_account" to "sortOrder")) {
                    val hasColumn = db.query("PRAGMA table_info(`${altered.first}`)").use { cursor ->
                        var found = false
                        while (cursor.moveToNext()) if (cursor.getString(1) == altered.second) found = true
                        found
                    }
                    assertFalse(hasColumn)
                }
                assertEquals(0, dataset.finance().groups().size)
                assertEquals(0, dataset.finance().ledgers().size)
                assertEquals(0, dataset.finance().accounts().size)
                if (index < cases.lastIndex) {
                    db.execSQL("ALTER TABLE `finance_group` ADD COLUMN `sortOrder` INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("ALTER TABLE `finance_group` ADD COLUMN `isDefault` INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("ALTER TABLE `finance_account` ADD COLUMN `sortOrder` INTEGER NOT NULL DEFAULT 0")
                }
            }
        }
    }
}
