package dev.icos.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.YearMonth

@Composable
internal fun LedgerMonthPicker(
    selected: YearMonth,
    onDismiss: () -> Unit,
    onSelect: (YearMonth) -> Unit,
) {
    var year by remember(selected) { mutableIntStateOf(selected.year) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择月份") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    IconButton(onClick = { year-- }, enabled = year > 1900) { AppIcon(AppSymbol.BACK, "上一年") }
                    Text("$year 年", style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = { year++ }, enabled = year < 9998) { AppIcon(AppSymbol.CHEVRON, "下一年") }
                }
                (0..3).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        (1..3).forEach { column ->
                            val month = row * 3 + column
                            FilterChip(
                                selected.year == year && selected.monthValue == month,
                                onClick = { onSelect(YearMonth.of(year, month)) },
                                label = { Text("${month}月") },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSelect(YearMonth.now()) }) { Text("回到本月") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
internal fun SavingDateField(
    label: String,
    value: String,
    enabled: Boolean = true,
    optional: Boolean = false,
    onChange: (String) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.OutlinedButton(onClick = {
            val date = runCatching { java.time.LocalDate.parse(value) }.getOrDefault(java.time.LocalDate.now())
            android.app
                .DatePickerDialog(context, { _, year, month, day ->
                    onChange(
                        java.time.LocalDate
                            .of(year, month + 1, day)
                            .toString(),
                    )
                }, date.year, date.monthValue - 1, date.dayOfMonth)
                .show()
        }, enabled = enabled, modifier = Modifier.weight(1f)) {
            Column(horizontalAlignment = Alignment.Start) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(AppSymbol.CALENDAR)
                    Text(label, style = MaterialTheme.typography.bodySmall)
                }
                Text(value.ifBlank { if (optional) "未设置" else "请选择" }, style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (optional &&
            value.isNotEmpty()
        ) {
            TextButton(
                onClick = {
                    onChange("")
                },
                enabled = enabled,
                modifier =
                    Modifier.widthIn(
                        min = 48.dp,
                    ),
                contentPadding = PaddingValues(horizontal = 4.dp),
            ) { Text("清除") }
        }
    }
}

@Composable
internal fun rememberEditorDismiss(
    changed: Boolean,
    enabled: Boolean,
    onDismiss: () -> Unit,
): () -> Unit {
    var confirming by remember { mutableStateOf(false) }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("放弃未保存的修改？") },
            text = { Text("离开后，本次输入不会保存。可以继续编辑并保存。") },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    onDismiss()
                }, enabled = enabled) { Text("放弃修改") }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("继续编辑") } },
        )
    }
    return {
        if (enabled) {
            if (changed) confirming = true else onDismiss()
        }
    }
}
