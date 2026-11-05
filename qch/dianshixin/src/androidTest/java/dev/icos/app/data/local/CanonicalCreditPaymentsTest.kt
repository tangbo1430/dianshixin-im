package dev.icos.app.data.local

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

class CanonicalCreditPaymentsTest {
    private val owner = uuid(1)
    private val epoch = uuid(2)
    private val recordId = uuid(10)
    private val eventId = uuid(11)
    private val liabilityId = uuid(12)
    private val assetId = uuid(13)
    private val ledgerId = uuid(14)

    private fun uuid(value: Long) = UUID(0, value).toString()
    private fun money(unscaled: Long, scale: Int = 2) = JSONObject()
        .put("unscaled", unscaled.toString()).put("scale", scale).put("currency", "CNY")

    private fun canonicalRow(id: String) = JSONObject()
        .put("id", id).put("ownerId", owner.toString()).put("datasetEpoch", epoch.toString())
        .put("serverRevision", 1).put("deletedAt", JSONObject.NULL)

    private fun fixture(paymentUnscaled: Long = -12_345, paymentScale: Int = 2): JSONObject {
        val liability = canonicalRow(liabilityId.toString())
            .put("type", "LIABILITY").put("currency", "CNY").put("selectable", 1)
            .put("archivedAt", JSONObject.NULL)
        val asset = canonicalRow(assetId.toString())
            .put("type", "ASSET").put("currency", "CNY").put("selectable", 1)
            .put("archivedAt", JSONObject.NULL)
        val event = canonicalRow(eventId.toString())
            .put("ledgerId", ledgerId.toString()).put("kind", "TRANSFER").put("status", "POSTED")
            .put("occurredAt", "2026-10-18T09:30:00Z").put("reversalOf", JSONObject.NULL)
        val record = canonicalRow(recordId.toString())
            .put("eventId", eventId.toString()).put("ledgerId", ledgerId.toString())
            .put("direction", "TRANSFER").put("status", "POSTED")
        val debtLeg = canonicalRow(uuid(20)).put("eventId", eventId.toString())
            .put("accountId", liabilityId.toString()).put("signed", money(paymentUnscaled, paymentScale))
            .put("reversalOf", JSONObject.NULL)
        val assetLeg = canonicalRow(uuid(21)).put("eventId", eventId.toString())
            .put("accountId", assetId.toString()).put("signed", money(paymentUnscaled, paymentScale))
            .put("reversalOf", JSONObject.NULL)
        return JSONObject()
            .put("records", JSONArray().put(record))
            .put("financial_events", JSONArray().put(event))
            .put("accounts", JSONArray().put(liability).put(asset))
            .put("account_postings", JSONArray().put(debtLeg).put(assetLeg))
            .put("credit_payment_allocations", JSONArray())
    }

    private fun read(tables: JSONObject) = CanonicalCreditPayments.read(tables, UUID.fromString(owner), UUID.fromString(epoch))

    @Test
    fun partialAllocationUsesExactMixedScalesAndFullPaymentIsNoLongerCandidate() {
        val tables = fixture()
        val allocations = tables.getJSONArray("credit_payment_allocations")
        allocations.put(canonicalRow(uuid(30)).put("paymentEventId", eventId.toString())
            .put("accountId", liabilityId.toString()).put("termId", uuid(31)).put("amount", money(1, 0)))
        allocations.put(canonicalRow(uuid(32)).put("paymentEventId", eventId.toString())
            .put("accountId", liabilityId.toString()).put("termId", uuid(33)).put("amount", money(25, 2)))

        val partial = read(tables).single()
        assertEquals(12_345L, partial.amount.unscaled)
        assertEquals(2, partial.amount.scale)
        assertEquals(12_220L, partial.remainingToAllocate.unscaled)
        assertEquals(2, partial.remainingToAllocate.scale)

        allocations.put(canonicalRow(uuid(34)).put("paymentEventId", eventId.toString())
            .put("accountId", liabilityId.toString()).put("termId", uuid(35)).put("amount", money(12_220, 2)))
        assertEquals(emptyList<FinanceCreditPayment>(), read(tables))
    }

    @Test
    fun rejectsInvalidLegsReversalsAndUnconfirmedPredictions() {
        val positiveAsset = fixture().also { tables ->
            tables.getJSONArray("account_postings").getJSONObject(1).put("signed", money(12_345))
        }
        assertEquals(emptyList<FinanceCreditPayment>(), read(positiveAsset))

        val threeLegs = fixture().also { tables ->
            tables.getJSONArray("accounts").put(canonicalRow(uuid(15)).put("type", "ASSET")
                .put("currency", "CNY").put("selectable", true).put("archivedAt", JSONObject.NULL))
            tables.getJSONArray("account_postings").put(canonicalRow(uuid(22)).put("eventId", eventId.toString())
                .put("accountId", uuid(15)).put("signed", money(-1)).put("reversalOf", JSONObject.NULL))
        }
        assertEquals(emptyList<FinanceCreditPayment>(), read(threeLegs))

        val reversed = fixture().also { tables ->
            tables.getJSONArray("financial_events").put(canonicalRow(uuid(40)).put("kind", "REVERSAL")
                .put("status", "POSTED").put("reversalOf", eventId.toString()))
        }
        assertEquals(emptyList<FinanceCreditPayment>(), read(reversed))

        val predictedRecord = fixture().also { tables ->
            tables.getJSONArray("records").getJSONObject(0).put("serverRevision", 0)
        }
        assertEquals(emptyList<FinanceCreditPayment>(), read(predictedRecord))
    }

    @Test
    fun rejectsRowsFromAnotherOwnerOrEpochAndAcceptsNumericSelectable() {
        val valid = fixture()
        assertEquals(1, read(valid).size)

        val foreignOwner = fixture().also { tables ->
            tables.getJSONArray("financial_events").getJSONObject(0).put("ownerId", uuid(99))
        }
        assertEquals(emptyList<FinanceCreditPayment>(), read(foreignOwner))

        val foreignEpoch = fixture().also { tables ->
            tables.getJSONArray("account_postings").getJSONObject(0).put("datasetEpoch", uuid(98))
        }
        assertEquals(emptyList<FinanceCreditPayment>(), read(foreignEpoch))
    }
}
