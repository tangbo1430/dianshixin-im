package dev.icos.app.ui

import android.app.TimePickerDialog
import android.icu.util.Calendar
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@Composable
internal fun ReminderSettingsPanel(
    model: FinanceViewModel,
    permissionStatus: () -> String,
    requestPermission: () -> Unit,
) {
    val context = LocalContext.current
    val value = model.reminderPreferences
    val permission = permissionStatus()
    val zone = java.time.ZoneId.systemDefault()
    val rawOffset = zone.rules.getOffset(java.time.Instant.now()).id
    val zoneOffset = if (rawOffset == "Z") "UTC" else "UTC$rawOffset"
    val reminderTime = "%02d:%02d".format(value.hour, value.minute)

    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("起始规则", style = MaterialTheme.typography.titleMedium)
                Text("周/月/年统计周期起点", style = MaterialTheme.typography.bodySmall)
                Text("每周从")
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(Calendar.MONDAY to "周一", Calendar.SUNDAY to "周日").forEach { (day, label) ->
                        FilterChip(
                            value.weekStart == day,
                            onClick = { model.updateReminderPreferences(value.copy(weekStart = day)) },
                            label = { Text(label) },
                        )
                    }
                }
                Text("每月从第几日开始")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(1, 5, 10, 15, 20, 25).forEach { day ->
                        FilterChip(value.monthStart == day, onClick = {
                            model.updateReminderPreferences(value.copy(monthStart = day))
                        }, label = { Text("${day}日") })
                    }
                }
                Text("每年从")
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(Calendar.JANUARY to "1月", Calendar.APRIL to "4月", Calendar.JULY to "7月", Calendar.OCTOBER to "10月").forEach { (month, label) ->
                        FilterChip(value.yearStartMonth == month, onClick = {
                            model.updateReminderPreferences(value.copy(yearStartMonth = month))
                        }, label = { Text(label) })
                    }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("提醒设置", style = MaterialTheme.typography.titleMedium)
                Row(
                    Modifier.fillMaxWidth().toggleable(
                        value = value.dailyEnabled,
                        role = Role.Switch,
                        onValueChange = { model.updateReminderPreferences(value.copy(dailyEnabled = it)) },
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("每日统计提醒")
                        Text(
                            "每天 $reminderTime",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = value.dailyEnabled, onCheckedChange = null)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("提醒时间", Modifier.weight(1f))
                    TextButton(onClick = {
                        TimePickerDialog(
                            context,
                            { _, h, m -> model.updateReminderPreferences(value.copy(hour = h, minute = m)) },
                            value.hour,
                            value.minute,
                            true,
                        ).show()
                    }) { Text(reminderTime) }
                }
                Row(
                    Modifier.fillMaxWidth().toggleable(
                        value = value.creditEnabled,
                        role = Role.Switch,
                        onValueChange = { model.updateReminderPreferences(value.copy(creditEnabled = it)) },
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("信用出账与还款提醒")
                        Text("共用同一提醒设置", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = value.creditEnabled, onCheckedChange = null)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("提前天数", Modifier.weight(1f))
                    FilterChip(
                        value.creditLeadDays == 3,
                        onClick = { model.updateReminderPreferences(value.copy(creditLeadDays = 3)) },
                        label = { Text("3天") },
                    )
                    FilterChip(
                        value.creditLeadDays == 7,
                        onClick = { model.updateReminderPreferences(value.copy(creditLeadDays = 7)) },
                        label = { Text("7天") },
                    )
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("通知设置", style = MaterialTheme.typography.titleMedium)
                Text("通知权限：$permission", style = MaterialTheme.typography.bodySmall)
                if (permission == "已授权" || permission == "系统不需要通知授权") {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppIcon(AppSymbol.CHECK, null, Modifier.size(20.dp), MaterialTheme.colorScheme.tertiary)
                        Text(if (permission == "已授权") "已授权" else "当前系统无需通知授权")
                    }
                } else {
                    TextButton(onClick = requestPermission) { Text("请求通知权限") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("设备时区", Modifier.weight(1f))
                    Text("${zone.id}（$zoneOffset）", style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    "提醒使用当前设备时区和系统本地闹钟；日期变更与夏令时按当地日历重新计算。信用提醒只读取已同步的有效信用资料。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
