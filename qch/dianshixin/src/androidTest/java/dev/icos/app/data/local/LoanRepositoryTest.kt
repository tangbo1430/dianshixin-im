package dev.icos.app.data.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.icos.app.domain.LoanDeleteStrategy
import dev.icos.app.domain.LoanPlanInput
import dev.icos.app.domain.Money
import dev.icos.app.domain.RepaymentMethod
import dev.icos.app.ui.categoryStatistics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigDecimal
import java.security.KeyStore
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LoanRepositoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun unfundedLoanPlanIsCreatedWithoutChangingAccountBalances() {
        val owner = UUID.randomUUID()
        val epoch = UUID.randomUUID()
        val directory = EncryptedDatasetStore.directory(context, owner, epoch)
        try {
            LocalFinanceRepository.openDataset(context, owner, epoch).use { repo ->
                val at = 1768449600000L
                val debt = repo.createAccount("未放款贷款", "CNY", Money(0, 2, "CNY"), at, accountType = "LIABILITY")
                val planId =
                    repo.createLoan(
                        LoanPlanInput(
                            accountId = debt,
                            principal = Money(10000, 2, "CNY"),
                            annualRateNumerator = 5,
                            annualRateDenominator = 100,
                            termCount = 12,
                            repaymentType = RepaymentMethod.EQUAL_PAYMENT,
                            firstDueDate = "2026-02-15",
                        ),
                    )
                val snapshot = repo.snapshot()
                assertEquals(
                    0L,
                    snapshot.accounts
                        .single { it.id == debt }
                        .balance.unscaled,
                )
                assertEquals(planId, snapshot.loanPlans.single().id)
                assertEquals(
                    12,
                    snapshot.loanPlans
                        .single()
                        .terms.size,
                )
            }
        } finally {
            KeyStore.getInstance("AndroidKeyStore").apply {
                load(null)
                deleteEntry(EncryptedDatasetStore.alias(owner, epoch))
            }
            directory.deleteRecursively()
            directory.parentFile?.delete()
        }
    }

    @Test
    fun fundedLoanAndPaymentKeepCashDebtAndNetFactsSeparate() {
        val owner = UUID.randomUUID()
        val epoch = UUID.randomUUID()
        val directory = EncryptedDatasetStore.directory(context, owner, epoch)
        try {
            LocalFinanceRepository.openDataset(context, owner, epoch).use { repo ->
                val at = 1768449600000L
                val cash = repo.createAccount("现金", "CNY", Money(0, 2, "CNY"), at)
                val debt = repo.createAccount("贷款", "CNY", Money(0, 2, "CNY"), at, accountType = "LIABILITY")
                val input =
                    LoanPlanInput(
                        accountId = debt,
                        principal = Money(10000, 2, "CNY"),
                        annualRateNumerator = 0,
                        annualRateDenominator = 1,
                        termCount = 3,
                        repaymentType = RepaymentMethod.EQUAL_PRINCIPAL,
                        firstDueDate = "2026-01-31",
                    )
                val loanId = repo.createLoan(input, LoanDisbursement(cash, at))
                var snapshot = repo.snapshot()
                assertEquals(Money(10000, 2, "CNY"), snapshot.accounts.single { it.id == cash }.balance)
                assertEquals(Money(10000, 2, "CNY"), snapshot.accounts.single { it.id == debt }.balance)
                val payment = Money(3533, 2, "CNY")
                repo.postLoanPayment(loanId, 1, 1, cash, at + 86_400_000, payment)
                snapshot = repo.snapshot()
                assertEquals(Money(6467, 2, "CNY"), snapshot.accounts.single { it.id == cash }.balance)
                assertEquals(Money(6667, 2, "CNY"), snapshot.accounts.single { it.id == debt }.balance)
                assertEquals(Money(200, 2, "CNY"), snapshot.summaries.single { it.currency == "CNY" }.expense)
                assertEquals(Money(0, 2, "CNY"), snapshot.summaries.single { it.currency == "CNY" }.budgetExpense)
                val interest = snapshot.loanContributions.single { it.kind == "REPAYMENT_INTEREST" }
                assertTrue(interest.statisticEligible)
                assertEquals(dev.icos.app.domain.Direction.EXPENSE, interest.statisticDirection)
                val paymentRecord = snapshot.records.single { it.occurredAtMillis == at + 86_400_000 }
                val loanExpense =
                    categoryStatistics(snapshot.statisticEntries, snapshot.categories)
                        .single { it.direction == dev.icos.app.domain.Direction.EXPENSE && it.currency == "CNY" && it.categoryId == null }
                assertEquals(BigDecimal("2.00"), loanExpense.amount)
                assertEquals("未分类", loanExpense.name)
                assertEquals(listOf(interest.amount), loanExpense.entries.map { it.amount })
                assertEquals(interest.eventId, loanExpense.entries.single().eventId)
                assertEquals(paymentRecord.id, loanExpense.entries.single().recordId)
                assertEquals(paymentRecord.occurredAtMillis, loanExpense.entries.single().occurredAtMillis)
                assertEquals(paymentRecord.timezone, loanExpense.entries.single().timezone)
                assertEquals(paymentRecord.remark, loanExpense.entries.single().remark)
                repo.saveBusinessPreferences(
                    expectedRevision = snapshot.businessPreferences!!.localRevision,
                    refundExcessAsIncome = false,
                    showRefundsInRecordList = true,
                    loanPrincipalAsIncome = true,
                    loanRepaymentAsExpense = true,
                    loanInterestAsExpense = false,
                )
                snapshot = repo.snapshot()
                assertEquals(Money(10000, 2, "CNY"), snapshot.summaries.single { it.currency == "CNY" }.income)
                assertEquals(Money(3333, 2, "CNY"), snapshot.summaries.single { it.currency == "CNY" }.expense)
                repo.deleteLoan(loanId, 2, LoanDeleteStrategy.KEEP_HISTORY)
                assertThrows(FinanceValidationException::class.java) {
                    repo.deleteRecord(paymentRecord.id, paymentRecord.localRevision)
                }
                assertEquals(
                    Money(6667, 2, "CNY"),
                    repo
                        .snapshot()
                        .accounts
                        .single { it.id == debt }
                        .balance,
                )
                assertEquals(
                    3,
                    repo
                        .snapshot()
                        .loanPlans
                        .single()
                        .terms.size,
                )
            }
        } finally {
            KeyStore.getInstance("AndroidKeyStore").apply {
                load(null)
                deleteEntry(EncryptedDatasetStore.alias(owner, epoch))
            }
            directory.deleteRecursively()
            directory.parentFile?.delete()
        }
    }
}
