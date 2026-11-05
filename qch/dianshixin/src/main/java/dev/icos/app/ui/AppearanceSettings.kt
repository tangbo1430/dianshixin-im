package dev.icos.app.ui

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import dev.icos.app.data.local.FinanceSnapshot
import java.util.concurrent.Executors

internal enum class ThemePreference {
    SYSTEM,
    LIGHT,
    DARK,
}

internal enum class AmountColorPreference {
    DEFAULT,
    RED_GREEN,
    BLUE_ORANGE,
}

internal enum class NumericFontPreference {
    SYSTEM,
    MONOSPACE,
    SERIF,
}

/** Accent palette; GOLD is the existing brand accent and stays the default. */
internal enum class ThemeColorPreference(
    val light: Color,
    val dark: Color,
) {
    GOLD(Color(0xFFE9C94E), Color(0xFFE9C94E)),
    CORAL(Color(0xFFE98B79), Color(0xFFF2A796)),
    GREEN(Color(0xFF4F8A63), Color(0xFF8FC4A2)),
    BLUE(Color(0xFF4A7AD8), Color(0xFF93B4F0)),
    PURPLE(Color(0xFF8367D8), Color(0xFFB7A3F0)),
    PINK(Color(0xFFE4879C), Color(0xFFF2AFC0)),
}

internal enum class IconStylePreference {
    LINEAR,
    COLOR,
}

internal data class AppearanceSettings(
    val theme: ThemePreference = ThemePreference.SYSTEM,
    val amountColors: AmountColorPreference = AmountColorPreference.DEFAULT,
    val fontScale: Float = 1f,
    val numericFont: NumericFontPreference = NumericFontPreference.SYSTEM,
    val themeColor: ThemeColorPreference = ThemeColorPreference.GOLD,
    val iconStyle: IconStylePreference = IconStylePreference.LINEAR,
) {
    fun preferenceValues(): List<String> =
        listOf(theme.name, amountColors.name, fontScale.toString(), numericFont.name, themeColor.name, iconStyle.name)

    companion object {
        fun fromPreferenceValues(values: List<String>): AppearanceSettings {
            val scale =
                values.getOrNull(2)?.toFloatOrNull()?.takeIf { it in FONT_SCALES } ?: 1f
            return AppearanceSettings(
                theme =
                    values.getOrNull(0)?.let { raw -> ThemePreference.entries.firstOrNull { it.name == raw } }
                        ?: ThemePreference.SYSTEM,
                amountColors =
                    values.getOrNull(1)?.let { raw -> AmountColorPreference.entries.firstOrNull { it.name == raw } }
                        ?: AmountColorPreference.DEFAULT,
                fontScale = scale,
                numericFont =
                    values.getOrNull(3)?.let { raw -> NumericFontPreference.entries.firstOrNull { it.name == raw } }
                        ?: NumericFontPreference.SYSTEM,
                themeColor =
                    values.getOrNull(4)?.let { raw -> ThemeColorPreference.entries.firstOrNull { it.name == raw } }
                        ?: ThemeColorPreference.GOLD,
                iconStyle =
                    values.getOrNull(5)?.let { raw -> IconStylePreference.entries.firstOrNull { it.name == raw } }
                        ?: IconStylePreference.LINEAR,
            )
        }
    }
}

internal val FONT_SCALES = listOf(0.9f, 1f, 1.1f, 1.2f)

internal data class AppearanceState(
    val settings: AppearanceSettings = AppearanceSettings(),
    val saving: Boolean = false,
    val saveFailed: Boolean = false,
    val saveCompleted: Boolean = false,
)

internal class AppearanceSettingsViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val preferences =
        application.getSharedPreferences("appearance_settings", Context.MODE_PRIVATE)
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var revision = 0

    @Volatile private var closed = false

    var state by mutableStateOf(AppearanceState(load()))
        private set

    fun update(settings: AppearanceSettings) {
        if (closed || (settings == state.settings && !state.saveFailed)) return
        val currentRevision = ++revision
        val previouslyFailed = state.saveFailed
        state = AppearanceState(settings = settings, saving = true, saveFailed = previouslyFailed)
        executor.execute {
            val values = settings.preferenceValues()
            val saved =
                runCatching {
                    preferences
                        .edit()
                        .putString(KEY_THEME, values[0])
                        .putString(KEY_COLORS, values[1])
                        .putString(KEY_SCALE, values[2])
                        .putString(KEY_FONT, values[3])
                        .putString(KEY_THEME_COLOR, values[4])
                        .putString(KEY_ICON_STYLE, values[5])
                        .commit()
                }.getOrDefault(false)
            main.post {
                if (!closed && currentRevision == revision) {
                    state = AppearanceState(settings = settings, saveFailed = !saved, saveCompleted = saved)
                }
            }
        }
    }

    private fun load(): AppearanceSettings =
        AppearanceSettings.fromPreferenceValues(
            listOf(
                preferences.getString(KEY_THEME, null).orEmpty(),
                preferences.getString(KEY_COLORS, null).orEmpty(),
                preferences.getString(KEY_SCALE, null).orEmpty(),
                preferences.getString(KEY_FONT, null).orEmpty(),
                preferences.getString(KEY_THEME_COLOR, null).orEmpty(),
                preferences.getString(KEY_ICON_STYLE, null).orEmpty(),
            ),
        )

    override fun onCleared() {
        closed = true
        executor.shutdown()
        super.onCleared()
    }

    private companion object {
        const val KEY_THEME = "theme"
        const val KEY_COLORS = "amount_colors"
        const val KEY_SCALE = "font_scale"
        const val KEY_FONT = "numeric_font"
        const val KEY_THEME_COLOR = "theme_color"
        const val KEY_ICON_STYLE = "icon_style"
    }
}

internal val LocalAppearanceSettings = staticCompositionLocalOf { AppearanceSettings() }

/**
 * @param snapshot loaded ledger snapshot used by the live preview; null renders an unavailable state.
 * @param zone statistics timezone used for the preview month and record timestamps.
 */
@Composable
internal fun AppearanceSettingsPanel(
    state: AppearanceState,
    onSettingsChange: (AppearanceSettings) -> Unit,
    modifier: Modifier = Modifier,
    snapshot: FinanceSnapshot? = null,
    zone: String = "Asia/Shanghai",
) {
    val settings = state.settings
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val accent = themeAccent(settings.themeColor, dark)
    val preview = remember(snapshot, zone) { appearancePreview(snapshot, zone) }
    val fontSample = preview?.let { ledgerAmount(it.expense, it.currency) } ?: "0.00"
    Column(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { AppearanceSaveStatus(state) }
        if (state.saveFailed && state.saving) {
            Text("上次保存失败，正在保存…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        AppearancePreviewCard(settings, preview)
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AppearanceGroup("主题模式") {
                    ThemeModeOptions(settings.theme, accent) { onSettingsChange(settings.copy(theme = it)) }
                }
                AppearanceGroup("主题色") {
                    ThemeColorOptions(settings.themeColor, dark) { onSettingsChange(settings.copy(themeColor = it)) }
                }
                AppearanceGroup("收支配色") {
                    AmountColorOptions(settings.amountColors, preview) { onSettingsChange(settings.copy(amountColors = it)) }
                }
                AppearanceGroup("字体大小") {
                    FontScaleSlider(settings.fontScale, accent) { onSettingsChange(settings.copy(fontScale = it)) }
                }
                AppearanceGroup("数字字体") {
                    NumericFontOptions(settings.numericFont, fontSample, accent) { onSettingsChange(settings.copy(numericFont = it)) }
                }
                AppearanceGroup("图标风格") {
                    IconStyleOptions(settings.iconStyle, accent) { onSettingsChange(settings.copy(iconStyle = it)) }
                }
            }
        }
        if (state.saveFailed && !state.saving) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "保存失败；更改目前只用于本次打开。",
                    Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = { onSettingsChange(settings) }) { Text("重试") }
            }
        }
        Text("收支方向同时以文字标注；配色仅作辅助。", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun AppearanceProvider(
    settings: AppearanceSettings,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalAppearanceSettings provides settings, content = content)
}

internal fun amountColor(
    preference: AmountColorPreference,
    income: Boolean,
    darkTheme: Boolean,
): Color? =
    when (preference) {
        AmountColorPreference.DEFAULT -> {
            when {
                darkTheme && income -> Color(0xFF9DD3AD)
                darkTheme -> Color(0xFFFFB4A4)
                income -> Color(0xFF466C58)
                else -> Color(0xFFAE4935)
            }
        }

        AmountColorPreference.RED_GREEN -> {
            when {
                darkTheme && income -> Color(0xFF8FE3A9)
                darkTheme -> Color(0xFFFFB4AB)
                income -> Color(0xFF167D3E)
                else -> Color(0xFFB3261E)
            }
        }

        AmountColorPreference.BLUE_ORANGE -> {
            when {
                darkTheme && income -> Color(0xFFFFB77D)
                darkTheme -> Color(0xFFA8C7FA)
                income -> Color(0xFF9A4D00)
                else -> Color(0xFF0057A8)
            }
        }
    }

internal fun numericFontFamily(preference: NumericFontPreference): FontFamily =
    when (preference) {
        NumericFontPreference.SYSTEM -> FontFamily.Default
        NumericFontPreference.MONOSPACE -> FontFamily.Monospace
        NumericFontPreference.SERIF -> FontFamily.Serif
    }

@Composable
internal fun amountFontFamily(): FontFamily = numericFontFamily(LocalAppearanceSettings.current.numericFont)

@Composable
internal fun AmountText(
    text: String,
    modifier: Modifier = Modifier,
    income: Boolean? = null,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
) {
    val settings = LocalAppearanceSettings.current
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    Text(
        text,
        modifier = modifier,
        style = style.copy(fontFamily = amountFontFamily()),
        color =
            if (income == null) {
                MaterialTheme.colorScheme.onSurface
            } else {
                amountColor(settings.amountColors, income, isDark) ?: MaterialTheme.colorScheme.onSurface
            },
    )
}

internal fun scaledTypography(scale: Float): Typography {
    val base = Typography()
    return base.copy(
        displayLarge = base.displayLarge.scaled(scale),
        displayMedium = base.displayMedium.scaled(scale),
        displaySmall = base.displaySmall.scaled(scale),
        headlineLarge = base.headlineLarge.scaled(scale),
        headlineMedium = base.headlineMedium.scaled(scale),
        headlineSmall = base.headlineSmall.scaled(scale),
        titleLarge = base.titleLarge.scaled(scale),
        titleMedium = base.titleMedium.scaled(scale),
        titleSmall = base.titleSmall.scaled(scale),
        bodyLarge = base.bodyLarge.scaled(scale),
        bodyMedium = base.bodyMedium.scaled(scale),
        bodySmall = base.bodySmall.scaled(scale),
        labelLarge = base.labelLarge.scaled(scale),
        labelMedium = base.labelMedium.scaled(scale),
        labelSmall = base.labelSmall.scaled(scale),
    )
}

private fun TextStyle.scaled(scale: Float) =
    copy(
        fontSize = fontSize * scale,
        lineHeight = lineHeight * scale,
        letterSpacing = letterSpacing * scale,
    )
