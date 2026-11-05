package dev.icos.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.icos.app.data.local.FinanceSnapshot
import dev.icos.app.data.remote.CategoryShareJson
import dev.icos.app.data.remote.CategorySharePreview
import dev.icos.app.domain.Direction
import java.util.UUID

private tailrec fun Context.findActivity(): ComponentActivity? =
    when (this) {
        is ComponentActivity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

@Composable
internal fun CategorySharePanel(
    snapshot: FinanceSnapshot,
    model: FinanceViewModel,
    accountMode: Boolean,
) {
    val ownerActivity = LocalContext.current.findActivity() ?: error("Activity ViewModel owner unavailable")
    val shareModel = remember(ownerActivity) { ownerActivity.viewModels<CategoryShareViewModel>().value }
    val authViewModel = remember(ownerActivity) { ownerActivity.viewModels<AuthViewModel>().value }
    androidx.compose.runtime.LaunchedEffect(authViewModel.state.sessionIdentity, authViewModel.isGuestMode) {
        shareModel.onIdentityChanged(authViewModel.state.sessionIdentity.takeUnless { authViewModel.isGuestMode })
    }
    val state = shareModel.state
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var direction by remember(snapshot.ledgerId) { mutableStateOf(Direction.EXPENSE) }
    var selected by remember(snapshot.ledgerId, direction) { mutableStateOf(setOf<UUID>()) }
    var expires by remember { mutableStateOf("86400") }
    var input by remember { mutableStateOf("") }
    var exported by remember { mutableStateOf<String?>(null) }
    var localPreview by remember { mutableStateOf<CategorySharePreview?>(null) }
    var showAllGuestCategories by remember(snapshot.ledgerId) { mutableStateOf(false) }
    val sourceCategories = if (accountMode) snapshot.categories else snapshot.allCategories.filter { it.ledgerId == snapshot.ledgerId }
    val candidates = sourceCategories.filter { it.direction == direction && it.archivedAt == null }
    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("分类分享", style = MaterialTheme.typography.titleMedium)
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("分享设置", style = MaterialTheme.typography.titleSmall)
                Text(if (accountMode) "导入会合并到当前账号账本；同名但设置不同的分类会提示冲突。" else "选择分类导出本地配置，并在其他访客账本中预览导入。")
                Text("当前分享格式不支持分类图标或颜色；包含这类设置的分类不可选，导入也会拒绝不支持的内容。")
            }
        }
        if (!accountMode) {
            FilterChip(showAllGuestCategories, enabled = !state.busy, onClick = {
                showAllGuestCategories = !showAllGuestCategories
                selected = emptySet()
            }, label = { Text(if (showAllGuestCategories) "包括全部可用分类" else "仅根分类") })
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FilterChip(direction == Direction.EXPENSE, enabled = !state.busy, onClick = {
                direction = Direction.EXPENSE
                selected =
                    emptySet()
            }, label = { Text("支出") })
            FilterChip(direction == Direction.INCOME, enabled = !state.busy, onClick = {
                direction = Direction.INCOME
                selected = emptySet()
            }, label = { Text("收入") })
        }
        val visibleCandidates = candidates.filter { showAllGuestCategories || accountMode || it.parentId == null }
        if (visibleCandidates.isEmpty()) {
            Text(if (candidates.isEmpty()) "此方向暂无可分享的有效分类。" else "没有符合当前范围的分类。", style = MaterialTheme.typography.bodyMedium)
        }
        visibleCandidates.forEach { category ->
            val styleSupported =
                runCatching {
                    CategoryShareJson.requireSupportedStyle(null, null)
                    val style = CategoryStyles.read(context, snapshot.ownerId, category)
                    style.icon == "DEFAULT" && style.color == CategoryStyles.default(category).color && style.treatment == "SOFT"
                }.getOrDefault(false)
            val included = category.id in selected
            FilterChip(selected = included, enabled = !state.busy && styleSupported, onClick = {
                selected =
                    if (included) {
                        if (category.parentId ==
                            null
                        ) {
                            selected - category.id - candidates.filter { it.parentId == category.id }.map { it.id }.toSet()
                        } else {
                            selected -
                                category.id
                        }
                    } else {
                        selected + category.id + listOfNotNull(category.parentId)
                    }
            }, label = { Text("${if (category.parentId != null) "　└ " else ""}${category.name}") })
        }
        if (accountMode) {
            OutlinedTextField(expires, {
                expires = it.take(7).filter(Char::isDigit)
            }, label = {
                Text(
                    "有效期秒数 (300–7776000)",
                )
            }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, enabled = !state.busy)
            val currentCategories = snapshot.categories.filter { it.direction.name == direction.name && it.archivedAt == null }
            val selectedValid =
                selected.isNotEmpty() && selected.all { id -> currentCategories.any { it.id == id } } &&
                    selected.all { id -> currentCategories.single { it.id == id }.parentId?.let { parent -> parent in selected } ?: true }
            TextButton(
                enabled = !state.busy && selectedValid && expires.toLongOrNull() in 300L..7_776_000L,
                onClick = { shareModel.createAccountShare(snapshot, direction.name, selected.toList(), expires.toLong()) },
            ) { Text(if (state.busy) "处理中…" else "创建服务器分享") }
            state.created?.let { created ->
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("分享链接", style = MaterialTheme.typography.titleSmall)
                        Text("分享令牌仅显示这一次，妥善复制保存。到期：${created.expiresAt}")
                        TextButton(onClick = { clipboard.setText(AnnotatedString(created.token)) }) { Text("复制令牌") }
                        TextButton(onClick = {
                            clipboard.setText(
                                AnnotatedString(
                                    dev.icos.app.BuildConfig.AUTH_API_ORIGIN
                                        .trimEnd('/') + created.sharePath,
                                ),
                            )
                        }) { Text("复制固定 API 地址") }
                    }
                }
            }
        } else {
            TextButton(
                enabled =
                    !state.busy && selected.isNotEmpty() &&
                        selected.all { id ->
                            candidates.singleOrNull { it.id == id }?.let { category ->
                                runCatching {
                                    val style = CategoryStyles.read(context, snapshot.ownerId, category)
                                    style.icon == "DEFAULT" && style.color == CategoryStyles.default(category).color &&
                                        style.treatment == "SOFT"
                                }.getOrDefault(false)
                            } == true
                        },
                onClick = {
                    shareModel.exportGuest(snapshot, model, direction.name, selected.toList()) { result -> exported = result.getOrNull() }
                },
            ) { Text("导出本地配置") }
        }
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("导入分类", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    input,
                    { input = it.take(64 * 1024) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("粘贴分享令牌、固定 API 地址或本地配置 JSON") },
                    minLines = 2,
                    maxLines = 4,
                    enabled = !state.busy,
                )
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(enabled = !state.busy && input.isNotBlank(), onClick = {
                val local =
                    runCatching {
                        dev.icos.app.data.remote.CategoryShareJson
                            .localImport(input)
                    }.getOrNull()
                if (local == null) {
                    localPreview = null
                    shareModel.preview(input)
                } else {
                    shareModel.clearResult()
                    localPreview = local
                }
            }) { Text("预览") }
            TextButton(
                enabled =
                    !state.busy && !model.state.busy && state.preview != null && (!accountMode || !model.state.accountMutationPending),
                onClick = {
                    state.preview?.let { preview ->
                        if (accountMode) {
                            shareModel.importAccount(
                                snapshot,
                                model,
                                preview,
                            )
                        } else {
                            shareModel.importGuest(snapshot, model, preview)
                        }
                    }
                },
            ) { Text(if (accountMode) "合并导入到当前账号账本" else "合并导入到当前访客账本") }
        }
        state.preview?.let { preview ->
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("导入预览", style = MaterialTheme.typography.titleSmall)
                    Text("有效期：${preview.expiresAt.ifBlank { "本地配置" }} · ${preview.categories.size} 个分类")
                    preview.categories.forEach { Text("${if (it.parentSourceId != null) "　└ " else ""}${it.name} · ${it.direction}") }
                }
            }
        }
        localPreview?.let { preview ->
            Text("本地配置预览 · ${preview.categories.size} 个分类")
            preview.categories.forEach { Text("${if (it.parentSourceId != null) "　└ " else ""}${it.name} · ${it.direction}") }
            if (state.preview ==
                null
            ) {
                TextButton(enabled = !state.busy && !model.state.busy && (!accountMode || !model.state.accountMutationPending), onClick = {
                    if (accountMode) {
                        shareModel.importAccount(snapshot, model, preview)
                    } else {
                        shareModel.importGuest(snapshot, model, preview)
                        localPreview =
                            null
                    }
                }) { Text("合并导入") }
            }
        }
        state.importedMapping?.let { Text(if (accountMode) "已生成分类映射：${it.size} 项；请查看账号同步结果。" else "已完成分类映射：${it.size} 项。") }
        if (state.busy) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("正在处理分类分享…", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (!state.busy && state.preview == null && localPreview == null && state.error == null) {
            Text("粘贴分享内容后可预览；确认后才会合并到账本。", style = MaterialTheme.typography.bodySmall)
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
    exported?.let { json ->
        AlertDialog(
            onDismissRequest = { exported = null },
            title = { Text("本地分类配置") },
            text = {
                Column {
                    Text("JSON 已生成，可复制后在另一访客账本粘贴导入。")
                    Text(json.take(1500), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(json))
                    exported = null
                }) { Text("复制全部 JSON") }
            },
            dismissButton = { TextButton(onClick = { exported = null }) { Text("关闭") } },
        )
    }
}
