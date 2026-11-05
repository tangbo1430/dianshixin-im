package dev.icos.app.domain

import java.util.UUID

enum class LoanFuturePolicy { ONLY_THIS_TERM, FIXED_FUTURE_AMOUNT }
enum class LoanDeleteStrategy { KEEP_HISTORY, REVERSE_REVERSIBLE }
enum class LoanDueState { DUE, NO_DUE, PAID }

data class LoanPlanInput(
    val id: UUID = UUID.randomUUID(),
    val accountId: UUID,
    val principal: Money,
    val annualRateNumerator: Long,
    val annualRateDenominator: Long,
    val termCount: Int,
    val paidTermCount: Int = 0,
    val repaymentType: RepaymentMethod,
    val firstDueDate: String,
)

data class LoanTerm(
    val termIndex: Int,
    val dueDate: String,
    val openingPrincipal: Money,
    val principal: Money,
    val interest: Money,
    val closingPrincipal: Money,
    val dueState: LoanDueState = LoanDueState.DUE,
) {
    val amount: Money get() = principal.checkedAdd(interest)
}

data class LoanPlanSchedule(val terms: List<LoanTerm>)
data class LoanPreview(
    val expectedVersion: Long,
    val oldTerms: List<LoanTerm>,
    val terms: List<LoanTerm>,
    val affectedFrom: Int,
    val principalDelta: Money,
    val interestDelta: Money,
)

object LoanCalculator {
    private const val MAX_TERMS = 1200
    private const val MIN_ISO_YEAR = -999_999_999
    private const val MAX_ISO_YEAR = 999_999_999

    private data class LoanDate(val year: Int, val month: Int, val day: Int) {
        fun plusMonths(amount: Long): LoanDate {
            val monthIndex = year.toLong() * 12 + month - 1 + amount
            val targetYear = Math.floorDiv(monthIndex, 12L)
            require(targetYear in MIN_ISO_YEAR.toLong()..MAX_ISO_YEAR.toLong())
            val targetMonth = Math.floorMod(monthIndex, 12L).toInt() + 1
            return LoanDate(
                targetYear.toInt(),
                targetMonth,
                minOf(day, daysInMonth(targetYear.toInt(), targetMonth)),
            )
        }

        override fun toString(): String {
            val yearText = when {
                year in 0..9999 -> year.toString().padStart(4, '0')
                year > 9999 -> "+$year"
                else -> "-${(-year).toString().padStart(4, '0')}"
            }
            return "$yearText-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}"
        }
    }

    private fun parseDate(value: String): LoanDate {
        val match = Regex("([+-]?[0-9]{4,9})-([0-9]{2})-([0-9]{2})").matchEntire(value)
            ?: throw IllegalArgumentException("Invalid loan date")
        val year = match.groupValues[1].toInt()
        require(year in MIN_ISO_YEAR..MAX_ISO_YEAR)
        val month = match.groupValues[2].toInt()
        val day = match.groupValues[3].toInt()
        require(month in 1..12 && day in 1..daysInMonth(year, month)) { "Invalid loan date" }
        val date = LoanDate(year, month, day)
        require(date.toString() == value) { "Non-canonical loan date" }
        return date
    }

    private fun daysInMonth(year: Int, month: Int): Int = when (month) {
        2 -> if (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }

    internal fun isValidLoanDate(value: String, fourDigitYearOnly: Boolean = false): Boolean =
        runCatching {
            val date = parseDate(value)
            !fourDigitYearOnly || date.year in 1..9999
        }.getOrDefault(false)

    fun create(input: LoanPlanInput): LoanPlanSchedule {
        validate(input)
        val base = PlanCalculator.loanSchedule(
            input.principal, input.annualRateNumerator, input.annualRateDenominator,
            input.termCount, input.repaymentType, input.principal.scale,
        )
        return schedule(input, base.terms.map { it.principal to it.interest }, input.principal)
    }

    fun changeRate(
        old: List<LoanTerm>,
        effectiveTerm: Int,
        annualNumerator: Long,
        annualDenominator: Long,
        method: RepaymentMethod,
        expectedVersion: Long,
    ): LoanPreview {
        require(annualNumerator >= 0 && annualDenominator > 0)
        val prefix = old.filter { it.termIndex < effectiveTerm }
        val suffix = old.filter { it.termIndex >= effectiveTerm }
        require(suffix.isNotEmpty() && suffix.all { it.dueState != LoanDueState.PAID })
        val principal = suffix.first().openingPrincipal
        val recalculated = PlanCalculator.loanSchedule(principal, annualNumerator, annualDenominator, suffix.size, method, principal.scale)
        var opening = principal
        val proposed = suffix.mapIndexed { index, term ->
            val p = recalculated.terms[index].principal
            val i = recalculated.terms[index].interest
            val next = term.copy(
                openingPrincipal = opening,
                principal = p,
                interest = i,
                closingPrincipal = opening.checkedSubtract(p),
            )
            opening = next.closingPrincipal
            next
        }
        val all = prefix + proposed
        return LoanPreview(
            expectedVersion, old, all, effectiveTerm,
            sum(all.filter { it.termIndex >= effectiveTerm }.map { it.principal }, principal).checkedSubtract(
                sum(old.filter { it.termIndex >= effectiveTerm }.map { it.principal }, principal),
            ),
            sum(all.filter { it.termIndex >= effectiveTerm }.map { it.interest }, principal).checkedSubtract(
                sum(old.filter { it.termIndex >= effectiveTerm }.map { it.interest }, principal),
            ),
        )
    }

    fun adjustTerm(
        old: List<LoanTerm>,
        termIndex: Int,
        principal: Money,
        interest: Money,
        policy: LoanFuturePolicy,
        annualNumerator: Long,
        annualDenominator: Long,
        expectedVersion: Long,
    ): LoanPreview {
        require(principal.unscaled >= 0 && interest.unscaled >= 0)
        val target = old.single { it.termIndex == termIndex }
        val suffix = old.filter { it.termIndex >= termIndex }
        require(suffix.all { it.dueState != LoanDueState.PAID })
        require(principal.currency == target.principal.currency && interest.currency == target.interest.currency)
        val opening = target.openingPrincipal
        require(principal.checkedSubtract(opening).unscaled <= 0)
        val closing = opening.checkedSubtract(principal)
        val edited = target.copy(principal = principal, interest = interest, closingPrincipal = closing)
        val proposedSuffix = when (policy) {
            LoanFuturePolicy.ONLY_THIS_TERM -> {
                require(principal.sameValue(target.principal)) { "INVALID_ALLOCATION" }
                listOf(edited) + suffix.drop(1)
            }
            LoanFuturePolicy.FIXED_FUTURE_AMOUNT -> {
                val rows = mutableListOf(edited)
                var balance = closing
                val targetAmount = edited.amount
                suffix.drop(1).forEachIndexed { index, row ->
                    val rateInterest = if (balance.unscaled == 0L) Money(0, balance.scale, balance.currency) else
                        PlanCalculator.loanSchedule(
                            balance, annualNumerator, annualDenominator, 1, RepaymentMethod.INTEREST_ONLY, balance.scale,
                        ).terms.single().interest
                    val p = if (balance.unscaled == 0L) {
                        Money(0, balance.scale, balance.currency)
                    } else {
                        targetAmount.checkedSubtract(rateInterest).also { require(it.unscaled >= 0) }
                    }
                    val principal = when {
                        balance.unscaled == 0L -> p
                        index == suffix.lastIndex - 1 || balance.checkedSubtract(p).unscaled < 0 -> balance
                        else -> p
                    }
                    val next = balance.checkedSubtract(principal)
                    rows += row.copy(openingPrincipal = balance, principal = principal, interest = rateInterest, closingPrincipal = next,
                        dueState = if (principal.unscaled == 0L && rateInterest.unscaled == 0L) LoanDueState.NO_DUE else LoanDueState.DUE)
                    balance = next
                }
                require(balance.unscaled == 0L) { "INVALID_ALLOCATION" }
                rows
            }
        }
        val all = old.takeWhile { it.termIndex < termIndex } + proposedSuffix
        return LoanPreview(
            expectedVersion, old, all, termIndex,
            sum(proposedSuffix.map { it.principal }, opening).checkedSubtract(sum(suffix.map { it.principal }, opening)),
            sum(proposedSuffix.map { it.interest }, opening).checkedSubtract(sum(suffix.map { it.interest }, opening)),
        )
    }

    private fun validate(input: LoanPlanInput) {
        input.principal.requirePositive()
        require(input.annualRateNumerator >= 0 && input.annualRateDenominator > 0)
        require(input.termCount in 1..MAX_TERMS && input.paidTermCount in 0..input.termCount)
        require(input.principal.scale in 0..6)
        parseDate(input.firstDueDate)
    }

    private fun sum(values: List<Money>, template: Money): Money =
        values.fold(Money(0, template.scale, template.currency)) { total, value -> total.checkedAdd(value) }

    private fun schedule(input: LoanPlanInput, rows: List<Pair<Money, Money>>, principal: Money): LoanPlanSchedule {
        var opening = principal
        val firstDate = parseDate(input.firstDueDate)
        val terms = rows.mapIndexed { index, (p, interest) ->
            val closing = opening.checkedSubtract(p)
            val term = LoanTerm(index + 1, firstDate.plusMonths(index.toLong()).toString(), opening, p, interest, closing)
            opening = closing
            term
        }
        require(opening.unscaled == 0L)
        return LoanPlanSchedule(terms)
    }
}
