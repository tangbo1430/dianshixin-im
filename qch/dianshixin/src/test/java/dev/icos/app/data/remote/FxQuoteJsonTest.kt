package dev.icos.app.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FxQuoteJsonTest {
    private fun response(base: String = "USD", rate: String = "0.000000000000000001", stale: Boolean = false) =
        """{"data":{"base":"$base","rates":{"CNY":"$rate"},"asOf":"2026-10-19T12:00:00Z","provider":"controlled-adapter","stale":$stale},"requestId":"00000000-0000-0000-0000-000000000001"}""".toByteArray()

    @Test
    fun decodesSourceBaseQuoteWithoutInvertingPrecision() {
        val snapshot = FxQuoteJson.parse(response(), "USD", setOf("CNY"))
        assertEquals("USD", snapshot.base)
        assertEquals("0.000000000000000001", snapshot.rates["CNY"])
        assertEquals("controlled-adapter", snapshot.provider)
        assertEquals(false, snapshot.stale)
    }

    @Test
    fun rejectsWrongBaseUnexpectedSymbolsDuplicatesAndOversizedPayload() {
        assertThrows(IllegalArgumentException::class.java) { FxQuoteJson.parse(response(), "CNY", setOf("CNY")) }
        val extra = response().decodeToString().replace("\"CNY\":\"0.000000000000000001\"", "\"EUR\":\"1\"").toByteArray()
        assertThrows(IllegalArgumentException::class.java) { FxQuoteJson.parse(extra, "USD", setOf("CNY")) }
        val duplicate = """{"data":{"base":"USD","rates":{"CNY":"7","CNY":"8"},"asOf":"2026-10-19T12:00:00Z","provider":"adapter","stale":false},"requestId":"00000000-0000-0000-0000-000000000001"}""".toByteArray()
        assertThrows(IllegalArgumentException::class.java) { FxQuoteJson.parse(duplicate, "USD", setOf("CNY")) }
        assertThrows(IllegalArgumentException::class.java) { FxQuoteJson.parse(ByteArray(FxQuoteJson.MAX_BYTES + 1), "USD", setOf("CNY")) }
        listOf("0", "-1", "1e2", "0.0000000000000000001", "1000000000001").forEach { rate ->
            assertThrows(IllegalArgumentException::class.java) { FxQuoteJson.parse(response(rate = rate), "USD", setOf("CNY")) }
        }
    }

    @Test
    fun acceptsMissingRequestedRateAsMissingRatherThanZero() {
        val missing = """{"data":{"base":"USD","rates":{},"asOf":"2026-10-19T12:00:00Z","provider":"adapter","stale":false},"requestId":"00000000-0000-0000-0000-000000000001"}""".toByteArray()
        val snapshot = FxQuoteJson.parse(missing, "USD", setOf("CNY"))
        assertEquals(emptyMap<String, String>(), snapshot.rates)
    }
}
