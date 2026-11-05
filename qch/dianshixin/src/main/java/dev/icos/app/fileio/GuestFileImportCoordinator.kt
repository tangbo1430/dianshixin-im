package dev.icos.app.fileio

import dev.icos.app.data.local.FinanceCategory
import dev.icos.app.data.local.FinanceSnapshot
import dev.icos.app.data.local.FileImportBatchEntity
import dev.icos.app.data.local.FileImportRowEntity
import dev.icos.app.data.local.LocalFinanceRepository
import dev.icos.app.domain.AccountAmount
import dev.icos.app.domain.Money
import dev.icos.app.domain.RecordInput
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.time.ZoneId
import java.util.UUID

/** Guest-only import coordinator. Preview is persisted before confirmation and never writes finance facts. */
class GuestFileImportCoordinator(private val repository: LocalFinanceRepository) {
    data class Preview(
        val stagedId: UUID,
        val digest: String,
        val parsed: TabularFileCore.ParseResult,
        val mapping: ImportPreview,
        val categoryOptions: List<FinanceCategory>,
        val accountOptions: Map<String, UUID>,
        val explicitCategoryBindings: Map<String, UUID>,
        val selectedRows: Set<Int>,
        val tagOptions: Map<String, List<UUID>>,
        val explicitTagBindings: Map<String, UUID>,
    )

    fun preview(file: File, zone: ZoneId, snapshot: FinanceSnapshot): Preview {
        val parsed = file.inputStream().use { TabularFileCore.parse(if (file.extension == "input") "staged.csv" else file.name, it) }
        val categories = snapshot.categories
        val accounts = snapshot.accounts.associate { it.name to it.id }
        val mapping = ImportRowMapper.map(parsed, zone, currencyCodes(), snapshot.summaries.firstOrNull()?.currency ?: snapshot.accounts.firstOrNull()?.currency ?: "CNY", emptyMap(), emptyMap())
        val digest = parsed.sha256
        val id = UUID.nameUUIDFromBytes((digest + ":preview").toByteArray(Charsets.UTF_8))
        val initiallySelected = mapping.candidates.filter { candidate -> mapping.errors.none { it.row == candidate.sourceRow && it.code !in setOf("CATEGORY_BINDING_REQUIRED", "ACCOUNT_BINDING_REQUIRED") } }.map { it.sourceRow }.toSet()
        val tags = snapshot.tags.filter { it.archivedAt == null }.groupBy({ it.name }, { it.id })
        return Preview(id, digest, parsed, mapping, categories, accounts, emptyMap(), initiallySelected, tags, emptyMap())
    }

    fun map(preview: Preview, zone: ZoneId, snapshot: FinanceSnapshot, accountBindings: Map<String, UUID>, categoryBindings: Map<String, UUID>, tagBindings: Map<String, UUID> = emptyMap()): Preview {
        val allowed = currencyCodes()
        val permittedAccounts = snapshot.accounts.filter { it.selectable && it.archivedAt == null }.map { it.id }.toSet()
        val safeAccounts = accountBindings.filterValues { it in permittedAccounts }
        val expectedDirections = preview.mapping.candidates.associate { candidate ->
            val key = "${candidate.primaryCategory}\u0000${candidate.secondaryCategory}\u0000${candidate.kind}"
            key to if (candidate.kind == "INCOME") dev.icos.app.domain.Direction.INCOME else dev.icos.app.domain.Direction.EXPENSE
        }
        val categoriesById = snapshot.categories.associateBy { it.id }
        val safeCategories = categoryBindings.filter { (key, id) ->
            categoriesById[id]?.direction == expectedDirections[key]
        }
        val mapping = ImportRowMapper.map(preview.parsed, zone, allowed, snapshot.summaries.firstOrNull()?.currency ?: snapshot.accounts.firstOrNull()?.currency ?: "CNY", safeAccounts, safeCategories)
        return preview.copy(mapping = mapping, explicitCategoryBindings = safeCategories, accountOptions = snapshot.accounts.filter { it.selectable && it.archivedAt == null }.associate { it.name to it.id }, explicitTagBindings = tagBindings.filterValues { id -> preview.tagOptions.values.flatten().contains(id) })
    }

    fun stage(preview: Preview): UUID {
        val selected = preview.mapping.candidates.filter { it.sourceRow in preview.selectedRows }
        require(selected.isNotEmpty()) { "IMPORT_NO_VALID_ROWS_SELECTED" }
        val selectedNumbers = selected.map { it.sourceRow }.toSet()
        require(preview.mapping.errors.none { it.row in selectedNumbers || it.row == 0 }) { "IMPORT_PREVIEW_HAS_SELECTED_ERRORS" }
        val prepared = selected.map { candidate ->
            val kind = candidate.kind ?: error("TYPE")
            val amount = candidate.amount ?: error("AMOUNT")
            val category = if (kind == "TRANSFER") null else preview.explicitCategoryBindings.getValue("${candidate.primaryCategory}\u0000${candidate.secondaryCategory}\u0000$kind")
            val input = when (kind) {
                "EXPENSE" -> {
                    val allocations = candidate.account1.takeIf(String::isNotBlank)?.let { listOf(AccountAmount(preview.accountOptions.getValue(it), amount)) }.orEmpty()
                    RecordInput.Expense(amount, null, allocations, true, true)
                }
                "INCOME" -> {
                    val allocations = candidate.account1.takeIf(String::isNotBlank)?.let { listOf(AccountAmount(preview.accountOptions.getValue(it), amount)) }.orEmpty()
                    RecordInput.Income(amount, allocations, true)
                }
                "TRANSFER" -> {
                    val from = AccountAmount(preview.accountOptions.getValue(candidate.account1), amount)
                    val to = AccountAmount(preview.accountOptions.getValue(candidate.account2), amount)
                    RecordInput.Transfer(from, to, null, null)
                }
                else -> error("TYPE")
            }
            val tagIds = candidate.tags.map { tagName ->
                preview.explicitTagBindings[tagName] ?: preview.tagOptions[tagName]?.singleOrNull() ?: error("TAG_BINDING_REQUIRED:$tagName")
            }
            PreparedImportRecord(candidate.sourceRow, input, candidate.occurredAtMillis ?: error("DATE"), candidate.timezone, candidate.remark, category, tagIds, candidate.externalId)
        }
        val rows = ImportBatchFactory.rows("pending", prepared)
        return repository.stageFileImport(preview.digest, rows)
    }

    fun confirm(batchId: UUID, digest: String) = repository.confirmFileImport(batchId, digest)

    fun history(): List<Pair<FileImportBatchEntity, Int>> = repository.fileImportBatches()

    fun reverse(batchId: UUID, reason: String) = repository.reverseFileImportBatch(batchId, reason)

    private fun currencyCodes(): Set<String> = MoneyRegistry.currencies
}

/** Registry loaded from the same pinned currency contract resource used by Money. */
private object MoneyRegistry {
    val currencies: Set<String> = requireNotNull(Money::class.java.getResourceAsStream("/iso-4217-currencies.txt"))
        .bufferedReader().use { it.readText().trim().split(Regex("\\s+")).toSet() }
}

object FinanceFileExportMapper {
    fun snapshot(snapshot: FinanceSnapshot, revision: Long, accountsById: Map<UUID, String>, categoriesById: Map<UUID, String>): FileExportSnapshot {
        require(snapshot.relatedPersonProfiles.isEmpty()) { "LOSSY_EXPORT_UNSUPPORTED:related_person_profile" }
        val rows = snapshot.records.asSequence().map { record ->
            val amount = when (val input = record.input) {
                is RecordInput.Expense -> input.gross
                is RecordInput.Income -> input.amount
                is RecordInput.Transfer -> input.from.amount
            }
            val kind = when (record.input) { is RecordInput.Expense -> "支出"; is RecordInput.Income -> "收入"; is RecordInput.Transfer -> "转账" }
            val allocations = when (val input = record.input) {
                is RecordInput.Expense -> input.allocations
                is RecordInput.Income -> input.allocations
                is RecordInput.Transfer -> listOf(input.from, input.to)
            }
            val omitted = when (val input = record.input) {
                is RecordInput.Expense -> listOfNotNull(input.discount?.takeIf { it.unscaled != 0L }?.let { "折扣" }, if (input.allocations.size > 1) "多账户分摊" else null)
                is RecordInput.Income -> if (input.allocations.size > 1) listOf("多账户分摊") else emptyList()
                is RecordInput.Transfer -> listOfNotNull(input.fee?.let { "手续费" }, input.bonus?.let { "优惠" }, if (input.from.amount.currency != input.to.amount.currency) "跨币种转账" else null)
            }
            require(omitted.isEmpty()) { "LOSSY_EXPORT_UNSUPPORTED_RECORD:${record.id}:$omitted" }
            val first = allocations.getOrNull(0)
            val second = if (record.input is RecordInput.Transfer) allocations.getOrNull(1) else null
            val category = record.categoryId?.let(categoriesById::get).orEmpty()
            val date = java.time.Instant.ofEpochMilli(record.occurredAtMillis).atZone(ZoneId.of(record.timezone)).format(java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss"))
            val tagNames = record.tagIds.map { tagId -> snapshot.tags.firstOrNull { it.id == tagId }?.name ?: error("EXPORT_UNKNOWN_TAG:${record.id}:$tagId") }
            require(first == null || first.accountId in accountsById) { "EXPORT_UNKNOWN_ACCOUNT:${record.id}" }
            require(second == null || second.accountId in accountsById) { "EXPORT_UNKNOWN_ACCOUNT:${record.id}" }
            require(record.categoryId == null || category != "") { "EXPORT_UNKNOWN_CATEGORY:${record.id}" }
            listOf(date, kind, amount.decimalText(), category, "", first?.accountId?.let(accountsById::get).orEmpty(), second?.accountId?.let(accountsById::get).orEmpty(), record.remark, amount.currency, tagNames.joinToString("") { "#$it" })
        }
        return FileExportSnapshot(revision, rows)
    }

    private fun Money.decimalText(): String = java.math.BigDecimal(java.math.BigInteger.valueOf(unscaled), scale).toPlainString()
}
