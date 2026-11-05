package dev.icos.app.domain

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RefundRecoveryTest {
    private fun fixtures(): JSONObject =
        InstrumentationRegistry.getInstrumentation().context.assets.open("refund-recovery-cases.json").bufferedReader().use {
            JSONObject(it.readText())
        }

    private fun money(value: JSONObject): Money =
        Money.fromWire(value.getString("unscaled"), value.getInt("scale"), value.getString("currency"))

    private fun refunds(values: JSONArray): List<RefundReceipt> =
        (0 until values.length()).map { index ->
            val value = values.getJSONObject(index)
            RefundReceipt(UUID.fromString(value.getString("id")), money(value.getJSONObject("amount")), value.getBoolean("cancelled"))
        }

    private fun assertMoney(
        id: String,
        actual: Money,
        expected: Money,
    ) {
        assertTrue("$id: actual $actual, expected $expected", actual.sameValue(expected))
    }

    @Test
    fun sharedRefundCasesCapReductionsAndReclassifyAfterCancellation() {
        val fixture = fixtures()
        assertEquals(1, fixture.getInt("schemaVersion"))
        assertEquals("D-refund-recovery-v1", fixture.getString("policy"))
        val cases = fixture.getJSONArray("cases")
        assertTrue(cases.length() > 0)
        val ids = mutableSetOf<String>()
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val id = case.getString("id")
            assertTrue(id, ids.add(id))
            val original = money(case.getJSONObject("original"))
            val receipts = refunds(case.getJSONArray("refunds"))
            val actual = calculateRefundRecovery(original, receipts)
            val expected = case.getJSONObject("expected")
            for ((key, value) in listOf(
                "refunded" to actual.refunded,
                "netExpense" to actual.netExpense,
                "excessAmount" to actual.excessAmount,
            )) {
                assertMoney(id, value, money(expected.getJSONObject(key)))
            }
            val effects = expected.getJSONArray("effects")
            assertEquals(id, effects.length(), actual.effects.size)
            var reductions = original.copy(unscaled = 0)
            var excess = original.copy(unscaled = 0)
            actual.effects.forEachIndexed { effectIndex, effect ->
                val expectedEffect = effects.getJSONObject(effectIndex)
                assertEquals(id, expectedEffect.getString("refundId"), effect.refundId.toString())
                assertMoney(id, effect.expenseReduction, money(expectedEffect.getJSONObject("expenseReduction")))
                assertMoney(id, effect.excessAmount, money(expectedEffect.getJSONObject("excessAmount")))
                val receipt = receipts.single { it.id == effect.refundId }
                assertTrue(id, !receipt.cancelled)
                assertMoney(id, effect.expenseReduction.checkedAdd(effect.excessAmount), receipt.amount)
                reductions = reductions.checkedAdd(effect.expenseReduction)
                excess = excess.checkedAdd(effect.excessAmount)
            }
            assertMoney(id, reductions.checkedAdd(actual.netExpense), original)
            assertMoney(id, reductions.checkedAdd(excess), actual.refunded)
            assertMoney(id, excess, actual.excessAmount)
            assertTrue(id, actual.netExpense.unscaled >= 0 && actual.excessAmount.unscaled >= 0)
        }
    }

    @Test
    fun invalidRefundCasesReturnNoEffects() {
        val cases = fixtures().getJSONArray("invalidCases")
        assertTrue(cases.length() > 0)
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            var code: String? = null
            try {
                calculateRefundRecovery(money(case.getJSONObject("original")), refunds(case.getJSONArray("refunds")))
            } catch (error: MoneyException) {
                code = error.code
            } catch (error: RefundException) {
                code = error.code
            }
            assertEquals(case.getString("id"), case.getString("expectedError"), code)
        }
    }
}
