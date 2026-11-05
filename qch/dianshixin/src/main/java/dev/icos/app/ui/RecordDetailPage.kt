package dev.icos.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.icos.app.data.local.FinanceCategory
import dev.icos.app.data.local.FinanceRecord
import dev.icos.app.domain.RecordInput
import java.util.UUID

/** P09 账单详情：只读展示已有账单数据，真实写入口复用编辑页与列表原有动作。 */
@Composable
internal fun RecordDetailPage(
    record: FinanceRecord,
    ownerId: UUID,
    category: FinanceCategory?,
    accountNames: Map<UUID, String>,
    ledgerName: String,
    timezone: String,
    tags: List<String>,
    claimLabel: String,
    error: String?,
    enabled: Boolean,
    canEdit: Boolean,
    canDelete: Boolean,
    canToggleClaim: Boolean,
    canRefund: Boolean,
    hasPostedClaim: Boolean,
    excludedStatistics: Boolean,
    excludedBudget: Boolean,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onCopyRecord: () -> Unit,
    onDelete: () -> Unit,
    onRefund: () -> Unit,
    onToggleClaim: () -> Unit,
    onLocation: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val input = record.input
    val amount =
        when (input) {
            is RecordInput.Expense -> ledgerRecordAmount(input.discount?.let(input.gross::checkedSubtract) ?: input.gross, income = false)
            is RecordInput.Income -> ledgerRecordAmount(input.amount, income = true)
            is RecordInput.Transfer -> "${display(input.from.amount)} → ${display(input.to.amount)}"
        }
    val incomeDirection = if (input is RecordInput.Transfer) null else input is RecordInput.Income
    val accountText =
        when (input) {
            is RecordInput.Transfer -> "${accountNames[input.from.accountId] ?: "转出账户"} → ${accountNames[input.to.accountId] ?: "转入账户"}"
            is RecordInput.Expense -> input.allocations.joinToString("、") { accountNames[it.accountId] ?: "账户" }.ifBlank { "不计入账户" }
            is RecordInput.Income -> input.allocations.joinToString("、") { accountNames[it.accountId] ?: "账户" }.ifBlank { "不计入账户" }
        }
    val heading =
        record.remark.ifBlank {
            category?.name ?: when (input) {
                is RecordInput.Expense -> "支出"
                is RecordInput.Income -> "收入"
                is RecordInput.Transfer -> "转账"
            }
        }
    val canCopy = enabled && canEdit && (input is RecordInput.Expense || input is RecordInput.Income)
    val hasOverflowActions = canDelete || input is RecordInput.Expense || input is RecordInput.Income
    var overflowOpen by remember { mutableStateOf(false) }
    EditorPage(
        onDismissRequest = onDismiss,
        error = error,
        title = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "账单详情",
                    Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                )
                if (canEdit) IconButton(onClick = onEdit, enabled = enabled) { AppIcon(AppSymbol.EDIT, "编辑账单") }
                if (hasOverflowActions) {
                    Box {
                        IconButton(onClick = { overflowOpen = true }) { AppIcon(AppSymbol.MORE, "更多操作") }
                        DropdownMenu(overflowOpen, onDismissRequest = { overflowOpen = false }) {
                            if (input is RecordInput.Expense || input is RecordInput.Income) {
                                DropdownMenuItem(
                                    text = { Text("复制账单") },
                                    leadingIcon = { AppIcon(AppSymbol.COPY) },
                                    enabled = canCopy,
                                    onClick = {
                                        overflowOpen = false
                                        onCopyRecord()
                                    },
                                )
                            }
                            // 报销登记与撤销继续受 canRefund/canToggleClaim 与已入账报销检查约束。
                            if (input is RecordInput.Expense) {
                                DropdownMenuItem(
                                    text = { Text("登记退款") },
                                    leadingIcon = { AppIcon(AppSymbol.REFUND) },
                                    enabled = canRefund,
                                    onClick = {
                                        overflowOpen = false
                                        onRefund()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(if (record.claimState == "PENDING") "取消待报销" else "标记待报销") },
                                    leadingIcon = { AppIcon(AppSymbol.TEMPLATE) },
                                    enabled = canToggleClaim,
                                    onClick = {
                                        overflowOpen = false
                                        onToggleClaim()
                                    },
                                )
                            }
                            if (canDelete) {
                                if (input is RecordInput.Expense || input is RecordInput.Income) HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("删除账单") },
                                    leadingIcon = { AppIcon(AppSymbol.DELETE) },
                                    enabled = enabled,
                                    onClick = {
                                        overflowOpen = false
                                        onDelete()
                                    },
                                )
                            }
                        }
                    }
                }
            }
        },
        text = {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(
                    color = summaryCardColor(),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            category?.let {
                                CategoryStyleBadge(CategoryStyles.read(context, ownerId, it))
                            }
                            Text(
                                heading,
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        AmountText(
                            amount,
                            income = incomeDirection,
                            style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.Bold),
                        )
                        Text(
                            recordTimeText(record.occurredAtMillis, timezone),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                        DetailRow(AppSymbol.WALLET, "账户", accountText)
                        if (input is RecordInput.Transfer) {
                            input.fee?.let {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))
                                DetailRow(null, "", "手续费 ${display(it.amount)} · ${accountNames[it.accountId].orEmpty()}")
                            }
                            input.bonus?.let {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))
                                DetailRow(null, "", "返现 ${display(it.amount)} · ${accountNames[it.accountId].orEmpty()}")
                            }
                        }
                        if (ledgerName.isNotBlank()) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))
                            DetailRow(AppSymbol.BOOK, "账本", ledgerName)
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))
                        DetailRow(AppSymbol.CALENDAR, "时间", recordTimeText(record.occurredAtMillis, timezone))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))
                        DetailRow(AppSymbol.FILTER, "标签", tags.joinToString("、").ifBlank { "未选择标签" })
                        if (record.attachmentIds.isNotEmpty()) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))
                            DetailRow(null, "附件", "${record.attachmentIds.size} 个 · 暂不支持查看")
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))
                        DetailRow(AppSymbol.TEMPLATE, "备注", record.remark.ifBlank { "无备注" })
                        if (input is RecordInput.Expense) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))
                            DetailRow(AppSymbol.CHECK, "报销", claimLabel)
                        }
                        if (excludedStatistics || excludedBudget) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))
                            DetailRow(
                                AppSymbol.EYE_OFF,
                                "统计",
                                listOfNotNull(
                                    if (excludedStatistics) "不计统计" else null,
                                    if (excludedBudget) "不计预算" else null,
                                ).joinToString(" · "),
                            )
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))
                        TextButton(
                            onClick = onLocation,
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding =
                                androidx.compose.foundation.layout
                                    .PaddingValues(0.dp),
                        ) {
                            Column(Modifier.weight(1f)) { DetailRow(AppSymbol.LOCATION, "位置", "查看本机位置") }
                            AppIcon(AppSymbol.CHEVRON, null)
                        }
                    }
                }
                if (!canDelete) Text("当前账单暂不可删除。", style = MaterialTheme.typography.bodySmall)
                if (input is RecordInput.Expense && hasPostedClaim) Text("登记退款前请先撤销已入账报销", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {},
        dismissButton = {},
        bottomContent = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        TextButton(
                            onClick = onCopyRecord,
                            enabled = canCopy,
                            modifier = Modifier.weight(1f),
                            contentPadding =
                                androidx.compose.foundation.layout
                                    .PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                AppIcon(AppSymbol.COPY)
                                Text("复制账单", style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                        if (input is RecordInput.Expense) {
                            TextButton(
                                onClick = onRefund,
                                enabled = canRefund,
                                modifier = Modifier.weight(1f),
                                contentPadding =
                                    androidx.compose.foundation.layout
                                        .PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    AppIcon(AppSymbol.REFUND)
                                    Text("退款", style = MaterialTheme.typography.bodyLarge)
                                }
                            }
                            TextButton(
                                onClick = onToggleClaim,
                                enabled = canToggleClaim,
                                modifier = Modifier.weight(1f),
                                contentPadding =
                                    androidx.compose.foundation.layout
                                        .PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    AppIcon(AppSymbol.TEMPLATE)
                                    Text(
                                        if (record.claimState == "PENDING") "取消待报销" else "标记待报销",
                                        textAlign = TextAlign.Center,
                                        style = MaterialTheme.typography.bodyLarge.copy(lineBreak = LineBreak.Heading),
                                    )
                                }
                            }
                        }
                    }
                }
                if (canDelete) {
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        TextButton(
                            onClick = onDelete,
                            enabled = enabled,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        ) {
                            AppIcon(AppSymbol.DELETE)
                            Text("删除账单", style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        },
    )
}

private fun recordTimeText(
    value: Long,
    zone: String,
): String =
    java.text
        .SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        .apply { timeZone = java.util.TimeZone.getTimeZone(java.time.ZoneId.of(zone)) }
        .format(java.util.Date(value))

@Composable
private fun DetailRow(
    symbol: AppSymbol?,
    label: String,
    value: String,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (symbol != null) AppIcon(symbol, null, Modifier.size(20.dp), MaterialTheme.colorScheme.onSurfaceVariant)
        if (label.isNotEmpty()) {
            Text(
                label,
                Modifier.width(48.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
    }
}
