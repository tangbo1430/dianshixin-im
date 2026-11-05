package dev.icos.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppearanceSettingsTest {
    @Test
    fun selectedAppearancePreferencesRestoreAfterRecreation() {
        val selected =
            AppearanceSettings(
                theme = ThemePreference.DARK,
                amountColors = AmountColorPreference.BLUE_ORANGE,
                fontScale = 1.2f,
                numericFont = NumericFontPreference.MONOSPACE,
                themeColor = ThemeColorPreference.BLUE,
                iconStyle = IconStylePreference.COLOR,
            )

        assertEquals(selected, AppearanceSettings.fromPreferenceValues(selected.preferenceValues()))
    }

    @Test
    fun invalidStoredAppearanceValuesFallBackToDefaults() {
        assertEquals(
            AppearanceSettings(),
            AppearanceSettings.fromPreferenceValues(listOf("UNKNOWN", "UNKNOWN", "4.0", "UNKNOWN", "UNKNOWN", "UNKNOWN")),
        )
    }

    @Test
    fun existingFourPreferencesKeepTheirValuesWhenNewOptionsAreAbsent() {
        assertEquals(
            AppearanceSettings(
                theme = ThemePreference.DARK,
                amountColors = AmountColorPreference.RED_GREEN,
                fontScale = 1.1f,
                numericFont = NumericFontPreference.SERIF,
            ),
            AppearanceSettings.fromPreferenceValues(listOf("DARK", "RED_GREEN", "1.1", "SERIF")),
        )
    }

    @Test
    fun everyAccentKeepsReadablePrimaryAndContainerTextInBothThemes() {
        fun contrast(
            first: Color,
            second: Color,
        ): Float {
            val firstLuminance = first.luminance()
            val secondLuminance = second.luminance()
            return (maxOf(firstLuminance, secondLuminance) + 0.05f) / (minOf(firstLuminance, secondLuminance) + 0.05f)
        }
        ThemeColorPreference.entries.forEach { accent ->
            listOf(false, true).forEach { dark ->
                val scheme = icosColorScheme(dark, accent)
                assertTrue("$accent dark=$dark primary", contrast(scheme.primary, scheme.onPrimary) >= 4.5f)
                assertTrue("$accent dark=$dark container", contrast(scheme.primaryContainer, scheme.onPrimaryContainer) >= 4.5f)
            }
        }
    }

    @Test
    fun blueOrangeColorsMatchDirectionLabelsInBothSchemes() {
        assertEquals(
            Color(0xFF0057A8),
            amountColor(AmountColorPreference.BLUE_ORANGE, income = false, darkTheme = false),
        )
        assertEquals(
            Color(0xFF9A4D00),
            amountColor(AmountColorPreference.BLUE_ORANGE, income = true, darkTheme = false),
        )
        assertEquals(
            Color(0xFFA8C7FA),
            amountColor(AmountColorPreference.BLUE_ORANGE, income = false, darkTheme = true),
        )
        assertEquals(
            Color(0xFFFFB77D),
            amountColor(AmountColorPreference.BLUE_ORANGE, income = true, darkTheme = true),
        )
    }
}
