package dev.icos.app.data.local

import dev.icos.app.domain.Money
import dev.icos.app.domain.MoneyException
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

class AssetHistoryTest {
    private val owner = UUID.randomUUID().toString()
    private val epoch = UUID.randomUUID().toString()
    private val zone = "America/Los_Angeles"
    private fun at(day: String) = LocalDate.parse(day).atStartOfDay(ZoneId.of(zone)).toInstant().toEpochMilli()

    @Test
    fun monthlyBalancesUseHistoricalPostingsReversalsAndEndExclusiveCutoffs() {
        try {
            Money(Long.MIN_VALUE, 0, "USD").checkedNegate()
            throw AssertionError("liability sign conversion overflow must be rejected")
        } catch (expected: MoneyException) {
            assertEquals("AMOUNT_OVERFLOW", expected.code)
        }
        val cash = UUID.randomUUID()
        val debt = UUID.randomUUID()
        val accounts = listOf(
            AssetHistoryAccount(cash, owner, epoch, "USD", "CASH", true, null),
            AssetHistoryAccount(debt, owner, epoch, "USD", "LIABILITY", true, at("2025-01-15")),
        )
        val postings = listOf(
            AssetHistoryPosting(cash, owner, epoch, at("2025-01-01"), Money(10000, 2, "USD")),
            AssetHistoryPosting(cash, owner, epoch, at("2025-01-20"), Money(-2000, 2, "USD")), // persisted reversal
            AssetHistoryPosting(cash, owner, epoch, at("2025-02-10"), Money(-1000, 2, "USD")),
            AssetHistoryPosting(cash, owner, epoch, at("2025-02-20"), Money(-500, 2, "USD")), // future at as-of
            AssetHistoryPosting(debt, owner, epoch, at("2025-01-01"), Money(5000, 2, "USD")),
            AssetHistoryPosting(debt, owner, epoch, at("2025-02-10"), Money(-1000, 2, "USD")),
            AssetHistoryPosting(cash, "other-owner", epoch, at("2025-02-10"), Money(99999, 2, "USD")),
            AssetHistoryPosting(cash, owner, "other-epoch", at("2025-02-10"), Money(99999, 2, "USD")),
        )
        val asOf = at("2025-02-15")
        val points = AssetHistoryCalculator.monthly(accounts, postings, owner, epoch, zone, asOf, months = 2)
        assertEquals("2025-01", points[0].period)
        assertEquals(Money(8000, 2, "USD"), points[0].assets["USD"])
        assertEquals(Money(5000, 2, "USD"), points[0].liabilities["USD"])
        assertEquals(Money(3000, 2, "USD"), points[0].netWorth["USD"])
        assertEquals(Money(7000, 2, "USD"), points[1].assets["USD"])
        assertEquals(Money(4000, 2, "USD"), points[1].liabilities["USD"])
        assertEquals(Money(3000, 2, "USD"), points[1].netWorth["USD"])
        assertEquals(at("2025-02-01"), points[0].endExclusiveMillis)
    }

    @Test
    fun futureSwitchOnlyAffectsCurrentHistoricalWindowAndAssetsRespectInclusionFlag() {
        val included = UUID.randomUUID()
        val excluded = UUID.randomUUID()
        val accounts = listOf(
            AssetHistoryAccount(included, owner, epoch, "EUR", "BANK", true, null),
            AssetHistoryAccount(excluded, owner, epoch, "EUR", "ASSET", false, null),
        )
        val postings = listOf(
            AssetHistoryPosting(included, owner, epoch, at("2025-02-20"), Money(100, 2, "EUR")),
            AssetHistoryPosting(excluded, owner, epoch, at("2025-02-10"), Money(99900, 2, "EUR")),
        )
        val default = AssetHistoryCalculator.monthly(accounts, postings, owner, epoch, zone, at("2025-02-15"), 2)
        val withFuture = AssetHistoryCalculator.monthly(accounts, postings, owner, epoch, zone, at("2025-02-15"), 2, true)
        assertEquals(Money(0, 0, "EUR"), default.last().assets["EUR"])
        assertEquals(Money(100, 2, "EUR"), withFuture.last().assets["EUR"])
        assertEquals(Money(0, 0, "EUR"), withFuture.first().assets["EUR"])
    }
}
