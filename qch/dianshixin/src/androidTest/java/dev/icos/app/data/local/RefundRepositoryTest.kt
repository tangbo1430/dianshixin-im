package dev.icos.app.data.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.icos.app.domain.AccountAmount
import dev.icos.app.domain.Direction
import dev.icos.app.domain.Money
import dev.icos.app.domain.PeriodRange
import dev.icos.app.domain.RecordInput
import dev.icos.app.domain.RemainderAction
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RefundRepositoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val at = 1768449600000L
    private val day = 86_400_000L

    private fun money(
        value: Long,
        currency: String = "CNY",
    ) = Money(value, 0, currency)

    private fun dataset(block: (UUID, UUID) -> Unit) {
        val owner = UUID.randomUUID()
        val epoch = UUID.randomUUID()
        val directory = EncryptedDatasetStore.directory(context, owner, epoch)
        try {
            block(owner, epoch)
        } finally {
            KeyStore.getInstance("AndroidKeyStore").apply {
                load(null)
                deleteEntry(EncryptedDatasetStore.alias(owner, epoch))
            }
            directory.deleteRecursively()
            directory.parentFile?.delete()
        }
    }

    private fun totals(
        snapshot: FinanceSnapshot,
        balance: Long,
        expense: Long,
    ) {
        assertEquals(money(balance), snapshot.accounts.single().balance)
        assertEquals(money(expense), snapshot.summaries.single().expense)
        assertEquals(money(expense), snapshot.summaries.single().budgetExpense)
        assertEquals(expense, snapshot.statisticEntries.filter { it.direction == Direction.EXPENSE }.sumOf { it.amount.unscaled })
    }

    @Test
    fun businessPreferencesApplyRefundExcessRetroactivelyAndPersistPerDataset() =
        dataset { owner, epoch ->
            lateinit var refundId: UUID
            lateinit var source: UUID
            lateinit var account: UUID
            LocalFinanceRepository.openDataset(context, owner, epoch).use { repo ->
                account = repo.createAccount("退款偏好", "CNY", money(1000), at)
                val expenseCategory = repo.snapshot().categories.first { it.direction == Direction.EXPENSE }
                source = repo.saveRecord(
                    null,
                    RecordInput.Expense(money(110), money(10), listOf(AccountAmount(account, money(100))), true, true),
                    at, "Asia/Shanghai", "测试退款超额", expenseCategory.id,
                )
                refundId = repo.createRefund(source, 1, money(130), account, at + day, "Asia/Shanghai")
                assertEquals(money(0), repo.snapshot().summaries.single().income)
                val enabled = repo.saveBusinessPreferences(1, refundExcessAsIncome = true, showRefundsInRecordList = false)
                assertEquals(2, enabled.localRevision)
                val on = repo.snapshot()
                assertEquals(money(30), on.summaries.single().income)
                assertTrue(on.statisticEntries.any { it.direction == Direction.INCOME && it.amount == money(30) })
                assertFalse(on.businessPreferences!!.showRefundsInRecordList)
                assertThrows(FinanceValidationException::class.java) {
                    repo.saveBusinessPreferences(1, refundExcessAsIncome = false, showRefundsInRecordList = true)
                }
                assertEquals(on, repo.snapshot())
                repo.saveBusinessPreferences(2, refundExcessAsIncome = false, showRefundsInRecordList = false)
                assertEquals(money(0), repo.snapshot().summaries.single().income)
                repo.saveBusinessPreferences(3, refundExcessAsIncome = true, showRefundsInRecordList = false)
                repo.cancelRefund(refundId, 1, 2, "测试撤销")
                assertEquals(money(0), repo.snapshot().summaries.single().income)
            }
            LocalFinanceRepository.openDataset(context, owner, epoch).use { reopened ->
                val preferences = reopened.businessPreferences()
                assertTrue(preferences.refundExcessAsIncome)
                assertFalse(preferences.showRefundsInRecordList)
                assertEquals(4, preferences.localRevision)
            }
        }

    @Test
    fun boundedIndependentRefundExcessRollsBackPreferenceAndNewRefund() =
        dataset { owner, epoch ->
            val max = Money(Long.MAX_VALUE, 2, "CNY")
            fun source(repo: LocalFinanceRepository, suffix: String): Pair<UUID, UUID> {
                val originAccount = repo.createAccount("来源$suffix", "CNY", money(1), at)
                val refundAccount = repo.createAccount("退款$suffix", "CNY", money(0), at)
                val category = repo.snapshot().categories.first { it.direction == Direction.EXPENSE }
                val record = repo.saveRecord(
                    null,
                    RecordInput.Expense(money(1), null, listOf(AccountAmount(originAccount, money(1))), true, true),
                    at, "Asia/Shanghai", "独立超额退款$suffix", category.id,
                )
                return record to refundAccount
            }
            LocalFinanceRepository.openDataset(context, owner, epoch).use { repo ->
                val first = source(repo, "一")
                val second = source(repo, "二")
                repo.createRefund(first.first, 1, max, first.second, at + day, "Asia/Shanghai")
                repo.createRefund(second.first, 1, max, second.second, at + 2 * day, "Asia/Shanghai")
                val before = repo.snapshot()
                assertThrows(FinanceValidationException::class.java) {
                    repo.saveBusinessPreferences(1, true, true)
                }
                assertEquals(before, repo.snapshot())
                assertFalse(repo.businessPreferences().refundExcessAsIncome)
            }
            dataset { innerOwner, innerEpoch ->
                LocalFinanceRepository.openDataset(context, innerOwner, innerEpoch).use { repo ->
                    val first = source(repo, "三")
                    val second = source(repo, "四")
                    repo.createRefund(first.first, 1, max, first.second, at + day, "Asia/Shanghai")
                    repo.saveBusinessPreferences(1, true, true)
                    val before = repo.snapshot()
                    assertThrows(FinanceValidationException::class.java) {
                        repo.createRefund(second.first, 1, max, second.second, at + 2 * day, "Asia/Shanghai")
                    }
                    assertEquals(before, repo.snapshot())
                    assertEquals(1, repo.snapshot().refunds.size)
                }
            }
        }

    @Test
    fun finalNormalizedIncomeCombinesRawRowsAndKeepsEmptyRangesEmpty() =
        dataset { owner, epoch ->
            LocalFinanceRepository.openDataset(context, owner, epoch).use { repo ->
                val maxScaleSix = Money(Long.MAX_VALUE, 6, "CNY")
                val micro = Money(1, 6, "CNY")
                val firstIncomeAccount = repo.createAccount("大额收入", "CNY", money(0), at)
                val expenseAccount = repo.createAccount("退款本金", "CNY", money(1), at)
                val lastIncomeAccount = repo.createAccount("末笔收入", "CNY", money(0), at)
                repo.saveBusinessPreferences(1, refundExcessAsIncome = true, showRefundsInRecordList = true)
                repo.saveRecord(
                    null,
                    RecordInput.Income(maxScaleSix, listOf(AccountAmount(firstIncomeAccount, maxScaleSix)), true),
                    at, "Asia/Shanghai", "I1", null,
                )
                val expenseCategory = repo.snapshot().categories.first { it.direction == Direction.EXPENSE }
                val source = repo.saveRecord(
                    null,
                    RecordInput.Expense(micro, null, listOf(AccountAmount(expenseAccount, micro)), true, true),
                    at + day, "Asia/Shanghai", "退款本金", expenseCategory.id,
                )
                repo.createRefund(source, 1, Money(194, 6, "CNY"), expenseAccount, at + 2 * day, "Asia/Shanghai")
                repo.saveRecord(
                    null,
                    RecordInput.Income(Money(1000, 6, "CNY"), listOf(AccountAmount(lastIncomeAccount, Money(1000, 6, "CNY"))), true),
                    at + 3 * day, "Asia/Shanghai", "I2", null,
                )

                val snapshot = repo.snapshot()
                assertEquals(Money(9_223_372_036_854_777, 3, "CNY"), snapshot.summaries.single().income)
                assertEquals(money(0), snapshot.summaries.single().expense)
                assertEquals(money(0), snapshot.summaries.single().budgetExpense)
                assertEquals(maxScaleSix, snapshot.accounts.single { it.id == firstIncomeAccount }.balance)
                assertEquals(Money(1_000_193, 6, "CNY"), snapshot.accounts.single { it.id == expenseAccount }.balance)
                assertEquals(Money(1, 3, "CNY"), snapshot.accounts.single { it.id == lastIncomeAccount }.balance)
                assertTrue(repo.snapshot(PeriodRange(at + 10 * day, at + 11 * day)).summaries.isEmpty())
            }
        }

    @Test
    fun receiptsReclassifyInCreationOrderReverseOnceAndSurviveEncryptedReopen() =
        dataset { owner, epoch ->
            lateinit var first: UUID
            lateinit var second: UUID
            lateinit var source: UUID
            lateinit var account: UUID
            lateinit var cancelled: FinanceSnapshot
            LocalFinanceRepository.openDataset(context, owner, epoch).use { repo ->
                account = repo.createAccount("退款测试", "CNY", money(1000), at)
                source =
                    repo.saveRecord(
                        null,
                        RecordInput.Expense(money(110), money(10), listOf(AccountAmount(account, money(100))), true, true),
                        at,
                        "Asia/Shanghai",
                        "实付一百",
                        null,
                    )

                fun rejected(block: () -> Unit) {
                    val before = repo.snapshot()
                    assertThrows(FinanceValidationException::class.java) { block() }
                    assertEquals(before, repo.snapshot())
                }
                rejected { repo.createRefund(source, 1, money(1, "USD"), account, at, "Asia/Shanghai") }
                rejected { repo.createRefund(source, 1, money(1), UUID.randomUUID(), at, "Asia/Shanghai") }
                rejected { repo.createRefund(source, 1, money(1), account, at - 1, "Asia/Shanghai") }
                rejected { repo.createRefund(source, 1, money(1), account, System.currentTimeMillis() + day, "Asia/Shanghai") }
                first = repo.createRefund(source, 1, money(60), account, at + day, "Asia/Shanghai")
                second = repo.createRefund(source, 2, money(60), account, at + 2 * day, "Asia/Shanghai")
                val full = repo.snapshot()
                totals(full, 1020, 0)
                assertEquals(money(60), full.refunds.single { it.id == first }.expenseReduction)
                assertEquals(money(40), full.refunds.single { it.id == second }.expenseReduction)
                assertEquals(money(20), full.refunds.single { it.id == second }.excessAmount)
                assertEquals(1, full.records.size)
                val record = full.records.single()
                assertEquals(3L, record.localRevision)
                rejected { repo.createRefund(source, 1, money(1), account, at + day, "Asia/Shanghai") }
                rejected { repo.markClaim(source, "PENDING", record.localRevision) }
                rejected { repo.deleteRecord(source, record.localRevision) }
                rejected { repo.deleteRecords(mapOf(source to record.localRevision)) }
                rejected { repo.saveRecord(source, record.input, at, "Asia/Shanghai", "不得编辑", record.categoryId, record.localRevision) }
            }
            EncryptedDatasetStore.open(context, owner, epoch).use { db ->
                for (id in listOf(first, second)) {
                    val receipt = db.finance().events().single { it.id == id.toString() }
                    assertEquals("REFUND", receipt.kind)
                    assertNull(receipt.recordId)
                    assertEquals(1, db.finance().postings().count { it.eventId == receipt.id })
                }
            }
            LocalFinanceRepository.openDataset(context, owner, epoch).use { repo ->
                repo.cancelRefund(first, 1, 3, "退款撤回")
                val partial = repo.snapshot()
                totals(partial, 960, 40)
                assertEquals(money(60), partial.refunds.single { it.id == second }.expenseReduction)
                assertEquals(money(0), partial.refunds.single { it.id == second }.excessAmount)
                assertEquals("CANCELLED", partial.refunds.single { it.id == first }.status)
                assertTrue(partial.statisticEntries.none { it.eventId == first })
                val receiptPeriod = repo.snapshot(PeriodRange(at + 2 * day, at + 3 * day))
                assertEquals(money(-60), receiptPeriod.summaries.single().expense)
                assertEquals(money(-60), receiptPeriod.summaries.single().budgetExpense)
                assertThrows(FinanceValidationException::class.java) { repo.cancelRefund(first, 1, 4, "重复撤销") }
                assertThrows(FinanceValidationException::class.java) { repo.cancelRefund(second, 1, 3, "过期版本") }
                assertEquals(partial, repo.snapshot())
            }
            LocalFinanceRepository.openDataset(context, owner, epoch).use { repo ->
                repo.cancelRefund(second, 1, 4, "退款撤回")
                cancelled = repo.snapshot()
                totals(cancelled, 900, 100)
                assertTrue(
                    cancelled.refunds.all {
                        it.status == "CANCELLED" && it.expenseReduction.unscaled == 0L &&
                            it.excessAmount.unscaled == 0L
                    },
                )
            }
            val stateMutation =
                EncryptedDatasetStore.open(context, owner, epoch).use { db ->
                    for (id in listOf(first, second)) {
                        val reversal = db.finance().events().single { it.reversalOf == id.toString() }
                        assertEquals(1, db.finance().postings().count { it.eventId == reversal.id })
                    }
                    val mutation = db.finance().mutations().filter { it.kind == "REFUND_STATE" }.maxBy { it.sequence }
                    db.openHelper.writableDatabase.execSQL("DELETE FROM finance_mutation WHERE opId = ?", arrayOf(mutation.opId))
                    mutation
                }
            val missing =
                assertThrows(FinanceValidationException::class.java) {
                    LocalFinanceRepository.openDataset(context, owner, epoch).use { it.snapshot() }
                }
            assertTrue(missing.cause is IllegalStateException)
            EncryptedDatasetStore.open(context, owner, epoch).use { it.finance().insert(stateMutation) }
            LocalFinanceRepository.openDataset(context, owner, epoch).use { assertEquals(cancelled, it.snapshot()) }
        }

    @Test
    fun postedRecoveryGuardsAndCancelledCurrencyHistoryKeepLedgersIndependent() =
        dataset { owner, epoch ->
            lateinit var expected: FinanceSnapshot
            LocalFinanceRepository.openDataset(context, owner, epoch).use { repo ->
                val account = repo.createAccount("CNY", "CNY", money(1000), at)
                val source =
                    repo.saveRecord(
                        null,
                        RecordInput.Expense(money(100), null, listOf(AccountAmount(account, money(100))), false, false),
                        at,
                        "Asia/Shanghai",
                        "不计统计预算",
                        null,
                    )
                repo.markClaim(source, "PENDING", 1)
                val refund = repo.createRefund(source, 2, money(50), account, at, "Asia/Shanghai")
                val before = repo.snapshot()
                assertTrue(before.summaries.isEmpty())
                assertTrue(before.statisticEntries.isEmpty())
                assertEquals(money(50), before.refunds.single().expenseReduction)
                assertThrows(FinanceValidationException::class.java) {
                    repo.settleClaims(
                        listOf(FinanceClaimAllocation(source, money(30), 3)),
                        account,
                        null,
                        RemainderAction.PARTIAL_KEEP,
                        at,
                        "Asia/Shanghai",
                    )
                }
                assertEquals(before, repo.snapshot())
                repo.cancelRefund(refund, 1, 3, "切换收款方式")
                val claim =
                    repo.settleClaims(
                        listOf(FinanceClaimAllocation(source, money(30), 4)),
                        account,
                        null,
                        RemainderAction.PARTIAL_KEEP,
                        at,
                        "Asia/Shanghai",
                    )
                val claimSnapshot = repo.snapshot()
                assertThrows(
                    FinanceValidationException::class.java,
                ) { repo.createRefund(source, 5, money(1), account, at, "Asia/Shanghai") }
                assertEquals(claimSnapshot, repo.snapshot())
                repo.cancelClaimSettlement(claim, 1, "报销撤回")
                repo.markClaim(source, "NONE", 6)
                val usd = repo.createAccount("USD", "USD", money(1000, "USD"), at)
                repo.saveRecord(
                    source,
                    RecordInput.Expense(money(100, "USD"), null, listOf(AccountAmount(usd, money(100, "USD"))), false, false),
                    at,
                    "Asia/Shanghai",
                    "历史退款均已撤销",
                    null,
                    7,
                )
                val current = repo.createRefund(source, 8, money(20, "USD"), usd, at, "Asia/Shanghai")
                expected = repo.snapshot()
                assertTrue(expected.summaries.isEmpty())
                assertEquals(money(1000), expected.accounts.single { it.id == account }.balance)
                assertEquals(money(920, "USD"), expected.accounts.single { it.id == usd }.balance)
                assertEquals(money(20, "USD"), expected.refunds.single { it.id == current }.expenseReduction)
                assertEquals("CANCELLED", expected.refunds.single { it.id == refund }.status)
                val overflow = repo.createAccount("溢出保护", "USD", money(Long.MAX_VALUE, "USD"), at)
                expected = repo.snapshot()
                assertThrows(FinanceValidationException::class.java) {
                    repo.createRefund(source, 9, money(1, "USD"), overflow, at, "Asia/Shanghai")
                }
                assertEquals(expected, repo.snapshot())
            }
            LocalFinanceRepository.openDataset(context, owner, epoch).use { assertEquals(expected, it.snapshot()) }
        }
}
