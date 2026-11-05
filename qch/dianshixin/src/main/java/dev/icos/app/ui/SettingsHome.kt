package dev.icos.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun SettingsHome(
    feedbackEnabled: Boolean,
    isAccount: Boolean,
    values: Map<String, String> = emptyMap(),
    onOpen: (String) -> Unit,
) {
    val groups =
        listOf(
            "账号与安全" to listOf("账号资料" to AppSymbol.ACCOUNT, "密码与隐私" to AppSymbol.LOCK),
            "账本与资产" to
                listOf(
                    "账本与分类" to AppSymbol.BOOK,
                    "标签管理" to AppSymbol.EDIT,
                    "分类分享" to AppSymbol.SHARE,
                    "账户与统计" to AppSymbol.STATISTICS,
                    "预算" to AppSymbol.WALLET,
                ),
            "偏好设置" to listOf("外观" to AppSymbol.EYE, "记账效率" to AppSymbol.SETTINGS, "时间与提醒" to AppSymbol.CALENDAR),
            "数据与支持" to (
                listOf(
                    "同步与备份" to AppSymbol.REFRESH,
                    "导入导出" to AppSymbol.TRANSFER,
                    "语言与货币" to AppSymbol.STATISTICS,
                    "报销退款设置" to AppSymbol.TEMPLATE,
                    "帮助与关于" to AppSymbol.MORE,
                    "数据管理" to AppSymbol.ARCHIVE,
                ) + if (feedbackEnabled) listOf("问题反馈" to AppSymbol.EDIT) else emptyList()
            ),
        )
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Card(
            onClick = { onOpen("账号资料") },
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = summaryCardColor()),
        ) {
            Row(
                Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AppIcon(AppSymbol.ACCOUNT, modifier = Modifier.size(36.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (isAccount) "我的账号" else "本机游客", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (isAccount) "查看账号资料与设备" else "数据仅保存在本机",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                AppIcon(AppSymbol.CHEVRON)
            }
        }
        groups.forEach { (heading, entries) ->
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column {
                    Text(
                        heading,
                        Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    entries.forEach { (title, icon) ->
                        HorizontalDivider(
                            Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                        )
                        TextButton(
                            onClick = { onOpen(title) },
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 8.dp),
                        ) {
                            val accent =
                                when (icon) {
                                    AppSymbol.ACCOUNT, AppSymbol.TRANSFER -> Color(0xFF2D6A4F)
                                    AppSymbol.LOCK, AppSymbol.WALLET, AppSymbol.CALENDAR -> Color(0xFFB54735)
                                    AppSymbol.STATISTICS, AppSymbol.REFRESH, AppSymbol.MORE -> Color(0xFF276A9B)
                                    else -> MaterialTheme.colorScheme.primary
                                }
                            val iconColor =
                                if (MaterialTheme.colorScheme.background.luminance() <
                                    0.5f
                                ) {
                                    lerp(accent, Color.White, 0.45f)
                                } else {
                                    accent
                                }
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 8.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Box(
                                    Modifier.size(32.dp).background(iconColor.copy(alpha = 0.12f), RoundedCornerShape(8.dp)),
                                    contentAlignment = Alignment.Center,
                                ) { AppIcon(icon, tint = iconColor) }
                                Text(
                                    title,
                                    Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                values[title]?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                AppIcon(AppSymbol.CHEVRON, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}
