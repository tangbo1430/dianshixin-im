package dev.icos.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.icos.app.data.local.FinanceRecord
import dev.icos.app.data.local.FinanceSnapshot
import dev.icos.app.domain.Money
import dev.icos.app.domain.PeriodResolver
import dev.icos.app.domain.RecordInput
import dev.icos.app.domain.ReportPeriod
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Preview derived from the loaded ledger snapshot; null means no ledger data is available. */
internal data class AppearancePreview(
    val ledgerName: String,
    val currency: String,
    val monthLabel: String,
    val income: BigDecimal,
    val expense: BigDecimal,
    val balance: BigDecimal,
    val records: List<AppearancePreviewRecord>,
)

internal data class AppearancePreviewRecord(
    val title: String,
    val meta: String,
    val amount: String,
    val income: Boolean,
)

internal fun themeAccent(
    preference: ThemeColorPreference,
    dark: Boolean,
): Color = if (dark) preference.dark else preference.light

/**
 * Real ledger data for the appearance preview. Every displayed figure comes from the snapshot in
 * the statistics timezone, so an empty or missing ledger renders an unavailable state instead of
 * sample money.
 */
internal fun appearancePreview(
    snapshot: FinanceSnapshot?,
    zone: String,
    nowMillis: Long = System.currentTimeMillis(),
): AppearancePreview? {
    if (snapshot == null) return null
    val ledger = snapshot.ledgers.firstOrNull { it.id == snapshot.ledgerId } ?: return null
    val range = PeriodResolver.range(ReportPeriod.MONTH, nowMillis, zone)
    val summary =
        ledgerMonthSummaries(snapshot.statisticEntries, range, ledger.currency)
            .firstOrNull { it.currency == ledger.currency } ?: return null
    val categories = snapshot.categories.associate { it.id to it.name }
    val categoryIds = snapshot.categories.filter { it.ledgerId == snapshot.ledgerId }.mapTo(HashSet()) { it.id }
    val accounts = snapshot.accounts.associate { it.id to it.name }
    val records =
        snapshot.records
            .filter { categoryIds.isEmpty() || it.categoryId == null || it.categoryId in categoryIds }
            .sortedByDescending { it.occurredAtMillis }
            .mapNotNull { record -> previewRecord(record, categories, accounts, zone) }
            .take(2)
    return AppearancePreview(
        ledgerName = ledger.name,
        currency = ledger.currency,
        monthLabel = SimpleDateFormat("yyyy年M月", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone(zone) }.format(Date(nowMillis)),
        income = summary.income,
        expense = summary.expense,
        balance = summary.balance,
        records = records,
    )
}

private fun previewRecord(
    record: FinanceRecord,
    categories: Map<java.util.UUID, String>,
    accounts: Map<java.util.UUID, String>,
    zone: String,
): AppearancePreviewRecord? {
    val input = record.input
    val amount: Money =
        when (input) {
            is RecordInput.Expense -> input.discount?.let(input.gross::checkedSubtract) ?: input.gross
            is RecordInput.Income -> input.amount
            is RecordInput.Transfer -> return null
        }
    val income = input is RecordInput.Income
    val accountId =
        when (input) {
            is RecordInput.Expense -> input.allocations.firstOrNull()?.accountId
            is RecordInput.Income -> input.allocations.firstOrNull()?.accountId
            is RecordInput.Transfer -> input.from.accountId
        }
    val date =
        SimpleDateFormat(
            "M月d日 HH:mm",
            Locale.ROOT,
        ).apply { timeZone = TimeZone.getTimeZone(zone) }.format(Date(record.occurredAtMillis))
    val account = accounts[accountId]
    return AppearancePreviewRecord(
        title = categories[record.categoryId] ?: record.remark.takeIf { it.isNotBlank() } ?: "未分类",
        meta = if (account == null) date else "$date · $account",
        amount = ledgerRecordAmount(amount, income),
        income = income,
    )
}

@Composable
internal fun AppearancePreviewCard(
    settings: AppearanceSettings,
    preview: AppearancePreview?,
    modifier: Modifier = Modifier,
) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val accent = themeAccent(settings.themeColor, dark)
    Surface(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = accent.copy(alpha = if (dark) 0.22f else 0.16f),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (preview == null) {
                Text("暂无可预览的账本数据", style = MaterialTheme.typography.titleSmall)
                Text(
                    "打开账本后，这里会显示当前账本的真实收支与最近账单。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    preview.ledgerName,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.weight(1f, fill = false),
                )
                AppIcon(AppSymbol.DOWN, modifier = Modifier.size(18.dp))
                Spacer(Modifier.weight(1f))
                AppIcon(AppSymbol.SEARCH, "搜索", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                AppIcon(AppSymbol.CALENDAR, "日历", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(preview.monthLabel, style = MaterialTheme.typography.bodyMedium)
                AppIcon(AppSymbol.DOWN, modifier = Modifier.size(16.dp))
                Spacer(Modifier.weight(1f))
                AppIcon(AppSymbol.EYE, "金额可见", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                // Each summary column needs ~92dp and the record amount needs room beside the
                // meta line; below that both stack so category/date/account text keeps full width.
                val roomy =
                    maxWidth >= 296.dp * (LocalDensity.current.fontScale * settings.fontScale).coerceAtLeast(1f)
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (roomy) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            PreviewSummary("本月支出", preview.expense, preview.currency, income = false, Modifier.weight(1f))
                            PreviewSummary("本月收入", preview.income, preview.currency, income = true, Modifier.weight(1f))
                            PreviewSummary("本月结余", preview.balance, preview.currency, income = null, Modifier.weight(1f))
                        }
                    } else {
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            PreviewSummary("本月支出", preview.expense, preview.currency, income = false, Modifier.fillMaxWidth())
                            PreviewSummary("本月收入", preview.income, preview.currency, income = true, Modifier.fillMaxWidth())
                            PreviewSummary("本月结余", preview.balance, preview.currency, income = null, Modifier.fillMaxWidth())
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                    if (preview.records.isEmpty()) {
                        Text("本月暂无账单", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    preview.records.forEach { record ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(30.dp)
                                    .background(
                                        color =
                                            (
                                                if (record.income) {
                                                    amountColor(
                                                        settings.amountColors,
                                                        true,
                                                        dark,
                                                    )
                                                } else {
                                                    amountColor(settings.amountColors, false, dark)
                                                }
                                            )?.copy(alpha = 0.16f) ?: accent.copy(alpha = 0.16f),
                                        shape = RoundedCornerShape(8.dp),
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                AppIcon(
                                    if (record.income) AppSymbol.SAVING else AppSymbol.WALLET,
                                    modifier = Modifier.size(18.dp),
                                    tint =
                                        (
                                            if (record.income) {
                                                amountColor(
                                                    settings.amountColors,
                                                    true,
                                                    dark,
                                                )
                                            } else {
                                                amountColor(settings.amountColors, false, dark)
                                            }
                                        )
                                            ?: accent,
                                )
                            }
                            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                                Text(record.title, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    record.meta,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (!roomy) PreviewRecordAmount(record.amount, record.income)
                            }
                            if (roomy) PreviewRecordAmount(record.amount, record.income)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewRecordAmount(
    amount: String,
    income: Boolean,
) {
    AmountText(
        amount,
        income = income,
        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
    )
}

@Composable
private fun PreviewSummary(
    label: String,
    amount: BigDecimal,
    currency: String,
    income: Boolean?,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f))
        AmountText(
            ledgerAmount(amount, currency),
            income = income,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
        )
    }
}
