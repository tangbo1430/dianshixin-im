package dev.icos.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.icos.app.domain.Direction
import java.math.BigDecimal

/** Ratios are computed with decimal values and converted only at the drawing boundary. */
internal fun statisticsBarRatios(values: List<CategoryStatistic>): Map<String, Float> =
    values
        .groupBy { it.direction to it.currency }
        .flatMap { (_, group) ->
            val maximum = group.maxOfOrNull { it.amount.abs() } ?: BigDecimal.ZERO
            group
                .associate { statistic ->
                    statistic.key to
                        if (maximum.signum() == 0) {
                            0f
                        } else {
                            statistic.amount
                                .abs()
                                .divide(maximum, 8, java.math.RoundingMode.HALF_UP)
                                .toFloat()
                                .coerceIn(0f, 1f)
                        }
                }.entries
        }.associate { it.toPair() }

@Composable
internal fun StatisticsChartRow(
    statistic: CategoryStatistic,
    ratio: Float,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val income = statistic.direction == Direction.INCOME
    val negative = statistic.amount.signum() < 0
    val directionLabel =
        when {
            negative -> "负向抵扣"
            income -> "收入"
            else -> "支出"
        }
    // The amount is shown as a magnitude with an explicit sign so the bar stays readable.
    val sign = if (income && !negative) "+" else "−"
    val amount = "${sign}${statistic.currency} ${statistic.amount.abs().stripTrailingZeros().toPlainString()}"
    // Negative offsets need their own color and grow from the right end so they never read as plain expense bars.
    val barColor =
        when {
            negative -> MaterialTheme.colorScheme.outline
            income -> MaterialTheme.colorScheme.tertiary
            else -> MaterialTheme.colorScheme.error
        }
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    Card(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clickable(onClick = onSelect),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(statistic.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "$directionLabel · ${statistic.entries.size} 条",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    AmountText(
                        amount,
                        income = income && !negative,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    )
                    Text(
                        statistic.percent?.let { "$it%" } ?: "含抵扣",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clipToBounds(),
            ) {
                val radius = size.height / 2f
                drawRoundRect(trackColor, size = Size(size.width, size.height), cornerRadius = CornerRadius(radius))
                val width = size.width * ratio.coerceIn(0f, 1f)
                if (width > 0f) {
                    val left = if (negative) size.width - width else 0f
                    drawRoundRect(
                        barColor,
                        topLeft = Offset(left, 0f),
                        size = Size(width, size.height),
                        cornerRadius = CornerRadius(radius),
                    )
                }
            }
            if (selected) {
                Text(
                    "已展开 · 点击收起明细",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
