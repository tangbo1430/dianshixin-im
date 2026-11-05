package dev.icos.app.data.local

import dev.icos.app.domain.Money
import org.json.JSONObject
import java.math.BigInteger
import java.util.UUID

/** Queues the server-supported KEEP_RECORDS deletion after a fresh canonical preflight. */
internal class AccountDeletionCommands(private val database: DatasetDatabase) {
    private val mutations = AccountMutationRepository(database)

    data class AccountDeletePreview(
        val ownerId: String,
        val epochId: String,
        val account: FinanceAccount,
        val postingCount: Int,
        val visibilityCount: Int,
        internal val accountRoot: String,
        internal val postingRows: List<String>,
        internal val visibilityRows: List<String>,
    )

    /** Captures the canonical root, exact posting balance and complete visibility membership. */
    fun preview(account: FinanceAccount): AccountDeletePreview = database.runInTransaction(
        java.util.concurrent.Callable {
            val tables = SnapshotImporter.canonicalTables(database)
            val row = activeAccount(tables, account.id)
            requireCapturedAccount(row, account)
            val postings = rows(tables, "account_postings").filter {
                it.optString("accountId") == account.id.toString() && it.isNull("deletedAt")
            }
            val canonicalBalance = requireZeroCanonicalBalance(row, postings)
            requireNoFuturePostings(tables, postings)
            requireNoUnsupportedDependencies(tables, account.id)
            val visibilityRows = rows(tables, "account_ledger_visibility").filter {
                it.optString("accountId") == account.id.toString() && it.isNull("deletedAt")
            }
            val visibility = visibilityRows.map { it.toString() }.sorted()
            val identity = database.identity().read() ?: throw FinanceValidationException("账号或账本已变化，请重新操作。")
            val capturedAccount = account.copy(
                name = row.getString("name"), currency = row.getString("currency"), balance = canonicalBalance,
                localRevision = row.getLong("serverRevision"), archivedAt = account.archivedAt,
                includeInAssets = row.canonicalBoolean("includeInAssets"), selectable = row.canonicalBoolean("selectable"),
                groupId = UUID.fromString(row.getString("groupId")), sortOrder = row.getLong("sortOrder"),
                type = row.getString("type"), visibilityMode = row.getString("visibilityMode"),
                visibleLedgerIds = visibilityRows.map { UUID.fromString(it.getString("ledgerId")) }.toSet(),
            )
            AccountDeletePreview(
                identity.ownerId, identity.epochId, capturedAccount, postings.size, visibility.size,
                row.toString(), postings.map { it.toString() }.sorted(), visibility,
            )
        },
    )

    /** Queues canonical-only metadata.delete; all history and opening postings remain on the server. */
    fun delete(deviceId: UUID, preview: AccountDeletePreview, confirmation: String) {
        if (preview.account.localRevision <= 0 || confirmation != preview.account.name) {
            throw FinanceValidationException("请输入账户名称确认删除。")
        }
        database.runInTransaction {
            requirePreviewIdentity(preview.ownerId, preview.epochId)
            val tables = SnapshotImporter.canonicalTables(database)
            val row = activeAccount(tables, preview.account.id)
            if (row.toString() != preview.accountRoot || row.getLong("serverRevision") != preview.account.localRevision ||
                row.getString("name") != confirmation) {
                throw FinanceValidationException("账户已变化，请关闭后重新确认删除。")
            }
            val postings = rows(tables, "account_postings").filter {
                it.optString("accountId") == preview.account.id.toString() && it.isNull("deletedAt")
            }
            if (postings.size != preview.postingCount || postings.map { it.toString() }.sorted() != preview.postingRows) {
                throw FinanceValidationException("账户资金记录已变化，请刷新后重新确认删除。")
            }
            requireZeroCanonicalBalance(row, postings)
            requireNoFuturePostings(tables, postings)
            requireNoUnsupportedDependencies(tables, preview.account.id)
            val visibility = rows(tables, "account_ledger_visibility").filter {
                it.optString("accountId") == preview.account.id.toString() && it.isNull("deletedAt")
            }.map { it.toString() }.sorted()
            if (visibility != preview.visibilityRows || visibility.size != preview.visibilityCount) {
                throw FinanceValidationException("账户显示范围已变化，请刷新后重新确认删除。")
            }
            requireNoPendingAccountIntents()
            mutations.enqueueCanonicalOnly(
                deviceId, "account", preview.account.id, "metadata.delete",
                JSONObject().put("strategy", "KEEP_RECORDS"),
                JSONObject().put("account:${preview.account.id}", preview.account.localRevision),
            )
        }
    }

    private fun requireZeroCanonicalBalance(account: JSONObject, postings: List<JSONObject>): Money {
        val currency = account.getString("currency")
        var total = BigInteger.ZERO
        postings.forEach { posting ->
            val signed = posting.optJSONObject("signed")
                ?: throw FinanceValidationException("账户资金记录不完整，无法安全删除。")
            val postingCurrency = signed.optString("currency")
            val scale = signed.optInt("scale", -1)
            val unscaled = signed.optString("unscaled")
            if (postingCurrency != currency || scale !in 0..6 || !INTEGER.matches(unscaled)) {
                throw FinanceValidationException("账户资金记录币种或金额无效，无法安全删除。")
            }
            val scaled = try {
                val units = unscaled.toLong()
                BigInteger.valueOf(units).multiply(BigInteger.TEN.pow(MAX_SCALE - scale))
            } catch (failure: NumberFormatException) {
                throw FinanceValidationException("账户资金记录金额无效，无法安全删除。", failure)
            }
            total = total.add(scaled)
        }
        if (total.signum() != 0) throw FinanceValidationException("仅可删除余额为零的账户。")
        return Money(0, 0, currency)
    }

    private fun requireNoFuturePostings(tables: JSONObject, postings: List<JSONObject>) {
        if (postings.isEmpty()) return
        val eventIds = postings.map { it.optString("eventId") }.toSet()
        val events = rows(tables, "financial_events").filter { it.optString("id") in eventIds }
        if (events.size != eventIds.size) {
            throw FinanceValidationException("账户资金记录关联的事件不完整，无法安全删除。")
        }
        val now = System.currentTimeMillis()
        if (events.any { event ->
                val raw = event.optString("occurredAt")
                val occurredAt = runCatching { java.time.Instant.parse(raw).toEpochMilli() }.getOrElse {
                    throw FinanceValidationException("账户资金记录时间无效，无法安全删除。", it)
                }
                occurredAt > now
            }) {
            throw FinanceValidationException("账户包含未来日期的资金记录，无法安全删除。")
        }
    }

    /** Rejects account-scoped products the server KEEP_RECORDS strategy does not detach. */
    private fun requireNoUnsupportedDependencies(tables: JSONObject, accountId: UUID) {
        val account = activeAccount(tables, accountId)
        // Receivable/payable operations are not represented in the client's DATA5 snapshot contract.
        if (account.optString("type") in setOf("RECEIVABLE", "PAYABLE")) {
            throw FinanceValidationException("此账户类型包含客户端无法完整检查的业务关联，暂不能删除。")
        }
        val id = accountId.toString()
        if (rows(tables, "account_postings").any { it.optString("accountId") == id && !it.isNull("deletedAt") }) {
            throw FinanceValidationException("账户包含不可安全核对的历史分录状态，暂不能删除。")
        }
        fun refers(table: String, field: String = "accountId") = rows(tables, table).any { it.optString(field) == id }
        if (refers("loan_plans") || refers("credit_profiles") || refers("credit_capacity_events") ||
            refers("installment_plans") || refers("credit_payment_allocations")) {
            throw FinanceValidationException("账户仍有关联贷款、信用额度或分期历史，无法删除。")
        }

        val accountTemplateIds = rows(tables, "template_allocations").filter {
            it.optString("accountId") == id
        }.map { it.optString("templateId") }.toSet()
        val liveTemplateAllocations = rows(tables, "template_allocations").any {
            it.optString("accountId") == id && it.isNull("deletedAt")
        }
        if (liveTemplateAllocations) {
            throw FinanceValidationException("账户仍被账单模板使用，无法删除。")
        }
        val activeRecurrencePlans = rows(tables, "recurrence_plans").filter {
            it.isNull("deletedAt") && it.optString("state") in setOf("RUNNING", "PAUSED")
        }.map { it.optString("id") }.toSet()
        val recurringTemplateIds = rows(tables, "recurrence_rule_segments").filter {
            it.optString("planId") in activeRecurrencePlans && it.isNull("deletedAt")
        }.map { it.optString("templateId") }.toSet()
        if (accountTemplateIds.any { it in recurringTemplateIds }) {
            throw FinanceValidationException("账户仍被周期业务使用，无法删除。")
        }

        val accountEventIds = rows(tables, "account_postings").filter {
            it.optString("accountId") == id && it.isNull("deletedAt")
        }.map { it.optString("eventId") }.toSet()
        val accountRecordIds = rows(tables, "record_allocations").filter {
            it.optString("accountId") == id && it.isNull("deletedAt")
        }.map { it.optString("recordId") }.toSet()
        val savingPlanIds = rows(tables, "saving_entries").filter { entry ->
            entry.isNull("cancelledAt") && (entry.optString("linkedRecordId") in accountRecordIds ||
                entry.optString("linkedTransferEventId") in accountEventIds)
        }.map { it.optString("planId") }.toSet()
        if (rows(tables, "saving_plans").any {
                it.optString("id") in savingPlanIds && it.isNull("deletedAt") && it.optString("state") == "ACTIVE"
            }) {
            throw FinanceValidationException("账户仍被存钱计划使用，无法删除。")
        }

        if (rows(tables, "claim_settlements").any { it.optString("accountId") == id && it.isNull("deletedAt") } ||
            rows(tables, "refunds").any { it.optString("accountId") == id && it.isNull("deletedAt") }) {
            throw FinanceValidationException("账户仍被理赔或退款业务使用，无法删除。")
        }
    }

    /** Every unresolved durable command can have an unknown server result, regardless of aggregate. */
    private fun requireNoPendingAccountIntents() {
        val identity = database.identity().read() ?: throw FinanceValidationException("账号或账本已变化，请重新操作。")
        val unresolved = database.accountMutations().intentsInOrder(identity.ownerId, identity.epochId)
            .filter { it.state in PENDING_STATES }
        if (unresolved.isNotEmpty()) {
            unresolved.forEach { intent ->
                val outbox = database.typedOutbox().byOpId(intent.opId)
                    ?: throw FinanceValidationException("账号操作队列不完整，无法安全删除账户。")
                if (outbox.ownerId != intent.ownerId || outbox.epochId != intent.epochId ||
                    outbox.aggregateType != intent.aggregateType || outbox.aggregateId != intent.aggregateId ||
                    outbox.commandType != intent.commandType || outbox.payloadJson != intent.payloadJson) {
                    throw FinanceValidationException("账号操作队列身份不匹配，无法安全删除账户。")
                }
            }
            throw FinanceValidationException("仍有账号操作等待服务器确认；请先同步原操作后再删除账户。")
        }
    }

    private fun requireCapturedAccount(row: JSONObject, account: FinanceAccount) {
        if (account.localRevision <= 0 || row.getLong("serverRevision") != account.localRevision ||
            row.getString("name") != account.name || row.getString("currency") != account.currency ||
            row.optString("groupId") != account.groupId.toString() || row.optString("type") != account.type ||
            row.optString("visibilityMode") != account.visibilityMode) {
            throw FinanceValidationException("账户已变化，请刷新后重新确认删除。")
        }
    }

    private fun activeAccount(tables: JSONObject, id: UUID): JSONObject =
        rows(tables, "accounts").singleOrNull { it.optString("id") == id.toString() && it.isNull("deletedAt") }
            ?: throw FinanceValidationException("账户已不可用，请先同步。")

    private fun requirePreviewIdentity(ownerId: String, epochId: String) {
        val identity = database.identity().read() ?: throw FinanceValidationException("账号或账本已变化，请重新确认删除。")
        if (identity.ownerId != ownerId || identity.epochId != epochId) {
            throw FinanceValidationException("账号或账本已变化，请重新确认删除。")
        }
    }

    private fun rows(tables: JSONObject, name: String): List<JSONObject> =
        tables.optJSONArray(name)?.let { array -> List(array.length()) { array.getJSONObject(it) } }.orEmpty()

    private companion object {
        val INTEGER = Regex("-?[0-9]+")
        const val MAX_SCALE = 6
        val PENDING_STATES = setOf("QUEUED", "AMBIGUOUS", "ACKED")
    }
}
