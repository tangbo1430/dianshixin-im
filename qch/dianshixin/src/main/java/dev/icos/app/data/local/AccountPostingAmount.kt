package dev.icos.app.data.local

import dev.icos.app.domain.Money

/**
 * Storage sign rule for ordinary record and receipt postings.
 *
 * Domain flow math is asset neutral: expenses are negative and income/transfers in are positive.
 * Asset balances keep that signed flow, while a positive liability balance means debt, so liability
 * postings store the opposite of the asset-flow signed amount. The rule is applied once at the
 * posting write boundary and never inside [Money] or `RecordInput.calculate`, whose callers already
 * pass fully signed amounts (OPENING, LOAN_CASH, LOAN_LIABILITY, reversals).
 */
internal fun liabilityAwareStoredAmount(accountType: String, signedAmount: Money): Money =
    if (accountType == "LIABILITY") signedAmount.checkedNegate() else signedAmount
