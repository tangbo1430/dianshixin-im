package dev.icos.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.icos.app.data.local.FinanceCategory
import java.util.UUID

@Composable
internal fun CategorySelector(
    owner: UUID,
    categories: List<FinanceCategory>,
    selected: UUID?,
    revision: Int,
    emptyLabel: String,
    enabled: Boolean,
    pickerStyle: CategoryPickerStyle = CategoryPickerStyle.POPUP,
    display: CategoryDisplay = CategoryDisplay.LIST,
    onSelect: (UUID?) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    key(revision, pickerStyle, display) {
        var expanded by remember { mutableStateOf(false) }
        val selectedCategory = remember(categories, selected) { categories.firstOrNull { it.id == selected } }
        val gridRows = remember(categories) { categories.chunked(4) }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (display == CategoryDisplay.LIST) {
                EditorFieldRow(
                    label = "分类",
                    value = selectedCategory?.name ?: if (selected == null) emptyLabel else "原分类不可用",
                    enabled = enabled,
                    onClick = { expanded = !expanded },
                    leading = {
                        selectedCategory?.let { category ->
                            CategoryStyleBadge(rememberCategoryStyle(context, owner, category, revision))
                        }
                    },
                    trailing = { AppIcon(AppSymbol.CHEVRON, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                )
            }
            if (display == CategoryDisplay.GRID) {
                LazyColumn(
                    Modifier.fillMaxWidth().heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(gridRows, key = { row -> row.first().id }) { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            row.forEach { category ->
                                val isSelected = category.id == selected
                                val style = rememberCategoryStyle(context, owner, category, revision)
                                Surface(
                                    onClick = { onSelect(category.id) },
                                    modifier = Modifier.weight(1f).heightIn(min = 72.dp).semantics { this.selected = isSelected },
                                    enabled = enabled,
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                                    border = if (isSelected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)) else null,
                                ) {
                                    Column(
                                        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        CategoryStyleBadge(style, Modifier.size(30.dp))
                                        Text(category.name, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, maxLines = 2)
                                    }
                                }
                            }
                            repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            } else if (pickerStyle == CategoryPickerStyle.POPUP) {
                if (expanded && enabled) {
                    AlertDialog(
                        onDismissRequest = { expanded = false },
                        title = { Text("选择分类") },
                        text = {
                            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                                items(categories, key = { it.id }) { category ->
                                    val style = rememberCategoryStyle(context, owner, category, revision)
                                    DropdownMenuItem(
                                        text = {
                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                CategoryStyleBadge(style)
                                                Text(category.name)
                                            }
                                        },
                                        onClick = {
                                            expanded = false
                                            onSelect(category.id)
                                        },
                                    )
                                }
                            }
                        },
                        confirmButton = { TextButton(onClick = { expanded = false }) { Text("取消") } },
                    )
                }
            } else if (expanded) {
                LazyColumn(
                    Modifier.fillMaxWidth().padding(start = 8.dp).heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(categories, key = { it.id }) { category ->
                        val style = rememberCategoryStyle(context, owner, category, revision)
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = MaterialTheme.shapes.small,
                            enabled = enabled,
                            onClick = {
                                expanded = false
                                onSelect(category.id)
                            },
                        ) {
                            Row(
                                Modifier.padding(6.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CategoryStyleBadge(style)
                                Text(
                                    category.name,
                                    color =
                                        if (selected ==
                                            category.id
                                        ) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun CategoryColorEditor(
    category: FinanceCategory,
    enabled: Boolean,
    busy: Boolean,
    stale: Boolean,
    error: String?,
    onSave: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var color by remember(category.id, category.localRevision) { mutableStateOf(category.color) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("${category.name} · 同步颜色") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("颜色会保存到账号，等待服务器确认后显示。图标与显示风格仍使用本机设置。")
                FilterChip(
                    selected = color == null,
                    enabled = enabled && !stale,
                    onClick = { color = null },
                    label = { Text("按收支方向使用默认色") },
                )
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CategoryStyles.colors().forEach { hex ->
                        FilterChip(
                            selected = color.equals(hex, ignoreCase = true),
                            enabled = enabled && !stale,
                            onClick = { color = hex },
                            label = { Text("●", color = Color(android.graphics.Color.parseColor(hex))) },
                        )
                    }
                }
                val previewColor =
                    color?.takeIf { it.matches(Regex("#[0-9A-Fa-f]{6}")) }
                        ?: CategoryStyles.default(category).color
                CategoryStyleBadge(CategoryStyles.default(category).copy(color = previewColor))
                if (stale) Text("分类、父级或账本已变化，请关闭后重新确认。", color = MaterialTheme.colorScheme.error)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = enabled && !stale && color != category.color,
                onClick = { onSave(color) },
            ) { Text("保存颜色") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
internal fun CategoryStyleEditor(
    owner: UUID,
    category: FinanceCategory,
    onSave: (CategoryStyle) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val original = remember(owner, category.id) { CategoryStyles.read(context, owner, category) }
    var style by remember(owner, category.id) { mutableStateOf(original) }
    var confirmExit by remember { mutableStateOf(false) }
    val close = { if (style == original) onDismiss() else confirmExit = true }
    EditorPage(
        onDismissRequest = close,
        title = { Text("图标与风格") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(min = 0.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(category.name, style = MaterialTheme.typography.titleMedium)
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CategoryStyleBadge(style)
                        Text("实时预览 · ${category.name}")
                    }
                }
                Text(if (category.usesCanonicalColor) "图标与显示风格保存在此设备；同步颜色请从分类管理设置。" else "图标、颜色和显示风格仅保存在此设备。")
                TextButton(onClick = { style = CategoryStyles.default(category) }) { Text("恢复默认样式") }
                Text("内置图标", style = MaterialTheme.typography.titleSmall)
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    CategoryStyles.icons().forEach { option ->
                        FilterChip(
                            selected = style.icon == option.key,
                            onClick = { style = style.copy(icon = option.key) },
                            label = {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    AppIcon(option.symbol)
                                    Text(option.label, style = MaterialTheme.typography.labelSmall)
                                }
                            },
                        )
                    }
                }
                Text("内置颜色", style = MaterialTheme.typography.titleSmall)
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    CategoryStyles.colors().forEach { hex ->
                        val color = Color(android.graphics.Color.parseColor(hex))
                        FilterChip(
                            selected = style.color.equals(hex, ignoreCase = true),
                            enabled = !category.usesCanonicalColor,
                            onClick = { style = style.copy(color = hex) },
                            label = { Text("●", color = color) },
                        )
                    }
                }
                Text("显示风格", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    CategoryStyles.treatments().forEach { treatment ->
                        FilterChip(selected = style.treatment == treatment, onClick = {
                            style = style.copy(treatment = treatment)
                        }, label = {
                            Text(
                                when (treatment) {
                                    "SOLID" -> "实色"
                                    "OUTLINE" -> "描边"
                                    else -> "柔和"
                                },
                            )
                        })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = style != original, onClick = {
                if (style == CategoryStyles.default(category)) onReset() else onSave(style)
                onDismiss()
            }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = close) { Text("取消") }
        },
    )
    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text("放弃样式修改？") },
            text = { Text("尚未保存的图标、颜色或显示风格将会丢弃。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmExit = false
                    onDismiss()
                }) { Text("放弃修改") }
            },
            dismissButton = {
                TextButton(onClick = { confirmExit = false }) { Text("继续编辑") }
            },
        )
    }
}
