package dev.icos.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class LoanTest {
    private val principal = Money(10000, 2, "CNY")

    @Test
    fun rateChangeKeepsPaidPrefixAndRecomputesRemainingPrincipal() {
        val fixture = requireNotNull(javaClass.classLoader?.getResourceAsStream("loan-posting-cases-v1.json"))
            .bufferedReader().use { it.readText() }
        val digest = MessageDigest.getInstance("SHA-256").digest(fixture.toByteArray())
            .joinToString("") { "%02x".format(it) }
        assertEquals("4452c6703a920ec1d2f6d4686e05a069d2c2e54a865cbcecdb9accaa12e41385", digest)
        val case = fixtureCase(fixture, "rate-change-after-actual-first-payment")
        val input = fixtureSection(case, "input")
        val expected = fixtureSection(case, "expected")
        val rates = Regex("\"annualRate\"\\s*:\\s*\\{\\s*\"numerator\"\\s*:\\s*(\\d+)\\s*,\\s*\"denominator\"\\s*:\\s*(\\d+)")
            .find(input)!!.groupValues
        val effectiveTerm = Regex("\"effectiveTerm\"\\s*:\\s*(\\d+)").find(input)!!.groupValues[1].toInt()
        val expectedInterest = Regex("\"interest\"\\s*:\\s*\\{\\s*\"unscaled\"\\s*:\\s*\"(\\d+)\"")
            .findAll(expected).map { it.groupValues[1].toLong() }.toList()
        val initial = LoanCalculator.create(
            LoanPlanInput(
                accountId = java.util.UUID.randomUUID(), principal = principal,
                annualRateNumerator = 0, annualRateDenominator = 1, termCount = 3,
                repaymentType = RepaymentMethod.EQUAL_PRINCIPAL, firstDueDate = "2026-01-31",
            ),
        ).terms
        val paid = initial.first().copy(dueState = LoanDueState.PAID)
        val source = listOf(paid) + initial.drop(1)
        val changed = LoanCalculator.changeRate(
            source, effectiveTerm, rates[1].toLong(), rates[2].toLong(), RepaymentMethod.EQUAL_PRINCIPAL, 2,
        )
        assertEquals(paid, changed.terms.first())
        assertEquals(expectedInterest, changed.terms.drop(effectiveTerm - 1).map { it.interest.unscaled })
        assertTrue(expected.contains("\"prefixUnchanged\": true"))
        assertTrue(expected.contains("\"remainingScheduledInterest\": {\n          \"unscaled\": \"100\""))
        assertEquals("2026-02-28", Regex("\"dueDate\"\\s*:\\s*\"([^\"]+)\"").find(fixtureSection(expected, "suffix"))!!.groupValues[1])
        assertEquals(Money(0, 2, "CNY"), changed.terms.last().closingPrincipal)
        assertTrue(fixture.contains("\"repaymentType\": \"INTEREST_ONLY\""))
        assertTrue(!fixture.contains("\"repaymentType\": \"INTEREST_FIRST\""))
    }

    private fun fixtureCase(json: String, id: String): String {
        val cases = json.indexOf("\"cases\"")
        val array = json.indexOf('[', cases)
        var pos = array + 1
        while (pos < json.length) {
            val start = json.indexOf('{', pos)
            if (start < 0) break
            val block = balancedObject(json, start)
            if (block.contains("\"id\": \"$id\"")) return block
            pos = start + block.length
        }
        error("Missing loan fixture case $id")
    }

    private fun fixtureSection(json: String, key: String): String {
        val field = json.indexOf("\"$key\"")
        require(field >= 0)
        val start = json.indexOf('{', field)
        return balancedObject(json, start)
    }

    private fun balancedObject(json: String, start: Int): String {
        var depth = 0
        var quoted = false
        var escaped = false
        for (index in start until json.length) {
            val char = json[index]
            if (quoted) {
                if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') quoted = false
            } else if (char == '"') quoted = true
            else if (char == '{') depth++
            else if (char == '}' && --depth == 0) return json.substring(start, index + 1)
        }
        error("Unclosed JSON object")
    }

    @Test
    fun fixedFutureAmountCreatesVisibleTailAndConservesPrincipal() {
        val initial = LoanCalculator.create(
            LoanPlanInput(
                accountId = java.util.UUID.randomUUID(), principal = principal,
                annualRateNumerator = 0, annualRateDenominator = 1, termCount = 3,
                repaymentType = RepaymentMethod.EQUAL_PRINCIPAL, firstDueDate = "2026-01-31",
            ),
        ).terms
        val preview = LoanCalculator.adjustTerm(
            initial, 1, Money(4000, 2, "CNY"), Money(0, 2, "CNY"),
            LoanFuturePolicy.FIXED_FUTURE_AMOUNT, 0, 1, 1,
        )
        assertEquals(listOf(4000L, 4000L, 2000L), preview.terms.map { it.principal.unscaled })
        assertEquals(10000L, preview.terms.sumOf { it.principal.unscaled })
        assertEquals(Money(0, 2, "CNY"), preview.terms.last().closingPrincipal)
    }

    @Test
    fun onlyThisTermRejectsPrincipalLeakWithoutChangingRemainingBalance() {
        val initial = LoanCalculator.create(
            LoanPlanInput(
                accountId = java.util.UUID.randomUUID(), principal = principal,
                annualRateNumerator = 0, annualRateDenominator = 1, termCount = 3,
                repaymentType = RepaymentMethod.EQUAL_PRINCIPAL, firstDueDate = "2026-01-31",
            ),
        ).terms
        assertThrows(IllegalArgumentException::class.java) {
            LoanCalculator.adjustTerm(
                initial, 2, Money(3000, 2, "CNY"), Money(500, 2, "CNY"),
                LoanFuturePolicy.ONLY_THIS_TERM, 0, 1, 1,
            )
        }
    }

    @Test
    fun fixedFutureAmountComparesMixedScalesAndKeepsMonthEndDates() {
        val initial = LoanCalculator.create(
            LoanPlanInput(
                accountId = java.util.UUID.randomUUID(), principal = Money(100, 0, "CNY"),
                annualRateNumerator = 0, annualRateDenominator = 1, termCount = 3,
                repaymentType = RepaymentMethod.EQUAL_PRINCIPAL, firstDueDate = "2026-01-31",
            ),
        ).terms
        val preview = LoanCalculator.adjustTerm(
            initial, 1, Money(40, 0, "CNY"), Money(0, 2, "CNY"),
            LoanFuturePolicy.FIXED_FUTURE_AMOUNT, 0, 1, 1,
        )

        assertEquals(listOf("2026-01-31", "2026-02-28", "2026-03-31"), preview.terms.map { it.dueDate })
        listOf(40L, 40L, 20L).forEachIndexed { index, amount ->
            assertTrue(preview.terms[index].principal.sameValue(Money(amount, 0, "CNY")))
        }
        assertTrue(preview.terms.last().closingPrincipal.sameValue(Money(0, 0, "CNY")))
        assertTrue(preview.terms.map { it.principal }.fold(Money(0, 0, "CNY"), Money::checkedAdd)
            .sameValue(Money(100, 0, "CNY")))
    }

    @Test
    fun loanDatesUseStrictProlepticGregorianAndAnchoredMonthEnds() {
        fun dates(firstDueDate: String, terms: Int) = LoanCalculator.create(
            LoanPlanInput(
                accountId = java.util.UUID.randomUUID(), principal = Money(terms.toLong(), 0, "CNY"),
                annualRateNumerator = 0, annualRateDenominator = 1, termCount = terms,
                repaymentType = RepaymentMethod.EQUAL_PRINCIPAL, firstDueDate = firstDueDate,
            ),
        ).terms.map { it.dueDate }

        assertEquals(
            listOf("2020-01-31", "2020-02-29", "2020-03-31"),
            dates("2020-01-31", 3),
        )
        assertEquals(
            listOf("2020-12-31", "2021-01-31", "2021-02-28"),
            dates("2020-12-31", 3),
        )
        assertTrue(!LoanCalculator.isValidLoanDate("1500-02-29"))
        assertTrue(LoanCalculator.isValidLoanDate("1600-02-29"))
        assertTrue(!LoanCalculator.isValidLoanDate("2024-13-01"))
        assertTrue(!LoanCalculator.isValidLoanDate("2024-04-31"))
    }

    @Test
    fun loanDateRangeMatchesCanonicalIsoExtendedYears() {
        assertEquals(listOf("-999999999-01-01"), LoanCalculator.create(
            LoanPlanInput(
                accountId = java.util.UUID.randomUUID(), principal = Money(1, 0, "CNY"),
                annualRateNumerator = 0, annualRateDenominator = 1, termCount = 1,
                repaymentType = RepaymentMethod.EQUAL_PRINCIPAL, firstDueDate = "-999999999-01-01",
            ),
        ).terms.map { it.dueDate })
        assertEquals(listOf("+999999999-12-31"), LoanCalculator.create(
            LoanPlanInput(
                accountId = java.util.UUID.randomUUID(), principal = Money(1, 0, "CNY"),
                annualRateNumerator = 0, annualRateDenominator = 1, termCount = 1,
                repaymentType = RepaymentMethod.EQUAL_PRINCIPAL, firstDueDate = "+999999999-12-31",
            ),
        ).terms.map { it.dueDate })
        assertTrue(LoanCalculator.isValidLoanDate("+10000-02-29"))
        assertTrue(!LoanCalculator.isValidLoanDate("+10000-02-30"))
    }

    @Test
    fun fixedFutureAmountPreservesScheduledPaymentAtHighValidRate() {
        val initial = LoanCalculator.create(
            LoanPlanInput(
                accountId = java.util.UUID.randomUUID(), principal = Money(200, 0, "CNY"),
                annualRateNumerator = 12, annualRateDenominator = 1, termCount = 3,
                repaymentType = RepaymentMethod.EQUAL_PRINCIPAL, firstDueDate = "2026-01-31",
            ),
        ).terms
        val preview = LoanCalculator.adjustTerm(
            initial, 1, Money(100, 0, "CNY"), Money(50, 0, "CNY"),
            LoanFuturePolicy.FIXED_FUTURE_AMOUNT, 12, 1, 1,
        )

        assertEquals(listOf(100L, 50L, 50L), preview.terms.map { it.principal.unscaled })
        assertEquals(listOf(50L, 100L, 50L), preview.terms.map { it.interest.unscaled })
        assertEquals(listOf(150L, 150L, 100L), preview.terms.map { it.amount.unscaled })
        assertTrue(preview.terms.last().closingPrincipal.sameValue(Money(0, 0, "CNY")))
    }

    @Test
    fun fixedFutureAmountShowsZeroDueRowsAfterVisibleEarlyPayoff() {
        val initial = LoanCalculator.create(
            LoanPlanInput(
                accountId = java.util.UUID.randomUUID(), principal = Money(100, 0, "CNY"),
                annualRateNumerator = 0, annualRateDenominator = 1, termCount = 3,
                repaymentType = RepaymentMethod.EQUAL_PRINCIPAL, firstDueDate = "2026-01-31",
            ),
        ).terms
        val preview = LoanCalculator.adjustTerm(
            initial, 1, Money(80, 0, "CNY"), Money(0, 0, "CNY"),
            LoanFuturePolicy.FIXED_FUTURE_AMOUNT, 0, 1, 1,
        )

        assertEquals(listOf(80L, 20L, 0L), preview.terms.map { it.principal.unscaled })
        assertEquals(listOf(LoanDueState.DUE, LoanDueState.DUE, LoanDueState.NO_DUE), preview.terms.map { it.dueState })
        assertTrue(preview.terms.all { it.interest.unscaled == 0L })
        assertTrue(preview.terms.last().closingPrincipal.sameValue(Money(0, 0, "CNY")))
    }
}
