package dev.icos.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** P04 device sessions: read, paginate and revoke through the existing AuthViewModel actions. */
@Composable
internal fun ProfileDevicesPage(
    model: AuthViewModel,
    onBack: () -> Unit,
) {
    val state = model.state
    val editable = !state.busy && !state.retryAvailable && state.ready && model.configured
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, enabled = !state.busy) { AppIcon(AppSymbol.BACK, "返回个人资料") }
            Text("我的设备", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AuthFeedback(state.error, state.message)
            Text(
                "列出当前账号仍在使用的服务器会话。当前会话请回到个人资料使用“退出账号”撤销；其他会话可单独撤销。两者都不影响本机访客账本。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AuthRetryPanel(model)
            val devices = state.devices
            if (devices == null) {
                OutlinedButton(onClick = model::loadDevices, enabled = editable) { Text("加载设备会话") }
            } else {
                if (devices.items.isEmpty()) {
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Text("当前账号没有活动的服务器会话。", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                } else {
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column {
                            devices.items.forEachIndexed { index, row ->
                                if (index > 0) {
                                    HorizontalDivider(
                                        Modifier.padding(horizontal = 16.dp),
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                                    )
                                }
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    // The device name is untrusted plain text and is rendered as text only.
                                    Text(row.deviceName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        "最近活动：${formatLastSeen(row.lastSeenAtMillis)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    if (row.current) {
                                        Text(
                                            "当前设备（本机登录会话）",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.tertiary,
                                        )
                                        Text(
                                            "请回到个人资料使用“退出账号”撤销当前会话。",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    } else {
                                        OutlinedButton(onClick = { model.requestRevoke(row) }, enabled = editable) { Text("撤销此会话") }
                                    }
                                }
                            }
                        }
                    }
                }
                if (devices.hasMore) {
                    OutlinedButton(onClick = model::loadMoreDevices, enabled = editable) { Text("加载更多会话") }
                }
                OutlinedButton(onClick = model::loadDevices, enabled = editable) { Text("重新加载设备会话") }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
