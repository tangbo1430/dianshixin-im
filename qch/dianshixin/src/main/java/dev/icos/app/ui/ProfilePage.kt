package dev.icos.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** P04 profile: real server profile actions only; email changes have no server API and stay read-only. */
@Composable
internal fun ProfilePage(
    model: AuthViewModel,
    onBack: () -> Unit,
    onDevices: () -> Unit,
) {
    val state = model.state
    val user = state.user ?: return
    val editable = !state.busy && !state.retryAvailable && state.ready && model.configured && state.confirmed
    val clipboard = LocalClipboardManager.current
    var showNickname by remember { mutableStateOf(false) }
    var showAvatarActions by remember { mutableStateOf(false) }
    var copiedId by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, enabled = !state.busy) { AppIcon(AppSymbol.BACK, "返回") }
            Text("个人资料", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            IconButton(
                onClick = {
                    model.updateNickname(user.nickname)
                    showNickname = true
                },
                enabled = editable,
            ) { AppIcon(AppSymbol.EDIT, "编辑昵称") }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AuthFeedback(state.error, state.message)
            Box(Modifier.size(104.dp), contentAlignment = Alignment.BottomEnd) {
                AccountAvatar(
                    bitmap = model.avatarBitmap,
                    modifier = Modifier.size(96.dp),
                    description = "账号头像",
                )
                Surface(
                    onClick = { showAvatarActions = true },
                    enabled = editable,
                    modifier = Modifier.size(34.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ) {
                    Box(contentAlignment = Alignment.Center) { AuthIcon(AuthSymbol.CAMERA, "更换头像", Modifier.size(18.dp)) }
                }
            }
            Text(user.nickname, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                if (state.confirmed) "服务器已确认的本人资料" else "本机保存的账号，待服务器确认",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = model::refreshProfile,
                enabled =
                    !state.busy && !state.retryAvailable && state.ready && model.configured,
            ) {
                Text("从服务器刷新本人资料")
            }

            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column {
                    ProfileRow(
                        icon = AuthSymbol.MAIL,
                        title = "邮箱",
                        value = user.verifiedEmail,
                        trailing = {
                            AuthIcon(
                                AuthSymbol.CHECK_CIRCLE,
                                description = "邮箱已验证",
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.tertiary,
                            )
                        },
                    )
                    HorizontalDivider(
                        Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                    )
                    ProfileRow(
                        icon = AuthSymbol.ID_CARD,
                        title = "昵称",
                        value = user.nickname,
                        enabled = editable,
                        onClick = {
                            model.updateNickname(user.nickname)
                            showNickname = true
                        },
                        trailing = { AppIcon(AppSymbol.CHEVRON, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    )
                    HorizontalDivider(
                        Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                    )
                    ProfileRow(
                        icon = AuthSymbol.ID_CARD,
                        title = "账号信息",
                        value = "•••• ${user.id.toString().takeLast(4)}",
                        trailing = {
                            IconButton(onClick = {
                                clipboard.setText(AnnotatedString(user.id.toString()))
                                copiedId = true
                            }) { AppIcon(AppSymbol.COPY, "复制账号 ID") }
                        },
                    )
                    if (copiedId) {
                        Text(
                            "已复制账号 ID",
                            Modifier.padding(start = 56.dp, bottom = 10.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider(
                        Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                    )
                    ProfileRow(
                        icon = AuthSymbol.MONITOR,
                        title = "我的设备",
                        value = state.devices?.let { "${it.items.size} 台" } ?: "管理",
                        onClick = onDevices,
                        trailing = { AppIcon(AppSymbol.CHEVRON, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    )
                }
            }

            Spacer(Modifier.height(4.dp))
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = editable) { model.logout() }.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    AuthIcon(AuthSymbol.LOGOUT, modifier = Modifier.size(26.dp), tint = MaterialTheme.colorScheme.error)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            "退出账号",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            "移除本机登录凭据并请求撤销服务器会话；访客账本保留",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    AppIcon(AppSymbol.CHEVRON, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = editable) { model.openAccountDeletion() }.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    AppIcon(AppSymbol.DELETE, tint = MaterialTheme.colorScheme.error)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            "注销账号",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Text(
                            "停用服务器账号并撤销会话；访客账本保留",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                    AppIcon(AppSymbol.CHEVRON, tint = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
            Text(
                "账号账本与访客账本分别保存。退出或注销都不会删除本机访客账本。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
        }
    }
    if (showNickname) {
        AlertDialog(
            onDismissRequest = { showNickname = false },
            title = { Text("编辑昵称") },
            text = {
                OutlinedTextField(
                    value = model.nicknameDraft,
                    onValueChange = model::updateNickname,
                    enabled = !state.busy,
                    label = { Text("昵称") },
                    singleLine = true,
                    supportingText = { Text("1–32 个字符，会去除首尾空白并拒绝控制字符") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !state.busy && model.nicknameDraft.trim().isNotEmpty() && model.nicknameDraft.trim() != user.nickname,
                    onClick = {
                        model.saveNickname()
                        showNickname = false
                    },
                ) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showNickname = false }) { Text("取消") } },
        )
    }
    if (showAvatarActions) {
        AlertDialog(
            onDismissRequest = { showAvatarActions = false },
            title = { Text("头像") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("选中的图片会在本机转换为服务端支持的头像格式后上传。保存到相册仅保存当前头像，不会自动上传其他图片。", style = MaterialTheme.typography.bodySmall)
                    val identity = state.sessionIdentity
                    key(identity) {
                        AvatarMediaActions(
                            bitmap = model.avatarBitmap,
                            enabled = editable,
                            isCurrent = {
                                identity != null && model.state.sessionIdentity == identity &&
                                    model.state.confirmed && !model.state.busy && !model.state.retryAvailable && model.avatarMediaAllowed
                            },
                            onSelected = {
                                model.uploadAvatar(it)
                                showAvatarActions = false
                            },
                        )
                    }
                    if (user.avatarId != null) {
                        OutlinedButton(
                            onClick = {
                                showAvatarActions = false
                                model.loadAvatar()
                            },
                            enabled =
                                editable && model.avatarBitmap == null,
                        ) {
                            Text("读取服务器头像")
                        }
                        OutlinedButton(onClick = {
                            showAvatarActions = false
                            model.clearAvatar()
                        }, enabled = editable) {
                            Text("清除服务器头像")
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showAvatarActions = false }) { Text("关闭") } },
        )
    }
}

@Composable
private fun ProfileRow(
    icon: AuthSymbol,
    title: String,
    value: String,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier,
            ).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        AuthIcon(icon, modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(
            value,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        trailing()
    }
}
