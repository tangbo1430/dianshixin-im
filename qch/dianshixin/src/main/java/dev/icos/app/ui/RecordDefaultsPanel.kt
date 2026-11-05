package dev.icos.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.icos.app.data.local.FinanceAccount
import dev.icos.app.data.local.FinanceCategory
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/**
 * Defaults for a new record. `defaultAccountId == null` means automatic fallback to the account the
 * parent resolves for the current ledger; a stale stored account is shown as that fallback and is
 * never rewritten here.
 */
@Composable
internal fun RecordDefaultSettingsCard(
    settings: RecordEfficiencySettings,
    accounts: List<FinanceAccount>,
    resolvedAccountId: UUID?,
    onChange: (RecordEfficiencySettings) -> Unit,
) {
    val selectedAccount = accounts.firstOrNull { it.id == settings.defaultAccountId }
    val fallbackAccount = accounts.firstOrNull { it.id == resolvedAccountId }
    val staleAccount = settings.defaultAccountId != null && selectedAccount == null
    val accountValue =
        if (selectedAccount != null) {
            "${selectedAccount.name} · ${selectedAccount.currency}"
        } else {
            listOfNotNull("自动选择", fallbackAccount?.name).joinToString(" · ")
        }
    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Text(
                "默认设置",
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            DefaultSettingRow(
                icon = AppSymbol.TEMPLATE,
                label = "默认记账类型",
                value = settings.defaultKind.label,
                hint = null,
            ) { dismiss ->
                EntryKind.entries.forEach { kind ->
                    DropdownMenuItem(
                        text = { Text(kind.label, Modifier.fillMaxWidth()) },
                        onClick = {
                            dismiss()
                            if (kind != settings.defaultKind) onChange(settings.copy(defaultKind = kind))
                        },
                        trailingIcon = { if (kind == settings.defaultKind) AppIcon(AppSymbol.CHECK) },
                        modifier =
                            Modifier.heightIn(min = 48.dp).semantics {
                                role = Role.RadioButton
                                selected = kind == settings.defaultKind
                            },
                    )
                }
            }
            DefaultSettingRow(
                icon = AppSymbol.WALLET,
                label = "默认账户",
                value = accountValue,
                hint = if (staleAccount) "原默认账户不可用，按可用账户自动回退" else null,
            ) { dismiss ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text("自动选择")
                            Text("按可用账户回退", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    onClick = {
                        dismiss()
                        if (settings.defaultAccountId != null) onChange(settings.copy(defaultAccountId = null))
                    },
                    trailingIcon = { if (selectedAccount == null) AppIcon(AppSymbol.CHECK) },
                    modifier =
                        Modifier.heightIn(min = 48.dp).semantics {
                            role = Role.RadioButton
                            selected = selectedAccount == null
                        },
                )
                accounts.forEach { account ->
                    DropdownMenuItem(
                        text = {
                            Column(Modifier.fillMaxWidth()) {
                                Text(account.name)
                                Text(
                                    account.currency,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        onClick = {
                            dismiss()
                            if (account.id != settings.defaultAccountId) onChange(settings.copy(defaultAccountId = account.id))
                        },
                        trailingIcon = { if (account.id == settings.defaultAccountId) AppIcon(AppSymbol.CHECK) },
                        modifier =
                            Modifier.heightIn(min = 48.dp).semantics {
                                role = Role.RadioButton
                                selected = account.id == settings.defaultAccountId
                            },
                    )
                }
            }
        }
    }
}

@Composable
private fun DefaultSettingRow(
    icon: AppSymbol,
    label: String,
    value: String,
    hint: String?,
    menu: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(role = Role.Button) { expanded = true }
                .semantics(mergeDescendants = true) { contentDescription = "$label，当前 $value" }
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier.size(32.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) { AppIcon(icon, tint = MaterialTheme.colorScheme.primary) }
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyLarge)
                hint?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(
                value,
                Modifier.weight(1f),
                textAlign = TextAlign.End,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AppIcon(AppSymbol.CHEVRON, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            menu { expanded = false }
        }
    }
}

/**
 * Truthful preview of the blank draft a new Record would open with; the ephemeral keyboard trial is
 * not saved and is never carried in here.
 */
@Composable
internal fun NextRecordPreviewCard(
    draft: RecordDraft,
    categories: List<FinanceCategory>,
    accounts: List<FinanceAccount>,
    owner: UUID?,
    decimalPlaces: Int,
    enabled: Boolean,
    onEdit: () -> Unit,
) {
    val context = LocalContext.current
    val category = draft.categoryId?.let { id -> categories.firstOrNull { it.id == id } }
    val categoryStyle =
        category?.let { c ->
            owner?.let { ownerId -> CategoryStyles.read(context, ownerId, c) } ?: CategoryStyles.default(c)
        }
    val account = accounts.firstOrNull { it.id == draft.accountId }
    val from = accounts.firstOrNull { it.id == draft.fromId }
    val to = accounts.firstOrNull { it.id == draft.toId }
    val kind = draft.kind
    val transfer = kind == EntryKind.TRANSFER
    val title =
        when {
            transfer -> EntryKind.TRANSFER.label
            category != null -> category.name
            else -> "未选择分类"
        }
    val accountName =
        if (transfer) {
            "${from?.name ?: "转出账户待选择"} → ${to?.name ?: "转入账户待选择"}"
        } else {
            account?.name ?: "账户待选择"
        }
    val accountText =
        if (transfer) {
            val fromText = from?.let { "${it.name} (${it.currency})" } ?: "转出账户待选择"
            val toText = to?.let { "${it.name} (${it.currency})" } ?: "转入账户待选择"
            "$fromText → $toText"
        } else {
            account?.let { "${it.name} · ${it.currency}" } ?: accountName
        }
    val time = previewTimeText(draft.occurredAtMillis, draft.timezone)
    val amountCurrency = if (transfer) from?.currency ?: draft.currency else draft.currency
    val amount = draft.amount.ifBlank { null }?.let { "$it $amountCurrency" } ?: "金额待填写"
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val amountTint =
        amountColor(LocalAppearanceSettings.current.amountColors, kind == EntryKind.INCOME, dark)
            ?: MaterialTheme.colorScheme.onSurface
    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "下一条记账预览",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                IconButton(onClick = onEdit, enabled = enabled, modifier = Modifier.size(48.dp)) {
                    AppIcon(AppSymbol.EDIT, description = "编辑下一条记账")
                }
            }
            Surface(Modifier.fillMaxWidth(), color = summaryCardColor(), shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val stacked = maxWidth < 260.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
                        val head: @Composable (Modifier) -> Unit = { modifier ->
                            Row(
                                modifier,
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                when {
                                    categoryStyle != null -> CategoryStyleBadge(categoryStyle)
                                    transfer -> AppIcon(AppSymbol.TRANSFER)
                                    else -> AppIcon(AppSymbol.BOOK)
                                }
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        "$accountText · $time",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        val amountText: @Composable () -> Unit = {
                            Text(
                                amount,
                                style =
                                    MaterialTheme.typography.titleLarge.copy(
                                        fontFamily = amountFontFamily(),
                                        fontWeight = FontWeight.Bold,
                                    ),
                                color = if (draft.amount.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else amountTint,
                                textAlign = if (stacked) TextAlign.Start else TextAlign.End,
                            )
                        }
                        if (stacked) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                head(Modifier.fillMaxWidth())
                                amountText()
                            }
                        } else {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                head(Modifier.weight(1f))
                                amountText()
                            }
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(kind.label, accountName, time, "$decimalPlaces 位小数").forEach { meta ->
                            Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f), shape = RoundedCornerShape(50)) {
                                Text(
                                    meta,
                                    Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private val previewTimeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.getDefault())

private fun previewTimeText(
    value: Long,
    zone: String,
): String =
    Instant
        .ofEpochMilli(value)
        .atZone(
            runCatching {
                ZoneId.of(zone)
            }.getOrDefault(ZoneId.systemDefault()),
        ).format(previewTimeFormat)
