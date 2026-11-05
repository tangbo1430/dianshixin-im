package dev.icos.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Annual report body only: year switcher, save/share actions and export state stay in
 * [AnnualReportPanel]. Category, trend and highlight cards are the existing composables
 * from AnnualReceiptPanels.kt, made internal for reuse here.
 */
@Composable
internal fun AnnualReportContent(report: AnnualReport) {
    val primary = annualPrimaryCurrency(report)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AnnualHeroCard(report, primary)
        if (report.empty) {
            AnnualSectionCard {
                Text("${report.year} 年还没有账单。", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "记下第一笔后，这里会显示真实的收支与分类汇总。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }
        AnnualCurrencyCard(report)
        if (report.entryCount == 0) {
            Text(
                "这一年有账单，但没有可计入统计的收支贡献。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (primary != null) {
            AnnualCategoryCard(report, primary)
            AnnualTrendCard(report, primary)
            if (report.months.keys.size > 1) {
                Text(
                    "图表按 $primary 展示；其他币种（${report.months.keys.filter { it != primary }.joinToString()}）的金额见上方收支与分类清单。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        AnnualHighlightsCard(report)
    }
}

@Composable
private fun AnnualHeroCard(
    report: AnnualReport,
    primary: String?,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(Brush.linearGradient(listOf(summaryCardColor(), MaterialTheme.colorScheme.surface)))
            .padding(18.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${report.year} 年", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    if (report.empty) "记录尚未开始" else "认真记录的每一天",
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                )
                Text(
                    if (report.empty) "这一年还没有账单。" else "累计记录 ${report.daysRecorded} 天 · ${report.recordCount} 笔账单",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "${report.entryCount} 条计入收支统计 · 按币种分别统计，不换算汇率",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnnualCalendarArt(Modifier.size(88.dp), report, primary)
        }
    }
}

/** Calendar drawn from the real month buckets: no bundled artwork, no invented bars. */
@Composable
private fun AnnualCalendarArt(
    modifier: Modifier,
    report: AnnualReport,
    primary: String?,
) {
    val accent = MaterialTheme.colorScheme.primary
    val paper = MaterialTheme.colorScheme.surface
    val values =
        (primary?.let { report.months[it] } ?: emptyList())
            .map { month -> month.income.max(BigDecimal.ZERO).add(month.expense.max(BigDecimal.ZERO)) }
            .filter { it.signum() > 0 }
            .takeLast(5)
    val peak = values.maxOrNull() ?: BigDecimal.ZERO
    Canvas(modifier) {
        val width = size.width
        val height = size.height
        drawRoundRect(paper, Offset(0f, height * 0.1f), Size(width, height * 0.9f), CornerRadius(width * 0.16f), style = Fill)
        drawRoundRect(
            accent.copy(alpha = 0.82f),
            Offset(0f, height * 0.1f),
            Size(width, height * 0.2f),
            CornerRadius(width * 0.16f),
            style = Fill,
        )
        drawRect(accent.copy(alpha = 0.82f), Offset(0f, height * 0.2f), Size(width, height * 0.1f))
        val ring = width * 0.055f
        drawLine(accent, Offset(width * 0.3f, 0f), Offset(width * 0.3f, height * 0.2f), strokeWidth = ring, cap = StrokeCap.Round)
        drawLine(accent, Offset(width * 0.7f, 0f), Offset(width * 0.7f, height * 0.2f), strokeWidth = ring, cap = StrokeCap.Round)
        if (peak.signum() > 0) {
            val barWidth = width * 0.09f
            val gap = width * 0.05f
            val baseY = height * 0.86f
            values.forEachIndexed { index, value ->
                val barHeight =
                    height * 0.06f +
                        height * 0.44f * value.divide(peak, 6, RoundingMode.HALF_UP).toFloat().coerceIn(0f, 1f)
                val left = width * 0.14f + index * (barWidth + gap)
                drawRoundRect(
                    accent.copy(alpha = 0.55f),
                    Offset(left, baseY - barHeight),
                    Size(barWidth, barHeight),
                    CornerRadius(barWidth / 2f),
                    style = Fill,
                )
            }
        }
    }
}

@Composable
private fun AnnualCurrencyCard(report: AnnualReport) {
    val currencies = (report.income.keys + report.expense.keys + report.incomeExpenseRatio.keys).sorted()
    AnnualSectionCard {
        Text("年度收支与结余", style = MaterialTheme.typography.titleMedium)
        if (currencies.isEmpty()) {
            Text(
                "没有可计入统计的收支。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        currencies.forEachIndexed { index, currency ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            val income = report.income[currency] ?: BigDecimal.ZERO
            val expense = report.expense[currency] ?: BigDecimal.ZERO
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    currency,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                report.incomeExpenseRatio[currency]?.let { ratio ->
                    Text(
                        "收支比 ${ratio.stripTrailingZeros().toPlainString()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AnnualMetric("收入", income, currency, true, Modifier.weight(1f))
                AnnualMetric("支出", expense, currency, false, Modifier.weight(1f))
                AnnualMetric("结余", income.subtract(expense), currency, null, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun AnnualMetric(
    label: String,
    amount: BigDecimal,
    currency: String,
    income: Boolean?,
    modifier: Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        AmountText(ledgerAmount(amount, currency), income = income, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun AnnualSectionCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}
