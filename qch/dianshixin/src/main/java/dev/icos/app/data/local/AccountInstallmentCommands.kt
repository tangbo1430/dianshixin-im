package dev.icos.app.data.local

import dev.icos.app.data.remote.SnapshotProtocol
import dev.icos.app.domain.InterestMode
import dev.icos.app.domain.Money
import dev.icos.app.domain.PlanCalculator
import dev.icos.app.domain.Remainder
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * Builds canonical `installment.*` commands for a credit (LIABILITY) account.
 *
 * Commands are handed to [AccountMutationRepository.enqueueCanonicalOnly] so the pending marker is
 * preserved and no financial facts are predicted locally: balances, capacity events and statistics
 * become visible only after the next canonical pull. This class only validates against the current
 * canonical mirror (schema gate, references, CAS revisions, exact money), mirrors the server's
 * `finance/installments.rs` rules and delegates the allocation admission to [PlanCalculator].
 */
internal class AccountInstallmentCommands(private val database: DatasetDatabase) {
    private val mutations = AccountMutationRepository(database)

    /**
     * Creates a CONSUMPTION installment plan. A fresh random plan id is generated; the plan root is
     * registered at revision 0 together with the account and ledger CAS revisions. Returns the id.
     */
    fun create(
        deviceId: UUID,
        accountId: UUID,
        expectedAccountRevision: Long,
        ledgerId: UUID,
        expectedLedgerRevision: Long,
        principal: Money,
        interest: Money,
        terms: Int,
        firstDate: String,
        timezone: String,
        principalPosting: String,
        interestMode: String,
        remainderMode: String,
    ): UUID {
        require(principal.unscaled > 0) { "分期本金必须为正数" }
        require(interest.unscaled >= 0 && interest.currency == principal.currency) { "分期利息无效" }
        require(terms in 1..1200) { "分期期数需在 1..1200 之间" }
        require(principalPosting in setOf("TERM_BY_TERM", "UPFRONT")) { "本金入账方式无效" }
        require(interestMode in setOf("TERM_BY_TERM", "FIRST_TERM")) { "利息模式无效" }
        require(remainderMode in setOf("FIRST_TERM", "LAST_TERM")) { "余数归属无效" }
        require(timezone.length in 1..128 && runCatching { ZoneId.of(timezone) }.isSuccess) { "入账时区无效" }
        requireValidDate(firstDate)
        require(expectedAccountRevision > 0 && expectedLedgerRevision > 0) { "分期需要服务端已确认的账户与账本版本" }
        requireCanonicalSchema()
        val tables = SnapshotImporter.canonicalTables(database)
        val account = find(tables, "accounts", accountId)
        require(account.optString("type") == "LIABILITY" && account.getString("currency") == principal.currency &&
            account.canonicalBoolean("selectable") && account.getLong("serverRevision") == expectedAccountRevision) {
            "负债账户已变化或不可选；请刷新后重新编辑。"
        }
        val ledger = find(tables, "ledgers", ledgerId)
        require(ledger.getString("baseCurrency") == principal.currency && ledger.getLong("serverRevision") == expectedLedgerRevision) {
            "账本已变化；请刷新后重新编辑。"
        }
        val profile = tables.getJSONArray("credit_profiles").objects().firstOrNull {
            it.optString("accountId") == accountId.toString() && it.isActive() && it.canonicalBoolean("enabled")
        } ?: throw IllegalStateException("请先配置有效的信用账期")
        val limit = profile.money("creditLimit")
        require(limit.currency == principal.currency) { "信用额度币种与分期本金不一致" }
        if (principalPosting == "TERM_BY_TERM") {
            // Mirrors the guest rule: deferred principal must fit inside the raw credit capacity.
            val capacity = tables.getJSONArray("credit_capacity_events").objects()
                .filter { it.optString("accountId") == accountId.toString() && it.isActive() }
                .fold(limit) { sum, event -> sum.checkedAdd(event.money("signed")) }
            require(capacity.checkedSubtract(principal).unscaled >= 0) { "剩余授信额度不足" }
        }
        // Promote principal to max(principal.scale, interest.scale) so the server allocation, which
        // uses the plan principal scale, never has to round the interest silently.
        val outputScale = maxOf(principal.scale, interest.scale)
        val scaledPrincipal = promote(principal, outputScale)
        val scaledInterest = promote(interest, outputScale)
        val capacityRestore = if (principalPosting == "TERM_BY_TERM") scaledPrincipal else Money(0, outputScale, principal.currency)
        PlanCalculator.installmentAllocation(
            scaledPrincipal, scaledInterest, terms, remainder(remainderMode), mode(interestMode), outputScale,
        )
        val planId = UUID.randomUUID()
        val plan = JSONObject()
            .put("sourceType", "CONSUMPTION")
            .put("sourceRecordId", JSONObject.NULL)
            .put("accountId", accountId.toString())
            .put("ledgerId", ledgerId.toString())
            .put("principal", money(scaledPrincipal))
            .put("interest", money(scaledInterest))
            .put("termCount", terms)
            .put("interestMode", interestMode)
            .put("remainderMode", remainderMode)
            .put("principalPosting", principalPosting)
            .put("capacityRestore", money(capacityRestore))
            .put("firstPostingDate", firstDate)
            .put("timezone", timezone)
        val versions = JSONObject()
            .put("installment:$planId", 0)
            .put("account:$accountId", expectedAccountRevision)
            .put("ledger:$ledgerId", expectedLedgerRevision)
        mutations.enqueueCanonicalOnly(
            deviceId, "installment", planId, "installment.create", JSONObject().put("plan", plan), versions,
        )
        return planId
    }

    /**
     * Reschedules every still-SCHEDULED term (from term 1) of an ACTIVE plan. The plan's own
     * interest and remainder modes are preserved; only the total interest, term count and
     * first posting date change. CAS is taken on the plan root and on the current account revision.
     */
    fun update(
        deviceId: UUID,
        planId: UUID,
        expectedRevision: Long,
        interest: Money,
        termCount: Int,
        firstDate: String,
    ) {
        require(expectedRevision > 0) { "分期更新需要服务端已确认的版本" }
        require(termCount in 1..1200) { "分期期数需在 1..1200 之间" }
        requireValidDate(firstDate)
        requireCanonicalSchema()
        val tables = SnapshotImporter.canonicalTables(database)
        val plan = find(tables, "installment_plans", planId)
        require(plan.getLong("serverRevision") == expectedRevision && plan.optString("state") == "ACTIVE") {
            "分期计划已变化或不可编辑；请刷新后重试。"
        }
        val accountId = UUID.fromString(plan.getString("accountId"))
        val account = find(tables, "accounts", accountId)
        require(account.optString("type") == "LIABILITY") { "分期关联账户无效" }
        val principal = plan.money("principal")
        require(interest.unscaled >= 0 && interest.currency == principal.currency) { "分期利息无效" }
        // The server reallocates future terms at the plan principal scale; a finer interest must be
        // exactly representable there and is rejected instead of being rounded silently.
        requireExactUnits(interest, principal.scale)
        val current = tables.getJSONArray("installment_terms").objects().filter {
            it.optString("planId") == planId.toString() && it.isActive() && it.optString("state") != "SUPERSEDED"
        }
        require(current.isNotEmpty() && current.all { it.optString("state") == "SCHEDULED" }) {
            "存在已入账期次，无法重排未来期次；请先处理已入账期次。"
        }
        PlanCalculator.installmentAllocation(
            principal, interest, termCount, remainder(plan.getString("remainderMode")), mode(plan.getString("interestMode")), principal.scale,
        )
        val payload = JSONObject().put("fromTerm", 1).put("changes", JSONObject()
            .put("interest", money(interest)).put("termCount", termCount).put("firstPostingDate", firstDate))
        val versions = JSONObject()
            .put("installment:$planId", expectedRevision)
            .put("account:$accountId", account.getLong("serverRevision"))
        mutations.enqueueCanonicalOnly(deviceId, "installment", planId, "installment.update", payload, versions)
    }

    /**
     * Terminates an ACTIVE plan with [KEEP_POSTED]: posted terms are retained as history, scheduled
     * terms are superseded. CAS is taken on the plan root and on the current account revision.
     */
    fun delete(deviceId: UUID, planId: UUID, expectedRevision: Long) {
        require(expectedRevision > 0) { "分期删除需要服务端已确认的版本" }
        requireCanonicalSchema()
        val tables = SnapshotImporter.canonicalTables(database)
        val plan = find(tables, "installment_plans", planId)
        require(plan.getLong("serverRevision") == expectedRevision && plan.optString("state") == "ACTIVE") {
            "分期计划已变化或不可删除；请刷新后重试。"
        }
        val accountId = UUID.fromString(plan.getString("accountId"))
        val account = find(tables, "accounts", accountId)
        require(account.optString("type") == "LIABILITY") { "分期关联账户无效" }
        val versions = JSONObject()
            .put("installment:$planId", expectedRevision)
            .put("account:$accountId", account.getLong("serverRevision"))
        mutations.enqueueCanonicalOnly(
            deviceId, "installment", planId, "installment.delete", JSONObject().put("strategy", "KEEP_POSTED"), versions,
        )
    }

    private fun requireCanonicalSchema() {
        val identity = database.identity().read() ?: error("Dataset identity missing")
        require(identity.snapshotSchemaVersion in SnapshotProtocol.DATA4_SCHEMA..SnapshotProtocol.DATA6_SCHEMA) {
            "分期命令需要支持信用分期的账号数据版本；请先同步升级。"
        }
    }

    private fun requireValidDate(value: String) {
        require(value.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")) && runCatching { LocalDate.parse(value) }.isSuccess) {
            "首期日期无效"
        }
    }

    private fun promote(value: Money, scale: Int): Money {
        require(scale >= value.scale) { "分期金额精度提升无效" }
        val scaled = BigInteger.valueOf(value.unscaled).multiply(BigInteger.TEN.pow(scale - value.scale))
        return Money(longExact(scaled), scale, value.currency)
    }

    private fun requireExactUnits(value: Money, scale: Int) {
        if (scale >= value.scale) return
        val remainder = BigInteger.valueOf(value.unscaled).divideAndRemainder(BigInteger.TEN.pow(value.scale - scale))[1]
        require(remainder.signum() == 0) { "利息精度超出可精确表达范围，请调整金额。" }
    }

    private fun longExact(value: BigInteger): Long = try {
        value.longValueExact()
    } catch (_: ArithmeticException) {
        throw IllegalArgumentException("分期金额超出可表示范围")
    }

    private fun remainder(value: String): Remainder = when (value) {
        "FIRST_TERM" -> Remainder.HEAD
        "LAST_TERM" -> Remainder.TAIL
        else -> error("Unsupported installment remainder mode")
    }

    private fun mode(value: String): InterestMode = when (value) {
        "TERM_BY_TERM" -> InterestMode.SPREAD
        "FIRST_TERM" -> InterestMode.FIRST
        else -> error("Unsupported installment interest mode")
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
