package dev.icos.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

private val backupRetentionRange = 1..365
private val pendingBackupStates = setOf("QUEUED", "CAPTURING", "VERIFYING")
private val backupTimeFormatter = DateTimeFormatter.ofPattern("M月d日 HH:mm")

internal fun backupKindLabel(kind: String?): String =
    when (kind) {
        "MANUAL" -> "手动备份"
        "DAILY" -> "每日自动备份"
        "RESTORE_PROTECTION" -> "恢复前保护备份"
        else -> "服务器快照"
    }

internal fun backupStateLabel(state: String): String =
    when (state) {
        "QUEUED" -> "排队中"
        "CAPTURING" -> "捕获中"
        "VERIFYING" -> "校验中"
        "READY" -> "已完成"
        "FAILED" -> "失败"
        "DELETING" -> "删除中"
        "DELETED" -> "已删除"
        else -> state
    }

internal fun backupTimeLabel(raw: String): String =
    runCatching {
        OffsetDateTime.parse(raw).atZoneSameInstant(ZoneId.systemDefault()).format(backupTimeFormatter)
    }.getOrElse { raw }

internal fun backupSizeLabel(bytes: Long?): String? =
    bytes?.takeIf { it >= 0 }?.let {
        if (it >= 1024L * 1024L) {
            String.format(Locale.getDefault(), "%.1f MB", it / (1024.0 * 1024.0))
        } else {
            String.format(Locale.getDefault(), "%.0f KB", it / 1024.0)
        }
    }

/** P61 服务器备份。面板本体位于设置页滚动容器内，不再嵌套滚动。 */
@Composable
internal fun BackupSettingsPanel(
    model: BackupSettingsViewModel,
    userId: UUID?,
) {
    if (userId == null) return
    val state = model.state
    var policyEnabled by remember { mutableStateOf(false) }
    var dailyTime by remember { mutableStateOf("03:00") }
    var zoneId by remember {
        mutableStateOf(
            java.util.TimeZone
                .getDefault()
                .id,
        )
    }
    var dailyRetention by remember { mutableStateOf("30") }
    var manualRetention by remember { mutableStateOf("30") }
    var deleteTarget by remember { mutableStateOf<UUID?>(null) }
    var deletePassword by remember { mutableStateOf("") }
    var policyDiagnostics by remember { mutableStateOf(false) }
    var detailDiagnostics by remember { mutableStateOf(false) }
    LaunchedEffect(state.policy?.version) {
        state.policy?.let {
            policyEnabled = it.enabled
            dailyTime = it.dailyTime
            zoneId = it.zoneId
            dailyRetention = it.dailyRetention.toString()
            manualRetention = it.manualRetention.toString()
        }
    }

    val pendingCount = state.rows.count { it.state in pendingBackupStates }
    val lastReady = state.rows.firstOrNull { it.state == "READY" }

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = summaryCardColor())) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AppIcon(AppSymbol.UPLOAD, "服务器备份")
                    Column(Modifier.weight(1f)) {
                        Text("服务器备份", style = MaterialTheme.typography.titleMedium)
                        Text(
                            lastReady?.let { "上次备份 ${backupTimeLabel(it.createdAt)}" } ?: "尚无已完成的服务器备份",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                Text(
                    "待完成请求 $pendingCount 项 · 快照只包含已上传到服务器的数据",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = model::createManualBackup, enabled = !state.busy, colors = primaryButtonColors()) {
                        AppIcon(AppSymbol.ADD)
                        Text("创建手动备份")
                    }
                    OutlinedButton(onClick = model::refresh, enabled = !state.busy) {
                        AppIcon(AppSymbol.REFRESH, "刷新备份", Modifier.padding(end = 6.dp).size(18.dp))
                        Text("刷新")
                    }
                    if (state.busy) CircularProgressIndicator(Modifier.padding(start = 4.dp).size(20.dp))
                }
                Text(
                    "受保护快照不会被删除；其他离线设备的未上传数据不在本快照内。本机待传数量见下方「同步设置」。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        state.error?.let { message ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(message, color = MaterialTheme.colorScheme.error)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.retryAvailable) {
                            OutlinedButton(onClick = model::retryOriginal, enabled = !state.busy) { Text("沿用原请求重试") }
                        }
                        TextButton(onClick = model::dismissError) { Text("关闭") }
                    }
                }
            }
        }
        state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }

        state.policy?.let { policy ->
            val daily = dailyRetention.toIntOrNull()?.takeIf { it in backupRetentionRange }
            val manual = manualRetention.toIntOrNull()?.takeIf { it in backupRetentionRange }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("自动备份策略", style = MaterialTheme.typography.titleSmall)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("每日自动备份")
                            Text(
                                "每日 ${policy.dailyTime} · ${policy.zoneId}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(policyEnabled, { policyEnabled = it }, enabled = !state.busy)
                    }
                    OutlinedTextField(
                        dailyTime,
                        { dailyTime = it },
                        label = { Text("每日时间 HH:mm") },
                        enabled = !state.busy,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        zoneId,
                        { zoneId = it },
                        label = { Text("时区 ID") },
                        enabled = !state.busy,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            dailyRetention,
                            { dailyRetention = it },
                            label = { Text("每日保留天数") },
                            enabled = !state.busy,
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            isError = daily == null,
                            supportingText = { if (daily == null) Text("请输入 1–365 的整数") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                        OutlinedTextField(
                            manualRetention,
                            { manualRetention = it },
                            label = { Text("手动保留天数") },
                            enabled = !state.busy,
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            isError = manual == null,
                            supportingText = { if (manual == null) Text("请输入 1–365 的整数") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                    }
                    Text(
                        "服务器按保留天数清理过期快照，并拒绝删除最后一份有效备份与恢复占用的快照。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                if (daily != null && manual != null) {
                                    model.updatePolicy(policyEnabled, dailyTime, zoneId, daily, manual)
                                }
                            },
                            enabled = !state.busy && daily != null && manual != null,
                            colors = primaryButtonColors(),
                        ) { Text("保存本人策略") }
                        TextButton(onClick = { policyDiagnostics = !policyDiagnostics }) {
                            Text(if (policyDiagnostics) "收起高级诊断" else "高级诊断")
                        }
                    }
                    if (policyDiagnostics) {
                        Text(
                            "策略版本 ${policy.version} · 上次排期 ${policy.lastScheduledLocalDate ?: "暂无"}\n" +
                                "最近自动备份 ${policy.lastAutoCreatedAt ?: "暂无"}\n" +
                                "最近复用 ${policy.lastAutoReusedAt ?: "无"} · 复用快照 ${policy.lastAutoReusedSnapshotId ?: "无"}" +
                                if (policy.lastAutoReused == true) " · 检查无变化" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("最近备份", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Text(
                        "共 ${state.rows.size} 份",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.rows.isEmpty()) {
                    Text(
                        if (state.busy) "正在读取服务器备份…" else "服务器上还没有备份。创建手动备份或等待每日自动备份。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                state.rows.forEach { item ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !state.busy) { model.select(item.id) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        AppIcon(
                            if (item.state == "READY") AppSymbol.CHECK else AppSymbol.UPLOAD,
                            null,
                            Modifier.size(20.dp),
                            if (item.state == "FAILED") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(backupKindLabel(item.kind), style = MaterialTheme.typography.bodyMedium)
                                if (item.protectedUntil != null) AppIcon(AppSymbol.LOCK, "受保护，不会删除", Modifier.size(14.dp))
                            }
                            Text(
                                "${backupTimeLabel(item.createdAt)} · ${backupSizeLabel(item.storedBytes) ?: "大小未提供"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            backupStateLabel(item.state),
                            style = MaterialTheme.typography.bodySmall,
                            color =
                                when (item.state) {
                                    "FAILED" -> MaterialTheme.colorScheme.error
                                    "READY" -> MaterialTheme.colorScheme.tertiary
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                        )
                        AppIcon(AppSymbol.CHEVRON, null, Modifier.size(16.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (state.nextBefore != null) {
                    OutlinedButton(onClick = model::loadMore, enabled = !state.busy) { Text("加载更多") }
                }
            }
        }

        state.selected?.let { backup ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("备份详情", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = { model.select(backup.id) }, enabled = !state.busy) { Text("刷新详情") }
                    }
                    Text(
                        "${backupKindLabel(backup.kind)} · ${backupTimeLabel(backup.createdAt)}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "状态 ${backupStateLabel(backup.state)} · 大小 ${backupSizeLabel(backup.storedBytes) ?: "未提供"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    backup.protectedUntil?.let { Text("保护至 $it，期间不会被清理", style = MaterialTheme.typography.bodySmall) }
                    backup.deleteAfter?.let { Text("计划清理时间 $it", style = MaterialTheme.typography.bodySmall) }
                    backup.failureCode?.let {
                        Text(
                            "失败原因 $it",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Text(
                        "恢复必须使用下方「同步设置」的恢复确认，覆盖整个账号并影响所有已登录设备。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { detailDiagnostics = !detailDiagnostics }) {
                            Text(if (detailDiagnostics) "收起高级诊断" else "高级诊断")
                        }
                        // 删除只作为详情内的次要操作，避免列表误触。
                        if (backup.kind == "MANUAL" && backup.state == "READY" && backup.protectedUntil == null) {
                            TextButton(onClick = { deleteTarget = backup.id }, enabled = !state.busy) { Text("删除备份…") }
                        }
                    }
                    if (detailDiagnostics) {
                        Text(
                            "ID ${backup.id} · 数据集 ${backup.epoch}\n" +
                                "捕获水位 ${backup.capturedSeq ?: "未提供"} · schema ${backup.schemaVersion ?: "未提供"}\n" +
                                "存储 ${backup.storedBytes ?: "未提供"} bytes · 明文 ${backup.plainBytes ?: "未提供"} bytes",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
    if (deleteTarget != null) {
        AlertDialog(
            onDismissRequest = {
                if (!state.busy) {
                    deleteTarget = null
                    deletePassword = ""
                }
            },
            title = { Text("确认删除此备份") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("服务器将检查保护状态、恢复任务与最后有效备份约束。删除会先进入 24 小时延迟清理。请重新输入账号密码授权此备份删除。")
                    OutlinedTextField(
                        deletePassword,
                        { deletePassword = it },
                        label = { Text("账号密码") },
                        enabled = !state.busy,
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val id = deleteTarget ?: return@TextButton
                    model.deleteBackup(id, deletePassword)
                    deletePassword = ""
                    deleteTarget = null
                }, enabled = !state.busy && deletePassword.isNotEmpty()) { Text("重新验证并删除") }
            },
            dismissButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    deletePassword = ""
                }, enabled = !state.busy) { Text("取消") }
            },
        )
    }
}
