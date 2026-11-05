package dev.icos.app.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.icos.app.data.local.FinanceAccount
import dev.icos.app.data.local.FinanceAccountGroup
import dev.icos.app.data.local.FinanceCategory
import dev.icos.app.data.local.FinanceRecord
import dev.icos.app.data.local.FinanceSnapshot
import dev.icos.app.domain.Money
import dev.icos.app.domain.PeriodResolver
import dev.icos.app.domain.RecordInput
import dev.icos.app.domain.ReportPeriod
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.time.YearMonth
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * P27 账户详情：当前余额、资产纳入状态、按统计时区选月的账户收支，以及本账户相关账单。
 * 统计只使用已明确归属的事实，无法归属的条目明确提示；当前余额不冒充月末余额。
 */
@Composable
internal fun AccountDetailPage(
    account: FinanceAccount,
    group: FinanceAccountGroup?,
    snapshot: FinanceSnapshot,
    statisticsZone: String,
    amountsHidden: Boolean,
    onToggleAmountsHidden: () -> Unit,
    onRecordClick: (FinanceRecord) -> Unit,
    canEdit: Boolean,
    canTransfer: Boolean,
    onClose: () -> Unit,
    onEdit: () -> Unit,
    onTransfer: () -> Unit,
) {
    val zone = remember(statisticsZone) { ZoneId.of(statisticsZone) }
    var monthKey by rememberSaveable(account.id) { mutableStateOf(YearMonth.now(zone).toString()) }
    val month = YearMonth.parse(monthKey)
    val range =
        remember(monthKey, statisticsZone) {
            PeriodResolver.range(
                ReportPeriod.MONTH,
                month
                    .atDay(1)
                    .atStartOfDay(zone)
                    .toInstant()
                    .toEpochMilli(),
                statisticsZone,
            )
        }
    val accountRecords = remember(snapshot.records, account.id) { accountLinkedRecords(snapshot.records, account.id) }
    val monthRecords = remember(accountRecords, range) { accountRecords.filter { range.contains(it.occurredAtMillis) } }
    val categories =
        remember(snapshot.categories, snapshot.allCategories) { (snapshot.categories + snapshot.allCategories).distinctBy { it.id } }
    val categoryNames = remember(categories) { categories.associate { it.id to it.name } }
    val totals =
        remember(snapshot.statisticEntries, range, account.id, account.currency) {
            val attributed =
                snapshot.statisticEntries.mapNotNull { entry ->
                    entry.accountAmounts?.get(account.id)?.let { entry.copy(amount = it) }
                }
            ledgerMonthSummaries(attributed, range, account.currency)
        }
    val unattributedCount =
        remember(snapshot.statisticEntries, range) {
            snapshot.statisticEntries.count { it.accountAmounts == null && range.contains(it.occurredAtMillis) }
        }
    val confirmedCount =
        remember(snapshot.statisticEntries, range, account.id) {
            snapshot.statisticEntries.count { it.accountAmounts?.containsKey(account.id) == true && range.contains(it.occurredAtMillis) }
        }
    val context = LocalContext.current.applicationContext
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    EditorPage(
        onDismissRequest = onClose,
        title = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(account.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = onEdit, enabled = canEdit) { AppIcon(AppSymbol.EDIT, "编辑资料") }
            }
        },
        text = {
            LazyColumn(
                Modifier.fillMaxSize(),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 12.dp),
            ) {
                item(key = "summary") {
                    Surface(color = summaryCardColor(), shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    account.name,
                                    Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                )
                                Surface(
                                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                                    shape = RoundedCornerShape(50),
                                ) {
                                    Text(
                                        if (account.includeInAssets) "计入资产合计" else "不计入资产合计",
                                        Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                }
                            }
                            Text(
                                listOfNotNull(
                                    if (account.type == "LIABILITY") "负债账户" else "资产账户",
                                    account.currency,
                                    group?.name ?: "默认分组",
                                    if (account.archivedAt != null) "已归档" else null,
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    AmountText(
                                        if (amountsHidden) {
                                            "••••"
                                        } else {
                                            ledgerAmount(
                                                java.math.BigDecimal.valueOf(account.balance.unscaled, account.balance.scale),
                                                account.balance.currency,
                                            )
                                        },
                                        modifier = Modifier.weight(1f, fill = false).alignByBaseline(),
                                        style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.Bold),
                                    )
                                    Text(account.balance.currency, Modifier.alignByBaseline(), style = MaterialTheme.typography.bodyMedium)
                                }
                                IconButton(onClick = onToggleAmountsHidden) {
                                    AppIcon(
                                        if (amountsHidden) AppSymbol.EYE_OFF else AppSymbol.EYE,
                                        if (amountsHidden) "显示账户金额" else "隐藏账户金额",
                                    )
                                }
                            }
                            Text(
                                "当前余额",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                item(key = "actions") {
                    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(16.dp)) {
                        Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(Modifier.fillMaxWidth()) {
                                AccountAction("转账", AppSymbol.TRANSFER, canTransfer, onTransfer)
                                AccountAction("调整余额", AppSymbol.ADJUST, enabled = false, onClick = {})
                                AccountAction("查看统计", AppSymbol.STATISTICS, enabled = true) {
                                    scope.launch { listState.animateScrollToItem(2) }
                                }
                            }
                            Text(
                                "当前账户暂不支持调整余额。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (!canEdit) {
                                Text(
                                    "当前账户或操作状态不可编辑资料。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (!canTransfer) {
                                Text(
                                    "转账需两个可用账户，且当前可写。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                item(key = "month") {
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "${month.year}年${month.monthValue}月",
                                    Modifier.weight(1f),
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                )
                                IconButton(onClick = { monthKey = month.minusMonths(1).toString() }) { AppIcon(AppSymbol.BACK, "上一月") }
                                IconButton(onClick = { monthKey = month.plusMonths(1).toString() }) { AppIcon(AppSymbol.CHEVRON, "下一月") }
                            }
                            if (confirmedCount == 0 && unattributedCount > 0) {
                                Text("暂无可明确归属的账户收支。", style = MaterialTheme.typography.bodyMedium)
                            } else {
                                if (unattributedCount > 0) Text("以下为已确认收支", style = MaterialTheme.typography.labelMedium)
                                totals.forEach { summary ->
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        MonthTotal("收入", summary.income, summary.currency, amountsHidden, income = true)
                                        MonthTotal("支出", summary.expense, summary.currency, amountsHidden, income = false)
                                        MonthTotal("结余", summary.balance, summary.currency, amountsHidden, income = null)
                                    }
                                }
                            }
                            Text(
                                "本月收支仅含当前账本可明确归属到本账户的统计，按账单币种分列；转账本金不计入。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (unattributedCount > 0) {
                                Text(
                                    "本月另有 $unattributedCount 条统计尚无法按账户归属，上述为可确认部分。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.tertiary,
                                )
                            }
                        }
                    }
                }
                item(key = "records-title") {
                    Text("相关账单 · ${month.year}年${month.monthValue}月", style = MaterialTheme.typography.titleMedium)
                }
                item(key = "records-scope") {
                    Text(
                        "以下仅为当前账本可见的账单，不代表账户完整历史；本月 ${monthRecords.size} 笔，全部 ${accountRecords.size} 笔。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (monthRecords.isEmpty()) {
                    item(key = "records-empty") {
                        Text("该月当前账本没有与本账户相关的账单。", Modifier.padding(vertical = 8.dp))
                    }
                }
                items(monthRecords, key = { it.id.toString() }) { record ->
                    AccountRecordRow(
                        record = record,
                        account = account,
                        category = record.categoryId?.let { id -> categories.firstOrNull { it.id == id } },
                        categoryName = record.categoryId?.let(categoryNames::get),
                        ownerId = snapshot.ownerId,
                        zone = statisticsZone,
                        amountsHidden = amountsHidden,
                        context = context,
                        onClick = { onRecordClick(record) },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {},
    )
}

@Composable
private fun RowScope.AccountAction(
    label: String,
    symbol: AppSymbol,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.weight(1f)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Surface(
                shape = CircleShape,
                color = summaryCardColor(),
                contentColor = MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else 0.38f),
            ) {
                AppIcon(symbol, modifier = Modifier.padding(12.dp))
            }
            Text(label, color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f))
        }
    }
}

@Composable
private fun RowScope.MonthTotal(
    label: String,
    amount: BigDecimal,
    currency: String,
    amountsHidden: Boolean,
    income: Boolean?,
) {
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        AmountText(
            if (amountsHidden) "••••" else ledgerAmount(amount, currency),
            income = income,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        )
        Text(currency, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Records in this ledger that carry a leg on the account; month filtering happens on the statistic zone. */
internal fun accountLinkedRecords(
    records: List<FinanceRecord>,
    accountId: UUID,
): List<FinanceRecord> =
    records
        .filter { record ->
            when (val input = record.input) {
                is RecordInput.Expense -> {
                    input.allocations.any { it.accountId == accountId }
                }

                is RecordInput.Income -> {
                    input.allocations.any { it.accountId == accountId }
                }

                is RecordInput.Transfer -> {
                    input.from.accountId == accountId || input.to.accountId == accountId ||
                        input.fee?.accountId == accountId || input.bonus?.accountId == accountId
                }
            }
        }.sortedByDescending { it.occurredAtMillis }

@Composable
private fun AccountRecordRow(
    record: FinanceRecord,
    account: FinanceAccount,
    category: FinanceCategory?,
    categoryName: String?,
    ownerId: UUID,
    zone: String,
    amountsHidden: Boolean,
    context: Context,
    onClick: () -> Unit,
) {
    val formatter =
        SimpleDateFormat("M月d日 · HH:mm", Locale.getDefault()).apply {
            timeZone = TimeZone.getTimeZone(ZoneId.of(zone))
        }
    val heading =
        record.remark.ifBlank {
            categoryName ?: when (record.input) {
                is RecordInput.Expense -> "支出"
                is RecordInput.Income -> "收入"
                is RecordInput.Transfer -> "转账"
            }
        }
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (category != null) CategoryStyleBadge(CategoryStyles.read(context, ownerId, category))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(heading, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                Text(
                    formatter.format(Date(record.occurredAtMillis)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AmountText(
                accountRecordDescription(record.input, account.id, amountsHidden),
                modifier = Modifier.weight(1.1f),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** Describes each leg the account actually carries, keeping the leg's own amount and currency. */
internal fun accountRecordDescription(
    input: RecordInput,
    accountId: UUID,
    amountsHidden: Boolean,
): String {
    val mask: (Money) -> String = { if (amountsHidden) "••••" else display(it) }
    return when (input) {
        is RecordInput.Expense -> {
            "支出 · " + input.allocations.filter { it.accountId == accountId }.joinToString("、") { mask(it.amount) }
        }

        is RecordInput.Income -> {
            "收入 · " + input.allocations.filter { it.accountId == accountId }.joinToString("、") { mask(it.amount) }
        }

        is RecordInput.Transfer -> {
            buildList {
                if (input.from.accountId == accountId) add("转出 ${mask(input.from.amount)}")
                if (input.to.accountId == accountId) add("转入 ${mask(input.to.amount)}")
                if (input.fee?.accountId == accountId) add("手续费 ${mask(input.fee.amount)}")
                if (input.bonus?.accountId == accountId) add("返现 ${mask(input.bonus.amount)}")
            }.joinToString("、", prefix = "转账 · ")
        }
    }
}
