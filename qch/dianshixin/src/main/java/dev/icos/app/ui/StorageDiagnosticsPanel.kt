package dev.icos.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.icos.app.data.local.IntegrityResult
import dev.icos.app.data.local.StorageDiagnosticReport

@Composable
internal fun StorageDiagnosticsPanel(model: FinanceViewModel) {
    val owner = model.state.accountOwner
    var report by remember(owner) { mutableStateOf<StorageDiagnosticReport?>(null) }
    var status by remember(owner) { mutableStateOf<String?>(null) }
    var expanded by remember(owner) { mutableStateOf(false) }
    val save =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            val fixedReport = report
            if (uri != null && fixedReport != null) {
                model.exportStorageDiagnostics(fixedReport, uri) { saved ->
                    status = if (saved) "诊断结果已保存。" else "未能保存诊断结果，请检查目标文件是否可写。"
                }
            }
        }

    fun label(value: IntegrityResult): String =
        when (value) {
            IntegrityResult.OK -> "通过"
            IntegrityResult.FAILED -> "发现异常"
            IntegrityResult.UNAVAILABLE -> "未能检查"
        }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Card(
            onClick = { expanded = !expanded },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Row(
                Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AppIcon(AppSymbol.SETTINGS, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("高级：本地数据诊断", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "只读检查；导出不含账号、账单与位置",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AppIcon(if (expanded) AppSymbol.DOWN else AppSymbol.CHEVRON, if (expanded) "收起诊断" else "展开诊断")
            }
        }
        if (expanded) {
            Card(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "只读检查当前本地加密账本。诊断导出仅含应用版本、系统版本、账本模式和检查结果，不含账号、账单、备注、位置、附件、密钥或网络日志。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = !model.state.busy,
                            onClick = {
                                report = null
                                status = "正在检查，请稍候…"
                                model.runStorageDiagnostics { result ->
                                    report = result.getOrNull()
                                    status = if (result.isFailure) "检查未完成。请保留应用数据，不要卸载、清除存储或覆盖原账本。" else null
                                }
                            },
                        ) { Text("检查完整性") }
                        OutlinedButton(
                            enabled = report != null && !model.state.busy,
                            onClick = { save.launch("icos-storage-diagnostics.json") },
                        ) { Text("导出诊断") }
                        if (model.state.busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                    report?.let { result ->
                        HelpDivider()
                        Text("加密页：${label(result.cipher)}", style = MaterialTheme.typography.bodyMedium)
                        Text("数据库：${label(result.sqlite)}", style = MaterialTheme.typography.bodyMedium)
                        Text("引用：${label(result.references)}", style = MaterialTheme.typography.bodyMedium)
                        if (!result.healthy) {
                            HelpNotice(
                                "请保留现有应用数据。账号账本可在确认服务器备份有效后通过恢复流程重新同步；访客账本请先尝试导出可读取的数据。检查不会自动修复、清空或重建数据库。",
                                error = true,
                            )
                        }
                    }
                    status?.let { HelpNotice(it) }
                }
            }
        }
    }
}
