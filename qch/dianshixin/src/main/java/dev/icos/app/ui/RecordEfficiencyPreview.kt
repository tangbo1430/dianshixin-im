package dev.icos.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.icos.app.data.local.FinanceCategory
import dev.icos.app.domain.Direction
import java.util.UUID

/** Ephemeral trial input; nothing here is persisted or recorded. */
@Composable
internal fun RecordEfficiencyPreview(
    settings: RecordEfficiencySettings,
    categories: List<FinanceCategory>,
    owner: UUID?,
) {
    var amount by remember(owner) { mutableStateOf("") }
    var direction by remember(owner) { mutableStateOf(Direction.EXPENSE) }
    var selected by remember(owner) { mutableStateOf<UUID?>(null) }
    Surface(Modifier.fillMaxWidth(), color = summaryCardColor(), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("键盘预览", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(enabled = amount.isNotEmpty(), onClick = { amount = "" }) { Text("清空") }
            }
            Text(
                "可试输金额并选择分类，不会保存账单。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val amountPane: @Composable (Modifier) -> Unit = { m ->
                PreviewAmountPane(amount, settings, direction, { amount = it }, m)
            }
            val categoryPane: @Composable (Modifier) -> Unit = { m ->
                PreviewCategoryPane(
                    categories,
                    direction,
                    owner,
                    selected,
                    {
                        direction = it
                        selected = null
                    },
                    { selected = it },
                    m,
                )
            }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                // 3 keypad columns need >=48dp each, so ~160dp per pane is the side-by-side floor.
                if (maxWidth >= 332.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        amountPane(Modifier.weight(1f))
                        categoryPane(Modifier.weight(1f))
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        amountPane(Modifier.fillMaxWidth())
                        categoryPane(Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewAmountPane(
    amount: String,
    settings: RecordEfficiencySettings,
    direction: Direction,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (settings.amountInputMode == AmountInputMode.NUMERIC) {
            // No auto focus: entering the settings page must not pop the IME; tapping the field does.
            RecordAmountInput(
                "金额",
                amount,
                enabled = true,
                settings = settings,
                showKeyboard = false,
                active = false,
                onChange = onChange,
            )
        } else {
            val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
            val tint =
                amountColor(LocalAppearanceSettings.current.amountColors, direction == Direction.INCOME, dark)
                    ?: MaterialTheme.colorScheme.onSurface
            Text(
                amount.ifEmpty { "0.00" },
                color = tint,
                maxLines = 1,
                softWrap = false,
                style =
                    MaterialTheme.typography.headlineMedium.copy(
                        fontFamily = amountFontFamily(),
                        fontWeight = FontWeight.Bold,
                    ),
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            )
            AmountKeyboard(amount, true, settings, onChange = onChange)
        }
    }
}

@Composable
private fun PreviewCategoryPane(
    categories: List<FinanceCategory>,
    direction: Direction,
    owner: UUID?,
    selected: UUID?,
    onDirection: (Direction) -> Unit,
    onSelect: (UUID) -> Unit,
    modifier: Modifier = Modifier,
) {
    val visible =
        categories
            .filter { it.archivedAt == null && it.direction == direction }
            .sortedWith(compareBy({ it.sortOrder }, { it.name }))
            .take(8)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("选择分类", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(Direction.EXPENSE, Direction.INCOME).forEach { option ->
                FilterChip(
                    option == direction,
                    { onDirection(option) },
                    label = { Text(option.label()) },
                )
            }
        }
        if (visible.isEmpty()) {
            Text(
                "当前账本没有${direction.label()}分类。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val fontScale =
                    (LocalDensity.current.fontScale * LocalAppearanceSettings.current.fontScale).coerceAtLeast(1f)
                val minCellWidth = 80.dp * fontScale
                val columns = when {
                    maxWidth >= minCellWidth * 3 + 12.dp -> 3
                    maxWidth >= minCellWidth * 2 + 6.dp -> 2
                    else -> 1
                }
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    visible.chunked(columns).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            row.forEach { category ->
                                PreviewCategoryChip(category, owner, selected == category.id, { onSelect(category.id) }, Modifier.weight(1f))
                            }
                            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewCategoryChip(
    category: FinanceCategory,
    owner: UUID?,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val style =
        remember(owner, category) {
            owner?.let { CategoryStyles.read(context, it, category) } ?: CategoryStyles.default(category)
        }
    FilterChip(
        selected = selected,
        onClick = onSelect,
        modifier = modifier.heightIn(min = 48.dp),
        label = {
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                CategoryStyleBadge(style)
                Text(category.name, textAlign = TextAlign.Center)
            }
        },
    )
}

private fun Direction.label(): String = if (this == Direction.INCOME) "收入" else "支出"
