package dev.icos.app.fileio

import dev.icos.app.domain.Money
import dev.icos.app.domain.RecordInput
import dev.icos.app.data.remote.RecordInputJson
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.security.MessageDigest
import java.util.UUID

/** Validated, fully mapped single record persisted as an owned import row. */
data class PreparedImportRecord(
    val rowNumber: Int,
    val input: RecordInput,
    val occurredAtMillis: Long,
    val timezone: String,
    val remark: String,
    val categoryId: UUID?,
    val tagIds: List<UUID>,
    val externalId: String? = null,
)

object ImportBatchFactory {
    fun rows(batchId: String, records: List<PreparedImportRecord>): List<dev.icos.app.data.local.FileImportRowEntity> {
        require(records.size in 1..TabularFileCore.MAX_ROWS)
        require(records.map { it.rowNumber }.distinct().size == records.size)
        return records.map { item ->
            require(item.rowNumber > 0 && item.remark.length <= 2000 && '\u0000' !in item.remark && item.timezone in java.util.TimeZone.getAvailableIDs() && item.occurredAtMillis in 0..System.currentTimeMillis())
            item.input.calculate()
            val recordId = UUID.randomUUID()
            val payload = JSONObject()
                .put("recordId", recordId.toString())
                .put("input", encodeInput(item.input))
                .put("occurredAtMillis", item.occurredAtMillis)
                .put("timezone", item.timezone)
                .put("remark", item.remark)
                .put("categoryId", item.categoryId?.toString() ?: JSONObject.NULL)
                .put("tagIds", JSONArray().also { a -> item.tagIds.forEach { a.put(it.toString()) } })
                .toString()
            val fingerprint = MessageDigest.getInstance("SHA-256").digest(
                listOf(item.occurredAtMillis.toString(), item.timezone, item.input.toString(), item.categoryId?.toString().orEmpty(), item.remark, item.tagIds.joinToString(",")).joinToString("\u0000").toByteArray(Charsets.UTF_8),
            ).joinToString("") { "%02x".format(it) }
            dev.icos.app.data.local.FileImportRowEntity(batchId, item.rowNumber, payload, fingerprint, item.externalId, null, "PREVIEWED")
        }
    }

    private fun encodeInput(input: RecordInput): JSONObject = when (input) {
        is RecordInput.Expense -> JSONObject().put("kind", "EXPENSE").put("gross", money(input.gross))
            .put("discount", input.discount?.let(::money) ?: JSONObject.NULL)
            .put("allocations", JSONArray().also { a ->
                val recordAmounts = input.recordCurrencyAmounts ?: input.allocations.map { it.amount }
                input.allocations.forEachIndexed { index, allocation ->
                    val encoded = accountAmount(allocation.accountId, allocation.amount)
                    if (recordAmounts[index].currency != allocation.amount.currency) encoded.put("recordCurrencyAmount", money(recordAmounts[index]))
                    a.put(encoded)
                }
            })
            .put("countInStatistics", input.countInStatistics).put("countInBudget", input.countInBudget)
        is RecordInput.Income -> JSONObject().put("kind", "INCOME").put("amount", money(input.amount))
            .put("allocations", JSONArray().also { a -> input.allocations.forEach { a.put(accountAmount(it.accountId, it.amount)) } })
            .put("countInStatistics", input.countInStatistics)
        is RecordInput.Transfer -> JSONObject().put("kind", "TRANSFER").put("from", accountAmount(input.from.accountId, input.from.amount))
            .put("to", accountAmount(input.to.accountId, input.to.amount))
            .put("fee", input.fee?.let { accountAmount(it.accountId, it.amount) } ?: JSONObject.NULL)
            .put("bonus", input.bonus?.let { accountAmount(it.accountId, it.amount) } ?: JSONObject.NULL)
    }

    private fun accountAmount(accountId: UUID, money: Money) = JSONObject().put("accountId", accountId.toString()).put("amount", money(money))
    private fun money(money: Money) = JSONObject().put("unscaled", money.unscaled.toString()).put("scale", money.scale).put("currency", money.currency)
}
