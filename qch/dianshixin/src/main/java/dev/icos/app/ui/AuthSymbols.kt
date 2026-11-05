package dev.icos.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import dev.icos.app.R

/** Generated outline icons used by auth, profile and app-lock pages. */
internal enum class AuthSymbol(
    val drawable: Int,
) {
    MAIL(R.drawable.icon_mail),
    KEY(R.drawable.icon_key),
    SHIELD_LOCK(R.drawable.icon_shield_lock),
    SHIELD_CHECK(R.drawable.icon_shield_check),
    CHECK_CIRCLE(R.drawable.icon_check_circle),
    FINGERPRINT(R.drawable.icon_fingerprint),
    BACKSPACE(R.drawable.icon_backspace),
    CAMERA(R.drawable.icon_camera),
    ID_CARD(R.drawable.icon_id_card),
    MONITOR(R.drawable.icon_monitor),
    LOGOUT(R.drawable.icon_logout),
}

@Composable
internal fun AuthIcon(
    symbol: AuthSymbol,
    description: String? = null,
    modifier: Modifier = Modifier,
    tint: Color = androidx.compose.material3.LocalContentColor.current,
) {
    Image(
        painter = painterResource(symbol.drawable),
        contentDescription = description,
        modifier = modifier.size(24.dp),
        colorFilter = ColorFilter.tint(tint),
    )
}
