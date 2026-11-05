package dev.icos.app.data.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.icos.app.domain.AccountAmount
import dev.icos.app.domain.Direction
import dev.icos.app.domain.Money
import dev.icos.app.domain.RecordInput
import dev.icos.app.domain.ReportPeriod
import dev.icos.app.data.remote.SnapshotProtocol
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class TagRepositoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun tagsGroupsArchiveDetachAndReopenAreAtomicAndKeepFinancialFacts() {
        val owner = UUID.randomUUID()
        val epoch = UUID.randomUUID()
        val directory = EncryptedDatasetStore.directory(context, owner, epoch)
        val at = 1768449600000L
        try {
            lateinit var recordId: UUID
            lateinit var tagId: UUID
            lateinit var groupId: UUID
            lateinit var accountId: UUID
            lateinit var before: FinanceSnapshot
            LocalFinanceRepository.openDataset(context, owner, epoch).use { repo ->
                val initial = repo.snapshot()
                val defaultGroup = initial.tagGroups.single { it.isDefault }
                groupId = repo.saveTagGroup(null, "旅行", 1)
                assertThrows(FinanceValidationException::class.java) {
                    repo.moveTagGroup(defaultGroup.id, groupId, defaultGroup.localRevision, emptyMap())
                }
                tagId = repo.saveTag(null, groupId, "行程", "#2468", 2)
                // Duplicate names are allowed by the server metadata contract.
                repo.saveTag(null, groupId, "行程", null, 3)
                accountId = repo.createAccount("标签测试", "CNY", Money(1000, 0, "CNY"), at)
                val beforeMissingTag = repo.snapshot()
                assertThrows(FinanceValidationException::class.java) {
                    repo.saveRecord(
                        null,
                        RecordInput.Expense(
                            Money(20, 0, "CNY"),
                            null,
                            listOf(AccountAmount(accountId, Money(20, 0, "CNY"))),
                            true,
                            true,
                        ),
                        at,
                        "Asia/Shanghai",
                        "缺失标签",
                        null,
                        tagIds = listOf(UUID.randomUUID()),
                    )
                }
                assertEquals(beforeMissingTag, repo.snapshot())
                recordId =
                    repo.saveRecord(
                        null,
                        RecordInput.Expense(
                            Money(100, 0, "CNY"),
                            null,
                            listOf(AccountAmount(accountId, Money(100, 0, "CNY"))),
                            true,
                            true,
                        ),
                        at,
                        "Asia/Shanghai",
                        "历史标签",
                        null,
                        tagIds = listOf(tagId),
                    )
                before = repo.snapshot()
                val sourceRecord = before.records.single { it.id == recordId }
                val templateId = UUID.randomUUID()
                repo.saveRecordTemplate(
                    FinanceRecordTemplate(
                        templateId,
                        "标签模板",
                        sourceRecord.input,
                        sourceRecord.categoryId,
                        sourceRecord.occurredAtMillis,
                        sourceRecord.timezone,
                        sourceRecord.remark,
                        tagIds = listOf(tagId),
                    ),
                )
                assertEquals(listOf(tagId), repo.recordTemplates().single { it.id == templateId }.tagIds)
                repo.saveSearchPreferences(
                    SearchPreferencesEntity(
                        type = "EXPENSE",
                        fromDate = "",
                        throughDate = "",
                        currency = "CNY",
                        accountId = "",
                        categoryId = "",
                        minimumAmount = "",
                        maximumAmount = "",
                        sort = "TIME_DESCENDING",
                        tagIds = tagId.toString(),
                        tagMatch = "ALL",
                    ),
                )
                val currentTag = before.tags.single { it.id == tagId }
                repo.setTagArchived(tagId, true, currentTag.localRevision)
                val archived = repo.snapshot()
                assertEquals(listOf(tagId), archived.records.single { it.id == recordId }.tagIds)
                assertEquals("模板标签已失效", repo.recordTemplates().single { it.id == templateId }.invalidReason)
                val archivedTag = archived.tags.single { it.id == tagId }
                val archivedRecord = archived.records.single { it.id == recordId }
                repo.saveRecord(
                    recordId,
                    archivedRecord.input,
                    archivedRecord.occurredAtMillis,
                    archivedRecord.timezone,
                    archivedRecord.remark,
                    archivedRecord.categoryId,
                    archivedRecord.localRevision,
                    archivedRecord.tagIds,
                )
                val editedHistory = repo.snapshot()
                assertEquals(listOf(tagId), editedHistory.records.single { it.id == recordId }.tagIds)
                assertThrows(FinanceValidationException::class.java) {
                    repo.saveRecord(
                        null,
                        RecordInput.Expense(
                            Money(50, 0, "CNY"),
                            null,
                            listOf(AccountAmount(accountId, Money(50, 0, "CNY"))),
                            true,
                            true,
                        ),
                        at,
                        "Asia/Shanghai",
                        "归档标签不可新选",
                        null,
                        tagIds = listOf(tagId),
                    )
                }
                val unchanged = repo.snapshot()
                assertEquals(editedHistory, unchanged)
                assertThrows(FinanceValidationException::class.java) {
                    repo.deleteTag(tagId, archivedTag.localRevision - 1, mapOf(recordId to unchanged.records.single().localRevision))
                }
                assertEquals(unchanged, repo.snapshot())
                assertThrows(FinanceValidationException::class.java) {
                    repo.deleteTag(tagId, archivedTag.localRevision, mapOf(recordId to archivedRecord.localRevision))
                }
                assertEquals(unchanged, repo.snapshot())
                repo.deleteTag(tagId, archivedTag.localRevision, mapOf(recordId to unchanged.records.single().localRevision))
                val detached = repo.snapshot()
                assertTrue(detached.records.single { it.id == recordId }.tagIds.isEmpty())
                assertEquals(unchanged.records.single().localRevision + 1, detached.records.single().localRevision)
                assertEquals(unchanged.accounts, detached.accounts)
                assertEquals(unchanged.summaries, detached.summaries)
                assertFalse(detached.tags.any { it.id == tagId })
                val movable = detached.tags.single { it.name == "行程" }
                repo.moveTagGroup(groupId, defaultGroup.id, repo.snapshot().tagGroups.single { it.id == groupId }.localRevision, mapOf(movable.id to movable.localRevision))
                val moved = repo.snapshot()
                assertEquals(defaultGroup.id, moved.tags.single { it.id == movable.id }.groupId)
                assertFalse(moved.tagGroups.any { it.id == groupId })
            }
            LocalFinanceRepository.openDataset(context, owner, epoch).use { repo ->
                val reopened = repo.snapshot()
                assertTrue(reopened.tagGroups.single { it.isDefault }.isDefault)
                assertTrue(reopened.records.single { it.id == recordId }.tagIds.isEmpty())
                assertEquals(before.accounts, reopened.accounts)
                assertEquals(before.summaries, reopened.summaries)
                assertEquals(tagId.toString(), repo.searchPreferences()?.tagIds)
                assertEquals("ALL", repo.searchPreferences()?.tagMatch)
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
    fun deletingTagDetachesAllActiveTemplateReferencesWithoutChangingTemplateOrFinancialFacts() {
        val owner = UUID.randomUUID()
        val epoch = UUID.randomUUID()
        val directory = EncryptedDatasetStore.directory(context, owner, epoch)
        val at = 1768449600000L
        val targetTemplateId = UUID.randomUUID()
        val multiTagTemplateId = UUID.randomUUID()
        val unrelatedTemplateId = UUID.randomUUID()
        lateinit var targetTagId: UUID
        lateinit var retainedTagId: UUID
        lateinit var secondRetainedTagId: UUID
        var tagRevision = 0L
        lateinit var beforeTemplates: List<RecordTemplateEntity>
        lateinit var beforeSnapshot: FinanceSnapshot
        try {
            LocalFinanceRepository.openDataset(context, owner, epoch).use { repo ->
                val defaultGroup = repo.snapshot().tagGroups.single { it.isDefault }
                targetTagId = repo.saveTag(null, defaultGroup.id, "待删除", null, 0)
                retainedTagId = repo.saveTag(null, defaultGroup.id, "保留", null, 1)
                secondRetainedTagId = repo.saveTag(null, defaultGroup.id, "顺序保留", null, 2)
                tagRevision = repo.snapshot().tags.single { it.id == targetTagId }.localRevision
                val accountId = repo.createAccount("模板DETACH测试", "CNY", Money(500, 0, "CNY"), at)
                val input = RecordInput.Expense(
                    Money(35, 0, "CNY"),
                    null,
                    listOf(AccountAmount(accountId, Money(35, 0, "CNY"))),
                    true,
                    true,
                )
                fun template(id: UUID, name: String, tags: List<UUID>) =
                    FinanceRecordTemplate(id, name, input, null, at, "Asia/Shanghai", "模板备注", tagIds = tags)
                repo.saveRecordTemplate(template(targetTemplateId, "仅目标标签", listOf(targetTagId)))
                repo.saveRecordTemplate(
                    template(multiTagTemplateId, "目标及保留标签", listOf(targetTagId, retainedTagId, secondRetainedTagId)),
                )
                repo.saveRecordTemplate(template(unrelatedTemplateId, "无关模板", listOf(retainedTagId)))
                beforeSnapshot = repo.snapshot()
                assertTrue(beforeSnapshot.records.isEmpty())
            }
            beforeTemplates = EncryptedDatasetStore.open(context, owner, epoch).use { it.recordTemplates().active() }
            assertTrue(EncryptedDatasetStore.open(context, owner, epoch).use { it.tags().recordTags().isEmpty() })

            LocalFinanceRepository.openDataset(context, owner, epoch).use { repo ->
                repo.deleteTag(targetTagId, tagRevision, emptyMap())
                val afterTemplates = repo.recordTemplates().associateBy { it.id }
                assertEquals(emptyList<UUID>(), afterTemplates.getValue(targetTemplateId).tagIds)
                assertEquals(listOf(retainedTagId, secondRetainedTagId), afterTemplates.getValue(multiTagTemplateId).tagIds)
                assertEquals(listOf(retainedTagId), afterTemplates.getValue(unrelatedTemplateId).tagIds)
                assertTrue(afterTemplates.values.all { it.invalidReason == null })
                val afterSnapshot = repo.snapshot()
                assertEquals(beforeSnapshot.accounts, afterSnapshot.accounts)
                assertEquals(beforeSnapshot.categories, afterSnapshot.categories)
                assertEquals(beforeSnapshot.records, afterSnapshot.records)
                assertEquals(beforeSnapshot.summaries, afterSnapshot.summaries)
                assertEquals(beforeSnapshot.statisticEntries, afterSnapshot.statisticEntries)
                assertEquals(beforeSnapshot.refunds, afterSnapshot.refunds)
            }

            val afterTemplates = EncryptedDatasetStore.open(context, owner, epoch).use { it.recordTemplates().active() }
            val beforeById = beforeTemplates.associateBy { it.id }
            val afterById = afterTemplates.associateBy { it.id }
            assertEquals(beforeById.keys, afterById.keys)
            val updatedTarget = afterById.getValue(targetTemplateId.toString())
            val originalTarget = beforeById.getValue(targetTemplateId.toString())
            assertEquals("[]", updatedTarget.tagIdsJson)
            assertEquals(originalTarget.name, updatedTarget.name)
            assertEquals(originalTarget.inputJson, updatedTarget.inputJson)
            assertEquals(originalTarget.categoryId, updatedTarget.categoryId)
            assertEquals(originalTarget.occurredAtMillis, updatedTarget.occurredAtMillis)
            assertEquals(originalTarget.timezone, updatedTarget.timezone)
            assertEquals(originalTarget.remark, updatedTarget.remark)
            assertEquals(originalTarget.createdAt, updatedTarget.createdAt)
            assertTrue(updatedTarget.updatedAt >= originalTarget.updatedAt)
            assertEquals(originalTarget.deletedAt, updatedTarget.deletedAt)
            assertEquals(
                """["$retainedTagId","$secondRetainedTagId"]""",
                afterById.getValue(multiTagTemplateId.toString()).tagIdsJson,
            )
            val originalMultiTag = beforeById.getValue(multiTagTemplateId.toString())
            val updatedMultiTag = afterById.getValue(multiTagTemplateId.toString())
            assertEquals(
                originalMultiTag.copy(tagIdsJson = updatedMultiTag.tagIdsJson, updatedAt = updatedMultiTag.updatedAt),
                updatedMultiTag,
            )
            assertEquals(
                beforeById.getValue(unrelatedTemplateId.toString()),
                afterById.getValue(unrelatedTemplateId.toString()),
            )

            LocalFinanceRepository.openDataset(context, owner, epoch).use { repo ->
                val reopened = repo.recordTemplates().associateBy { it.id }
                assertEquals(emptyList<UUID>(), reopened.getValue(targetTemplateId).tagIds)
                assertEquals(listOf(retainedTagId, secondRetainedTagId), reopened.getValue(multiTagTemplateId).tagIds)
                assertEquals(listOf(retainedTagId), reopened.getValue(unrelatedTemplateId).tagIds)
                assertTrue(reopened.values.all { it.invalidReason == null })
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
    fun deletingTagAfterSingleAndBulkRecordDeleteDetachesTombstonesWithoutBypassingLiveRevisionFence() {
        val owner = UUID.randomUUID()
        val epoch = UUID.randomUUID()
        val directory = EncryptedDatasetStore.directory(context, owner, epoch)
        val at = 1768449600000L
        val templateId = UUID.randomUUID()
        lateinit var tagId: UUID
        lateinit var liveRecordId: UUID
        lateinit var deletedRecordIds: List<UUID>
        var tagRevision = 0L
        try {
            LocalFinanceRepository.openDataset(context, owner, epoch).use { repo ->
                val group = repo.snapshot().tagGroups.single { it.isDefault }
                tagId = repo.saveTag(null, group.id, "墓碑可删除", null, 0)
                val accountId = repo.createAccount("墓碑DETACH测试", "CNY", Money(1000, 0, "CNY"), at)
                fun createRecord(remark: String) =
                    repo.saveRecord(
                        null,
                        RecordInput.Expense(
                            Money(40, 0, "CNY"),
                            null,
                            listOf(AccountAmount(accountId, Money(40, 0, "CNY"))),
                            true,
                            true,
                        ),
                        at,
                        "Asia/Shanghai",
                        remark,
                        null,
                        tagIds = listOf(tagId),
                    )
                val firstDeleted = createRecord("单笔删除")
                val secondDeleted = createRecord("批量删除一")
                val thirdDeleted = createRecord("批量删除二")
                liveRecordId = createRecord("保留活动记录")
                deletedRecordIds = listOf(firstDeleted, secondDeleted, thirdDeleted)
                val liveRecord = repo.snapshot().records.single { it.id == liveRecordId }
                repo.saveRecordTemplate(
                    FinanceRecordTemplate(
                        templateId,
                        "墓碑标签模板",
                        liveRecord.input,
                        liveRecord.categoryId,
                        liveRecord.occurredAtMillis,
                        liveRecord.timezone,
                        liveRecord.remark,
                        tagIds = listOf(tagId),
                    ),
                )
                tagRevision = repo.snapshot().tags.single { it.id == tagId }.localRevision
                val snapshot = repo.snapshot()
                repo.deleteRecord(firstDeleted, snapshot.records.single { it.id == firstDeleted }.localRevision)
                val beforeBulk = repo.snapshot()
                repo.deleteRecords(
                    listOf(secondDeleted, thirdDeleted).associateWith { id ->
                        beforeBulk.records.single { it.id == id }.localRevision
                    },
                )
            }

            EncryptedDatasetStore.open(context, owner, epoch).use { db ->
                val links = db.tags().recordTags().filter { it.tagId == tagId.toString() }
                assertEquals(listOf(liveRecordId.toString()), links.map { it.recordId })
            }
            // Simulate a link left by an older build, before soft-delete detached record tags.
            EncryptedDatasetStore.open(context, owner, epoch).use { db ->
                db.tags().insert(
                    RecordTagEntity(owner.toString(), epoch.toString(), deletedRecordIds.first().toString(), tagId.toString(), at),
                )
            }
            val linksBeforeTagDelete = EncryptedDatasetStore.open(context, owner, epoch).use { db ->
                db.tags().recordTags().filter { it.tagId == tagId.toString() }
            }
            assertEquals(2, linksBeforeTagDelete.size)
            val mutationsBeforeFailure: List<MutationEntity>
            val rowsBeforeFailure: List<RecordEntity>
            EncryptedDatasetStore.open(context, owner, epoch).use { db ->
                mutationsBeforeFailure = db.finance().mutations()
                rowsBeforeFailure = db.finance().records()
            }
            lateinit var snapshotBeforeFailure: FinanceSnapshot
            lateinit var budgetBeforeFailure: FinanceBudgetOverview
            LocalFinanceRepository.openDataset(context, owner, epoch).use { repo ->
                snapshotBeforeFailure = repo.snapshot()
                budgetBeforeFailure = repo.budgetOverview(ReportPeriod.MONTH, at, "Asia/Shanghai")
                val live = snapshotBeforeFailure.records.single { it.id == liveRecordId }
                assertThrows(FinanceValidationException::class.java) {
                    repo.deleteTag(tagId, tagRevision, mapOf(liveRecordId to live.localRevision - 1))
                }
                assertEquals(snapshotBeforeFailure, repo.snapshot())
            }
            EncryptedDatasetStore.open(context, owner, epoch).use { db ->
                assertEquals(mutationsBeforeFailure, db.finance().mutations())
                assertEquals(rowsBeforeFailure, db.finance().records())
                assertEquals(linksBeforeTagDelete, db.tags().recordTags().filter { it.tagId == tagId.toString() })
            }

            LocalFinanceRepository.openDataset(context, owner, epoch).use { repo ->
                val live = repo.snapshot().records.single { it.id == liveRecordId }
                repo.deleteTag(tagId, tagRevision, mapOf(liveRecordId to live.localRevision))
                val after = repo.snapshot()
                assertTrue(after.tags.none { it.id == tagId })
                assertTrue(after.records.single { it.id == liveRecordId }.tagIds.isEmpty())
                assertEquals(snapshotBeforeFailure.accounts, after.accounts)
                assertEquals(snapshotBeforeFailure.claimSettlements, after.claimSettlements)
                assertEquals(snapshotBeforeFailure.summaries, after.summaries)
                assertEquals(snapshotBeforeFailure.statisticEntries, after.statisticEntries)
                assertEquals(snapshotBeforeFailure.refunds, after.refunds)
                assertEquals(snapshotBeforeFailure.businessPreferences, after.businessPreferences)
                assertEquals(budgetBeforeFailure, repo.budgetOverview(ReportPeriod.MONTH, at, "Asia/Shanghai"))
                assertTrue(repo.recordTemplates().single { it.id == templateId }.tagIds.isEmpty())
            }
            EncryptedDatasetStore.open(context, owner, epoch).use { db ->
                assertTrue(db.tags().recordTags().none { it.tagId == tagId.toString() })
                assertEquals(mutationsBeforeFailure.size + 2, db.finance().mutations().size)
                val rowsAfter = db.finance().records().associateBy { it.id }
                val rowsBefore = rowsBeforeFailure.associateBy { it.id }
                deletedRecordIds.forEach { id ->
                    assertEquals(rowsBefore.getValue(id.toString()).localRevision, rowsAfter.getValue(id.toString()).localRevision)
                    assertTrue(rowsAfter.getValue(id.toString()).deletedAt != null)
                }
                assertEquals(rowsBefore.getValue(liveRecordId.toString()).localRevision + 1, rowsAfter.getValue(liveRecordId.toString()).localRevision)
                assertEquals("[]", db.recordTemplates().active().single { it.id == templateId.toString() }.tagIdsJson)
            }
            LocalFinanceRepository.openDataset(context, owner, epoch).use { repo ->
                assertTrue(repo.recordTemplates().single { it.id == templateId }.tagIds.isEmpty())
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
    fun corruptTemplateTagJsonRejectsDeleteWithoutChangingRecordRevisionOrJournal() {
        val owner = UUID.randomUUID()
        val epoch = UUID.randomUUID()
        val directory = EncryptedDatasetStore.directory(context, owner, epoch)
        val at = 1768449600000L
        val templateId = UUID.randomUUID()
        lateinit var recordId: UUID
        lateinit var tagId: UUID
        var tagRevision = 0L
        try {
            LocalFinanceRepository.openDataset(context, owner, epoch).use { repo ->
                val group = repo.snapshot().tagGroups.single { it.isDefault }
                tagId = repo.saveTag(null, group.id, "损坏引用", null, 0)
                val accountId = repo.createAccount("回滚测试", "CNY", Money(500, 0, "CNY"), at)
                recordId = repo.saveRecord(
                    null,
                    RecordInput.Expense(
                        Money(35, 0, "CNY"),
                        null,
                        listOf(AccountAmount(accountId, Money(35, 0, "CNY"))),
                        true,
                        true,
                    ),
                    at,
                    "Asia/Shanghai",
                    "待保留记录",
                    null,
                    tagIds = listOf(tagId),
                )
                val record = repo.snapshot().records.single { it.id == recordId }
                tagRevision = repo.snapshot().tags.single { it.id == tagId }.localRevision
                repo.saveRecordTemplate(
                    FinanceRecordTemplate(templateId, "损坏模板", record.input, null, at, "Asia/Shanghai", "原备注", tagIds = listOf(tagId)),
                )
            }
            EncryptedDatasetStore.open(context, owner, epoch).use { db ->
                val template = db.recordTemplates().active().single { it.id == templateId.toString() }
                db.recordTemplates().update(template.copy(tagIdsJson = "{broken"))
            }
            lateinit var beforeSnapshot: FinanceSnapshot
            lateinit var beforeMutations: List<MutationEntity>
            EncryptedDatasetStore.open(context, owner, epoch).use { db ->
                beforeMutations = db.finance().mutations()
            }
            LocalFinanceRepository.openDataset(context, owner, epoch).use { repo ->
                beforeSnapshot = repo.snapshot()
                val recordRevision = beforeSnapshot.records.single { it.id == recordId }.localRevision
                assertThrows(FinanceValidationException::class.java) {
                    repo.deleteTag(tagId, tagRevision, mapOf(recordId to recordRevision))
                }
                assertEquals(beforeSnapshot, repo.snapshot())
            }
            EncryptedDatasetStore.open(context, owner, epoch).use { db ->
                assertEquals(beforeMutations, db.finance().mutations())
                assertEquals("{broken", db.recordTemplates().active().single { it.id == templateId.toString() }.tagIdsJson)
                assertEquals(listOf(tagId.toString()), db.tags().recordTags().filter { it.recordId == recordId.toString() }.map { it.tagId })
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
    fun schemaSevenUpgradePreservesIdentityRecordsSearchAndTemplateData() {
        val owner = UUID.randomUUID()
        val epoch = UUID.randomUUID()
        val directory = EncryptedDatasetStore.directory(context, owner, epoch)
        val at = 1768449600000L
        val cursor = "room7-tag-upgrade-${UUID.randomUUID()}"
        lateinit var recordId: UUID
        val templateId = UUID.randomUUID()
        try {
            LocalFinanceRepository.openDataset(context, owner, epoch).use { repo ->
                val account = repo.createAccount("Room7迁移", "CNY", Money(200, 0, "CNY"), at)
                recordId =
                    repo.saveRecord(
                        null,
                        RecordInput.Expense(Money(20, 0, "CNY"), null, listOf(AccountAmount(account, Money(20, 0, "CNY"))), true, true),
                        at,
                        "Asia/Shanghai",
                        "迁移账单",
                        null,
                    )
                val record = repo.snapshot().records.single { it.id == recordId }
                repo.saveSearchPreferences(
                    SearchPreferencesEntity(
                        type = "EXPENSE",
                        fromDate = "",
                        throughDate = "",
                        currency = "CNY",
                        accountId = "",
                        categoryId = "",
                        minimumAmount = "10",
                        maximumAmount = "",
                        sort = "TIME_DESCENDING",
                    ),
                )
                repo.saveRecordTemplate(
                    FinanceRecordTemplate(
                        templateId,
                        "Room7模板",
                        record.input,
                        record.categoryId,
                        record.occurredAtMillis,
                        record.timezone,
                        record.remark,
                    ),
                )
            }
            EncryptedDatasetStore.open(context, owner, epoch).use { db ->
                val sql = db.openHelper.writableDatabase
                db.runInTransaction {
                    sql.execSQL("DROP TABLE `finance_record_tag`")
                    sql.execSQL("DROP TABLE `finance_tag`")
                    sql.execSQL("DROP TABLE `finance_tag_group`")
                    sql.execSQL(
                        "CREATE TABLE `dataset_search_preferences_v7` (`singleton` INTEGER NOT NULL, `type` TEXT NOT NULL, `fromDate` TEXT NOT NULL, `throughDate` TEXT NOT NULL, `currency` TEXT NOT NULL, `accountId` TEXT NOT NULL, `categoryId` TEXT NOT NULL, `minimumAmount` TEXT NOT NULL, `maximumAmount` TEXT NOT NULL, `sort` TEXT NOT NULL, PRIMARY KEY(`singleton`))",
                    )
                    sql.execSQL(
                        "INSERT INTO `dataset_search_preferences_v7` SELECT `singleton`,`type`,`fromDate`,`throughDate`,`currency`,`accountId`,`categoryId`,`minimumAmount`,`maximumAmount`,`sort` FROM `dataset_search_preferences`",
                    )
                    sql.execSQL("DROP TABLE `dataset_search_preferences`")
                    sql.execSQL("ALTER TABLE `dataset_search_preferences_v7` RENAME TO `dataset_search_preferences`")
                    sql.execSQL(
                        "CREATE TABLE `finance_record_template_v7` (`id` TEXT NOT NULL, `ownerId` TEXT NOT NULL, `epochId` TEXT NOT NULL, `name` TEXT NOT NULL, `inputJson` TEXT NOT NULL, `categoryId` TEXT, `occurredAtMillis` INTEGER NOT NULL, `timezone` TEXT NOT NULL, `remark` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `deletedAt` INTEGER, PRIMARY KEY(`id`))",
                    )
                    sql.execSQL(
                        "INSERT INTO `finance_record_template_v7` SELECT `id`,`ownerId`,`epochId`,`name`,`inputJson`,`categoryId`,`occurredAtMillis`,`timezone`,`remark`,`createdAt`,`updatedAt`,`deletedAt` FROM `finance_record_template`",
                    )
                    sql.execSQL("DROP TABLE `finance_record_template`")
                    sql.execSQL("ALTER TABLE `finance_record_template_v7` RENAME TO `finance_record_template`")
                    sql.execSQL("UPDATE `dataset_identity` SET `serverCursor` = ?, `lastAppliedSeq` = 23 WHERE `singleton` = 1", arrayOf(cursor))
                    sql.execSQL("UPDATE `room_master_table` SET `identity_hash` = 'e259bf1d91e06b2add70cf8f0c59e2b7' WHERE `id` = 42")
                }
                sql.execSQL("PRAGMA user_version = 7")
            }
            LocalFinanceRepository.openDataset(context, owner, epoch).use { repo ->
                val snapshot = repo.snapshot()
                assertEquals(listOf(recordId), snapshot.records.map { it.id })
                assertEquals(1, snapshot.tagGroups.count { it.isDefault })
                assertEquals("", repo.searchPreferences()?.tagIds)
                assertEquals("ANY", repo.searchPreferences()?.tagMatch)
                assertEquals("10", repo.searchPreferences()?.minimumAmount)
                assertEquals(templateId, repo.recordTemplates().single().id)
                assertEquals(emptyList<UUID>(), repo.recordTemplates().single().tagIds)
            }
            EncryptedDatasetStore.open(context, owner, epoch).use { db ->
                assertEquals(cursor, db.identity().read()?.serverCursor)
                assertEquals(23L, db.identity().read()?.lastAppliedSeq)
                db.openHelper.writableDatabase.query("PRAGMA user_version").use {
                    assertTrue(it.moveToFirst())
                    assertEquals(8, it.getInt(0))
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
    fun tagCreationRejectsMissingAndForeignDatasetGroups() {
        val owner = UUID.randomUUID()
        val epoch = UUID.randomUUID()
        val foreignOwner = UUID.randomUUID()
        val foreignEpoch = UUID.randomUUID()
        val localDirectory = EncryptedDatasetStore.directory(context, owner, epoch)
        val foreignDirectory = EncryptedDatasetStore.directory(context, foreignOwner, foreignEpoch)
        try {
            val foreignGroup = LocalFinanceRepository.openDataset(context, foreignOwner, foreignEpoch).use { repo ->
                repo.snapshot().tagGroups.single { it.isDefault }.id
            }
            LocalFinanceRepository.openDataset(context, owner, epoch).use { repo ->
                assertThrows(FinanceValidationException::class.java) {
                    repo.saveTag(null, UUID.randomUUID(), "Missing group", null, 0)
                }
                assertThrows(FinanceValidationException::class.java) {
                    repo.saveTag(null, foreignGroup, "Foreign group", null, 0)
                }
                assertTrue(repo.snapshot().tags.isEmpty())
            }
        } finally {
            KeyStore.getInstance("AndroidKeyStore").apply {
                load(null)
                deleteEntry(EncryptedDatasetStore.alias(owner, epoch))
                deleteEntry(EncryptedDatasetStore.alias(foreignOwner, foreignEpoch))
            }
            localDirectory.deleteRecursively()
            localDirectory.parentFile?.delete()
            foreignDirectory.deleteRecursively()
            foreignDirectory.parentFile?.delete()
        }
    }

    @Test
    fun canonicalTagDeleteRejectsChangedAssociationAndStaleDatasetPreviewAtomically() {
        val owner = UUID.randomUUID()
        val epoch = UUID.randomUUID()
        val directory = EncryptedDatasetStore.directory(context, owner, epoch)
        val groupId = UUID.randomUUID()
        val tagId = UUID.randomUUID()
        val recordId = UUID.randomUUID()
        try {
            EncryptedDatasetStore.open(context, owner, epoch).use { db ->
                initializeCanonicalTagTables(db)
                putCanonical(db, owner, epoch, "tag_groups", groupId, "name" to "分类", "sortOrder" to 0L, "isDefault" to true, "serverRevision" to 4L, "deletedAt" to null)
                putCanonical(db, owner, epoch, "tags", tagId, "groupId" to groupId.toString(), "name" to "目标", "color" to null, "sortOrder" to 0L, "archivedAt" to null, "serverRevision" to 7L, "deletedAt" to null)
                putCanonical(db, owner, epoch, "records", recordId, "serverRevision" to 11L, "deletedAt" to null)
                val linkId = UUID.randomUUID()
                putCanonical(db, owner, epoch, "record_tags", linkId, "recordId" to recordId.toString(), "tagId" to tagId.toString(), "serverRevision" to 2L, "deletedAt" to null)
                val commands = AccountTagCommands(db)
                val tag = FinanceTag(tagId, groupId, "目标", null, 0, null, 7)
                val preview = commands.previewDeleteTag(tag)
                assertThrows(IllegalArgumentException::class.java) {
                    commands.deleteTag(UUID.randomUUID(), preview.copy(epochId = UUID.randomUUID().toString()))
                }
                assertTrue(db.typedOutbox().all(owner.toString(), epoch.toString()).isEmpty())
                putCanonical(db, owner, epoch, "record_tags", UUID.randomUUID(), "recordId" to recordId.toString(), "tagId" to tagId.toString(), "serverRevision" to 1L, "deletedAt" to null)
                val before = SnapshotImporter.canonicalTables(db)
                assertThrows(IllegalArgumentException::class.java) { commands.deleteTag(UUID.randomUUID(), preview) }
                assertEquals(before.toString(), SnapshotImporter.canonicalTables(db).toString())
                assertTrue(db.typedOutbox().all(owner.toString(), epoch.toString()).isEmpty())
                assertTrue(db.accountMutations().intentsInOrder(owner.toString(), epoch.toString()).isEmpty())
            }
        } finally {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(EncryptedDatasetStore.alias(owner, epoch)) }
            directory.deleteRecursively()
            directory.parentFile?.delete()
        }
    }

    @Test
    fun canonicalTagDetachCapturesExactVersionsAndLeavesCanonicalFinancialFactsUntouched() {
        val owner = UUID.randomUUID()
        val epoch = UUID.randomUUID()
        val directory = EncryptedDatasetStore.directory(context, owner, epoch)
        val groupId = UUID.randomUUID()
        val tagId = UUID.randomUUID()
        val recordId = UUID.randomUUID()
        val templateId = UUID.randomUUID()
        val eventId = UUID.randomUUID()
        val ledgerId = UUID.randomUUID()
        val accountId = UUID.randomUUID()
        try {
            EncryptedDatasetStore.open(context, owner, epoch).use { db ->
                initializeCanonicalTagTables(db)
                putCanonical(db, owner, epoch, "ledgers", ledgerId, "name" to "账本", "baseCurrency" to "CNY", "isDefault" to true, "sortOrder" to 0L, "serverRevision" to 1L, "deletedAt" to null)
                putCanonical(db, owner, epoch, "accounts", accountId, "name" to "现金", "currency" to "CNY", "serverRevision" to 2L, "deletedAt" to null)
                putCanonical(db, owner, epoch, "tag_groups", groupId, "name" to "分类", "sortOrder" to 0L, "isDefault" to true, "serverRevision" to 4L, "deletedAt" to null)
                putCanonical(db, owner, epoch, "tags", tagId, "groupId" to groupId.toString(), "name" to "目标", "color" to null, "sortOrder" to 0L, "archivedAt" to null, "serverRevision" to 7L, "deletedAt" to null)
                putCanonical(db, owner, epoch, "financial_events", eventId, "ledgerId" to ledgerId.toString(), "kind" to "EXPENSE", "occurredAt" to "2026-05-15T10:11:12Z", "timezone" to "UTC")
                putCanonical(db, owner, epoch, "records", recordId, "ledgerId" to ledgerId.toString(), "eventId" to eventId.toString(), "status" to "POSTED", "direction" to "EXPENSE", "serverRevision" to 11L, "deletedAt" to null)
                putCanonical(db, owner, epoch, "record_allocations", UUID.randomUUID(), "recordId" to recordId.toString(), "accountId" to accountId.toString(), "role" to "PAYMENT", "recordCurrencyAmount" to JSONObject().put("unscaled", "1200").put("scale", 2).put("currency", "CNY"))
                putCanonical(db, owner, epoch, "account_postings", UUID.randomUUID(), "eventId" to eventId.toString(), "accountId" to accountId.toString(), "signed" to JSONObject().put("unscaled", "-1200").put("scale", 2).put("currency", "CNY"))
                putCanonical(db, owner, epoch, "record_tags", UUID.randomUUID(), "recordId" to recordId.toString(), "tagId" to tagId.toString(), "serverRevision" to 2L, "deletedAt" to null)
                putCanonical(db, owner, epoch, "record_templates", templateId, "serverRevision" to 13L, "deletedAt" to null)
                putCanonical(db, owner, epoch, "template_tags", UUID.randomUUID(), "templateId" to templateId.toString(), "tagId" to tagId.toString(), "serverRevision" to 3L, "deletedAt" to null)
                val commands = AccountTagCommands(db)
                val preview = commands.previewDeleteTag(FinanceTag(tagId, groupId, "目标", null, 0, null, 7))
                val factsBefore = listOf("financial_events", "records", "record_allocations", "account_postings")
                    .associateWith { SnapshotImporter.canonicalTables(db).getJSONArray(it).toString() }
                db.openHelper.writableDatabase.execSQL("UPDATE server_records SET server_revision=12 WHERE id=?", arrayOf(recordId.toString()))
                assertThrows(IllegalArgumentException::class.java) { commands.deleteTag(UUID.randomUUID(), preview) }
                assertTrue(db.typedOutbox().all(owner.toString(), epoch.toString()).isEmpty())
                db.openHelper.writableDatabase.execSQL("UPDATE server_records SET server_revision=11 WHERE id=?", arrayOf(recordId.toString()))
                commands.deleteTag(UUID.randomUUID(), preview)
                val outbox = db.typedOutbox().all(owner.toString(), epoch.toString()).single()
                assertEquals("tag", outbox.aggregateType)
                assertEquals("metadata.delete", outbox.commandType)
                assertEquals("DETACH", JSONObject(outbox.payloadJson).getString("strategy"))
                val versions = JSONObject(outbox.baseVersionsJson)
                assertEquals(7L, versions.getLong("tag:$tagId"))
                assertEquals(11L, versions.getLong("record:$recordId"))
                assertEquals(13L, versions.getLong("record_template:$templateId"))
                val factsAfter = factsBefore.keys.associateWith { SnapshotImporter.canonicalTables(db).getJSONArray(it).toString() }
                assertEquals(factsBefore, factsAfter)
            }
        } finally {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(EncryptedDatasetStore.alias(owner, epoch)) }
            directory.deleteRecursively()
            directory.parentFile?.delete()
        }
    }

    @Test
    fun canonicalGroupDeleteCapturesArchivedMembersAndRejectsChangedMemberSet() {
        val owner = UUID.randomUUID()
        val epoch = UUID.randomUUID()
        val directory = EncryptedDatasetStore.directory(context, owner, epoch)
        val sourceId = UUID.randomUUID()
        val defaultId = UUID.randomUUID()
        val liveTagId = UUID.randomUUID()
        val archivedTagId = UUID.randomUUID()
        try {
            EncryptedDatasetStore.open(context, owner, epoch).use { db ->
                initializeCanonicalTagTables(db)
                putCanonical(db, owner, epoch, "tag_groups", sourceId, "name" to "来源", "sortOrder" to 1L, "isDefault" to false, "serverRevision" to 5L, "deletedAt" to null)
                putCanonical(db, owner, epoch, "tag_groups", defaultId, "name" to "默认", "sortOrder" to 0L, "isDefault" to true, "serverRevision" to 6L, "deletedAt" to null)
                putCanonical(db, owner, epoch, "tags", liveTagId, "groupId" to sourceId.toString(), "name" to "活动", "color" to null, "sortOrder" to 0L, "archivedAt" to null, "serverRevision" to 8L, "deletedAt" to null)
                putCanonical(db, owner, epoch, "tags", archivedTagId, "groupId" to sourceId.toString(), "name" to "已归档", "color" to null, "sortOrder" to 1L, "archivedAt" to "2026-01-01T00:00:00Z", "serverRevision" to 9L, "deletedAt" to null)
                val commands = AccountTagCommands(db)
                val preview = commands.previewDeleteTagGroup(sourceId, defaultId)
                assertEquals(setOf(liveTagId, archivedTagId), preview.members.map { it.id }.toSet())
                putCanonical(db, owner, epoch, "tags", UUID.randomUUID(), "groupId" to sourceId.toString(), "name" to "后来新增", "color" to null, "sortOrder" to 2L, "archivedAt" to null, "serverRevision" to 1L, "deletedAt" to null)
                val before = SnapshotImporter.canonicalTables(db)
                assertThrows(IllegalArgumentException::class.java) { commands.deleteTagGroup(UUID.randomUUID(), preview) }
                assertEquals(before.toString(), SnapshotImporter.canonicalTables(db).toString())
                assertTrue(db.typedOutbox().all(owner.toString(), epoch.toString()).isEmpty())
                assertTrue(db.accountMutations().intentsInOrder(owner.toString(), epoch.toString()).isEmpty())
                db.openHelper.writableDatabase.execSQL("DELETE FROM server_tags WHERE id NOT IN (?,?)", arrayOf(liveTagId.toString(), archivedTagId.toString()))
                commands.deleteTagGroup(UUID.randomUUID(), preview)
                val outbox = db.typedOutbox().all(owner.toString(), epoch.toString()).single()
                val versions = JSONObject(outbox.baseVersionsJson)
                assertEquals("MOVE_TO_DEFAULT", JSONObject(outbox.payloadJson).getString("strategy"))
                assertEquals(4, versions.length())
                assertEquals(8L, versions.getLong("tag:$liveTagId"))
                assertEquals(9L, versions.getLong("tag:$archivedTagId"))
            }
        } finally {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(EncryptedDatasetStore.alias(owner, epoch)) }
            directory.deleteRecursively()
            directory.parentFile?.delete()
        }
    }

    @Test
    fun conflictingCanonicalTagDeleteCannotBeRetriedAsFreshOperation() {
        val owner = UUID.randomUUID()
        val epoch = UUID.randomUUID()
        val directory = EncryptedDatasetStore.directory(context, owner, epoch)
        val groupId = UUID.randomUUID()
        val tagId = UUID.randomUUID()
        try {
            EncryptedDatasetStore.open(context, owner, epoch).use { db ->
                initializeCanonicalTagTables(db)
                putCanonical(db, owner, epoch, "tag_groups", groupId, "name" to "分类", "sortOrder" to 0L, "isDefault" to true, "serverRevision" to 4L, "deletedAt" to null)
                putCanonical(db, owner, epoch, "tags", tagId, "groupId" to groupId.toString(), "name" to "目标", "color" to null, "sortOrder" to 0L, "archivedAt" to null, "serverRevision" to 7L, "deletedAt" to null)
                val commands = AccountTagCommands(db)
                commands.deleteTag(UUID.randomUUID(), commands.previewDeleteTag(FinanceTag(tagId, groupId, "目标", null, 0, null, 7)))
                val original = db.typedOutbox().all(owner.toString(), epoch.toString()).single()
                db.openHelper.writableDatabase.execSQL("UPDATE typed_sync_outbox SET state='CONFLICT' WHERE opId=?", arrayOf(original.opId))
                db.accountMutations().state(original.opId, "CONFLICT")
                assertThrows(IllegalArgumentException::class.java) {
                    AccountMutationRepository(db).retryConflict(UUID.randomUUID(), UUID.fromString(original.opId))
                }
                assertEquals(listOf(original.opId), db.typedOutbox().all(owner.toString(), epoch.toString()).map { it.opId })
                assertEquals("CONFLICT", db.typedOutbox().byOpId(original.opId)!!.state)
            }
        } finally {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(EncryptedDatasetStore.alias(owner, epoch)) }
            directory.deleteRecursively()
            directory.parentFile?.delete()
        }
    }

    @Test
    fun accountCategoryImportRollsBackLateConflictAndKeepsParentMappingInOneQueueTransaction() {
        val owner = UUID.randomUUID()
        val epoch = UUID.randomUUID()
        val ledger = UUID.randomUUID()
        val source = UUID.randomUUID()
        val child = UUID.randomUUID()
        val directory = EncryptedDatasetStore.directory(context, owner, epoch)
        try {
            EncryptedDatasetStore.open(context, owner, epoch).use { db ->
                initializeCanonicalTagTables(db)
                putCanonical(db, owner, epoch, "ledgers", ledger, "serverRevision" to 3L, "archivedAt" to null, "deletedAt" to null)
                putCanonical(db, owner, epoch, "categories", UUID.randomUUID(), "ledgerId" to ledger.toString(), "name" to "重复", "direction" to "EXPENSE",
                    "sortOrder" to 1L, "parentId" to null, "serverRevision" to 1L, "excludeBudget" to false, "archivedAt" to null, "deletedAt" to null)
                val snapshot = FinanceSnapshot(owner, epoch, ledger, emptyList(), emptyList(), emptyList(), emptyList(),
                    ledgers = listOf(FinanceLedger(ledger, "账本", "CNY", 0, true, null, 3)))
                val root = dev.icos.app.data.remote.SharedCategoryConfig(source, "EXPENSE", null, "新根", null, null, 0, false)
                val collision = dev.icos.app.data.remote.SharedCategoryConfig(UUID.randomUUID(), "EXPENSE", null, "重复", null, null, 2, false)
                val nested = dev.icos.app.data.remote.SharedCategoryConfig(child, "EXPENSE", source, "子分类", null, null, 0, false)
                val commands = AccountCategoryCommands(db)
                val device = UUID.randomUUID()
                assertThrows(IllegalArgumentException::class.java) { commands.importSharedCategories(device, snapshot, listOf(root, collision, nested)) }
                assertTrue(db.typedOutbox().all(owner.toString(), epoch.toString()).isEmpty())
                val mapping = commands.importSharedCategories(device, snapshot, listOf(nested, root))
                val queued = db.typedOutbox().all(owner.toString(), epoch.toString())
                assertEquals(2, queued.size)
                assertEquals(mapping.getValue(source).toString(), queued.first().aggregateId)
                val childCommand = queued.single { it.aggregateId == mapping.getValue(child).toString() }
                assertEquals(mapping.getValue(source).toString(), JSONObject(childCommand.payloadJson).getJSONObject("entity").getString("parentId"))
                assertEquals(1L, JSONObject(childCommand.baseVersionsJson).getLong("category:${mapping.getValue(source)}"))
                assertEquals(1, SnapshotImporter.canonicalTables(db).getJSONArray("categories").length())
            }
        } finally {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(EncryptedDatasetStore.alias(owner, epoch)) }
            directory.deleteRecursively()
            directory.parentFile?.delete()
        }
    }

    @Test
    fun accountBulkDeletePreflightsTheWholeBatchAndQueuesOneAtomicCommand() {
        val owner = UUID.randomUUID()
        val epoch = UUID.randomUUID()
        val ledger = UUID.randomUUID()
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        val directory = EncryptedDatasetStore.directory(context, owner, epoch)
        try {
            EncryptedDatasetStore.open(context, owner, epoch).use { db ->
                initializeCanonicalTagTables(db)
                putCanonical(db, owner, epoch, "ledgers", ledger, "serverRevision" to 3L)
                for (id in listOf(first, second)) putCanonical(db, owner, epoch, "records", id,
                    "ledgerId" to ledger.toString(), "eventId" to UUID.randomUUID().toString(), "status" to "POSTED",
                    "direction" to "EXPENSE", "claimState" to "NONE", "serverRevision" to 5L, "deletedAt" to null)
                val repository = AccountMutationRepository(db)
                val device = UUID.randomUUID()
                val before = SnapshotImporter.canonicalTables(db).getJSONArray("records").toString()
                assertThrows(IllegalArgumentException::class.java) {
                    repository.deleteRecords(device, linkedMapOf(first to 5L, second to 4L))
                }
                assertTrue(db.typedOutbox().all(owner.toString(), epoch.toString()).isEmpty())
                repository.deleteRecords(device, linkedMapOf(first to 5L, second to 5L))
                val command = db.typedOutbox().all(owner.toString(), epoch.toString()).single()
                assertEquals("record.bulkDelete", command.commandType)
                assertEquals("record_batch", command.aggregateType)
                val payload = JSONObject(command.payloadJson)
                assertEquals("REJECT", payload.getString("dependencyStrategy"))
                assertEquals(listOf(first.toString(), second.toString()), (0..1).map { payload.getJSONArray("recordIds").getString(it) })
                val versions = JSONObject(command.baseVersionsJson)
                assertEquals(5L, versions.getLong("record:$first"))
                assertEquals(5L, versions.getLong("record:$second"))
                assertEquals(3L, versions.getLong("ledger:$ledger"))
                assertNull(db.accountMutations().activePrediction(command.opId))
                assertEquals(before, SnapshotImporter.canonicalTables(db).getJSONArray("records").toString())
                assertThrows(IllegalStateException::class.java) { repository.deleteRecords(device, mapOf(first to 5L)) }
                assertEquals(1, db.typedOutbox().all(owner.toString(), epoch.toString()).size)
            }
        } finally {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(EncryptedDatasetStore.alias(owner, epoch)) }
            directory.deleteRecursively()
            directory.parentFile?.delete()
        }
    }

    @Test
    fun setBudgetEnabledQueuesCanonicalOnlyCasMarkerWithoutPredictingAndRejectsStaleRevision() {
        val owner = UUID.randomUUID()
        val epoch = UUID.randomUUID()
        val ledgerId = UUID.randomUUID()
        val directory = EncryptedDatasetStore.directory(context, owner, epoch)
        try {
            EncryptedDatasetStore.open(context, owner, epoch).use { db ->
                initializeCanonicalTagTables(db)
                putCanonical(db, owner, epoch, "ledgers", ledgerId, "name" to "账本", "baseCurrency" to "CNY",
                    "isDefault" to true, "sortOrder" to 0L, "serverRevision" to 3L, "archivedAt" to null, "deletedAt" to null)
                putCanonical(db, owner, epoch, "budgets", ledgerId, "ledgerId" to ledgerId.toString(),
                    "serverRevision" to 7L, "enabled" to true, "deletedAt" to null)
                val before = SnapshotImporter.canonicalTables(db).getJSONArray("budgets").toString()
                val repository = AccountMutationRepository(db)
                val deviceId = UUID.randomUUID()
                repository.setBudgetEnabled(deviceId, ledgerId, 7L, false)

                val outbox = db.typedOutbox().all(owner.toString(), epoch.toString()).single()
                assertEquals("budget", outbox.aggregateType)
                assertEquals(ledgerId.toString(), outbox.aggregateId)
                assertEquals("budget.setEnabled", outbox.commandType)
                assertEquals(deviceId.toString(), outbox.deviceId)
                assertEquals(setOf("budget:$ledgerId", "ledger:$ledgerId"), JSONObject(outbox.baseVersionsJson).keys().asSequence().toSet())
                assertEquals(7L, JSONObject(outbox.baseVersionsJson).getLong("budget:$ledgerId"))
                assertEquals(3L, JSONObject(outbox.baseVersionsJson).getLong("ledger:$ledgerId"))
                assertEquals(false, JSONObject(outbox.payloadJson).getBoolean("enabled"))
                val marker = db.accountMutations().activePrediction(outbox.opId)!!
                assertEquals("budget", marker.aggregateType)
                assertEquals(ledgerId.toString(), marker.aggregateId)
                assertEquals(outbox.payloadJson, marker.payloadJson)
                val projected = SnapshotImporter.canonicalTables(db)
                CanonicalAccountRepository.applyPredictions(projected, listOf(marker),
                    db.accountMutations().intentsInOrder(owner.toString(), epoch.toString()), owner, epoch)
                assertEquals(before, projected.getJSONArray("budgets").toString())
                assertEquals(before, SnapshotImporter.canonicalTables(db).getJSONArray("budgets").toString())

                assertThrows(IllegalArgumentException::class.java) {
                    repository.setBudgetEnabled(deviceId, ledgerId, 6L, true)
                }
                assertEquals(1, db.typedOutbox().all(owner.toString(), epoch.toString()).size)
                assertEquals(before, SnapshotImporter.canonicalTables(db).getJSONArray("budgets").toString())
            }
        } finally {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(EncryptedDatasetStore.alias(owner, epoch)) }
            directory.deleteRecursively()
            directory.parentFile?.delete()
        }
    }

    @Test
    fun allPendingBudgetEnableStatesBlockConfigureResetAndAnotherToggle() {
        val owner = UUID.randomUUID()
        val epoch = UUID.randomUUID()
        val ledgerId = UUID.randomUUID()
        val directory = EncryptedDatasetStore.directory(context, owner, epoch)
        try {
            EncryptedDatasetStore.open(context, owner, epoch).use { db ->
                initializeCanonicalTagTables(db)
                putCanonical(db, owner, epoch, "ledgers", ledgerId, "name" to "账本", "baseCurrency" to "CNY",
                    "isDefault" to true, "sortOrder" to 0L, "serverRevision" to 3L, "archivedAt" to null, "deletedAt" to null)
                putCanonical(db, owner, epoch, "budgets", ledgerId, "ledgerId" to ledgerId.toString(),
                    "serverRevision" to 7L, "enabled" to true, "deletedAt" to null)
                val repository = AccountMutationRepository(db)
                val deviceId = UUID.randomUUID()
                repository.setBudgetEnabled(deviceId, ledgerId, 7L, false)
                val operation = db.typedOutbox().all(owner.toString(), epoch.toString()).single()
                for (state in listOf("QUEUED", "AMBIGUOUS", "ACKED")) {
                    db.openHelper.writableDatabase.execSQL("UPDATE typed_sync_outbox SET state=? WHERE opId=?", arrayOf(state, operation.opId))
                    db.accountMutations().state(operation.opId, state)
                    assertThrows(IllegalStateException::class.java) {
                        repository.configureBudget(deviceId, ledgerId, ReportPeriod.MONTH, BudgetScope.DEFAULT, "2026-01-01",
                            listOf(null to Money(1000, 0, "CNY")), 7L)
                    }
                    assertThrows(IllegalStateException::class.java) {
                        repository.resetBudget(deviceId, ledgerId, ReportPeriod.MONTH, "2026-01-01", false, 7L)
                    }
                    assertThrows(IllegalStateException::class.java) {
                        repository.setBudgetEnabled(deviceId, ledgerId, 7L, true)
                    }
                    assertEquals(1, db.typedOutbox().all(owner.toString(), epoch.toString()).size)
                }
            }
        } finally {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(EncryptedDatasetStore.alias(owner, epoch)) }
            directory.deleteRecursively()
            directory.parentFile?.delete()
        }
    }

    @Test
    fun categoryBudgetExclusionUsesCompleteMetadataAndRejectsHistoricalDependencies() {
        val owner = UUID.randomUUID()
        val epoch = UUID.randomUUID()
        val directory = EncryptedDatasetStore.directory(context, owner, epoch)
        val ledgerId = UUID.randomUUID()
        val categoryId = UUID.randomUUID()
        try {
            EncryptedDatasetStore.open(context, owner, epoch).use { db ->
                initializeCanonicalTagTables(db)
                putCanonical(db, owner, epoch, "ledgers", ledgerId, "name" to "账本", "baseCurrency" to "CNY",
                    "isDefault" to true, "sortOrder" to 0L, "serverRevision" to 3L, "archivedAt" to null, "deletedAt" to null)
                putCanonical(db, owner, epoch, "categories", categoryId, "ledgerId" to ledgerId.toString(),
                    "direction" to "EXPENSE", "parentId" to null, "name" to "日常", "iconRef" to null,
                    "color" to "#123456", "sortOrder" to 2L, "archivedAt" to null, "excludeBudget" to false,
                    "serverRevision" to 5L, "deletedAt" to null)
                putCanonical(db, owner, epoch, "records", UUID.randomUUID(), "categoryId" to categoryId.toString(),
                    "serverRevision" to 8L, "deletedAt" to 123L)
                val category = FinanceCategory(categoryId, "日常", Direction.EXPENSE, localRevision = 5, ledgerId = ledgerId,
                    color = "#123456", excludeBudget = false)
                val commands = AccountCategoryCommands(db)
                assertThrows(IllegalArgumentException::class.java) {
                    commands.create(UUID.randomUUID(), FinanceLedger(ledgerId, "账本", "CNY", 0, true, null, 3), "收入分类", Direction.INCOME, null, true)
                }
                assertThrows(IllegalArgumentException::class.java) {
                    commands.setBudgetExcluded(UUID.randomUUID(), category, true, FinanceLedger(ledgerId, "账本", "CNY", 0, true, null, 3), null)
                }
                assertTrue(db.typedOutbox().all(owner.toString(), epoch.toString()).isEmpty())
                db.openHelper.writableDatabase.execSQL("DELETE FROM `server_records` WHERE `category_id` = ?", arrayOf(categoryId.toString()))
                commands.setBudgetExcluded(UUID.randomUUID(), category, true, FinanceLedger(ledgerId, "账本", "CNY", 0, true, null, 3), null)
                val outbox = db.typedOutbox().all(owner.toString(), epoch.toString()).single()
                assertEquals("metadata.update", outbox.commandType)
                val changes = JSONObject(outbox.payloadJson).getJSONObject("changes")
                assertEquals("日常", changes.getString("name"))
                assertEquals("EXPENSE", changes.getString("direction"))
                assertEquals(ledgerId.toString(), changes.getString("ledgerId"))
                assertEquals("#123456", changes.getString("color"))
                assertTrue(changes.getBoolean("excludeBudget"))
                assertNotNull(db.accountMutations().activePrediction(outbox.opId))
                assertFalse(SnapshotImporter.canonicalTables(db).getJSONArray("categories").getJSONObject(0).canonicalBoolean("excludeBudget"))
            }
        } finally {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(EncryptedDatasetStore.alias(owner, epoch)) }
            directory.deleteRecursively()
            directory.parentFile?.delete()
        }
    }

    @Test
    fun canonicalCategoryMigrateDeleteCapturesDependencyVersionsAndLeavesFinancialFactsUntouched() {
        val owner = UUID.randomUUID()
        val epoch = UUID.randomUUID()
        val directory = EncryptedDatasetStore.directory(context, owner, epoch)
        val ids = CategoryMigrationFixture(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
        try {
            EncryptedDatasetStore.open(context, owner, epoch).use { db ->
                initializeCanonicalTagTables(db)
                seedCategoryMigration(db, owner, epoch, ids)
                val commands = AccountCategoryCommands(db)
                val preview = commands.previewMigrateDelete(categoryMigrationSource(ids), categoryMigrationTarget(ids))
                assertEquals(1, preview.recordCount)
                assertEquals(1, preview.templateCount)
                assertEquals(1, preview.budgetCount)
                val facts = listOf("records", "budget_configs", "budget_entries", "budgets")
                val before = facts.associateWith { SnapshotImporter.canonicalTables(db).getJSONArray(it).toString() }
                commands.deleteMigrating(UUID.randomUUID(), preview)
                val outbox = db.typedOutbox().all(owner.toString(), epoch.toString()).single()
                assertEquals("category", outbox.aggregateType)
                assertEquals("metadata.delete", outbox.commandType)
                val payload = JSONObject(outbox.payloadJson)
                assertEquals("MIGRATE_RECORDS", payload.getString("strategy"))
                assertEquals(ids.targetId.toString(), payload.getString("targetId"))
                val versions = JSONObject(outbox.baseVersionsJson)
                assertEquals(3L, versions.getLong("category:${ids.sourceId}"))
                assertEquals(5L, versions.getLong("category:${ids.targetId}"))
                assertEquals(11L, versions.getLong("record:${ids.recordId}"))
                assertEquals(13L, versions.getLong("record_template:${ids.templateId}"))
                assertEquals(17L, versions.getLong("budget:${ids.budgetId}"))
                assertEquals(before, facts.associateWith { SnapshotImporter.canonicalTables(db).getJSONArray(it).toString() })
            }
        } finally {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(EncryptedDatasetStore.alias(owner, epoch)) }
            directory.deleteRecursively()
            directory.parentFile?.delete()
        }
    }

    @Test
    fun canonicalCategoryMigrateDeleteRejectsChangedBudgetEntryAtomically() {
        val owner = UUID.randomUUID()
        val epoch = UUID.randomUUID()
        val directory = EncryptedDatasetStore.directory(context, owner, epoch)
        val ids = CategoryMigrationFixture(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
        try {
            EncryptedDatasetStore.open(context, owner, epoch).use { db ->
                initializeCanonicalTagTables(db)
                seedCategoryMigration(db, owner, epoch, ids)
                val commands = AccountCategoryCommands(db)
                val preview = commands.previewMigrateDelete(categoryMigrationSource(ids), categoryMigrationTarget(ids))
                db.openHelper.writableDatabase.execSQL(
                    "UPDATE `server_budget_entries` SET `amount` = ? WHERE `category_id` = ?",
                    arrayOf(JSONObject().put("unscaled", "9999").put("scale", 2).put("currency", "CNY").toString(), ids.sourceId.toString()),
                )
                val before = SnapshotImporter.canonicalTables(db)
                assertThrows(IllegalArgumentException::class.java) { commands.deleteMigrating(UUID.randomUUID(), preview) }
                assertEquals(before.toString(), SnapshotImporter.canonicalTables(db).toString())
                assertTrue(db.typedOutbox().all(owner.toString(), epoch.toString()).isEmpty())
                assertTrue(db.accountMutations().intentsInOrder(owner.toString(), epoch.toString()).isEmpty())
            }
        } finally {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(EncryptedDatasetStore.alias(owner, epoch)) }
            directory.deleteRecursively()
            directory.parentFile?.delete()
        }
    }

    @Test
    fun accountDeleteUsesExactCrossScalePostingBalanceAndPreservesCanonicalHistory() {
        val owner = UUID.randomUUID()
        val epoch = UUID.randomUUID()
        val directory = EncryptedDatasetStore.directory(context, owner, epoch)
        val accountId = UUID.randomUUID()
        val openingEventId = UUID.randomUUID()
        val recordEventId = UUID.randomUUID()
        val openingPostingId = UUID.randomUUID()
        val recordPostingId = UUID.randomUUID()
        try {
            EncryptedDatasetStore.open(context, owner, epoch).use { db ->
                initializeCanonicalTagTables(db)
                val groupId = UUID.randomUUID()
                putCanonical(db, owner, epoch, "account_groups", groupId, "name" to "默认", "sortOrder" to 0L,
                    "isDefault" to true, "serverRevision" to 1L, "deletedAt" to null)
                putCanonical(db, owner, epoch, "accounts", accountId, "name" to "零余额", "currency" to "CNY",
                    "type" to "ASSET", "groupId" to groupId.toString(), "openingEventId" to openingEventId.toString(),
                    "serverRevision" to 7L, "visibilityMode" to "ALL", "includeInAssets" to true, "selectable" to true,
                    "sortOrder" to 0L, "archivedAt" to null, "deletedAt" to null)
                putCanonical(db, owner, epoch, "financial_events", openingEventId, "ledgerId" to UUID.randomUUID().toString(),
                    "kind" to "OPENING", "status" to "POSTED", "occurredAt" to "2026-05-15T10:11:12Z")
                putCanonical(db, owner, epoch, "financial_events", recordEventId, "ledgerId" to UUID.randomUUID().toString(),
                    "kind" to "EXPENSE", "status" to "POSTED", "occurredAt" to "2026-05-16T10:11:12Z")
                putCanonical(db, owner, epoch, "account_postings", openingPostingId, "eventId" to openingEventId.toString(),
                    "accountId" to accountId.toString(), "role" to "OPENING",
                    "signed" to JSONObject().put("unscaled", "100").put("scale", 2).put("currency", "CNY"), "deletedAt" to null)
                putCanonical(db, owner, epoch, "account_postings", recordPostingId, "eventId" to recordEventId.toString(),
                    "accountId" to accountId.toString(), "role" to "RECORD",
                    "signed" to JSONObject().put("unscaled", "-1000").put("scale", 3).put("currency", "CNY"), "deletedAt" to null)
                val account = FinanceAccount(accountId, "零余额", "CNY", Money(9900, 0, "CNY"), 7L, null,
                    true, true, groupId, 0, "ASSET", "ALL", emptySet())
                val commands = AccountDeletionCommands(db)
                val preview = commands.preview(account)
                val financialTables = listOf("accounts", "financial_events", "account_postings")
                val before = financialTables.associateWith { SnapshotImporter.canonicalTables(db).getJSONArray(it).toString() }
                commands.delete(UUID.randomUUID(), preview, "零余额")
                val outbox = db.typedOutbox().all(owner.toString(), epoch.toString()).single()
                assertEquals("account", outbox.aggregateType)
                assertEquals(accountId.toString(), outbox.aggregateId)
                assertEquals("metadata.delete", outbox.commandType)
                assertEquals("KEEP_RECORDS", JSONObject(outbox.payloadJson).getString("strategy"))
                assertEquals(setOf("account:$accountId"), JSONObject(outbox.baseVersionsJson).keys().asSequence().toSet())
                assertEquals(7L, JSONObject(outbox.baseVersionsJson).getLong("account:$accountId"))
                assertEquals(before, financialTables.associateWith { SnapshotImporter.canonicalTables(db).getJSONArray(it).toString() })
            }
        } finally {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(EncryptedDatasetStore.alias(owner, epoch)) }
            directory.deleteRecursively()
            directory.parentFile?.delete()
        }
    }

    @Test
    fun accountDeleteRejectsNonzeroFutureAndStaleEpochWithoutEnqueue() {
        val owner = UUID.randomUUID()
        val epoch = UUID.randomUUID()
        val directory = EncryptedDatasetStore.directory(context, owner, epoch)
        val accountId = UUID.randomUUID()
        val openingEventId = UUID.randomUUID()
        val recordEventId = UUID.randomUUID()
        val postingId = UUID.randomUUID()
        try {
            EncryptedDatasetStore.open(context, owner, epoch).use { db ->
                initializeCanonicalTagTables(db)
                val groupId = UUID.randomUUID()
                val ledgerId = UUID.randomUUID()
                putCanonical(db, owner, epoch, "account_groups", groupId, "name" to "默认", "sortOrder" to 0L,
                    "isDefault" to true, "serverRevision" to 1L, "deletedAt" to null)
                putCanonical(db, owner, epoch, "accounts", accountId, "name" to "受限账户", "currency" to "CNY",
                    "type" to "ASSET", "groupId" to groupId.toString(), "openingEventId" to openingEventId.toString(),
                    "serverRevision" to 5L, "visibilityMode" to "ALL", "includeInAssets" to true, "selectable" to true,
                    "sortOrder" to 0L, "archivedAt" to null, "deletedAt" to null)
                putCanonical(db, owner, epoch, "financial_events", openingEventId, "ledgerId" to ledgerId.toString(),
                    "kind" to "OPENING", "status" to "POSTED", "occurredAt" to "2026-05-15T10:11:12Z")
                putCanonical(db, owner, epoch, "financial_events", recordEventId, "ledgerId" to ledgerId.toString(),
                    "kind" to "EXPENSE", "status" to "POSTED", "occurredAt" to "2026-05-16T10:11:12Z")
                putCanonical(db, owner, epoch, "account_postings", postingId, "eventId" to recordEventId.toString(),
                    "accountId" to accountId.toString(), "role" to "RECORD",
                    "signed" to JSONObject().put("unscaled", "1").put("scale", 0).put("currency", "CNY"), "deletedAt" to null)
                val account = FinanceAccount(accountId, "受限账户", "CNY", Money(0, 0, "CNY"), 5L, null,
                    true, true, groupId, 0, "ASSET", "ALL", emptySet())
                val commands = AccountDeletionCommands(db)
                assertThrows(FinanceValidationException::class.java) { commands.preview(account) }
                assertTrue(db.typedOutbox().all(owner.toString(), epoch.toString()).isEmpty())

                db.openHelper.writableDatabase.execSQL(
                    "UPDATE `server_account_postings` SET `signed` = ? WHERE `id` = ?",
                    arrayOf(JSONObject().put("unscaled", "0").put("scale", 0).put("currency", "CNY").toString(), postingId.toString()),
                )
                db.openHelper.writableDatabase.execSQL(
                    "UPDATE `server_financial_events` SET `occurred_at` = ? WHERE `id` = ?",
                    arrayOf("2999-01-01T00:00:00Z", recordEventId.toString()),
                )
                assertThrows(FinanceValidationException::class.java) { commands.preview(account) }
                assertTrue(db.typedOutbox().all(owner.toString(), epoch.toString()).isEmpty())

                db.openHelper.writableDatabase.execSQL(
                    "UPDATE `server_financial_events` SET `occurred_at` = ? WHERE `id` = ?",
                    arrayOf("2026-05-16T10:11:12Z", recordEventId.toString()),
                )
                val preview = commands.preview(account)
                assertThrows(FinanceValidationException::class.java) {
                    commands.delete(UUID.randomUUID(), preview.copy(epochId = UUID.randomUUID().toString()), "受限账户")
                }
                assertTrue(db.typedOutbox().all(owner.toString(), epoch.toString()).isEmpty())
                assertTrue(db.accountMutations().intentsInOrder(owner.toString(), epoch.toString()).isEmpty())
            }
        } finally {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(EncryptedDatasetStore.alias(owner, epoch)) }
            directory.deleteRecursively()
            directory.parentFile?.delete()
        }
    }

    @Test
    fun categoryDeleteRecordsPreviewsExactReversalCapturesCasAndKeepsCanonicalFacts() {
        val owner = UUID.randomUUID()
        val epoch = UUID.randomUUID()
        val directory = EncryptedDatasetStore.directory(context, owner, epoch)
        val ids = CategoryRecordDeleteFixture.random()
        try {
            EncryptedDatasetStore.open(context, owner, epoch).use { db ->
                initializeCanonicalTagTables(db)
                seedCategoryRecordDeleteExpense(db, owner, epoch, ids)
                val commands = AccountCategoryDeletionCommands(db)
                val preview = commands.preview(ids.source)
                assertEquals(owner.toString(), preview.ownerId)
                assertEquals(epoch.toString(), preview.epochId)
                assertEquals(ids.source.id, preview.source.id)
                assertEquals(1, preview.recordCount)
                assertEquals(listOf(AccountCategoryDeletionCommands.AccountImpact(ids.accountId, "现金", Money(1234, 2, "CNY"))), preview.accountImpacts)

                val financialTables = listOf(
                    "financial_events", "records", "record_allocations", "account_postings",
                    "stat_contributions", "budget_contributions", "record_tags",
                )
                val before = financialTables.associateWith { SnapshotImporter.canonicalTables(db).getJSONArray(it).toString() }
                commands.delete(UUID.randomUUID(), preview, ids.source.name)

                val outbox = db.typedOutbox().all(owner.toString(), epoch.toString()).single()
                assertEquals("category", outbox.aggregateType)
                assertEquals(ids.source.id.toString(), outbox.aggregateId)
                assertEquals("metadata.delete", outbox.commandType)
                assertEquals("DELETE_RECORDS", JSONObject(outbox.payloadJson).getString("strategy"))
                val versions = JSONObject(outbox.baseVersionsJson)
                assertEquals(
                    setOf(
                        "category:${ids.source.id}", "ledger:${ids.ledgerId}", "record:${ids.recordId}",
                        "account:${ids.accountId}", "tag:${ids.tagId}",
                    ),
                    versions.keys().asSequence().toSet(),
                )
                assertEquals(5L, versions.getLong("category:${ids.source.id}"))
                assertEquals(3L, versions.getLong("ledger:${ids.ledgerId}"))
                assertEquals(11L, versions.getLong("record:${ids.recordId}"))
                assertEquals(7L, versions.getLong("account:${ids.accountId}"))
                assertEquals(9L, versions.getLong("tag:${ids.tagId}"))
                assertEquals(before, financialTables.associateWith { SnapshotImporter.canonicalTables(db).getJSONArray(it).toString() })
                assertTrue(db.accountMutations().intentsInOrder(owner.toString(), epoch.toString()).isNotEmpty())
            }
        } finally {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(EncryptedDatasetStore.alias(owner, epoch)) }
            directory.deleteRecursively()
            directory.parentFile?.delete()
        }
    }

    @Test
    fun categoryDeleteRecordsRejectsStaleMembershipLedgerAmountAndEpochWithoutEnqueue() {
        listOf("epoch", "membership", "ledger", "amount").forEach { drift ->
            val owner = UUID.randomUUID()
            val epoch = UUID.randomUUID()
            val directory = EncryptedDatasetStore.directory(context, owner, epoch)
            val ids = CategoryRecordDeleteFixture.random()
            try {
                EncryptedDatasetStore.open(context, owner, epoch).use { db ->
                    initializeCanonicalTagTables(db)
                    seedCategoryRecordDeleteExpense(db, owner, epoch, ids)
                    val commands = AccountCategoryDeletionCommands(db)
                    val preview = commands.preview(ids.source)
                    when (drift) {
                        "epoch" -> assertThrows(FinanceValidationException::class.java) {
                            commands.delete(UUID.randomUUID(), preview.copy(epochId = UUID.randomUUID().toString()), ids.source.name)
                        }
                        "membership" -> db.openHelper.writableDatabase.execSQL(
                            "UPDATE `server_records` SET `category_id` = ? WHERE `id` = ?",
                            arrayOf(UUID.randomUUID().toString(), ids.recordId.toString()),
                        )
                        "ledger" -> db.openHelper.writableDatabase.execSQL(
                            "UPDATE `server_ledgers` SET `name` = ? WHERE `id` = ?",
                            arrayOf("账本已漂移", ids.ledgerId.toString()),
                        )
                        "amount" -> db.openHelper.writableDatabase.execSQL(
                            "UPDATE `server_account_postings` SET `signed` = ? WHERE `event_id` = ?",
                            arrayOf(JSONObject().put("unscaled", "-1235").put("scale", 2).put("currency", "CNY").toString(), ids.eventId.toString()),
                        )
                    }
                    if (drift != "epoch") {
                        assertThrows("$drift drift", FinanceValidationException::class.java) {
                            commands.delete(UUID.randomUUID(), preview, ids.source.name)
                        }
                    }
                    assertTrue(db.typedOutbox().all(owner.toString(), epoch.toString()).isEmpty())
                    assertTrue(db.accountMutations().intentsInOrder(owner.toString(), epoch.toString()).isEmpty())
                }
            } finally {
                KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(EncryptedDatasetStore.alias(owner, epoch)) }
                directory.deleteRecursively()
                directory.parentFile?.delete()
            }
        }
    }

    @Test
    fun categoryDeleteRecordsRejectsFeeBonusChildrenAndInstallmentOrRecurrenceLinks() {
        listOf("child", "fee_relation", "bonus_relation", "installment", "recurrence").forEach { dependency ->
            val owner = UUID.randomUUID()
            val epoch = UUID.randomUUID()
            val directory = EncryptedDatasetStore.directory(context, owner, epoch)
            val ids = CategoryRecordDeleteFixture.random()
            try {
                EncryptedDatasetStore.open(context, owner, epoch).use { db ->
                    initializeCanonicalTagTables(db)
                    seedCategoryRecordDeleteExpense(db, owner, epoch, ids)
                    when (dependency) {
                        "child" -> putCanonical(
                            db, owner, epoch, "records", UUID.randomUUID(),
                            "ledgerId" to ids.ledgerId.toString(), "eventId" to UUID.randomUUID().toString(),
                            "parentRecordId" to ids.recordId.toString(), "categoryId" to ids.source.id.toString(),
                            "status" to "POSTED", "direction" to "EXPENSE", "serverRevision" to 2L, "deletedAt" to null,
                        )
                        "fee_relation", "bonus_relation" -> putCanonical(
                            db, owner, epoch, "event_relations", UUID.randomUUID(),
                            "parentEventId" to ids.eventId.toString(), "childEventId" to UUID.randomUUID().toString(),
                            "kind" to if (dependency == "fee_relation") "FEE" else "DISCOUNT", "deletedAt" to null,
                        )
                        "installment" -> putCanonical(
                            db, owner, epoch, "installment_plans", UUID.randomUUID(),
                            "sourceRecordId" to ids.recordId.toString(), "serverRevision" to 3L, "deletedAt" to null,
                        )
                        "recurrence" -> putCanonical(
                            db, owner, epoch, "recurrence_occurrences", UUID.randomUUID(),
                            "recordId" to ids.recordId.toString(), "deletedAt" to null,
                        )
                    }
                    val commands = AccountCategoryDeletionCommands(db)
                    assertThrows("$dependency dependency", IllegalArgumentException::class.java) { commands.preview(ids.source) }
                    assertTrue(db.typedOutbox().all(owner.toString(), epoch.toString()).isEmpty())
                    assertTrue(db.accountMutations().intentsInOrder(owner.toString(), epoch.toString()).isEmpty())
                }
            } finally {
                KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(EncryptedDatasetStore.alias(owner, epoch)) }
                directory.deleteRecursively()
                directory.parentFile?.delete()
            }
        }
    }

    private data class CategoryRecordDeleteFixture(
        val ledgerId: UUID,
        val source: FinanceCategory,
        val recordId: UUID,
        val eventId: UUID,
        val accountId: UUID,
        val tagId: UUID,
    ) {
        companion object {
            fun random() = CategoryRecordDeleteFixture(
                UUID.randomUUID(),
                FinanceCategory(UUID.randomUUID(), "日常", Direction.EXPENSE, ledgerId = UUID(0, 0), localRevision = 5),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            ).let { fixture -> fixture.copy(source = fixture.source.copy(ledgerId = fixture.ledgerId)) }
        }
    }

    private fun seedCategoryRecordDeleteExpense(
        db: DatasetDatabase,
        owner: UUID,
        epoch: UUID,
        ids: CategoryRecordDeleteFixture,
    ) {
        val groupId = UUID.randomUUID()
        val openingEventId = UUID.randomUUID()
        val amount = JSONObject().put("unscaled", "1234").put("scale", 2).put("currency", "CNY")
        putCanonical(db, owner, epoch, "ledgers", ids.ledgerId, "name" to "主账本", "baseCurrency" to "CNY",
            "ownerId" to owner.toString(), "datasetEpoch" to epoch.toString(), "serverRevision" to 3L, "deletedAt" to null)
        putCanonical(db, owner, epoch, "categories", ids.source.id, "ledgerId" to ids.ledgerId.toString(), "name" to ids.source.name,
            "direction" to "EXPENSE", "parentId" to null, "sortOrder" to 0L, "serverRevision" to 5L,
            "archivedAt" to null, "excludeBudget" to false, "deletedAt" to null)
        putCanonical(db, owner, epoch, "account_groups", groupId, "name" to "默认", "sortOrder" to 0L,
            "isDefault" to true, "serverRevision" to 1L, "deletedAt" to null)
        putCanonical(db, owner, epoch, "accounts", ids.accountId, "name" to "现金", "currency" to "CNY", "type" to "ASSET",
            "ownerId" to owner.toString(), "datasetEpoch" to epoch.toString(), "groupId" to groupId.toString(), "openingEventId" to openingEventId.toString(), "serverRevision" to 7L,
            "visibilityMode" to "ALL", "includeInAssets" to true, "selectable" to true, "sortOrder" to 0L,
            "archivedAt" to null, "deletedAt" to null)
        putCanonical(db, owner, epoch, "financial_events", openingEventId, "ledgerId" to ids.ledgerId.toString(),
            "ownerId" to owner.toString(), "datasetEpoch" to epoch.toString(), "kind" to "OPENING", "status" to "POSTED", "occurredAt" to "2026-05-15T10:11:12Z", "timezone" to "UTC")
        putCanonical(db, owner, epoch, "financial_events", ids.eventId, "ledgerId" to ids.ledgerId.toString(),
            "ownerId" to owner.toString(), "datasetEpoch" to epoch.toString(),
            "kind" to "EXPENSE", "status" to "POSTED", "origin" to "USER", "occurredAt" to "2026-05-16T10:11:12Z", "timezone" to "UTC")
        putCanonical(db, owner, epoch, "records", ids.recordId, "ledgerId" to ids.ledgerId.toString(), "eventId" to ids.eventId.toString(),
            "categoryId" to ids.source.id.toString(), "status" to "POSTED", "direction" to "EXPENSE", "gross" to amount,
            "actual" to amount, "discount" to JSONObject.NULL, "countInStatistics" to true, "countInBudget" to true,
            "parentRecordId" to null, "claimState" to "NONE", "templateId" to null, "attachmentIds" to "[]",
            "serverRevision" to 11L, "deletedAt" to null)
        putCanonical(db, owner, epoch, "account_postings", UUID.randomUUID(), "eventId" to openingEventId.toString(),
            "accountId" to ids.accountId.toString(), "role" to "OPENING", "signed" to JSONObject().put("unscaled", "0").put("scale", 2).put("currency", "CNY"), "deletedAt" to null)
        putCanonical(db, owner, epoch, "account_postings", UUID.randomUUID(), "eventId" to ids.eventId.toString(),
            "accountId" to ids.accountId.toString(), "role" to "RECORD", "signed" to JSONObject().put("unscaled", "-1234").put("scale", 2).put("currency", "CNY"), "deletedAt" to null)
        putCanonical(db, owner, epoch, "record_allocations", UUID.randomUUID(), "recordId" to ids.recordId.toString(),
            "accountId" to ids.accountId.toString(), "role" to "PAYMENT", "accountAmount" to amount,
            "recordCurrencyAmount" to amount, "deletedAt" to null)
        putCanonical(db, owner, epoch, "stat_contributions", UUID.randomUUID(), "eventId" to ids.eventId.toString(),
            "originRecordId" to ids.recordId.toString(), "ledgerId" to ids.ledgerId.toString(),
            "categoryId" to ids.source.id.toString(), "periodDate" to "2026-05-16", "direction" to "EXPENSE", "amount" to amount, "serverRevision" to 1L, "deletedAt" to null)
        putCanonical(db, owner, epoch, "budget_contributions", UUID.randomUUID(), "eventId" to ids.eventId.toString(),
            "originRecordId" to ids.recordId.toString(), "ledgerId" to ids.ledgerId.toString(),
            "categoryId" to ids.source.id.toString(), "direction" to "EXPENSE", "periodDate" to "2026-05-16",
            "amount" to amount, "serverRevision" to 1L, "deletedAt" to null)
        val tagGroupId = UUID.randomUUID()
        putCanonical(db, owner, epoch, "tag_groups", tagGroupId, "name" to "默认", "sortOrder" to 0L,
            "isDefault" to true, "serverRevision" to 1L, "deletedAt" to null)
        putCanonical(db, owner, epoch, "tags", ids.tagId, "groupId" to tagGroupId.toString(), "name" to "收据",
            "color" to null, "sortOrder" to 0L, "archivedAt" to null, "serverRevision" to 9L, "deletedAt" to null)
        putCanonical(db, owner, epoch, "record_tags", UUID.randomUUID(), "recordId" to ids.recordId.toString(),
            "tagId" to ids.tagId.toString(), "serverRevision" to 2L, "deletedAt" to null)
    }

    private data class CategoryMigrationFixture(
        val ledgerId: UUID,
        val sourceId: UUID,
        val targetId: UUID,
        val recordId: UUID,
        val templateId: UUID,
        val budgetId: UUID,
        val configId: UUID,
    )

    private fun categoryMigrationSource(ids: CategoryMigrationFixture) =
        FinanceCategory(id = ids.sourceId, name = "原分类", direction = Direction.EXPENSE, ledgerId = ids.ledgerId, localRevision = 3)

    private fun categoryMigrationTarget(ids: CategoryMigrationFixture) =
        FinanceCategory(id = ids.targetId, name = "目标分类", direction = Direction.EXPENSE, ledgerId = ids.ledgerId, localRevision = 5)

    /** Seeds two active sibling roots and one record, template, budget config and budget entry for the source. */
    private fun seedCategoryMigration(db: DatasetDatabase, owner: UUID, epoch: UUID, ids: CategoryMigrationFixture) {
        val money = JSONObject().put("unscaled", "1200").put("scale", 2).put("currency", "CNY")
        val ledger = ids.ledgerId.toString()
        putCanonical(db, owner, epoch, "categories", ids.sourceId, "name" to "原分类", "direction" to "EXPENSE", "ledgerId" to ledger, "parentId" to null, "sortOrder" to 0L, "serverRevision" to 3L, "archivedAt" to null, "excludeBudget" to false, "deletedAt" to null)
        putCanonical(db, owner, epoch, "categories", ids.targetId, "name" to "目标分类", "direction" to "EXPENSE", "ledgerId" to ledger, "parentId" to null, "sortOrder" to 1L, "serverRevision" to 5L, "archivedAt" to null, "excludeBudget" to false, "deletedAt" to null)
        putCanonical(db, owner, epoch, "records", ids.recordId, "ledgerId" to ledger, "categoryId" to ids.sourceId.toString(), "status" to "POSTED", "direction" to "EXPENSE", "gross" to money, "actual" to money, "serverRevision" to 11L, "deletedAt" to null)
        putCanonical(db, owner, epoch, "record_templates", ids.templateId, "ledgerId" to ledger, "categoryId" to ids.sourceId.toString(), "serverRevision" to 13L, "deletedAt" to null)
        putCanonical(db, owner, epoch, "budgets", ids.budgetId, "ledgerId" to ledger, "serverRevision" to 17L, "deletedAt" to null)
        putCanonical(db, owner, epoch, "budget_configs", ids.configId, "budgetId" to ids.budgetId.toString(), "periodType" to "MONTH")
        putCanonical(db, owner, epoch, "budget_entries", UUID.randomUUID(), "configId" to ids.configId.toString(), "categoryId" to ids.sourceId.toString(), "amount" to money)
    }

    private fun initializeCanonicalTagTables(db: DatasetDatabase) {
        val identity = db.identity().read() ?: error("Dataset identity missing")
        db.identity().update(identity.copy(snapshotSchemaVersion = SnapshotProtocol.DATA5_SCHEMA))
        val sql = db.openHelper.writableDatabase
        SnapshotProtocol.tablesFor(SnapshotProtocol.DATA5_SCHEMA).forEach { table ->
            sql.execSQL("CREATE TABLE `server_$table` (`id` TEXT NOT NULL PRIMARY KEY, `owner_id` TEXT NOT NULL, `epoch` TEXT NOT NULL)")
        }
    }

    private fun putCanonical(
        db: DatasetDatabase,
        owner: UUID,
        epoch: UUID,
        table: String,
        id: UUID,
        vararg fields: Pair<String, Any?>,
    ) {
        val sql = db.openHelper.writableDatabase
        val values = linkedMapOf<String, Any?>("id" to id.toString(), "owner_id" to owner.toString(), "epoch" to epoch.toString())
        val columns = sql.query("PRAGMA table_info(`server_$table`)").use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
        }
        fields.forEach { (name, value) ->
            require(name.matches(Regex("[a-zA-Z][a-zA-Z0-9_]*")))
            val column = name.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").lowercase()
            if (column !in columns) {
                val affinity = if (value is Int || value is Long || value is Boolean) "INTEGER" else "TEXT"
                sql.execSQL("ALTER TABLE `server_$table` ADD COLUMN `$column` $affinity")
            }
            values[column] = when (value) {
                is Boolean -> if (value) 1L else 0L
                is JSONObject -> value.toString()
                else -> value
            }
        }
        sql.execSQL(
            "INSERT INTO `server_$table` (${values.keys.joinToString(",") { "`$it`" }}) VALUES (${values.keys.joinToString(",") { "?" }})",
            values.values.toTypedArray(),
        )
    }
}
