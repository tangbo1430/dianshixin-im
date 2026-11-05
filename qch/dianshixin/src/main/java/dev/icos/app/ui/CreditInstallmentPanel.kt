package dev.icos.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.icos.app.data.local.FinanceAccount
import dev.icos.app.data.local.FinanceCreditProfile
import dev.icos.app.data.local.FinanceInstallmentPlan
import dev.icos.app.data.local.FinanceInstallmentTerm
import dev.icos.app.data.local.FinanceSnapshot
import dev.icos.app.domain.Money
import java.math.BigDecimal
import java.util.TimeZone

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CreditInstallmentPanel(
    snapshot: FinanceSnapshot,
    model: FinanceViewModel,
    modifier: Modifier = Modifier,
) {
    val accounts = snapshot.accounts.filter { it.type == "LIABILITY" && it.selectable && it.archivedAt == null }
    val guestWritesEnabled = model.state.accountOwner == null
    val accountWritesEnabled =
        guestWritesEnabled || (
            !model.state.syncPaused && !model.state.accountMutationPending &&
                !model.state.accountClearPending && !model.state.restoreBusy
        )
    val assetAccounts =
        snapshot.accounts.filter {
            (it.type == "ASSET" || !guestWritesEnabled && it.type in setOf("CASH", "BANK")) && it.selectable && it.archivedAt == null
        }
    var selected by remember { mutableStateOf(accounts.firstOrNull()?.id) }
    var selectedAssetId by remember { mutableStateOf(assetAccounts.firstOrNull()?.id) }
    var paymentAsset by remember { mutableStateOf<FinanceAccount?>(null) }
    var paymentLedger by remember { mutableStateOf<dev.icos.app.data.local.FinanceLedger?>(null) }
    var selectedPayment by remember { mutableStateOf<dev.icos.app.data.local.FinanceCreditPayment?>(null) }
    var dialog by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var interest by remember { mutableStateOf("0") }
    var termCount by remember { mutableStateOf("3") }
    var day by remember { mutableStateOf("20") }
    var repaymentValue by remember { mutableStateOf("5") }
    var rule by remember { mutableStateOf("FIXED_MONTH_DAY") }
    var includeStatementDay by remember { mutableStateOf(true) }
    var timezone by remember { mutableStateOf(TimeZone.getDefault().id) }
    var expectedAccountRevision by remember { mutableStateOf<Long?>(null) }
    var editingAccountId by remember { mutableStateOf<java.util.UUID?>(null) }
    var installmentLedger by remember { mutableStateOf<dev.icos.app.data.local.FinanceLedger?>(null) }
    var reason by remember { mutableStateOf("") }
    var firstDate by remember {
        mutableStateOf(
            java.time.LocalDate
                .now()
                .plusMonths(1)
                .withDayOfMonth(1)
                .toString(),
        )
    }
    // 编辑草稿只存于组合状态，身份/账期变化即丢弃，不写入 Bundle 或磁盘。
    var editingPlan by remember(snapshot.ownerId, snapshot.epochId, selected) { mutableStateOf<FinanceInstallmentPlan?>(null) }
    var posting by remember { mutableStateOf("TERM_BY_TERM") }
    var paymentAllocations by remember { mutableStateOf<Map<java.util.UUID, String>>(emptyMap()) }
    var detailPlanId by remember(snapshot.ownerId, snapshot.epochId, selected) { mutableStateOf<java.util.UUID?>(null) }
    var planSourceFilter by remember { mutableStateOf("ALL") }
    var planStateFilter by remember { mutableStateOf("ALL") }
    val account = accounts.firstOrNull { it.id == selected }
    val profile = snapshot.creditProfiles.firstOrNull { it.accountId == account?.id }
    val available =
        account?.let { row ->
            val delta =
                snapshot.creditCapacityEvents
                    .filter {
                        it.accountId == row.id
                    }.fold(Money(0, 0, row.currency)) { total, event -> total.checkedAdd(event.delta) }
            val limit = profile?.limit ?: Money(0, 0, row.currency)
            runCatching { limit.checkedAdd(delta) }.getOrNull()
        }

    fun money(text: String): Money? =
        runCatching {
            moneyInput(text, account?.currency ?: "CNY", signed = true)
        }.getOrNull()

    fun paymentAllocationMoney(text: String): Money? =
        runCatching {
            val decimal = BigDecimal(text)
            require(decimal.scale() <= 6)
            val scale = maxOf(0, decimal.scale())
            Money(decimal.setScale(scale).movePointRight(scale).longValueExact(), scale, account?.currency ?: "CNY")
        }.getOrNull()
    val allocationTerms =
        snapshot.installmentTerms.filter { term ->
            snapshot.installmentPlans.any { it.id == term.planId && it.accountId == account?.id } && term.state == "POSTED"
        }

    fun remainingDue(term: FinanceInstallmentTerm): Money {
        val allocated =
            snapshot.creditPaymentAllocations
                .filter { it.termId == term.id }
                .fold(Money(0, 0, term.principal.currency)) { total, row -> total.checkedAdd(row.amount) }
        return term.remainingDue ?: term.principal.checkedAdd(term.interest).checkedSubtract(allocated)
    }
    val parsedAllocations =
        runCatching {
            paymentAllocations.filterValues { it.isNotBlank() }.mapValues { (id, raw) ->
                val term = allocationTerms.firstOrNull { it.id == id } ?: error("期次已变化")
                val value = paymentAllocationMoney(raw) ?: error("分配金额格式无效")
                require(value.unscaled > 0 && remainingDue(term).checkedSubtract(value).unscaled >= 0) { "分配金额超过剩余应还" }
                value
            }
        }.getOrNull()
    val allocationTotal =
        parsedAllocations?.values?.let { values ->
            runCatching { values.fold(Money(0, 0, account?.currency ?: "CNY")) { total, value -> total.checkedAdd(value) } }.getOrNull()
        }
    val hasInvalidPaymentAllocation = parsedAllocations == null || allocationTotal == null
    val confirmedPayments = snapshot.creditPayments.filter { it.accountId == account?.id }
    val currentPayment = selectedPayment?.let { draft -> confirmedPayments.firstOrNull { it.eventId == draft.eventId } }
    // 详情按 id 实时解析；身份/账期/账户变化由 remember 键关闭，计划消失时解析为 null 即不再展示。
    val detailPlan =
        detailPlanId?.let { id ->
            snapshot.installmentPlans.firstOrNull {
                it.id == id && it.accountId == account?.id &&
                    it.state != "DELETED"
            }
        }

    LazyColumn(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("信用额度与分期", style = MaterialTheme.typography.titleLarge)
                if (!guestWritesEnabled) {
                    Text(
                        "账号更改保存后等待服务器确认，分期由服务器到期入账。还款先完成转账，确认后再分配到期次。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (accounts.isEmpty()) Text("请先在账户中创建负债账户。")
                accounts.forEach { item -> FilterChip(selected == item.id, onClick = { selected = item.id }, label = { Text(item.name) }) }
                if (account != null) {
                    CreditBillSummary(profile, available, guestWritesEnabled)
                    Card(
                        Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("账户余额表示欠款：${display(account.balance)}")
                            Text(
                                "信用额度是独立额度事件投影，不等于可用额度或本期应还。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            profile?.let {
                                Text(
                                    "账单日 ${it.statementDay} 日 · ${it.repaymentRule} ${it.repaymentValue}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            profile?.rawAvailableCapacity?.let { Text("原始可用额度 ${display(it)}", style = MaterialTheme.typography.bodySmall) }
                            profile?.currentDebt?.let { Text("当前负债 ${display(it)}", style = MaterialTheme.typography.bodySmall) }
                            profile?.let {
                                if (it.rawAvailableCapacity == null) {
                                    Text(
                                        if (guestWritesEnabled) "游客账期按本地事件展示。" else "服务器账期汇总暂不可用；下方仅展示本机规范镜像。",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            dialog = "configure"
                            editingAccountId = account.id
                            expectedAccountRevision = account.localRevision
                            amount = profile?.limit?.let(::moneyText).orEmpty()
                            day = (profile?.statementDay ?: 20).toString()
                            repaymentValue = (profile?.repaymentValue ?: 5).toString()
                            rule = profile?.repaymentRule ?: "FIXED_MONTH_DAY"
                            includeStatementDay = profile?.includeStatementDay ?: true
                            timezone =
                                profile?.timezone ?: TimeZone.getDefault().id
                        }, enabled = accountWritesEnabled && !model.state.busy) { Text("配置额度/账期") }
                        OutlinedButton(onClick = {
                            dialog = "capacity"
                            editingAccountId = account.id
                            expectedAccountRevision = account.localRevision
                            amount = ""
                            reason = ""
                        }, enabled = accountWritesEnabled && !model.state.busy && profile != null) { Text("调整额度") }
                        Button(
                            onClick = {
                                dialog = "installment"
                                editingAccountId = account.id
                                expectedAccountRevision = account.localRevision
                                installmentLedger = snapshot.ledgers.firstOrNull { it.id == snapshot.ledgerId }
                                amount = ""
                                interest = "0"
                                timezone = profile?.timezone ?: TimeZone.getDefault().id
                            },
                            enabled =
                                accountWritesEnabled && !model.state.busy && profile != null &&
                                    snapshot.ledgers.any {
                                        it.id == snapshot.ledgerId && it.currency == account.currency &&
                                            it.archivedAt == null
                                    },
                        ) { AppIcon(AppSymbol.ADD); Text("新建消费分期") }
                        Button(
                            onClick = {
                                dialog = "payment"
                                amount = ""
                                paymentAllocations = emptyMap()
                                editingAccountId = account.id
                                expectedAccountRevision = account.localRevision
                                paymentAsset = assetAccounts.firstOrNull { it.id == selectedAssetId && it.currency == account.currency }
                                    ?: assetAccounts.firstOrNull { it.currency == account.currency }
                                selectedAssetId = paymentAsset?.id
                                paymentLedger = snapshot.ledgers.firstOrNull { it.id == snapshot.ledgerId }
                                timezone = profile?.timezone ?: TimeZone.getDefault().id
                            },
                            enabled =
                                accountWritesEnabled && !model.state.busy && profile != null &&
                                    assetAccounts.any { it.currency == account.currency } &&
                                    snapshot.ledgers.any {
                                        it.id == snapshot.ledgerId && it.archivedAt == null && it.currency == account.currency
                                    },
                        ) { Text(if (guestWritesEnabled) "还款与分配" else "信用还款") }
                        if (!guestWritesEnabled) {
                            OutlinedButton(
                                onClick = {
                                    dialog = "allocate"
                                    paymentAllocations = emptyMap()
                                    selectedPayment = confirmedPayments.firstOrNull()
                                    editingAccountId = account.id
                                    expectedAccountRevision = account.localRevision
                                },
                                enabled =
                                    accountWritesEnabled && !model.state.busy && confirmedPayments.isNotEmpty() &&
                                        allocationTerms.any { remainingDue(it).unscaled > 0 },
                            ) { Text("分配已确认还款") }
                        }
                    }
                }
            }
        }
        val allPlans = snapshot.installmentPlans.filter { it.accountId == account?.id && it.state != "DELETED" }
        val planSourceTypes = allPlans.map { it.sourceType }.distinct()
        val plans =
            allPlans.filter { plan ->
                (planSourceFilter == "ALL" || planSourceFilter !in planSourceTypes || plan.sourceType == planSourceFilter) &&
                    (
                        planStateFilter == "ALL" ||
                            when (planStateFilter) {
                                "ACTIVE" -> plan.state == "ACTIVE"
                                "ENDED" -> plan.state == "COMPLETED" || plan.state == "TERMINATED"
                                else -> true
                            }
                    )
            }
        item {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(planStateFilter == "ALL", { planStateFilter = "ALL" }, label = { Text("全部") })
                    FilterChip(planStateFilter == "ACTIVE", { planStateFilter = "ACTIVE" }, label = { Text("进行中") })
                    FilterChip(planStateFilter == "ENDED", { planStateFilter = "ENDED" }, label = { Text("已结束") })
                }
                if (planSourceTypes.size > 1) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(planSourceFilter == "ALL", { planSourceFilter = "ALL" }, label = { Text("全部来源") })
                        planSourceTypes.forEach { sourceType ->
                            FilterChip(planSourceFilter == sourceType, { planSourceFilter = sourceType }, label = {
                                Text(installmentSourceLabel(sourceType))
                            })
                        }
                    }
                }
                when {
                    allPlans.isEmpty() -> {
                        Text("暂无分期计划")
                    }

                    plans.isEmpty() -> {
                        Text("当前筛选下没有分期计划")
                    }

                    else -> {
                        Text("筛选结果 ${plans.size} 个计划", style = MaterialTheme.typography.bodySmall)
                        val pendingTerms =
                            plans.flatMap { plan ->
                                snapshot.installmentTerms.filter { it.planId == plan.id && it.state == "SCHEDULED" }
                            }
                        InstallmentTotals(
                            "待入账本金与利息",
                            pendingTerms,
                            pendingTerms.map { it.planId }.toSet().associateWith { id ->
                                allPlans.firstOrNull { it.id == id }?.principalPosting ?: ""
                            },
                        )
                    }
                }
            }
        }
        items(plans, key = { it.id }) { plan ->
            val terms = snapshot.installmentTerms.filter { it.planId == plan.id }.sortedBy { it.termIndex }
            Card(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(
                        installmentSourceLabel(plan.sourceType),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text("本金 ${display(plan.principal)}", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
                    Text(
                        "利息 ${display(
                            plan.interest,
                        )} · ${plan.termCount} 期 · ${principalPostingLabel(
                            plan.principalPosting,
                        )} · ${installmentPlanStateLabel(plan.state)} · ${installmentExecutorLabel(plan.executor)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = { detailPlanId = plan.id }, enabled = !model.state.busy) { Text("查看计划详情") }
                    Text(
                        "已入账 ${terms.count { it.state == "POSTED" || it.state == "PAID" }} 期 · " +
                            "待入账 ${terms.count { it.state == "SCHEDULED" }} 期",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    InstallmentTotals("待入账本金与利息", terms.filter { it.state == "SCHEDULED" }, mapOf(plan.id to plan.principalPosting))
                    if (plan.executor in setOf("LOCAL", "SERVER") && plan.state == "ACTIVE") {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                onClick = {
                                    editingPlan = plan
                                    amount = moneyText(plan.principal)
                                    interest = moneyText(plan.interest)
                                    termCount =
                                        plan.termCount.toString()
                                    firstDate = terms.firstOrNull { it.state != "SUPERSEDED" }?.dueDate ?: firstDate
                                },
                                enabled =
                                    accountWritesEnabled && !model.state.busy && terms.any { it.state != "SUPERSEDED" } &&
                                        terms.filter { it.state != "SUPERSEDED" }.all { it.state == "SCHEDULED" },
                            ) { Text("修改计划") }
                            TextButton(
                                onClick = { model.deleteInstallment(plan) },
                                enabled = accountWritesEnabled && !model.state.busy,
                            ) { Text("停止未入账期次（保留历史）") }
                        }
                    }
                }
            }
        }
    }
    detailPlan?.let { plan ->
        val detailTerms = snapshot.installmentTerms.filter { it.planId == plan.id }.sortedBy { it.termIndex }
        val pendingTerms = detailTerms.filter { it.state == "SCHEDULED" }
        val postedTerms = detailTerms.filter { it.state == "POSTED" || it.state == "PAID" }
        val stoppedTerms = detailTerms.filter { it.state == "SUPERSEDED" }
        val unknownTerms = detailTerms.filter { it.state !in setOf("SCHEDULED", "POSTED", "PAID", "SUPERSEDED") }
        EditorPage(
            onDismissRequest = { if (!model.state.busy) detailPlanId = null },
            title = { Text("分期计划详情") },
            text = {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "${installmentSourceLabel(plan.sourceType)} · ${installmentPlanStateLabel(plan.state)}",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    )
                    Text("原始本金 ${display(plan.principal)} · 总利息 ${display(plan.interest)} · ${plan.termCount} 期")
                    Text(
                        "沿用计划创建时设置的入账时区。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "入本金方式 ${principalPostingLabel(plan.principalPosting)} · 执行方 ${installmentExecutorLabel(plan.executor)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "待入账 ${pendingTerms.size} 期 · 已入账 ${postedTerms.size} 期" +
                            if (stoppedTerms.isEmpty()) "" else " · 已停止 ${stoppedTerms.size} 期",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    InstallmentTotals("待入账本金与利息", pendingTerms, mapOf(plan.id to plan.principalPosting))
                    InstallmentTotals("已入账期次本金与利息（不含创建时提前入账本金）", postedTerms, mapOf(plan.id to plan.principalPosting))
                    if (detailTerms.isEmpty()) Text("该计划暂无可展示期次。")
                    LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(detailTerms, key = { it.id }) { term ->
                            Text(
                                "${term.termIndex}期 · ${term.dueDate} · 本金 ${display(
                                    term.principal,
                                )} · 利息 ${display(term.interest)} · ${installmentTermStateLabel(term.state)}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (term.state == "SCHEDULED" && plan.executor == "LOCAL") {
                                Button(
                                    onClick = { model.postInstallmentTerm(term, plan) },
                                    enabled = guestWritesEnabled && !model.state.busy,
                                ) { Text("入账 ${term.termIndex}期") }
                            }
                        }
                    }
                    if (unknownTerms.isNotEmpty()) {
                        Text(
                            "未知状态期次 ${unknownTerms.size} 期已原样列出（只读），不计入上方合计。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (stoppedTerms.isNotEmpty()) {
                        Text(
                            "已停止期次 ${stoppedTerms.size} 期，保留历史；停止期次不参与待入账合计。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (plan.executor in setOf("LOCAL", "SERVER") && plan.state == "ACTIVE") {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Button(
                                onClick = {
                                    editingPlan = plan
                                    amount = moneyText(plan.principal)
                                    interest = moneyText(plan.interest)
                                    termCount = plan.termCount.toString()
                                    firstDate = detailTerms.firstOrNull { it.state != "SUPERSEDED" }?.dueDate ?: firstDate
                                    detailPlanId = null
                                },
                                enabled =
                                    accountWritesEnabled && !model.state.busy && detailTerms.any { it.state != "SUPERSEDED" } &&
                                        detailTerms.filter { it.state != "SUPERSEDED" }.all { it.state == "SCHEDULED" },
                            ) { Text("修改计划") }
                            Button(
                                onClick = {
                                    detailPlanId = null
                                    model.deleteInstallment(plan)
                                },
                                enabled = accountWritesEnabled && !model.state.busy,
                            ) { Text("停止未入账期次（保留历史）") }
                        }
                    }
                    Text(
                        when (plan.executor) {
                            "SERVER" -> "服务器执行：本机不代入账，期次状态以服务器确认为准。还款与分配仍在信用账户页完成。"
                            "LOCAL" -> "本机执行：仅已到期的待入账期次可手动入账。还款与分配仍在信用账户页完成。"
                            else -> "未知执行方：本页只读展示计划状态。还款与分配仍在信用账户页完成。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    model.state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                Button(onClick = { detailPlanId = null }, enabled = !model.state.busy) { Text("关闭") }
            },
            dismissButton = {
                Button(onClick = { detailPlanId = null }, enabled = !model.state.busy) { Text("返回") }
            },
        )
    }
    editingPlan?.let { plan ->
        val original = snapshot.installmentPlans.firstOrNull { it.id == plan.id }
        // 只以当前未停止期次作为预填与 dirty 基准；历史 SUPERSEDED 期次不影响再次修改。
        val editTerms = snapshot.installmentTerms.filter { it.planId == plan.id && it.state != "SUPERSEDED" }.sortedBy { it.termIndex }
        val dirty =
            original != null && (
                !sameMoney(money(interest), original.interest) ||
                    termCount != original.termCount.toString() ||
                    firstDate != (editTerms.firstOrNull()?.dueDate ?: "")
            )
        val dismiss = rememberEditorDismiss(dirty, !model.state.busy) { editingPlan = null }
        EditorPage(
            onDismissRequest = dismiss,
            title = { Text("修改未入账分期") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("分期本金 ${display(plan.principal)}（保持不变）")
                    OutlinedTextField(interest, { interest = it }, label = { Text("总利息") }, singleLine = true)
                    OutlinedTextField(termCount, { termCount = it }, label = { Text("期数") }, singleLine = true)
                    OutlinedTextField(firstDate, { firstDate = it }, label = { Text("首期日期 YYYY-MM-DD") }, singleLine = true)
                    Text(
                        "沿用计划原有入账时区；此处修改利息、期数与首期日期。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val previewLast =
                        runCatching {
                            java.time.LocalDate
                                .parse(firstDate)
                                .plusMonths(((termCount.toIntOrNull() ?: 1) - 1).toLong())
                        }.getOrNull()
                    val dateInputValid = isIsoDate(firstDate) && termCount.toIntOrNull() in 1..1200
                    Text(
                        if (dateInputValid && previewLast != null) {
                            "首期 $firstDate · 末期预计 $previewLast（按每月一期预览，保存前未生成）"
                        } else {
                            "首期日期需为 YYYY-MM-DD，期数需为 1–1200。"
                        },
                        color =
                            if (dateInputValid && previewLast != null) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text("当前期次均未入账时才可修改；旧期次保留历史。")
                    if (snapshot.installmentPlans.none { it.id == plan.id && it.localRevision == plan.localRevision }) {
                        Text("计划版本已变化，请取消后重新打开。", color = MaterialTheme.colorScheme.error)
                    }
                    model.state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val nextInterest = money(interest)
                        if (nextInterest != null) {
                            model.updateInstallment(plan, nextInterest, termCount.toIntOrNull() ?: 0, firstDate) {
                                if (editingPlan?.id == plan.id) editingPlan = null
                            }
                        }
                    },
                    enabled =
                        accountWritesEnabled && !model.state.busy && money(interest)?.unscaled?.let { it >= 0 } == true &&
                            termCount.toIntOrNull() in 1..1200 && isIsoDate(firstDate) &&
                            snapshot.installmentPlans.any { it.id == plan.id && it.localRevision == plan.localRevision },
                    colors = primaryButtonColors(),
                ) { Text("更新期次") }
            },
            dismissButton = { TextButton(onClick = dismiss, enabled = !model.state.busy) { Text("取消") } },
        )
    }
    if (dialog.isNotEmpty() && account != null) {
        EditorPage(
            onDismissRequest = { if (!model.state.busy) dialog = "" },
            title = {
                Text(
                    when (dialog) {
                        "configure" -> "配置额度与账期"
                        "capacity" -> "调整信用额度"
                        "installment" -> "新建消费分期"
                        "allocate" -> "分配已确认还款"
                        else -> "信用账户还款"
                    },
                )
            },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (dialog == "configure") {
                        OutlinedTextField(amount, { amount = it }, label = { Text("信用额度") }, singleLine = true)
                        OutlinedTextField(day, { day = it }, label = { Text("账单日 1–31") }, singleLine = true)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(includeStatementDay, { includeStatementDay = true }, label = { Text("包含账单日") })
                            FilterChip(!includeStatementDay, { includeStatementDay = false }, label = { Text("不包含账单日") })
                        }
                        OutlinedTextField(timezone, { timezone = it }, label = { Text("时区（IANA）") }, singleLine = true)
                        OutlinedTextField(repaymentValue, { repaymentValue = it }, label = {
                            Text(
                                if (rule ==
                                    "FIXED_MONTH_DAY"
                                ) {
                                    "还款日 1–31"
                                } else {
                                    "账单日后天数"
                                },
                            )
                        }, singleLine = true)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(rule == "FIXED_MONTH_DAY", { rule = "FIXED_MONTH_DAY" }, label = { Text("每月固定日") })
                            FilterChip(rule == "DAYS_AFTER_STATEMENT", { rule = "DAYS_AFTER_STATEMENT" }, label = { Text("账单后天数") })
                        }
                    } else if (dialog == "capacity") {
                        OutlinedTextField(amount, { amount = it }, label = { Text("额度变化，可正可负") }, singleLine = true)
                        OutlinedTextField(reason, { reason = it }, label = { Text("原因") })
                        Text("调整独立授信额度，不改变欠款、收支或预算。")
                    } else if (dialog == "installment") {
                        OutlinedTextField(amount, { amount = it }, label = { Text("分期本金") }, singleLine = true)
                        OutlinedTextField(interest, { interest = it }, label = { Text("总利息") }, singleLine = true)
                        OutlinedTextField(termCount, { termCount = it }, label = { Text("期数 1–1200") }, singleLine = true)
                        OutlinedTextField(firstDate, { firstDate = it }, label = { Text("首期日期 YYYY-MM-DD") }, singleLine = true)
                        OutlinedTextField(timezone, { timezone = it }, label = { Text("入账时区（IANA）") }, singleLine = true)
                        Text("记入账本：${installmentLedger?.name ?: "不可用"}")
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(posting == "TERM_BY_TERM", { posting = "TERM_BY_TERM" }, label = { Text("逐期入本金") })
                            FilterChip(posting == "UPFRONT", { posting = "UPFRONT" }, label = { Text("本金提前入账") })
                        }
                    } else {
                        if (dialog == "payment") {
                            OutlinedTextField(amount, { amount = it }, label = { Text("实际还款金额") }, singleLine = true)
                            assetAccounts.filter { it.currency == account.currency }.forEach { asset ->
                                FilterChip(selectedAssetId == asset.id, onClick = {
                                    selectedAssetId = asset.id
                                    paymentAsset = asset
                                }, label = { Text("${asset.name} · ${display(asset.balance)}") })
                            }
                            if (!guestWritesEnabled) {
                                Text("记入账本：${paymentLedger?.name ?: "不可用"}")
                                Text("本次保存将转出资金并减少负债。确认后，可通过“分配已确认还款”安排期次；重试同步沿用原还款。")
                            }
                        } else {
                            confirmedPayments.forEach { payment ->
                                val date =
                                    java.time.Instant
                                        .ofEpochMilli(payment.occurredAtMillis)
                                        .atZone(java.time.ZoneId.systemDefault())
                                        .toLocalDate()
                                FilterChip(selectedPayment?.eventId == payment.eventId, onClick = {
                                    selectedPayment = payment
                                    paymentAllocations = emptyMap()
                                }, label = {
                                    Text(
                                        "$date · ${display(
                                            payment.amount,
                                        )} · 可分配 ${display(payment.remainingToAllocate)} · ${payment.recordId.toString().take(8)}",
                                    )
                                })
                            }
                            if (currentPayment == null) Text("所选还款已变化，请重新选择。", color = MaterialTheme.colorScheme.error)
                            Text("本次只分配已确认还款，不再转出资金。")
                        }
                        if (guestWritesEnabled || dialog == "allocate") {
                            allocationTerms.forEach { term ->
                                val due = remainingDue(term)
                                val text = paymentAllocations[term.id].orEmpty()
                                val paidAlready =
                                    snapshot.creditPaymentAllocations
                                        .filter {
                                            it.termId == term.id
                                        }.fold(Money(0, 0, due.currency)) { total, allocation -> total.checkedAdd(allocation.amount) }
                                val dueAfterDraft =
                                    runCatching {
                                        due.checkedSubtract(
                                            if (text.isBlank()) {
                                                Money(0, 0, due.currency)
                                            } else {
                                                paymentAllocationMoney(text)
                                                    ?: error("金额无效")
                                            },
                                        )
                                    }.getOrNull()
                                Text(
                                    "${term.termIndex}期剩余 ${display(
                                        due,
                                    )} · 累计分配 ${display(paidAlready)} · 本次后 ${dueAfterDraft?.let(::display) ?: "金额无效"}",
                                )
                                OutlinedTextField(
                                    text,
                                    { paymentAllocations = paymentAllocations + (term.id to it) },
                                    label = { Text("分配金额") },
                                    singleLine = true,
                                )
                            }
                        }
                        if ((guestWritesEnabled || dialog == "allocate") && hasInvalidPaymentAllocation) {
                            Text("分配金额无效或超过期次剩余应还，请修正后再保存。", color = MaterialTheme.colorScheme.error)
                        }
                        if (dialog == "allocate") Text("本次分配合计 ${allocationTotal?.let(::display) ?: "金额无效"}")
                        Text("还款从资产账户转出并等额减少负债；未分配金额保留为预付款。")
                    }
                    if (expectedAccountRevision != account.localRevision) {
                        Text("账户版本已变化，请取消后重新打开表单。", color = MaterialTheme.colorScheme.error)
                    }
                    model.state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        when (dialog) {
                            "configure" -> {
                                money(amount)?.let { value ->
                                    val revision = expectedAccountRevision
                                    if (editingAccountId == account.id && revision != null) {
                                        model.configureCredit(
                                            account,
                                            revision,
                                            value,
                                            day.toIntOrNull() ?: 0,
                                            includeStatementDay,
                                            rule,
                                            repaymentValue.toIntOrNull() ?: 0,
                                            timezone,
                                        ) {
                                            dialog =
                                                ""
                                        }
                                    }
                                }
                            }

                            "capacity" -> {
                                money(amount)?.let { value ->
                                    val revision = expectedAccountRevision
                                    if (editingAccountId == account.id &&
                                        revision != null
                                    ) {
                                        model.adjustCreditCapacity(account, revision, value, reason) { dialog = "" }
                                    }
                                }
                            }

                            "installment" -> {
                                val principal = money(amount)
                                val fee = money(interest)
                                val ledger = installmentLedger
                                val revision = expectedAccountRevision
                                if (principal != null && fee != null && ledger != null && revision != null &&
                                    editingAccountId == account.id
                                ) {
                                    model.createInstallment(
                                        account,
                                        revision,
                                        ledger,
                                        principal,
                                        fee,
                                        termCount.toIntOrNull() ?: 0,
                                        firstDate,
                                        timezone,
                                        posting,
                                        "TERM_BY_TERM",
                                        "LAST_TERM",
                                    ) { dialog = "" }
                                }
                            }

                            "payment" -> {
                                money(amount)?.let { value ->
                                    val asset = paymentAsset
                                    val ledger = paymentLedger
                                    val revision = expectedAccountRevision
                                    val allocations = if (guestWritesEnabled) parsedAllocations else emptyMap()
                                    if (asset != null && ledger != null && revision != null && allocations != null) {
                                        model.payCredit(account, revision, asset, ledger, value, timezone, allocations) { dialog = "" }
                                    }
                                }
                            }

                            "allocate" -> {
                                val payment = currentPayment
                                val revision = expectedAccountRevision
                                val allocations = parsedAllocations
                                if (payment != null && revision != null && allocations != null) {
                                    model.allocateCreditPayment(account, revision, payment.eventId, allocations) { dialog = "" }
                                }
                            }
                        }
                    },
                    enabled =
                        !model.state.busy &&
                            when (dialog) {
                                "configure" -> {
                                    accountWritesEnabled && editingAccountId == account.id &&
                                        expectedAccountRevision == account.localRevision &&
                                        money(amount)?.unscaled?.let { it >= 0 } == true && day.toIntOrNull() in 1..31 &&
                                        repaymentValue.toIntOrNull()?.let {
                                            if (rule ==
                                                "FIXED_MONTH_DAY"
                                            ) {
                                                it in 1..31
                                            } else {
                                                it in 1..366
                                            }
                                        } ==
                                        true &&
                                        timezone.length <= 128 && runCatching { java.time.ZoneId.of(timezone) }.isSuccess
                                }

                                "capacity" -> {
                                    accountWritesEnabled && editingAccountId == account.id &&
                                        expectedAccountRevision == account.localRevision &&
                                        money(amount)?.unscaled?.let { it != 0L } == true && reason.isNotBlank() &&
                                        reason.trim().toByteArray(Charsets.UTF_8).size <= 512
                                }

                                "installment" -> {
                                    accountWritesEnabled && editingAccountId == account.id &&
                                        expectedAccountRevision == account.localRevision &&
                                        installmentLedger?.let { draft ->
                                            snapshot.ledgers.any {
                                                it.id == draft.id &&
                                                    it.localRevision == draft.localRevision &&
                                                    it.archivedAt == null &&
                                                    it.currency == account.currency
                                            }
                                        } ==
                                        true &&
                                        money(amount)?.unscaled?.let { it > 0 } == true &&
                                        money(interest)?.unscaled?.let { it >= 0 } == true &&
                                        termCount.toIntOrNull() in 1..1200 && isIsoDate(firstDate) && timezone.length <= 128 &&
                                        runCatching { java.time.ZoneId.of(timezone) }.isSuccess
                                }

                                "allocate" -> {
                                    !guestWritesEnabled && accountWritesEnabled && editingAccountId == account.id &&
                                        expectedAccountRevision == account.localRevision && currentPayment != null &&
                                        !hasInvalidPaymentAllocation &&
                                        !parsedAllocations.isNullOrEmpty() && allocationTotal?.let { total ->
                                            runCatching {
                                                currentPayment.remainingToAllocate
                                                    .checkedSubtract(
                                                        total,
                                                    ).unscaled >= 0
                                            }.getOrDefault(false)
                                        } == true
                                }

                                else -> {
                                    accountWritesEnabled && editingAccountId == account.id &&
                                        expectedAccountRevision == account.localRevision &&
                                        money(amount)?.unscaled?.let { it > 0 } == true &&
                                        paymentAsset?.let { draft ->
                                            assetAccounts.any {
                                                it.id == draft.id && it.localRevision == draft.localRevision &&
                                                    it.currency == account.currency
                                            }
                                        } ==
                                        true &&
                                        paymentLedger?.let { draft ->
                                            snapshot.ledgers.any {
                                                it.id == draft.id && it.localRevision == draft.localRevision &&
                                                    it.archivedAt == null &&
                                                    it.currency == account.currency
                                            }
                                        } ==
                                        true &&
                                        (
                                            !guestWritesEnabled || !hasInvalidPaymentAllocation && allocationTotal?.let { total ->
                                                money(
                                                    amount,
                                                )?.let { runCatching { it.checkedSubtract(total).unscaled >= 0 }.getOrDefault(false) }
                                            } == true
                                        )
                                }
                            },
                    colors = primaryButtonColors(),
                ) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { dialog = "" }, enabled = !model.state.busy) { Text("取消") } },
        )
    }
}

private fun sameMoney(
    value: Money?,
    expected: Money,
): Boolean =
    value != null && value.currency == expected.currency &&
        BigDecimal.valueOf(value.unscaled, value.scale).compareTo(BigDecimal.valueOf(expected.unscaled, expected.scale)) == 0

private fun installmentSourceLabel(sourceType: String): String =
    when (sourceType) {
        "CONSUMPTION" -> "消费分期"
        else -> sourceType
    }

private fun installmentPlanStateLabel(state: String): String =
    when (state) {
        "ACTIVE" -> "进行中"
        "COMPLETED" -> "已完成"
        "TERMINATED" -> "已停止"
        else -> state
    }

private fun installmentTermStateLabel(state: String): String =
    when (state) {
        "SCHEDULED" -> "待入账"
        "POSTED" -> "已入账"
        "PAID" -> "已还清"
        "SUPERSEDED" -> "已停止"
        else -> state
    }

private fun principalPostingLabel(posting: String): String =
    when (posting) {
        "TERM_BY_TERM" -> "逐期入本金"
        "UPFRONT" -> "本金提前入账"
        else -> posting
    }

private fun installmentExecutorLabel(executor: String): String =
    when (executor) {
        "LOCAL" -> "本机执行"
        "SERVER" -> "服务器执行"
        else -> executor
    }

internal data class InstallmentMoneyTotal(
    val currency: String,
    val principal: BigDecimal,
    val interest: BigDecimal,
    // 未知入本金方式时无法判定本金是否已入账，不用零冒充。
    val principalUnconfirmed: Boolean,
)

// 本金是否待入账取决于计划的入本金方式：UPFRONT 在创建时已入本金，期次之后只入利息；TERM_BY_TERM 才按期入本金。
internal fun installmentMoneyTotals(
    terms: List<FinanceInstallmentTerm>,
    principalPosting: (FinanceInstallmentTerm) -> String?,
): List<InstallmentMoneyTotal> =
    terms.groupBy { it.principal.currency }.map { (currency, rows) ->
        var principal = BigDecimal.ZERO
        var interest = BigDecimal.ZERO
        var unconfirmed = false
        rows.forEach { term ->
            interest += BigDecimal.valueOf(term.interest.unscaled, term.interest.scale)
            when (principalPosting(term)) {
                "TERM_BY_TERM" -> principal += BigDecimal.valueOf(term.principal.unscaled, term.principal.scale)
                "UPFRONT" -> Unit
                else -> unconfirmed = true
            }
        }
        InstallmentMoneyTotal(currency, principal, interest, unconfirmed)
    }

@Composable
private fun InstallmentTotals(
    label: String,
    terms: List<FinanceInstallmentTerm>,
    postingByPlan: Map<java.util.UUID, String>,
) {
    if (terms.isEmpty()) {
        Text(
            "$label：无期次",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    installmentMoneyTotals(terms) { postingByPlan[it.planId] }.forEach { total ->
        Text(
            if (total.principalUnconfirmed) {
                "$label：本金不可确认（未知入本金方式） · 利息 ${ledgerAmount(total.interest, total.currency)}（${total.currency}）"
            } else {
                "$label：本金 ${ledgerAmount(total.principal, total.currency)} · " +
                    "利息 ${ledgerAmount(total.interest, total.currency)}（${total.currency}）"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CreditBillSummary(
    profile: FinanceCreditProfile?,
    available: Money?,
    guestWritesEnabled: Boolean,
) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = summaryCardColor()),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                if (profile?.statementAmount != null) "本期应还" else "信用账号",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                profile?.statementAmount?.let(::display) ?: "—",
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            )
            profile?.let {
                Text(
                    "账单日 ${it.statementDate ?: "—"} · 还款日 ${it.repaymentDate ?: "—"}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            val limit = profile?.limit
            if (limit != null) {
                Text(
                    "总额度 ${display(limit)}" +
                        (available?.let { " · ${if (guestWritesEnabled) "游客" else "规范镜像"}额度事件合计 ${display(it)}" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (limit.unscaled > 0 && available != null) {
                    val ratio =
                        BigDecimal
                            .valueOf(available.unscaled, available.scale)
                            .divide(BigDecimal.valueOf(limit.unscaled, limit.scale), 4, java.math.RoundingMode.HALF_UP)
                            .toFloat()
                            .coerceIn(0f, 1f)
                    LinearProgressIndicator(
                        progress = { ratio },
                        modifier = Modifier.fillMaxWidth().height(8.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        strokeCap = StrokeCap.Round,
                    )
                }
            }
        }
    }
}
