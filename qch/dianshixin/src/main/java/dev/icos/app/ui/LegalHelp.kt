package dev.icos.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.icos.app.BuildConfig
import dev.icos.app.R
import dev.icos.app.data.remote.AndroidRelease
import dev.icos.app.data.remote.ConsentRecord
import dev.icos.app.data.remote.LegalDocument
import dev.icos.app.data.remote.PublicPublicationClient
import dev.icos.app.data.remote.PublicationJson
import dev.icos.app.data.remote.PublicationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

private val publicationExecutor = Executors.newSingleThreadExecutor()

/** Published legal text plus the cache/error state both the first-run gate and help screen need. */
internal data class PublishedLegal(
    val privacy: LegalDocument? = null,
    val terms: LegalDocument? = null,
    val cached: Boolean = false,
    val errorCode: String? = null,
    val loading: Boolean = true,
)

/** Read-only publication load; it never records consent or substitutes offline text for a published version. */
internal suspend fun loadPublishedLegal(context: Context): PublishedLegal =
    withContext(Dispatchers.IO) {
        val api = PublicPublicationClient(context.applicationContext)
        val privacyRequest = async { api.legal("privacy") }
        val termsRequest = async { api.legal("terms") }
        val privacy = privacyRequest.await()
        val terms = termsRequest.await()
        PublishedLegal(
            privacy = (privacy as? PublicationResult.Available)?.value,
            terms = (terms as? PublicationResult.Available)?.value,
            cached =
                (privacy as? PublicationResult.Available)?.cached == true ||
                    (terms as? PublicationResult.Available)?.cached == true,
            errorCode = listOf(privacy, terms).filterIsInstance<PublicationResult.Unavailable>().firstOrNull()?.code,
            loading = false,
        )
    }

/**
 * Shared read-only dialog for the currently published agreements. Used by the help screen and by
 * auth screens; replacing auth's local loader with this keeps both views on one publication path.
 */
internal enum class PublishedLegalDocument { PRIVACY, TERMS }

@Composable
internal fun PublishedLegalDialog(
    document: PublishedLegalDocument? = null,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    var documents by remember { mutableStateOf<PublishedLegal?>(null) }
    var loadAttempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(loadAttempt) {
        documents = null
        documents = loadPublishedLegal(context)
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = {
            Text(
                when (document) {
                    PublishedLegalDocument.PRIVACY -> "隐私政策"
                    PublishedLegalDocument.TERMS -> "用户协议"
                    null -> "用户协议与隐私政策"
                },
            )
        },
        text = {
            Column(
                Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val loaded = documents
                if (loaded == null || loaded.loading) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    Text("正在加载协议…", style = MaterialTheme.typography.bodySmall)
                } else if (loaded.privacy == null && loaded.terms == null) {
                    Text("暂时无法加载协议，请检查网络后重试。")
                } else {
                    if (loaded.cached) HelpNotice("当前显示已缓存的协议，重新加载可获取最新版本。")
                    if (document != PublishedLegalDocument.TERMS) {
                        loaded.privacy?.let { body -> PublishedLegalBody("隐私政策", body) }
                            ?: Text("当前无法取得已发布的隐私政策正文。", color = MaterialTheme.colorScheme.error)
                    }
                    if (document != PublishedLegalDocument.PRIVACY) {
                        loaded.terms?.let { body -> PublishedLegalBody("用户协议", body) }
                            ?: Text("当前无法取得已发布的用户协议正文。", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("关闭") } },
        dismissButton = {
            val loaded = documents
            if (loaded != null && !loaded.loading && (loaded.cached || loaded.privacy == null || loaded.terms == null)) {
                TextButton(onClick = { loadAttempt++ }) { Text("重新加载") }
            }
        },
    )
}

@Composable
private fun PublishedLegalBody(
    kind: String,
    document: LegalDocument,
) {
    Text(kind, style = MaterialTheme.typography.titleSmall)
    Text(document.content, style = MaterialTheme.typography.bodySmall)
}

@Composable
internal fun FirstRunAgreementGate(onContinue: (networkAllowed: Boolean) -> Unit) {
    val context = LocalContext.current
    val scrollWholePage = LocalConfiguration.current.screenHeightDp < 700 || LocalDensity.current.fontScale > 1.3f
    var state by remember { mutableStateOf(PublishedLegal()) }
    var showDocument by remember { mutableStateOf<LegalDocument?>(null) }
    var accepted by remember { mutableStateOf(false) }
    var loadAttempt by remember { mutableIntStateOf(0) }
    var consentError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(loadAttempt) {
        accepted = false
        consentError = null
        state = PublishedLegal()
        state = loadPublishedLegal(context)
    }
    val privacy = state.privacy
    val terms = state.terms
    val documentsValid = privacy != null && terms != null
    val ready = PublicationJson.mayAcceptAgreement(documentsValid, state.loading, state.cached, state.errorCode)
    val alreadyAccepted = documentsValid && ConsentRecord.matches(context, privacy!!) && ConsentRecord.matches(context, terms!!)
    val networkConsent = ConsentRecord.hasNetworkConsent(context)
    val continueAsGuest = {
        if (ConsentRecord.setNetworkAllowed(context, false)) {
            onContinue(false)
        } else {
            consentError = "未能保存使用方式，请重试。"
        }
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .then(if (scrollWholePage) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier.fillMaxWidth().then(
                    if (scrollWholePage) Modifier else Modifier.weight(1f).verticalScroll(rememberScrollState()),
                ),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                FirstRunWelcomeSection()
                FirstRunFeatureSection()
            }
            Card(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("选择你的使用方式", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "联网使用可登录账号，同步账本与备份；游客记账仅保存在本机。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (state.loading) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text("正在加载用户协议与隐私政策…", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    AgreementDocumentRow("用户协议", terms) { showDocument = terms }
                    AgreementDocumentRow("隐私政策", privacy) { showDocument = privacy }
                    when {
                        state.cached -> HelpNotice("当前显示已缓存的协议，请重新加载后再开启联网使用。")
                        state.errorCode != null -> HelpNotice(
                            if (state.errorCode == "UNCONFIGURED") {
                                "当前安装包未配置联网服务，请使用联网版本。"
                            } else {
                                "暂时无法获取完整协议，请检查网络后重新加载。"
                            },
                            error = true,
                        )
                    }
                    if (!state.loading && !ready) {
                        OutlinedButton(
                            onClick = { loadAttempt++ },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            AppIcon(AppSymbol.REFRESH, modifier = Modifier.size(18.dp))
                            Text("重新加载协议")
                        }
                    }
                    if (ready && !alreadyAccepted) {
                        Row(
                            Modifier.fillMaxWidth().toggleable(
                                value = accepted,
                                role = Role.Checkbox,
                                onValueChange = { accepted = it },
                            ).padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(accepted, onCheckedChange = null)
                            Text("我已阅读并同意《用户协议》和《隐私政策》", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (ready && alreadyAccepted) Text("你已同意以上协议，可开启联网使用。", style = MaterialTheme.typography.bodySmall)
                    consentError?.let { HelpNotice(it, error = true) }
                    Button(
                        enabled = ready && (accepted || alreadyAccepted),
                        colors = primaryButtonColors(),
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            if (ConsentRecord.record(context, listOf(privacy!!, terms!!))) {
                                onContinue(true)
                            } else {
                                consentError = "未能保存协议同意记录，请重试。"
                            }
                        },
                    ) {
                        Text(when {
                            state.loading -> "正在加载协议…"
                            alreadyAccepted -> "开启联网使用"
                            else -> "同意并开启联网使用"
                        })
                    }
                    LaunchedEffect(alreadyAccepted, documentsValid, state.loading, state.cached, state.errorCode, networkConsent) {
                        if (PublicationJson.shouldAutoContinueAgreement(
                                alreadyAccepted,
                                documentsValid,
                                state.loading,
                                state.cached,
                                state.errorCode,
                                networkConsent,
                            )
                        ) {
                            onContinue(true)
                        }
                    }
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = continueAsGuest,
                    ) { Text("先使用本机游客账本") }
                }
            }
        }
    }
    showDocument?.let { doc ->
        AlertDialog(
            onDismissRequest = { showDocument = null },
            title = { Text(if (doc.kind == "privacy") "隐私政策" else "用户协议") },
            text = {
                Column(
                    Modifier.heightIn(max = 480.dp).fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(doc.content)
                }
            },
            confirmButton = { TextButton(onClick = { showDocument = null }) { Text("关闭") } },
        )
    }
}

@Composable
private fun AgreementDocumentRow(
    kind: String,
    document: LegalDocument?,
    onOpen: () -> Unit,
) {
    OutlinedButton(onClick = onOpen, enabled = document != null, modifier = Modifier.fillMaxWidth()) {
        Text(kind, modifier = Modifier.weight(1f), textAlign = TextAlign.Start)
        Text(if (document != null) "查看正文" else "等待加载", style = MaterialTheme.typography.labelSmall)
        AppIcon(AppSymbol.CHEVRON, modifier = Modifier.size(18.dp))
    }
}

@Composable
internal fun HelpAboutPanel(
    androidUpdatesEnabled: Boolean = true,
    feedbackEnabled: Boolean = false,
    feedbackAccountEnabled: Boolean = false,
    onOpenFeedback: () -> Unit = {},
    onOpenDataManagement: () -> Unit = {},
) {
    var showGuide by remember { mutableStateOf(false) }
    val faqRequester = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var update by remember { mutableStateOf<AndroidRelease?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var legalDocument by remember { mutableStateOf<PublishedLegalDocument?>(null) }
    val networkConsent = ConsentRecord.hasNetworkConsent(context)
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Card(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = summaryCardColor()),
        ) {
            Column(
                Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    Modifier.padding(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
                        Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                            Image(
                                painter = painterResource(R.drawable.dsx_logo),
                                contentDescription = null,
                                modifier = Modifier.size(44.dp),
                            )
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("v${BuildConfig.VERSION_NAME}（版本码 ${BuildConfig.VERSION_CODE}）", style = MaterialTheme.typography.bodySmall)
                        Text(
                            "专注记账，守护你的每一笔生活",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(
                        enabled = !busy && networkConsent && androidUpdatesEnabled,
                        onClick = {
                            busy = true
                            status = null
                            publicationExecutor.execute {
                                val result = PublicPublicationClient(context.applicationContext).update("stable")
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    busy = false
                                    when (result) {
                                        is PublicationResult.Available -> {
                                            update = result.value
                                            status = if (result.cached) "显示缓存的发布信息；本次在线检查未完成。" else "已检查 stable 发布渠道。"
                                        }

                                        is PublicationResult.Unavailable -> {
                                            status =
                                                when (result.code) {
                                                    "UNCONFIGURED" -> "未配置服务地址。"
                                                    "NOT_PUBLISHED" -> "当前没有已发布的 stable 更新信息。"
                                                    else -> "暂时无法查询更新。"
                                                }
                                        }
                                    }
                                }
                            }
                        },
                    ) {
                        if (busy) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            AppIcon(AppSymbol.REFRESH, modifier = Modifier.size(18.dp))
                            Text("检查更新")
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    HelpQuickAction(
                        modifier = Modifier.weight(1f),
                        symbol = AppSymbol.BOOK,
                        title = "使用指南",
                        caption = "快速上手",
                        onClick = { showGuide = true },
                    )
                    HelpQuickAction(
                        modifier = Modifier.weight(1f),
                        symbol = AppSymbol.TEMPLATE,
                        title = "常见问题",
                        caption = "常见疑问",
                        onClick = { scope.launch { faqRequester.bringIntoView() } },
                    )
                    HelpQuickAction(
                        modifier = Modifier.weight(1f),
                        symbol = AppSymbol.EDIT,
                        title = "意见反馈",
                        caption =
                            when {
                                !feedbackEnabled -> "暂不可用"
                                feedbackAccountEnabled -> "提交或查看"
                                else -> "需登录账号"
                            },
                        enabled = feedbackEnabled && feedbackAccountEnabled,
                        onClick = onOpenFeedback,
                    )
                }
            }
        }
        if (!androidUpdatesEnabled) HelpNotice("更新查询当前未开放。")
        if (!networkConsent) HelpNotice("需先在线取得并同意当前隐私政策与用户协议，之后才能检查更新。", error = true)
        status?.let { HelpNotice(it) }

        HelpSectionCard("常见问题", Modifier.bringIntoViewRequester(faqRequester)) {
            HelpRow(AppSymbol.LOCK, "如何保护数据？", "可在设置的“密码与隐私”中启用数字锁。")
            HelpDivider()
            HelpRow(AppSymbol.TEMPLATE, "游客数据在哪里？", "保存在本机设备；账号数据按实际显示的连接与同步状态使用自建服务。")
            HelpDivider()
            HelpRow(AppSymbol.REFRESH, "协议和更新从哪里来？", "正文以自建服务当前已发布版本为准；服务不可用时不会展示伪造正文。")
        }

        HelpSectionCard("诊断与数据管理") {
            HelpRow(AppSymbol.SETTINGS, "诊断信息导出", "在数据管理中运行并导出本地诊断", onClick = onOpenDataManagement) {
                AppIcon(AppSymbol.CHEVRON)
            }
        }

        HelpSectionCard("协议与说明") {
            HelpRow(
                AppSymbol.LOCK,
                "隐私政策",
                "查看当前已发布版本正文",
                onClick = { legalDocument = PublishedLegalDocument.PRIVACY },
            ) { AppIcon(AppSymbol.CHEVRON) }
            HelpDivider()
            HelpRow(
                AppSymbol.LOCK,
                "用户协议",
                "查看当前已发布版本正文",
                onClick = { legalDocument = PublishedLegalDocument.TERMS },
            ) { AppIcon(AppSymbol.CHEVRON) }
        }

        HelpSectionCard("版本更新") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                update?.let { release ->
                    Text("最新发布 ${release.versionName}（${release.versionCode}）", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "最低支持版本码 ${release.minimumSupported}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text("变更说明", style = MaterialTheme.typography.labelLarge)
                    Text(release.notes, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "APK SHA-256：${release.sha256}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "签名证书 SHA-256：${release.certificateSha256}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "发布下载地址：${release.downloadUrl}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (release.minimumSupported > BuildConfig.VERSION_CODE) {
                        HelpNotice("此版本已低于最低支持版本；下载前请核验发布者、APK 哈希和签名证书。", error = true)
                    } else if (release.versionCode > BuildConfig.VERSION_CODE) {
                        Text("发现新版本，可在系统浏览器中打开发布页。", style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text("当前已是已发布的最新版本。", style = MaterialTheme.typography.bodySmall)
                    }
                    if (release.versionCode > BuildConfig.VERSION_CODE) {
                        Button(
                            colors = primaryButtonColors(),
                            enabled = !busy,
                            onClick = {
                                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.downloadUrl))) }
                                    .onFailure { status = "无法打开系统浏览器。" }
                            },
                        ) {
                            AppIcon(AppSymbol.DOWNLOAD, modifier = Modifier.size(18.dp))
                            Text("在浏览器中打开发布页")
                        }
                    }
                } ?: Text(
                    if (busy) "正在查询 stable 发布渠道…" else "尚未查询；检查只读取发布信息，不会自动下载或安装。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    if (showGuide) {
        AlertDialog(
            onDismissRequest = { showGuide = false },
            title = { Text("使用指南") },
            text = {
                Text(
                    "导入账单：设置 > 数据管理 > 导入与导出，选择CSV/XLSX并先核对预览。\n\n" +
                        "备份：游客账本保存在本机，可从文件导出；账号账本请在同步与备份中查看当前可用操作。恢复前先阅读覆盖范围。\n\n" +
                        "保护数据：在密码与隐私中启用数字锁。锁定后账单和资产界面会隐藏。",
                )
            },
            confirmButton = { Button(onClick = { showGuide = false }) { Text("知道了") } },
        )
    }
    legalDocument?.let { document ->
        PublishedLegalDialog(onClose = { legalDocument = null }, document = document)
    }
}

@Composable
private fun HelpQuickAction(
    modifier: Modifier,
    symbol: AppSymbol,
    title: String,
    caption: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier.clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (enabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                    AppIcon(
                        symbol,
                        tint = if (enabled) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(
                caption,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
internal fun HelpSectionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Card(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(Modifier.padding(vertical = 4.dp), content = content)
        }
    }
}

@Composable
internal fun HelpRow(
    symbol: AppSymbol,
    title: String,
    caption: String? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
) {
    val modifier =
        if (onClick != null) {
            Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick)
        } else {
            Modifier.fillMaxWidth()
        }
    Row(
        modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppIcon(symbol, tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            caption?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        trailing()
    }
}

@Composable
internal fun HelpDivider() {
    Surface(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.fillMaxWidth().height(1.dp)) {}
}

@Composable
internal fun HelpNotice(
    text: String,
    error: Boolean = false,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(text, Modifier.padding(horizontal = 14.dp, vertical = 10.dp), style = MaterialTheme.typography.bodySmall)
    }
}
