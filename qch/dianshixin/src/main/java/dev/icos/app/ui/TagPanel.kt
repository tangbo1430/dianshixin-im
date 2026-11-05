package dev.icos.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.icos.app.data.local.FinanceSnapshot
import dev.icos.app.data.local.FinanceTag
import dev.icos.app.data.local.FinanceTagGroup
import dev.icos.app.data.local.tagColorIsValid
import dev.icos.app.data.local.tagNameIsValid
import java.util.UUID

/** Tag settings retain captured versions and deletion previews until durable command enqueue. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TagPanel(
    snapshot: FinanceSnapshot,
    model: FinanceViewModel,
    enabled: Boolean,
    error: String?,
) {
    val accountMode = model.state.accountOwner != null
    val canMutate =
        enabled &&
            !(
                accountMode && (
                    model.state.accountMutationPending || model.state.syncPaused ||
                        model.state.accountClearPending || model.state.restoreBusy
                )
            )
    val canDismiss = !model.state.busy
    var editingTag by remember { mutableStateOf<FinanceTag?>(null) }
    var editingGroup by remember { mutableStateOf<FinanceTagGroup?>(null) }
    var creatingGroup by remember { mutableStateOf(false) }
    var creatingTag by remember { mutableStateOf(false) }
    var deletingTag by remember { mutableStateOf<FinanceTag?>(null) }
    var deletingGroup by remember { mutableStateOf<FinanceTagGroup?>(null) }
    var accountTagDelete by remember { mutableStateOf<dev.icos.app.data.local.AccountTagCommands.TagDeletePreview?>(null) }
    var accountGroupDelete by remember { mutableStateOf<dev.icos.app.data.local.AccountTagCommands.TagGroupDeletePreview?>(null) }
    LaunchedEffect(model.state.accountOwner, snapshot.ownerId, snapshot.epochId) {
        editingTag = null
        editingGroup = null
        creatingGroup = false
        creatingTag = false
        deletingTag = null
        deletingGroup = null
        accountTagDelete = null
        accountGroupDelete = null
    }
    var search by remember(snapshot.ownerId, snapshot.epochId) { mutableStateOf("") }
    var collapsedGroups by remember(snapshot.ownerId, snapshot.epochId) { mutableStateOf(emptySet<UUID>()) }
    var groupMenu by remember(snapshot.ownerId, snapshot.epochId, search) { mutableStateOf<UUID?>(null) }
    var tagMenu by remember(snapshot.ownerId, snapshot.epochId, search) { mutableStateOf<UUID?>(null) }
    val usageCounts =
        remember(snapshot.records) {
            snapshot.records
                .flatMap { it.tagIds }
                .groupingBy { it }
                .eachCount()
        }
    val orderedGroups = snapshot.tagGroups.sortedBy { it.sortOrder }
    val groupOrderEditable = search.isBlank() && canMutate && orderedGroups.size > 1 && orderedGroups.all { it.localRevision > 0 }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            enabled = canDismiss,
            label = { Text("搜索标签或分组") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(enabled = canMutate, onClick = {
                model.clearError()
                creatingTag = true
            }) { Text("新增标签") }
            TextButton(enabled = canMutate, onClick = {
                model.clearError()
                creatingGroup = true
            }) { Text("新增分组") }
        }
        val visibleGroups =
            orderedGroups.filter { group ->
                group.name.contains(search, ignoreCase = true) ||
                    snapshot.tags.any { it.groupId == group.id && it.name.contains(search, ignoreCase = true) }
            }
        if (snapshot.tagGroups.isEmpty()) {
            Text("暂无标签分组")
        } else if (visibleGroups.isEmpty()) {
            Text("没有匹配的标签或分组")
        }
        Text("长按标签可打开操作菜单；次数仅统计当前账本。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        visibleGroups.forEachIndexed { groupIndex, group ->
            val groupTags =
                orderedTags(snapshot, group).filter {
                    group.name.contains(search, ignoreCase = true) || it.name.contains(search, ignoreCase = true)
                }
            val expanded = group.id !in collapsedGroups || search.isNotBlank()
            val tagOrderEditable = search.isBlank() && canMutate && groupTags.size > 1 && groupTags.all { it.localRevision > 0 }
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(group.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        Text("${snapshot.tags.count { it.groupId == group.id }} 个标签", style = MaterialTheme.typography.labelSmall)
                        IconButton(enabled = search.isBlank(), onClick = {
                            collapsedGroups = if (group.id in collapsedGroups) collapsedGroups - group.id else collapsedGroups + group.id
                        }) { AppIcon(AppSymbol.CHEVRON, if (expanded) "收起分组" else "展开分组", Modifier.rotate(if (expanded) 270f else 90f)) }
                        Box {
                            TextButton(
                                modifier = Modifier.semantics { contentDescription = "分组操作" },
                                onClick = { groupMenu = group.id },
                            ) { Text("⋯") }
                            DropdownMenu(expanded = groupMenu == group.id, onDismissRequest = { groupMenu = null }) {
                                DropdownMenuItem(text = { Text("编辑分组") }, enabled = canMutate, onClick = {
                                    groupMenu = null
                                    model.clearError()
                                    editingGroup = group
                                })
                                if (accountMode) {
                                    DropdownMenuItem(text = { Text("上移") }, enabled = groupOrderEditable && groupIndex > 0, onClick = {
                                        groupMenu = null
                                        moveGroup(orderedGroups, groupIndex, -1, model)
                                    })
                                    DropdownMenuItem(
                                        text = { Text("下移") },
                                        enabled =
                                            groupOrderEditable && groupIndex < orderedGroups.lastIndex,
                                        onClick = {
                                            groupMenu = null
                                            moveGroup(orderedGroups, groupIndex, 1, model)
                                        },
                                    )
                                }
                                if (!group.isDefault) {
                                    DropdownMenuItem(text = { Text("删除分组并移入默认组") }, enabled = canMutate, onClick = {
                                        groupMenu = null
                                        if (accountMode) {
                                            model.previewAccountTagGroupDelete(group) { accountGroupDelete = it }
                                        } else {
                                            deletingGroup =
                                                group
                                        }
                                    })
                                }
                            }
                        }
                    }
                    if (expanded) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            groupTags.forEachIndexed { tagIndex, tag ->
                                val usage = usageCounts[tag.id] ?: 0
                                val tint = tag.color?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() }
                                Box {
                                    Surface(
                                        modifier =
                                            Modifier
                                                .widthIn(max = 300.dp)
                                                .heightIn(min = 48.dp)
                                                .combinedClickable(
                                                    enabled = canMutate,
                                                    role = Role.Button,
                                                    onClickLabel = "编辑标签",
                                                    onLongClickLabel = "标签操作",
                                                    onClick = {
                                                        model.clearError()
                                                        editingTag = tag
                                                    },
                                                    onLongClick = { tagMenu = tag.id },
                                                ).semantics {
                                                    contentDescription =
                                                        tag.name + "，当前账本 $usage 笔" + if (tag.archivedAt == null) "" else "，已归档"
                                                },
                                        shape = RoundedCornerShape(10.dp),
                                        color = tint?.copy(alpha = 0.14f) ?: MaterialTheme.colorScheme.surfaceVariant,
                                        contentColor =
                                            if (canMutate) {
                                                MaterialTheme.colorScheme.onSurface
                                            } else {
                                                MaterialTheme.colorScheme.onSurface
                                                    .copy(
                                                        alpha = 0.38f,
                                                    )
                                            },
                                    ) {
                                        Row(
                                            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(tag.name + if (tag.archivedAt == null) "" else " · 已归档", Modifier.weight(1f, fill = false))
                                            Text("$usage", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                    DropdownMenu(expanded = tagMenu == tag.id, onDismissRequest = { tagMenu = null }) {
                                        DropdownMenuItem(text = { Text("编辑标签") }, enabled = canMutate, onClick = {
                                            tagMenu = null
                                            model.clearError()
                                            editingTag = tag
                                        })
                                        DropdownMenuItem(text = {
                                            Text(
                                                if (tag.archivedAt ==
                                                    null
                                                ) {
                                                    "归档标签"
                                                } else {
                                                    "恢复标签"
                                                },
                                            )
                                        }, enabled = canMutate, onClick = {
                                            tagMenu = null
                                            model.archiveTag(tag)
                                        })
                                        if (accountMode) {
                                            DropdownMenuItem(text = { Text("上移") }, enabled = tagOrderEditable && tagIndex > 0, onClick = {
                                                tagMenu = null
                                                moveTag(group.id, groupTags, tagIndex, -1, model)
                                            })
                                            DropdownMenuItem(
                                                text = { Text("下移") },
                                                enabled =
                                                    tagOrderEditable && tagIndex < groupTags.lastIndex,
                                                onClick = {
                                                    tagMenu = null
                                                    moveTag(group.id, groupTags, tagIndex, 1, model)
                                                },
                                            )
                                        }
                                        DropdownMenuItem(text = { Text("删除标签") }, enabled = canMutate, onClick = {
                                            tagMenu = null
                                            if (accountMode) {
                                                model.previewAccountTagDelete(tag) { accountTagDelete = it }
                                            } else {
                                                deletingTag =
                                                    tag
                                            }
                                        })
                                    }
                                }
                            }
                        }
                        if (groupTags.isEmpty()) {
                            Text(
                                if (search.isBlank()) "此分组暂无标签" else "没有匹配的标签",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
    TagEditDialog(
        tag = editingTag,
        groups = snapshot.tagGroups,
        accountMode = accountMode,
        enabled = canMutate,
        canDismiss = canDismiss,
        currentTags = snapshot.tags,
        error = error,
        usageCount = editingTag?.let { usageCounts[it.id] ?: 0 } ?: 0,
        onArchive = {
            editingTag?.let(model::archiveTag)
            editingTag = null
        },
        onDismiss = {
            if (canDismiss) {
                editingTag = null
                creatingTag = false
            }
        },
        onSave = { groupId, name, color, order, capturedRevision, sourceRevision ->
            val target = editingTag
            val revision = if (accountMode) capturedRevision else null
            if (target == null) {
                model.saveTag(null, groupId, name, color, order, null, revision) { creatingTag = false }
            } else {
                model.saveTag(
                    target.id,
                    groupId,
                    name,
                    color,
                    if (accountMode) target.sortOrder else order,
                    target.localRevision,
                    revision,
                    sourceRevision,
                ) { editingTag = null }
            }
        },
        show = editingTag != null || creatingTag,
    )
    if (creatingGroup || editingGroup != null) {
        var name by remember(creatingGroup, editingGroup?.id) { mutableStateOf(editingGroup?.name.orEmpty()) }
        var sortOrder by remember(creatingGroup, editingGroup?.id) { mutableStateOf(editingGroup?.sortOrder?.toString().orEmpty()) }
        val close = {
            creatingGroup = false
            editingGroup = null
        }
        val original = editingGroup
        val changed =
            accountMode && original != null &&
                snapshot.tagGroups.none { it.id == original.id && it.localRevision == original.localRevision }
        AlertDialog(
            onDismissRequest = { if (canDismiss) close() },
            title = { Text(if (editingGroup == null) "新增标签分组" else "编辑标签分组") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, {
                        if (it.length <=
                            100
                        ) {
                            name = it
                        }
                    }, enabled = canMutate && !changed, label = { Text("分组名称") }, singleLine = true)
                    if (accountMode) {
                        Text("名称以外的字段由服务器保留；排序请使用组行的上移/下移。", style = MaterialTheme.typography.bodySmall)
                    } else {
                        OutlinedTextField(sortOrder, { sortOrder = it }, label = { Text("排序值") }, singleLine = true)
                    }
                    if (changed) Text("分组已变化，请关闭后重新编辑。", color = MaterialTheme.colorScheme.error)
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                Button(enabled = canMutate && !changed && name.isNotBlank(), onClick = {
                    val group = editingGroup
                    val order =
                        if (accountMode) {
                            group?.sortOrder ?: 0L
                        } else {
                            sortOrder.toLongOrNull() ?: (snapshot.tagGroups.maxOfOrNull { it.sortOrder } ?: -1) + 1
                        }
                    model.saveTagGroup(group?.id, name, order, group?.localRevision) { close() }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(enabled = canDismiss, onClick = close) { Text("取消") } },
        )
    }
    accountTagDelete?.let { preview ->
        val changed =
            snapshot.ownerId.toString() != preview.ownerId || snapshot.epochId.toString() != preview.epochId ||
                snapshot.tags.none { it.id == preview.tag.id && it.localRevision == preview.tag.localRevision }
        AlertDialog(
            onDismissRequest = { if (canDismiss) accountTagDelete = null },
            title = { Text("删除标签？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("将解除 ${preview.recordLinks.size} 笔账单、${preview.templateLinks.size} 个模板的标签关联；资金和统计保持不变。")
                    if (changed) Text("标签已变化，请关闭后重新确认。", color = MaterialTheme.colorScheme.error)
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                Button(enabled = canMutate && !changed, onClick = {
                    model.deleteAccountTag(preview) { accountTagDelete = null }
                }) { Text("删除并解除关联") }
            },
            dismissButton = { TextButton(enabled = canDismiss, onClick = { accountTagDelete = null }) { Text("取消") } },
        )
    }
    accountGroupDelete?.let { preview ->
        val members = snapshot.tags.filter { it.groupId == preview.source.id }.associate { it.id to it.localRevision }
        val changed =
            snapshot.ownerId.toString() != preview.ownerId || snapshot.epochId.toString() != preview.epochId ||
                snapshot.tagGroups.none { it.id == preview.source.id && it.localRevision == preview.source.localRevision } ||
                snapshot.tagGroups.none {
                    it.id == preview.target.id && it.localRevision == preview.target.localRevision && it.isDefault
                } ||
                members != preview.members.associate { it.id to it.localRevision }
        AlertDialog(
            onDismissRequest = { if (canDismiss) accountGroupDelete = null },
            title = { Text("删除标签分组？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${preview.members.size} 个标签（含归档）将移入 ${preview.target.name}，账单标签保留。")
                    if (changed) Text("分组或成员已变化，请关闭后重新确认。", color = MaterialTheme.colorScheme.error)
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                Button(enabled = canMutate && !changed, onClick = {
                    model.deleteAccountTagGroup(preview) { accountGroupDelete = null }
                }) { Text("移入默认组并删除") }
            },
            dismissButton = { TextButton(enabled = canDismiss, onClick = { accountGroupDelete = null }) { Text("取消") } },
        )
    }
    if (!accountMode) {
        deletingTag?.let { tag ->
            AlertDialog(
                onDismissRequest = { if (canDismiss) deletingTag = null },
                title = { Text("删除标签？") },
                text = { Text("将从 ${snapshot.records.count { tag.id in it.tagIds }} 笔历史账单中解除此标签。资金和统计不会变化。") },
                confirmButton = {
                    Button(enabled = canMutate, onClick = {
                        model.deleteTag(tag)
                        deletingTag = null
                    }) { Text("删除并解除关联") }
                },
                dismissButton = { TextButton(enabled = canDismiss, onClick = { deletingTag = null }) { Text("取消") } },
            )
        }
        deletingGroup?.let { group ->
            AlertDialog(
                onDismissRequest = { if (canDismiss) deletingGroup = null },
                title = { Text("删除标签分组？") },
                text = { Text("组内标签（含已归档标签）将移入默认组。") },
                confirmButton = {
                    Button(enabled = canMutate, onClick = {
                        model.deleteTagGroup(group)
                        deletingGroup = null
                    }) { Text("移入默认组并删除") }
                },
                dismissButton = { TextButton(enabled = canDismiss, onClick = { deletingGroup = null }) { Text("取消") } },
            )
        }
    }
}

/** Canonical display order used for both rendering and the reorder payload. Archived tags included. */
private fun orderedTags(
    snapshot: FinanceSnapshot,
    group: FinanceTagGroup,
): List<FinanceTag> = snapshot.tags.filter { it.groupId == group.id }.sortedWith(compareBy({ it.sortOrder }, { it.name }))

private fun moveGroup(
    groups: List<FinanceTagGroup>,
    index: Int,
    delta: Int,
    model: FinanceViewModel,
) {
    val target = index + delta
    if (target !in groups.indices) return
    val next = groups.toMutableList()
    next[index] = next[target].also { next[target] = next[index] }
    model.reorderAccountTagGroups(next)
}

private fun moveTag(
    groupId: UUID,
    tags: List<FinanceTag>,
    index: Int,
    delta: Int,
    model: FinanceViewModel,
) {
    val target = index + delta
    if (target !in tags.indices) return
    val next = tags.toMutableList()
    next[index] = next[target].also { next[target] = next[index] }
    model.reorderAccountTags(groupId, next)
}

@Composable
private fun TagEditDialog(
    tag: FinanceTag?,
    groups: List<FinanceTagGroup>,
    accountMode: Boolean,
    enabled: Boolean,
    canDismiss: Boolean,
    currentTags: List<FinanceTag>,
    error: String?,
    usageCount: Int,
    onArchive: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (UUID, String, String?, Long, Long?, Long?) -> Unit,
    show: Boolean,
) {
    if (!show) return
    val defaultGroupId = groups.firstOrNull { it.isDefault }?.id ?: groups.firstOrNull()?.id
    var name by remember(tag?.id) { mutableStateOf(tag?.name.orEmpty()) }
    var color by remember(tag?.id) { mutableStateOf(tag?.color.orEmpty()) }
    var groupId by remember(tag?.id) { mutableStateOf(tag?.groupId ?: defaultGroupId) }
    var capturedGroup by remember(tag?.id) { mutableStateOf(groups.singleOrNull { it.id == groupId }) }
    val sourceGroup = remember(tag?.id) { groups.singleOrNull { it.id == tag?.groupId } }
    val groupAtOpen = capturedGroup
    val changed =
        accountMode && (
            groupAtOpen == null || groups.none { it.id == groupAtOpen.id && it.localRevision == groupAtOpen.localRevision } ||
                tag != null && (
                    currentTags.none { it.id == tag.id && it.localRevision == tag.localRevision } ||
                        sourceGroup == null || groups.none { it.id == sourceGroup.id && it.localRevision == sourceGroup.localRevision }
                )
        )
    var order by remember(tag?.id) { mutableStateOf(tag?.sortOrder?.toString().orEmpty()) }
    val nameValid = tagNameIsValid(name)
    val orderValid = accountMode || order.isBlank() || order.toLongOrNull() != null
    val colorValid = tagColorIsValid(color.ifEmpty { null })
    val dirty =
        if (tag == null) {
            name.isNotBlank() || color.isNotBlank() || groupId != defaultGroupId || (!accountMode && order.isNotBlank())
        } else {
            name != tag.name || color != tag.color.orEmpty() || groupId != tag.groupId ||
                (!accountMode && order != tag.sortOrder.toString())
        }
    // The draft lives only in composition state; nothing is persisted before the enqueue callback runs.
    val dismiss = rememberEditorDismiss(dirty, enabled = canDismiss, onDismiss = onDismiss)
    val swatch = if (colorValid) runCatching { Color(android.graphics.Color.parseColor(color)) }.getOrNull() else null
    EditorPage(
        onDismissRequest = dismiss,
        title = { Text(if (tag == null) "新增标签" else "编辑标签") },
        error = error,
        confirmButton = {
            Button(enabled = enabled && !changed && groupId != null && nameValid && colorValid && orderValid && (tag == null || dirty), onClick = {
                val selected = groupId
                if (selected != null) {
                    onSave(
                        selected,
                        name,
                        color.ifEmpty { null },
                        order.toLongOrNull() ?: 0,
                        capturedGroup?.localRevision,
                        sourceGroup?.localRevision,
                    )
                }
            }) { Text("保存") }
        },
        dismissButton = {
            if (tag == null) {
                TextButton(enabled = canDismiss, onClick = dismiss) { Text("取消") }
            } else {
                TextButton(enabled = enabled && !changed && !dirty, onClick = onArchive) {
                    Text(if (tag.archivedAt == null) "归档标签" else "恢复标签")
                }
            }
        },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (it.codePointCount(0, it.length) <= 100) name = it },
                    enabled = enabled && !changed,
                    label = { Text("标签名称") },
                    supportingText = { Text("1 至 100 个字符") },
                    isError = !nameValid && name.isNotEmpty(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("所属分组", style = MaterialTheme.typography.titleSmall)
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    groups.forEach { group ->
                        TextButton(enabled = enabled, onClick = {
                            groupId = group.id
                            capturedGroup = group
                        }) {
                            Text("${if (groupId == group.id) "✓ " else ""}${group.name}")
                        }
                    }
                }
                Text("标签颜色", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(
                        Modifier
                            .size(56.dp)
                            .clearAndSetSemantics {
                                contentDescription = "默认标签颜色"
                                role = Role.RadioButton
                                selected = color.isEmpty()
                                stateDescription = if (color.isEmpty()) "已选中" else "未选中"
                                if (enabled && !changed) {
                                    onClick("选择默认颜色") {
                                        color = ""
                                        true
                                    }
                                } else {
                                    disabled()
                                }
                            }.selectable(
                                selected = color.isEmpty(),
                                enabled = enabled && !changed,
                                role = Role.RadioButton,
                                onClick = { color = "" },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier
                                .size(40.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                                .border(
                                    if (color.isEmpty()) 2.dp else 0.dp,
                                    if (color.isEmpty()) MaterialTheme.colorScheme.primary else Color.Transparent,
                                    CircleShape,
                                ),
                            contentAlignment = Alignment.Center,
                        ) { Text("默认", style = MaterialTheme.typography.labelSmall) }
                    }
                    CategoryStyles.colors().forEach { hex ->
                        val chosen = color.equals(hex, ignoreCase = true)
                        val choice = Color(android.graphics.Color.parseColor(hex))
                        Box(
                            Modifier
                                .size(56.dp)
                                .clearAndSetSemantics {
                                    contentDescription = "标签颜色 $hex"
                                    role = Role.RadioButton
                                    selected = chosen
                                    stateDescription = if (chosen) "已选中" else "未选中"
                                    if (enabled && !changed) {
                                        onClick("选择颜色 $hex") {
                                            color = hex
                                            true
                                        }
                                    } else {
                                        disabled()
                                    }
                                }.selectable(
                                    selected = chosen,
                                    enabled = enabled && !changed,
                                    role = Role.RadioButton,
                                    onClick = { color = hex },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                Modifier
                                    .size(40.dp)
                                    .background(choice, CircleShape)
                                    .border(
                                        if (chosen) 2.dp else 0.dp,
                                        if (chosen) MaterialTheme.colorScheme.primary else Color.Transparent,
                                        CircleShape,
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (chosen) {
                                    Text(
                                        "✓",
                                        color =
                                            if (choice.luminance() >
                                                0.179f
                                            ) {
                                                Color.Black
                                            } else {
                                                Color.White
                                            },
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                }
                            }
                        }
                    }
                }
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Row(
                        Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(24.dp).background(swatch ?: MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp)))
                        Text(name.ifBlank { "标签预览" }, style = MaterialTheme.typography.titleSmall)
                    }
                }
                OutlinedTextField(
                    value = color,
                    onValueChange = { color = it },
                    enabled = enabled && !changed,
                    label = { Text("自定义颜色（可空）") },
                    supportingText = { Text("可选用上方色板；留空使用默认色") },
                    isError = !colorValid,
                    trailingIcon =
                        if (swatch == null) {
                            null
                        } else {
                            val tinted = swatch
                            { Box(Modifier.size(20.dp).background(tinted, RoundedCornerShape(6.dp))) }
                        },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("当前账本已用于 $usageCount 笔账单", style = MaterialTheme.typography.bodySmall)
                if (accountMode) {
                    Text("排序使用列表中的上移/下移；移动分组会保留历史账单标签。", style = MaterialTheme.typography.bodySmall)
                    if (changed) Text("标签或分组已变化，请关闭后重新编辑或重选分组。", color = MaterialTheme.colorScheme.error)
                } else {
                    OutlinedTextField(
                        order,
                        { order = it },
                        label = { Text("排序值（留空为0）") },
                        singleLine = true,
                        enabled = enabled && !changed,
                        isError = !orderValid,
                    )
                }
                if (!enabled) Text("当前不可修改标签：正在保存或等待同步确认。", style = MaterialTheme.typography.bodySmall)
                if (!nameValid && name.isNotEmpty()) Text("标签名称需为 1 至 100 个字符。", color = MaterialTheme.colorScheme.error)
                if (!orderValid) Text("排序值需为有效整数。", color = MaterialTheme.colorScheme.error)
                if (!colorValid) Text("颜色值超过 32 字节，请缩短后再保存。", color = MaterialTheme.colorScheme.error)
            }
        },
    )
}
