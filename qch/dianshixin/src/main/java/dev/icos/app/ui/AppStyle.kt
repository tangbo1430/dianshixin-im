package dev.icos.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import dev.icos.app.R

internal fun icosColorScheme(
    dark: Boolean,
    themeColor: ThemeColorPreference = ThemeColorPreference.GOLD,
): ColorScheme {
    val base =
        if (dark) {
            darkColorScheme(
                primary = Color(0xFFE9C94E),
                onPrimary = Color(0xFF352B05),
                primaryContainer = Color(0xFF4C4018),
                onPrimaryContainer = Color(0xFFFFE899),
                secondary = Color(0xFFCEC5AB),
                onSecondary = Color(0xFF342F22),
                secondaryContainer = Color(0xFF3C382D),
                onSecondaryContainer = Color(0xFFEAE3CF),
                tertiary = Color(0xFFA9CCB7),
                onTertiary = Color(0xFF18382B),
                tertiaryContainer = Color(0xFF304D3E),
                onTertiaryContainer = Color(0xFFC5E9D3),
                background = Color(0xFF171815),
                onBackground = Color(0xFFE9E8E0),
                surface = Color(0xFF242620),
                onSurface = Color(0xFFE9E8E0),
                surfaceVariant = Color(0xFF35362F),
                onSurfaceVariant = Color(0xFFC3C3B8),
                surfaceTint = Color(0xFFE9C94E),
                inverseSurface = Color(0xFFE9E8E0),
                inverseOnSurface = Color(0xFF30312B),
                inversePrimary = Color(0xFF705C05),
                outline = Color(0xFF8E9083),
                outlineVariant = Color(0xFF44463C),
                surfaceDim = Color(0xFF171815),
                surfaceBright = Color(0xFF383A32),
                surfaceContainerLowest = Color(0xFF11120F),
                surfaceContainerLow = Color(0xFF1C1D19),
                surfaceContainer = Color(0xFF242620),
                surfaceContainerHigh = Color(0xFF2D2F27),
                surfaceContainerHighest = Color(0xFF242620),
            )
        } else {
            lightColorScheme(
                primary = Color(0xFF745D00),
                onPrimary = Color.White,
                primaryContainer = Color(0xFFE9C94E),
                onPrimaryContainer = Color(0xFF242620),
                secondary = Color(0xFF66604D),
                onSecondary = Color.White,
                secondaryContainer = Color(0xFFF0EBD9),
                onSecondaryContainer = Color(0xFF29271D),
                tertiary = Color(0xFF466C58),
                onTertiary = Color.White,
                tertiaryContainer = Color(0xFFD9EBDD),
                onTertiaryContainer = Color(0xFF18382B),
                background = Color(0xFFF7F7F2),
                onBackground = Color(0xFF242620),
                surface = Color(0xFFFFFFFF),
                onSurface = Color(0xFF242620),
                surfaceVariant = Color(0xFFEFEFE7),
                onSurfaceVariant = Color(0xFF6B6D62),
                surfaceTint = Color(0xFF745D00),
                inverseSurface = Color(0xFF30312B),
                inverseOnSurface = Color(0xFFF2F2EA),
                inversePrimary = Color(0xFFE9C94E),
                outline = Color(0xFF85877C),
                outlineVariant = Color(0xFFE4E5DC),
                surfaceDim = Color(0xFFE0E1D8),
                surfaceBright = Color.White,
                surfaceContainerLowest = Color.White,
                surfaceContainerLow = Color.White,
                surfaceContainer = Color.White,
                surfaceContainerHigh = Color.White,
                surfaceContainerHighest = Color.White,
            )
        }
    if (themeColor == ThemeColorPreference.GOLD) return base
    val accent = if (dark) themeColor.dark else themeColor.light
    val primary = if (dark) accent else lerp(accent, Color.Black, 0.32f)
    return base.copy(
        primary = primary,
        onPrimary = if (dark) base.background else Color.White,
        primaryContainer = if (dark) lerp(accent, base.background, 0.72f) else lerp(accent, Color.White, 0.3f),
        onPrimaryContainer = if (dark) accent else base.onSurface,
        secondaryContainer = if (dark) lerp(accent, base.background, 0.8f) else lerp(accent, Color.White, 0.84f),
        onSecondaryContainer = if (dark) accent else base.onSurface,
        surfaceTint = primary,
        inversePrimary = if (dark) lerp(themeColor.light, Color.Black, 0.32f) else themeColor.dark,
    )
}

@Composable
internal fun summaryCardColor(): Color {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val themeColor = LocalAppearanceSettings.current.themeColor
    if (themeColor == ThemeColorPreference.GOLD) return if (dark) Color(0xFF302B18) else Color(0xFFFBF1CD)
    return if (dark) {
        lerp(themeColor.dark, MaterialTheme.colorScheme.background, 0.89f)
    } else {
        lerp(themeColor.light, Color.White, 0.85f)
    }
}

@Composable
internal fun primaryButtonColors() =
    ButtonDefaults.buttonColors(
        containerColor =
            if (MaterialTheme.colorScheme.background.luminance() <
                0.5f
            ) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.primaryContainer
            },
        contentColor =
            if (MaterialTheme.colorScheme.background.luminance() <
                0.5f
            ) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onPrimaryContainer
            },
    )

@Composable
internal fun IcosTheme(
    settings: AppearanceSettings,
    content: @Composable () -> Unit,
) {
    val dark =
        when (settings.theme) {
            ThemePreference.SYSTEM -> isSystemInDarkTheme()
            ThemePreference.LIGHT -> false
            ThemePreference.DARK -> true
        }
    MaterialTheme(
        colorScheme = icosColorScheme(dark, settings.themeColor),
        typography = scaledTypography(settings.fontScale),
        shapes =
            Shapes(
                extraSmall = RoundedCornerShape(8.dp),
                small = RoundedCornerShape(12.dp),
                medium = RoundedCornerShape(16.dp),
                large = RoundedCornerShape(20.dp),
                extraLarge = RoundedCornerShape(24.dp),
            ),
    ) {
        ThemeSystemBars()
        AppearanceProvider(settings, content)
    }
}

internal enum class AppSymbol(
    val drawable: Int,
) {
    BOOK(R.drawable.icon_book),
    STATISTICS(R.drawable.icon_statistics),
    WALLET(R.drawable.icon_wallet),
    SAVING(R.drawable.icon_saving),
    ADD(R.drawable.icon_add),
    SEARCH(R.drawable.icon_search),
    CALENDAR(R.drawable.icon_calendar),
    FILTER(R.drawable.icon_filter),
    SETTINGS(R.drawable.icon_settings),
    ACCOUNT(R.drawable.icon_account),
    REFRESH(R.drawable.icon_refresh),
    REFUND(R.drawable.icon_refund),
    BACK(R.drawable.icon_back),
    MORE(R.drawable.icon_more),
    GRID(R.drawable.icon_grid),
    LIST(R.drawable.icon_list),
    CHEVRON(R.drawable.icon_chevron),
    LOCATION(R.drawable.icon_location),
    DOWN(R.drawable.icon_down),
    TEMPLATE(R.drawable.icon_template),
    EYE(R.drawable.icon_eye),
    EYE_OFF(R.drawable.icon_eye_off),
    TRANSFER(R.drawable.icon_transfer),
    ADJUST(R.drawable.icon_adjust),
    EDIT(R.drawable.icon_edit),
    CLOSE(R.drawable.icon_close),
    ARCHIVE(R.drawable.icon_archive),
    DELETE(R.drawable.icon_delete),
    COPY(R.drawable.icon_copy),
    SHARE(R.drawable.icon_share),
    CHECK(R.drawable.icon_check),
    LOCK(R.drawable.icon_lock),
    UPLOAD(R.drawable.icon_upload),
    DOWNLOAD(R.drawable.icon_download),
    FOOD(R.drawable.icon_food),
    TRANSPORT(R.drawable.icon_transport),
    HOME(R.drawable.icon_home),
    HEALTH(R.drawable.icon_health),
    SHOPPING(R.drawable.icon_shopping),
    LEISURE(R.drawable.icon_leisure),
    GIFT(R.drawable.icon_gift),
}

@Composable
internal fun AppIcon(
    symbol: AppSymbol,
    description: String? = null,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
) {
    val roleColor =
        when (symbol) {
            AppSymbol.BOOK -> Color(0xFF876D0E)
            AppSymbol.STATISTICS, AppSymbol.TRANSFER -> Color(0xFF4672B8)
            AppSymbol.WALLET, AppSymbol.SAVING -> Color(0xFF468262)
            AppSymbol.CALENDAR -> Color(0xFFAD6250)
            AppSymbol.ACCOUNT, AppSymbol.TEMPLATE -> Color(0xFF7960AE)
            else -> Color.Unspecified
        }
    val colored =
        LocalAppearanceSettings.current.iconStyle == IconStylePreference.COLOR && tint == Color.Unspecified &&
            roleColor != Color.Unspecified
    val resolvedTint =
        if (colored) {
            (
                if (MaterialTheme.colorScheme.background.luminance() <
                    0.5f
                ) {
                    lerp(roleColor, Color.White, 0.45f)
                } else {
                    roleColor
                }
            ).copy(alpha = LocalContentColor.current.alpha)
        } else if (tint == Color.Unspecified) {
            LocalContentColor.current
        } else {
            tint
        }
    val iconModifier =
        if (colored) {
            modifier.size(24.dp).background(resolvedTint.copy(alpha = resolvedTint.alpha * 0.14f), RoundedCornerShape(6.dp)).padding(2.dp)
        } else {
            modifier.size(24.dp)
        }
    Image(
        painter = painterResource(symbol.drawable),
        contentDescription = description,
        modifier = iconModifier,
        colorFilter = ColorFilter.tint(resolvedTint),
    )
}

@Composable
internal fun AccountAvatar(
    bitmap: android.graphics.Bitmap?,
    modifier: Modifier = Modifier,
    description: String = "账号头像",
) {
    if (bitmap == null) {
        Image(
            painter = painterResource(R.drawable.profile_avatar_default),
            contentDescription = description,
            modifier = modifier.clip(CircleShape),
            contentScale = ContentScale.Crop,
        )
    } else {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = description,
            modifier = modifier.clip(CircleShape),
            contentScale = ContentScale.Crop,
        )
    }
}
