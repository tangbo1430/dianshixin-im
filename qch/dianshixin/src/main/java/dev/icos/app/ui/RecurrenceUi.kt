package dev.icos.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** 周期计划的展示文案与本地时间换算；wire 值仍由 EditorModels/ViewModel 校验。 */
internal val recurrenceFilterStates = listOf("RUNNING", "PAUSED", "ENDED")

internal fun recurrenceStateLabel(state: String): String =
    when (state) {
        "RUNNING" -> "进行中"
        "PAUSED" -> "已暂停"
        "ENDED" -> "已结束"
        else -> state
    }

internal fun recurrenceIntervalUnit(frequency: String): String =
    when (frequency) {
        "DAILY" -> "天"
        "WEEKLY" -> "周"
        "MONTHLY" -> "个月"
        "YEARLY" -> "年"
        else -> "周期"
    }

internal val recurrenceWeekDayNames = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

internal fun recurrenceRuleText(
    frequency: String?,
    interval: Int,
    weekDays: List<Int>,
    monthDays: List<Int>,
): String {
    if (frequency == null) return "未设置重复规则"
    val base = "每 $interval ${recurrenceIntervalUnit(frequency)}"
    val week =
        weekDays
            .filter { it in 1..7 }
            .distinct()
            .sorted()
            .joinToString("、") { recurrenceWeekDayNames[it - 1] }
    val month =
        monthDays
            .filter { it in 1..31 }
            .distinct()
            .sorted()
            .joinToString("、") { "$it 日" }
    return when (frequency) {
        "WEEKLY" -> if (week.isNotEmpty()) "$base · $week" else "$base · 未设置星期"
        "MONTHLY" -> if (month.isNotEmpty()) "$base · 每月 $month" else "$base · 按开始日"
        "YEARLY" -> if (month.isNotEmpty()) "$base · 每年 $month" else "$base · 按开始日"
        else -> base
    }
}

internal fun recurrenceNextDueText(
    nextDueAtMillis: Long?,
    timezone: String,
): String {
    if (nextDueAtMillis == null) return "下次执行：暂无"
    val moment = recurrenceInstantText(nextDueAtMillis, timezone) ?: return "下次执行：时区 $timezone 无法解析"
    val remaining =
        when (val days = recurrenceDaysUntil(nextDueAtMillis, timezone)) {
            null -> ""
            0L -> "（今天）"
            else -> if (days > 0) "（还有 $days 天）" else ""
        }
    return "下次执行：$moment$remaining"
}

internal fun recurrenceInstantText(
    millis: Long,
    timezone: String,
): String? {
    if (runCatching { ZoneId.of(timezone) }.isFailure) return null
    return SimpleDateFormat("yyyy年M月d日 E HH:mm", Locale.getDefault())
        .apply { timeZone = TimeZone.getTimeZone(ZoneId.of(timezone)) }
        .format(Date(millis))
}

internal fun recurrenceDaysUntil(
    millis: Long,
    timezone: String,
): Long? =
    runCatching {
        val zone = ZoneId.of(timezone)
        ChronoUnit.DAYS.between(LocalDate.now(zone), Instant.ofEpochMilli(millis).atZone(zone).toLocalDate())
    }.getOrNull()

/** 计划以「本地日期时间 + 时区」存储；缺失本地时间（DST 跳变）返回 null，交由调用方保留原输入。 */
internal fun recurrenceStartMillis(
    localDateTime: String,
    timezone: String,
): Long? {
    val local = runCatching { LocalDateTime.parse(localDateTime) }.getOrNull() ?: return null
    val zone = runCatching { ZoneId.of(timezone) }.getOrNull() ?: return null
    if (zone.rules.getValidOffsets(local).isEmpty()) return null
    return local.atZone(zone).toInstant().toEpochMilli()
}

internal fun recurrenceStartText(
    localDateTime: String,
    timezone: String,
): String? = recurrenceStartMillis(localDateTime, timezone)?.let { recurrenceInstantText(it, timezone) }

internal fun recurrenceLocalDateTime(
    millis: Long,
    timezone: String,
): String? =
    runCatching {
        Instant
            .ofEpochMilli(millis)
            .atZone(ZoneId.of(timezone))
            .toLocalDateTime()
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT))
    }.getOrNull()

internal fun recurrenceEndText(
    endAt: String,
    timezone: String,
): String? =
    runCatching { recurrenceInstantText(OffsetDateTime.parse(endAt).toInstant().toEpochMilli(), timezone) }
        .getOrNull()

internal fun recurrenceDraftSignature(draft: AccountRecurrenceDraft): List<Any?> =
    listOf(
        draft.templateId,
        draft.startLocalDateTime,
        draft.timezone,
        draft.frequency,
        draft.interval,
        draft.weekDays,
        draft.monthDays,
        draft.endAt,
        draft.maxCount,
        draft.effectiveFromSlot,
        draft.confirmTemplateRevision,
        draft.state,
    )

@Composable
internal fun RecurrenceSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}
