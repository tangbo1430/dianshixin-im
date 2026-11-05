package dev.icos.app.ui

import dev.icos.app.domain.Direction
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Test

class StatisticsChartTest {
    @Test
    fun ratiosKeepSignedZeroAndCurrencyGroupsIndependent() {
        val values =
            listOf(
                statistic("usd-negative", Direction.EXPENSE, "USD", "-4"),
                statistic("usd-positive", Direction.EXPENSE, "USD", "2"),
                statistic("usd-zero", Direction.EXPENSE, "USD", "0"),
                statistic("cny-positive", Direction.EXPENSE, "CNY", "100"),
                statistic("income-positive", Direction.INCOME, "USD", "10"),
            )

        val ratios = statisticsBarRatios(values)

        assertEquals(1f, ratios.getValue("usd-negative"), 0f)
        assertEquals(0.5f, ratios.getValue("usd-positive"), 0.0001f)
        assertEquals(0f, ratios.getValue("usd-zero"), 0f)
        assertEquals(1f, ratios.getValue("cny-positive"), 0f)
        assertEquals(1f, ratios.getValue("income-positive"), 0f)
    }

    @Test
    fun emptyAndAllZeroGroupsHaveNoVisibleWidth() {
        assertEquals(emptyMap<String, Float>(), statisticsBarRatios(emptyList()))

        val zeroValues =
            listOf(
                statistic("usd-zero-a", Direction.EXPENSE, "USD", "0"),
                statistic("usd-zero-b", Direction.EXPENSE, "USD", "0"),
            )

        assertEquals(mapOf("usd-zero-a" to 0f, "usd-zero-b" to 0f), statisticsBarRatios(zeroValues))
    }

    private fun statistic(key: String, direction: Direction, currency: String, amount: String) =
        CategoryStatistic(
            key = key,
            categoryId = null,
            name = key,
            direction = direction,
            currency = currency,
            amount = BigDecimal(amount),
            percent = null,
            entries = emptyList(),
        )
}
