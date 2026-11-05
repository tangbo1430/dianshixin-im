package dev.icos.app.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.icos.app.data.local.FinanceSnapshot
import dev.icos.app.fileio.AccountExportFilter
import dev.icos.app.fileio.AccountExportFormat
import dev.icos.app.fileio.AccountExportKind
import dev.icos.app.fileio.AccountFileExport
import dev.icos.app.fileio.AccountFileImportCoordinator
import dev.icos.app.fileio.GuestFileImportCoordinator
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.util.UUID

@Composable
internal fun FileIoPanel(
    model: FinanceViewModel,
    snapshot: FinanceSnapshot?,
    accountMode: Boolean,
    accountIdentity: String,
    incoming: Triple<String, Boolean, String>?,
    consumeIncoming: () -> Unit,
) {
    // The lock screen removes this composition: every value that must survive it lives in the model.
    var staged by model.fileIoField({ it.staged }, { state, value -> state.copy(staged = value) })
    var page by model.fileIoField({ it.page }, { state, value -> state.copy(page = value) })
    var preview by model.fileIoField({ it.guestPreview }, { state, value -> state.copy(guestPreview = value) })
    var accountPreview by model.fileIoField({ it.accountPreview }, { state, value -> state.copy(accountPreview = value) })
    var accountHistory by model.fileIoField({ it.accountHistory }, { state, value -> state.copy(accountHistory = value) })
    var batchId by model.fileIoField({ it.batchId }, { state, value -> state.copy(batchId = value) })
    var status by model.fileIoField({ it.status }, { state, value -> state.copy(status = value) })
    var exportMime by model.fileIoField({ it.exportMime }, { state, value -> state.copy(exportMime = value) })
    var accountExportFormat by model.fileIoField({ it.accountExportFormat }, { state, value -> state.copy(accountExportFormat = value) })
    var accountExportKinds by model.fileIoField({ it.accountExportKinds }, { state, value -> state.copy(accountExportKinds = value) })
    var accountFromDate by model.fileIoField({ it.accountFromDate }, { state, value -> state.copy(accountFromDate = value) })
    var accountThroughDate by model.fileIoField({ it.accountThroughDate }, { state, value -> state.copy(accountThroughDate = value) })
    val accountExportPending = model.fileIo.exportPreparing
    val accountExportError = model.fileIo.accountExportError
    val exporting = model.fileIo.exporting
    val exportDone = model.fileIo.exportDone
    val exportTotal = model.fileIo.exportTotal
    val pickerOutstanding = model.fileIoPickerOutstanding
    val accountBindings = model.fileIo.accountBindings
    val categoryBindings = model.fileIo.categoryBindings
    val tagBindings = model.fileIo.tagBindings
    var reverseTarget by remember { mutableStateOf<UUID?>(null) }
    var reverseReason by remember { mutableStateOf("用户撤销文件导入") }
    var showReverseDialog by remember { mutableStateOf(false) }
    LaunchedEffect(incoming?.third) {
        val shared = incoming
        if (shared != null && snapshot != null && !accountMode) {
            model.previewIncomingGuestFile(shared.first, shared.second, shared.third)
            consumeIncoming()
        }
    }

    BackHandler(enabled = page != FileIoPage.SOURCE) { page = FileIoPage.SOURCE }
    val ledgerName = snapshot?.let { value -> value.ledgers.firstOrNull { it.id == value.ledgerId }?.name }
    val pickFile = { model.beginFileImport() }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val wide = maxWidth >= 600.dp
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // The settings shell already renders the app bar and the section title; keep only the page tabs.
            IoPageTabs(page) { page = it }
            Text(
                page.subtitle(accountMode),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            status?.let { IoMessage(it) }

            when (page) {
                FileIoPage.SOURCE -> {
                    IoHero(
                        if (accountMode) "从本地文件导入账号账单" else "从本地文件导入账单",
                        if (accountMode) {
                            "支持 CSV / XLSX。文件先在本机解析并复制到应用私有暂存区；核对映射后才上传服务端暂存，整批原子提交。"
                        } else {
                            "支持 CSV / XLSX。文件仅在本机解析并复制到应用私有暂存区，不会上传到任何服务器。"
                        },
                    )
                    IoGroup {
                        IoSectionTitle("选择来源", "支持 CSV / XLSX；暂不支持支付宝、微信支付账单直接导入。旧版 .xls 请先另存为 .xlsx 或 CSV。")
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            IoSourceTile(
                                "CSV 文件",
                                "通用表格格式",
                                AppSymbol.TEMPLATE,
                                enabled = !model.state.busy && !pickerOutstanding,
                                modifier = Modifier.weight(1f),
                            ) { pickFile() }
                            IoSourceTile(
                                "XLSX 文件",
                                "Excel 工作簿",
                                AppSymbol.BOOK,
                                enabled = !model.state.busy && !pickerOutstanding,
                                modifier = Modifier.weight(1f),
                            ) { pickFile() }
                        }
                        IoSourceTile(
                            "选择文件",
                            "从设备选择 CSV 或 XLSX 文件",
                            AppSymbol.UPLOAD,
                            enabled = !model.state.busy && !pickerOutstanding,
                            modifier = Modifier.fillMaxWidth(),
                        ) { pickFile() }
                        if (accountMode) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(enabled = model.state.busy, onClick = {
                                    model.cancelCurrentAccountFileImport()
                                    status =
                                        "正在停止当前导入；已发出的请求会按原操作标识核对。"
                                }) { Text("停止当前导入") }
                            }
                        }
                        model.fileIoProgress?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                    IoGroup {
                        IoSectionTitle("字段模板", "第一行表头必须完全一致，可选第 11 列 externalId。")
                        Text("日期,类型,金额,一级分类,二级分类,账户1,账户2,备注,货币,标签", style = MaterialTheme.typography.labelSmall)
                        Text(
                            "CSV 使用 UTF-8；XLSX 需包含“明细”和“说明”两张工作表，单元格保持文本。可从导出页生成同格式 CSV 作为填写参考，当前不提供独立模板下载。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IoGroup {
                        IoSectionTitle("导入步骤", "预览不会写入账本；只有明确确认后才整批提交。")
                        IoSteps(
                            if ((if (accountMode) accountPreview else preview) == null) {
                                1
                            } else if (batchId == null) {
                                2
                            } else {
                                3
                            },
                        )
                        Text(
                            if ((if (accountMode) accountPreview else preview) == null) "尚未选择文件。" else "已有预览或待确认批次，可从上方“预览”返回核对。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                FileIoPage.PREVIEW -> {
                    if (accountMode) {
                        val current =
                            accountPreview?.takeIf { item ->
                                "${item.ownerId}:${item.epochId}:${item.sessionIdentity.sessionId}:${item.generation}" == accountIdentity
                            }
                        if (current == null) {
                            IoGroup {
                                IoSectionTitle("账号导入预览", "选择文件后在此核对统计、字段映射与逐行错误。")
                                Text(
                                    "尚未选择文件，或账号预览身份已变化。请重新选择账号账单文件。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                TextButton(onClick = { page = FileIoPage.SOURCE }) { Text("去选择文件") }
                            }
                        } else {
                            IoGroup {
                                IoSectionTitle(
                                    "账号导入预览",
                                    "暂存副本 · SHA-256 ${current.parsed.sha256.take(16)}… · ${current.parsed.rows.size} 行",
                                )
                                if (current.parsed.workbookSheets.isNotEmpty()) {
                                    Text(
                                        "工作表：${current.parsed.workbookSheets.joinToString()}（仅导入“明细”）",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                ledgerName?.let {
                                    Text(
                                        "目标账本：$it",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IoStats(
                                    listOf(
                                        Triple("${current.parsed.rows.size}", "总记录", MaterialTheme.colorScheme.onSurface),
                                        Triple("${current.mapping.candidates.size}", "可导入", MaterialTheme.colorScheme.primary),
                                        Triple(
                                            "${current.mapping.errors.map { it.row }.filter { it > 0 }.toSet().size}",
                                            "需修正",
                                            MaterialTheme.colorScheme.error,
                                        ),
                                        Triple(
                                            "${current.mapping.errors.count { it.code == "DUPLICATE_EXTERNAL_ID" }}",
                                            "文件内重复",
                                            MaterialTheme.colorScheme.tertiary,
                                        ),
                                    ),
                                )
                                Text(
                                    "导入范围：全部 ${current.parsed.rows.size} 行；账号导入不支持逐行勾选，存在错误时不能上传暂存。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (current.mapping.errors.isNotEmpty()) {
                                    IoSectionTitle("需要修正的行", "整批提交必须包含所有数据行；错误行不会被写入。")
                                    current.mapping.errors.take(20).forEach {
                                        Text(
                                            "第 ${it.row} 行 · ${it.code} · ${it.message}",
                                            color = MaterialTheme.colorScheme.error,
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                    if (current.mapping.errors.size >
                                        20
                                    ) {
                                        Text(
                                            "仅列出前 20 条错误，共 ${current.mapping.errors.size} 条。",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                IoSectionTitle("字段映射", "账户、分类与标签未绑定时会阻止上传。")
                                val accounts =
                                    current.mapping.candidates
                                        .flatMap {
                                            listOf(
                                                it.account1,
                                                it.account2,
                                            )
                                        }.filter(String::isNotBlank)
                                        .distinct() +
                                        listOfNotNull(
                                            "".takeIf {
                                                current.mapping.candidates.any { row ->
                                                    row.kind != "TRANSFER" &&
                                                        row.account1.isBlank()
                                                }
                                            },
                                        )
                                accounts.forEach { name ->
                                    BindingPicker(
                                        if (name.isBlank()) "未指定账户：选择入账账户" else "账户：$name",
                                        current.accountOptions[name],
                                        current.accountOptions.values.toList(),
                                        current.accountOptions.entries.associate {
                                            it.value to
                                                it.key
                                        },
                                        accountBindings[name],
                                        { id ->
                                            val bindings = accountBindings + (name to id)
                                            model.updateFileIo { state ->
                                                state.copy(
                                                    accountBindings = bindings,
                                                    accountPreview =
                                                        AccountFileImportCoordinator.remapPreview(
                                                            current,
                                                            ZoneId.of(model.fileImportZone),
                                                            bindings,
                                                            state.categoryBindings,
                                                        ),
                                                )
                                            }
                                        },
                                    )
                                }
                                val categories =
                                    current.mapping.candidates
                                        .filter {
                                            it.kind != "TRANSFER"
                                        }.map { "${it.primaryCategory}\u0000${it.secondaryCategory}\u0000${it.kind}" }
                                        .distinct()
                                categories.forEach { key ->
                                    val parts = key.split('\u0000')
                                    val label =
                                        listOf(
                                            parts[0],
                                            parts.getOrNull(1).orEmpty(),
                                        ).filter(String::isNotBlank).joinToString(" / ")
                                    val choices = current.categoryOptions.filter { it.direction.name == parts[2] }
                                    BindingPicker(
                                        "分类：$label",
                                        null,
                                        choices.map { it.id },
                                        choices.associate {
                                            it.id to
                                                "${it.name} · ${it.direction}"
                                        },
                                        categoryBindings[key],
                                        { id ->
                                            val bindings = categoryBindings + (key to id)
                                            model.updateFileIo { state ->
                                                state.copy(
                                                    categoryBindings = bindings,
                                                    accountPreview =
                                                        AccountFileImportCoordinator.remapPreview(
                                                            current,
                                                            ZoneId.of(model.fileImportZone),
                                                            state.accountBindings,
                                                            bindings,
                                                        ),
                                                )
                                            }
                                        },
                                    )
                                }
                                current.mapping.candidates.flatMap { it.tags }.distinct().forEach { name ->
                                    val ids = current.tagOptions[name].orEmpty()
                                    BindingPicker("标签：$name", ids.singleOrNull(), ids, ids.associateWith { name }, tagBindings[name], {
                                        model.updateFileIo { state -> state.copy(tagBindings = state.tagBindings + (name to it)) }
                                    })
                                }
                                IoSectionTitle("数据预览", "第 N 行 = 源文件行号；账号导入按整批提交，不提供逐行勾选。")
                                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                                    items(current.mapping.candidates, key = { it.sourceRow }) { row ->
                                        Text(
                                            "第 ${row.sourceRow} 行 · ${row.kind ?: "类型无效"} · ${row.amount?.let {
                                                "${it.unscaled} / 10^${it.scale} ${it.currency}"
                                            } ?: "金额无效"} · ${row.remark}",
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                }
                                IoSectionTitle("预检与确认", "上传暂存只准备服务端批次；确认整批提交并由 canonical pull 确认后才算完成。")
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    if (model.state.busy) {
                                        TextButton(onClick = {
                                            model.cancelCurrentAccountFileImport()
                                            status =
                                                "正在停止当前导入；已发出的请求会按原操作标识核对。"
                                        }) { Text("停止当前导入") }
                                    }
                                    FilledTonalButton(
                                        enabled =
                                            staged != null && current.mapping.errors.isEmpty() &&
                                                current.mapping.candidates.size == current.parsed.rows.size &&
                                                model.state.accountOwner != null &&
                                                !model.state.busy &&
                                                !model.state.syncPaused &&
                                                accountIdentity == model.accountFileIdentity,
                                        onClick = {
                                            val file = staged ?: return@FilledTonalButton
                                            if ("${current.ownerId}:${current.epochId}:${current.sessionIdentity.sessionId}:${current.generation}" !=
                                                model.accountFileIdentity
                                            ) {
                                                model.discardFileIoStaged()
                                                accountPreview = null
                                                status = "账号预览身份已变化，请重新选择文件。"
                                                return@FilledTonalButton
                                            }
                                            model.stageAccountFileImport(
                                                current,
                                                file,
                                                accountBindings.toMap(),
                                                categoryBindings.toMap(),
                                                tagBindings.toMap(),
                                            ) { result ->
                                                if (accountIdentity != model.accountFileIdentity ||
                                                    "${current.ownerId}:${current.epochId}:${current.sessionIdentity.sessionId}:${current.generation}" !=
                                                    model.accountFileIdentity
                                                ) {
                                                    if (result.isFailure) model.discardFileIoStaged()
                                                    return@stageAccountFileImport
                                                }
                                                result
                                                    .onSuccess {
                                                        batchId = it
                                                        status = "服务端已暂存完整批次；请再次确认原子提交。"
                                                        model.refreshAccountFileImportHistory()
                                                    }.onFailure { status = fileIoErrorMessage(it) }
                                            }
                                        },
                                    ) { Text("持久化映射并上传暂存") }
                                    Button(
                                        enabled =
                                            batchId != null && !model.state.busy && !model.state.syncPaused &&
                                                accountIdentity == model.accountFileIdentity,
                                        onClick = {
                                            val id = batchId ?: return@Button
                                            model.commitAccountFileImport(id) { result ->
                                                if (accountIdentity != model.accountFileIdentity) return@commitAccountFileImport
                                                result.fold({
                                                    status = "批次已提交并由 canonical pull 确认。"
                                                    batchId = null
                                                    accountPreview = null
                                                    model.discardFileIoStaged()
                                                    page = FileIoPage.HISTORY
                                                    model.refreshAccountFileImportHistory()
                                                }, {
                                                    status =
                                                        fileIoErrorMessage(it)
                                                })
                                            }
                                        },
                                    ) { Text("确认整批提交") }
                                    TextButton(enabled = !model.state.busy && !model.state.syncPaused, onClick = {
                                        val id = batchId
                                        model.discardFileIoStaged()
                                        accountPreview = null
                                        page = FileIoPage.SOURCE
                                        if (id == null) {
                                            status = "预览已取消，未写入账号账本。"
                                        } else {
                                            model.cancelAccountFileImport(id) { result ->
                                                if (accountIdentity != model.accountFileIdentity) return@cancelAccountFileImport
                                                status = result.fold({ "服务端暂存已取消。" }, { fileIoErrorMessage(it) })
                                                if (result.isSuccess) {
                                                    batchId = null
                                                    model.refreshAccountFileImportHistory()
                                                }
                                            }
                                        }
                                    }) { Text("取消预览") }
                                }
                            }
                        }
                    } else {
                        val current = preview
                        if (current == null) {
                            IoGroup {
                                IoSectionTitle("导入预览", "选择文件后在此核对统计、字段映射与逐行错误。")
                                Text(
                                    "尚未选择文件，或预览已取消。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                TextButton(onClick = { page = FileIoPage.SOURCE }) { Text("去选择文件") }
                            }
                        } else {
                            IoGroup {
                                IoSectionTitle(
                                    "导入预览",
                                    buildString {
                                        append("暂存副本 · SHA-256 ${current.digest.take(16)}… · ${current.parsed.rows.size} 行")
                                        staged?.let { append(" · ${it.bytes / 1024} KB") }
                                    },
                                )
                                if (current.parsed.workbookSheets.isNotEmpty()) {
                                    Text(
                                        "工作表：${current.parsed.workbookSheets.joinToString()}（仅导入“明细”，忽略白名单“说明”）",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                ledgerName?.let {
                                    Text(
                                        "目标账本：$it",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IoStats(
                                    listOf(
                                        Triple("${current.parsed.rows.size}", "总记录", MaterialTheme.colorScheme.onSurface),
                                        Triple("${current.selectedRows.size}", "已选", MaterialTheme.colorScheme.primary),
                                        Triple(
                                            "${current.mapping.errors.map { it.row }.filter { it > 0 }.toSet().size}",
                                            "需修正",
                                            MaterialTheme.colorScheme.error,
                                        ),
                                        Triple(
                                            "${current.mapping.errors.count { it.code == "DUPLICATE_EXTERNAL_ID" }}",
                                            "文件内重复",
                                            MaterialTheme.colorScheme.tertiary,
                                        ),
                                    ),
                                )
                                Text(
                                    "导入范围：已选 ${current.selectedRows.size} / ${current.parsed.rows.size} 行；错误行默认跳过，可修正后重新选择文件。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (current.mapping.errors.isNotEmpty()) {
                                    IoSectionTitle("需要修正的行", "存在错误的行不会导入；可修正源文件后重新选择。")
                                    current.mapping.errors.take(12).forEach {
                                        Text(
                                            "第 ${it.row} 行 · ${it.code} · ${it.message}",
                                            color = MaterialTheme.colorScheme.error,
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                    if (current.mapping.errors.size >
                                        12
                                    ) {
                                        Text(
                                            "仅列出前 12 条错误，共 ${current.mapping.errors.size} 条。",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                IoSectionTitle("字段映射", "账户、分类与标签未绑定时会阻止建立批次。")
                                val accountNames =
                                    current.mapping.candidates
                                        .flatMap {
                                            listOf(
                                                it.account1,
                                                it.account2,
                                            )
                                        }.filter(String::isNotBlank)
                                        .distinct()
                                accountNames.forEach { name ->
                                    BindingPicker(
                                        "账户：$name",
                                        current.accountOptions.entries
                                            .firstOrNull {
                                                it.key ==
                                                    name
                                            }?.value,
                                        current.accountOptions.values.toList(),
                                        current.accountOptions.entries.associate {
                                            it.value to
                                                it.key
                                        },
                                        accountBindings[name],
                                        { id ->
                                            model.updateFileIo { state ->
                                                state.copy(
                                                    accountBindings =
                                                        state.accountBindings + (name to id),
                                                )
                                            }
                                        },
                                    )
                                }
                                val categoryKeys =
                                    current.mapping.candidates
                                        .filter {
                                            it.kind != "TRANSFER"
                                        }.map { "${it.primaryCategory}\u0000${it.secondaryCategory}\u0000${it.kind}" }
                                        .distinct()
                                categoryKeys.forEach { key ->
                                    val parts = key.split('\u0000')
                                    val label =
                                        listOf(
                                            parts[0],
                                            parts.getOrNull(1).orEmpty(),
                                        ).filter(String::isNotBlank).joinToString(" / ")
                                    BindingPicker(
                                        "分类：$label",
                                        null,
                                        current.categoryOptions.map { it.id },
                                        current.categoryOptions.associate {
                                            it.id to
                                                "${it.name} · ${it.direction}"
                                        },
                                        categoryBindings[key],
                                        { id ->
                                            model.updateFileIo { state ->
                                                state.copy(
                                                    categoryBindings =
                                                        state.categoryBindings + (key to id),
                                                )
                                            }
                                        },
                                    )
                                }
                                val tagNames =
                                    current.mapping.candidates
                                        .flatMap { it.tags }
                                        .distinct()
                                tagNames.forEach { name ->
                                    BindingPicker(
                                        "标签：$name",
                                        null,
                                        current.tagOptions[name].orEmpty(),
                                        current.tagOptions.values.flatten().distinct().associateWith { id ->
                                            current.tagOptions.entries
                                                .firstOrNull {
                                                    id in
                                                        it.value
                                                }?.key
                                                ?: ""
                                        },
                                        tagBindings[name],
                                        { id ->
                                            model.updateFileIo { state ->
                                                state.copy(tagBindings = state.tagBindings + (name to id))
                                            }
                                        },
                                    )
                                }
                                IoSectionTitle("数据预览", "第 N 行 = 源文件行号；勾选要导入的行，错误行会被排除。")
                                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                                    items(current.mapping.candidates, key = { it.sourceRow }) { candidate ->
                                        val rowErrors = current.mapping.errors.filter { it.row == candidate.sourceRow }
                                        Row(
                                            Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalAlignment = Alignment.Top,
                                        ) {
                                            Checkbox(checked = candidate.sourceRow in current.selectedRows, onCheckedChange = { checked ->
                                                preview =
                                                    current.copy(
                                                        selectedRows =
                                                            if (checked) {
                                                                current.selectedRows + candidate.sourceRow
                                                            } else {
                                                                current.selectedRows -
                                                                    candidate.sourceRow
                                                            },
                                                    )
                                            })
                                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                Text(
                                                    "第 ${candidate.sourceRow} 行 · ${candidate.kind ?: "类型无效"} · ${candidate.amount?.let {
                                                        "${it.unscaled} / 10^${it.scale} ${it.currency}"
                                                    } ?: "金额无效"} · ${candidate.remark}",
                                                    style = MaterialTheme.typography.bodySmall,
                                                )
                                                rowErrors.take(2).forEach {
                                                    Text(
                                                        "第 ${it.row} 行 · ${it.code} · ${it.message}",
                                                        color = MaterialTheme.colorScheme.error,
                                                        style = MaterialTheme.typography.labelSmall,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                                IoSectionTitle("预检与确认", "建立待确认批次只做预检并暂存，不写入账本；确认后整批原子提交，失败不留部分记录。")
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    FilledTonalButton(
                                        enabled =
                                            batchId == null && current.selectedRows.isNotEmpty() &&
                                                current.mapping.errors.none {
                                                    it.row == 0 ||
                                                        (
                                                            it.row in current.selectedRows &&
                                                                it.code !in setOf("CATEGORY_BINDING_REQUIRED", "ACCOUNT_BINDING_REQUIRED")
                                                        )
                                                } &&
                                                accountIdentity == model.accountFileIdentity,
                                        onClick = {
                                            val currentSnapshot = snapshot
                                            if (currentSnapshot != null) {
                                                model.runFileIo({ repo, financeSnapshot ->
                                                    val mapped =
                                                        GuestFileImportCoordinator(
                                                            repo,
                                                        ).map(
                                                            current,
                                                            ZoneId.of(model.fileImportZone),
                                                            financeSnapshot,
                                                            accountBindings.toMap(),
                                                            categoryBindings.toMap(),
                                                            tagBindings.toMap(),
                                                        )
                                                    GuestFileImportCoordinator(repo).stage(mapped)
                                                }) { result ->
                                                    result
                                                        .onSuccess {
                                                            batchId = it
                                                            status = "批次已预检并暂存，确认后才会原子写入账本。"
                                                        }.onFailure {
                                                            status =
                                                                fileIoErrorMessage(it)
                                                        }
                                                }
                                            }
                                        },
                                    ) { Text("建立待确认批次") }
                                    Button(enabled = batchId != null, onClick = {
                                        val id = batchId ?: return@Button
                                        val digest = current.digest
                                        model.runFileIo({ repo, _ -> GuestFileImportCoordinator(repo).confirm(id, digest) }) { result ->
                                            result
                                                .onSuccess {
                                                    status =
                                                        "批次已原子提交。"
                                                    preview = null
                                                    batchId = null
                                                    model.discardFileIoStaged()
                                                    page =
                                                        FileIoPage.HISTORY
                                                    model.refresh()
                                                }.onFailure { status = fileIoErrorMessage(it) }
                                        }
                                    }) { Text("确认整批导入") }
                                    TextButton(onClick = {
                                        model.discardFileIoStaged()
                                        preview = null
                                        batchId = null
                                        page =
                                            FileIoPage.SOURCE
                                    }) { Text("取消预览") }
                                }
                            }
                        }
                    }
                }

                FileIoPage.HISTORY -> {
                    if (accountMode) {
                        IoGroup {
                            IoSectionTitle("账号导入历史", "仅已提交批次可撤销；暂存中的批次可按原操作标识确认、恢复或取消。")
                            LaunchedEffect(snapshot, batchId, status, accountIdentity) {
                                if (accountIdentity == model.accountFileIdentity) model.refreshAccountFileImportHistory()
                            }
                            if (accountHistory.isEmpty()) {
                                Text(
                                    "暂无账号导入批次。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            accountHistory.forEach { batch ->
                                val state = batch.state.substringBefore('{')
                                Card(Modifier.fillMaxWidth()) {
                                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text(importStateLabel(state), modifier = Modifier.weight(1f), color = importStateColor(state))
                                            Text(
                                                java.text.DateFormat
                                                    .getDateTimeInstance()
                                                    .format(java.util.Date(batch.createdAt)),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        Text(
                                            "${batch.rowCount} 行 · ${importStateDetail(state)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            if (state ==
                                                "COMMITTED"
                                            ) {
                                                TextButton(enabled = !model.state.busy && !model.state.syncPaused, onClick = {
                                                    reverseTarget =
                                                        UUID.fromString(batch.id)
                                                    showReverseDialog = true
                                                }) { Text("撤销整批") }
                                            }
                                            if (state in
                                                setOf("PREPARED_LOCAL", "STAGING", "COMMITTING", "CANCELLING", "ROLLING_BACK")
                                            ) {
                                                TextButton(
                                                    enabled =
                                                        !model.state.busy && !model.state.syncPaused,
                                                    onClick = {
                                                        val target = UUID.fromString(batch.id)
                                                        val complete: (Result<Unit>) -> Unit = complete@{ result ->
                                                            if (accountIdentity != model.accountFileIdentity) return@complete
                                                            status =
                                                                result.fold({ "批次状态已从服务端 canonical pull 恢复。" }, { fileIoErrorMessage(it) })
                                                            model.refreshAccountFileImportHistory()
                                                        }
                                                        when (state) {
                                                            "STAGING" -> model.commitAccountFileImport(target, complete)
                                                            else -> model.resumeAccountFileImport(target, complete)
                                                        }
                                                    },
                                                ) { Text(if (state == "STAGING") "确认提交批次" else "恢复批次") }
                                            }
                                            if (state in
                                                setOf("STAGING", "PREPARED_LOCAL")
                                            ) {
                                                TextButton(
                                                    enabled =
                                                        !model.state.busy && !model.state.syncPaused,
                                                    onClick = {
                                                        model.cancelAccountFileImport(UUID.fromString(batch.id)) { result ->
                                                            if (accountIdentity != model.accountFileIdentity) return@cancelAccountFileImport
                                                            status = result.fold({ "服务端暂存已取消。" }, { fileIoErrorMessage(it) })
                                                            model.refreshAccountFileImportHistory()
                                                        }
                                                    },
                                                ) { Text("取消暂存") }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        IoGroup {
                            IoSectionTitle("导入历史", "已提交批次可整批撤销；待确认批次不会写入账本。")
                            val history = model.fileIo.guestHistory
                            LaunchedEffect(snapshot, batchId, status) {
                                model.refreshGuestFileImportHistory()
                            }
                            if (history.isEmpty()) {
                                Text(
                                    "暂无导入批次。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            history.forEach { (batch, committedRows) ->
                                val state = batch.state.substringBefore('{')
                                Card(Modifier.fillMaxWidth()) {
                                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text(importStateLabel(state), modifier = Modifier.weight(1f), color = importStateColor(state))
                                            Text(
                                                java.text.DateFormat
                                                    .getDateTimeInstance()
                                                    .format(java.util.Date(batch.createdAt)),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        Text(
                                            if (state ==
                                                "COMMITTED"
                                            ) {
                                                "已写入 $committedRows 行"
                                            } else {
                                                "暂存 ${batch.rowCount} 行 · ${importStateDetail(state)}"
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            if (state ==
                                                "COMMITTED"
                                            ) {
                                                TextButton(onClick = {
                                                    reverseTarget = UUID.fromString(batch.id)
                                                    showReverseDialog =
                                                        true
                                                }) { Text("撤销整批") }
                                            }
                                            if (state == "PREVIEWED" &&
                                                batchId?.toString() == batch.id
                                            ) {
                                                TextButton(onClick = { page = FileIoPage.PREVIEW }) { Text("回到预览") }
                                            }
                                        }
                                    }
                                }
                            }
                            if (history.any { it.first.state.substringBefore('{') == "PREVIEWED" }) {
                                Text(
                                    "待确认批次尚未写入账本，也不会自动提交。",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                FileIoPage.EXPORT -> {
                    if (accountMode) {
                        IoGroup {
                            IoSectionTitle("导出当前账号账单", "仅导出当前已同步的服务器规范事实；本地待确认更改、清账/恢复未确认状态不会进入导出。")
                            ledgerName?.let {
                                Text(
                                    "账本：$it",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            val exportBlocked = pickerOutstanding || accountExportPending || exporting
                            val dateFieldsEnabled = !exportBlocked
                            val fromField: @Composable (Modifier) -> Unit = { modifier ->
                                OutlinedTextField(value = accountFromDate, onValueChange = {
                                    accountFromDate = it
                                }, label = {
                                    Text(
                                        "起始日期（含）",
                                    )
                                }, placeholder = {
                                    Text(
                                        "YYYY-MM-DD",
                                    )
                                }, singleLine = true, enabled = dateFieldsEnabled, modifier = modifier)
                            }
                            val throughField: @Composable (Modifier) -> Unit = { modifier ->
                                OutlinedTextField(value = accountThroughDate, onValueChange = {
                                    accountThroughDate = it
                                }, label = {
                                    Text(
                                        "结束日期（含）",
                                    )
                                }, placeholder = {
                                    Text(
                                        "YYYY-MM-DD",
                                    )
                                }, singleLine = true, enabled = dateFieldsEnabled, modifier = modifier)
                            }
                            if (wide) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    fromField(Modifier.weight(1f))
                                    throughField(Modifier.weight(1f))
                                }
                            } else {
                                fromField(Modifier.fillMaxWidth())
                                throughField(Modifier.fillMaxWidth())
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                AccountExportKind.entries.forEach { kind ->
                                    FilterChip(
                                        selected = kind in accountExportKinds,
                                        onClick = {
                                            accountExportKinds =
                                                if (kind in accountExportKinds) accountExportKinds - kind else accountExportKinds + kind
                                        },
                                        label = { Text(kind.label) },
                                        enabled = !exportBlocked,
                                    )
                                }
                            }
                            IoSectionTitle("导出格式", "CSV 兼容大多数表格软件；XLSX 便于继续用 Excel 编辑。两种格式都只包含当前十列。")
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                IoFormatCard(
                                    "CSV 格式",
                                    "通用表格格式",
                                    accountExportFormat == AccountExportFormat.CSV,
                                    !exportBlocked,
                                    Modifier.weight(1f),
                                ) {
                                    accountExportFormat =
                                        AccountExportFormat.CSV
                                }
                                IoFormatCard(
                                    "XLSX 格式",
                                    "Excel 工作簿",
                                    accountExportFormat == AccountExportFormat.XLSX,
                                    !exportBlocked,
                                    Modifier.weight(1f),
                                ) {
                                    accountExportFormat =
                                        AccountExportFormat.XLSX
                                }
                            }
                            val estimate = accountExportEstimate(snapshot, accountExportKinds, accountFromDate, accountThroughDate)
                            Text(
                                if (estimate !=
                                    null
                                ) {
                                    "预估记录数：$estimate 条（生成时以固定规范快照为准）"
                                } else {
                                    "填写有效的范围与类型后显示预估记录数。"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                "附件、费用/优惠分摊、退款/理赔或超出十列格式的记录会被明确拒绝，不会静默丢弃。当前不提供“包含附件”选项。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (pickerOutstanding ||
                                accountExportPending
                            ) {
                                Text(
                                    "已固定导出快照，等待系统文件选择；请完成保存位置选择。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(enabled = !exportBlocked, onClick = {
                                    model.beginAccountFileExport(accountExportFormat)
                                }) { Text(if (accountExportPending) "准备中…" else "生成文件") }
                                if (exporting) {
                                    TextButton(onClick = {
                                        model.cancelFileIo()
                                    }) { Text("取消导出") }
                                }
                            }
                            if (exporting) {
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                                Text("已处理 $exportDone 条记录，正在写入文件…", style = MaterialTheme.typography.bodySmall)
                            }
                            accountExportError?.let {
                                Text(
                                    it,
                                    color =
                                        if (it.startsWith(
                                                "已导出",
                                            )
                                        ) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.error
                                        },
                                )
                            }
                        }
                    } else {
                        IoGroup {
                            IoSectionTitle("导出账单", "导出当前账本的固定快照；生成前不会改动账本。")
                            ledgerName?.let {
                                Text(
                                    "账本：$it",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                "当前账本记录：${snapshot?.records?.size ?: 0} 条（生成时固定快照）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                "当前不提供日期或类型筛选；导出整本账本。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            IoSectionTitle("导出格式", "CSV 兼容大多数表格软件；XLSX 便于继续用 Excel 编辑。")
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                IoFormatCard("CSV 格式", "通用表格格式", exportMime == "text/csv", !exporting, Modifier.weight(1f)) {
                                    exportMime =
                                        "text/csv"
                                }
                                IoFormatCard("XLSX 格式", "Excel 工作簿", exportMime != "text/csv", !exporting, Modifier.weight(1f)) {
                                    exportMime =
                                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                                }
                            }
                            Text(
                                "折扣、多账户分摊、转账手续费/优惠、跨币种转账或关联人资料等超出十列格式的记录会被明确拒绝，不会静默丢弃。当前不提供“包含附件”选项。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(enabled = !exporting && !pickerOutstanding, onClick = {
                                    model.beginGuestFileExport(
                                        if (exportMime == GuestExportFormat.CSV.mime) GuestExportFormat.CSV else GuestExportFormat.XLSX,
                                    )
                                }) { Text("生成文件") }
                                if (exporting) TextButton(onClick = { model.cancelFileIo() }) { Text("取消导出") }
                            }
                            if (exporting) {
                                val ratio = if (exportTotal > 0) (exportDone.toFloat() / exportTotal).coerceIn(0f, 1f) else null
                                if (ratio !=
                                    null
                                ) {
                                    LinearProgressIndicator(progress = { ratio }, modifier = Modifier.fillMaxWidth())
                                } else {
                                    LinearProgressIndicator(Modifier.fillMaxWidth())
                                }
                                Text(
                                    if (exportTotal >
                                        0
                                    ) {
                                        "正在生成 $exportDone / $exportTotal 行"
                                    } else {
                                        "正在生成文件…"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    reverseTarget?.takeIf { showReverseDialog }?.let { id ->
        AlertDialog(onDismissRequest = {
            reverseTarget = null
            showReverseDialog = false
        }, title = { Text("撤销整个导入批次？") }, text = {
            Column {
                Text("将对批次执行完整依赖检查，并在同一事务中撤销全部记录。")
                androidx.compose.material3.OutlinedTextField(value = reverseReason, onValueChange = {
                    reverseReason = it
                }, label = { Text("撤销原因") }, modifier = Modifier.fillMaxWidth())
            }
        }, confirmButton = {
            TextButton(enabled = reverseReason.isNotBlank() && reverseReason.length <= 1000, onClick = {
                val target = id
                if (accountMode) {
                    model.rollbackAccountFileImport(target) { result ->
                        status = result.fold({ "批次已撤销并由 canonical pull 确认。" }, { it.message ?: "批次撤销失败；可按原操作标识重试" })
                        reverseTarget = null
                        showReverseDialog = false
                        model.refreshAccountFileImportHistory()
                    }
                } else {
                    model.runFileIo<Unit>({ repo, _ -> GuestFileImportCoordinator(repo).reverse(target, reverseReason) }) { result ->
                        status =
                            result.fold({ "批次已撤销。" }, { it.message ?: "批次撤销失败" })
                        reverseTarget = null
                        showReverseDialog = false
                        model.refresh()
                    }
                }
            }) { Text("确认撤销") }
        }, dismissButton = {
            TextButton(onClick = {
                reverseTarget = null
                showReverseDialog = false
            }) { Text("返回") }
        })
    }
}

private fun FileIoPage.subtitle(accountMode: Boolean): String =
    when (this) {
        FileIoPage.SOURCE -> if (accountMode) "选择文件并核对服务端暂存" else "选择本地 CSV 或 XLSX 文件"
        FileIoPage.PREVIEW -> "核对统计、映射与逐行错误"
        FileIoPage.HISTORY -> "查看批次状态与可恢复操作"
        FileIoPage.EXPORT -> "生成 CSV 或 XLSX 文件"
    }

private fun importStateLabel(state: String): String =
    when (state) {
        "COMMITTED" -> "导入成功"
        "PREVIEWED" -> "等待确认"
        "PREPARED_LOCAL" -> "等待上传"
        "STAGING" -> "上传暂存中"
        "COMMITTING" -> "提交中"
        "CANCELLING" -> "取消中"
        "ROLLING_BACK" -> "回滚中"
        "CANCELLED" -> "已取消"
        "ROLLED_BACK" -> "已回滚"
        "REVERSED" -> "已撤销"
        else -> state
    }

private fun importStateDetail(state: String): String =
    when (state) {
        "COMMITTED" -> "已写入账本"
        "PREVIEWED" -> "待确认，未写入账本"
        "PREPARED_LOCAL" -> "已在服务端准备，尚未提交"
        "STAGING" -> "服务端暂存中"
        "COMMITTING" -> "正在原子提交"
        "CANCELLING" -> "正在取消暂存"
        "ROLLING_BACK" -> "正在回滚"
        "CANCELLED" -> "已取消"
        "ROLLED_BACK" -> "已回滚"
        "REVERSED" -> "已撤销并恢复依赖"
        else -> "状态：$state"
    }

@Composable
private fun importStateColor(state: String) =
    when (state) {
        "COMMITTED" -> MaterialTheme.colorScheme.primary
        "PREVIEWED", "PREPARED_LOCAL", "STAGING" -> MaterialTheme.colorScheme.tertiary
        "REVERSED", "CANCELLED", "ROLLED_BACK" -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurface
    }

internal fun fileIoErrorMessage(failure: Throwable): String {
    val raw = failure.message
    return when {
        raw == null -> "文件操作失败"
        raw.contains("UNSUPPORTED_XLS") -> "不支持旧版 .xls 文件；请另存为 .xlsx 或 CSV 后重试。"
        raw.startsWith("LOSSY_EXPORT_UNSUPPORTED") -> "导出会丢失附件、优惠/费用或关联信息，已拒绝；请先调整相关记录。"
        raw == "EXPORT_MULTIPLE_CURRENCIES_UNSUPPORTED" -> "账本包含多种货币，当前 CSV/XLSX 导出不支持汇总。"
        raw == "TOO_MANY_ROWS" -> "记录超过单文件上限，无法导出。"
        else -> raw
    }
}

private fun accountExportEstimate(
    snapshot: FinanceSnapshot?,
    kinds: Set<AccountExportKind>,
    from: String,
    through: String,
): Int? {
    val current = snapshot ?: return null
    val filter =
        try {
            AccountExportFilter(
                from.takeIf(String::isNotBlank)?.let(LocalDate::parse),
                through.takeIf(String::isNotBlank)?.let(LocalDate::parse),
                kinds,
            )
        } catch (_: DateTimeParseException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
    return AccountFileExport.filterRecords(current, filter).size
}

@Composable
private fun IoPageTabs(
    page: FileIoPage,
    onSelect: (FileIoPage) -> Unit,
) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            FileIoPage.SOURCE to "来源",
            FileIoPage.PREVIEW to "预览",
            FileIoPage.HISTORY to "历史",
            FileIoPage.EXPORT to "导出",
        ).forEach { (target, label) ->
            FilterChip(selected = page == target, onClick = { onSelect(target) }, label = { Text(label) })
        }
    }
}

@Composable
private fun IoHero(
    title: String,
    detail: String,
) {
    androidx.compose.material3.Surface(color = summaryCardColor(), shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(18.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(detail, style = MaterialTheme.typography.bodySmall)
            }
            AppIcon(AppSymbol.TRANSFER, "文件导入导出", Modifier.size(36.dp))
        }
    }
}

@Composable
private fun IoSourceTile(
    title: String,
    detail: String,
    icon: AppSymbol,
    enabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Card(
        modifier.clickable(enabled = enabled) { onClick() },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AppIcon(icon, title, Modifier.size(28.dp))
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun IoFormatCard(
    title: String,
    detail: String,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier,
    onSelect: () -> Unit,
) {
    Card(
        modifier.clickable(enabled = enabled) { onSelect() },
        colors =
            CardDefaults.cardColors(
                containerColor = if (selected) summaryCardColor() else MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (selected) AppIcon(AppSymbol.CHECK, "已选择")
        }
    }
}

@Composable
private fun IoGroup(content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = { content() })
    }
}

@Composable
private fun IoSectionTitle(
    title: String,
    detail: String? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun IoMessage(message: String) {
    Card(Modifier.fillMaxWidth()) {
        Text(message, Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun IoStats(items: List<Triple<String, String, androidx.compose.ui.graphics.Color>>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        items.forEach { (value, label, color) ->
            Column(Modifier.weight(1f), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                Text(value, style = MaterialTheme.typography.titleLarge, color = color)
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun IoSteps(active: Int) {
    val steps = listOf("选择文件", "核对预览", "确认导入")
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        steps.forEachIndexed { index, label ->
            androidx.compose.material3.Surface(
                color =
                    if (index + 1 ==
                        active
                    ) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerLow
                    },
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(
                    "${index + 1}  $label",
                    Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

@Composable
private fun BindingPicker(
    title: String,
    suggested: UUID?,
    ids: List<UUID>,
    labels: Map<UUID, String>,
    value: UUID?,
    onSelect: (UUID) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, modifier = Modifier.weight(1f))
        TextButton(onClick = { expanded = true }) { Text(value?.let { labels[it] } ?: "选择绑定") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ids.distinct().forEach { id ->
                DropdownMenuItem(text = { Text(labels[id] ?: id.toString()) }, onClick = {
                    onSelect(id)
                    expanded =
                        false
                })
            }
        }
    }
}
