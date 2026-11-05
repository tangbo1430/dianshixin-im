package dev.icos.app.data.remote

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.icos.app.domain.Money
import dev.icos.app.domain.MoneyException
import dev.icos.app.domain.RecordException
import dev.icos.app.domain.RecordInput
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RecordInputJsonTest {
    private fun record(kind: String = "EXPENSE"): JSONObject {
        val context = InstrumentationRegistry.getInstrumentation().context
        val fixture =
            JSONObject(
                context.assets
                    .open("financial-cases.json")
                    .bufferedReader()
                    .use { it.readText() },
            )
        val records =
            when (kind) {
                "TRANSFER" -> fixture.getJSONArray("cases").getJSONObject(2).getJSONArray("records")
                else -> fixture.getJSONArray("cases").getJSONObject(0).getJSONArray("records")
            }
        return records.getJSONObject(if (kind == "INCOME") 1 else 0)
    }

    @Test
    fun acceptsCrossCurrencyExpenseAllocationButKeepsIncomeAndTransferStrict() {
        val expense = JSONObject(record().toString())
        val allocation = expense.getJSONArray("allocations").getJSONObject(0)
        allocation.put("amount", JSONObject().put("unscaled", "450").put("scale", 2).put("currency", "USD"))
            .put("recordCurrencyAmount", JSONObject().put("unscaled", "10000").put("scale", 2).put("currency", "CNY"))
        val decoded = RecordInputJson.decode(expense) as RecordInput.Expense
        assertEquals(Money(450, 2, "USD"), decoded.allocations.single().amount)
        assertEquals(listOf(Money(10000, 2, "CNY")), decoded.recordCurrencyAmounts)

        val income = JSONObject(record("INCOME").toString())
        income.getJSONArray("allocations").getJSONObject(0).put("recordCurrencyAmount", JSONObject().put("unscaled", "10000").put("scale", 2).put("currency", "CNY"))
        assertThrows(RecordException::class.java) { RecordInputJson.decode(income) }
        val transfer = JSONObject(record("TRANSFER").toString())
        transfer.getJSONObject("from").put("recordCurrencyAmount", JSONObject().put("unscaled", "10000").put("scale", 2).put("currency", "CNY"))
        assertThrows(RecordException::class.java) { RecordInputJson.decode(transfer) }
    }

    @Test
    fun frozenFxAllocationContractAcceptsT09AndRejectsXfx02() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val fixture = JSONObject(context.assets.open("financial-cases.json").bufferedReader().use { it.readText() })
        val t09Case = fixture.getJSONArray("cases").let { cases ->
            (0 until cases.length()).map(cases::getJSONObject).single { it.getString("id") == "T09" }
        }
        val t09 = t09Case.getJSONArray("records").getJSONObject(0)
        val effects = RecordInputJson.decode(t09).calculate()
        assertEquals(listOf(Money(-3000, 2, "CNY"), Money(-450, 2, "USD")), effects.postings.map { it.amount })
        assertEquals(Money(9000, 2, "CNY"), effects.statistics.single().amount)
        assertEquals(Money(9000, 2, "CNY"), effects.budget.single())
        val invalidCases = fixture.getJSONArray("invalidCases").let { cases ->
            (0 until cases.length()).map(cases::getJSONObject).filter { it.getString("id").startsWith("XFX") }
        }
        assertEquals(4, invalidCases.size)
        for (invalid in invalidCases) {
            val error = assertThrows(RecordException::class.java) { RecordInputJson.decode(invalid.getJSONObject("record")).calculate() }
            assertEquals(invalid.getString("expectedError"), error.code)
        }
    }

    @Test
    fun rejectsUnknownFieldsAtEveryInputObject() {
        for (kind in listOf("EXPENSE", "INCOME", "TRANSFER")) {
            for (field in listOf("balance", "postings", "ownerId", "unexpected")) {
                assertThrows(RecordException::class.java) { RecordInputJson.decode(record(kind).put(field, "client controlled")) }
            }
        }
        val mutations: List<(JSONObject) -> Unit> =
            listOf(
                { it.getJSONObject("gross").put("balance", "1") },
                { it.getJSONArray("allocations").getJSONObject(0).put("ownerId", "client controlled") },
                {
                    it
                        .getJSONArray("allocations")
                        .getJSONObject(0)
                        .getJSONObject("amount")
                        .put("balance", "1")
                },
                { it.put("discount", JSONObject(it.getJSONObject("gross").toString()).put("extra", true)) },
            )
        for (mutate in mutations) {
            val input = record()
            mutate(input)
            assertThrows(RecordException::class.java) { RecordInputJson.decode(input) }
        }
        for (name in listOf("from", "to", "fee", "bonus")) {
            for (moneyField in listOf(false, true)) {
                val input = record("TRANSFER")
                val nested = input.getJSONObject(name)
                (if (moneyField) nested.getJSONObject("amount") else nested).put("extra", true)
                assertThrows(RecordException::class.java) { RecordInputJson.decode(input) }
            }
        }
    }

    @Test
    fun rejectsCoercedTypesAndNullRequiredFields() {
        val mutations: List<(JSONObject) -> Unit> =
            listOf(
                { it.put("kind", "expense") },
                { it.put("kind", JSONObject.NULL) },
                { it.put("gross", JSONObject.NULL) },
                { it.put("gross", "100") },
                { it.put("allocations", JSONObject.NULL) },
                { it.put("allocations", JSONObject()) },
                { it.put("allocations", JSONArray().put(JSONObject.NULL)) },
                { it.put("countInStatistics", "true") },
                { it.put("countInBudget", 1) },
                { it.getJSONObject("gross").put("unscaled", 100) },
                { it.getJSONObject("gross").put("unscaled", JSONObject.NULL) },
                { it.getJSONObject("gross").put("scale", "2") },
                { it.getJSONObject("gross").put("scale", 2.0) },
                { it.getJSONObject("gross").put("scale", -1) },
                { it.getJSONObject("gross").put("scale", 256) },
                { it.getJSONObject("gross").put("currency", 1) },
                { it.put("discount", false) },
                { it.getJSONArray("allocations").getJSONObject(0).put("accountId", JSONObject.NULL) },
                { it.getJSONArray("allocations").getJSONObject(0).put("amount", JSONObject.NULL) },
            )
        for ((index, mutate) in mutations.withIndex()) {
            val input = record()
            mutate(input)
            assertThrows("mutation $index", RecordException::class.java) { RecordInputJson.decode(input) }
        }
        for ((kind, fields) in listOf(
            "EXPENSE" to listOf("kind", "gross", "allocations", "countInStatistics", "countInBudget"),
            "INCOME" to listOf("amount", "allocations", "countInStatistics"),
            "TRANSFER" to listOf("from", "to"),
        )) {
            for (field in fields) {
                val input = record(kind).apply { remove(field) }
                assertThrows("$kind missing $field", RecordException::class.java) { RecordInputJson.decode(input) }
            }
        }
    }

    @Test
    fun moneyWireErrorsRetainExactBoundaries() {
        for ((field, value, expected) in listOf(
            Triple("unscaled", "1.5", "INVALID_MONEY"),
            Triple("unscaled", "1e3", "INVALID_MONEY"),
            Triple("unscaled", "+1", "INVALID_MONEY"),
            Triple("unscaled", " 1", "INVALID_MONEY"),
            Triple("unscaled", "-", "INVALID_MONEY"),
            Triple("unscaled", "9223372036854775808", "AMOUNT_OVERFLOW"),
            Triple("unscaled", "-9223372036854775809", "AMOUNT_OVERFLOW"),
            Triple("scale", 7, "INVALID_MONEY"),
            Triple("currency", "XYZ", "INVALID_MONEY"),
            Triple("currency", "cny", "INVALID_MONEY"),
        )) {
            val input = record()
            input.getJSONObject("gross").put(field, value)
            assertEquals(expected, assertThrows(MoneyException::class.java) { RecordInputJson.decode(input) }.code)
        }
        for (value in listOf(Long.MIN_VALUE, -1, 0, 1, Long.MAX_VALUE)) {
            val input = record("INCOME")
            input.getJSONObject("amount").put("unscaled", value.toString()).put("scale", 6)
            val decoded = RecordInputJson.decode(input) as RecordInput.Income
            assertEquals(Money(value, 6, "CNY"), decoded.amount)
        }
        val context = InstrumentationRegistry.getInstrumentation().context
        val currencies =
            context.assets
                .open("iso-4217-currencies.txt")
                .bufferedReader()
                .use { it.readText().trim().split(Regex("\\s+")) }
        assertEquals(178, currencies.size)
        for (currency in currencies) {
            val input = record("INCOME").put("allocations", JSONArray())
            input.getJSONObject("amount").put("currency", currency)
            assertEquals(
                currency,
                RecordInputJson
                    .decode(input)
                    .calculate()
                    .statistics
                    .single()
                    .amount.currency,
            )
        }
    }

    @Test
    fun optionalFieldsAndUuidReferencesMatchRust() {
        val expense = record()
        val withoutDiscount = JSONObject(expense.toString()).apply { remove("discount") }
        assertEquals(RecordInputJson.decode(expense), RecordInputJson.decode(withoutDiscount))
        val transfer = record("TRANSFER").put("fee", JSONObject.NULL).put("bonus", JSONObject.NULL)
        val withoutExtras =
            JSONObject(transfer.toString()).apply {
                remove("fee")
                remove("bonus")
            }
        assertEquals(RecordInputJson.decode(transfer), RecordInputJson.decode(withoutExtras))
        for (id in listOf(
            "00000000-0000-0000-0000-000000000000",
            "00000000000000000000000000000000",
            "{00000000-0000-0000-0000-000000000000}",
            "urn:uuid:00000000-0000-0000-0000-000000000000",
        )) {
            val input = record("INCOME")
            input.getJSONArray("allocations").getJSONObject(0).put("accountId", id)
            assertEquals(
                UUID(0, 0),
                RecordInputJson
                    .decode(input)
                    .calculate()
                    .postings
                    .single()
                    .accountId,
            )
        }
        for (id in listOf(
            "1-1-1-1-1", "not-a-uuid", "", " 00000000-0000-0000-0000-000000000001",
            "{00000000000000000000000000000000}", "urn:uuid:00000000000000000000000000000000",
        )) {
            val input = record("INCOME")
            input.getJSONArray("allocations").getJSONObject(0).put("accountId", id)
            assertThrows(RecordException::class.java) { RecordInputJson.decode(input) }
        }
    }
}
