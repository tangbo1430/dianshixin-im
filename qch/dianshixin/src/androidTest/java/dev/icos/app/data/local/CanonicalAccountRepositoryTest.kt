package dev.icos.app.data.local

import dev.icos.app.data.remote.SnapshotProtocol
import dev.icos.app.domain.Direction
import dev.icos.app.domain.LoanDueState
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.util.UUID

/** Exercises the production canonical JSON reader with server-shaped DATA4 rows. */
class CanonicalAccountRepositoryTest {
    @Test
    fun readsCanonicalLedgerRecordsStatisticsAndBudget() {
        val owner = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val epoch = UUID.fromString("00000000-0000-0000-0000-000000000002")
        val ledger = "00000000-0000-0000-0000-000000000010"
        val account = "00000000-0000-0000-0000-000000000020"
        val group = "00000000-0000-0000-0000-000000000021"
        val category = "00000000-0000-0000-0000-000000000030"
        val event = "00000000-0000-0000-0000-000000000040"
        val record = "00000000-0000-0000-0000-000000000050"
        val now =
            java.text
                .SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.ROOT)
                .parse("2026-05-15T12:00:00Z")!!
                .time
        val tables =
            JSONObject(
                """
                {
                  "ledgers":[{"id":"$ledger","name":"Daily","baseCurrency":"CNY","isDefault":true,"sortOrder":0,"serverRevision":1}],
                  "accounts":[{"id":"$account","name":"Cash","currency":"CNY","serverRevision":2,"archivedAt":null,"includeInAssets":true,"selectable":true,"groupId":"$group","sortOrder":0,"type":"ASSET","visibilityMode":"ALL","deletedAt":null}],
                  "account_groups":[{"id":"$group","name":"Cash","sortOrder":0,"isDefault":true,"serverRevision":1,"deletedAt":null}],
                  "account_ledger_visibility":[],
                  "account_postings":[{"id":"00000000-0000-0000-0000-000000000060","eventId":"00000000-0000-0000-0000-000000000041","accountId":"$account","signed":{"unscaled":"13345","scale":2,"currency":"CNY"},"reversalOf":null,"deletedAt":null},{"id":"00000000-0000-0000-0000-000000000061","eventId":"$event","accountId":"$account","signed":{"unscaled":"-100","scale":1,"currency":"CNY"},"reversalOf":null,"deletedAt":null}],
                  "categories":[{"id":"$category","ledgerId":"$ledger","name":"Food","direction":"EXPENSE","excludeBudget":false,"deletedAt":null}],
                  "financial_events":[{"id":"$event","ledgerId":"$ledger","kind":"EXPENSE","occurredAt":"2026-05-15T10:11:12.123456+08:00","timezone":"Asia/Shanghai"},{"id":"00000000-0000-0000-0000-000000000041","ledgerId":"$ledger","kind":"OPENING","occurredAt":"2026-05-01T00:00:00+08:00","timezone":"Asia/Shanghai"}],
                  "records":[{"id":"$record","ledgerId":"$ledger","eventId":"$event","status":"POSTED","direction":"EXPENSE","occurredAt":"2026-05-15T10:11:12.123456+08:00","remark":"lunch","categoryId":"$category","serverRevision":3,"gross":{"unscaled":"1250","scale":2,"currency":"CNY"},"actual":{"unscaled":"1000","scale":2,"currency":"CNY"},"discount":{"unscaled":"250","scale":2,"currency":"CNY"},"countInStatistics":true,"countInBudget":true,"attachmentIds":["00000000-0000-0000-0000-000000000095"]}],
                  "record_allocations":[{"id":"00000000-0000-0000-0000-000000000070","recordId":"$record","accountId":"$account","role":"PAYMENT","recordCurrencyAmount":{"unscaled":"1000","scale":2,"currency":"CNY"}}],
                  "record_tags":[],"tags":[],"tag_groups":[],"stat_contributions":[{"id":"00000000-0000-0000-0000-000000000080","ledgerId":"$ledger","eventId":"$event","originRecordId":"$record","categoryId":"$category","direction":"EXPENSE","amount":{"unscaled":"1000","scale":2,"currency":"CNY"},"periodDate":"2026-05-15","deletedAt":null}],
                  "budget_contributions":[{"id":"00000000-0000-0000-0000-000000000081","ledgerId":"$ledger","eventId":"$event","originRecordId":"$record","categoryId":"$category","direction":"EXPENSE","amount":{"unscaled":"1000","scale":2,"currency":"CNY"},"periodDate":"2026-05-15","deletedAt":null}],
                  "budgets":[{"id":"$ledger","ledgerId":"$ledger","serverRevision":4,"configs":[{"id":"00000000-0000-0000-0000-000000000091","periodType":"MONTH","scope":"DEFAULT","effectiveFrom":"2026-01-01","deletedAt":null,"entries":[{"categoryId":null,"amount":{"unscaled":"50000","scale":2,"currency":"CNY"}}]},{"id":"00000000-0000-0000-0000-000000000092","periodType":"MONTH","scope":"CURRENT","periodStart":"2026-05-01","deletedAt":null,"entries":[{"categoryId":null,"amount":{"unscaled":"45000","scale":2,"currency":"CNY"}}]}]}],
                  "record_templates":[],"template_allocations":[],"template_tags":[],"claim_items":[],"claim_settlements":[],"claim_allocations":[],"refunds":[],"saving_plans":[],"saving_entries":[],"loan_plans":[{"id":"00000000-0000-0000-0000-000000000100","accountId":"$account","principal":{"unscaled":"100000","scale":2,"currency":"CNY"},"status":"ACTIVE","serverRevision":2,"paidTermCount":0,"annualRateNumerator":5,"annualRateDenominator":100,"repaymentType":"EQUAL_INSTALLMENT","firstDueDate":"2026-06-01"}],"loan_terms":[{"id":"00000000-0000-0000-0000-000000000101","planId":"00000000-0000-0000-0000-000000000100","termIndex":1,"dueDate":"2026-06-01","opening":{"unscaled":"100000","scale":2,"currency":"CNY"},"principal":{"unscaled":"8000","scale":2,"currency":"CNY"},"interest":{"unscaled":"400","scale":2,"currency":"CNY"},"closing":{"unscaled":"92000","scale":2,"currency":"CNY"},"state":"POSTED","paidPrincipal":{"unscaled":"8000","scale":2,"currency":"CNY"},"paidInterest":{"unscaled":"400","scale":2,"currency":"CNY"},"paidAt":"2026-05-15T11:00:00Z"},{"id":"00000000-0000-0000-0000-000000000102","planId":"00000000-0000-0000-0000-000000000100","termIndex":2,"dueDate":"2026-07-01","opening":{"unscaled":"92000","scale":2,"currency":"CNY"},"principal":{"unscaled":"9000","scale":2,"currency":"CNY"},"interest":{"unscaled":"300","scale":2,"currency":"CNY"},"closing":{"unscaled":"83000","scale":2,"currency":"CNY"},"state":"SUPERSEDED"}],"loan_rate_changes":[],"business_preferences":[],"recurrence_rule_segments":[],"recurrence_occurrences":[],"recurrence_plans":[]
                }
                """.trimIndent(),
            )

        val view = CanonicalAccountRepository.readTables(tables, owner, epoch, now = now, statisticsZone = "UTC")
        assertEquals(1, view.snapshot.records.size)
        assertEquals(
            listOf(UUID.fromString("00000000-0000-0000-0000-000000000095")),
            view.snapshot.records
                .single()
                .attachmentIds,
        )
        val expense =
            view.snapshot.records
                .single()
                .input as dev.icos.app.domain.RecordInput.Expense
        assertEquals(1250L, expense.gross.unscaled)
        assertEquals(
            1,
            expense.allocations
                .single()
                .amount.scale,
        )
        assertEquals(1, view.snapshot.statisticEntries.size)
        assertEquals(
            Direction.EXPENSE,
            view.snapshot.statisticEntries
                .single()
                .direction,
        )
        assertEquals(45000L, view.budget.limit!!.unscaled)
        assertEquals("2026-05-01", view.budget.periodStart)
        assertEquals(17, view.budget.remainingDays)
        assertEquals(
            12345L,
            view.snapshot.accounts
                .single()
                .balance.unscaled,
        )
        assertNull(
            view.snapshot.accounts
                .single()
                .archivedAt,
        )
        val loan = view.snapshot.loanPlans.single()
        assertEquals(1, loan.terms.size)
        assertEquals(LoanDueState.DUE, loan.terms.single().dueState)
        assertEquals(1, loan.payments.size)
        val archivedId = UUID.fromString("00000000-0000-0000-0000-000000000011")
        val deletedId = UUID.fromString("00000000-0000-0000-0000-000000000012")
        val otherActiveId = UUID.fromString("00000000-0000-0000-0000-000000000013")
        tables
            .getJSONArray("ledgers")
            .getJSONObject(0)
            .put("isDefault", 1)
            .put("sortOrder", 10)
        tables
            .getJSONArray("ledgers")
            .put(
                JSONObject()
                    .put("id", archivedId.toString())
                    .put("name", "Archived")
                    .put("baseCurrency", "CNY")
                    .put("archivedAt", "2026-05-01T00:00:00Z")
                    .put("isDefault", 0)
                    .put("sortOrder", 0),
            ).put(JSONObject().put("id", deletedId.toString()).put("deletedAt", "2026-05-01T00:00:00Z"))
            .put(
                JSONObject()
                    .put("id", otherActiveId.toString())
                    .put("name", "Another")
                    .put("baseCurrency", "CNY")
                    .put("isDefault", 0)
                    .put("sortOrder", 1),
            )
        val fallback = CanonicalAccountRepository.readTables(tables, owner, epoch, archivedId, now, statisticsZone = "UTC")
        assertEquals(UUID.fromString(ledger), fallback.selectedLedgerId)
        assertEquals(listOf(otherActiveId, UUID.fromString(ledger)), fallback.ledgers.map { it.id })
        assertEquals(listOf(archivedId, otherActiveId, UUID.fromString(ledger)), fallback.snapshot.ledgers.map { it.id })
        assertEquals(1, fallback.snapshot.records.size)
        assertEquals(
            12345L,
            fallback.snapshot.allAccounts
                .single()
                .balance.unscaled,
        )
        assertEquals(
            otherActiveId,
            CanonicalAccountRepository.readTables(tables, owner, epoch, otherActiveId, now, statisticsZone = "UTC").selectedLedgerId,
        )
        val childId = UUID.fromString("00000000-0000-0000-0000-000000000031")
        tables
            .getJSONArray("categories")
            .getJSONObject(0)
            .put("serverRevision", 8)
            .put("sortOrder", 2)
            .put("color", "#D95763")
        tables.getJSONArray("categories").put(
            JSONObject()
                .put("id", childId.toString())
                .put("ledgerId", ledger)
                .put("name", "Archived child")
                .put("direction", "EXPENSE")
                .put("parentId", category)
                .put("archivedAt", "2026-05-01T00:00:00Z")
                .put("sortOrder", 3)
                .put("serverRevision", 9)
                .put("excludeBudget", false),
        )
        val categoryView = CanonicalAccountRepository.readTables(tables, owner, epoch, now = now, statisticsZone = "UTC").snapshot
        assertEquals(listOf(UUID.fromString(category)), categoryView.categories.map { it.id })
        assertEquals(8L, categoryView.categories.single().localRevision)
        assertEquals("#D95763", categoryView.categories.single().color)
        org.junit.Assert.assertTrue(categoryView.categories.single().usesCanonicalColor)
        val archivedChild = categoryView.allCategories.single { it.id == childId }
        assertEquals(UUID.fromString(category), archivedChild.parentId)
        assertEquals(UUID.fromString(ledger), archivedChild.ledgerId)
        assertEquals(9L, archivedChild.localRevision)
        org.junit.Assert.assertNotNull(archivedChild.archivedAt)
        tables.getJSONArray("accounts").getJSONObject(0).put("deletedAt", "2026-05-20T00:00:00Z")
        val afterAccountDelete = CanonicalAccountRepository.readTables(tables, owner, epoch, now = now, statisticsZone = "UTC").snapshot
        org.junit.Assert.assertTrue(afterAccountDelete.allAccounts.isEmpty())
        assertEquals(
            view.snapshot.accounts
                .single()
                .name,
            afterAccountDelete.historicalAccountNames[UUID.fromString(account)],
        )
        assertEquals(1, afterAccountDelete.records.size)
    }

    @Test
    fun recordUpdatePredictionKeepsAttachmentsOnlyWhenThePayloadOmitsThem() {
        val owner = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val epoch = UUID.fromString("00000000-0000-0000-0000-000000000002")
        val account = "00000000-0000-0000-0000-000000000010"
        val ledger = "00000000-0000-0000-0000-000000000011"
        val category = "00000000-0000-0000-0000-000000000012"
        val record = "00000000-0000-0000-0000-000000000013"
        val event = "00000000-0000-0000-0000-000000000014"
        val attachment = "00000000-0000-0000-0000-000000000095"

        fun money(value: Long) = JSONObject().put("unscaled", value.toString()).put("scale", 0).put("currency", "CNY")

        fun tables() =
            JSONObject()
                .put("accounts", JSONArray().put(JSONObject().put("id", account).put("type", "ASSET")))
                .put(
                    "categories",
                    JSONArray().put(JSONObject().put("id", category).put("excludeBudget", 0L).put("deletedAt", JSONObject.NULL)),
                ).put(
                    "records",
                    JSONArray().put(
                        JSONObject()
                            .put("id", record)
                            .put("ownerId", owner.toString())
                            .put("datasetEpoch", epoch.toString())
                            .put("ledgerId", ledger)
                            .put("eventId", event)
                            .put("direction", "EXPENSE")
                            .put("status", "POSTED")
                            .put("occurredAt", "2026-01-02T03:04:05Z")
                            .put("timezone", "UTC")
                            .put("localDate", "2026-01-02")
                            .put("remark", "old")
                            .put("categoryId", category)
                            .put("gross", money(100))
                            .put("actual", money(100))
                            .put("discount", JSONObject.NULL)
                            .put("countInStatistics", true)
                            .put("countInBudget", true)
                            .put("parentRecordId", JSONObject.NULL)
                            .put("claimState", "NONE")
                            .put("serverRevision", 4)
                            .put("attachmentIds", JSONArray().put(attachment))
                            .put("deletedAt", JSONObject.NULL),
                    ),
                ).put(
                    "financial_events",
                    JSONArray().put(
                        JSONObject()
                            .put("id", event)
                            .put("ledgerId", ledger)
                            .put("occurredAt", "2026-01-02T03:04:05Z")
                            .put("timezone", "UTC"),
                    ),
                ).put(
                    "account_postings",
                    JSONArray().put(
                        JSONObject()
                            .put("id", "00000000-0000-0000-0000-000000000015")
                            .put("eventId", event)
                            .put("accountId", account)
                            .put("signed", money(100))
                            .put("reversalOf", JSONObject.NULL),
                    ),
                ).put(
                    "record_allocations",
                    JSONArray().put(
                        JSONObject()
                            .put("id", "00000000-0000-0000-0000-000000000016")
                            .put(
                                "recordId",
                                record,
                            ).put("accountId", account)
                            .put("role", "PAYMENT")
                            .put("recordCurrencyAmount", money(100))
                            .put("deletedAt", JSONObject.NULL),
                    ),
                ).put("record_tags", JSONArray())
                .put(
                    "stat_contributions",
                    JSONArray().put(
                        JSONObject()
                            .put("id", "00000000-0000-0000-0000-000000000017")
                            .put("eventId", event)
                            .put("recordId", record)
                            .put("originRecordId", record)
                            .put("ledgerId", ledger)
                            .put("categoryId", category)
                            .put("direction", "EXPENSE")
                            .put("amount", money(100))
                            .put("deletedAt", JSONObject.NULL),
                    ),
                ).put(
                    "budget_contributions",
                    JSONArray().put(
                        JSONObject()
                            .put("id", "00000000-0000-0000-0000-000000000018")
                            .put("eventId", event)
                            .put("recordId", record)
                            .put("originRecordId", record)
                            .put("ledgerId", ledger)
                            .put("categoryId", category)
                            .put("direction", "EXPENSE")
                            .put("periodDate", "2026-01-02")
                            .put("amount", money(100))
                            .put("deletedAt", JSONObject.NULL),
                    ),
                )

        fun predict(attachmentIds: JSONArray?): JSONObject {
            val tables = tables()
            val input =
                JSONObject()
                    .put("kind", "EXPENSE")
                    .put("gross", money(150))
                    .put("discount", JSONObject.NULL)
                    .put("allocations", JSONArray().put(JSONObject().put("accountId", account).put("amount", money(150))))
                    .put("countInStatistics", true)
                    .put("countInBudget", true)
            val changes =
                JSONObject()
                    .put("ledgerId", ledger)
                    .put("categoryId", category)
                    .put("occurredAt", "2026-01-02T03:04:05Z")
                    .put("timezone", "UTC")
                    .put("localDate", "2026-01-02")
                    .put("remark", "updated")
                    .put("tagIds", JSONArray())
                    .put("input", input)
            attachmentIds?.let { changes.put("attachmentIds", it) }
            val opId = UUID.randomUUID().toString()
            val payload = JSONObject().put("changes", changes).toString()
            CanonicalAccountRepository.applyPredictions(
                tables,
                listOf(AccountPredictionEntity(opId, owner.toString(), epoch.toString(), "record", record, payload, true)),
                listOf(
                    AccountMutationIntentEntity(
                        opId,
                        owner.toString(),
                        epoch.toString(),
                        "record",
                        record,
                        "record.update",
                        payload,
                        "QUEUED",
                        1,
                    ),
                ),
                owner,
                epoch,
            )
            return (0 until tables.getJSONArray("records").length()).map { tables.getJSONArray("records").getJSONObject(it) }.single {
                it.getString("id") ==
                    record
            }
        }

        val omitted = predict(null)
        assertEquals("updated", omitted.getString("remark"))
        assertEquals(5L, omitted.getLong("serverRevision"))
        assertEquals(
            listOf(attachment),
            (0 until omitted.getJSONArray("attachmentIds").length()).map {
                omitted.getJSONArray("attachmentIds").getString(it)
            },
        )
        val explicit = predict(JSONArray())
        assertEquals(0, explicit.getJSONArray("attachmentIds").length())
    }

    @Test
    fun data5ImportTablesAllowEmptyAndValidateSameSnapshotReferences() {
        val owner = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val epoch = UUID.fromString("00000000-0000-0000-0000-000000000002")
        val batch = "00000000-0000-0000-0000-000000000010"
        val ledger = "00000000-0000-0000-0000-000000000011"
        val account = "00000000-0000-0000-0000-000000000012"
        val category = "00000000-0000-0000-0000-000000000013"
        val batches =
            JSONArray().put(
                JSONObject()
                    .put("id", batch)
                    .put("status", "STAGING")
                    .put("formatVersion", 1)
                    .put("totalChunks", 1)
                    .put("totalRows", 1)
                    .put("source", "vendor")
                    .put("fileDigest", "a".repeat(64)),
            )
        val ids =
            mapOf(
                "ledgers" to setOf(ledger),
                "categories" to setOf(category),
                "accounts" to setOf(account),
                "tags" to emptySet(),
                "import_batches" to setOf(batch),
            )
        SnapshotImporter.validateImportRows(batches, JSONArray(), JSONArray())
        val unmappedLedger = "00000000-0000-0000-0000-000000000099"
        val unmappedCategory = "00000000-0000-0000-0000-000000000098"
        val unmappedAccount = "00000000-0000-0000-0000-000000000097"
        val unmappedTag = "00000000-0000-0000-0000-000000000096"
        val attachment = "00000000-0000-0000-0000-000000000095"
        val record =
            JSONObject()
                .put("ledgerId", unmappedLedger)
                .put("categoryId", unmappedCategory)
                .put("tagIds", JSONArray().put(unmappedTag))
                .put("attachmentIds", JSONArray().put(attachment))
                .put("occurredAt", "2026-05-01T08:00:00Z")
                .put("localDate", "2026-05-01")
                .put("timezone", "Asia/Shanghai")
                .put("remark", "staged draft")
                .put(
                    "input",
                    JSONObject().put("kind", "EXPENSE").put(
                        "allocations",
                        JSONArray().put(
                            JSONObject().put("accountId", unmappedAccount),
                        ),
                    ),
                )
        val row =
            JSONObject()
                .put("batchId", batch)
                .put("rowIndex", 0)
                .put("externalId", JSONObject.NULL)
                .put("disposition", "PENDING")
                .put("payload", record)
        val stagedChunk =
            JSONObject()
                .put("batchId", batch)
                .put("chunkIndex", 0)
                .put("rowCount", 1)
                .put("digest", "b".repeat(64))
                .put(
                    "payload",
                    JSONArray().put(
                        JSONObject().put("rowIndex", 0).put("externalId", JSONObject.NULL).put("record", record),
                    ),
                )
        SnapshotImporter.validateImportRows(batches, JSONArray().put(row), JSONArray().put(stagedChunk))
        val committedDuplicate = JSONObject(batches.getJSONObject(0).toString()).put("status", "COMMITTED")
        val skippedRow = JSONObject(row.toString()).put("disposition", "DUPLICATE_SKIPPED")
        SnapshotImporter.validateImportRows(JSONArray().put(committedDuplicate), JSONArray().put(skippedRow), JSONArray().put(stagedChunk))
        assertThrows(IllegalArgumentException::class.java) {
            SnapshotImporter.validateImportRows(
                batches,
                JSONArray().put(JSONObject(row.toString()).put("payload", JSONObject(record.toString()).put("ledgerId", "not-a-uuid"))),
                JSONArray(),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            SnapshotImporter.validateImportRows(
                batches,
                JSONArray().put(row.put("batchId", "00000000-0000-0000-0000-000000000099")),
                JSONArray(),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            SnapshotImporter.validateSnapshotRowIdentity(
                JSONObject()
                    .put("id", UUID.randomUUID().toString())
                    .put("ownerId", UUID.randomUUID().toString())
                    .put("datasetEpoch", epoch.toString()),
                owner,
                epoch,
            )
        }
    }

    @Test
    fun data5SnapshotReferencesValidateAfterTablesAreMaterialized() {
        val importId = "00000000-0000-0000-0000-000000000010"
        val recordId = "00000000-0000-0000-0000-000000000011"
        val tables =
            JSONObject()
                .put("records", JSONArray().put(JSONObject().put("id", recordId).put("importBatchId", importId)))
                .put("import_batches", JSONArray().put(JSONObject().put("id", importId)))
                .put("import_rows", JSONArray())
                .put("import_staging_chunks", JSONArray())
        val ids = mapOf("records" to setOf(recordId), "import_batches" to setOf(importId))
        SnapshotImporter.validateSnapshotReferences(tables, SnapshotProtocol.DATA4_SCHEMA, ids)
        SnapshotImporter.validateSnapshotReferences(tables, SnapshotProtocol.DATA5_SCHEMA, ids, includeImportReferences = true)
        assertEquals("import_rows", SnapshotImporter.tableForEntity("import_row", SnapshotProtocol.DATA5_SCHEMA))
        assertEquals("import_staging_chunks", SnapshotImporter.tableForEntity("import_staging_chunk", SnapshotProtocol.DATA5_SCHEMA))
        assertEquals("records", SnapshotImporter.tableForEntity("record", SnapshotProtocol.DATA4_SCHEMA))
        assertEquals(44, SnapshotProtocol.data5Tables.size)
        assertEquals(45, SnapshotProtocol.data6Tables.size)
        assertEquals(
            "receivable_payable_operations",
            SnapshotImporter.tableForEntity("receivable_payable_operation", SnapshotProtocol.DATA6_SCHEMA),
        )
        assertThrows(dev.icos.app.data.remote.AuthFailure::class.java) { SnapshotProtocol.tablesFor(7) }
        assertThrows(IllegalArgumentException::class.java) {
            SnapshotImporter.validateSnapshotReferences(
                tables,
                SnapshotProtocol.DATA5_SCHEMA,
                ids + ("import_batches" to emptySet()),
                includeImportReferences = true,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            SnapshotImporter.validateSnapshotReferences(tables, SnapshotProtocol.DATA5_SCHEMA, ids, includeImportReferences = true)
        }
    }

    @Test
    fun data6DebtOperationsRequireAccountsAndEventInTheSameSnapshot() {
        val event = UUID.randomUUID().toString()
        val debt = UUID.randomUUID().toString()
        val cash = UUID.randomUUID().toString()
        val operation = UUID.randomUUID().toString()
        val tables =
            JSONObject().put(
                "receivable_payable_operations",
                JSONArray().put(
                    JSONObject()
                        .put("id", operation)
                        .put("eventId", event)
                        .put("accountId", debt)
                        .put("cashAccountId", cash),
                ),
            )
        tables.put("accounts", JSONArray()).put("financial_events", JSONArray())
        val ids =
            mapOf(
                "receivable_payable_operations" to setOf(operation),
                "financial_events" to setOf(event),
                "accounts" to setOf(debt, cash),
            )
        SnapshotImporter.validateSnapshotReferences(tables, SnapshotProtocol.DATA6_SCHEMA, ids, includeImportReferences = false)
        assertThrows(IllegalArgumentException::class.java) {
            SnapshotImporter.validateSnapshotReferences(
                tables,
                SnapshotProtocol.DATA6_SCHEMA,
                ids + ("accounts" to setOf(debt)),
                includeImportReferences = false,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            SnapshotImporter.validateSnapshotReferences(
                tables,
                SnapshotProtocol.DATA6_SCHEMA,
                ids + ("financial_events" to emptySet()),
                includeImportReferences = false,
            )
        }
    }

    @Test
    fun snapshotScannerStreamsRowsAndRejectsUnknownKeysAndOversizedRows() {
        val owner = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val epoch = UUID.fromString("00000000-0000-0000-0000-000000000002")
        val rows =
            JSONObject()
                .put("id", "00000000-0000-0000-0000-000000000003")
                .put("ownerId", owner.toString())
                .put("datasetEpoch", epoch.toString())
        val file = File.createTempFile("snapshot-stream-", ".json")
        try {
            val tables = JSONObject().put("ledgers", JSONArray().put(rows))
            SnapshotProtocol.data1Tables.filter { it != "ledgers" }.forEach { tables.put(it, JSONArray()) }
            file.writeText(
                """{"format":"icos-native-snapshot","formatVersion":1,"schemaVersion":1,"ownerId":"$owner","epoch":"$epoch","capturedSeq":1,"tables":$tables,"attachmentManifest":[]}""",
            )
            val seen = mutableListOf<String>()
            SnapshotProtocol.scanRows(
                SnapshotProtocol.VerifiedSnapshot(
                    owner,
                    epoch,
                    1,
                    "cursor",
                    file,
                    SnapshotProtocol.data1Tables.associateWith {
                        if (it ==
                            "ledgers"
                        ) {
                            1L
                        } else {
                            0L
                        }
                    },
                    1,
                    "",
                ),
            ) { table, row -> seen += table + row.getString("id") }
            assertEquals(listOf("ledgers${rows.getString("id")}"), seen)
            file.writeText(file.readText().replace("\"attachmentManifest\":[]", "\"unknown\":true,\"attachmentManifest\":[]"))
            assertThrows(IllegalArgumentException::class.java) {
                SnapshotProtocol.scanRows(
                    SnapshotProtocol.VerifiedSnapshot(
                        owner,
                        epoch,
                        1,
                        "cursor",
                        file,
                        SnapshotProtocol.data1Tables.associateWith {
                            if (it ==
                                "ledgers"
                            ) {
                                1L
                            } else {
                                0L
                            }
                        },
                        1,
                        "",
                    ),
                ) { _, _ -> }
            }
            file.writeText(file.readText().replace("\"unknown\":true,", ""))
            val oversized = "x".repeat(SnapshotProtocol.MAX_SNAPSHOT_ROW_BYTES + 1)
            file.writeText(file.readText().replace(rows.getString("id"), rows.getString("id") + oversized))
            assertThrows(IllegalArgumentException::class.java) {
                SnapshotProtocol.scanRows(
                    SnapshotProtocol.VerifiedSnapshot(
                        owner,
                        epoch,
                        1,
                        "cursor",
                        file,
                        SnapshotProtocol.data1Tables.associateWith {
                            if (it ==
                                "ledgers"
                            ) {
                                1L
                            } else {
                                0L
                            }
                        },
                        1,
                        "",
                    ),
                ) { _, _ -> }
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun canonicalTimestampParsesZuluAndOffsets() {
        val method = CanonicalAccountRepository::class.java.getDeclaredMethod("canonicalTimestamp", String::class.java)
        method.isAccessible = true
        val zulu = method.invoke(CanonicalAccountRepository, "2026-01-02T03:04:05Z") as Long
        val offset = method.invoke(CanonicalAccountRepository, "2026-01-02T11:04:05+08:00") as Long
        assertEquals(zulu, offset)
        assertNull(JSONObject("{\"archivedAt\":null}").opt("archivedAt"))
    }
}
