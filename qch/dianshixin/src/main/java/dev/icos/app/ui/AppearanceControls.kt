package dev.icos.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.icos.app.R
import kotlin.math.roundToInt

@Composable
internal fun AppearanceGroup(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
    }
}

@Composable
internal fun AppearanceSaveStatus(state: AppearanceState) {
    when {
        state.saveFailed -> {
            Text("保存失败", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
        }

        state.saving -> {
            Text("正在保存…", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        state.saveCompleted -> {
            AppIcon(AppSymbol.CHECK, "已保存", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("已自动保存", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun ThemeModeOptions(
    selected: ThemePreference,
    accent: Color,
    onSelect: (ThemePreference) -> Unit,
) {
    val modes =
        listOf(
            ThemePreference.LIGHT to ("浅色" to ThemeModeGlyph.SUN),
            ThemePreference.DARK to ("深色" to ThemeModeGlyph.MOON),
            ThemePreference.SYSTEM to ("跟随系统" to ThemeModeGlyph.MONITOR),
        )
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        modes.forEach { (mode, label) ->
            val isSelected = selected == mode
            AppearanceOptionSurface(isSelected, accent, { onSelect(mode) }, Modifier.weight(1f).fillMaxHeight()) {
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Image(
                        painter = painterResource(label.second.drawable),
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                        colorFilter =
                            androidx.compose.ui.graphics.ColorFilter.tint(
                                if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                    )
                    Text(label.first, style = MaterialTheme.typography.bodySmall)
                }
                AppearanceSelectionMark(isSelected, accent, Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-4).dp))
            }
        }
    }
}

@Composable
internal fun ThemeColorOptions(
    selected: ThemeColorPreference,
    dark: Boolean,
    onSelect: (ThemeColorPreference) -> Unit,
) {
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ThemeColorPreference.entries.forEach { color ->
            val swatch = themeAccent(color, dark)
            val isSelected = selected == color
            Surface(
                selected = isSelected,
                onClick = { onSelect(color) },
                modifier =
                    Modifier.size(48.dp).semantics {
                        contentDescription =
                            when (color) {
                                ThemeColorPreference.GOLD -> "金黄"
                                ThemeColorPreference.CORAL -> "珊瑚"
                                ThemeColorPreference.GREEN -> "绿色"
                                ThemeColorPreference.BLUE -> "蓝色"
                                ThemeColorPreference.PURPLE -> "紫色"
                                ThemeColorPreference.PINK -> "粉色"
                            }
                        role = Role.RadioButton
                    },
                shape = RoundedCornerShape(10.dp),
                color = swatch,
                border = if (isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.onSurface) else null,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (isSelected) {
                        AppIcon(
                            AppSymbol.CHECK,
                            null,
                            Modifier.size(18.dp),
                            tint =
                                if (swatch.luminance() >
                                    0.5f
                                ) {
                                    Color(0xFF242620)
                                } else {
                                    Color.White
                                },
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun AmountColorOptions(
    selected: AmountColorPreference,
    preview: AppearancePreview?,
    onSelect: (AmountColorPreference) -> Unit,
) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val expense = preview?.let { "-${ledgerAmount(it.expense, it.currency)}" } ?: "—"
    val income = preview?.let { "+${ledgerAmount(it.income, it.currency)}" } ?: "—"
    val minWidth = 96.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AmountColorPreference.entries.forEach { preference ->
            val isSelected = selected == preference
            AppearanceOptionSurface(
                isSelected,
                amountColor(preference, false, dark) ?: MaterialTheme.colorScheme.primary,
                { onSelect(preference) },
                Modifier.widthIn(min = minWidth).weight(1f),
            ) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        AppearanceSelectionMark(isSelected, accentFor(preference, dark))
                        Text(
                            when (preference) {
                                AmountColorPreference.DEFAULT -> "默认红绿"
                                AmountColorPreference.RED_GREEN -> "红支绿收"
                                AmountColorPreference.BLUE_ORANGE -> "蓝支橙收"
                            },
                            Modifier.weight(1f).semantics { contentDescription = amountColorLabel(preference) },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    AmountSample(expense, false, preference, dark)
                    AmountSample(income, true, preference, dark)
                }
            }
        }
    }
}

@Composable
internal fun FontScaleSlider(
    scale: Float,
    accent: Color,
    onScale: (Float) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Aa", style = MaterialTheme.typography.bodySmall)
        Slider(
            value = FONT_SCALES.indexOf(scale).coerceAtLeast(0).toFloat(),
            onValueChange = { onScale(FONT_SCALES[it.roundToInt().coerceIn(FONT_SCALES.indices)]) },
            modifier =
                Modifier.weight(1f).semantics {
                    contentDescription = "字号"
                    stateDescription = "${(scale * 100).roundToInt()}%"
                },
            valueRange = 0f..FONT_SCALES.lastIndex.toFloat(),
            steps = FONT_SCALES.size - 2,
            colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent),
        )
        Text(
            "${(scale * 100).roundToInt()}%",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("Aa", style = MaterialTheme.typography.headlineSmall)
    }
}

@Composable
internal fun NumericFontOptions(
    selected: NumericFontPreference,
    sample: String,
    accent: Color,
    onSelect: (NumericFontPreference) -> Unit,
) {
    val minWidth = 96.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        NumericFontPreference.entries.forEach { preference ->
            val isSelected = selected == preference
            AppearanceOptionSurface(isSelected, accent, { onSelect(preference) }, Modifier.widthIn(min = minWidth).weight(1f)) {
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        sample,
                        style =
                            MaterialTheme.typography.bodySmall.copy(
                                fontFamily = numericFontFamily(preference),
                                fontWeight = FontWeight.Medium,
                            ),
                        modifier = Modifier.fillMaxWidth().padding(end = 20.dp),
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        numericFontLabel(preference),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AppearanceSelectionMark(isSelected, accent, Modifier.align(Alignment.TopEnd))
            }
        }
    }
}

@Composable
internal fun IconStyleOptions(
    selected: IconStylePreference,
    accent: Color,
    onSelect: (IconStylePreference) -> Unit,
) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val minWidth = 144.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconStylePreference.entries.forEach { style ->
            val isSelected = selected == style
            AppearanceOptionSurface(isSelected, accent, { onSelect(style) }, Modifier.widthIn(min = minWidth).weight(1f)) {
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(AppSymbol.BOOK, AppSymbol.STATISTICS, AppSymbol.WALLET, AppSymbol.SAVING).forEachIndexed { index, symbol ->
                            AppIcon(
                                symbol,
                                modifier = Modifier.size(18.dp),
                                tint =
                                    if (style == IconStylePreference.COLOR) {
                                        coloredIconTints(dark)[index]
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                            )
                        }
                    }
                    Text(
                        iconStyleLabel(style),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AppearanceSelectionMark(isSelected, accent, Modifier.align(Alignment.TopEnd))
            }
        }
    }
}

@Composable
private fun AmountSample(
    text: String,
    income: Boolean,
    preference: AmountColorPreference,
    dark: Boolean,
) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium.copy(fontFamily = amountFontFamily(), fontWeight = FontWeight.Medium),
        color = amountColor(preference, income, dark) ?: MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun AppearanceOptionSurface(
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    Surface(
        selected = selected,
        onClick = onClick,
        // M3's selected Surface leaves role unset; the radio role names the single-choice group.
        modifier = modifier.semantics { role = Role.RadioButton },
        shape = RoundedCornerShape(14.dp),
        color = if (selected) accent.copy(alpha = if (dark) 0.20f else 0.12f) else MaterialTheme.colorScheme.surface,
        border = BorderStroke(if (selected) 1.5.dp else 1.dp, if (selected) accent else MaterialTheme.colorScheme.outlineVariant),
    ) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp), content = content)
    }
}

@Composable
private fun AppearanceSelectionMark(
    selected: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    if (selected) {
        Box(modifier.size(16.dp).background(accent, CircleShape), contentAlignment = Alignment.Center) {
            AppIcon(
                AppSymbol.CHECK,
                null,
                Modifier.size(12.dp),
                tint = if (accent.luminance() > 0.5f) Color(0xFF242620) else Color.White,
            )
        }
    } else {
        Box(modifier.size(16.dp).border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape))
    }
}

private fun amountColorLabel(preference: AmountColorPreference): String =
    when (preference) {
        AmountColorPreference.DEFAULT -> "默认红绿"
        AmountColorPreference.RED_GREEN -> "红色支出 · 绿色收入"
        AmountColorPreference.BLUE_ORANGE -> "蓝色支出 · 橙色收入"
    }

private fun numericFontLabel(preference: NumericFontPreference): String =
    when (preference) {
        NumericFontPreference.SYSTEM -> "系统字体"
        NumericFontPreference.MONOSPACE -> "等宽字体"
        NumericFontPreference.SERIF -> "衬线字体"
    }

private fun iconStyleLabel(preference: IconStylePreference): String =
    when (preference) {
        IconStylePreference.LINEAR -> "线性图标"
        IconStylePreference.COLOR -> "彩色图标"
    }

private fun accentFor(
    preference: AmountColorPreference,
    dark: Boolean,
): Color = amountColor(preference, false, dark) ?: ThemeColorPreference.GOLD.light

private fun coloredIconTints(dark: Boolean): List<Color> =
    listOf(
        themeAccent(ThemeColorPreference.GOLD, dark),
        themeAccent(ThemeColorPreference.GREEN, dark),
        themeAccent(ThemeColorPreference.BLUE, dark),
        themeAccent(ThemeColorPreference.CORAL, dark),
    )

/** Mode-specific generated icons for the appearance options. */
private enum class ThemeModeGlyph(
    val drawable: Int,
) {
    SUN(R.drawable.icon_sun),
    MOON(R.drawable.icon_moon),
    MONITOR(R.drawable.icon_monitor),
}
