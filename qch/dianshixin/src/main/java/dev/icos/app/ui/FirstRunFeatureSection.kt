package dev.icos.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.icos.app.R

private class FirstRunFeature(
    val icon: Int,
    val title: String,
    val caption: String,
)

private val firstRunFeatures =
    listOf(
        FirstRunFeature(AppSymbol.STATISTICS.drawable, "清晰记录", "收支一目了然"),
        FirstRunFeature(R.drawable.icon_planning_pie, "合理规划", "掌握财务状况"),
        FirstRunFeature(AuthSymbol.SHIELD_CHECK.drawable, "云端同步", "登录后同步备份"),
    )

// Allow roughly88dp per caption at system font1.0; stack when text needs more width.
@Composable
internal fun FirstRunFeatureSection() {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= 88.dp * 3 * fontScale + 24.dp) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                firstRunFeatures.forEach { feature ->
                    Column(
                        Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        FirstRunFeatureIcon(feature.icon)
                        Text(feature.title, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
                        Text(
                            feature.caption,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        } else {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                firstRunFeatures.forEach { feature ->
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        FirstRunFeatureIcon(feature.icon)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(feature.title, style = MaterialTheme.typography.labelLarge)
                            Text(
                                feature.caption,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FirstRunFeatureIcon(icon: Int) {
    Surface(shape = CircleShape, color = summaryCardColor()) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            Image(
                painter = painterResource(icon),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary),
            )
        }
    }
}
