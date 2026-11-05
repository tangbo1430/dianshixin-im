package dev.icos.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.icos.app.data.local.BusinessPreferencesEntity

@Composable
internal fun RefundPreferencesPanel(
    preferences: BusinessPreferencesEntity?,
    busy: Boolean,
    error: String?,
    onChange: (Long, Boolean, Boolean) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AppIcon(AppSymbol.REFUND, tint = MaterialTheme.colorScheme.primary)
                    Column {
                        Text("退款", style = MaterialTheme.typography.titleMedium)
                        Text("用于支出支付后发生的退款", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SwitchRow(
                    "超额退款计入收入",
                    "超出原支出的退款金额按收入记账。",
                    preferences?.refundExcessAsIncome ?: false,
                    enabled = !busy && preferences != null,
                    description = "超额退款计入收入",
                ) { checked ->
                    preferences?.let { current -> onChange(current.localRevision, checked, current.showRefundsInRecordList) }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SwitchRow(
                    "在账单列表显示退款",
                    "关闭后退款仍计入账户余额，只是不在列表中重复展示。",
                    preferences?.showRefundsInRecordList ?: true,
                    enabled = !busy && preferences != null,
                    description = "在账单列表显示退款",
                ) { checked ->
                    preferences?.let { current -> onChange(current.localRevision, current.refundExcessAsIncome, checked) }
                }
            }
        }
        if (busy) CircularProgressIndicator(Modifier.padding(8.dp))
        if (error != null) Text("保存失败：$error", color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    description: String,
    onChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp, top = 8.dp, bottom = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            modifier = Modifier.semantics { contentDescription = description },
        )
    }
}
