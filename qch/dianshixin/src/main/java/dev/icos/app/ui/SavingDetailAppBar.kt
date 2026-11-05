package dev.icos.app.ui

import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
internal fun SavingDetailTitle(
    name: String,
    modifier: Modifier = Modifier,
    onHeightChange: (Dp) -> Unit,
) {
    val density = LocalDensity.current
    Text(
        text = name,
        // Measure both lines before the bar has grown beyond its initial height.
        modifier = modifier.wrapContentHeight(unbounded = true),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
        onTextLayout = { layout -> onHeightChange(with(density) { layout.size.height.toDp() }) },
    )
}

internal fun savingDetailTopBarHeight(titleHeight: Dp): Dp = maxOf(TopAppBarDefaults.TopAppBarExpandedHeight, titleHeight + 16.dp)
