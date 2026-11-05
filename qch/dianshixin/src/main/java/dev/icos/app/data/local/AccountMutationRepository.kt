package dev.icos.app.data.local

import dev.icos.app.data.remote.TypedSyncClient
import dev.icos.app.domain.LoanCalculator
import dev.icos.app.domain.LoanFuturePolicy
import dev.icos.app.domain.LoanPlanInput
import dev.icos.app.domain.LoanTerm
import dev.icos.app.domain.Money
import dev.icos.app.domain.RecordInput
import dev.icos.app.domain.RepaymentMethod
import dev.icos.app.domain.ReportPeriod
import dev.icos.app.domain.normalizeNewSavingEntryRemark
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/** Builds immutable server commands from the canonical account mirror and records a separate prediction. */
internal class AccountMutationRepository(
    private val database: DatasetDatabase,
) {
    private val typed = TypedSyncStore(database)
    private val dao = database.accountMutations()

    fun restoreDatasetClearIntent(
        operation: dev.icos.app.data.remote.PendingDatasetClear,
        deviceId: UUID,
    ) {
        val identity = database.identity().read() ?: error("Dataset identity missing")
        require(identity.ownerId == operation.ownerId.toString() && identity.epochId == operation.epochId.toString())
        val payload = JSONObject().put("ledgerIds", JSONArray(operation.ledgerIdsJson)).put("dependencyStrategy", "REJECT")
        val command =
            TypedCommand(
                opId = operation.opId,
                groupId = operation.groupId,
                ownerId = operation.ownerId,
                epochId = operation.epochId,
                deviceId = deviceId,
                aggregateType = "dataset",
                aggregateId = operation.ownerId,
                baseVersionsJson = "{}",
                commandType = "dataset.clearRecords",
                payloadJson = payload.toString(),
            )
        database.runInTransaction {
            typed.enqueue(command)
            dao.insertIntent(
                AccountMutationIntentEntity(
                    operation.opId.toString(),
                    operation.ownerId.toString(),
                    operation.epochId.toString(),
                    "dataset",
                    operation.ownerId.toString(),
                    "dataset.clearRecords",
                    payload.toString(),
                    if (operation.phase == "ACKED") "ACKED" else "QUEUED",
                    System.currentTimeMillis(),
                ),
            )
        }
        val intent = dao.intent(operation.opId.toString()) ?: error("Clear intent missing")
        check(
            intent.ownerId == operation.ownerId.toString() && intent.epochId == operation.epochId.toString() &&
                intent.aggregateType == "dataset" && intent.aggregateId == operation.ownerId.toString() &&
                intent.commandType == "dataset.clearRecords" && intent.payloadJson == payload.toString(),
        )
        val row = database.typedOutbox().byOpId(operation.opId.toString()) ?: error("Clear outbox missing")
        check(
            row.groupId == operation.groupId.toString() && row.ownerId == operation.ownerId.toString() &&
                row.epochId == operation.epochId.toString() && row.deviceId == deviceId.toString() &&
                row.aggregateType == "dataset" && row.aggregateId == operation.ownerId.toString() &&
                row.commandType == "dataset.clearRecords" && row.payloadJson == payload.toString(),
        )
    }

    fun stageDatasetClearRecords(
        deviceId: UUID,
        sessionId: UUID,
        opId: UUID,
        groupId: UUID,
        expectedHeadSeq: Long,
        password: String,
        persistProtected: (dev.icos.app.data.remote.PendingDatasetClear) -> dev.icos.app.data.remote.PendingDatasetClear,
        discardProtected: (dev.icos.app.data.remote.PendingDatasetClear) -> Unit,
    ): dev.icos.app.data.remote.PendingDatasetClear {
        require(
            opId != UUID(0, 0) && groupId != UUID(0, 0) && expectedHeadSeq >= 0 && password.codePointCount(0, password.length) in 12..128,
        )
        val identity = database.identity().read() ?: error("Dataset identity missing")
        val tables = SnapshotImporter.canonicalTables(database)
        val ledgerIds =
            (0 until tables.getJSONArray("ledgers").length())
                .map { tables.getJSONArray("ledgers").getJSONObject(it) }
                .filter { it.isActive() }
                .map { UUID.fromString(it.getString("id")) }
                .distinct()
                .sortedBy(UUID::toString)
        require(ledgerIds.size in 1..100) { "当前账本数量超出服务端清账合同支持范围" }
        val owner = UUID.fromString(identity.ownerId)
        val epoch = UUID.fromString(identity.epochId)
        val payload = JSONObject().put("ledgerIds", JSONArray(ledgerIds.map(UUID::toString))).put("dependencyStrategy", "REJECT")
        val command =
            TypedCommand(
                opId,
                groupId,
                owner,
                epoch,
                deviceId,
                "dataset",
                owner,
                "{}",
                "dataset.clearRecords",
                payloadJson = payload.toString(),
            )
        var persisted: dev.icos.app.data.remote.PendingDatasetClear? = null
        try {
            persisted =
                persistProtected(
                    dev.icos.app.data.remote.PendingDatasetClear(
                        owner,
                        epoch,
                        sessionId,
                        opId,
                        groupId,
                        JSONArray(ledgerIds.map(UUID::toString)).toString(),
                        expectedHeadSeq,
                        "REAUTH",
                        password,
                        null,
                        null,
                        null,
                        null,
                    ),
                )
            database.runInTransaction {
                typed.enqueue(command)
                dao.insertIntent(
                    AccountMutationIntentEntity(
                        opId.toString(),
                        owner.toString(),
                        epoch.toString(),
                        "dataset",
                        owner.toString(),
                        "dataset.clearRecords",
                        payload.toString(),
                        "QUEUED",
                        System.currentTimeMillis(),
                    ),
                )
            }
            return persisted
        } catch (failure: Exception) {
            persisted?.let { runCatching { discardProtected(it) } }
            runCatching { database.runInTransaction { TypedSyncStore(database).blockDatasetClear(opId) } }
            throw failure
        }
    }

    fun markDatasetClearAck(
        operation: dev.icos.app.data.remote.PendingDatasetClear,
        result: JSONObject,
    ) {
        val identity = database.identity().read() ?: error("Dataset identity missing")
        check(identity.ownerId == operation.ownerId.toString() && identity.epochId == operation.epochId.toString())
        database.runInTransaction {
            val row = database.typedOutbox().byOpId(operation.opId.toString()) ?: error("Clear outbox missing")
            check(
                row.commandType == "dataset.clearRecords" && row.groupId == operation.groupId.toString() &&
                    row.ownerId == identity.ownerId && row.epochId == identity.epochId,
            )
            typed.acknowledgeDatasetClear(operation.opId, operation.ownerId, operation.epochId, result.toString())
            dao.insertReceipt(
                AccountWriteReceiptEntity(
                    operation.opId.toString(),
                    identity.ownerId,
                    identity.epochId,
                    result.getString("status"),
                    result.toString(),
                    System.currentTimeMillis(),
                ),
            )
            check(dao.state(operation.opId.toString(), "ACKED") == 1)
        }
    }

    fun markDatasetClearAmbiguous(operation: dev.icos.app.data.remote.PendingDatasetClear) {
        database.runInTransaction {
            check(dao.markIntentAmbiguous(operation.opId.toString()) == 1)
            val command = database.typedOutbox().byOpId(operation.opId.toString()) ?: error("Clear outbox missing")
            if (command.state in setOf("QUEUED", "SENDING", "AMBIGUOUS")) typed.markAmbiguous(command)
        }
    }

    fun conflicts(
        ownerId: UUID,
        epochId: UUID,
    ): List<AccountMutationIntentEntity> {
        val identity = database.identity().read() ?: error("Dataset identity missing")
        check(identity.ownerId == ownerId.toString() && identity.epochId == epochId.toString())
        return dao.conflicts(ownerId.toString(), epochId.toString())
    }

    fun latestConflictResolution(
        ownerId: UUID,
        epochId: UUID,
        conflictId: UUID,
    ): AccountMutationIntentEntity? {
        val identity = database.identity().read() ?: error("Dataset identity missing")
        check(identity.ownerId == ownerId.toString() && identity.epochId == epochId.toString())
        return dao.latestConflictResolution(ownerId.toString(), epochId.toString(), conflictId.toString())
    }

    fun pendingConflictResolution(
        ownerId: UUID,
        epochId: UUID,
        conflictId: UUID,
    ): AccountMutationIntentEntity? {
        val identity = database.identity().read() ?: error("Dataset identity missing")
        check(identity.ownerId == ownerId.toString() && identity.epochId == epochId.toString())
        return dao.pendingConflictResolution(ownerId.toString(), epochId.toString(), conflictId.toString())
    }

    fun markConflictReviewStale(
        ownerId: UUID,
        epochId: UUID,
        conflictId: UUID,
        requestOpId: UUID,
    ) {
        val identity = database.identity().read() ?: error("Dataset identity missing")
        check(identity.ownerId == ownerId.toString() && identity.epochId == epochId.toString())
        database.runInTransaction {
            val intent = dao.intent(requestOpId.toString()) ?: error("Conflict resolution intent missing")
            check(
                intent.ownerId == ownerId.toString() && intent.epochId == epochId.toString() && intent.aggregateId == conflictId.toString() &&
                    intent.aggregateType == "sync_conflict" && intent.commandType == "sync.conflict.resolve" && intent.state == "AMBIGUOUS",
            )
            check(dao.state(requestOpId.toString(), "CONFLICT_REVIEW_STALE") == 1)
        }
    }

    fun persistConflictResolution(
        conflictId: UUID,
        oldOpId: UUID,
        ownerId: UUID,
        epochId: UUID,
        requestOpId: UUID,
        exactRequestBody: String,
    ) {
        require(conflictId != UUID(0, 0) && oldOpId != UUID(0, 0) && requestOpId != UUID(0, 0))
        val request = JSONObject(exactRequestBody)
        require(request.optString("newOpId") == requestOpId.toString())
        val identity = database.identity().read() ?: error("Dataset identity missing")
        check(identity.ownerId == ownerId.toString() && identity.epochId == epochId.toString())
        val existing = dao.intent(requestOpId.toString())
        if (existing != null) {
            check(
                existing.ownerId == ownerId.toString() && existing.epochId == epochId.toString() &&
                    existing.aggregateType == "sync_conflict" &&
                    existing.aggregateId == conflictId.toString() && existing.commandType == "sync.conflict.resolve" &&
                    existing.payloadJson == exactRequestBody,
            )
            return
        }
        val previous = dao.latestConflictResolution(ownerId.toString(), epochId.toString(), conflictId.toString())
        if (previous != null) {
            val mayRetryAfterFreshReview =
                if (previous.state == "CONFLICT_REVIEW_STALE") {
                    val oldRequest = JSONObject(previous.payloadJson)
                    val newReviewed = request.optJSONObject("reviewedServerVersions")
                    val oldReviewed = oldRequest.optJSONObject("reviewedServerVersions")
                    request.optLong("expectedHeadSeq", -1) >= 0 && newReviewed != null && oldReviewed != null &&
                        (
                            request.optLong("expectedHeadSeq", -1) != oldRequest.optLong("expectedHeadSeq", -2) ||
                                !sameVersionMap(newReviewed, oldReviewed)
                        )
                } else {
                    false
                }
            check(mayRetryAfterFreshReview) { "该冲突已有终态或仍使用过期审阅的决议；必须重新审阅后生成新操作。" }
        }
        database.runInTransaction {
            dao.insertIntent(
                AccountMutationIntentEntity(
                    requestOpId.toString(),
                    ownerId.toString(),
                    epochId.toString(),
                    "sync_conflict",
                    conflictId.toString(),
                    "sync.conflict.resolve",
                    exactRequestBody,
                    "AMBIGUOUS",
                    System.currentTimeMillis(),
                ),
            )
        }
    }

    fun recordConflictResolution(
        conflictId: UUID,
        oldOpId: UUID,
        ownerId: UUID,
        epochId: UUID,
        resolutionOpId: UUID,
        resultJson: String,
        terminalState: String,
    ) {
        require(terminalState in setOf("RESOLVED_LOCAL", "RESOLVED_SERVER", "RESOLVED_EDITED"))
        val result = JSONObject(resultJson)
        check(
            result.optString("conflictId") == conflictId.toString() && result.optString("resolutionOpId") == resolutionOpId.toString() &&
                result.optString("discardedOpId") == oldOpId.toString() && result.optString("state") == terminalState,
        )
        val identity = database.identity().read() ?: error("Dataset identity missing")
        check(identity.ownerId == ownerId.toString() && identity.epochId == epochId.toString())
        database.runInTransaction {
            val pending = dao.intent(resolutionOpId.toString()) ?: error("Conflict resolution intent missing")
            check(
                pending.ownerId == ownerId.toString() && pending.epochId == epochId.toString() && pending.aggregateType == "sync_conflict" &&
                    pending.aggregateId == conflictId.toString() && pending.commandType == "sync.conflict.resolve" &&
                    pending.state == "AMBIGUOUS",
            )
            val oldIntent = dao.intent(oldOpId.toString())
            val oldOutbox = database.typedOutbox().byOpId(oldOpId.toString())
            val localOrigin = conflictResolutionHasLocalOrigin(oldIntent, oldOutbox, ownerId, epochId, oldOpId)
            check(localOrigin || (oldIntent?.state != "CONFLICT" && oldOutbox?.state != "CONFLICT"))
            check(dao.state(resolutionOpId.toString(), terminalState) == 1)
            dao.insertReceipt(
                AccountWriteReceiptEntity(
                    resolutionOpId.toString(),
                    ownerId.toString(),
                    epochId.toString(),
                    terminalState,
                    resultJson,
                    System.currentTimeMillis(),
                ),
            )
            if (localOrigin) {
                checkNotNull(oldIntent)
                checkNotNull(oldOutbox)
                check(
                    database.typedOutbox().recordResult(
                        oldOutbox.opId,
                        oldOutbox.ownerId,
                        oldOutbox.epochId,
                        oldOutbox.deviceId,
                        "BLOCKED_DEPENDENCY",
                        resultJson,
                        null,
                        System.currentTimeMillis(),
                    ) == 1,
                )
                check(dao.state(oldOpId.toString(), terminalState) == 1)
                check(dao.deactivatePrediction(oldOpId.toString()) in 0..1)
            }
        }
    }

    fun retryConflict(
        deviceId: UUID,
        opId: UUID,
    ): UUID = database.runInTransaction(java.util.concurrent.Callable { retryConflictInTransaction(deviceId, opId) })

    private fun retryConflictInTransaction(
        deviceId: UUID,
        opId: UUID,
    ): UUID {
        val previous = database.typedOutbox().byOpId(opId.toString()) ?: error("Mutation missing")
        require(previous.state in setOf("CONFLICT", "REJECTED")) { "Mutation is not retryable" }
        if (previous.commandType == "budget.setEnabled") {
            throw FinanceValidationException("预算启停版本冲突；请重新查看当前规范状态后再发起新操作。不能刷新旧请求的版本并重发。")
        }
        val oldIntent = dao.intent(opId.toString()) ?: error("Intent missing")
        val identity = database.identity().read() ?: error("Dataset identity missing")
        check(previous.ownerId == identity.ownerId && previous.epochId == identity.epochId)
        check(
            oldIntent.ownerId == identity.ownerId && oldIntent.epochId == identity.epochId &&
                oldIntent.aggregateType == previous.aggregateType && oldIntent.aggregateId == previous.aggregateId &&
                oldIntent.commandType == previous.commandType && oldIntent.payloadJson == previous.payloadJson &&
                oldIntent.state in setOf("CONFLICT", "REJECTED"),
        ) { "重试队列身份或状态不匹配" }
        if (previous.commandType == "metadata.delete" && previous.aggregateType in setOf("tag", "tag_group", "category", "account")) {
            throw FinanceValidationException("删除或迁移需要重新核对完整关联，请关闭原更改并重新打开删除确认。")
        }
        val tables = SnapshotImporter.canonicalTables(database)
        val oldVersions = JSONObject(previous.baseVersionsJson)
        val freshVersions = JSONObject()
        oldVersions.keys().asSequence().sorted().forEach { key ->
            val (kind, rawId) = key.split(":", limit = 2)
            val id = UUID.fromString(rawId)
            val table =
                when (kind) {
                    "ledger" -> "ledgers"
                    "category" -> "categories"
                    "account_group" -> "account_groups"
                    "account" -> "accounts"
                    "tag" -> "tags"
                    "tag_group" -> "tag_groups"
                    "record" -> "records"
                    "saving" -> "saving_plans"
                    "saving_entry" -> "saving_entries"
                    "record_template" -> "record_templates"
                    "claim_settlement" -> "claim_settlements"
                    "refund" -> "refunds"
                    "loan" -> "loan_plans"
                    "loan_term" -> "loan_terms"
                    "loan_rate_change" -> "loan_rate_changes"
                    "preferences" -> "business_preferences"
                    "recurrence" -> "recurrence_plans"
                    "installment" -> "installment_plans"
                    "fx_snapshot" -> "fx_snapshots"
                    "budget" -> "budgets"
                    else -> error("Unsupported retry dependency")
                }
            val revision =
                if (kind == "account" &&
                    (
                        previous.commandType in setOf("metadata.archive", "metadata.update", "metadata.reorder") ||
                            previous.aggregateType == "account_group" && previous.commandType == "metadata.delete"
                    )
                ) {
                    tables
                        .getJSONArray("accounts")
                        .objects()
                        .singleOrNull { it.optString("id") == rawId && it.isNull("deletedAt") }
                        ?.getLong("serverRevision") ?: error("账户已不可用")
                } else if (kind in setOf("ledger", "category", "tag", "tag_group") &&
                    previous.aggregateType in setOf("ledger", "category", "tag", "tag_group")
                ) {
                    val row = tables.getJSONArray(table).objects().singleOrNull { it.optString("id") == rawId }
                    require(row == null || row.isNull("deletedAt")) { "资料已被删除，请关闭原更改。" }
                    row?.getLong("serverRevision") ?: 0L
                } else {
                    runCatching { revision(tables, table, id) }.getOrDefault(0L)
                }
            freshVersions.put(key, revision)
        }
        if (previous.aggregateType in setOf("account", "account_group") && previous.commandType == "metadata.update") {
            val rootKey = "${previous.aggregateType}:${previous.aggregateId}"
            require(oldVersions.getLong(rootKey) == freshVersions.getLong(rootKey)) {
                "账户或分组资料已被更新，请关闭此更改并重新编辑；不能用旧资料覆盖新版本。"
            }
        }
        if (previous.aggregateType in setOf("ledger", "category", "tag", "tag_group")) {
            require(oldVersions.keys().asSequence().all { oldVersions.getLong(it) == freshVersions.getLong(it) }) {
                "资料或依赖版本已变化，请关闭原更改并重新确认；不能用旧资料覆盖新版本。"
            }
        }
        if (previous.aggregateType == "category" && previous.commandType == "metadata.archive" &&
            JSONObject(previous.payloadJson).getBoolean("archived")
        ) {
            val children =
                tables
                    .getJSONArray("categories")
                    .objects()
                    .filter {
                        it.optString("parentId") == previous.aggregateId && it.isNull("deletedAt")
                    }.associate { "category:${it.getString("id")}" to it.getLong("serverRevision") }
            val oldChildren =
                oldVersions
                    .keys()
                    .asSequence()
                    .filter {
                        it.startsWith("category:") &&
                            it != "category:${previous.aggregateId}"
                    }.associateWith { oldVersions.getLong(it) }
            require(children == oldChildren) {
                "子分类已变化，请关闭原更改并重新确认归档。"
            }
        }
        if (previous.aggregateType == "account_group" && previous.commandType == "metadata.delete") {
            val members =
                tables
                    .getJSONArray("accounts")
                    .objects()
                    .filter {
                        it.optString("groupId") == previous.aggregateId && it.isNull("deletedAt")
                    }.associate { "account:${it.getString("id")}" to it.getLong("serverRevision") }
            val oldMembers =
                oldVersions
                    .keys()
                    .asSequence()
                    .filter { it.startsWith("account:") }
                    .associateWith { oldVersions.getLong(it) }
            require(
                members == oldMembers &&
                    oldVersions
                        .keys()
                        .asSequence()
                        .filter { it.startsWith("account_group:") }
                        .all { oldVersions.getLong(it) == freshVersions.getLong(it) },
            ) {
                "分组或成员资料已变化，请关闭原更改并重新确认删除。"
            }
        }
        if (previous.commandType == "metadata.reorder") {
            require(oldVersions.keys().asSequence().all { oldVersions.getLong(it) == freshVersions.getLong(it) }) {
                "排序对象版本已变化，请关闭原更改并重新排序。"
            }
        }
        val newId = UUID.randomUUID()
        check(
            dao.intentsInOrder(identity.ownerId, identity.epochId).none {
                it.aggregateType == previous.aggregateType && it.aggregateId == previous.aggregateId &&
                    it.opId != previous.opId && it.state in setOf("QUEUED", "AMBIGUOUS", "ACKED")
            },
        ) { "该更改仍在等待服务器规范确认，请先重试同步原操作。" }
        val command =
            TypedCommand(
                opId = newId,
                groupId = UUID.randomUUID(),
                ownerId = UUID.fromString(identity.ownerId),
                epochId = UUID.fromString(identity.epochId),
                deviceId = deviceId,
                aggregateType = previous.aggregateType,
                aggregateId = UUID.fromString(previous.aggregateId),
                baseVersionsJson = freshVersions.toString(),
                commandType = previous.commandType,
                schemaVersion = previous.schemaVersion,
                payloadJson = previous.payloadJson,
            )
        database.runInTransaction {
            if (previous.commandType.startsWith("credit.") || previous.aggregateType == "installment" ||
                previous.aggregateType == "account" &&
                previous.commandType in setOf("metadata.archive", "metadata.update", "metadata.reorder") ||
                previous.aggregateType == "account_group" && previous.commandType == "metadata.delete"
            ) {
                requireNoPendingAccountMutations(
                    freshVersions
                        .keys()
                        .asSequence()
                        .filter { it.startsWith("account:") }
                        .map { UUID.fromString(it.removePrefix("account:")) }
                        .toSet(),
                )
            }
            if (previous.commandType == "metadata.reorder" && previous.aggregateType in setOf("account", "account_group")) {
                check(
                    dao.intentsInOrder(identity.ownerId, identity.epochId).none {
                        it.aggregateType in setOf("account_group", "account") && it.state in setOf("QUEUED", "AMBIGUOUS", "ACKED")
                    },
                ) { "账户或分组仍有操作等待确认，请先同步原操作。" }
            }
            if (previous.aggregateType == "account_group") {
                check(
                    dao.intentsInOrder(identity.ownerId, identity.epochId).none {
                        it.aggregateType == "account_group" && it.state in setOf("QUEUED", "AMBIGUOUS", "ACKED")
                    },
                ) { "账户分组仍有操作等待确认，请先同步原操作。" }
            }
            if (previous.aggregateType in setOf("ledger", "category")) {
                AccountLedgerCommands(database).requireNoPendingLedgerCommands()
            }
            if (previous.aggregateType == "ledger" && previous.commandType == "metadata.delete") {
                check(
                    dao.intentsInOrder(identity.ownerId, identity.epochId).none {
                        it.state in setOf("QUEUED", "AMBIGUOUS", "ACKED")
                    },
                ) { "仍有操作等待确认，请先同步再重新确认删除账本。" }
            }
            if (previous.aggregateType in setOf("tag", "tag_group")) {
                check(
                    dao.intentsInOrder(identity.ownerId, identity.epochId).none {
                        it.aggregateType in setOf("tag", "tag_group") && it.state in setOf("QUEUED", "AMBIGUOUS", "ACKED")
                    },
                ) { "标签或分组仍有操作等待确认，请先同步原操作。" }
            }
            if (oldIntent.commandType in RECOVERY_COMMANDS) {
                check(
                    dao.intentsInOrder(identity.ownerId, identity.epochId).none {
                        it.commandType in RECOVERY_COMMANDS && it.state in setOf("QUEUED", "AMBIGUOUS", "ACKED")
                    },
                ) { "报销或退款更改仍待服务器确认；请先重试原操作，不能生成新操作。" }
            }
            typed.enqueue(command)
            dao.insertIntent(
                AccountMutationIntentEntity(
                    newId.toString(),
                    oldIntent.ownerId,
                    oldIntent.epochId,
                    oldIntent.aggregateType,
                    oldIntent.aggregateId,
                    oldIntent.commandType,
                    oldIntent.payloadJson,
                    "QUEUED",
                    System.currentTimeMillis(),
                ),
            )
            dao.insertPrediction(
                AccountPredictionEntity(
                    newId.toString(),
                    oldIntent.ownerId,
                    oldIntent.epochId,
                    oldIntent.aggregateType,
                    oldIntent.aggregateId,
                    oldIntent.payloadJson,
                    true,
                ),
            )
        }
        return newId
    }

    fun hasPendingBudgetMutation(ledgerId: UUID): Boolean {
        val identity = database.identity().read() ?: error("Dataset identity missing")
        return dao.hasPendingBudgetMutation(identity.ownerId, identity.epochId, ledgerId.toString())
    }

    fun configureCredit(
        deviceId: UUID,
        accountId: UUID,
        expectedAccountRevision: Long,
        limit: Money,
        statementDay: Int,
        includeStatementDay: Boolean,
        repaymentRule: String,
        repaymentValue: Int,
        timezone: String,
    ) {
        require(limit.unscaled >= 0 && statementDay in 1..31)
        require(
            (repaymentRule == "FIXED_MONTH_DAY" && repaymentValue in 1..31) ||
                (repaymentRule == "DAYS_AFTER_STATEMENT" && repaymentValue in 1..366),
        )
        require(timezone.length <= 128 && runCatching { java.time.ZoneId.of(timezone) }.isSuccess) { "信用账期时区无效" }
        requireCreditAccount(accountId, expectedAccountRevision, limit.currency)
        val profile =
            JSONObject()
                .put("accountId", accountId.toString())
                .put("limit", money(limit))
                .put("statementDay", statementDay)
                .put("includeStatementDay", includeStatementDay)
                .put("repaymentRule", repaymentRule)
                .put("repaymentValue", repaymentValue)
                .put("timezone", timezone)
        enqueue(
            deviceId,
            "account",
            accountId,
            "credit.configure",
            JSONObject().put("creditProfile", profile),
            JSONObject().put("account:$accountId", expectedAccountRevision),
            rejectPendingForAggregate = true,
        )
    }

    fun adjustCreditCapacity(
        deviceId: UUID,
        accountId: UUID,
        expectedAccountRevision: Long,
        delta: Money,
        reason: String,
        occurredAtMillis: Long = System.currentTimeMillis(),
    ) {
        require(delta.unscaled != 0L && occurredAtMillis in 0..System.currentTimeMillis())
        val reasonText = reason.trim()
        require(reasonText.isNotEmpty() && reasonText.toByteArray(Charsets.UTF_8).size <= 512) { "请输入不超过512字节的原因" }
        val tables = requireCreditAccount(accountId, expectedAccountRevision, delta.currency)
        require(
            tables.getJSONArray("credit_profiles").objects().any {
                it.optString("accountId") == accountId.toString() && it.isActive() && it.canonicalBoolean("enabled") &&
                    it.money("creditLimit").currency == delta.currency
            },
        ) { "请先配置有效的信用账期" }
        val payload =
            JSONObject()
                .put(
                    "capacityDelta",
                    money(delta),
                ).put("occurredAt", loanTimestamp(occurredAtMillis))
                .put("reason", reasonText)
        enqueue(
            deviceId,
            "account",
            accountId,
            "credit.capacityAdjust",
            payload,
            JSONObject().put("account:$accountId", expectedAccountRevision),
            rejectPendingForAggregate = true,
        )
    }

    private fun requireCreditAccount(
        accountId: UUID,
        expectedRevision: Long,
        currency: String,
    ): JSONObject {
        val identity = database.identity().read() ?: error("Dataset identity missing")
        require(
            identity.snapshotSchemaVersion in
                dev.icos.app.data.remote.SnapshotProtocol.DATA4_SCHEMA..dev.icos.app.data.remote.SnapshotProtocol.DATA6_SCHEMA,
        ) { "信用命令需要支持信用账期的账号数据版本；请先同步升级。" }
        val tables = SnapshotImporter.canonicalTables(database)
        val account = find(tables, "accounts", accountId)
        require(
            expectedRevision > 0 && account.getLong("serverRevision") == expectedRevision &&
                account.optString("type") == "LIABILITY" && account.canonicalBoolean("selectable") &&
                account.isActive() && account.getString("currency") == currency,
        ) { "负债账户已变化或不可选；请刷新后重新编辑。" }
        return tables
    }

    fun configureBudget(
        deviceId: UUID,
        ledgerId: UUID,
        period: ReportPeriod,
        scope: BudgetScope,
        periodStart: String,
        entries: List<Pair<UUID?, Money>>,
        expectedBudgetRevision: Long,
    ) {
        require(entries.isNotEmpty() && entries.size <= 500)
        val tables = SnapshotImporter.canonicalTables(database)
        require(expectedBudgetRevision >= 0 && revisionIfPresent(tables, "budgets", ledgerId) == expectedBudgetRevision) {
            "预算已变化，请刷新后重新编辑。"
        }
        val ledger = find(tables, "ledgers", ledgerId)
        val total = entries.singleOrNull { it.first == null }?.second ?: error("Budget total entry missing")
        require(total.currency == ledger.getString("baseCurrency") && total.unscaled >= 0)
        require(
            periodStart.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")) &&
                runCatching {
                    java.time.LocalDate.parse(periodStart)
                }.isSuccess,
        ) { "预算周期日期无效" }
        require(entries.all { it.second.currency == total.currency && it.second.unscaled >= 0 })
        require(entries.map { it.first }.distinct().size == entries.size)
        val categoryRows =
            entries
                .mapNotNull { (categoryId, amount) ->
                    categoryId?.let { id ->
                        val category = find(tables, "categories", id)
                        require(
                            category.getString("ledgerId") == ledgerId.toString() && category.getString("direction") == "EXPENSE" &&
                                !category.canonicalBoolean("excludeBudget"),
                        )
                        if (!category.isNull("parentId")) UUID.fromString(category.getString("parentId")) to amount else null to amount
                    }
                }.groupBy({ it.first }, { it.second })
        require(
            categoryRows[null]
                .orEmpty()
                .fold(Money(0, 0, total.currency)) { sum, amount -> sum.checkedAdd(amount) }
                .checkedSubtract(total)
                .unscaled <= 0,
        ) {
            "预算类别额度合计不能超过总额度"
        }
        categoryRows.filterKeys { it != null }.forEach { (parent, amounts) ->
            val parentId = requireNotNull(parent)
            val parentCategory = find(tables, "categories", parentId)
            require(
                parentCategory.getString("ledgerId") == ledgerId.toString() && parentCategory.getString("direction") == "EXPENSE" &&
                    !parentCategory.canonicalBoolean("excludeBudget"),
            )
            val parentLimit = entries.singleOrNull { it.first == parentId }?.second ?: error("子类别预算需要父类别预算额度")
            require(
                amounts
                    .fold(Money(0, 0, total.currency)) { sum, amount -> sum.checkedAdd(amount) }
                    .checkedSubtract(parentLimit)
                    .unscaled <= 0,
            ) {
                "预算子类别合计不能超过父类别额度"
            }
        }
        val payload =
            JSONObject()
                .put("periodType", period.name)
                .put("scope", scope.name)
                .put(
                    "entries",
                    JSONArray().also { array ->
                        entries.forEach { (categoryId, amount) ->
                            require(amount.currency == total.currency && amount.unscaled >= 0)
                            if (categoryId != null) {
                                val category = find(tables, "categories", categoryId)
                                require(
                                    category.getString("ledgerId") == ledgerId.toString() && category.getString("direction") == "EXPENSE" &&
                                        !category.canonicalBoolean("excludeBudget"),
                                )
                            }
                            array.put(
                                JSONObject().put("categoryId", categoryId?.toString() ?: JSONObject.NULL).put("amount", money(amount)),
                            )
                        }
                    },
                )
        if (scope == BudgetScope.DEFAULT) payload.put("effectiveFrom", periodStart) else payload.put("periodStart", periodStart)
        val versions =
            JSONObject()
                .put("budget:$ledgerId", expectedBudgetRevision)
                .put("ledger:$ledgerId", revision(tables, "ledgers", ledgerId))
        entries.mapNotNull { it.first }.distinct().forEach { categoryId ->
            versions.put("category:$categoryId", revision(tables, "categories", categoryId))
        }
        enqueue(deviceId, "budget", ledgerId, "budget.configure", payload, versions, rejectPendingBudget = true)
    }

    fun resetBudget(
        deviceId: UUID,
        ledgerId: UUID,
        period: ReportPeriod,
        periodStart: String,
        clearCurrent: Boolean,
        expectedBudgetRevision: Long,
    ) {
        val tables = SnapshotImporter.canonicalTables(database)
        require(expectedBudgetRevision >= 0 && revisionIfPresent(tables, "budgets", ledgerId) == expectedBudgetRevision) {
            "预算已变化，请刷新后重新编辑。"
        }
        find(tables, "ledgers", ledgerId)
        require(
            periodStart.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")) &&
                runCatching {
                    java.time.LocalDate.parse(periodStart)
                }.isSuccess,
        ) { "预算周期日期无效" }
        val payload =
            JSONObject()
                .put("periodType", period.name)
                .put("periodStart", periodStart)
                .put("mode", if (clearCurrent) "CLEAR_CURRENT" else "RESET_DEFAULT")
        val versions =
            JSONObject()
                .put("budget:$ledgerId", expectedBudgetRevision)
                .put("ledger:$ledgerId", revision(tables, "ledgers", ledgerId))
        enqueue(deviceId, "budget", ledgerId, "budget.reset", payload, versions, rejectPendingBudget = true)
    }

    fun setBudgetEnabled(
        deviceId: UUID,
        ledgerId: UUID,
        expectedBudgetRevision: Long,
        enabled: Boolean,
    ) {
        require(expectedBudgetRevision >= 0) { "预算版本无效，请刷新后重新查看。" }
        database.runInTransaction {
            val tables = SnapshotImporter.canonicalTables(database)
            val ledger = find(tables, "ledgers", ledgerId)
            val roots =
                tables.getJSONArray("budgets").objects().filter {
                    it.optString("id") == ledgerId.toString() || it.optString("ledgerId") == ledgerId.toString()
                }
            require(roots.size <= 1) { "规范预算根重复，无法安全启停。" }
            val budget = roots.singleOrNull()
            if (budget != null) {
                require(
                    budget.optString("id") == ledgerId.toString() && budget.optString("ledgerId") == ledgerId.toString() &&
                        budget.isNull("deletedAt"),
                ) { "规范预算账本不匹配，无法安全启停。" }
            }
            val actualRevision =
                budget?.get("serverRevision")?.let { raw ->
                    require(raw is Number) { "规范预算版本无效，无法安全启停。" }
                    raw.toString().toLongOrNull()?.takeIf { it > 0 } ?: error("规范预算版本无效，无法安全启停。")
                } ?: 0L
            require(actualRevision == expectedBudgetRevision) { "预算已变化，请重新查看后再操作。" }
            val versions =
                JSONObject()
                    .put("budget:$ledgerId", actualRevision)
                    .put("ledger:$ledgerId", ledger.getLong("serverRevision"))
            // A replay marker is required even though applyPredictions leaves this command unchanged.
            enqueue(
                deviceId,
                "budget",
                ledgerId,
                "budget.setEnabled",
                JSONObject().put("enabled", enabled),
                versions,
                rejectPendingBudget = true,
            )
        }
    }

    private fun revisionIfPresent(
        tables: JSONObject,
        table: String,
        id: UUID,
    ): Long =
        (0 until tables.getJSONArray(table).length())
            .map { tables.getJSONArray(table).getJSONObject(it) }
            .firstOrNull { it.optString("id") == id.toString() }
            ?.getLong("serverRevision") ?: 0

    fun createAccount(
        deviceId: UUID,
        id: UUID,
        name: String,
        currency: String,
        opening: Money,
        openingAtMillis: Long,
        groupId: UUID?,
        accountType: String,
    ): UUID {
        require(accountType in setOf("ASSET", "LIABILITY") && currency == opening.currency)
        val tables = SnapshotImporter.canonicalTables(database)
        val groups = tables.getJSONArray("account_groups")
        val group =
            groupId ?: (0 until groups.length())
                .map { groups.getJSONObject(it) }
                .firstOrNull { it.canonicalBoolean("isDefault") && it.isActive() }
                ?.uuid("id")
                ?: throw IllegalStateException("Account group unavailable")
        val ledger =
            (0 until tables.getJSONArray("ledgers").length())
                .map { tables.getJSONArray("ledgers").getJSONObject(it) }
                .firstOrNull { it.canonicalBoolean("isDefault") && it.isActive() }
                ?.uuid("id")
                ?: throw IllegalStateException("Ledger unavailable")
        val date =
            SimpleDateFormat(
                "yyyy-MM-dd",
                Locale.ROOT,
            ).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(openingAtMillis))
        val entity =
            JSONObject()
                .put("type", accountType)
                .put("name", name.trim())
                .put("currency", currency)
                .put("groupId", group.toString())
                .put("iconRef", JSONObject.NULL)
                .put("sortOrder", accountsInGroup(tables, group) + 1)
                .put("hiddenAt", JSONObject.NULL)
                .put("archivedAt", JSONObject.NULL)
                .put("includeInAssets", true)
                .put("selectable", true)
                .put("visibilityMode", "ALL")
                .put("ledgerIds", JSONArray())
                .put("openingBalance", money(opening))
                .put("openingDate", date)
        val payload = JSONObject().put("entity", entity)
        val versions =
            JSONObject()
                .put(
                    "account:$id",
                    0,
                ).put("account_group:$group", revision(tables, "account_groups", group))
                .put("ledger:$ledger", revision(tables, "ledgers", ledger))
        enqueue(deviceId, "account", id, "metadata.create", payload, versions)
        return id
    }

    fun saveRecord(
        deviceId: UUID,
        id: UUID,
        existingRevision: Long?,
        ledgerId: UUID,
        input: RecordInput,
        occurredAtMillis: Long,
        timezone: String,
        remark: String,
        categoryId: UUID?,
        tagIds: List<UUID>,
        sourceTemplateId: UUID? = null,
        sourceTemplateRevision: Long? = null,
    ) = database.runInTransaction {
        require(
            input is RecordInput.Expense || input is RecordInput.Income || input is RecordInput.Transfer,
        ) { "Unsupported account record input" }
        if (sourceTemplateId == null) {
            require(sourceTemplateRevision == null)
        } else {
            require(sourceTemplateRevision != null && sourceTemplateRevision > 0) { "Template must be server-confirmed before use" }
        }
        if (existingRevision != null &&
            existingRevision <= 0
        ) {
            throw IllegalStateException("Record create must be confirmed by server before update")
        }
        // Creating a record depends on metadata, not historical records/postings/contributions.
        val tables = SnapshotImporter.canonicalTables(database, if (existingRevision == null)
            setOf("ledgers", "categories", "accounts", "tags", "record_templates") else null)
        val identity = database.identity().read() ?: error("Dataset identity missing")
        val mutationDao = database.accountMutations()
        CanonicalAccountRepository.applyPredictions(tables,
            mutationDao.predictions(identity.ownerId, identity.epochId).filter { it.aggregateType == "category" },
            mutationDao.intentsInOrder(identity.ownerId, identity.epochId), UUID.fromString(identity.ownerId), UUID.fromString(identity.epochId))
        if (existingRevision != null) requireWritableCanonicalRecord(tables, id, existingRevision)
        val transfer = input as? RecordInput.Transfer
        if (transfer == null) {
            require(categoryId != null) { "Expense and income records require a category" }
            requireCategory(tables, ledgerId, categoryId!!, if (input is RecordInput.Expense) "EXPENSE" else "INCOME")
        } else {
            require(categoryId == null) { "Transfer principal does not accept a category" }
            if (transfer.from.amount.currency != transfer.to.amount.currency) {
                throw IllegalArgumentException("Account transfer writes require matching source and destination currencies")
            }
        }
        val feeCategoryId = transfer?.fee?.let { purposeCategory(tables, ledgerId, "TRANSFER_FEE") }
        val bonusCategoryId = transfer?.bonus?.let { purposeCategory(tables, ledgerId, "TRANSFER_BONUS") }
        val date =
            SimpleDateFormat(
                "yyyy-MM-dd",
                Locale.ROOT,
            ).apply { timeZone = TimeZone.getTimeZone(timezone) }.format(Date(occurredAtMillis))
        val occurred =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT)
                .apply {
                    timeZone = TimeZone.getTimeZone(timezone)
                }.format(Date(occurredAtMillis))
        val entity =
            JSONObject()
                .put("ledgerId", ledgerId.toString())
                .put("categoryId", categoryId?.toString() ?: JSONObject.NULL)
                .put("occurredAt", occurred)
                .put("timezone", timezone)
                .put("localDate", date)
                .put("remark", remark)
                .put("tagIds", JSONArray(tagIds.map(UUID::toString)))
                .put("templateId", sourceTemplateId?.toString() ?: JSONObject.NULL)
                .put("input", input(input))
                .put("feeCategoryId", feeCategoryId?.toString() ?: JSONObject.NULL)
                .put("bonusCategoryId", bonusCategoryId?.toString() ?: JSONObject.NULL)
        val command = if (existingRevision == null) "record.create" else "record.update"
        // This editor cannot change attachments; omit on update to preserve even references not yet pulled.
        if (existingRevision == null) entity.put("attachmentIds", JSONArray())
        val payload = if (existingRevision == null) JSONObject().put("record", entity) else JSONObject().put("changes", entity)
        val versions = JSONObject().put("record:$id", existingRevision ?: 0)
        sourceTemplateId?.let { template ->
            val templateRow = find(tables, "record_templates", template)
            require(templateRow.getLong("serverRevision") == sourceTemplateRevision) { "Template revision changed; sync before use" }
            versions.put("record_template:${templateRow.getString("id")}", sourceTemplateRevision!!)
        }
        versions.put("ledger:$ledgerId", revision(tables, "ledgers", ledgerId))
        if (existingRevision != null) {
            mergeVersions(versions, recordDeletionVersions(tables, find(tables, "records", id), existingRevision))
        }
        categoryId?.let { addCategoryVersions(versions, tables, it) }
        feeCategoryId?.let { addCategoryVersions(versions, tables, it) }
        bonusCategoryId?.let { addCategoryVersions(versions, tables, it) }
        val effects = input.calculate()
        effects.postings.forEach { posting ->
            val row = find(tables, "accounts", posting.accountId)
            if (row.getString("currency") != posting.amount.currency) {
                throw IllegalArgumentException("Account dependency currency does not match its allocation")
            }
            versions.put("account:${posting.accountId}", row.getLong("serverRevision"))
        }
        tagIds.distinct().forEach { tag -> versions.put("tag:$tag", revision(tables, "tags", tag)) }
        enqueue(deviceId, "record", id, command, payload, versions)
    }

    fun markClaim(
        deviceId: UUID,
        id: UUID,
        expectedRevision: Long,
        claimState: String,
    ) {
        require(claimState in setOf("NONE", "PENDING"))
        val tables = SnapshotImporter.canonicalTables(database)
        val record = find(tables, "records", id)
        require(
            record.getLong("serverRevision") == expectedRevision && expectedRevision > 0,
        ) { "Record revision changed; sync before editing" }
        require(
            record.optString("status") == "POSTED" && record.optString("direction") == "EXPENSE" &&
                record.optString("claimState", "NONE") in setOf("NONE", "PENDING") &&
                record.optString("claimState", "NONE") != claimState && record.isNull("parentRecordId"),
        ) {
            "Claim mark requires a confirmed posted expense"
        }
        val actual = record.getJSONObject("actual")
        require(actual.getString("unscaled").toLong() > 0) { "Zero amount records cannot be reimbursed" }
        enqueueRecovery(
            deviceId,
            "record",
            id,
            "claim.mark",
            JSONObject().put("claimState", claimState),
            JSONObject().put("record:$id", expectedRevision),
        )
    }

    fun settleClaims(
        deviceId: UUID,
        id: UUID,
        ledgerId: UUID,
        allocations: List<dev.icos.app.data.local.FinanceClaimAllocation>,
        accountId: UUID,
        incomeCategoryId: UUID?,
        remainderAction: dev.icos.app.domain.RemainderAction,
        settledAtMillis: Long,
        timezone: String,
    ) {
        require(id != UUID(0, 0) && allocations.size in 1..50 && allocations.map { it.originRecordId }.distinct().size == allocations.size)
        require(timezone in TimeZone.getAvailableIDs() && settledAtMillis in 0..System.currentTimeMillis())
        val currency = allocations.first().actualAmount.currency
        allocations.forEach {
            it.actualAmount.requirePositive()
            require(it.actualAmount.currency == currency)
        }
        val total = allocations.drop(1).fold(allocations.first().actualAmount) { sum, item -> sum.checkedAdd(item.actualAmount) }
        val tables = SnapshotImporter.canonicalTables(database)
        val ledger = find(tables, "ledgers", ledgerId)
        val account = find(tables, "accounts", accountId)
        require(account.canonicalBoolean("selectable") && account.getString("currency") == currency) { "Settlement account is unavailable" }
        if (account.optString("visibilityMode") == "SELECTED") {
            require(
                tables.getJSONArray("account_ledger_visibility").objects().any {
                    it.optString("accountId") == accountId.toString() && it.optString("ledgerId") == ledgerId.toString() && it.isActive()
                },
            ) { "Settlement account is not visible in this ledger" }
        }
        val category = incomeCategoryId?.let { find(tables, "categories", it) }
        if (category != null) require(category.getString("direction") == "INCOME" && category.getString("ledgerId") == ledgerId.toString())
        val versions =
            JSONObject()
                .put("claim_settlement:$id", 0)
                .put("ledger:$ledgerId", ledger.getLong("serverRevision"))
                .put("account:$accountId", account.getLong("serverRevision"))
        category?.let { addCategoryVersions(versions, tables, incomeCategoryId!!) }
        val date =
            SimpleDateFormat(
                "yyyy-MM-dd",
                Locale.ROOT,
            ).apply { timeZone = TimeZone.getTimeZone(timezone) }.format(Date(settledAtMillis))
        val settledAt =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT)
                .apply {
                    timeZone = TimeZone.getTimeZone(timezone)
                }.format(Date(settledAtMillis))
        val encodedAllocations = JSONArray()
        allocations.forEach { allocation ->
            val record = find(tables, "records", allocation.originRecordId)
            require(record.getLong("serverRevision") == allocation.expectedLocalRevision && allocation.expectedLocalRevision > 0)
            require(
                record.optString("ledgerId") == ledgerId.toString() && record.optString("direction") == "EXPENSE" &&
                    record.optString("status") == "POSTED" && record.isNull("parentRecordId") &&
                    record.optString("claimState") in setOf("PENDING", "PARTIAL"),
            ) { "Record is not eligible for settlement" }
            val originalAt =
                java.time.OffsetDateTime
                    .parse(record.getString("occurredAt"))
                    .toInstant()
                    .toEpochMilli()
            require(originalAt <= settledAtMillis) { "Settlement time cannot predate the original expense" }
            val input = allocation.actualAmount
            require(input.currency == currency)
            versions.put("record:${allocation.originRecordId}", record.getLong("serverRevision"))
            encodedAllocations.put(
                JSONObject().put("originRecordId", allocation.originRecordId.toString()).put("actualAmount", money(input)),
            )
        }
        require(total.unscaled > 0)
        val settlement =
            JSONObject()
                .put("ledgerId", ledgerId.toString())
                .put("actualAmount", money(total))
                .put("settledAt", settledAt)
                .put("timezone", timezone)
                .put("localDate", date)
                .put("accountId", accountId.toString())
                .put("incomeCategoryId", incomeCategoryId?.toString() ?: JSONObject.NULL)
                .put("remainderAction", remainderAction.name)
        enqueueRecovery(
            deviceId,
            "claim_settlement",
            id,
            "claim.settle",
            JSONObject().put("settlement", settlement).put("allocations", encodedAllocations),
            versions,
        )
    }

    fun cancelClaimSettlement(
        deviceId: UUID,
        id: UUID,
        expectedRevision: Long,
        reason: String,
    ) {
        val cleaned = reason.trim()
        require(cleaned.isNotEmpty() && cleaned.codePointCount(0, cleaned.length) <= 1000) { "Cancellation reason is required" }
        val tables = SnapshotImporter.canonicalTables(database)
        val settlement = find(tables, "claim_settlements", id)
        require(
            settlement.getLong("serverRevision") == expectedRevision && expectedRevision > 0 && settlement.optString("status") == "POSTED",
        )
        val ledgerId = UUID.fromString(settlement.getString("ledgerId"))
        val versions =
            JSONObject()
                .put("claim_settlement:$id", expectedRevision)
                .put("ledger:$ledgerId", revisionIncludingArchived(tables, "ledgers", ledgerId))
        if (!settlement.isNull("accountId")) {
            val accountId = UUID.fromString(settlement.getString("accountId"))
            versions.put("account:$accountId", revisionIncludingArchived(tables, "accounts", accountId))
        }
        tables
            .getJSONArray("claim_allocations")
            .objects()
            .filter { it.optString("settlementId") == id.toString() }
            .map { UUID.fromString(it.getString("claimItemId")) }
            .distinct()
            .forEach { recordId ->
                versions.put("record:$recordId", revisionIncludingArchived(tables, "records", recordId))
            }
        enqueueRecovery(deviceId, "claim_settlement", id, "claim.cancel", JSONObject().put("reason", cleaned), versions)
    }

    fun createRefund(
        deviceId: UUID,
        id: UUID,
        originRecordId: UUID,
        expectedRecordRevision: Long,
        amount: Money,
        accountId: UUID,
        refundAtMillis: Long,
        timezone: String,
        ledgerId: UUID,
    ) {
        amount.requirePositive()
        require(timezone in TimeZone.getAvailableIDs() && refundAtMillis in 0..System.currentTimeMillis())
        val tables = SnapshotImporter.canonicalTables(database)
        val record = find(tables, "records", originRecordId)
        require(record.getLong("serverRevision") == expectedRecordRevision && expectedRecordRevision > 0)
        require(
            record.optString("status") == "POSTED" && record.optString("direction") == "EXPENSE" && record.isNull("parentRecordId") &&
                record.optString("ledgerId") == ledgerId.toString(),
        ) { "Record is not eligible for refund" }
        val originalAt =
            runCatching {
                java.time.OffsetDateTime
                    .parse(record.getString("occurredAt"))
                    .toInstant()
                    .toEpochMilli()
            }.getOrElse { throw IllegalStateException("Canonical record time is invalid") }
        require(refundAtMillis >= originalAt) { "Refund time cannot predate the original expense" }
        val original =
            Money(
                record.getJSONObject("actual").getString("unscaled").toLong(),
                record.getJSONObject("actual").getInt("scale"),
                record.getJSONObject("actual").getString("currency"),
            )
        require(original.unscaled > 0 && original.currency == amount.currency) { "Refund amount must match a positive expense" }
        require(
            tables.getJSONArray("claim_settlements").objects().none { settlement ->
                settlement.optString("status") == "POSTED" &&
                    tables.getJSONArray("claim_allocations").objects().any {
                        it.optString("settlementId") == settlement.optString("id") &&
                            it.optString("claimItemId") == originRecordId.toString()
                    }
            },
        ) { "Posted reimbursement must be cancelled before refund" }
        val account = find(tables, "accounts", accountId)
        require(account.canonicalBoolean("selectable") && account.getString("currency") == amount.currency) {
            "Refund account currency or availability is invalid"
        }
        if (account.optString("visibilityMode") == "SELECTED") {
            require(
                tables.getJSONArray("account_ledger_visibility").objects().any {
                    it.optString("accountId") == accountId.toString() && it.optString("ledgerId") == ledgerId.toString() && it.isActive()
                },
            ) { "Refund account is not visible in this ledger" }
        }
        val versions =
            JSONObject()
                .put("refund:$id", 0)
                .put("record:$originRecordId", expectedRecordRevision)
                .put("ledger:$ledgerId", revision(tables, "ledgers", ledgerId))
                .put("account:$accountId", account.getLong("serverRevision"))
        val date =
            SimpleDateFormat(
                "yyyy-MM-dd",
                Locale.ROOT,
            ).apply { timeZone = TimeZone.getTimeZone(timezone) }.format(Date(refundAtMillis))
        val occurred =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT)
                .apply {
                    timeZone = TimeZone.getTimeZone(timezone)
                }.format(Date(refundAtMillis))
        enqueueRecovery(
            deviceId,
            "refund",
            id,
            "refund.create",
            JSONObject()
                .put("originRecordId", originRecordId.toString())
                .put("amount", money(amount))
                .put("accountId", accountId.toString())
                .put("refundAt", occurred)
                .put("timezone", timezone)
                .put("localDate", date),
            versions,
        )
    }

    fun cancelRefund(
        deviceId: UUID,
        id: UUID,
        expectedRevision: Long,
        expectedOriginRevision: Long,
        reason: String,
    ) {
        val cleaned = reason.trim()
        require(cleaned.isNotEmpty() && cleaned.codePointCount(0, cleaned.length) <= 1000) { "Cancellation reason is required" }
        val tables = SnapshotImporter.canonicalTables(database)
        val refund = find(tables, "refunds", id)
        require(refund.getLong("serverRevision") == expectedRevision && expectedRevision > 0 && refund.optString("status") == "POSTED")
        val recordId = UUID.fromString(refund.getString("originRecordId"))
        require(revision(tables, "records", recordId) == expectedOriginRevision && expectedOriginRevision > 0) {
            "Origin record changed; sync before cancelling refund"
        }
        val ledgerId = UUID.fromString(refund.getString("ledgerId"))
        val accountId = UUID.fromString(refund.getString("accountId"))
        val versions =
            JSONObject()
                .put("refund:$id", expectedRevision)
                .put("record:$recordId", revisionIncludingArchived(tables, "records", recordId))
                .put(
                    "ledger:$ledgerId",
                    revisionIncludingArchived(tables, "ledgers", ledgerId),
                ).put("account:$accountId", revisionIncludingArchived(tables, "accounts", accountId))
        enqueueRecovery(deviceId, "refund", id, "refund.cancel", JSONObject().put("reason", cleaned), versions)
    }

    private fun enqueueRecovery(
        deviceId: UUID,
        aggregate: String,
        id: UUID,
        command: String,
        payload: JSONObject,
        versions: JSONObject,
    ) {
        val identity = database.identity().read() ?: error("Dataset identity missing")
        val unresolved =
            dao.intentsInOrder(identity.ownerId, identity.epochId).firstOrNull {
                it.commandType in RECOVERY_COMMANDS &&
                    it.state in setOf("QUEUED", "AMBIGUOUS", "ACKED")
            }
        check(unresolved == null) { "报销或退款更改仍待服务器确认；请先同步原操作，暂不生成新操作。" }
        enqueue(deviceId, aggregate, id, command, payload, versions, rejectPendingRecovery = true, createPrediction = false)
    }

    fun deleteRecord(
        deviceId: UUID,
        id: UUID,
        expectedRevision: Long,
    ) {
        require(expectedRevision > 0) { "Record delete requires a server-confirmed revision" }
        val tables = SnapshotImporter.canonicalTables(database)
        val target = find(tables, "records", id)
        requireWritableCanonicalRecord(tables, id, expectedRevision)
        enqueue(
            deviceId,
            "record",
            id,
            "record.delete",
            JSONObject().put("dependencyStrategy", "REJECT"),
            recordDeletionVersions(tables, target, expectedRevision),
        )
    }

    fun deleteRecords(
        deviceId: UUID,
        revisions: Map<UUID, Long>,
    ) {
        require(revisions.size in 1..500 && revisions.values.all { it > 0 }) { "请选择 1–500 笔已同步的账单" }
        database.runInTransaction {
            val identity = database.identity().read() ?: error("Dataset identity missing")
            check(dao.intentsInOrder(identity.ownerId, identity.epochId).none { it.state in setOf("QUEUED", "AMBIGUOUS", "ACKED") }) {
                "仍有更改等待服务器确认；请先同步原操作。"
            }
            val tables = SnapshotImporter.canonicalTables(database)
            val versions = JSONObject()
            revisions.forEach { (id, expected) ->
                requireWritableCanonicalRecord(tables, id, expected)
                mergeVersions(versions, recordDeletionVersions(tables, find(tables, "records", id), expected))
            }
            require(versions.length() <= 2048) { "所选账单依赖过多，请缩小批次" }
            enqueue(
                deviceId,
                "record_batch",
                UUID.randomUUID(),
                "record.bulkDelete",
                JSONObject().put("recordIds", JSONArray(revisions.keys.map(UUID::toString))).put("dependencyStrategy", "REJECT"),
                versions,
                createPrediction = false,
            )
        }
    }

    fun saveRecordTemplate(
        deviceId: UUID,
        value: FinanceRecordTemplate,
        ledgerId: UUID,
    ) {
        require(
            value.name.trim().isNotEmpty() && value.name.trim().length <= 80 &&
                value.name.none {
                    it == '\u0000'
                },
        ) { "Template name must contain 1 to 80 characters" }
        require(value.remark.length <= 2000 && value.remark.none { it == '\u0000' }) { "Template remark is invalid" }
        require(value.timezone in TimeZone.getAvailableIDs()) { "Template timezone is invalid" }
        require(value.tagIds.size <= 50 && value.tagIds.distinct().size == value.tagIds.size) { "Template tags are invalid" }
        val existingRevision = value.serverRevision
        require(existingRevision == null || existingRevision > 0) { "Template must be server-confirmed before update" }
        val tables = SnapshotImporter.canonicalTables(database)
        val old = if (existingRevision != null) find(tables, "record_templates", value.id) else null
        if (existingRevision != null) {
            require(old!!.getLong("serverRevision") == existingRevision && old.getString("ledgerId") == ledgerId.toString()) {
                "Template revision changed; sync before editing"
            }
            requireNoUnsupportedTemplateLinks(tables, value.id)
        } else {
            require(
                (0 until tables.getJSONArray("record_templates").length()).none {
                    tables.getJSONArray("record_templates").getJSONObject(it).optString("id") == value.id.toString()
                },
            ) { "Template identity already exists" }
        }
        val input = value.input
        require(input is RecordInput.Expense || input is RecordInput.Income || input is RecordInput.Transfer) {
            "Unsupported account template input"
        }
        require(input !is RecordInput.Expense || input.recordCurrencyAmounts == null) { "Account templates do not support FX allocations" }
        input.calculate()
        if (input is RecordInput.Transfer) {
            if (input.from.amount.currency !=
                input.to.amount.currency
            ) {
                throw IllegalArgumentException("Account template transfers require matching currencies")
            }
        }
        val date =
            SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
                .apply {
                    timeZone = TimeZone.getTimeZone(value.timezone)
                }.format(Date(value.occurredAtMillis))
        val occurred =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT)
                .apply {
                    timeZone = TimeZone.getTimeZone(value.timezone)
                }.format(Date(value.occurredAtMillis))
        val transfer = input as? RecordInput.Transfer
        if (transfer == null) {
            require(value.categoryId != null) { "Expense and income templates require a category" }
            requireCategory(tables, ledgerId, value.categoryId, if (input is RecordInput.Expense) "EXPENSE" else "INCOME")
        } else {
            require(value.categoryId == null) { "Transfer templates do not accept a category" }
        }
        val feeCategory = transfer?.fee?.let { purposeCategory(tables, ledgerId, "TRANSFER_FEE") }
        val bonusCategory = transfer?.bonus?.let { purposeCategory(tables, ledgerId, "TRANSFER_BONUS") }
        val record =
            JSONObject()
                .put("ledgerId", ledgerId.toString())
                .put("categoryId", value.categoryId?.toString() ?: JSONObject.NULL)
                .put("occurredAt", occurred)
                .put("timezone", value.timezone)
                .put("localDate", date)
                .put("remark", value.remark)
                .put("tagIds", JSONArray(value.tagIds.map(UUID::toString)))
                .put("attachmentIds", JSONArray())
                .put("input", input(input))
                .put("feeCategoryId", feeCategory?.toString() ?: JSONObject.NULL)
                .put("bonusCategoryId", bonusCategory?.toString() ?: JSONObject.NULL)
        val versions =
            JSONObject()
                .put("record_template:${value.id}", existingRevision ?: 0)
                .put("ledger:$ledgerId", revision(tables, "ledgers", ledgerId))
        value.categoryId?.let { addCategoryVersions(versions, tables, it) }
        feeCategory?.let { addCategoryVersions(versions, tables, it) }
        bonusCategory?.let { addCategoryVersions(versions, tables, it) }
        input.calculate().postings.forEach { posting ->
            val account = find(tables, "accounts", posting.accountId)
            require(
                account.getString("currency") == posting.amount.currency,
            ) { "Account dependency currency does not match its allocation" }
            versions.put("account:${posting.accountId}", account.getLong("serverRevision"))
        }
        value.tagIds.distinct().forEach { tag -> versions.put("tag:$tag", revision(tables, "tags", tag)) }
        val payload =
            JSONObject().put(
                if (existingRevision == null) "entity" else "changes",
                JSONObject().put("name", value.name.trim()).put("record", record),
            )
        enqueue(
            deviceId,
            "record_template",
            value.id,
            if (existingRevision ==
                null
            ) {
                "metadata.create"
            } else {
                "metadata.update"
            },
            payload,
            versions,
            rejectPendingForAggregate = true,
        )
    }

    fun deleteRecordTemplate(
        deviceId: UUID,
        id: UUID,
        expectedRevision: Long,
    ) {
        require(expectedRevision > 0) { "Template delete requires a server-confirmed revision" }
        val tables = SnapshotImporter.canonicalTables(database)
        val target =
            tables.getJSONArray("record_templates").objects().firstOrNull {
                it.optString("id") == id.toString() && it.isNull("deletedAt")
            } ?: throw IllegalStateException("Canonical record template unavailable")
        require(target.getLong("serverRevision") == expectedRevision) { "Template revision changed; sync before deleting" }
        requireNoUnsupportedTemplateLinks(tables, id)
        val versions = JSONObject().put("record_template:$id", expectedRevision)
        enqueue(
            deviceId,
            "record_template",
            id,
            "metadata.delete",
            JSONObject().put("strategy", "KEEP_RECORDS"),
            versions,
            rejectPendingForAggregate = true,
        )
    }

    private fun requireNoUnsupportedTemplateLinks(
        tables: JSONObject,
        templateId: UUID,
    ) {
        val ids =
            tables
                .getJSONArray("recurrence_rule_segments")
                .objects()
                .filter {
                    it.optString("templateId") == templateId.toString() &&
                        it.isActive()
                }.map { it.getString("id") }
                .toSet()
        require(
            tables.getJSONArray("recurrence_plans").objects().none { plan ->
                plan.isActive() &&
                    tables.getJSONArray("recurrence_rule_segments").objects().any { segment ->
                        segment.getString("id") in ids && segment.optString("planId") == plan.optString("id")
                    }
            },
        ) { "Template is used by recurrence; edit or delete is not supported" }
    }

    fun createLoan(
        deviceId: UUID,
        input: LoanPlanInput,
        disbursement: LoanDisbursement?,
        opId: UUID,
    ) {
        require(opId != UUID(0, 0))
        val tables = SnapshotImporter.canonicalTables(database)
        require(input.paidTermCount == 0 || disbursement == null) { "Historical paid terms cannot include a new disbursement" }
        disbursement?.let { require(it.occurredAtMillis in 0..System.currentTimeMillis()) { "Disbursement timestamp is invalid" } }
        LoanCalculator.create(input)
        val liability = find(tables, "accounts", input.accountId)
        val hasLoanPlan =
            tables.getJSONArray("loan_plans").objects().any {
                it.optString("accountId") == input.accountId.toString() && it.optString("status") != "DELETED" && it.isActive()
            }
        require(
            liability.optString("type") == "LIABILITY" && liability.optString("currency") == input.principal.currency &&
                liability.canonicalBoolean("selectable") && liability.isActive() && !hasLoanPlan,
        ) { "Loan requires an unused selectable liability account with matching currency" }
        val identity = database.identity().read() ?: error("Dataset identity missing")
        require(
            dao.intentsInOrder(identity.ownerId, identity.epochId).none { intent ->
                intent.aggregateType == "loan" && intent.commandType == "loan.create" && intent.state in
                    setOf(
                        "QUEUED",
                        "AMBIGUOUS",
                        "ACKED",
                    ) &&
                    runCatching {
                        JSONObject(intent.payloadJson).getJSONObject("plan").optString("accountId") == input.accountId.toString()
                    }.getOrDefault(false)
            },
        ) { "Loan creation for this liability is awaiting canonical confirmation; retry the original operation." }
        val payload =
            JSONObject()
                .put(
                    "plan",
                    JSONObject()
                        .put("id", input.id.toString())
                        .put("accountId", input.accountId.toString())
                        .put(
                            "principal",
                            money(input.principal),
                        ).put(
                            "annualRate",
                            JSONObject().put("numerator", input.annualRateNumerator).put("denominator", input.annualRateDenominator),
                        ).put(
                            "termCount",
                            input.termCount,
                        ).put(
                            "paidTermCount",
                            input.paidTermCount,
                        ).put("repaymentType", input.repaymentType.name)
                        .put("firstDueDate", input.firstDueDate),
                ).put(
                    "disbursement",
                    disbursement?.let { movement ->
                        val cash = find(tables, "accounts", movement.actualAccountId)
                        require(
                            cash.optString("type") == "ASSET" && cash.optString("currency") == input.principal.currency &&
                                cash.canonicalBoolean("selectable") && cash.isActive(),
                        ) { "Disbursement requires a matching selectable asset account" }
                        JSONObject()
                            .put(
                                "actualAccountId",
                                movement.actualAccountId.toString(),
                            ).put("occurredAt", loanTimestamp(movement.occurredAtMillis))
                    } ?: JSONObject.NULL,
                )
        val versions = JSONObject().put("loan:${input.id}", 0).put("account:${input.accountId}", liability.getLong("serverRevision"))
        disbursement?.let { versions.put("account:${it.actualAccountId}", revision(tables, "accounts", it.actualAccountId)) }
        enqueue(
            deviceId,
            "loan",
            input.id,
            "loan.create",
            payload,
            versions,
            rejectPendingForAggregate = true,
            createPrediction = false,
            opId = opId,
        )
    }

    fun adjustLoanRate(
        deviceId: UUID,
        planId: UUID,
        expectedRevision: Long,
        effectiveTerm: Int,
        numerator: Long,
        denominator: Long,
    ) {
        require(numerator >= 0 && denominator > 0 && expectedRevision > 0)
        val tables = SnapshotImporter.canonicalTables(database)
        val plan = requireLoanRevision(tables, planId, expectedRevision)
        val terms =
            tables.getJSONArray("loan_terms").objects().filter {
                it.optString("planId") == planId.toString() && it.optString("state") != "SUPERSEDED" && it.isNull("deletedAt")
            }
        require(
            terms.any { it.optInt("termIndex") == effectiveTerm } &&
                terms.none { it.optInt("termIndex") >= effectiveTerm && it.optString("state") in setOf("PAID", "POSTED") },
        ) { "Paid or posted terms cannot be recalculated" }
        val schedule = terms.sortedBy { it.optInt("termIndex") }.map(::loanTerm)
        LoanCalculator.changeRate(
            schedule,
            effectiveTerm,
            numerator,
            denominator,
            RepaymentMethod.valueOf(plan.getString("repaymentType")),
            expectedRevision,
        )
        val payload =
            JSONObject()
                .put(
                    "effectiveTerm",
                    effectiveTerm,
                ).put("annualRate", JSONObject().put("numerator", numerator).put("denominator", denominator))
        enqueue(
            deviceId,
            "loan",
            planId,
            "loan.adjustRate",
            payload,
            JSONObject().put("loan:$planId", expectedRevision),
            rejectPendingForAggregate = true,
            createPrediction = false,
        )
    }

    fun adjustLoanTerm(
        deviceId: UUID,
        planId: UUID,
        expectedRevision: Long,
        termIndex: Int,
        principal: Money,
        interest: Money,
        policy: LoanFuturePolicy,
    ) {
        val tables = SnapshotImporter.canonicalTables(database)
        val plan = requireLoanRevision(tables, planId, expectedRevision)
        val terms =
            tables
                .getJSONArray("loan_terms")
                .objects()
                .filter {
                    it.optString("planId") == planId.toString() && it.optString("state") != "SUPERSEDED" && it.isNull("deletedAt")
                }.sortedBy { it.optInt("termIndex") }
        val schedule = terms.map(::loanTerm)
        require(terms.none { it.optInt("termIndex") >= termIndex && it.optString("state") in setOf("PAID", "POSTED") }) {
            "Paid or posted terms cannot be recalculated"
        }
        LoanCalculator.adjustTerm(
            schedule,
            termIndex,
            principal,
            interest,
            policy,
            plan.getLong("annualRateNumerator"),
            plan.getLong("annualRateDenominator"),
            expectedRevision,
        )
        val payload =
            JSONObject()
                .put(
                    "termIndex",
                    termIndex,
                ).put("principal", money(principal))
                .put("interest", money(interest))
                .put("futurePolicy", policy.name)
        enqueue(
            deviceId,
            "loan",
            planId,
            "loan.adjustTerm",
            payload,
            JSONObject().put("loan:$planId", expectedRevision),
            rejectPendingForAggregate = true,
            createPrediction = false,
        )
    }

    fun postLoanPayment(
        deviceId: UUID,
        planId: UUID,
        expectedRevision: Long,
        termIndex: Int,
        accountId: UUID,
        occurredAtMillis: Long,
        amount: Money,
        opId: UUID,
    ) {
        require(opId != UUID(0, 0))
        val tables = SnapshotImporter.canonicalTables(database)
        val plan = requireLoanRevision(tables, planId, expectedRevision)
        val term =
            tables.getJSONArray("loan_terms").objects().singleOrNull {
                it.optString(
                    "planId",
                ) == planId.toString() && it.optInt("termIndex") == termIndex && it.optString("state") != "SUPERSEDED" &&
                    it.isNull("deletedAt")
            } ?: error("Loan term unavailable")
        val principal = term.money("principal")
        val scheduledInterest = term.money("interest")
        require(
            term.optString("state") == "DUE" &&
                tables.getJSONArray("loan_terms").objects().none {
                    it.optString("planId") == planId.toString() && it.optInt("termIndex") < termIndex &&
                        it.isNull("deletedAt") && it.optString("state") !in setOf("PAID", "POSTED", "SUPERSEDED")
                } && amount.currency == principal.currency && amount.checkedSubtract(principal).unscaled >= 0 &&
                (amount.unscaled > 0 || (principal.unscaled == 0L && scheduledInterest.unscaled == 0L)),
        ) { "Loan payment is invalid or already posted" }
        require(occurredAtMillis in 0..System.currentTimeMillis()) { "Loan payment timestamp is invalid" }
        val cash = find(tables, "accounts", accountId)
        require(
            cash.optString("type") == "ASSET" && cash.optString("currency") == amount.currency && cash.canonicalBoolean("selectable") &&
                cash.isActive(),
        ) { "Loan payment requires a matching selectable asset account" }
        val payload =
            JSONObject()
                .put("termIndex", termIndex)
                .put("actualAccount", accountId.toString())
                .put("occurredAt", loanTimestamp(occurredAtMillis))
                .put("amount", money(amount))
        enqueue(
            deviceId,
            "loan",
            planId,
            "loan.postPayment",
            payload,
            JSONObject().put("loan:$planId", expectedRevision).put("account:$accountId", cash.getLong("serverRevision")),
            rejectPendingForAggregate = true,
            createPrediction = false,
            opId = opId,
        )
    }

    fun deleteLoan(
        deviceId: UUID,
        planId: UUID,
        expectedRevision: Long,
    ) {
        val tables = SnapshotImporter.canonicalTables(database)
        requireLoanRevision(tables, planId, expectedRevision)
        enqueue(
            deviceId,
            "loan",
            planId,
            "loan.delete",
            JSONObject().put("strategy", "KEEP_HISTORY"),
            JSONObject().put("loan:$planId", expectedRevision),
            rejectPendingForAggregate = true,
            createPrediction = false,
        )
    }

    fun setLoanPreferences(
        deviceId: UUID,
        expectedRevision: Long,
        principalAsIncome: Boolean,
        repaymentAsExpense: Boolean,
        interestAsExpense: Boolean,
    ) {
        updateBusinessPreferences(
            deviceId,
            expectedRevision,
            JSONObject()
                .put("loanPrincipalAsIncome", principalAsIncome)
                .put("loanRepaymentAsExpense", repaymentAsExpense)
                .put("loanInterestAsExpense", interestAsExpense),
        )
    }

    fun setRefundPreferences(
        deviceId: UUID,
        expectedRevision: Long,
        excessAsIncome: Boolean,
        showInRecordList: Boolean,
    ) {
        updateBusinessPreferences(
            deviceId,
            expectedRevision,
            JSONObject()
                .put("refundExcessAsIncome", excessAsIncome)
                .put("showRefundsInRecordList", showInRecordList),
        )
    }

    private fun updateBusinessPreferences(
        deviceId: UUID,
        expectedRevision: Long,
        changes: JSONObject,
    ) {
        val identity = database.identity().read() ?: error("Dataset identity missing")
        val owner = UUID.fromString(identity.ownerId)
        val tables = SnapshotImporter.canonicalTables(database)
        val rows = tables.getJSONArray("business_preferences").objects().filter { it.optString("id") == owner.toString() }
        require(rows.size <= 1) { "Canonical business preferences are ambiguous" }
        val row = rows.singleOrNull()
        val actualRevision = row?.getLong("serverRevision") ?: 0L
        require(row == null || row.isActive()) { "Canonical business preferences are deleted" }
        require(actualRevision == expectedRevision) { "Loan preferences changed; sync before editing" }
        require(
            dao.intentsInOrder(identity.ownerId, identity.epochId).none {
                it.aggregateType == "preferences" && it.aggregateId == owner.toString() && it.commandType == "preferences.update" &&
                    it.state in setOf("QUEUED", "AMBIGUOUS", "ACKED")
            },
        ) { "Loan preference update is awaiting canonical confirmation; retry the original operation." }
        enqueue(
            deviceId,
            "preferences",
            owner,
            "preferences.update",
            JSONObject().put("changes", changes),
            JSONObject().put("preferences:$owner", actualRevision),
            rejectPendingForAggregate = true,
            createPrediction = false,
        )
    }

    private fun requireLoanRevision(
        tables: JSONObject,
        planId: UUID,
        expectedRevision: Long,
    ): JSONObject {
        require(expectedRevision > 0)
        val plan = find(tables, "loan_plans", planId)
        require(plan.getLong("serverRevision") == expectedRevision) { "Loan revision changed; sync before editing" }
        return plan
    }

    private fun loanTerm(row: JSONObject) =
        LoanTerm(
            row.optInt("termIndex"),
            row.optString("dueDate"),
            row.money("opening"),
            row.money("principal"),
            row.money("interest"),
            row.money("closing"),
            runCatching {
                dev.icos.app.domain.LoanDueState
                    .valueOf(row.optString("state"))
            }.getOrDefault(dev.icos.app.domain.LoanDueState.DUE),
        )

    private fun loanTimestamp(value: Long) =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT)
            .apply {
                timeZone =
                    TimeZone.getTimeZone("UTC")
            }.format(Date(value))

    private fun requireCategory(
        tables: JSONObject,
        ledgerId: UUID,
        id: UUID,
        direction: String,
    ) {
        val row = find(tables, "categories", id)
        require(row.getString("ledgerId") == ledgerId.toString() && row.getString("direction") == direction) {
            "Choose a canonical category from the active ledger"
        }
    }

    private fun purposeCategory(
        tables: JSONObject,
        ledgerId: UUID,
        purpose: String,
    ): UUID {
        val rows = tables.getJSONArray("categories")
        val found =
            (0 until rows.length()).map { rows.getJSONObject(it) }.filter {
                it.optString("ledgerId") == ledgerId.toString() && it.optString("purpose") == purpose && it.isActive()
            }
        require(found.size == 1) { "Canonical $purpose category is unavailable or ambiguous" }
        val category = found.single()
        val direction = if (purpose == "TRANSFER_FEE") "EXPENSE" else "INCOME"
        require(category.optString("direction") == direction) { "Canonical $purpose category has an invalid direction" }
        return category.uuid("id")
    }

    private fun mergeVersions(
        target: JSONObject,
        additions: JSONObject,
    ) {
        additions.keys().forEach { key -> target.put(key, additions.getLong(key)) }
    }

    private fun recordDeletionVersions(
        tables: JSONObject,
        target: JSONObject,
        expectedRevision: Long,
    ): JSONObject {
        val versions = JSONObject()
        val pending = mutableListOf(target)
        val pendingIds = hashSetOf<String>()
        val seen = hashSetOf<String>()
        while (pending.isNotEmpty()) {
            val record = pending.removeAt(0)
            val recordId = record.getString("id")
            require(seen.add(recordId)) { "Canonical record dependency cycle" }
            require(
                record.optString("status") == "POSTED" && record.optString("claimState", "NONE") == "NONE" &&
                    record.isNull("templateId") && (record.optJSONArray("attachmentIds")?.length() ?: 0) == 0 &&
                    record.stringOrNull("importBatchId") == null && record.stringOrNull("externalId") == null,
            ) {
                "Record has an unsupported canonical relationship"
            }
            versions.put("record:$recordId", if (recordId == target.getString("id")) expectedRevision else record.getLong("serverRevision"))
            val ledger = UUID.fromString(record.getString("ledgerId"))
            versions.put("ledger:$ledger", revision(tables, "ledgers", ledger))
            record.stringOrNull("categoryId")?.let { addCategoryVersions(versions, tables, UUID.fromString(it)) }
            val recordAllocations =
                tables.getJSONArray("record_allocations").objects().filter {
                    it.optString("recordId") == recordId &&
                        it.isNull("deletedAt")
                }
            recordAllocations.mapNotNull { it.stringOrNull("fxSnapshotId") }.distinct().forEach { rawId ->
                val snapshotId = UUID.fromString(rawId)
                val snapshot = find(tables, "fx_snapshots", snapshotId)
                versions.put("fx_snapshot:$snapshotId", snapshot.getLong("serverRevision"))
            }
            val accountIds = recordAllocations.map { UUID.fromString(it.getString("accountId")) }.toSet()
            accountIds.forEach { versions.put("account:$it", revision(tables, "accounts", it)) }
            tables
                .getJSONArray("record_tags")
                .objects()
                .filter { it.optString("recordId") == recordId && it.isActive() }
                .forEach { versions.put("tag:${it.getString("tagId")}", revision(tables, "tags", UUID.fromString(it.getString("tagId")))) }
            val eventId = record.getString("eventId")
            val feeCategory = record.stringOrNull("feeCategoryId")?.let(UUID::fromString)
            val bonusCategory = record.stringOrNull("bonusCategoryId")?.let(UUID::fromString)
            if (record.optString("direction") == "TRANSFER") {
                feeCategory?.let { addCategoryVersions(versions, tables, it) }
                bonusCategory?.let { addCategoryVersions(versions, tables, it) }
            }
            val eventRelations =
                tables.getJSONArray("event_relations").objects().filter {
                    it.optString("parentEventId") == eventId && it.isNull("deletedAt")
                }
            require(eventRelations.all { it.optString("kind") in setOf("FEE", "DISCOUNT") }) {
                "Record has an unsupported canonical relationship"
            }
            val children =
                tables.getJSONArray("records").objects().filter {
                    it.optString("parentRecordId") == recordId && it.isNull("deletedAt")
                }
            if (record.stringOrNull("parentRecordId") != null) {
                require(children.isEmpty() && eventRelations.isEmpty()) { "Record has an unsupported canonical relationship" }
                val parent = find(tables, "records", UUID.fromString(record.getString("parentRecordId")))
                val parentEventId = parent.getString("eventId")
                require(
                    !tables.getJSONArray("event_relations").objects().any {
                        it.isActive() && it.optString("childEventId") == eventId &&
                            !(it.optString("parentEventId") == parentEventId && it.optString("kind") in setOf("FEE", "DISCOUNT"))
                    },
                ) { "Record has an unsupported canonical relationship" }
            }
            require(children.size == eventRelations.size) { "Canonical record child relation is incomplete" }
            children.forEach { child ->
                require(child.optString("eventId") in eventRelations.map { it.optString("childEventId") }) {
                    "Canonical record child relation is incomplete"
                }
                if (child.getString("id") !in seen && child.getString("id") !in pendingIds) {
                    pending += child
                    pendingIds += child.getString("id")
                }
            }
            require(
                !tables.getJSONArray("event_relations").objects().any {
                    it.optString("childEventId") == eventId && it.isActive() && it.optString("kind") !in setOf("FEE", "DISCOUNT")
                },
            ) { "Record has an unsupported canonical relationship" }
        }
        return versions
    }

    private fun JSONArray.objects() = List(length()) { getJSONObject(it) }

    private fun requireWritableCanonicalRecord(
        tables: JSONObject,
        id: UUID,
        expectedRevision: Long,
    ) {
        val record = find(tables, "records", id)
        require(record.getLong("serverRevision") == expectedRevision) { "Record revision changed; sync before editing" }
        require(
            record.optString("status") == "POSTED" && record.optString("claimState", "NONE") == "NONE" && record.isNull("parentRecordId") &&
                record.isNull("templateId") && (record.optJSONArray("attachmentIds")?.length() ?: 0) == 0 &&
                record.stringOrNull("importBatchId") == null && record.stringOrNull("externalId") == null,
        ) {
            "Record has an unsupported canonical relationship"
        }
        val eventId = record.getString("eventId")
        val children = tables.getJSONArray("records").objects().filter { it.optString("parentRecordId") == id.toString() && it.isActive() }
        val relations =
            tables
                .getJSONArray(
                    "event_relations",
                ).objects()
                .filter { it.optString("parentEventId") == eventId && it.isActive() }
        require(
            children.size == relations.size &&
                relations.all { relation ->
                    relation.optString("kind") in setOf("FEE", "DISCOUNT") &&
                        children.any { it.optString("eventId") == relation.optString("childEventId") }
                },
        ) { "Record has an unsupported canonical relationship" }
        require(
            !tables.getJSONArray("event_relations").objects().any {
                it.optString("childEventId") == eventId && it.isActive() && it.optString("kind") !in setOf("FEE", "DISCOUNT")
            },
        ) { "Record has an unsupported canonical relationship" }

        fun linked(
            table: String,
            field: String,
        ) = tables.optJSONArray(table)?.objects().orEmpty().any {
            it.optString(field) == id.toString() && it.isActive() && it.optString("status") !in setOf("CANCELLED", "REVERSED", "DELETED")
        }
        require(
            !linked("refunds", "originRecordId") && !linked("saving_entries", "linkedRecordId") &&
                !linked("recurrence_occurrences", "recordId") && !linked("installment_plans", "sourceRecordId") &&
                !linked("loan_events", "recordId") &&
                !tables.getJSONArray("financial_events").objects().any { it.optString("reversalOf") == eventId } &&
                tables
                    .optJSONArray("credit_payment_allocations")
                    ?.objects()
                    .orEmpty()
                    .none { it.optString("paymentEventId") == eventId },
        ) {
            "Record has an unsupported canonical relationship"
        }
    }

    fun createSaving(
        deviceId: UUID,
        id: UUID,
        input: dev.icos.app.domain.SavingPlanInput,
    ) {
        dev.icos.app.domain
            .validateSavingPlanDatePair(input.startDate, input.endDate)
        dev.icos.app.domain
            .validateSavingPlan(input)
        val plan = savingPlanJson(input, state = "ACTIVE")
        enqueue(deviceId, "saving", id, "saving.create", JSONObject().put("plan", plan), JSONObject().put("saving:$id", 0))
    }

    fun updateSaving(
        deviceId: UUID,
        id: UUID,
        expectedRevision: Long,
        patch: dev.icos.app.domain.SavingPlanPatch,
    ) {
        require(expectedRevision > 0) { "Saving plan update requires a server-confirmed revision" }
        val tables = SnapshotImporter.canonicalTables(database)
        val row = find(tables, "saving_plans", id)
        require(row.getLong("serverRevision") == expectedRevision) { "Saving plan revision changed; sync before editing" }
        val changes = JSONObject()
        when (val value = patch.name) {
            dev.icos.app.domain.SavingPatchValue.Unchanged -> {
                Unit
            }

            is dev.icos.app.domain.SavingPatchValue.Set -> {
                changes.put(
                    "name",
                    requireNotNull(value.value) { "Saving plan name cannot be null" }.also {
                        require(it.isNotBlank() && it.length <= 100 && '\u0000' !in it)
                    },
                )
            }
        }
        when (val value = patch.iconRef) {
            dev.icos.app.domain.SavingPatchValue.Unchanged -> {
                Unit
            }

            is dev.icos.app.domain.SavingPatchValue.Set -> {
                changes.put(
                    "iconRef",
                    value.value?.also { require(it.length <= 256) } ?: JSONObject.NULL,
                )
            }
        }
        when (val value = patch.target) {
            dev.icos.app.domain.SavingPatchValue.Unchanged -> {
                Unit
            }

            is dev.icos.app.domain.SavingPatchValue.Set -> {
                val target = requireNotNull(value.value) { "Saving target cannot be null" }
                require(target.unscaled > 0 && target.currency == row.getString("currency"))
                changes.put("target", money(target))
            }
        }
        when (val value = patch.endDate) {
            dev.icos.app.domain.SavingPatchValue.Unchanged -> {
                Unit
            }

            is dev.icos.app.domain.SavingPatchValue.Set -> {
                value.value?.let {
                    dev.icos.app.domain
                        .validateSavingDate(it)
                }
                val startDate =
                    when {
                        !row.isNull("start") -> row.getString("start")
                        !row.isNull("startDate") -> row.getString("startDate")
                        else -> null
                    }
                dev.icos.app.domain
                    .validateSavingPlanDatePair(startDate, value.value)
                changes.put("end", value.value ?: JSONObject.NULL)
            }
        }
        when (val value = patch.allowManualEntries) {
            dev.icos.app.domain.SavingPatchValue.Unchanged -> {
                Unit
            }

            is dev.icos.app.domain.SavingPatchValue.Set -> {
                changes.put(
                    "allowManualEntries",
                    requireNotNull(value.value) {
                        "Manual entry setting cannot be null"
                    },
                )
            }
        }
        require(changes.length() > 0) { "Saving plan update has no changes" }
        enqueue(
            deviceId,
            "saving",
            id,
            "saving.update",
            JSONObject().put("changes", changes),
            JSONObject().put("saving:$id", expectedRevision),
        )
    }

    fun addSavingEntry(
        deviceId: UUID,
        planId: UUID,
        planRevision: Long,
        ledgerId: UUID,
        entry: dev.icos.app.domain.SavingEntryInput,
    ) {
        require(planRevision > 0 && entry.amount.unscaled > 0) { "Saving entry requires a positive amount and confirmed plan" }
        val tables = SnapshotImporter.canonicalTables(database)
        val plan = find(tables, "saving_plans", planId)
        require(plan.getLong("serverRevision") == planRevision && plan.getString("state") == "ACTIVE") {
            "Saving plan revision changed or plan is archived"
        }
        require(plan.getString("currency") == entry.amount.currency) { "Saving entry currency does not match plan" }
        val remark = normalizeNewSavingEntryRemark(entry.remark)
        val card = entry.cardIndex
        if (entry.kind == dev.icos.app.domain.SavingEntryKind.WITHDRAWAL || plan.getString("kind") == "FLEXIBLE") require(card == null)
        if (entry.kind == dev.icos.app.domain.SavingEntryKind.DEPOSIT && card == null && plan.getString("kind") != "FLEXIBLE") {
            require(plan.canonicalBoolean("allowManualEntries")) { "Manual saving entries are disabled" }
        }
        card?.let { index ->
            require(
                index > 0 &&
                    tables.getJSONArray("saving_entries").objects().none {
                        it.optString("planId") == planId.toString() && it.optInt("cardIndex", -1) == index && it.isNull("cancelledAt") &&
                            it.isNull("deletedAt")
                    },
            ) { "Saving card index is invalid or already used" }
            val expected =
                when (plan.getString("kind")) {
                    "WEEK_52", "DAY_365" -> {
                        val maximum = if (plan.getString("kind") == "WEEK_52") 52 else 365
                        require(index <= maximum)
                        val first =
                            plan
                                .getJSONObject(
                                    "firstAmount",
                                ).let { Money(it.getString("unscaled").toLong(), it.getInt("scale"), it.getString("currency")) }
                        val increment =
                            plan.getJSONObject("increment").let {
                                Money(it.getString("unscaled").toLong(), it.getInt("scale"), it.getString("currency"))
                            }
                        val scale = maxOf(first.scale, increment.scale)
                        val units =
                            java.math.BigInteger
                                .valueOf(first.unscaled)
                                .multiply(
                                    java.math.BigInteger.TEN
                                        .pow(scale - first.scale),
                                ) +
                                java.math.BigInteger
                                    .valueOf(increment.unscaled)
                                    .multiply(
                                        java.math.BigInteger.TEN
                                            .pow(scale - increment.scale),
                                    ) *
                                java.math.BigInteger.valueOf((index - 1).toLong())
                        dev.icos.app.domain
                            .wideSavingMoney(units, scale, first.currency)
                    }

                    "FIXED" -> {
                        val target =
                            plan
                                .getJSONObject(
                                    "target",
                                ).let { Money(it.getString("unscaled").toLong(), it.getInt("scale"), it.getString("currency")) }
                        val first =
                            plan
                                .getJSONObject(
                                    "firstAmount",
                                ).let { Money(it.getString("unscaled").toLong(), it.getInt("scale"), it.getString("currency")) }
                        val scale = maxOf(target.scale, first.scale)
                        val targetUnits =
                            java.math.BigInteger.valueOf(target.unscaled).multiply(
                                java.math.BigInteger.TEN
                                    .pow(scale - target.scale),
                            )
                        val firstUnits =
                            java.math.BigInteger
                                .valueOf(
                                    first.unscaled,
                                ).multiply(
                                    java.math.BigInteger.TEN
                                        .pow(scale - first.scale),
                                )
                        val count = (targetUnits + firstUnits - java.math.BigInteger.ONE).divide(firstUnits).intValueExact()
                        require(index <= count)
                        if (index == count) {
                            val remainder = targetUnits - firstUnits * java.math.BigInteger.valueOf((count - 1).toLong())
                            dev.icos.app.domain
                                .wideSavingMoney(remainder, scale, target.currency)
                        } else {
                            first
                        }
                    }

                    else -> {
                        entry.amount
                    }
                }
            require(expected.sameValue(entry.amount)) { "Saving entry amount does not match its card" }
        }
        val occurredAt =
            java.text
                .SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT)
                .apply {
                    timeZone =
                        TimeZone.getTimeZone("UTC")
                }.format(Date(entry.occurredAtMillis))
        val entryId = UUID.randomUUID()
        val payload =
            JSONObject()
                .put("entryId", entryId.toString())
                .put("kind", entry.kind.name)
                .put("amount", money(entry.amount))
                .put("occurredAt", occurredAt)
                .put("cardIndex", entry.cardIndex ?: JSONObject.NULL)
                .put("remark", remark ?: "")
        val versions = JSONObject().put("saving:$planId", planRevision).put("saving_entry:$entryId", 0)
        val link = entry.linkedTransfer
        if (link == null) {
            payload.put("linkedTransfer", JSONObject.NULL)
        } else {
            require(entry.kind in setOf(dev.icos.app.domain.SavingEntryKind.DEPOSIT, dev.icos.app.domain.SavingEntryKind.WITHDRAWAL))
            require(link.input.fee == null && link.input.bonus == null) { "Saving linked transfer cannot include fee or bonus postings" }
            require(link.timezone.isNotBlank() && runCatching { java.time.ZoneId.of(link.timezone) }.isSuccess)
            val recordId = link.recordId
            val occurred =
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT)
                    .apply {
                        timeZone =
                            TimeZone.getTimeZone(link.timezone)
                    }.format(Date(entry.occurredAtMillis))
            val localDate =
                SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
                    .apply {
                        timeZone = TimeZone.getTimeZone(link.timezone)
                    }.format(Date(entry.occurredAtMillis))
            val record =
                JSONObject()
                    .put("ledgerId", ledgerId.toString())
                    .put("categoryId", JSONObject.NULL)
                    .put("occurredAt", occurred)
                    .put("timezone", link.timezone)
                    .put("localDate", localDate)
                    .put(
                        "remark",
                        remark ?: "",
                    ).put("tagIds", JSONArray(link.tagIds.map(UUID::toString)))
                    .put("attachmentIds", JSONArray())
                    .put("input", input(link.input))
                    .put("feeCategoryId", JSONObject.NULL)
                    .put("bonusCategoryId", JSONObject.NULL)
                    .put("templateId", JSONObject.NULL)
            payload.put("linkedTransfer", JSONObject().put("recordId", recordId.toString()).put("record", record))
            require(link.input.from.amount.currency == entry.amount.currency && link.input.to.amount.currency == entry.amount.currency)
            val linkedAmount =
                if (entry.kind ==
                    dev.icos.app.domain.SavingEntryKind.DEPOSIT
                ) {
                    link.input.to.amount
                } else {
                    link.input.from.amount
                }
            require(
                linkedAmount.sameValue(entry.amount) &&
                    link.input
                        .calculate()
                        .postings
                        .isNotEmpty(),
            ) {
                "Linked transfer does not match saving entry"
            }
            link.tagIds.distinct().forEach { tagId -> versions.put("tag:$tagId", revision(tables, "tags", tagId)) }
            versions.put("record:$recordId", 0)
            val linkedLedgerId = UUID.fromString(record.getString("ledgerId"))
            versions.put("ledger:$linkedLedgerId", revision(tables, "ledgers", linkedLedgerId))
            (
                listOf(link.input.from.accountId, link.input.to.accountId) +
                    listOfNotNull(link.input.fee?.accountId, link.input.bonus?.accountId)
            ).distinct().forEach { accountId ->
                val account = find(tables, "accounts", accountId)
                require(account.canonicalBoolean("selectable") && account.getString("currency") == entry.amount.currency) {
                    "Linked transfer account is unavailable or its currency does not match saving plan"
                }
                versions.put("account:$accountId", account.getLong("serverRevision"))
            }
        }
        enqueue(deviceId, "saving", planId, "saving.entry", payload, versions)
    }

    fun cancelSavingEntry(
        deviceId: UUID,
        planId: UUID,
        planRevision: Long,
        entryId: UUID,
        entryRevision: Long,
    ) {
        require(planRevision > 0 && entryRevision > 0)
        val tables = SnapshotImporter.canonicalTables(database)
        val plan = find(tables, "saving_plans", planId)
        val entry = find(tables, "saving_entries", entryId)
        require(
            plan.getLong("serverRevision") == planRevision && entry.getLong("serverRevision") == entryRevision &&
                entry.getString("planId") == planId.toString() &&
                entry.isNull("cancelledAt"),
        ) {
            "Saving plan or entry revision changed; sync before editing"
        }
        val versions = JSONObject().put("saving:$planId", planRevision).put("saving_entry:$entryId", entryRevision)
        entry.stringOrNull("linkedRecordId")?.let { raw ->
            val recordId = UUID.fromString(raw)
            val record = find(tables, "records", recordId)
            val recordRevision = record.getLong("serverRevision")
            val dependencies = recordDeletionVersions(tables, record, recordRevision)
            require(
                dependencies.keys().asSequence().all { key ->
                    key.startsWith("record:") || key.startsWith("ledger:") ||
                        key.startsWith("category:") ||
                        key.startsWith("account:") ||
                        key.startsWith("tag:") ||
                        key.startsWith("fx_snapshot:")
                },
            )
            mergeVersions(versions, dependencies)
        }
        enqueue(deviceId, "saving", planId, "saving.cancelEntry", JSONObject().put("entryId", entryId.toString()), versions)
    }

    fun archiveSaving(
        deviceId: UUID,
        id: UUID,
        expectedRevision: Long,
        archived: Boolean,
    ) {
        require(expectedRevision > 0)
        val tables = SnapshotImporter.canonicalTables(database)
        require(find(tables, "saving_plans", id).getLong("serverRevision") == expectedRevision) {
            "Saving plan revision changed; sync before editing"
        }
        enqueue(
            deviceId,
            "saving",
            id,
            "saving.archive",
            JSONObject().put("archived", archived),
            JSONObject().put("saving:$id", expectedRevision),
        )
    }

    fun deleteSaving(
        deviceId: UUID,
        id: UUID,
        expectedRevision: Long,
        strategy: dev.icos.app.domain.SavingTransferStrategy,
    ) {
        require(expectedRevision > 0)
        val tables = SnapshotImporter.canonicalTables(database)
        val plan = find(tables, "saving_plans", id)
        require(plan.getLong("serverRevision") == expectedRevision) { "Saving plan revision changed; sync before editing" }
        val versions = JSONObject().put("saving:$id", expectedRevision)
        if (strategy == dev.icos.app.domain.SavingTransferStrategy.DELETE_TRANSFERS) {
            tables
                .getJSONArray("saving_entries")
                .objects()
                .filter { it.optString("planId") == id.toString() && it.isNull("cancelledAt") }
                .mapNotNull { it.stringOrNull("linkedRecordId")?.let(UUID::fromString) }
                .distinct()
                .forEach { recordId ->
                    val record = find(tables, "records", recordId)
                    val recordRevision = record.getLong("serverRevision")
                    val dependencies = recordDeletionVersions(tables, record, recordRevision)
                    require(
                        dependencies.keys().asSequence().all { key ->
                            key.startsWith("record:") || key.startsWith("ledger:") ||
                                key.startsWith("category:") ||
                                key.startsWith("account:") ||
                                key.startsWith("tag:") ||
                                key.startsWith("fx_snapshot:")
                        },
                    )
                    mergeVersions(versions, dependencies)
                }
        }
        enqueue(deviceId, "saving", id, "saving.delete", JSONObject().put("transferStrategy", strategy.name), versions)
    }

    private fun savingPlanJson(
        input: dev.icos.app.domain.SavingPlanInput,
        state: String,
    ) = JSONObject()
        .put("kind", input.kind.name)
        .put("name", input.name.trim())
        .put("iconRef", input.iconRef ?: JSONObject.NULL)
        .put("currency", input.target.currency)
        .put("target", money(input.target))
        .put("initialSaved", money(input.initialSaved))
        .put("firstAmount", input.firstAmount?.let(::money) ?: JSONObject.NULL)
        .put(
            "increment",
            input.increment?.let(::money) ?: JSONObject.NULL,
        ).put("interval", input.interval?.let { JSONObject().put("unit", it.unit.name).put("step", it.step) } ?: JSONObject.NULL)
        .put("start", input.startDate ?: JSONObject.NULL)
        .put("end", input.endDate ?: JSONObject.NULL)
        .put("state", state)
        .put("allowManualEntries", input.allowManualEntries)

    private fun activeDefaultLedger(tables: JSONObject): UUID =
        tables
            .getJSONArray("ledgers")
            .objects()
            .firstOrNull { it.canonicalBoolean("isDefault") && it.isActive() }
            ?.uuid("id")
            ?: throw IllegalStateException("Canonical default ledger unavailable")

    fun createRecurrence(
        deviceId: UUID,
        id: UUID,
        templateId: UUID,
        startLocalDateTime: String,
        timezone: String,
        frequency: String,
        interval: Int,
        weekDays: List<Int> = emptyList(),
        monthDays: List<Int> = emptyList(),
        endAt: String? = null,
        maxCount: Long? = null,
        state: String = "RUNNING",
    ) {
        require(id != UUID(0, 0) && templateId != UUID(0, 0))
        require(state in setOf("RUNNING", "PAUSED"))
        val tables = SnapshotImporter.canonicalTables(database)
        val template = find(tables, "record_templates", templateId)
        require(template.getLong("serverRevision") > 0 && template.isActive()) { "Select an active server-confirmed template" }
        validateRecurrenceRule(frequency, interval, weekDays, monthDays, endAt, maxCount)
        val plan =
            JSONObject()
                .put("templateId", templateId.toString())
                .put("startLocalDateTime", startLocalDateTime)
                .put("timezone", timezone)
                .put("frequency", frequency)
                .put("interval", interval)
                .put("weekDays", JSONArray(weekDays))
                .put("monthDays", JSONArray(monthDays))
                .put("endAt", endAt ?: JSONObject.NULL)
                .put("maxCount", maxCount ?: JSONObject.NULL)
                .put("state", state)
        enqueue(
            deviceId,
            "recurrence",
            id,
            "recurrence.create",
            JSONObject().put("plan", plan),
            JSONObject().put("recurrence:$id", 0).put("record_template:$templateId", template.getLong("serverRevision")),
        )
    }

    fun updateRecurrence(
        deviceId: UUID,
        plan: CanonicalAccountRepository.RecurrenceSummary,
        effectiveFromSlot: Long,
        templateId: UUID,
        templateRevisionConfirmed: Boolean,
        frequency: String,
        interval: Int,
        weekDays: List<Int>,
        monthDays: List<Int>,
        endAt: String?,
        maxCount: Long?,
    ) {
        require(plan.serverRevision > 0 && effectiveFromSlot >= plan.nextDueSlot && effectiveFromSlot >= 0)
        require(plan.state in setOf("RUNNING", "PAUSED"))
        val tables = SnapshotImporter.canonicalTables(database)
        val current = find(tables, "recurrence_plans", plan.id)
        require(current.getLong("serverRevision") == plan.serverRevision)
        require(effectiveFromSlot == current.getLong("nextDueSlot")) { "Rule edits must begin at the server's next due slot" }
        val template = find(tables, "record_templates", templateId)
        require(template.getLong("serverRevision") > 0 && template.isActive())
        if (templateId == plan.templateId && plan.templateRevision != template.getLong("serverRevision")) {
            require(templateRevisionConfirmed) { "Template revision changed; explicit confirmation is required to use its new revision" }
        }
        validateRecurrenceRule(frequency, interval, weekDays, monthDays, endAt, maxCount)
        val changes =
            JSONObject()
                .put("templateId", templateId.toString())
                .put("frequency", frequency)
                .put("interval", interval)
                .put("weekDays", JSONArray(weekDays))
                .put("monthDays", JSONArray(monthDays))
                .put("endAt", endAt ?: JSONObject.NULL)
                .put("maxCount", maxCount ?: JSONObject.NULL)
        enqueue(
            deviceId,
            "recurrence",
            plan.id,
            "recurrence.update",
            JSONObject().put("effectiveFromSlot", effectiveFromSlot).put("changes", changes),
            JSONObject()
                .put(
                    "recurrence:${plan.id}",
                    plan.serverRevision,
                ).put("record_template:$templateId", template.getLong("serverRevision")),
        )
    }

    fun setRecurrenceState(
        deviceId: UUID,
        plan: CanonicalAccountRepository.RecurrenceSummary,
        state: String,
    ) {
        require(
            plan.serverRevision > 0 &&
                (plan.state to state) in setOf("RUNNING" to "PAUSED", "RUNNING" to "ENDED", "PAUSED" to "RUNNING", "PAUSED" to "ENDED"),
        )
        val tables = SnapshotImporter.canonicalTables(database)
        require(find(tables, "recurrence_plans", plan.id).getLong("serverRevision") == plan.serverRevision)
        enqueue(
            deviceId,
            "recurrence",
            plan.id,
            "recurrence.setState",
            JSONObject().put("state", state),
            JSONObject().put("recurrence:${plan.id}", plan.serverRevision),
        )
    }

    fun deleteRecurrence(
        deviceId: UUID,
        plan: CanonicalAccountRepository.RecurrenceSummary,
    ) {
        require(plan.serverRevision > 0 && plan.state !in setOf("DELETED", "ENDED"))
        val tables = SnapshotImporter.canonicalTables(database)
        require(find(tables, "recurrence_plans", plan.id).getLong("serverRevision") == plan.serverRevision)
        enqueue(
            deviceId,
            "recurrence",
            plan.id,
            "recurrence.delete",
            JSONObject().put("retainGenerated", true),
            JSONObject().put("recurrence:${plan.id}", plan.serverRevision),
        )
    }

    private fun validateRecurrenceRule(
        frequency: String,
        interval: Int,
        weekDays: List<Int>,
        monthDays: List<Int>,
        endAt: String?,
        maxCount: Long?,
    ) {
        require(frequency in setOf("DAILY", "WEEKLY", "MONTHLY", "YEARLY") && interval > 0)
        require(weekDays.distinct().size == weekDays.size && weekDays.all { it in 1..7 })
        require(monthDays.distinct().size == monthDays.size && monthDays.all { it in 1..31 })
        when (frequency) {
            "DAILY" -> require(monthDays.isEmpty())
            "WEEKLY" -> require(weekDays.isNotEmpty() && monthDays.isEmpty())
            "MONTHLY", "YEARLY" -> require(weekDays.isEmpty())
        }
        require(maxCount == null || maxCount > 0)
        endAt?.let { java.time.OffsetDateTime.parse(it) }
    }

    fun setAccountArchived(
        deviceId: UUID,
        id: UUID,
        expectedRevision: Long,
        archived: Boolean,
    ) {
        require(expectedRevision > 0) { "账户需要先获得服务器确认" }
        database.runInTransaction {
            val tables = SnapshotImporter.canonicalTables(database)
            val account =
                tables.getJSONArray("accounts").objects().singleOrNull {
                    it.optString("id") == id.toString() && it.isNull("deletedAt")
                } ?: error("账户已不可用")
            require(account.getLong("serverRevision") == expectedRevision && account.isNull("archivedAt") == archived) {
                "账户版本或归档状态已变化，请刷新后重新操作。"
            }
            requireNoPendingAccountMutations(setOf(id))
            val payload =
                JSONObject()
                    .put("archived", archived)
                    .put("includeInAssets", !archived && account.canonicalBoolean("includeInAssets"))
            enqueueCanonicalOnly(
                deviceId,
                "account",
                id,
                "metadata.archive",
                payload,
                JSONObject().put("account:$id", expectedRevision),
            )
        }
    }

    fun setAccountOptions(
        deviceId: UUID,
        id: UUID,
        expectedRevision: Long,
        includeInAssets: Boolean? = null,
        selectable: Boolean? = null,
    ) {
        require(expectedRevision > 0 && (includeInAssets == null) != (selectable == null)) { "每次只修改一个账户开关" }
        database.runInTransaction {
            val tables = SnapshotImporter.canonicalTables(database)
            val account =
                tables.getJSONArray("accounts").objects().singleOrNull {
                    it.optString("id") == id.toString() && it.isNull("deletedAt")
                } ?: error("账户已不可用")
            require(account.getLong("serverRevision") == expectedRevision) { "账户版本已变化，请刷新后重试。" }
            requireNoPendingAccountMutations(setOf(id))
            val (changes, versions) = accountMetadataChanges(tables, account, includeInAssets, selectable)
            enqueueCanonicalOnly(deviceId, "account", id, "metadata.update", JSONObject().put("changes", changes), versions)
        }
    }

    fun updateAccountDetails(
        deviceId: UUID,
        id: UUID,
        expectedRevision: Long,
        name: String,
        groupId: UUID,
        expectedGroupRevision: Long,
    ) {
        require(expectedRevision > 0 && expectedGroupRevision > 0) { "账户和分组需要先获得服务器确认" }
        database.runInTransaction {
            val tables = SnapshotImporter.canonicalTables(database)
            val account =
                tables.getJSONArray("accounts").objects().singleOrNull {
                    it.optString("id") == id.toString() && it.isNull("deletedAt")
                } ?: error("账户已不可用")
            require(account.getLong("serverRevision") == expectedRevision) { "账户版本已变化，请刷新后重新编辑。" }
            requireNoPendingAccountMutations(setOf(id))
            val (changes, versions) = accountMetadataChanges(tables, account, null, null, name, groupId)
            require(versions.getLong("account_group:$groupId") == expectedGroupRevision) { "账户分组已变化，请刷新后重新选择。" }
            enqueueCanonicalOnly(deviceId, "account", id, "metadata.update", JSONObject().put("changes", changes), versions)
        }
    }

    fun setAccountVisibility(
        deviceId: UUID,
        id: UUID,
        expectedRevision: Long,
        visibilityMode: String,
        ledgerRevisions: Map<UUID, Long>,
    ) {
        require(expectedRevision > 0 && ledgerRevisions.values.all { it > 0 }) { "账户和账本需要先获得服务器确认" }
        database.runInTransaction {
            val tables = SnapshotImporter.canonicalTables(database)
            val account =
                tables.getJSONArray("accounts").objects().singleOrNull {
                    it.optString("id") == id.toString() && it.isNull("deletedAt")
                } ?: error("账户已不可用")
            require(account.getLong("serverRevision") == expectedRevision) { "账户版本已变化，请刷新后重新编辑。" }
            requireNoPendingAccountMutations(setOf(id))
            val (changes, versions) =
                accountMetadataChanges(
                    tables,
                    account,
                    null,
                    null,
                    visibilityMode = visibilityMode,
                    visibleLedgerIds = ledgerRevisions.keys,
                )
            ledgerRevisions.forEach { (ledgerId, revision) ->
                require(versions.getLong("ledger:$ledgerId") == revision) { "账本版本已变化，请刷新后重新选择。" }
            }
            enqueueCanonicalOnly(deviceId, "account", id, "metadata.update", JSONObject().put("changes", changes), versions)
        }
    }

    internal fun installmentCommands() = AccountInstallmentCommands(database)

    internal fun creditPaymentCommands() = AccountCreditPaymentCommands(database)

    internal fun accountGroupCommands() = AccountGroupCommands(database)

    internal fun accountOrderCommands() = AccountOrderCommands(database)

    internal fun accountLedgerCommands() = AccountLedgerCommands(database)

    internal fun accountCategoryCommands() = AccountCategoryCommands(database)

    internal fun accountTagCommands() = AccountTagCommands(database)

    internal fun accountDeletionCommands() = AccountDeletionCommands(database)

    internal fun accountCategoryDeletionCommands() = AccountCategoryDeletionCommands(database)

    internal fun requireNoPendingAccountMutations(accountIds: Set<UUID>) {
        val identity = database.identity().read() ?: error("Dataset identity missing")
        val accountKeys = accountIds.map { "account:$it" }.toSet()
        check(
            dao.intentsInOrder(identity.ownerId, identity.epochId).none { intent ->
                if (intent.state !in setOf("QUEUED", "AMBIGUOUS", "ACKED")) {
                    false
                } else {
                    val pending = database.typedOutbox().byOpId(intent.opId) ?: error("账号操作队列不完整")
                    check(
                        pending.ownerId == identity.ownerId && pending.epochId == identity.epochId &&
                            pending.aggregateType == intent.aggregateType && pending.aggregateId == intent.aggregateId &&
                            pending.commandType == intent.commandType && pending.payloadJson == intent.payloadJson,
                    ) { "账号操作队列身份不匹配" }
                    (intent.aggregateType == "account" && "account:${intent.aggregateId}" in accountKeys) ||
                        JSONObject(pending.baseVersionsJson).keys().asSequence().any { it in accountKeys }
                }
            },
        ) { "相关账户仍有操作等待确认；请先同步原操作。" }
    }

    internal fun enqueueCanonicalOnly(
        deviceId: UUID,
        aggregate: String,
        id: UUID,
        command: String,
        payload: JSONObject,
        versions: JSONObject,
        rejectPendingForAggregate: Boolean = true,
    ) {
        enqueue(deviceId, aggregate, id, command, payload, versions, rejectPendingForAggregate = rejectPendingForAggregate)
    }

    private fun enqueue(
        deviceId: UUID,
        aggregate: String,
        id: UUID,
        command: String,
        payload: JSONObject,
        versions: JSONObject,
        rejectPendingForAggregate: Boolean = false,
        rejectPendingBudget: Boolean = false,
        rejectPendingRecovery: Boolean = false,
        createPrediction: Boolean = true,
        opId: UUID = UUID.randomUUID(),
    ) {
        database.runInTransaction {
        val identity = database.identity().read() ?: error("Dataset identity missing")
        val groupId = UUID.randomUUID()
        if (aggregate == "category" && command in setOf("metadata.create", "metadata.update") || command == "record.create") {
            typed.captureCategoryDependencies(versions)
        }
        val baseVersions = versions.toString()
        val body = payload.toString()
        val typedCommand =
            TypedCommand(
                opId = opId,
                groupId = groupId,
                ownerId = UUID.fromString(identity.ownerId),
                epochId = UUID.fromString(identity.epochId),
                deviceId = deviceId,
                aggregateType = aggregate,
                aggregateId = id,
                baseVersionsJson = baseVersions,
                commandType = command,
                payloadJson = body,
            )
            if (command.startsWith("credit.") || aggregate == "installment") {
                val accountIds =
                    versions
                        .keys()
                        .asSequence()
                        .filter { it.startsWith("account:") }
                        .map { UUID.fromString(it.removePrefix("account:")) }
                        .toSet()
                check(accountIds.isNotEmpty()) { "信用命令缺少账户版本" }
                requireNoPendingAccountMutations(accountIds)
            }
            if (aggregate == "saving") {
                check(
                    dao.intentsInOrder(identity.ownerId, identity.epochId).none {
                        it.aggregateType == "saving" && it.state in setOf("QUEUED", "AMBIGUOUS", "ACKED")
                    },
                ) { "存钱更改尚未获得服务器规范确认，请先重试同步原操作。" }
            }
            if (aggregate == "record_template") {
                check(dao.pendingTemplate(identity.ownerId, identity.epochId, id.toString()) == null) {
                    "Template mutation is already awaiting canonical confirmation"
                }
            }
            if (aggregate == "recurrence") {
                check(dao.pendingRecurrence(identity.ownerId, identity.epochId, id.toString()) == null) {
                    "This recurrence already has an operation awaiting server confirmation; retry the same sync operation first"
                }
                if (command == "recurrence.create") {
                    check(dao.pendingRecurrenceCreate(identity.ownerId, identity.epochId) == null) {
                        "A recurrence create is awaiting server confirmation; retry the original sync operation first"
                    }
                }
            }
            if (rejectPendingForAggregate) {
                val unresolved =
                    dao.intentsInOrder(identity.ownerId, identity.epochId).any {
                        it.aggregateType == aggregate && it.aggregateId == id.toString() &&
                            it.state in setOf("QUEUED", "AMBIGUOUS", "ACKED")
                    }
                check(!unresolved) { "This mutation is already awaiting canonical confirmation; retry the original operation." }
            }
            if (rejectPendingBudget) {
                check(!dao.hasPendingBudgetMutation(identity.ownerId, identity.epochId, id.toString())) {
                    "预算更改已在本机队列；请通过重试同步继续原操作。"
                }
            }
            if (rejectPendingRecovery) {
                check(
                    dao.intentsInOrder(identity.ownerId, identity.epochId).none {
                        it.commandType in RECOVERY_COMMANDS &&
                            it.state in setOf("QUEUED", "AMBIGUOUS", "ACKED")
                    },
                ) { "报销或退款更改仍待服务器确认；请先同步原操作，暂不生成新操作。" }
            }
            typed.enqueue(typedCommand)
            dao.insertIntent(
                AccountMutationIntentEntity(
                    opId.toString(),
                    identity.ownerId,
                    identity.epochId,
                    aggregate,
                    id.toString(),
                    command,
                    body,
                    "QUEUED",
                    System.currentTimeMillis(),
                ),
            )
            // Replay markers for canonical-only commands carry identity without predicting financial facts.
            if (createPrediction || aggregate in setOf("loan", "preferences") || command in RECOVERY_COMMANDS) {
                dao.insertPrediction(
                    AccountPredictionEntity(opId.toString(), identity.ownerId, identity.epochId, aggregate, id.toString(), body, true),
                )
            }
        }
    }

    internal companion object {
        private val RECOVERY_COMMANDS = setOf("claim.mark", "claim.settle", "claim.cancel", "refund.create", "refund.cancel")

        fun conflictResolutionHasLocalOrigin(
            oldIntent: AccountMutationIntentEntity?,
            oldOutbox: TypedOutboxEntity?,
            ownerId: UUID,
            epochId: UUID,
            oldOpId: UUID,
        ): Boolean {
            if (oldIntent == null && oldOutbox == null) return false
            require(oldIntent != null && oldOutbox != null) { "本机冲突 intent/outbox 不完整" }
            require(
                oldIntent.ownerId == ownerId.toString() && oldIntent.epochId == epochId.toString() && oldIntent.opId == oldOpId.toString() &&
                    oldIntent.state == "CONFLICT" && oldIntent.aggregateType != "sync_conflict" &&
                    oldIntent.commandType != "sync.conflict.resolve",
            ) { "旧冲突 intent 不匹配" }
            require(
                oldOutbox.opId == oldOpId.toString() && oldOutbox.ownerId == ownerId.toString() && oldOutbox.epochId == epochId.toString() &&
                    oldOutbox.state == "CONFLICT" && oldOutbox.aggregateType == oldIntent.aggregateType &&
                    oldOutbox.aggregateId == oldIntent.aggregateId &&
                    oldOutbox.commandType == oldIntent.commandType && oldOutbox.payloadJson == oldIntent.payloadJson,
            ) { "旧冲突 outbox 不匹配" }
            return true
        }
    }

    private fun sameVersionMap(
        left: JSONObject,
        right: JSONObject,
    ): Boolean {
        val leftKeys = left.keys().asSequence().toSet()
        val rightKeys = right.keys().asSequence().toSet()
        return leftKeys == rightKeys && leftKeys.all { key -> left.optLong(key, -1) == right.optLong(key, -2) }
    }

    private fun addCategoryVersions(
        versions: JSONObject,
        tables: JSONObject,
        id: UUID,
    ) {
        val row = find(tables, "categories", id)
        versions.put("category:$id", row.getLong("serverRevision"))
        if (!row.isNull("parentId")) {
            val parent = UUID.fromString(row.getString("parentId"))
            versions.put("category:$parent", revision(tables, "categories", parent))
        }
    }

    private fun accountsInGroup(
        tables: JSONObject,
        group: UUID,
    ): Long =
        (0 until tables.getJSONArray("accounts").length())
            .count { i ->
                val row = tables.getJSONArray("accounts").getJSONObject(i)
                row.optString("groupId") == group.toString() && row.isActive()
            }.toLong()

    private fun revision(
        tables: JSONObject,
        table: String,
        id: UUID,
    ): Long = find(tables, table, id).getLong("serverRevision")

    private fun revisionIncludingArchived(
        tables: JSONObject,
        table: String,
        id: UUID,
    ): Long =
        tables
            .getJSONArray(table)
            .objects()
            .firstOrNull { it.optString("id") == id.toString() }
            ?.getLong("serverRevision")
            ?: throw IllegalStateException("Canonical $table dependency unavailable")

    private fun find(
        tables: JSONObject,
        table: String,
        id: UUID,
    ): JSONObject =
        (0 until tables.getJSONArray(table).length())
            .map { tables.getJSONArray(table).getJSONObject(it) }
            .firstOrNull { it.optString("id") == id.toString() && it.isActive() }
            ?: throw IllegalStateException("Canonical $table reference unavailable")

    private fun JSONObject.stringOrNull(key: String): String? = if (isNull(key)) null else getString(key).takeIf(String::isNotBlank)

    private fun JSONObject.isActive() = isNull("deletedAt") && (!has("archivedAt") || isNull("archivedAt"))

    private fun JSONObject.uuid(key: String) = UUID.fromString(getString(key))

    private fun input(value: RecordInput): JSONObject =
        when (value) {
            is RecordInput.Expense -> {
                JSONObject()
                    .put("kind", "EXPENSE")
                    .put("gross", money(value.gross))
                    .put("discount", value.discount?.let(::money) ?: JSONObject.NULL)
                    .put(
                        "allocations",
                        JSONArray().also { array ->
                            val recordAmounts = value.recordCurrencyAmounts ?: value.allocations.map { it.amount }
                            value.allocations.forEachIndexed { index, allocation ->
                                val encoded = allocation(allocation.accountId, allocation.amount)
                                if (recordAmounts[index].currency != allocation.amount.currency) {
                                    encoded.put("recordCurrencyAmount", money(recordAmounts[index]))
                                }
                                array.put(encoded)
                            }
                        },
                    ).put("countInStatistics", value.countInStatistics)
                    .put("countInBudget", value.countInBudget)
            }

            is RecordInput.Income -> {
                JSONObject()
                    .put("kind", "INCOME")
                    .put("amount", money(value.amount))
                    .put(
                        "allocations",
                        JSONArray().also { array ->
                            value.allocations.forEach { array.put(allocation(it.accountId, it.amount)) }
                        },
                    ).put("countInStatistics", value.countInStatistics)
            }

            is RecordInput.Transfer -> {
                JSONObject()
                    .put("kind", "TRANSFER")
                    .put("from", allocation(value.from.accountId, value.from.amount))
                    .put("to", allocation(value.to.accountId, value.to.amount))
                    .put("fee", value.fee?.let { allocation(it.accountId, it.amount) } ?: JSONObject.NULL)
                    .put("bonus", value.bonus?.let { allocation(it.accountId, it.amount) } ?: JSONObject.NULL)
            }
        }

    private fun allocation(
        accountId: UUID,
        amount: Money,
    ) = JSONObject().put("accountId", accountId.toString()).put("amount", money(amount))

    private fun money(value: Money) =
        JSONObject().put("unscaled", value.unscaled.toString()).put("scale", value.scale).put("currency", value.currency)

    private fun JSONObject.money(key: String): Money =
        getJSONObject(key).let {
            Money(it.getString("unscaled").toLong(), it.getInt("scale"), it.getString("currency"))
        }
}
