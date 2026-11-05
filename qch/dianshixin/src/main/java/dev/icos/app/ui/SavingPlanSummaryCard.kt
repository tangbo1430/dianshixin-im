package dev.icos.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.icos.app.domain.Money
import dev.icos.app.domain.SavingPlanSummary
import java.math.BigDecimal
import java.math.RoundingMode

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun SavingPlanSummaryCard(plan: SavingPlanSummary, amountsHidden: Boolean = false) {
    val ratio = savingPlanRatio(plan.saved, plan.input.target)
    val percent = "${ratio.multiply(BigDecimal(100)).setScale(0, RoundingMode.HALF_UP)}%"
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
                    Text(
                        "已存金额",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    AmountText(
                        if (amountsHidden) "••••" else display(plan.saved),
                        style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                    )
                    Text(
                        "目标 ${if (amountsHidden) "••••" else display(plan.input.target)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                SavingRing(ratio.toFloat(), if (amountsHidden) "••" else percent)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SavingStat("剩余金额", if (amountsHidden) "••••" else display(plan.remaining))
                SavingStat("目标日期", plan.input.endDate ?: "未设置", AppSymbol.CALENDAR)
            }
        }
    }
}

@Composable
private fun SavingRing(
    ratio: Float,
    percent: String,
) {
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    val color = MaterialTheme.colorScheme.primary
    Box(Modifier.size(88.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 10.dp.toPx()
            val topLeft = Offset(stroke / 2f, stroke / 2f)
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(track, 0f, 360f, false, topLeft = topLeft, size = arcSize, style = Stroke(stroke))
            drawArc(
                color,
                -90f,
                360f * ratio.coerceIn(0f, 1f),
                false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
        Text(
            percent,
            modifier = Modifier.padding(10.dp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        )
    }
}

@Composable
private fun SavingStat(
    label: String,
    value: String,
    icon: AppSymbol? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            icon?.let { AppIcon(it) }
            Text(value, style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** Same scale-4 ratio and zero-target guard as [SavingProgress]; clamped only when drawn as an arc. */
private fun savingPlanRatio(
    saved: Money,
    target: Money,
): BigDecimal {
    val total = BigDecimal.valueOf(target.unscaled, target.scale)
    val current = BigDecimal.valueOf(saved.unscaled, saved.scale)
    return if (total.signum() > 0) current.divide(total, 4, RoundingMode.HALF_UP) else BigDecimal.ZERO
}
