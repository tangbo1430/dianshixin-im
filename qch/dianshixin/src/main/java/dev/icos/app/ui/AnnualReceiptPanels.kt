package dev.icos.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.icos.app.data.local.FinanceRecord
import dev.icos.app.data.local.FinanceSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.ZoneId
import java.util.Calendar
import java.util.TimeZone
import kotlin.coroutines.resume

@Composable
internal fun AnnualReportPanel(
    snapshot: FinanceSnapshot,
    zone: String,
    onLoad: (Int, (Result<FinanceSnapshot>) -> Unit) -> Unit,
    export: ReceiptExport,
    onClose: () -> Unit,
) {
    val currentYear = Calendar.getInstance(TimeZone.getTimeZone(zone)).get(Calendar.YEAR)
    val pageKey = receiptKey(snapshot)
    val year = export.annualYear.takeIf { export.annualOpen == pageKey } ?: currentYear
    val setYear: (Int) -> Unit = { export.switchAnnualYear(it) }
    val retained = (export.frozen?.first as? ExportPayload.Annual)?.takeIf { it.year == year && it.isCurrent(snapshot) }
    var source by remember(pageKey, year) { mutableStateOf<FinanceSnapshot?>(null) }
    var loading by remember(pageKey, year) { mutableStateOf(false) }
    var loadError by remember(pageKey, year) { mutableStateOf<String?>(null) }
    var reloadTick by remember(pageKey, year) { mutableStateOf(0) }
    LaunchedEffect(pageKey, year, reloadTick) {
        if (retained != null) return@LaunchedEffect
        source = null
        loading = true
        loadError = null
        val result =
            suspendCancellableCoroutine<Result<FinanceSnapshot>> { continuation ->
                onLoad(year) { if (continuation.isActive) continuation.resume(it) }
            }
        if (export.annualOpen == pageKey && export.annualYear == year) {
            source = result.getOrNull()?.takeIf { receiptKey(it) == pageKey }
            loadError = if (source == null) "无法读取当前账本年度数据，请重试。" else null
            loading = false
        }
    }
    val reportSnapshot =
        source?.takeIf {
            it.ownerId == snapshot.ownerId && it.epochId == snapshot.epochId &&
                it.ledgerId == snapshot.ledgerId
        }
    val report = retained?.report ?: remember(reportSnapshot, year, zone) { reportSnapshot?.let { annualReport(it, year, zone) } }
    val payload = report?.let { ExportPayload.Annual(it, year) }
    val bitmap = export.frozen?.takeIf { payload != null && it.first == payload }?.second
    var generating by remember(payload) { mutableStateOf(false) }
    var regenerateTick by remember(snapshot.ownerId, snapshot.epochId, snapshot.ledgerId) { mutableStateOf(0) }
    val latestSnapshot by rememberUpdatedState(snapshot)
    LaunchedEffect(payload, regenerateTick) {
        if (payload == null || (regenerateTick == 0 && export.frozen?.first == payload)) return@LaunchedEffect
        generating = true
        var image: Bitmap? = null
        var imageTooLarge = false
        try {
            if (payload.isCurrent(latestSnapshot)) {
                withContext(Dispatchers.Default) {
                    image =
                        try {
                            ReportImageRenderer.annual(payload.report)
                        } catch (
                            _: ReportImageRenderer.AnnualImageTooLarge,
                        ) {
                            imageTooLarge = true
                            null
                        } catch (_: RuntimeException) {
                            null
                        } catch (_: OutOfMemoryError) {
                            null
                        }
                }
                val ready = image
                if (payload.isCurrent(latestSnapshot) && export.annualOpen == pageKey && export.annualYear == year) {
                    if (ready != null) {
                        export.freeze(payload, ready)
                        image = null
                    } else {
                        export.showStatus(if (imageTooLarge) "年度内容过多，无法生成完整图片；请在应用中查看全部数据。" else "无法生成年度图片，请重试。")
                    }
                }
            }
        } finally {
            image?.recycle()
            generating = false
        }
    }
    val save: () -> Unit = {
        val image = bitmap
        if (image != null && !generating && !export.busy) export.requestSave(payload!!, image, "icos-$year-report.png")
    }
    val share: () -> Unit = {
        val image = bitmap
        if (image != null && !generating && !export.busy) export.requestShare(payload!!, image)
    }
    val status = export.status
    val ready = bitmap != null && !generating && !export.busy
    EditorPage(
        onDismissRequest = onClose,
        title = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("年度报告", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                IconButton(enabled = ready, onClick = share) { AppIcon(AppSymbol.SHARE, "分享年度图片") }
                IconButton(enabled = ready, onClick = save) { AppIcon(AppSymbol.DOWNLOAD, "保存图片") }
            }
        },
        text = {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = {
                        setYear(year - 1)
                    }, enabled = !export.busy && year > 1900) { AppIcon(AppSymbol.CHEVRON, "上一年", Modifier.rotate(180f)) }
                    Text("$year 年", Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.titleMedium)
                    IconButton(
                        onClick = { setYear(year + 1) },
                        enabled = !export.busy && year < currentYear,
                    ) { AppIcon(AppSymbol.CHEVRON, "下一年") }
                }
                if (report == null) {
                    AnnualMessageCard("年度报告", if (loading) "正在读取当前账本年度数据…" else loadError ?: "等待年度数据。")
                    if (!loading && loadError != null) TextButton(onClick = { reloadTick++ }) { Text("重试") }
                } else {
                    Text("当前账本 · $zone", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    AnnualReportContent(report)
                    bitmap?.let { image ->
                        Card(
                            Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        ) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("分享图片预览", style = MaterialTheme.typography.titleSmall)
                                Image(
                                    image.asImageBitmap(),
                                    contentDescription = "$year 年度报告图片预览",
                                    modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {},
        bottomContent = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(
                    enabled = report != null && !generating && !export.busy,
                    onClick = { if (bitmap == null) regenerateTick++ else share() },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    AppIcon(AppSymbol.SHARE)
                    Text(if (generating) "正在生成年度图片…" else "生成分享图片", Modifier.padding(start = 8.dp))
                }
                status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        },
    )
}

@Composable
private fun AnnualMessageCard(
    title: String,
    message: String,
) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Currency with the largest yearly activity; charts are single-currency because amounts are never converted. */
internal fun annualPrimaryCurrency(report: AnnualReport): String? =
    report.months
        .maxByOrNull { (_, months) -> months.fold(BigDecimal.ZERO) { sum, month -> sum.add(month.income).add(month.expense) } }
        ?.key

internal fun annualDonutShares(
    categories: List<AnnualCategoryShare>,
    currency: String,
    visible: Int = 5,
): List<AnnualCategoryShare> {
    val values = categories.filter { it.currency == currency }
    if (values.size <= visible) return values
    val rest = values.drop(visible).fold(BigDecimal.ZERO) { sum, share -> sum.add(share.amount) }
    return values.take(visible) + AnnualCategoryShare("其他分类", currency, rest)
}

@Composable
internal fun AnnualCategoryCard(
    report: AnnualReport,
    currency: String,
) {
    val shares = annualDonutShares(report.categories, currency)
    val listed = shares.fold(BigDecimal.ZERO) { sum, share -> sum.add(share.amount) }
    // Percentages use the signed yearly currency total; a non-positive net total cannot produce valid shares.
    val total = report.expense[currency] ?: listed
    val palette =
        listOf(
            MaterialTheme.colorScheme.error,
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.tertiary,
            MaterialTheme.colorScheme.secondary,
            MaterialTheme.colorScheme.outline,
            MaterialTheme.colorScheme.primaryContainer,
        )
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("支出分类占比", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Text(currency, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (shares.isEmpty()) {
                Text("这一年没有可计入统计的支出分类。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                if (total.signum() <= 0) {
                    Text(
                        "退款净抵扣后 $currency 年度支出合计不为正，无法形成有效占比；分类金额按实际净额显示。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (shares.any { it.amount.signum() < 0 }) {
                    Text(
                        "含净抵扣分类：分类金额与占比按实际净额显示，本图不绘制占比环。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    AnnualDonut(
                        total,
                        ledgerAmount(total, currency),
                        shares.map { it.amount },
                        palette,
                        showSlices = total.signum() > 0 && shares.all { it.amount.signum() >= 0 },
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        shares.forEachIndexed { index, share ->
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Box(Modifier.size(8.dp).background(palette[index % palette.size], CircleShape))
                                Text(share.name, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                                Text(ledgerAmount(share.amount, share.currency), style = MaterialTheme.typography.bodySmall)
                                Text(
                                    sharePercent(share.amount, total) ?: "—",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                if (report.omittedCategoryCount > 0) {
                    Text(
                        "分类清单按全币种金额合计排序，仅列出前六个分类与币种组合；未列入项不参与图例。占比按 $currency 全年支出总额计算。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val otherCurrencies = report.categories.filter { it.currency != currency }
                if (otherCurrencies.isNotEmpty()) {
                    Text(
                        "其他币种的分类：${otherCurrencies.joinToString(
                            "、",
                        ) { "${it.name}（${it.currency}）${it.amount.stripTrailingZeros().toPlainString()}" }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun AnnualDonut(
    total: BigDecimal,
    totalText: String,
    amounts: List<BigDecimal>,
    palette: List<Color>,
    showSlices: Boolean,
) {
    val trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    Box(Modifier.size(128.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 20.dp.toPx()
            val topLeft = Offset(stroke / 2f, stroke / 2f)
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(trackColor, 0f, 360f, false, topLeft = topLeft, size = arcSize, style = Stroke(stroke))
            if (showSlices) {
                var start = -90f
                amounts.forEachIndexed { index, amount ->
                    val sweep =
                        amount
                            .max(BigDecimal.ZERO)
                            .divide(total, 6, RoundingMode.HALF_UP)
                            .toFloat()
                            .coerceIn(0f, 1f) * 360f
                    if (sweep > 0f) {
                        drawArc(
                            palette[index % palette.size],
                            start,
                            (sweep - 1.5f).coerceAtLeast(0f),
                            false,
                            topLeft = topLeft,
                            size = arcSize,
                            style = Stroke(stroke),
                        )
                    }
                    start += sweep
                }
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            AmountText(totalText, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
            Text("支出合计", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun AnnualTrendCard(
    report: AnnualReport,
    currency: String,
) {
    val months = report.months[currency] ?: return
    val peak = months.maxOfOrNull { maxOf(it.income, it.expense) } ?: BigDecimal.ZERO
    // Refund adjustments can drive a month's signed total below zero; those bars show as empty and need a caption.
    val hasNetOffset = months.any { it.income.signum() < 0 || it.expense.signum() < 0 }
    val incomeColor = MaterialTheme.colorScheme.tertiary
    val expenseColor = MaterialTheme.colorScheme.error
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("月度收支趋势", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AnnualLegendDot(incomeColor, "收入")
                    AnnualLegendDot(expenseColor, "支出")
                }
            }
            Text(
                "$currency · 柱高按当年该币种最大值等比，不换算汇率",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (hasNetOffset) {
                Text(
                    "退款抵扣超过当月支出的月份净额为负，柱高按 0 显示；具体金额见年度收支与分类金额行。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                months.forEach { month ->
                    Text(
                        "${month.month}月 收${month.income.stripTrailingZeros().toPlainString()} 支${month.expense.stripTrailingZeros().toPlainString()}",
                        style = MaterialTheme.typography.labelSmall,
                        color =
                            if (month.income.signum() < 0 ||
                                month.expense.signum() < 0
                            ) {
                                expenseColor
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                months.forEach { month ->
                    Column(
                        Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(
                            Modifier.height(88.dp),
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(1.dp),
                        ) {
                            AnnualTrendBar(month.income, peak, incomeColor)
                            AnnualTrendBar(month.expense, peak, expenseColor)
                        }
                        Text("${month.month}", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun AnnualTrendBar(
    value: BigDecimal,
    peak: BigDecimal,
    color: Color,
) {
    val ratio =
        if (peak.signum() <= 0 || value.signum() <= 0) {
            0f
        } else {
            value.divide(peak, 6, RoundingMode.HALF_UP).toFloat().coerceIn(0f, 1f)
        }
    Box(
        Modifier
            .width(4.dp)
            .height(88.dp * ratio)
            .background(color, RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp)),
    )
}

@Composable
private fun AnnualLegendDot(
    color: Color,
    label: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun AnnualHighlightsCard(report: AnnualReport) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("年度要点", style = MaterialTheme.typography.titleMedium)
            report.largest.toSortedMap().entries.take(3).forEach { (currency, entry) ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "最大支出 · $currency",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    AmountText("${moneyText(entry.amount)} $currency", income = false, style = MaterialTheme.typography.titleSmall)
                    if (entry.remark.isNotBlank()) {
                        Text(entry.remark.take(80), style = MaterialTheme.typography.bodySmall, maxLines = 2)
                    }
                }
            }
            if (report.largest.isEmpty()) {
                Text("这一年没有支出账单。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (report.largest.size > 3) {
                Text(
                    "另有 ${report.largest.size - 3} 种支出币种的最大支出未展开显示。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("记账天数", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text("${report.daysRecorded} 天", style = MaterialTheme.typography.bodyMedium)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("账单 / 统计贡献", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text("${report.recordCount} 笔 / ${report.entryCount} 条", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private fun sharePercent(
    amount: BigDecimal,
    total: BigDecimal,
): String? {
    if (total.signum() <= 0) return null
    return amount
        .multiply(BigDecimal(100))
        .divide(total, 1, RoundingMode.HALF_UP)
        .stripTrailingZeros()
        .toPlainString() + "%"
}

@Composable
internal fun ReceiptPreviewDialog(
    snapshot: FinanceSnapshot,
    record: FinanceRecord,
    zone: ZoneId = ZoneId.systemDefault(),
    export: ReceiptExport,
    onDismiss: () -> Unit,
) {
    val key = listOf(snapshot.ownerId, snapshot.epochId, snapshot.ledgerId, record.id)
    // The configuration, the frozen image and the picker result belong to the Activity, so locking or
    // the system picker cannot drop them; only the in-memory copy is used here.
    val draft = export.draft ?: return
    val receiptScope = draft.scope
    val style = draft.style
    val baseReceipt =
        remember(snapshot, record, receiptScope, zone) {
            runCatching { receiptReport(snapshot, record, receiptScope, "", "", zone, ReceiptPaperStyle.PLAIN) }.getOrNull()
        }
    if (baseReceipt == null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("暂无法生成小票") },
            text = { Text("账单数据暂不可用于生成，请返回账本检查。") },
            confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        )
        return
    }
    val receipt = baseReceipt.copy(title = draft.title, subtitle = draft.subtitle, style = style)
    val receiptPayload = remember(receipt) { ExportPayload.Receipt(receipt, receiptKey(snapshot)) }
    val bitmap = export.frozen?.takeIf { it.first == receiptPayload }?.second
    var generating by remember(key) { mutableStateOf(export.frozen?.first != receiptPayload) }
    val latestSnapshot by rememberUpdatedState(snapshot)
    val saveImage: () -> Unit = {
        val image = bitmap
        if (image != null && !generating && receipt.isCurrent(latestSnapshot)) {
            export.requestSave(ExportPayload.Receipt(receipt, receiptKey(latestSnapshot)), image, "icos-receipt.png")
        }
    }
    val share: () -> Unit = {
        val image = bitmap
        if (image != null && !generating && receipt.isCurrent(latestSnapshot)) {
            export.requestShare(ExportPayload.Receipt(receipt, receiptKey(latestSnapshot)), image)
        }
    }
    LaunchedEffect(receipt) {
        if (export.frozen?.first == receiptPayload) {
            generating = false
            return@LaunchedEffect
        }
        generating = true
        export.showStatus(null)
        if (!receipt.isCurrent(latestSnapshot)) {
            generating = false
            export.showStatus("账单已失效，请重新选择。")
            return@LaunchedEffect
        }
        delay(180)
        val image =
            withContext(Dispatchers.Default) {
                try {
                    ReportImageRenderer.receipt(receipt)
                } catch (_: RuntimeException) {
                    null
                } catch (_: OutOfMemoryError) {
                    null
                }
            }
        if (image == null) {
            generating = false
            export.showStatus("无法生成小票图片，请缩小范围后重试。")
        } else if (receipt.isCurrent(latestSnapshot)) {
            export.freeze(ExportPayload.Receipt(receipt, receiptKey(latestSnapshot)), image)
            generating = false
        } else {
            image.recycle()
            generating = false
            export.showStatus("账单已失效，请重新选择。")
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        ThemeSystemBars()
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .imePadding()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { AppIcon(AppSymbol.BACK, "关闭") }
                    Text("记账小票", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    IconButton(
                        enabled = bitmap != null && !generating && !export.busy,
                        onClick = share,
                    ) { AppIcon(AppSymbol.SHARE, "分享小票") }
                    IconButton(enabled = bitmap != null && !generating && !export.busy, onClick = saveImage) {
                        AppIcon(AppSymbol.DOWNLOAD, "保存图片")
                    }
                }
                Card(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Column {
                        val image = bitmap
                        if (image != null) {
                            Box(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                                Image(
                                    image.asImageBitmap(),
                                    contentDescription = receipt.accessibleText(),
                                    modifier = Modifier.fillMaxWidth().aspectRatio(image.width.toFloat() / image.height),
                                )
                            }
                        } else {
                            Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
                                Text(
                                    if (generating) "正在生成小票预览…" else "暂无法生成小票",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                Card(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "小票范围 · ${zone.id}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ReceiptScope.entries.forEach { option ->
                                FilterChip(
                                    selected = receiptScope == option,
                                    onClick = { export.update { it.copy(scope = option) } },
                                    label = { Text(option.label) },
                                )
                            }
                        }
                    }
                }
                Card(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("小票模板", style = MaterialTheme.typography.titleMedium)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            ReceiptPaperStyle.entries.forEach { option ->
                                ReceiptStyleSwatch(option, style == option) { export.update { it.copy(style = option) } }
                            }
                        }
                    }
                }
                Card(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                draft.title,
                                { text -> export.update { it.copy(title = text.take(48)) } },
                                modifier = Modifier.weight(1f),
                                label = { Text("标题") },
                                singleLine = true,
                            )
                            OutlinedTextField(
                                draft.subtitle,
                                { text -> export.update { it.copy(subtitle = text.take(96)) } },
                                modifier = Modifier.weight(1f),
                                label = { Text("副标题") },
                                singleLine = true,
                            )
                        }
                        Text(
                            "仅影响本次小票图片，关闭后不保存。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        enabled = bitmap != null && !generating && !export.busy,
                        onClick = saveImage,
                        modifier = Modifier.weight(1f),
                    ) {
                        AppIcon(AppSymbol.DOWNLOAD)
                        Text("保存图片", Modifier.padding(start = 8.dp))
                    }
                    Button(enabled = bitmap != null && !generating && !export.busy, onClick = share, modifier = Modifier.weight(1f)) {
                        AppIcon(AppSymbol.SHARE)
                        Text("分享", Modifier.padding(start = 8.dp))
                    }
                }
                export.status?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("关闭") }
            }
        }
    }
}

private fun ReceiptReport.accessibleText(): String =
    buildString {
        append(title.ifBlank { "记账小票" })
            .append("。")
            .append(subtitle)
            .append("。")
            .append(dateRange)
            .append("。")
        rows.forEach {
            append(
                it.label,
            ).append("，").append(it.amount).append("，").append(it.detail).append("，").append(it.extra.joinToString("，")).append("。")
        }
        totals.forEach {
            append(it.currency)
                .append("，支出 ")
                .append(it.expense.toPlainString())
                .append("，收入 ")
                .append(it.income.toPlainString())
                .append("。")
        }
        append("共 ").append(recordCount).append(" 笔，按原始账单金额汇总，不抵减独立退款、报销收款凭证。")
        if (omittedRowCount > 0) append("另有 ").append(omittedRowCount).append(" 笔未显示，合计已包含。")
        if (omittedCurrencyCount > 0) append("另有 ").append(omittedCurrencyCount).append(" 种币种未显示。")
    }

@Composable
private fun ReceiptStyleSwatch(
    style: ReceiptPaperStyle,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .selectable(selected = selected, role = androidx.compose.ui.semantics.Role.RadioButton, onClick = onSelect)
                .semantics { contentDescription = style.label },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(54.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(style.paper))
                    .border(
                        width = if (selected) 2.dp else 1.dp,
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(12.dp),
                    ),
        ) {
            Canvas(Modifier.fillMaxSize()) { drawReceiptPattern(style.pattern, Color(style.accent)) }
        }
        Text(
            style.label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun DrawScope.drawReceiptPattern(
    pattern: ReceiptPaperPattern,
    accent: Color,
) {
    when (pattern) {
        ReceiptPaperPattern.NONE -> {
            Unit
        }

        ReceiptPaperPattern.LEAF -> {
            drawOval(
                accent.copy(alpha = 0.5f),
                topLeft = Offset(size.width * 0.18f, size.height * 0.28f),
                size =
                    Size(
                        size.width * 0.5f,
                        size.height * 0.24f,
                    ),
            )
            drawOval(
                accent.copy(alpha = 0.4f),
                topLeft = Offset(size.width * 0.44f, size.height * 0.6f),
                size =
                    Size(
                        size.width * 0.42f,
                        size.height * 0.2f,
                    ),
            )
        }

        ReceiptPaperPattern.BLOSSOM -> {
            repeat(3) { index ->
                drawCircle(
                    accent.copy(alpha = 0.45f),
                    radius = size.minDimension * 0.08f,
                    center = Offset(size.width * (0.28f + index * 0.22f), size.height * (0.34f + index * 0.16f)),
                )
            }
        }

        ReceiptPaperPattern.CLOUD -> {
            drawCircle(accent.copy(alpha = 0.4f), size.minDimension * 0.16f, Offset(size.width * 0.35f, size.height * 0.42f))
            drawCircle(accent.copy(alpha = 0.35f), size.minDimension * 0.13f, Offset(size.width * 0.62f, size.height * 0.36f))
        }

        ReceiptPaperPattern.GRID -> {
            val step = size.width / 4f
            var x = step
            while (x < size.width) {
                drawLine(accent.copy(alpha = 0.35f), Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
                x += step
            }
            var y = step
            while (y < size.height) {
                drawLine(accent.copy(alpha = 0.35f), Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                y += step
            }
        }
    }
}
