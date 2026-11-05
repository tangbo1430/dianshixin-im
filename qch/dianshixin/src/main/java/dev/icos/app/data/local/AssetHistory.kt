package dev.icos.app.data.local

import dev.icos.app.domain.Money
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

internal data class AssetHistoryAccount(
    val id: UUID,
    val ownerId: String,
    val epochId: String,
    val currency: String,
    val type: String,
    val includeInAssets: Boolean,
    val archivedAt: Long?,
)

internal data class AssetHistoryPosting(
    val accountId: UUID,
    val ownerId: String,
    val epochId: String,
    val occurredAtMillis: Long,
    val amount: Money,
)

internal data class AssetHistoryPoint(
    val period: String,
    val endExclusiveMillis: Long,
    val assets: Map<String, Money>,
    val liabilities: Map<String, Money>,
    val netWorth: Map<String, Money>,
)

/** Rebuilds each month-end balance from dated postings; all sums stay in checked exact Money. */
internal object AssetHistoryCalculator {
    private val periodFormatter = DateTimeFormatter.ofPattern("uuuu-MM")

    fun monthly(
        accounts: List<AssetHistoryAccount>,
        postings: List<AssetHistoryPosting>,
        ownerId: String,
        epochId: String,
        zoneId: String,
        asOfMillis: Long,
        months: Int = 12,
        includeFuture: Boolean = false,
    ): List<AssetHistoryPoint> {
        require(months in 1..60)
        val zone = ZoneId.of(zoneId)
        val currentMonth = YearMonth.from(Instant.ofEpochMilli(asOfMillis).atZone(zone))
        val startMonth = currentMonth.minusMonths((months - 1).toLong())
        val eligibleAccounts = accounts.filter {
            it.ownerId == ownerId && it.epochId == epochId && it.type in setOf("ASSET", "CASH", "BANK", "LIABILITY") &&
                (it.type == "LIABILITY" || it.includeInAssets)
        }
        val accountById = eligibleAccounts.associateBy { it.id }
        val validPostings = postings.filter { posting ->
            val account = accountById[posting.accountId]
            account != null && posting.ownerId == ownerId && posting.epochId == epochId &&
                posting.amount.currency == account.currency &&
                (includeFuture || posting.occurredAtMillis <= asOfMillis)
        }
        return (0 until months).map { index ->
            val month = startMonth.plusMonths(index.toLong())
            val end = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val cutOff = if (includeFuture) end else minOf(end, asOfMillis + 1)
            val balances = HashMap<Pair<String, String>, Money>()
            eligibleAccounts.forEach { account ->
                val key = (if (account.type == "LIABILITY") "LIABILITY" else "ASSET") to account.currency
                balances.putIfAbsent(key, Money(0, 0, account.currency))
            }
            validPostings.asSequence().filter { it.occurredAtMillis < cutOff }.forEach { posting ->
                val account = accountById.getValue(posting.accountId)
                val key = (if (account.type == "LIABILITY") "LIABILITY" else "ASSET") to account.currency
                balances[key] = balances.getValue(key).checkedAdd(posting.amount)
            }
            val assetValues = balances.filterKeys { it.first == "ASSET" }.mapKeys { it.key.second }
            val liabilityValues = balances.filterKeys { it.first == "LIABILITY" }.mapKeys { it.key.second }
            val currencies = (assetValues.keys + liabilityValues.keys).toSortedSet()
            val net = currencies.associateWith { currency ->
                (assetValues[currency] ?: Money(0, 0, currency)).checkedSubtract(
                    liabilityValues[currency] ?: Money(0, 0, currency),
                )
            }
            AssetHistoryPoint(month.format(periodFormatter), end, assetValues, liabilityValues, net)
        }
    }
}
