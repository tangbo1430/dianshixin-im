package dev.icos.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.UUID

@Composable
internal fun FeedbackPanel(
    model: FeedbackViewModel,
    userId: UUID?,
) {
    val state = model.state
    var type by remember { mutableStateOf("SUGGESTION") }
    var message by remember { mutableStateOf("") }
    var diagnosticConsent by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        HelpSectionCard("意见反馈") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "提交后可查看处理状态和工作人员回复。图片附件暂不支持。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (userId == null) HelpNotice("请登录已确认的服务器账号后提交和查看反馈。")
                if (state.busy) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("正在处理反馈请求…", style = MaterialTheme.typography.bodySmall)
                    }
                }
                state.error?.let { error ->
                    HelpNotice(error, error = true)
                    if (state.retryAvailable) {
                        Button(onClick = model::retryOriginal, enabled = !state.busy) { Text("沿用原请求重试") }
                    }
                }
                state.message?.let { HelpNotice(it) }
                Text("反馈类型", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("BUG" to "问题反馈", "SUGGESTION" to "功能建议", "OTHER" to "其他").forEach { (value, label) ->
                        FilterChip(
                            selected = type == value,
                            onClick = { type = value },
                            enabled = userId != null && !state.busy,
                            label = { Text(label) },
                        )
                    }
                }
                OutlinedTextField(
                    message,
                    { if (it.codePointCount(0, it.length) <= 10000) message = it },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = userId != null && !state.busy && !state.retryAvailable,
                    label = { Text("反馈内容") },
                    minLines = 3,
                    maxLines = 8,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(diagnosticConsent, { diagnosticConsent = it }, enabled = userId != null && !state.busy)
                    Text("同意随反馈记录应用版本及本次诊断授权状态", style = MaterialTheme.typography.bodySmall)
                }
                Button(
                    onClick = { model.submit(type, message, diagnosticConsent) },
                    colors = primaryButtonColors(),
                    enabled = userId != null && !state.busy && !state.retryAvailable && message.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("提交反馈") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = model::refresh, enabled = userId != null && !state.busy) { Text("刷新我的反馈") }
                    if (state.hasMore) OutlinedButton(onClick = model::loadMore, enabled = userId != null && !state.busy) { Text("加载更多") }
                }
            }
        }

        HelpSectionCard("我的反馈") {
            if (state.rows.isEmpty()) {
                Text(
                    if (userId == null) "登录后可查看已提交的反馈与回复。" else "点按“刷新我的反馈”查看已提交记录。",
                    Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                state.rows.forEachIndexed { index, row ->
                    if (index > 0) HelpDivider()
                    HelpRow(
                        symbol = AppSymbol.MORE,
                        title = feedbackTypeLabel(row.type),
                        caption = "${row.status} · ${row.updatedAt}",
                        enabled = !state.busy,
                        onClick = { model.open(row.id) },
                    ) { AppIcon(AppSymbol.CHEVRON) }
                }
            }
        }

        state.selected?.let { detail ->
            HelpSectionCard("反馈详情") {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "${feedbackTypeLabel(detail.row.type)} · ${detail.row.status} · ${detail.row.updatedAt}",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(detail.message)
                    Text(
                        "提交版本 ${detail.appVersion} · 诊断授权 ${if (detail.diagnosticConsent) "是" else "否"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (detail.replies.isEmpty()) {
                        HelpNotice("尚无工作人员回复。")
                    } else {
                        detail.replies.forEach { reply ->
                            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    reply.createdAt,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(reply.message, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    TextButton(onClick = model::closeDetail) {
                        AppIcon(AppSymbol.BACK, modifier = Modifier.size(18.dp))
                        Text("返回反馈列表")
                    }
                }
            }
        }
    }
}

private fun feedbackTypeLabel(type: String) =
    when (type) {
        "BUG" -> "问题反馈"
        "SUGGESTION" -> "功能建议"
        else -> "其他"
    }
