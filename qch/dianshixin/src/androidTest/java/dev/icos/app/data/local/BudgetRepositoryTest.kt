package dev.icos.app.data.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.icos.app.domain.AccountAmount
import dev.icos.app.domain.Direction
import dev.icos.app.domain.Money
import dev.icos.app.domain.RecordInput
import dev.icos.app.domain.ReportPeriod
import dev.icos.app.data.remote.SnapshotProtocol
import dev.icos.app.data.remote.TypedSyncClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BudgetRepositoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val at = 1768449600000L
    private val zone = "Asia/Shanghai"

    private fun money(value: Long) = Money(value, 0, "CNY")

    private fun overview(repo: LocalFinanceRepository) = repo.budgetOverview(ReportPeriod.MONTH, at, zone)

    private fun withDataset(block: (UUID, UUID, File) -> Unit) {
        val owner = UUID.randomUUID()
        val epoch = UUID.randomUUID()
        val directory = EncryptedDatasetStore.directory(context, owner, epoch)
        try {
            block(owner, epoch, directory)
        } finally {
            KeyStore.getInstance("AndroidKeyStore").apply {
                load(null)
                deleteEntry(EncryptedDatasetStore.alias(owner, epoch))
            }
            directory.deleteRecursively()
            directory.parentFile?.delete()
        }
    }

    private fun seed(repo: LocalFinanceRepository) {
        val account = repo.createAccount("Budget fixture", "CNY", money(1000), at)
        repo.saveRecord(
            null,
            RecordInput.Expense(money(120), null, listOf(AccountAmount(account, money(120))), false, true),
            at,
            zone,
            "budget only",
            null,
        )
    }

    @Test
    fun budgetRootPullAppliesNestedMirrorAndAcknowledgedIntent() = withDataset { owner, epoch, _ ->
        EncryptedDatasetStore.open(context, owner, epoch).use { db ->
            val identity = db.identity().read()!!
            db.identity().update(identity.copy(serverCursor = "before", lastAppliedSeq = 4, snapshotSchemaVersion = 1))
            val sql = db.openHelper.writableDatabase
            createCanonicalMirrors(sql)
            val ledgerId = UUID.randomUUID()
            sql.execSQL("INSERT INTO server_ledgers(id,owner_id,epoch) VALUES(?,?,?)", arrayOf(ledgerId.toString(), owner.toString(), epoch.toString()))

            val opId = UUID.randomUUID().toString()
            val groupId = UUID.randomUUID().toString()
            db.accountMutations().insertIntent(AccountMutationIntentEntity(
                opId, owner.toString(), epoch.toString(), "budget", ledgerId.toString(), "budget.setEnabled",
                "{\"enabled\":true}", "ACKED", at,
            ))
            db.accountMutations().insertPrediction(AccountPredictionEntity(
                opId, owner.toString(), epoch.toString(), "budget", ledgerId.toString(), "{\"enabled\":true}", true,
            ))
            db.typedOutbox().insert(TypedOutboxEntity(
                opId = opId, groupId = groupId, ownerId = owner.toString(), epochId = epoch.toString(),
                deviceId = UUID.randomUUID().toString(), aggregateType = "budget", aggregateId = ledgerId.toString(),
                baseVersionsJson = "{}", commandType = "budget.setEnabled", schemaVersion = 1,
                payloadJson = "{\"enabled\":true}", state = "APPLIED", createdAt = at,
            ))

            val budgetId = ledgerId
            val payload = JSONObject()
                .put("id", budgetId.toString()).put("ownerId", owner.toString()).put("datasetEpoch", epoch.toString())
                .put("serverRevision", 1).put("ledgerId", ledgerId.toString()).put("enabled", true)
                .put("configs", JSONArray().put(JSONObject().put("id", UUID.randomUUID().toString()).put("entries", JSONArray())))
            SnapshotImporter.applyPullMirror(db, pullPage(epoch, 5, groupId, "budget", budgetId, payload), owner)

            assertEquals("after-5", db.identity().read()!!.serverCursor)
            assertEquals(5L, db.identity().read()!!.lastAppliedSeq)
            assertEquals("PULLED", db.accountMutations().intent(opId)!!.state)
            assertNull(db.accountMutations().activePrediction(opId))
            sql.query("SELECT enabled,configs FROM server_budgets WHERE id=?", arrayOf(budgetId.toString())).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
                assertEquals(payload.getJSONArray("configs").toString(), cursor.getString(1))
            }
            val mirroredBudget = SnapshotImporter.canonicalTables(db).getJSONArray("budgets").getJSONObject(0)
            assertTrue(mirroredBudget.canonicalBoolean("enabled"))
            assertEquals(payload.getJSONArray("configs").toString(), mirroredBudget.getJSONArray("configs").toString())
        }
    }

    @Test
    fun invalidBudgetLedgerReferenceRollsBackPullAndKeepsAcknowledgedIntent() = withDataset { owner, epoch, _ ->
        EncryptedDatasetStore.open(context, owner, epoch).use { db ->
            val identity = db.identity().read()!!
            db.identity().update(identity.copy(serverCursor = "before", lastAppliedSeq = 4, snapshotSchemaVersion = 1))
            val sql = db.openHelper.writableDatabase
            createCanonicalMirrors(sql)
            val localLedgerId = UUID.randomUUID()
            sql.execSQL("INSERT INTO server_ledgers(id,owner_id,epoch) VALUES(?,?,?)", arrayOf(localLedgerId.toString(), owner.toString(), epoch.toString()))
            val otherOwner = UUID.randomUUID()
            val foreignOwnerLedgerId = UUID.randomUUID()
            sql.execSQL("INSERT INTO server_ledgers(id,owner_id,epoch) VALUES(?,?,?)", arrayOf(foreignOwnerLedgerId.toString(), otherOwner.toString(), epoch.toString()))
            val otherEpoch = UUID.randomUUID()
            val foreignEpochLedgerId = UUID.randomUUID()
            sql.execSQL("INSERT INTO server_ledgers(id,owner_id,epoch) VALUES(?,?,?)", arrayOf(foreignEpochLedgerId.toString(), owner.toString(), otherEpoch.toString()))

            val opId = UUID.randomUUID().toString()
            val groupId = UUID.randomUUID().toString()
            db.accountMutations().insertIntent(AccountMutationIntentEntity(
                opId, owner.toString(), epoch.toString(), "budget", localLedgerId.toString(), "budget.setEnabled",
                "{\"enabled\":true}", "ACKED", at,
            ))
            db.accountMutations().insertPrediction(AccountPredictionEntity(
                opId, owner.toString(), epoch.toString(), "budget", localLedgerId.toString(), "{\"enabled\":true}", true,
            ))
            db.typedOutbox().insert(TypedOutboxEntity(
                opId = opId, groupId = groupId, ownerId = owner.toString(), epochId = epoch.toString(),
                deviceId = UUID.randomUUID().toString(), aggregateType = "budget", aggregateId = localLedgerId.toString(),
                baseVersionsJson = "{}", commandType = "budget.setEnabled", schemaVersion = 1,
                payloadJson = "{\"enabled\":true}", state = "APPLIED", createdAt = at,
            ))

            listOf(foreignOwnerLedgerId, foreignEpochLedgerId, UUID.randomUUID()).forEach { referencedLedgerId ->
                val payload = JSONObject()
                    .put("id", localLedgerId.toString()).put("ownerId", owner.toString()).put("datasetEpoch", epoch.toString())
                    .put("serverRevision", 1).put("ledgerId", referencedLedgerId.toString()).put("enabled", true)
                assertThrows(IllegalStateException::class.java) {
                    SnapshotImporter.applyPullMirror(db, pullPage(epoch, 5, groupId, "budget", localLedgerId, payload), owner)
                }
                assertEquals("before", db.identity().read()!!.serverCursor)
                assertEquals(4L, db.identity().read()!!.lastAppliedSeq)
                assertEquals("ACKED", db.accountMutations().intent(opId)!!.state)
                assertNotNull(db.accountMutations().activePrediction(opId))
                sql.query("SELECT COUNT(*) FROM server_budgets").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(0L, cursor.getLong(0))
                }
            }
        }
    }

    private fun createCanonicalMirrors(sql: androidx.sqlite.db.SupportSQLiteDatabase) {
        SnapshotProtocol.tablesFor(1).sorted().forEach { table ->
            val columns = if (table == "budgets") ", ledger_id TEXT, enabled INTEGER, configs TEXT" else ""
            sql.execSQL("CREATE TABLE server_$table (id TEXT NOT NULL PRIMARY KEY, owner_id TEXT NOT NULL, epoch TEXT NOT NULL$columns)")
        }
    }

    private fun pullPage(epoch: UUID, seq: Long, groupId: String, entity: String, id: UUID, payload: JSONObject) =
        TypedSyncClient.PullPage(epoch, JSONArray().put(JSONObject()
            .put("seq", seq).put("groupId", groupId).put("ordinal", 0).put("entityType", entity)
            .put("entityId", id.toString()).put("revision", payload.getLong("serverRevision"))
            .put("operation", "UPSERT").put("payload", payload)), "after-$seq", false, seq)

    @Test
    fun defaultsOverridesZeroResetAndJournalFailurePreserveFinancialFacts() =
        withDataset { owner, epoch, _ ->
            lateinit var unchanged: FinanceSnapshot
            lateinit var beforeFailure: FinanceBudgetOverview
            LocalFinanceRepository.openDataset(context, owner, epoch).use { repo ->
                seed(repo)
                unchanged = repo.snapshot()
                val empty = overview(repo)
                assertNull(empty.limit)
                assertFalse(empty.enabled)
                assertTrue(empty.used.sameValue(money(120)))
                assertEquals(17, empty.remainingDays)
                repo.configureBudget(empty, BudgetScope.DEFAULT, money(100), at, zone)
                assertTrue(overview(repo).amounts!!.remaining.sameValue(money(-20)))
                assertEquals(java.math.BigDecimal.ZERO, overview(repo).amounts!!.dailyAllowance)
                assertNull(repo.budgetOverview(ReportPeriod.WEEK, at, zone).limit)
                assertNull(repo.budgetOverview(ReportPeriod.YEAR, at, zone).limit)
                repo.setBudgetEnabled(overview(repo), true, at, zone)
                val stale = overview(repo)
                repo.configureBudget(stale, BudgetScope.CURRENT, money(0), at, zone)
                val zero = overview(repo)
                assertNotNull(zero.overrideAmount)
                assertTrue(zero.limit!!.sameValue(money(0)))
                assertThrows(
                    FinanceValidationException::class.java,
                ) { repo.configureBudget(stale, BudgetScope.CURRENT, money(200), at, zone) }
                assertEquals(zero, overview(repo))
                repo.configureBudget(zero, BudgetScope.DEFAULT, money(500), at, zone)
                assertTrue(overview(repo).limit!!.sameValue(money(0)))
                repo.resetBudgetToDefault(overview(repo), at, zone)
                assertNull(overview(repo).overrideAmount)
                assertTrue(overview(repo).limit!!.sameValue(money(500)))
                assertTrue(overview(repo).amounts!!.remaining.sameValue(money(380)))
                repo.setBudgetEnabled(overview(repo), false, at, zone)
                assertFalse(overview(repo).enabled)
                assertEquals(unchanged, repo.snapshot())
                beforeFailure = overview(repo)
            }
            EncryptedDatasetStore.open(context, owner, epoch).use { db ->
                db.openHelper.writableDatabase.execSQL(
                    "CREATE TRIGGER fail_budget_intent BEFORE INSERT ON finance_mutation WHEN NEW.kind = 'BUDGET_CONFIGURE' BEGIN SELECT RAISE(ABORT, 'fixture failure'); END",
                )
            }
            LocalFinanceRepository.openDataset(context, owner, epoch).use { repo ->
                assertThrows(
                    FinanceValidationException::class.java,
                ) { repo.configureBudget(beforeFailure, BudgetScope.CURRENT, money(700), at, zone) }
                assertEquals(beforeFailure, overview(repo))
                assertEquals(unchanged, repo.snapshot())
            }
            EncryptedDatasetStore.open(context, owner, epoch).use { db ->
                assertEquals(3, db.finance().mutations().count { it.kind == "BUDGET_CONFIGURE" })
                assertEquals(
                    2L,
                    db
                        .budget()
                        .templates()
                        .single()
                        .localRevision,
                )
                assertNotNull(
                    db
                        .budget()
                        .overrides()
                        .single()
                        .deletedAt,
                )
                db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_budget_intent")
            }
            LocalFinanceRepository.openDataset(context, owner, epoch).use { repo ->
                assertEquals(beforeFailure, overview(repo))
                assertEquals(unchanged, repo.snapshot())
            }
        }

    @Test
    fun categoryBudgetExclusionPreservesFinancialFactsAndClaimBudgetConservation() = withDataset { owner, epoch, _ ->
        LocalFinanceRepository.openDataset(context, owner, epoch).use { repo ->
            val account = repo.createAccount("分类预算排除", "CNY", money(1000), at)
            repo.saveCategory(null, null, "父级排除", Direction.EXPENSE, null, excludeBudget = true)
            val parent = repo.snapshot().categories.single { it.name == "父级排除" }
            repo.saveCategory(null, null, "继承排除", Direction.EXPENSE, parent.id)
            repo.saveCategory(null, null, "自身排除", Direction.EXPENSE, null, excludeBudget = true)
            val categories = repo.snapshot().categories.associateBy { it.name }
            val parentExcluded = categories.getValue("继承排除")
            val selfExcluded = categories.getValue("自身排除")

            fun expense(categoryId: UUID, countInBudget: Boolean = true) = repo.saveRecord(
                null,
                RecordInput.Expense(money(100), null, listOf(AccountAmount(account, money(100))), true, countInBudget),
                at,
                zone,
                "分类预算排除断言",
                categoryId,
            )

            expense(parentExcluded.id)
            expense(selfExcluded.id)
            val claimRecord = expense(selfExcluded.id)
            val withExpenses = repo.snapshot()
            assertEquals(3, withExpenses.records.size)
            assertTrue(withExpenses.records.all { (it.input as RecordInput.Expense).countInBudget })
            assertEquals(700L, withExpenses.accounts.single { it.id == account }.balance.unscaled)
            assertEquals(300L, withExpenses.summaries.single().expense.unscaled)
            assertEquals(0L, withExpenses.summaries.single().budgetExpense.unscaled)
            assertEquals(300L, withExpenses.statisticEntries.filter { it.direction == Direction.EXPENSE }.sumOf { it.amount.unscaled })
            assertThrows(FinanceValidationException::class.java) {
                repo.setBudgetExcluded(selfExcluded.id, selfExcluded.localRevision, false)
            }
            assertTrue(repo.snapshot().categories.single { it.id == selfExcluded.id }.excludeBudget)

            repo.markClaim(claimRecord, "PENDING", 1)
            val settlement = repo.settleClaims(
                listOf(FinanceClaimAllocation(claimRecord, money(50), 2)),
                account,
                null,
                dev.icos.app.domain.RemainderAction.PARTIAL_KEEP,
                at,
                zone,
            )
            val settled = repo.snapshot()
            assertEquals(750L, settled.accounts.single { it.id == account }.balance.unscaled)
            assertEquals(250L, settled.summaries.single().expense.unscaled)
            assertEquals(0L, settled.summaries.single().budgetExpense.unscaled)
            assertEquals(250L, settled.statisticEntries.filter { it.direction == Direction.EXPENSE }.sumOf { it.amount.unscaled })
            assertTrue(settled.statisticEntries.any { it.eventId == settlement && it.amount.unscaled == -50L })

            repo.cancelClaimSettlement(settlement, 1, "撤销无预算支出的报销")
            val cancelled = repo.snapshot()
            assertEquals(700L, cancelled.accounts.single { it.id == account }.balance.unscaled)
            assertEquals(300L, cancelled.summaries.single().expense.unscaled)
            assertEquals(0L, cancelled.summaries.single().budgetExpense.unscaled)
            assertEquals(300L, cancelled.statisticEntries.filter { it.direction == Direction.EXPENSE }.sumOf { it.amount.unscaled })
            assertTrue(cancelled.statisticEntries.none { it.eventId == settlement })
        }
    }

    @Test
    fun searchFiltersAndSortPersistAcrossEncryptedReopen() =
        withDataset { owner, epoch, _ ->
            val saved =
                SearchPreferencesEntity(
                    type = "EXPENSE",
                    fromDate = "2026-10-01",
                    throughDate = "2026-10-31",
                    currency = "CNY",
                    accountId = "account-id",
                    categoryId = "category-id",
                    minimumAmount = "1.20",
                    maximumAmount = "99.00",
                    sort = "AMOUNT_ASCENDING",
                )
            LocalFinanceRepository.openDataset(context, owner, epoch).use { repo ->
                assertNull(repo.searchPreferences())
                repo.saveSearchPreferences(saved)
                assertEquals(saved, repo.searchPreferences())
            }
            LocalFinanceRepository.openDataset(context, owner, epoch).use { repo ->
                assertEquals(saved, repo.searchPreferences())
            }
        }

    @Test
    fun schemaTwoMigratesWithoutChangingEnvelopeCursorOrFinancialFacts() =
        withDataset { owner, epoch, directory ->
            lateinit var before: FinanceSnapshot
            lateinit var template: FinanceRecordTemplate
            LocalFinanceRepository.openDataset(context, owner, epoch).use { repo ->
                seed(repo)
                before = repo.snapshot()
            }
            val envelope = File(directory, "passphrase.envelope").readBytes()
            EncryptedDatasetStore.open(context, owner, epoch).use { db ->
                val sql = db.openHelper.writableDatabase
                sql.execSQL("UPDATE dataset_identity SET serverCursor='budget-migration',lastAppliedSeq=7")
                // Reconstruct exported schema 2 while retaining its encrypted financial data and identity.
                for (table in listOf(
                    "finance_budget_override",
                    "finance_budget_template",
                    "finance_budget_preference",
                    "dataset_search_preferences",
                    "finance_record_template",
                )) {
                    sql.execSQL("DROP TABLE `$table`")
                }
                // Reconstruct the schema-2 finance_record table before replaying migrations 2→6.
                sql.execSQL("ALTER TABLE `finance_record` DROP COLUMN `claimState`")
                sql.execSQL("UPDATE room_master_table SET identity_hash='b1c6f860cf30161d65c374aec985ae6c' WHERE id=42")
                sql.execSQL("PRAGMA user_version=2")
            }
            LocalFinanceRepository.openDataset(context, owner, epoch).use { repo ->
                assertEquals(before, repo.snapshot())
                assertNull(overview(repo).limit)
                repo.configureBudget(overview(repo), BudgetScope.DEFAULT, money(200), at, zone)
                assertTrue(overview(repo).limit!!.sameValue(money(200)))
                assertEquals(before, repo.snapshot())
                val record = before.records.single()
                template =
                    FinanceRecordTemplate(
                        UUID.randomUUID(),
                        "早餐",
                        record.input,
                        before.categories
                            .first {
                                it.direction == Direction.EXPENSE
                            }.id,
                        record.occurredAtMillis,
                        record.timezone,
                        record.remark,
                    )
                assertEquals(template.id, repo.saveRecordTemplate(template))
                assertEquals(listOf(template), repo.recordTemplates())
            }
            LocalFinanceRepository.openDataset(context, owner, epoch).use { repo ->
                assertEquals(listOf(template), repo.recordTemplates())
                assertEquals(before, repo.snapshot())
            }
            val categoryId = template.categoryId!!.toString()
            EncryptedDatasetStore.open(context, owner, epoch).use { db ->
                db.openHelper.writableDatabase.execSQL("UPDATE finance_category SET deletedAt=? WHERE id=?", arrayOf(at, categoryId))
            }
            LocalFinanceRepository.openDataset(context, owner, epoch).use { repo ->
                assertEquals("模板分类已失效", repo.recordTemplates().single().invalidReason)
                assertEquals(before.records, repo.snapshot().records)
                repo.deleteRecordTemplate(template.id)
                assertTrue(repo.recordTemplates().isEmpty())
            }
            EncryptedDatasetStore.open(context, owner, epoch).use { db ->
                db.openHelper.writableDatabase.execSQL("UPDATE finance_category SET deletedAt=NULL WHERE id=?", arrayOf(categoryId))
            }
            LocalFinanceRepository.openDataset(context, owner, epoch).use { repo ->
                assertEquals(before, repo.snapshot())
            }
            EncryptedDatasetStore.open(context, owner, epoch).use { db ->
                assertEquals("budget-migration", db.identity().read()!!.serverCursor)
                assertEquals(7L, db.identity().read()!!.lastAppliedSeq)
                db.openHelper.writableDatabase.query("PRAGMA user_version").use {
                    assertTrue(it.moveToFirst())
                    assertEquals(6, it.getInt(0))
                }
                db.openHelper.writableDatabase
                    .query(
                        "SELECT claimState FROM finance_record WHERE ownerId=? AND epochId=?",
                        arrayOf(owner.toString(), epoch.toString()),
                    ).use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        assertEquals("NONE", cursor.getString(0))
                    }
            }
            assertArrayEquals(envelope, File(directory, "passphrase.envelope").readBytes())
        }
}
