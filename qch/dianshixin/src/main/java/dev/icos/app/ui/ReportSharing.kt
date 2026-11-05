package dev.icos.app.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import androidx.core.content.FileProvider
import dev.icos.app.data.local.FinanceRecord
import dev.icos.app.data.local.FinanceSnapshot
import dev.icos.app.data.local.FinanceStatisticEntry
import dev.icos.app.domain.Direction
import dev.icos.app.domain.Money
import dev.icos.app.domain.PeriodResolver
import dev.icos.app.domain.RecordInput
import dev.icos.app.domain.ReportPeriod
import java.io.File
import java.io.FileOutputStream
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin

internal data class AnnualCategoryShare(
    val name: String,
    val currency: String,
    val amount: BigDecimal,
)

internal data class AnnualMonthTotals(
    val month: Int,
    val income: BigDecimal,
    val expense: BigDecimal,
)

internal data class AnnualReport(
    val ownerId: UUID,
    val epochId: UUID,
    val ledgerId: UUID,
    val year: Int,
    val income: Map<String, BigDecimal>,
    val expense: Map<String, BigDecimal>,
    val incomeExpenseRatio: Map<String, BigDecimal>,
    val largest: Map<String, FinanceStatisticEntry>,
    val categories: List<AnnualCategoryShare>,
    val months: Map<String, List<AnnualMonthTotals>>,
    val omittedCategoryCount: Int,
    val daysRecorded: Int,
    val recordCount: Int,
    val entryCount: Int,
    val empty: Boolean,
)

/** Twelve month buckets per currency; amounts stay in their own currency and are never converted. */
internal fun annualMonthTotals(
    entries: List<FinanceStatisticEntry>,
    zone: String,
): Map<String, List<AnnualMonthTotals>> {
    val formatter = SimpleDateFormat("M", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone(zone) }
    return entries
        .groupBy { it.amount.currency }
        .toSortedMap()
        .mapValues { (_, values) ->
            val income = Array(12) { BigDecimal.ZERO }
            val expense = Array(12) { BigDecimal.ZERO }
            values.forEach { entry ->
                val month = formatter.format(Date(entry.occurredAtMillis)).toIntOrNull() ?: return@forEach
                if (month !in 1..12) return@forEach
                val amount = entry.amount.decimal()
                if (entry.direction == Direction.INCOME) {
                    income[month - 1] = income[month - 1].add(amount)
                } else {
                    expense[month - 1] = expense[month - 1].add(amount)
                }
            }
            (1..12).map { month -> AnnualMonthTotals(month, income[month - 1], expense[month - 1]) }
        }
}

internal fun annualReport(
    snapshot: FinanceSnapshot,
    year: Int,
    zone: String,
): AnnualReport {
    require(year in 1900..2200)
    val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone(zone) }
    val yearRecords = snapshot.records.filter { formatter.format(Date(it.occurredAtMillis)).startsWith("$year-") }
    val entries =
        snapshot.statisticEntries.filter {
            formatter.format(Date(it.occurredAtMillis)).startsWith("$year-")
        }

    fun totals(direction: Direction): Map<String, BigDecimal> =
        entries
            .asSequence()
            .filter { it.direction == direction }
            .groupBy { it.amount.currency }
            .mapValues { (_, values) -> values.fold(BigDecimal.ZERO) { sum, entry -> sum.add(entry.amount.decimal()) } }
            .toSortedMap()
    val names = snapshot.categories.associate { it.id to it.name }
    val expenses = entries.filter { it.direction == Direction.EXPENSE }
    val incomeExpenseRatio =
        (totals(Direction.INCOME).keys intersect totals(Direction.EXPENSE).keys).associateWith { currency ->
            val expense = totals(Direction.EXPENSE).getValue(currency)
            if (expense.signum() ==
                0
            ) {
                BigDecimal.ZERO
            } else {
                totals(Direction.INCOME).getValue(currency).divide(expense, 4, java.math.RoundingMode.HALF_UP)
            }
        }
    val largest =
        expenses
            .groupBy { it.amount.currency }
            .mapNotNull { (currency, values) ->
                values
                    .maxWithOrNull(
                        compareBy<FinanceStatisticEntry> { it.amount.decimal() }.thenBy { it.occurredAtMillis },
                    )?.let { currency to it }
            }.toMap()
    val allCategories =
        expenses
            .groupBy { (it.categoryId?.let(names::get) ?: "其他") to it.amount.currency }
            .mapValues { (_, values) -> values.fold(BigDecimal.ZERO) { sum, entry -> sum.add(entry.amount.decimal()) } }
            .entries
            .sortedWith(
                compareByDescending<Map.Entry<Pair<String, String>, BigDecimal>> {
                    it.value
                }.thenBy { it.key.first }.thenBy { it.key.second },
            )
    val categoryTotals = allCategories.take(6).map { (key, amount) -> AnnualCategoryShare(key.first, key.second, amount) }
    return AnnualReport(
        snapshot.ownerId,
        snapshot.epochId,
        snapshot.ledgerId,
        year,
        totals(Direction.INCOME),
        totals(Direction.EXPENSE),
        incomeExpenseRatio,
        largest,
        categoryTotals,
        annualMonthTotals(entries, zone),
        (allCategories.size - categoryTotals.size).coerceAtLeast(0),
        yearRecords.map { formatter.format(Date(it.occurredAtMillis)) }.toSet().size,
        yearRecords.size,
        entries.size,
        yearRecords.isEmpty() && entries.isEmpty(),
    )
}

internal enum class ReceiptScope(
    val label: String,
) {
    RECORD("本笔"),
    DAY("本笔当日"),
    MONTH("本笔当月"),
    LEDGER("当前账本"),
}

internal enum class ReceiptPaperPattern { NONE, LEAF, BLOSSOM, CLOUD, GRID }

internal enum class ReceiptPaperStyle(
    val label: String,
    val paper: Int,
    val accent: Int,
    val pattern: ReceiptPaperPattern,
) {
    PLAIN("素纸", 0xFFFFFCF5.toInt(), 0xFFD0A53C.toInt(), ReceiptPaperPattern.NONE),
    GREEN("绿意", 0xFFF1F7EC.toInt(), 0xFF7BA86B.toInt(), ReceiptPaperPattern.LEAF),
    SAKURA("樱粉", 0xFFFDF0F3.toInt(), 0xFFD98BA3.toInt(), ReceiptPaperPattern.BLOSSOM),
    SKY("晴空", 0xFFEDF5FD.toInt(), 0xFF7FA9D6.toInt(), ReceiptPaperPattern.CLOUD),
    GRID("方格", 0xFFFBF5E8.toInt(), 0xFFC0AF88.toInt(), ReceiptPaperPattern.GRID),
}

internal data class ReceiptRow(
    val label: String,
    val detail: String,
    val amount: String,
    /** Null for transfers: the principal never enters income/expense totals. */
    val direction: Direction?,
    val extra: List<String>,
)

internal data class ReceiptCurrencyTotal(
    val currency: String,
    val income: BigDecimal,
    val expense: BigDecimal,
    val incomeCount: Int,
    val expenseCount: Int,
)

internal data class ReceiptReport(
    val ownerId: UUID,
    val epochId: UUID,
    val ledgerId: UUID,
    val recordIds: Set<UUID>,
    val scope: ReceiptScope,
    val title: String,
    val subtitle: String,
    val dateRange: String,
    val rows: List<ReceiptRow>,
    val omittedRowCount: Int,
    val omittedCurrencyCount: Int,
    val recordCount: Int,
    val transferCount: Int,
    val totals: List<ReceiptCurrencyTotal>,
    val style: ReceiptPaperStyle,
)

/** Every referenced record must still exist in the same ledger before a render or share is published. */
internal fun ReceiptReport.isCurrent(snapshot: FinanceSnapshot): Boolean =
    ownerId == snapshot.ownerId && epochId == snapshot.epochId && ledgerId == snapshot.ledgerId &&
        recordIds.isNotEmpty() && snapshot.records.count { it.id in recordIds } == recordIds.size

private const val RECEIPT_MAX_ROWS = 12
private const val RECEIPT_MAX_CURRENCIES = 8

internal fun receiptReport(
    snapshot: FinanceSnapshot,
    anchor: FinanceRecord,
    scope: ReceiptScope,
    title: String,
    subtitle: String,
    zone: ZoneId,
    style: ReceiptPaperStyle,
): ReceiptReport {
    val current = snapshot.records.singleOrNull { it.id == anchor.id } ?: error("BILL_NOT_IN_CURRENT_LEDGER")
    val records = receiptScopeRecords(snapshot, current, scope, zone)
    val ordered = records.sortedWith(compareByDescending<FinanceRecord> { it.occurredAtMillis }.thenBy { it.id })
    val shown = ordered.take(RECEIPT_MAX_ROWS)
    val totals = receiptTotals(records)
    return ReceiptReport(
        snapshot.ownerId,
        snapshot.epochId,
        snapshot.ledgerId,
        records.map { it.id }.toSet(),
        scope,
        title.take(48),
        subtitle.take(96),
        receiptRange(current, scope, zone),
        receiptRows(shown, snapshot, zone, scope),
        ordered.size - shown.size,
        (totals.size - RECEIPT_MAX_CURRENCIES).coerceAtLeast(0),
        records.size,
        records.count { it.input is RecordInput.Transfer },
        totals.take(RECEIPT_MAX_CURRENCIES),
        style,
    )
}

/** Keeps the original single-record entry point; callers that know the statistics zone should pass it. */
internal fun receiptReport(
    snapshot: FinanceSnapshot,
    record: FinanceRecord,
    title: String,
    subtitle: String,
    zone: ZoneId = ZoneId.systemDefault(),
): ReceiptReport = receiptReport(snapshot, record, ReceiptScope.RECORD, title, subtitle, zone, ReceiptPaperStyle.PLAIN)

private fun receiptScopeRecords(
    snapshot: FinanceSnapshot,
    anchor: FinanceRecord,
    scope: ReceiptScope,
    zone: ZoneId,
): List<FinanceRecord> =
    when (scope) {
        ReceiptScope.RECORD -> {
            listOf(anchor)
        }

        ReceiptScope.DAY -> {
            val day = Instant.ofEpochMilli(anchor.occurredAtMillis).atZone(zone).toLocalDate()
            snapshot.records.filter { Instant.ofEpochMilli(it.occurredAtMillis).atZone(zone).toLocalDate() == day }
        }

        ReceiptScope.MONTH -> {
            val range = PeriodResolver.range(ReportPeriod.MONTH, anchor.occurredAtMillis, zone.id)
            snapshot.records.filter { range.contains(it.occurredAtMillis) }
        }

        ReceiptScope.LEDGER -> {
            snapshot.records
        }
    }

/** Per-currency totals over every record in scope; transfer principal is excluded, fee/bonus are kept. */
internal fun receiptTotals(records: List<FinanceRecord>): List<ReceiptCurrencyTotal> {
    class Acc {
        var income: BigDecimal = BigDecimal.ZERO
        var expense: BigDecimal = BigDecimal.ZERO
        var incomeCount: Int = 0
        var expenseCount: Int = 0
    }

    val acc = sortedMapOf<String, Acc>()

    fun add(
        currency: String,
        income: Boolean,
        amount: BigDecimal,
    ) {
        val entry = acc.getOrPut(currency) { Acc() }
        if (income) {
            entry.income = entry.income.add(amount)
            entry.incomeCount++
        } else {
            entry.expense = entry.expense.add(amount)
            entry.expenseCount++
        }
    }
    records.forEach { record ->
        when (val input = record.input) {
            is RecordInput.Expense -> {
                val actual = input.discount?.let(input.gross::checkedSubtract) ?: input.gross
                add(actual.currency, false, actual.decimal())
            }

            is RecordInput.Income -> {
                add(input.amount.currency, true, input.amount.decimal())
            }

            is RecordInput.Transfer -> {
                input.fee?.let { add(it.amount.currency, false, it.amount.decimal()) }
                input.bonus?.let { add(it.amount.currency, true, it.amount.decimal()) }
            }
        }
    }
    return acc.map { (currency, entry) ->
        ReceiptCurrencyTotal(currency, entry.income, entry.expense, entry.incomeCount, entry.expenseCount)
    }
}

private fun receiptRows(
    records: List<FinanceRecord>,
    snapshot: FinanceSnapshot,
    zone: ZoneId,
    scope: ReceiptScope,
): List<ReceiptRow> {
    val names = snapshot.historicalAccountNames + snapshot.allAccounts.associate { it.id to it.name }
    val categories = (snapshot.categories + snapshot.allCategories).distinctBy { it.id }.associate { it.id to it.name }
    val clock =
        DateTimeFormatter.ofPattern(
            when (scope) {
                ReceiptScope.RECORD, ReceiptScope.DAY -> "HH:mm"
                ReceiptScope.MONTH -> "MM-dd HH:mm"
                ReceiptScope.LEDGER -> "yyyy-MM-dd HH:mm"
            },
            Locale.ROOT,
        )
    return records.map { record ->
        val time = Instant.ofEpochMilli(record.occurredAtMillis).atZone(zone).format(clock)
        when (val input = record.input) {
            is RecordInput.Expense -> {
                val actual = input.discount?.let(input.gross::checkedSubtract) ?: input.gross
                ReceiptRow(
                    label = record.categoryId?.let(categories::get) ?: "支出",
                    detail = receiptDetail(time, input.allocations.map { it.accountId }, names, record.remark),
                    amount = receiptMoney(actual, Direction.EXPENSE),
                    direction = Direction.EXPENSE,
                    extra = emptyList(),
                )
            }

            is RecordInput.Income -> {
                ReceiptRow(
                    label = record.categoryId?.let(categories::get) ?: "收入",
                    detail = receiptDetail(time, input.allocations.map { it.accountId }, names, record.remark),
                    amount = receiptMoney(input.amount, Direction.INCOME),
                    direction = Direction.INCOME,
                    extra = emptyList(),
                )
            }

            is RecordInput.Transfer -> {
                ReceiptRow(
                    label = "转账",
                    detail = "$time · ${names[input.from.accountId] ?: "账户"} → ${names[input.to.accountId] ?: "账户"}",
                    amount = "不计收支",
                    direction = null,
                    extra =
                        listOfNotNull(
                            "本金 ${receiptMoney(input.from.amount)} → ${receiptMoney(input.to.amount)}",
                            input.fee?.let { "手续费 ${receiptMoney(it.amount, Direction.EXPENSE)}" },
                            input.bonus?.let { "返现 ${receiptMoney(it.amount, Direction.INCOME)}" },
                        ),
                )
            }
        }
    }
}

private fun receiptDetail(
    time: String,
    accountIds: List<UUID>,
    names: Map<UUID, String>,
    remark: String,
): String {
    val accounts = accountIds.distinct().map { names[it] ?: "账户" }
    val accountText =
        when {
            accounts.isEmpty() -> null
            accounts.size <= 2 -> accounts.joinToString("、")
            else -> "${accounts.first()} 等 ${accounts.size} 个账户"
        }
    return listOfNotNull(time, accountText, remark.trim().take(24).takeIf { it.isNotBlank() }).joinToString(" · ")
}

private fun receiptRange(
    anchor: FinanceRecord,
    scope: ReceiptScope,
    zone: ZoneId,
): String {
    val at = Instant.ofEpochMilli(anchor.occurredAtMillis).atZone(zone)
    return when (scope) {
        ReceiptScope.RECORD -> at.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT))
        ReceiptScope.DAY -> at.format(DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT))
        ReceiptScope.MONTH -> at.format(DateTimeFormatter.ofPattern("yyyy年M月", Locale.ROOT))
        ReceiptScope.LEDGER -> "当前账本"
    }
}

private fun receiptSignedAmount(
    value: BigDecimal,
    income: Boolean,
): String {
    val signed = if (income) value else value.negate()
    return (if (signed.signum() > 0) "+" else "") + signed.toPlainString()
}

private fun receiptMoney(
    value: Money,
    direction: Direction? = null,
): String =
    "${if (direction == null) {
        value.decimal().toPlainString()
    } else {
        receiptSignedAmount(
            value.decimal(),
            direction == Direction.INCOME,
        )
    }} ${value.currency}"

private fun Money.decimal(): BigDecimal = BigDecimal.valueOf(unscaled, scale)

private val RECEIPT_INK = 0xFF372E1D.toInt()
private val RECEIPT_SUBTLE = 0xFF8A8377.toInt()
private val RECEIPT_EXPENSE = 0xFFB4462F.toInt()
private val RECEIPT_INCOME = 0xFF2F7A56.toInt()
private val RECEIPT_DIVIDER = 0xFFD9D4C6.toInt()
private val RECEIPT_PAGE = 0xFFF3EEE4.toInt()

internal object ReportImageRenderer {
    private const val WIDTH = 1080
    private const val ANNUAL_MAX_HEIGHT = 9000
    private val ANNUAL_GOLD = 0xFFFBF1CD.toInt()
    private val ANNUAL_CARD_BG = 0xFFFFFFFF.toInt()
    private val ANNUAL_TRACK = 0xFFE9E2D4.toInt()
    private val ANNUAL_PALETTE =
        intArrayOf(
            0xFFB4462F.toInt(),
            0xFFD0A53C.toInt(),
            0xFF2F7A56.toInt(),
            0xFF4672B8.toInt(),
            0xFF7960AE.toInt(),
            0xFFA89C86.toInt(),
        )

    class AnnualImageTooLarge : IllegalArgumentException()

    fun annual(report: AnnualReport): Bitmap {
        val scratch = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val bottom =
            try {
                drawAnnualReport(Canvas(scratch), report, 48f)
            } finally {
                scratch.recycle()
            }
        val height =
            kotlin.math
                .ceil((bottom + 48f).toDouble())
                .toInt()
                .coerceAtLeast(360)
        // shortcut: reject reports above the bitmap budget; add paged export when larger reports are required.
        if (height > ANNUAL_MAX_HEIGHT) throw AnnualImageTooLarge()
        val bitmap = Bitmap.createBitmap(WIDTH, height, Bitmap.Config.ARGB_8888)
        var complete = false
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(RECEIPT_PAGE)
            check(drawAnnualReport(canvas, report, 48f) + 48f <= height) { "Annual image layout exceeds its measured height" }
            complete = true
            return bitmap
        } finally {
            if (!complete) bitmap.recycle()
        }
    }

    private data class AnnualCurrencyBlock(
        val currency: String,
        val columns: List<Pair<String, String>>,
        val ratio: BigDecimal?,
    )

    private fun drawAnnualReport(
        canvas: Canvas,
        report: AnnualReport,
        top: Float,
    ): Float {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val contentWidth = WIDTH - 96f
        var y = drawAnnualHero(canvas, paint, report, top, contentWidth)
        if (report.empty) {
            return drawAnnualEmptyCard(canvas, paint, report, y, contentWidth)
        }
        y = drawAnnualCurrencyTotals(canvas, paint, report, y, contentWidth)
        if (annualPrimaryCurrency(report) != null) {
            y = drawAnnualCategories(canvas, paint, report, y, contentWidth)
            y = drawAnnualMonths(canvas, paint, report, y, contentWidth)
        }
        return drawAnnualHighlights(canvas, paint, report, y, contentWidth)
    }

    private fun annualInk(
        size: Float,
        bold: Boolean = false,
    ): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            color = RECEIPT_INK
            typeface = receiptTypeface(bold)
        }

    private fun annualLines(
        text: String,
        paint: Paint,
        maxWidth: Float,
    ): List<String> {
        if (text.isEmpty()) return listOf("")
        val lines = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            var end = text.length
            while (end > start + 1 && paint.measureText(text, start, end) > maxWidth) end--
            if (end <= start) end = start + 1
            lines += text.substring(start, end)
            start = end
        }
        return lines
    }

    private fun annualDrawLines(
        canvas: Canvas,
        lines: List<String>,
        left: Float,
        top: Float,
        lineHeight: Float,
        paint: Paint,
    ): Float {
        var y = top
        lines.forEach {
            canvas.drawText(it, left, y + paint.textSize, paint)
            y += lineHeight
        }
        return y
    }

    /** Shrinks instead of ellipsizing so totals and long amounts are never cut. */
    private fun annualDrawFitted(
        canvas: Canvas,
        text: String,
        paint: Paint,
        maxSize: Float,
        x: Float,
        baseline: Float,
        maxWidth: Float,
        align: Paint.Align,
    ) {
        paint.textSize = maxSize
        val width = paint.measureText(text)
        if (width > maxWidth) paint.textSize *= maxWidth / width
        val previous = paint.textAlign
        paint.textAlign = align
        canvas.drawText(text, x, baseline, paint)
        paint.textAlign = previous
    }

    private fun drawAnnualCard(
        canvas: Canvas,
        paint: Paint,
        top: Float,
        height: Float,
        contentWidth: Float,
        color: Int = ANNUAL_CARD_BG,
    ) {
        paint.reset()
        paint.isAntiAlias = true
        paint.color = color
        canvas.drawRoundRect(RectF(48f, top, 48f + contentWidth, top + height), 36f, 36f, paint)
    }

    private fun annualMoney(
        value: BigDecimal,
        currency: String,
    ): String = "${ledgerAmount(value, currency)} $currency"

    private fun annualPercent(
        amount: BigDecimal,
        total: BigDecimal,
    ): String? {
        if (total.signum() <= 0) return null
        return amount
            .multiply(BigDecimal(100))
            .divide(total, 1, java.math.RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString() + "%"
    }

    private fun annualBarRatio(
        value: BigDecimal,
        peak: BigDecimal,
    ): Float =
        if (peak.signum() <= 0 || value.signum() <= 0) {
            0f
        } else {
            value.divide(peak, 6, java.math.RoundingMode.HALF_UP).toFloat().coerceIn(0f, 1f)
        }

    private fun drawAnnualHero(
        canvas: Canvas,
        paint: Paint,
        report: AnnualReport,
        top: Float,
        contentWidth: Float,
    ): Float {
        val height = 330f
        val pad = 40f
        val artSize = 240f
        val left = 48f + pad
        val textWidth = contentWidth - pad * 2 - artSize - 24f
        drawAnnualCard(canvas, paint, top, height, contentWidth, ANNUAL_GOLD)
        var y = top + pad
        paint.color = RECEIPT_INK
        paint.textSize = 34f
        paint.typeface = receiptTypeface(false)
        canvas.drawText("${report.year} 年", left, y + 34f, paint)
        y += 44f
        paint.textSize = 52f
        paint.typeface = receiptTypeface(true)
        canvas.drawText(if (report.empty) "记录尚未开始" else "认真记录的每一天", left, y + 52f, paint)
        y += 64f
        paint.textSize = 30f
        paint.typeface = receiptTypeface(false)
        val body = if (report.empty) "这一年还没有账单。" else "累计记录 ${report.daysRecorded} 天 · ${report.recordCount} 笔账单"
        y = annualDrawLines(canvas, annualLines(body, paint, textWidth), left, y, 42f, paint) + 8f
        paint.textSize = 26f
        paint.color = RECEIPT_SUBTLE
        val note = annualLines("${report.entryCount} 条计入收支统计 · 按币种分别统计，不换算汇率", paint, textWidth)
        annualDrawLines(canvas, note, left, y, 36f, paint)
        drawAnnualCalendar(canvas, paint, 48f + contentWidth - pad - artSize, top + (height - artSize) / 2f, artSize, report)
        return top + height + 28f
    }

    private fun drawAnnualCalendar(
        canvas: Canvas,
        paint: Paint,
        left: Float,
        top: Float,
        size: Float,
        report: AnnualReport,
    ) {
        val currency = annualPrimaryCurrency(report)
        val values =
            (currency?.let { report.months[it] } ?: emptyList())
                .map { it.income.max(BigDecimal.ZERO).add(it.expense.max(BigDecimal.ZERO)) }
                .filter { it.signum() > 0 }
                .takeLast(5)
        val peak = values.maxOrNull() ?: BigDecimal.ZERO
        paint.reset()
        paint.isAntiAlias = true
        paint.color = 0xFFFFFDF8.toInt()
        canvas.drawRoundRect(RectF(left, top + size * 0.1f, left + size, top + size), size * 0.16f, size * 0.16f, paint)
        paint.color = 0xFFD0A53C.toInt()
        val header = RectF(left, top + size * 0.1f, left + size, top + size * 0.29f)
        canvas.drawRoundRect(header, size * 0.16f, size * 0.16f, paint)
        canvas.drawRect(left, header.centerY(), left + size, header.bottom, paint)
        paint.color = RECEIPT_INK
        paint.strokeWidth = size * 0.045f
        paint.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(left + size * 0.3f, top + size * 0.02f, left + size * 0.3f, top + size * 0.2f, paint)
        canvas.drawLine(left + size * 0.7f, top + size * 0.02f, left + size * 0.7f, top + size * 0.2f, paint)
        if (peak.signum() > 0) {
            paint.color = 0xFFB98A1C.toInt()
            val barWidth = size * 0.09f
            val gap = size * 0.05f
            val baseY = top + size * 0.86f
            val maxBar = size * 0.44f
            values.forEachIndexed { index, value ->
                val barHeight = size * 0.05f + maxBar * annualBarRatio(value, peak)
                val x = left + size * 0.13f + index * (barWidth + gap)
                canvas.drawRoundRect(RectF(x, baseY - barHeight, x + barWidth, baseY), barWidth / 2f, barWidth / 2f, paint)
            }
        }
    }

    private fun drawAnnualCurrencyTotals(
        canvas: Canvas,
        paint: Paint,
        report: AnnualReport,
        top: Float,
        contentWidth: Float,
    ): Float {
        val pad = 40f
        val left = 48f + pad
        val innerWidth = contentWidth - pad * 2
        val currencies = (report.income.keys + report.expense.keys + report.incomeExpenseRatio.keys).sorted()
        val amountPaint = annualInk(36f, bold = true)
        val columnGap = 24f
        val columnWidth = (innerWidth - columnGap * 2) / 3f
        val blocks =
            currencies.map { currency ->
                val income = report.income[currency] ?: BigDecimal.ZERO
                val expense = report.expense[currency] ?: BigDecimal.ZERO
                AnnualCurrencyBlock(
                    currency,
                    listOf(
                        "收入" to annualMoney(income, currency),
                        "支出" to annualMoney(expense, currency),
                        "结余" to annualMoney(income.subtract(expense), currency),
                    ),
                    report.incomeExpenseRatio[currency],
                )
            }
        val footnote = annualInk(26f).apply { color = RECEIPT_SUBTLE }
        val footnoteLines = annualLines("按币种分别统计，不进行汇率换算", footnote, innerWidth)
        var height = pad * 2 + 56f
        if (blocks.isEmpty()) height += 44f
        blocks.forEach { block ->
            val lines = block.columns.maxOf { annualLines(it.second, amountPaint, columnWidth).size }
            height += 44f + 46f + lines * 46f + 18f
        }
        if (blocks.size > 1) height += footnoteLines.size * 36f
        drawAnnualCard(canvas, paint, top, height, contentWidth)
        var y = top + pad
        paint.color = RECEIPT_INK
        paint.textSize = 42f
        paint.typeface = receiptTypeface(true)
        canvas.drawText("年度收支与结余", left, y + 42f, paint)
        y += 56f
        if (blocks.isEmpty()) {
            paint.color = RECEIPT_SUBTLE
            paint.textSize = 27f
            paint.typeface = receiptTypeface(false)
            canvas.drawText("没有可计入统计的收支。", left, y + 27f, paint)
            y += 44f
        }
        blocks.forEach { block ->
            paint.color = RECEIPT_SUBTLE
            paint.textSize = 30f
            paint.typeface = receiptTypeface(true)
            canvas.drawText(block.currency, left, y + 30f, paint)
            block.ratio?.let { ratio ->
                paint.textSize = 26f
                paint.typeface = receiptTypeface(false)
                annualDrawFitted(
                    canvas,
                    "收支比 ${ratio.stripTrailingZeros().toPlainString()}",
                    paint,
                    26f,
                    48f + contentWidth - pad,
                    y + 28f,
                    innerWidth * 0.5f,
                    Paint.Align.RIGHT,
                )
            }
            y += 44f
            val lines = block.columns.maxOf { annualLines(it.second, amountPaint, columnWidth).size }
            block.columns.forEachIndexed { index, (label, amount) ->
                val x = left + index * (columnWidth + columnGap)
                paint.color = RECEIPT_SUBTLE
                paint.textSize = 27f
                paint.typeface = receiptTypeface(false)
                canvas.drawText(label, x, y + 27f, paint)
                paint.color =
                    when (index) {
                        0 -> RECEIPT_INCOME
                        1 -> RECEIPT_EXPENSE
                        else -> RECEIPT_INK
                    }
                paint.textSize = 36f
                paint.typeface = receiptTypeface(true)
                annualDrawLines(canvas, annualLines(amount, paint, columnWidth), x, y + 46f, 46f, paint)
            }
            y += 46f + lines * 46f + 18f
        }
        if (blocks.size > 1) {
            paint.color = RECEIPT_SUBTLE
            paint.textSize = 26f
            paint.typeface = receiptTypeface(false)
            annualDrawLines(canvas, footnoteLines, left, y, 36f, paint)
        }
        return top + height + 28f
    }

    private fun drawAnnualCategories(
        canvas: Canvas,
        paint: Paint,
        report: AnnualReport,
        top: Float,
        contentWidth: Float,
    ): Float {
        val currency = annualPrimaryCurrency(report) ?: return top
        val pad = 40f
        val left = 48f + pad
        val right = 48f + contentWidth - pad
        val innerWidth = contentWidth - pad * 2
        val shares = annualDonutShares(report.categories, currency)
        val listed = shares.fold(BigDecimal.ZERO) { sum, share -> sum.add(share.amount) }
        // Percentages use the signed yearly currency total; a non-positive net total cannot produce valid shares.
        val total = report.expense[currency] ?: listed
        val showSlices = total.signum() > 0 && shares.all { it.amount.signum() >= 0 }
        val namePaint = annualInk(30f)
        val notePaint = annualInk(26f).apply { color = RECEIPT_SUBTLE }
        val donutSize = 300f
        val legendLeft = left + donutSize + 40f
        val legendWidth = right - legendLeft
        val nameWidth = legendWidth - 96f
        val amountPaint = annualInk(28f, bold = true)
        val rowHeights =
            shares.map { share ->
                annualLines(share.name, namePaint, nameWidth).size * 36f +
                    annualLines(annualMoney(share.amount, share.currency), amountPaint, nameWidth).size * 36f + 10f
            }
        val legendHeight = rowHeights.sum()
        val notes =
            buildList {
                if (shares.isEmpty()) add("这一年没有可计入统计的支出分类。")
                if (total.signum() <= 0) {
                    add("退款净抵扣后 $currency 年度支出合计不为正，无法形成有效占比；分类金额按实际净额显示。")
                } else if (shares.any { it.amount.signum() < 0 }) {
                    add("含净抵扣分类：分类金额与占比按实际净额显示，本图不绘制占比环。")
                }
                if (report.omittedCategoryCount > 0) {
                    add("另有 ${report.omittedCategoryCount} 个分类未在图中列出；占比按 $currency 全年支出总额计算。")
                }
                val others = report.categories.filter { it.currency != currency }
                if (others.isNotEmpty()) {
                    add("其他币种分类：" + others.joinToString("、") { "${it.name}（${it.currency}）${annualMoney(it.amount, it.currency)}" })
                }
            }.flatMap { annualLines(it, notePaint, innerWidth) }
        val bodyHeight = if (shares.isEmpty()) 0f else maxOf(donutSize, legendHeight)
        val height = pad * 2 + 56f + bodyHeight + (if (notes.isEmpty()) 0f else notes.size * 36f + 18f)
        drawAnnualCard(canvas, paint, top, height, contentWidth)
        var y = top + pad
        paint.color = RECEIPT_INK
        paint.textSize = 42f
        paint.typeface = receiptTypeface(true)
        canvas.drawText("支出分类占比", left, y + 42f, paint)
        paint.color = RECEIPT_SUBTLE
        paint.textSize = 26f
        paint.typeface = receiptTypeface(false)
        paint.textAlign = Paint.Align.RIGHT
        canvas.drawText(currency, right, y + 40f, paint)
        paint.textAlign = Paint.Align.LEFT
        y += 56f
        if (shares.isNotEmpty()) {
            drawAnnualDonut(canvas, paint, left, y, donutSize, total, currency, shares, showSlices)
            var legendY = y
            shares.forEachIndexed { index, share ->
                paint.color = ANNUAL_PALETTE[index % ANNUAL_PALETTE.size]
                canvas.drawCircle(legendLeft + 8f, legendY + 18f, 8f, paint)
                paint.color = RECEIPT_INK
                paint.textSize = 30f
                paint.typeface = receiptTypeface(false)
                val nameEnd = annualDrawLines(canvas, annualLines(share.name, paint, nameWidth), legendLeft + 30f, legendY, 36f, paint)
                paint.color = RECEIPT_SUBTLE
                paint.textSize = 26f
                paint.typeface = receiptTypeface(false)
                annualDrawFitted(
                    canvas,
                    annualPercent(share.amount, total) ?: "—",
                    paint,
                    26f,
                    right,
                    legendY + 26f,
                    90f,
                    Paint.Align.RIGHT,
                )
                paint.color = RECEIPT_INK
                paint.textSize = 28f
                paint.typeface = receiptTypeface(true)
                annualDrawLines(
                    canvas,
                    annualLines(annualMoney(share.amount, share.currency), paint, nameWidth),
                    legendLeft + 30f,
                    nameEnd,
                    36f,
                    paint,
                )
                legendY += rowHeights[index]
            }
        }
        if (notes.isNotEmpty()) {
            paint.color = RECEIPT_SUBTLE
            paint.textSize = 26f
            paint.typeface = receiptTypeface(false)
            val notesTop = if (shares.isEmpty()) y else top + height - pad - notes.size * 36f
            annualDrawLines(canvas, notes, left, notesTop, 36f, paint)
        }
        return top + height + 28f
    }

    private fun drawAnnualDonut(
        canvas: Canvas,
        paint: Paint,
        left: Float,
        top: Float,
        size: Float,
        total: BigDecimal,
        currency: String,
        shares: List<AnnualCategoryShare>,
        showSlices: Boolean,
    ) {
        val stroke = size * 0.16f
        val rect = RectF(left + stroke / 2f, top + stroke / 2f, left + size - stroke / 2f, top + size - stroke / 2f)
        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = stroke
        paint.color = ANNUAL_TRACK
        canvas.drawArc(rect, 0f, 360f, false, paint)
        if (showSlices) {
            var start = -90f
            shares.forEachIndexed { index, share ->
                val sweep =
                    share.amount
                        .divide(total, 6, java.math.RoundingMode.HALF_UP)
                        .toFloat()
                        .coerceIn(0f, 1f) * 360f
                if (sweep > 0f) {
                    paint.color = ANNUAL_PALETTE[index % ANNUAL_PALETTE.size]
                    canvas.drawArc(rect, start, (sweep - 1.5f).coerceAtLeast(0f), false, paint)
                }
                start += sweep
            }
        }
        paint.style = Paint.Style.FILL
        paint.color = RECEIPT_INK
        paint.typeface = receiptTypeface(true)
        val centerX = left + size / 2f
        annualDrawFitted(
            canvas,
            annualMoney(total, currency),
            paint,
            32f,
            centerX,
            top + size / 2f + 4f,
            size - stroke * 2,
            Paint.Align.CENTER,
        )
        paint.color = RECEIPT_SUBTLE
        paint.textSize = 26f
        paint.typeface = receiptTypeface(false)
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText("支出合计", centerX, top + size / 2f + 44f, paint)
        paint.textAlign = Paint.Align.LEFT
    }

    private fun drawAnnualMonths(
        canvas: Canvas,
        paint: Paint,
        report: AnnualReport,
        top: Float,
        contentWidth: Float,
    ): Float {
        val currency = annualPrimaryCurrency(report) ?: return top
        val months = report.months[currency] ?: return top
        val pad = 40f
        val left = 48f + pad
        val right = 48f + contentWidth - pad
        val innerWidth = contentWidth - pad * 2
        val peak = months.maxOfOrNull { maxOf(it.income, it.expense) } ?: BigDecimal.ZERO
        // Refund adjustments can drive a month's signed total below zero; bars show at 0 and the real amounts stay listed.
        val negative = months.filter { it.income.signum() < 0 || it.expense.signum() < 0 }
        val captionPaint = annualInk(26f).apply { color = RECEIPT_SUBTLE }
        val caption =
            annualLines(
                "$currency · 柱高按当年该币种最大值等比，不换算汇率" +
                    if (peak.signum() > 0) "；峰值 ${annualMoney(peak, currency)}" else "",
                captionPaint,
                innerWidth,
            )
        val negativeLines =
            negative.flatMap {
                annualLines(
                    "${it.month}月 收${annualMoney(it.income, currency)} 支${annualMoney(it.expense, currency)}",
                    captionPaint,
                    innerWidth,
                )
            }
        val chartHeight = 280f
        val height = pad * 2 + 56f + caption.size * 36f + 16f + chartHeight + negativeLines.size * 36f
        drawAnnualCard(canvas, paint, top, height, contentWidth)
        var y = top + pad
        paint.color = RECEIPT_INK
        paint.textSize = 42f
        paint.typeface = receiptTypeface(true)
        canvas.drawText("月度收支趋势", left, y + 42f, paint)
        paint.textSize = 26f
        paint.typeface = receiptTypeface(false)
        paint.textAlign = Paint.Align.RIGHT
        paint.color = RECEIPT_INCOME
        canvas.drawText("收入", right, y + 40f, paint)
        paint.color = RECEIPT_EXPENSE
        canvas.drawText("支出", right - 90f, y + 40f, paint)
        paint.textAlign = Paint.Align.LEFT
        y += 56f
        y = annualDrawLines(canvas, caption, left, y, 36f, captionPaint) + 16f
        val topY = y + 8f
        val baseY = y + chartHeight - 36f
        paint.color = ANNUAL_TRACK
        paint.strokeWidth = 2f
        for (level in 1..3) {
            val lineY = baseY - (baseY - topY) * level / 4f
            canvas.drawLine(left, lineY, right, lineY, paint)
        }
        canvas.drawLine(left, baseY, right, baseY, paint)
        val groupWidth = innerWidth / 12f
        val barWidth = 16f
        months.forEachIndexed { index, month ->
            val center = left + groupWidth * index + groupWidth / 2f
            val incomeHeight = (baseY - topY) * annualBarRatio(month.income, peak)
            val expenseHeight = (baseY - topY) * annualBarRatio(month.expense, peak)
            paint.color = RECEIPT_INCOME
            if (incomeHeight > 0f) {
                canvas.drawRoundRect(RectF(center - barWidth - 2f, baseY - incomeHeight, center - 2f, baseY), 4f, 4f, paint)
            }
            paint.color = RECEIPT_EXPENSE
            if (expenseHeight > 0f) {
                canvas.drawRoundRect(RectF(center + 2f, baseY - expenseHeight, center + barWidth + 2f, baseY), 4f, 4f, paint)
            }
            paint.color = RECEIPT_SUBTLE
            paint.textSize = 24f
            paint.typeface = receiptTypeface(false)
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText("${month.month}", center, baseY + 30f, paint)
            paint.textAlign = Paint.Align.LEFT
        }
        if (negativeLines.isNotEmpty()) {
            paint.color = RECEIPT_SUBTLE
            paint.textSize = 26f
            paint.typeface = receiptTypeface(false)
            annualDrawLines(canvas, negativeLines, left, y + chartHeight, 36f, captionPaint)
        }
        return top + height + 28f
    }

    private fun drawAnnualHighlights(
        canvas: Canvas,
        paint: Paint,
        report: AnnualReport,
        top: Float,
        contentWidth: Float,
    ): Float {
        val pad = 40f
        val left = 48f + pad
        val right = 48f + contentWidth - pad
        val innerWidth = contentWidth - pad * 2
        val remarkPaint = annualInk(27f)
        val blocks =
            report.largest.toSortedMap().map { (currency, entry) ->
                val remark = if (entry.remark.isNotBlank()) annualLines(entry.remark, remarkPaint, innerWidth) else emptyList()
                Triple(currency, entry, remark)
            }
        val facts = listOf("记账天数" to "${report.daysRecorded} 天", "账单 / 统计贡献" to "${report.recordCount} 笔 / ${report.entryCount} 条")
        var height = pad * 2 + 56f
        if (blocks.isEmpty()) height += 44f
        blocks.forEach { height += 34f + 48f + it.third.size * 36f + 14f }
        height += 24f + facts.size * 46f
        drawAnnualCard(canvas, paint, top, height, contentWidth)
        var y = top + pad
        paint.color = RECEIPT_INK
        paint.textSize = 42f
        paint.typeface = receiptTypeface(true)
        canvas.drawText("年度要点", left, y + 42f, paint)
        y += 56f
        blocks.forEach { (currency, entry, remark) ->
            paint.color = RECEIPT_SUBTLE
            paint.textSize = 27f
            paint.typeface = receiptTypeface(false)
            canvas.drawText("最大支出 · $currency", left, y + 27f, paint)
            y += 34f
            paint.color = RECEIPT_EXPENSE
            paint.textSize = 38f
            paint.typeface = receiptTypeface(true)
            y = annualDrawLines(canvas, annualLines(annualMoney(entry.amount.decimal(), currency), paint, innerWidth), left, y, 48f, paint)
            if (remark.isNotEmpty()) {
                paint.color = RECEIPT_SUBTLE
                paint.textSize = 27f
                paint.typeface = receiptTypeface(false)
                y = annualDrawLines(canvas, remark, left, y, 36f, paint)
            }
            y += 14f
        }
        if (blocks.isEmpty()) {
            paint.color = RECEIPT_SUBTLE
            paint.textSize = 27f
            paint.typeface = receiptTypeface(false)
            canvas.drawText("这一年没有支出账单。", left, y + 27f, paint)
            y += 44f
        }
        paint.color = RECEIPT_DIVIDER
        paint.strokeWidth = 2f
        canvas.drawLine(left, y + 6f, right, y + 6f, paint)
        y += 24f
        facts.forEach { (label, value) ->
            paint.color = RECEIPT_INK
            paint.textSize = 30f
            paint.typeface = receiptTypeface(false)
            canvas.drawText(label, left, y + 30f, paint)
            annualDrawFitted(canvas, value, paint, 30f, right, y + 30f, innerWidth * 0.6f, Paint.Align.RIGHT)
            y += 46f
        }
        return top + height + 28f
    }

    private fun drawAnnualEmptyCard(
        canvas: Canvas,
        paint: Paint,
        report: AnnualReport,
        top: Float,
        contentWidth: Float,
    ): Float {
        val pad = 40f
        val height = pad * 2 + 46f + 46f
        drawAnnualCard(canvas, paint, top, height, contentWidth)
        paint.color = RECEIPT_INK
        paint.textSize = 30f
        paint.typeface = receiptTypeface(false)
        canvas.drawText("${report.year} 年还没有账单。", 48f + pad, top + pad + 30f, paint)
        paint.color = RECEIPT_SUBTLE
        paint.textSize = 26f
        canvas.drawText("记下第一笔后，这里会显示真实的收支与分类汇总。", 48f + pad, top + pad + 76f, paint)
        return top + height + 28f
    }

    fun receipt(report: ReceiptReport): Bitmap {
        val visible = report.totals.filter { it.incomeCount > 0 || it.expenseCount > 0 }
        val lines =
            buildList {
                add(
                    ReceiptLine(
                        report.title.ifBlank {
                            "记账小票"
                        },
                        size = 56f,
                        color = RECEIPT_INK,
                        height = 92f,
                        bold = true,
                        centered = true,
                        accent = report.style.accent,
                    ),
                )
                if (report.subtitle.isNotBlank()) {
                    add(
                        ReceiptLine(report.subtitle, size = 32f, color = RECEIPT_SUBTLE, height = 54f, centered = true),
                    )
                }
                add(ReceiptLine(report.dateRange, size = 28f, color = RECEIPT_SUBTLE, height = 52f, centered = true))
                add(ReceiptLine(divider = true, height = 40f))
                report.rows.forEach { row ->
                    add(
                        ReceiptLine(
                            row.label,
                            trailing = row.amount,
                            size = 38f,
                            color = RECEIPT_INK,
                            height = 58f,
                            bold = true,
                            trailingColor = receiptAmountColor(row.direction),
                        ),
                    )
                    add(ReceiptLine(row.detail, size = 27f, color = RECEIPT_SUBTLE, height = 40f))
                    row.extra.forEach { add(ReceiptLine(it, size = 26f, color = RECEIPT_SUBTLE, height = 38f, exactAmount = true)) }
                    add(ReceiptLine(height = 20f))
                }
                if (report.rows.isEmpty()) add(ReceiptLine("所选范围没有账单", size = 28f, color = RECEIPT_SUBTLE, height = 54f, centered = true))
                if (report.omittedRowCount > 0) {
                    add(
                        ReceiptLine(
                            "另有 ${report.omittedRowCount} 笔未显示，合计已包含全部 ${report.recordCount} 笔",
                            size = 26f,
                            color = RECEIPT_SUBTLE,
                            height = 54f,
                            centered = true,
                        ),
                    )
                }
                if (report.omittedCurrencyCount > 0) {
                    add(
                        ReceiptLine(
                            "另有 ${report.omittedCurrencyCount} 种币种未显示，请在应用内查看",
                            size = 26f,
                            color = RECEIPT_SUBTLE,
                            height = 50f,
                            centered = true,
                        ),
                    )
                }
                add(ReceiptLine(divider = true, height = 36f))
                if (visible.isEmpty()) {
                    add(
                        ReceiptLine(
                            if (report.transferCount > 0 && report.recordCount == report.transferCount) "转账本金不计入收支" else "所选范围没有收支",
                            size = 28f,
                            color = RECEIPT_SUBTLE,
                            height = 56f,
                            centered = true,
                        ),
                    )
                } else {
                    visible.forEach { total ->
                        if (total.expenseCount > 0) {
                            add(
                                ReceiptLine(
                                    "${total.currency} 支出",
                                    trailing = "共 ${total.expenseCount} 项",
                                    size = 26f,
                                    color = RECEIPT_SUBTLE,
                                    height = 40f,
                                ),
                            )
                            add(
                                ReceiptLine(
                                    receiptSignedAmount(total.expense, income = false),
                                    size = 58f,
                                    color = RECEIPT_EXPENSE,
                                    height = 76f,
                                    bold = true,
                                    exactAmount = true,
                                ),
                            )
                        }
                        if (total.incomeCount > 0) {
                            add(
                                ReceiptLine(
                                    "${total.currency} 收入",
                                    trailing = "共 ${total.incomeCount} 项",
                                    size = 26f,
                                    color = RECEIPT_SUBTLE,
                                    height = 40f,
                                ),
                            )
                            add(
                                ReceiptLine(
                                    receiptSignedAmount(total.income, income = true),
                                    size = 58f,
                                    color = RECEIPT_INCOME,
                                    height = 76f,
                                    bold = true,
                                    exactAmount = true,
                                ),
                            )
                        }
                    }
                }
                add(
                    ReceiptLine(
                        "共 ${report.recordCount} 笔 · 金额按币种分列，不换算",
                        size = 25f,
                        color = RECEIPT_SUBTLE,
                        height = 54f,
                        centered = true,
                    ),
                )
                add(ReceiptLine("按原始账单金额汇总，含不计统计的账单", size = 24f, color = RECEIPT_SUBTLE, height = 40f, centered = true))
                add(ReceiptLine("未抵减独立退款、报销收款凭证", size = 24f, color = RECEIPT_SUBTLE, height = 40f, centered = true))
            }
        // 图片按手机宽度预览时，字号与行距需要一同放大。
        return renderReceipt(report.style, lines.map { it.copy(size = it.size * 1.5f, height = it.height * 1.5f) })
    }

    private fun renderReceipt(
        style: ReceiptPaperStyle,
        lines: List<ReceiptLine>,
    ): Bitmap {
        val top = 96f
        val height = (top + lines.sumOf { it.height.toDouble() } + 112.0).toInt().coerceIn(360, 9000)
        val bitmap = Bitmap.createBitmap(WIDTH, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(RECEIPT_PAGE)
        drawReceiptPaper(canvas, style, height)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        var y = top
        lines.forEach { line ->
            if (line.divider) {
                paint.reset()
                paint.isAntiAlias = true
                paint.color = RECEIPT_DIVIDER
                paint.strokeWidth = 2f
                paint.pathEffect = DashPathEffect(floatArrayOf(14f, 12f), 0f)
                canvas.drawLine(64f, y + line.height / 2f, WIDTH - 64f, y + line.height / 2f, paint)
            } else {
                paint.reset()
                paint.isAntiAlias = true
                paint.color = line.color
                paint.textSize = line.size
                paint.typeface = receiptTypeface(line.bold)
                paint.textAlign = Paint.Align.LEFT
                val baseline = y + line.size
                if (line.centered) {
                    paint.textAlign = Paint.Align.CENTER
                    val text = ellipsize(line.text, paint, WIDTH - 128f)
                    canvas.drawText(text, WIDTH / 2f, baseline, paint)
                    if (line.accent != null) {
                        val half = paint.measureText(text).coerceAtMost(WIDTH - 128f) / 2f
                        paint.color = line.accent
                        paint.strokeWidth = 6f
                        paint.strokeCap = Paint.Cap.ROUND
                        canvas.drawLine(WIDTH / 2f - half, baseline + 12f, WIDTH / 2f + half, baseline + 12f, paint)
                    }
                } else {
                    val trailingWidth =
                        line.trailing?.let {
                            paint.typeface = receiptTypeface(line.trailingBold)
                            paint.textSize *= minOf(1f, (WIDTH - 312f) / paint.measureText(it).coerceAtLeast(1f))
                            paint.measureText(it)
                        } ?: 0f
                    val trailingSize = paint.textSize
                    paint.typeface = receiptTypeface(line.bold)
                    paint.textSize = line.size
                    if (line.exactAmount) paint.textSize *= minOf(1f, (WIDTH - 128f) / paint.measureText(line.text).coerceAtLeast(1f))
                    val label =
                        if (line.exactAmount) {
                            line.text
                        } else {
                            ellipsize(
                                line.text,
                                paint,
                                (WIDTH - 128f - trailingWidth - 24f).coerceAtLeast(160f),
                            )
                        }
                    canvas.drawText(label, 64f, baseline, paint)
                    line.trailing?.let { trailing ->
                        paint.typeface = receiptTypeface(line.trailingBold)
                        paint.textSize = trailingSize
                        paint.color = line.trailingColor ?: line.color
                        paint.textAlign = Paint.Align.RIGHT
                        canvas.drawText(trailing, WIDTH - 64f, baseline, paint)
                    }
                }
            }
            y += line.height
        }
        return bitmap
    }

    private data class ReceiptLine(
        val text: String = "",
        val trailing: String? = null,
        val size: Float = 28f,
        val color: Int = RECEIPT_INK,
        val trailingColor: Int? = null,
        val height: Float = 40f,
        val bold: Boolean = false,
        val trailingBold: Boolean = false,
        val centered: Boolean = false,
        val accent: Int? = null,
        val exactAmount: Boolean = false,
        val divider: Boolean = false,
    )

    private fun receiptAmountColor(direction: Direction?): Int =
        when (direction) {
            Direction.EXPENSE -> RECEIPT_EXPENSE
            Direction.INCOME -> RECEIPT_INCOME
            null -> RECEIPT_SUBTLE
        }

    private fun receiptTypeface(bold: Boolean): Typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)

    private fun drawReceiptPaper(
        canvas: Canvas,
        style: ReceiptPaperStyle,
        height: Int,
    ) {
        val left = 18f
        val right = WIDTH - 18f
        val tooth = 16f
        val teeth = 28
        val step = (right - left) / teeth
        val path =
            Path().apply {
                moveTo(left, tooth)
                for (index in 0 until teeth) {
                    lineTo(left + index * step + step / 2f, 0f)
                    lineTo(left + (index + 1) * step, tooth)
                }
                lineTo(right, height - tooth)
                for (index in teeth - 1 downTo 0) {
                    lineTo(left + index * step + step / 2f, height.toFloat())
                    lineTo(left + index * step, height - tooth)
                }
                close()
            }
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = style.paper })
        canvas.save()
        canvas.clipPath(path)
        drawReceiptPattern(canvas, style, height)
        canvas.restore()
    }

    private fun drawReceiptPattern(
        canvas: Canvas,
        style: ReceiptPaperStyle,
        height: Int,
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = style.accent }
        when (style.pattern) {
            ReceiptPaperPattern.NONE -> {
                return
            }

            ReceiptPaperPattern.LEAF -> {
                paint.alpha = 34
                var y = 80f
                var offset = 0f
                while (y < height) {
                    var x = 70f + offset
                    while (x < WIDTH) {
                        canvas.drawOval(RectF(x, y, x + 46f, y + 22f), paint)
                        x += 150f
                    }
                    y += 120f
                    offset = if (offset == 0f) 40f else 0f
                }
            }

            ReceiptPaperPattern.BLOSSOM -> {
                paint.alpha = 40
                var y = 90f
                while (y < height) {
                    var x = 80f
                    while (x < WIDTH) {
                        for (petal in 0 until 5) {
                            val angle = petal * Math.PI * 2 / 5
                            canvas.drawCircle(x + cos(angle).toFloat() * 12f, y + sin(angle).toFloat() * 12f, 9f, paint)
                        }
                        x += 140f
                    }
                    y += 130f
                }
            }

            ReceiptPaperPattern.CLOUD -> {
                paint.alpha = 45
                var y = 110f
                while (y < height) {
                    var x = 90f
                    while (x < WIDTH) {
                        canvas.drawCircle(x, y, 30f, paint)
                        canvas.drawCircle(x + 34f, y - 10f, 38f, paint)
                        canvas.drawCircle(x + 72f, y, 26f, paint)
                        x += 210f
                    }
                    y += 160f
                }
            }

            ReceiptPaperPattern.GRID -> {
                paint.alpha = 46
                paint.strokeWidth = 2f
                var x = 60f
                while (x < WIDTH) {
                    canvas.drawLine(x, 0f, x, height.toFloat(), paint)
                    x += 54f
                }
                var y = 60f
                while (y < height) {
                    canvas.drawLine(0f, y, WIDTH.toFloat(), y, paint)
                    y += 54f
                }
            }
        }
    }

    private fun ellipsize(
        value: String,
        paint: Paint,
        maxWidth: Float,
    ): String {
        if (paint.measureText(value) <= maxWidth) return value
        var end = value.length
        while (end > 1 && paint.measureText(value.substring(0, end) + "…") > maxWidth) end--
        return value.substring(0, end) + "…"
    }
}

internal fun saveReportBitmap(
    context: Context,
    bitmap: Bitmap,
    uri: Uri,
): Boolean =
    runCatching {
        context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        } ?: error("Destination unavailable")
    }.isSuccess

internal fun writeShareBitmap(
    context: Context,
    bitmap: Bitmap,
    name: String,
): Uri? =
    runCatching {
        val directory = File(context.cacheDir, "report-sharing").apply { mkdirs() }
        directory
            .listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?.drop(3)
            ?.forEach { it.delete() }
        val file = File(directory, "${UUID.randomUUID()}-${name.take(24)}.png")
        FileOutputStream(file).use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        FileProvider.getUriForFile(context, "${context.packageName}.report-files", file)
    }.getOrNull()

internal fun launchReportShare(
    context: Context,
    uri: Uri,
): Boolean =
    runCatching {
        val send =
            Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = android.content.ClipData.newUri(context.contentResolver, "ICOS image", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        context.startActivity(Intent.createChooser(send, "分享图片").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)
