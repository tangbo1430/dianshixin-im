package dev.icos.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.icos.app.data.local.FinanceAccount
import dev.icos.app.data.local.FinanceCategory
import java.util.UUID

internal enum class DecimalKeyPosition { BEFORE_ZERO, AFTER_ZERO }

internal enum class DeleteKeyPosition { LEFT, RIGHT }

internal enum class CategoryPickerStyle { EXPANDED, POPUP }

internal enum class CategoryDisplay { GRID, LIST }

internal enum class AmountInputMode { CALCULATOR, NUMERIC }

internal data class RecordEfficiencySettings(
    val amountInputMode: AmountInputMode = AmountInputMode.CALCULATOR,
    val rememberSearchFilters: Boolean = true,
    val decimalPlaces: Int = 6,
    val decimalKeyPosition: DecimalKeyPosition = DecimalKeyPosition.BEFORE_ZERO,
    val deleteKeyPosition: DeleteKeyPosition = DeleteKeyPosition.RIGHT,
    val categoryPickerStyle: CategoryPickerStyle = CategoryPickerStyle.POPUP,
    val categoryDisplay: CategoryDisplay = CategoryDisplay.GRID,
    val remarkSuggestions: Boolean = true,
    val defaultKind: EntryKind = EntryKind.EXPENSE,
    val defaultAccountId: UUID? = null,
)

@Composable
internal fun RecordEfficiencySettingsPanel(
    settings: RecordEfficiencySettings,
    onChange: (RecordEfficiencySettings) -> Unit,
    categories: List<FinanceCategory>,
    owner: UUID?,
    accounts: List<FinanceAccount>,
    defaultDraft: RecordDraft?,
    enabled: Boolean,
    guest: Boolean,
    onEdit: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        RecordEfficiencyPreview(settings, categories, owner)
        RecordDefaultSettingsCard(
            settings,
            accounts,
            defaultDraft?.let { if (it.kind == EntryKind.TRANSFER) it.fromId else it.accountId },
            onChange,
        )
        Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("输入设置", style = MaterialTheme.typography.titleLarge)
                Text("金额输入方式", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(settings.amountInputMode == AmountInputMode.CALCULATOR, {
                        onChange(settings.copy(amountInputMode = AmountInputMode.CALCULATOR))
                    }, label = { Text("计算器") })
                    FilterChip(settings.amountInputMode == AmountInputMode.NUMERIC, {
                        onChange(settings.copy(amountInputMode = AmountInputMode.NUMERIC))
                    }, label = { Text("数字") })
                }
                Text("计算器：使用页面内数字键盘；数字：在金额框中直接使用系统小数键盘。", style = MaterialTheme.typography.bodySmall)
                Text("金额键盘精度（最多小数位）", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    (0..6).forEach { places ->
                        FilterChip(
                            settings.decimalPlaces == places,
                            { onChange(settings.copy(decimalPlaces = places)) },
                            label = { Text("$places") },
                        )
                    }
                }
                Text("小数点位置", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(settings.decimalKeyPosition == DecimalKeyPosition.BEFORE_ZERO, {
                        onChange(settings.copy(decimalKeyPosition = DecimalKeyPosition.BEFORE_ZERO))
                    }, label = { Text("0 前") })
                    FilterChip(settings.decimalKeyPosition == DecimalKeyPosition.AFTER_ZERO, {
                        onChange(settings.copy(decimalKeyPosition = DecimalKeyPosition.AFTER_ZERO))
                    }, label = { Text("0 后") })
                }
                Text("删除键位置", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(settings.deleteKeyPosition == DeleteKeyPosition.LEFT, {
                        onChange(settings.copy(deleteKeyPosition = DeleteKeyPosition.LEFT))
                    }, label = { Text("左侧") })
                    FilterChip(settings.deleteKeyPosition == DeleteKeyPosition.RIGHT, {
                        onChange(settings.copy(deleteKeyPosition = DeleteKeyPosition.RIGHT))
                    }, label = { Text("右侧") })
                }
                Text("分类显示方式", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(settings.categoryDisplay == CategoryDisplay.GRID, {
                        onChange(settings.copy(categoryDisplay = CategoryDisplay.GRID))
                    }, label = { Text("网格") })
                    FilterChip(settings.categoryDisplay == CategoryDisplay.LIST, {
                        onChange(settings.copy(categoryDisplay = CategoryDisplay.LIST))
                    }, label = { Text("列表") })
                }
                Text("列表沿用下方选择布局；网格始终原地显示。", style = MaterialTheme.typography.bodySmall)
                Text("分类选择布局", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(settings.categoryPickerStyle == CategoryPickerStyle.EXPANDED, {
                        onChange(settings.copy(categoryPickerStyle = CategoryPickerStyle.EXPANDED))
                    }, label = { Text("原地展开") })
                    FilterChip(settings.categoryPickerStyle == CategoryPickerStyle.POPUP, {
                        onChange(settings.copy(categoryPickerStyle = CategoryPickerStyle.POPUP))
                    }, label = { Text("弹层选择") })
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("历史备注推荐", style = MaterialTheme.typography.titleMedium)
                        Text("从当前账本的同类型账单推荐，点击后填入备注。", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(
                        settings.remarkSuggestions,
                        { onChange(settings.copy(remarkSuggestions = it)) },
                        modifier = Modifier.semantics { contentDescription = "历史备注推荐" },
                    )
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("记住筛选与排序", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (guest) {
                                "下次打开账单沿用上次筛选；关闭后已保存的条件不再自动套用。"
                            } else {
                                "筛选与排序仅在本机游客模式下记住；账号数据不保存筛选条件。"
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (guest) {
                        Switch(
                            settings.rememberSearchFilters,
                            { onChange(settings.copy(rememberSearchFilters = it)) },
                            modifier = Modifier.semantics { contentDescription = "记住筛选与排序" },
                        )
                    }
                }
                Text("精度设置仅限制新输入；已有金额原样保留，降低精度不会自动舍入。", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (defaultDraft != null) {
            NextRecordPreviewCard(defaultDraft, categories, accounts, owner, settings.decimalPlaces, enabled, onEdit)
        } else {
            Text("打开账本后显示下一条记账预览。", style = MaterialTheme.typography.bodySmall)
        }
    }
}

internal fun amountKeyboardAppend(
    value: String,
    key: String,
    decimalPlaces: Int,
): String? {
    if (decimalPlaces !in 0..6 || value.length >= 32) return null
    if (key == ".") {
        if (decimalPlaces == 0 || '.' in value) return null
        return if (value.isEmpty()) "0." else "$value."
    }
    if (key.length != 1 || key[0] !in '0'..'9') return null
    val fractionalDigits = value.substringAfter('.', "").length.takeIf { '.' in value } ?: 0
    if ('.' in value && fractionalDigits >= decimalPlaces) return null
    return value + key
}

internal fun amountKeyboardDelete(value: String): String = value.dropLast(1)

/**
 * Whole-field edit from the system decimal keyboard: digits and at most one `.`, at most 32 characters,
 * and no more fractional digits than [decimalPlaces]. `null` rejects the edit and keeps the previous text.
 */
internal fun amountNumericEdit(
    value: String,
    decimalPlaces: Int,
): String? {
    if (value.length > 32 || decimalPlaces !in 0..6) return null
    if (value.any { it !in '0'..'9' && it != '.' }) return null
    val points = value.count { it == '.' }
    if (points > 1 || points == 1 && decimalPlaces == 0) return null
    if ('.' in value && value.substringAfter('.').length > decimalPlaces) return null
    return value
}

internal fun RecordEfficiencySettings.normalized(): RecordEfficiencySettings = copy(decimalPlaces = decimalPlaces.coerceIn(0, 6))

@Composable
internal fun RecordAmountInput(
    label: String,
    value: String,
    enabled: Boolean,
    settings: RecordEfficiencySettings,
    showKeyboard: Boolean = true,
    income: Boolean? = null,
    active: Boolean = true,
    onActivate: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    onChange: (String) -> Unit,
) {
    // Numeric mode uses the system decimal IME, so it must not grab focus and pop the keyboard on open.
    val numeric = settings.amountInputMode == AmountInputMode.NUMERIC
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (active && !numeric) focusRequester.requestFocus() }
    val fractionalDigits = value.substringAfter('.', "").length.takeIf { '.' in value } ?: 0
    val textColor =
        income?.let {
            amountColor(LocalAppearanceSettings.current.amountColors, it, MaterialTheme.colorScheme.background.luminance() < 0.5f)
        } ?: MaterialTheme.colorScheme.onSurface
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TextField(
            value = value,
            onValueChange =
                if (numeric) {
                    { next -> amountNumericEdit(next, settings.decimalPlaces)?.let(onChange) }
                } else {
                    {}
                },
            label = { Text(label) },
            placeholder = {
                Text(
                    "0.00",
                    style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.Bold, fontFamily = amountFontFamily()),
                    color = textColor,
                )
            },
            textStyle = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.Bold, fontFamily = amountFontFamily()),
            singleLine = true,
            enabled = enabled,
            readOnly = !numeric,
            keyboardOptions = if (numeric) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onFocusChanged {
                        if (it.isFocused) onActivate?.invoke()
                    }.semantics { selected = active },
            shape = RoundedCornerShape(16.dp),
            colors =
                TextFieldDefaults.colors(
                    focusedTextColor = textColor,
                    unfocusedTextColor = textColor,
                    focusedContainerColor = summaryCardColor(),
                    unfocusedContainerColor = summaryCardColor(),
                    focusedLabelColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedLabelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                    focusedIndicatorColor = if (active) textColor else androidx.compose.ui.graphics.Color.Transparent,
                    unfocusedIndicatorColor = if (active) textColor else androidx.compose.ui.graphics.Color.Transparent,
                ),
            trailingIcon = {
                if (value.isNotEmpty()) TextButton(onClick = { onChange("") }, enabled = enabled) { Text("清空") }
            },
        )
        if (showKeyboard && !numeric) AmountKeyboard(value, enabled, settings, onChange = onChange)
        if (fractionalDigits > settings.decimalPlaces) {
            Text(
                "当前金额有 $fractionalDigits 位小数；已保留原值，请删除多余位后再输入。",
                color = MaterialTheme.colorScheme.tertiary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
internal fun AmountKeyboard(
    value: String,
    enabled: Boolean,
    settings: RecordEfficiencySettings,
    confirmButton: (@Composable () -> Unit)? = null,
    confirmBelow: Boolean = false,
    onChange: (String) -> Unit,
) {
    AmountKeyboardLayout(settings, confirmButton, confirmBelow) { key, modifier ->
        AmountKeyboardKey(key, value, enabled, settings, modifier, onChange)
    }
}

@Composable
private fun AmountKeyboardKey(
    key: String,
    value: String,
    enabled: Boolean,
    settings: RecordEfficiencySettings,
    modifier: Modifier,
    onChange: (String) -> Unit,
) {
    val next =
        if (key ==
            "⌫"
        ) {
            amountKeyboardDelete(value).takeIf { value.isNotEmpty() }
        } else {
            amountKeyboardAppend(value, key, settings.decimalPlaces)
        }
    Button(
        onClick = { next?.let(onChange) },
        enabled = enabled && next != null,
        modifier =
            modifier.height(48.dp).semantics {
                contentDescription =
                    if (key ==
                        "⌫"
                    ) {
                        "删除一位金额"
                    } else {
                        key
                    }
            },
        shape = RoundedCornerShape(8.dp),
        contentPadding = PaddingValues(0.dp),
        colors =
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
    ) {
        Text(key, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
    }
}
