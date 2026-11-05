package dev.icos.app.data.local

import android.content.Context
import android.os.Looper
import dev.icos.app.domain.AccountAmount
import dev.icos.app.domain.BudgetAmounts
import dev.icos.app.domain.ClaimReceipt
import dev.icos.app.domain.ClaimReceiptEffect
import dev.icos.app.domain.Direction
import dev.icos.app.domain.Money
import dev.icos.app.domain.normalizeNewSavingEntryRemark
import dev.icos.app.domain.MoneyException
import dev.icos.app.domain.LoanCalculator
import dev.icos.app.domain.LoanDeleteStrategy
import dev.icos.app.domain.LoanFuturePolicy
import dev.icos.app.domain.LoanPlanInput
import dev.icos.app.domain.LoanPreview
import dev.icos.app.domain.LoanTerm
import dev.icos.app.domain.LoanDueState
import dev.icos.app.domain.RepaymentMethod
import dev.icos.app.domain.PeriodRange
import dev.icos.app.domain.PeriodResolver
import dev.icos.app.domain.PlanCalculator
import dev.icos.app.domain.RecordException
import dev.icos.app.domain.RecordInput
import dev.icos.app.domain.RemainderAction
import dev.icos.app.domain.ReportPeriod
import dev.icos.app.domain.RefundReceipt
import dev.icos.app.domain.SavingEntryInput
import dev.icos.app.domain.SavingEntryKind
import dev.icos.app.domain.SavingEntrySummary
import dev.icos.app.domain.SavingLinkedTransfer
import dev.icos.app.domain.SavingInterval
import dev.icos.app.domain.SavingIntervalUnit
import dev.icos.app.domain.SavingKind
import dev.icos.app.domain.SavingPlanInput
import dev.icos.app.domain.SavingPlanPatch
import dev.icos.app.domain.SavingPlanSummary
import dev.icos.app.domain.SavingState
import dev.icos.app.domain.SavingTransferStrategy
import dev.icos.app.domain.budgetAmounts
import dev.icos.app.domain.calculateClaimRecovery
import dev.icos.app.domain.calculateRefundRecovery
import dev.icos.app.domain.validateSavingPlan
import dev.icos.app.domain.validateSavingPlanDatePair
import dev.icos.app.domain.toSavingEntity
import dev.icos.app.domain.toSavingInput
import dev.icos.app.domain.initialSavedMoney
import dev.icos.app.domain.firstAmountMoney
import dev.icos.app.domain.incrementMoney
import dev.icos.app.domain.decimalForSaving
import dev.icos.app.domain.wideSavingMoney
import dev.icos.app.domain.savingUtcDate
import dev.icos.app.domain.orCurrent
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.math.BigDecimal
import java.math.BigInteger
import java.util.Calendar
import java.util.Locale
import java.util.Properties
import java.util.TimeZone
import java.util.UUID

class FinanceValidationException(
    val userMessage: String,
    cause: Throwable? = null,
) : IllegalArgumentException(userMessage, cause)

data class FinanceSnapshot(
    val ownerId: UUID,
    val epochId: UUID,
    val ledgerId: UUID,
    val accounts: List<FinanceAccount>,
    val categories: List<FinanceCategory>,
    val records: List<FinanceRecord>,
    val summaries: List<FinanceSummary>,
    val claimSettlements: List<FinanceClaimSettlement> = emptyList(),
    val statisticEntries: List<FinanceStatisticEntry> = emptyList(),
    val refunds: List<FinanceRefundReceipt> = emptyList(),
    val businessPreferences: BusinessPreferencesEntity? = null,
    val tagGroups: List<FinanceTagGroup> = emptyList(),
    val tags: List<FinanceTag> = emptyList(),
    val accountGroups: List<FinanceAccountGroup> = emptyList(),
    val savingPlans: List<SavingPlanSummary> = emptyList(),
    val savingEntries: List<SavingEntrySummary> = emptyList(),
    val loanContributions: List<FinanceLoanContribution> = emptyList(),
    val loanPlans: List<FinanceLoanPlan> = emptyList(),
    val creditProfiles: List<FinanceCreditProfile> = emptyList(),
    val creditCapacityEvents: List<FinanceCreditCapacityEvent> = emptyList(),
    val creditPaymentAllocations: List<FinanceCreditPaymentAllocation> = emptyList(),
    val installmentPlans: List<FinanceInstallmentPlan> = emptyList(),
    val installmentTerms: List<FinanceInstallmentTerm> = emptyList(),
    val ledgers: List<FinanceLedger> = emptyList(),
    val allCategories: List<FinanceCategory> = emptyList(),
    val relatedPersonProfiles: List<RelatedPersonProfile> = emptyList(),
    val creditPayments: List<FinanceCreditPayment> = emptyList(),
    val allAccounts: List<FinanceAccount> = accounts,
    val historicalAccountNames: Map<UUID, String> = emptyMap(),
)

data class RelatedPersonProfile(
    val accountId: UUID,
    val direction: String,
    val relation: String,
    val authorizedLimit: Money,
)

enum class BudgetScope { DEFAULT, CURRENT }

data class FinanceBudgetOverview(
    val period: ReportPeriod,
    val periodStart: String,
    val periodEnd: String,
    val currency: String,
    val enabled: Boolean,
    val preferenceId: UUID?,
    val preferenceRevision: Long,
    val templateId: UUID?,
    val templateRevision: Long,
    val defaultAmount: Money?,
    val overrideId: UUID?,
    val overrideRevision: Long,
    val overrideAmount: Money?,
    val used: Money,
    val remainingDays: Int,
    val otherCurrencies: List<String>,
    val categoryAmounts: Map<UUID, Money> = emptyMap(),
    val defaultCategoryAmounts: Map<UUID, Money> = emptyMap(),
    val overrideCategoryAmounts: Map<UUID, Money> = emptyMap(),
) {
    val limit: Money? = overrideAmount ?: defaultAmount
    val amounts: BudgetAmounts? = limit?.let { budgetAmounts(it, used, remainingDays) }
}

data class FinanceAccount(
    val id: UUID,
    val name: String,
    val currency: String,
    val balance: Money,
    val localRevision: Long,
    val archivedAt: Long?,
    val includeInAssets: Boolean,
    val selectable: Boolean,
    val groupId: UUID = UUID(0, 0),
    val sortOrder: Long = 0,
    val type: String = "ASSET",
    val visibilityMode: String = "ALL",
    val visibleLedgerIds: Set<UUID> = emptySet(),
)

data class FinanceAccountGroup(
    val id: UUID,
    val name: String,
    val sortOrder: Long,
    val isDefault: Boolean,
    val localRevision: Long,
)

data class FinanceLedger(
    val id: UUID,
    val name: String,
    val currency: String,
    val sortOrder: Long,
    val isDefault: Boolean,
    val archivedAt: Long?,
    val localRevision: Long,
)

data class FinanceCategory(
    val id: UUID,
    val name: String,
    val direction: Direction,
    val parentId: UUID? = null,
    val archivedAt: Long? = null,
    val sortOrder: Long = 0,
    val localRevision: Long = 1,
    val ledgerId: UUID = UUID(0, 0),
    val color: String? = null,
    val usesCanonicalColor: Boolean = false,
    val excludeBudget: Boolean = false,
    val purpose: String? = null,
)

data class FinanceRecord(
    val id: UUID,
    val input: RecordInput,
    val occurredAtMillis: Long,
    val timezone: String,
    val remark: String,
    val categoryId: UUID?,
    val localRevision: Long,
    val claimState: String = "NONE",
    val tagIds: List<UUID> = emptyList(),
    val accountMutationSupported: Boolean = true,
    val currentEventId: UUID? = null,
    val parentRecordId: UUID? = null,
    val attachmentIds: List<UUID> = emptyList(),
)

data class FinanceClaimAllocation(
    val originRecordId: UUID,
    val actualAmount: Money,
    val expectedLocalRevision: Long = 0,
)

data class FinanceClaimSettlement(
    val id: UUID,
    val actualAmount: Money,
    val accountId: UUID?,
    val incomeCategoryId: UUID?,
    val remainderAction: RemainderAction,
    val settledAtMillis: Long,
    val timezone: String,
    val allocations: List<FinanceClaimAllocation>,
    val status: String,
    val localRevision: Long,
    val createdSequence: Long,
)

data class FinanceRefundReceipt(
    val id: UUID,
    val originRecordId: UUID,
    val amount: Money,
    val accountId: UUID,
    val refundAtMillis: Long,
    val timezone: String,
    val status: String,
    val localRevision: Long,
    val createdSequence: Long,
    val expenseReduction: Money,
    val excessAmount: Money,
)

data class FinanceStatisticEntry(
    val eventId: UUID,
    val recordId: UUID?,
    val categoryId: UUID?,
    val direction: Direction,
    val amount: Money,
    val occurredAtMillis: Long,
    val timezone: String,
    val remark: String,
    val accountAmounts: Map<UUID, Money>? = null,
)

internal fun projectLoanStatisticEntries(
    contributions: List<FinanceLoanContribution>,
    events: Map<String, EventEntity>,
    records: Map<String, RecordEntity>,
    ownerId: String,
    epochId: String,
    reversedEventIds: Set<String>,
): List<FinanceStatisticEntry> =
    contributions.mapNotNull { contribution ->
        if (!contribution.statisticEligible || contribution.amount.unscaled == 0L) return@mapNotNull null
        val direction = contribution.statisticDirection ?: return@mapNotNull null
        val eventId = contribution.eventId?.toString() ?: return@mapNotNull null
        val event = events[eventId]?.takeIf {
            it.id == eventId && it.ownerId == ownerId && it.epochId == epochId &&
                it.reversalOf == null && it.id !in reversedEventIds
        } ?: return@mapNotNull null
        val sourceRecordId = event.recordId ?: return@mapNotNull null
        val sourceRecord = records[sourceRecordId]?.takeIf {
            it.id == sourceRecordId && it.ownerId == ownerId && it.epochId == epochId
        } ?: return@mapNotNull null
        FinanceStatisticEntry(
            UUID.fromString(event.id),
            UUID.fromString(sourceRecord.id),
            event.categoryId?.let(UUID::fromString),
            direction,
            contribution.amount,
            contribution.occurredAtMillis,
            event.timezone,
            event.remark,
        )
    }

data class FinanceSummary(
    val currency: String,
    val income: Money,
    val expense: Money,
    val budgetExpense: Money,
) {
    val balance: Money = income.checkedSubtract(expense)
}

data class FinanceLoanContribution(
    val planId: UUID,
    val termIndex: Int?,
    val eventId: UUID?,
    val kind: String,
    val amount: Money,
    val statisticDirection: Direction?,
    val statisticEligible: Boolean,
    val budgetAmount: Money,
    val categoryId: UUID?,
    val occurredAtMillis: Long,
)

data class LoanDisbursement(val actualAccountId: UUID, val occurredAtMillis: Long)

data class FinanceLoanPlan(
    val id: UUID,
    val accountId: UUID,
    val principal: Money,
    val localRevision: Long,
    val state: String,
    val terms: List<LoanTerm>,
    val paidTermCount: Int = 0,
    val annualRateNumerator: Long = 0,
    val annualRateDenominator: Long = 1,
    val repaymentType: String = "EQUAL_PAYMENT",
    val firstDueDate: String = "",
    val rateChanges: List<FinanceLoanRateChange> = emptyList(),
    val payments: List<FinanceLoanPayment> = emptyList(),
)

data class FinanceLoanPayment(
    val termIndex: Int,
    val principal: Money,
    val interest: Money,
    val paidAtMillis: Long?,
)

data class FinanceLoanRateChange(
    val effectiveTerm: Int,
    val oldRateNumerator: Long,
    val oldRateDenominator: Long,
    val newRateNumerator: Long,
    val newRateDenominator: Long,
    val fromRevision: Long,
    val toRevision: Long,
    val changedAtMillis: Long,
)

/** One active guest dataset; calls, including close, belong on the caller's serial background executor. */
class LocalFinanceRepository private constructor(
    private val database: DatasetDatabase,
    private val ownerId: UUID,
    private val epochId: UUID,
    private val accountDataset: Boolean = false,
) : Closeable {
    private val dao = database.finance()
    private val budgetDao = database.budget()
    private val searchPreferencesDao = database.searchPreferences()
    private val businessPreferencesDao = database.businessPreferences()
    private val recordTemplateDao = database.recordTemplates()
    private val tagDao = database.tags()
    private val savingDao = database.savings()
    private val loanDao = database.loans()
    private val creditDao = database.credit()
    private val relatedPersonDao = database.relatedPersons()
    private val owner = ownerId.toString()
    private val epoch = epochId.toString()
    private var closed = false
    private var selectedLedgerId: String? = null
    private var cachedBatchFacts: Facts? = null
    private var batchApplying = false

    companion object {
        private val timezones = TimeZone.getAvailableIDs().toSet()

        fun openGuest(context: Context): LocalFinanceRepository {
            offMain()
            try {
                val (owner, epoch) = GuestIdentity.readOrCreate(context.applicationContext)
                return openDataset(context.applicationContext, owner, epoch)
            } catch (_: Exception) {
                throw FinanceValidationException("本地账本无法打开，请保留数据并恢复存储")
            }
        }

        internal fun openDataset(
            context: Context,
            owner: UUID,
            epoch: UUID,
        ): LocalFinanceRepository {
            offMain()
            val database = EncryptedDatasetStore.open(context, owner, epoch)
            return initialize(context, owner, epoch, database)
        }

        internal fun openAccountDataset(
            context: Context,
            owner: UUID,
            epoch: UUID,
        ): LocalFinanceRepository {
            offMain()
            return initialize(context, owner, epoch, EncryptedDatasetStore.openAccount(context, owner, epoch), accountDataset = true)
        }

        internal fun initialize(
            context: Context,
            owner: UUID,
            epoch: UUID,
            database: DatasetDatabase,
            accountDataset: Boolean = false,
        ): LocalFinanceRepository {
            try {
                return LocalFinanceRepository(database, owner, epoch, accountDataset).also { repository ->
                    repository.transaction {
                        repository.checkIdentity()
                        repository.initializeDefaults()
                        repository.initializeTagDefaults()
                        repository.readSnapshot()
                    }
                }
            } catch (failure: Exception) {
                database.close()
                throw failure
            }
        }

        private fun offMain() {
            check(Looper.myLooper() != Looper.getMainLooper()) { "Use a background executor" }
        }
    }

    fun createLedger(name: String, currency: String): UUID = transaction {
        valid(!accountDataset, "服务器账号账本当前只读")
        val cleaned = name.trim()
        valid(cleaned.isNotEmpty() && cleaned.codePointCount(0, cleaned.length) <= 100 && cleaned.none { it == '\u0000' }, "账本名称无效")
        valid(currency.matches(Regex("[A-Z]{3}")), "账本币种无效")
        Money(0, 0, currency)
        val rows = dao.ledgers().filter { it.ownerId == owner && it.epochId == epoch && it.deletedAt == null }
        val now = System.currentTimeMillis()
        val id = newId()
        val ledger = LedgerEntity(id, owner, epoch, cleaned, currency, now, now, 1, null, rows.size.toLong(), rows.none { it.isDefault }, null)
        dao.insert(ledger)
        val defaults = listOf("EXPENSE" to Direction.EXPENSE, "INCOME" to Direction.INCOME, "TRANSFER_FEE" to Direction.EXPENSE, "TRANSFER_BONUS" to Direction.INCOME)
        defaults.forEachIndexed { index, (purpose, direction) ->
            dao.insert(CategoryEntity(newId(), owner, epoch, id, when (purpose) { "EXPENSE" -> "其他支出"; "INCOME" -> "其他收入"; "TRANSFER_FEE" -> "转账手续费"; else -> "转账优惠" }, direction.name, purpose, now, now, 1, null, index.toLong()))
        }
        if (rows.isEmpty()) selectedLedgerId = id
        journal(id, 1, 0, "ledger.create", JSONObject().put("name", cleaned).put("baseCurrency", currency).put("isDefault", rows.isEmpty()).put("sortOrder", rows.size), now)
        UUID.fromString(id)
    }

    fun updateLedger(id: UUID, expectedRevision: Long, name: String, sortOrder: Long, makeDefault: Boolean) = transaction {
        valid(!accountDataset, "服务器账号账本当前只读")
        val old = dao.ledgers().singleOrNull { it.id == id.toString() && it.ownerId == owner && it.epochId == epoch && it.deletedAt == null }
            ?: throw FinanceValidationException("账本不存在")
        valid(old.localRevision == expectedRevision && sortOrder >= 0 && (!makeDefault || old.archivedAt == null), "账本已变更、归档账本不能设为默认，或顺序无效")
        val cleaned = name.trim()
        valid(cleaned.isNotEmpty() && cleaned.codePointCount(0, cleaned.length) <= 100 && cleaned.none { it == '\u0000' }, "账本名称无效")
        val now = System.currentTimeMillis()
        if (makeDefault) {
            dao.ledgers().filter { it.ownerId == owner && it.epochId == epoch && it.id != old.id && it.isDefault && it.deletedAt == null }.forEach { previous ->
                dao.update(previous.copy(isDefault = false, updatedAt = now, localRevision = Math.addExact(previous.localRevision, 1)))
                journal(previous.id, previous.localRevision + 1, previous.localRevision, "ledger.update", JSONObject().put("isDefault", false).put("name", previous.name).put("sortOrder", previous.sortOrder), now)
            }
            if (!old.isDefault) dao.unsetOtherDefaults(owner, epoch, old.id, now)
        }
        val next = old.copy(name = cleaned, sortOrder = sortOrder, isDefault = if (makeDefault) true else old.isDefault, updatedAt = now, localRevision = Math.addExact(old.localRevision, 1))
        dao.update(next)
        journal(next.id, next.localRevision, old.localRevision, "ledger.update", JSONObject().put("name", cleaned).put("sortOrder", sortOrder).put("isDefault", next.isDefault), now)
    }

    fun archiveLedger(id: UUID, expectedRevision: Long, archived: Boolean) = transaction {
        valid(!accountDataset, "服务器账号账本当前只读")
        val old = dao.ledgers().singleOrNull { it.id == id.toString() && it.ownerId == owner && it.epochId == epoch && it.deletedAt == null }
            ?: throw FinanceValidationException("账本不存在")
        valid(old.localRevision == expectedRevision && !(old.isDefault && archived), "默认账本不可归档或账本已变更")
        val now = System.currentTimeMillis()
        val next = old.copy(archivedAt = if (archived) now else null, updatedAt = now, localRevision = Math.addExact(old.localRevision, 1))
        dao.update(next)
        journal(next.id, next.localRevision, old.localRevision, "ledger.archive", JSONObject().put("archived", archived), now)
        if (selectedLedgerId == old.id && archived) selectedLedgerId = dao.ledgers().firstOrNull { it.id != old.id && it.deletedAt == null && it.archivedAt == null }?.id
    }

    fun copyLedger(sourceId: UUID, name: String): Map<UUID, UUID> = transaction {
        valid(!accountDataset, "服务器账号账本当前只读")
        val source = dao.ledgers().singleOrNull { it.id == sourceId.toString() && it.ownerId == owner && it.epochId == epoch && it.deletedAt == null }
            ?: throw FinanceValidationException("来源账本不存在")
        val cleaned = name.trim()
        valid(cleaned.isNotEmpty() && cleaned.codePointCount(0, cleaned.length) <= 100 && cleaned.none { it == '\u0000' }, "账本名称无效")
        val rows = dao.ledgers().filter { it.ownerId == owner && it.epochId == epoch && it.deletedAt == null }
        val now = System.currentTimeMillis()
        val id = newId()
        dao.insert(LedgerEntity(id, owner, epoch, cleaned, source.baseCurrency, now, now, 1, null, rows.size.toLong(), false, null))
        val mapping = mutableMapOf<UUID, UUID>()
        dao.categories().filter { it.ledgerId == source.id && it.deletedAt == null && it.parentId == null }.sortedBy { it.sortOrder }.forEach { root ->
            val rootId = newId(); mapping[UUID.fromString(root.id)] = UUID.fromString(rootId)
            dao.insert(root.copy(id = rootId, ledgerId = id, localRevision = 1, createdAt = now, updatedAt = now))
            dao.categories().filter { it.parentId == root.id && it.deletedAt == null }.sortedBy { it.sortOrder }.forEach { child ->
                val childId = newId(); mapping[UUID.fromString(child.id)] = UUID.fromString(childId)
                dao.insert(child.copy(id = childId, ledgerId = id, parentId = rootId, localRevision = 1, createdAt = now, updatedAt = now))
            }
        }
        journal(id, 1, 0, "ledger.copy", JSONObject().put("sourceLedgerId", source.id).put("name", cleaned).put("categoryIdMapping", JSONObject().also { obj -> mapping.forEach { (from, to) -> obj.put(from.toString(), to.toString()) } }), now)
        mapping
    }

    internal fun exportSharedCategories(ledgerId: UUID, direction: String, selectedIds: List<UUID>): List<dev.icos.app.data.remote.SharedCategoryConfig> = transaction {
        valid(!accountDataset, "账号分类分享由服务器账号通道创建")
        valid(direction in setOf("EXPENSE", "INCOME") && selectedIds.isNotEmpty() && selectedIds.size <= 100 && selectedIds.distinct().size == selectedIds.size && selectedIds.none { it == UUID(0, 0) }, "分享分类选择无效")
        val data = facts()
        valid(data.ledger.id == ledgerId.toString(), "所选账本不是当前访客账本")
        val rows = dao.categories().filter { it.ownerId == owner && it.epochId == epoch && it.ledgerId == ledgerId.toString() && it.deletedAt == null && it.archivedAt == null && it.direction == direction }
        valid(rows.size == dao.categories().count { it.ledgerId == ledgerId.toString() && it.deletedAt == null && it.archivedAt == null && it.direction == direction } && dao.categories().none { it.ledgerId == ledgerId.toString() && it.deletedAt == null && it.archivedAt == null && it.direction == direction && (it.ownerId != owner || it.epochId != epoch) }, "分类所有者或数据集不匹配")
        val byId = rows.associateBy { UUID.fromString(it.id) }
        val chosen = selectedIds.map { byId[it] ?: throw FinanceValidationException("所选分类不存在、已归档或方向不匹配") }
        valid(chosen.all { it.parentId == null || chosen.any { parent -> parent.id == it.parentId } }, "分享子分类时必须同时选择父分类")
        chosen.sortedWith(compareBy<CategoryEntity> { it.parentId != null }.thenBy { it.sortOrder }.thenBy { it.id }).map {
            dev.icos.app.data.remote.SharedCategoryConfig(UUID.fromString(it.id), it.direction, it.parentId?.let(UUID::fromString), it.name, null, null, it.sortOrder, it.excludeBudget)
        }
    }

    internal fun importSharedCategories(categories: List<dev.icos.app.data.remote.SharedCategoryConfig>): Map<UUID, UUID> = transaction {
        valid(!accountDataset, "服务器账号账本当前只读")
        valid(categories.isNotEmpty() && categories.size <= 100 && categories.map { it.sourceId }.distinct().size == categories.size, "分享分类数量或标识无效")
        val data = facts()
        val targetLedgerId = data.ledger.id
        val allRows = dao.categories().filter { it.ownerId == owner && it.epochId == epoch && it.ledgerId == targetLedgerId && it.deletedAt == null }.toMutableList()
        valid(allRows.size == dao.categories().count { it.ledgerId == targetLedgerId && it.deletedAt == null } && dao.categories().none { it.ledgerId == targetLedgerId && it.deletedAt == null && (it.ownerId != owner || it.epochId != epoch) }, "目标分类所有者或数据集不匹配")
        val bySource = categories.associateBy { it.sourceId }
        categories.forEach { category ->
            valid(category.direction in setOf("EXPENSE", "INCOME") && category.name.isNotBlank() && category.name == category.name.trim() && category.name.codePointCount(0, category.name.length) <= 100 && category.name.none { it == '\u0000' }, "分享分类字段无效")
            valid(category.iconRef == null && category.color == null, "分享分类的图标和颜色当前不受支持")
            valid(category.sortOrder in 0..1_000_000, "分享分类字段无效")
            valid(categories.none { other -> other.sourceId != category.sourceId && other.direction == category.direction && other.parentSourceId == category.parentSourceId && other.name == category.name }, "分享配置含重复同层级分类名称")
            valid(category.parentSourceId == null || bySource[category.parentSourceId]?.let { it.parentSourceId == null && it.direction == category.direction } == true, "分享分类父级无效")
        }
        val mapping = linkedMapOf<UUID, UUID>()
        categories.sortedWith(compareBy<dev.icos.app.data.remote.SharedCategoryConfig> { it.parentSourceId != null }.thenBy { it.sortOrder }.thenBy { it.sourceId }).forEach { imported ->
            val parentId = imported.parentSourceId?.let { mapping[it] ?: throw FinanceValidationException("分享分类父级无效") }
            val sameName = allRows.filter { it.direction == imported.direction && it.name == imported.name && it.parentId == parentId?.toString() }
            val archivedCollision = sameName.any { it.archivedAt != null }
            valid(!archivedCollision, "目标账本存在同名归档分类；请先处理归档分类")
            val compatible = sameName.singleOrNull { it.archivedAt == null && it.excludeBudget == imported.excludeBudget && it.sortOrder == imported.sortOrder }
            if (compatible != null) {
                mapping[imported.sourceId] = UUID.fromString(compatible.id)
            } else {
                valid(sameName.none { it.archivedAt == null }, "目标账本存在同名分类但配置不兼容")
                val now = System.currentTimeMillis()
                val row = CategoryEntity(newId(), owner, epoch, targetLedgerId, imported.name, imported.direction, "CUSTOM", now, now, 1, null,
                    imported.sortOrder, parentId?.toString(), null, imported.excludeBudget)
                dao.insert(row)
                journal(row.id, 1, 0, "category.create", JSONObject().put("ledgerId", row.ledgerId).put("name", row.name).put("direction", row.direction)
                    .put("parentId", row.parentId ?: JSONObject.NULL).put("purpose", row.purpose).put("excludeBudget", row.excludeBudget).put("sortOrder", row.sortOrder), now)
                mapping[imported.sourceId] = UUID.fromString(row.id)
                allRows.plusAssign(row)
            }
        }
        mapping
    }

    fun saveCategory(
        id: UUID?,
        expectedRevision: Long?,
        name: String,
        direction: Direction,
        parentId: UUID?,
        purpose: String = "CUSTOM",
        excludeBudget: Boolean? = null,
    ): UUID = transaction {
        valid(!accountDataset, "服务器账号账本当前只读")
        val ledger = selectedLedger(dao.ledgers().filter { it.ownerId == owner && it.epochId == epoch })
        val categories = dao.categories().filter { it.ownerId == owner && it.epochId == epoch && it.ledgerId == ledger.id }
        val cleaned = name.trim()
        valid(cleaned.isNotEmpty() && cleaned.codePointCount(0, cleaned.length) <= 100 && cleaned.none { it == '\u0000' }, "分类名称无效")
        val parent = parentId?.let { selected -> categories.firstOrNull { it.id == selected.toString() } }
        valid(parentId == null || (parent != null && parent.parentId == null && parent.direction == direction.name && parent.deletedAt == null), "上级分类无效")
        val old = id?.let { selected -> categories.firstOrNull { it.id == selected.toString() } }
        val now = System.currentTimeMillis()
        if (old == null) {
            valid(id == null && expectedRevision == null, "分类已变更，请刷新后重试")
            val requestedExcludeBudget = excludeBudget ?: false
            valid(direction == Direction.EXPENSE || !requestedExcludeBudget, "仅支出分类可排除预算")
            valid(categories.none { it.parentId == parent?.id && it.direction == direction.name && it.name == cleaned && it.deletedAt == null }, "同层级已有同名分类")
            val row = CategoryEntity(newId(), owner, epoch, ledger.id, cleaned, direction.name, purpose, now, now, 1, null,
                categories.count { it.parentId == parent?.id }.toLong(), parent?.id, null, requestedExcludeBudget)
            dao.insert(row)
            journal(row.id, 1, 0, "category.create", JSONObject().put("ledgerId", row.ledgerId).put("name", cleaned).put("direction", direction.name)
                .put("parentId", row.parentId ?: JSONObject.NULL).put("purpose", purpose).put("excludeBudget", row.excludeBudget).put("sortOrder", row.sortOrder), now)
            UUID.fromString(row.id)
        } else {
            valid(old.ledgerId == ledger.id && old.deletedAt == null && old.localRevision == expectedRevision, "分类已变更，请刷新后重试")
            valid(old.parentId == parent?.id && old.direction == direction.name, "请使用分类移动操作更改层级或方向")
            valid(categories.none { it.id != old.id && it.parentId == old.parentId && it.direction == old.direction && it.name == cleaned && it.deletedAt == null }, "同层级已有同名分类")
            val nextExcludeBudget = excludeBudget ?: old.excludeBudget
            valid(direction == Direction.EXPENSE || !nextExcludeBudget, "仅支出分类可排除预算")
            if (nextExcludeBudget != old.excludeBudget) validateCategoryBudgetExclusionChange(old)
            if (cleaned != old.name || nextExcludeBudget != old.excludeBudget) {
                val next = old.copy(name = cleaned, excludeBudget = nextExcludeBudget, updatedAt = now, localRevision = Math.addExact(old.localRevision, 1))
                dao.update(next)
                journal(next.id, next.localRevision, old.localRevision, "category.update", JSONObject().put("ledgerId", next.ledgerId).put("name", cleaned)
                    .put("direction", next.direction).put("parentId", next.parentId ?: JSONObject.NULL).put("purpose", next.purpose)
                    .put("excludeBudget", next.excludeBudget).put("sortOrder", next.sortOrder), now)
            }
            UUID.fromString(old.id)
        }
    }

    fun setBudgetExcluded(id: UUID, expectedRevision: Long, excludeBudget: Boolean) = transaction {
        valid(!accountDataset, "服务器账号账本当前只读")
        val category = dao.categories().singleOrNull {
            it.id == id.toString() && it.ownerId == owner && it.epochId == epoch && it.deletedAt == null
        } ?: throw FinanceValidationException("分类不存在")
        valid(category.localRevision == expectedRevision, "分类已变更，请刷新后重试")
        valid(category.archivedAt == null && dao.ledgers().any {
            it.id == category.ledgerId && it.ownerId == owner && it.epochId == epoch && it.deletedAt == null && it.archivedAt == null
        }, "请先恢复分类及所属账本")
        category.parentId?.let { parentId ->
            valid(dao.categories().any {
                it.id == parentId && it.ledgerId == category.ledgerId && it.direction == category.direction &&
                    it.parentId == null && it.deletedAt == null && it.archivedAt == null
            }, "上级分类不可用，请刷新后重试")
        }
        if (category.excludeBudget == excludeBudget) return@transaction
        valid(category.direction == Direction.EXPENSE.name || !excludeBudget, "仅支出分类可排除预算")
        validateCategoryBudgetExclusionChange(category)
        val now = System.currentTimeMillis()
        val next = category.copy(excludeBudget = excludeBudget, updatedAt = now, localRevision = Math.addExact(category.localRevision, 1))
        dao.update(next)
        journal(next.id, next.localRevision, category.localRevision, "category.update", JSONObject().put("ledgerId", next.ledgerId)
            .put("name", next.name).put("direction", next.direction).put("parentId", next.parentId ?: JSONObject.NULL)
            .put("purpose", next.purpose).put("excludeBudget", next.excludeBudget).put("sortOrder", next.sortOrder), now)
    }

    private fun validateCategoryBudgetExclusionChange(category: CategoryEntity) {
        valid(category.archivedAt == null, "请先恢复分类，再修改预算规则")
        val categoryRows = dao.categories().filter { it.ownerId == owner && it.epochId == epoch && it.ledgerId == category.ledgerId }
        valid(categoryRows.none { it.parentId == category.id }, "存在历史子分类，不能切换预算排除")
        valid(dao.events().none { it.categoryId == category.id } &&
            dao.statistics().none { it.categoryId == category.id } && dao.budgets().none { it.categoryId == category.id },
            "分类存在历史账单或统计引用，不能切换预算排除")
        valid(database.openHelper.readableDatabase.query(
            "SELECT EXISTS(SELECT 1 FROM finance_record_template WHERE ownerId=? AND epochId=? AND categoryId=?)",
            arrayOf(owner, epoch, category.id),
        ).use { it.moveToFirst() && it.getInt(0) == 0 }, "分类存在模板历史引用，不能切换预算排除")
    }

    fun moveCategory(id: UUID, expectedRevision: Long, targetLedgerId: UUID, direction: Direction, parentId: UUID?) = transaction {
        valid(!accountDataset, "服务器账号账本当前只读")
        val source = dao.categories().singleOrNull { it.id == id.toString() && it.ownerId == owner && it.epochId == epoch && it.deletedAt == null }
            ?: throw FinanceValidationException("分类不存在")
        valid(source.localRevision == expectedRevision, "分类已变更，请刷新后重试")
        valid(dao.categories().none { it.parentId == source.id && it.deletedAt == null }, "仅支持移动无子分类的分类")
        val targetLedger = dao.ledgers().singleOrNull { it.id == targetLedgerId.toString() && it.ownerId == owner && it.epochId == epoch && it.deletedAt == null && it.archivedAt == null }
            ?: throw FinanceValidationException("目标账本不存在或已归档")
        val parent = parentId?.let { pid -> dao.categories().singleOrNull { it.id == pid.toString() && it.ledgerId == targetLedger.id && it.direction == direction.name && it.parentId == null && it.deletedAt == null && it.archivedAt == null } }
        valid(parentId == null || parent != null, "目标上级分类无效")
        valid(dao.events().none { it.categoryId == source.id } && budgetDao.templates().none { it.ledgerId == source.ledgerId } && budgetDao.overrides().none { it.ledgerId == source.ledgerId } && recordTemplateDao.active().none { it.categoryId == source.id || it.categoryId?.let { templateCategory -> dao.categories().any { category -> category.id == templateCategory && category.parentId == source.id } } == true }, "分类被资金记录或预算引用，需先迁移或删除引用")
        valid(dao.categories().none { it.id != source.id && it.ledgerId == targetLedger.id && it.parentId == parent?.id && it.direction == direction.name && it.name == source.name && it.deletedAt == null }, "目标层级已有同名分类")
        val now = System.currentTimeMillis()
        val next = source.copy(ledgerId = targetLedger.id, direction = direction.name, parentId = parent?.id, localRevision = Math.addExact(source.localRevision, 1), updatedAt = now)
        dao.update(next)
        journal(next.id, next.localRevision, source.localRevision, "category.move", JSONObject().put("targetLedgerId", targetLedger.id).put("newDirection", direction.name).put("newParentId", next.parentId ?: JSONObject.NULL), now)
    }

    fun archiveCategory(id: UUID, expectedRevision: Long, archived: Boolean) = transaction {
        valid(!accountDataset, "服务器账号账本当前只读")
        val old = dao.categories().singleOrNull { it.id == id.toString() && it.ownerId == owner && it.epochId == epoch && it.deletedAt == null }
            ?: throw FinanceValidationException("分类不存在")
        valid(old.localRevision == expectedRevision, "分类已变更，请刷新后重试")
        val now = System.currentTimeMillis()
        dao.update(old.copy(archivedAt = if (archived) now else null, updatedAt = now, localRevision = Math.addExact(old.localRevision, 1)))
        if (archived) dao.categories().filter { it.parentId == old.id && it.deletedAt == null && it.archivedAt == null }.forEach { child ->
            val childNext = child.copy(archivedAt = now, updatedAt = now, localRevision = Math.addExact(child.localRevision, 1))
            dao.update(childNext)
            journal(childNext.id, childNext.localRevision, child.localRevision, "category.archive", JSONObject().put("archived", true), now)
        }
        journal(old.id, old.localRevision + 1, old.localRevision, "category.archive", JSONObject().put("archived", archived), now)
    }

    fun deleteCategory(id: UUID, expectedRevision: Long, strategy: String, targetId: UUID? = null) = transaction {
        valid(!accountDataset, "服务器账号账本当前只读")
        val source = dao.categories().singleOrNull { it.id == id.toString() && it.ownerId == owner && it.epochId == epoch && it.deletedAt == null }
            ?: throw FinanceValidationException("分类不存在")
        valid(source.localRevision == expectedRevision && dao.categories().none { it.parentId == source.id && it.deletedAt == null }, "分类已变更或仍有子分类")
        val references = dao.events().filter { it.categoryId == source.id }
        when (strategy) {
            "MIGRATE_RECORDS" -> {
                val target = targetId?.let { dao.categories().singleOrNull { row -> row.id == it.toString() && row.ownerId == owner && row.epochId == epoch && row.deletedAt == null } }
                    ?: throw FinanceValidationException("请选择迁移目标分类")
                valid(target.ledgerId == source.ledgerId && target.direction == source.direction && target.archivedAt == null && target.parentId == source.parentId, "目标分类必须处于相同账本、层级与方向")
                val duplicate = dao.categories().any {
                    it.id != source.id && it.id != target.id && it.ledgerId == source.ledgerId &&
                        it.parentId == source.parentId && it.direction == source.direction &&
                        it.name == source.name && it.deletedAt == null
                }
                valid(!duplicate, "迁移后的分类名称冲突")
                valid(dao.budgets().none { it.categoryId == source.id && dao.budgets().any { other -> other.eventId == it.eventId && other.categoryId == target.id } }, "迁移会造成预算重复")
                valid(recordTemplateDao.active().none { template -> template.categoryId == source.id || template.categoryId?.let { templateCategory -> dao.categories().any { category -> category.id == templateCategory && category.parentId == source.id } } == true }, "模板引用需先修改")
                valid(dao.events().none { it.categoryId == source.id } || dao.statistics().filter { it.categoryId == source.id }.all { stat -> dao.events().any { it.id == stat.eventId && it.categoryId == source.id } }, "存在非记录分类引用，当前无法安全迁移")
                val records = references.mapNotNull { it.recordId }.distinct().mapNotNull { recordId -> dao.records().singleOrNull { it.id == recordId && it.deletedAt == null } }
                    .filter { record -> facts().currentEvent(record).categoryId == source.id }
                valid(records.size == references.mapNotNull { it.recordId }.distinct().size && records.all { it.claimState == "NONE" && !hasPostedRecovery(facts(), it.id) && !hasActiveSavingLink(it.id) }, "关联账单含理赔、退款、报销或存钱依赖，当前无法安全迁移")
                valid(loanDao.plans().none { plan -> plan.disbursementEventId in references.map { it.id } || loanDao.terms().any { term -> (term.paidEventId in references.map { row -> row.id }) || (term.postedEventId in references.map { row -> row.id }) } }, "分类关联贷款事件，当前无法安全迁移")
                records.forEach { record ->
                    val oldEvent = facts().currentEvent(record)
                    val reversalRevision = Math.addExact(record.localRevision, 1)
                    val nextRevision = Math.addExact(record.localRevision, 2)
                    val now = System.currentTimeMillis()
                    reverse(oldEvent, reversalRevision, now)
                    val newEvent = oldEvent.copy(id = newId(), localRevision = nextRevision, categoryId = target.id, reversalOf = null, createdAt = now)
                    dao.insert(newEvent)
                    dao.allocations().filter { it.eventId == oldEvent.id }.forEachIndexed { position, allocation -> dao.insert(allocation.copy(id = newId(), eventId = newEvent.id, position = position)) }
                    dao.postings().filter { it.eventId == oldEvent.id }.forEachIndexed { position, posting -> dao.insert(posting.copy(id = newId(), eventId = newEvent.id, position = position, reversalOf = null)) }
                    dao.statistics().filter { it.eventId == oldEvent.id && it.reversalOf == null }.forEachIndexed { position, statistic -> dao.insert(statistic.copy(id = newId(), eventId = newEvent.id, position = position, categoryId = target.id, reversalOf = null)) }
                    dao.budgets().filter { it.eventId == oldEvent.id && it.reversalOf == null }.forEachIndexed { position, budget -> dao.insert(budget.copy(id = newId(), eventId = newEvent.id, position = position, categoryId = target.id, reversalOf = null)) }
                    dao.update(record.copy(updatedAt = now, localRevision = nextRevision))
                    journal(record.id, reversalRevision, record.localRevision, "CATEGORY_MIGRATION_REVERSE", JSONObject().put("eventId", oldEvent.id).put("reversalEventId", oldEvent.id), now)
                    journal(record.id, nextRevision, reversalRevision, "RECORD_EDIT", JSONObject().put("categoryId", target.id).put("migratedFrom", source.id), now)
                }
            }
            "DELETE_RECORDS" -> {
        valid(dao.budgets().none { it.categoryId == source.id } && recordTemplateDao.active().none { it.categoryId == source.id } && dao.records().none { record -> references.any { it.recordId == record.id && record.claimState != "NONE" } }, "分类仍被预算、模板或理赔引用")
                val referencedRecordIds = references.mapNotNull { it.recordId }.distinct()
                val records = referencedRecordIds.mapNotNull { recordId -> dao.records().singleOrNull { it.id == recordId && it.deletedAt == null } }
                    .filter { record -> facts().currentEvent(record).categoryId == source.id }
                valid(records.size == referencedRecordIds.size, "分类含已删除或已迁移账单，当前无法安全删除")
                records.forEach { record ->
                    valid(record.claimState == "NONE" && !hasPostedRecovery(facts(), record.id) && !hasActiveSavingLink(record.id), "关联账单含理赔、退款、报销或存钱依赖")
                }
                valid(loanDao.plans().none { plan -> plan.disbursementEventId in references.map { it.id } || loanDao.terms().any { term -> (term.paidEventId in references.map { row -> row.id }) || (term.postedEventId in references.map { row -> row.id }) } }, "分类关联贷款事件，当前无法安全删除")
                val now = System.currentTimeMillis()
                records.forEach { record ->
                    val current = facts().currentEvent(record)
                    val revision = Math.addExact(record.localRevision, 1)
                    val reversal = reverse(current, revision, now)
                    dao.update(record.copy(deletedAt = now, updatedAt = now, localRevision = revision))
                    tagDao.deleteRecordTags(record.id)
                    journal(record.id, revision, record.localRevision, "RECORD_DELETE", JSONObject().put("reason", "CATEGORY_DELETE").put("reversalEventId", reversal.id), now)
                }
            }
            else -> throw FinanceValidationException("不支持的分类删除策略")
        }
        val now = System.currentTimeMillis()
        dao.update(source.copy(deletedAt = now, updatedAt = now, localRevision = Math.addExact(source.localRevision, 1)))
        journal(source.id, source.localRevision + 1, source.localRevision, "category.delete", JSONObject().put("strategy", strategy).put("targetId", targetId ?: JSONObject.NULL), now)
    }

    fun deleteLedger(id: UUID, expectedRevision: Long, strategy: String) = transaction {
        valid(!accountDataset, "服务器账号账本当前只读")
        val source = dao.ledgers().singleOrNull { it.id == id.toString() && it.ownerId == owner && it.epochId == epoch && it.deletedAt == null }
            ?: throw FinanceValidationException("账本不存在")
        valid(source.localRevision == expectedRevision && !source.isDefault && strategy == "DELETE_LEDGER", "默认账本不可删除或删除策略无效")
        valid(dao.events().none { it.ledgerId == source.id } && dao.categories().none { it.ledgerId == source.id && it.deletedAt == null } && budgetDao.preferences().none { it.ledgerId == source.id } && budgetDao.templates().none { it.ledgerId == source.id } && budgetDao.overrides().none { it.ledgerId == source.id } && recordTemplateDao.active().isEmpty() && savingDao.entries().none { entry -> entry.linkedRecordId?.let { linked -> dao.events().any { it.recordId == linked && it.ledgerId == source.id } } == true } && loanDao.plans().none { plan -> dao.events().any { event -> event.recordId == plan.disbursementEventId && event.ledgerId == source.id } }, "账本仍有账单、分类、预算或相关资金记录；当前只允许空账本删除")
        val now = System.currentTimeMillis()
        val next = source.copy(deletedAt = now, updatedAt = now, localRevision = Math.addExact(source.localRevision, 1))
        dao.update(next)
        journal(source.id, next.localRevision, source.localRevision, "ledger.delete", JSONObject().put("strategy", strategy), now)
        if (selectedLedgerId == source.id) selectedLedgerId = dao.ledgers().firstOrNull { it.id != source.id && it.deletedAt == null && it.archivedAt == null }?.id
    }

    fun snapshot(range: PeriodRange? = null): FinanceSnapshot = transaction { readSnapshot(range) }

    /** Every nondeleted category of this owner and epoch, mapped exactly like [FinanceSnapshot.allCategories]. */
    internal fun readCategories(): List<FinanceCategory> = transaction {
        dao.categories().filter { it.ownerId == owner && it.epochId == epoch && it.deletedAt == null }
            .map(::financeCategory)
    }

    internal fun assetHistory(months: Int, zoneId: String, asOfMillis: Long, includeFuture: Boolean): List<AssetHistoryPoint> = transaction {
        val facts = facts()
        val accountRows = facts.accounts.values.filter { it.ownerId == owner && it.epochId == epoch && it.deletedAt == null }
            .map { AssetHistoryAccount(UUID.fromString(it.id), it.ownerId, it.epochId, it.currency, it.type, it.includeInAssets, it.archivedAt) }
        val events = facts.events.values.filter { it.ownerId == owner && it.epochId == epoch }.associateBy { it.id }
        val postingRows = facts.postings.mapNotNull { posting ->
            val event = events[posting.eventId] ?: return@mapNotNull null
            if (posting.accountId !in facts.accounts) return@mapNotNull null
            AssetHistoryPosting(UUID.fromString(posting.accountId), owner, epoch, event.occurredAtMillis,
                Money(posting.amountUnscaled, posting.amountScale, posting.amountCurrency))
        }
        AssetHistoryCalculator.monthly(accountRows, postingRows, owner, epoch, zoneId, asOfMillis, months, includeFuture)
    }

    fun setLoanArchived(planId: UUID, expectedVersion: Long, archived: Boolean) = transaction {
        valid(!accountDataset, "账号贷款归档需服务端 typed producer，当前不可用")
        val plan = activeLoan(planId, expectedVersion)
        val nextState = if (archived) "ARCHIVED" else "ACTIVE"
        valid(plan.state != nextState, "贷款计划状态未变化")
        val revision = Math.addExact(expectedVersion, 1)
        val now = System.currentTimeMillis()
        loanDao.update(plan.copy(state = nextState, localRevision = revision, updatedAt = now))
        journal(plan.id, revision, expectedVersion, "loan.archive", JSONObject().put("archived", archived), now)
        readSnapshot()
    }


    fun configureCredit(
        accountId: UUID, expectedAccountRevision: Long, limit: Money, statementDay: Int,
        includeStatementDay: Boolean, repaymentRule: String, repaymentValue: Int, timezone: String,
    ): UUID = transaction {
        valid(!accountDataset, "账号信用设置需服务端 typed producer")
        val account = dao.accounts().singleOrNull { it.id == accountId.toString() && it.ownerId == owner && it.epochId == epoch && it.deletedAt == null }
            ?: throw FinanceValidationException("信用账户不存在")
        valid(account.localRevision == expectedAccountRevision && account.type == "LIABILITY" && account.currency == limit.currency, "请选择未变更的同币种负债账户")
        valid(limit.unscaled >= 0 && statementDay in 1..31 && timezone in timezones, "信用额度或账期规则无效")
        valid((repaymentRule == "FIXED_MONTH_DAY" && repaymentValue in 1..31) || (repaymentRule == "DAYS_AFTER_STATEMENT" && repaymentValue in 1..366), "还款日规则无效")
        val now = System.currentTimeMillis()
        val old = creditDao.profiles().singleOrNull { it.accountId == account.id && it.ownerId == owner && it.epochId == epoch && it.deletedAt == null }
        val capacityDelta = creditDao.capacityEvents().filter { it.accountId == account.id && it.ownerId == owner && it.epochId == epoch }
            .fold(Money(0, 0, limit.currency)) { total, row -> total.checkedAdd(Money(row.deltaUnscaled, row.deltaScale, row.currency)) }
        valid(limit.checkedAdd(capacityDelta).checkedSubtract(Money(0, 0, limit.currency)).unscaled >= 0, "新额度低于已占用额度")
        val profileId = old?.id ?: newId()
        val revision = Math.addExact(account.localRevision, 1)
        val profile = CreditProfileEntity(profileId, owner, epoch, account.id, limit.unscaled, limit.scale, limit.currency,
            statementDay, includeStatementDay, repaymentRule, repaymentValue, timezone, true,
            Math.addExact(old?.localRevision ?: 0, 1), old?.createdAt ?: now, now, null)
        if (old == null) creditDao.insert(profile) else creditDao.update(profile)
        dao.update(account.copy(localRevision = revision, updatedAt = now))
        journal(account.id, revision, account.localRevision, "credit.configure", JSONObject().put("profileId", profileId)
            .put("limit", moneyIntent(limit)).put("statementDay", statementDay).put("includeStatementDay", includeStatementDay)
            .put("repaymentRule", repaymentRule).put("repaymentValue", repaymentValue).put("timezone", timezone), now)
        readSnapshot(); UUID.fromString(profileId)
    }

    fun adjustCreditCapacity(accountId: UUID, expectedAccountRevision: Long, delta: Money, reason: String, atMillis: Long = System.currentTimeMillis()) = transaction {
        valid(!accountDataset, "账号信用额度调整需服务端 typed producer")
        val profile = creditDao.profiles().singleOrNull { it.accountId == accountId.toString() && it.ownerId == owner && it.epochId == epoch && it.deletedAt == null && it.enabled }
            ?: throw FinanceValidationException("请先配置信用额度")
        val account = dao.accounts().single { it.id == accountId.toString() && it.ownerId == owner && it.epochId == epoch }
        valid(account.localRevision == expectedAccountRevision && delta.unscaled != 0L && delta.currency == profile.currency, "信用额度已变更或金额无效")
        val reasonText = reason.trim()
        valid(reasonText.isNotEmpty() && reasonText.length <= 512 && atMillis in 0..System.currentTimeMillis(), "请输入有效原因和时间")
        val rawCapacity = Money(profile.limitUnscaled, profile.limitScale, profile.currency).checkedAdd(creditDao.capacityEvents().filter { it.accountId == account.id && it.ownerId == owner && it.epochId == epoch }
            .fold(Money(0, 0, profile.currency)) { total, row -> total.checkedAdd(Money(row.deltaUnscaled, row.deltaScale, row.currency)) })
        val nextCapacity = rawCapacity.checkedAdd(delta)
        val zero = Money(0, 0, profile.currency)
        valid(rawCapacity.checkedSubtract(zero).unscaled >= 0 && nextCapacity.checkedSubtract(zero).unscaled >= 0, "可用授信额度不能小于零")
        val now = System.currentTimeMillis(); val revision = Math.addExact(account.localRevision, 1); val id = newId()
        creditDao.insert(CreditCapacityEventEntity(id, owner, epoch, account.id, "MANUAL", id, delta.unscaled, delta.scale, delta.currency, atMillis, reasonText, 1))
        creditDao.update(profile.copy(localRevision = Math.addExact(profile.localRevision, 1), updatedAt = now))
        dao.update(account.copy(localRevision = revision, updatedAt = now))
        journal(account.id, revision, account.localRevision, "credit.capacityAdjust", JSONObject().put("eventId", id).put("delta", moneyIntent(delta)).put("reason", reasonText), now)
        readSnapshot()
    }

    fun createInstallment(
        accountId: UUID, expectedAccountRevision: Long, principal: Money, interest: Money, terms: Int,
        firstDate: String, timezone: String, principalPosting: String, interestMode: String, remainderMode: String,
    ): UUID = transaction {
        valid(!accountDataset, "账号分期需服务端 typed producer")
        val account = dao.accounts().singleOrNull { it.id == accountId.toString() && it.ownerId == owner && it.epochId == epoch && it.deletedAt == null }
            ?: throw FinanceValidationException("信用账户不存在")
        val profile = creditDao.profiles().singleOrNull { it.accountId == account.id && it.ownerId == owner && it.epochId == epoch && it.deletedAt == null && it.enabled }
            ?: throw FinanceValidationException("请先配置信用额度")
        valid(account.type == "LIABILITY" && account.localRevision == expectedAccountRevision && account.currency == principal.currency &&
            principal.unscaled > 0 && interest.currency == principal.currency && interest.unscaled >= 0 && terms in 1..1200 &&
            principalPosting in setOf("TERM_BY_TERM", "UPFRONT") && interestMode in setOf("TERM_BY_TERM", "FIRST_TERM") && remainderMode in setOf("FIRST_TERM", "LAST_TERM") && timezone in timezones,
            "分期计划参数无效")
        val effectiveCapacity = Money(profile.limitUnscaled, profile.limitScale, profile.currency).checkedAdd(creditDao.capacityEvents()
            .filter { it.accountId == account.id && it.ownerId == owner && it.epochId == epoch }
            .fold(Money(0, 0, profile.currency)) { total, row -> total.checkedAdd(Money(row.deltaUnscaled, row.deltaScale, row.currency)) })
        valid(principalPosting == "UPFRONT" || effectiveCapacity.checkedSubtract(principal).unscaled >= 0, "剩余授信额度不足")
        val date = try { java.time.LocalDate.parse(firstDate) } catch (_: Exception) { throw FinanceValidationException("首期日期无效") }
        val planId = newId(); val now = System.currentTimeMillis(); val ledger = facts().ledger
        val rows = PlanCalculator.installmentAllocation(principal, interest, terms,
            if (remainderMode == "FIRST_TERM") dev.icos.app.domain.Remainder.HEAD else dev.icos.app.domain.Remainder.TAIL,
            if (interestMode == "FIRST_TERM") dev.icos.app.domain.InterestMode.FIRST else dev.icos.app.domain.InterestMode.SPREAD,
            maxOf(principal.scale, interest.scale))
        var creationEventId: String? = null
        if (principalPosting == "UPFRONT") {
            val id = newId(); val ev = event(id, ledger.id, null, 1, "INSTALLMENT_POSTING", now, timezone, "分期本金提前入账", null, null, now, principal)
            dao.insert(ev); dao.insert(posting(id, 0, AccountAmount(accountId, principal))); creationEventId = id
        }
        val plan = InstallmentPlanEntity(planId, owner, epoch, "CONSUMPTION", null, account.id, ledger.id, creationEventId,
            principal.unscaled, principal.scale, interest.unscaled, interest.scale, principal.currency, terms, interestMode, remainderMode,
            principalPosting, 0, firstDate, timezone, "LOCAL", "ACTIVE", 1, 1, now, now, null)
        creditDao.insert(plan)
        rows.forEachIndexed { index, term ->
            val dueMonth = java.time.YearMonth.from(date).plusMonths(index.toLong())
            val due = dueMonth.atDay(minOf(date.dayOfMonth, dueMonth.lengthOfMonth())).toString(); val id = newId()
            creditDao.insert(InstallmentTermEntity(id, owner, epoch, planId, index + 1, 1, due, term.principal.unscaled, term.principal.scale,
                term.interest.unscaled, term.interest.scale, principal.currency, "SCHEDULED", null, null, null, null, 1))
            if (principalPosting == "TERM_BY_TERM" && term.principal.unscaled > 0) {
                val capacityId = newId(); val occupied = term.principal.checkedNegate()
                creditDao.insert(CreditCapacityEventEntity(capacityId, owner, epoch, account.id, "INSTALLMENT", id,
                    occupied.unscaled, occupied.scale, occupied.currency, now, "分期本金占用", 1))
            }
        }
        val revision = Math.addExact(account.localRevision, 1); dao.update(account.copy(localRevision = revision, updatedAt = now))
        creditDao.update(profile.copy(localRevision = Math.addExact(profile.localRevision, 1), updatedAt = now))
        journal(planId, 1, 0, "installment.create", JSONObject().put("accountId", account.id).put("principal", moneyIntent(principal))
            .put("interest", moneyIntent(interest)).put("terms", terms).put("firstDate", firstDate).put("principalPosting", principalPosting), now)
        readSnapshot(); UUID.fromString(planId)
    }

    fun postInstallmentTerm(termId: UUID, expectedPlanRevision: Long, atMillis: Long = System.currentTimeMillis()) = transaction {
        valid(!accountDataset, "账号分期入账由服务端 worker 执行")
        val term = creditDao.terms().singleOrNull { it.id == termId.toString() && it.ownerId == owner && it.epochId == epoch }
            ?: throw FinanceValidationException("分期期次不存在")
        val plan = creditDao.plans().single { it.id == term.planId && it.ownerId == owner && it.epochId == epoch }
        valid(plan.executor == "LOCAL" && plan.localRevision == expectedPlanRevision && plan.state == "ACTIVE" && term.state == "SCHEDULED", "期次已变更或不可入账")
        val linkedCapacity = creditDao.capacityEvents().filter { it.ownerId == owner && it.epochId == epoch && it.sourceId == term.id }
        valid(linkedCapacity.any { it.sourceType == "INSTALLMENT" } || plan.principalPosting == "UPFRONT" || term.principalUnscaled == 0L, "分期额度占用数据缺失")
        val profile = creditDao.profiles().singleOrNull { it.accountId == plan.accountId && it.ownerId == owner && it.epochId == epoch && it.deletedAt == null }
            ?: throw FinanceValidationException("信用额度配置缺失")
        val account = dao.accounts().single { it.id == plan.accountId && it.ownerId == owner && it.epochId == epoch && it.deletedAt == null }
        val principal = Money(term.principalUnscaled, term.principalScale, term.currency)
        val interest = Money(term.interestUnscaled, term.interestScale, term.currency)
        val due = principal.checkedAdd(interest); val now = System.currentTimeMillis(); valid(atMillis in 0..now, "入账时间无效")
        val dueDate = try { java.time.LocalDate.parse(term.dueDate) } catch (_: Exception) { throw FinanceValidationException("期次日期无效") }
        val postingDate = java.time.Instant.ofEpochMilli(atMillis).atZone(java.time.ZoneId.of(plan.timezone)).toLocalDate()
        valid(!postingDate.isBefore(dueDate), "期次尚未到期")
        var eventId = newId(); val debtDelta = if (plan.principalPosting == "TERM_BY_TERM") due else interest
        if (debtDelta.unscaled != 0L) {
            val ev = event(eventId, plan.ledgerId, null, 1, "INSTALLMENT_POSTING", atMillis, plan.timezone, "分期第${term.termIndex}期入账", null, null, now, debtDelta)
            dao.insert(ev); dao.insert(posting(eventId, 0, AccountAmount(UUID.fromString(plan.accountId), debtDelta)))
        } else eventId = ""
        creditDao.update(term.copy(state = "POSTED", postingEventId = eventId.takeIf(String::isNotEmpty), localRevision = Math.addExact(term.localRevision, 1)))
        if (plan.principalPosting == "TERM_BY_TERM" && principal.unscaled > 0) {
            val restored = newId()
            creditDao.insert(CreditCapacityEventEntity(restored, owner, epoch, plan.accountId, "CAPACITY_RESTORE", term.id,
                principal.unscaled, principal.scale, principal.currency, atMillis, "分期本金入账，恢复额度", 1))
        }
        val revision = Math.addExact(plan.localRevision, 1)
        val remainingScheduled = creditDao.terms().any { it.planId == plan.id && it.id != term.id && it.state == "SCHEDULED" && it.ownerId == owner && it.epochId == epoch }
        creditDao.update(plan.copy(localRevision = revision, updatedAt = now, state = if (!remainingScheduled) "COMPLETED" else plan.state))
        val accountRevision = Math.addExact(account.localRevision, 1); dao.update(account.copy(localRevision = accountRevision, updatedAt = now))
        creditDao.update(profile.copy(localRevision = Math.addExact(profile.localRevision, 1), updatedAt = now))
        journal(plan.id, revision, expectedPlanRevision, "installment.postTerm", JSONObject().put("termId", term.id).put("eventId", eventId), now)
        readSnapshot()
    }

    fun updateInstallment(planId: UUID, expectedPlanRevision: Long, interest: Money, termCount: Int, firstDate: String, interestMode: String? = null, remainderMode: String? = null) = transaction {
        valid(!accountDataset, "账号分期更新需服务端 typed producer")
        val plan = creditDao.plans().singleOrNull { it.id == planId.toString() && it.ownerId == owner && it.epochId == epoch && it.deletedAt == null }
            ?: throw FinanceValidationException("分期计划不存在")
        val nextInterestMode = interestMode ?: plan.interestMode
        val nextRemainderMode = remainderMode ?: plan.remainderMode
        valid(plan.localRevision == expectedPlanRevision && plan.executor == "LOCAL" && plan.state == "ACTIVE", "分期计划已变更或由服务器执行")
        val activeTerms = creditDao.terms().filter { it.planId == plan.id && it.ownerId == owner && it.epochId == epoch && it.scheduleRevision == plan.scheduleRevision && it.state != "SUPERSEDED" }
        valid(activeTerms.isNotEmpty() && activeTerms.all { it.state == "SCHEDULED" }, "已有期次入账后不能替换计划规则")
        val principal = Money(plan.principalUnscaled, plan.principalScale, plan.currency)
        valid(interest.currency == plan.currency && interest.unscaled >= 0 && termCount in 1..1200 && nextInterestMode in setOf("TERM_BY_TERM", "FIRST_TERM") && nextRemainderMode in setOf("FIRST_TERM", "LAST_TERM"), "分期规则无效")
        val first = try { java.time.LocalDate.parse(firstDate) } catch (_: Exception) { throw FinanceValidationException("首期日期无效") }
        val allocation = PlanCalculator.installmentAllocation(principal, interest, termCount,
            if (nextRemainderMode == "FIRST_TERM") dev.icos.app.domain.Remainder.HEAD else dev.icos.app.domain.Remainder.TAIL,
            if (nextInterestMode == "FIRST_TERM") dev.icos.app.domain.InterestMode.FIRST else dev.icos.app.domain.InterestMode.SPREAD,
            maxOf(principal.scale, interest.scale))
        val now = System.currentTimeMillis(); val revision = Math.addExact(plan.localRevision, 1); val scheduleRevision = Math.addExact(plan.scheduleRevision, 1)
        activeTerms.forEach { old ->
            creditDao.update(old.copy(state = "SUPERSEDED", localRevision = Math.addExact(old.localRevision, 1)))
            creditDao.capacityEvents().filter { it.sourceType == "INSTALLMENT" && it.sourceId == old.id && it.ownerId == owner && it.epochId == epoch }.forEach { event ->
                val restored = Money(event.deltaUnscaled, event.deltaScale, event.currency).checkedNegate()
                creditDao.insert(CreditCapacityEventEntity(newId(), owner, epoch, plan.accountId, "REVERSAL", event.id, restored.unscaled, restored.scale, restored.currency, now, "更新分期，撤销旧额度占用", 1))
            }
        }
        allocation.forEachIndexed { index, term ->
            val dueMonth = java.time.YearMonth.from(first).plusMonths(index.toLong())
            val dueDate = dueMonth.atDay(minOf(first.dayOfMonth, dueMonth.lengthOfMonth())).toString()
            val id = newId(); creditDao.insert(InstallmentTermEntity(id, owner, epoch, plan.id, index + 1, scheduleRevision,
                dueDate, term.principal.unscaled, term.principal.scale, term.interest.unscaled,
                term.interest.scale, plan.currency, "SCHEDULED", null, null, null, null, 1))
            if (plan.principalPosting == "TERM_BY_TERM" && term.principal.unscaled > 0) {
                val occupied = term.principal.checkedNegate()
                creditDao.insert(CreditCapacityEventEntity(newId(), owner, epoch, plan.accountId, "INSTALLMENT", id, occupied.unscaled, occupied.scale, occupied.currency, now, "更新分期本金占用", 1))
            }
        }
        creditDao.update(plan.copy(interestUnscaled = interest.unscaled, interestScale = interest.scale, termCount = termCount,
            interestMode = nextInterestMode, remainderMode = nextRemainderMode, firstPostingDate = firstDate, scheduleRevision = scheduleRevision,
            localRevision = revision, updatedAt = now))
        val account = dao.accounts().single { it.id == plan.accountId && it.ownerId == owner && it.epochId == epoch && it.deletedAt == null }
        dao.update(account.copy(localRevision = Math.addExact(account.localRevision, 1), updatedAt = now))
        creditDao.profiles().singleOrNull { it.accountId == plan.accountId && it.ownerId == owner && it.epochId == epoch && it.deletedAt == null }?.let { profile ->
            creditDao.update(profile.copy(localRevision = Math.addExact(profile.localRevision, 1), updatedAt = now))
        }
        journal(plan.id, revision, expectedPlanRevision, "installment.update", JSONObject().put("interest", moneyIntent(interest)).put("termCount", termCount)
            .put("firstPostingDate", firstDate).put("interestMode", nextInterestMode).put("remainderMode", nextRemainderMode), now)
        readSnapshot()
    }

    fun deleteInstallment(planId: UUID, expectedPlanRevision: Long) = transaction {
        valid(!accountDataset, "账号分期删除需服务端 typed producer")
        val plan = creditDao.plans().singleOrNull { it.id == planId.toString() && it.ownerId == owner && it.epochId == epoch && it.deletedAt == null }
            ?: throw FinanceValidationException("分期计划不存在")
        valid(plan.localRevision == expectedPlanRevision && plan.executor == "LOCAL", "分期计划已变更或由服务器执行")
        val scheduled = creditDao.terms().filter { it.planId == plan.id && it.ownerId == owner && it.epochId == epoch && it.state == "SCHEDULED" }
        valid(scheduled.isNotEmpty(), "没有可停止的未入账期次")
        val now = System.currentTimeMillis()
        scheduled.forEach { term ->
            creditDao.update(term.copy(state = "SUPERSEDED", localRevision = Math.addExact(term.localRevision, 1)))
            creditDao.capacityEvents().filter { it.sourceType == "INSTALLMENT" && it.sourceId == term.id && it.ownerId == owner && it.epochId == epoch }.forEach { occupation ->
                val reversalId = newId()
                creditDao.insert(CreditCapacityEventEntity(reversalId, owner, epoch, plan.accountId, "REVERSAL", occupation.id,
                    Money(occupation.deltaUnscaled, occupation.deltaScale, occupation.currency).checkedNegate().unscaled,
                    occupation.deltaScale, occupation.currency, now, "停止未来分期，恢复额度", 1))
            }
        }
        val nextRevision = Math.addExact(plan.localRevision, 1)
        creditDao.update(plan.copy(state = "TERMINATED", localRevision = nextRevision, updatedAt = now))
        val account = dao.accounts().singleOrNull { it.id == plan.accountId && it.ownerId == owner && it.epochId == epoch && it.deletedAt == null }
            ?: throw FinanceValidationException("分期负债账户不存在")
        dao.update(account.copy(localRevision = Math.addExact(account.localRevision, 1), updatedAt = now))
        creditDao.profiles().singleOrNull { it.accountId == plan.accountId && it.ownerId == owner && it.epochId == epoch && it.deletedAt == null }?.let { profile ->
            creditDao.update(profile.copy(localRevision = Math.addExact(profile.localRevision, 1), updatedAt = now))
        }
        journal(plan.id, nextRevision, expectedPlanRevision, "installment.delete", JSONObject().put("strategy", "STOP_FUTURE"), now)
        readSnapshot()
    }

    fun allocateCreditPayment(accountId: UUID, expectedAccountRevision: Long, assetAccountId: UUID, amount: Money, atMillis: Long, allocations: Map<UUID, Money>): UUID = transaction {
        valid(!accountDataset, "账号信用还款分配需服务端 typed producer")
        val liability = dao.accounts().singleOrNull { it.id == accountId.toString() && it.ownerId == owner && it.epochId == epoch && it.type == "LIABILITY" && it.deletedAt == null }
            ?: throw FinanceValidationException("信用负债账户不存在")
        val asset = dao.accounts().singleOrNull { it.id == assetAccountId.toString() && it.ownerId == owner && it.epochId == epoch && it.type == "ASSET" && it.deletedAt == null && it.archivedAt == null && it.selectable }
            ?: throw FinanceValidationException("请选择可用资产账户")
        valid(liability.localRevision == expectedAccountRevision && liability.currency == amount.currency && asset.currency == amount.currency && amount.unscaled > 0 && atMillis in 0..System.currentTimeMillis(), "还款金额或账户无效")
        val id = newId(); val now = System.currentTimeMillis(); val ledger = facts().ledger
        val ev = event(id, ledger.id, null, 1, "CREDIT_PAYMENT", atMillis, TimeZone.getDefault().id, "信用卡还款", null, null, now, amount)
        dao.insert(ev); dao.insert(posting(id, 0, AccountAmount(assetAccountId, amount.checkedNegate())))
        dao.insert(posting(id, 1, AccountAmount(accountId, amount.checkedNegate())))
        val eventOwner = dao.events().singleOrNull { it.id == id && it.ownerId == owner && it.epochId == epoch }
            ?: throw FinanceValidationException("还款事件缺失")
        valid(eventOwner.ledgerId == ledger.id && eventOwner.kind == "CREDIT_PAYMENT", "还款事件账本无效")
        var totalAllocated = Money(0, 0, amount.currency)
        allocations.forEach { (termId, allocated) ->
            valid(allocated.unscaled > 0 && allocated.currency == amount.currency, "期次分配金额无效")
            val term = creditDao.terms().singleOrNull { it.id == termId.toString() && it.ownerId == owner && it.epochId == epoch && it.state in setOf("POSTED", "PAID") }
                ?: throw FinanceValidationException("只能分配到已入账期次")
            val termPlan = creditDao.plans().singleOrNull { it.id == term.planId && it.accountId == liability.id && it.ownerId == owner && it.epochId == epoch }
                ?: throw FinanceValidationException("期次所属分期无效")
            val due = Money(term.principalUnscaled, term.principalScale, term.currency).checkedAdd(Money(term.interestUnscaled, term.interestScale, term.currency))
            val paidBefore = creditDao.paymentAllocations().filter { it.termId == term.id && it.ownerId == owner && it.epochId == epoch }
                .fold(Money(0, 0, amount.currency)) { sum, row -> sum.checkedAdd(Money(row.amountUnscaled, row.amountScale, row.currency)) }
            valid(amount.currency == term.currency && allocated.unscaled > 0 && paidBefore.checkedAdd(allocated).checkedSubtract(due).unscaled <= 0, "期次分配金额超出应还")
            val paid = paidBefore.checkedAdd(allocated)
            creditDao.insert(CreditPaymentAllocationEntity(newId(), owner, epoch, liability.id, id, term.id, allocated.unscaled, allocated.scale, allocated.currency))
            if (paid.sameValue(due)) creditDao.update(term.copy(state = "PAID", paidEventId = id, paidUnscaled = paid.unscaled, paidScale = paid.scale, localRevision = Math.addExact(term.localRevision, 1)))
            totalAllocated = totalAllocated.checkedAdd(allocated)
            check(termPlan.executor == "LOCAL")
        }
        valid(totalAllocated.checkedSubtract(amount).unscaled <= 0, "期次分配超过实际还款金额")
        val resultingDebt = dao.postings().filter { it.accountId == liability.id && it.amountCurrency == liability.currency }
            .fold(Money(0, 0, liability.currency)) { sum, row -> sum.checkedAdd(Money(row.amountUnscaled, row.amountScale, row.amountCurrency)) }
        valid(resultingDebt.unscaled >= 0, "还款超过当前负债余额")
        val revision = Math.addExact(liability.localRevision, 1); dao.update(liability.copy(localRevision = revision, updatedAt = now))
        creditDao.profiles().singleOrNull { it.accountId == liability.id && it.ownerId == owner && it.epochId == epoch && it.deletedAt == null }?.let { profile ->
            creditDao.update(profile.copy(localRevision = Math.addExact(profile.localRevision, 1), updatedAt = now))
        }
        creditDao.plans().filter { it.accountId == liability.id && it.ownerId == owner && it.epochId == epoch && it.state == "ACTIVE" }.forEach { plan ->
            val planTerms = creditDao.terms().filter { it.planId == plan.id && it.ownerId == owner && it.epochId == epoch }
            if (planTerms.isNotEmpty() && planTerms.all { it.state in setOf("PAID", "SUPERSEDED") }) {
                creditDao.update(plan.copy(state = "COMPLETED", localRevision = Math.addExact(plan.localRevision, 1), updatedAt = now))
            }
        }
        journal(liability.id, revision, expectedAccountRevision, "credit.allocatePayment", JSONObject().put("paymentEventId", id).put("amount", moneyIntent(amount)).put("allocated", moneyIntent(totalAllocated)), now)
        readSnapshot(); UUID.fromString(id)
    }

    fun selectLedger(ledgerId: UUID) = transaction {
        val row = dao.ledgers().singleOrNull { it.id == ledgerId.toString() && it.ownerId == owner && it.epochId == epoch && it.deletedAt == null && it.archivedAt == null }
            ?: throw FinanceValidationException("账本不可用")
        selectedLedgerId = row.id
        readSnapshot()
        Unit
    }

    fun availableLedgers(): List<FinanceLedger> = transaction {
        dao.ledgers().filter { it.ownerId == owner && it.epochId == epoch && it.deletedAt == null }
            .sortedWith(compareBy<LedgerEntity> { it.sortOrder }.thenBy { it.id })
            .map { FinanceLedger(UUID.fromString(it.id), it.name, it.baseCurrency, it.sortOrder, it.isDefault, it.archivedAt, it.localRevision) }
    }

    internal fun storageDiagnostics(): StorageDiagnosticReport {
        offMain()
        check(!closed)
        return StorageDiagnostics.inspect(database, accountDataset)
    }

    fun fileImportBatches(): List<Pair<FileImportBatchEntity, Int>> = transaction {
        database.fileImports().batches(owner, epoch).map { batch -> batch to database.fileImports().rows(batch.id).size }
    }

    fun stageFileImport(digest: String, rows: List<FileImportRowEntity>): UUID = transaction {
        valid(!accountDataset, "账号导入需服务端批次协议，当前不可用")
        valid(digest.matches(Regex("[0-9a-f]{64}")) && rows.size in 1..100_000, "导入批次无效")
        valid(rows.map { it.rowNumber }.distinct().size == rows.size && rows.all { it.batchId.isEmpty() || it.batchId == "pending" }, "导入行不唯一")
        val imports = database.fileImports()
        imports.committedDigest(digest)?.let { return@transaction UUID.fromString(it) }
        val id = newId()
        val now = System.currentTimeMillis()
        imports.insert(FileImportBatchEntity(id, owner, epoch, digest, "icos-ledger-v1", "PREVIEWED", rows.size, now, null))
        imports.insertRows(rows.map { it.copy(batchId = id, recordId = null, state = "PREVIEWED") })
        UUID.fromString(id)
    }

    fun confirmFileImport(
        batchId: UUID,
        confirmedDigest: String,
        dependencyCheck: (FinanceSnapshot) -> Unit = {},
    ): UUID = transaction {
        valid(!accountDataset, "账号导入需服务端批次协议，当前不可用")
        val imports = database.fileImports()
        val id = batchId.toString()
        val batch = imports.batch(id) ?: throw FinanceValidationException("导入预览已失效")
        valid(batch.ownerId == owner && batch.epochId == epoch && batch.fileDigest == confirmedDigest, "导入批次已变更，请重新预检")
        if (batch.state == "COMMITTED") return@transaction batchId
        valid(batch.state == "PREVIEWED" && imports.committedDigest(confirmedDigest) == null, "该文件摘要已提交或预览已失效")
        val rows = imports.rows(id)
        valid(rows.size == batch.rowCount && rows.isNotEmpty() && rows.map { it.rowNumber }.distinct().size == rows.size && rows.all { it.state == "PREVIEWED" }, "导入行已变更")
        valid(rows.all { row ->
            try {
                val payload = JSONObject(row.payloadJson)
                dev.icos.app.data.remote.RecordInputJson.decode(payload.getJSONObject("input")).calculate()
                UUID.fromString(payload.getString("recordId")) != UUID(0, 0) && payload.getString("timezone") in timezones &&
                    payload.getLong("occurredAtMillis") in 0..System.currentTimeMillis() && row.rowNumber > 0 &&
                    row.contentFingerprint.matches(Regex("[0-9a-f]{64}"))
            } catch (_: Exception) { false }
        }, "导入预检数据损坏")
        valid(rows.mapNotNull { it.externalId }.distinct().size == rows.count { it.externalId != null }, "导入externalId重复")
        rows.mapNotNull { it.externalId }.forEach { valid(imports.byExternalId(it) == null, "externalId已由其他批次导入") }
        val snapshot = readSnapshot()
        dependencyCheck(snapshot)
        cachedBatchFacts = facts()
        batchApplying = true
        try {
            rows.forEach { row ->
                val payload = JSONObject(row.payloadJson)
                saveRecordInternal(
                    null,
                    dev.icos.app.data.remote.RecordInputJson.decode(payload.getJSONObject("input")),
                    payload.getLong("occurredAtMillis"), payload.getString("timezone"), payload.getString("remark"),
                    payload.optString("categoryId").takeIf { it.isNotEmpty() && it != "null" }?.let(UUID::fromString),
                    null, (payload.optJSONArray("tagIds") ?: JSONArray()).let { array -> List(array.length()) { UUID.fromString(array.getString(it)) } }, true,
                    createId = UUID.fromString(payload.getString("recordId")),
                )
                imports.setRowCommitted(id, row.rowNumber, payload.getString("recordId"))
            }
            cachedBatchFacts = null
            readSnapshot()
            imports.setBatchState(id, "COMMITTED", System.currentTimeMillis())
            batchId
        } finally {
            batchApplying = false
            cachedBatchFacts = null
        }
    }

    fun reverseFileImportBatch(
        batchId: UUID,
        reason: String,
        dependencyCheck: (FinanceSnapshot) -> Unit = {},
    ) = transaction {
        valid(!accountDataset, "账号批次撤销需服务端协议，当前不可用")
        val imports = database.fileImports()
        val id = batchId.toString()
        val batch = imports.batch(id) ?: throw FinanceValidationException("导入批次不存在")
        valid(batch.ownerId == owner && batch.epochId == epoch && batch.state == "COMMITTED", "导入批次不可撤销")
        valid(reason.isNotBlank() && reason.length <= 1000, "请输入撤销原因")
        val rows = imports.rows(id)
        valid(rows.size == batch.rowCount && rows.isNotEmpty() && rows.all { it.state == "COMMITTED" && it.recordId != null } &&
            rows.mapNotNull { it.externalId }.distinct().size == rows.count { it.externalId != null }, "导入批次记录不完整")
        val current = facts()
        val selected = rows.map { row ->
            val record = current.records[row.recordId]
            valid(record != null && record.ownerId == owner && record.epochId == epoch && record.deletedAt == null, "批次账单不存在或已变化")
            valid(!hasActiveSavingLink(record!!.id) && !hasPostedRecovery(current, record.id), "批次存在报销、退款、存钱或贷款依赖")
            val event = current.currentEvent(record)
            valid(current.events.values.none { it.reversalOf == event.id }, "批次账单已经撤销")
            row to record
        }
        dependencyCheck(readSnapshot())
        val now = System.currentTimeMillis()
        selected.forEach { (_, record) ->
            reverse(current.currentEvent(record), Math.addExact(record.localRevision, 1), now)
            dao.update(record.copy(deletedAt = now, updatedAt = now, localRevision = Math.addExact(record.localRevision, 1)))
            tagDao.deleteRecordTags(record.id)
        }
        journal(id, 2, 1, "FILE_IMPORT_BATCH_REVERSE", JSONObject().put("reason", reason)
            .put("recordIds", JSONArray().also { array -> selected.forEach { array.put(it.second.id) } }), now)
        readSnapshot()
        imports.setBatchState(id, "REVERSED", now)
    }

    fun loanPlans(): List<FinanceLoanPlan> = transaction {
        loanDao.plans().filter { it.ownerId == owner && it.epochId == epoch }.map { plan ->
            FinanceLoanPlan(
                UUID.fromString(plan.id), UUID.fromString(plan.accountId),
                Money(plan.principalUnscaled, plan.principalScale, plan.currency), plan.localRevision, plan.state,
                loanDao.terms().filter { it.planId == plan.id && (plan.deletedAt != null || it.activeTermKey != null) }
                    .sortedWith(compareBy<LoanTermEntity> { it.planRevision }.thenBy { it.termIndex }).map(::loanTerm),
                plan.paidTermCount,
                plan.annualRateNumerator,
                plan.annualRateDenominator,
                plan.repaymentType,
                plan.firstDueDate,
                loanDao.rateChanges().filter { it.ownerId == owner && it.epochId == epoch && it.planId == plan.id }
                    .sortedWith(compareBy<LoanRateChangeEntity> { it.toRevision }.thenBy { it.id })
                    .map { FinanceLoanRateChange(it.effectiveTerm, it.oldRateNumerator, it.oldRateDenominator,
                        it.newRateNumerator, it.newRateDenominator, it.fromRevision, it.toRevision, it.createdAt) },
                loanDao.terms().filter { it.ownerId == owner && it.epochId == epoch && it.planId == plan.id &&
                    (plan.deletedAt != null || it.activeTermKey != null) && it.paidPrincipalUnscaled != null && it.paidInterestUnscaled != null }
                    .sortedBy { it.termIndex }.map { FinanceLoanPayment(it.termIndex,
                        Money(it.paidPrincipalUnscaled!!, it.paidPrincipalScale!!, plan.currency),
                        Money(it.paidInterestUnscaled!!, it.paidInterestScale!!, plan.currency), it.paidAtMillis) },
            )
        }
    }

    fun createLoan(
        input: LoanPlanInput,
        disbursement: LoanDisbursement? = null,
        opId: UUID = UUID.randomUUID(),
    ): UUID = transaction {
        val payload = loanCreateIntent(input, disbursement)
        loanReplay(opId, input.id.toString(), 0, "loan.create", payload)?.let { return@transaction input.id }
        valid(input.paidTermCount == 0 || disbursement == null, "历史已还计划不能重复放款")
        val account = dao.accounts().singleOrNull { it.id == input.accountId.toString() }
        valid(account != null && account.ownerId == owner && account.epochId == epoch && account.deletedAt == null &&
            account.archivedAt == null && account.type == "LIABILITY", "请选择有效负债账户")
        valid(account!!.currency == input.principal.currency, "负债账户币种与贷款币种不一致")
        valid(loanDao.plans().none { it.accountId == account.id && it.deletedAt == null }, "该负债账户已有有效贷款计划")
        val calculated = LoanCalculator.create(input)
        val now = System.currentTimeMillis()
        val plan = LoanPlanEntity(
            input.id.toString(), owner, epoch, account.id, "$owner:$epoch:${account.id}", null,
            input.principal.currency, input.principal.unscaled, input.principal.scale,
            input.annualRateNumerator, input.annualRateDenominator, input.termCount, input.paidTermCount,
            input.repaymentType.name, input.firstDueDate, "ACTIVE", 1, now, now, null,
        )
        loanDao.insert(plan)
        calculated.terms.forEach { term ->
            loanDao.insert(term.toEntity(input.id.toString(), 1, term.termIndex <= input.paidTermCount, owner, epoch))
        }
        disbursement?.let {
            val eventId = postLoanFact(account.id, it.actualAccountId, input.principal, input.principal,
                it.occurredAtMillis, "INCOME", now)
            loanDao.update(plan.copy(disbursementEventId = eventId))
        }
        journal(input.id.toString(), 1, 0, "loan.create", payload, now, opId.toString())
        readSnapshot()
        input.id
    }

    fun previewLoanRate(
        planId: UUID, expectedVersion: Long, effectiveTerm: Int, numerator: Long, denominator: Long,
    ): LoanPreview = transaction {
        val plan = activeLoan(planId, expectedVersion)
        ensureLoanSuffixEditable(plan.id, effectiveTerm)
        LoanCalculator.changeRate(activeLoanTerms(plan.id), effectiveTerm, numerator, denominator,
            RepaymentMethod.valueOf(plan.repaymentType), expectedVersion)
    }

    fun adjustLoanRate(
        planId: UUID, expectedVersion: Long, effectiveTerm: Int, numerator: Long, denominator: Long,
        opId: UUID = UUID.randomUUID(),
    ) = transaction {
        val payload = JSONObject().put("effectiveTerm", effectiveTerm)
            .put("annualRate", JSONObject().put("numerator", numerator).put("denominator", denominator))
        loanReplay(opId, planId.toString(), expectedVersion, "loan.adjustRate", payload)?.let { return@transaction }
        val plan = activeLoan(planId, expectedVersion)
        ensureLoanSuffixEditable(plan.id, effectiveTerm)
        val oldTerms = activeLoanTerms(plan.id)
        val preview = LoanCalculator.changeRate(oldTerms, effectiveTerm, numerator, denominator,
            RepaymentMethod.valueOf(plan.repaymentType), expectedVersion)
        commitLoanSchedule(plan, oldTerms, preview.terms, expectedVersion, effectiveTerm, payload, opId, "loan.adjustRate")
        val revision = Math.addExact(expectedVersion, 1)
        loanDao.insert(LoanRateChangeEntity(newId(), owner, epoch, plan.id, effectiveTerm, plan.annualRateNumerator,
            plan.annualRateDenominator, numerator, denominator, expectedVersion, revision, System.currentTimeMillis()))
        loanDao.update(plan.copy(annualRateNumerator = numerator, annualRateDenominator = denominator,
            localRevision = revision, updatedAt = System.currentTimeMillis()))
        readSnapshot()
    }

    fun previewLoanTerm(
        planId: UUID, expectedVersion: Long, termIndex: Int, principal: Money, interest: Money,
        policy: LoanFuturePolicy,
    ): LoanPreview = transaction {
        val plan = activeLoan(planId, expectedVersion)
        ensureLoanSuffixEditable(plan.id, termIndex)
        LoanCalculator.adjustTerm(activeLoanTerms(plan.id), termIndex, principal, interest, policy,
            plan.annualRateNumerator, plan.annualRateDenominator, expectedVersion)
    }

    fun adjustLoanTerm(
        planId: UUID, expectedVersion: Long, termIndex: Int, principal: Money, interest: Money,
        policy: LoanFuturePolicy, opId: UUID = UUID.randomUUID(),
    ) = transaction {
        val payload = JSONObject().put("termIndex", termIndex).put("principal", moneyIntent(principal))
            .put("interest", moneyIntent(interest)).put("futurePolicy", policy.name)
        loanReplay(opId, planId.toString(), expectedVersion, "loan.adjustTerm", payload)?.let { return@transaction }
        val plan = activeLoan(planId, expectedVersion)
        ensureLoanSuffixEditable(plan.id, termIndex)
        val oldTerms = activeLoanTerms(plan.id)
        val preview = LoanCalculator.adjustTerm(oldTerms, termIndex, principal, interest, policy,
            plan.annualRateNumerator, plan.annualRateDenominator, expectedVersion)
        commitLoanSchedule(plan, oldTerms, preview.terms, expectedVersion, termIndex, payload, opId, "loan.adjustTerm")
        readSnapshot()
    }

    fun postLoanPayment(
        planId: UUID, expectedVersion: Long, termIndex: Int, actualAccountId: UUID,
        occurredAtMillis: Long, amount: Money, opId: UUID = UUID.randomUUID(),
    ): UUID? = transaction {
        val payload = JSONObject().put("termIndex", termIndex).put("actualAccount", actualAccountId.toString())
            .put("occurredAt", occurredAtMillis).put("amount", moneyIntent(amount))
        val prior = loanReplay(opId, planId.toString(), expectedVersion, "loan.postPayment", payload)
        if (prior != null) return@transaction loanDao.terms().single {
            it.planId == planId.toString() && it.termIndex == termIndex && it.activeTermKey != null
        }.paidEventId?.let(UUID::fromString)
        val plan = activeLoan(planId, expectedVersion)
        val term = loanDao.terms().singleOrNull { it.planId == plan.id && it.termIndex == termIndex && it.activeTermKey != null }
            ?: throw FinanceValidationException("贷款期次不存在")
        valid(term.paidEventId == null && term.dueState == "DUE", "该期已结清或无需还款")
        val principal = Money(term.principalUnscaled, term.principalScale, plan.currency)
        val actualInterest = amount.checkedSubtract(principal)
        valid(amount.unscaled >= 0 && actualInterest.unscaled >= 0 && amount.currency == plan.currency, "实际还款金额无效")
        val debt = dao.accounts().single { it.id == plan.accountId }
        val cash = dao.accounts().singleOrNull { it.id == actualAccountId.toString() }
        valid(cash != null && cash.ownerId == owner && cash.epochId == epoch && cash.type == "ASSET" && cash.deletedAt == null && cash.archivedAt == null && cash.selectable, "请选择可用资产账户")
        valid(cash!!.currency == plan.currency, "账户币种与贷款币种不一致")
        val now = System.currentTimeMillis()
        valid(occurredAtMillis in 0..now, "还款时间无效")
        val eventId = if (amount.unscaled == 0L && principal.unscaled == 0L) null else
            postLoanFact(debt.id, actualAccountId, amount, principal, occurredAtMillis, "EXPENSE", now)
        loanDao.update(term.copy(paidEventId = eventId, paidPrincipalUnscaled = principal.unscaled, paidPrincipalScale = principal.scale,
            paidInterestUnscaled = actualInterest.unscaled, paidInterestScale = actualInterest.scale, paidAtMillis = occurredAtMillis,
            dueState = "PAID", localRevision = Math.addExact(term.localRevision, 1)))
        val revision = Math.addExact(plan.localRevision, 1)
        loanDao.update(plan.copy(localRevision = revision, updatedAt = now))
        journal(plan.id, revision, expectedVersion, "loan.postPayment", payload, now, opId.toString())
        readSnapshot()
        eventId?.let(UUID::fromString)
    }

    fun deleteLoan(planId: UUID, expectedVersion: Long, strategy: LoanDeleteStrategy, opId: UUID = UUID.randomUUID()) = transaction {
        val payload = JSONObject().put("strategy", strategy.name)
        loanReplay(opId, planId.toString(), expectedVersion, "loan.delete", payload)?.let { return@transaction }
        val plan = activeLoan(planId, expectedVersion)
        val terms = loanDao.terms().filter { it.planId == plan.id && it.activeTermKey != null }
        if (strategy == LoanDeleteStrategy.REVERSE_REVERSIBLE) {
            val events = (terms.flatMap { listOfNotNull(it.postedEventId, it.paidEventId) } + listOfNotNull(plan.disbursementEventId))
                .distinct().map { id ->
                dao.events().singleOrNull { it.id == id && it.ownerId == owner && it.epochId == epoch }
                    ?: throw FinanceValidationException("贷款关联事件缺失")
            }
            valid(events.all { event -> dao.events().none { it.reversalOf == event.id } }, "贷款事件已撤销")
            val linkedRecords = events.mapNotNull { it.recordId }.distinct().map { id ->
                val record = dao.records().singleOrNull { it.id == id && it.ownerId == owner && it.epochId == epoch }
                    ?: throw FinanceValidationException("贷款关联账单缺失")
                valid(!hasPostedRecovery(facts(), id), "贷款账单存在报销或退款依赖")
                record
            }
            val now = System.currentTimeMillis()
            events.forEach { reverse(it, Math.addExact(it.localRevision, 1), now) }
            linkedRecords.forEach { record ->
                dao.update(record.copy(deletedAt = now, updatedAt = now, localRevision = Math.addExact(record.localRevision, 1)))
                tagDao.deleteRecordTags(record.id)
            }
        }
        val now = System.currentTimeMillis()
        val revision = Math.addExact(plan.localRevision, 1)
        loanDao.terms().filter { it.planId == plan.id && it.activeTermKey != null }.forEach {
            loanDao.update(it.copy(activeTermKey = null, localRevision = Math.addExact(it.localRevision, 1)))
        }
        loanDao.update(plan.copy(activeAccountKey = null, state = "DELETED", deletedAt = now, updatedAt = now, localRevision = revision))
        journal(plan.id, revision, expectedVersion, "loan.delete", payload, now, opId.toString())
        readSnapshot()
    }

    fun saveAccountGroup(
        id: UUID?,
        name: String,
        sortOrder: Long,
        expectedLocalRevision: Long? = null,
    ): UUID = transaction {
        val cleaned = name.trim()
        val characters = cleaned.codePointCount(0, cleaned.length)
        valid(characters in 1..100 && cleaned.none { it == '\u0000' }, "账户分组名称需为 1 至 100 个字符")
        valid(sortOrder >= 0, "账户分组顺序无效")
        val old = id?.let { dao.groups().singleOrNull { row -> row.id == it.toString() } }
        val now = System.currentTimeMillis()
        val nextId = id ?: UUID.randomUUID()
        if (old == null) {
            valid(id == null && expectedLocalRevision == null, "账户分组已变更，请刷新后重试")
            val row = FinanceGroupEntity(nextId.toString(), owner, epoch, "ACCOUNT", cleaned, now, now, 1, null, sortOrder, false)
            dao.insert(row)
            journal(row.id, 1, 0, "ACCOUNT_GROUP_CREATE", JSONObject().put("name", cleaned).put("sortOrder", sortOrder), now)
        } else {
            valid(old.ownerId == owner && old.epochId == epoch && old.kind == "ACCOUNT" && old.deletedAt == null, "账户分组不可用")
            valid(old.localRevision == expectedLocalRevision, "账户分组已变更，请刷新后重试")
            val row = old.copy(name = cleaned, sortOrder = sortOrder, updatedAt = now, localRevision = Math.addExact(old.localRevision, 1))
            dao.update(row)
            journal(row.id, row.localRevision, old.localRevision, "ACCOUNT_GROUP_UPDATE", JSONObject().put("name", cleaned).put("sortOrder", sortOrder), now)
        }
        nextId
    }

    fun reorderAccountGroups(ids: List<UUID>, expectedRevisions: Map<UUID, Long>) = transaction {
        val active = dao.groups().filter { it.kind == "ACCOUNT" && it.deletedAt == null }.sortedWith(compareBy<FinanceGroupEntity> { it.sortOrder }.thenBy { it.id })
        valid(ids.size == active.size && ids.distinct().size == ids.size && ids.toSet() == active.map { UUID.fromString(it.id) }.toSet(), "账户分组顺序已变化，请刷新后重试")
        valid(expectedRevisions.keys == active.map { UUID.fromString(it.id) }.toSet(), "账户分组顺序已变化，请刷新后重试")
        valid(active.all { it.ownerId == owner && it.epochId == epoch && expectedRevisions[UUID.fromString(it.id)] == it.localRevision }, "账户分组已变更，请刷新后重试")
        val byId = active.associateBy { it.id }
        val now = System.currentTimeMillis()
        ids.forEachIndexed { index, uuid ->
            val old = byId.getValue(uuid.toString())
            val order = index.toLong()
            if (old.sortOrder != order) {
                val next = old.copy(sortOrder = order, updatedAt = now, localRevision = Math.addExact(old.localRevision, 1))
                dao.update(next)
                journal(next.id, next.localRevision, old.localRevision, "ACCOUNT_GROUP_REORDER", JSONObject().put("sortOrder", order), now)
            }
        }
    }

    fun reorderAccounts(groupId: UUID, ids: List<UUID>, expectedRevisions: Map<UUID, Long>) = transaction {
        val group = dao.groups().singleOrNull { it.id == groupId.toString() }
        valid(group != null && group.ownerId == owner && group.epochId == epoch && group.kind == "ACCOUNT" && group.deletedAt == null, "账户分组不可用")
        val members = dao.accounts().filter { it.groupId == group!!.id && it.deletedAt == null }.sortedWith(compareBy<AccountEntity> { it.sortOrder }.thenBy { it.createdAt }.thenBy { it.id })
        valid(ids.size == members.size && ids.distinct().size == ids.size && ids.toSet() == members.map { UUID.fromString(it.id) }.toSet(), "账户分组成员已变化，请刷新后重试")
        valid(expectedRevisions.keys == members.map { UUID.fromString(it.id) }.toSet(), "账户分组成员已变化，请刷新后重试")
        valid(members.all { it.ownerId == owner && it.epochId == epoch && expectedRevisions[UUID.fromString(it.id)] == it.localRevision }, "账户已变更，请刷新后重试")
        val byId = members.associateBy { it.id }
        val now = System.currentTimeMillis()
        ids.forEachIndexed { index, uuid ->
            val old = byId.getValue(uuid.toString())
            val order = index.toLong()
            if (old.sortOrder != order) {
                val next = old.copy(sortOrder = order, updatedAt = now, localRevision = Math.addExact(old.localRevision, 1))
                dao.update(next)
                journal(next.id, next.localRevision, old.localRevision, "ACCOUNT_REORDER", JSONObject().put("groupId", groupId.toString()).put("sortOrder", order), now)
            }
        }
    }

    fun moveAccount(id: UUID, targetGroupId: UUID, expectedLocalRevision: Long) = transaction {
        val data = facts()
        val old = data.accounts[id.toString()]?.takeIf { it.deletedAt == null } ?: throw FinanceValidationException("账户不存在")
        val target = data.groups.singleOrNull { it.id == targetGroupId.toString() }
        valid(old.ownerId == owner && old.epochId == epoch && target != null && target.ownerId == owner && target.epochId == epoch && target.kind == "ACCOUNT" && target.deletedAt == null, "目标账户分组不可用")
        valid(old.localRevision == expectedLocalRevision, "账户已变更，请刷新后重试")
        if (old.groupId == target!!.id) return@transaction
        val last = data.accounts.values.filter { it.groupId == target.id && it.deletedAt == null }.maxOfOrNull { it.sortOrder } ?: -1L
        val now = System.currentTimeMillis()
        val next = old.copy(groupId = target.id, sortOrder = Math.addExact(last, 1), updatedAt = now, localRevision = Math.addExact(old.localRevision, 1))
        dao.update(next)
        journal(next.id, next.localRevision, old.localRevision, "ACCOUNT_MOVE_GROUP", JSONObject().put("groupId", target.id).put("sortOrder", next.sortOrder), now)
        readSnapshot()
    }

    fun deleteAccountGroup(id: UUID, expectedLocalRevision: Long, expectedAccountRevisions: Map<UUID, Long>) = transaction {
        val data = facts()
        val source = data.groups.singleOrNull { it.id == id.toString() }
        val target = data.groups.singleOrNull { it.kind == "ACCOUNT" && it.isDefault && it.deletedAt == null }
        valid(source != null && source.ownerId == owner && source.epochId == epoch && source.kind == "ACCOUNT" && source.deletedAt == null, "账户分组不存在")
        valid(!source!!.isDefault && target != null && target.ownerId == owner && target.epochId == epoch, "默认账户分组不能删除")
        valid(source.localRevision == expectedLocalRevision, "账户分组已变更，请刷新后重试")
        val members = data.accounts.values.filter { it.groupId == source.id && it.deletedAt == null }
            .sortedWith(compareBy<AccountEntity> { it.sortOrder }.thenBy { it.createdAt }.thenBy { it.id })
        valid(expectedAccountRevisions.keys == members.map { UUID.fromString(it.id) }.toSet(), "账户分组成员已变化，请刷新后重试")
        valid(members.all { it.ownerId == owner && it.epochId == epoch }, "账户分组包含其他数据集的账户")
        valid(members.all { expectedAccountRevisions[UUID.fromString(it.id)] == it.localRevision }, "账户已变更，请刷新后重试")
        val now = System.currentTimeMillis()
        var nextOrder = (data.accounts.values.filter { it.groupId == target!!.id && it.deletedAt == null }.maxOfOrNull { it.sortOrder } ?: -1L)
        members.forEach { old ->
            nextOrder = Math.addExact(nextOrder, 1)
            val next = old.copy(groupId = target!!.id, sortOrder = nextOrder, updatedAt = now, localRevision = Math.addExact(old.localRevision, 1))
            dao.update(next)
            journal(next.id, next.localRevision, old.localRevision, "ACCOUNT_MOVE_GROUP", JSONObject().put("groupId", target.id).put("sortOrder", nextOrder), now)
        }
        val deleted = source.copy(deletedAt = now, updatedAt = now, localRevision = Math.addExact(source.localRevision, 1))
        dao.update(deleted)
        journal(deleted.id, deleted.localRevision, source.localRevision, "ACCOUNT_GROUP_DELETE_MOVE_TO_DEFAULT", JSONObject().put("targetGroupId", target!!.id), now)
        readSnapshot()
    }

    fun saveTagGroup(
        id: UUID?,
        name: String,
        sortOrder: Long,
        expectedLocalRevision: Long? = null,
    ): UUID = transaction {
        val cleaned = name
        valid(tagNameIsValid(cleaned), "标签分组名称需为 1 至 100 个字符")
        val now = System.currentTimeMillis()
        val old = id?.let { tagDao.groups().singleOrNull { row -> row.id == it.toString() } }
        val nextId = id ?: UUID.randomUUID()
        if (old == null) {
            valid(id == null && expectedLocalRevision == null, "标签分组已变更，请刷新后重试")
            val row = TagGroupEntity(nextId.toString(), owner, epoch, cleaned, sortOrder, false, now, now, 1, null)
            tagDao.insert(row)
            journal(row.id, 1, 0, "TAG_GROUP_CREATE", JSONObject().put("name", cleaned).put("sortOrder", sortOrder), now)
        } else {
            check(old.ownerId == owner && old.epochId == epoch && old.deletedAt == null)
            valid(expectedLocalRevision == old.localRevision, "标签分组已变更，请刷新后重试")
            val row = old.copy(name = cleaned, sortOrder = sortOrder, updatedAt = now, localRevision = Math.addExact(old.localRevision, 1))
            tagDao.update(row)
            journal(row.id, row.localRevision, old.localRevision, "TAG_GROUP_UPDATE", JSONObject().put("name", cleaned).put("sortOrder", sortOrder), now)
        }
        nextId
    }

    fun moveTagGroup(
        id: UUID,
        targetGroupId: UUID,
        expectedGroupRevision: Long,
        expectedTagRevisions: Map<UUID, Long>,
    ) = transaction {
        val groups = tagDao.groups().associateBy { it.id }
        val source = groups[id.toString()]
        val target = groups[targetGroupId.toString()]
        val sourceRow = source ?: throw FinanceValidationException("标签分组不存在")
        val targetRow = target ?: throw FinanceValidationException("目标分组不存在")
        valid(sourceRow.ownerId == owner && sourceRow.epochId == epoch && targetRow.ownerId == owner &&
            targetRow.epochId == epoch && sourceRow.deletedAt == null && targetRow.deletedAt == null,
            "标签分组不存在")
        valid(sourceRow.localRevision == expectedGroupRevision && !sourceRow.isDefault && sourceRow.id != targetRow.id, "标签分组已变更，请刷新后重试")
        val children = tagDao.tags().filter { it.groupId == sourceRow.id && it.deletedAt == null }
        valid(children.all { it.ownerId == owner && it.epochId == epoch }, "标签分组包含其他数据集的标签")
        valid(children.all { expectedTagRevisions[UUID.fromString(it.id)] == it.localRevision }, "标签已变更，请刷新后重试")
        val now = System.currentTimeMillis()
        children.forEach { row ->
            val next = row.copy(groupId = targetRow.id, updatedAt = now, localRevision = Math.addExact(row.localRevision, 1))
            tagDao.update(next)
            journal(next.id, next.localRevision, row.localRevision, "TAG_MOVE_GROUP", JSONObject().put("groupId", targetRow.id), now)
        }
        val deleted = sourceRow.copy(deletedAt = now, updatedAt = now, localRevision = Math.addExact(sourceRow.localRevision, 1))
        tagDao.update(deleted)
        journal(deleted.id, deleted.localRevision, sourceRow.localRevision, "TAG_GROUP_DELETE_MOVE_TO_DEFAULT", JSONObject().put("targetGroupId", targetRow.id), now)
    }

    fun saveTag(
        id: UUID?,
        groupId: UUID,
        name: String,
        color: String?,
        sortOrder: Long,
        expectedLocalRevision: Long? = null,
    ): UUID = transaction {
        val cleaned = name
        valid(tagNameIsValid(cleaned), "标签名称需为 1 至 100 个字符")
        valid(tagColorIsValid(color), "标签颜色无效")
        val group = tagDao.groups().singleOrNull { it.id == groupId.toString() }
        valid(group != null && group.ownerId == owner && group.epochId == epoch && group.deletedAt == null, "标签分组不可用")
        val now = System.currentTimeMillis()
        val old = id?.let { tagDao.tags().singleOrNull { row -> row.id == it.toString() } }
        val nextId = id ?: UUID.randomUUID()
        valid(
            tagDao.tags().none {
                it.id != nextId.toString() && it.groupId == groupId.toString() && it.name == cleaned && it.deletedAt == null
            },
            "同一分组已存在同名标签；如需复用请先恢复已归档标签",
        )
        if (old == null) {
            valid(id == null && expectedLocalRevision == null, "标签已变更，请刷新后重试")
            val row = TagEntity(nextId.toString(), owner, epoch, groupId.toString(), cleaned, color, sortOrder, null, now, now, 1, null)
            tagDao.insert(row)
            journal(row.id, 1, 0, "TAG_CREATE", JSONObject().put("groupId", groupId.toString()).put("name", cleaned).put("color", color ?: JSONObject.NULL).put("sortOrder", sortOrder), now)
        } else {
            check(old.ownerId == owner && old.epochId == epoch && old.deletedAt == null)
            valid(old.localRevision == expectedLocalRevision, "标签已变更，请刷新后重试")
            val row = old.copy(groupId = groupId.toString(), name = cleaned, color = color, sortOrder = sortOrder, updatedAt = now, localRevision = Math.addExact(old.localRevision, 1))
            tagDao.update(row)
            journal(row.id, row.localRevision, old.localRevision, "TAG_UPDATE", JSONObject().put("groupId", groupId.toString()).put("name", cleaned).put("color", color ?: JSONObject.NULL).put("sortOrder", sortOrder), now)
        }
        nextId
    }

    fun setTagArchived(id: UUID, archived: Boolean, expectedLocalRevision: Long) = transaction {
        val old = tagDao.tags().singleOrNull { it.id == id.toString() }
        valid(old != null && old.ownerId == owner && old.epochId == epoch && old.deletedAt == null, "标签不存在")
        valid(old!!.localRevision == expectedLocalRevision, "标签已变更，请刷新后重试")
        val now = System.currentTimeMillis()
        val row = old.copy(archivedAt = if (archived) now else null, updatedAt = now, localRevision = Math.addExact(old.localRevision, 1))
        tagDao.update(row)
        journal(row.id, row.localRevision, old.localRevision, if (archived) "TAG_ARCHIVE" else "TAG_UNARCHIVE", JSONObject().put("archived", archived), now)
    }

    fun deleteTag(id: UUID, expectedLocalRevision: Long, expectedRecordRevisions: Map<UUID, Long>) = transaction {
        val old = tagDao.tags().singleOrNull { it.id == id.toString() }
        valid(old != null && old.ownerId == owner && old.epochId == epoch && old.deletedAt == null, "标签不存在")
        valid(old!!.localRevision == expectedLocalRevision, "标签已变更，请刷新后重试")
        val links = tagDao.recordTags().filter { it.tagId == old.id }
        valid(links.all { it.ownerId == owner && it.epochId == epoch }, "标签关联包含其他数据集的数据")
        val records = dao.records().associateBy { it.id }
        val liveLinks =
            links.filter { link ->
                val record = records[link.recordId]
                valid(
                    record != null && record.ownerId == owner && record.epochId == epoch,
                    "标签关联包含其他数据集的数据",
                )
                if (record!!.deletedAt == null) {
                    valid(
                        expectedRecordRevisions[UUID.fromString(record.id)] == record.localRevision,
                        "关联账单已变更，请刷新后重试",
                    )
                    true
                } else {
                    false
                }
            }
        val templatesToUpdate =
            recordTemplateDao.active().mapNotNull { template ->
                valid(template.ownerId == owner && template.epochId == epoch, "模板属于其他数据集")
                val templateTagIds = parseTemplateTagIds(template.tagIdsJson)
                if (id in templateTagIds) {
                    template to templateTagIds.filterNot { it == id }
                } else {
                    null
                }
            }
        val now = System.currentTimeMillis()
        liveLinks.map { it.recordId }.distinct().forEach { recordId ->
            val record = records.getValue(recordId)
            val next = record.copy(updatedAt = now, localRevision = Math.addExact(record.localRevision, 1))
            dao.update(next)
            journal(record.id, next.localRevision, record.localRevision, "RECORD_TAG_DETACH", JSONObject().put("tagId", old.id), now)
        }
        templatesToUpdate.forEach { (template, remainingTagIds) ->
            val tagIdsJson = JSONArray().also { array -> remainingTagIds.forEach { array.put(it.toString()) } }.toString()
            recordTemplateDao.update(template.copy(tagIdsJson = tagIdsJson, updatedAt = now))
        }
        tagDao.deleteTagLinks(old.id)
        val deleted = old.copy(deletedAt = now, updatedAt = now, localRevision = Math.addExact(old.localRevision, 1))
        tagDao.update(deleted)
        journal(deleted.id, deleted.localRevision, old.localRevision, "TAG_DELETE_DETACH", JSONObject().put("strategy", "DETACH"), now)
    }

    internal fun searchPreferences(): SearchPreferencesEntity? = transaction { searchPreferencesDao.read() }

    internal fun businessPreferences(): BusinessPreferencesEntity = transaction {
        businessPreferencesDao.read()?.also { check(it.ownerId == owner && it.epochId == epoch) }
            ?: BusinessPreferencesEntity(ownerId = owner, epochId = epoch).also(businessPreferencesDao::insert)
    }

    internal fun saveBusinessPreferences(
        expectedRevision: Long,
        refundExcessAsIncome: Boolean,
        showRefundsInRecordList: Boolean,
        loanPrincipalAsIncome: Boolean? = null,
        loanRepaymentAsExpense: Boolean? = null,
        loanInterestAsExpense: Boolean? = null,
    ): BusinessPreferencesEntity = transaction {
        val old = businessPreferencesDao.read() ?: BusinessPreferencesEntity(ownerId = owner, epochId = epoch).also(businessPreferencesDao::insert)
        check(old.ownerId == owner && old.epochId == epoch)
        valid(old.localRevision == expectedRevision, "退款设置已变更，请刷新后重试")
        val next = old.copy(
            localRevision = Math.addExact(old.localRevision, 1),
            refundExcessAsIncome = refundExcessAsIncome,
            showRefundsInRecordList = showRefundsInRecordList,
            loanPrincipalAsIncome = loanPrincipalAsIncome ?: old.loanPrincipalAsIncome,
            loanRepaymentAsExpense = loanRepaymentAsExpense ?: old.loanRepaymentAsExpense,
            loanInterestAsExpense = loanInterestAsExpense ?: old.loanInterestAsExpense,
        )
        businessPreferencesDao.update(next)
        journal(
            "business-preferences:$owner:$epoch", next.localRevision, old.localRevision, "BUSINESS_PREFERENCES",
            JSONObject().put("refundExcessAsIncome", refundExcessAsIncome).put("showRefundsInRecordList", showRefundsInRecordList)
                .put("loanPrincipalAsIncome", next.loanPrincipalAsIncome)
                .put("loanRepaymentAsExpense", next.loanRepaymentAsExpense)
                .put("loanInterestAsExpense", next.loanInterestAsExpense),
            System.currentTimeMillis(),
        )
        readSnapshot() // Validate normalized per-currency income before committing the preference revision.
        next
    }

    internal fun saveSearchPreferences(value: SearchPreferencesEntity) {
        transaction {
            valid(value.singleton == 1, "筛选设置无效")
            if (searchPreferencesDao.read() == null) searchPreferencesDao.insert(value) else searchPreferencesDao.update(value)
        }
    }

    internal fun recordTemplates(): List<FinanceRecordTemplate> =
        transaction {
            val data = facts()
            recordTemplateDao.active().map { row ->
                check(row.ownerId == owner && row.epochId == epoch)
                val input = decodeRecordInput(row.inputJson)
                val templateTagIds = parseTemplateTagIds(row.tagIdsJson)
                val tags = tagDao.tags().associateBy { it.id }
                val direction = templateInputDirection(input)
                val category = row.categoryId?.let { data.categories[it] }
                val invalid =
                    when {
                        templateAccountsInvalid(input, data.accounts) -> "模板引用的账户不可用"

                        row.categoryId != null &&
                            (
                                category == null || category.deletedAt != null || category.ledgerId != data.ledger.id ||
                                    category.direction != direction
                            ) -> "模板分类已失效"

                        templateTagIds.any { id ->
                            val tag = tags[id.toString()]
                            tag == null || tag.deletedAt != null || tag.archivedAt != null || tag.ownerId != owner || tag.epochId != epoch
                        } -> "模板标签已失效"

                        else -> null
                    }
                FinanceRecordTemplate(
                    UUID.fromString(row.id),
                    row.name,
                    input,
                    row.categoryId?.let(UUID::fromString),
                    row.occurredAtMillis,
                    row.timezone,
                    row.remark,
                    invalid,
                    templateTagIds,
                )
            }
        }

    private fun parseTemplateTagIds(tagIdsJson: String): List<UUID> =
        try {
            JSONArray(tagIdsJson).let { array -> (0 until array.length()).map { index -> UUID.fromString(array.getString(index)) } }
        } catch (failure: Exception) {
            throw FinanceValidationException("模板标签引用损坏", failure)
        }

    internal fun saveRecordTemplate(value: FinanceRecordTemplate): UUID =
        transaction {
            val name = value.name.trim()
            valid(templateNameIsValid(name), "模板名称需为 1 至 80 个字符")
            valid(value.remark.length <= 2000 && value.remark.none { it == '\u0000' }, "备注不能超过两千个字符")
            valid(value.timezone in timezones, "时区无效")
            try {
                value.input.calculate()
            } catch (_: Exception) {
                throw FinanceValidationException("请检查预设账单金额和账户分摊")
            }
            val data = facts()
            valid(value.tagIds.size <= 50 && value.tagIds.distinct().size == value.tagIds.size, "模板标签选择无效")
            val templateTags = tagDao.tags().associateBy { it.id }
            valid(value.tagIds.all { id ->
                templateTags[id.toString()]?.let { tag ->
                    tag.ownerId == owner && tag.epochId == epoch && tag.deletedAt == null && tag.archivedAt == null &&
                        tagDao.groups().any { it.id == tag.groupId && it.ownerId == owner && it.epochId == epoch && it.deletedAt == null }
                } == true
            }, "模板标签已失效，请刷新后重试")
            inputAllocations(value.input).forEach { (_, allocation) ->
                val account = data.accounts[allocation.accountId.toString()]
                valid(account != null && account.deletedAt == null && account.type == "ASSET" && account.archivedAt == null && account.selectable, "账户不可用，请选择可用账户")
                valid(account!!.currency == allocation.amount.currency, "账户币种与金额币种不一致")
            }
            val direction = templateInputDirection(value.input)
            if (direction == null) {
                valid(value.categoryId == null, "转账模板无需分类")
            } else {
                value.categoryId?.let { id ->
                    val category = data.categories[id.toString()]
                    valid(
                        category != null && category.deletedAt == null && category.ledgerId == data.ledger.id &&
                            category.direction == direction,
                        "请选择同账本的正确收支分类",
                    )
                }
            }
            val old = recordTemplateDao.active().firstOrNull { it.id == value.id.toString() }
            if (old == null) {
                recordTemplateDao.insert(templateStored(value.copy(name = name), ownerId, epochId, System.currentTimeMillis()))
            } else {
                check(old.ownerId == owner && old.epochId == epoch)
                recordTemplateDao.update(templateStored(value.copy(name = name), ownerId, epochId, System.currentTimeMillis(), old))
            }
            value.id
        }

    internal fun deleteRecordTemplate(id: UUID) {
        transaction {
            val row = recordTemplateDao.active().firstOrNull { it.id == id.toString() }
            valid(row != null && row.ownerId == owner && row.epochId == epoch, "模板不存在")
            val now = System.currentTimeMillis()
            recordTemplateDao.update(row!!.copy(updatedAt = now, deletedAt = now))
        }
    }

    fun budgetOverview(
        period: ReportPeriod,
        atMillis: Long,
        zone: String,
    ): FinanceBudgetOverview = transaction { readBudgetOverview(period, atMillis, zone) }

    fun setBudgetEnabled(
        expected: FinanceBudgetOverview,
        enabled: Boolean,
        atMillis: Long,
        zone: String,
    ) {
        transaction {
            val data = facts()
            val current = readBudgetOverview(expected.period, atMillis, zone, data)
            valid(
                current.preferenceId == expected.preferenceId && current.preferenceRevision == expected.preferenceRevision,
                "预算设置已变更，请刷新后重试",
            )
            val old = data.budgetPreference
            val row =
                BudgetPreferenceEntity(
                    old?.id ?: newId(),
                    owner,
                    epoch,
                    data.ledger.id,
                    enabled,
                    Math.addExact(old?.localRevision ?: 0, 1),
                    old?.createdAt ?: atMillis,
                    atMillis,
                )
            if (old == null) budgetDao.insert(row) else budgetDao.update(row)
            journal(
                row.id,
                row.localRevision,
                old?.localRevision ?: 0,
                "BUDGET_ENABLE",
                JSONObject().put("ledgerId", row.ledgerId).put("enabled", enabled),
                atMillis,
            )
        }
    }

    fun configureBudget(
        expected: FinanceBudgetOverview,
        scope: BudgetScope,
        amount: Money,
        atMillis: Long,
        zone: String,
    ) {
        transaction {
            val data = facts()
            val current = readBudgetOverview(expected.period, atMillis, zone, data)
            valid(current.periodStart == expected.periodStart, "预算周期已变化，请刷新后重试")
            valid(amount.unscaled >= 0 && amount.currency == current.currency, "预算金额不能为负数，且必须使用账本币种")
            val payload =
                JSONObject()
                    .put("ledgerId", data.ledger.id)
                    .put("periodType", expected.period.name)
                    .put("scope", scope.name)
                    .put("periodStart", current.periodStart)
                    .put("amount", moneyIntent(amount))
            when (scope) {
                BudgetScope.DEFAULT -> {
                    valid(
                        current.templateId == expected.templateId && current.templateRevision == expected.templateRevision,
                        "默认预算已变更，请刷新后重试",
                    )
                    val old =
                        data.budgetTemplates.singleOrNull {
                            it.periodType == expected.period.name &&
                                it.effectiveFrom == current.periodStart
                        }
                    val row =
                        BudgetTemplateEntity(
                            old?.id ?: newId(),
                            owner,
                            epoch,
                            data.ledger.id,
                            expected.period.name,
                            current.periodStart,
                            amount.unscaled,
                            amount.scale,
                            amount.currency,
                            Math.addExact(old?.localRevision ?: 0, 1),
                            old?.createdAt ?: atMillis,
                            atMillis,
                        )
                    if (old == null) budgetDao.insert(row) else budgetDao.update(row)
                    journal(
                        row.id,
                        row.localRevision,
                        old?.localRevision ?: 0,
                        "BUDGET_CONFIGURE",
                        payload.put("effectiveFrom", row.effectiveFrom),
                        atMillis,
                    )
                }

                BudgetScope.CURRENT -> {
                    valid(
                        current.overrideId == expected.overrideId && current.overrideRevision == expected.overrideRevision,
                        "当期预算已变更，请刷新后重试",
                    )
                    val old =
                        data.budgetOverrides.singleOrNull {
                            it.periodType == expected.period.name &&
                                it.periodStart == current.periodStart
                        }
                    val row =
                        BudgetOverrideEntity(
                            old?.id ?: newId(),
                            owner,
                            epoch,
                            data.ledger.id,
                            expected.period.name,
                            current.periodStart,
                            amount.unscaled,
                            amount.scale,
                            amount.currency,
                            Math.addExact(old?.localRevision ?: 0, 1),
                            old?.createdAt ?: atMillis,
                            atMillis,
                            null,
                        )
                    if (old == null) budgetDao.insert(row) else budgetDao.update(row)
                    journal(row.id, row.localRevision, old?.localRevision ?: 0, "BUDGET_CONFIGURE", payload, atMillis)
                }
            }
            readBudgetOverview(expected.period, atMillis, zone)
        }
    }

    fun resetBudgetToDefault(
        expected: FinanceBudgetOverview,
        atMillis: Long,
        zone: String,
    ) {
        transaction {
            val data = facts()
            val current = readBudgetOverview(expected.period, atMillis, zone, data)
            valid(current.periodStart == expected.periodStart, "预算周期已变化，请刷新后重试")
            valid(current.overrideId == expected.overrideId && current.overrideRevision == expected.overrideRevision, "当期预算已变更，请刷新后重试")
            val old = data.budgetOverrides.singleOrNull { it.periodType == expected.period.name && it.periodStart == current.periodStart }
            if (old != null && old.deletedAt == null) {
                val row = old.copy(deletedAt = atMillis, updatedAt = atMillis, localRevision = Math.addExact(old.localRevision, 1))
                budgetDao.update(row)
                journal(
                    row.id,
                    row.localRevision,
                    old.localRevision,
                    "BUDGET_RESET",
                    JSONObject()
                        .put(
                            "ledgerId",
                            row.ledgerId,
                        ).put("periodType", row.periodType)
                        .put("periodStart", row.periodStart)
                        .put("mode", "RESET_DEFAULT"),
                    atMillis,
                )
            }
            readBudgetOverview(expected.period, atMillis, zone)
        }
    }

    fun createAccount(
        name: String,
        currency: String,
        openingBalance: Money,
        openingDateMillis: Long,
        groupId: UUID? = null,
        accountType: String = "ASSET",
    ): UUID =
        transaction {
            valid(name.isNotBlank() && name.length <= 80 && name.none { it.isISOControl() }, "账户名称须为一至八十个字符")
            valid(currency == openingBalance.currency, "账户币种与金额币种不一致")
            valid(accountType in setOf("ASSET", "LIABILITY"), "账户类型无效")
            val facts = facts()
            val group = if (groupId == null) {
                facts.groups.single { it.kind == "ACCOUNT" && it.isDefault && it.deletedAt == null }
            } else {
                facts.groups.singleOrNull { it.id == groupId.toString() }
                    ?: throw FinanceValidationException("账户分组不可用")
            }
            valid(group.ownerId == owner && group.epochId == epoch && group.kind == "ACCOUNT" && group.deletedAt == null, "账户分组不可用")
            val id = UUID.randomUUID()
            val now = System.currentTimeMillis()
            val zone = TimeZone.getDefault().id
            val event =
                event(
                    id = newId(),
                    ledgerId = facts.ledger.id,
                    recordId = null,
                    revision = 1,
                    kind = "OPENING",
                    at = openingDateMillis,
                    zone = zone,
                    remark = "",
                    category = null,
                    input = null,
                    now = now,
                    amount = openingBalance,
                )
            dao.insert(event)
            val sortOrder = Math.addExact(facts.accounts.values.filter { it.groupId == group.id && it.deletedAt == null }.maxOfOrNull { it.sortOrder } ?: -1L, 1L)
            dao.insert(AccountEntity(id.toString(), owner, epoch, group.id, event.id, name, currency, accountType, now, now, 1, null, sortOrder = sortOrder))
            dao.insert(posting(event.id, 0, AccountAmount(id, openingBalance)))
            journal(
                id.toString(),
                1,
                0,
                "ACCOUNT_CREATE",
                JSONObject()
                    .put("name", name)
                    .put("currency", currency)
                    .put("type", accountType)
                    .put("groupId", group.id)
                    .put("openingEvent", eventIntent(event)),
                now,
            )
            readSnapshot() // Final balances and contribution totals must fit Money before commit.
            id
        }

    fun setAccountArchived(
        id: UUID,
        expectedLocalRevision: Long,
        archived: Boolean,
    ) = changeAccountMetadata(id, expectedLocalRevision, "ACCOUNT_ARCHIVE", "archivedAt") { account, now ->
        if (archived) account.copy(archivedAt = now, selectable = false) else account.copy(archivedAt = null)
    }

    fun setAccountSelectable(
        id: UUID,
        expectedLocalRevision: Long,
        selectable: Boolean,
    ) = changeAccountMetadata(id, expectedLocalRevision, "ACCOUNT_SELECTABLE", "selectable") { account, _ ->
        valid(account.archivedAt == null || !selectable, "请先恢复账户")
        account.copy(selectable = selectable)
    }

    fun saveRelatedPersonProfile(accountId: UUID, direction: String, relation: String, authorizedLimit: Money) = transaction {
        valid(!accountDataset, "亲情账户资料仅支持游客本机使用")
        valid(direction in setOf("GIVEN_TO_RELATIVE", "RECEIVED_FROM_RELATIVE"), "亲友方向无效")
        val normalizedRelation = relation.trim()
        valid(normalizedRelation.isNotEmpty() && normalizedRelation.codePointCount(0, normalizedRelation.length) <= 80, "亲友关系须为一至八十个字符")
        valid(authorizedLimit.unscaled >= 0, "授权额度不能为负数")
        val account = dao.accounts().singleOrNull { it.id == accountId.toString() && it.ownerId == owner && it.epochId == epoch && it.deletedAt == null }
            ?: throw FinanceValidationException("账户不存在")
        valid(account.type in setOf("ASSET", "LIABILITY"), "账户类型无效")
        valid(authorizedLimit.currency == account.currency, "授权额度币种须与账户一致")
        val previous = relatedPersonDao.profile(account.id)
        val now = System.currentTimeMillis()
        relatedPersonDao.upsert(RelatedPersonProfileEntity(
            accountId = account.id,
            ownerId = owner,
            epochId = epoch,
            direction = direction,
            relation = normalizedRelation,
            authorizedLimitUnscaled = authorizedLimit.unscaled,
            authorizedLimitScale = authorizedLimit.scale,
            currency = authorizedLimit.currency,
            createdAt = previous?.createdAt ?: now,
            updatedAt = now,
        ))
        readSnapshot()
    }

    fun clearRelatedPersonProfile(accountId: UUID) = transaction {
        valid(!accountDataset, "亲情账户资料仅支持游客本机使用")
        val profile = relatedPersonDao.profile(accountId.toString()) ?: return@transaction readSnapshot()
        valid(profile.ownerId == owner && profile.epochId == epoch, "亲情资料不可用")
        relatedPersonDao.delete(profile.accountId)
        readSnapshot()
    }

    fun setAccountIncludedInAssets(
        id: UUID,
        expectedLocalRevision: Long,
        included: Boolean,
    ) = changeAccountMetadata(id, expectedLocalRevision, "ACCOUNT_ASSET_INCLUSION", "includeInAssets") { account, _ ->
        account.copy(includeInAssets = included)
    }

    fun updateAccountDetails(
        id: UUID,
        expectedLocalRevision: Long,
        name: String,
        groupId: UUID,
        expectedGroupRevision: Long,
    ) = transaction {
        val cleaned = name.trim()
        valid(cleaned.isNotEmpty() && cleaned.length <= 80 && cleaned.none { it.isISOControl() }, "账户名称须为一至八十个字符")
        val data = facts()
        val old = data.accounts[id.toString()]?.takeIf { it.deletedAt == null }
            ?: throw FinanceValidationException("账户不存在")
        val target = data.groups.singleOrNull { it.id == groupId.toString() }
        valid(old.ownerId == owner && old.epochId == epoch, "账户不可用")
        valid(target != null && target.ownerId == owner && target.epochId == epoch && target.kind == "ACCOUNT" && target.deletedAt == null, "目标账户分组不可用")
        valid(old.localRevision == expectedLocalRevision, "账户已变更，请刷新后重试")
        valid(target!!.localRevision == expectedGroupRevision, "账户分组已变更，请刷新后重试")
        val now = System.currentTimeMillis()
        val sortOrder = if (old.groupId == target.id) old.sortOrder else {
            Math.addExact(data.accounts.values.filter { it.groupId == target.id && it.deletedAt == null }.maxOfOrNull { it.sortOrder } ?: -1L, 1L)
        }
        val next = old.copy(name = cleaned, groupId = target.id, sortOrder = sortOrder, updatedAt = now, localRevision = Math.addExact(old.localRevision, 1))
        dao.update(next)
        journal(next.id, next.localRevision, old.localRevision, "ACCOUNT_UPDATE", JSONObject().put("name", cleaned).put("groupId", target.id), now)
        readSnapshot()
    }

    private fun changeAccountMetadata(
        id: UUID,
        expectedLocalRevision: Long,
        kind: String,
        field: String,
        transform: (AccountEntity, Long) -> AccountEntity,
    ) = transaction {
        val old = facts().accounts[id.toString()]?.takeIf { it.deletedAt == null }
            ?: throw FinanceValidationException("账户不存在")
        check(old.ownerId == owner && old.epochId == epoch)
        valid(old.localRevision == expectedLocalRevision, "账户已变更，请刷新后重试")
        val now = System.currentTimeMillis()
        val revised = transform(old, now).copy(updatedAt = now, localRevision = Math.addExact(old.localRevision, 1))
        dao.update(revised)
        journal(id.toString(), revised.localRevision, old.localRevision, kind, JSONObject().put(field, when (field) {
            "archivedAt" -> revised.archivedAt ?: JSONObject.NULL
            "selectable" -> revised.selectable
            else -> revised.includeInAssets
        }), now)
        readSnapshot()
    }

    fun markClaim(
        id: UUID,
        claimState: String,
        expectedLocalRevision: Long,
    ) = transaction {
        valid(claimState in setOf("NONE", "PENDING"), "不支持的报销状态")
        val facts = facts()
        val record =
            facts.records[id.toString()]?.takeIf { it.deletedAt == null }
                ?: throw FinanceValidationException("账单不存在或已删除")
        valid(record.localRevision == expectedLocalRevision, "账单已变更，请刷新后重试")
        valid(!hasActiveSavingLink(record.id), "存钱或贷款关联账单不能修改报销状态")
        valid(record.claimState in setOf("NONE", "PENDING") && record.claimState != claimState, "当前账单不能更改报销状态")
        valid(!hasPostedRefund(facts, record.id), "仍有已入账退款，不能更改报销标记")
        valid(claimState != "NONE" || !hasPostedClaim(facts, record.id), "仍有已入账报销，不能取消标记")
        val event = facts.currentEvent(record)
        val input = restoreInput(event, facts.allocations.filter { it.eventId == event.id })
        val expense = input as? RecordInput.Expense ?: throw FinanceValidationException("仅支出账单可标记待报销")
        valid((expense.discount?.let(expense.gross::checkedSubtract) ?: expense.gross).unscaled > 0, "零金额账单不可报销")
        val now = System.currentTimeMillis()
        val revision = Math.addExact(record.localRevision, 1)
        dao.update(record.copy(updatedAt = now, localRevision = revision, claimState = claimState))
        journal(id.toString(), revision, record.localRevision, "CLAIM_MARK", JSONObject().put("claimState", claimState), now)
        readSnapshot()
    }

    fun settleClaims(
        allocations: List<FinanceClaimAllocation>,
        accountId: UUID?,
        incomeCategoryId: UUID?,
        remainderAction: RemainderAction,
        settledAtMillis: Long,
        timezone: String,
    ): UUID =
        transaction {
            valid(allocations.isNotEmpty() && allocations.size <= 50, "一次最多结算五十笔报销")
            valid(allocations.map { it.originRecordId }.distinct().size == allocations.size, "报销账单重复")
            val currency = allocations.first().actualAmount.currency
            allocations.forEach { it.actualAmount.requirePositive() }
            valid(allocations.all { it.actualAmount.currency == currency }, "报销批次币种不一致")
            val amount = total(currency, allocations.map { it.actualAmount })
            val now = System.currentTimeMillis()
            valid(settledAtMillis in 0..now && timezone in timezones, "报销时间无效")
            val date = localDate(settledAtMillis, timezone)
            val facts = facts()
            val account =
                accountId?.let { id ->
                    facts.accounts[id.toString()]?.takeIf { it.deletedAt == null && it.archivedAt == null && it.selectable && it.currency == currency }
                        ?: throw FinanceValidationException("报销入账账户不可用")
                }
            val incomeCategory =
                incomeCategoryId?.let { id ->
                    facts.categories[id.toString()]?.takeIf {
                        it.deletedAt == null && it.ledgerId == facts.ledger.id && it.direction == "INCOME"
                    } ?: throw FinanceValidationException("报销收入分类不可用")
                }
            val records =
                allocations.map { allocation ->
                    val record =
                        facts.records[allocation.originRecordId.toString()]?.takeIf { it.deletedAt == null }
                            ?: throw FinanceValidationException("待报销账单不存在或已删除")
                    valid(record.localRevision == allocation.expectedLocalRevision, "账单已变更，请刷新后重试")
                    valid(!hasActiveSavingLink(record.id), "存钱或贷款关联账单不能报销")
                    valid(record.claimState in setOf("PENDING", "PARTIAL"), "账单当前不可报销")
                    valid(!hasPostedRefund(facts, record.id), "仍有已入账退款，不能结算报销")
                    val originalEvent = facts.currentEvent(record)
                    valid(originalEvent.occurredAtMillis <= settledAtMillis, "报销时间不能早于原账单")
                    val expense =
                        restoreInput(originalEvent, facts.allocations.filter { it.eventId == originalEvent.id }) as? RecordInput.Expense
                            ?: throw FinanceValidationException("仅支出账单可报销")
                    val net = expense.discount?.let(expense.gross::checkedSubtract) ?: expense.gross
                    valid(net.unscaled > 0 && net.currency == currency, "报销金额与原账单不匹配")
                    record
                }
            val existing = claimSettlements(facts)
            allocations.forEach { allocation ->
                valid(
                    existing.count { settlement ->
                        settlement.status == "POSTED" && settlement.allocations.any { it.originRecordId == allocation.originRecordId }
                    } < 100,
                    "单笔账单报销批次过多",
                )
            }
            val id = newId()
            val event =
                event(
                    id,
                    facts.ledger.id,
                    null,
                    1,
                    "CLAIM_SETTLEMENT",
                    settledAtMillis,
                    timezone,
                    "报销收款",
                    incomeCategory?.id,
                    null,
                    now,
                    amount,
                )
            dao.insert(event)
            account?.let {
                dao.insert(posting(event.id, 0, AccountAmount(UUID.fromString(it.id), liabilityAwareStoredAmount(it.type, amount))))
            }
            journal(
                id,
                1,
                0,
                "CLAIM_SETTLE",
                JSONObject()
                    .put("eventId", id)
                    .put("actualAmount", claimMoneyIntent(amount))
                    .put("accountId", account?.id ?: JSONObject.NULL)
                    .put("incomeCategoryId", incomeCategory?.id ?: JSONObject.NULL)
                    .put("remainderAction", remainderAction.name)
                    .put("settledAtMillis", settledAtMillis)
                    .put("timezone", timezone)
                    .put(
                        "allocations",
                        JSONArray().also { array ->
                            allocations.forEach { allocation ->
                                array.put(
                                    JSONObject()
                                        .put("originRecordId", allocation.originRecordId.toString())
                                        .put("expectedLocalRevision", allocation.expectedLocalRevision)
                                        .put("actualAmount", claimMoneyIntent(allocation.actualAmount)),
                                )
                            }
                        },
                    ),
                now,
            )
            rebuildClaimRecords(records, now)
            readSnapshot()
            UUID.fromString(id)
        }

    fun cancelClaimSettlement(
        id: UUID,
        expectedLocalRevision: Long,
        reason: String,
    ) = transaction {
        val facts = facts()
        val settlement = claimSettlements(facts).singleOrNull { it.id == id }
        valid(settlement != null && settlement.status == "POSTED", "报销批次不存在或已撤销")
        valid(settlement!!.localRevision == expectedLocalRevision, "报销批次已变更，请刷新后重试")
        val cleanedReason = reason.trim()
        valid(cleanedReason.isNotEmpty() && cleanedReason.length <= 1000, "请输入撤销原因")
        val original = facts.events.getValue(id.toString())
        val now = System.currentTimeMillis()
        val reversal = reverse(original, Math.addExact(original.localRevision, 1), now)
        journal(
            id.toString(),
            Math.addExact(settlement.localRevision, 1),
            settlement.localRevision,
            "CLAIM_CANCEL",
            JSONObject().put("reason", cleanedReason).put("reversalEventId", reversal.id),
            now,
        )
        val records = settlement.allocations.map { facts.records.getValue(it.originRecordId.toString()) }
        rebuildClaimRecords(records, now)
        readSnapshot()
    }

    fun createRefund(
        originRecordId: UUID,
        expectedLocalRevision: Long,
        amount: Money,
        accountId: UUID,
        refundAtMillis: Long,
        timezone: String,
    ): UUID = transaction {
        amount.requirePositive()
        val data = facts()
        val record = data.records[originRecordId.toString()]?.takeIf { it.deletedAt == null }
            ?: throw FinanceValidationException("退款原账单不存在或已删除")
        valid(record.localRevision == expectedLocalRevision, "账单已变更，请刷新后重试")
        valid(!hasActiveSavingLink(record.id), "存钱或贷款关联账单不能退款")
        valid(!hasPostedClaim(data, record.id), "仍有已入账报销，不能登记退款")
        val original = data.currentEvent(record)
        val expense = restoreInput(original, data.allocations.filter { it.eventId == original.id }) as? RecordInput.Expense
            ?: throw FinanceValidationException("仅支出账单可退款")
        val principal = expense.discount?.let(expense.gross::checkedSubtract) ?: expense.gross
        valid(principal.unscaled > 0 && amount.currency == principal.currency, "退款金额与原账单币种不匹配")
        val account = data.accounts[accountId.toString()]?.takeIf { it.deletedAt == null && it.archivedAt == null && it.selectable && it.currency == amount.currency }
            ?: throw FinanceValidationException("退款入账账户不可用")
        val now = System.currentTimeMillis()
        valid(timezone in timezones && refundAtMillis in original.occurredAtMillis..now, "退款时间无效")
        val before = refunds(data)
        valid(before.count { it.originRecordId == originRecordId } < 100, "单笔账单退款次数过多")
        val id = newId()
        val receipt = event(id, data.ledger.id, null, 1, "REFUND", refundAtMillis, timezone, "退款收款", original.categoryId, null, now, amount)
        dao.insert(receipt)
        dao.insert(posting(id, 0, AccountAmount(accountId, liabilityAwareStoredAmount(account.type, amount))))
        journal(id, 1, 0, "REFUND_CREATE", JSONObject()
            .put("originRecordId", record.id)
            .put("expectedLocalRevision", record.localRevision)
            .put("amount", claimMoneyIntent(amount))
            .put("accountId", account.id)
            .put("refundAtMillis", refundAtMillis)
            .put("timezone", timezone)
            .put("localDate", receipt.localDate), now)
        rebuildRefundRecord(record, before, id, now)
        readSnapshot()
        UUID.fromString(id)
    }

    fun cancelRefund(
        id: UUID,
        expectedLocalRevision: Long,
        expectedOriginRevision: Long,
        reason: String,
    ) = transaction {
        val data = facts()
        val before = refunds(data)
        val refund = before.singleOrNull { it.id == id }
            ?: throw FinanceValidationException("退款不存在")
        valid(refund.status == "POSTED" && refund.localRevision == expectedLocalRevision, "退款已变更或撤销，请刷新后重试")
        val record = data.records.getValue(refund.originRecordId.toString())
        valid(record.deletedAt == null && record.localRevision == expectedOriginRevision, "原账单已变更，请刷新后重试")
        val cleanedReason = reason.trim()
        valid(cleanedReason.isNotEmpty() && cleanedReason.length <= 1000, "请输入撤销原因")
        val now = System.currentTimeMillis()
        val revision = Math.addExact(refund.localRevision, 1)
        val reversal = reverse(data.events.getValue(id.toString()), revision, now)
        journal(id.toString(), revision, refund.localRevision, "REFUND_CANCEL", JSONObject()
            .put("originRecordId", record.id)
            .put("expectedOriginRevision", record.localRevision)
            .put("reason", cleanedReason)
            .put("reversalEventId", reversal.id), now)
        rebuildRefundRecord(record, before, id.toString(), now)
        readSnapshot()
    }

    fun createSaving(input: SavingPlanInput): UUID = transaction {
        validateSavingPlanDatePair(input.startDate, input.endDate)
        validateSavingPlan(input)
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID()
        val row = input.toSavingEntity(id.toString(), owner, epoch, now)
        savingDao.insert(row)
        journal(row.id, 1, 0, "saving.create", savingPlanIntent(input), now)
        readSnapshot()
        id
    }

    fun updateSaving(id: UUID, expectedLocalRevision: Long, patch: SavingPlanPatch) = transaction {
        val old = savingDao.plans().singleOrNull { it.id == id.toString() && it.ownerId == owner && it.epochId == epoch && it.deletedAt == null }
            ?: throw FinanceValidationException("存钱计划不存在")
        valid(old.localRevision == expectedLocalRevision, "存钱计划已变更，请刷新后重试")
        val currentInput = old.toSavingInput()
        val proposed = currentInput.copy(
            name = patch.name.orCurrent(old.name) ?: throw FinanceValidationException("计划名称不能为空"),
            iconRef = patch.iconRef.orCurrent(old.iconRef),
            target = patch.target.orCurrent(Money(old.targetUnscaled, old.targetScale, old.currency))
                ?: throw FinanceValidationException("计划目标不能为空"),
            endDate = patch.endDate.orCurrent(old.endDate),
            allowManualEntries = patch.allowManualEntries.orCurrent(old.allowManualEntries)
                ?: throw FinanceValidationException("手动存入设置无效"),
        )
        // Keep legacy guest end-only metadata editable without creating new end-only plans.
        if (currentInput.startDate != null || currentInput.endDate == null) validateSavingPlanDatePair(proposed.startDate, proposed.endDate)
        validateSavingPlan(proposed)
        val hasHistory = savingDao.entries().any { it.ownerId == owner && it.epochId == epoch && it.planId == old.id }
        valid(!hasHistory || (proposed.kind.name == old.kind && proposed.initialSaved == old.toSavingInput().initialSaved &&
            proposed.startDate == old.startDate && proposed.interval?.unit?.name == old.intervalUnit &&
            proposed.interval?.step == old.intervalStep && proposed.firstAmount == old.firstAmountMoney() &&
            proposed.increment == old.incrementMoney()), "已有存钱记录后，计划规则和初始金额不能修改")
        val now = System.currentTimeMillis()
        val revision = Math.addExact(old.localRevision, 1)
        savingDao.update(old.copy(
            name = proposed.name.trim(), iconRef = proposed.iconRef,
            targetUnscaled = proposed.target.unscaled, targetScale = proposed.target.scale,
            endDate = proposed.endDate, allowManualEntries = proposed.allowManualEntries,
            updatedAt = now, localRevision = revision,
        ))
        journal(old.id, revision, old.localRevision, "saving.update", savingPlanIntent(proposed), now)
        readSnapshot()
    }

    fun addSavingEntry(planId: UUID, expectedPlanRevision: Long, input: SavingEntryInput): UUID = transaction {
        val plan = savingDao.plans().singleOrNull { it.id == planId.toString() && it.ownerId == owner && it.epochId == epoch && it.deletedAt == null }
            ?: throw FinanceValidationException("存钱计划不存在")
        valid(plan.localRevision == expectedPlanRevision && plan.state == "ACTIVE", "计划已变更、归档或删除")
        input.amount.requirePositive()
        valid(input.amount.currency == plan.currency, "存入金额币种与计划不一致")
        val entryDate = savingUtcDate(input.occurredAtMillis)
        valid((plan.startDate == null || entryDate >= plan.startDate) && (plan.endDate == null || entryDate <= plan.endDate),
            "存钱记录日期超出计划范围")
        val remark = normalizeNewSavingEntryRemark(input.remark)
        val card = input.cardIndex
        valid(card == null || card > 0, "卡片序号无效")
        valid(input.kind == SavingEntryKind.DEPOSIT || card == null, "取出记录不能指定卡片")
        valid(plan.kind != "FLEXIBLE" || card == null, "灵活计划不能指定卡片")
        valid(card != null || input.kind != SavingEntryKind.DEPOSIT || plan.kind == "FLEXIBLE" || plan.allowManualEntries, "此计划不允许无卡片存入")
        if (card != null) {
            val active = savingDao.entries().filter { it.ownerId == owner && it.epochId == epoch && it.planId == plan.id && it.cancelledAt == null }
            valid(active.none { it.cardIndex == card }, "该卡片已有有效记录")
            val expected = when (plan.kind) {
                "WEEK_52", "DAY_365" -> {
                    valid(card <= if (plan.kind == "WEEK_52") 52 else 365, "卡片序号超出计划范围")
                    val first = plan.firstAmountMoney() ?: throw FinanceValidationException("卡片规则无效")
                    val increment = plan.incrementMoney() ?: Money(0, 0, plan.currency)
                    val totalUnits = java.math.BigInteger.valueOf(first.unscaled).multiply(java.math.BigInteger.TEN.pow(6 - first.scale)) +
                        java.math.BigInteger.valueOf(increment.unscaled).multiply(java.math.BigInteger.TEN.pow(6 - increment.scale))
                            .multiply(java.math.BigInteger.valueOf((card - 1).toLong()))
                    wideSavingMoney(totalUnits, 6, plan.currency)
                }
                "FIXED" -> {
                    val first = plan.firstAmountMoney() ?: throw FinanceValidationException("卡片规则无效")
                    val count = Money(plan.targetUnscaled, plan.targetScale, plan.currency).decimalForSaving()
                        .divide(first.decimalForSaving(), 0, java.math.RoundingMode.CEILING).intValueExact()
                    valid(card <= count, "卡片序号超出计划范围")
                    if (card == count) {
                        val scale = maxOf(plan.targetScale, first.scale)
                        val total = java.math.BigInteger.valueOf(plan.targetUnscaled).multiply(java.math.BigInteger.TEN.pow(scale - plan.targetScale))
                        val consumed = java.math.BigInteger.valueOf(first.unscaled).multiply(java.math.BigInteger.TEN.pow(scale - first.scale))
                            .multiply(java.math.BigInteger.valueOf((count - 1).toLong()))
                        wideSavingMoney(total - consumed, scale, plan.currency)
                    } else first
                }
                else -> input.amount
            }
            valid(expected.sameValue(input.amount), "存入金额与卡片金额不一致")
        }
        val transfer = input.linkedTransfer
        if (transfer != null) {
            val transferAmount = if (input.kind == SavingEntryKind.DEPOSIT) transfer.input.to.amount else transfer.input.from.amount
            valid(transferAmount.sameValue(input.amount), "关联转账金额必须与存钱金额一致")
            valid(facts().records[transfer.recordId.toString()] == null, "关联转账记录编号已存在")
            saveRecordInternal(
                null, transfer.input, input.occurredAtMillis, transfer.timezone,
                remark.orEmpty(), transfer.categoryId, null, transfer.tagIds, suppressJournal = true, createId = transfer.recordId,
            )
        }
        val linkedEvent = transfer?.let { id -> facts().currentEvent(facts().records.getValue(id.recordId.toString())).id }
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID()
        val entry = SavingEntryEntity(id.toString(), owner, epoch, plan.id, input.kind.name, input.amount.unscaled,
            input.amount.scale, input.amount.currency, input.occurredAtMillis, card, remark,
            transfer?.recordId?.toString(), linkedEvent, now, 1, null)
        savingDao.insert(entry)
        val revision = Math.addExact(plan.localRevision, 1)
        savingDao.update(plan.copy(localRevision = revision, updatedAt = now))
        journal(plan.id, revision, plan.localRevision, "saving.entry", savingEntryIntent(entry), now)
        readSnapshot()
        id
    }

    fun cancelSavingEntry(planId: UUID, entryId: UUID, expectedPlanRevision: Long, expectedEntryRevision: Long) = transaction {
        val plan = savingDao.plans().singleOrNull { it.id == planId.toString() && it.ownerId == owner && it.epochId == epoch && it.deletedAt == null }
            ?: throw FinanceValidationException("存钱计划不存在")
        val entry = savingDao.entries().singleOrNull { it.id == entryId.toString() && it.planId == plan.id && it.ownerId == owner && it.epochId == epoch }
            ?: throw FinanceValidationException("存钱记录不存在")
        valid(plan.localRevision == expectedPlanRevision && entry.localRevision == expectedEntryRevision && entry.cancelledAt == null,
            "存钱记录已变更或已撤销，请刷新后重试")
        val wallNow = System.currentTimeMillis()
        val previousCancellation = entry.cardIndex?.let { index ->
            savingDao.entries().filter { it.planId == plan.id && it.cardIndex == index }.maxOfOrNull { it.cancelledAt ?: 0L }
        } ?: 0L
        val now = maxOf(wallNow, if (previousCancellation == Long.MAX_VALUE) throw ArithmeticException() else previousCancellation + 1)
        var reversalId: String? = null
        if (entry.linkedRecordId != null) {
            val facts = facts()
            val record = facts.records[entry.linkedRecordId] ?: throw FinanceValidationException("关联转账记录不存在")
            valid(record.deletedAt == null, "关联转账已删除")
            val current = facts.currentEvent(record)
            valid(current.id == entry.linkedTransferEventId, "关联转账已变化，请刷新后重试")
            val recordRevision = Math.addExact(record.localRevision, 1)
            reversalId = reverse(current, recordRevision, now).id
            dao.update(record.copy(updatedAt = now, localRevision = recordRevision, deletedAt = now))
            tagDao.deleteRecordTags(record.id)
        }
        savingDao.update(entry.copy(cancelledAt = now, localRevision = Math.addExact(entry.localRevision, 1)))
        val revision = Math.addExact(plan.localRevision, 1)
        savingDao.update(plan.copy(localRevision = revision, updatedAt = now))
        journal(plan.id, revision, plan.localRevision, "saving.cancelEntry",
            JSONObject().put("entryId", entry.id).put("expectedEntryRevision", entry.localRevision)
                .put("linkedRecordId", entry.linkedRecordId ?: JSONObject.NULL)
                .put("linkedTransferEventId", entry.linkedTransferEventId ?: JSONObject.NULL)
                .put("reversalEventId", reversalId ?: JSONObject.NULL), now)
        readSnapshot()
    }

    fun archiveSaving(id: UUID, expectedLocalRevision: Long, archived: Boolean) = transaction {
        val old = savingDao.plans().singleOrNull { it.id == id.toString() && it.ownerId == owner && it.epochId == epoch && it.deletedAt == null }
            ?: throw FinanceValidationException("存钱计划不存在")
        valid(old.localRevision == expectedLocalRevision, "存钱计划已变更，请刷新后重试")
        val state = if (archived) "ARCHIVED" else "ACTIVE"
        valid(old.state != state, "存钱计划状态未变化")
        val now = System.currentTimeMillis()
        val revision = Math.addExact(old.localRevision, 1)
        savingDao.update(old.copy(state = state, updatedAt = now, localRevision = revision))
        journal(old.id, revision, old.localRevision, "saving.archive", JSONObject().put("archived", archived), now)
        readSnapshot()
    }

    fun deleteSaving(id: UUID, expectedLocalRevision: Long, strategy: SavingTransferStrategy) = transaction {
        val old = savingDao.plans().singleOrNull { it.id == id.toString() && it.ownerId == owner && it.epochId == epoch && it.deletedAt == null }
            ?: throw FinanceValidationException("存钱计划不存在")
        valid(old.localRevision == expectedLocalRevision, "存钱计划已变更，请刷新后重试")
        val linked = savingDao.entries().filter { it.planId == old.id && it.ownerId == owner && it.epochId == epoch && it.cancelledAt == null && it.linkedRecordId != null }
        val recordIds = JSONArray()
        val baseRevisions = JSONObject()
        val reversalIds = JSONObject()
        if (strategy == SavingTransferStrategy.DELETE_TRANSFERS) {
            val f = facts()
            val selected = linked.map { entry ->
                val record = f.records[entry.linkedRecordId] ?: throw FinanceValidationException("关联转账已不存在")
                valid(record.deletedAt == null, "关联转账已删除")
                valid(!hasPostedRecovery(f, record.id), "关联转账存在未完成的报销或退款")
                Triple(entry, record, Math.addExact(record.localRevision, 1))
            }
            selected.forEach { (_, record, recordRevision) ->
                val now = System.currentTimeMillis()
                val reversal = reverse(f.currentEvent(record), recordRevision, now)
                dao.update(record.copy(updatedAt = now, localRevision = recordRevision, deletedAt = now))
                tagDao.deleteRecordTags(record.id)
                recordIds.put(record.id)
                baseRevisions.put(record.id, record.localRevision)
                reversalIds.put(record.id, reversal.id)
            }
        }
        val now = System.currentTimeMillis()
        val revision = Math.addExact(old.localRevision, 1)
        savingDao.update(old.copy(state = "DELETED", deletedAt = now, updatedAt = now, localRevision = revision))
        journal(old.id, revision, old.localRevision, "saving.delete",
            JSONObject().put("transferStrategy", strategy.name).put("recordIds", recordIds)
                .put("baseLocalRevisions", baseRevisions).put("reversalEventIds", reversalIds), now)
        readSnapshot()
    }

    fun saveRecord(
        id: UUID?,
        input: RecordInput,
        occurredAtMillis: Long,
        timezone: String,
        remark: String,
        categoryId: UUID?,
        expectedLocalRevision: Long? = null,
        tagIds: List<UUID> = emptyList(),
        createId: UUID? = null,
    ): UUID = saveRecordInternal(id, input, occurredAtMillis, timezone, remark, categoryId, expectedLocalRevision, tagIds, false, createId)

    private fun saveRecordInternal(
        id: UUID?,
        input: RecordInput,
        occurredAtMillis: Long,
        timezone: String,
        remark: String,
        categoryId: UUID?,
        expectedLocalRevision: Long? = null,
        tagIds: List<UUID> = emptyList(),
        suppressJournal: Boolean,
        createId: UUID? = null,
    ): UUID =
        transaction {
            valid(remark.length <= 2000 && remark.none { it == '\u0000' }, "备注不能超过两千个字符")
            val allocations = inputAllocations(input)
            valid(allocations.size <= 100, "单笔账单的账户分摊过多")
            val effects = input.calculate()
            val facts = facts()
            val old = id?.let { facts.records[it.toString()] }
            allocations.forEach { (_, allocation) ->
                val account = facts.accounts[allocation.accountId.toString()]
                valid(
                    account != null && account.deletedAt == null && account.type in setOf("ASSET", "LIABILITY") &&
                        account.archivedAt == null && account.selectable,
                    "账户不可用，请选择可用账户",
                )
                valid(account!!.currency == allocation.amount.currency, "账户币种与金额币种不一致")
            }
            val direction =
                when (input) {
                    is RecordInput.Expense -> Direction.EXPENSE
                    is RecordInput.Income -> Direction.INCOME
                    is RecordInput.Transfer -> null
                }
            val category =
                if (direction == null) {
                    valid(categoryId == null, "转账本金无需分类")
                    null
                } else {
                    val selected =
                        categoryId?.let { facts.categories[it.toString()] }
                            ?: if (categoryId == null) facts.categories.values.firstOrNull { it.purpose == direction.name && it.parentId == null && it.deletedAt == null && it.archivedAt == null }
                            else null
                    valid(
                        selected != null && selected.deletedAt == null && selected.ledgerId == facts.ledger.id &&
                            selected.direction == direction.name,
                        "请选择同账本的正确收支分类",
                    )
                    selected
                }
            valid(tagIds.size <= 50 && tagIds.distinct().size == tagIds.size, "标签选择无效")
            val groupsById = tagDao.groups().associateBy { it.id }
            val tagsById = tagDao.tags().associateBy { it.id }
            val selectedTags = tagIds.map { selectedId ->
                val row = tagsById[selectedId.toString()]
                valid(
                    row != null && row.ownerId == owner && row.epochId == epoch && row.deletedAt == null &&
                        (row.archivedAt == null || tagDao.recordTags().any {
                            it.recordId == old?.id && it.tagId == row.id && it.ownerId == owner && it.epochId == epoch
                        }) && groupsById[row.groupId]?.let {
                            it.ownerId == owner && it.epochId == epoch && it.deletedAt == null
                        } == true,
                    "所选标签不可用",
                )
                row!!
            }
            val now = System.currentTimeMillis()
            valid(id == null || createId == null, "新增与编辑账单不能同时指定编号")
            val recordId = id ?: createId ?: UUID.randomUUID()
            val revision: Long
            if (id == null) {
                valid(expectedLocalRevision == null, "新增账单不能指定旧版本")
                revision = 1
                dao.insert(RecordEntity(recordId.toString(), owner, epoch, now, now, revision, null))
            } else {
                valid(old != null && old.deletedAt == null, "账单不存在或已删除")
                valid(expectedLocalRevision != null && old!!.localRevision == expectedLocalRevision, "账单已变更，请刷新后重试")
                valid(!hasActiveSavingLink(old!!.id), "该账单关联有效存钱记录，不能编辑")
                valid(!hasPostedRecovery(facts, old!!.id), "报销或退款入账期间不能编辑账单")
                revision = Math.addExact(old.localRevision, 1)
                reverse(facts.currentEvent(old), revision, now)
                dao.update(old.copy(updatedAt = now, localRevision = revision))
            }
            tagDao.deleteRecordTags(recordId.toString())
            selectedTags.forEach { tag -> tagDao.insert(RecordTagEntity(owner, epoch, recordId.toString(), tag.id, now)) }
            val kind =
                when (input) {
                    is RecordInput.Expense -> "EXPENSE"
                    is RecordInput.Income -> "INCOME"
                    is RecordInput.Transfer -> "TRANSFER"
                }
            val event =
                event(
                    newId(),
                    facts.ledger.id,
                    recordId.toString(),
                    revision,
                    kind,
                    occurredAtMillis,
                    timezone,
                    remark,
                    category?.id,
                    input,
                    now,
                )
            dao.insert(event)
            allocations.forEachIndexed { position, (role, allocation) ->
                dao.insert(
                    AllocationEntity(
                        newId(),
                        event.id,
                        position,
                        role,
                        allocation.accountId.toString(),
                        allocation.amount.unscaled,
                        allocation.amount.scale,
                        allocation.amount.currency,
                    ),
                )
            }
            effects.postings.forEachIndexed { position, value ->
                val accountType = facts.accounts.getValue(value.accountId.toString()).type
                val stored = liabilityAwareStoredAmount(accountType, value.amount)
                dao.insert(posting(event.id, position, value.copy(amount = stored)))
            }
            effects.statistics.forEachIndexed { position, value ->
                val contributionCategory =
                    category ?: facts.categories.values.single {
                        it.purpose == if (value.direction == Direction.EXPENSE) "TRANSFER_FEE" else "TRANSFER_BONUS"
                    }
                dao.insert(
                    StatisticEntity(
                        newId(),
                        event.id,
                        position,
                        contributionCategory.id,
                        value.direction.name,
                        value.amount.unscaled,
                        value.amount.scale,
                        value.amount.currency,
                        null,
                    ),
                )
            }
            effects.budget.forEachIndexed { position, value ->
                val budgetCategory = category ?: facts.categories.values.single { it.purpose == "TRANSFER_FEE" }
                if (budgetCategory.excludeBudget || budgetCategory.parentId?.let(facts.categories::get)?.excludeBudget == true) return@forEachIndexed
                dao.insert(
                    BudgetEntity(newId(), event.id, position, budgetCategory.id, value.unscaled, value.scale, value.currency, null),
                )
            }
            if (!suppressJournal) {
                journal(
                    recordId.toString(),
                    revision,
                    old?.localRevision ?: 0,
                    if (old == null) "RECORD_CREATE" else "RECORD_EDIT",
                    eventIntent(event).put("tagIds", JSONArray().also { array -> selectedTags.forEach { array.put(it.id) } }).put(
                    "allocations",
                    JSONArray().also { array ->
                        allocations.forEach { (role, allocation) ->
                            array.put(
                                JSONObject()
                                    .put(
                                        "role",
                                        role,
                                    ).put("accountId", allocation.accountId.toString())
                                    .put("amount", moneyIntent(allocation.amount)),
                            )
                        }
                    },
                    ),
                    now,
                )
            }
            if (!batchApplying) readSnapshot()
            recordId
        }

    fun deleteRecord(
        id: UUID,
        expectedLocalRevision: Long,
    ) {
        transaction {
            val facts = facts()
            val old = facts.records[id.toString()]
            valid(old != null && old.deletedAt == null, "账单不存在或已删除")
            valid(old!!.localRevision == expectedLocalRevision, "账单已变更，请刷新后重试")
            valid(!hasActiveSavingLink(old.id), "该账单关联有效存钱记录，不能删除")
            valid(!hasPostedRecovery(facts, old.id), "报销或退款入账期间不能删除账单")
            val revision = Math.addExact(old.localRevision, 1)
            val now = System.currentTimeMillis()
            val reversal = reverse(facts.currentEvent(old), revision, now)
            dao.update(old.copy(updatedAt = now, localRevision = revision, deletedAt = now))
            tagDao.deleteRecordTags(old.id)
            journal(old.id, revision, old.localRevision, "RECORD_DELETE", JSONObject().put("reversalEventId", reversal.id), now)
            readSnapshot()
        }
    }

    private fun validateNoLedgerLoanReferences(facts: Facts) {
        val allPlans = loanDao.plans()
        val plans = allPlans.filter { it.ownerId == owner && it.epochId == epoch }
        val planIds = plans.mapTo(mutableSetOf()) { it.id }
        val allTerms = loanDao.terms()
        val terms = allTerms.filter { it.ownerId == owner && it.epochId == epoch }
        valid(terms.all { it.planId in planIds }, "贷款期次引用缺失计划，已拒绝清账")
        valid(allTerms.none { it.planId in planIds && (it.ownerId != owner || it.epochId != epoch) }, "贷款期次所有者或数据集不匹配，已拒绝清账")
        val allEvents = dao.events().filter { it.ownerId == owner && it.epochId == epoch }
        val eventsById = allEvents.associateBy { it.id }
        val referencedIds = buildSet {
            plans.forEach { plan -> plan.disbursementEventId?.let(::add) }
            terms.forEach { term -> term.postedEventId?.let(::add); term.paidEventId?.let(::add) }
        }
        referencedIds.forEach { eventId ->
            val referenced = eventsById[eventId] ?: throw FinanceValidationException("贷款计划引用缺失资金事件，已拒绝清账")
            valid(referenced.ownerId == owner && referenced.epochId == epoch, "贷款事件所有者不匹配，已拒绝清账")
        }
        val currentLedgerEventIds = allEvents.asSequence()
            .filter { it.ledgerId == facts.ledger.id }
            .mapTo(mutableSetOf()) { it.id }
        valid(referencedIds.none { it in currentLedgerEventIds }, "当前账本存在贷款关联资金事件，已拒绝清账")
    }

    private fun validateNoLedgerCreditReferences(facts: Facts) {
        val allPlans = creditDao.plans()
        val plans = allPlans.filter { it.ownerId == owner && it.epochId == epoch }
        val planById = plans.associateBy { it.id }
        val allTerms = creditDao.terms()
        val terms = allTerms.filter { it.ownerId == owner && it.epochId == epoch }
        val termById = terms.associateBy { it.id }
        valid(terms.all { it.planId in planById }, "信用分期期次引用缺失计划，已拒绝清账")
        valid(allTerms.none { it.planId in planById && (it.ownerId != owner || it.epochId != epoch) },
            "信用分期期次所有者或数据集不匹配，已拒绝清账")
        val allEvents = dao.events().filter { it.ownerId == owner && it.epochId == epoch }
        val eventsById = allEvents.associateBy { it.id }
        val recordsById = dao.records().filter { it.ownerId == owner && it.epochId == epoch }.associateBy { it.id }
        val ledgersById = dao.ledgers().filter { it.ownerId == owner && it.epochId == epoch }.associateBy { it.id }
        val accountsById = dao.accounts().filter { it.ownerId == owner && it.epochId == epoch }.associateBy { it.id }
        val allocations = creditDao.paymentAllocations().filter { it.ownerId == owner && it.epochId == epoch }
        valid(creditDao.paymentAllocations().none { it.termId in termById && (it.ownerId != owner || it.epochId != epoch) },
            "信用还款分配所有者或数据集不匹配，已拒绝清账")
        valid(allocations.all { it.termId in termById }, "信用还款分配引用缺失期次，已拒绝清账")
        allocations.forEach { allocation ->
            valid(allocation.currency.matches(Regex("[A-Z]{3}")) && allocation.amountUnscaled > 0,
                "信用还款分配金额无效，已拒绝清账")
            val term = termById[allocation.termId] ?: throw FinanceValidationException("信用还款分配引用缺失期次，已拒绝清账")
            val plan = planById[term.planId] ?: throw FinanceValidationException("信用还款分配引用缺失计划，已拒绝清账")
            valid(allocation.accountId == plan.accountId && allocation.accountId in accountsById,
                "信用还款分配账户与分期计划不匹配，已拒绝清账")
            valid(allocation.currency == term.currency && allocation.currency == plan.currency,
                "信用还款分配币种与期次不匹配，已拒绝清账")
            val event = eventsById[allocation.paymentEventId]
                ?: throw FinanceValidationException("信用还款分配引用缺失资金事件，已拒绝清账")
            valid(event.kind == "CREDIT_PAYMENT" && event.recordId == null,
                "信用还款事件类型或账单关联不匹配，已拒绝清账")
        }
        val allocationEventIds = allocations.mapTo(mutableSetOf()) { it.paymentEventId }
        plans.forEach { plan ->
            valid(plan.ledgerId in ledgersById, "信用分期计划引用缺失或异属账本，已拒绝清账")
            valid(plan.accountId in accountsById, "信用分期计划引用缺失或异属账户，已拒绝清账")
            plan.sourceRecordId?.let { recordId ->
                val record = recordsById[recordId]
                    ?: throw FinanceValidationException("信用分期计划引用缺失或异属账单，已拒绝清账")
                valid(record.ownerId == owner && record.epochId == epoch, "信用分期来源账单所有者或数据集不匹配，已拒绝清账")
                val sourceEvents = allEvents.filter { it.recordId == recordId }
                valid(sourceEvents.isNotEmpty() && sourceEvents.all { it.ledgerId == plan.ledgerId },
                    "信用分期来源账单资金事件缺失或账本不匹配，已拒绝清账")
            }
            plan.creationEventId?.let { eventId ->
                val event = eventsById[eventId] ?: throw FinanceValidationException("信用分期引用缺失资金事件，已拒绝清账")
                valid(event.ledgerId == plan.ledgerId && event.kind == "INSTALLMENT_POSTING" && event.recordId == null,
                    "信用分期创建事件账本或类型不匹配，已拒绝清账")
            }
        }
        terms.forEach { term ->
            val plan = planById[term.planId] ?: throw FinanceValidationException("信用分期期次引用缺失计划，已拒绝清账")
            term.postingEventId?.let { eventId ->
                val event = eventsById[eventId] ?: throw FinanceValidationException("信用分期期次引用缺失资金事件，已拒绝清账")
                valid(event.ledgerId == plan.ledgerId && event.kind == "INSTALLMENT_POSTING" && event.recordId == null,
                    "信用分期期次入账事件账本或类型不匹配，已拒绝清账")
            }
            term.paidEventId?.let { eventId ->
                val event = eventsById[eventId] ?: throw FinanceValidationException("信用分期期次引用缺失资金事件，已拒绝清账")
                valid(event.kind == "CREDIT_PAYMENT" && event.recordId == null,
                    "信用分期期次还款事件类型或账单关联不匹配，已拒绝清账")
            }
        }
        valid(plans.none { it.ledgerId == facts.ledger.id }, "当前账本存在信用分期计划，已拒绝清账")
        val currentLedgerCreditEvents = allEvents.asSequence()
            .filter { it.ledgerId == facts.ledger.id && it.kind in setOf("INSTALLMENT_POSTING", "CREDIT_PAYMENT") }
            .mapTo(mutableSetOf()) { it.id }
        valid(currentLedgerCreditEvents.isEmpty(), "当前账本存在信用分期关联资金事件，已拒绝清账")
        val currentLedgerEventIds = allEvents.asSequence().filter { it.ledgerId == facts.ledger.id }.mapTo(mutableSetOf()) { it.id }
        val referencedEventIds = buildSet {
            plans.forEach { plan ->
                plan.creationEventId?.let(::add)
                plan.sourceRecordId?.let { recordId -> allEvents.filter { it.recordId == recordId }.forEach { add(it.id) } }
            }
            terms.forEach { term -> term.postingEventId?.let(::add); term.paidEventId?.let(::add) }
            addAll(allocationEventIds)
        }
        valid(referencedEventIds.none { it in currentLedgerEventIds }, "当前账本存在信用分期关联资金事件，已拒绝清账")
    }

    private fun validateNoLedgerBusinessReferences(facts: Facts) {
        validateNoLedgerLoanReferences(facts)
        validateNoLedgerCreditReferences(facts)
    }

    internal fun clearRecordsPreview(): ClearRecordsPolicy.Preview = transaction {
        valid(!accountDataset, "访客清账仅适用于本地账本")
        val facts = facts()
        valid(database.fileImports().committedCount(owner, epoch) == 0, "存在已提交的文件导入，暂不支持清账")
        valid(dao.activeRecordWithoutEventCount(owner, epoch) == 0, "存在缺少资金事件的活动账单，已拒绝清账")
        valid(dao.orphanEventCount(facts.ledger.id) == 0, "存在未关联的普通资金事件，已拒绝清账")
        validateNoLedgerBusinessReferences(facts)
        val records = facts.records.values.filter { it.deletedAt == null }
        val importedIds = database.fileImports().batches(owner, epoch).filter { it.state == "COMMITTED" }
            .flatMap { database.fileImports().rows(it.id) }.mapNotNull { it.recordId }.toSet()
        val decisions = records.map { record ->
            val event = facts.currentEvent(record)
            ClearRecordsPolicy.evaluate(ClearRecordsPolicy.Candidate(
                kind = event.kind,
                hasChildEvents = dao.unsupportedLinkedEventCount(record.id) != 0 || dao.transferEventCount(record.id) != 0,
                hasReversal = dao.reversalEventCount(record.id) != 0,
                hasImport = record.id in importedIds,
                hasBusinessDependency = record.claimState != "NONE" || hasActiveSavingLink(record.id) || hasPostedRecovery(facts, record.id),
            ))
        }
        ClearRecordsPolicy.Preview(facts.ledger.name, records.size, decisions.count { it.supported },
            decisions.mapNotNull { it.reason }.distinct(), records.associate { UUID.fromString(it.id) to it.localRevision })
    }

    fun clearRecords(expectedLocalRevisions: Map<UUID, Long>): FinanceSnapshot = transaction {
        valid(!accountDataset, "访客清账仅适用于本地账本")
        val facts = facts()
        valid(database.fileImports().committedCount(owner, epoch) == 0, "存在已提交的文件导入，暂不支持清账")
        valid(dao.activeRecordWithoutEventCount(owner, epoch) == 0, "存在缺少资金事件的活动账单，已拒绝清账")
        valid(dao.orphanEventCount(facts.ledger.id) == 0, "存在未关联的普通资金事件，已拒绝清账")
        validateNoLedgerBusinessReferences(facts)
        val records = facts.records.values.filter { it.deletedAt == null }
        valid(records.isNotEmpty(), "当前账本没有可清账记录")
        valid(expectedLocalRevisions.size == records.size && records.all { expectedLocalRevisions[UUID.fromString(it.id)] == it.localRevision }, "账单已变更，请重新预览")
        val importedIds = database.fileImports().batches(owner, epoch).filter { it.state == "COMMITTED" }
            .flatMap { database.fileImports().rows(it.id) }.mapNotNull { it.recordId }.toSet()
        val eligible = records.map { record ->
            val current = facts.currentEvent(record)
            val decision = ClearRecordsPolicy.evaluate(ClearRecordsPolicy.Candidate(
                kind = current.kind,
                hasChildEvents = dao.unsupportedLinkedEventCount(record.id) != 0 || dao.transferEventCount(record.id) != 0,
                hasReversal = dao.reversalEventCount(record.id) != 0,
                hasImport = record.id in importedIds,
                hasBusinessDependency = record.claimState != "NONE" || hasActiveSavingLink(record.id) || hasPostedRecovery(facts, record.id),
            ))
            valid(decision.supported, decision.reason ?: "账单依赖暂不支持清账")
            record to current
        }
        val now = System.currentTimeMillis()
        eligible.forEach { (record, current) ->
            val revision = Math.addExact(record.localRevision, 1)
            val reversal = reverse(current, revision, now)
            dao.update(record.copy(updatedAt = now, localRevision = revision, deletedAt = now))
            tagDao.deleteRecordTags(record.id)
            journal(record.id, revision, record.localRevision, "RECORD_DELETE",
                JSONObject().put("reversalEventId", reversal.id).put("clearRecords", true).put("dependencyStrategy", "REJECT"), now)
        }
        readSnapshot()
    }

    fun deleteRecords(expectedLocalRevisions: Map<UUID, Long>) {
        transaction {
            valid(expectedLocalRevisions.size in 1..500, "请选择1至500笔账单")
            val facts = facts()
            val selected =
                expectedLocalRevisions.map { (id, expected) ->
                    val record = facts.records[id.toString()]
                    valid(record != null && record.deletedAt == null, "选中的账单不存在或已删除")
                    valid(record!!.localRevision == expected, "选中的账单已变更，请刷新后重试")
                    valid(!hasActiveSavingLink(record.id), "选中的账单关联有效存钱记录，不能删除")
                    valid(!hasPostedRecovery(facts, record.id), "报销或退款入账期间不能删除账单")
                    record to Math.addExact(record.localRevision, 1)
                }
            val now = System.currentTimeMillis()
            val ids = JSONArray()
            val bases = JSONObject()
            val reversals = JSONObject()
            selected.forEach { (record, revision) ->
                val reversal = reverse(facts.currentEvent(record), revision, now)
                dao.update(record.copy(updatedAt = now, localRevision = revision, deletedAt = now))
                tagDao.deleteRecordTags(record.id)
                ids.put(record.id)
                bases.put(record.id, record.localRevision)
                reversals.put(record.id, reversal.id)
            }
            journal(
                newId(),
                1,
                0,
                "RECORD_BULK_DELETE",
                JSONObject()
                    .put("recordIds", ids)
                    .put("baseLocalRevisions", bases)
                    .put("reversalEventIds", reversals)
                    .put("dependencyStrategy", "REJECT"),
                now,
            )
            readSnapshot()
        }
    }

    override fun close() {
        offMain()
        if (!closed) {
            closed = true
            database.close()
        }
    }

    private fun <T> transaction(block: () -> T): T {
        offMain()
        valid(!closed, "本地账本已关闭")
        if (batchApplying) return block()
        try {
            return database.runInTransaction(
                java.util.concurrent.Callable {
                    checkIdentity()
                    block()
                },
            )
        } catch (failure: FinanceValidationException) {
            throw failure
        } catch (_: MoneyException) {
            throw FinanceValidationException("金额无效或超出可保存范围")
        } catch (_: RecordException) {
            throw FinanceValidationException("请检查金额、优惠与账户分摊")
        } catch (_: ArithmeticException) {
            throw FinanceValidationException("金额或版本超出可保存范围")
        } catch (failure: Exception) {
            throw FinanceValidationException("本地账本操作失败，数据未更改", failure)
        }
    }

    private fun checkIdentity() {
        val identity = database.identity().read()
        check(database.identity().count() == 1 && identity != null && identity.ownerId == owner && identity.epochId == epoch)
        check(identity.schemaVersion == 1 && identity.lastAppliedSeq >= 0)
    }

    private fun initializeDefaults() {
        if (dao.ledgers().isNotEmpty()) return
        // A missing ledger in a populated schema is corruption, never permission to replace metadata.
        check(
            dao.groups().isEmpty() && dao.categories().isEmpty() && dao.accounts().isEmpty() && dao.records().isEmpty() &&
                dao.events().isEmpty() &&
                dao.mutations().isEmpty(),
        )
        val now = System.currentTimeMillis()
        val ledger = LedgerEntity(newId(), owner, epoch, "日常", "CNY", now, now, 1, null, 0, true)
        dao.insert(ledger)
        val groups =
            listOf(Triple("ACCOUNT", "默认账户组", true), Triple("TAG", "默认标签组", false)).map { (kind, name, isDefault) ->
                FinanceGroupEntity(newId(), owner, epoch, kind, name, now, now, 1, null, 0, isDefault).also { dao.insert(it) }
            }
        val categories =
            listOf(
                Triple("EXPENSE", "其他支出", Direction.EXPENSE),
                Triple("INCOME", "其他收入", Direction.INCOME),
                Triple("TRANSFER_FEE", "转账手续费", Direction.EXPENSE),
                Triple("TRANSFER_BONUS", "转账优惠", Direction.INCOME),
            ).mapIndexed { index, (purpose, name, direction) ->
                CategoryEntity(newId(), owner, epoch, ledger.id, name, direction.name, purpose, now, now, 1, null, index.toLong()).also { dao.insert(it) }
            }
        journal(
            ledger.id,
            1,
            0,
            "DATASET_INITIALIZE",
            JSONObject()
                .put("ledger", JSONObject().put("id", ledger.id).put("name", ledger.name).put("baseCurrency", ledger.baseCurrency))
                .put(
                    "groups",
                    JSONArray().also { array ->
                        groups.forEach { array.put(JSONObject().put("id", it.id).put("kind", it.kind).put("name", it.name)) }
                    },
                ).put(
                    "categories",
                    JSONArray().also { array ->
                        categories.forEach {
                            array.put(
                                JSONObject()
                                    .put("id", it.id)
                                    .put("name", it.name)
                                    .put("direction", it.direction)
                                    .put("purpose", it.purpose),
                            )
                        }
                    },
                ),
            now,
        )
    }

    private fun initializeTagDefaults() {
        val groups = tagDao.groups()
        if (groups.isNotEmpty()) {
            check(groups.count { it.isDefault && it.deletedAt == null } == 1)
            groups.forEach { check(it.ownerId == owner && it.epochId == epoch) }
            return
        }
        check(tagDao.tags().isEmpty() && tagDao.recordTags().isEmpty())
        val now = System.currentTimeMillis()
        val row = TagGroupEntity(newId(), owner, epoch, "默认标签组", 0, true, now, now, 1, null)
        tagDao.insert(row)
        journal(row.id, 1, 0, "TAG_GROUP_CREATE_DEFAULT", JSONObject().put("name", row.name).put("isDefault", true), now)
    }

    private data class Facts(
        val ledger: LedgerEntity,
        val groups: List<FinanceGroupEntity>,
        val categories: Map<String, CategoryEntity>,
        val accounts: Map<String, AccountEntity>,
        val records: Map<String, RecordEntity>,
        val events: Map<String, EventEntity>,
        val allocations: List<AllocationEntity>,
        val postings: List<PostingEntity>,
        val statistics: List<StatisticEntity>,
        val budgets: List<BudgetEntity>,
        val budgetPreference: BudgetPreferenceEntity?,
        val budgetTemplates: List<BudgetTemplateEntity>,
        val budgetOverrides: List<BudgetOverrideEntity>,
        val mutations: List<MutationEntity>,
    ) {
        fun currentEvent(record: RecordEntity): EventEntity {
            val candidates =
                events.values.filter { it.recordId == record.id && it.localRevision <= record.localRevision && it.kind != "REVERSAL" }
            val revision = candidates.maxOfOrNull { it.localRevision } ?: error("Record has no current finance event")
            val current = candidates.single { it.localRevision == revision }
            val claimChanges =
                mutations.filter {
                    it.aggregateId == record.id && it.localRevision > current.localRevision && it.localRevision <= record.localRevision
                }
            check(claimChanges.size.toLong() == record.localRevision - current.localRevision)
            check(
                claimChanges.all {
                    it.kind in setOf("CLAIM_MARK", "CLAIM_SETTLEMENT_STATE", "REFUND_STATE", "RECORD_TAG_DETACH", "CATEGORY_MIGRATION_REVERSE", "RECORD_DELETE") &&
                        it.baseLocalRevision == it.localRevision - 1
                },
            )
            val deletions = claimChanges.filter { it.kind == "RECORD_DELETE" }
            check(deletions.size <= 1 && (deletions.isEmpty() || record.deletedAt != null))
            deletions.forEach { deletion ->
                check(deletion.localRevision == record.localRevision)
                val reversalId = JSONObject(deletion.payload).optString("reversalEventId")
                val reversal = events[reversalId]
                check(reversal != null && reversal.recordId == record.id && reversal.kind == "REVERSAL" &&
                    reversal.reversalOf == current.id && reversal.localRevision == deletion.localRevision)
            }
            return current
        }
    }

    private fun selectedLedger(allLedgers: List<LedgerEntity>): LedgerEntity {
        check(allLedgers.isNotEmpty())
        allLedgers.forEach {
            check(it.ownerId == owner && it.epochId == epoch && it.localRevision > 0 && it.sortOrder >= 0)
            Money(0, 0, it.baseCurrency)
        }
        val ledger = allLedgers.singleOrNull { it.id == selectedLedgerId && it.deletedAt == null && it.archivedAt == null }
            ?: allLedgers.singleOrNull { it.isDefault && it.deletedAt == null && it.archivedAt == null }
            ?: allLedgers.filter { it.deletedAt == null && it.archivedAt == null }.minWithOrNull(compareBy<LedgerEntity> { it.sortOrder }.thenBy { it.id })
            ?: throw FinanceValidationException("没有可用账本")
        selectedLedgerId = ledger.id
        return ledger
    }

    private fun facts(): Facts {
        val allLedgers = dao.ledgers().filter { it.ownerId == owner && it.epochId == epoch }
        val ledger = selectedLedger(allLedgers)
        val groups = dao.groups()
        val allEvents = dao.events()
        val ledgerEvents = allEvents.filter { it.ledgerId == ledger.id }
        val ledgerEventIds = ledgerEvents.mapTo(mutableSetOf()) { it.id }
        val categories = dao.categories().filter { it.ledgerId == ledger.id }.associateBy { it.id }
        val accounts = dao.accounts().filter { account ->
            allEvents.any { it.id == account.openingEventId && it.ledgerId == ledger.id } ||
                dao.postings().any { posting -> posting.accountId == account.id && posting.eventId in ledgerEventIds }
        }.associateBy { it.id }
        val events = (ledgerEvents + allEvents.filter { event -> accounts.values.any { it.openingEventId == event.id } }).distinctBy { it.id }.associateBy { it.id }
        val allRecords = dao.records()
        val records = allRecords.filter { record -> ledgerEvents.any { it.recordId == record.id } }.associateBy { it.id }
        val mutations = dao.mutations().sortedBy { it.sequence }
        val ledgerPreference = budgetDao.preferences().singleOrNull { it.ledgerId == ledger.id }
        val facts =
            Facts(
                ledger,
                groups,
                categories,
                accounts,
                records,
                events,
                dao.allocations().filter { it.eventId in ledgerEventIds },
                dao.postings().filter { it.eventId in ledgerEventIds },
                dao.statistics().filter { it.eventId in ledgerEventIds },
                dao.budgets().filter { it.eventId in ledgerEventIds },
                ledgerPreference,
                budgetDao.templates().filter { it.ledgerId == ledger.id },
                budgetDao.overrides().filter { it.ledgerId == ledger.id },
                mutations,
            )
        // Plans and progress-only entries belong to the dataset, including transfers from other ledgers.
        val savingPlans = savingDao.plans().filter { it.ownerId == owner && it.epochId == epoch }
        val savingEntries = savingDao.entries().filter { it.ownerId == owner && it.epochId == epoch }
        val savingRecordsById = allRecords.associateBy { it.id }
        val savingEventsById = allEvents.associateBy { it.id }
        savingPlans.forEach { plan ->
            check(plan.ownerId == owner && plan.epochId == epoch && plan.localRevision > 0)
            check(plan.kind in setOf("FLEXIBLE", "FIXED", "WEEK_52", "DAY_365"))
            check(plan.state in setOf("ACTIVE", "ARCHIVED", "DELETED"))
            check(Money(plan.targetUnscaled, plan.targetScale, plan.currency).unscaled > 0)
            check(plan.initialSavedMoney().unscaled >= 0)
            check((plan.firstAmountUnscaled == null) == (plan.firstAmountScale == null))
            check((plan.incrementUnscaled == null) == (plan.incrementScale == null))
        }
        val savingPlanById = savingPlans.associateBy { it.id }
        savingEntries.forEach { entry ->
            val plan = savingPlanById[entry.planId]
            check(entry.ownerId == owner && entry.epochId == epoch && plan != null)
            check(plan!!.ownerId == owner && plan.epochId == epoch && entry.localRevision > 0)
            Money(entry.amountUnscaled, entry.amountScale, entry.currency).requirePositive()
            check(entry.currency == plan.currency)
            check((entry.linkedRecordId == null) == (entry.linkedTransferEventId == null))
            entry.linkedRecordId?.let { recordId ->
                val record = savingRecordsById[recordId] ?: error("Saving entry references a missing record")
                val eventId = entry.linkedTransferEventId!!
                check(record.ownerId == owner && record.epochId == epoch)
                check(savingEventsById[eventId]?.let { it.ownerId == owner && it.epochId == epoch && it.recordId == recordId && it.kind == "TRANSFER" } == true)
            }
        }
        savingEntries.filter { it.cancelledAt == null && it.cardIndex != null &&
            savingPlanById[it.planId]?.deletedAt == null }.groupBy { it.planId to it.cardIndex }
            .values.forEach { check(it.size == 1) }

        val allLoanPlans = loanDao.plans().filter { it.ownerId == owner && it.epochId == epoch }
        val allLoanById = allLoanPlans.associateBy { it.id }
        val loanPlans = allLoanPlans.filter { plan ->
            (plan.disbursementEventId == null && plan.accountId in accounts) || plan.disbursementEventId in events.keys ||
                loanDao.terms().any { it.planId == plan.id && (it.paidEventId in events.keys || it.postedEventId in events.keys) }
        }
        loanPlans.forEach { plan ->
            check(plan.ownerId == owner && plan.epochId == epoch && plan.localRevision > 0)
            check(plan.state in setOf("ACTIVE", "DELETED") && plan.termCount in 1..1200 && plan.paidTermCount in 0..plan.termCount)
            check((plan.deletedAt == null) == (plan.activeAccountKey != null))
            check(plan.activeAccountKey == null || plan.activeAccountKey == "$owner:$epoch:${plan.accountId}")
            val account = accounts[plan.accountId]
            check(account?.let { it.ownerId == owner && it.epochId == epoch && it.type == "LIABILITY" && it.currency == plan.currency } == true)
            check(Money(plan.principalUnscaled, plan.principalScale, plan.currency).unscaled > 0)
            check(plan.annualRateNumerator >= 0 && plan.annualRateDenominator > 0)
            plan.disbursementEventId?.let { eventId ->
                val event = events[eventId] ?: error("Loan references a missing disbursement event")
                check(event.ownerId == owner && event.epochId == epoch && event.kind == "INCOME" && event.recordId?.let { it in records } == true)
            }
        }
        val loanTerms = loanDao.terms()
        loanTerms.forEach { term ->
            val plan = allLoanById[term.planId]
            check(term.ownerId == owner && term.epochId == epoch && term.localRevision > 0 && term.planRevision > 0)
            check(plan?.let { it.ownerId == owner && it.epochId == epoch } == true)
            check(term.termIndex in 1..plan!!.termCount && term.dueState in setOf("DUE", "NO_DUE", "PAID"))
            val opening = Money(term.openingPrincipalUnscaled, term.openingPrincipalScale, plan.currency)
            val principal = Money(term.principalUnscaled, term.principalScale, plan.currency)
            val interest = Money(term.interestUnscaled, term.interestScale, plan.currency)
            val closing = Money(term.closingPrincipalUnscaled, term.closingPrincipalScale, plan.currency)
            check(opening.unscaled >= 0 && principal.unscaled >= 0 && interest.unscaled >= 0)
            check(opening.checkedSubtract(principal).sameValue(closing))
            check(term.activeTermKey == null || term.activeTermKey == "${plan.id}:${term.termIndex}")
            check((term.paidPrincipalUnscaled == null) == (term.paidPrincipalScale == null))
            check((term.paidInterestUnscaled == null) == (term.paidInterestScale == null))
            if (term.paidEventId != null || term.paidAtMillis != null) {
                check(term.paidPrincipalUnscaled != null && term.paidInterestUnscaled != null && term.dueState == "PAID")
            }
            listOfNotNull(term.postedEventId, term.paidEventId).forEach { eventId ->
                val event = events[eventId] ?: error("Loan term references a missing event")
                check(event.ownerId == owner && event.epochId == epoch && event.recordId?.let { it in records } == true)
            }
        }
        loanTerms.filter { it.activeTermKey != null }.groupBy { it.activeTermKey }.values.forEach { check(it.size == 1) }
        loanDao.rateChanges().forEach { change ->
            check(change.ownerId == owner && change.epochId == epoch && allLoanById[change.planId] != null)
            check(change.effectiveTerm in 1..allLoanById.getValue(change.planId).termCount)
            check(change.newRateNumerator >= 0 && change.newRateDenominator > 0 && change.toRevision == change.fromRevision + 1)
        }

        fun scoped(
            rowOwner: String,
            rowEpoch: String,
        ) = check(rowOwner == owner && rowEpoch == epoch)
        val tagGroups = tagDao.groups()
        val tagRows = tagDao.tags()
        val tagLinks = tagDao.recordTags()
        check(tagGroups.count { it.isDefault && it.deletedAt == null } == 1)
        tagGroups.forEach { scoped(it.ownerId, it.epochId) }
        tagRows.forEach { row ->
            scoped(row.ownerId, row.epochId)
            if (row.deletedAt == null) {
                check(tagGroups.any { it.id == row.groupId && it.deletedAt == null })
            }
        }
        tagLinks.forEach { link ->
            scoped(link.ownerId, link.epochId)
            if (link.recordId in records) {
                check(tagRows.any { it.id == link.tagId && it.deletedAt == null })
            }
        }
        check(allLedgers.count { it.isDefault && it.deletedAt == null && it.archivedAt == null } == 1)
        check(ledger.deletedAt == null && ledger.localRevision > 0)
        Money(0, 0, ledger.baseCurrency)
        check(groups.isNotEmpty() && groups.all { it.kind in setOf("ACCOUNT", "TAG") })
        groups.forEach {
            scoped(it.ownerId, it.epochId)
            if (it.kind == "TAG") check(it.deletedAt == null && !it.isDefault)
        }
        val accountGroups = groups.filter { it.kind == "ACCOUNT" }
        check(accountGroups.isNotEmpty() && accountGroups.count { it.isDefault && it.deletedAt == null } == 1)
        accountGroups.forEach { group ->
            check(group.localRevision > 0 && group.sortOrder >= 0)
            if (group.isDefault) check(group.deletedAt == null)
        }
        categories.values.forEach {
            scoped(it.ownerId, it.epochId)
            check(allLedgers.any { ledgerRow -> ledgerRow.id == it.ledgerId })
            check(it.direction in setOf("EXPENSE", "INCOME"))
            it.parentId?.let { parentId ->
                val parent = categories[parentId] ?: error("Category parent is missing")
                check(parent.ledgerId == it.ledgerId && parent.direction == it.direction && parent.parentId == null)
            }
            check(it.sortOrder >= 0)
        }
        accounts.values.forEach {
            scoped(it.ownerId, it.epochId)
            val accountGroup = groups.single { group -> group.id == it.groupId }
            check(accountGroup.kind == "ACCOUNT" && it.type in setOf("ASSET", "LIABILITY") && it.sortOrder >= 0)
            if (it.deletedAt == null) check(accountGroup.deletedAt == null)
            val opening = events.getValue(it.openingEventId)
            check(opening.kind == "OPENING" && opening.recordId == null && opening.amountCurrency == it.currency)
            Money(0, 0, it.currency)
        }
        records.values.forEach {
            scoped(it.ownerId, it.epochId)
            check(it.localRevision > 0)
        }
        events.values.forEach {
            scoped(it.ownerId, it.epochId)
            check(allLedgers.any { ledgerRow -> ledgerRow.id == it.ledgerId } && it.origin == "LOCAL" && it.localRevision > 0)
            check(localDate(it.occurredAtMillis, it.timezone) == it.localDate)
            it.recordId?.let { id -> check(records.containsKey(id)) }
            it.categoryId?.let { id -> check(categories.getValue(id).ledgerId == it.ledgerId) }
            it.reversalOf?.let { id ->
                check(events.getValue(id).kind != "REVERSAL" && events.getValue(id).recordId == it.recordId && it.kind == "REVERSAL")
            }
        }
        facts.allocations.forEach {
            check(events.containsKey(it.eventId) && accounts.getValue(it.accountId).currency == it.amountCurrency)
            Money(it.amountUnscaled, it.amountScale, it.amountCurrency)
        }
        facts.postings.forEach {
            check(events.containsKey(it.eventId) && accounts.getValue(it.accountId).currency == it.amountCurrency)
            Money(it.amountUnscaled, it.amountScale, it.amountCurrency)
            it.reversalOf?.let { id ->
                check(
                    facts.postings.any { original ->
                        original.id == id &&
                            original.eventId == events.getValue(it.eventId).reversalOf
                    },
                )
            }
        }
        facts.statistics.forEach {
            check(events.containsKey(it.eventId) && categories.getValue(it.categoryId).direction == it.direction)
            Money(it.amountUnscaled, it.amountScale, it.amountCurrency)
        }
        facts.budgets.forEach {
            check(events.containsKey(it.eventId) && categories.getValue(it.categoryId).direction == "EXPENSE")
            Money(it.amountUnscaled, it.amountScale, it.amountCurrency)
        }
        facts.budgetPreference?.let {
            scoped(it.ownerId, it.epochId)
            check(it.ledgerId == ledger.id && it.localRevision > 0)
        }

        fun budgetLimit(
            period: String,
            date: String,
            amount: Money,
            revision: Long,
        ) {
            check(period in ReportPeriod.entries.map { it.name } && revision > 0)
            check(amount.currency == ledger.baseCurrency && amount.unscaled >= 0)
            check(date.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")))
            val format =
                java.text.SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply {
                    calendar =
                        java.util.GregorianCalendar(TimeZone.getTimeZone("UTC"), Locale.ROOT).apply {
                            gregorianChange =
                                java.util.Date(Long.MIN_VALUE)
                        }
                    isLenient = false
                }
            check(format.format(format.parse(date)!!) == date)
        }
        facts.budgetTemplates.forEach {
            scoped(it.ownerId, it.epochId)
            check(it.ledgerId == ledger.id)
            budgetLimit(it.periodType, it.effectiveFrom, Money(it.amountUnscaled, it.amountScale, it.amountCurrency), it.localRevision)
        }
        facts.budgetOverrides.forEach {
            scoped(it.ownerId, it.epochId)
            check(it.ledgerId == ledger.id)
            budgetLimit(it.periodType, it.periodStart, Money(it.amountUnscaled, it.amountScale, it.amountCurrency), it.localRevision)
        }
        facts.mutations.forEach {
            scoped(it.ownerId, it.epochId)
            check(it.intentVersion == 1 && it.state == "LOCAL_PENDING")
        }
        return facts
    }

    private fun readBudgetOverview(
        period: ReportPeriod,
        atMillis: Long,
        zone: String,
        data: Facts = facts(),
    ): FinanceBudgetOverview {
        val range = PeriodResolver.range(period, atMillis, zone)
        val start = localDate(range.startInclusiveMillis, zone)
        val end = localDate(range.endExclusiveMillis, zone)
        val template =
            data.budgetTemplates
                .filter {
                    it.periodType == period.name && it.effectiveFrom <= start
                }.maxByOrNull { it.effectiveFrom }
        val override = data.budgetOverrides.singleOrNull { it.periodType == period.name && it.periodStart == start }
        val events =
            data.events.values
                .filter { it.occurredAtMillis in range }
                .map { it.id }
                .toHashSet()
        val contributions = data.budgets.filter { it.eventId in events }
        val currency = data.ledger.baseCurrency
        val used =
            total(
                currency,
                contributions.filter { it.amountCurrency == currency }.map { Money(it.amountUnscaled, it.amountScale, it.amountCurrency) },
            )
        return FinanceBudgetOverview(
            period,
            start,
            end,
            currency,
            data.budgetPreference?.enabled ?: false,
            data.budgetPreference?.id?.let(UUID::fromString),
            data.budgetPreference?.localRevision ?: 0,
            template?.id?.let(UUID::fromString),
            template?.localRevision ?: 0,
            template?.let { Money(it.amountUnscaled, it.amountScale, it.amountCurrency) },
            override?.id?.let(UUID::fromString),
            override?.localRevision ?: 0,
            override?.takeIf { it.deletedAt == null }?.let { Money(it.amountUnscaled, it.amountScale, it.amountCurrency) },
            used,
            PeriodResolver.remainingDays(range, atMillis, zone),
            contributions
                .map {
                    it.amountCurrency
                }.filter { it != currency }
                .distinct()
                .sorted(),
        )
    }

    private fun refunds(data: Facts): List<FinanceRefundReceipt> {
        val cancellations = data.mutations.filter { it.kind == "REFUND_CANCEL" }.associateBy { it.aggregateId }
        val receipts = data.mutations.filter { it.kind == "REFUND_CREATE" }.map { mutation ->
            val value = JSONObject(mutation.payload)
            val amount = claimMoney(value.getJSONObject("amount"))
            val cancelled = cancellations[mutation.aggregateId]
            FinanceRefundReceipt(
                UUID.fromString(mutation.aggregateId), UUID.fromString(value.getString("originRecordId")), amount,
                UUID.fromString(value.getString("accountId")), value.getLong("refundAtMillis"), value.getString("timezone"),
                if (cancelled == null) "POSTED" else "CANCELLED", cancelled?.localRevision ?: mutation.localRevision,
                mutation.sequence, amount.copy(unscaled = 0), amount.copy(unscaled = 0),
            )
        }.sortedBy { it.createdSequence }
        val effects = receipts.filter { it.status == "POSTED" }.groupBy { it.originRecordId }.values.flatMap { active ->
            val record = data.records.getValue(active.first().originRecordId.toString())
            val original = data.currentEvent(record)
            val expense = restoreInput(original, data.allocations.filter { it.eventId == original.id }) as? RecordInput.Expense
                ?: throw FinanceValidationException("退款原账单不是支出")
            val principal = expense.discount?.let(expense.gross::checkedSubtract) ?: expense.gross
            calculateRefundRecovery(principal, active.map { RefundReceipt(it.id, it.amount, false) }).effects
        }.associateBy { it.refundId }
        return receipts.map { receipt ->
            effects[receipt.id]?.let { receipt.copy(expenseReduction = it.expenseReduction, excessAmount = it.excessAmount) } ?: receipt
        }
    }

    private fun hasPostedRefund(data: Facts, recordId: String): Boolean =
        refunds(data).any { it.originRecordId.toString() == recordId && it.status == "POSTED" }

    private fun hasPostedRecovery(data: Facts, recordId: String): Boolean =
        hasPostedClaim(data, recordId) || hasPostedRefund(data, recordId)

    private fun rebuildRefundRecord(record: RecordEntity, before: List<FinanceRefundReceipt>, refundId: String, now: Long) {
        val data = facts()
        val original = data.currentEvent(record)
        val previous = before.associateBy { it.id }
        val active = refunds(data).filter { it.originRecordId.toString() == record.id && it.status == "POSTED" }
        for (receipt in active) {
            val prior = previous[receipt.id]?.expenseReduction ?: receipt.amount.copy(unscaled = 0)
            val adjustment = prior.checkedSubtract(receipt.expenseReduction)
            if (adjustment.unscaled == 0L) continue
            val eventId = receipt.id.toString()
            if (original.countInStatistics) {
                val category = original.categoryId ?: throw FinanceValidationException("退款原账单缺少支出分类")
                val position = data.statistics.filter { it.eventId == eventId }.maxOfOrNull { it.position + 1 } ?: 0
                dao.insert(StatisticEntity(newId(), eventId, position, category, "EXPENSE", adjustment.unscaled, adjustment.scale, adjustment.currency, null))
            }
            if (data.budgets.any { it.eventId == original.id }) {
                val category = original.categoryId ?: throw FinanceValidationException("退款原账单缺少支出分类")
                val position = data.budgets.filter { it.eventId == eventId }.maxOfOrNull { it.position + 1 } ?: 0
                dao.insert(BudgetEntity(newId(), eventId, position, category, adjustment.unscaled, adjustment.scale, adjustment.currency, null))
            }
        }
        val revision = Math.addExact(record.localRevision, 1)
        dao.update(record.copy(updatedAt = now, localRevision = revision))
        journal(record.id, revision, record.localRevision, "REFUND_STATE", JSONObject().put("refundId", refundId), now)
    }

    private fun claimSettlements(facts: Facts): List<FinanceClaimSettlement> {
        val cancelled = facts.mutations.filter { it.kind == "CLAIM_CANCEL" }.associateBy { it.aggregateId }
        return facts.mutations
            .filter { it.kind == "CLAIM_SETTLE" }
            .map { mutation ->
                val value = JSONObject(mutation.payload)
                val allocations = value.getJSONArray("allocations")
                val settlement = cancelled[mutation.aggregateId]
                FinanceClaimSettlement(
                    UUID.fromString(mutation.aggregateId),
                    claimMoney(value.getJSONObject("actualAmount")),
                    if (value.isNull("accountId")) null else UUID.fromString(value.getString("accountId")),
                    if (value.isNull("incomeCategoryId")) null else UUID.fromString(value.getString("incomeCategoryId")),
                    RemainderAction.valueOf(value.getString("remainderAction")),
                    value.getLong("settledAtMillis"),
                    value.getString("timezone"),
                    (0 until allocations.length()).map { index ->
                        val allocation = allocations.getJSONObject(index)
                        FinanceClaimAllocation(
                            UUID.fromString(allocation.getString("originRecordId")),
                            claimMoney(allocation.getJSONObject("actualAmount")),
                            allocation.getLong("expectedLocalRevision"),
                        )
                    },
                    if (settlement == null) "POSTED" else "CANCELLED",
                    settlement?.localRevision ?: mutation.localRevision,
                    mutation.sequence,
                )
            }.sortedBy { it.createdSequence }
    }

    private fun hasPostedClaim(
        facts: Facts,
        recordId: String,
    ): Boolean =
        claimSettlements(facts).any { settlement ->
            settlement.status == "POSTED" && settlement.allocations.any { it.originRecordId.toString() == recordId }
        }

    private fun claimEffects(mutation: MutationEntity?): Map<UUID, ClaimReceiptEffect> {
        if (mutation == null) return emptyMap()
        val values = JSONObject(mutation.payload).getJSONArray("effects")
        return (0 until values.length()).associate { index ->
            val value = values.getJSONObject(index)
            val id = UUID.fromString(value.getString("settlementId"))
            id to
                ClaimReceiptEffect(
                    id,
                    claimMoney(value.getJSONObject("processedPrincipal")),
                    claimMoney(value.getJSONObject("expenseReduction")),
                    claimMoney(value.getJSONObject("excessIncome")),
                    claimMoney(value.getJSONObject("closedUnreimbursed")),
                )
        }
    }

    private fun rebuildClaimRecords(
        changedRecords: List<RecordEntity>,
        now: Long,
    ) {
        val facts = facts()
        val settlements = claimSettlements(facts)
        val nextStatisticPosition = mutableMapOf<String, Int>()
        val nextBudgetPosition = mutableMapOf<String, Int>()
        for (changed in changedRecords.distinctBy { it.id }) {
            val record = facts.records.getValue(changed.id)
            val originalEvent = facts.currentEvent(record)
            val expense =
                restoreInput(originalEvent, facts.allocations.filter { it.eventId == originalEvent.id }) as? RecordInput.Expense
                    ?: throw FinanceValidationException("仅支出账单可报销")
            val original = expense.discount?.let(expense.gross::checkedSubtract) ?: expense.gross
            val receipts =
                settlements.mapNotNull { settlement ->
                    val allocation =
                        settlement.allocations.singleOrNull { it.originRecordId.toString() == record.id } ?: return@mapNotNull null
                    ClaimReceipt(settlement.id, allocation.actualAmount, settlement.remainderAction, settlement.status == "CANCELLED")
                }
            val previous =
                facts.mutations.lastOrNull { it.aggregateId == record.id && it.kind == "CLAIM_SETTLEMENT_STATE" }
            val oldEffects = claimEffects(previous)
            val recovery = calculateClaimRecovery(original, receipts)
            val newEffects = recovery.effects.associateBy { it.receiptId }
            val settlementsById = settlements.associateBy { it.id }
            val zero = Money(0, 0, original.currency)
            val oldReduction = total(original.currency, oldEffects.values.map { it.expenseReduction })
            val newReduction = total(original.currency, newEffects.values.map { it.expenseReduction })
            val oldIncome = total(original.currency, oldEffects.values.map { it.excessIncome })
            val newIncome = total(original.currency, newEffects.values.map { it.excessIncome })
            for ((receiptId, effect) in newEffects) {
                val settlement = settlementsById.getValue(receiptId)
                val previousEffect = oldEffects[receiptId]
                val priorReduction = previousEffect?.expenseReduction ?: zero
                val priorIncome = previousEffect?.excessIncome ?: zero
                val eventId = receiptId.toString()
                val expenseAdjustment = priorReduction.checkedSubtract(effect.expenseReduction)
                if (originalEvent.countInStatistics && expenseAdjustment.unscaled != 0L) {
                    val category = originalEvent.categoryId ?: throw FinanceValidationException("报销原账单缺少支出分类")
                    val position =
                        nextStatisticPosition.getOrPut(eventId) {
                            facts.statistics.filter { it.eventId == eventId }.maxOfOrNull { it.position + 1 } ?: 0
                        }
                    dao.insert(
                        StatisticEntity(
                            newId(),
                            eventId,
                            position,
                            category,
                            "EXPENSE",
                            expenseAdjustment.unscaled,
                            expenseAdjustment.scale,
                            expenseAdjustment.currency,
                            null,
                        ),
                    )
                    nextStatisticPosition[eventId] = position + 1
                }
                if (facts.budgets.any { it.eventId == originalEvent.id } && expenseAdjustment.unscaled != 0L) {
                    val category = originalEvent.categoryId ?: throw FinanceValidationException("报销原账单缺少支出分类")
                    val position =
                        nextBudgetPosition.getOrPut(eventId) {
                            facts.budgets.filter { it.eventId == eventId }.maxOfOrNull { it.position + 1 } ?: 0
                        }
                    dao.insert(
                        BudgetEntity(
                            newId(),
                            eventId,
                            position,
                            category,
                            expenseAdjustment.unscaled,
                            expenseAdjustment.scale,
                            expenseAdjustment.currency,
                            null,
                        ),
                    )
                    nextBudgetPosition[eventId] = position + 1
                }
                val incomeAdjustment = effect.excessIncome.checkedSubtract(priorIncome)
                if (originalEvent.countInStatistics && incomeAdjustment.unscaled != 0L) {
                    val category = settlement.incomeCategoryId ?: throw FinanceValidationException("超额报销需要收入分类")
                    val position =
                        nextStatisticPosition.getOrPut(eventId) {
                            facts.statistics.filter { it.eventId == eventId }.maxOfOrNull { it.position + 1 } ?: 0
                        }
                    dao.insert(
                        StatisticEntity(
                            newId(),
                            eventId,
                            position,
                            category.toString(),
                            "INCOME",
                            incomeAdjustment.unscaled,
                            incomeAdjustment.scale,
                            incomeAdjustment.currency,
                            null,
                        ),
                    )
                    nextStatisticPosition[eventId] = position + 1
                }
            }
            val revision = Math.addExact(record.localRevision, 1)
            dao.update(record.copy(updatedAt = now, localRevision = revision, claimState = recovery.status.name))
            journal(
                record.id,
                revision,
                record.localRevision,
                "CLAIM_SETTLEMENT_STATE",
                JSONObject()
                    .put("status", recovery.status.name)
                    .put("remainingEligible", claimMoneyIntent(recovery.remainingEligible))
                    .put(
                        "effects",
                        JSONArray().also { values ->
                            recovery.effects.forEach { effect ->
                                values.put(
                                    JSONObject()
                                        .put("settlementId", effect.receiptId.toString())
                                        .put("processedPrincipal", claimMoneyIntent(effect.processedPrincipal))
                                        .put("expenseReduction", claimMoneyIntent(effect.expenseReduction))
                                        .put("excessIncome", claimMoneyIntent(effect.excessIncome))
                                        .put("closedUnreimbursed", claimMoneyIntent(effect.closedUnreimbursed)),
                                )
                            }
                        },
                    ),
                now,
            )
        }
    }

    private fun claimMoneyIntent(value: Money): JSONObject =
        JSONObject().put("unscaled", value.unscaled.toString()).put("scale", value.scale).put("currency", value.currency)

    private fun claimMoney(value: JSONObject): Money =
        Money.fromWire(value.getString("unscaled"), value.getInt("scale"), value.getString("currency"))

    private val categoryDisplayOrder = compareBy<CategoryEntity> { it.sortOrder }.thenBy { it.parentId ?: "" }.thenBy { it.id }

    private fun financeCategory(row: CategoryEntity) = FinanceCategory(
        UUID.fromString(row.id), row.name, Direction.valueOf(row.direction), row.parentId?.let(UUID::fromString),
        row.archivedAt, row.sortOrder, row.localRevision, UUID.fromString(row.ledgerId),
        excludeBudget = row.excludeBudget, purpose = row.purpose,
    )

    private fun readSnapshot(range: PeriodRange? = null): FinanceSnapshot {
        val facts = facts()
        val accounts =
            facts.accounts.values.filter { it.deletedAt == null }
                .sortedWith(compareBy<AccountEntity> { facts.groups.single { group -> group.id == it.groupId }.sortOrder }.thenBy { it.groupId }.thenBy { it.sortOrder }.thenBy { it.id })
                .map { account ->
                FinanceAccount(
                    UUID.fromString(account.id),
                    account.name,
                    account.currency,
                    total(
                        account.currency,
                        facts.postings
                            .filter {
                                it.accountId == account.id
                            }.map { Money(it.amountUnscaled, it.amountScale, it.amountCurrency) },
                    ),
                    account.localRevision,
                    account.archivedAt,
                    account.includeInAssets,
                    account.selectable,
                    UUID.fromString(account.groupId),
                    account.sortOrder,
                    account.type,
                )
            }
        val records =
            facts.records.values
                .filter { it.deletedAt == null }
                .map { record ->
                    val event = facts.currentEvent(record)
                    FinanceRecord(
                        UUID.fromString(record.id),
                        restoreInput(event, facts.allocations.filter { it.eventId == event.id }),
                        event.occurredAtMillis,
                        event.timezone,
                        event.remark,
                        event.categoryId?.let(UUID::fromString),
                        record.localRevision,
                        record.claimState,
                        tagDao.recordTags()
                            .filter { it.recordId == record.id && it.ownerId == owner && it.epochId == epoch }
                            .mapNotNull { link ->
                                tagDao.tags().singleOrNull {
                                    it.id == link.tagId && it.ownerId == owner && it.epochId == epoch && it.deletedAt == null
                                }?.let { UUID.fromString(it.id) }
                            }.sortedBy(UUID::toString),
                        currentEventId = UUID.fromString(event.id),
                    )
                }.sortedWith(compareByDescending<FinanceRecord> { it.occurredAtMillis }.thenBy { it.id.toString() })
        val eventIds =
            range?.let { bounds ->
                facts.events.values
                    .filter { it.occurredAtMillis in bounds }
                    .map { it.id }
                    .toHashSet()
            }
        val statistics = if (eventIds == null) facts.statistics else facts.statistics.filter { it.eventId in eventIds }
        val budgets = if (eventIds == null) facts.budgets else facts.budgets.filter { it.eventId in eventIds }
        val inputsByEventId = records.mapNotNull { record -> record.currentEventId?.let { it.toString() to record.input } }.toMap()
        val livePostingsByEventId = facts.postings.filter { it.reversalOf == null }.groupBy { it.eventId }

        data class StatisticKey(
            val eventId: String,
            val categoryId: String,
            val direction: String,
            val currency: String,
        )
        val statisticEntries =
            facts.statistics
                .groupBy { row ->
                    val event = facts.events.getValue(row.eventId)
                    StatisticKey(event.reversalOf ?: event.id, row.categoryId, row.direction, row.amountCurrency)
                }.mapNotNull { (key, rows) ->
                    val amount = total(key.currency, rows.map { Money(it.amountUnscaled, it.amountScale, it.amountCurrency) })
                    if (amount.unscaled == 0L) return@mapNotNull null
                    val event = facts.events.getValue(key.eventId)
                    FinanceStatisticEntry(
                        UUID.fromString(event.id),
                        event.recordId?.let(UUID::fromString),
                        UUID.fromString(key.categoryId),
                        Direction.valueOf(key.direction),
                        amount,
                        event.occurredAtMillis,
                        event.timezone,
                        event.remark,
                        AccountStatisticProjection.project(
                            event.kind,
                            inputsByEventId[event.id],
                            Direction.valueOf(key.direction),
                            amount,
                            livePostingsByEventId[event.id]?.singleOrNull()?.accountId?.let(UUID::fromString),
                        ),
                    )
                }.sortedWith(
                    compareByDescending<FinanceStatisticEntry> { it.occurredAtMillis }
                        .thenBy { it.eventId.toString() }
                        .thenBy { it.categoryId.toString() }
                        .thenBy { it.direction.ordinal }
                        .thenBy { it.amount.currency },
                )
        val businessPreferences = businessPreferencesDao.read()?.also { check(it.ownerId == owner && it.epochId == epoch) }
            ?: BusinessPreferencesEntity(ownerId = owner, epochId = epoch).also(businessPreferencesDao::insert)
        val derivedIncome = if (businessPreferences.refundExcessAsIncome) {
            refunds(facts).asSequence().filter { it.status == "POSTED" && it.excessAmount.unscaled > 0 }
                .mapNotNull { refund ->
                    val record = facts.records[refund.originRecordId.toString()] ?: return@mapNotNull null
                    val original = facts.currentEvent(record)
                    if (record.deletedAt != null || !original.countInStatistics || original.categoryId == null) return@mapNotNull null
                    FinanceStatisticEntry(
                        UUID.fromString(original.id), UUID.fromString(record.id), UUID.fromString(original.categoryId),
                        Direction.INCOME, refund.excessAmount, original.occurredAtMillis, original.timezone, original.remark,
                    )
                }.toList()
        } else emptyList()
        val derivedInRange = derivedIncome.filter { entry ->
            eventIds == null || entry.eventId.toString() in eventIds
        }
        val preferences = businessPreferences
        val loanPlans = loanDao.plans().filter { plan ->
            plan.ownerId == owner && plan.epochId == epoch &&
                ((plan.disbursementEventId == null && plan.accountId in facts.accounts) || plan.disbursementEventId in facts.events.keys ||
                    loanDao.terms().any { it.planId == plan.id && (it.paidEventId in facts.events.keys || it.postedEventId in facts.events.keys) })
        }
        val reversedEvents = facts.events.values.mapNotNull { it.reversalOf }.toSet()
        val loanIncome = mutableMapOf<String, MutableList<Money>>()
        val loanExpense = mutableMapOf<String, MutableList<Money>>()
        val loanContributions = mutableListOf<FinanceLoanContribution>()
        loanPlans.forEach { plan ->
            plan.disbursementEventId?.let(facts.events::get)?.takeIf { event ->
                event.reversalOf == null && event.id !in reversedEvents && (eventIds == null || event.id in eventIds)
            }?.let { event ->
                val amount = Money(plan.principalUnscaled, plan.principalScale, plan.currency)
                loanContributions += FinanceLoanContribution(
                    UUID.fromString(plan.id), null, UUID.fromString(event.id), "PRINCIPAL_DRAW", amount,
                    if (preferences.loanPrincipalAsIncome) Direction.INCOME else null,
                    preferences.loanPrincipalAsIncome, Money(0, amount.scale, amount.currency), null, event.occurredAtMillis,
                )
                if (preferences.loanPrincipalAsIncome) {
                    loanIncome.getOrPut(plan.currency) { mutableListOf() } += amount
                }
            }
            loanDao.terms().filter { it.planId == plan.id && it.paidAtMillis != null && it.paidEventId?.let { id -> id !in reversedEvents } != false }
                .filter { term -> range == null || term.paidAtMillis?.let { it in range } == true }
                .forEach { term ->
                    val principal = Money(requireNotNull(term.paidPrincipalUnscaled), requireNotNull(term.paidPrincipalScale), plan.currency)
                    val interest = Money(requireNotNull(term.paidInterestUnscaled), requireNotNull(term.paidInterestScale), plan.currency)
                    val paymentEvent = term.paidEventId?.let(facts.events::get)
                    val reversed = paymentEvent?.id?.let { it in reversedEvents } == true
                    val withinRange = range == null || term.paidAtMillis?.let { it in range } == true
                    if (withinRange) {
                        loanContributions += FinanceLoanContribution(
                            UUID.fromString(plan.id), term.termIndex, paymentEvent?.id?.let(UUID::fromString),
                            "REPAYMENT_PRINCIPAL", principal,
                            if (preferences.loanRepaymentAsExpense && !reversed) Direction.EXPENSE else null,
                            preferences.loanRepaymentAsExpense && !reversed,
                            Money(0, principal.scale, principal.currency), paymentEvent?.categoryId?.let(UUID::fromString),
                            term.paidAtMillis ?: 0L,
                        )
                        loanContributions += FinanceLoanContribution(
                            UUID.fromString(plan.id), term.termIndex, paymentEvent?.id?.let(UUID::fromString),
                            "REPAYMENT_INTEREST", interest,
                            if (preferences.loanInterestAsExpense && !reversed) Direction.EXPENSE else null,
                            preferences.loanInterestAsExpense && !reversed,
                            if (!reversed && paymentEvent?.countInBudget == true && paymentEvent.categoryId != null) interest
                            else Money(0, interest.scale, interest.currency),
                            paymentEvent?.categoryId?.let(UUID::fromString), term.paidAtMillis ?: 0L,
                        )
                    }
                    if (!reversed && preferences.loanRepaymentAsExpense && principal.unscaled > 0) loanExpense.getOrPut(plan.currency) { mutableListOf() } += principal
                    if (!reversed && preferences.loanInterestAsExpense && interest.unscaled > 0) loanExpense.getOrPut(plan.currency) { mutableListOf() } += interest
                }
        }
        val currencies = (statistics.map { it.amountCurrency } + budgets.map { it.amountCurrency } + derivedInRange.map { it.amount.currency } +
            loanIncome.keys + loanExpense.keys).toSortedSet()
        val summaries = currencies.map { currency ->
            val baseIncome = statistics.filter { it.amountCurrency == currency && it.direction == Direction.INCOME.name }
                .map { Money(it.amountUnscaled, it.amountScale, it.amountCurrency) }
            val excessIncome = derivedInRange.filter { it.amount.currency == currency }.map { it.amount }
            val expense = total(
                currency,
                statistics.filter { it.amountCurrency == currency && it.direction == Direction.EXPENSE.name }
                    .map { Money(it.amountUnscaled, it.amountScale, it.amountCurrency) },
            )
            val budgetExpense = total(
                currency,
                budgets.filter { it.amountCurrency == currency }
                    .map { Money(it.amountUnscaled, it.amountScale, it.amountCurrency) },
            )
            FinanceSummary(
                currency,
                total(currency, baseIncome + excessIncome + loanIncome[currency].orEmpty()),
                total(currency, listOf(expense) + loanExpense[currency].orEmpty()),
                budgetExpense,
            )
        }
        val loanStatisticEntries =
            projectLoanStatisticEntries(
                loanContributions,
                facts.events,
                facts.records,
                owner,
                epoch,
                reversedEvents,
            )
        val effectiveEntries =
            (statisticEntries + derivedIncome + loanStatisticEntries)
                .sortedWith(
                    compareByDescending<FinanceStatisticEntry> { it.occurredAtMillis }
                        .thenBy { it.eventId.toString() }
                        .thenBy { it.direction.ordinal }
                        .thenBy { it.categoryId?.toString().orEmpty() }
                        .thenBy { it.amount.currency },
                )
        if (derivedIncome.isNotEmpty()) effectiveEntries.filter { it.direction == Direction.INCOME }.groupBy { it.amount.currency }.forEach { (currency, entries) -> total(currency, entries.map { it.amount }) }
        return FinanceSnapshot(
            ownerId = ownerId,
            epochId = epochId,
            ledgerId = UUID.fromString(facts.ledger.id),
            accounts = accounts,
            categories = facts.categories.values.filter { it.deletedAt == null && it.archivedAt == null }
                .sortedWith(categoryDisplayOrder)
                .map(::financeCategory),
            records = records,
            summaries = summaries,
            claimSettlements(facts),
            effectiveEntries,
            refunds(facts),
            businessPreferences,
            tagGroups = tagDao.groups().filter { it.deletedAt == null && it.ownerId == owner && it.epochId == epoch }
                .map { FinanceTagGroup(UUID.fromString(it.id), it.name, it.sortOrder, it.isDefault, it.localRevision) },
            tags = tagDao.tags().filter { it.deletedAt == null && it.ownerId == owner && it.epochId == epoch }
                .map { FinanceTag(UUID.fromString(it.id), UUID.fromString(it.groupId), it.name, it.color, it.sortOrder, it.archivedAt, it.localRevision) },
            accountGroups = facts.groups.filter { it.kind == "ACCOUNT" && it.deletedAt == null }
                .sortedWith(compareBy<FinanceGroupEntity> { it.sortOrder }.thenBy { it.id })
                .map { FinanceAccountGroup(UUID.fromString(it.id), it.name, it.sortOrder, it.isDefault, it.localRevision) },
            relatedPersonProfiles = relatedPersonDao.profiles().filter { it.ownerId == owner && it.epochId == epoch }.map { profile ->
                val account = dao.accounts().singleOrNull { it.id == profile.accountId && it.ownerId == owner && it.epochId == epoch && it.deletedAt == null }
                    ?: error("Related person profile references a missing account")
                check(profile.direction in setOf("GIVEN_TO_RELATIVE", "RECEIVED_FROM_RELATIVE"))
                check(profile.relation.isNotBlank() && profile.relation.codePointCount(0, profile.relation.length) <= 80)
                val limit = Money(profile.authorizedLimitUnscaled, profile.authorizedLimitScale, profile.currency)
                check(limit.unscaled >= 0 && limit.currency == account.currency)
                RelatedPersonProfile(UUID.fromString(profile.accountId), profile.direction, profile.relation, limit)
            },
            savingPlans = savingDao.plans().filter { it.ownerId == owner && it.epochId == epoch }
                .map { plan ->
                    val entries = savingDao.entries().filter { it.ownerId == owner && it.epochId == epoch && it.planId == plan.id && it.cancelledAt == null }
                    val progress = PlanCalculator.savingProgress(
                        Money(plan.targetUnscaled, plan.targetScale, plan.currency),
                        plan.initialSavedMoney(),
                        entries.filter { it.kind == "DEPOSIT" }.map { Money(it.amountUnscaled, it.amountScale, it.currency) },
                        entries.filter { it.kind == "WITHDRAWAL" }.map { Money(it.amountUnscaled, it.amountScale, it.currency) },
                    )
                    SavingPlanSummary(UUID.fromString(plan.id), plan.toSavingInput(),
                        if (plan.deletedAt == null) SavingState.valueOf(plan.state) else SavingState.DELETED,
                        progress.saved, progress.remaining, plan.localRevision)
                },
            savingEntries = savingDao.entries().filter { it.ownerId == owner && it.epochId == epoch }
                .map {
                    SavingEntrySummary(UUID.fromString(it.id), UUID.fromString(it.planId), SavingEntryKind.valueOf(it.kind),
                        Money(it.amountUnscaled, it.amountScale, it.currency), it.occurredAtMillis, it.cardIndex, it.remark,
                        it.linkedRecordId?.let(UUID::fromString), it.cancelledAt, it.localRevision)
                },
            creditProfiles = creditDao.profiles().filter { it.ownerId == owner && it.epochId == epoch && it.deletedAt == null }.map { row ->
                FinanceCreditProfile(UUID.fromString(row.id), UUID.fromString(row.accountId), Money(row.limitUnscaled, row.limitScale, row.currency), row.statementDay, row.includeStatementDay, row.repaymentRule, row.repaymentValue, row.timezone, row.enabled, row.localRevision)
            },
            creditCapacityEvents = creditDao.capacityEvents().filter { it.ownerId == owner && it.epochId == epoch }.map { row ->
                FinanceCreditCapacityEvent(UUID.fromString(row.id), UUID.fromString(row.accountId), row.sourceType, Money(row.deltaUnscaled, row.deltaScale, row.currency), row.occurredAtMillis, row.reason)
            },
            creditPaymentAllocations = creditDao.paymentAllocations().filter { it.ownerId == owner && it.epochId == epoch }.map { row ->
                FinanceCreditPaymentAllocation(UUID.fromString(row.id), UUID.fromString(row.accountId), UUID.fromString(row.paymentEventId), UUID.fromString(row.termId), Money(row.amountUnscaled, row.amountScale, row.currency))
            },
            installmentPlans = creditDao.plans().filter { it.ownerId == owner && it.epochId == epoch && it.deletedAt == null }.map { row ->
                FinanceInstallmentPlan(UUID.fromString(row.id), row.sourceType, UUID.fromString(row.accountId), Money(row.principalUnscaled, row.principalScale, row.currency), Money(row.interestUnscaled, row.interestScale, row.currency), row.termCount, row.principalPosting, row.executor, row.state, row.localRevision)
            },
            installmentTerms = creditDao.terms().filter { it.ownerId == owner && it.epochId == epoch }.map { row ->
                val due = Money(row.principalUnscaled, row.principalScale, row.currency).checkedAdd(Money(row.interestUnscaled, row.interestScale, row.currency))
                val allocated = creditDao.paymentAllocations().filter { it.termId == row.id && it.ownerId == owner && it.epochId == epoch }
                    .fold(Money(0, 0, row.currency)) { total, allocation -> total.checkedAdd(Money(allocation.amountUnscaled, allocation.amountScale, allocation.currency)) }
                FinanceInstallmentTerm(UUID.fromString(row.id), UUID.fromString(row.planId), row.termIndex, row.dueDate,
                    Money(row.principalUnscaled, row.principalScale, row.currency), Money(row.interestUnscaled, row.interestScale, row.currency),
                    row.state, row.postingEventId?.let(UUID::fromString), due.checkedSubtract(allocated))
            },
            loanPlans = loanPlans.map { plan ->
                FinanceLoanPlan(
                    UUID.fromString(plan.id), UUID.fromString(plan.accountId),
                    Money(plan.principalUnscaled, plan.principalScale, plan.currency), plan.localRevision, plan.state,
                    loanDao.terms().filter { it.planId == plan.id && (plan.deletedAt != null || it.activeTermKey != null) }
                        .sortedWith(compareBy<LoanTermEntity> { it.planRevision }.thenBy { it.termIndex }).map(::loanTerm),
                    plan.paidTermCount,
                    plan.annualRateNumerator,
                    plan.annualRateDenominator,
                    plan.repaymentType,
                    plan.firstDueDate,
                    loanDao.rateChanges().filter { it.ownerId == owner && it.epochId == epoch && it.planId == plan.id }
                        .sortedWith(compareBy<LoanRateChangeEntity> { it.toRevision }.thenBy { it.id })
                        .map { FinanceLoanRateChange(it.effectiveTerm, it.oldRateNumerator, it.oldRateDenominator,
                            it.newRateNumerator, it.newRateDenominator, it.fromRevision, it.toRevision, it.createdAt) },
                    loanDao.terms().filter { it.ownerId == owner && it.epochId == epoch && it.planId == plan.id &&
                        (plan.deletedAt != null || it.activeTermKey != null) && it.paidPrincipalUnscaled != null && it.paidInterestUnscaled != null }
                        .sortedBy { it.termIndex }.map { FinanceLoanPayment(it.termIndex,
                            Money(it.paidPrincipalUnscaled!!, it.paidPrincipalScale!!, plan.currency),
                            Money(it.paidInterestUnscaled!!, it.paidInterestScale!!, plan.currency), it.paidAtMillis) },
                )
            },
            ledgers = dao.ledgers().filter { it.ownerId == owner && it.epochId == epoch && it.deletedAt == null }
                .sortedWith(compareBy<LedgerEntity> { it.sortOrder }.thenBy { it.id })
                .map { FinanceLedger(UUID.fromString(it.id), it.name, it.baseCurrency, it.sortOrder, it.isDefault, it.archivedAt, it.localRevision) },
            allCategories = dao.categories().filter { it.ownerId == owner && it.epochId == epoch && it.deletedAt == null }
                .map(::financeCategory),
        )
    }

    private fun reverse(
        old: EventEntity,
        revision: Long,
        now: Long,
    ): EventEntity {
        val reversal = old.copy(id = newId(), localRevision = revision, kind = "REVERSAL", reversalOf = old.id, createdAt = now)
        dao.insert(reversal) // Unique reversalOf prevents a second reversal of the same event.
        dao.postings().filter { it.eventId == old.id }.forEach {
            dao.insert(
                it.copy(id = newId(), eventId = reversal.id, amountUnscaled = Math.negateExact(it.amountUnscaled), reversalOf = it.id),
            )
        }
        dao.statistics().filter { it.eventId == old.id }.forEach {
            dao.insert(
                it.copy(id = newId(), eventId = reversal.id, amountUnscaled = Math.negateExact(it.amountUnscaled), reversalOf = it.id),
            )
        }
        dao.budgets().filter { it.eventId == old.id }.forEach {
            dao.insert(
                it.copy(id = newId(), eventId = reversal.id, amountUnscaled = Math.negateExact(it.amountUnscaled), reversalOf = it.id),
            )
        }
        return reversal
    }

    private fun hasActiveSavingLink(recordId: String): Boolean {
        val plans = savingDao.plans().associateBy { it.id }
        val savingLink = savingDao.entries().any { entry ->
            entry.ownerId == owner && entry.epochId == epoch && entry.linkedRecordId == recordId &&
                entry.cancelledAt == null && plans[entry.planId]?.deletedAt == null
        }
        val loanEventIds = loanDao.terms().flatMap { listOfNotNull(it.postedEventId, it.paidEventId) }.toSet()
        val loanDrawIds = loanDao.plans().mapNotNull { it.disbursementEventId }.toSet()
        val loanLink = dao.events().any { it.id in loanEventIds + loanDrawIds && it.recordId == recordId && it.ownerId == owner && it.epochId == epoch }
        return savingLink || loanLink
    }

    private fun loanCreateIntent(input: LoanPlanInput, disbursement: LoanDisbursement?) =
        JSONObject().put("plan", JSONObject().put("id", input.id.toString()).put("accountId", input.accountId.toString())
            .put("principal", moneyIntent(input.principal))
            .put("annualRate", JSONObject().put("numerator", input.annualRateNumerator).put("denominator", input.annualRateDenominator))
            .put("termCount", input.termCount).put("paidTermCount", input.paidTermCount)
            .put("repaymentType", input.repaymentType.name).put("firstDueDate", input.firstDueDate))
            .put("disbursement", if (disbursement == null) JSONObject.NULL else JSONObject()
                .put("actualAccountId", disbursement.actualAccountId.toString()).put("occurredAt", disbursement.occurredAtMillis))

    private fun loanReplay(opId: UUID, aggregate: String, base: Long, kind: String, payload: JSONObject): Boolean? {
        val old = dao.mutations().singleOrNull { it.opId == opId.toString() } ?: return null
        valid(old.ownerId == owner && old.epochId == epoch, "操作身份已变化")
        valid(old.aggregateId == aggregate && old.baseLocalRevision == base && old.intentVersion == 1 &&
            old.kind == kind && old.payload == payload.toString(), "操作编号已用于不同内容")
        return true
    }

    private fun activeLoan(id: UUID, expectedVersion: Long): LoanPlanEntity =
        loanDao.plans().singleOrNull { it.id == id.toString() && it.ownerId == owner && it.epochId == epoch && it.deletedAt == null }
            ?.also { valid(it.localRevision == expectedVersion, "贷款计划已变更，请刷新后重试") }
            ?: throw FinanceValidationException("贷款计划不存在")

    private fun activeLoanTerms(planId: String) =
        loanDao.terms().filter { it.planId == planId && it.activeTermKey != null }
            .sortedBy { it.termIndex }.map(::loanTerm)

    private fun ensureLoanSuffixEditable(planId: String, fromTerm: Int) {
        val affected = loanDao.terms().filter { it.planId == planId && it.activeTermKey != null && it.termIndex >= fromTerm }
        valid(affected.isNotEmpty(), "贷款期次不存在")
        valid(affected.all { it.paidEventId == null && it.postedEventId == null && it.dueState != "PAID" },
            "已还或已入账期次不能重算")
    }

    private fun loanTerm(row: LoanTermEntity) = LoanTerm(
        row.termIndex, row.dueDate,
        Money(row.openingPrincipalUnscaled, row.openingPrincipalScale, loanCurrency(row.planId)),
        Money(row.principalUnscaled, row.principalScale, loanCurrency(row.planId)),
        Money(row.interestUnscaled, row.interestScale, loanCurrency(row.planId)),
        Money(row.closingPrincipalUnscaled, row.closingPrincipalScale, loanCurrency(row.planId)),
        LoanDueState.valueOf(row.dueState),
    )

    private fun loanCurrency(planId: String) =
        loanDao.plans().single { it.id == planId && it.ownerId == owner && it.epochId == epoch }.currency

    private fun commitLoanSchedule(
        plan: LoanPlanEntity,
        old: List<LoanTerm>,
        proposed: List<LoanTerm>,
        expectedVersion: Long,
        affectedFrom: Int,
        payload: JSONObject,
        opId: UUID,
        kind: String,
    ) {
        val activeRows = loanDao.terms().filter { it.planId == plan.id && it.activeTermKey != null && it.termIndex >= affectedFrom }
        valid(activeRows.all { it.paidEventId == null && it.postedEventId == null && it.dueState != "PAID" }, "已还或已入账期次不能重算")
        val now = System.currentTimeMillis()
        activeRows.forEach { loanDao.update(it.copy(activeTermKey = null, localRevision = Math.addExact(it.localRevision, 1))) }
        proposed.filter { it.termIndex >= affectedFrom }.forEach { loanDao.insert(it.toEntity(plan.id, expectedVersion + 1, false, owner, epoch)) }
        val revision = Math.addExact(expectedVersion, 1)
        loanDao.update(plan.copy(localRevision = revision, updatedAt = now))
        journal(plan.id, revision, expectedVersion, kind, payload, now, opId.toString())
    }

    private fun postLoanFact(
        liabilityAccountId: String,
        actualAccountId: UUID,
        amount: Money,
        principal: Money,
        occurredAtMillis: Long,
        kind: String,
        now: Long,
    ): String {
        amount.requirePositive()
        valid(occurredAtMillis in 0..now, "贷款资金时间无效")
        val facts = facts()
        val asset = facts.accounts[actualAccountId.toString()]
        valid(asset != null && asset.ownerId == owner && asset.epochId == epoch && asset.type == "ASSET" &&
            asset.deletedAt == null && asset.archivedAt == null && asset.selectable && asset.currency == amount.currency, "请选择可用资产账户")
        val recordId = newId()
        val eventId = newId()
        val expense = kind == "EXPENSE"
        val input: RecordInput = if (expense) {
            RecordInput.Expense(amount, null, listOf(AccountAmount(actualAccountId, amount)), false, false)
        } else {
            RecordInput.Income(amount, listOf(AccountAmount(actualAccountId, amount)), false)
        }
        val record = RecordEntity(recordId, owner, epoch, now, now, 1, null)
        dao.insert(record)
        val event = event(eventId, facts.ledger.id, recordId, 1, kind, occurredAtMillis, TimeZone.getDefault().id,
            "贷款", null, input, now, amount).copy(countInStatistics = false, countInBudget = false)
        dao.insert(event)
        dao.insert(AllocationEntity(newId(), eventId, 0, "ALLOCATION", actualAccountId.toString(),
            amount.unscaled, amount.scale, amount.currency))
        dao.insert(posting(eventId, 0, AccountAmount(actualAccountId, if (expense) amount.checkedNegate() else amount)))
        if (expense) dao.insert(posting(eventId, 1, AccountAmount(UUID.fromString(liabilityAccountId), principal.checkedNegate())))
        else dao.insert(posting(eventId, 1, AccountAmount(UUID.fromString(liabilityAccountId), principal)))
        return eventId
    }

    private fun event(
        id: String,
        ledgerId: String,
        recordId: String?,
        revision: Long,
        kind: String,
        at: Long,
        zone: String,
        remark: String,
        category: String?,
        input: RecordInput?,
        now: Long,
        amount: Money? = null,
    ): EventEntity {
        val primary =
            amount ?: when (input) {
                is RecordInput.Expense -> input.gross
                is RecordInput.Income -> input.amount
                else -> null
            }
        val discount = (input as? RecordInput.Expense)?.discount
        return EventEntity(
            id,
            owner,
            epoch,
            ledgerId,
            recordId,
            revision,
            kind,
            at,
            zone,
            localDate(at, zone),
            remark,
            category,
            when (input) {
                is RecordInput.Expense -> input.countInStatistics
                is RecordInput.Income -> input.countInStatistics
                else -> false
            },
            (input as? RecordInput.Expense)?.countInBudget ?: false,
            primary?.unscaled,
            primary?.scale,
            primary?.currency,
            discount?.unscaled,
            discount?.scale,
            discount?.currency,
            null,
            now,
            "LOCAL",
        )
    }

    private fun posting(
        event: String,
        position: Int,
        value: AccountAmount,
    ) = PostingEntity(
        newId(),
        event,
        position,
        value.accountId.toString(),
        value.amount.unscaled,
        value.amount.scale,
        value.amount.currency,
        null,
    )

    private fun restoreInput(
        event: EventEntity,
        rows: List<AllocationEntity>,
    ): RecordInput {
        val allocations = rows.sortedBy { it.position }

        fun AllocationEntity.value() = AccountAmount(UUID.fromString(accountId), Money(amountUnscaled, amountScale, amountCurrency))

        fun primary() = Money(requireNotNull(event.amountUnscaled), requireNotNull(event.amountScale), requireNotNull(event.amountCurrency))
        return when (event.kind) {
            "EXPENSE" -> {
                RecordInput.Expense(
                    primary(),
                    event.discountUnscaled?.let { Money(it, requireNotNull(event.discountScale), requireNotNull(event.discountCurrency)) },
                    allocations.map {
                        check(it.role == "ALLOCATION")
                        it.value()
                    },
                    event.countInStatistics,
                    event.countInBudget,
                )
            }

            "INCOME" -> {
                RecordInput.Income(
                    primary(),
                    allocations.map {
                        check(it.role == "ALLOCATION")
                        it.value()
                    },
                    event.countInStatistics,
                )
            }

            "TRANSFER" -> {
                check(allocations.map { it.role }.distinct().size == allocations.size)
                RecordInput.Transfer(
                    allocations.single { it.role == "FROM" }.value(),
                    allocations.single { it.role == "TO" }.value(),
                    allocations.singleOrNull { it.role == "FEE" }?.value(),
                    allocations.singleOrNull { it.role == "BONUS" }?.value(),
                )
            }

            else -> {
                error("Invalid record kind")
            }
        }.also { it.calculate() }
    }

    private fun inputAllocations(input: RecordInput): List<Pair<String, AccountAmount>> =
        when (input) {
            is RecordInput.Expense -> {
                input.allocations.map { "ALLOCATION" to it }
            }

            is RecordInput.Income -> {
                input.allocations.map { "ALLOCATION" to it }
            }

            is RecordInput.Transfer -> {
                listOfNotNull(
                    "FROM" to input.from,
                    "TO" to input.to,
                    input.fee?.let { "FEE" to it },
                    input.bonus?.let {
                        "BONUS" to
                            it
                    },
                )
            }
        }

    /** Exact accumulation includes historical reversals; only the final result is bounded to i64. */
    private fun total(
        currency: String,
        values: List<Money>,
    ): Money {
        var sum = BigDecimal.ZERO
        values.forEach {
            check(it.currency == currency)
            sum = sum.add(BigDecimal(BigInteger.valueOf(it.unscaled), it.scale))
        }
        sum = sum.stripTrailingZeros()
        if (sum.scale() < 0) sum = sum.setScale(0)
        return Money(sum.unscaledValue().longValueExact(), sum.scale(), currency)
    }

    private fun localDate(
        at: Long,
        zone: String,
    ): String {
        valid(zone in timezones, "请选择有效时区")
        val calendar = Calendar.getInstance(TimeZone.getTimeZone(zone), Locale.ROOT).apply { timeInMillis = at }
        valid(calendar.get(Calendar.ERA) == 1 && calendar.get(Calendar.YEAR) in 1..9999, "日期超出支持范围")
        return String.format(
            Locale.ROOT,
            "%04d-%02d-%02d",
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH),
        )
    }

    private fun journal(
        aggregate: String,
        revision: Long,
        base: Long,
        kind: String,
        payload: JSONObject,
        now: Long,
        opId: String = newId(),
    ) {
        // Local intent format only: a future mapper must validate and freeze a separate server request.
        dao.insert(
            MutationEntity(
                opId = opId,
                ownerId = owner,
                epochId = epoch,
                aggregateId = aggregate,
                localRevision = revision,
                baseLocalRevision = base,
                intentVersion = 1,
                kind = kind,
                payload = payload.toString(),
                state = "LOCAL_PENDING",
                createdAt = now,
            ),
        )
    }

    private fun moneyIntent(value: Money) =
        JSONObject().put("unscaled", value.unscaled.toString()).put("scale", value.scale).put("currency", value.currency)

    private fun savingPlanIntent(input: SavingPlanInput) =
        JSONObject().put("kind", input.kind.name).put("name", input.name).put("iconRef", input.iconRef ?: JSONObject.NULL)
            .put("target", moneyIntent(input.target)).put("initialSaved", moneyIntent(input.initialSaved))
            .put("firstAmount", input.firstAmount?.let(::moneyIntent) ?: JSONObject.NULL)
            .put("increment", input.increment?.let(::moneyIntent) ?: JSONObject.NULL)
            .put("interval", input.interval?.let { JSONObject().put("unit", it.unit.name).put("step", it.step) } ?: JSONObject.NULL)
            .put("startDate", input.startDate ?: JSONObject.NULL).put("endDate", input.endDate ?: JSONObject.NULL)
            .put("allowManualEntries", input.allowManualEntries)

    private fun savingEntryIntent(entry: SavingEntryEntity): JSONObject {
        val linked = entry.linkedRecordId?.let { recordId ->
            val data = facts()
            val record = data.records[recordId] ?: error("Linked saving record is missing")
            val current = data.currentEvent(record)
            val recordIntent = eventIntent(current)
                .put("allocations", JSONArray().also { array ->
                    data.allocations.filter { it.eventId == current.id }.sortedBy { it.position }.forEach { allocation ->
                        array.put(JSONObject().put("role", allocation.role).put("accountId", allocation.accountId)
                            .put("amount", moneyIntent(Money(allocation.amountUnscaled, allocation.amountScale, allocation.amountCurrency))))
                    }
                })
                .put("tagIds", JSONArray().also { array ->
                    tagDao.recordTags().filter { it.recordId == recordId && it.ownerId == owner && it.epochId == epoch }
                        .forEach { array.put(it.tagId) }
                })
            JSONObject().put("recordId", recordId).put("record", recordIntent).put("expectedLocalRevision", record.localRevision)
        } ?: JSONObject.NULL
        return JSONObject().put("entryId", entry.id).put("kind", entry.kind)
            .put("amount", moneyIntent(Money(entry.amountUnscaled, entry.amountScale, entry.currency)))
            .put("occurredAtMillis", entry.occurredAtMillis).put("cardIndex", entry.cardIndex ?: JSONObject.NULL)
            .put("remark", entry.remark ?: JSONObject.NULL).put("linkedTransfer", linked)
    }

    private fun eventIntent(event: EventEntity): JSONObject =
        JSONObject()
            .put("eventId", event.id)
            .put("recordId", event.recordId ?: JSONObject.NULL)
            .put("ledgerId", event.ledgerId)
            .put(
                "kind",
                event.kind,
            ).put("occurredAtMillis", event.occurredAtMillis)
            .put("timezone", event.timezone)
            .put("localDate", event.localDate)
            .put("remark", event.remark)
            .put("categoryId", event.categoryId ?: JSONObject.NULL)
            .put("countInStatistics", event.countInStatistics)
            .put("countInBudget", event.countInBudget)
            .put(
                "amount",
                event.amountUnscaled?.let { moneyIntent(Money(it, event.amountScale!!, event.amountCurrency!!)) } ?: JSONObject.NULL,
            ).put(
                "discount",
                event.discountUnscaled?.let { moneyIntent(Money(it, event.discountScale!!, event.discountCurrency!!)) } ?: JSONObject.NULL,
            )

    private fun newId() = UUID.randomUUID().toString()

    private fun valid(
        condition: Boolean,
        message: String,
    ) {
        if (!condition) throw FinanceValidationException(message)
    }
}

/** Private no-backup preferences. Interrupted or missing identity beside existing data fails closed. */
private object GuestIdentity {
    @Synchronized fun readOrCreate(context: Context): Pair<UUID, UUID> {
        val directory = context.noBackupFilesDir
        RandomAccessFile(File(directory, "guest-identity.lock"), "rw").use { file ->
            file.channel.lock().use {
                val preferences = File(directory, "guest-identity.preferences")
                val pending = File(directory, "guest-identity.pending")
                check(!pending.exists())
                val creating = !preferences.exists()
                if (creating) {
                    val datasets = File(directory, "datasets")
                    check(!datasets.exists() || (datasets.isDirectory && datasets.list()?.isEmpty() == true))
                    val owner = UUID.randomUUID()
                    val epoch = UUID.randomUUID()
                    check(pending.createNewFile())
                    FileOutputStream(pending).use { output ->
                        Properties()
                            .apply {
                                setProperty("version", "1")
                                setProperty("ownerId", owner.toString())
                                setProperty("epochId", epoch.toString())
                            }.store(output, null)
                        output.fd.sync()
                    }
                    check(pending.renameTo(preferences))
                }
                check(preferences.isFile && preferences.length() in 1..512)
                val values = Properties().apply { preferences.inputStream().use { load(it) } }
                check(values.keys == setOf("version", "ownerId", "epochId") && values.getProperty("version") == "1")

                fun uuid(key: String): UUID {
                    val text = values.getProperty(key)
                    return UUID.fromString(text).also { check(it.toString() == text && it != UUID(0, 0)) }
                }
                val identity = uuid("ownerId") to uuid("epochId")
                if (!creating) check(EncryptedDatasetStore.directory(context, identity.first, identity.second).isDirectory)
                return identity
            }
        }
    }
}
