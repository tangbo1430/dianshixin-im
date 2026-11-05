package dev.icos.app.data.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.icos.app.domain.AccountAmount
import dev.icos.app.domain.Direction
import dev.icos.app.domain.LoanPlanInput
import dev.icos.app.domain.Money
import dev.icos.app.domain.PeriodRange
import dev.icos.app.domain.RecordInput
import dev.icos.app.domain.RepaymentMethod
import dev.icos.app.domain.SavingEntryInput
import dev.icos.app.domain.SavingEntryKind
import dev.icos.app.domain.SavingKind
import dev.icos.app.domain.SavingLinkedTransfer
import dev.icos.app.domain.SavingPlanInput
import dev.icos.app.domain.SavingTransferStrategy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LocalFinanceRepositoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun money(
        value: Long,
        currency: String = "CNY",
    ) = Money(value, 0, currency)

    @Test
    fun categorySaveKeepsLedgerHierarchyAndRevisionBoundaries() {
        val owner = UUID.randomUUID()
        val epoch = UUID.randomUUID()
        val directory = EncryptedDatasetStore.directory(context, owner, epoch)
        try {
            LocalFinanceRepository.openDataset(context, owner, epoch).use { repo ->
                val parentId = repo.saveCategory(null, null, "同名根分类", Direction.EXPENSE, null)
                val parent = repo.snapshot().categories.single { it.id == parentId }
                val childId = repo.saveCategory(null, null, "子分类", Direction.EXPENSE, parentId)
                assertEquals(parentId, repo.snapshot().allCategories.single { it.id == childId }.parentId)
                val secondLedger = repo.createLedger("另一个账本", "CNY")
                repo.selectLedger(secondLedger)
                assertThrows(FinanceValidationException::class.java) {
                    repo.saveCategory(null, null, "越界子分类", Direction.EXPENSE, parentId)
                }
                val createdId = repo.saveCategory(null, null, "同名根分类", Direction.EXPENSE, null)
                val category = repo.snapshot().categories.single { it.id == createdId }
                assertEquals(secondLedger, category.ledgerId)
                assertThrows(FinanceValidationException::class.java) {
                    repo.saveCategory(null, null, category.name, Direction.EXPENSE, null)
                }
                assertThrows(FinanceValidationException::class.java) {
                    repo.saveCategory(category.id, category.localRevision + 1, "过期改名", Direction.EXPENSE, null)
                }
                assertThrows(FinanceValidationException::class.java) {
                    repo.saveCategory(null, null, "收入排除", Direction.INCOME, null, excludeBudget = true)
                }
                assertEquals(createdId, repo.saveCategory(category.id, category.localRevision, "改名根分类", Direction.EXPENSE, null))
                val snapshot = repo.snapshot()
                val renamed = snapshot.categories.single { it.id == createdId }
                assertEquals("改名根分类", renamed.name)
                assertEquals(category.localRevision + 1, renamed.localRevision)
                assertEquals(listOf(renamed), snapshot.categories.filter { it.id == createdId })
                assertFalse(snapshot.allCategories.any { it.name in setOf("越界子分类", "过期改名", "收入排除") })
                assertEquals(parent.id, snapshot.allCategories.single { it.id == childId }.parentId)
                // Targeted reads return allCategories unchanged and the active ledger's categories without financial facts.
                val read = repo.readCategories()
                assertEquals(snapshot.allCategories, read)
                assertEquals(
                    snapshot.categories.associateBy { it.id },
                    read.filter { it.ledgerId == snapshot.ledgerId && it.archivedAt == null }.associateBy { it.id },
                )
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
    fun guestClearReversesEveryRecordAndCommitsPerRecordRevisionJournal() {
        val owner = UUID.randomUUID()
        val epoch = UUID.randomUUID()
        val directory = EncryptedDatasetStore.directory(context, owner, epoch)
        val at = 1768449600000L
        lateinit var expense: UUID
        lateinit var income: UUID
        try {
            LocalFinanceRepository.openDataset(context, owner, epoch).use { repo ->
                val account = repo.createAccount("清账事务账户", "CNY", money(1000), at)
                expense =
                    repo.saveRecord(
                        null,
                        RecordInput.Expense(money(100), null, listOf(AccountAmount(account, money(100))), true, true),
                        at,
                        "Asia/Shanghai",
                        "清账支出",
                        null,
                    )
                income =
                    repo.saveRecord(
                        null,
                        RecordInput.Income(money(50), listOf(AccountAmount(account, money(50))), true),
                        at,
                        "Asia/Shanghai",
                        "清账收入",
                        null,
                    )
                val preview = repo.clearRecordsPreview()
                assertEquals(2, preview.recordCount)
                assertEquals(2, preview.supportedCount)
                assertTrue(preview.rejectedReasons.isEmpty())
                val cleared = repo.clearRecords(preview.recordRevisions)
                assertTrue(cleared.records.isEmpty())
                assertTrue(
                    cleared.accounts
                        .single()
                        .balance
                        .sameValue(money(1000)),
                )
                assertTrue(
                    cleared.summaries
                        .single()
                        .expense
                        .sameValue(money(0)),
                )
                assertTrue(
                    cleared.summaries
                        .single()
                        .income
                        .sameValue(money(0)),
                )
            }
            EncryptedDatasetStore.open(context, owner, epoch).use { db ->
                val finance = db.finance()
                assertEquals(2, finance.events().count { it.kind == "REVERSAL" })
                val deleted = finance.records().filter { it.id in setOf(expense.toString(), income.toString()) }
                assertEquals(2, deleted.size)
                deleted.forEach { record ->
                    assertEquals(2L, record.localRevision)
                    val journal = finance.mutations().single { it.aggregateId == record.id && it.localRevision == 2L }
                    assertEquals("RECORD_DELETE", journal.kind)
                    assertEquals(1L, journal.baseLocalRevision)
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
    fun guestAccountDetailsUpdatesNameAndGroupAtomicallyWithoutChangingMoneyFacts() {
        val owner = UUID.randomUUID()
        val epoch = UUID.randomUUID()
        val directory = EncryptedDatasetStore.directory(context, owner, epoch)
        val at = 1768449600000L
        try {
            LocalFinanceRepository.openDataset(context, owner, epoch).use { repo ->
                val accountId = repo.createAccount("编辑前账户", "CNY", money(1250), at)
                val targetGroupId = repo.saveAccountGroup(null, "新分组", 1)
                val initial = repo.snapshot()
                val account = initial.allAccounts.single { it.id == accountId }
                val targetGroup = initial.accountGroups.single { it.id == targetGroupId }
                val changed = repo.updateAccountDetails(accountId, account.localRevision, " 编辑后账户 ", targetGroupId, targetGroup.localRevision)
                val updated = changed.allAccounts.single { it.id == accountId }
                assertEquals("编辑后账户", updated.name)
                assertEquals(targetGroupId, updated.groupId)
                assertTrue(updated.balance.sameValue(money(1250)))

                assertThrows(FinanceValidationException::class.java) {
                    repo.updateAccountDetails(accountId, account.localRevision, "不应提交", account.groupId, initial.accountGroups.single { it.id == account.groupId }.localRevision)
                }
                val afterStaleAccount = repo.snapshot().allAccounts.single { it.id == accountId }
                assertEquals("编辑后账户", afterStaleAccount.name)
                assertEquals(targetGroupId, afterStaleAccount.groupId)
                assertThrows(FinanceValidationException::class.java) {
                    repo.updateAccountDetails(accountId, updated.localRevision, "不应提交", targetGroupId, targetGroup.localRevision - 1)
                }
                val afterStaleGroup = repo.snapshot().allAccounts.single { it.id == accountId }
                assertEquals("编辑后账户", afterStaleGroup.name)
                assertEquals(targetGroupId, afterStaleGroup.groupId)
                assertTrue(afterStaleGroup.balance.sameValue(money(1250)))
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
    fun guestClearRejectsLoanDisbursementEventsEvenWhenNoActiveRecordReferencesThem() {
        val owner = UUID.randomUUID()
        val epoch = UUID.randomUUID()
        val directory = EncryptedDatasetStore.directory(context, owner, epoch)
        try {
            LocalFinanceRepository.openDataset(context, owner, epoch).use { repo ->
                val actual = repo.createAccount("贷款入账账户", "CNY", money(0), 1768449600000L)
                val liability = repo.createAccount("贷款负债", "CNY", money(1000), 1768449600000L, accountType = "LIABILITY")
                repo.createLoan(
                    LoanPlanInput(
                        accountId = liability,
                        principal = money(100),
                        annualRateNumerator = 1,
                        annualRateDenominator = 100,
                        termCount = 12,
                        repaymentType = RepaymentMethod.EQUAL_PAYMENT,
                        firstDueDate = "2026-02-15",
                    ),
                    LoanDisbursement(actual, 1768449600000L),
                )
                val failure = assertThrows(FinanceValidationException::class.java) { repo.clearRecordsPreview() }
                assertTrue(failure.message.orEmpty().contains("贷款关联资金事件"))
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
    fun guestLedgersSwitchWithoutMixingRecordsAndCopyCategoryIds() {
        val owner = UUID.randomUUID()
        val epoch = UUID.randomUUID()
        val directory = EncryptedDatasetStore.directory(context, owner, epoch)
        try {
            LocalFinanceRepository.openDataset(context, owner, epoch).use { repo ->
                val first = repo.snapshot()
                assertEquals(1, first.ledgers.size)
                assertEquals(first.ledgerId, first.ledgers.single { it.isDefault }.id)
                val firstAccount = repo.createAccount("第一账本账户", "CNY", money(1000), 1768449600000L)
                repo.saveRecord(
                    null,
                    RecordInput.Expense(money(120), null, listOf(AccountAmount(firstAccount, money(120))), true, true),
                    1768449600000L,
                    "Asia/Shanghai",
                    "第一账本记录",
                    null,
                )
                val copiedMap = repo.copyLedger(first.ledgerId, "第一账本副本")
                assertEquals(first.categories.map { it.id }.toSet(), copiedMap.keys)
                assertEquals(first.categories.size, copiedMap.values.toSet().size)
                val second = repo.availableLedgers().single { it.id != first.ledgerId }
                repo.selectLedger(second.id)
                val secondView = repo.snapshot()
                assertEquals(second.id, secondView.ledgerId)
                assertTrue(secondView.records.isEmpty())
                assertTrue(secondView.categories.all { it.id in copiedMap.values })
                repo.selectLedger(first.ledgerId)
                assertEquals(
                    "第一账本记录",
                    repo
                        .snapshot()
                        .records
                        .single()
                        .remark,
                )
                assertTrue(
                    repo
                        .snapshot()
                        .ledgers
                        .single { it.id == first.ledgerId }
                        .isDefault,
                )
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
    fun room15To16PreservesIdentityAndExistingFinancialFacts() {
        val owner = UUID.randomUUID()
        val epoch = UUID.randomUUID()
        val directory = EncryptedDatasetStore.directory(context, owner, epoch)
        try {
            lateinit var before: FinanceSnapshot
            lateinit var identity: DatasetIdentity
            LocalFinanceRepository.openDataset(context, owner, epoch).use { repo ->
                val account = repo.createAccount("迁移账户", "CNY", money(500), 1768449600000L)
                repo.saveRecord(
                    null,
                    RecordInput.Expense(money(25), null, listOf(AccountAmount(account, money(25))), true, true),
                    1768449601000L,
                    "Asia/Shanghai",
                    "迁移账单",
                    null,
                )
                before = repo.snapshot()
            }
            EncryptedDatasetStore.open(context, owner, epoch).use { db ->
                identity = db.identity().read()!!
                val sql = db.openHelper.writableDatabase
                sql.execSQL("DROP INDEX IF EXISTS `index_finance_category_ledgerId_purpose`")
                sql.execSQL("CREATE UNIQUE INDEX `index_finance_category_ledgerId_purpose` ON `finance_category` (`ledgerId`, `purpose`)")
                sql.execSQL("ALTER TABLE `finance_category` DROP COLUMN `excludeBudget`")
                sql.execSQL("ALTER TABLE `finance_category` DROP COLUMN `archivedAt`")
                sql.execSQL("ALTER TABLE `finance_category` DROP COLUMN `parentId`")
                sql.execSQL("ALTER TABLE `finance_category` DROP COLUMN `sortOrder`")
                sql.execSQL("ALTER TABLE `finance_ledger` DROP COLUMN `archivedAt`")
                sql.execSQL("ALTER TABLE `finance_ledger` DROP COLUMN `isDefault`")
                sql.execSQL("ALTER TABLE `finance_ledger` DROP COLUMN `sortOrder`")
                sql.execSQL("PRAGMA user_version = 15")
            }
            LocalFinanceRepository.openDataset(context, owner, epoch).use { migrated ->
                val after = migrated.snapshot()
                EncryptedDatasetStore.open(context, owner, epoch).use { db -> assertEquals(identity, db.identity().read()) }
                assertEquals(before.ownerId, after.ownerId)
                assertEquals(before.epochId, after.epochId)
                assertEquals(before.ledgerId, after.ledgerId)
                assertEquals(before.records, after.records)
                assertEquals(before.summaries, after.summaries)
                EncryptedDatasetStore.open(context, owner, epoch).use { db -> assertEquals(identity, db.identity().read()) }
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
    fun localSavingProgressIsExactAndUnlinkedEntriesDoNotChangeFinance() {
        val owner = UUID.randomUUID()
        val epoch = UUID.randomUUID()
        val directory = EncryptedDatasetStore.directory(context, owner, epoch)
        try {
            LocalFinanceRepository.openDataset(context, owner, epoch).use { repo ->
                val account = repo.createAccount("存钱验证账户", "CNY", money(500), 1768449600000L)
                val before = repo.snapshot()
                val plan =
                    repo.createSaving(
                        SavingPlanInput(
                            kind = SavingKind.FLEXIBLE,
                            name = "旅行",
                            iconRef = null,
                            target = money(100),
                            initialSaved = money(25),
                            allowManualEntries = true,
                        ),
                    )
                val created = repo.snapshot().savingPlans.single { it.id == plan }
                val deposit =
                    repo.addSavingEntry(
                        plan,
                        created.localRevision,
                        SavingEntryInput(SavingEntryKind.DEPOSIT, money(40), 1768449600000L),
                    )
                val afterDeposit = repo.snapshot()
                assertEquals(money(65), afterDeposit.savingPlans.single { it.id == plan }.saved)
                assertEquals(before.accounts.single { it.id == account }.balance, afterDeposit.accounts.single { it.id == account }.balance)
                assertEquals(before.summaries, afterDeposit.summaries)
                val current = afterDeposit.savingPlans.single { it.id == plan }
                val withdrawal =
                    repo.addSavingEntry(
                        plan,
                        current.localRevision,
                        SavingEntryInput(SavingEntryKind.WITHDRAWAL, money(80), 1768449601000L),
                    )
                val negative = repo.snapshot()
                assertEquals(money(-15), negative.savingPlans.single { it.id == plan }.saved)
                assertEquals(money(115), negative.savingPlans.single { it.id == plan }.remaining)
                repo.cancelSavingEntry(
                    plan,
                    withdrawal,
                    negative.savingPlans.single { it.id == plan }.localRevision,
                    negative.savingEntries.single { it.id == withdrawal }.localRevision,
                )
                assertEquals(
                    money(65),
                    repo
                        .snapshot()
                        .savingPlans
                        .single { it.id == plan }
                        .saved,
                )
                assertNotNull(
                    repo
                        .snapshot()
                        .savingEntries
                        .single { it.id == withdrawal }
                        .cancelledAt,
                )
                assertNull(
                    repo
                        .snapshot()
                        .savingEntries
                        .single { it.id == deposit }
                        .cancelledAt,
                )
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
    fun linkedTransferCancelDeleteStrategyAndRecordGuardShareTransactions() {
        val owner = UUID.randomUUID()
        val epoch = UUID.randomUUID()
        val directory = EncryptedDatasetStore.directory(context, owner, epoch)
        try {
            LocalFinanceRepository.openDataset(context, owner, epoch).use { repo ->
                val source = repo.createAccount("转出账户", "CNY", money(100), 1768449600000L)
                val destination = repo.createAccount("存钱账户", "CNY", money(0), 1768449600000L)
                val planId =
                    repo.createSaving(
                        SavingPlanInput(SavingKind.FLEXIBLE, "转账存钱", null, money(100), money(0), allowManualEntries = true),
                    )
                var plan = repo.snapshot().savingPlans.single { it.id == planId }
                val firstRecord = UUID.randomUUID()
                val firstEntry =
                    repo.addSavingEntry(
                        planId,
                        plan.localRevision,
                        SavingEntryInput(
                            SavingEntryKind.DEPOSIT,
                            money(30),
                            1768449601000L,
                            linkedTransfer =
                                SavingLinkedTransfer(
                                    firstRecord,
                                    RecordInput.Transfer(
                                        AccountAmount(source, money(30)),
                                        AccountAmount(destination, money(30)),
                                        null,
                                        null,
                                    ),
                                    "Asia/Shanghai",
                                ),
                        ),
                    )
                var snapshot = repo.snapshot()
                val sourceLedger = snapshot.ledgerId
                repo.selectLedger(repo.createLedger("另一账本", "CNY"))
                assertEquals(
                    money(30),
                    repo
                        .snapshot()
                        .savingPlans
                        .single { it.id == planId }
                        .saved,
                )
                repo.selectLedger(sourceLedger)
                snapshot = repo.snapshot()
                assertEquals(money(70), snapshot.accounts.single { it.id == source }.balance)
                assertEquals(money(30), snapshot.accounts.single { it.id == destination }.balance)
                assertEquals(1, snapshot.records.count { it.id == firstRecord })
                assertThrows(FinanceValidationException::class.java) {
                    repo.deleteRecord(firstRecord, snapshot.records.single { it.id == firstRecord }.localRevision)
                }
                repo.cancelSavingEntry(
                    planId,
                    firstEntry,
                    snapshot.savingPlans.single { it.id == planId }.localRevision,
                    snapshot.savingEntries.single { it.id == firstEntry }.localRevision,
                )
                snapshot = repo.snapshot()
                assertEquals(money(100), snapshot.accounts.single { it.id == source }.balance)
                assertEquals(money(0), snapshot.accounts.single { it.id == destination }.balance)
                assertTrue(snapshot.records.none { it.id == firstRecord })
                assertEquals(money(0), snapshot.savingPlans.single { it.id == planId }.saved)

                plan = snapshot.savingPlans.single { it.id == planId }
                val secondRecord = UUID.randomUUID()
                repo.addSavingEntry(
                    planId,
                    plan.localRevision,
                    SavingEntryInput(
                        SavingEntryKind.DEPOSIT,
                        money(20),
                        1768449602000L,
                        linkedTransfer =
                            SavingLinkedTransfer(
                                secondRecord,
                                RecordInput.Transfer(AccountAmount(source, money(20)), AccountAmount(destination, money(20)), null, null),
                                "Asia/Shanghai",
                            ),
                    ),
                )
                snapshot = repo.snapshot()
                repo.deleteSaving(
                    planId,
                    snapshot.savingPlans.single { it.id == planId }.localRevision,
                    SavingTransferStrategy.DELETE_TRANSFERS,
                )
                snapshot = repo.snapshot()
                assertEquals(money(100), snapshot.accounts.single { it.id == source }.balance)
                assertEquals(money(0), snapshot.accounts.single { it.id == destination }.balance)
                assertTrue(snapshot.savingEntries.any { it.linkedRecordId == secondRecord })
                assertEquals(
                    "DELETED",
                    snapshot.savingPlans
                        .single { it.id == planId }
                        .state.name,
                )
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
    fun migration10To11PreservesIdentityAndExistingFinancialFacts() {
        val owner = UUID.randomUUID()
        val epoch = UUID.randomUUID()
        val directory = EncryptedDatasetStore.directory(context, owner, epoch)
        try {
            lateinit var before: FinanceSnapshot
            LocalFinanceRepository.openDataset(context, owner, epoch).use { repo ->
                val account = repo.createAccount("迁移保留账户", "CNY", money(275), 1768449600000L)
                repo.saveRecord(
                    null,
                    RecordInput.Expense(money(25), null, listOf(AccountAmount(account, money(25))), true, true),
                    1768449601000L,
                    "Asia/Shanghai",
                    "迁移保留账单",
                    null,
                )
                before = repo.snapshot()
            }
            EncryptedDatasetStore.open(context, owner, epoch).use { db ->
                val sql = db.openHelper.writableDatabase
                sql.execSQL("DROP TABLE `saving_entry`")
                sql.execSQL("DROP TABLE `saving_plan`")
                sql.execSQL("PRAGMA user_version = 10")
            }
            LocalFinanceRepository.openDataset(context, owner, epoch).use { migrated ->
                val after = migrated.snapshot()
                assertEquals(owner, after.ownerId)
                assertEquals(epoch, after.epochId)
                assertEquals(before.ledgerId, after.ledgerId)
                assertEquals(before.accounts, after.accounts)
                assertEquals(before.records, after.records)
                assertEquals(before.summaries, after.summaries)
                assertTrue(after.savingPlans.isEmpty())
                assertTrue(after.savingEntries.isEmpty())
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
    fun accountLifecyclePreservesFactsEnforcesEligibilityAndPersistsAfterReopen() {
        val owner = UUID.randomUUID()
        val epoch = UUID.randomUUID()
        val directory = EncryptedDatasetStore.directory(context, owner, epoch)
        val at = 1768449600000L
        try {
            lateinit var accountId: UUID
            lateinit var templateId: UUID
            lateinit var archived: FinanceSnapshot
            lateinit var beforeRecords: List<FinanceRecord>
            lateinit var beforeBudget: FinanceBudgetOverview
            LocalFinanceRepository.openDataset(context, owner, epoch).use { repo ->
                accountId = repo.createAccount("生命周期账户", "CNY", money(1000), at)
                repo.saveRecord(
                    null,
                    RecordInput.Expense(money(100), null, listOf(AccountAmount(accountId, money(100))), true, true),
                    at,
                    "Asia/Shanghai",
                    "保留历史",
                    null,
                )
                templateId =
                    repo.saveRecordTemplate(
                        FinanceRecordTemplate(
                            UUID.randomUUID(),
                            "账户模板",
                            RecordInput.Expense(money(10), null, listOf(AccountAmount(accountId, money(10))), true, true),
                            null,
                            at,
                            "Asia/Shanghai",
                            "",
                        ),
                    )
                val before = repo.snapshot()
                beforeRecords = before.records
                beforeBudget = repo.budgetOverview(dev.icos.app.domain.ReportPeriod.MONTH, at, "Asia/Shanghai")

                repo.setAccountIncludedInAssets(accountId, 1, false)
                val excluded = repo.snapshot()
                assertFalse(excluded.accounts.single().includeInAssets)
                assertEquals(before.accounts.single().balance, excluded.accounts.single().balance)
                assertEquals(before.summaries, excluded.summaries)
                assertEquals(before.statisticEntries, excluded.statisticEntries)
                assertEquals(beforeBudget.used, repo.budgetOverview(dev.icos.app.domain.ReportPeriod.MONTH, at, "Asia/Shanghai").used)

                repo.setAccountArchived(accountId, 2, true)
                archived = repo.snapshot()
                assertEquals(beforeRecords, archived.records)
                assertEquals(before.summaries, archived.summaries)
                assertEquals(before.statisticEntries, archived.statisticEntries)
                assertNotNull(archived.accounts.single().archivedAt)
                assertFalse(archived.accounts.single().selectable)
                assertEquals("模板引用的账户不可用", repo.recordTemplates().single { it.id == templateId }.invalidReason)
                assertThrows(FinanceValidationException::class.java) {
                    repo.setAccountIncludedInAssets(accountId, 2, true)
                }
                assertThrows(FinanceValidationException::class.java) {
                    repo.saveRecord(
                        null,
                        RecordInput.Expense(money(5), null, listOf(AccountAmount(accountId, money(5))), true, true),
                        at,
                        "Asia/Shanghai",
                        "拒绝已归档账户",
                        null,
                    )
                }
                assertEquals(archived, repo.snapshot())

                repo.setAccountArchived(accountId, archived.accounts.single().localRevision, false)
                val restored = repo.snapshot().accounts.single()
                assertNull(restored.archivedAt)
                assertFalse(restored.selectable)
                assertEquals("模板引用的账户不可用", repo.recordTemplates().single { it.id == templateId }.invalidReason)
                repo.setAccountSelectable(accountId, restored.localRevision, true)
                assertNull(repo.recordTemplates().single { it.id == templateId }.invalidReason)
            }

            LocalFinanceRepository.openDataset(context, owner, epoch).use { repo ->
                val persisted = repo.snapshot().accounts.single()
                assertTrue(persisted.selectable)
                assertFalse(persisted.includeInAssets)
                assertEquals(5L, persisted.localRevision)
                assertNull(repo.recordTemplates().single { it.id == templateId }.invalidReason)
            }
            EncryptedDatasetStore.open(context, owner, epoch).use { db ->
                assertEquals(2, db.finance().events().size)
                assertEquals(2, db.finance().postings().size)
                assertEquals(1, db.finance().statistics().size)
                assertEquals(5, db.finance().mutations().count { it.kind.startsWith("ACCOUNT_") })
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
    fun accountLifecycleMigration8To9DefaultsExistingAccounts() {
        val owner = UUID.randomUUID()
        val epoch = UUID.randomUUID()
        val directory = EncryptedDatasetStore.directory(context, owner, epoch)
        val at = 1768449600000L
        try {
            lateinit var accountId: UUID
            LocalFinanceRepository.openDataset(context, owner, epoch).use { repo ->
                accountId = repo.createAccount("迁移账户", "CNY", money(25), at)
            }
            EncryptedDatasetStore.open(context, owner, epoch).use { db ->
                val sql = db.openHelper.writableDatabase
                sql.execSQL("ALTER TABLE `finance_account` DROP COLUMN `archivedAt`")
                sql.execSQL("ALTER TABLE `finance_account` DROP COLUMN `includeInAssets`")
                sql.execSQL("ALTER TABLE `finance_account` DROP COLUMN `selectable`")
                sql.execSQL("ALTER TABLE `finance_account` DROP COLUMN `sortOrder`")
                sql.execSQL("ALTER TABLE `finance_group` DROP COLUMN `sortOrder`")
                sql.execSQL("ALTER TABLE `finance_group` DROP COLUMN `isDefault`")
                sql.execSQL("PRAGMA user_version = 8")
            }
            LocalFinanceRepository.openDataset(context, owner, epoch).use { repo ->
                val account = repo.snapshot().accounts.single { it.id == accountId }
                assertNull(account.archivedAt)
                assertTrue(account.includeInAssets)
                assertTrue(account.selectable)
                assertEquals(1L, account.localRevision)
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
    fun accountLifecycleRejectsForeignOwnerRowWithoutChangingRevision() {
        val owner = UUID.randomUUID()
        val epoch = UUID.randomUUID()
        val directory = EncryptedDatasetStore.directory(context, owner, epoch)
        val at = 1768449600000L
        try {
            lateinit var accountId: UUID
            LocalFinanceRepository.openDataset(context, owner, epoch).use { repo ->
                accountId = repo.createAccount("隔离账户", "CNY", money(25), at)
            }
            EncryptedDatasetStore.open(context, owner, epoch).use { db ->
                db.openHelper.writableDatabase.execSQL(
                    "UPDATE `finance_account` SET `ownerId` = ? WHERE `id` = ?",
                    arrayOf(UUID.randomUUID().toString(), accountId.toString()),
                )
            }
            LocalFinanceRepository.openDataset(context, owner, epoch).use { repo ->
                assertThrows(FinanceValidationException::class.java) {
                    repo.setAccountIncludedInAssets(accountId, 1, false)
                }
            }
            EncryptedDatasetStore.open(context, owner, epoch).use { db ->
                val sql = db.openHelper.writableDatabase
                sql.execSQL(
                    "UPDATE `finance_account` SET `ownerId` = ? WHERE `id` = ?",
                    arrayOf(owner.toString(), accountId.toString()),
                )
                assertEquals(
                    1L,
                    db
                        .finance()
                        .accounts()
                        .single { it.id == accountId.toString() }
                        .localRevision,
                )
                assertTrue(
                    db
                        .finance()
                        .accounts()
                        .single { it.id == accountId.toString() }
                        .includeInAssets,
                )
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
    fun pendingClaimMarkPersistsWithoutChangingFinancialFacts() {
        val owner = UUID.randomUUID()
        val epoch = UUID.randomUUID()
        val directory = EncryptedDatasetStore.directory(context, owner, epoch)
        val at = 1768449600000L
        try {
            lateinit var expense: UUID
            lateinit var before: FinanceSnapshot
            LocalFinanceRepository.openDataset(context, owner, epoch).use { repo ->
                val account = repo.createAccount("报销测试", "CNY", money(1000), at)
                expense =
                    repo.saveRecord(
                        null,
                        RecordInput.Expense(money(100), null, listOf(AccountAmount(account, money(100))), true, true),
                        at,
                        "Asia/Shanghai",
                        "待报销",
                        null,
                    )
                before = repo.snapshot()
                repo.markClaim(expense, "PENDING", 1)
                val pending = repo.snapshot()
                assertEquals("PENDING", pending.records.single().claimState)
                assertEquals(before.accounts, pending.accounts)
                assertEquals(before.summaries, pending.summaries)
                assertThrows(FinanceValidationException::class.java) { repo.markClaim(expense, "PENDING", 1) }
            }
            LocalFinanceRepository.openDataset(context, owner, epoch).use { repo ->
                val pending = repo.snapshot().records.single()
                assertEquals("PENDING", pending.claimState)
                assertEquals(2L, pending.localRevision)
                repo.markClaim(expense, "NONE", pending.localRevision)
                assertEquals(
                    "NONE",
                    repo
                        .snapshot()
                        .records
                        .single()
                        .claimState,
                )
            }
            val missingClaimMutation =
                EncryptedDatasetStore.open(context, owner, epoch).use { db ->
                    assertEquals(2, db.finance().events().size)
                    assertEquals(2, db.finance().postings().size)
                    assertEquals(1, db.finance().statistics().size)
                    assertEquals(1, db.finance().budgets().size)
                    assertEquals(2, db.finance().mutations().count { it.kind == "CLAIM_MARK" })
                    val mutation = db.finance().mutations().first { it.kind == "CLAIM_MARK" }
                    db.openHelper.writableDatabase.execSQL("DELETE FROM finance_mutation WHERE opId = ?", arrayOf(mutation.opId))
                    mutation
                }
            val gapFailure =
                assertThrows(FinanceValidationException::class.java) {
                    LocalFinanceRepository.openDataset(context, owner, epoch).use { it.snapshot() }
                }
            assertTrue(gapFailure.cause is IllegalStateException)
            EncryptedDatasetStore.open(context, owner, epoch).use { db ->
                db.finance().insert(missingClaimMutation)
                val event = db.finance().events().single { it.recordId == expense.toString() }
                db.finance().insert(event.copy(id = UUID.randomUUID().toString(), kind = "INCOME"))
            }
            val ambiguousFailure =
                assertThrows(FinanceValidationException::class.java) {
                    LocalFinanceRepository.openDataset(context, owner, epoch).use { it.snapshot() }
                }
            assertTrue(ambiguousFailure.cause is IllegalArgumentException)
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
    fun claimSettlementPostsOnceBlocksDependenciesAndCancelsAtomicallyAcrossRecords() {
        val owner = UUID.randomUUID()
        val epoch = UUID.randomUUID()
        val directory = EncryptedDatasetStore.directory(context, owner, epoch)
        val at = 1768449600000L
        try {
            lateinit var first: UUID
            lateinit var second: UUID
            lateinit var settlement: UUID
            lateinit var settled: FinanceSnapshot
            lateinit var cancelled: FinanceSnapshot
            LocalFinanceRepository.openDataset(context, owner, epoch).use { repo ->
                val account = repo.createAccount("报销结算", "CNY", money(1000), at)

                fun expense() =
                    repo.saveRecord(
                        null,
                        RecordInput.Expense(money(100), null, listOf(AccountAmount(account, money(100))), true, true),
                        at,
                        "Asia/Shanghai",
                        "待报销",
                        null,
                    )
                first = expense()
                second = expense()
                repo.markClaim(first, "PENDING", 1)
                repo.markClaim(second, "PENDING", 1)
                val beforeInvalid = repo.snapshot()
                for ((invalidAccount, invalidCategory) in listOf(UUID.randomUUID() to null, account to UUID.randomUUID())) {
                    assertThrows(FinanceValidationException::class.java) {
                        repo.settleClaims(
                            listOf(FinanceClaimAllocation(first, money(60), 2)),
                            invalidAccount,
                            invalidCategory,
                            dev.icos.app.domain.RemainderAction.PARTIAL_KEEP,
                            at,
                            "Asia/Shanghai",
                        )
                    }
                    assertEquals(beforeInvalid, repo.snapshot())
                }
                settlement =
                    repo.settleClaims(
                        listOf(
                            FinanceClaimAllocation(first, money(60), 2),
                            FinanceClaimAllocation(second, money(20), 2),
                        ),
                        account,
                        null,
                        dev.icos.app.domain.RemainderAction.PARTIAL_KEEP,
                        at,
                        "Asia/Shanghai",
                    )
                settled = repo.snapshot()
                assertEquals(
                    880L,
                    settled.accounts
                        .single()
                        .balance.unscaled,
                )
                assertEquals(
                    120L,
                    settled.summaries
                        .single()
                        .expense.unscaled,
                )
                assertEquals(
                    120L,
                    settled.summaries
                        .single()
                        .budgetExpense.unscaled,
                )
                assertEquals(
                    120L,
                    settled.statisticEntries.filter { it.direction == dev.icos.app.domain.Direction.EXPENSE }.sumOf { it.amount.unscaled },
                )
                assertEquals(
                    -80L,
                    settled.statisticEntries
                        .single { it.eventId == settlement }
                        .amount.unscaled,
                )
                assertEquals("PARTIAL", settled.records.single { it.id == first }.claimState)
                assertEquals("PARTIAL", settled.records.single { it.id == second }.claimState)
                assertEquals(
                    2,
                    settled.claimSettlements
                        .single()
                        .allocations.size,
                )
                assertEquals("POSTED", settled.claimSettlements.single().status)
                assertThrows(FinanceValidationException::class.java) {
                    repo.deleteRecord(first, settled.records.single { it.id == first }.localRevision)
                }
                assertThrows(FinanceValidationException::class.java) {
                    repo.saveRecord(
                        first,
                        settled.records.single { it.id == first }.input,
                        settled.records.single { it.id == first }.occurredAtMillis,
                        settled.records.single { it.id == first }.timezone,
                        settled.records.single { it.id == first }.remark,
                        settled.records.single { it.id == first }.categoryId,
                        settled.records.single { it.id == first }.localRevision,
                    )
                }
                assertThrows(FinanceValidationException::class.java) {
                    repo.markClaim(first, "NONE", settled.records.single { it.id == first }.localRevision)
                }
                assertEquals(settled, repo.snapshot())
            }
            EncryptedDatasetStore.open(context, owner, epoch).use { db ->
                val finance = db.finance()
                assertEquals(1, finance.events().count { it.id == settlement.toString() && it.kind == "CLAIM_SETTLEMENT" })
                assertEquals(1, finance.postings().count { it.eventId == settlement.toString() })
                assertEquals(2, finance.mutations().count { it.kind == "CLAIM_SETTLEMENT_STATE" })
            }
            LocalFinanceRepository.openDataset(context, owner, epoch).use { repo ->
                repo.cancelClaimSettlement(settlement, 1, "收款信息有误")
                cancelled = repo.snapshot()
                assertEquals(
                    800L,
                    cancelled.accounts
                        .single()
                        .balance.unscaled,
                )
                assertEquals(
                    200L,
                    cancelled.summaries
                        .single()
                        .expense.unscaled,
                )
                assertEquals(
                    200L,
                    cancelled.summaries
                        .single()
                        .budgetExpense.unscaled,
                )
                assertEquals(
                    200L,
                    cancelled.statisticEntries
                        .filter {
                            it.direction == dev.icos.app.domain.Direction.EXPENSE
                        }.sumOf { it.amount.unscaled },
                )
                assertTrue(cancelled.statisticEntries.none { it.eventId == settlement })
                assertTrue(cancelled.records.all { it.claimState == "PENDING" })
                assertEquals("CANCELLED", cancelled.claimSettlements.single().status)
                assertThrows(FinanceValidationException::class.java) {
                    repo.cancelClaimSettlement(settlement, 1, "重复撤销")
                }
            }
            EncryptedDatasetStore.open(context, owner, epoch).use { db ->
                val finance = db.finance()
                assertEquals(1, finance.events().count { it.reversalOf == settlement.toString() })
                assertEquals(
                    1,
                    finance.postings().count {
                        it.reversalOf != null &&
                            it.eventId == finance.events().single { event -> event.reversalOf == settlement.toString() }.id
                    },
                )
                assertEquals(1, finance.mutations().count { it.kind == "CLAIM_CANCEL" })
            }
            LocalFinanceRepository.openDataset(context, owner, epoch).use { repo ->
                assertEquals(cancelled, repo.snapshot())
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
    fun bulkDeletePreflightsRollsBackAndCommitsOneIntent() {
        val owner = UUID.randomUUID()
        val epoch = UUID.randomUUID()
        val directory = EncryptedDatasetStore.directory(context, owner, epoch)
        val at = 1768449600000L
        lateinit var revisions: Map<UUID, Long>
        lateinit var before: FinanceSnapshot
        lateinit var after: FinanceSnapshot
        try {
            LocalFinanceRepository.openDataset(context, owner, epoch).use { repo ->
                val account = repo.createAccount("批量测试", "CNY", money(1000), at)
                val expense =
                    repo.saveRecord(
                        null,
                        RecordInput.Expense(money(100), null, listOf(AccountAmount(account, money(100))), true, true),
                        at,
                        "Asia/Shanghai",
                        "支出",
                        null,
                    )
                val income =
                    repo.saveRecord(
                        null,
                        RecordInput.Income(money(50), listOf(AccountAmount(account, money(50))), true),
                        at,
                        "Asia/Shanghai",
                        "收入",
                        null,
                    )
                revisions = linkedMapOf(expense to 1L, income to 1L)
                before = repo.snapshot()
                assertThrows(FinanceValidationException::class.java) { repo.deleteRecords(emptyMap()) }
                assertThrows(FinanceValidationException::class.java) {
                    repo.deleteRecords(linkedMapOf(expense to 1L, income to 0L))
                }
                assertEquals(before, repo.snapshot())
            }
            EncryptedDatasetStore.open(context, owner, epoch).use { db ->
                db.openHelper.writableDatabase.execSQL(
                    "CREATE TRIGGER fail_bulk_journal BEFORE INSERT ON finance_mutation WHEN NEW.kind = 'RECORD_BULK_DELETE' BEGIN SELECT RAISE(ABORT, 'test failure'); END",
                )
            }
            LocalFinanceRepository.openDataset(context, owner, epoch).use { repo ->
                assertThrows(FinanceValidationException::class.java) { repo.deleteRecords(revisions) }
                assertEquals(before, repo.snapshot())
            }
            EncryptedDatasetStore.open(context, owner, epoch).use { db ->
                assertTrue(db.finance().events().none { it.kind == "REVERSAL" })
                db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_bulk_journal")
            }
            LocalFinanceRepository.openDataset(context, owner, epoch).use { repo ->
                repo.deleteRecords(revisions)
                after = repo.snapshot()
                assertTrue(after.records.isEmpty())
                assertTrue(
                    after.accounts
                        .single()
                        .balance
                        .sameValue(money(1000)),
                )
                val summary = after.summaries.single()
                assertTrue(summary.expense.sameValue(money(0)))
                assertTrue(summary.income.sameValue(money(0)))
                assertTrue(summary.budgetExpense.sameValue(money(0)))
            }
            EncryptedDatasetStore.open(context, owner, epoch).use { db ->
                val dao = db.finance()
                assertEquals(2, dao.records().count { it.deletedAt != null && it.localRevision == 2L })
                assertEquals(2, dao.events().count { it.kind == "REVERSAL" })
                val intent = dao.mutations().single { it.kind == "RECORD_BULK_DELETE" }
                val payload = org.json.JSONObject(intent.payload)
                assertEquals(2, payload.getJSONArray("recordIds").length())
                revisions.forEach { (id, version) ->
                    assertEquals(version, payload.getJSONObject("baseLocalRevisions").getLong(id.toString()))
                }
                assertEquals("REJECT", payload.getString("dependencyStrategy"))
            }
            LocalFinanceRepository.openDataset(context, owner, epoch).use { assertEquals(after, it.snapshot()) }
        } finally {
            KeyStore.getInstance("AndroidKeyStore").apply {
                load(null)
                deleteEntry(EncryptedDatasetStore.alias(owner, epoch))
            }
            directory.deleteRecursively()
            directory.parentFile?.delete() // Remove the empty random-owner fixture directory too.
        }
    }

    @Test
    fun schemaOnePreservesIdentityAndKeyThenTransactionsReverseAndReopen() {
        val owner = UUID.randomUUID()
        val epoch = UUID.randomUUID()
        val directory = EncryptedDatasetStore.directory(context, owner, epoch)
        val at = 1768449600000L
        val zone = "Asia/Shanghai"
        try {
            // Reconstruct the exported schema-1 database, retaining its real SQLCipher envelope and identity.
            EncryptedDatasetStore.open(context, owner, epoch).use { db ->
                val sql = db.openHelper.writableDatabase
                val tables = mutableListOf<String>()
                sql.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name LIKE 'finance_%'").use {
                    while (it.moveToNext()) tables += it.getString(0)
                }
                sql.execSQL("DROP TABLE IF EXISTS `dataset_search_preferences`")
                sql.execSQL("PRAGMA foreign_keys = OFF")
                tables.forEach { sql.execSQL("DROP TABLE `$it`") }
                sql.execSQL("PRAGMA foreign_keys = ON")
                sql.execSQL("UPDATE dataset_identity SET serverCursor = 'migration-cursor', lastAppliedSeq = 7")
                sql.execSQL("UPDATE room_master_table SET identity_hash = 'e9fe76ab1c1375074151df5f3fc9e22b' WHERE id = 42")
                sql.execSQL("PRAGMA user_version = 1")
            }
            val envelope = File(directory, "passphrase.envelope").readBytes()
            EncryptedDatasetStore.open(context, owner, epoch).use { db ->
                assertEquals(
                    DatasetIdentity(
                        ownerId = owner.toString(),
                        epochId = epoch.toString(),
                        serverCursor = "migration-cursor",
                        lastAppliedSeq = 7,
                    ),
                    db.identity().read(),
                )
                db.openHelper.writableDatabase.query("PRAGMA user_version").use {
                    assertTrue(it.moveToFirst())
                    assertEquals(10, it.getInt(0))
                }
                assertTrue(db.finance().ledgers().isEmpty())
            }
            assertArrayEquals(envelope, File(directory, "passphrase.envelope").readBytes())
            lateinit var a: UUID
            lateinit var b: UUID
            lateinit var transfer: UUID
            lateinit var finalSnapshot: FinanceSnapshot
            LocalFinanceRepository.openDataset(context, owner, epoch).use { repo ->
                val empty = repo.snapshot()
                assertEquals(owner, empty.ownerId)
                assertEquals(epoch, empty.epochId)
                assertEquals(4, empty.categories.size)
                assertTrue(empty.accounts.isEmpty() && empty.records.isEmpty() && empty.summaries.isEmpty())
                a = repo.createAccount("现金", "CNY", money(1000), at)
                b = repo.createAccount("储蓄", "CNY", money(500), at)

                fun expense(
                    value: Long,
                    statistics: Boolean = true,
                    budget: Boolean = true,
                ) = RecordInput.Expense(
                    money(value),
                    null,
                    listOf(AccountAmount(a, money(value))),
                    statistics,
                    budget,
                )

                fun balance(id: UUID) =
                    repo
                        .snapshot()
                        .accounts
                        .single { it.id == id }
                        .balance
                val record = repo.saveRecord(null, expense(100), at, zone, "原始备注", null)
                assertTrue(balance(a).sameValue(money(900)))
                repo.saveRecord(record, expense(80), at, zone, "编辑备注", null, 1)
                assertTrue(balance(a).sameValue(money(920)))
                val afterEdit = repo.snapshot()
                assertThrows(FinanceValidationException::class.java) { repo.saveRecord(record, expense(70), at, zone, "", null, 1) }
                assertThrows(FinanceValidationException::class.java) { repo.deleteRecord(record, 1) }
                // Timezone failure happens after reversal insertion, so all that work must roll back.
                assertThrows(
                    FinanceValidationException::class.java,
                ) { repo.saveRecord(record, expense(60), at, "Invalid/Zone", "", null, 2) }
                assertEquals(afterEdit, repo.snapshot())
                repo.deleteRecord(record, 2)
                assertTrue(balance(a).sameValue(money(1000)))
                assertTrue(repo.snapshot().records.isEmpty())
                assertTrue(
                    repo
                        .snapshot()
                        .summaries
                        .single()
                        .expense
                        .sameValue(money(0)),
                )
                assertTrue(
                    repo
                        .snapshot()
                        .summaries
                        .single()
                        .budgetExpense
                        .sameValue(money(0)),
                )
                assertThrows(FinanceValidationException::class.java) { repo.deleteRecord(record, 3) }

                transfer =
                    repo.saveRecord(
                        null,
                        RecordInput.Transfer(
                            AccountAmount(a, money(100)),
                            AccountAmount(b, money(100)),
                            AccountAmount(a, money(2)),
                            AccountAmount(b, money(1)),
                        ),
                        at,
                        zone,
                        "手续费和优惠",
                        null,
                    )
                assertTrue(balance(a).sameValue(money(898)))
                assertTrue(balance(b).sameValue(money(601)))
                repo.saveRecord(null, expense(20, false, true), at, zone, "只计预算", null)
                repo.saveRecord(null, RecordInput.Income(money(10), emptyList(), true), at, zone, "无账户收入", null)
                val summary = repo.snapshot().summaries.single()
                assertTrue(summary.income.sameValue(money(11)))
                assertTrue(summary.expense.sameValue(money(2)))
                assertTrue(summary.budgetExpense.sameValue(money(22)))
                val usd = repo.createAccount("美元", "USD", money(Long.MAX_VALUE, "USD"), at)
                val beforeOverflow = repo.snapshot()
                assertThrows(FinanceValidationException::class.java) {
                    repo.saveRecord(
                        null,
                        RecordInput.Income(money(1, "USD"), listOf(AccountAmount(usd, money(1, "USD"))), true),
                        at,
                        zone,
                        "",
                        null,
                    )
                }
                assertEquals(beforeOverflow, repo.snapshot())
                assertThrows(FinanceValidationException::class.java) {
                    repo.saveRecord(
                        null,
                        RecordInput.Expense(money(1), null, listOf(AccountAmount(UUID.randomUUID(), money(1))), true, true),
                        at,
                        zone,
                        "",
                        null,
                    )
                }
                assertThrows(FinanceValidationException::class.java) {
                    repo.saveRecord(null, RecordInput.Income(money(1), listOf(AccountAmount(usd, money(1))), true), at, zone, "", null)
                }
                val incomeCategory = empty.categories.first { it.direction == dev.icos.app.domain.Direction.INCOME }
                assertThrows(FinanceValidationException::class.java) { repo.saveRecord(null, expense(1), at, zone, "", incomeCategory.id) }
                val huge = repo.saveRecord(null, RecordInput.Income(money(Long.MAX_VALUE, "EUR"), emptyList(), true), at, zone, "", null)
                val beforeSummaryOverflow = repo.snapshot()
                assertThrows(FinanceValidationException::class.java) {
                    repo.saveRecord(null, RecordInput.Income(money(1, "EUR"), emptyList(), true), at, zone, "", null)
                }
                assertEquals(beforeSummaryOverflow, repo.snapshot())
                repo.deleteRecord(huge, 1)
                finalSnapshot = repo.snapshot()
            }
            EncryptedDatasetStore.open(context, owner, epoch).use { db ->
                val dao = db.finance()
                assertTrue(dao.records().any { it.deletedAt != null && it.localRevision == 3L })
                val reversals = dao.events().filter { it.kind == "REVERSAL" }
                assertEquals(3, reversals.size)
                assertEquals(reversals.size, reversals.map { it.reversalOf }.distinct().size)
                assertEquals(3, dao.statistics().count { it.reversalOf != null })
                assertEquals(2, dao.postings().count { it.reversalOf != null })
                val mutations = dao.mutations().sortedBy { it.sequence }
                assertEquals(12, mutations.size)
                assertTrue(
                    mutations.all {
                        it.intentVersion == 1 && it.state == "LOCAL_PENDING" && it.ownerId == owner.toString() &&
                            it.epochId == epoch.toString()
                    },
                )
                assertEquals(mutations.size, mutations.map { it.opId }.distinct().size)
                assertEquals(
                    listOf(0L, 1L, 2L),
                    mutations
                        .filter {
                            it.kind in listOf("RECORD_CREATE", "RECORD_EDIT", "RECORD_DELETE") &&
                                it.aggregateId == reversals.first().recordId
                        }.map { it.baseLocalRevision },
                )
                assertEquals("2026-01-15", dao.events().first { it.recordId == transfer.toString() }.localDate)
                // Storage failure at journal insertion must roll back both reversal and new facts.
                db.openHelper.writableDatabase.execSQL(
                    "CREATE TRIGGER fail_journal BEFORE INSERT ON finance_mutation BEGIN SELECT RAISE(ABORT, 'test failure'); END",
                )
            }
            LocalFinanceRepository.openDataset(context, owner, epoch).use { repo ->
                assertEquals(finalSnapshot, repo.snapshot())
                assertThrows(FinanceValidationException::class.java) { repo.deleteRecord(transfer, 1) }
                assertEquals(finalSnapshot, repo.snapshot())
            }
            EncryptedDatasetStore.open(context, owner, epoch).use { db ->
                db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_journal")
                db.openHelper.writableDatabase.execSQL(
                    "UPDATE finance_account SET ownerId = ? WHERE id = ?",
                    arrayOf(UUID.randomUUID().toString(), a.toString()),
                )
            }
            assertThrows(FinanceValidationException::class.java) { LocalFinanceRepository.openDataset(context, owner, epoch).close() }
            assertArrayEquals(envelope, File(directory, "passphrase.envelope").readBytes())
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
    fun dateWindowFollowsReversalsWithoutFilteringAccountBalances() {
        val owner = UUID.randomUUID()
        val epoch = UUID.randomUUID()
        val directory = EncryptedDatasetStore.directory(context, owner, epoch)
        val start = 1768449600000L
        val end = start + 1000
        val range = PeriodRange(start, end)
        try {
            LocalFinanceRepository.openDataset(context, owner, epoch).use { repo ->
                val account = repo.createAccount("Window fixture", "CNY", money(100), start - 1)

                fun expense(
                    amount: Long,
                    statistics: Boolean,
                ) = RecordInput.Expense(
                    money(amount),
                    null,
                    listOf(AccountAmount(account, money(amount))),
                    statistics,
                    true,
                )
                val record = repo.saveRecord(null, expense(10, false), start, "UTC", "budget only", null)
                repo.saveRecord(
                    null,
                    RecordInput.Income(money(5), listOf(AccountAmount(account, money(5))), true),
                    start + 500,
                    "UTC",
                    "inside",
                    null,
                )
                repo.saveRecord(
                    null,
                    RecordInput.Income(money(7), listOf(AccountAmount(account, money(7))), true),
                    end,
                    "UTC",
                    "excluded end",
                    null,
                )
                val all = repo.snapshot()
                val selected = repo.snapshot(range)
                assertEquals(all.accounts, selected.accounts)
                assertEquals(all.records, selected.records)
                assertTrue(
                    selected.accounts
                        .single()
                        .balance
                        .sameValue(money(102)),
                )
                selected.summaries.single().let {
                    assertTrue(it.income.sameValue(money(5)))
                    assertTrue(it.expense.sameValue(money(0)))
                    assertTrue(it.budgetExpense.sameValue(money(10)))
                    assertTrue(it.balance.sameValue(money(5)))
                }
                repo.saveRecord(record, expense(12, true), end, "UTC", "moved date", null, 1)
                assertTrue(
                    repo
                        .snapshot(range)
                        .summaries
                        .single()
                        .budgetExpense
                        .sameValue(money(0)),
                )
                val later = repo.snapshot(PeriodRange(end, end + 1000)).summaries.single()
                assertTrue(later.income.sameValue(money(7)))
                assertTrue(later.expense.sameValue(money(12)))
                assertTrue(later.budgetExpense.sameValue(money(12)))
                repo.deleteRecord(record, 2)
                val afterDelete = repo.snapshot(PeriodRange(end, end + 1000)).summaries.single()
                assertTrue(afterDelete.expense.sameValue(money(0)))
                assertTrue(afterDelete.budgetExpense.sameValue(money(0)))
                repo.saveRecord(null, RecordInput.Income(money(3, "USD"), emptyList(), true), start, "UTC", "separate currency", null)
                val currencies = repo.snapshot(range).summaries.associateBy { it.currency }
                assertEquals(setOf("CNY", "USD"), currencies.keys)
                assertTrue(currencies.getValue("CNY").income.sameValue(money(5)))
                assertTrue(currencies.getValue("USD").income.sameValue(money(3, "USD")))
                assertTrue(
                    repo
                        .snapshot(range)
                        .accounts
                        .single()
                        .balance
                        .sameValue(money(112)),
                )
            }
            LocalFinanceRepository.openDataset(context, owner, epoch).use { repo ->
                assertTrue(
                    repo
                        .snapshot(range)
                        .accounts
                        .single()
                        .balance
                        .sameValue(money(112)),
                )
                assertEquals(2, repo.snapshot(range).summaries.size)
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
}
