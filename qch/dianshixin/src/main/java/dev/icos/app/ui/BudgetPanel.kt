package dev.icos.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.icos.app.data.local.BudgetScope
import dev.icos.app.data.local.FinanceBudgetOverview
import dev.icos.app.domain.Money
import dev.icos.app.domain.ReportPeriod
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

@Composable
internal fun BudgetPanel(
    model: FinanceViewModel,
    compact: Boolean = false,
    onClose: (() -> Unit)? = null,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    var confirmation by remember { mutableStateOf<Pair<Boolean, FinanceBudgetOverview>?>(null) }
    val budget = model.displayState.budget
    val guestOnly = model.state.accountOwner == null
    val enabled =
        !model.state.busy && (
            guestOnly || !model.state.accountMutationPending &&
                !model.state.syncPaused && !model.state.accountClearPending && !model.state.restoreBusy
        )
    if (compact) {
        budget?.let { LedgerBudgetCard(it, !model.state.busy) { open = true } }
    } else if (onClose == null) {
        TextButton(onClick = { open = true }, enabled = budget != null && !model.state.busy) { Text("预算") }
    }
    if (onClose != null && budget == null) {
        Text(model.state.error ?: if (model.state.busy) "正在读取预算…" else "预算暂不可用，请返回账本后重试。")
    }
    if ((open || onClose != null) && budget != null) {
        BudgetOverviewDialog(
            model = model,
            budget = budget,
            guestOnly = guestOnly,
            enabled = enabled,
            categoryNames =
                model.state.snapshot
                    ?.categories
                    ?.associate { it.id to it.name }
                    .orEmpty(),
            onClose = {
                open = false
                onClose?.invoke()
            },
            onClear = { confirmation = true to budget },
            onReset = { confirmation = false to budget },
        )
    }
    confirmation?.let { (clear, expected) ->
        AlertDialog(
            onDismissRequest = { confirmation = null },
            title = { Text(if (clear) "清空当期预算？" else "恢复默认预算？") },
            text = { Text(if (clear) "本期额度设为 0，默认预算和账单保留。" else "移除本期覆盖，重新使用默认预算，账单保留。") },
            confirmButton = {
                TextButton(enabled = enabled, onClick = {
                    confirmation = null
                    if (clear) model.clearBudget(expected) else model.resetBudget(expected)
                }) { Text("确认") }
            },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text("取消") } },
        )
    }
    model.budgetCategoryDraft?.let { draft -> BudgetCategoryEditor(model, draft, enabled) }
    model.budgetDraft?.let { draft -> BudgetAmountEditor(model, draft, enabled) }
}

@Composable
private fun BudgetOverviewDialog(
    model: FinanceViewModel,
    budget: FinanceBudgetOverview,
    guestOnly: Boolean,
    enabled: Boolean,
    categoryNames: Map<UUID, String>,
    onClose: () -> Unit,
    onClear: () -> Unit,
    onReset: () -> Unit,
) {
    EditorPage(
        onDismissRequest = onClose,
        title = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("预算", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = { model.editBudget(BudgetScope.CURRENT) }, enabled = enabled) {
                    AppIcon(AppSymbol.EDIT, "编辑当期预算")
                }
                Switch(
                    checked = budget.enabled,
                    onCheckedChange = model::setBudgetEnabled,
                    enabled = enabled,
                    modifier = Modifier.semantics { contentDescription = "开启预算" },
                )
            }
        },
        text = {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("周预算" to ReportPeriod.WEEK, "月预算" to ReportPeriod.MONTH, "年预算" to ReportPeriod.YEAR).forEach { (label, period) ->
                        FilterChip(
                            selected = model.budgetPeriod == period,
                            enabled = enabled,
                            onClick = { model.selectBudgetPeriod(period) },
                            label = { Text(label) },
                        )
                    }
                }
                Text(
                    "${budgetPeriodLabel(budget)} · ${model.statisticsZone} · ${budget.currency}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                BudgetSummaryCard(budget)
                if (!budget.enabled) {
                    Text(
                        "预算已关闭，配置与账单保留；此开关适用于当前账本的周、月、年预算。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                BudgetCategoryCard(budget, categoryNames, guestOnly, enabled, model)
                BudgetActionsCard(
                    budget = budget,
                    enabled = enabled,
                    onEditDefault = { model.editBudget(BudgetScope.DEFAULT) },
                    onEditCurrent = { model.editBudget(BudgetScope.CURRENT) },
                    onClear = onClear,
                    onReset = onReset,
                )
                if (!guestOnly && model.state.accountMutationPending) {
                    Text("更改正在等待服务器确认，请通过重试同步继续原操作。", style = MaterialTheme.typography.bodySmall)
                }
                if (budget.otherCurrencies.isNotEmpty()) {
                    Text(
                        "其他币种（${budget.otherCurrencies.joinToString()}）尚未换算到 ${budget.currency}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                model.state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(
                onClick = { model.editBudget(BudgetScope.CURRENT) },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
                colors = primaryButtonColors(),
            ) {
                AppIcon(AppSymbol.EDIT)
                Spacer(Modifier.size(6.dp))
                Text("编辑预算")
            }
        },
        dismissButton = {},
    )
}

private fun budgetPeriodLabel(budget: FinanceBudgetOverview): String {
    val lastDay =
        java.time.LocalDate
            .parse(budget.periodEnd)
            .minusDays(1)
    return "${budget.periodStart} 至 $lastDay"
}

/** Share of the limit already used; values above 1 mean the budget is overspent. */
internal fun budgetUsedRatio(
    used: Money,
    limit: Money?,
): Float? {
    if (limit == null) return null
    val usedValue = BigDecimal.valueOf(used.unscaled, used.scale)
    val limitValue = BigDecimal.valueOf(limit.unscaled, limit.scale)
    if (limitValue.signum() <= 0) return if (usedValue.signum() > 0) 1f else 0f
    return usedValue.divide(limitValue, 6, RoundingMode.HALF_UP).toFloat()
}

@Composable
private fun BudgetSummaryCard(budget: FinanceBudgetOverview) {
    val limit = budget.limit
    val remaining = budget.amounts?.remaining
    val overspent = remaining != null && remaining.unscaled < 0
    val ratio = budgetUsedRatio(budget.used, limit)
    val percent =
        limit?.let { total ->
            if (total.unscaled <= 0) {
                null
            } else {
                BigDecimal
                    .valueOf(budget.used.unscaled, budget.used.scale)
                    .multiply(BigDecimal(100))
                    .divide(BigDecimal.valueOf(total.unscaled, total.scale), 1, RoundingMode.HALF_UP)
                    .stripTrailingZeros()
                    .toPlainString() + "%"
            }
        }
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = summaryCardColor()),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("剩余预算", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (remaining != null && budget.enabled) {
                        AmountText(
                            "${moneyText(remaining)} ${budget.currency}",
                            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                        )
                    } else {
                        Text(
                            when {
                                !budget.enabled -> "预算已关闭"
                                limit == null -> "尚未设置预算"
                                else -> "剩余 ${moneyText(budget.amounts?.remaining ?: budget.used)} ${budget.currency}"
                            },
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    Text(
                        when {
                            !budget.enabled -> "开启后按当前周期继续累计"

                            limit == null -> "设置额度后显示剩余与每日可用"

                            overspent -> "已超支 ${moneyText(
                                budget.amounts?.remaining?.let { Money(-it.unscaled, it.scale, it.currency) } ?: budget.used,
                            )} ${budget.currency}"

                            else -> "剩余 ${budget.remainingDays} 个自然日（包含今天）"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (overspent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                BudgetProgressRing(if (budget.enabled) ratio else null, overspent, if (budget.enabled) percent else null)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BudgetStat("预算", limit?.let { "${moneyText(it)} ${budget.currency}" } ?: "未设置")
                BudgetStat("已使用", "${moneyText(budget.used)} ${budget.currency}")
                BudgetStat("剩余天数", "${budget.remainingDays} 天")
                BudgetStat("每日可用", budget.amounts?.let { "${it.dailyAllowance.toPlainString()} ${budget.currency}" } ?: "—")
            }
        }
    }
}

@Composable
private fun BudgetProgressRing(
    ratio: Float?,
    overspent: Boolean,
    percent: String?,
) {
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    val color = if (overspent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 10.dp.toPx()
            val inset = stroke / 2f
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(track, 0f, 360f, false, topLeft = Offset(inset, inset), size = arcSize, style = Stroke(stroke))
            ratio?.let {
                drawArc(
                    color,
                    -90f,
                    360f * it.coerceIn(0f, 1f),
                    false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(percent ?: "—", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
            Text(
                if (overspent) "已超支" else "已使用",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BudgetStat(
    label: String,
    value: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleSmall, maxLines = 1)
    }
}

@Composable
private fun BudgetCategoryCard(
    budget: FinanceBudgetOverview,
    categoryNames: Map<UUID, String>,
    guestOnly: Boolean,
    enabled: Boolean,
    model: FinanceViewModel,
) {
    val total =
        budget.categoryAmounts.values.fold(
            BigDecimal.ZERO,
        ) { sum, amount -> sum.add(BigDecimal.valueOf(amount.unscaled, amount.scale)) }
    val rows =
        budget.categoryAmounts.entries
            .sortedByDescending { it.value.unscaled }
            .take(8)
            .map { (id, amount) -> Triple(categoryNames[id] ?: "已删除类别", amount, budgetCategoryRatio(amount, total)) }
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("分类预算", style = MaterialTheme.typography.titleMedium)
            if (rows.isNotEmpty()) {
                Text(
                    "进度为分类额度占已配置额度的比例，分类实际消耗由预算服务端统计。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (rows.isEmpty()) {
                Text(
                    if (guestOnly) {
                        "本机账本支持总预算。分类额度需登录已配置的服务器账号后设置。"
                    } else {
                        "尚未配置分类额度，可设置默认额度或当期额度。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                rows.forEach { (name, amount, share) ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                            Text(
                                "${moneyText(amount)} ${budget.currency}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        LinearProgressIndicator(
                            progress = { share },
                            modifier = Modifier.fillMaxWidth().height(6.dp),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                            strokeCap = StrokeCap.Round,
                        )
                    }
                }
                if (budget.categoryAmounts.size > rows.size) {
                    Text(
                        "另有 ${budget.categoryAmounts.size - rows.size} 个分类额度，请编辑查看。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (!guestOnly) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { model.editBudgetCategories(BudgetScope.DEFAULT) }, enabled = enabled) { Text("默认类别额度") }
                    TextButton(onClick = { model.editBudgetCategories(BudgetScope.CURRENT) }, enabled = enabled) { Text("当期类别额度") }
                }
            }
        }
    }
}

/** Configured category limit as a share of the configured limits; not consumption. */
internal fun budgetCategoryRatio(
    amount: Money,
    total: BigDecimal,
): Float {
    if (total.signum() <= 0) return 0f
    return BigDecimal
        .valueOf(amount.unscaled, amount.scale)
        .divide(total, 6, RoundingMode.HALF_UP)
        .toFloat()
        .coerceIn(0f, 1f)
}

@Composable
private fun BudgetActionsCard(
    budget: FinanceBudgetOverview,
    enabled: Boolean,
    onEditDefault: () -> Unit,
    onEditCurrent: () -> Unit,
    onClear: () -> Unit,
    onReset: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("预算配置", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("默认预算", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text(
                    budget.defaultAmount?.let { "${moneyText(it)} ${budget.currency}" } ?: "未设置",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("当期预算", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text(
                    budget.overrideAmount?.let { "${moneyText(it)} ${budget.currency}" } ?: "继承默认",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onEditDefault, enabled = enabled) { Text("设置默认预算") }
                TextButton(onClick = onEditCurrent, enabled = enabled) { Text("设置当期预算") }
                TextButton(onClick = onClear, enabled = enabled) { Text("清空当期预算") }
                TextButton(onClick = onReset, enabled = enabled && budget.overrideAmount != null) { Text("恢复默认预算") }
            }
        }
    }
}

@Composable
private fun BudgetAmountEditor(
    model: FinanceViewModel,
    draft: BudgetDraft,
    enabled: Boolean,
) {
    val scopeLabel = if (draft.scope == BudgetScope.DEFAULT) "默认预算" else "当期预算"
    val initialAmount = remember(draft) { draft.amount }
    val canDismiss = !model.state.busy && !draft.saveQueued
    val dismiss = rememberEditorDismiss(draft.amount != initialAmount, canDismiss, model::dismissBudget)
    EditorPage(
        onDismissRequest = dismiss,
        title = { Text("编辑$scopeLabel") },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "${periodLabel(draft.expected.period)} · ${budgetPeriodLabel(draft.expected)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    if (draft.scope == BudgetScope.DEFAULT) "从当前周期起生效，以前周期的默认额度保留。" else "只覆盖当前周期，0 表示本期额度为零。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = draft.amount,
                    onValueChange = { draft.amount = it.take(32) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("${periodLabel(draft.expected.period)}总预算（${draft.expected.currency}）") },
                    textStyle = MaterialTheme.typography.headlineSmall,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    enabled = enabled && !draft.saveQueued,
                )
                model.state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (draft.saveQueued) Text("更改已写入本机队列，服务器结果尚未确认；请通过重试同步继续原操作。")
            }
        },
        confirmButton = {
            Button(onClick = model::saveBudget, enabled = enabled && !draft.saveQueued) {
                AppIcon(AppSymbol.CHECK)
                Spacer(Modifier.size(6.dp))
                Text("保存预算")
            }
        },
        dismissButton = {
            TextButton(onClick = dismiss, enabled = canDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun BudgetCategoryEditor(
    model: FinanceViewModel,
    draft: BudgetCategoryDraft,
    enabled: Boolean,
) {
    val scopeLabel = if (draft.scope == BudgetScope.DEFAULT) "默认" else "当期"
    val total = if (draft.scope == BudgetScope.DEFAULT) draft.expected.defaultAmount else draft.expected.overrideAmount
    // The enforced rule sums top-level categories only; sub-category limits nest inside their parent.
    val topLevel =
        draft.categories
            .filter { it.parentId == null }
            .map { it.id }
            .toSet()
    // "移除" only clears the draft input; the server always receives the full configured set on save.
    var included by remember(draft) { mutableStateOf(draft.amounts.keys.toSet()) }
    val initialAmounts = remember(draft) { draft.amounts.toMap() }
    val canDismiss = !model.state.busy && !draft.saveQueued
    val dismiss =
        rememberEditorDismiss(draft.amounts.toMap() != initialAmounts, canDismiss, model::dismissBudgetCategories)
    val entered =
        draft.amounts.entries
            .filter { it.key in topLevel && it.key in included }
            .mapNotNull { amountInput(it.value) }
            .fold(BigDecimal.ZERO, BigDecimal::add)
    EditorPage(
        onDismissRequest = dismiss,
        title = { Text("${scopeLabel}类别额度") },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "${periodLabel(draft.expected.period)} · ${budgetPeriodLabel(draft.expected)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "留空表示不单独限定该分类；分类额度合计不能超过${periodLabel(draft.expected.period)}总预算。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (draft.categories.isEmpty()) {
                    Text("当前账本没有可分配额度支出的分类。", style = MaterialTheme.typography.bodyMedium)
                }
                draft.categories.forEach { category ->
                    if (category.id !in included) return@forEach
                    OutlinedTextField(
                        value = draft.amounts[category.id].orEmpty(),
                        onValueChange = { draft.amounts[category.id] = it.take(32) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(category.name) },
                        prefix = { Text(draft.expected.currency) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        enabled = enabled && !draft.saveQueued,
                        singleLine = true,
                        trailingIcon = {
                            IconButton(
                                onClick = {
                                    included = included - category.id
                                    draft.amounts.remove(category.id)
                                },
                                enabled = enabled && !draft.saveQueued,
                            ) { AppIcon(AppSymbol.CLOSE, "移除${category.name}额度") }
                        },
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("分类合计", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "${entered.stripTrailingZeros().toPlainString()} ${draft.expected.currency}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (draft.categories.any { it.parentId != null }) {
                    Text(
                        "合计只统计一级分类；子分类额度包含在所属分类额度内。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (total != null) {
                    val remaining = BigDecimal.valueOf(total.unscaled, total.scale).subtract(entered)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("剩余金额", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${remaining.stripTrailingZeros().toPlainString()} ${draft.expected.currency}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (remaining.signum() < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                model.state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                val addable = draft.categories.filter { it.id !in included }
                if (addable.isEmpty()) {
                    if (draft.categories.isNotEmpty()) {
                        Text(
                            "所有支出分类都已列出；额度留空表示不单独限定该分类。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        addable.forEach { category ->
                            FilterChip(
                                selected = false,
                                enabled = enabled && !draft.saveQueued,
                                onClick = {
                                    included = included + category.id
                                    draft.amounts[category.id] = ""
                                },
                                leadingIcon = { AppIcon(AppSymbol.ADD, modifier = Modifier.size(18.dp)) },
                                label = { Text("添加 ${category.name}") },
                            )
                        }
                    }
                }
                if (draft.saveQueued) Text("更改已写入本机队列，服务器结果尚未确认；请通过重试同步继续原操作。")
            }
        },
        confirmButton = {
            Button(onClick = model::saveBudgetCategories, enabled = enabled && !draft.saveQueued) { Text("保存预算") }
        },
        dismissButton = {
            TextButton(onClick = dismiss, enabled = canDismiss) { Text("取消") }
        },
    )
}

private fun periodLabel(period: ReportPeriod): String =
    when (period) {
        ReportPeriod.WEEK -> "周预算"
        ReportPeriod.MONTH -> "月度预算"
        ReportPeriod.YEAR -> "年度预算"
    }

/** Lenient parse for the running category total; invalid input is reported by the save path. */
private fun amountInput(text: String): BigDecimal? = text.trim().toBigDecimalOrNull()
