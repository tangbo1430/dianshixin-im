package dev.icos.app.data.local

import dev.icos.app.domain.Money
import org.json.JSONObject
import java.time.OffsetDateTime
import java.util.UUID

internal object CanonicalCreditPayments {
    fun read(tables: JSONObject, owner: UUID, epoch: UUID): List<FinanceCreditPayment> {
        fun rows(table: String): List<JSONObject> {
            val array = tables.optJSONArray(table) ?: return emptyList()
            return (0 until array.length()).map { array.getJSONObject(it) }
                .filter { it.optString("ownerId") == owner.toString() && it.optString("datasetEpoch") == epoch.toString() }
        }
        val events = rows("financial_events")
        val eventsById = events.associateBy { it.optString("id") }
        val reversed = events.mapNotNull { it.stringOrNull("reversalOf") }.toSet()
        val accounts = rows("accounts").associateBy { it.optString("id") }
        val postings = rows("account_postings").filter { it.isNull("reversalOf") && it.isNull("deletedAt") }
            .groupBy { it.optString("eventId") }
        val allocations = rows("credit_payment_allocations").groupBy { it.optString("paymentEventId") }
        return rows("records").mapNotNull { record ->
            runCatching {
                require(record.confirmed() && record.isNull("deletedAt") &&
                    record.optString("direction") == "TRANSFER" && record.optString("status") == "POSTED")
                val eventId = record.getString("eventId")
                require(eventId !in reversed)
                val event = requireNotNull(eventsById[eventId])
                require(event.confirmed() && event.optString("kind") == "TRANSFER" &&
                    event.optString("status") == "POSTED" && event.isNull("deletedAt") &&
                    event.getString("ledgerId") == record.getString("ledgerId"))
                val legs = postings[eventId].orEmpty()
                require(legs.size == 2 && legs.all { it.confirmed() })
                val legAccounts = legs.map { leg ->
                    requireNotNull(accounts[leg.getString("accountId")]).also { account ->
                        require(account.confirmed() && account.isNull("deletedAt") &&
                            account.isNull("archivedAt") && account.canonicalBoolean("selectable"))
                    }
                }
                val liabilityIndex = legAccounts.indices.single { legAccounts[it].optString("type") == "LIABILITY" }
                val assetIndex = 1 - liabilityIndex
                require(legAccounts[assetIndex].optString("type") in setOf("ASSET", "CASH", "BANK"))
                val liability = legAccounts[liabilityIndex]
                val asset = legAccounts[assetIndex]
                require(liability.getString("id") != asset.getString("id"))
                val debtLeg = legs[liabilityIndex].money("signed")
                val assetLeg = legs[assetIndex].money("signed")
                require(debtLeg.unscaled < 0 && assetLeg.unscaled < 0 && debtLeg.sameValue(assetLeg) &&
                    debtLeg.currency == liability.getString("currency") && assetLeg.currency == asset.getString("currency"))
                val amount = debtLeg.checkedNegate()
                val allocated = allocations[eventId].orEmpty().fold(Money(0, 0, amount.currency)) { total, row ->
                    require(row.confirmed() && row.getString("accountId") == liability.getString("id"))
                    val value = row.money("amount")
                    value.requirePositive()
                    total.checkedAdd(value)
                }
                val remaining = amount.checkedSubtract(allocated)
                require(remaining.unscaled > 0)
                FinanceCreditPayment(UUID.fromString(record.getString("id")), UUID.fromString(eventId),
                    UUID.fromString(liability.getString("id")), UUID.fromString(asset.getString("id")),
                    amount, remaining, OffsetDateTime.parse(event.getString("occurredAt")
                        .replace(Regex("([+-]\\d{2})(\\d{2})$"), "$1:$2")).toInstant().toEpochMilli())
            }.getOrNull()
        }.sortedByDescending { it.occurredAtMillis }
    }

    private fun JSONObject.confirmed() = opt("serverRevision")?.toString()?.toLongOrNull()?.let { it > 0 } == true
    private fun JSONObject.stringOrNull(key: String): String? = if (isNull(key)) null else getString(key)
    private fun JSONObject.money(key: String): Money = getJSONObject(key).let {
        Money.fromWire(it.getString("unscaled"), it.getInt("scale"), it.getString("currency"))
    }
}
