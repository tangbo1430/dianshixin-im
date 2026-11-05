package dev.icos.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.icos.app.data.local.FinanceLoanPlan
import dev.icos.app.data.local.FinanceSnapshot
import dev.icos.app.data.local.LoanDisbursement
import dev.icos.app.domain.LoanCalculator
import dev.icos.app.domain.LoanDeleteStrategy
import dev.icos.app.domain.LoanDueState
import dev.icos.app.domain.LoanFuturePolicy
import dev.icos.app.domain.LoanPlanInput
import dev.icos.app.domain.LoanTerm
import dev.icos.app.domain.Money
import dev.icos.app.domain.RepaymentMethod
import java.math.BigDecimal
import java.math.BigInteger
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

@Composable
internal fun LoanPanel(
    snapshot: FinanceSnapshot,
    model: FinanceViewModel,
    modifier: Modifier,
) {
    var createOpen by remember { mutableStateOf(false) }
    var selectedId by remember { mutableStateOf<UUID?>(null) }
    var deleteTarget by remember { mutableStateOf<FinanceLoanPlan?>(null) }
    var archiveTarget by remember { mutableStateOf<FinanceLoanPlan?>(null) }
    var showArchived by remember { mutableStateOf(false) }
    val guestWritesEnabled = model.state.accountOwner == null
    val plan = snapshot.loanPlans.firstOrNull { it.id == selectedId && it.state != "DELETED" }
    if (plan != null) {
        LoanDetail(snapshot, plan, model, guestWritesEnabled, !model.state.busy, onBack = { selectedId = null }, onDelete = {
            deleteTarget =
                plan
        }, onArchive = { archiveTarget = plan })
    } else {
        val currencies =
            snapshot.allAccounts
                .filter { it.type == "LIABILITY" || it.includeInAssets }
                .map { it.currency }
                .distinct()
                .sorted()
        LazyColumn(
            modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 20.dp),
        ) {
            item {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("资产负债", style = MaterialTheme.typography.titleLarge)
                    Text("按币种分别汇总，不做跨币种合计", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(currencies, key = { "position:$it" }) { currency ->
                accountPosition(snapshot.allAccounts, currency)?.let { position -> AccountPositionCard(currency, position) }
            }
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("贷款计划", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    Button(onClick = { createOpen = true }, enabled = !model.state.busy) { AppIcon(AppSymbol.ADD); Text("新建贷款") }
                }
            }
            if (!guestWritesEnabled) {
                item {
                    Text(
                        "账号贷款以服务器确认后的同步镜像为准；归档与撤回可逆事件尚未开放。",
                        Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            val activePlans = snapshot.loanPlans.filter { it.state != "DELETED" && it.state != "ARCHIVED" }
            val archivedPlans = snapshot.loanPlans.filter { it.state == "ARCHIVED" }
            items(activePlans, key = { "loan:${it.id}" }) { item ->
                val account = snapshot.accounts.firstOrNull { it.id == item.accountId }
                LoanSummaryCard(item, account?.name.orEmpty(), onClick = { selectedId = item.id })
            }
            if (archivedPlans.isNotEmpty()) {
                item {
                    TextButton(
                        onClick = { showArchived = !showArchived },
                        modifier = Modifier.padding(horizontal = 16.dp),
                        enabled = !model.state.busy,
                    ) {
                        Text(if (showArchived) "收起已归档贷款（${archivedPlans.size}）" else "查看已归档贷款（${archivedPlans.size}）")
                    }
                }
                if (showArchived) {
                    items(archivedPlans, key = { "archived-loan:${it.id}" }) { item ->
                        val account = snapshot.accounts.firstOrNull { it.id == item.accountId }
                        LoanSummaryCard(item, account?.name.orEmpty() + " · 已归档", onClick = { selectedId = item.id })
                    }
                }
            }
            if (activePlans.isEmpty() && archivedPlans.isEmpty()) {
                item {
                    Card(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    ) {
                        Text("尚无贷款计划。先在账户页创建负债账户，再建立贷款计划。", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        if (createOpen) CreateLoanDialog(snapshot, model, onDismiss = { createOpen = false })
    }
    archiveTarget?.let { target ->
        val archiving = target.state == "ACTIVE"
        AlertDialog(
            onDismissRequest = { if (!model.state.busy) archiveTarget = null },
            title = { Text(if (archiving) "归档贷款计划？" else "恢复贷款计划？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (archiving) "计划及历史将保留，并从当前活动计划列表移至已归档列表。" else "计划将恢复到活动贷款列表。")
                    model.state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    model.archiveLoan(target, archiving)
                    archiveTarget = null
                }, enabled = !model.state.busy && guestWritesEnabled) { Text(if (archiving) "确认归档" else "确认恢复") }
            },
            dismissButton = { TextButton(onClick = { archiveTarget = null }, enabled = !model.state.busy) { Text("取消") } },
        )
    }
    deleteTarget?.let { target ->
        var strategy by remember(target.id) { mutableStateOf(LoanDeleteStrategy.KEEP_HISTORY) }
        AlertDialog(
            onDismissRequest = { if (!model.state.busy) deleteTarget = null },
            title = { Text("删除贷款计划？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (guestWritesEnabled) {
                        Text("保留历史：贷款标记为已删除，历史流水保留。")
                    } else {
                        Text("账号贷款仅支持保留历史删除；归档和撤回可逆事件未开放。历史流水不会被删除。")
                    }
                    if (guestWritesEnabled) Text("撤回可逆事件：仅在核心预检允许时一并撤回放款及还款关联账单和账户变动。")
                    if (guestWritesEnabled) {
                        listOf(LoanDeleteStrategy.KEEP_HISTORY, LoanDeleteStrategy.REVERSE_REVERSIBLE).forEach { option ->
                            Row(Modifier.fillMaxWidth().clickable { strategy = option }, verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(strategy == option, onClick = { strategy = option })
                                Text(if (option == LoanDeleteStrategy.KEEP_HISTORY) "保留历史" else "撤回可逆事件")
                            }
                        }
                    }
                    model.state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (guestWritesEnabled) {
                        model.deleteLoan(target, strategy) {
                            deleteTarget = null
                            selectedId = null
                        }
                    } else {
                        model.deleteLoan(target, LoanDeleteStrategy.KEEP_HISTORY) {
                            deleteTarget = null
                            selectedId = null
                        }
                    }
                }, enabled = !model.state.busy && (guestWritesEnabled || strategy == LoanDeleteStrategy.KEEP_HISTORY)) { Text("确认删除") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }, enabled = !model.state.busy) { Text("取消") } },
        )
    }
}

@Composable
private fun AccountPositionCard(
    currency: String,
    position: AccountPosition,
) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f)),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("净资产 · $currency", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            AmountText(amountOf(position.net), style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold))
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LoanMetric("资产", amountOf(position.assets), Modifier.weight(1f))
                LoanMetric("负债", amountOf(position.liabilities), Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun LoanSummaryCard(
    plan: FinanceLoanPlan,
    accountName: String,
    onClick: () -> Unit,
) {
    val terms = plan.terms.sortedBy { it.termIndex }
    val remaining = remainingLoanPrincipal(plan)
    val paidPrincipal =
        plan.payments.fold(
            Money(0, plan.principal.scale, plan.principal.currency),
        ) { sum, payment -> sum.checkedAdd(payment.principal) }
    val paidInterest =
        plan.payments.fold(
            Money(0, plan.principal.scale, plan.principal.currency),
        ) { sum, payment -> sum.checkedAdd(payment.interest) }
    val scheduledInterest =
        terms.fold(
            Money(0, plan.principal.scale, plan.principal.currency),
        ) { sum, term -> sum.checkedAdd(term.interest) }
    val nextTerm = nextLoanTerm(terms)
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(accountName.ifBlank { "负债账户" }, style = MaterialTheme.typography.titleMedium)
            Text("剩余本金", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            AmountText(amountOf(remaining), style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold))
            LoanProgressBar(loanPrincipalProgress(paidPrincipal, plan.principal))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LoanMetric("原始本金", amountOf(plan.principal), Modifier.weight(1f))
                LoanMetric("已还本金", amountOf(paidPrincipal), Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LoanMetric("还款进度", "${plan.paidTermCount} / ${terms.size} 期", Modifier.weight(1f))
                LoanMetric("年利率", "${rateText(plan.annualRateNumerator, plan.annualRateDenominator)}%", Modifier.weight(1f))
            }
            nextTerm?.let {
                Text("下次应还 ${amountOf(it.amount)} · ${it.dueDate}", style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "计划利息 ${amountOf(scheduledInterest)} · 已付利息 ${amountOf(paidInterest)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("查看计划与还款", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                AppIcon(AppSymbol.CHEVRON)
            }
        }
    }
}

internal fun remainingLoanPrincipal(plan: FinanceLoanPlan): Money =
    plan.terms
        .asSequence()
        .filter { it.dueState != LoanDueState.PAID }
        .fold(Money(0, plan.principal.scale, plan.principal.currency)) { sum, term -> sum.checkedAdd(term.principal) }

private fun nextLoanTerm(terms: List<LoanTerm>): LoanTerm? =
    terms.firstOrNull { it.dueState == LoanDueState.DUE } ?: terms.firstOrNull { it.dueState != LoanDueState.PAID }

private fun loanPrincipalProgress(
    paid: Money,
    principal: Money,
): Float {
    if (principal.unscaled <= 0) return 0f
    return BigDecimal
        .valueOf(paid.unscaled, paid.scale)
        .divide(BigDecimal.valueOf(principal.unscaled, principal.scale), 6, java.math.RoundingMode.HALF_UP)
        .toFloat()
        .coerceIn(0f, 1f)
}

private fun amountOf(value: Money): String = ledgerAmount(BigDecimal.valueOf(value.unscaled, value.scale), value.currency)

@Composable
private fun LoanProgressBar(ratio: Float) {
    LinearProgressIndicator(
        progress = { ratio },
        modifier = Modifier.fillMaxWidth().height(8.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        trackColor = MaterialTheme.colorScheme.surfaceVariant,
        strokeCap = StrokeCap.Round,
    )
}

@Composable
private fun LoanMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        AmountText(value, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun CreateLoanDialog(
    snapshot: FinanceSnapshot,
    model: FinanceViewModel,
    onDismiss: () -> Unit,
) {
    val createOpId = remember { UUID.randomUUID() }
    val liabilities = snapshot.accounts.filter { it.type == "LIABILITY" && it.archivedAt == null && it.selectable }
    var accountId by remember { mutableStateOf(liabilities.firstOrNull()?.id) }
    val account = liabilities.firstOrNull { it.id == accountId }
    val currency = account?.currency ?: "CNY"
    var principalText by remember { mutableStateOf("") }
    var rateText by remember { mutableStateOf("5") }
    var termText by remember { mutableStateOf("12") }
    var paidText by remember { mutableStateOf("0") }
    var method by remember { mutableStateOf(RepaymentMethod.EQUAL_PAYMENT) }
    var firstDate by remember { mutableStateOf(defaultFirstDueDate()) }
    var disburse by remember { mutableStateOf(false) }
    val cashAccounts = snapshot.accounts.filter { it.type == "ASSET" && it.currency == currency && it.selectable && it.archivedAt == null }
    var cashId by remember { mutableStateOf(cashAccounts.firstOrNull()?.id) }
    var cashAt by remember { mutableStateOf(System.currentTimeMillis()) }
    val inputResult =
        runCatching {
            val principal = moneyInput(principalText, currency).also { it.requirePositive() }
            val (count, paid) = loanTermCounts(termText, paidText)
            val (n, d) = annualPercentRatio(rateText)
            require(isIsoDate(firstDate)) { "请输入有效首期日期" }
            LoanPlanInput(
                id = createOpId,
                accountId = accountId ?: error("请选择负债账户"),
                principal = principal,
                annualRateNumerator = n,
                annualRateDenominator = d,
                termCount = count,
                paidTermCount = paid,
                repaymentType = method,
                firstDueDate = firstDate,
            )
        }
    val schedule = inputResult.getOrNull()?.let { runCatching { LoanCalculator.create(it) }.getOrNull() }
    val enabled =
        !model.state.busy && account != null && schedule != null &&
            (!disburse || (inputResult.getOrNull()?.let { canRecordDisbursement(it.paidTermCount) } == true && cashId != null))
    AlertDialog(
        onDismissRequest = { if (!model.state.busy) onDismiss() },
        title = { Text("新建贷款计划") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (liabilities.isEmpty()) Text("请先创建可用负债账户。")
                Choice("负债账户", liabilities.map { it.id to "${it.name} · ${it.currency}" }, accountId, enabled = !model.state.busy) {
                    accountId =
                        it
                    ; cashId = null
                }
                Field("贷款本金 · $currency", principalText) { principalText = it }
                Field("年利率（百分比）", rateText) { rateText = it }
                Field("期数（1–1200）", termText) { termText = it }
                Field("历史已还期数", paidText) { paidText = it }
                RepaymentMethod.entries.forEach { item ->
                    FilterChip(method == item, onClick = { method = item }, label = { Text(methodLabel(item)) })
                }
                Field("首期日期 YYYY-MM-DD", firstDate) { firstDate = it }
                Text("预览来自贷款计算器；历史已还期次按计划标记，不生成现金流水。")
                inputResult.exceptionOrNull()?.let { Text(it.message ?: "请检查输入", color = MaterialTheme.colorScheme.error) }
                schedule?.let { result ->
                    val principal = inputResult.getOrThrow().principal
                    val totalInterest =
                        result.terms.fold(
                            Money(0, principal.scale, currency),
                        ) { sum, term -> sum.checkedAdd(term.interest) }
                    Text("本金 ${display(principal)} · 计划利息 ${display(totalInterest)}")
                    LazyColumn(Modifier.heightIn(max = 180.dp)) {
                        items(result.terms, key = { it.termIndex }) { term ->
                            Text(
                                "第${term.termIndex}期 ${term.dueDate} · 本金 ${display(
                                    term.principal,
                                )} · 利息 ${display(
                                    term.interest,
                                )}${if (term.termIndex <= inputResult.getOrThrow().paidTermCount) " · 历史已还" else ""}",
                            )
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(
                        disburse,
                        { disburse = it },
                        enabled =
                            inputResult.getOrNull()?.let { canRecordDisbursement(it.paidTermCount) } == true,
                    )
                    Text("同时登记实际到账现金")
                }
                if (disburse) {
                    Choice("到账资产账户", cashAccounts.map { it.id to "${it.name} · ${it.currency}" }, cashId, enabled = !model.state.busy) {
                        cashId =
                            it
                    }
                    DateTimeInput(cashAt, TimeZone.getDefault().id, !model.state.busy, model::showError) { cashAt = it }
                    Text("仅按确认的实际放款记入现金账户；不选择时只创建计划。", style = MaterialTheme.typography.bodySmall)
                }
                model.state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(onClick = {
                val input = inputResult.getOrThrow()
                val movement = if (disburse) LoanDisbursement(cashId!!, cashAt) else null
                model.createLoan(input, movement, opId = createOpId, after = onDismiss)
            }, enabled = enabled) { Text("创建计划") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !model.state.busy) { Text("取消") } },
    )
}

@Composable
private fun LoanDetail(
    snapshot: FinanceSnapshot,
    plan: FinanceLoanPlan,
    model: FinanceViewModel,
    guestWritesEnabled: Boolean,
    loanWritesEnabled: Boolean,
    onBack: () -> Unit,
    onDelete: () -> Unit,
    onArchive: () -> Unit,
) {
    var editingTerm by remember(plan.id) { mutableStateOf<LoanTerm?>(null) }
    var payingTerm by remember(plan.id) { mutableStateOf<LoanTerm?>(null) }
    var rateEditOpen by remember(plan.id) { mutableStateOf(false) }
    val accountName =
        snapshot.accounts
            .firstOrNull { it.id == plan.accountId }
            ?.name
            .orEmpty()
    val terms = plan.terms.sortedBy { it.termIndex }
    val remaining = remainingLoanPrincipal(plan)
    val paidPrincipal =
        plan.payments.fold(
            Money(0, plan.principal.scale, plan.principal.currency),
        ) { sum, payment -> sum.checkedAdd(payment.principal) }
    val paidInterest =
        plan.payments.fold(
            Money(0, plan.principal.scale, plan.principal.currency),
        ) { sum, payment -> sum.checkedAdd(payment.interest) }
    val scheduledInterest =
        terms.fold(
            Money(0, plan.principal.scale, plan.principal.currency),
        ) { sum, term -> sum.checkedAdd(term.interest) }
    val nextTerm = nextLoanTerm(terms)
    val archived = plan.state == "ARCHIVED"
    val method = methodLabel(runCatching { RepaymentMethod.valueOf(plan.repaymentType) }.getOrDefault(RepaymentMethod.EQUAL_PAYMENT))
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { AppIcon(AppSymbol.BACK, "返回贷款列表") }
                    Text(accountName.ifBlank { "贷款详情" }, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    if (archived) {
                        Text(
                            "已归档",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Card(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f)),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "剩余本金 · ${plan.principal.currency}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        AmountText(amountOf(remaining), style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold))
                        LoanProgressBar(loanPrincipalProgress(paidPrincipal, plan.principal))
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            LoanMetric("原始本金", amountOf(plan.principal), Modifier.weight(1f))
                            LoanMetric("已还本金", amountOf(paidPrincipal), Modifier.weight(1f))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            LoanMetric("还款进度", "${plan.paidTermCount} / ${terms.size} 期", Modifier.weight(1f))
                            LoanMetric("已付利息", amountOf(paidInterest), Modifier.weight(1f))
                        }
                    }
                }
                Card(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("下次还款", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (nextTerm != null) {
                            AmountText(
                                amountOf(nextTerm.amount),
                                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                            )
                            Text(
                                nextTerm.dueDate,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            Text("无待还期次", style = MaterialTheme.typography.titleMedium)
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            LoanMetric("年利率", "${rateText(plan.annualRateNumerator, plan.annualRateDenominator)}%", Modifier.weight(1f))
                            LoanMetric("还款方式", method, Modifier.weight(1f))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            LoanMetric("贷款期数", "${terms.size} 期", Modifier.weight(1f))
                            LoanMetric("首期日期", plan.firstDueDate, Modifier.weight(1f))
                        }
                        Text(
                            "计划利息 ${amountOf(scheduledInterest)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (archived) {
                    Text(
                        "此贷款已归档，仅可查看历史与利率记录。",
                        Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Card(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Column {
                        LoanActionRow("调整利率", loanWritesEnabled && plan.state == "ACTIVE") {
                            model.clearLoanPreview()
                            rateEditOpen = true
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        LoanActionRow(
                            if (archived) "恢复贷款计划" else "归档贷款计划",
                            !model.state.busy && guestWritesEnabled && plan.state in setOf("ACTIVE", "ARCHIVED"),
                            onArchive,
                        )
                    }
                }
                model.loanPreviewError?.let { Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error) }
                model.loanPreview?.let { preview ->
                    val affectedTerms = preview.terms.filter { it.termIndex >= preview.affectedFrom }
                    Card(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f)),
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(if (model.loanPreviewRate != null) "利率调整预览" else "期次调整预览", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "从第${preview.affectedFrom}期起 · 本金变化 ${amountOf(
                                    preview.principalDelta,
                                )} · 利息变化 ${amountOf(preview.interestDelta)}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            val oldTerms = preview.oldTerms.associateBy { it.termIndex }
                            affectedTerms.take(6).forEach { row ->
                                val before = oldTerms[row.termIndex]
                                Text(
                                    "第${row.termIndex}期 ${before?.let { amountOf(it.amount) } ?: "—"} → ${amountOf(row.amount)}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            if (affectedTerms.size >
                                6
                            ) {
                                Text(
                                    "…共 ${affectedTerms.size} 期受影响",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (model.loanPreviewRate != null) {
                                Button(
                                    onClick = { model.commitLoanRate(plan, preview) },
                                    enabled = loanWritesEnabled && preview.expectedVersion == plan.localRevision,
                                ) { Text("应用利率预览") }
                            }
                            TextButton(onClick = { model.clearLoanPreview() }, enabled = !model.state.busy) { Text("清除预览") }
                        }
                    }
                }
                Card(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("利率变更记录", style = MaterialTheme.typography.titleMedium)
                        if (plan.rateChanges.isEmpty()) {
                            Text(
                                "暂无利率调整",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        plan.rateChanges.forEach { change ->
                            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                Text(
                                    "第${change.effectiveTerm}期起 · ${rateText(
                                        change.oldRateNumerator,
                                        change.oldRateDenominator,
                                    )}% → ${rateText(change.newRateNumerator, change.newRateDenominator)}%",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    "修订 ${change.fromRevision} → ${change.toRevision}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Text(
                            "利率变更历史暂不支持删除。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "删除后计划不再显示，历史流水按所选策略处理。",
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(
                        onClick = onDelete,
                        enabled = loanWritesEnabled,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Text("删除计划") }
                }
            }
        }
        items(terms, key = { "term:${plan.id}:${it.termIndex}" }) { term ->
            LoanTermCard(
                plan,
                term,
                loanWritesEnabled,
                onPay = { payingTerm = term },
                onAdjust = {
                    model.clearLoanPreview()
                    editingTerm = term
                },
            )
        }
    }
    editingTerm?.let { term -> AdjustLoanTermDialog(plan, term, model, loanWritesEnabled) { editingTerm = null } }
    payingTerm?.let { term -> LoanPaymentDialog(snapshot, plan, term, model) { payingTerm = null } }
    if (rateEditOpen) RateChangeDialog(plan, model, loanWritesEnabled) { rateEditOpen = false }
}

@Composable
private fun LoanActionRow(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AppIcon(AppSymbol.CHEVRON, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LoanTermCard(
    plan: FinanceLoanPlan,
    term: LoanTerm,
    loanWritesEnabled: Boolean,
    onPay: () -> Unit,
    onAdjust: () -> Unit,
) {
    val payment = plan.payments.firstOrNull { it.termIndex == term.termIndex }
    val status =
        when (term.dueState) {
            LoanDueState.PAID -> "已还"
            LoanDueState.NO_DUE -> "无需还款"
            else -> "待还"
        }
    val statusColor =
        when (term.dueState) {
            LoanDueState.PAID -> MaterialTheme.colorScheme.tertiary
            LoanDueState.NO_DUE -> MaterialTheme.colorScheme.onSurfaceVariant
            else -> MaterialTheme.colorScheme.primary
        }
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("第${term.termIndex}期", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Text(status, style = MaterialTheme.typography.labelMedium, color = statusColor)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    term.dueDate,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                AmountText(amountOf(term.amount), style = MaterialTheme.typography.titleMedium)
            }
            Text("本金 ${amountOf(term.principal)} · 利息 ${amountOf(term.interest)}", style = MaterialTheme.typography.bodySmall)
            if (payment != null) {
                Text(
                    "已入账 本金 ${amountOf(payment.principal)} · 利息 ${amountOf(payment.interest)}" +
                        (
                            payment.paidAtMillis?.let {
                                " · ${java.text.SimpleDateFormat(
                                    "yyyy-MM-dd HH:mm",
                                    java.util.Locale.ROOT,
                                ).format(java.util.Date(it))}"
                            }
                                ?: " · 历史已还记录"
                        ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (term.dueState != LoanDueState.PAID) {
                Text(
                    "剩余本金 ${amountOf(term.openingPrincipal)} → ${amountOf(term.closingPrincipal)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (loanWritesEnabled && plan.state == "ACTIVE" && term.dueState != LoanDueState.PAID) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (term.dueState == LoanDueState.DUE) Button(onClick = onPay) { Text("登记还款") }
                    TextButton(onClick = onAdjust) { Text("调整期次") }
                }
            }
        }
    }
}

@Composable
private fun RateChangeDialog(
    plan: FinanceLoanPlan,
    model: FinanceViewModel,
    loanWritesEnabled: Boolean,
    onDismiss: () -> Unit,
) {
    var rateText by remember(plan.id, plan.localRevision) { mutableStateOf(rateText(plan.annualRateNumerator, plan.annualRateDenominator)) }
    var termText by remember(
        plan.id,
        plan.localRevision,
    ) { mutableStateOf((plan.paidTermCount + 1).coerceAtMost(plan.terms.size).toString()) }
    val rate = runCatching { annualPercentRatio(rateText) }.getOrNull()
    val term = termText.toIntOrNull()?.takeIf { it in 1..plan.terms.size && it > plan.paidTermCount }
    val preview = model.loanPreview?.takeIf { model.loanPreviewRate != null }
    AlertDialog(
        onDismissRequest = { if (!model.state.busy) onDismiss() },
        title = { Text("调整年利率") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("当前年利率 ${rateText(plan.annualRateNumerator, plan.annualRateDenominator)}%")
                Field("新年利率（百分比）", rateText) {
                    rateText = it
                    model.clearLoanPreview()
                }
                Field("生效期次", termText) {
                    termText = it
                    model.clearLoanPreview()
                }
                Text("只重算所选期次及未还后续期次；已还历史不变。")
                model.loanPreviewError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                preview?.let {
                    Text("影响自第${it.affectedFrom}期 · 本金变化 ${display(it.principalDelta)} · 利息变化 ${display(it.interestDelta)}")
                    val oldTerms = it.oldTerms.associateBy { old -> old.termIndex }
                    LazyColumn(Modifier.heightIn(max = 180.dp)) {
                        items(
                            it.terms.filter { row ->
                                row.termIndex >= it.affectedFrom
                            },
                            key = { "rate-preview:${it.termIndex}" },
                        ) { row ->
                            Text(
                                "第${row.termIndex}期 ${oldTerms[row.termIndex]?.let { old ->
                                    display(old.amount)
                                } ?: "—"} → ${display(row.amount)}",
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = {
                    if (rate != null && term != null) model.previewLoanRate(plan, term, rate.first, rate.second)
                }, enabled = rate != null && term != null && !model.loanPreviewBusy && !model.state.busy) { Text("计算预览") }
                TextButton(
                    onClick = { preview?.let { model.commitLoanRate(plan, it, after = onDismiss) } },
                    enabled = preview != null && preview.expectedVersion == plan.localRevision && loanWritesEnabled,
                ) { Text("应用预览") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun AdjustLoanTermDialog(
    plan: FinanceLoanPlan,
    term: LoanTerm,
    model: FinanceViewModel,
    loanWritesEnabled: Boolean,
    onDismiss: () -> Unit,
) {
    var principalText by remember(plan.id, term.termIndex) { mutableStateOf(moneyText(term.principal)) }
    var interestText by remember(plan.id, term.termIndex) { mutableStateOf(moneyText(term.interest)) }
    var policy by remember(plan.id, term.termIndex) { mutableStateOf(LoanFuturePolicy.ONLY_THIS_TERM) }
    val pair =
        runCatching {
            moneyInput(principalText, plan.principal.currency) to moneyInput(interestText, plan.principal.currency)
        }.getOrNull()
    val preview = model.loanPreview?.takeIf { model.loanPreviewRate == null && it.affectedFrom == term.termIndex }
    AlertDialog(
        onDismissRequest = { if (!model.state.busy) onDismiss() },
        title = { Text("调整第${term.termIndex}期") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("当前：本金 ${display(term.principal)} · 利息 ${display(term.interest)}")
                Field("本金", principalText) {
                    principalText = it
                    model.clearLoanPreview()
                }
                Field("利息", interestText) {
                    interestText = it
                    model.clearLoanPreview()
                }
                listOf(LoanFuturePolicy.ONLY_THIS_TERM, LoanFuturePolicy.FIXED_FUTURE_AMOUNT).forEach { item ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(policy == item, onClick = {
                            policy = item
                            model.clearLoanPreview()
                        })
                        Text(if (item == LoanFuturePolicy.ONLY_THIS_TERM) "仅本期（本金保持原额）" else "固定未来每期总额")
                    }
                }
                model.loanPreviewError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                preview?.let {
                    Text("本金变化 ${display(it.principalDelta)} · 利息变化 ${display(it.interestDelta)}")
                    val oldTerms = it.oldTerms.associateBy { old -> old.termIndex }
                    it.terms.filter { row -> row.termIndex >= term.termIndex }.forEach { row ->
                        Text(
                            "第${row.termIndex}期 ${oldTerms[row.termIndex]?.let { old ->
                                display(old.amount)
                            } ?: "—"} → ${display(row.amount)}",
                        )
                    }
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(
                    onClick = { pair?.let { model.previewLoanTerm(plan, term.termIndex, it.first, it.second, policy) } },
                    enabled = pair != null && !model.loanPreviewBusy && !model.state.busy,
                ) { Text(if (model.loanPreviewBusy) "预览中…" else "预览") }
                TextButton(
                    onClick = { preview?.let { model.commitLoanTerm(plan, it, policy, after = onDismiss) } },
                    enabled = preview != null && preview.expectedVersion == plan.localRevision && loanWritesEnabled,
                ) { Text("保存预览") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun LoanPaymentDialog(
    snapshot: FinanceSnapshot,
    plan: FinanceLoanPlan,
    term: LoanTerm,
    model: FinanceViewModel,
    onDismiss: () -> Unit,
) {
    val accounts =
        snapshot.accounts.filter {
            it.type == "ASSET" && it.currency == plan.principal.currency && it.selectable &&
                it.archivedAt == null
        }
    var accountId by remember(plan.id, term.termIndex) { mutableStateOf(accounts.firstOrNull()?.id) }
    var amountText by remember(plan.id, term.termIndex) { mutableStateOf(moneyText(term.amount)) }
    var atMillis by remember(plan.id, term.termIndex) { mutableStateOf(System.currentTimeMillis()) }
    val guestWritesEnabled = model.state.accountOwner == null
    val amount = runCatching { moneyInput(amountText, plan.principal.currency) }.getOrNull()
    val actualInterest = amount?.let { runCatching { it.checkedSubtract(term.principal) }.getOrNull() }
    val opId = remember(plan.id, plan.localRevision, term.termIndex) { UUID.randomUUID() }
    val enabled =
        !model.state.busy && accountId != null && amount != null && amount.unscaled >= 0 && actualInterest != null &&
            actualInterest.unscaled >= 0 &&
            atMillis in 0..System.currentTimeMillis() &&
            (amount.unscaled > 0 || (term.principal.unscaled == 0L && term.interest.unscaled == 0L))
    AlertDialog(
        onDismissRequest = { if (!model.state.busy) onDismiss() },
        title = { Text("登记实际还款") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("计划本金 ${display(term.principal)} · 计划利息 ${display(term.interest)}")
                Choice("扣款资产账户", accounts.map { it.id to "${it.name} · ${it.currency}" }, accountId, enabled = !model.state.busy) {
                    accountId =
                        it
                }
                Field("实际还款总额 · ${plan.principal.currency}", amountText) { amountText = it }
                actualInterest?.let { Text("实际利息 ${display(it)}（总额减计划本金）") }
                DateTimeInput(atMillis, TimeZone.getDefault().id, !model.state.busy, model::showError) { atMillis = it }
                if (term.dueState == LoanDueState.NO_DUE) Text("无需还款期不能登记实际还款。")
                if (!guestWritesEnabled) Text("还款通过服务器入账后更新贷款与资金统计。", style = MaterialTheme.typography.bodySmall)
                model.state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(onClick = {
                model.postLoanPayment(plan, term.termIndex, accountId!!, atMillis, amount!!, opId, after = onDismiss)
            }, enabled = enabled) { Text("确认入账") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !model.state.busy) { Text("取消") } },
    )
}

@Composable
internal fun LoanStatisticsSettings(
    snapshot: FinanceSnapshot,
    model: FinanceViewModel,
) {
    val prefs = snapshot.businessPreferences ?: return
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("贷款统计", style = MaterialTheme.typography.titleMedium)
        Text("这些选项只影响统计，不改写资金流水；本金相关预算规则仍由贷款业务规则决定。", style = MaterialTheme.typography.bodySmall)
        LoanPreference("贷款本金计为收入", prefs.loanPrincipalAsIncome, !model.state.busy && !model.state.accountMutationPending) {
            model.setLoanPreferences(prefs.localRevision, it, prefs.loanRepaymentAsExpense, prefs.loanInterestAsExpense)
        }
        LoanPreference("还款总额计为支出", prefs.loanRepaymentAsExpense, !model.state.busy && !model.state.accountMutationPending) {
            model.setLoanPreferences(prefs.localRevision, prefs.loanPrincipalAsIncome, it, prefs.loanInterestAsExpense)
        }
        LoanPreference("实际利息计为支出", prefs.loanInterestAsExpense, !model.state.busy && !model.state.accountMutationPending) {
            model.setLoanPreferences(prefs.localRevision, prefs.loanPrincipalAsIncome, prefs.loanRepaymentAsExpense, it)
        }
    }
}

@Composable
private fun LoanPreference(
    label: String,
    value: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.Checkbox(value, onChange, enabled = enabled)
        Text(label)
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    onValue: (String) -> Unit,
) {
    OutlinedTextField(value, onValue, modifier = Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true)
}

internal fun annualPercentRatio(raw: String): Pair<Long, Long> {
    require(raw.trim().matches(Regex("(?:[0-9]+(?:\\.[0-9]+)?|\\.[0-9]+)"))) { "请输入有效年利率" }
    val value = BigDecimal(raw.trim())
    require(value.signum() > 0) { "年利率须大于 0" }
    val fraction = value.divide(BigDecimal("100")).stripTrailingZeros()
    val numerator = fraction.unscaledValue()
    val denominator = BigInteger.TEN.pow(fraction.scale().coerceAtLeast(0))
    val gcd = numerator.gcd(denominator)
    return (numerator / gcd).longValueExact() to (denominator / gcd).longValueExact()
}

internal fun loanTermCounts(
    termText: String,
    paidText: String,
): Pair<Int, Int> {
    val count = termText.toInt().also { require(it in 1..1200) }
    val paid = paidText.toInt().also { require(it in 0..count) }
    return count to paid
}

internal fun canRecordDisbursement(paidTermCount: Int): Boolean = paidTermCount == 0

internal fun isIsoDate(raw: String): Boolean = LoanCalculator.isValidLoanDate(raw, fourDigitYearOnly = true)

private fun defaultFirstDueDate(): String =
    Calendar
        .getInstance()
        .apply {
            add(Calendar.MONTH, 1)
            set(Calendar.DAY_OF_MONTH, 1)
        }.let { SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(it.time) }

private fun rateText(
    numerator: Long,
    denominator: Long,
): String =
    BigDecimal
        .valueOf(
            numerator,
        ).multiply(
            BigDecimal("100"),
        ).divide(BigDecimal.valueOf(denominator), 8, java.math.RoundingMode.HALF_UP)
        .stripTrailingZeros()
        .toPlainString()

private fun methodLabel(method: RepaymentMethod): String =
    when (method) {
        RepaymentMethod.EQUAL_PAYMENT -> "等额本息"
        RepaymentMethod.EQUAL_PRINCIPAL -> "等额本金"
        RepaymentMethod.INTEREST_ONLY -> "先息后本"
        RepaymentMethod.FLAT_INTEREST -> "等本等息"
    }
