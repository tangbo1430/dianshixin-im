package dev.icos.app.ui

import android.os.SystemClock
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.icos.app.R
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun AuthPanel(model: AuthViewModel) {
    if (!model.visible) return
    // Text field internals must not save passwords or OTPs into an Activity bundle.
    CompositionLocalProvider(LocalSaveableStateRegistry provides null) {
        AuthPanelRoute(model)
        if (model.accountDeletionOpen) AccountDeletionDialog(model)
        model.state.revokeConfirm?.let { RevokeSessionDialog(model, it) }
    }
}

private enum class AuthPage { FORM, PROFILE, DEVICES }

@Composable
private fun AuthPanelRoute(model: AuthViewModel) {
    val user = model.state.user
    var page by remember { mutableStateOf(if (user != null) AuthPage.PROFILE else AuthPage.FORM) }
    LaunchedEffect(user?.id) { page = if (user != null) AuthPage.PROFILE else AuthPage.FORM }
    Dialog(
        onDismissRequest = {
            when {
                model.state.busy -> Unit
                user != null && page == AuthPage.DEVICES -> page = AuthPage.PROFILE
                else -> model.dismiss()
            }
        },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when (page) {
                AuthPage.PROFILE -> {
                    ProfilePage(
                        model,
                        onBack = { if (!model.state.busy) model.dismiss() },
                        onDevices = { page = AuthPage.DEVICES },
                    )
                }

                AuthPage.DEVICES -> {
                    ProfileDevicesPage(model, onBack = { page = AuthPage.PROFILE })
                }

                AuthPage.FORM -> {
                    AuthFormPage(model)
                }
            }
        }
    }
}

@Composable
private fun AuthFormPage(model: AuthViewModel) {
    val state = model.state
    val wait = rememberWaitSeconds(state.waitUntil)
    var showSecret by remember { mutableStateOf(false) }
    var showConfirm by remember { mutableStateOf(false) }
    var confirmSecret by remember { mutableStateOf("") }
    var agreementsAccepted by remember { mutableStateOf(false) }
    var showLegal by remember { mutableStateOf(false) }
    LaunchedEffect(model.form) {
        confirmSecret = ""
        agreementsAccepted = false
        showSecret = false
        showConfirm = false
    }
    val editable = !state.busy && !state.retryAvailable && state.ready && model.configured
    val emailEditable = editable && state.challenge == null && state.reset == null
    val hasChallenge = state.challenge != null || (model.form == AuthForm.RESET && state.reset != null)
    val validSecret = model.secret.codePointCount(0, model.secret.length) in 12..128
    val validCode = model.code.matches(Regex("[0-9]{6}"))
    val canSubmit =
        editable && model.email.isNotBlank() && validSecret &&
            when (model.form) {
                AuthForm.LOGIN -> true
                AuthForm.REGISTER -> agreementsAccepted
                AuthForm.RESET -> hasChallenge && validCode && confirmSecret == model.secret
            }
    val canRequestCode = model.form == AuthForm.RESET && editable && wait == 0L && model.email.isNotBlank()
    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = model::dismiss, enabled = !state.busy) { AppIcon(AppSymbol.BACK, "返回") }
            Text(
                when (model.form) {
                    AuthForm.LOGIN -> "登录账号"
                    AuthForm.REGISTER -> "创建账号"
                    AuthForm.RESET -> "找回密码"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
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
            Spacer(Modifier.height(8.dp))
            Surface(shape = MaterialTheme.shapes.large, color = summaryCardColor()) {
                Box(Modifier.size(76.dp), contentAlignment = Alignment.Center) {
                    if (model.form == AuthForm.RESET) {
                        AuthIcon(
                            AuthSymbol.KEY,
                            description = null,
                            modifier = Modifier.size(38.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        Image(
                            painter = painterResource(R.drawable.ic_launcher_foreground),
                            contentDescription = null,
                            modifier = Modifier.size(54.dp),
                        )
                    }
                }
            }
            Text(
                when (model.form) {
                    AuthForm.LOGIN -> "欢迎回来"
                    AuthForm.REGISTER -> "创建账号"
                    AuthForm.RESET -> "找回密码"
                },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                when (model.form) {
                    AuthForm.LOGIN -> "登录后可在多设备间同步账本，账号账本与访客账本分别保留"
                    AuthForm.REGISTER -> "使用邮箱和密码注册，账号账本与访客账本分别保留"
                    AuthForm.RESET -> "验证邮箱后设置新密码；重置成功后请重新登录"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (!model.configured) {
                Text(
                    "此版本尚未配置账号服务器，仍可返回使用本机访客账本。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            AuthFeedback(state.error, state.message)
            if (wait > 0) {
                Text(
                    "请等待 $wait 秒后重试或重新申请验证码",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!state.ready && !state.busy) {
                OutlinedButton(onClick = model::reload) { Text("重试读取账号状态") }
            }
            if (state.reset != null) {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = summaryCardColor())) {
                    Text(
                        "上次密码重置尚未确认，暂不能登录或注册。可重输原验证码及原新密码恢复同一请求，或完成同一邮箱的新重置。关闭面板或重启不会解除此保护。",
                        Modifier.padding(14.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            OutlinedTextField(
                value = model.email,
                onValueChange = model::updateEmail,
                enabled = emailEditable,
                label = { Text("邮箱地址") },
                placeholder = { Text("name@example.com") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                leadingIcon = { AuthIcon(AuthSymbol.MAIL) },
                trailingIcon =
                    if (emailEditable && model.email.isNotEmpty()) {
                        {
                            IconButton(onClick = { model.updateEmail("") }) { AppIcon(AppSymbol.CLOSE, "清空邮箱") }
                        }
                    } else {
                        null
                    },
            )

            if (model.form == AuthForm.RESET) {
                OutlinedTextField(
                    value = model.code,
                    onValueChange = model::updateCode,
                    enabled = editable,
                    label = { Text("6 位邮箱验证码") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Next),
                    visualTransformation = PasswordVisualTransformation(),
                    leadingIcon = { AuthIcon(AuthSymbol.SHIELD_CHECK) },
                    supportingText = {
                        Text(
                            when {
                                state.reset != null && state.challenge == null -> "恢复原重置：请输入当时的验证码，即使验证码已过期也可尝试取回同请求回执。"
                                state.challenge != null -> "验证码已发送至该邮箱，5 分钟有效；请保留前导零。"
                                else -> "先获取邮箱验证码，再设置密码。"
                            },
                        )
                    },
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (state.challenge != null || state.reset != null) "没有收到验证码？" else "验证码用于确认邮箱归属。",
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(
                        onClick = model::requestChallenge,
                        enabled = canRequestCode,
                    ) {
                        Text(
                            when {
                                wait > 0 -> "${wait}秒"
                                state.challenge != null || state.reset != null -> "重新发送"
                                else -> "获取验证码"
                            },
                        )
                    }
                }
                if (state.reset == null && state.challenge != null) {
                    TextButton(onClick = model::newChallenge, enabled = editable) { Text("更换邮箱或重新申请") }
                }
            }

            OutlinedTextField(
                value = model.secret,
                onValueChange = model::updateSecret,
                enabled = editable,
                label = { Text(if (model.form == AuthForm.RESET) "新密码" else "密码") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (showSecret) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions =
                    KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = if (model.form == AuthForm.RESET) ImeAction.Next else ImeAction.Done,
                    ),
                leadingIcon = { AppIcon(AppSymbol.LOCK) },
                trailingIcon = {
                    IconButton(onClick = { showSecret = !showSecret }) {
                        AppIcon(if (showSecret) AppSymbol.EYE_OFF else AppSymbol.EYE, if (showSecret) "隐藏密码" else "显示密码")
                    }
                },
                supportingText = { Text("12–128 个字符") },
            )

            if (model.form == AuthForm.RESET) {
                OutlinedTextField(
                    value = confirmSecret,
                    onValueChange = { confirmSecret = it },
                    enabled = editable,
                    label = { Text("确认新密码") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (showConfirm) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    leadingIcon = { AppIcon(AppSymbol.LOCK) },
                    trailingIcon = {
                        IconButton(onClick = { showConfirm = !showConfirm }) {
                            AppIcon(if (showConfirm) AppSymbol.EYE_OFF else AppSymbol.EYE, if (showConfirm) "隐藏确认密码" else "显示确认密码")
                        }
                    },
                )
                if (confirmSecret.isNotEmpty() && confirmSecret != model.secret) {
                    Text("两次密码输入不一致", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }

            if (model.form == AuthForm.REGISTER) {
                Row(verticalAlignment = Alignment.Top) {
                    Checkbox(
                        checked = agreementsAccepted,
                        onCheckedChange = { agreementsAccepted = it },
                        enabled = editable,
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("我已阅读并同意当前版本的", style = MaterialTheme.typography.bodySmall)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                onClick = { showLegal = true },
                                contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp),
                            ) { Text("用户协议") }
                            Text("与", style = MaterialTheme.typography.bodySmall)
                            TextButton(
                                onClick = { showLegal = true },
                                contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp),
                            ) { Text("隐私政策") }
                        }
                    }
                }
            }

            Button(
                onClick = model::submit,
                enabled = canSubmit,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                colors = primaryButtonColors(),
            ) {
                if (state.busy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
                    Spacer(Modifier.size(8.dp))
                    Text("处理中…")
                } else {
                    Text(
                        when (model.form) {
                            AuthForm.LOGIN -> "登录"
                            AuthForm.REGISTER -> "创建账号"
                            AuthForm.RESET -> "重置密码"
                        },
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (model.form == AuthForm.LOGIN) {
                    TextButton(onClick = { model.chooseForm(AuthForm.REGISTER) }, enabled = editable) { Text("没有账号？注册") }
                    TextButton(onClick = { model.chooseForm(AuthForm.RESET) }, enabled = editable) { Text("忘记密码？") }
                } else {
                    TextButton(
                        onClick = { model.chooseForm(AuthForm.LOGIN) },
                        enabled = editable && state.reset == null,
                    ) { Text("已有账号？去登录") }
                    if (model.form == AuthForm.RESET && state.reset == null) {
                        TextButton(onClick = { model.chooseForm(AuthForm.LOGIN) }, enabled = editable) { Text("取消") }
                    }
                    if (model.form == AuthForm.RESET && state.reset != null) {
                        Text(
                            "重置尚未确认时不能返回登录；可继续恢复原请求，或关闭面板返回账本。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            AuthRetryPanel(model)

            if (model.form == AuthForm.LOGIN) {
                Card(
                    onClick = model::dismiss,
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Row(
                        Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Surface(shape = MaterialTheme.shapes.medium, color = summaryCardColor()) {
                            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                                AppIcon(AppSymbol.BOOK, tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("本机访客继续", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(
                                "不登录也可以记账，账本仅保存在本机",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        AppIcon(AppSymbol.CHEVRON, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                TextButton(onClick = model::dismiss, enabled = !state.busy) { Text("返回本机访客账本") }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
    if (showLegal) PublishedLegalDialog(onClose = { showLegal = false })
}

@Composable
internal fun AuthFeedback(
    error: String?,
    message: String?,
) {
    error?.let {
        Text(
            it,
            Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
    }
    message?.let {
        Text(
            it,
            Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun AuthRetryPanel(model: AuthViewModel) {
    val state = model.state
    if (!state.retryAvailable) return
    val wait = rememberWaitSeconds(state.waitUntil)
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Text(
        if (state.retryIsDevice) {
            "上次撤销请求未确认。重试会使用相同请求编号和同一目标会话；放弃后该撤销可能已在服务器完成，需重新加载设备列表。"
        } else {
            "上次请求未确认。重试会使用相同请求编号和原始内容；关闭面板会清除内存中的密码、验证码及本次重试。"
        },
        style = MaterialTheme.typography.bodySmall,
    )
    Button(onClick = model::retry, enabled = !state.busy && wait == 0L) { Text("重试同一请求") }
    TextButton(onClick = model::discardRetry, enabled = !state.busy) {
        Text(if (state.reset == null) "放弃本次重试" else "清除输入，改用重置恢复")
    }
}

@Composable
private fun rememberWaitSeconds(waitUntil: Long): Long {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(waitUntil) {
        now = SystemClock.elapsedRealtime()
        while (now < waitUntil) {
            delay(1000)
            now = SystemClock.elapsedRealtime()
        }
    }
    return ((waitUntil - now + 999) / 1000).coerceAtLeast(0)
}

@Composable
private fun AccountDeletionDialog(model: AuthViewModel) {
    val state = model.state
    val wait = rememberWaitSeconds(state.waitUntil)
    var deletionConfirmed by remember { mutableStateOf(false) }
    var deletionPermanentlyConfirmed by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = model::closeAccountDeletion,
        title = { Text("注销服务器账号") },
        text = {
            Column(
                Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("此操作不可撤销：服务器将停用账号、吊销会话并启动分阶段清理。本机访客账本与本地数据会保留。")
                Text("第一步：明确确认你要申请注销此服务器账号。")
                Checkbox(deletionConfirmed, onCheckedChange = {
                    deletionConfirmed = it
                    deletionPermanentlyConfirmed = false
                    model.setDeletionReady(it)
                }, enabled = !state.busy)
                Text("我了解注销会停用服务器账号，并确认继续")
                if (deletionConfirmed && !state.deletionReady && !state.retryAvailable && state.deletionReceipt == null) {
                    Text("输入当前账号密码重新验证身份。")
                    OutlinedTextField(
                        value = model.secret,
                        onValueChange = model::updateSecret,
                        enabled = !state.busy,
                        label = { Text("当前账号密码") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    )
                    OutlinedButton(
                        onClick = model::confirmDeletionReauth,
                        enabled = !state.busy && model.secret.codePointCount(0, model.secret.length) in 12..128,
                    ) { Text("重新验证当前账号") }
                }
                if (state.deletionReady && !state.retryAvailable && state.deletionReceipt == null) {
                    Text("第二步：重新验证已完成。再次确认后，将向服务器提交不可撤销的注销请求。")
                    Checkbox(deletionPermanentlyConfirmed, onCheckedChange = { deletionPermanentlyConfirmed = it }, enabled = !state.busy)
                    Text("我再次确认永久注销此服务器账号。")
                    OutlinedButton(
                        onClick = model::confirmAccountDeletion,
                        enabled = !state.busy && deletionPermanentlyConfirmed,
                    ) { Text("确认提交注销申请") }
                }
                if (state.busy) CircularProgressIndicator()
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                state.message?.let { Text(it) }
                if (state.retryAvailable) {
                    Text("请求结果未确认。重试将沿用原操作 ID、reauth 密码或 action token 与注销正文；不会自动创建新操作。")
                    OutlinedButton(onClick = model::retryAccountDeletion, enabled = !state.busy && wait == 0L) { Text("手动重试原操作") }
                    TextButton(
                        onClick = model::cancelAccountDeletionRetry,
                        enabled = !state.busy && model.canDiscardDeletionRetry,
                    ) { Text("清除本次重新验证材料") }
                }
                state.deletionReceipt?.let { receipt ->
                    Text("服务器已受理注销申请：${receipt.jobId}，状态 ${receipt.state}，进度 ${receipt.progress}%。")
                    receipt.errorCode?.let { Text("服务器阶段状态：$it") }
                    Text("当前会话已按服务器确认撤销；后台清理尚未确认完成。本客户端不会使用已撤销会话伪造进度。")
                }
            }
        },
        confirmButton = { TextButton(onClick = model::closeAccountDeletion, enabled = !state.busy) { Text("关闭") } },
    )
}

@Composable
private fun RevokeSessionDialog(
    model: AuthViewModel,
    row: dev.icos.app.data.remote.DeviceSession,
) {
    AlertDialog(
        onDismissRequest = model::cancelRevoke,
        title = { Text("撤销其他设备会话") },
        text = { Text("将撤销设备“${row.deviceName}”的服务器会话，该设备需要重新登录。本机访客账本与本地数据不受影响。") },
        confirmButton = { TextButton(onClick = { model.revoke(row) }, enabled = !model.state.busy) { Text("确认撤销") } },
        dismissButton = { TextButton(onClick = model::cancelRevoke, enabled = !model.state.busy) { Text("取消") } },
    )
}

private val lastSeenFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

@Composable
internal fun formatLastSeen(millis: Long): String = remember(millis) { lastSeenFormat.format(Date(millis)) }
