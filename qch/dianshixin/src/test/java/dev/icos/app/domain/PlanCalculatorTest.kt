package dev.icos.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PlanCalculatorTest {
    private data class Fixture(val id: String, val values: Map<String, String>)

    private fun fixtures(): List<Fixture> {
        val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream("plan-calculation-cases.json"))
        val json = stream.bufferedReader().use { it.readText() }
        val bodies = Regex("\"(?:cases|invalidCases)\"\\s*:\\s*\\[(.*?)]", RegexOption.DOT_MATCHES_ALL)
            .findAll(json).map { it.groupValues[1] }.toList()
        require(bodies.size == 2) { "expected cases and invalidCases arrays" }
        return bodies.flatMap { body ->
            Regex("\\{([^{}]+)}").findAll(body).map { match ->
                val values = Regex("\"([^\"]+)\"\\s*:\\s*(?:\"([^\"]*)\"|(-?\\d+))")
                    .findAll(match.groupValues[1]).associate { it.groupValues[1] to (it.groupValues[2].ifEmpty { it.groupValues[3] }) }
                Fixture(values.getValue("id"), values)
            }
        }.toList()
    }

    private fun long(v: Map<String, String>, key: String) = v.getValue(key).toLong()
    private fun int(v: Map<String, String>, key: String) = v.getValue(key).toInt()
    private fun money(v: Map<String, String>, key: String) = Money(long(v, key), int(v, "scale"), "CNY")

    private data class HistoryCase(
        val id: String,
        val target: Money,
        val initial: Money,
        val deposits: List<Money>,
        val withdrawals: List<Money>,
        val saved: Money?,
        val remaining: Money?,
        val expectedError: String?,
    )

    private fun historyCases(): List<HistoryCase> {
        val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream("saving-progress-entry-cases.json"))
        val json = stream.bufferedReader().use { it.readText() }
        check(Regex("\"schemaVersion\"\\s*:\\s*1").containsMatchIn(json))
        val arrayStart = json.indexOf("\"cases\"").let { json.indexOf('[', it) }
        require(arrayStart >= 0)
        val bodies = mutableListOf<String>()
        var depth = 0
        var start = -1
        var quoted = false
        var escaped = false
        for (index in arrayStart + 1 until json.length) {
            val char = json[index]
            if (quoted) {
                if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') quoted = false
                continue
            }
            if (char == '"') quoted = true
            else if (char == '{') {
                if (depth == 0) start = index
                depth++
            } else if (char == '}') {
                depth--
                if (depth == 0 && start >= 0) bodies += json.substring(start, index + 1)
            } else if (char == ']' && depth == 0) break
        }
        fun parseMoney(raw: String): Money {
            val v = Regex("\"([^\"]+)\"\\s*:\\s*(?:\"([^\"]*)\"|(-?\\d+))")
                .findAll(raw).associate { it.groupValues[1] to (it.groupValues[2].ifEmpty { it.groupValues[3] }) }
            return Money(v.getValue("unscaled").toLong(), v.getValue("scale").toInt(), v.getValue("currency"))
        }
        fun objectValue(body: String, key: String): Money {
            val raw = Regex("\"$key\"\\s*:\\s*(\\{[^{}]*})").find(body)?.groupValues?.get(1)
                ?: error("missing $key in shared history fixture")
            return parseMoney(raw)
        }
        fun arrayValue(body: String, key: String): List<Money> {
            val raw = Regex("\"$key\"\\s*:\\s*\\[(.*?)]", RegexOption.DOT_MATCHES_ALL).find(body)?.groupValues?.get(1)
                ?: error("missing $key in shared history fixture")
            return Regex("\\{[^{}]*}").findAll(raw).map { parseMoney(it.value) }.toList()
        }
        return bodies.map { body ->
            val id = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").find(body)!!.groupValues[1]
            val error = Regex("\"expectedError\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
            HistoryCase(
                id, objectValue(body, "target"), objectValue(body, "initialSaved"),
                arrayValue(body, "deposits"), arrayValue(body, "withdrawals"),
                if (error == null) objectValue(body, "saved") else null,
                if (error == null) objectValue(body, "remaining") else null,
                error,
            )
        }
    }

    @Test
    fun sharedFixtureLoanInstallmentAndSavingCases() {
        val allFixtures = fixtures()
        val validCases = allFixtures.filter { it.values["kind"] != "invalid" }
        check(validCases.size == 15) { "expected 15 shared valid cases, got ${validCases.size}" }
        check(validCases.groupingBy { it.values.getValue("kind") }.eachCount() == mapOf(
            "loan" to 7, "installment" to 3, "ramp" to 2, "progress" to 3,
        )) { "unknown or missing valid fixture kind" }
        val cases = validCases.associateBy { it.id }
        cases.values.filter { it.values["kind"] == "loan" }.forEach { fixture ->
            val v = fixture.values
            val outputScale = v["outputScale"]?.toInt() ?: int(v, "scale")
            val schedule = PlanCalculator.loanSchedule(
                Money(long(v, "principal"), int(v, "scale"), "CNY"),
                long(v, "rateNum"), long(v, "rateDen"), int(v, "count"),
                RepaymentMethod.valueOf(v.getValue("method")), outputScale,
            )
            v["principalTotal"]?.let { assertEquals(it.toBigInteger(), schedule.principalTotal.unscaled.toBigInteger() * java.math.BigInteger.TEN.pow(outputScale - schedule.principalTotal.scale)) }
            v["interestTotal"]?.let { assertEquals(it.toBigInteger(), schedule.interestTotal.unscaled.toBigInteger() * java.math.BigInteger.TEN.pow(outputScale - schedule.interestTotal.scale)) }
            v["firstPrincipal"]?.let { assertEquals(it.toLong(), schedule.terms.first().principal.unscaled) }
            v["firstInterest"]?.let { assertEquals(it.toLong(), schedule.terms.first().interest.unscaled) }
            v["lastPrincipal"]?.let { assertEquals(it.toLong(), schedule.terms.last().principal.unscaled) }
            v["lastInterest"]?.let { assertEquals(it.toLong(), schedule.terms.last().interest.unscaled) }
            assertEquals(Money(long(v, "principal"), int(v, "scale"), "CNY"), schedule.principalTotal)
        }
        val tail = cases.getValue("installment-tail").values
        val tailRows = PlanCalculator.installmentAllocation(
            money(tail, "principal"), money(tail, "interest"), int(tail, "count"),
            Remainder.TAIL, InterestMode.SPREAD, int(tail, "scale"),
        )
        assertEquals(listOf(3333L, 3333L, 3334L), tailRows.map { it.principal.unscaled })
        assertEquals(10000L, tailRows.sumOf { it.principal.unscaled })
        listOf("installment-head", "installment-first-interest").forEach { id ->
            val v = cases.getValue(id).values
            val rows = PlanCalculator.installmentAllocation(
                money(v, "principal"), money(v, "interest"), int(v, "count"),
                Remainder.valueOf(v.getValue("mode")),
                InterestMode.valueOf(v.getValue("interestMode")),
                int(v, "scale"),
            )
            assertEquals(v.getValue("expectedPrincipal").split(',').map(String::toLong), rows.map { it.principal.unscaled })
            assertEquals(v.getValue("expectedInterest").split(',').map(String::toLong), rows.map { it.interest.unscaled })
        }

        val equal = cases.getValue("loan-equal-principal").values
        val schedule = PlanCalculator.loanSchedule(
            money(equal, "principal"), long(equal, "rateNum"), long(equal, "rateDen"),
            int(equal, "count"), RepaymentMethod.EQUAL_PRINCIPAL, int(equal, "scale"),
        )
        assertEquals(1200L, schedule.terms.first().interest.unscaled)
        assertEquals(100L, schedule.terms.last().interest.unscaled)
        assertEquals(7800L, schedule.interestTotal.unscaled)

        listOf("saving-52", "saving-365").forEach { id ->
            val ramp = cases.getValue(id).values
            val got = PlanCalculator.savingRamp(money(ramp, "first"), money(ramp, "increment"), int(ramp, "count"))
            assertEquals(long(ramp, "total"), got.unscaled)
        }
        validCases.filter { it.values["kind"] == "progress" }.forEach { fixture ->
            val progress = fixture.values
            val result = PlanCalculator.savingProgress(
                money(progress, "target"), money(progress, "initial"),
                money(progress, "deposits"), money(progress, "withdrawals"),
            )
            assertEquals(fixture.id, long(progress, "saved"), result.saved.unscaled)
            assertEquals(fixture.id, long(progress, "remaining"), result.remaining.unscaled)
        }
    }

    @Test
    fun sharedInvalidFixtureCasesFailWithDeclaredErrors() {
        val cases = fixtures().filter { it.values["kind"] == "invalid" }
        check(cases.size == 6) { "expected 6 shared invalid cases, got ${cases.size}" }
        cases.forEach { fixture ->
            val v = fixture.values
            val expected = v.getValue("expectedError")
            val thrown = assertThrows(MoneyException::class.java) {
                when {
                    "count" in v && long(v, "count") == 0L ->
                        PlanCalculator.savingRamp(Money(100, 2, "CNY"), Money(100, 2, "CNY"), 0)
                    "rateDen" in v && (long(v, "rateNum") < 0L || long(v, "rateDen") == 0L) ->
                        PlanCalculator.loanSchedule(
                            Money(10000, 2, "CNY"), long(v, "rateNum"), long(v, "rateDen"), 2,
                            RepaymentMethod.EQUAL_PRINCIPAL, 2,
                        )
                    v.getValue("id") == "precision-loss" ->
                        PlanCalculator.installmentAllocation(
                            money(v, "principal"), Money(0, int(v, "scale"), "CNY"), int(v, "count"),
                            Remainder.TAIL, InterestMode.SPREAD, int(v, "outputScale"),
                        )
                    v.getValue("id") == "mismatched-currency" ->
                        PlanCalculator.installmentAllocation(
                            Money(100, 2, v.getValue("currency")), Money(0, 2, v.getValue("otherCurrency")), 2,
                            Remainder.TAIL, InterestMode.SPREAD, 2,
                        )
                    v.getValue("id") == "true-final-overflow" ->
                        PlanCalculator.savingRamp(money(v, "principal"), Money(1, 0, "CNY"), int(v, "count"))
                    else -> error("unknown invalid fixture: ${fixture.id}")
                }
            }
            assertEquals("case ${fixture.id}", expected, thrown.code)
        }
    }

    @Test
    fun keepsBigCancelledSumsExact() {
        assertEquals("INVALID_ALLOCATION", assertThrows(MoneyException::class.java) {
            PlanCalculator.savingRamp(Money(100, 2, "CNY"), Money(-100, 2, "CNY"), 2)
        }.code)
        assertEquals("INVALID_MONEY", assertThrows(MoneyException::class.java) {
            PlanCalculator.installmentAllocation(
                Money(1, 3, "CNY"), Money(0, 2, "CNY"), 2,
                Remainder.TAIL, InterestMode.SPREAD, 2,
            )
        }.code)
        assertEquals("INVALID_MONEY", assertThrows(MoneyException::class.java) {
            PlanCalculator.installmentAllocation(
                Money(100, 2, "CNY"), Money(0, 2, "USD"), 2,
                Remainder.TAIL, InterestMode.SPREAD, 2,
            )
        }.code)
        assertEquals("AMOUNT_OVERFLOW", assertThrows(MoneyException::class.java) {
            PlanCalculator.savingRamp(Money(Long.MAX_VALUE, 0, "CNY"), Money(1, 0, "CNY"), 2)
        }.code)
        val large = Money(9_223_372_036_854_777L, 3, "CNY")
        val progress = PlanCalculator.savingProgress(large, large, large, large)
        assertEquals(large, progress.saved)
        assertEquals(Money(0, 3, "CNY"), progress.remaining)
    }

    @Test
    fun progressAggregatesIndividualHistoryBeforeBoundingTheResult() {
        val max = Money(Long.MAX_VALUE, 0, "CNY")
        val progress = PlanCalculator.savingProgress(
            max, Money(0, 0, "CNY"),
            listOf(max, Money(10, 0, "CNY")),
            listOf(max, Money(10, 0, "CNY")),
        )
        assertEquals(Money(0, 0, "CNY"), progress.saved)
        assertEquals(max, progress.remaining)

        assertEquals("AMOUNT_OVERFLOW", assertThrows(MoneyException::class.java) {
            PlanCalculator.savingProgress(max, Money(0, 0, "CNY"), listOf(max, Money(1, 0, "CNY")), emptyList())
        }.code)
        assertEquals("INVALID_MONEY", assertThrows(MoneyException::class.java) {
            PlanCalculator.savingProgress(max, Money(0, 0, "CNY"), listOf(Money(1, 0, "USD")), emptyList())
        }.code)
    }

    @Test
    fun sharedHistoryFixtureCasesPassThroughThePublicProgressApi() {
        val cases = historyCases()
        assertEquals(7, cases.size)
        assertEquals(4, cases.count { it.expectedError == null })
        assertEquals(3, cases.count { it.expectedError != null })
        cases.forEach { fixture ->
            if (fixture.expectedError == null) {
                val result = PlanCalculator.savingProgress(
                    fixture.target, fixture.initial, fixture.deposits, fixture.withdrawals,
                )
                assertEquals("saved case ${fixture.id}", fixture.saved, result.saved)
                assertEquals("remaining case ${fixture.id}", fixture.remaining, result.remaining)
            } else {
                val thrown = assertThrows(MoneyException::class.java) {
                    PlanCalculator.savingProgress(
                        fixture.target, fixture.initial, fixture.deposits, fixture.withdrawals,
                    )
                }
                assertEquals("error case ${fixture.id}", fixture.expectedError, thrown.code)
            }
        }
    }
}
