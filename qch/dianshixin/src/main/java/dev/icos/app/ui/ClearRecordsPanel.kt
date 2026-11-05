package dev.icos.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun ClearRecordsPanel(
    state: FinanceUiState,
    model: FinanceViewModel,
    onOpenFileIo: () -> Unit,
    onOpenBackups: () -> Unit,
) {
    var confirm by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("本地账本", style = MaterialTheme.typography.titleMedium)
            state.snapshot?.let { snapshot ->
                val name = snapshot.ledgers.firstOrNull { it.id == snapshot.ledgerId }?.name ?: "当前账本"
                Text("$name · ${snapshot.records.size} 条账单")
            }
            Text(
                if (state.accountOwner == null) {
                    "游客账本仅保存在本机。导入、导出和备份操作可从此处打开。"
                } else {
                    "当前为账号账本的本地副本；同步和备份状态可从此处查看。"
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Text("数据操作", style = MaterialTheme.typography.titleSmall)
            TextButton(onClick = onOpenFileIo, modifier = Modifier.fillMaxWidth()) {
                AppIcon(AppSymbol.TRANSFER)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text("导入与导出")
                    Text("从文件导入或导出账本和账单", style = MaterialTheme.typography.bodySmall)
                }
                AppIcon(AppSymbol.CHEVRON)
            }
            TextButton(onClick = onOpenBackups, modifier = Modifier.fillMaxWidth()) {
                AppIcon(AppSymbol.REFRESH)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text("同步与备份")
                    Text("查看备份与恢复选项", style = MaterialTheme.typography.bodySmall)
                }
                AppIcon(AppSymbol.CHEVRON)
            }
            Text("清账记录", style = MaterialTheme.typography.titleMedium)
            if (state.accountOwner != null) {
                Text("清账仅影响服务器当前数据集中的活动记录；账本、账户、分类和账号身份会保留。服务端会整组拒绝含不支持关联的请求，成功后需完成全量快照屏障。")
                if (state.accountClearPending) {
                    Text("清账操作已加密保留。服务端结果或快照屏障尚未确认；请继续原操作。")
                    if (state.accountClearReauthRequired) {
                        var password by remember(state.accountClearReauthRequired) { mutableStateOf("") }
                        androidx.compose.material3.OutlinedTextField(
                            password,
                            { password = it.take(128) },
                            label = { Text("重新输入账号密码以恢复原验证") },
                            visualTransformation =
                                androidx.compose.ui.text.input
                                    .PasswordVisualTransformation(),
                            singleLine = true,
                            enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            onClick = {
                                model.setAccountClearPassword(password)
                                password = ""
                                model.resumeAccountClearReauth()
                            },
                            enabled = !state.busy && password.codePointCount(0, password.length) in 12..128,
                        ) { Text("继续原验证") }
                    } else {
                        TextButton(
                            onClick = model::retryAccountClearRecords,
                            enabled = !state.busy && !state.syncPaused,
                        ) { Text("继续同一清账操作") }
                    }
                } else {
                    TextButton(
                        onClick = model::previewAccountClearRecords,
                        enabled = !state.busy && !state.syncPaused,
                    ) { Text("预览服务器清账范围") }
                }
                state.accountClearPreview?.let { preview ->
                    Text(preview)
                    var password by remember(state.accountClearPreview) { mutableStateOf("") }
                    var confirmed by remember(state.accountClearPreview) { mutableStateOf(false) }
                    androidx.compose.material3.OutlinedTextField(
                        password,
                        { password = it.take(128) },
                        label = { Text("账号密码（重新验证）") },
                        visualTransformation =
                            androidx.compose.ui.text.input
                                .PasswordVisualTransformation(),
                        singleLine = true,
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(
                            checked = confirmed,
                            onCheckedChange = { confirmed = it },
                            enabled = !state.busy,
                        )
                        Text("我已确认清除以上账本的服务器记录")
                    }
                    Button(
                        onClick = {
                            model.setAccountClearPassword(password)
                            password = ""
                            model.confirmAccountClearRecords()
                        },
                        enabled = confirmed && password.codePointCount(0, password.length) in 12..128 && !state.busy && !state.syncPaused,
                    ) { Text("重新验证并清除") }
                    TextButton(onClick = model::cancelAccountClearPreview, enabled = !state.busy) { Text("取消预览") }
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                return@Column
            }
            Text("逐笔为普通支出和收入生成金额相反的冲正并软删除；账户、账本、计划和设置保留。有关联业务或不支持记录时整批拒绝。")
            TextButton(onClick = model::previewClearRecords, enabled = !state.busy) { Text("预览当前账本") }
            state.clearPreview?.let { preview ->
                Text("范围：${preview.ledgerName} · 共 ${preview.recordCount} 笔 · 可安全处理 ${preview.supportedCount} 笔")
                if (preview.rejectedReasons.isNotEmpty()) {
                    Text(
                        "拒绝原因：${preview.rejectedReasons.joinToString("；")}",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (preview.recordCount > 0 && preview.supportedCount == preview.recordCount && preview.rejectedReasons.isEmpty()) {
                    Button(onClick = { confirm = true }, enabled = !state.busy) { Text("继续清账") }
                }
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text("操作写入本地事务和冲正记录，不会删除数据库或直接清表。", style = MaterialTheme.typography.bodySmall)
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("确认清账？") },
            text = { Text("将为预览中的 ${state.clearPreview?.recordCount ?: 0} 笔记录逐笔冲正并软删除。此操作不能撤销；计划、账户及账本设置会保留。") },
            confirmButton = {
                Button(onClick = {
                    confirm = false
                    model.clearGuestRecords {}
                }, enabled = !state.busy) { Text("确认冲正并清账") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消") } },
        )
    }
}
