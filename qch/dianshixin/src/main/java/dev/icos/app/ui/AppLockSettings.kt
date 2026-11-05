package dev.icos.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
internal fun AppLockSettingsPanel(lock: AppLockViewModel) {
    val state = lock.state
    var dialog by remember { mutableStateOf<String?>(null) }
    var oldPin by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    val clearPins = {
        oldPin = ""
        newPin = ""
        confirmPin = ""
    }
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = summaryCardColor())) {
            Row(
                Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                AuthIcon(
                    if (state.configured) AuthSymbol.SHIELD_LOCK else AuthSymbol.SHIELD_CHECK,
                    modifier = Modifier.size(40.dp),
                    tint = if (state.configured) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        if (state.configured) "数字锁已开启" else "数字锁未开启",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        if (state.configured) "已保护你的账本数据安全" else "开启后启动应用需要验证数字密码",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column {
                SettingRow(AuthSymbol.SHIELD_LOCK, if (state.configured) "修改数字密码" else "创建数字密码", "6 位数字密码用于解锁应用", !state.busy) {
                    lock.requestSetup()
                    clearPins()
                    dialog = if (state.configured) "modify" else "create"
                }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                SwitchRow(
                    AuthSymbol.FINGERPRINT,
                    "生物识别解锁",
                    "支持指纹或面容解锁",
                    state.biometricEnabled,
                    !state.busy && state.configured,
                ) {
                    if (it) {
                        dialog = "biometric"
                    } else {
                        lock.setBiometric(false) {}
                    }
                }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                SwitchRow(AuthSymbol.SHIELD_CHECK, "隐藏敏感信息", "在最近任务中隐藏账本内容", state.privacyScreen, true) {
                    lock.setPrivacyScreen(it)
                }
            }
        }

        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("自动锁定时间", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("无操作一段时间后自动锁定", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppLockViewModel.TIMEOUTS.forEach { minutes ->
                        FilterChip(
                            selected = state.timeoutMinutes == minutes,
                            onClick = { lock.setTimeout(minutes) },
                            enabled = !state.busy,
                            label = { Text(if (minutes == 0) "立即" else "$minutes 分钟") },
                        )
                    }
                }
            }
        }

        if (state.configured) {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = summaryCardColor())) {
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = !state.busy) { lock.lock() }.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    AuthIcon(AuthSymbol.SHIELD_LOCK, modifier = Modifier.size(28.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("立即锁定", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text("手动锁定应用，保护隐私", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    AppIcon(AppSymbol.CHEVRON)
                }
            }
        }

        if (state.configured) {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !state.busy) {
                            lock.requestSetup()
                            clearPins()
                            dialog = "disable"
                        }.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Box(
                        Modifier.size(36.dp),
                        contentAlignment = Alignment.Center,
                    ) { AppIcon(AppSymbol.CLOSE, tint = MaterialTheme.colorScheme.error) }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            "关闭数字锁",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Text(
                            "关闭后启动应用不再要求验证",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                    AppIcon(AppSymbol.CHEVRON, tint = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }
        Text(
            "数字密码只保存在本机，不会上传到服务器，也不会以明文显示。关闭数字锁不会删除账本或服务器账号。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    when (dialog) {
        "create", "modify", "disable" -> {
            AlertDialog(
                onDismissRequest = {
                    dialog = null
                    clearPins()
                },
                title = {
                    Text(
                        when (dialog) {
                            "create" -> "创建数字密码"
                            "modify" -> "修改数字密码"
                            else -> "关闭数字锁"
                        },
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (dialog != "create") {
                            LockPinField(oldPin, { if (it.length <= 6 && it.all(Char::isDigit)) oldPin = it }, "当前密码", ImeAction.Next)
                        }
                        if (dialog != "disable") {
                            LockPinField(newPin, { if (it.length <= 6 && it.all(Char::isDigit)) newPin = it }, "新 6 位数字密码", ImeAction.Next)
                            LockPinField(confirmPin, {
                                if (it.length <= 6 &&
                                    it.all(Char::isDigit)
                                ) {
                                    confirmPin = it
                                }
                            }, "再次输入新密码", ImeAction.Done)
                            if (confirmPin.isNotEmpty() && newPin != confirmPin) {
                                Text("两次密码输入不一致", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (dialog == "disable") {
                            Text("关闭后需要当前密码验证。账本数据、服务器账号与已保存的登录状态不受影响。", style = MaterialTheme.typography.bodySmall)
                        }
                        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled =
                            !state.busy &&
                                when (dialog) {
                                    "create" -> newPin.length == 6 && newPin == confirmPin
                                    "modify" -> oldPin.length == 6 && newPin.length == 6 && newPin == confirmPin
                                    else -> oldPin.length == 6
                                },
                        onClick = {
                            val type = dialog ?: return@TextButton
                            val old = oldPin.toCharArray()
                            val fresh = newPin.takeIf { type != "disable" }?.toCharArray()
                            clearPins()
                            dialog = null
                            if (type == "create") {
                                lock.configure(fresh ?: CharArray(0)) {}
                            } else {
                                lock.verifyThenConfigure(old, fresh) {}
                            }
                        },
                    ) { Text(if (dialog == "disable") "确认关闭" else "确认") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        dialog = null
                        clearPins()
                    }) { Text("取消") }
                },
            )
        }

        "biometric" -> {
            val keyguard = LocalContext.current.getSystemService(android.app.KeyguardManager::class.java)
            val manager = LocalContext.current.getSystemService(android.hardware.biometrics.BiometricManager::class.java)
            val supported =
                android.os.Build.VERSION.SDK_INT >= 29 && state.configured && keyguard.isDeviceSecure &&
                    manager?.canAuthenticate(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
                    android.hardware.biometrics.BiometricManager.BIOMETRIC_SUCCESS
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text("生物识别解锁") },
                text = { Text(if (supported) "启用后，验证由 Android 系统完成；ICOS 不读取或保存生物特征。" else "此设备未提供可用的系统生物识别或安全锁屏；可继续使用数字密码。") },
                confirmButton = {
                    TextButton(enabled = supported, onClick = {
                        lock.setBiometric(true) {}
                        dialog = null
                    }) { Text(if (supported) "启用" else "知道了") }
                },
                dismissButton = { if (supported) TextButton(onClick = { dialog = null }) { Text("取消") } },
            )
        }
    }
}

@Composable
private fun LockPinField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    imeAction: ImeAction,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = imeAction),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SettingRow(
    icon: AuthSymbol,
    title: String,
    subtitle: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        AuthIcon(icon, modifier = Modifier.size(26.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AppIcon(AppSymbol.CHEVRON, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SwitchRow(
    icon: AuthSymbol,
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        AuthIcon(
            icon,
            modifier = Modifier.size(26.dp),
            tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}
