package dev.icos.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.icos.app.domain.Money
import dev.icos.app.domain.SavingEntryKind
import dev.icos.app.domain.SavingEntrySummary
import dev.icos.app.domain.SavingKind
import dev.icos.app.domain.SavingPlanSummary
import dev.icos.app.domain.SavingState

@Composable
internal fun SavingPlanCardsSection(
    scopeKey: String,
    plan: SavingPlanSummary,
    cardCount: Int,
    entries: List<SavingEntrySummary>,
    busy: Boolean,
    amountsHidden: Boolean,
    cardAmount: (Int) -> Money,
    onCard: (Int, Money) -> Unit,
) {
    key(scopeKey) {
        var listMode by rememberSaveable { mutableStateOf(false) }
        val gridState = rememberSaveable(saver = LazyGridState.Saver) { LazyGridState() }
        val completed =
            entries
                .filter {
                    it.cancelledAt == null && it.kind == SavingEntryKind.DEPOSIT && it.cardIndex != null
                }.mapNotNull { it.cardIndex }
                .toSet()
                .size
        val depositable = plan.state == SavingState.ACTIVE && !busy
        Card(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(Modifier.widthIn(min = 160.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(savingPlanCardsTitle(plan.input.kind), style = MaterialTheme.typography.titleMedium)
                        Text(
                            "卡片 $completed / $cardCount 已完成",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = !listMode,
                            onClick = { listMode = false },
                            label = { AppIcon(AppSymbol.GRID, description = "网格视图") },
                            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                        )
                        FilterChip(
                            selected = listMode,
                            onClick = { listMode = true },
                            label = { AppIcon(AppSymbol.LIST, description = "列表视图") },
                            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                        )
                    }
                }
                LazyVerticalGrid(
                    columns = if (listMode) GridCells.Fixed(1) else GridCells.Adaptive(48.dp),
                    state = gridState,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(count = cardCount, key = { "card-$it" }) { index ->
                        val number = index + 1
                        val amount = cardAmount(number)
                        val occupied = entries.firstOrNull { it.cardIndex == number && it.cancelledAt == null } != null
                        SavingCardCell(number, amount, occupied, !occupied && depositable, listMode, amountsHidden) { onCard(number, amount) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SavingCardCell(
    number: Int,
    amount: Money,
    occupied: Boolean,
    enabled: Boolean,
    listMode: Boolean,
    amountsHidden: Boolean,
    onClick: () -> Unit,
) {
    val status = if (occupied) "已存" else "未存"
    val detail = if (amountsHidden) "金额已隐藏" else "${moneyText(amount)} ${amount.currency}"
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .semantics { contentDescription = "第 $number 张，$detail，$status" },
        shape = RoundedCornerShape(8.dp),
        color = if (occupied) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        contentColor = if (occupied) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        border = if (occupied) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        if (!listMode) {
            Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                Text(number.toString(), style = MaterialTheme.typography.labelLarge)
            }
        } else {
            // List mode keeps the number and status visible while the amount takes the remaining width and wraps instead of clipping.
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("第 $number 张", style = MaterialTheme.typography.bodySmall)
                    AmountText(detail, style = MaterialTheme.typography.bodyMedium)
                }
                Text(status, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

private fun savingPlanCardsTitle(kind: SavingKind) =
    when (kind) {
        SavingKind.WEEK_52 -> "52周存钱计划"
        SavingKind.DAY_365 -> "365天存钱计划"
        SavingKind.FIXED -> "固定存钱计划"
        SavingKind.FLEXIBLE -> "灵活存钱计划"
    }
