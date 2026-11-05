package dev.icos.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.icos.app.data.local.FinanceRecord
import dev.icos.app.data.local.FinanceSnapshot
import dev.icos.app.domain.RecordInput
import java.util.UUID

private const val REMARK_SUGGESTION_LIMIT = 6
private const val REMARK_MAX_LENGTH = 2000

private fun recordEntryKind(input: RecordInput): EntryKind =
    when (input) {
        is RecordInput.Expense -> EntryKind.EXPENSE
        is RecordInput.Income -> EntryKind.INCOME
        is RecordInput.Transfer -> EntryKind.TRANSFER
    }

@Composable
internal fun RecordRemarkSuggestions(
    snapshot: FinanceSnapshot,
    currentRemark: String,
    categoryId: UUID?,
    kind: EntryKind,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    val suggestions = remember(snapshot.ownerId, snapshot.epochId, snapshot.ledgerId, snapshot.records, categoryId, kind, currentRemark) {
        val now = System.currentTimeMillis()
        val query = currentRemark.trim()
        val hasQuery = query.isNotEmpty()
        val ordered = snapshot.records
            .asSequence()
            .filter { record ->
                record.occurredAtMillis <= now &&
                    recordEntryKind(record.input) == kind &&
                    record.remark.isNotBlank() &&
                    record.remark.length <= REMARK_MAX_LENGTH &&
                    (
                        !hasQuery ||
                            (record.remark.contains(query, ignoreCase = true) && !record.remark.equals(query, ignoreCase = true))
                        )
            }
            .sortedWith(
                compareByDescending<FinanceRecord> { it.categoryId != null && it.categoryId == categoryId }
                    .thenByDescending { it.occurredAtMillis },
            )
        val seen = LinkedHashSet<String>()
        for (record in ordered) {
            seen.add(record.remark)
            if (seen.size >= REMARK_SUGGESTION_LIMIT) break
        }
        seen.toList()
    }
    if (suggestions.isEmpty()) return
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "历史备注",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            suggestions.forEach { remark ->
                AssistChip(
                    onClick = { if (enabled) onSelect(remark) },
                    modifier = Modifier.semantics { contentDescription = "填入备注：$remark" },
                    enabled = enabled,
                    label = { Text(remark, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                )
            }
        }
    }
}
