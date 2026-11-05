package dev.icos.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.icos.app.data.remote.DeviceSessionsState
import dev.icos.app.data.remote.RemoteBackup
import dev.icos.app.data.remote.SyncConflict
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

private val syncTimeFormatter = DateTimeFormatter.ofPattern("M月d日 HH:mm")

internal fun conflictCommandLabel(command: String): String =
    when (command.substringBefore('.')) {
        "record" -> "账单修改"
        "account", "account_group" -> "账户修改"
        "ledger", "category", "tag" -> "账本分类修改"
        "saving", "saving_plan", "saving_entry" -> "存钱计划修改"
        "budget" -> "预算修改"
        else -> "待同步的修改"
    }

/** 恢复提交结果未知或任务仍在查询时，只能沿用原请求重试，不能重新发起。 */
private fun restoreRetryVisible(state: FinanceUiState): Boolean =
    state.restoreSubmissionRetryAvailable ||
        (state.restoreJobId != null && state.restoreStatus?.state !in setOf("FAILED", "CANCELLED", "COMMITTED")) ||
        state.restoreProgress?.contains("检测到上次") == true ||
        state.restoreError?.contains("原操作编号") == true ||
        state.restoreError?.contains("继续查询") == true

/** 例如“刚刚 / 5分钟前 / 2小时前 / 5月18日 09:00”。 */
internal fun syncTimeLabel(millis: Long): String {
    val minutes = ((System.currentTimeMillis() - millis).coerceAtLeast(0L)) / 60_000
    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "${minutes}分钟前"
        minutes < 24 * 60 -> "${minutes / 60}小时前"
        else -> Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(syncTimeFormatter)
    }
}

/** P63 同步设置，并承载 P62 恢复确认与 P64 冲突处理两个整页确认。 */
@Composable
internal fun SyncSettingsPanel(
    state: FinanceUiState,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRefresh: () -> Unit,
    onLoadBackups: () -> Unit,
    onSelectBackup: (RemoteBackup?) -> Unit,
    onRestore: (String) -> Unit,
    onRetryRestore: () -> Unit,
    onLoadConflicts: () -> Unit,
    onOpenConflict: (UUID) -> Unit,
    onDismissConflict: () -> Unit,
    onSetEditedCommand: (String) -> Unit,
    onSetReviewKeys: (String) -> Unit,
    onReviewDependencies: () -> Unit,
    onResolveConflict: (String) -> Unit,
    pausePending: Boolean = false,
    pauseRecoveryAvailable: Boolean = false,
    deviceSessions: DeviceSessionsState? = null,
    deviceBusy: Boolean = false,
    deviceError: String? = null,
    onLoadDevices: () -> Unit = {},
    onLoadMoreDevices: () -> Unit = {},
    onReloadAccount: () -> Unit = {},
) {
    if (state.accountOwner == null) {
        Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("当前使用本机账本", style = MaterialTheme.typography.titleMedium)
                Text("本机账本可通过数据管理导出文件。登录账号后可使用服务器同步与备份。")
            }
        }
        return
    }
    val busyGate = !state.busy && !pausePending
    // 冲突操作沿用原有闸门，不因后台同步忙碌而禁用。
    val conflictGate = !state.syncPaused && !state.restoreBusy && !pausePending
    val selected = state.selectedRestoreBackup
    val (confirmed, setConfirmed) = remember(selected?.id) { mutableStateOf(false) }
    val (password, setPassword) = remember(selected?.id) { mutableStateOf("") }

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = summaryCardColor())) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AppIcon(
                        if (state.syncError == null) AppSymbol.CHECK else AppSymbol.REFRESH,
                        null,
                        Modifier.size(24.dp),
                        if (state.syncError == null) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                    )
                    Column(Modifier.weight(1f)) {
                        Text("账号同步", style = MaterialTheme.typography.titleMedium)
                        Text(
                            state.syncLastSuccessAt?.let { "已同步 ${syncTimeLabel(it)}" } ?: "尚无成功同步记录",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                Text(
                    "本机待确认 ${state.syncPendingCount} 条 · 待处理冲突 ${state.conflicts.size} 项",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    when {
                        pausePending -> "正在停止账号网络同步…"
                        pauseRecoveryAvailable && !state.syncPaused -> "暂停状态保存失败；账号请求仍保持关闭。请重试恢复同步。"
                        state.syncPaused -> "网络同步已暂停。SERVER 计划继续按服务器规则运行；本地操作保留在本机队列。"
                        else -> "网络同步已开启。服务器计划由服务器独立运行。"
                    },
                    color =
                        if (pauseRecoveryAvailable &&
                            !state.syncPaused
                        ) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.syncPaused || pauseRecoveryAvailable) {
                        Button(
                            onClick = onResume,
                            enabled = busyGate,
                            colors = primaryButtonColors(),
                        ) {
                            Text(
                                if (pauseRecoveryAvailable && !state.syncPaused) {
                                    "重试恢复同步"
                                } else {
                                    "继续并立即同步"
                                },
                            )
                        }
                    } else {
                        OutlinedButton(onClick = onPause, enabled = !pausePending) {
                            Text(if (pausePending) "正在暂停…" else "暂停网络同步")
                        }
                    }
                    OutlinedButton(onClick = onRefresh, enabled = busyGate && !state.syncPaused) {
                        AppIcon(AppSymbol.REFRESH, null, Modifier.padding(end = 6.dp).size(18.dp))
                        Text("立即同步")
                    }
                }
                if (state.syncPausedRequestUncertain) {
                    Text(
                        "暂停时已有请求在途，其服务器结果可能尚未确定；继续后会按原操作标识核对。",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (state.syncEpochArchived) {
                    Text("服务器数据已恢复；旧的本地数据已封存，当前数据已重新同步。", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        if (state.syncRecoveryRequired) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("账号数据需要恢复", style = MaterialTheme.typography.titleSmall)
                    Text("继续恢复可能需要从服务器下载账号数据，请主动重新获取。", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(
                        onClick = onReloadAccount,
                        enabled = busyGate && !state.syncPaused && !state.restoreBusy && !state.accountClearPending,
                    ) { Text("重新获取账号数据") }
                }
            }
        }

        state.bootstrapProgress?.let { progress ->
            Card(Modifier.fillMaxWidth()) {
                Text(progress, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }
        state.bootstrapError?.let { failure ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("账本同步未完成", style = MaterialTheme.typography.titleSmall)
                    Text(failure, color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = onRefresh, enabled = busyGate && !state.syncPaused) { Text("重试同步") }
                }
            }
        }
        if (state.accountMutationRetryOnly) {
            Card(Modifier.fillMaxWidth()) {
                Text(
                    "存在被服务器拒绝或冲突的操作；请按提示修正后重新保存。",
                    Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        state.syncError?.let { error ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("同步未完成", style = MaterialTheme.typography.titleSmall)
                    Text(error.message, color = MaterialTheme.colorScheme.error)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onRefresh, enabled = busyGate && !state.syncPaused) { Text("重试同步") }
                    }
                    Text(
                        "失败的本机修改不会删除；修正后可重新提交，冲突需逐项决议。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("同步状态", style = MaterialTheme.typography.titleSmall)
                SyncStatusRow("网络同步", if (state.syncPaused) "已暂停" else "已开启")
                SyncStatusRow("本机待确认修改", "${state.syncPendingCount} 条")
                SyncStatusRow(
                    "最近成功",
                    state.syncLastSuccessAt?.let { syncTimeLabel(it) } ?: "尚无记录",
                )
                SyncStatusRow("待处理冲突", "${state.conflicts.size} 项")
                Text(
                    "暂停只停止本应用的账号同步，不会暂停服务器计划；未确认的在途请求可能已被服务器接收，继续同步后会沿用原操作标识核对。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("设备", style = MaterialTheme.typography.titleSmall)
                Button(onClick = onLoadDevices, enabled = !deviceBusy) {
                    Text(
                        if (deviceBusy) {
                            "正在载入设备"
                        } else if (deviceSessions == null) {
                            "载入设备"
                        } else {
                            "刷新设备"
                        },
                    )
                }
                deviceError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (deviceSessions == null) {
                    Text(
                        "载入当前账号的服务器会话后，可查看设备名称与最近活动时间。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (deviceSessions.items.isEmpty()) {
                    Text("当前账号没有活动的服务器会话。", style = MaterialTheme.typography.bodySmall)
                } else {
                    deviceSessions.items.forEach { device ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            AppIcon(AppSymbol.ACCOUNT, null, Modifier.size(20.dp))
                            Column(Modifier.weight(1f)) {
                                Text(if (device.current) "本机 · ${device.deviceName}" else device.deviceName)
                                Text(
                                    "${if (device.current) "当前设备 · " else ""}最近活动 ${syncTimeLabel(device.lastSeenAtMillis)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    if (deviceSessions.hasMore) {
                        OutlinedButton(onClick = onLoadMoreDevices, enabled = !deviceBusy) {
                            Text(if (deviceBusy) "正在载入设备" else "载入更多设备")
                        }
                    }
                }
                Text(
                    "恢复后所有已登录设备都需要重新同步。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("待处理冲突", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Text(
                        "${state.conflicts.size} 项",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "操作冲突由用户逐项决议；不会自动覆盖服务器数据。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = onLoadConflicts, enabled = conflictGate && !state.conflictsBusy) {
                    if (state.conflictsBusy) Text("正在载入冲突") else Text("载入待处理冲突（${state.conflicts.size}）")
                }
                state.conflictError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                state.conflicts.forEach { conflict ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = conflictGate && !state.conflictsBusy) { onOpenConflict(conflict.conflictId) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        AppIcon(AppSymbol.EDIT, null, Modifier.size(20.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                        Column(Modifier.weight(1f)) {
                            Text(conflictCommandLabel(conflict.localCommand.optString("commandType")))
                            Text(
                                "${backupTimeLabel(conflict.createdAt)} · 服务器版本 ${conflict.revision}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        AppIcon(AppSymbol.CHEVRON, null, Modifier.size(16.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("服务器备份与恢复", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = onLoadBackups, enabled = !state.backupsBusy && !state.restoreBusy) {
                        Text(if (state.backupsBusy) "正在载入" else "载入备份")
                    }
                }
                if (state.backups.isEmpty() && !state.backupsBusy) {
                    Text(
                        "尚未载入服务器备份。备份与恢复只影响服务器账号数据。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                state.backups.forEach { backup ->
                    val schemaSupported =
                        backup.schemaVersion?.let { schema ->
                            runCatching {
                                dev.icos.app.data.remote.SnapshotProtocol
                                    .tablesFor(schema)
                            }.isSuccess
                        } == true
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !state.restoreBusy && backup.state == "READY") { onSelectBackup(backup) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        AppIcon(
                            AppSymbol.DOWNLOAD,
                            "恢复",
                            Modifier.size(20.dp),
                            if (backup.state == "READY" &&
                                schemaSupported
                            ) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        Column(Modifier.weight(1f)) {
                            Text("${backupKindLabel(backup.kind)} · ${backupTimeLabel(backup.createdAt)}")
                            Text(
                                "状态 ${backupStateLabel(backup.state)} · 快照版本 ${backup.schemaVersion ?: "未知"}" +
                                    if (schemaSupported) " · 本版本可恢复" else " · 本版本不支持，恢复会被拒绝",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (schemaSupported) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                            )
                            if (backup.domains.isNotEmpty()) {
                                Text(
                                    "业务域 ${backup.domains.joinToString()}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (backup.state == "READY" && schemaSupported) {
                            Text("恢复", color = MaterialTheme.colorScheme.primary)
                            AppIcon(AppSymbol.CHEVRON, null, Modifier.size(16.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                state.restoreStatus?.let {
                    Text(
                        "恢复任务 ${it.state} · ${it.progress}%${it.errorCode?.let { code -> " · $code" } ?: ""}",
                    )
                }
                state.restoreProgress?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                state.restoreError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (restoreRetryVisible(state)) {
                    OutlinedButton(onClick = onRetryRestore, enabled = !state.restoreBusy && !state.syncPaused) {
                        Text(if (state.restoreJobId == null) "重试同一恢复请求" else "继续查询恢复任务")
                    }
                }
            }
        }
    }

    if (selected != null) {
        RestoreConfirmPage(
            backup = selected,
            pendingLocalChanges = state.syncPendingCount,
            restoreBusy = state.restoreBusy,
            syncPaused = state.syncPaused,
            restoreError = state.restoreError,
            restoreProgress = state.restoreProgress,
            restoreJobId = state.restoreJobId,
            retryVisible = restoreRetryVisible(state),
            onRetryRestore = onRetryRestore,
            password = password,
            onPasswordChange = setPassword,
            confirmed = confirmed,
            onConfirmedChange = setConfirmed,
            onRestore = {
                onRestore(password)
                setPassword("")
            },
            onCancel = {
                setPassword("")
                setConfirmed(false)
                onSelectBackup(null)
            },
        )
    }

    state.conflictDetail?.let { conflict ->
        ConflictResolutionPage(
            conflict = conflict,
            state = state,
            conflictError = state.conflictError,
            gate = conflictGate,
            onDismiss = onDismissConflict,
            onSetEditedCommand = onSetEditedCommand,
            onSetReviewKeys = onSetReviewKeys,
            onReviewDependencies = onReviewDependencies,
            onResolveConflict = onResolveConflict,
        )
    }
}

@Composable
private fun SyncStatusRow(
    label: String,
    value: String,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** P62 服务器恢复确认：整页、可滚动，保留二次验证、影响范围确认与全部恢复屏障。 */
@Composable
private fun RestoreConfirmPage(
    backup: RemoteBackup,
    pendingLocalChanges: Int,
    restoreBusy: Boolean,
    syncPaused: Boolean,
    restoreError: String?,
    restoreProgress: String?,
    restoreJobId: UUID?,
    retryVisible: Boolean,
    onRetryRestore: () -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    confirmed: Boolean,
    onConfirmedChange: (Boolean) -> Unit,
    onRestore: () -> Unit,
    onCancel: () -> Unit,
) {
    EditorPage(
        onDismissRequest = { if (!restoreBusy) onCancel() },
        title = { Text("恢复备份") },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "${backupKindLabel(backup.kind)} · ${backupTimeLabel(backup.createdAt)}",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        SyncStatusRow("状态", backupStateLabel(backup.state))
                        SyncStatusRow("快照版本", backup.schemaVersion?.toString() ?: "未知")
                        if (backup.domains.isNotEmpty()) SyncStatusRow("业务域", backup.domains.joinToString())
                    }
                }
                Text(
                    "将覆盖整个账号的财务数据",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text("所有已登录设备都需要重新同步。", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "恢复会用所选备份替换账号的账本、账户及关联财务记录。服务器会完整校验备份后再切换，原数据会封存保留；其他设备上的旧数据需要重新同步。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    RestoreChecklistRow("本机未上传更改（$pendingLocalChanges 笔）会封存到恢复记录，不会自动重放")
                    RestoreChecklistRow("恢复前自动创建保护备份，失败时当前数据不变")
                    RestoreChecklistRow("网络中断可继续查询恢复任务，不会重复执行")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = confirmed, onCheckedChange = onConfirmedChange, enabled = !restoreBusy)
                    Text("我确认覆盖整个账号的财务数据，并了解所有已登录设备均会受影响", Modifier.weight(1f))
                }
                OutlinedTextField(
                    value = password,
                    onValueChange = onPasswordChange,
                    enabled = !restoreBusy,
                    label = { Text("账号密码，用于重新验证") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                restoreProgress?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                restoreError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (retryVisible) {
                    Text(
                        "提交结果尚未确认或任务仍在运行：只能沿用原操作编号继续核对，不能重新发起新的恢复。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = onRetryRestore, enabled = !restoreBusy && !syncPaused) {
                        Text(if (restoreJobId == null) "重试同一恢复请求" else "继续查询恢复任务")
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onRestore,
                enabled = confirmed && password.length >= 12 && !restoreBusy && !syncPaused && !retryVisible,
                colors = primaryButtonColors(),
            ) { Text(if (restoreBusy) "正在提交" else "重新验证并恢复") }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onCancel,
                enabled = !restoreBusy,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text("取消") }
        },
    )
}

@Composable
private fun RestoreChecklistRow(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AppIcon(AppSymbol.CHECK, null, Modifier.size(16.dp), MaterialTheme.colorScheme.tertiary)
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

/** P64 冲突处理：整页、可滚动，保留高级诊断、依赖复核与手动合并。 */
@Composable
private fun ConflictResolutionPage(
    conflict: SyncConflict,
    state: FinanceUiState,
    conflictError: String?,
    gate: Boolean,
    onDismiss: () -> Unit,
    onSetEditedCommand: (String) -> Unit,
    onSetReviewKeys: (String) -> Unit,
    onReviewDependencies: () -> Unit,
    onResolveConflict: (String) -> Unit,
) {
    val requiresReconfirmation =
        SyncControlPolicy.requiresMetadataReconfirmation(
            conflict.localCommand.optString("aggregateType"),
            conflict.localCommand.optString("commandType"),
        )
    var choice by remember(conflict.conflictId) { mutableStateOf<String?>(null) }
    var diagnosticsOpen by remember(conflict.conflictId) { mutableStateOf(false) }
    val resolutionGate = gate && !state.conflictResolutionBusy

    EditorPage(
        onDismissRequest = { if (!state.conflictResolutionBusy) onDismiss() },
        title = { Text("处理冲突") },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            conflictCommandLabel(conflict.localCommand.optString("commandType")),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        SyncStatusRow("本机修改时间", backupTimeLabel(conflict.createdAt))
                        SyncStatusRow("服务器版本", conflict.revision.toString())
                    }
                }
                Text(
                    "服务器上的相关数据已有更新，这次本机修改尚未确认。请选择保留哪一方。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "服务器只返回本机操作与该聚合的版本号，没有返回可直接比较的云端字段值，因此这里不展示逐字段差异；决议仍会按完整依赖与版本重新校验。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ConflictChoiceRow(
                    selected = choice == "LOCAL",
                    title = "保留本机",
                    detail =
                        if (requiresReconfirmation) {
                            "此更改需重新确认：先采用服务器版本，再返回预算或管理页面重新操作。"
                        } else {
                            "按当前已审阅的服务器版本，以新操作重新提交本次修改。"
                        },
                    enabled = resolutionGate,
                    onSelect = { choice = "LOCAL" },
                )
                ConflictChoiceRow(
                    selected = choice == "SERVER",
                    title = "使用云端",
                    detail = "放弃这次尚未同步的本机修改，采用服务器当前数据。",
                    enabled = resolutionGate,
                    onSelect = { choice = "SERVER" },
                )
                ConflictChoiceRow(
                    selected = choice == "EDITED",
                    title = "编辑合并",
                    detail = "在高级诊断中提交编辑后的完整命令组，必须保留全部原依赖。",
                    enabled = resolutionGate,
                    onSelect = { choice = "EDITED" },
                )
                if (choice == "EDITED") {
                    OutlinedTextField(
                        value = state.conflictReviewKeys,
                        onValueChange = onSetReviewKeys,
                        enabled = !state.conflictsBusy && !state.conflictResolutionBusy,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("新增依赖键，逗号分隔，最多100项") },
                        minLines = 1,
                        maxLines = 3,
                    )
                    OutlinedButton(
                        onClick = onReviewDependencies,
                        enabled = state.conflictReviewKeys.isNotBlank() && resolutionGate && !state.conflictsBusy,
                    ) { Text("重新读取依赖版本") }
                    Text(
                        "编辑后的完整 typed group JSON。必须保留所有原依赖；baseVersions 必须与当前已审阅版本完全一致。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = state.conflictEditedGroupJson,
                        onValueChange = onSetEditedCommand,
                        enabled = !state.conflictResolutionBusy,
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 4,
                        maxLines = 8,
                    )
                }
                TextButton(onClick = { diagnosticsOpen = !diagnosticsOpen }) {
                    Text(if (diagnosticsOpen) "收起高级诊断" else "高级诊断")
                }
                if (diagnosticsOpen) {
                    Text("原操作 ${conflict.opId}", style = MaterialTheme.typography.bodySmall)
                    Text("已审阅版本：${conflict.reviewedServerVersions}", style = MaterialTheme.typography.bodySmall)
                    Text("本地命令：${conflict.localCommand}", style = MaterialTheme.typography.bodySmall)
                }
                state.conflictResolutionResult?.let { result ->
                    Text(
                        when (result.state) {
                            "RESOLVED_SERVER" -> "已采用服务器版本；原本地预测已撤回。"
                            "RESOLVED_LOCAL" -> "已按新操作 ID 采用本地版本；正在同步服务器规范数据。"
                            else -> "已提交编辑版本；正在同步服务器规范数据。"
                        },
                    )
                }
                conflictError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(
                onClick = { choice?.let(onResolveConflict) },
                enabled = resolutionGate && choice != null && (choice != "EDITED" || state.conflictEditedGroupJson.isNotBlank()),
                colors = primaryButtonColors(),
            ) {
                Text(
                    when {
                        state.conflictResolutionBusy -> "正在提交决议"
                        choice == "EDITED" && requiresReconfirmation -> "重试原编辑决议（如有）"
                        choice == "LOCAL" && requiresReconfirmation -> "重试原本地决议（如有）"
                        else -> "确认选择"
                    },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !state.conflictResolutionBusy) { Text("稍后处理") }
        },
    )
}

@Composable
private fun ConflictChoiceRow(
    selected: Boolean,
    title: String,
    detail: String,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = onSelect, enabled = enabled)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
