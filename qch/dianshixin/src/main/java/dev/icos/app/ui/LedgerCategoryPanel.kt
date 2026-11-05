package dev.icos.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.icos.app.data.local.FinanceCategory
import dev.icos.app.data.local.FinanceLedger
import dev.icos.app.data.local.FinanceSnapshot
import dev.icos.app.domain.Direction
import java.util.UUID

@Composable
internal fun LedgerCategoryPanel(
    snapshot: FinanceSnapshot,
    model: FinanceViewModel,
    enabled: Boolean,
    showLedgerManagement: Boolean,
    modifier: Modifier = Modifier,
) {
    var createLedger by remember { mutableStateOf(false) }
    var copyLedger by remember { mutableStateOf(false) }
    var copySource by remember { mutableStateOf<FinanceLedger?>(null) }
    val accountMode = model.state.accountOwner != null
    val localCategoryEnabled = enabled && !model.categorySaving
    val categoryEnabled = localCategoryEnabled && (!accountMode ||
        !model.state.accountMutationPending && !model.state.syncPaused)
    val ledgerEnabled = categoryEnabled
    var ledgerName by remember { mutableStateOf("") }
    var ledgerCurrency by remember { mutableStateOf("CNY") }
    var renameLedger by remember { mutableStateOf<FinanceLedger?>(null) }
    var renameValue by remember { mutableStateOf("") }
    var deleteLedger by remember { mutableStateOf<FinanceLedger?>(null) }
    var deleteName by remember { mutableStateOf("") }
    var createCategory by remember { mutableStateOf(false) }
    var categoryName by remember { mutableStateOf("") }
    var categoryDirection by remember { mutableStateOf(Direction.EXPENSE) }
    var categoryParent by remember { mutableStateOf<UUID?>(null) }
    var categoryParentRevision by remember { mutableStateOf<Long?>(null) }
    var categoryExcludeBudget by remember { mutableStateOf(false) }
    var budgetCategory by remember { mutableStateOf<FinanceCategory?>(null) }
    var budgetCategoryLedger by remember { mutableStateOf<FinanceLedger?>(null) }
    var budgetCategoryParent by remember { mutableStateOf<FinanceCategory?>(null) }
    var budgetCategoryExcluded by remember { mutableStateOf(false) }
    var categoryLedger by remember { mutableStateOf<FinanceLedger?>(null) }
    var renameCategory by remember { mutableStateOf<FinanceCategory?>(null) }
    var renameCategoryValue by remember { mutableStateOf("") }
    var moveCategory by remember { mutableStateOf<FinanceCategory?>(null) }
    var moveLedgerId by remember { mutableStateOf(snapshot.ledgerId) }
    var moveDirection by remember { mutableStateOf(Direction.EXPENSE) }
    var moveParentId by remember { mutableStateOf<UUID?>(null) }
    var moveLedgerRevision by remember { mutableStateOf<Long?>(null) }
    var moveParentRevision by remember { mutableStateOf<Long?>(null) }
    var moveSourceParentRevision by remember { mutableStateOf<Long?>(null) }
    var deleteCategory by remember { mutableStateOf<FinanceCategory?>(null) }
    var deleteStrategy by remember { mutableStateOf("MIGRATE_RECORDS") }
    var deleteTarget by remember { mutableStateOf<UUID?>(null) }
    var accountCategoryDelete by remember { mutableStateOf<dev.icos.app.data.local.AccountCategoryCommands.CategoryDeletePreview?>(null) }
    var accountCategoryRecordDelete by remember {
        mutableStateOf<dev.icos.app.data.local.AccountCategoryDeletionCommands.CategoryRecordDeletePreview?>(null)
    }
    var categoryDeleteName by remember { mutableStateOf("") }
    var styleCategory by remember { mutableStateOf<FinanceCategory?>(null) }
    var colorCategory by remember { mutableStateOf<FinanceCategory?>(null) }
    var colorLedger by remember { mutableStateOf<FinanceLedger?>(null) }
    var colorParent by remember { mutableStateOf<FinanceCategory?>(null) }
    var archiveLedgerTarget by remember { mutableStateOf<FinanceLedger?>(null) }
    var archiveCategoryTarget by remember { mutableStateOf<FinanceCategory?>(null) }
    var archivedLedgersOpen by remember { mutableStateOf(false) }
    var archivedCategoriesOpen by remember { mutableStateOf(false) }
    val styleContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val visibleCategories = remember(snapshot.allCategories, snapshot.ledgerId) {
        snapshot.allCategories.filter { it.ledgerId == snapshot.ledgerId }
    }
    val categoriesById = remember(snapshot.allCategories) { snapshot.allCategories.associateBy { it.id } }
    val ledgersById = remember(snapshot.ledgers) { snapshot.ledgers.associateBy { it.id } }
    val categorySiblings = remember(visibleCategories) {
        buildMap<UUID, Pair<List<FinanceCategory>, Int>> {
            visibleCategories.groupBy { it.direction to it.parentId }.values.forEach { members ->
                val sorted = members.sortedWith(compareBy<FinanceCategory> { it.sortOrder }.thenBy { it.id.toString() })
                sorted.forEachIndexed { index, category -> put(category.id, sorted to index) }
            }
        }
    }
    androidx.compose.runtime.LaunchedEffect(model.state.accountOwner, snapshot.ownerId, snapshot.epochId) {
        createLedger = false
        renameLedger = null
        deleteLedger = null
        copySource = null
        createCategory = false
        renameCategory = null
        moveCategory = null
        deleteCategory = null
        accountCategoryDelete = null
        accountCategoryRecordDelete =
            null
        categoryDeleteName = ""
        styleCategory = null
        colorCategory = null
        colorLedger = null
        colorParent = null
        budgetCategory = null
        budgetCategoryLedger = null
        budgetCategoryParent = null
        archiveLedgerTarget = null
        archiveCategoryTarget = null
        archivedLedgersOpen = false
        archivedCategoriesOpen = false
    }
    val pendingCategories = remember(model.state.pendingCategories, snapshot.ledgerId) {
        model.state.pendingCategories.filter { it.ledgerId == snapshot.ledgerId }
    }
    val displayedLedgers = remember(snapshot.ledgers, archivedLedgersOpen) {
        snapshot.ledgers.withIndex().filter { (_, ledger) -> ledger.archivedAt == null || archivedLedgersOpen }
    }
    val displayedCategories = remember(visibleCategories, archivedCategoriesOpen) {
        visibleCategories.filter { it.archivedAt == null || archivedCategoriesOpen }
    }
    val archivedLedgerCount = remember(snapshot.ledgers) { snapshot.ledgers.count { it.archivedAt != null } }
    val archivedCategoryCount = remember(visibleCategories) { visibleCategories.count { it.archivedAt != null } }
    LazyColumn(
        modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!accountMode) {
            item(key = "multiLedgerToggle") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("多账本", Modifier.weight(1f))
                        Switch(
                            checked = model.state.guestMultiLedgerEnabled,
                            onCheckedChange = model::setGuestMultiLedgerEnabled,
                            enabled = ledgerEnabled,
                        )
                    }
                    Text("关闭后切换到默认账本并隐藏账本管理入口；现有账本和账务会保留。", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (showLedgerManagement) {
            item(key = "ledgers-header") {
                Text("我的账本", style = MaterialTheme.typography.titleMedium)
            }
            items(displayedLedgers, key = { "ledger-${it.value.id}" }) { indexed ->
                val index = indexed.index
                val ledger = indexed.value
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    colors =
                        CardDefaults.cardColors(
                            containerColor =
                                if (snapshot.ledgerId ==
                                    ledger.id
                                ) {
                                    MaterialTheme.colorScheme.secondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surface
                                },
                        ),
                ) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(enabled = ledgerEnabled && ledger.archivedAt == null, onClick = {
                            if (accountMode) model.selectAccountLedger(ledger.id) else model.selectGuestLedger(ledger.id)
                        }) {
                            Text(
                                "${ledger.name} · ${ledger.currency}${if (ledger.isDefault) " · 默认" else ""}${if (snapshot.ledgerId == ledger.id) " · 当前" else ""}",
                            )
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                            TextButton(enabled = ledgerEnabled && if (accountMode) index > 0 else ledger.sortOrder > Long.MIN_VALUE, onClick = {
                                if (accountMode) {
                                    model.reorderAccountLedgers(snapshot.ledgers.toMutableList().apply { add(index - 1, removeAt(index)) })
                                } else {
                                    model.updateLedger(ledger.id, ledger.localRevision, ledger.name, ledger.sortOrder - 1, ledger.isDefault)
                                }
                            }) { Text("上移") }
                            TextButton(
                                enabled =
                                    ledgerEnabled && if (accountMode) index < snapshot.ledgers.lastIndex else ledger.sortOrder < Long.MAX_VALUE,
                                onClick = {
                                    if (accountMode) {
                                        model.reorderAccountLedgers(
                                            snapshot.ledgers.toMutableList().apply { add(index + 1, removeAt(index)) },
                                        )
                                    } else {
                                        model.updateLedger(
                                            ledger.id,
                                            ledger.localRevision,
                                            ledger.name,
                                            ledger.sortOrder + 1,
                                            ledger.isDefault,
                                        )
                                    }
                                },
                            ) { Text("下移") }
                            TextButton(enabled = ledgerEnabled && ledger.archivedAt == null && !ledger.isDefault, onClick = {
                                model.updateLedger(ledger.id, ledger.localRevision, ledger.name, ledger.sortOrder, true)
                            }) { Text("设默认") }
                            TextButton(enabled = ledgerEnabled, onClick = {
                                renameLedger = ledger
                                renameValue = ledger.name
                                model.clearError()
                            }) { Text("改名") }
                            TextButton(enabled = ledgerEnabled && !ledger.isDefault, onClick = {
                                archiveLedgerTarget = ledger
                            }) { Text(if (ledger.archivedAt == null) "归档" else "恢复") }
                            TextButton(enabled = ledgerEnabled && !ledger.isDefault, onClick = {
                                deleteLedger = ledger
                                deleteName = ""
                                model.clearError()
                            }) { Text("删除") }
                        }
                    }
                }
            }
            if (archivedLedgerCount > 0) {
                item(key = "ledgers-archived") {
                    TextButton(onClick = { archivedLedgersOpen = !archivedLedgersOpen }) {
                        Text(if (archivedLedgersOpen) "收起已归档账本（$archivedLedgerCount）" else "查看已归档账本（$archivedLedgerCount）")
                    }
                }
            }
            if (snapshot.ledgers.isEmpty()) {
                item(key = "ledgers-empty") {
                    Text("还没有账本，请创建账本后开始管理分类。", style = MaterialTheme.typography.bodyMedium)
                }
            }
            item(key = "ledgers-actions") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(enabled = ledgerEnabled, onClick = {
                        copyLedger = false
                        copySource = null
                        ledgerName = ""
                        model.clearError()
                        createLedger = true
                    }) { AppIcon(AppSymbol.ADD); Text("新建账本") }
                    TextButton(enabled = ledgerEnabled && snapshot.ledgers.any { it.id == snapshot.ledgerId && it.archivedAt == null }, onClick = {
                        copySource = snapshot.ledgers.firstOrNull { it.id == snapshot.ledgerId }
                        copyLedger = true
                        ledgerName = "${copySource?.name.orEmpty()} 副本"
                        model.clearError()
                        createLedger = true
                    }) { Text("复制当前账本") }
                }
            }
        }
        if (accountMode) {
            item(key = "account-notice") {
                Text(
                    "分类新增和改名保存到本机后立即生效，分类和账单在后台同步。复制只包含账本和分类；账本更改等操作等待服务器确认。只允许删除无关联空账本、移动无引用的末级分类；删除分类先预览迁移或普通账单冲正范围。颜色可同步，图标和显示风格保存在本机。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        item(key = "categories-header") {
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                Text("分类", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 12.dp))
                TextButton(enabled = localCategoryEnabled, onClick = {
                    categoryLedger = snapshot.ledgers.firstOrNull { it.id == snapshot.ledgerId }
                    categoryName = ""
                    categoryDirection = Direction.EXPENSE
                    categoryParent = null
                    categoryParentRevision = null
                    categoryExcludeBudget = false
                    model.clearError()
                    createCategory = true
                }) { AppIcon(AppSymbol.ADD); Text("添加支出") }
                TextButton(enabled = localCategoryEnabled, onClick = {
                    categoryLedger = snapshot.ledgers.firstOrNull { it.id == snapshot.ledgerId }
                    categoryName = ""
                    categoryDirection = Direction.INCOME
                    categoryParent = null
                    categoryParentRevision = null
                    categoryExcludeBudget = false
                    model.clearError()
                    createCategory = true
                }) { AppIcon(AppSymbol.ADD); Text("添加收入") }
            }
        }
        if (visibleCategories.isEmpty()) {
            item(key = "categories-empty") {
                Text("当前账本还没有分类，添加收入或支出分类即可开始记账。", style = MaterialTheme.typography.bodyMedium)
            }
        }
        items(displayedCategories, key = { "category-${it.id}" }) { category ->
            val (siblings, categoryIndex) = categorySiblings.getValue(category.id)
            val parent = categoriesById[category.parentId]
            val parentArchived = parent?.archivedAt != null
            val categoryLedgerArchived = ledgersById[category.ledgerId]?.archivedAt != null
            val style = rememberCategoryStyle(styleContext, snapshot.ownerId, category, model.categoryStyleRevision)
            val restoreBlocked = category.archivedAt != null && (parentArchived || categoryLedgerArchived)
            Card(
                Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CategoryStyleBadge(style)
                        Text(
                            "${if (category.parentId != null) "└ " else ""}${category.name} · ${if (category.direction == Direction.EXPENSE) "支出" else "收入"}${if (category.archivedAt != null) " · 已归档" else ""}",
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                    if (accountMode && category.localRevision == 0L) {
                        Text("已保存到本机，可立即记账；等待后台同步。", style = MaterialTheme.typography.bodySmall)
                    }
                    if (restoreBlocked) {
                        Text(
                            if (categoryLedgerArchived) "所在账本已归档，先恢复账本才能恢复此分类。" else "上级分类已归档，先恢复上级分类才能恢复此分类。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (category.direction == Direction.EXPENSE && (category.excludeBudget ||
                            parent?.let { it.ledgerId == category.ledgerId && it.excludeBudget } == true)) {
                        Text(if (category.excludeBudget) "此分类不计预算" else "上级分类不计预算", style = MaterialTheme.typography.bodySmall)
                    }
                    FlowRow(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(0.dp),
                    ) {
                        if (accountMode) {
                            TextButton(enabled = categoryEnabled && categoryIndex > 0, onClick = {
                                model.reorderAccountCategories(
                                    siblings.toMutableList().apply { add(categoryIndex - 1, removeAt(categoryIndex)) },
                                )
                            }) { Text("↑") }
                            TextButton(enabled = categoryEnabled && categoryIndex < siblings.lastIndex, onClick = {
                                model.reorderAccountCategories(
                                    siblings.toMutableList().apply { add(categoryIndex + 1, removeAt(categoryIndex)) },
                                )
                            }) { Text("↓") }
                        }
                        TextButton(enabled = categoryEnabled, onClick = { styleCategory = category }) {
                            Text(if (accountMode) "图标与风格" else "图标、颜色与风格")
                        }
                        if (accountMode) {
                            TextButton(enabled = categoryEnabled && category.archivedAt == null, onClick = {
                                colorCategory = category
                                colorLedger = snapshot.ledgers.firstOrNull { it.id == category.ledgerId }
                                colorParent = snapshot.allCategories.firstOrNull { it.id == category.parentId }
                                model.clearError()
                            }) { Text("同步颜色") }
                        }
                        if (category.direction == Direction.EXPENSE) {
                            TextButton(enabled = categoryEnabled && category.archivedAt == null, onClick = {
                                budgetCategory = category
                                budgetCategoryLedger = snapshot.ledgers.firstOrNull { it.id == category.ledgerId }
                                budgetCategoryParent = snapshot.allCategories.firstOrNull { it.id == category.parentId }
                                budgetCategoryExcluded = category.excludeBudget
                                model.clearError()
                            }) { Text("预算规则") }
                        }
                        TextButton(enabled = localCategoryEnabled && (!accountMode || category.archivedAt == null), onClick = {
                            renameCategory = category
                            renameCategoryValue = category.name
                            model.clearError()
                        }) { Text("改名") }
                        TextButton(enabled = categoryEnabled, onClick = {
                            moveCategory = category
                            moveLedgerId = category.ledgerId
                            moveDirection = category.direction
                            moveParentId = category.parentId
                            moveLedgerRevision = snapshot.ledgers.firstOrNull { it.id == category.ledgerId }?.localRevision
                            moveParentRevision = snapshot.allCategories.firstOrNull { it.id == category.parentId }?.localRevision
                            moveSourceParentRevision = moveParentRevision
                            model.clearError()
                        }) { Text("移动") }
                        TextButton(enabled = categoryEnabled && !restoreBlocked, onClick = {
                            archiveCategoryTarget = category
                        }) { Text(if (category.archivedAt == null) "归档" else "恢复") }
                        TextButton(enabled = categoryEnabled && (!accountMode || category.archivedAt == null), onClick = {
                            deleteCategory = category
                            deleteStrategy = "MIGRATE_RECORDS"
                            deleteTarget = null
                            model.clearError()
                        }) { Text("删除") }
                    }
                }
            }
        }
        if (archivedCategoryCount > 0) {
            item(key = "categories-archived") {
                TextButton(onClick = { archivedCategoriesOpen = !archivedCategoriesOpen }) {
                    Text(if (archivedCategoriesOpen) "收起已归档分类（$archivedCategoryCount）" else "查看已归档分类（$archivedCategoryCount）")
                }
            }
        }
    }
    archiveLedgerTarget?.let { ledger ->
        val changed = snapshot.ledgers.none { it.id == ledger.id && it.localRevision == ledger.localRevision }
        val archiving = ledger.archivedAt == null
        AlertDialog(
            onDismissRequest = { if (!model.state.busy) archiveLedgerTarget = null },
            title = { Text(if (archiving) "归档账本？" else "恢复账本？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (archiving) {
                            "「${ledger.name}」将不再出现在账本选择中，历史账单、分类和预算全部保留，可随时恢复。"
                        } else {
                            "「${ledger.name}」将重新出现在账本选择中。"
                        },
                    )
                    if (changed) Text("账本已变化，请关闭后重新确认。", color = MaterialTheme.colorScheme.error)
                    model.state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(enabled = ledgerEnabled && !changed, onClick = {
                    model.archiveLedger(ledger.id, ledger.localRevision, archiving)
                    archiveLedgerTarget = null
                }) { Text(if (archiving) "归档" else "恢复") }
            },
            dismissButton = { TextButton(enabled = !model.state.busy, onClick = { archiveLedgerTarget = null }) { Text("取消") } },
        )
    }
    archiveCategoryTarget?.let { category ->
        val changed =
            snapshot.allCategories.none { it.id == category.id && it.localRevision == category.localRevision }
        val archiving = category.archivedAt == null
        AlertDialog(
            onDismissRequest = { if (!model.state.busy) archiveCategoryTarget = null },
            title = { Text(if (archiving) "归档分类？" else "恢复分类？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (archiving) {
                            "「${category.name}」及其子分类将不再出现在记账和筛选中，历史账单和预算全部保留，可随时恢复。"
                        } else {
                            "「${category.name}」将重新出现在记账和筛选中。"
                        },
                    )
                    if (changed) Text("分类已变化，请关闭后重新确认。", color = MaterialTheme.colorScheme.error)
                    model.state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(enabled = categoryEnabled && !changed, onClick = {
                    model.archiveCategory(category, archiving)
                    archiveCategoryTarget = null
                }) { Text(if (archiving) "归档" else "恢复") }
            },
            dismissButton = { TextButton(enabled = !model.state.busy, onClick = { archiveCategoryTarget = null }) { Text("取消") } },
        )
    }
    budgetCategory?.let { category ->
        val ledger = budgetCategoryLedger
        val parent = budgetCategoryParent
        val changed =
            ledger == null || snapshot.ledgerId != ledger.id ||
                snapshot.ledgers.none { it.id == ledger.id && it.localRevision == ledger.localRevision && it.archivedAt == null } ||
                snapshot.allCategories.none {
                    it.id == category.id && it.localRevision == category.localRevision && it.archivedAt == null
                } ||
                category.parentId != parent?.id || parent != null &&
                snapshot.allCategories.none {
                    it.id == parent.id && it.localRevision == parent.localRevision && it.archivedAt == null
                }
        val hasVisibleReferences =
            snapshot.allCategories.any { it.parentId == category.id } ||
                snapshot.records.any { it.categoryId == category.id }
        AlertDialog(
            onDismissRequest = { if (!model.state.busy) budgetCategory = null },
            title = { Text("分类预算规则") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(category.name)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("此分类不计预算")
                        Switch(
                            checked = budgetCategoryExcluded,
                            onCheckedChange = { budgetCategoryExcluded = it },
                            enabled = categoryEnabled && !changed && !hasVisibleReferences,
                        )
                    }
                    if (parent?.excludeBudget == true) Text("上级分类不计预算，因此本分类的账单也不占用预算。")
                    Text(
                        "仅无子分类、无账单历史且未被预算、模板或报销退款等业务引用的末级分类可以修改；保存时会核对完整引用。单笔计入预算选择保留，账户资金和统计不受影响。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (hasVisibleReferences) Text("此分类已有账单或子分类，不能切换排除规则。")
                    if (changed) Text("分类、上级或账本已变化，请重新打开。", color = MaterialTheme.colorScheme.error)
                    model.state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(
                    enabled =
                        categoryEnabled && !changed && !hasVisibleReferences && budgetCategoryExcluded != category.excludeBudget,
                    onClick = {
                        ledger?.let {
                            model.setCategoryBudgetExcluded(
                                category,
                                it,
                                parent,
                                budgetCategoryExcluded,
                            ) { budgetCategory = null }
                        }
                    },
                ) { Text("保存") }
            },
            dismissButton = { TextButton(enabled = !model.state.busy, onClick = { budgetCategory = null }) { Text("取消") } },
        )
    }
    colorCategory?.let { category ->
        val ledger = colorLedger
        val parent = colorParent
        val stale =
            ledger == null || snapshot.ledgerId != ledger.id ||
                snapshot.ledgers.none { it.id == ledger.id && it.localRevision == ledger.localRevision && it.archivedAt == null } ||
                snapshot.allCategories.none {
                    it.id == category.id && it.localRevision == category.localRevision && it.archivedAt == null
                } ||
                category.parentId != parent?.id || parent != null &&
                snapshot.allCategories.none {
                    it.id == parent.id && it.localRevision == parent.localRevision && it.archivedAt == null
                }
        CategoryColorEditor(
            category,
            categoryEnabled,
            model.state.busy,
            stale,
            model.state.error,
            onSave = { color -> ledger?.let { model.saveAccountCategoryColor(category, it, parent, color) { colorCategory = null } } },
            onDismiss = { colorCategory = null },
        )
    }
    styleCategory?.let { category ->
        CategoryStyleEditor(
            owner = snapshot.ownerId,
            category = category,
            onSave = { model.saveCategoryStyle(snapshot.ownerId, category, it) },
            onReset = { model.resetCategoryStyle(snapshot.ownerId, category) },
            onDismiss = { styleCategory = null },
        )
    }
    if (createLedger) {
        val source = copySource
        val sourceChanged =
            accountMode && copyLedger &&
                (
                    source == null ||
                        snapshot.ledgers.none { it.id == source.id && it.localRevision == source.localRevision && it.archivedAt == null }
                )
        AlertDialog(
            onDismissRequest = { if (!model.state.busy) createLedger = false },
            title = { Text(if (copyLedger) "复制账本" else "新建账本") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(ledgerName, {
                        if (it.length <=
                            100
                        ) {
                            ledgerName = it
                        }
                    }, enabled = ledgerEnabled, label = { Text("名称") }, singleLine = true)
                    if (!copyLedger) {
                        OutlinedTextField(ledgerCurrency, {
                            ledgerCurrency = it.uppercase(java.util.Locale.ROOT).take(3)
                        }, enabled = ledgerEnabled, label = { Text("基础币种") }, singleLine = true)
                    }
                    if (sourceChanged) Text("来源账本已变化，请关闭后重新确认。", color = MaterialTheme.colorScheme.error)
                    model.state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(enabled = ledgerEnabled && ledgerName.isNotBlank() && !sourceChanged, onClick = {
                    if (copyLedger) {
                        source?.let { model.copyLedger(it.id, ledgerName, it.localRevision) { createLedger = false } }
                    } else {
                        model.createLedger(ledgerName, ledgerCurrency) { createLedger = false }
                    }
                }) { Text(if (copyLedger) "复制" else "创建") }
            },
            dismissButton = { TextButton(enabled = !model.state.busy, onClick = { createLedger = false }) { Text("取消") } },
        )
    }
    renameLedger?.let { ledger ->
        val changed = accountMode && snapshot.ledgers.none { it.id == ledger.id && it.localRevision == ledger.localRevision }
        AlertDialog(
            onDismissRequest = { if (!model.state.busy) renameLedger = null },
            title = { Text("重命名账本") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(renameValue, {
                        if (it.length <=
                            100
                        ) {
                            renameValue = it
                        }
                    }, enabled = ledgerEnabled && !changed, label = { Text("名称") })
                    if (changed) Text("账本版本已变化，请关闭后重新编辑。", color = MaterialTheme.colorScheme.error)
                    model.state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(enabled = ledgerEnabled && renameValue.isNotBlank() && !changed, onClick = {
                    model.updateLedger(ledger.id, ledger.localRevision, renameValue, ledger.sortOrder, ledger.isDefault) {
                        renameLedger =
                            null
                    }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(enabled = !model.state.busy, onClick = { renameLedger = null }) { Text("取消") } },
        )
    }
    deleteLedger?.let { ledger ->
        val changed = snapshot.ledgers.none { it.id == ledger.id && it.localRevision == ledger.localRevision }
        AlertDialog(
            onDismissRequest = { if (!model.state.busy) deleteLedger = null },
            title = { Text("删除账本") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("仅当账本没有资金历史、分类、预算或其他关联时才能删除。请输入账本名称确认。")
                    OutlinedTextField(deleteName, { deleteName = it }, enabled = ledgerEnabled && !changed, label = { Text(ledger.name) })
                    if (changed) Text("账本已变化，请关闭后重新确认。", color = MaterialTheme.colorScheme.error)
                    model.state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(enabled = ledgerEnabled && !changed && deleteName == ledger.name, onClick = {
                    model.deleteLedger(ledger, deleteName) { deleteLedger = null }
                }) { Text("确认删除") }
            },
            dismissButton = { TextButton(enabled = !model.state.busy, onClick = { deleteLedger = null }) { Text("取消") } },
        )
    }
    if (createCategory) {
        val ledger = categoryLedger
        val changed =
            accountMode && (
                ledger == null || snapshot.ledgerId != ledger.id ||
                    snapshot.ledgers.none { it.id == ledger.id && it.localRevision == ledger.localRevision && it.archivedAt == null } ||
                    categoryParent != null &&
                    snapshot.categories.none { it.id == categoryParent && it.localRevision == categoryParentRevision }
            )
        val trimmedName = categoryName.trim()
        val duplicate = (visibleCategories + pendingCategories).any {
            it.direction == categoryDirection && it.parentId == categoryParent && it.name.trim() == trimmedName
        }
        val saving = model.categorySaving
        val parents = remember(snapshot.categories, snapshot.ledgerId, categoryDirection) {
            snapshot.categories.filter {
                it.ledgerId == snapshot.ledgerId && it.parentId == null &&
                    it.direction == categoryDirection && it.archivedAt == null
            }
        }
        val parentChoices = remember(parents) { parents.map { it.id to it.name } }
        AlertDialog(
            onDismissRequest = {
                if (!model.state.busy && !saving) createCategory = false
            },
            title = { Text("添加${if (categoryDirection == Direction.EXPENSE) "支出" else "收入"}分类") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(categoryName, {
                        if (it.length <=
                            100
                        ) {
                            categoryName = it
                        }
                    }, enabled = localCategoryEnabled && !changed, label = { Text("分类名称") }, singleLine = true)
                    Choice("上级分类", parentChoices, categoryParent, emptyLabel = "根分类", enabled = localCategoryEnabled && !changed) { id ->
                        categoryParent = id
                        categoryParentRevision = parents.firstOrNull { it.id == id }?.localRevision
                    }
                    if (categoryDirection == Direction.EXPENSE) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("此分类不计预算")
                            Switch(
                                checked = categoryExcludeBudget,
                                onCheckedChange = { categoryExcludeBudget = it },
                                enabled =
                                    localCategoryEnabled && !changed,
                            )
                        }
                        if (snapshot.allCategories.any { it.id == categoryParent && it.excludeBudget }) {
                            Text("上级分类不计预算；即使此开关关闭，本分类的账单也不会占用预算。", style = MaterialTheme.typography.bodySmall)
                        }
                        Text("不影响账户资金或统计；分类有账单或其他引用后不能切换此规则。", style = MaterialTheme.typography.bodySmall)
                    }
                    if (duplicate) Text("当前账本已有同名同级分类（含归档及待同步的分类），请选择已有分类或使用其他名称。", color = MaterialTheme.colorScheme.error)
                    if (changed) Text("账本或上级分类已变化，请关闭后重新确认。", color = MaterialTheme.colorScheme.error)
                    if (accountMode) Text("保存到本机后立即可用，后台同步无需等待。", style = MaterialTheme.typography.bodySmall)
                    model.state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(enabled = localCategoryEnabled && trimmedName.isNotBlank() && !duplicate && !changed, onClick = {
                    model.saveCategory(
                        name = trimmedName,
                        direction = categoryDirection,
                        parentId = categoryParent,
                        expectedLedgerRevision = ledger?.localRevision,
                        expectedParentRevision = categoryParentRevision,
                        excludeBudget = categoryExcludeBudget,
                    ) { _ -> createCategory = false }
                }) { Text(if (saving) "保存中…" else "保存") }
            },
            dismissButton = { TextButton(enabled = !model.state.busy && !saving, onClick = { createCategory = false }) { Text("取消") } },
        )
    }
    renameCategory?.let { category ->
        val changed =
            accountMode &&
                (
                    snapshot.ledgerId != category.ledgerId ||
                        snapshot.allCategories.none { it.id == category.id && it.localRevision == category.localRevision }
                )
        val saving = model.categorySaving
        AlertDialog(
            onDismissRequest = { if (!model.state.busy && !saving) renameCategory = null },
            title = { Text("重命名分类") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(renameCategoryValue, {
                        if (it.length <=
                            100
                        ) {
                            renameCategoryValue = it
                        }
                    }, enabled = localCategoryEnabled && !changed, label = { Text("名称") })
                    if (changed) Text("分类版本已变化，请关闭后重新编辑。", color = MaterialTheme.colorScheme.error)
                    model.state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(enabled = localCategoryEnabled && renameCategoryValue.isNotBlank() && !changed, onClick = {
                    model.saveCategory(category.id, category.localRevision, renameCategoryValue, category.direction, category.parentId) { savedId ->
                        if (savedId == category.id) renameCategory = null
                    }
                }) { Text(if (saving) "保存中…" else "保存") }
            },
            dismissButton = { TextButton(enabled = !model.state.busy && !saving, onClick = { renameCategory = null }) { Text("取消") } },
        )
    }
    moveCategory?.let { category ->
        val targetLedgers = snapshot.ledgers.filter { it.archivedAt == null }
        val targetParents =
            snapshot.allCategories.filter {
                it.ledgerId == moveLedgerId && it.direction == moveDirection &&
                    it.parentId == null &&
                    it.id != category.id &&
                    it.archivedAt == null
            }
        val changed =
            accountMode && (
                snapshot.allCategories.none { it.id == category.id && it.localRevision == category.localRevision } ||
                    targetLedgers.none { it.id == moveLedgerId && it.localRevision == moveLedgerRevision } ||
                    category.parentId != null &&
                    snapshot.allCategories.none { it.id == category.parentId && it.localRevision == moveSourceParentRevision } ||
                    moveParentId != null && targetParents.none { it.id == moveParentId && it.localRevision == moveParentRevision }
            )
        AlertDialog(
            onDismissRequest = { if (!model.state.busy) moveCategory = null },
            title = { Text("移动分类") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("仅支持移动没有子分类的分类。若分类被记录、预算或模板引用，操作会被拒绝；请先使用支持的迁移流程处理引用。")
                    Text("目标账本")
                    targetLedgers.forEach { ledger ->
                        FilterChip(selected = moveLedgerId == ledger.id, enabled = categoryEnabled, onClick = {
                            moveLedgerId = ledger.id
                            moveLedgerRevision =
                                ledger.localRevision
                            moveParentId = null
                            moveParentRevision = null
                        }, label = { Text("${ledger.name} · ${ledger.currency}") })
                    }
                    Text("收支方向")
                    FilterChip(selected = moveDirection == Direction.EXPENSE, enabled = categoryEnabled, onClick = {
                        moveDirection = Direction.EXPENSE
                        moveParentId =
                            null
                        moveParentRevision = null
                    }, label = { Text("支出") })
                    FilterChip(selected = moveDirection == Direction.INCOME, enabled = categoryEnabled, onClick = {
                        moveDirection = Direction.INCOME
                        moveParentId =
                            null
                        moveParentRevision = null
                    }, label = { Text("收入") })
                    Text("目标上级")
                    FilterChip(selected = moveParentId == null, enabled = categoryEnabled, onClick = {
                        moveParentId = null
                        moveParentRevision = null
                    }, label = { Text("根分类") })
                    targetParents.forEach { parent ->
                        FilterChip(selected = moveParentId == parent.id, enabled = categoryEnabled, onClick = {
                            moveParentId = parent.id
                            moveParentRevision =
                                parent.localRevision
                        }, label = { Text(parent.name) })
                    }
                    if (changed) Text("分类或目标版本已变化，请重新打开或选择目标。", color = MaterialTheme.colorScheme.error)
                    model.state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(
                    enabled =
                        categoryEnabled && !changed && targetLedgers.any { it.id == moveLedgerId } &&
                            (moveParentId == null || targetParents.any { it.id == moveParentId }),
                    onClick = {
                        model.moveCategory(
                            category,
                            moveLedgerId,
                            moveDirection,
                            moveParentId,
                            moveLedgerRevision,
                            moveParentRevision,
                            moveSourceParentRevision,
                        ) {
                            moveCategory =
                                null
                        }
                    },
                ) { Text("移动") }
            },
            dismissButton = { TextButton(enabled = !model.state.busy, onClick = { moveCategory = null }) { Text("取消") } },
        )
    }
    deleteCategory?.let { category ->
        val targets =
            snapshot.allCategories.filter {
                it.id != category.id && it.ledgerId == category.ledgerId &&
                    it.direction == category.direction && it.archivedAt == null
            }
        val changed = accountMode && snapshot.allCategories.none { it.id == category.id && it.localRevision == category.localRevision }
        AlertDialog(
            onDismissRequest = { if (!model.state.busy) deleteCategory = null },
            title = { Text("删除分类") },
            text = {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        if (accountMode && deleteStrategy == "MIGRATE_RECORDS") {
                            "账单及其他业务引用将迁移到所选分类；下一步会读取并展示完整影响范围。资金金额保持不变。"
                        } else if (accountMode) {
                            "仅支持独立普通收支账单；下一步预览笔数和账户余额冲正额。手续费、返现及预算、模板、报销退款、分期、周期等关联会阻止删除。"
                        } else {
                            "迁移会保留账单并改用目标分类；删除账单会按原交易精确冲正。有关联预算、模板、理赔、退款或存钱事项时会拒绝。"
                        },
                    )
                    FilterChip(
                        selected = deleteStrategy == "MIGRATE_RECORDS",
                        enabled = categoryEnabled,
                        onClick = { deleteStrategy = "MIGRATE_RECORDS" },
                        label = { Text("迁移账单") },
                    )
                    if (deleteStrategy == "MIGRATE_RECORDS") {
                        targets.forEach { target ->
                            FilterChip(
                                selected = deleteTarget == target.id,
                                enabled = categoryEnabled,
                                onClick = { deleteTarget = target.id },
                                label = { Text("迁移到 ${target.name}") },
                            )
                        }
                    }
                    FilterChip(
                        selected = deleteStrategy == "DELETE_RECORDS",
                        enabled = categoryEnabled,
                        onClick = {
                            deleteStrategy = "DELETE_RECORDS"
                            deleteTarget = null
                        },
                        label = { Text("冲正并删除关联账单") },
                    )
                    if (changed) Text("分类已变化，请关闭后重新确认。", color = MaterialTheme.colorScheme.error)
                    model.state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(
                    enabled =
                        categoryEnabled && !changed && (deleteStrategy == "DELETE_RECORDS" || targets.any { it.id == deleteTarget }),
                    onClick = {
                        if (accountMode) {
                            if (deleteStrategy == "DELETE_RECORDS") {
                                model.previewAccountCategoryRecordDeletion(category) {
                                    accountCategoryRecordDelete = it
                                    categoryDeleteName = ""
                                    deleteCategory = null
                                }
                            } else {
                                targets.singleOrNull { it.id == deleteTarget }?.let { target ->
                                    model.previewAccountCategoryMigration(category, target) {
                                        accountCategoryDelete = it
                                        deleteCategory = null
                                    }
                                }
                            }
                        } else {
                            model.deleteCategory(category, deleteStrategy, deleteTarget)
                            deleteCategory = null
                        }
                    },
                ) { Text(if (accountMode) "预览影响" else "确认") }
            },
            dismissButton = { TextButton(enabled = !model.state.busy, onClick = { deleteCategory = null }) { Text("取消") } },
        )
    }
    accountCategoryRecordDelete?.let { preview ->
        val changed =
            snapshot.ownerId.toString() != preview.ownerId || snapshot.epochId.toString() != preview.epochId ||
                snapshot.ledgerId != preview.source.ledgerId ||
                snapshot.allCategories.none {
                    it.id == preview.source.id && it.localRevision == preview.source.localRevision &&
                        it.archivedAt == null
                }
        AlertDialog(
            onDismissRequest = { if (!model.state.busy) accountCategoryRecordDelete = null },
            title = { Text("冲正账单并删除分类") },
            text = {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("分类：${preview.source.name}")
                    Text("将冲正并删除 ${preview.recordCount} 笔普通收支账单，再删除分类。原交易和冲正分录保留为历史，统计与预算贡献按原交易抵消；确认后等待服务器处理。")
                    if (preview.accountImpacts.isEmpty()) {
                        Text("没有账户余额变动。")
                    } else {
                        Text("各账户余额变动", style = MaterialTheme.typography.titleSmall)
                        preview.accountImpacts.forEach { impact ->
                            val amount = impact.amount
                            val value =
                                java.math.BigDecimal
                                    .valueOf(amount.unscaled, amount.scale)
                                    .stripTrailingZeros()
                                    .toPlainString()
                            Text("${impact.name} · ${amount.currency} ${if (amount.unscaled > 0) "+" else ""}$value")
                        }
                    }
                    OutlinedTextField(
                        categoryDeleteName,
                        { categoryDeleteName = it },
                        enabled = categoryEnabled && !changed,
                        label = { Text("输入完整分类名称：${preview.source.name}") },
                        singleLine = true,
                    )
                    if (changed) Text("分类或账本已变化，请关闭后重新预览。", color = MaterialTheme.colorScheme.error)
                    model.state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(enabled = categoryEnabled && !changed && categoryDeleteName == preview.source.name, onClick = {
                    model.deleteAccountCategoryWithRecords(preview, categoryDeleteName) {
                        accountCategoryRecordDelete = null
                        categoryDeleteName =
                            ""
                    }
                }) { Text("确认冲正并删除") }
            },
            dismissButton = {
                TextButton(enabled = !model.state.busy, onClick = {
                    accountCategoryRecordDelete = null
                    categoryDeleteName =
                        ""
                }) { Text("取消") }
            },
        )
    }
    accountCategoryDelete?.let { preview ->
        val changed =
            snapshot.ownerId.toString() != preview.ownerId || snapshot.epochId.toString() != preview.epochId ||
                snapshot.allCategories.none { it.id == preview.source.id && it.localRevision == preview.source.localRevision } ||
                snapshot.allCategories.none { it.id == preview.target.id && it.localRevision == preview.target.localRevision }
        AlertDialog(
            onDismissRequest = { if (!model.state.busy) accountCategoryDelete = null },
            title = { Text("确认迁移并删除分类") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${preview.source.name} → ${preview.target.name}")
                    Text(
                        "涉及 ${preview.recordCount} 笔账单、${preview.templateCount} 个模板、${preview.budgetCount} 个预算，共 ${preview.referenceCount} 项引用。金额保持不变，分类统计将归入目标分类。",
                    )
                    if (changed) Text("分类已变化，请关闭后重新预览。", color = MaterialTheme.colorScheme.error)
                    model.state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(enabled = categoryEnabled && !changed, onClick = {
                    model.deleteAccountCategoryMigrating(preview) { accountCategoryDelete = null }
                }) { Text("迁移引用并删除") }
            },
            dismissButton = { TextButton(enabled = !model.state.busy, onClick = { accountCategoryDelete = null }) { Text("取消") } },
        )
    }
}
