package dev.icos.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.icos.app.data.local.AssetHistoryPoint
import dev.icos.app.domain.Money
import java.math.BigDecimal
import java.math.BigInteger

internal fun assetHistoryCoordinateRatios(values: List<Money>): List<Float> {
    if (values.isEmpty()) return emptyList()
    val decimals = values.map { BigDecimal(BigInteger.valueOf(it.unscaled), it.scale) }
    val minimum = decimals.minOrNull() ?: BigDecimal.ZERO
    val maximum = decimals.maxOrNull() ?: BigDecimal.ZERO
    val span = maximum.subtract(minimum)
    if (span.signum() == 0) return values.map { 0.5f }
    return decimals.map { value ->
        value
            .subtract(minimum)
            .divide(span, 12, java.math.RoundingMode.HALF_UP)
            .toFloat()
            .coerceIn(0f, 1f)
    }
}

@Composable
internal fun AssetHistoryPanel(model: FinanceViewModel) {
    var includeFuture by remember(model.state.accountOwner) { mutableStateOf(model.state.assetHistoryIncludeFuture) }
    LaunchedEffect(
        model.state.accountOwner,
        model.state.snapshot?.ledgerId,
        model.state.assetHistoryMonths,
        model.state.assetHistoryIncludeFuture,
    ) {
        model.loadAssetHistory(months = model.state.assetHistoryMonths, includeFuture = model.state.assetHistoryIncludeFuture)
    }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("资产历史趋势", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("包含未来发生分录（默认关闭）", Modifier.semantics { contentDescription = "未来分录是否计入历史余额；默认关闭" })
            Switch(
                checked = includeFuture,
                onCheckedChange = {
                    includeFuture = it
                    model.loadAssetHistory(months = model.state.assetHistoryMonths, includeFuture = it)
                },
                enabled = !model.state.assetHistoryBusy,
                modifier = Modifier.semantics { contentDescription = if (includeFuture) "包含未来发生分录" else "排除未来发生分录" },
            )
        }
        Text(
            if (model.state.accountOwner == null) "按访客账本历史分录计算。" else "按服务器 canonical 分录和本机有效预测计算；预测尚未确认可能回滚。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (model.state.assetHistoryBusy) CircularProgressIndicator()
        model.state.assetHistoryError?.let { Text("历史趋势加载失败：$it", color = MaterialTheme.colorScheme.error) }
        val points = model.state.assetHistory
        val currencies = points.flatMap { it.netWorth.keys }.toSortedSet()
        if (points.isEmpty() && !model.state.assetHistoryBusy && model.state.assetHistoryError == null) {
            Card(
                Modifier.fillMaxWidth(),
                shape =
                    androidx.compose.foundation.shape
                        .RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Text(
                    "暂无历史分录",
                    Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        currencies.forEach { currency ->
            val assets = points.map { it.assets[currency] ?: Money(0, 0, currency) }
            val liabilities = points.map { it.liabilities[currency] ?: Money(0, 0, currency) }
            val net = points.map { it.netWorth[currency] ?: Money(0, 0, currency) }
            AssetHistorySeries("$currency · 总资产", points, assets, MaterialTheme.colorScheme.primary)
            AssetHistorySeries("$currency · 总负债", points, liabilities, MaterialTheme.colorScheme.error)
            AssetHistorySeries("$currency · 净资产", points, net, MaterialTheme.colorScheme.tertiary)
        }
    }
}

@Composable
private fun AssetHistorySeries(
    title: String,
    points: List<AssetHistoryPoint>,
    values: List<Money>,
    color: androidx.compose.ui.graphics.Color,
) {
    if (points.isEmpty()) return
    val ratios = assetHistoryCoordinateRatios(values)
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                "末期金额 ${moneyText(values.last())} ${values.last().currency}",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            )
            if (points.size > 1) {
                Text(
                    "${points.first().period} — ${points.last().period} · ${points.size} 个周期",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Canvas(
                Modifier.fillMaxWidth().height(96.dp).semantics {
                    contentDescription = "$title：" + points.indices.joinToString("；") { "${points[it].period} ${moneyText(values[it])}" }
                },
            ) {
                if (ratios.size > 1) {
                    val line =
                        ratios.indices.map { index ->
                            Offset(size.width * index.toFloat() / (ratios.lastIndex), size.height * (1f - ratios[index]))
                        }
                    line.zipWithNext().forEach { (start, end) -> drawLine(color, start, end, strokeWidth = 3.dp.toPx()) }
                    line.forEach { drawCircle(color, radius = 3.dp.toPx(), center = it) }
                }
            }
            Text("月份 · 精确金额", style = MaterialTheme.typography.labelMedium)
            points.indices.forEach { index ->
                Text("${points[index].period} · ${moneyText(values[index])} ${values[index].currency}")
            }
        }
    }
}
