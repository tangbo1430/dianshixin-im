package dev.icos.app.domain

import androidx.test.platform.app.InstrumentationRegistry
import dev.icos.app.data.remote.RecordInputJson
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.util.UUID

@RunWith(Parameterized::class)
class FinancialCasesTest(
    private val id: String,
    private val case: JSONObject,
    private val invalid: Boolean,
    private val opening: JSONArray,
) {
    @Test
    fun sharedFinancialCase() {
        if (invalid) {
            val error = assertThrows(IllegalArgumentException::class.java) {
                RecordInputJson.decode(case.getJSONObject("record")).calculate()
            }
            val code =
                when (error) {
                    is MoneyException -> error.code
                    is RecordException -> error.code
                    else -> throw error
                }
            assertEquals(id, case.getString("expectedError"), code)
            return
        }
        assertTrue(case.getString("description").isNotEmpty())
        val balances =
            opening
                .objects()
                .map(::accountAmount)
                .associate { it.accountId to it.amount }
                .toMutableMap()
        val statistics = mutableListOf<StatContribution>()
        val budget = mutableListOf<Money>()
        for (record in case.getJSONArray("records").objects()) {
            val effects = RecordInputJson.decode(record).calculate()
            for (posting in effects.postings) {
                balances[posting.accountId] = balances.getValue(posting.accountId).checkedAdd(posting.amount)
            }
            statistics += effects.statistics
            budget += effects.budget
        }
        assertEquals(
            id,
            case
                .getJSONArray("expectedBalances")
                .objects()
                .map(::accountAmount)
                .associate { it.accountId to it.amount },
            balances,
        )
        assertEquals(
            id,
            case.getJSONArray("expectedStatistics").objects().map {
                StatContribution(Direction.valueOf(it.getString("direction")), money(it.getJSONObject("amount")))
            },
            statistics,
        )
        assertEquals(id, case.getJSONArray("expectedBudget").objects().map(::money), budget)
    }

    private fun money(value: JSONObject) = Money.fromWire(value.getString("unscaled"), value.getInt("scale"), value.getString("currency"))

    private fun accountAmount(value: JSONObject) =
        AccountAmount(UUID.fromString(value.getString("accountId")), money(value.getJSONObject("amount")))

    companion object {
        private fun JSONArray.objects(): List<JSONObject> = List(length()) { getJSONObject(it) }

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun fixtures(): List<Array<Any>> {
            val context = InstrumentationRegistry.getInstrumentation().context
            val fixture =
                JSONObject(
                    context.assets
                        .open("financial-cases.json")
                        .bufferedReader()
                        .use { it.readText() },
                )
            assertEquals(1, fixture.getInt("schemaVersion"))
            assertEquals("D-basic-ledger-v1", fixture.getString("policy"))
            assertTrue(fixture.getString("specification").isNotEmpty())
            assertTrue(fixture.getString("acceptance").isNotEmpty())
            val valid = fixture.getJSONArray("cases").objects()
            val invalid = fixture.getJSONArray("invalidCases").objects()
            assertEquals(15, valid.size)
            assertEquals(19, invalid.size)
            val ids = (valid + invalid).map { it.getString("id") }
            assertEquals(ids.size, ids.toSet().size)
            val opening = fixture.getJSONArray("openingBalances")
            return valid.map { arrayOf(it.getString("id"), it, false, opening) } +
                invalid.map { arrayOf(it.getString("id"), it, true, opening) }
        }
    }
}
