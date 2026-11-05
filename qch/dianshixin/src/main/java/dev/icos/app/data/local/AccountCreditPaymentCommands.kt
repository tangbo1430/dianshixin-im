package dev.icos.app.data.local

import dev.icos.app.data.remote.SnapshotProtocol
import dev.icos.app.domain.AccountAmount
import dev.icos.app.domain.Money
import dev.icos.app.domain.RecordInput
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZoneId
import java.util.UUID

/**
 * Builds the two explicit account steps used to repay credit installments.
 *
 * Repayment is intentionally split: [create] enqueues a real ASSET -> LIABILITY transfer record,
 * and only once that transfer is canonically confirmed does [allocate] distribute the confirmed
 * payment across installment terms with `credit.allocatePayment`. There is no automatic second
 * step and no persisted draft: the caller re-selects the confirmed transfer.
 *
 * Transfers use the existing record queue and prediction; allocations keep a nonfinancial replay
 * marker. Allocation always reads the canonical mirror and never uses a predicted event.
 */
internal class AccountCreditPaymentCommands(private val database: DatasetDatabase) {
    private val mutations = AccountMutationRepository(database)

    /**
     * Enqueues the repayment transfer (cash out of [assetId] and equal debt reduction on the
     * LIABILITY [accountId]). The returned id is the new record id; its canonical event id is only
     * available after the next pull and is what [allocate] must be given.
     */
    fun create(
        deviceId: UUID,
        accountId: UUID,
        expectedAccountRevision: Long,
        assetId: UUID,
        expectedAssetRevision: Long,
        ledgerId: UUID,
        expectedLedgerRevision: Long,
        amount: Money,
        timezone: String,
        occurredAtMillis: Long = System.currentTimeMillis(),
    ): UUID {
        require(accountId != assetId) { "还款账户无效" }
        require(amount.unscaled > 0) { "还款金额必须为正数" }
        require(occurredAtMillis in 0..System.currentTimeMillis()) { "还款时间无效" }
        require(timezone.length in 1..128 && runCatching { ZoneId.of(timezone) }.isSuccess) { "还款时区无效" }
        require(expectedAccountRevision > 0 && expectedAssetRevision > 0 && expectedLedgerRevision > 0) {
            "还款需要服务端已确认的账户与账本版本"
        }
        requireCanonicalSchema()
        val recordId = UUID.randomUUID()
        database.runInTransaction {
            val tables = SnapshotImporter.canonicalTables(database)
            val liability = find(tables, "accounts", accountId)
            require(liability.optString("type") == "LIABILITY" && liability.canonicalBoolean("selectable") &&
                liability.getString("currency") == amount.currency && liability.getLong("serverRevision") == expectedAccountRevision) {
                "负债账户已变化或不可选；请刷新后重新编辑。"
            }
            val asset = find(tables, "accounts", assetId)
            require(asset.optString("type") in setOf("ASSET", "CASH", "BANK") && asset.canonicalBoolean("selectable") &&
                asset.getString("currency") == amount.currency && asset.getLong("serverRevision") == expectedAssetRevision) {
                "还款资产账户已变化或不可选；请刷新后重新编辑。"
            }
            val ledger = find(tables, "ledgers", ledgerId)
            require(ledger.getString("baseCurrency") == amount.currency && ledger.getLong("serverRevision") == expectedLedgerRevision) {
                "账本已变化；请刷新后重新编辑。"
            }
            require(tables.getJSONArray("credit_profiles").objects().any {
                it.optString("accountId") == accountId.toString() && it.isActive() &&
                    it.canonicalBoolean("enabled") && it.money("creditLimit").currency == amount.currency
            }) { "请先配置有效的信用账期" }
            mutations.requireNoPendingAccountMutations(setOf(accountId, assetId))
            val input = RecordInput.Transfer(
                from = AccountAmount(assetId, amount),
                to = AccountAmount(accountId, amount),
                fee = null,
                bonus = null,
            )
            mutations.saveRecord(deviceId, recordId, null, ledgerId, input, occurredAtMillis, timezone, "信用还款", null, emptyList())
        }
        return recordId
    }

    /**
     * Allocates a canonically confirmed repayment transfer to one or more POSTED/PAID terms. The
     * transfer must be a two-leg, non-reversed TRANSFER whose both stored legs are negative and
     * equal (a real cash-out repayment), and the running per-payment total must stay within it.
     */
    fun allocate(
        deviceId: UUID,
        accountId: UUID,
        expectedAccountRevision: Long,
        paymentEventId: UUID,
        allocations: Map<UUID, Money>,
    ) {
        require(expectedAccountRevision > 0) { "还款分配需要服务端已确认的账户版本" }
        require(allocations.isNotEmpty() && allocations.size <= 1200) { "请选择至少一个待还期次" }
        requireCanonicalSchema()
        val identity = database.identity().read() ?: error("Dataset identity missing")
        val tables = SnapshotImporter.canonicalTables(database)
        val liability = find(tables, "accounts", accountId)
        require(liability.optString("type") == "LIABILITY" && liability.canonicalBoolean("selectable") &&
            liability.getLong("serverRevision") == expectedAccountRevision) {
            "负债账户已变化或不可选；请刷新后重新编辑。"
        }
        val currency = liability.getString("currency")
        val event = tables.getJSONArray("financial_events").objects().firstOrNull {
            it.optString("id") == paymentEventId.toString() && it.isActive()
        } ?: throw IllegalStateException("还款转账尚未同步确认；请稍后重试。")
        requireSameCanonical(event, identity) { "还款转账不在当前账号数据内" }
        require(event.getLong("serverRevision") > 0 && event.optString("kind") == "TRANSFER" && event.optString("status") == "POSTED" &&
            tables.getJSONArray("financial_events").objects().none { it.optString("reversalOf") == paymentEventId.toString() }) {
            "还款转账尚未同步确认；请稍后重试。"
        }
        val record = tables.getJSONArray("records").objects().firstOrNull {
            it.optString("eventId") == paymentEventId.toString() && it.isActive()
        } ?: throw IllegalStateException("还款转账记录缺失")
        requireSameCanonical(record, identity) { "还款转账不在当前账号数据内" }
        require(record.getLong("serverRevision") > 0 && record.optString("direction") == "TRANSFER" && record.optString("status") == "POSTED" &&
            record.optString("ledgerId") == event.optString("ledgerId")) { "还款转账记录无效" }
        val postings = tables.getJSONArray("account_postings").objects().filter {
            it.optString("eventId") == paymentEventId.toString() && it.isActive()
        }
        require(postings.size == 2 && postings.all { it.isNull("reversalOf") }) { "还款转账分录无效" }
        postings.forEach { requireSameCanonical(it, identity) { "还款转账不在当前账号数据内" } }
        val debtPosting = postings.singleOrNull { it.optString("accountId") == accountId.toString() }
            ?: throw IllegalStateException("还款转账分录无效")
        val counterpartPosting = postings.single { it.optString("accountId") != accountId.toString() }
        val counterpartId = UUID.fromString(counterpartPosting.getString("accountId"))
        val counterpart = find(tables, "accounts", counterpartId)
        require(counterpart.optString("type") in setOf("ASSET", "CASH", "BANK") && counterpart.canonicalBoolean("selectable") &&
            counterpart.getLong("serverRevision") > 0 && counterpart.getString("currency") == currency) { "还款资产账户无效" }
        val payment = debtPosting.money("signed")
        val counterpartAmount = counterpartPosting.money("signed")
        require(payment.unscaled < 0 && counterpartAmount.unscaled < 0 &&
            payment.sameValue(counterpartAmount) && payment.currency == currency) { "还款转账分录无效" }
        val transferable = payment.checkedNegate()
        val plans = tables.getJSONArray("installment_plans").objects().filter { it.optString("accountId") == accountId.toString() }
        val terms = tables.getJSONArray("installment_terms").objects()
        val existing = tables.getJSONArray("credit_payment_allocations").objects()
        val priorTotal = existing.filter { it.optString("paymentEventId") == paymentEventId.toString() }
            .fold(Money(0, 0, currency)) { sum, row -> sum.checkedAdd(row.money("amount")) }
        var requestedTotal = Money(0, 0, currency)
        allocations.forEach { (termId, amount) ->
            require(amount.unscaled > 0 && amount.currency == currency) { "还款分配金额无效" }
            val term = terms.singleOrNull {
                it.optString("id") == termId.toString() && it.isActive() && it.optString("state") != "SUPERSEDED"
            } ?: throw IllegalStateException("分期期次不可还款")
            require(plans.any { it.optString("id") == term.optString("planId") } &&
                term.optString("state") in setOf("POSTED", "PAID") && !term.isNull("postingEventId")) {
                "分期期次不可还款，请刷新后重试。"
            }
            val ceiling = term.money("principal").checkedAdd(term.money("interest"))
            require(ceiling.currency == currency) { "分期期次币种不一致" }
            val already = existing.filter { it.optString("termId") == termId.toString() }
                .fold(Money(0, 0, currency)) { sum, row -> sum.checkedAdd(row.money("amount")) }
            require(ceiling.checkedSubtract(already).checkedSubtract(amount).unscaled >= 0) { "还款分配超过期次剩余应还金额" }
            requestedTotal = requestedTotal.checkedAdd(amount)
        }
        require(transferable.checkedSubtract(priorTotal).checkedSubtract(requestedTotal).unscaled >= 0) {
            "还款分配超过本次转账可分配金额"
        }
        val encoded = JSONArray()
        allocations.forEach { (termId, amount) ->
            require(termId != UUID(0, 0)) { "还款分配期次无效" }
            encoded.put(JSONObject().put("termId", termId.toString()).put("amount", money(amount)))
        }
        val payload = JSONObject().put("paymentEventId", paymentEventId.toString()).put("allocations", encoded)
        val versions = JSONObject()
            .put("account:$accountId", expectedAccountRevision)
            .put("account:$counterpartId", counterpart.getLong("serverRevision"))
        mutations.enqueueCanonicalOnly(deviceId, "account", accountId, "credit.allocatePayment", payload, versions)
    }

    private fun requireCanonicalSchema() {
        val identity = database.identity().read() ?: error("Dataset identity missing")
        require(identity.snapshotSchemaVersion in SnapshotProtocol.DATA4_SCHEMA..SnapshotProtocol.DATA6_SCHEMA) {
            "还款命令需要支持信用分期的账号数据版本；请先同步升级。"
        }
    }

    private fun requireSameCanonical(row: JSONObject, identity: DatasetIdentity, message: () -> String) {
        require(row.optString("ownerId") == identity.ownerId && row.optString("datasetEpoch") == identity.epochId, message)
    }

    private fun find(tables: JSONObject, table: String, id: UUID): JSONObject =
        tables.getJSONArray(table).objects().firstOrNull { it.optString("id") == id.toString() && it.isActive() }
            ?: throw IllegalStateException("Canonical $table reference unavailable")

    private fun JSONArray.objects() = List(length()) { getJSONObject(it) }
    private fun JSONObject.isActive() = isNull("deletedAt") && (!has("archivedAt") || isNull("archivedAt"))
    private fun JSONObject.money(key: String): Money = getJSONObject(key).let {
        Money(it.getString("unscaled").toLong(), it.getInt("scale"), it.getString("currency"))
    }
    private fun money(value: Money) = JSONObject()
        .put("unscaled", value.unscaled.toString()).put("scale", value.scale).put("currency", value.currency)
}
