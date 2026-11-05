package dev.icos.app.data.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.icos.app.domain.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CreditInstallmentRepositoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun reschedulingKeepsStoredAllocationModesAndReplacesOnlyCurrentTerms() {
        val owner = UUID.randomUUID()
        val epoch = UUID.randomUUID()
        val directory = EncryptedDatasetStore.directory(context, owner, epoch)
        try {
            LocalFinanceRepository.openDataset(context, owner, epoch).use { repo ->
                val at = 1_640_995_200_000L
                val liability = repo.createAccount("分期负债", "CNY", Money(0, 2, "CNY"), at, accountType = "LIABILITY")
                var account = repo.snapshot().accounts.single { it.id == liability }
                repo.configureCredit(liability, account.localRevision, Money(10_000, 2, "CNY"), 20, true, "FIXED_MONTH_DAY", 5, "UTC")
                account = repo.snapshot().accounts.single { it.id == liability }
                val planId =
                    repo.createInstallment(
                        liability,
                        account.localRevision,
                        Money(10_000, 2, "CNY"),
                        Money(3, 2, "CNY"),
                        3,
                        "2026-01-31",
                        "UTC",
                        "TERM_BY_TERM",
                        "FIRST_TERM",
                        "FIRST_TERM",
                    )
                var plan = repo.snapshot().installmentPlans.single { it.id == planId }

                repo.updateInstallment(plan.id, plan.localRevision, Money(5, 2, "CNY"), 6, "2026-02-28")
                var snapshot = repo.snapshot()
                var current = snapshot.installmentTerms.filter { it.planId == planId && it.state == "SCHEDULED" }.sortedBy { it.termIndex }
                assertEquals(6, current.size)
                assertEquals(Money(5, 2, "CNY"), current.first().interest)
                assertTrue(current.drop(1).all { it.interest.unscaled == 0L })
                assertTrue(current.first().principal.unscaled > current.last().principal.unscaled)
                assertEquals(3, snapshot.installmentTerms.count { it.planId == planId && it.state == "SUPERSEDED" })

                plan = snapshot.installmentPlans.single { it.id == planId }
                repo.updateInstallment(plan.id, plan.localRevision, Money(7, 2, "CNY"), 5, "2026-03-31")
                snapshot = repo.snapshot()
                current = snapshot.installmentTerms.filter { it.planId == planId && it.state == "SCHEDULED" }.sortedBy { it.termIndex }
                assertEquals(5, current.size)
                assertEquals(Money(7, 2, "CNY"), current.first().interest)
                assertTrue(current.drop(1).all { it.interest.unscaled == 0L })
                assertEquals(9, snapshot.installmentTerms.count { it.planId == planId && it.state == "SUPERSEDED" })
                assertTrue(Money(0, 2, "CNY").sameValue(snapshot.accounts.single { it.id == liability }.balance))
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
    fun clearPreflightRejectsCurrentLedgerCreditEventsWithoutRecordReferences() {
        val owner = UUID.randomUUID()
        val epoch = UUID.randomUUID()
        val directory = EncryptedDatasetStore.directory(context, owner, epoch)
        try {
            LocalFinanceRepository.openDataset(context, owner, epoch).use { repo ->
                val at = 1_640_995_200_000L
                repo.createAccount("备用资产", "CNY", Money(20_000, 2, "CNY"), at)
                val liability = repo.createAccount("分期负债", "CNY", Money(0, 2, "CNY"), at, accountType = "LIABILITY")
                var snapshot = repo.snapshot()
                var account = snapshot.accounts.single { it.id == liability }
                repo.configureCredit(liability, account.localRevision, Money(10_000, 2, "CNY"), 20, true, "FIXED_MONTH_DAY", 5, "UTC")
                snapshot = repo.snapshot()
                account = snapshot.accounts.single { it.id == liability }
                repo.createInstallment(
                    liability,
                    account.localRevision,
                    Money(10_000, 2, "CNY"),
                    Money(200, 2, "CNY"),
                    2,
                    "2020-01-31",
                    "UTC",
                    "UPFRONT",
                    "TERM_BY_TERM",
                    "LAST_TERM",
                )
                val failure = assertThrows(FinanceValidationException::class.java) { repo.clearRecordsPreview() }
                assert(failure.message.orEmpty().contains("信用分期"))
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
    fun clearPreflightAcceptsValidCreditReferencesOnlyInOtherLedgers() {
        val owner = UUID.randomUUID()
        val epoch = UUID.randomUUID()
        val directory = EncryptedDatasetStore.directory(context, owner, epoch)
        try {
            LocalFinanceRepository.openDataset(context, owner, epoch).use { repo ->
                val at = 1_640_995_200_000L
                val asset = repo.createAccount("储蓄", "CNY", Money(20_000, 2, "CNY"), at)
                val liability = repo.createAccount("分期负债", "CNY", Money(0, 2, "CNY"), at, accountType = "LIABILITY")
                var snapshot = repo.snapshot()
                var account = snapshot.accounts.single { it.id == liability }
                repo.configureCredit(liability, account.localRevision, Money(10_000, 2, "CNY"), 20, true, "FIXED_MONTH_DAY", 5, "UTC")
                snapshot = repo.snapshot()
                account = snapshot.accounts.single { it.id == liability }
                val planId =
                    repo.createInstallment(
                        liability,
                        account.localRevision,
                        Money(10_000, 2, "CNY"),
                        Money(200, 2, "CNY"),
                        1,
                        "2020-01-31",
                        "UTC",
                        "TERM_BY_TERM",
                        "TERM_BY_TERM",
                        "LAST_TERM",
                    )
                snapshot = repo.snapshot()
                val plan = snapshot.installmentPlans.single { it.id == planId }
                val term = snapshot.installmentTerms.single { it.planId == planId }
                repo.postInstallmentTerm(term.id, plan.localRevision, at)

                val paymentLedger = repo.createLedger("付款账本", "CNY")
                repo.selectLedger(paymentLedger)
                snapshot = repo.snapshot()
                account = snapshot.accounts.single { it.id == liability }
                repo.allocateCreditPayment(
                    liability,
                    account.localRevision,
                    asset,
                    Money(10_200, 2, "CNY"),
                    at,
                    mapOf(term.id to Money(10_200, 2, "CNY")),
                )

                val unrelatedLedger = repo.createLedger("无关账本", "CNY")
                repo.selectLedger(unrelatedLedger)
                assertTrue(repo.clearRecordsPreview().rejectedReasons.isEmpty())

                repo.selectLedger(paymentLedger)
                val paymentFailure = assertThrows(FinanceValidationException::class.java) { repo.clearRecordsPreview() }
                assertTrue(paymentFailure.message.orEmpty().contains("信用"))

                repo.selectLedger(snapshot.ledgerId)
                val planFailure = assertThrows(FinanceValidationException::class.java) { repo.clearRecordsPreview() }
                assertTrue(planFailure.message.orEmpty().contains("信用"))
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
    fun installmentPostingAndPaymentAllocationCommitAtomicallyWithDebtSigns() {
        val owner = UUID.randomUUID()
        val epoch = UUID.randomUUID()
        val directory = EncryptedDatasetStore.directory(context, owner, epoch)
        try {
            LocalFinanceRepository.openDataset(context, owner, epoch).use { repo ->
                val at = 1_640_995_200_000L // 2022-01-01 UTC
                val asset = repo.createAccount("现金", "CNY", Money(20_000, 2, "CNY"), at)
                val liability = repo.createAccount("信用账户", "CNY", Money(0, 2, "CNY"), at, accountType = "LIABILITY")
                var snapshot = repo.snapshot()
                var creditAccount = snapshot.accounts.single { it.id == liability }
                repo.configureCredit(liability, creditAccount.localRevision, Money(10_000, 2, "CNY"), 20, true, "FIXED_MONTH_DAY", 5, "UTC")
                snapshot = repo.snapshot()
                creditAccount = snapshot.accounts.single { it.id == liability }

                val planId =
                    repo.createInstallment(
                        liability,
                        creditAccount.localRevision,
                        Money(10_000, 2, "CNY"),
                        Money(200, 2, "CNY"),
                        2,
                        "2020-01-31",
                        "UTC",
                        "TERM_BY_TERM",
                        "TERM_BY_TERM",
                        "LAST_TERM",
                    )
                snapshot = repo.snapshot()
                val plan = snapshot.installmentPlans.single { it.id == planId }
                var term = snapshot.installmentTerms.single { it.planId == planId && it.termIndex == 1 }
                repo.postInstallmentTerm(term.id, plan.localRevision, at)
                snapshot = repo.snapshot()
                creditAccount = snapshot.accounts.single { it.id == liability }
                val nextTerm = snapshot.installmentTerms.single { it.planId == planId && it.termIndex == 2 }
                val nextPlan = snapshot.installmentPlans.single { it.id == planId }
                repo.postInstallmentTerm(nextTerm.id, nextPlan.localRevision, at)

                snapshot = repo.snapshot()
                creditAccount = snapshot.accounts.single { it.id == liability }
                term = snapshot.installmentTerms.single { it.id == term.id }
                val secondTerm = snapshot.installmentTerms.single { it.id == nextTerm.id }
                assertEquals("POSTED", term.state)
                assertEquals("POSTED", secondTerm.state)
                assertEquals(Money(10_200, 2, "CNY"), creditAccount.balance)
                assertEquals(Money(20_000, 2, "CNY"), snapshot.accounts.single { it.id == asset }.balance)

                val allocation = Money(5_100, 2, "CNY")
                assertThrows(FinanceValidationException::class.java) {
                    repo.allocateCreditPayment(
                        liability,
                        creditAccount.localRevision,
                        asset,
                        allocation,
                        at,
                        mapOf(term.id to allocation, secondTerm.id to Money(1, 2, "CNY")),
                    )
                }
                snapshot = repo.snapshot()
                creditAccount = snapshot.accounts.single { it.id == liability }
                assertEquals(Money(10_200, 2, "CNY"), creditAccount.balance)
                assertEquals(Money(20_000, 2, "CNY"), snapshot.accounts.single { it.id == asset }.balance)
                assertEquals("POSTED", snapshot.installmentTerms.single { it.id == term.id }.state)
                assertEquals("POSTED", snapshot.installmentTerms.single { it.id == secondTerm.id }.state)
                assertEquals(0, snapshot.creditPaymentAllocations.count { it.termId == term.id || it.termId == secondTerm.id })

                repo.allocateCreditPayment(liability, creditAccount.localRevision, asset, allocation, at, mapOf(term.id to allocation))
                snapshot = repo.snapshot()
                assertEquals(Money(5_100, 2, "CNY"), snapshot.accounts.single { it.id == liability }.balance)
                assertEquals(Money(14_900, 2, "CNY"), snapshot.accounts.single { it.id == asset }.balance)
                assertEquals("PAID", snapshot.installmentTerms.single { it.id == term.id }.state)
                assertEquals(allocation, snapshot.creditPaymentAllocations.single { it.termId == term.id }.amount)
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
