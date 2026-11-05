package dev.icos.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.icos.app.data.local.FinanceBudgetOverview
import dev.icos.app.data.local.FinanceSnapshot
import dev.icos.app.data.local.FinanceStatisticEntry
import dev.icos.app.domain.Direction
import dev.icos.app.domain.PeriodRange
import dev.icos.app.domain.PeriodResolver
import dev.icos.app.domain.ReportPeriod
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

internal data class LedgerMonthSummary(
    val currency: String,
    val income: BigDecimal,
    val expense: BigDecimal,
) {
    val balance: BigDecimal get() = income.subtract(expense)
}

internal fun ledgerMonthSummaries(
    entries: List<FinanceStatisticEntry>,
    range: PeriodRange,
    currency: String,
): List<LedgerMonthSummary> {
    val monthly = entries.filter { range.contains(it.occurredAtMillis) }.groupBy { it.amount.currency }
    return (setOf(currency) + monthly.keys.sorted()).map { code ->
        val facts = monthly[code].orEmpty()

        fun total(direction: Direction) =
            facts
                .filter { it.direction == direction }
                .fold(BigDecimal.ZERO) { sum, entry -> sum + BigDecimal.valueOf(entry.amount.unscaled, entry.amount.scale) }
        LedgerMonthSummary(code, total(Direction.INCOME), total(Direction.EXPENSE))
    }
}

@Composable
internal fun SavingProgress(
    saved: dev.icos.app.domain.Money,
    target: dev.icos.app.domain.Money,
) {
    val total = BigDecimal.valueOf(target.unscaled, target.scale)
    val current = BigDecimal.valueOf(saved.unscaled, saved.scale)
    val ratio = if (total.signum() > 0) current.divide(total, 4, RoundingMode.HALF_UP) else BigDecimal.ZERO
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LinearProgressIndicator(
            progress = { ratio.toFloat().coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(8.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            strokeCap = StrokeCap.Round,
        )
        Text(
            "已完成 ${ratio.multiply(BigDecimal(100)).setScale(0, RoundingMode.HALF_UP)}%",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

internal fun ledgerRecordAmount(
    value: dev.icos.app.domain.Money,
    income: Boolean,
): String {
    val amount = BigDecimal.valueOf(value.unscaled, value.scale).let { if (income) it else it.negate() }
    val sign =
        if (amount.signum() < 0) {
            "−"
        } else if (amount.signum() > 0) {
            "+"
        } else {
            ""
        }
    return "$sign${ledgerAmount(amount.abs(), value.currency)} ${value.currency}"
}

/** Home-page presentation: the design prefixes the CNY symbol, other currencies keep their code. */
internal fun ledgerSymbolAmount(
    amount: BigDecimal,
    currency: String,
): String {
    val digits = ledgerAmount(amount.abs(), currency)
    val sign = if (amount.signum() < 0) "−" else ""
    return if (currency == "CNY") "$sign¥$digits" else "$sign$digits $currency"
}

internal fun ledgerAmount(
    amount: BigDecimal,
    currency: String,
): String {
    val digits =
        runCatching {
            java.util.Currency
                .getInstance(currency)
                .defaultFractionDigits
                .coerceAtLeast(0)
        }.getOrDefault(2)
    return NumberFormat
        .getNumberInstance(Locale.CHINA)
        .apply {
            minimumFractionDigits = digits
            maximumFractionDigits = maxOf(digits, amount.scale())
        }.format(amount)
}

@Composable
internal fun LedgerOverview(
    snapshot: FinanceSnapshot,
    zone: String,
    range: PeriodRange,
    periodLabel: String,
    onChoosePeriod: () -> Unit,
    hasSearchFilters: Boolean = false,
) {
    val currency =
        snapshot.ledgers.firstOrNull { it.id == snapshot.ledgerId }?.currency
            ?: snapshot.accounts.firstOrNull()?.currency ?: "CNY"
    val summaries =
        remember(snapshot.statisticEntries, range, currency) { ledgerMonthSummaries(snapshot.statisticEntries, range, currency) }
    var hidden by rememberSaveable { mutableStateOf(false) }
    // A date range or search narrows the summary, so the month wording would be wrong there.
    val scope = if (hasSearchFilters) "" else "本月"

    fun amountText(
        amount: BigDecimal,
        currency: String,
    ) = if (hidden) "••••" else ledgerSymbolAmount(amount, currency)
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = summaryCardColor()),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.TextButton(onClick = onChoosePeriod, contentPadding = PaddingValues(0.dp)) {
                    Text(
                        periodLabel,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    AppIcon(AppSymbol.DOWN, tint = MaterialTheme.colorScheme.onSurface)
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { hidden = !hidden }) {
                    AppIcon(if (hidden) AppSymbol.EYE_OFF else AppSymbol.EYE, if (hidden) "显示金额" else "隐藏金额")
                }
            }
            if (hasSearchFilters) Text("日期范围收支 · 搜索结果汇总见下方", style = MaterialTheme.typography.labelSmall)
            summaries.forEachIndexed { index, summary ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                // Only repeated currency groups need the code spelled out next to the label.
                val code = if (summaries.size > 1) " · ${summary.currency}" else ""
                if (snapshot.records.isEmpty()) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        listOf(
                            Triple("${scope}支出$code", summary.expense, false),
                            Triple("${scope}收入$code", summary.income, true),
                            Triple("${scope}结余$code", summary.balance, null),
                        ).forEach { (label, amount, income) ->
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    label,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                                )
                                AmountText(
                                    amountText(amount, summary.currency),
                                    income = income,
                                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                )
                            }
                        }
                    }
                } else {
                    Text("${scope}支出$code", style = MaterialTheme.typography.bodyMedium)
                    AmountText(
                        amountText(summary.expense, summary.currency),
                        style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                "${scope}收入",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                            )
                            AmountText(
                                amountText(summary.income, summary.currency),
                                income = true,
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                            )
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                "${scope}结余",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                            )
                            AmountText(
                                amountText(summary.balance, summary.currency),
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun LedgerBudgetCard(
    budget: FinanceBudgetOverview,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val limit = budget.limit
    val remaining = budget.amounts?.remaining
    val usedText =
        limit?.takeIf { budget.enabled }?.let { total ->
            "已用 ${ledgerSymbolAmount(BigDecimal.valueOf(budget.used.unscaled, budget.used.scale), budget.currency)}" +
                " / ${ledgerSymbolAmount(BigDecimal.valueOf(total.unscaled, total.scale), budget.currency)}"
        }
    val ratio = (if (budget.enabled) budgetUsedRatio(budget.used, limit) else null) ?: 0f
    val progress = ratio.coerceIn(0f, 1f)
    val period =
        when (budget.period) {
            ReportPeriod.WEEK -> "本周预算"
            ReportPeriod.MONTH -> "本月预算"
            ReportPeriod.YEAR -> "本年预算"
        }
    Card(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(period, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                if (budget.enabled && limit == null) {
                    AppIcon(AppSymbol.SETTINGS, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        "设置预算",
                        Modifier.padding(start = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        when {
                            !budget.enabled -> "已关闭"

                            remaining != null -> "剩余 ${ledgerSymbolAmount(
                                BigDecimal.valueOf(remaining.unscaled, remaining.scale),
                                budget.currency,
                            )}"

                            else -> budget.currency
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color =
                            if (remaining != null && remaining.unscaled < 0 &&
                                budget.enabled
                            ) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                    )
                }
                AppIcon(AppSymbol.CHEVRON, modifier = Modifier.padding(start = 4.dp))
            }
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                strokeCap = StrokeCap.Round,
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    usedText ?: if (budget.enabled) "给日常开支定个小目标" else "点击管理预算，已有配置会保留",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (usedText != null) {
                    Text(
                        "${(ratio.coerceAtLeast(0f) * 100).roundToInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
