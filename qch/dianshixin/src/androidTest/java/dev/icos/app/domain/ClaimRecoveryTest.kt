package dev.icos.app.domain

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ClaimRecoveryTest {
    private fun fixtures(): JSONObject =
        InstrumentationRegistry.getInstrumentation().context.assets.open("claim-recovery-cases.json").bufferedReader().use {
            JSONObject(it.readText())
        }

    private fun money(value: JSONObject): Money =
        Money.fromWire(value.getString("unscaled"), value.getInt("scale"), value.getString("currency"))

    private fun receipts(values: JSONArray): List<ClaimReceipt> =
        (0 until values.length()).map { index ->
            val value = values.getJSONObject(index)
            ClaimReceipt(
                UUID.fromString(value.getString("id")),
                money(value.getJSONObject("actualAmount")),
                RemainderAction.valueOf(value.getString("remainderAction")),
                value.getBoolean("cancelled"),
            )
        }

    private fun assertMoney(
        id: String,
        actual: Money,
        expected: Money,
    ) {
        assertTrue("$id: actual $actual, expected $expected", actual.sameValue(expected))
    }

    @Test
    fun sharedClaimCasesConserveMoneyAndReclassifySurvivingReceipts() {
        val fixture = fixtures()
        assertEquals(1, fixture.getInt("schemaVersion"))
        assertEquals("D-claim-recovery-v1", fixture.getString("policy"))
        for (key in listOf("specification", "acceptance", "ordering")) assertFalse(fixture.getString(key).isEmpty())
        val cases = fixture.getJSONArray("cases")
        assertTrue(cases.length() > 0)
        val ids = mutableSetOf<String>()
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val id = case.getString("id")
            assertTrue(id, ids.add(id))
            assertFalse(case.getString("description").isEmpty())
            val original = money(case.getJSONObject("original"))
            val actual = calculateClaimRecovery(original, receipts(case.getJSONArray("receipts")))
            val expected = case.getJSONObject("expected")
            assertEquals(id, expected.getString("status"), actual.status.name)
            for ((key, value) in listOf(
                "received" to actual.received,
                "netExpense" to actual.netExpense,
                "excessIncome" to actual.excessIncome,
                "remainingEligible" to actual.remainingEligible,
            )) {
                assertMoney(id, value, money(expected.getJSONObject(key)))
            }
            val effects = expected.getJSONArray("effects")
            assertEquals(id, effects.length(), actual.effects.size)
            var processed = original.copy(unscaled = 0)
            var reductions = original.copy(unscaled = 0)
            var income = original.copy(unscaled = 0)
            actual.effects.forEachIndexed { effectIndex, effect ->
                val expectedEffect = effects.getJSONObject(effectIndex)
                assertEquals(id, expectedEffect.getString("receiptId"), effect.receiptId.toString())
                for ((key, value) in listOf(
                    "processedPrincipal" to effect.processedPrincipal,
                    "expenseReduction" to effect.expenseReduction,
                    "excessIncome" to effect.excessIncome,
                    "closedUnreimbursed" to effect.closedUnreimbursed,
                )) {
                    assertMoney(id, value, money(expectedEffect.getJSONObject(key)))
                }
                assertMoney(id, effect.expenseReduction.checkedAdd(effect.closedUnreimbursed), effect.processedPrincipal)
                processed = processed.checkedAdd(effect.processedPrincipal)
                reductions = reductions.checkedAdd(effect.expenseReduction)
                income = income.checkedAdd(effect.excessIncome)
            }
            assertMoney(id, processed.checkedAdd(actual.remainingEligible), original)
            assertMoney(id, reductions.checkedAdd(actual.netExpense), original)
            assertMoney(id, reductions.checkedAdd(income), actual.received)
        }
    }

    @Test
    fun invalidClaimCasesReturnNoEffects() {
        val cases = fixtures().getJSONArray("invalidCases")
        assertTrue(cases.length() > 0)
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val id = case.getString("id")
            var code: String? = null
            try {
                calculateClaimRecovery(money(case.getJSONObject("original")), receipts(case.getJSONArray("receipts")))
            } catch (error: MoneyException) {
                code = error.code
            } catch (error: ClaimException) {
                code = error.code
            }
            assertEquals(id, case.getString("expectedError"), code)
        }
    }
}
