package dev.icos.app.data.local

import dev.icos.app.domain.Direction
import dev.icos.app.domain.Money
import dev.icos.app.ui.categoryStatistics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

class LoanStatisticProjectionTest {
    private val owner = id(1)
    private val epoch = id(2)
    private val ledger = id(3)

    @Test
    fun loanInterestInSummaryHasMatchingUnclassifiedCategoryAndOriginalLink() {
        val event = event(id(10), id(11), owner, epoch, at = 100)
        val snapshot =
            FinanceSnapshot(
                ownerId = owner,
                epochId = epoch,
                ledgerId = ledger,
                accounts = emptyList(),
                categories = emptyList(),
                records = emptyList(),
                summaries = listOf(FinanceSummary("CNY", money("0.00"), money("2.00"), money("0.00"))),
                loanContributions =
                    listOf(
                        contribution("REPAYMENT_PRINCIPAL", event.id, money("33.33"), null, false, at = 100),
                        contribution("REPAYMENT_INTEREST", event.id, money("2.00"), Direction.EXPENSE, true, at = 100),
                    ),
            )

        val entries = project(snapshot.loanContributions, listOf(event), listOf(record(UUID.fromString(event.recordId!!))))
        val categories = categoryStatistics(entries, snapshot.categories)
        val expense = categories.filter { it.direction == Direction.EXPENSE && it.currency == "CNY" }
            .fold(BigDecimal.ZERO) { total, category -> total.add(category.amount) }

        assertEquals(snapshot.summaries.single().expense, money(expense))
        assertEquals(1, categories.size)
        assertNull(categories.single().categoryId)
        assertEquals("未分类", categories.single().name)
        assertEquals(listOf(money("2.00")), categories.single().entries.map { it.amount })
        assertEquals(UUID.fromString(event.id), categories.single().entries.single().eventId)
        assertEquals(UUID.fromString(event.recordId), categories.single().entries.single().recordId)
        assertEquals(event.timezone, categories.single().entries.single().timezone)
        assertEquals(event.remark, categories.single().entries.single().remark)
        assertEquals(100L, categories.single().entries.single().occurredAtMillis)
    }

    @Test
    fun independentPreferencesKeepPrincipalInterestIncomeAndCurrencyGroupsSeparate() {
        val payment = event(id(20), id(21), owner, epoch, at = 200)
        val draw = event(id(22), id(23), owner, epoch, at = 150)
        val usdPayment = event(id(24), id(25), owner, epoch, at = 250)
        val sources = listOf(payment, draw, usdPayment)
        val records = sources.map { record(UUID.fromString(it.recordId!!)) }

        for (principalEnabled in listOf(false, true)) {
            for (interestEnabled in listOf(false, true)) {
                val contributions =
                    listOf(
                        contribution(
                            "REPAYMENT_PRINCIPAL",
                            payment.id,
                            money("33.33"),
                            if (principalEnabled) Direction.EXPENSE else null,
                            principalEnabled,
                            at = 200,
                        ),
                        contribution(
                            "REPAYMENT_INTEREST",
                            payment.id,
                            money("2.00"),
                            if (interestEnabled) Direction.EXPENSE else null,
                            interestEnabled,
                            at = 200,
                        ),
                    )
                val entries = project(contributions, sources, records)
                val expense = categoryStatistics(entries, emptyList())
                    .filter { it.direction == Direction.EXPENSE && it.currency == "CNY" }
                    .fold(BigDecimal.ZERO) { total, category -> total.add(category.amount) }
                val expected = (if (principalEnabled) money("33.33") else money("0.00"))
                    .checkedAdd(if (interestEnabled) money("2.00") else money("0.00"))

                assertEquals(expected, money(expense))
            }
        }

        val entries =
            project(
                listOf(
                    contribution("PRINCIPAL_DRAW", draw.id, money("100.00"), Direction.INCOME, true, at = 150),
                    contribution("REPAYMENT_PRINCIPAL", payment.id, money("33.33"), Direction.EXPENSE, true, at = 200),
                    contribution("REPAYMENT_INTEREST", payment.id, money("2.00"), Direction.EXPENSE, true, at = 200),
                    contribution("REPAYMENT_INTEREST", usdPayment.id, money("7.00", "USD"), Direction.EXPENSE, true, at = 250),
                ),
                sources,
                records,
            )
        val categories = categoryStatistics(entries, emptyList())

        assertEquals(
            listOf(
                Triple(Direction.INCOME, "CNY", BigDecimal("100.00")),
                Triple(Direction.EXPENSE, "CNY", BigDecimal("35.33")),
                Triple(Direction.EXPENSE, "USD", BigDecimal("7.00")),
            ),
            categories.map { Triple(it.direction, it.currency, it.amount) },
        )
        assertTrue(categories.all { it.categoryId == null && it.name == "未分类" })
        assertEquals(2, categories.single { it.direction == Direction.EXPENSE && it.currency == "CNY" }.entries.size)
    }

    @Test
    fun projectionSkipsIneligibleZeroMissingCrossScopeAndReversedSources() {
        val valid = event(id(30), id(31), owner, epoch, at = 300)
        val wrongOwner = event(id(32), id(33), id(4), epoch, at = 300)
        val wrongEpoch = event(id(34), id(35), owner, id(5), at = 300)
        val reversed = event(id(36), id(37), owner, epoch, at = 300)
        val reversal = event(id(38), id(39), owner, epoch, at = 301).copy(reversalOf = reversed.id)
        val missingRecord = event(id(40), null, owner, epoch, at = 300)
        val sources = listOf(valid, wrongOwner, wrongEpoch, reversed, reversal, missingRecord)
        val records = listOf(
            record(UUID.fromString(valid.recordId!!)),
            record(UUID.fromString(wrongOwner.recordId!!), id(4), epoch),
            record(UUID.fromString(wrongEpoch.recordId!!), owner, id(5)),
            record(UUID.fromString(reversed.recordId!!)),
            record(UUID.fromString(reversal.recordId!!)),
        )
        val contributions =
            listOf(
                contribution("REPAYMENT_INTEREST", valid.id, money("2.00"), Direction.EXPENSE, true, at = 300),
                contribution("REPAYMENT_INTEREST", valid.id, money("5.00"), null, false, at = 300),
                contribution("REPAYMENT_INTEREST", valid.id, money("0.00"), Direction.EXPENSE, true, at = 300),
                contribution("REPAYMENT_INTEREST", wrongOwner.id, money("2.00"), Direction.EXPENSE, true, at = 300),
                contribution("REPAYMENT_INTEREST", wrongEpoch.id, money("2.00"), Direction.EXPENSE, true, at = 300),
                contribution("REPAYMENT_INTEREST", reversed.id, money("2.00"), Direction.EXPENSE, true, at = 300),
                contribution("REPAYMENT_INTEREST", reversal.id, money("2.00"), Direction.EXPENSE, true, at = 301),
                contribution("REPAYMENT_INTEREST", missingRecord.id, money("2.00"), Direction.EXPENSE, true, at = 300),
                contribution("REPAYMENT_INTEREST", null, money("2.00"), Direction.EXPENSE, true, at = 300),
            )

        val projected = project(contributions, sources, records, setOf(reversed.id))

        assertEquals(listOf(money("2.00")), projected.map { it.amount })
        assertEquals(listOf(UUID.fromString(valid.id)), projected.map { it.eventId })
    }

    private fun project(
        contributions: List<FinanceLoanContribution>,
        events: List<EventEntity>,
        records: List<RecordEntity>,
        reversedEventIds: Set<String> = emptySet(),
    ) = projectLoanStatisticEntries(
        contributions,
        events.associateBy { it.id },
        records.associateBy { it.id },
        owner.toString(),
        epoch.toString(),
        reversedEventIds,
    )

    private fun contribution(
        kind: String,
        eventId: String?,
        amount: Money,
        direction: Direction?,
        eligible: Boolean,
        at: Long,
    ) = FinanceLoanContribution(
        planId = id(6),
        termIndex = if (kind == "PRINCIPAL_DRAW") null else 1,
        eventId = eventId?.let(UUID::fromString),
        kind = kind,
        amount = amount,
        statisticDirection = direction,
        statisticEligible = eligible,
        budgetAmount = Money(0, amount.scale, amount.currency),
        categoryId = null,
        occurredAtMillis = at,
    )

    private fun event(
        eventId: UUID,
        recordId: UUID?,
        eventOwner: UUID,
        eventEpoch: UUID,
        at: Long,
    ) = EventEntity(
        id = eventId.toString(),
        ownerId = eventOwner.toString(),
        epochId = eventEpoch.toString(),
        ledgerId = ledger.toString(),
        recordId = recordId?.toString(),
        localRevision = 1,
        kind = "EXPENSE",
        occurredAtMillis = at,
        timezone = "Asia/Shanghai",
        localDate = "2026-01-01",
        remark = "loan payment",
        categoryId = null,
        countInStatistics = false,
        countInBudget = false,
        amountUnscaled = null,
        amountScale = null,
        amountCurrency = null,
        discountUnscaled = null,
        discountScale = null,
        discountCurrency = null,
        reversalOf = null,
        createdAt = at,
        origin = "LOCAL",
    )

    private fun record(recordId: UUID, recordOwner: UUID = owner, recordEpoch: UUID = epoch) =
        RecordEntity(
            id = recordId.toString(),
            ownerId = recordOwner.toString(),
            epochId = recordEpoch.toString(),
            createdAt = 0,
            updatedAt = 0,
            localRevision = 1,
            deletedAt = null,
        )

    private fun money(value: String, currency: String = "CNY"): Money {
        val amount = BigDecimal(value)
        return Money(amount.movePointRight(amount.scale()).longValueExact(), amount.scale(), currency)
    }

    private fun money(value: BigDecimal): Money {
        val scaled = value.setScale(2, RoundingMode.UNNECESSARY)
        return Money(scaled.movePointRight(2).longValueExact(), 2, "CNY")
    }

    private fun id(value: Int) = UUID.fromString("00000000-0000-0000-0000-${value.toString().padStart(12, '0')}")
}
