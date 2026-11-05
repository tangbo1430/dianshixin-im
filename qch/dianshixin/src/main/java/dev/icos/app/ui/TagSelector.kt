package dev.icos.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.icos.app.data.local.FinanceSnapshot
import java.util.UUID

@Composable
internal fun TagSelector(
    snapshot: FinanceSnapshot,
    selected: List<UUID>,
    enabled: Boolean,
    onConfirm: (List<UUID>) -> Unit,
    onQuickCreate: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, enabled = enabled) {
        Text(if (selected.isEmpty()) "选择标签" else "标签 ${selected.size}")
    }
    if (!open) return
    var search by remember { mutableStateOf("") }
    var quickName by remember { mutableStateOf("") }
    var chosen by remember(selected) { mutableStateOf(selected.toSet()) }
    val usageCounts =
        remember(snapshot.records) {
            snapshot.records
                .flatMap { it.tagIds }
                .groupingBy { it }
                .eachCount()
        }
    val dismiss = rememberEditorDismiss(chosen != selected.toSet() || quickName.isNotBlank(), enabled = true) { open = false }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("选择标签") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(search, { search = it }, label = { Text("搜索标签") }, singleLine = true, enabled = enabled)
                val matchable =
                    snapshot.tags.filter {
                        it.name.contains(search, ignoreCase = true) && (it.archivedAt == null || it.id in chosen)
                    }
                if (snapshot.tags.isEmpty()) {
                    Text(
                        "暂无标签，可在下方快速创建。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (matchable.isEmpty()) {
                    Text("没有匹配的标签。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                snapshot.tagGroups.sortedBy { it.sortOrder }.forEach { group ->
                    val tags =
                        matchable
                            .filter { it.groupId == group.id }
                            .sortedWith(compareBy({ it.sortOrder }, { it.name.lowercase() }))
                    if (tags.isNotEmpty()) {
                        Text(group.name, style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            tags.forEach { tag ->
                                FilterChip(
                                    selected = tag.id in chosen,
                                    enabled = enabled,
                                    onClick = {
                                        chosen =
                                            if (tag.id in
                                                chosen
                                            ) {
                                                chosen - tag.id
                                            } else if (tag.archivedAt == null) {
                                                chosen + tag.id
                                            } else {
                                                chosen
                                            }
                                    },
                                    label = {
                                        val usage = usageCounts[tag.id] ?: 0
                                        Text(
                                            listOfNotNull(
                                                tag.name,
                                                if (usage > 0) "本账本 $usage 笔" else null,
                                                if (tag.archivedAt == null) null else "已归档",
                                            ).joinToString(" · "),
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("已选 ${chosen.size} 个", Modifier.weight(1f))
                    TextButton(enabled = enabled && chosen.isNotEmpty(), onClick = { chosen = emptySet() }) { Text("清空") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        quickName,
                        { quickName = it },
                        label = { Text("快速创建") },
                        singleLine = true,
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        enabled = enabled && quickName.isNotBlank(),
                        onClick = {
                            onQuickCreate(quickName)
                            quickName = ""
                        },
                    ) { AppIcon(AppSymbol.ADD); Text("创建") }
                }
            }
        },
        confirmButton = {
            Button(enabled = enabled, onClick = {
                onConfirm(chosen.toList())
                open = false
            }) { Text("确认（${chosen.size}）") }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("取消") } },
    )
}
