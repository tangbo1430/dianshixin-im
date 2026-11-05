package dev.icos.app.data.local

import dev.icos.app.domain.Direction
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Category metadata commands persist with canonical CAS and nonfinancial replay markers. */
internal class AccountCategoryCommands(private val database: DatasetDatabase) {
    private val mutations = AccountMutationRepository(database)

    /** Appends a category under the captured active ledger and optional parent. */
    fun create(
        deviceId: UUID, ledger: FinanceLedger, name: String, direction: Direction, parent: FinanceCategory?,
        excludeBudget: Boolean = false,
    ): UUID {
        val cleaned = requireName(name)
        require(!excludeBudget || direction == Direction.EXPENSE) { "收入分类不能排除预算。" }
        val parentId = parent?.id
        val newId = UUID.randomUUID()
        database.runInTransaction {
            val tables = projectedTables(setOf("categories", "ledgers"))
            val ledgerRow = requireLedger(tables, ledger.id, ledger.localRevision)
            requireNoPendingLedger()
            val parentRow = parent?.let { requireParent(tables, it, it.localRevision, ledger.id, direction) }
            val categories = tables.getJSONArray("categories").objects()
            requireUniqueSibling(categories, ledger.id, direction, parentId, cleaned, exceptId = null)
            val sortOrder = nextSortOrder(categories, ledger.id, direction, parentId)
            val entity = JSONObject()
                .put("ledgerId", ledger.id.toString())
                .put("direction", direction.name)
                .put("parentId", parentId?.toString() ?: JSONObject.NULL)
                .put("name", cleaned)
                .put("iconRef", JSONObject.NULL)
                .put("color", JSONObject.NULL)
                .put("sortOrder", sortOrder)
                .put("archivedAt", JSONObject.NULL)
                .put("excludeBudget", excludeBudget)
            val versions = JSONObject()
                .put("category:$newId", 0)
                .put("ledger:${ledger.id}", ledgerRow.getLong("serverRevision"))
            parent?.let { versions.put("category:${it.id}", requireNotNull(parentRow).getLong("serverRevision")) }
            mutations.enqueueCanonicalOnly(deviceId, "category", newId, "metadata.create",
                JSONObject().put("entity", entity), versions)
        }
        return newId
    }

    fun importSharedCategories(
        deviceId: UUID, snapshot: FinanceSnapshot, categories: List<dev.icos.app.data.remote.SharedCategoryConfig>,
    ): Map<UUID, UUID> {
        dev.icos.app.data.remote.CategoryShareJson.validateCategories(categories)
        return database.runInTransaction(java.util.concurrent.Callable {
            val identity = database.identity().read() ?: error("Dataset identity missing")
            check(identity.ownerId == snapshot.ownerId.toString() && identity.epochId == snapshot.epochId.toString()) { "账号账本已切换，请重新预览" }
            val ledger = snapshot.ledgers.single { it.id == snapshot.ledgerId }
            val tables = SnapshotImporter.canonicalTables(database)
            val ledgerRow = requireLedger(tables, ledger.id, ledger.localRevision)
            requireNoPendingCategoryOrLedger()
            val rows = tables.getJSONArray("categories").objects().filter {
                it.optString("ledgerId") == ledger.id.toString() && it.isNull("deletedAt")
            }.toMutableList()
            val mapping = linkedMapOf<UUID, UUID>()
            categories.sortedWith(compareBy<dev.icos.app.data.remote.SharedCategoryConfig> { it.parentSourceId != null }
                .thenBy { it.sortOrder }.thenBy { it.sourceId }).forEach { imported ->
                val parentId = imported.parentSourceId?.let { mapping.getValue(it) }
                val matches = rows.filter {
                    it.optString("direction") == imported.direction && it.optString("name") == imported.name &&
                        it.uuidOrNull("parentId") == parentId
                }
                require(matches.none { !it.isNull("archivedAt") }) { "目标账本存在同名归档分类，请先处理归档分类" }
                if (matches.isNotEmpty()) {
                    val compatible = matches.singleOrNull()?.takeIf {
                        it.getLong("sortOrder") == imported.sortOrder && it.canonicalBoolean("excludeBudget") == imported.excludeBudget &&
                            it.isNull("iconRef") && it.isNull("color")
                    }
                    requireNotNull(compatible) { "目标账本存在同名分类但配置不兼容" }
                    mapping[imported.sourceId] = UUID.fromString(compatible.getString("id"))
                } else {
                    val id = UUID.randomUUID()
                    val entity = JSONObject().put("ledgerId", ledger.id.toString()).put("direction", imported.direction)
                        .put("parentId", parentId?.toString() ?: JSONObject.NULL).put("name", imported.name)
                        .put("iconRef", JSONObject.NULL).put("color", JSONObject.NULL).put("sortOrder", imported.sortOrder)
                        .put("archivedAt", JSONObject.NULL).put("excludeBudget", imported.excludeBudget)
                    val versions = JSONObject().put("category:$id", 0).put("ledger:${ledger.id}", ledgerRow.getLong("serverRevision"))
                    parentId?.let { parent -> versions.put("category:$parent", rows.single { it.getString("id") == parent.toString() }.getLong("serverRevision")) }
                    mutations.enqueueCanonicalOnly(deviceId, "category", id, "metadata.create", JSONObject().put("entity", entity), versions)
                    rows += JSONObject(entity.toString()).put("id", id.toString()).put("serverRevision", 1)
                    mapping[imported.sourceId] = id
                }
            }
            mapping
        })
    }

    /** Renames an active category while preserving the complete canonical DTO. */
    fun rename(
        deviceId: UUID, category: FinanceCategory, name: String, ledger: FinanceLedger, parent: FinanceCategory?,
    ) = updateDetails(deviceId, category, name, ledger, parent)

    /** Updates an active category's synchronized color while preserving its canonical metadata. */
    fun setColor(
        deviceId: UUID, category: FinanceCategory, color: String?, ledger: FinanceLedger, parent: FinanceCategory?,
    ) = updateDetails(deviceId, category, category.name, ledger, parent, color, updateColor = true)

    /** Changes budget exclusion without modifying historical budget contributions. */
    fun setBudgetExcluded(
        deviceId: UUID, category: FinanceCategory, excluded: Boolean, ledger: FinanceLedger, parent: FinanceCategory?,
    ) = updateDetails(deviceId, category, category.name, ledger, parent, excludeBudget = excluded)

    private fun updateDetails(
        deviceId: UUID,
        category: FinanceCategory,
        name: String,
        ledger: FinanceLedger,
        parent: FinanceCategory?,
        color: String? = null,
        updateColor: Boolean = false,
        excludeBudget: Boolean? = null,
    ) {
        require(category.localRevision >= 0) { "分类版本无效" }
        val cleaned = requireName(name)
        val normalizedColor = if (updateColor) color?.also {
            require(it.matches(Regex("#[0-9A-Fa-f]{6}"))) { "分类颜色格式无效" }
        }?.uppercase(java.util.Locale.ROOT) else null
        database.runInTransaction {
            val tables = projectedTables()
            val row = findCategory(tables, category.id)
            require(row.getLong("serverRevision") == category.localRevision && row.getString("name") == category.name) { "分类已变化，请刷新后重新编辑。" }
            require(row.isNull("archivedAt")) { "请先恢复分类，再修改名称或颜色。" }
            require(row.isNull("iconRef")) { "此分类图标暂不支持编辑，原值已保留。" }
            require(row.optString("ledgerId") == ledger.id.toString() && row.optString("ledgerId") == category.ledgerId.toString()) {
                "分类账本已变化，请刷新后重试。"
            }
            require(row.uuidOrNull("parentId") == category.parentId && row.uuidOrNull("parentId") == parent?.id) {
                "分类层级已变化，请刷新后重试。"
            }
            val direction = Direction.valueOf(row.getString("direction"))
            val nextExcludeBudget = excludeBudget ?: row.canonicalBoolean("excludeBudget")
            require(!nextExcludeBudget || direction == Direction.EXPENSE) { "收入分类不能排除预算。" }
            if (row.canonicalBoolean("excludeBudget") != nextExcludeBudget) {
                requireNoBudgetExclusionDependencies(tables, category.id)
            }
            val ledgerRow = requireLedger(tables, ledger.id, ledger.localRevision)
            requireNoPendingLedger()
            val parentRow = parent?.let { requireParent(tables, it, it.localRevision, ledger.id, direction) }
            requireUniqueSibling(tables.getJSONArray("categories").objects(), ledger.id, direction, parent?.id, cleaned, category.id)
            val changes = JSONObject()
                .put("ledgerId", row.getString("ledgerId"))
                .put("direction", row.getString("direction"))
                .put("parentId", row.opt("parentId") ?: JSONObject.NULL)
                .put("name", cleaned)
                .put("iconRef", row.opt("iconRef") ?: JSONObject.NULL)
                .put("color", if (updateColor) normalizedColor ?: JSONObject.NULL else row.opt("color") ?: JSONObject.NULL)
                .put("sortOrder", row.getLong("sortOrder"))
                .put("archivedAt", row.opt("archivedAt") ?: JSONObject.NULL)
                .put("excludeBudget", nextExcludeBudget)
            val versions = JSONObject()
                .put("category:${category.id}", category.localRevision)
                .put("ledger:${ledger.id}", ledgerRow.getLong("serverRevision"))
            parent?.let { versions.put("category:${it.id}", requireNotNull(parentRow).getLong("serverRevision")) }
            mutations.enqueueCanonicalOnly(deviceId, "category", category.id, "metadata.update",
                JSONObject().put("changes", changes), versions, rejectPendingForAggregate = false)
        }
    }

    /** Archive captures every live child; restore requires an active ledger and parent. */
    fun archive(
        deviceId: UUID, category: FinanceCategory, archived: Boolean, ledger: FinanceLedger,
        parent: FinanceCategory?, children: List<FinanceCategory>,
    ) {
        require(category.localRevision > 0) { "分类需要先获得服务器确认" }
        database.runInTransaction {
            val tables = SnapshotImporter.canonicalTables(database)
            val row = findCategory(tables, category.id)
            require(row.getLong("serverRevision") == category.localRevision && row.isNull("archivedAt") == archived) {
                "分类版本或归档状态已变化，请刷新后重新操作。"
            }
            require(row.optString("ledgerId") == ledger.id.toString() && row.uuidOrNull("parentId") == parent?.id) {
                "分类归属已变化，请刷新后重试。"
            }
            val versions = JSONObject().put("category:${category.id}", category.localRevision)
            if (archived) {
                require(children.size <= 2047 && children.map { it.id }.distinct().size == children.size) { "子分类列表无效或过大" }
                val members = tables.getJSONArray("categories").objects().filter {
                    it.isNull("deletedAt") && it.uuidOrNull("parentId") == category.id
                }
                require(members.map { it.getString("id") }.toSet() == children.map { it.id.toString() }.toSet()) {
                    "子分类已变化，请刷新后重试。"
                }
                val revisions = children.associate { it.id.toString() to it.localRevision }
                members.forEach { member ->
                    val childId = member.getString("id")
                    val expected = requireNotNull(revisions[childId]) { "子分类已变化，请刷新后重试。" }
                    require(expected > 0 && member.getLong("serverRevision") == expected) { "子分类已变化，请刷新后重试。" }
                    versions.put("category:$childId", member.getLong("serverRevision"))
                }
            } else {
                val ledgerRow = requireLedger(tables, ledger.id, ledger.localRevision)
                versions.put("ledger:${ledger.id}", ledgerRow.getLong("serverRevision"))
                parent?.let { p ->
                    val parentRow = findCategory(tables, p.id)
                    require(parentRow.isNull("archivedAt") && parentRow.optString("ledgerId") == ledger.id.toString() &&
                        p.localRevision > 0 && parentRow.getLong("serverRevision") == p.localRevision) {
                        "上级分类已变化或已归档，请刷新后重试。"
                    }
                    versions.put("category:${p.id}", parentRow.getLong("serverRevision"))
                }
            }
            requireNoPendingCategoryOrLedger()
            mutations.enqueueCanonicalOnly(deviceId, "category", category.id, "metadata.archive",
                JSONObject().put("archived", archived).put("includeInAssets", false), versions)
        }
    }

    private companion object {
        const val MAX_CATEGORY_DELETE_VERSIONS = 2048
    }

    data class CategoryDeletePreview(
        val ownerId: String,
        val epochId: String,
        val source: FinanceCategory,
        val target: FinanceCategory,
        val recordCount: Int,
        val templateCount: Int,
        val budgetCount: Int,
        val referenceCount: Int,
        internal val records: List<VersionedDependency>,
        internal val templates: List<TemplateDependency>,
        internal val claims: List<VersionedDependency>,
        internal val refunds: List<VersionedDependency>,
        internal val budgets: List<VersionedDependency>,
        internal val statContributions: List<VersionedDependency>,
        internal val budgetContributions: List<VersionedDependency>,
        internal val budgetMembership: List<BudgetMembership>,
        internal val targetBudgets: List<VersionedDependency>,
        internal val targetBudgetMembership: List<BudgetMembership>,
        internal val categoryReferences: List<VersionedDependency>,
    )

    data class VersionedDependency(val id: UUID, val revision: Long)
    data class TemplateDependency(
        val id: UUID,
        val revision: Long,
        val categoryIds: List<UUID>,
    )
    data class BudgetMembership(
        val budgetId: UUID,
        val configRows: List<String>,
        val entryRows: List<String>,
    )

    /** Captures the complete backend MIGRATE_RECORDS dependency set without predicting money. */
    fun previewMigrateDelete(source: FinanceCategory, target: FinanceCategory): CategoryDeletePreview =
        database.runInTransaction(java.util.concurrent.Callable {
            val tables = SnapshotImporter.canonicalTables(database)
            val sourceRow = findCategory(tables, source.id)
            val targetRow = findCategory(tables, target.id)
            if (source.id == target.id || source.localRevision <= 0 || target.localRevision <= 0 ||
                sourceRow.getLong("serverRevision") != source.localRevision || targetRow.getLong("serverRevision") != target.localRevision) {
                throw FinanceValidationException("分类或迁移目标已变化，请刷新后重试。")
            }
            if (!sourceRow.isNull("archivedAt") || !targetRow.isNull("archivedAt") ||
                sourceRow.optString("ledgerId") != source.ledgerId.toString() || targetRow.optString("ledgerId") != target.ledgerId.toString() ||
                sourceRow.optString("direction") != source.direction.name || targetRow.optString("direction") != target.direction.name ||
                sourceRow.uuidOrNull("parentId") != source.parentId || targetRow.uuidOrNull("parentId") != target.parentId) {
                throw FinanceValidationException("分类或迁移目标归属已变化，请刷新后重新确认。")
            }
            require(sourceRow.getString("ledgerId") == targetRow.getString("ledgerId") &&
                sourceRow.getString("direction") == targetRow.getString("direction")) {
                "迁移目标必须与原分类处于相同账本和方向。"
            }
            fun effectivelyExcluded(row: JSONObject): Boolean {
                val parentId = row.uuidOrNull("parentId") ?: return row.canonicalBoolean("excludeBudget")
                val parent = findCategory(tables, parentId)
                if (!parent.isNull("archivedAt")) throw FinanceValidationException("分类父级已归档，无法安全迁移。")
                return row.canonicalBoolean("excludeBudget") || parent.canonicalBoolean("excludeBudget")
            }
            if (effectivelyExcluded(sourceRow) != effectivelyExcluded(targetRow)) {
                throw FinanceValidationException("原分类与迁移目标的预算排除设置不一致，服务端无法安全迁移。")
            }
            val categories = tables.getJSONArray("categories").objects()
            if (categories.any { it.isNull("deletedAt") && it.uuidOrNull("parentId") == source.id }) {
                throw FinanceValidationException("分类仍有子分类，无法迁移删除。")
            }
            val records = tables.getJSONArray("records").objects().filter {
                it.isNull("deletedAt") && it.uuidOrNull("categoryId") == source.id
            }.map { row ->
                if (row.optString("ledgerId") != sourceRow.optString("ledgerId")) {
                    throw FinanceValidationException("分类关联账单账本与分类不一致，无法安全迁移。")
                }
                versioned(row)
            }.sortedBy { it.id.toString() }
            val templatesRows = tables.getJSONArray("record_templates").objects().filter {
                it.isNull("deletedAt") && listOf("categoryId", "feeCategoryId", "bonusCategoryId").any { key -> it.uuidOrNull(key) == source.id }
            }
            val templates = templatesRows.map { row ->
                val referenced = listOf("categoryId", "feeCategoryId", "bonusCategoryId")
                    .mapNotNull(row::uuidOrNull).distinct().sortedBy(UUID::toString)
                require(row.optString("ledgerId") == sourceRow.optString("ledgerId")) { "关联模板账本与分类不一致，无法安全迁移。" }
                referenced.forEach { categoryId ->
                    val category = findCategory(tables, categoryId)
                    require(category.isNull("archivedAt") && category.optString("ledgerId") == sourceRow.optString("ledgerId")) {
                        "迁移模板含已归档分类或跨账本分类引用，无法安全迁移。"
                    }
                }
                TemplateDependency(UUID.fromString(row.getString("id")), row.getLong("serverRevision"), referenced)
            }.sortedBy { it.id.toString() }
            val claims = tables.getJSONArray("claim_allocations").objects().filter {
                it.uuidOrNull("expenseCategoryId") == source.id
            }.map { row -> require(row.isNull("deletedAt")) { "分类含已删除或不可安全迁移的理赔关联。" }; versioned(row) }
                .sortedBy { it.id.toString() }
            val refunds = tables.getJSONArray("refunds").objects().filter {
                it.uuidOrNull("expenseCategoryId") == source.id
            }.map { row -> require(row.isNull("deletedAt")) { "分类含已删除或不可安全迁移的退款关联。" }; versioned(row) }
                .sortedBy { it.id.toString() }
            val sourceEntries = tables.getJSONArray("budget_entries").objects().filter { it.uuidOrNull("categoryId") == source.id }
            val allBudgetEntries = tables.getJSONArray("budget_entries").objects()
            val targetEntryKeys = allBudgetEntries.filter { it.uuidOrNull("categoryId") == target.id }
                .map { "${it.optString("configId")}:${it.optString("categoryId")}" }.toSet()
            if (sourceEntries.any { "${it.optString("configId")}:${target.id}" in targetEntryKeys }) {
                throw FinanceValidationException("迁移会造成同一预算配置中的分类重复。")
            }
            val configs = tables.getJSONArray("budget_configs").objects().associateBy { it.optString("id") }
            val budgets = sourceEntries.map { entry ->
                val config = configs[entry.optString("configId")] ?: error("分类关联预算配置不可用，无法安全迁移。")
                UUID.fromString(config.getString("budgetId"))
            }.distinct().sortedBy(UUID::toString).map { id ->
                val row = tables.getJSONArray("budgets").objects().singleOrNull { it.optString("id") == id.toString() && it.isNull("deletedAt") }
                    ?: error("分类关联预算已不可用，无法安全迁移。")
                versioned(row)
            }
            fun budgetMembershipFor(categoryId: UUID, budgetRows: List<VersionedDependency>) = budgetRows.map { budget ->
                val configRows = tables.getJSONArray("budget_configs").objects().filter { it.optString("budgetId") == budget.id.toString() }
                val configIds = configRows.map { UUID.fromString(it.getString("id")) }.sortedBy(UUID::toString)
                val entryRows = tables.getJSONArray("budget_entries").objects().filter {
                    it.optString("configId") in configIds.map(UUID::toString) && it.uuidOrNull("categoryId") == categoryId
                }
                BudgetMembership(budget.id, configRows.map { it.toString() }.sorted(), entryRows.map { it.toString() }.sorted())
            }
            val budgetMembership = budgetMembershipFor(source.id, budgets)
            val targetBudgetIds = allBudgetEntries.filter { it.uuidOrNull("categoryId") == target.id }.map { entry ->
                val config = configs[entry.optString("configId")] ?: error("目标分类关联预算配置不可用，无法安全迁移。")
                UUID.fromString(config.getString("budgetId"))
            }.distinct().sortedBy(UUID::toString).map { id ->
                val row = tables.getJSONArray("budgets").objects().singleOrNull { it.optString("id") == id.toString() && it.isNull("deletedAt") }
                    ?: error("目标分类关联预算已不可用，无法安全迁移。")
                versioned(row)
            }
            val targetBudgetMembership = budgetMembershipFor(target.id, targetBudgetIds)
            val statContributions = contributions(tables, "stat_contributions", source.id)
            val budgetContributions = contributions(tables, "budget_contributions", source.id)
            val categoryRefs = (listOf(source.id, target.id) + listOfNotNull(sourceRow.uuidOrNull("parentId"), targetRow.uuidOrNull("parentId")) +
                templates.flatMap { it.categoryIds }).distinct().map { id ->
                val row = findCategory(tables, id)
                require(row.isNull("archivedAt") || id in setOf(source.id, target.id)) { "迁移模板引用了已归档分类。" }
                versioned(row)
            }.sortedBy { it.id.toString() }
            val versionKeys = categoryRefs.map { "category:${it.id}" } + records.map { "record:${it.id}" } +
                templates.map { "record_template:${it.id}" } + claims.map { "claim_allocation:${it.id}" } +
                refunds.map { "refund:${it.id}" } + (budgets + targetBudgetIds).map { "budget:${it.id}" }
            if (versionKeys.distinct().size > MAX_CATEGORY_DELETE_VERSIONS)
                throw FinanceValidationException("分类关联过多，无法安全迁移删除。")
            val identity = database.identity().read() ?: error("Dataset identity missing")
            CategoryDeletePreview(identity.ownerId, identity.epochId, financeCategory(sourceRow), financeCategory(targetRow),
                records.size, templates.size, budgets.size,
                records.size + templates.size + budgets.size + claims.size + refunds.size + statContributions.size + budgetContributions.size,
                records, templates, claims, refunds, budgets, statContributions, budgetContributions, budgetMembership,
                targetBudgetIds, targetBudgetMembership, categoryRefs)
        })

    /** Rechecks the captured dependency set transactionally, then queues canonical-only deletion. */
    fun deleteMigrating(deviceId: UUID, preview: CategoryDeletePreview) {
        require(preview.source.localRevision > 0 && preview.target.localRevision > 0 && preview.source.id != preview.target.id)
        if (preview.recordCount != preview.records.size || preview.templateCount != preview.templates.size ||
            preview.budgetCount != preview.budgets.size) {
            throw FinanceValidationException("分类删除预览无效，请重新确认。")
        }
        database.runInTransaction {
            requirePreviewIdentity(preview.ownerId, preview.epochId)
            val tables = SnapshotImporter.canonicalTables(database)
            val source = findCategory(tables, preview.source.id)
            val target = findCategory(tables, preview.target.id)
            if (source.getLong("serverRevision") != preview.source.localRevision || target.getLong("serverRevision") != preview.target.localRevision ||
                !source.isNull("archivedAt") || !target.isNull("archivedAt") || source.getString("ledgerId") != target.getString("ledgerId") ||
                source.getString("direction") != target.getString("direction") || source.uuidOrNull("parentId") != preview.source.parentId ||
                target.uuidOrNull("parentId") != preview.target.parentId) {
                throw FinanceValidationException("分类或迁移目标已变化，请刷新后重新确认删除。")
            }
            if (tables.getJSONArray("categories").objects().any { it.isNull("deletedAt") && it.uuidOrNull("parentId") == preview.source.id }) {
                throw FinanceValidationException("分类仍有子分类，无法迁移删除。")
            }
            requireVersionedSet(tables, "records", preview.records, "分类关联账单") { it.uuidOrNull("categoryId") == preview.source.id && it.isNull("deletedAt") }
            val actualTemplates = tables.getJSONArray("record_templates").objects().filter { it.isNull("deletedAt") &&
                listOf("categoryId", "feeCategoryId", "bonusCategoryId").any { key -> it.uuidOrNull(key) == preview.source.id } }
            if (actualTemplates.map { it.getString("id") }.toSet() != preview.templates.map { it.id.toString() }.toSet()) {
                throw FinanceValidationException("分类关联模板已变化，请重新确认。")
            }
            preview.templates.forEach { dep ->
                val row = actualTemplates.single { it.getString("id") == dep.id.toString() }
                if (row.getLong("serverRevision") != dep.revision || listOf("categoryId", "feeCategoryId", "bonusCategoryId")
                        .mapNotNull(row::uuidOrNull).distinct().sortedBy(UUID::toString) != dep.categoryIds) {
                    throw FinanceValidationException("分类关联模板已变化，请重新确认。")
                }
            }
            requireVersionedSet(tables, "claim_allocations", preview.claims, "理赔关联") { it.uuidOrNull("expenseCategoryId") == preview.source.id }
            requireVersionedSet(tables, "refunds", preview.refunds, "退款关联") { it.uuidOrNull("expenseCategoryId") == preview.source.id }
            requireVersionedSet(tables, "budgets", preview.budgets, "分类关联预算") { row ->
                val ids = tables.getJSONArray("budget_configs").objects().filter { it.optString("budgetId") == row.optString("id") }.map { it.getString("id") }.toSet()
                tables.getJSONArray("budget_entries").objects().any { it.optString("configId") in ids && it.uuidOrNull("categoryId") == preview.source.id }
            }
            require(contributions(tables, "stat_contributions", preview.source.id) == preview.statContributions &&
                contributions(tables, "budget_contributions", preview.source.id) == preview.budgetContributions) { "分类统计关联已变化，请刷新后重新确认。" }
            requireBudgetMembership(tables, preview.budgetMembership, preview.source.id)
            requireBudgetMembership(tables, preview.targetBudgetMembership, preview.target.id)
            val targetBudgetIds = tables.getJSONArray("budget_entries").objects().filter { it.uuidOrNull("categoryId") == preview.target.id }
                .map { entry ->
                    val config = tables.getJSONArray("budget_configs").objects().singleOrNull { it.optString("id") == entry.optString("configId") }
                        ?: throw FinanceValidationException("目标分类关联预算配置已变化，请重新确认。")
                    config.optString("budgetId")
                }.toSet()
            if (targetBudgetIds != preview.targetBudgets.map { it.id.toString() }.toSet()) {
                throw FinanceValidationException("目标分类预算关联已变化，请刷新后重新确认。")
            }
            requireVersionedSet(tables, "budgets", preview.targetBudgets, "目标分类关联预算") { row ->
                val configIds = tables.getJSONArray("budget_configs").objects().filter { it.optString("budgetId") == row.optString("id") }
                    .map { it.getString("id") }.toSet()
                tables.getJSONArray("budget_entries").objects().any { it.optString("configId") in configIds && it.uuidOrNull("categoryId") == preview.target.id }
            }
            val targetBudgetKeys = tables.getJSONArray("budget_entries").objects().filter { it.uuidOrNull("categoryId") == preview.target.id }
                .map { "${it.optString("configId")}:${it.optString("categoryId")}" }.toSet()
            if (tables.getJSONArray("budget_entries").objects().any {
                    it.uuidOrNull("categoryId") == preview.source.id && "${it.optString("configId")}:${preview.target.id}" in targetBudgetKeys
                }) {
                throw FinanceValidationException("迁移会造成同一预算配置中的分类重复。")
            }
            preview.categoryReferences.forEach { dep ->
                val row = findCategory(tables, dep.id)
                require(row.getLong("serverRevision") == dep.revision) { "分类引用已变化，请刷新后重新确认。" }
            }
            requireNoPendingCategoryOrLedger()
            requireNoPendingCategoryDeleteDependencies(preview)
            val versions = JSONObject().put("category:${preview.source.id}", preview.source.localRevision)
                .put("category:${preview.target.id}", preview.target.localRevision)
            preview.categoryReferences.forEach { versions.put("category:${it.id}", it.revision) }
            preview.records.forEach { versions.put("record:${it.id}", it.revision) }
            preview.templates.forEach { versions.put("record_template:${it.id}", it.revision) }
            preview.claims.forEach { versions.put("claim_allocation:${it.id}", it.revision) }
            preview.refunds.forEach { versions.put("refund:${it.id}", it.revision) }
            preview.budgets.forEach { versions.put("budget:${it.id}", it.revision) }
            preview.targetBudgets.forEach { versions.put("budget:${it.id}", it.revision) }
            require(versions.length() <= MAX_CATEGORY_DELETE_VERSIONS) { "分类关联过多，无法安全迁移删除。" }
            mutations.enqueueCanonicalOnly(deviceId, "category", preview.source.id, "metadata.delete",
                JSONObject().put("strategy", "MIGRATE_RECORDS").put("targetId", preview.target.id.toString()), versions)
        }
    }

    /** Moves an unused leaf category to an active ledger, direction and optional root parent. */
    fun move(
        deviceId: UUID, category: FinanceCategory, targetLedger: FinanceLedger, direction: Direction, parent: FinanceCategory?,
        sourceParent: FinanceCategory?,
    ) {
        require(category.localRevision > 0) { "分类需要先获得服务器确认" }
        database.runInTransaction {
            val tables = SnapshotImporter.canonicalTables(database)
            val row = findCategory(tables, category.id)
            require(row.getLong("serverRevision") == category.localRevision) { "分类已变化，请刷新后重新移动。" }
            require(row.getString("ledgerId") == category.ledgerId.toString() &&
                row.getString("direction") == category.direction.name && row.uuidOrNull("parentId") == category.parentId) {
                "分类归属已变化，请刷新后重试。"
            }
            val targetLedgerRow = requireLedger(tables, targetLedger.id, targetLedger.localRevision)
            val targetParentRow = parent?.let { requireParent(tables, it, it.localRevision, targetLedger.id, direction) }
            require(parent?.id != category.id) { "不能将分类移动到自身之下。" }

            val categories = tables.getJSONArray("categories").objects()
            require(categories.none { it.isNull("deletedAt") && it.uuidOrNull("parentId") == category.id }) {
                "仅支持移动没有子分类的分类。"
            }
            val records = tables.getJSONArray("records").objects()
            val templates = tables.getJSONArray("record_templates").objects()
            val claimAllocations = tables.getJSONArray("claim_allocations").objects()
            val refunds = tables.getJSONArray("refunds").objects()
            val budgets = tables.getJSONArray("budget_entries").objects()
            require(records.none { it.uuidOrNull("categoryId") == category.id } &&
                budgets.none { it.uuidOrNull("categoryId") == category.id } &&
                templates.none { it.uuidOrNull("categoryId") == category.id || it.uuidOrNull("feeCategoryId") == category.id ||
                    it.uuidOrNull("bonusCategoryId") == category.id } &&
                claimAllocations.none { it.uuidOrNull("expenseCategoryId") == category.id } &&
                refunds.none { it.uuidOrNull("expenseCategoryId") == category.id }) {
                "分类仍被记录、预算、模板、理赔或退款引用，不能移动。"
            }
            val sourceParentId = row.uuidOrNull("parentId")
            require(sourceParentId == sourceParent?.id) { "原上级分类已变化，请刷新后重试。" }
            val sourceParentRow = sourceParent?.let {
                requireParent(tables, it, it.localRevision, category.ledgerId, category.direction)
            }
            requireUniqueSibling(categories, targetLedger.id, direction, parent?.id, row.getString("name"), category.id)
            require(row.getString("ledgerId") != targetLedger.id.toString() || row.getString("direction") != direction.name ||
                sourceParentId != parent?.id) { "分类已经位于目标层级。" }
            requireNoPendingCategoryOrLedger()

            val versions = JSONObject()
                .put("category:${category.id}", category.localRevision)
                .put("ledger:${targetLedger.id}", targetLedgerRow.getLong("serverRevision"))
            sourceParentId?.let { versions.put("category:$it", requireNotNull(sourceParentRow).getLong("serverRevision")) }
            parent?.let { versions.put("category:${it.id}", requireNotNull(targetParentRow).getLong("serverRevision")) }
            val payload = JSONObject()
                .put("targetLedgerId", targetLedger.id.toString())
                .put("newDirection", direction.name)
                .put("newParentId", parent?.id?.toString() ?: JSONObject.NULL)
            mutations.enqueueCanonicalOnly(deviceId, "category", category.id, "category.move", payload, versions)
        }
    }

    fun reorder(deviceId: UUID, categories: List<FinanceCategory>) {
        require(categories.size in 1..1000 && categories.map { it.id }.distinct().size == categories.size) { "分类排序列表无效" }
        val first = categories.first()
        require(categories.all { it.ledgerId == first.ledgerId && it.direction == first.direction && it.parentId == first.parentId }) {
            "只能对同账本、同方向、同层级分类排序"
        }
        database.runInTransaction {
            val rows = SnapshotImporter.canonicalTables(database).getJSONArray("categories").objects().filter {
                it.isNull("deletedAt") && it.getString("ledgerId") == first.ledgerId.toString() &&
                    it.getString("direction") == first.direction.name && it.uuidOrNull("parentId") == first.parentId
            }
            require(rows.map { it.getString("id") }.toSet() == categories.map { it.id.toString() }.toSet()) { "分类列表已变化，请刷新后重试。" }
            val versions = JSONObject()
            categories.forEach { category ->
                require(category.localRevision > 0 && rows.single { it.getString("id") == category.id.toString() }
                    .getLong("serverRevision") == category.localRevision) { "分类版本已变化，请刷新后重试。" }
                versions.put("category:${category.id}", category.localRevision)
            }
            requireNoPendingCategoryOrLedger()
            mutations.enqueueCanonicalOnly(deviceId, "category", first.id, "metadata.reorder",
                JSONObject().put("orderedIds", JSONArray(categories.map { it.id.toString() })), versions)
        }
    }

    private fun requireNoBudgetExclusionDependencies(tables: JSONObject, categoryId: UUID) {
        fun rows(table: String) = try {
            tables.getJSONArray(table).objects()
        } catch (_: Exception) {
            throw FinanceValidationException("分类依赖资料不完整，无法安全修改预算排除设置。")
        }
        val used = rows("records").any { it.uuidOrNull("categoryId") == categoryId } ||
            rows("categories").any { it.uuidOrNull("parentId") == categoryId } ||
            rows("budget_entries").any { it.uuidOrNull("categoryId") == categoryId } ||
            rows("budgets").any { root ->
                root.optJSONArray("configs")?.objects().orEmpty().any { config ->
                    config.getJSONArray("entries").objects().any { it.uuidOrNull("categoryId") == categoryId }
                }
            } ||
            rows("record_templates").any {
                listOf("categoryId", "feeCategoryId", "bonusCategoryId").any { key -> it.uuidOrNull(key) == categoryId }
            } || rows("claim_allocations").any { it.uuidOrNull("expenseCategoryId") == categoryId } ||
            rows("refunds").any { it.uuidOrNull("expenseCategoryId") == categoryId }
        require(!used) { "分类仍被记录、子分类、预算、模板、理赔或退款引用，不能修改预算排除设置。" }
    }

    private fun requireNoPendingCategoryOrLedger() {
        val identity = database.identity().read() ?: error("Dataset identity missing")
        if (database.accountMutations().intentsInOrder(identity.ownerId, identity.epochId).any {
                (it.aggregateType == "category" || it.aggregateType == "ledger") && it.state in setOf("QUEUED", "AMBIGUOUS", "ACKED")
            }) {
            throw FinanceValidationException("分类或账本仍有操作等待服务器确认；请先同步原操作。")
        }
    }

    private fun requireNoPendingCategoryDeleteDependencies(preview: CategoryDeletePreview) {
        val identity = database.identity().read() ?: error("Dataset identity missing")
        val recordKeys = preview.records.map { "record:${it.id}" }.toSet()
        val templateKeys = preview.templates.map { "record_template:${it.id}" }.toSet()
        val claimKeys = preview.claims.map { "claim_allocation:${it.id}" }.toSet()
        val refundKeys = preview.refunds.map { "refund:${it.id}" }.toSet()
        val budgetKeys = (preview.budgets + preview.targetBudgets).map { "budget:${it.id}" }.toSet()
        val allKeys = recordKeys + templateKeys + claimKeys + refundKeys + budgetKeys +
            preview.categoryReferences.map { "category:${it.id}" }.toSet()
        check(database.accountMutations().intentsInOrder(identity.ownerId, identity.epochId).none { intent ->
            if (intent.state !in setOf("QUEUED", "AMBIGUOUS", "ACKED")) false
            else {
                val outbox = database.typedOutbox().byOpId(intent.opId) ?: error("分类依赖操作队列不完整")
                check(outbox.ownerId == intent.ownerId && outbox.epochId == intent.epochId &&
                    outbox.aggregateType == intent.aggregateType && outbox.aggregateId == intent.aggregateId &&
                    outbox.commandType == intent.commandType && outbox.payloadJson == intent.payloadJson) { "分类依赖操作队列身份不匹配" }
                val keys = JSONObject(outbox.baseVersionsJson).keys().asSequence().toSet()
                (intent.aggregateType == "record" && "record:${intent.aggregateId}" in recordKeys) ||
                    (intent.aggregateType == "record_template" && "record_template:${intent.aggregateId}" in templateKeys) ||
                    (intent.aggregateType == "claim_allocation" && "claim_allocation:${intent.aggregateId}" in claimKeys) ||
                    (intent.aggregateType == "refund" && "refund:${intent.aggregateId}" in refundKeys) ||
                    (intent.aggregateType == "budget" && "budget:${intent.aggregateId}" in budgetKeys) || keys.any { it in allKeys }
            }
        }) { "分类关联账单、模板或资金资料仍有操作等待确认；请先同步原操作。" }
    }

    private fun requirePreviewIdentity(ownerId: String, epochId: String) {
        val identity = database.identity().read() ?: error("Dataset identity missing")
        require(identity.ownerId == ownerId && identity.epochId == epochId) { "账号或账本已变化，请重新确认删除。" }
    }

    private fun versioned(row: JSONObject) = VersionedDependency(UUID.fromString(row.getString("id")), row.getLong("serverRevision"))

    private fun contributions(tables: JSONObject, table: String, categoryId: UUID): List<VersionedDependency> =
        tables.getJSONArray(table).objects().filter { it.uuidOrNull("categoryId") == categoryId }
            .map(::versioned).sortedBy { it.id.toString() }

    private fun requireVersionedSet(
        tables: JSONObject, table: String, expected: List<VersionedDependency>, label: String, predicate: (JSONObject) -> Boolean,
    ) {
        require(expected.map { it.id }.distinct().size == expected.size && expected.all { it.revision > 0 }) { "${label}预览无效" }
        val actual = tables.getJSONArray(table).objects().filter(predicate).associateBy { UUID.fromString(it.getString("id")) }
        require(actual.size == expected.size && actual.keys == expected.map { it.id }.toSet() &&
            expected.all { actual[it.id]?.getLong("serverRevision") == it.revision }) { "${label}已变化，请刷新后重新确认删除。" }
    }

    private fun requireBudgetMembership(
        tables: JSONObject, membership: List<BudgetMembership>, categoryId: UUID,
    ) {
        val configs = tables.getJSONArray("budget_configs").objects()
        val entries = tables.getJSONArray("budget_entries").objects()
        membership.forEach { expected ->
            val configRows = configs.filter { it.optString("budgetId") == expected.budgetId.toString() }
            val configIds = configRows.map { it.getString("id") }.toSet()
            val entryRows = entries.filter { it.optString("configId") in configIds && it.uuidOrNull("categoryId") == categoryId }
            require(configRows.map { it.toString() }.sorted() == expected.configRows &&
                entryRows.map { it.toString() }.sorted() == expected.entryRows) {
                "预算配置或分类预算关联已变化，请刷新后重新确认删除。"
            }
        }
    }

    private fun requireNoPendingLedger() {
        val identity = database.identity().read() ?: error("Dataset identity missing")
        require(database.accountMutations().intentsInOrder(identity.ownerId, identity.epochId).none {
            it.aggregateType == "ledger" && it.state in setOf("QUEUED", "AMBIGUOUS", "ACKED")
        }) { "账本仍有操作等待服务器确认；请先同步原操作。" }
    }

    private fun projectedTables(selectedTables: Set<String>? = null): JSONObject {
        val identity = database.identity().read() ?: error("Dataset identity missing")
        val tables = SnapshotImporter.canonicalTables(database, selectedTables)
        val dao = database.accountMutations()
        CanonicalAccountRepository.applyPredictions(tables, dao.predictions(identity.ownerId, identity.epochId).filter { it.aggregateType == "category" || selectedTables == null },
            dao.intentsInOrder(identity.ownerId, identity.epochId), UUID.fromString(identity.ownerId), UUID.fromString(identity.epochId))
        return tables
    }


    private fun requireLedger(tables: JSONObject, id: UUID, expectedRevision: Long): JSONObject {
        val row = tables.getJSONArray("ledgers").objects().singleOrNull { it.optString("id") == id.toString() && it.isNull("deletedAt") }
            ?: throw IllegalStateException("账本已不可用，请先同步。")
        require(row.isNull("archivedAt")) { "已归档账本不能维护分类。" }
        require(expectedRevision > 0 && row.getLong("serverRevision") == expectedRevision) { "账本已变化，请刷新后重新操作。" }
        return row
    }

    /** Validates a caller-supplied parent against the canonical root category it must be. */
    private fun requireParent(
        tables: JSONObject, parent: FinanceCategory, expectedRevision: Long, ledgerId: UUID, direction: Direction,
    ): JSONObject {
        val row = findCategory(tables, parent.id)
        require(row.isNull("archivedAt")) { "上级分类已归档，请先同步。" }
        require(row.optString("ledgerId") == ledgerId.toString() && row.optString("direction") == direction.name && row.uuidOrNull("parentId") == null) {
            "上级分类必须处于相同账本与方向，且自身为顶层分类。"
        }
        require(expectedRevision >= 0 && row.getLong("serverRevision") == expectedRevision && row.getString("name") == parent.name) { "上级分类已变化，请刷新后重新选择。" }
        return row
    }

    private fun findCategory(tables: JSONObject, id: UUID): JSONObject =
        tables.getJSONArray("categories").objects().singleOrNull { it.optString("id") == id.toString() && it.isNull("deletedAt") }
            ?: throw IllegalStateException("分类已不可用，请先同步。")

    private fun financeCategory(row: JSONObject) = FinanceCategory(
        UUID.fromString(row.getString("id")), row.getString("name"), Direction.valueOf(row.getString("direction")),
        row.uuidOrNull("parentId"), null, row.getLong("sortOrder"),
        row.getLong("serverRevision"), UUID.fromString(row.getString("ledgerId")),
        color = row.optString("color").takeIf { !row.isNull("color") }, usesCanonicalColor = true,
        excludeBudget = row.canonicalBoolean("excludeBudget"),
        purpose = row.optString("purpose").takeIf { !row.isNull("purpose") },
    )

    /** Mirrors the server's unique sibling index (ledger, direction, parent, name) over live rows. */
    private fun requireUniqueSibling(
        categories: List<JSONObject>, ledgerId: UUID, direction: Direction, parentId: UUID?, name: String, exceptId: UUID?,
    ) {
        require(categories.none {
            it.isNull("deletedAt") && it.optString("ledgerId") == ledgerId.toString() && it.optString("direction") == direction.name &&
                it.uuidOrNull("parentId") == parentId && it.getString("name") == name &&
                (exceptId == null || it.optString("id") != exceptId.toString())
        }) { "同层级已有同名分类" }
    }

    private fun nextSortOrder(categories: List<JSONObject>, ledgerId: UUID, direction: Direction, parentId: UUID?): Long {
        val maxSort = categories.filter {
            it.isNull("deletedAt") && it.optString("ledgerId") == ledgerId.toString() && it.optString("direction") == direction.name &&
                it.uuidOrNull("parentId") == parentId
        }.maxOfOrNull { it.getLong("sortOrder") } ?: -1L
        require(maxSort < Long.MAX_VALUE) { "分类排序值超出范围" }
        return maxSort + 1
    }

    private fun requireName(name: String): String {
        val cleaned = name.trim()
        require(cleaned.isNotEmpty() && cleaned.codePointCount(0, cleaned.length) <= 100 && cleaned.none { it == '\u0000' }) {
            "分类名称需为1到100个字符"
        }
        return cleaned
    }
}

private fun JSONArray.objects() = List(length()) { getJSONObject(it) }
private fun JSONObject.uuidOrNull(key: String): UUID? = if (isNull(key)) null else UUID.fromString(getString(key))
