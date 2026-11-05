package dev.icos.app.data.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.icos.app.domain.AccountAmount
import dev.icos.app.domain.Money
import dev.icos.app.domain.RecordInput
import dev.icos.app.domain.RemainderAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LiabilityPostingRepositoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val at = 1768449600000L
    private val day = 86_400_000L

    @Test
    fun ordinaryRecordsAndReceiptsUseLiabilityDebtSignsAndCancelPersistedFacts() {
        val owner = UUID.randomUUID()
        val epoch = UUID.randomUUID()
        val directory = EncryptedDatasetStore.directory(context, owner, epoch)
        try {
            LocalFinanceRepository.openDataset(context, owner, epoch).use { repo ->
                val liability =
                    repo.createAccount(
                        "信用账户",
                        "CNY",
                        Money(0, 2, "CNY"),
                        at,
                        accountType = "LIABILITY",
                    )
                val expenseCategory = repo.snapshot().categories.first { it.direction.name == "EXPENSE" }
                val incomeCategory = repo.snapshot().categories.first { it.direction.name == "INCOME" }

                val purchase =
                    repo.saveRecord(
                        null,
                        RecordInput.Expense(
                            Money(10000, 2, "CNY"),
                            null,
                            listOf(AccountAmount(liability, Money(10000, 2, "CNY"))),
                            true,
                            true,
                        ),
                        at,
                        "Asia/Shanghai",
                        "负债消费",
                        expenseCategory.id,
                    )
                assertTrue(
                    Money(10000, 2, "CNY").sameValue(
                        repo
                            .snapshot()
                            .accounts
                            .single { it.id == liability }
                            .balance,
                    ),
                )

                val refund =
                    repo.createRefund(
                        purchase,
                        1,
                        Money(3000, 2, "CNY"),
                        liability,
                        at + day,
                        "Asia/Shanghai",
                    )
                assertTrue(
                    Money(7000, 2, "CNY").sameValue(
                        repo
                            .snapshot()
                            .accounts
                            .single { it.id == liability }
                            .balance,
                    ),
                )
                repo.cancelRefund(refund, 1, 2, "撤销退款")
                assertTrue(
                    Money(10000, 2, "CNY").sameValue(
                        repo
                            .snapshot()
                            .accounts
                            .single { it.id == liability }
                            .balance,
                    ),
                )

                repo.markClaim(purchase, "PENDING", 3)
                val settlement =
                    repo.settleClaims(
                        listOf(FinanceClaimAllocation(purchase, Money(6000, 2, "CNY"), 4)),
                        liability,
                        incomeCategory.id,
                        RemainderAction.PARTIAL_KEEP,
                        at + 2 * day,
                        "Asia/Shanghai",
                    )
                assertTrue(
                    Money(4000, 2, "CNY").sameValue(
                        repo
                            .snapshot()
                            .accounts
                            .single { it.id == liability }
                            .balance,
                    ),
                )
                repo.cancelClaimSettlement(settlement, 1, "撤销报销收款")
                assertTrue(
                    Money(10000, 2, "CNY").sameValue(
                        repo
                            .snapshot()
                            .accounts
                            .single { it.id == liability }
                            .balance,
                    ),
                )

                val income =
                    repo.saveRecord(
                        null,
                        RecordInput.Income(
                            Money(3000, 2, "CNY"),
                            listOf(AccountAmount(liability, Money(3000, 2, "CNY"))),
                            true,
                        ),
                        at + 3 * day,
                        "Asia/Shanghai",
                        "负债还款返还",
                        incomeCategory.id,
                    )
                assertTrue(
                    Money(7000, 2, "CNY").sameValue(
                        repo
                            .snapshot()
                            .accounts
                            .single { it.id == liability }
                            .balance,
                    ),
                )
                repo.deleteRecord(income, 1)
                assertTrue(
                    Money(10000, 2, "CNY").sameValue(
                        repo
                            .snapshot()
                            .accounts
                            .single { it.id == liability }
                            .balance,
                    ),
                )
            }

            EncryptedDatasetStore.open(context, owner, epoch).use { db ->
                val finance = db.finance()
                val refund = finance.events().single { it.kind == "REFUND" }
                val refundReceipt = finance.postings().single { it.eventId == refund.id }
                assertEquals(-3000L, refundReceipt.amountUnscaled)
                val refundReversal = finance.events().single { it.reversalOf == refund.id }
                assertEquals(3000L, finance.postings().single { it.eventId == refundReversal.id }.amountUnscaled)

                val claim = finance.events().single { it.kind == "CLAIM_SETTLEMENT" }
                val claimReceipt = finance.postings().single { it.eventId == claim.id }
                assertTrue(
                    Money(
                        -6000,
                        2,
                        "CNY",
                    ).sameValue(Money(claimReceipt.amountUnscaled, claimReceipt.amountScale, claimReceipt.amountCurrency)),
                )
                val claimReversal = finance.events().single { it.reversalOf == claim.id }
                val reversedClaimReceipt = finance.postings().single { it.eventId == claimReversal.id }
                assertTrue(
                    Money(
                        6000,
                        2,
                        "CNY",
                    ).sameValue(
                        Money(reversedClaimReceipt.amountUnscaled, reversedClaimReceipt.amountScale, reversedClaimReceipt.amountCurrency),
                    ),
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
