package dev.icos.app.ui

import android.app.Activity
import android.app.Application
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.ContextWrapper
import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModelProvider
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import java.math.BigDecimal
import dev.icos.app.R
import dev.icos.app.data.local.FinanceClaimAllocation
import dev.icos.app.data.local.FinanceClaimSettlement
import dev.icos.app.data.local.FinanceCategory
import dev.icos.app.data.local.FinanceRecord
import dev.icos.app.data.local.FinanceRecordTemplate
import dev.icos.app.data.local.FinanceRefundReceipt
import dev.icos.app.data.local.FinanceSnapshot
import dev.icos.app.data.local.FinanceValidationException
import dev.icos.app.data.local.templateNameIsValid
import dev.icos.app.domain.ClaimReceipt
import dev.icos.app.domain.Direction
import dev.icos.app.domain.Money
import dev.icos.app.domain.normalizeNewSavingEntryRemark
import dev.icos.app.domain.PeriodResolver
import dev.icos.app.domain.RecordInput
import dev.icos.app.domain.RefundReceipt
import dev.icos.app.domain.RefundRecovery
import dev.icos.app.domain.RemainderAction
import dev.icos.app.domain.ReportPeriod
import dev.icos.app.domain.SavingKind
import dev.icos.app.domain.SavingIntervalUnit
import dev.icos.app.domain.SavingEntryKind
import dev.icos.app.domain.SavingTransferStrategy
import dev.icos.app.domain.calculateClaimRecovery
import dev.icos.app.domain.calculateRefundRecovery
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FinanceApp(
    model: FinanceViewModel,
    auth: AuthViewModel,
    appearance: AppearanceSettingsViewModel,
    lock: AppLockViewModel,
    receiptExport: ReceiptExport,
    onSessionReady: () -> Unit,
    notificationPermissionStatus: () -> String,
    requestNotificationPermission: () -> Unit,
) {
    // MainActivity already gates the lock and the agreement flow; this entry only renders the business page.
    FinanceAppContent(model, auth, appearance, lock, receiptExport, onSessionReady, notificationPermissionStatus, requestNotificationPermission)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun FinanceAppContent(
    model: FinanceViewModel,
    auth: AuthViewModel,
    appearance: AppearanceSettingsViewModel,
    lock: AppLockViewModel,
    receiptExport: ReceiptExport,
    onSessionReady: () -> Unit,
    notificationPermissionStatus: () -> String,
    requestNotificationPermission: () -> Unit,
) {
    val appearanceState = appearance.state
    val applicationContext = LocalContext.current.applicationContext
    val networkConsent = dev.icos.app.data.remote.ConsentRecord.hasNetworkConsent(applicationContext)
    var configuration by remember { mutableStateOf<dev.icos.app.data.remote.PublicConfiguration?>(null) }
    var configurationLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(networkConsent) {
        configurationLoaded = false
        configuration = if (networkConsent) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            (dev.icos.app.data.remote.PublicPublicationClient(applicationContext).configuration() as? dev.icos.app.data.remote.PublicationResult.Available)?.value
        } else null
        configurationLoaded = true
    }
    val feedbackEnabled = configurationLoaded && configuration?.feedbackEnabled != false
    val androidUpdatesEnabled = configurationLoaded && configuration?.androidUpdatesEnabled != false
    val darkTheme =
        when (appearanceState.settings.theme) {
            ThemePreference.SYSTEM -> isSystemInDarkTheme()
            ThemePreference.LIGHT -> false
            ThemePreference.DARK -> true
        }
    val colors = icosColorScheme(darkTheme, appearanceState.settings.themeColor)
    val imeVisible = WindowInsets.isImeVisible
    val activity = LocalContext.current.findActivity()
    SideEffect {
        activity?.let { currentActivity ->
            WindowCompat.getInsetsController(currentActivity.window, currentActivity.window.decorView).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
            // Temporarily allow screenshots while retaining the saved privacy preference.
            currentActivity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
    MaterialTheme(colorScheme = colors, typography = scaledTypography(appearanceState.settings.fontScale), shapes = MaterialTheme.shapes) {
        AppearanceProvider(appearanceState.settings) {
            val selected = model.selectedTab
            var settingsOpen by rememberSaveable { mutableStateOf(false) }
            var settingsSection by rememberSaveable { mutableStateOf<String?>(null) }
            var searchOpen by rememberSaveable { mutableStateOf(false) }
            var ledgerCalendarOpen by rememberSaveable { mutableStateOf(false) }
            var assetsSection by rememberSaveable { mutableStateOf("accounts") }
            var assetAmountsHidden by rememberSaveable { mutableStateOf(false) }
            BackHandler(enabled = settingsOpen) {
                if (settingsSection != null) settingsSection = null else settingsOpen = false
            }
            val state = model.displayState
            val snapshot = state.snapshot
            val savingDetail = snapshot?.savingPlans?.firstOrNull { selected == 3 && it.id == model.selectedSavingId }
            val snackbarHostState = remember { SnackbarHostState() }
            val authState = auth.state
            val accountSessionIdentity = auth.cachedDeviceIdentity
            val accountUserId = authState.user?.id?.takeIf { accountSessionIdentity?.owner == it }
            val sessionReadyForBinding = authState.ready && (authState.user == null || accountUserId != null)
            val remoteAccountUserId = accountUserId.takeIf { networkConsent && authState.confirmed }
            val remoteSessionIdentity = accountSessionIdentity.takeIf { remoteAccountUserId != null }
            val activity = LocalContext.current.findActivity() ?: error("Activity unavailable")
            val reminderActivity = activity as? dev.icos.app.MainActivity ?: error("MainActivity unavailable")
            val feedbackModel = remember(activity) {
                ViewModelProvider(
                    activity as androidx.lifecycle.ViewModelStoreOwner,
                    ViewModelProvider.AndroidViewModelFactory.getInstance(activity.application),
                )[FeedbackViewModel::class.java]
            }
            val backupApplication = LocalContext.current.applicationContext as Application
            val fxQuotesModel = remember(activity, backupApplication) {
                ViewModelProvider(
                    activity as androidx.lifecycle.ViewModelStoreOwner,
                    ViewModelProvider.AndroidViewModelFactory.getInstance(backupApplication),
                )[FxQuotesViewModel::class.java]
            }
            val backupSettingsModel = remember(activity, backupApplication) {
                ViewModelProvider(
                    activity as androidx.lifecycle.ViewModelStoreOwner,
                    ViewModelProvider.AndroidViewModelFactory.getInstance(backupApplication),
                )[BackupSettingsViewModel::class.java]
            }
            LaunchedEffect(remoteAccountUserId, remoteSessionIdentity, feedbackEnabled) {
                feedbackModel.sessionChanged(remoteAccountUserId.takeIf { feedbackEnabled }, remoteSessionIdentity.takeIf { feedbackEnabled })
            }
            LaunchedEffect(sessionReadyForBinding, accountUserId, accountSessionIdentity, auth.isGuestMode, snapshot?.ownerId) {
                if (!sessionReadyForBinding) return@LaunchedEffect
                model.onSessionChanged(accountUserId, accountSessionIdentity, auth.isGuestMode)
                val reminderOwner = accountUserId ?: snapshot?.ownerId
                if (reminderOwner != null) model.applyReminderOwner(reminderOwner, accountUserId != null, accountSessionIdentity)
                fxQuotesModel.sessionChanged(reminderOwner, accountUserId?.let { accountSessionIdentity })
            }
            LaunchedEffect(remoteAccountUserId, remoteSessionIdentity) {
                backupSettingsModel.sessionChanged(remoteAccountUserId, remoteSessionIdentity)
                if (remoteAccountUserId != null) backupSettingsModel.load()
            }
            val bound = model.boundSession
            ForegroundAccountSync(
                model,
                networkConsent && sessionReadyForBinding && !authState.busy && bound?.owner == accountUserId &&
                    bound?.identity == accountSessionIdentity && bound?.guestMode == false,
                accountSessionIdentity,
            )
            val authSettled = authState.ready && !authState.busy
            val receiptSession = verifiedReceiptSession(bound, authSettled, auth.isGuestMode, authState.user?.id, accountSessionIdentity)
            LaunchedEffect(bound, authSettled, receiptSession, snapshot?.ownerId, snapshot?.epochId, snapshot?.ledgerId) {
                receiptExport.invalidateIfStale(snapshot?.let(::receiptKey), receiptSession, authSettled)
                // Retry queued results and incoming files only after the live auth and applied binding agree.
                onSessionReady()
            }
            // A retained file request or preview reopens its settings page once the verified session is ready.
            val fileIoActive = model.fileIoRequestActive
            LaunchedEffect(fileIoActive, receiptSession) {
                if (fileIoActive && receiptSession != null && settingsSection != "导入导出") {
                    settingsOpen = true
                    settingsSection = "导入导出"
                }
            }
            if (!sessionReadyForBinding || bound == null || bound.owner != accountUserId || bound.identity != accountSessionIdentity?.takeIf { it.owner == accountUserId } || bound.guestMode != auth.isGuestMode) {
                // The binding effect has not confirmed this session yet; never paint the previous one first.
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("正在核对当前会话…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                return@AppearanceProvider
            }
            val undoRecordId = model.undoNewRecordId
            LaunchedEffect(undoRecordId) {
                undoRecordId?.let { id ->
                    val result =
                        snackbarHostState.showSnackbar(
                            message = "账单已保存",
                            actionLabel = "撤销",
                            withDismissAction = true,
                            duration = SnackbarDuration.Short,
                        )
                    if (result == SnackbarResult.ActionPerformed) {
                        model.undoNewRecord(id)
                    } else {
                        model.dismissUndoNewRecord(id)
                    }
                }
            }
            val tabs = listOf(R.string.tab_ledger, R.string.tab_statistics, R.string.tab_assets, R.string.tab_saving)
            val tabIcons = listOf(AppSymbol.BOOK, AppSymbol.STATISTICS, AppSymbol.WALLET, AppSymbol.SAVING)
            var ledgerMenuOpen by remember { mutableStateOf(false) }
            var moreMenuOpen by remember { mutableStateOf(false) }
            val ledgers = if (state.accountOwner == null) snapshot?.ledgers.orEmpty().filter { it.archivedAt == null }.map { it.id to it.name }
                else state.accountLedgers.map { it.id to it.name }
            val canSwitchLedger = ledgers.size > 1 && (state.accountOwner != null || state.guestMultiLedgerEnabled)
            val ledgerName = ledgers.firstOrNull { it.first == snapshot?.ledgerId }?.second ?: "日常账本"
            Scaffold(
                snackbarHost = { SnackbarHost(snackbarHostState) },
                topBar = {
                    var savingTitleHeight by remember(savingDetail?.id, savingDetail?.input?.name) { mutableStateOf(0.dp) }
                    TopAppBar(
                        expandedHeight = if (savingDetail != null && !settingsOpen) savingDetailTopBarHeight(savingTitleHeight) else TopAppBarDefaults.TopAppBarExpandedHeight,
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                        title = {
                            Column {
                                Row(
                                    Modifier.clickable(enabled = !settingsOpen && selected in 0..1 && canSwitchLedger && !state.busy) { ledgerMenuOpen = true },
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (savingDetail != null && !settingsOpen) {
                                        SavingDetailTitle(savingDetail.input.name, Modifier.weight(1f, fill = false)) { savingTitleHeight = it }
                                    } else {
                                        Text(if (settingsOpen) settingsSection ?: "设置" else if (selected == 0) ledgerName else stringResource(tabs[selected]),
                                            modifier = Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
                                    }
                                    if (!settingsOpen && selected == 0 && canSwitchLedger) AppIcon(AppSymbol.DOWN, "切换账本")
                                }
                                if (!settingsOpen && savingDetail == null && selected == 1) {
                                    Row(
                                        Modifier.clickable(enabled = canSwitchLedger && !state.busy) { ledgerMenuOpen = true },
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(ledgerName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        if (canSwitchLedger) AppIcon(AppSymbol.DOWN, "切换账本", Modifier.size(18.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                DropdownMenu(expanded = ledgerMenuOpen, onDismissRequest = { ledgerMenuOpen = false }) {
                                    ledgers.forEach { (id, name) ->
                                        DropdownMenuItem(text = { Text(name) }, onClick = {
                                            ledgerMenuOpen = false
                                            if (state.accountOwner == null) model.selectGuestLedger(id) else model.selectAccountLedger(id)
                                        }, enabled = !state.busy)
                                    }
                                }
                            }
                        },
                        navigationIcon = {
                            when {
                                settingsOpen || selected == 3 && model.selectedSavingId != null ->
                                    IconButton(onClick = {
                                        if (settingsOpen) {
                                            if (settingsSection != null) settingsSection = null else settingsOpen = false
                                        } else {
                                            model.closeSaving()
                                        }
                                    }) { AppIcon(AppSymbol.BACK, "返回") }
                                selected == 0 ->
                                    IconButton(onClick = auth::open) {
                                        AccountAvatar(auth.avatarBitmap, Modifier.size(36.dp), "账号")
                                    }
                            }
                        },
                        actions = {
                            if (!settingsOpen) {
                                savingDetail?.let { plan ->
                                    IconButton(onClick = { model.editSaving(plan) }, enabled = !state.busy && plan.state.name != "DELETED") {
                                        AppIcon(AppSymbol.EDIT, "编辑计划")
                                    }
                                }
                                if (selected == 0) {
                                    IconButton(onClick = { searchOpen = !searchOpen }) { AppIcon(AppSymbol.SEARCH, "搜索账单") }
                                    IconButton(onClick = { ledgerCalendarOpen = true }) { AppIcon(AppSymbol.CALENDAR, "收支日历") }
                                } else if (selected == 1) {
                                    IconButton(onClick = { model.selectTab(0); ledgerCalendarOpen = true }) {
                                        AppIcon(AppSymbol.CALENDAR, "打开收支日历")
                                    }
                                }
                                if (selected == 2 && assetsSection == "accounts") {
                                    IconButton(onClick = { assetAmountsHidden = !assetAmountsHidden }) {
                                        AppIcon(if (assetAmountsHidden) AppSymbol.EYE_OFF else AppSymbol.EYE, if (assetAmountsHidden) "显示资产金额" else "隐藏资产金额")
                                    }
                                    IconButton(onClick = model::addAccount, enabled = snapshot != null && !state.busy) {
                                        AppIcon(AppSymbol.ADD, "添加账户")
                                    }
                                }
                                if (selected == 3 && savingDetail == null && model.savingPlanDraft == null && model.savingEntryDraft == null) {
                                    IconButton(onClick = model::createSaving, enabled = !state.busy) {
                                        AppIcon(AppSymbol.ADD, "新建存钱计划")
                                    }
                                }
                                if ((selected != 2 || assetsSection != "accounts") && (selected != 3 || savingDetail != null)) {
                                    Box {
                                        IconButton(onClick = { moreMenuOpen = true }) { AppIcon(AppSymbol.MORE, "更多操作") }
                                        DropdownMenu(expanded = moreMenuOpen, onDismissRequest = { moreMenuOpen = false }) {
                                            savingDetail?.let { plan ->
                                                DropdownMenuItem(text = { Text(if (plan.state.name == "ACTIVE") "归档" else "恢复") }, leadingIcon = { AppIcon(AppSymbol.ARCHIVE) },
                                                    onClick = { moreMenuOpen = false; model.archiveSaving(plan, plan.state.name == "ACTIVE") }, enabled = !state.busy)
                                                DropdownMenuItem(text = { Text("删除计划") }, leadingIcon = { AppIcon(AppSymbol.DELETE) },
                                                    onClick = { moreMenuOpen = false; model.requestDeleteSaving(plan) }, enabled = !state.busy)
                                                HorizontalDivider()
                                            }
                                            DropdownMenuItem(text = { Text("设置") }, leadingIcon = { AppIcon(AppSymbol.SETTINGS) },
                                                onClick = { moreMenuOpen = false; settingsOpen = true })
                                            if (lock.state.configured) DropdownMenuItem(text = { Text("立即锁定") },
                                                onClick = { moreMenuOpen = false; lock.lock() })
                                        }
                                    }
                                }
                            }
                        },
                    )
                },
                bottomBar = {
                    if (!imeVisible && !settingsOpen && !(selected == 3 && model.selectedSavingId != null)) {
                        NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                            tabs.forEachIndexed { index, label ->
                                if (index == 2) {
                                    Surface(
                                        modifier = Modifier.padding(horizontal = 6.dp).size(56.dp).combinedClickable(
                                            enabled = snapshot != null && !state.busy, role = Role.Button,
                                            onClickLabel = "记一笔", onLongClickLabel = "打开账单模板",
                                            onClick = { model.editRecord() }, onLongClick = model::openTemplates,
                                        ).semantics { contentDescription = "记一笔" },
                                        shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    ) { Box(contentAlignment = Alignment.Center) { AppIcon(AppSymbol.ADD, modifier = Modifier.size(32.dp)) } }
                                }
                                NavigationBarItem(
                                    selected = selected == index,
                                    onClick = { model.selectTab(index) },
                                    icon = { AppIcon(tabIcons[index]) },
                                    label = {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text(stringResource(label))
                                            Box(Modifier.padding(top = 3.dp).size(width = 22.dp, height = 3.dp)
                                                .background(if (selected == index) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(2.dp)))
                                        }
                                    },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = MaterialTheme.colorScheme.primary, selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                        indicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant, unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    ),
                                )
                            }
                        }
                    }
                },
            ) { padding ->
                Column(
                    Modifier.fillMaxSize().padding(padding).imePadding().then(
                        if (settingsOpen && settingsSection != "账本与分类") Modifier.verticalScroll(remember(settingsSection) { androidx.compose.foundation.ScrollState(0) }) else Modifier,
                    ),
                ) {
                    if (model.savingRecordCount > 0) {
                        Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(18.dp))
                                Spacer(Modifier.padding(horizontal = 6.dp))
                                Text("正在保存 ${model.savingRecordCount} 笔账单，可继续记账")
                            }
                        }
                    }
                    model.failedRecordSaves.forEach { failure ->
                        Surface(color = MaterialTheme.colorScheme.errorContainer) {
                            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                Text(failure.message, color = MaterialTheme.colorScheme.onErrorContainer)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = { model.retryRecordSave(failure.save.record.id) }, enabled = !state.busy) { Text("重试保存") }
                                    TextButton(onClick = { model.restoreFailedRecordSave(failure.save.record.id) }, enabled = !state.busy) { Text("恢复草稿") }
                                }
                            }
                        }
                    }
                    if (settingsOpen) {
                        if (settingsSection == null) {
                            SettingsHome(feedbackEnabled, remoteAccountUserId != null, values = mapOf(
                                "外观" to when (appearanceState.settings.theme) { ThemePreference.SYSTEM -> "跟随系统"; ThemePreference.LIGHT -> "浅色"; ThemePreference.DARK -> "深色" },
                                "记账效率" to "${model.recordEfficiencySettings.decimalPlaces} 位小数",
                                "同步与备份" to if (accountUserId != null) "已登录" else "本机游客",
                            )) {
                                if (it == "账号资料") auth.open() else settingsSection = it
                            }
                        }
                        if (snapshot == null && settingsSection in setOf("账本与分类", "分类分享", "标签管理", "账户与统计", "语言与货币")) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(if (state.busy) "正在打开本地账本…" else "本地账本尚未打开，请重试。")
                                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                                TextButton(onClick = model::refresh, enabled = !state.busy) { AppIcon(AppSymbol.REFRESH); Text("重新打开账本") }
                            }
                        }
                        if (settingsSection == "外观") AppearanceSettingsPanel(appearanceState, appearance::update, snapshot = snapshot, zone = model.statisticsZone)
                        if (settingsSection == "记账效率") {
                            key(snapshot?.ownerId, snapshot?.ledgerId) {
                                val efficiency = model.recordEfficiencySettings
                                val availableAccounts = snapshot?.let(model::visibleAccounts).orEmpty()
                                    .filter { it.selectable && it.archivedAt == null }
                                val nextDraft = remember(
                                    snapshot,
                                    efficiency.defaultKind,
                                    efficiency.defaultAccountId,
                                    state.budget?.currency,
                                    availableAccounts.map { it.id },
                                ) {
                                    snapshot?.let(model::newRecordDraft)
                                }
                                RecordEfficiencySettingsPanel(
                                    efficiency,
                                    model::updateRecordEfficiencySettings,
                                    categories = snapshot?.let { current -> current.categories.filter { it.ledgerId == current.ledgerId } }.orEmpty(),
                                    owner = snapshot?.ownerId,
                                    accounts = availableAccounts,
                                    defaultDraft = nextDraft,
                                    enabled = snapshot != null && !state.busy,
                                    guest = state.accountOwner == null,
                                    onEdit = { model.editRecord() },
                                )
                            }
                        }
                        if (settingsSection == "时间与提醒") ReminderSettingsPanel(model, reminderActivity::reminderPermissionStatus, reminderActivity::requestReminderNotificationPermission)
                        if (settingsSection == "密码与隐私") AppLockSettingsPanel(lock)
                        if (settingsSection == "帮助与关于") HelpAboutPanel(
                            androidUpdatesEnabled,
                            feedbackEnabled = feedbackEnabled,
                            feedbackAccountEnabled = remoteAccountUserId != null,
                            onOpenFeedback = { settingsSection = "问题反馈" },
                            onOpenDataManagement = { settingsSection = "数据管理" },
                        )
                        if (settingsSection == "问题反馈" && feedbackEnabled) FeedbackPanel(feedbackModel, remoteAccountUserId)
                        if (settingsSection == "预算") BudgetPanel(model, onClose = { settingsSection = null })
                        if (settingsSection == "账本与分类") {
                        snapshot?.let {
                            LedgerCategoryPanel(
                                snapshot = it,
                                model = model,
                                enabled = !state.busy && (state.accountOwner == null ||
                                    !state.accountClearPending && !state.restoreBusy),
                                showLedgerManagement = state.accountOwner != null || state.guestMultiLedgerEnabled,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        }
                        if (settingsSection == "分类分享") snapshot?.let { CategorySharePanel(it, model, state.accountOwner != null) }
                        if (settingsSection == "标签管理") snapshot?.let { TagPanel(it, model, !state.busy && (state.accountOwner == null ||
                            !state.accountMutationPending && !state.syncPaused && !state.accountClearPending && !state.restoreBusy), state.error) }
                        if (settingsSection == "同步与备份") {
                        BackupSettingsPanel(backupSettingsModel, remoteAccountUserId)
                        SyncSettingsPanel(
                            state,
                            model::pauseAccountSync,
                            model::resumeAccountSync,
                            model::refresh,
                            model::loadBackups,
                            model::selectRestoreBackup,
                            model::restoreSelectedBackup,
                            model::retryRestoreSubmission,
                            model::loadSyncConflicts,
                            model::openSyncConflict,
                            model::dismissSyncConflictDetail,
                            model::setConflictEditedGroupJson,
                            model::setConflictReviewKeys,
                            model::reviewConflictDependencies,
                            model::resolveSyncConflict,
                            model.accountSyncPausePending,
                            model.accountSyncPauseRecoveryAvailable,
                            deviceSessions = auth.state.devices,
                            deviceBusy = auth.state.deviceBusy,
                            deviceError = auth.state.error,
                            onLoadDevices = auth::loadDevices,
                            onLoadMoreDevices = auth::loadMoreDevices,
                            onReloadAccount = model::reloadAccountData,
                        )
                        }
                        if (settingsSection == "账户与统计") snapshot?.let { LoanStatisticsSettings(it, model) }
                        if (settingsSection == "语言与货币") snapshot?.let { FxSettingsPanel(it, fxQuotesModel, accountSessionIdentity) }
                        if (settingsSection == "报销退款设置") RefundPreferencesPanel(snapshot?.businessPreferences, state.busy || state.accountMutationPending, state.error, model::setRefundPreferences)
                        if (settingsSection == "导入导出") FileIoPanel(model, snapshot, state.accountOwner != null, model.accountFileIdentity, model.incomingFile, model::consumeIncomingFile)
                        if (settingsSection == "数据管理") {
                            StorageDiagnosticsPanel(model)
                            ClearRecordsPanel(
                                state,
                                model,
                                onOpenFileIo = { settingsSection = "导入导出" },
                                onOpenBackups = { settingsSection = "同步与备份" },
                            )
                        }
                    } else {
                        if (state.accountOwner != null) {
                            if (state.accountLedgers.size > 1) {
                                Row(
                                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    state.accountLedgers.forEach { ledger ->
                                        FilterChip(
                                            selected = snapshot?.ledgerId == ledger.id,
                                            onClick = { model.selectAccountLedger(ledger.id) },
                                            label = { Text("${ledger.name} · ${ledger.currency}") },
                                            enabled = !state.busy,
                                        )
                                    }
                                }
                            }
                        }
                        if (snapshot == null) state.bootstrapProgress?.let { progress ->
                            Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(Modifier.size(18.dp))
                                    Spacer(Modifier.padding(horizontal = 6.dp))
                                    Text(progress, color = MaterialTheme.colorScheme.onSecondaryContainer)
                                }
                            }
                        }
                        if (snapshot == null) state.bootstrapError?.let { failure ->
                            Surface(color = MaterialTheme.colorScheme.errorContainer) {
                                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(failure, Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
                                    TextButton(onClick = model::refresh, enabled = !state.busy) { Text("重新打开账本") }
                                }
                            }
                        }
                        if (state.accountOwner != null) {
                            RecurrencePanel(state.accountRecurrences, state.templates, model)
                        }
                        if (state.pendingLocationCleanupId != null) {
                            Surface(color = MaterialTheme.colorScheme.errorContainer) {
                                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(state.locationOperationStatus ?: "已删除账单的位置文件待清理。", Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
                                    TextButton(onClick = model::retryRecordLocationCleanup, enabled = !state.busy) { Text("重试清理") }
                                }
                            }
                        }
                        if (state.error != null && (snapshot == null || state.error != state.bootstrapError && state.error != state.syncError?.message)) {
                            Surface(color = MaterialTheme.colorScheme.errorContainer) {
                                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(state.error, Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
                                    TextButton(onClick = model::clearError) { Text("关闭提示") }
                                }
                            }
                        }
                        if (snapshot == null) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                if (state.busy || state.restoreBusy) {
                                    CircularProgressIndicator()
                                } else {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("本地账本尚未打开")
                                        TextButton(onClick = model::refresh) { Text("重新打开账本") }
                                    }
                                }
                            }
                        } else {
                            when (selected) {
                                0 -> {
                                    RecordList(snapshot, model, searchOpen, ledgerCalendarOpen, { searchOpen = false }, { ledgerCalendarOpen = false },
                                        onImport = { settingsSection = "导入导出"; settingsOpen = true })
                                }

                                1 -> {
                                    Statistics(snapshot, model, receiptExport, bound)
                                }

                                2 -> Assets(snapshot, model, assetsSection, { assetsSection = it }, assetAmountsHidden) { assetAmountsHidden = !assetAmountsHidden }

                                else -> SavingPanel(snapshot, model, assetAmountsHidden) { assetAmountsHidden = !assetAmountsHidden }
                            }
                        }
                    }
                }
            }
            AuthPanel(auth)
            snapshot?.let { current ->
                val draft = receiptExport.draft
                val record =
                    draft
                        ?.takeIf { it.key == receiptKey(current) && it.session == bound }
                        ?.let { shown -> current.records.firstOrNull { it.id == shown.recordId } }
                if (record == null) {
                    if (draft != null) LaunchedEffect(draft) { receiptExport.close() }
                } else {
                    ReceiptPreviewDialog(current, record, java.time.ZoneId.of(model.statisticsZone), receiptExport) { receiptExport.close() }
                }
            }
            model.recurrenceDraft?.let { draft ->
                RecurrenceEditor(draft, state.templates, model)
            }
            model.recurrenceDeleteTarget?.let { plan ->
                AlertDialog(onDismissRequest = model::dismissDeleteRecurrence,
                    title = { Text("删除周期计划？") },
                    text = { Text("删除后会停止后续自动执行；已生成账单和 occurrence 历史将保留。此计划不可恢复。") },
                    confirmButton = { TextButton(onClick = model::deleteRecurrence, enabled = !state.busy) { Text("保留历史并删除") } },
                    dismissButton = { TextButton(onClick = model::dismissDeleteRecurrence, enabled = !state.busy) { Text("取消") } })
            }
            if (snapshot != null) {
                model.savingPlanDraft?.let { SavingPlanEditor(it, model) }
                model.savingEntryDraft?.let { SavingEntryEditor(it, snapshot, model) }
                model.savingDeleteTarget?.let { plan ->
                    var strategy by remember(plan.id) { mutableStateOf(SavingTransferStrategy.KEEP_TRANSFERS) }
                    AlertDialog(onDismissRequest = model::dismissDeleteSaving,
                        title = { Text("删除存钱计划？") },
                        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("关联转账如何处理？选择保留时账单仍在；选择删除时关联转账及其账户变动会一并撤回。")
                            listOf(SavingTransferStrategy.KEEP_TRANSFERS, SavingTransferStrategy.DELETE_TRANSFERS).forEach { option ->
                                Row(Modifier.fillMaxWidth().clickable { strategy = option }, verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = strategy == option, onClick = { strategy = option })
                                    Text(if (option == SavingTransferStrategy.KEEP_TRANSFERS) "保留关联转账" else "删除关联转账")
                                }
                            }
                        } },
                        confirmButton = { TextButton(onClick = { model.deleteSaving(strategy) }, enabled = !state.busy) { Text("确认删除") } },
                        dismissButton = { TextButton(onClick = model::dismissDeleteSaving, enabled = !state.busy) { Text("取消") } })
                }
                model.recordDraft?.let { RecordEditor(it, snapshot, model, efficiencySettings = model.recordEfficiencySettings) }
                model.templateDraft?.let { RecordEditor(it.record, snapshot, model, template = it, efficiencySettings = model.recordEfficiencySettings) }
                model.accountDraft?.let { AccountEditor(it, snapshot, model) }
            }
            if (model.templatesOpen && snapshot != null) TemplatePanel(snapshot, state.templates, model)
            model.accountArchiveTarget?.let { account ->
                AlertDialog(
                    onDismissRequest = model::dismissAccountArchive,
                    title = { Text(if (account.archivedAt == null) "归档账户？" else "恢复账户？") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                if (account.archivedAt == null && state.accountOwner != null) "“${account.name}”将归档、关闭新选并取消计入资产标记；历史账单、余额及负债合计会保留。"
                                else if (account.archivedAt == null) "“${account.name}”将归档并关闭新选，资产标记保留；历史账单、余额及负债合计会保留。"
                                else if (state.accountOwner != null) "“${account.name}”将恢复显示，保留当前资产标记，仍关闭新选。确认后可在账户页重新开放。"
                                else "“${account.name}”将恢复显示，但仍关闭新选；可在账户页单独重新开放。",
                            )
                            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        }
                    },
                    confirmButton = { TextButton(onClick = model::confirmAccountArchive, enabled = !state.busy &&
                        (state.accountOwner == null || !state.accountMutationPending && !state.syncPaused && !state.accountClearPending && !state.restoreBusy)) { Text("确认") } },
                    dismissButton = { TextButton(onClick = model::dismissAccountArchive, enabled = !state.busy) { Text("取消") } },
                )
            }
            model.deleteTemplateTarget?.let { template ->
                AlertDialog(
                    onDismissRequest = model::dismissDeleteTemplate,
                    title = { Text("删除模板？") },
                    text = { Text("将删除“${template.name}”模板。现有账单不会改变。") },
                    confirmButton = { TextButton(onClick = model::deleteTemplate, enabled = !state.busy) { Text("删除") } },
                    dismissButton = { TextButton(onClick = model::dismissDeleteTemplate, enabled = !state.busy) { Text("取消") } },
                )
            }
            model.deleteTarget?.let {
                AlertDialog(
                    onDismissRequest = model::dismissDelete,
                    title = { Text("删除这笔账单？") },
                    text = {
                        Column {
                            Text("该笔账单对应的账户变动、统计和预算计入会一并撤回。")
                            state.error?.let { message -> Text(message, color = MaterialTheme.colorScheme.error) }
                        }
                    },
                    confirmButton = { TextButton(onClick = model::deleteRecord, enabled = !state.busy) { Text("删除") } },
                    dismissButton = { TextButton(onClick = model::dismissDelete, enabled = !state.busy) { Text("取消") } },
                )
            }
        }
    }
}

@Composable
private fun RecurrencePanel(
    plans: List<dev.icos.app.data.local.CanonicalAccountRepository.RecurrenceSummary>,
    templates: List<FinanceRecordTemplate>,
    model: FinanceViewModel,
) {
    var statusFilter by rememberSaveable { mutableStateOf("ALL") }
    val visible = plans.filter { statusFilter == "ALL" || it.state == statusFilter || it.state !in recurrenceFilterStates }
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("周期计划", style = MaterialTheme.typography.titleMedium)
                Button(onClick = model::createRecurrence, enabled = !model.state.busy && templates.any { it.invalidReason == null && (it.serverRevision ?: 0) > 0 }, colors = primaryButtonColors()) { AppIcon(AppSymbol.ADD); Text("新建计划") }
            }
        }
        item {
            FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("ALL" to "全部", "RUNNING" to "进行中", "PAUSED" to "已暂停", "ENDED" to "已结束").forEach { (value, label) ->
                    FilterChip(statusFilter == value, onClick = { statusFilter = value }, enabled = !model.state.busy, label = { Text(label) })
                }
            }
        }
        if (visible.isEmpty()) item {
            Text(
                if (plans.isEmpty()) "还没有周期计划。选择账单模板并设置周期后，即可按计划生成账单。" else "当前筛选下没有计划，可切换「全部」查看。",
                Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        items(visible, key = { it.id }) { plan ->
            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(plan.templateName ?: "模板 ${plan.id.toString().take(8)}", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        Text(recurrenceStateLabel(plan.state), style = MaterialTheme.typography.bodySmall,
                            color = if (plan.state == "RUNNING") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(recurrenceRuleText(plan.frequency, plan.interval, plan.weekDays, plan.monthDays), style = MaterialTheme.typography.bodyMedium)
                    Text(recurrenceNextDueText(plan.nextDueAtMillis, plan.timezone), style = MaterialTheme.typography.bodySmall)
                    Text("已生成 ${plan.generatedCount} 笔 · 历史执行 ${plan.occurrenceCount} 次 · 时区 ${plan.timezone}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (plan.state in setOf("RUNNING", "PAUSED")) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { model.editRecurrence(plan) }, enabled = !model.state.busy && plan.serverRevision > 0) { Text("编辑") }
                            TextButton(onClick = { model.setRecurrenceState(plan, if (plan.state == "RUNNING") "PAUSED" else "RUNNING") }, enabled = !model.state.busy) { Text(if (plan.state == "RUNNING") "暂停" else "恢复") }
                            TextButton(onClick = { model.setRecurrenceState(plan, "ENDED") }, enabled = !model.state.busy) { Text("结束") }
                            TextButton(onClick = { model.requestDeleteRecurrence(plan) }, enabled = !model.state.busy) { Text("删除") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecurrenceEditor(draft: AccountRecurrenceDraft, templates: List<FinanceRecordTemplate>, model: FinanceViewModel) {
    val enabled = !model.state.busy && !draft.mutationQueued
    val choices = templates.filter { it.invalidReason == null && (it.serverRevision ?: 0) > 0 }.map { it.id to it.name }
    val startMillis = recurrenceStartMillis(draft.startLocalDateTime, draft.timezone)
    // 创建态开始时间不可解析（DST 跳变/坏时区/坏文本）时禁止保存，避免服务端静默挪到另一时刻。
    val startValid = draft.original != null || startMillis != null
    val initial = remember(draft) { recurrenceDraftSignature(draft) }
    val dismiss = rememberEditorDismiss(initial != recurrenceDraftSignature(draft), !model.state.busy, model::dismissRecurrence)
    EditorPage(onDismissRequest = dismiss, title = { Text(if (draft.original == null) "新建周期计划" else "编辑周期计划") },
        text = {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val intervalValue = draft.interval.toIntOrNull() ?: 1
                val week = draft.weekDays.split(',').mapNotNull { it.trim().toIntOrNull() }.toSet()
                val month = draft.monthDays.split(',').mapNotNull { it.trim().toIntOrNull() }
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = summaryCardColor())) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(choices.firstOrNull { it.first == draft.templateId }?.second ?: "未选择模板", style = MaterialTheme.typography.titleMedium)
                        Text(recurrenceRuleText(draft.frequency, intervalValue, week.toList(), month), style = MaterialTheme.typography.bodyMedium)
                        draft.original?.let { original ->
                            Text(recurrenceNextDueText(original.nextDueAtMillis, original.timezone), style = MaterialTheme.typography.bodySmall)
                            Text("开始于 ${recurrenceStartText(original.startLocalDateTime, original.timezone) ?: "${original.startLocalDateTime}（${original.timezone}）"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                RecurrenceSection("引用模板") {
                    if (choices.isEmpty()) Text("当前没有服务器确认的账单模板；请同步后再创建计划。")
                    else FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        choices.forEach { (id, name) ->
                            FilterChip(draft.templateId == id, onClick = { draft.templateId = id }, enabled = enabled, label = { Text(name) })
                        }
                    }
                    val selectedRevision = draft.templateId?.let { id -> templates.firstOrNull { it.id == id }?.serverRevision }
                    if (draft.original != null && draft.original.templateId == draft.templateId && draft.original.templateRevision != selectedRevision) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(draft.confirmTemplateRevision, { draft.confirmTemplateRevision = it }, enabled = enabled)
                            Text("模板内容已在服务器更新，确认用于后续生成的账单")
                        }
                    }
                }
                RecurrenceSection("重复规则") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("DAILY" to "每天", "WEEKLY" to "每周", "MONTHLY" to "每月", "YEARLY" to "每年").forEach { (value, label) ->
                            FilterChip(draft.frequency == value, onClick = { draft.selectFrequency(value) }, enabled = enabled, label = { Text(label) })
                        }
                    }
                    OutlinedTextField(draft.interval, { draft.interval = it }, label = { Text("重复间隔（正整数）") }, enabled = enabled, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
                    Text("每 $intervalValue ${recurrenceIntervalUnit(draft.frequency)}执行一次", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (draft.frequency == "WEEKLY") {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            // 点选会按已选星期重写列表；未点选时保留服务端原值。
                            recurrenceWeekDayNames.forEachIndexed { index, name ->
                                val day = index + 1
                                FilterChip(day in week, onClick = { draft.weekDays = (if (day in week) week - day else week + day).sorted().joinToString(",") }, enabled = enabled, label = { Text(name) })
                            }
                        }
                    }
                    if (draft.frequency in setOf("MONTHLY", "YEARLY")) {
                        OutlinedTextField(draft.monthDays, { draft.monthDays = it }, label = { Text("执行日（1-31，逗号分隔）") }, enabled = enabled, singleLine = true)
                        Text("留空表示按开始日期执行。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                RecurrenceSection(if (draft.original == null) "开始时间" else "生效方式") {
                    if (draft.original == null) {
                        OutlinedTextField(draft.timezone, { draft.timezone = it }, label = { Text("计划时区（如 Asia/Shanghai）") }, enabled = enabled, singleLine = true)
                        Text("计划按该时区的当地时间重复执行。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (startMillis == null) {
                            Text("开始时间或时区暂时无法解析，请修改后重新选择；在此之前无法保存。", color = MaterialTheme.colorScheme.error)
                            OutlinedTextField(draft.startLocalDateTime, { draft.startLocalDateTime = it }, label = { Text("开始时间（yyyy-MM-ddTHH:mm:ss）") }, enabled = enabled, singleLine = true)
                        } else {
                            DateTimeInput(startMillis, draft.timezone, enabled, model::showError) { millis ->
                                recurrenceLocalDateTime(millis, draft.timezone)?.let { draft.startLocalDateTime = it }
                            }
                        }
                    } else {
                        Text("开始时间：${recurrenceStartText(draft.startLocalDateTime, draft.timezone) ?: "${draft.startLocalDateTime}（${draft.timezone}）"}")
                        Text("开始时间和时区沿用现有计划；本次只调整后续规则。新规则从下一次尚未执行的时间开始生效，已生成账单和历史保持不变。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                RecurrenceSection("结束条件") {
                    val original = draft.original
                    OutlinedTextField(draft.endAt, { draft.endAt = it }, label = { Text(if (original == null) "结束时间（留空表示不限）" else "结束时间（留空沿用当前设置）") }, enabled = enabled, singleLine = true)
                    val endPreview = draft.endAt.trim().takeIf { it.isNotEmpty() }?.let { recurrenceEndText(it, draft.timezone) }
                    Text(
                        when {
                            draft.endAt.isNotBlank() && endPreview != null -> "结束于 $endPreview（按计划时区显示）"
                            draft.endAt.isNotBlank() -> "需要带时区偏移的时间，如 2026-06-01T00:00:00+08:00。"
                            original == null -> "不限结束时间。"
                            original.endAt == null -> "当前不限结束时间；留空仍为不限。"
                            else -> "当前结束于 ${recurrenceEndText(original.endAt, original.timezone) ?: original.endAt}；留空沿用该设置。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (draft.endAt.isNotBlank() && endPreview == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(draft.maxCount, { draft.maxCount = it }, label = { Text(if (original == null) "最多生成次数（留空表示不限）" else "最多生成次数（留空沿用当前上限）") }, enabled = enabled, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
                    if (original != null) {
                        Text(
                            original.maxCount?.let { "当前最多生成 $it 次；留空沿用该上限。" } ?: "当前不限次数；留空仍为不限。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (draft.original == null) {
                    RecurrenceSection("创建后状态") {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf("RUNNING" to "立即启用", "PAUSED" to "创建后暂停").forEach { (value, label) ->
                                FilterChip(draft.state == value, { draft.state = value }, enabled = enabled, label = { Text(label) })
                            }
                        }
                        Text("启用后由服务器按计划生成账单。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                model.state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(onClick = model::saveRecurrence, enabled = enabled && choices.isNotEmpty() && startValid, colors = primaryButtonColors()) {
                Text(if (model.state.busy) "保存中…" else "保存周期")
            }
        },
        dismissButton = { TextButton(onClick = dismiss, enabled = !model.state.busy) { Text("取消") } })
}

private data class SavingCurrencyTotals(val saved: BigDecimal, val target: BigDecimal, val planCount: Int)

@Composable
private fun SavingPanel(
    snapshot: FinanceSnapshot,
    model: FinanceViewModel,
    amountsHidden: Boolean,
    onToggleAmountsHidden: () -> Unit,
) {
    val plan = snapshot.savingPlans.firstOrNull { it.id == model.selectedSavingId }
    BackHandler(enabled = plan != null && model.savingEntryDraft == null && model.savingPlanDraft == null) { model.closeSaving() }
    var archived by rememberSaveable { mutableStateOf(false) }
    var cancelEntry by remember(snapshot.ownerId, snapshot.epochId, snapshot.ledgerId, plan?.id) { mutableStateOf<dev.icos.app.domain.SavingEntrySummary?>(null) }
    cancelEntry?.let { entry ->
        val linked = snapshot.records.firstOrNull { it.id == entry.linkedRecordId }?.input as? RecordInput.Transfer
        val names = snapshot.historicalAccountNames + snapshot.allAccounts.associate { it.id to it.name }
        AlertDialog(onDismissRequest = { if (!model.state.busy) cancelEntry = null },
            title = { Text("撤销这笔存钱记录？") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("将撤销${if (entry.kind == SavingEntryKind.DEPOSIT) "存入" else "取出"} ${if (amountsHidden) "••••" else display(entry.amount)}，并更新计划进度。")
                if (entry.linkedRecordId == null) Text("这笔记录没有关联转账，账户余额不会改变。")
                else {
                    Text("关联转账也会撤回，相关账户余额将相应恢复。此操作无法直接恢复。")
                    linked?.let { Text("${names[it.from.accountId] ?: "转出账户"} → ${names[it.to.accountId] ?: "转入账户"} · ${if (amountsHidden) "••••" else display(it.from.amount)}") }
                }
                model.state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } },
            confirmButton = { TextButton(onClick = { model.cancelSavingEntry(entry); cancelEntry = null }, enabled = !model.state.busy) { Text("确认撤销") } },
            dismissButton = { TextButton(onClick = { cancelEntry = null }, enabled = !model.state.busy) { Text("保留记录") } })
    }
    val activeTotalsByCurrency = remember(snapshot.savingPlans) {
        snapshot.savingPlans
            .filter { it.state == dev.icos.app.domain.SavingState.ACTIVE }
            .groupBy { it.input.target.currency }
            .mapValues { (_, plans) ->
                val saved = plans.fold(BigDecimal.ZERO) { total, item ->
                    total.add(BigDecimal.valueOf(item.saved.unscaled, item.saved.scale))
                }
                val target = plans.fold(BigDecimal.ZERO) { total, item ->
                    total.add(BigDecimal.valueOf(item.input.target.unscaled, item.input.target.scale))
                }
                SavingCurrencyTotals(saved, target, plans.size)
            }
    }
    if (plan == null) {
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!archived, onClick = { archived = false }, label = { Text("进行中") })
                    FilterChip(archived, onClick = { archived = true }, label = { Text("已归档") })
                }
            }
            val plans = snapshot.savingPlans.filter { if (archived) it.state == dev.icos.app.domain.SavingState.ARCHIVED else it.state == dev.icos.app.domain.SavingState.ACTIVE }
            if (!archived && activeTotalsByCurrency.isNotEmpty()) item {
                Card(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = summaryCardColor()),
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        activeTotalsByCurrency.entries.forEachIndexed { index, (currency, totals) ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("已存总额 · $currency", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    AmountText(
                                        if (amountsHidden) "••••" else ledgerSymbolAmount(totals.saved, currency),
                                        style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                                    )
                                }
                                if (index == 0) IconButton(onClick = onToggleAmountsHidden) {
                                    AppIcon(
                                        if (amountsHidden) AppSymbol.EYE_OFF else AppSymbol.EYE,
                                        if (amountsHidden) "显示存钱金额" else "隐藏存钱金额",
                                    )
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("进行中目标 ${totals.planCount} 个", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                Spacer(Modifier.width(1.dp).height(40.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)))
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("累计目标金额", style = MaterialTheme.typography.bodySmall)
                                    AmountText(
                                        if (amountsHidden) "••••" else ledgerSymbolAmount(totals.target, currency),
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    )
                                }
                            }
                            if (index < activeTotalsByCurrency.size - 1) HorizontalDivider()
                        }
                    }
                }
            }
            if (plans.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    AppIcon(AppSymbol.SAVING, tint = MaterialTheme.colorScheme.primary)
                    Text(if (archived) "还没有归档的计划" else "给下一个心愿，存一点期待", style = MaterialTheme.typography.titleMedium)
                    Text(if (archived) "归档后的计划和记录会保留在这里" else "创建计划，记录每一次靠近目标", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(plans, key = { it.id }) { item ->
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clickable { model.openSaving(item) }) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(item.input.name, style = MaterialTheme.typography.titleMedium)
                        Text("${savingKindLabel(item.input.kind)} · ${if (item.state.name == "ARCHIVED") "已归档" else "进行中"}")
                        AmountText(if (amountsHidden) "已存 •••• / 目标 ••••" else "已存 ${display(item.saved)} / 目标 ${display(item.input.target)}")
                        SavingProgress(item.saved, item.input.target)
                        Text(if (amountsHidden) "剩余 ••••" else "剩余 ${display(item.remaining)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        return
    }
    val entries = snapshot.savingEntries.filter { it.planId == plan.id }.sortedByDescending { it.occurredAtMillis }
    val cardCount = runCatching { savingCardCount(plan.input) }.getOrNull()
    val accountNames = snapshot.historicalAccountNames + snapshot.allAccounts.associate { it.id to it.name }
    val linkedRecords = remember(snapshot.ownerId, snapshot.epochId, snapshot.ledgerId, snapshot.records) { snapshot.records.associateBy { it.id } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item { Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (plan.input.kind == SavingKind.FLEXIBLE || plan.state.name != "ACTIVE")
                Text("${savingKindLabel(plan.input.kind)} · ${if (plan.state.name == "ARCHIVED") "已归档" else "进行中"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SavingPlanSummaryCard(plan, amountsHidden)
            if (plan.state.name == "ACTIVE") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { model.addSavingEntry(false) }, enabled = !model.state.busy,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer)) {
                        AppIcon(AppSymbol.DOWNLOAD)
                        Spacer(Modifier.width(8.dp))
                        Text("存入")
                    }
                    OutlinedButton(onClick = { model.addSavingEntry(true) }, enabled = !model.state.busy,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        AppIcon(AppSymbol.UPLOAD)
                        Spacer(Modifier.width(8.dp))
                        Text("取出")
                    }
                }
            }
            if (cardCount == null) Text("卡片数量超出显示范围", color = MaterialTheme.colorScheme.error)
        } }
        if (cardCount != null && cardCount > 0) item {
            Box(Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp)) {
                SavingPlanCardsSection(
                    scopeKey = "${snapshot.ownerId}:${snapshot.epochId}:${snapshot.ledgerId}:${plan.id}",
                    plan = plan, cardCount = cardCount, entries = entries, busy = model.state.busy, amountsHidden = amountsHidden,
                    cardAmount = { number -> savingCardAmount(plan.input, number, cardCount) },
                    onCard = { number, amount -> model.addSavingEntry(false, number, amount) },
                )
            }
        }
        item {
            Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                shape = if (entries.isEmpty()) RoundedCornerShape(16.dp) else RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("存取记录", style = MaterialTheme.typography.titleMedium)
                    if (entries.isEmpty()) Text("暂无存取记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        itemsIndexed(entries, key = { _, entry -> entry.id }) { index, entry ->
            val deposit = entry.kind == SavingEntryKind.DEPOSIT
            val linked = entry.linkedRecordId?.let { linkedRecords[it]?.input as? RecordInput.Transfer }
            val accountLabel = if (linked != null) "${accountNames[linked.from.accountId] ?: "转出账户"} → ${accountNames[linked.to.accountId] ?: "转入账户"}"
                else if (entry.linkedRecordId != null) "已关联转账" else "仅更新计划进度"
            val last = index == entries.lastIndex
            Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                shape = RoundedCornerShape(bottomStart = if (last) 16.dp else 0.dp, bottomEnd = if (last) 16.dp else 0.dp),
                color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HorizontalDivider()
                    Row(Modifier.padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                        Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                            AppIcon(if (deposit) AppSymbol.DOWNLOAD else AppSymbol.UPLOAD)
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("${if (deposit) "存入" else "取出"}${entry.cardIndex?.let { " · 第 $it 张" }.orEmpty()}", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                                AmountText(
                                    if (amountsHidden) "••••" else "${if (deposit) "+" else "−"}${moneyText(entry.amount)} ${entry.amount.currency}",
                                    Modifier.weight(1f),
                                    income = if (entry.cancelledAt == null) deposit else null,
                                    style = MaterialTheme.typography.titleSmall.copy(textAlign = TextAlign.End),
                                )
                            }
                            Text("$accountLabel · ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date(entry.occurredAtMillis))}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            entry.remark?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                            if (entry.cancelledAt != null) Text("已撤销", style = MaterialTheme.typography.bodySmall)
                            else TextButton(onClick = { model.clearError(); cancelEntry = entry }, enabled = !model.state.busy) { Text("撤销记录") }
                        }
                    }
                }
            }
        }
    }
}

private fun savingKindLabel(kind: SavingKind) = when (kind) {
    SavingKind.FLEXIBLE -> "灵活存钱"
    SavingKind.FIXED -> "固定金额"
    SavingKind.WEEK_52 -> "52 周递增"
    SavingKind.DAY_365 -> "365 天递增"
}

@Composable
private fun SavingPlanEditor(draft: SavingPlanDraft, model: FinanceViewModel) {
    val dismiss = rememberEditorDismiss(draft.hasChanges, !model.state.busy, model::dismissSavingPlan)
    val target = runCatching { moneyInput(draft.target, draft.currency) }.getOrNull()
    val progress = target?.let {
        runCatching { dev.icos.app.domain.PlanCalculator.savingProgress(it,
            draft.original?.saved ?: moneyInput(draft.initialSaved, draft.currency), emptyList(), emptyList()) }.getOrNull()
    }
    val rampCount = when (draft.kind) { SavingKind.WEEK_52 -> 52; SavingKind.DAY_365 -> 365; else -> 0 }
    val rampFirst = runCatching { moneyInput(draft.firstAmount, draft.currency) }.getOrNull()
    val rampIncrement = runCatching { moneyInput(draft.increment, draft.currency) }.getOrNull()
    val rampTotal = if (rampCount > 0 && rampFirst != null && rampIncrement != null) runCatching {
        dev.icos.app.domain.PlanCalculator.savingRamp(rampFirst, rampIncrement, rampCount)
    }.getOrNull() else null
    val currencyValid = runCatching { moneyInput("0", draft.currency) }.isSuccess
    val nameError = if ((draft.name.isNotBlank() || draft.target.isNotBlank()) && (draft.name.isBlank() || draft.name.trim().length > 100)) "请输入1–100字的有效计划名称" else null
    val currencyError = if (!currencyValid) "请输入有效币种代码" else null
    val targetError = when {
        !currencyValid || (draft.name.isBlank() && draft.target.isBlank()) -> null
        target == null || target.unscaled <= 0 -> "目标金额须大于0，最多支持6位小数"
        rampTotal != null && !target.sameValue(rampTotal) -> "目标金额需与计划总额一致"
        else -> null
    }
    val initialError = if (draft.original == null && currencyValid && runCatching { moneyInput(draft.initialSaved, draft.currency) }.isFailure) "请输入非负的有效初始金额" else null
    val firstError = if (draft.kind != SavingKind.FLEXIBLE && currencyValid && draft.firstAmount.isNotBlank() && (rampFirst == null || rampFirst.unscaled <= 0)) "请输入大于0的有效首期金额" else null
    val incrementError = if (rampCount > 0 && currencyValid && draft.increment.isNotBlank() && rampIncrement == null) "请输入非负递增金额，最多6位小数" else null
    val manualError = if (draft.kind == SavingKind.FLEXIBLE && !draft.allowManualEntries) "灵活计划需要允许手动存入" else null
    val inputValidation = runCatching { draft.toValidatedInput() }
    val canSave = !model.state.busy && inputValidation.isSuccess
    val formError = model.state.error ?: nameError ?: currencyError ?: targetError ?: initialError ?: firstError ?: incrementError ?: manualError
        ?: (inputValidation.exceptionOrNull() as? dev.icos.app.data.local.FinanceValidationException)?.message?.takeIf { draft.hasChanges }
        ?: if (draft.hasChanges && inputValidation.isFailure) "请检查未填写或不符合规则的计划信息" else null
    EditorPage(onDismissRequest = dismiss, title = { Text(if (draft.original == null) "新建存钱计划" else "编辑存钱计划") }, error = formError,
        text = { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (draft.original == null) {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val columns = if (maxWidth >= 360.dp) 4 else 2
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SavingKind.values().toList().chunked(columns).forEach { kinds ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                kinds.forEach { kind ->
                                    FilterChip(
                                        selected = draft.kind == kind,
                                        onClick = { draft.kind = kind },
                                        enabled = !model.state.busy,
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(16.dp),
                                        colors = FilterChipDefaults.filterChipColors(containerColor = MaterialTheme.colorScheme.surface, selectedContainerColor = summaryCardColor()),
                                        label = {
                                            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                AppIcon(when (kind) { SavingKind.FLEXIBLE -> AppSymbol.SAVING; SavingKind.WEEK_52 -> AppSymbol.STATISTICS; else -> AppSymbol.CALENDAR })
                                                Text(when (kind) { SavingKind.FLEXIBLE -> "灵活"; SavingKind.FIXED -> "固定"; SavingKind.WEEK_52 -> "52 周"; SavingKind.DAY_365 -> "365 天" })
                                                Text(when (kind) { SavingKind.FLEXIBLE -> "随时存取"; SavingKind.FIXED -> "定期存钱"; SavingKind.WEEK_52 -> "每周递增"; SavingKind.DAY_365 -> "每日存钱" }, style = MaterialTheme.typography.bodySmall)
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            } else Text("${savingKindLabel(draft.kind)}（规则和初始金额不可更改）")
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = summaryCardColor())) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(draft.name, { draft.name = it }, modifier = Modifier.fillMaxWidth(), label = { Text("计划名称") }, singleLine = true,
                        isError = nameError != null, supportingText = if (nameError != null) { { Text("名称须为1–100字", maxLines = 1, overflow = TextOverflow.Ellipsis) } } else null)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(draft.target, { draft.target = it }, modifier = Modifier.weight(2f), label = { Text("目标金额") }, singleLine = true, textStyle = MaterialTheme.typography.headlineSmall, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            isError = targetError != null, supportingText = if (targetError != null) { { Text(when { target == null -> "金额格式无效"; target.unscaled <= 0 -> "金额须大于0"; else -> "须等于总额" }, maxLines = 1, overflow = TextOverflow.Ellipsis) } } else null)
                        OutlinedTextField(draft.currency, { draft.currency = it.uppercase(Locale.ROOT) }, modifier = Modifier.weight(1f), label = { Text("币种") }, singleLine = true,
                            isError = currencyError != null, supportingText = if (currencyError != null) { { Text("无效", maxLines = 1) } } else null)
                    }
                    OutlinedTextField(draft.initialSaved, { draft.initialSaved = it }, modifier = Modifier.fillMaxWidth(), label = { Text("初始已存") }, singleLine = true, enabled = draft.original == null, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        isError = initialError != null, supportingText = if (initialError != null) { { Text(initialError, maxLines = 1, overflow = TextOverflow.Ellipsis) } } else null)
                    progress?.let {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(if (draft.original == null) "初始已存预览" else "当前已存", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                AmountText("${moneyText(it.saved)} ${it.saved.currency}", style = MaterialTheme.typography.titleMedium)
                            }
                            Column(Modifier.weight(1f)) {
                                Text("还需存入", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                AmountText("${moneyText(it.remaining)} ${it.remaining.currency}", style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                }
            }
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (draft.kind != SavingKind.FLEXIBLE) {
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            val amountFields: @Composable (Modifier) -> Unit = { fieldModifier ->
                                OutlinedTextField(draft.firstAmount, { draft.firstAmount = it }, modifier = fieldModifier, label = { Text(if (rampCount > 0) "首期存入金额" else "固定每期金额") }, singleLine = true, enabled = draft.original == null, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    isError = firstError != null, supportingText = if (firstError != null) { { Text(firstError, maxLines = 1, overflow = TextOverflow.Ellipsis) } } else null)
                                if (rampCount > 0) OutlinedTextField(draft.increment, { draft.increment = it }, modifier = fieldModifier, label = { Text("每期递增金额") }, singleLine = true, enabled = draft.original == null, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    isError = incrementError != null, supportingText = if (incrementError != null) { { Text(incrementError, maxLines = 1, overflow = TextOverflow.Ellipsis) } } else null)
                            }
                            if (maxWidth >= 320.dp) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { amountFields(Modifier.weight(1f)) }
                            else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { amountFields(Modifier.fillMaxWidth()) }
                        }
                        if (draft.kind == SavingKind.FIXED) {
                            FlowRow { SavingIntervalUnit.values().forEach { unit -> FilterChip(draft.intervalUnit == unit, { draft.intervalUnit = unit }, enabled = draft.original == null && !model.state.busy, label = { Text(when(unit) { SavingIntervalUnit.DAY -> "天"; SavingIntervalUnit.WEEK -> "周"; SavingIntervalUnit.MONTH -> "月"; SavingIntervalUnit.YEAR -> "年" }) }) } }
                            OutlinedTextField(draft.intervalStep, { draft.intervalStep = it }, modifier = Modifier.fillMaxWidth(), label = { Text("间隔") }, enabled = draft.original == null && !model.state.busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        }
                    }
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        FlowRow(maxItemsInEachRow = if (maxWidth >= 420.dp) 2 else 1, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.weight(1f)) { SavingDateField("开始日期", draft.startDate, enabled = draft.original == null && !model.state.busy, optional = draft.kind == SavingKind.FLEXIBLE) { draft.startDate = it } }
                            Box(Modifier.weight(1f)) { SavingDateField("目标日期", draft.endDate, enabled = !model.state.busy && (draft.original == null || draft.original.input.startDate != null || draft.original.input.endDate != null), optional = true) { draft.endDate = it } }
                        }
                    }
                    if (draft.original == null && draft.kind == SavingKind.FLEXIBLE) {
                        Text("可不设置日期；设置目标日期时需同时选择开始日期", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else if (draft.original != null && draft.original.input.startDate == null) {
                        Text(if (draft.original.input.endDate == null) "此计划创建时未设置开始日期，无法新增目标日期"
                            else "此计划创建时未设置开始日期；目标日期可修改或清除，清除后无法再设置",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (rampTotal != null && rampFirst != null && rampIncrement != null) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Text("计划预览", style = MaterialTheme.typography.titleMedium)
                        val start = runCatching { java.time.LocalDate.parse(draft.startDate) }.getOrNull()
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            FlowRow(maxItemsInEachRow = if (maxWidth >= 320.dp) 3 else 1, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                (1..3).forEach { number ->
                                    val amount = savingRampCardAmount(rampFirst, rampIncrement, number)
                                    val date = start?.let { runCatching { if (draft.kind == SavingKind.WEEK_52) it.plusWeeks((number - 1).toLong()) else it.plusDays((number - 1).toLong()) }.getOrNull() }
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text("第${number}期", style = MaterialTheme.typography.labelMedium)
                                        Text(date?.toString() ?: "日期待设置", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        AmountText("${moneyText(amount)} ${amount.currency}", style = MaterialTheme.typography.titleMedium)
                                    }
                                }
                            }
                        }
                        Text("${rampCount}期总额 ${moneyText(rampTotal)} ${rampTotal.currency}", style = MaterialTheme.typography.bodyMedium)
                    }
                    Row(Modifier.fillMaxWidth().toggleable(value = draft.allowManualEntries, enabled = !model.state.busy, role = Role.Checkbox, onValueChange = { draft.allowManualEntries = it }), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(draft.allowManualEntries, onCheckedChange = null, enabled = !model.state.busy)
                        Text("允许无卡片序号手动存入")
                    }
                    if (manualError != null) Text(manualError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        } },
        confirmButton = { Button(onClick = model::saveSavingPlan, enabled = canSave, colors = primaryButtonColors()) { Text(if (model.state.busy) "保存中…" else "保存计划") } },
        bottomContent = {
            Button(onClick = model::saveSavingPlan, enabled = canSave, colors = primaryButtonColors(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(if (model.state.busy) "保存中…" else "保存计划")
            }
        },
        dismissButton = { TextButton(onClick = dismiss, enabled = !model.state.busy) { Text("取消") } })
}

@Composable
private fun SavingEntryEditor(draft: SavingEntryDraft, snapshot: FinanceSnapshot, model: FinanceViewModel) {
    val remarkValidation = remember(draft.remark) { runCatching { normalizeNewSavingEntryRemark(draft.remark) } }
    val normalizedRemark = remarkValidation.getOrNull()
    val remarkError = remarkValidation.exceptionOrNull()?.let { it.message ?: "备注无效" }
    val visibleAccounts = model.visibleAccounts(snapshot)
    val accounts = visibleAccounts.filter { it.selectable && it.archivedAt == null && it.currency == draft.plan.input.target.currency }
    val inputValidation = runCatching { draft.toValidatedInput(visibleAccounts) }
    val canSave = !model.state.busy && inputValidation.isSuccess
    val inputError = (inputValidation.exceptionOrNull() as? dev.icos.app.data.local.FinanceValidationException)?.message?.takeIf { draft.hasChanges }
    val dismiss = rememberEditorDismiss(draft.hasChanges, !model.state.busy, model::dismissSavingEntry)
    val amountKeypad: @Composable () -> Unit = {
        AmountKeyboard(draft.amount, !model.state.busy, model.recordEfficiencySettings, confirmBelow = true, confirmButton = {
            Button(onClick = model::saveSavingEntry, enabled = canSave, colors = primaryButtonColors(),
                modifier = Modifier.fillMaxSize(), shape = RoundedCornerShape(28.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp)) {
                Text(if (model.state.busy) "保存中…" else "保存记录", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
        }) { draft.amount = it }
    }
    EditorPage(onDismissRequest = dismiss, title = { Text(if (draft.withdrawal) "取出" else "存入") }, error = remarkError ?: model.state.error ?: inputError,
        text = { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            RecordAmountInput("金额 · ${draft.plan.input.target.currency}", draft.amount, !model.state.busy, model.recordEfficiencySettings, showKeyboard = false) { draft.amount = it }
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (draft.withdrawal) "取出自" else "存入到", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(draft.plan.input.name, style = MaterialTheme.typography.titleMedium)
                    Text("已存 ${display(draft.plan.saved)} · 目标 ${display(draft.plan.input.target)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!draft.withdrawal && draft.plan.input.kind != SavingKind.FLEXIBLE) OutlinedTextField(draft.cardIndex, { draft.cardIndex = it }, modifier = Modifier.fillMaxWidth(), label = { Text("卡片序号（留空为手动存入）") }, enabled = !model.state.busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    DateTimeInput(draft.occurredAtMillis, TimeZone.getDefault().id, !model.state.busy, model::showError) { draft.occurredAtMillis = it }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SavingEntryRemarkField(draft.remark, { draft.remark = it }, !model.state.busy, remarkError, normalizedRemark?.codePointCount(0, normalizedRemark.length) ?: 0)
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = draft.linkTransfer, enabled = !model.state.busy, role = Role.Checkbox, onValueChange = { draft.linkTransfer = it }), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(draft.linkTransfer, onCheckedChange = null, enabled = !model.state.busy)
                        Text("同时创建关联转账", Modifier.weight(1f))
                    }
                    if (draft.linkTransfer) {
                        Text("转出和转入账户必须不同，金额与本次存取一致。")
                        accounts.forEach { account ->
                            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = draft.fromAccountId == account.id, enabled = !model.state.busy, role = Role.RadioButton, onClick = { draft.fromAccountId = account.id }), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(draft.fromAccountId == account.id, onClick = null, enabled = !model.state.busy)
                                Text("转出：${account.name}")
                            }
                            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = draft.toAccountId == account.id, enabled = !model.state.busy, role = Role.RadioButton, onClick = { draft.toAccountId = account.id }), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(draft.toAccountId == account.id, onClick = null, enabled = !model.state.busy)
                                Text("转入：${account.name}")
                            }
                        }
                    }
                }
            }
        } },
        confirmButton = { Button(onClick = model::saveSavingEntry, enabled = canSave, colors = primaryButtonColors()) { Text(if (model.state.busy) "保存中…" else "保存记录") } },
        bottomContent = if (model.recordEfficiencySettings.amountInputMode == AmountInputMode.NUMERIC) null else amountKeypad,
        dismissButton = { TextButton(onClick = dismiss, enabled = !model.state.busy) { Text("取消") } })
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

@Composable
private fun TemplatePanel(
    snapshot: FinanceSnapshot,
    templates: List<FinanceRecordTemplate>,
    model: FinanceViewModel,
) {
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var kindFilter by rememberSaveable { mutableStateOf("") }
    val names =
        remember(snapshot.allAccounts, snapshot.historicalAccountNames) {
            snapshot.historicalAccountNames + snapshot.allAccounts.associate { it.id to it.name }
        }
    val categories =
        remember(snapshot.categories, snapshot.allCategories, model.categoryStyleRevision) {
            (snapshot.categories + snapshot.allCategories).associateBy { it.id }
        }
    fun kindOf(template: FinanceRecordTemplate) =
        when (template.input) {
            is RecordInput.Expense -> "EXPENSE"
            is RecordInput.Income -> "INCOME"
            is RecordInput.Transfer -> "TRANSFER"
        }
    fun kindLabel(template: FinanceRecordTemplate) =
        when (kindOf(template)) {
            "EXPENSE" -> "支出"
            "INCOME" -> "收入"
            else -> "转账"
        }
    val visibleTemplates =
        remember(templates, query, kindFilter) {
            templates.filter { template ->
                (kindFilter.isEmpty() || kindOf(template) == kindFilter) &&
                    (query.isBlank() || template.name.contains(query.trim(), ignoreCase = true))
            }
        }
    EditorPage(
        onDismissRequest = model::dismissTemplates,
        title = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("账单模板", Modifier.weight(1f))
                IconButton(onClick = { searchOpen = !searchOpen; if (!searchOpen) query = "" }, enabled = !model.state.busy) {
                    AppIcon(AppSymbol.SEARCH, "搜索模板")
                }
            }
        },
        text = {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (searchOpen) OutlinedTextField(
                    query, { query = it }, Modifier.fillMaxWidth(),
                    placeholder = { Text("搜索模板名称") },
                    leadingIcon = { AppIcon(AppSymbol.SEARCH, "搜索模板") },
                    singleLine = true, enabled = !model.state.busy,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("" to "全部", "EXPENSE" to "支出", "INCOME" to "收入", "TRANSFER" to "转账").forEach { (key, label) ->
                        FilterChip(
                            selected = kindFilter == key,
                            onClick = { kindFilter = key },
                            enabled = !model.state.busy,
                            label = { Text("$label (${templates.count { key.isEmpty() || kindOf(it) == key }})") },
                        )
                    }
                }
                model.state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                when {
                    templates.isEmpty() ->
                        Text("还没有模板。保存模板不会记账，选择模板后仍需保存账单。", style = MaterialTheme.typography.bodyMedium)
                    visibleTemplates.isEmpty() ->
                        Text("没有匹配的模板。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else ->
                        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(visibleTemplates, key = { it.id }) { template ->
                                val category = template.categoryId?.let(categories::get)
                                val amount =
                                    when (val input = template.input) {
                                        is RecordInput.Expense -> input.gross
                                        is RecordInput.Income -> input.amount
                                        is RecordInput.Transfer -> input.from.amount
                                    }
                                val accountNames =
                                    when (val input = template.input) {
                                        is RecordInput.Expense -> input.allocations.map { it.accountId }
                                        is RecordInput.Income -> input.allocations.map { it.accountId }
                                        is RecordInput.Transfer -> listOf(input.from.accountId, input.to.accountId)
                                    }.distinct().mapNotNull(names::get).distinct()
                                val categoryLabel =
                                    category?.name ?: when {
                                        template.categoryId == null && template.input is RecordInput.Transfer -> "无分类"
                                        template.categoryId == null -> "未设置分类"
                                        else -> "分类不可用"
                                    }
                                val canUse =
                                    !model.state.busy && template.invalidReason == null && model.templateVisibleInCurrentLedger(template)
                                var menuOpen by remember(template.id) { mutableStateOf(false) }
                                Column(
                                    Modifier.fillMaxWidth()
                                        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                                        .clickable(enabled = canUse) { model.useTemplate(template) }
                                        .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        if (category != null) {
                                            val style =
                                                rememberCategoryStyle(context, snapshot.ownerId, category, model.categoryStyleRevision)
                                            CategoryStyleBadge(style)
                                        } else {
                                            Box(
                                                Modifier.size(30.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp)),
                                                contentAlignment = Alignment.Center,
                                            ) { AppIcon(AppSymbol.TEMPLATE, "无分类") }
                                        }
                                        Column(Modifier.weight(1f)) {
                                            Text(template.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(
                                                "${kindLabel(template)} · $categoryLabel" + (if (accountNames.isEmpty()) "" else " · ${accountNames.joinToString("、")}"),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        Text(display(amount), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                        Box {
                                            IconButton(onClick = { menuOpen = true }, enabled = !model.state.busy) {
                                                AppIcon(AppSymbol.MORE, "模板操作")
                                            }
                                            DropdownMenu(menuOpen, onDismissRequest = { menuOpen = false }) {
                                                DropdownMenuItem(
                                                    text = { Text("编辑") },
                                                    leadingIcon = { AppIcon(AppSymbol.EDIT) },
                                                    enabled = !model.state.busy,
                                                    onClick = {
                                                        menuOpen = false
                                                        model.editTemplate(template)
                                                    },
                                                )
                                                DropdownMenuItem(
                                                    text = { Text("删除") },
                                                    leadingIcon = { AppIcon(AppSymbol.DELETE) },
                                                    enabled = !model.state.busy,
                                                    onClick = {
                                                        menuOpen = false
                                                        model.requestDeleteTemplate(template)
                                                    },
                                                )
                                            }
                                        }
                                    }
                                    template.invalidReason?.let {
                                        Text(
                                            "不可使用：$it",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                            }
                        }
                }
            }
        },
        confirmButton = {
            Button(onClick = { model.editTemplate() }, enabled = !model.state.busy, colors = primaryButtonColors()) {
                AppIcon(AppSymbol.ADD)
                Text("新建模板")
            }
        },
        dismissButton = { TextButton(onClick = model::dismissTemplates, enabled = !model.state.busy) { Text("关闭") } },
        bottomContent = {
            Button(
                onClick = { model.editTemplate() },
                enabled = !model.state.busy,
                colors = primaryButtonColors(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                AppIcon(AppSymbol.ADD)
                Text("新建模板")
            }
        },
    )
}

@Composable
private fun RecordDetailRoute(
    snapshot: FinanceSnapshot,
    record: FinanceRecord,
    model: FinanceViewModel,
    onDismiss: () -> Unit,
    onRefund: (FinanceRecord) -> Unit,
) {
    var locationOpen by remember(snapshot.ownerId, snapshot.epochId, record.id, record.currentEventId, record.localRevision) { mutableStateOf(false) }
    val names = remember(snapshot.allAccounts, snapshot.historicalAccountNames) {
        snapshot.historicalAccountNames + snapshot.allAccounts.associate { it.id to it.name }
    }
    val input = record.input
    val recordWritable = record.localRevision > 0 && (model.state.accountOwner == null || record.accountMutationSupported)
    val writable = recordWritable && !model.state.busy && (model.state.accountOwner == null ||
        !model.state.accountClearPending && !model.state.restoreBusy)
    val postedClaim = snapshot.claimSettlements.any { it.status == "POSTED" && it.allocations.any { allocation -> allocation.originRecordId == record.id } }
    RecordDetailPage(
        record = record,
        ownerId = snapshot.ownerId,
        category = record.categoryId?.let { id -> snapshot.allCategories.firstOrNull { it.id == id } },
        accountNames = names,
        ledgerName = snapshot.ledgers.firstOrNull { it.id == snapshot.ledgerId }?.name.orEmpty(),
        timezone = record.timezone,
        tags = record.tagIds.mapNotNull { id -> snapshot.tags.firstOrNull { it.id == id }?.name },
        claimLabel = when (record.claimState) {
            "PENDING" -> "待报销"
            "PARTIAL" -> "部分报销"
            "SETTLED" -> "已报销"
            "NONE" -> "未报销"
            else -> "报销处理中"
        },
        error = model.state.error,
        enabled = writable,
        canEdit = recordWritable,
        canDelete = recordWritable,
        canToggleClaim = writable && input is RecordInput.Expense && record.claimState in setOf("NONE", "PENDING"),
        canRefund = writable && input is RecordInput.Expense && !postedClaim,
        hasPostedClaim = postedClaim,
        excludedStatistics = when (input) {
            is RecordInput.Expense -> !input.countInStatistics
            is RecordInput.Income -> !input.countInStatistics
            is RecordInput.Transfer -> false
        },
        excludedBudget = input is RecordInput.Expense &&
            (!input.countInBudget || categoryExcludesBudget(record.categoryId, snapshot.allCategories)),
        onDismiss = onDismiss,
        onEdit = { model.editRecord(record) },
        onCopyRecord = { model.copyRecord(record) },
        onDelete = { onDismiss(); model.requestDelete(record) },
        onRefund = { model.clearError(); onRefund(record) },
        onToggleClaim = { model.toggleClaim(record) },
        onLocation = { locationOpen = true },
    )
    if (locationOpen) {
        AlertDialog(
            onDismissRequest = { if (!model.state.busy) locationOpen = false },
            title = { Text("账单位置") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (model.state.accountOwner != null) Text("账号账本暂不支持维护本机位置。")
                    RecordLocationPanel(
                        record = record, ownerId = snapshot.ownerId, epochId = snapshot.epochId,
                        enabled = writable && model.state.accountOwner == null,
                        operationError = model.state.locationOperationStatus, refreshKey = model.state.locationRevision,
                        cleanupFailed = model.state.pendingLocationCleanupId != null,
                        onSave = model::saveRecordLocation, onDelete = model::deleteRecordLocation,
                        onRetryCleanup = model::retryRecordLocationCleanup,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { locationOpen = false }, enabled = !model.state.busy) { Text("完成") } },
        )
    }
}

@Composable
private fun RecordList(
    snapshot: FinanceSnapshot,
    model: FinanceViewModel,
    searchOpen: Boolean,
    calendarOpen: Boolean,
    closeSearch: () -> Unit,
    closeCalendar: () -> Unit,
    onImport: () -> Unit,
) {
    val names = remember(snapshot.allAccounts, snapshot.historicalAccountNames) {
        snapshot.historicalAccountNames + snapshot.allAccounts.associate { it.id to it.name }
    }
    val categories = remember(snapshot.categories, model.categoryStyleRevision) { snapshot.categories.associate { it.id to it.name } }
    var query by rememberSaveable { mutableStateOf("") }
    var appliedQuery by remember { mutableStateOf("") }
    var history by rememberSaveable(stateSaver = searchHistorySaver) { mutableStateOf<List<String>>(emptyList()) }
    var filtersOpen by remember { mutableStateOf(false) }
    var appliedFilters by rememberSaveable(stateSaver = recordFiltersSaver) { mutableStateOf(model.rememberedSearchFilters) }
    var draftFilters by rememberSaveable(stateSaver = recordFiltersSaver) { mutableStateOf(model.rememberedSearchFilters) }
    var selectedSort by rememberSaveable { mutableStateOf(model.rememberedSearchSort) }
    var selectedIds by remember { mutableStateOf<List<String>>(emptyList()) }
    var detailRecordId by remember(snapshot.ownerId, snapshot.epochId, snapshot.ledgerId) { mutableStateOf<String?>(null) }
    val detailRecord = snapshot.records.firstOrNull { it.id.toString() == detailRecordId }
    LaunchedEffect(detailRecordId, snapshot.records) { if (detailRecord == null) detailRecordId = null }

    fun setSearchFilters(
        filters: RecordFilters,
        sort: String,
    ) {
        appliedFilters = filters
        selectedSort = sort
        if (model.recordEfficiencySettings.rememberSearchFilters) model.saveSearchPreferences(filters, sort)
    }

    fun resetSearchFilters() {
        val empty = RecordFilters()
        draftFilters = empty
        setSearchFilters(empty, SORT_TIME_DESCENDING)
    }
    var batchDeleteTargets by remember { mutableStateOf<List<FinanceRecord>?>(null) }
    var claimSettlementTargets by remember { mutableStateOf<List<FinanceRecord>?>(null) }
    var cancelClaimTarget by remember { mutableStateOf<FinanceClaimSettlement?>(null) }
    var refundTarget by remember { mutableStateOf<FinanceRecord?>(null) }
    var cancelRefundTarget by remember { mutableStateOf<Pair<FinanceRefundReceipt, FinanceRecord>?>(null) }
    val selectedTargets = snapshot.records.filter { it.id.toString() in selectedIds }
    val selectedCurrency = (selectedTargets.firstOrNull()?.input as? RecordInput.Expense)?.gross?.currency
    val canSettleSelection =
        selectedIds.size in 1..50 && selectedTargets.size == selectedIds.size && selectedCurrency != null &&
            selectedTargets.all { record ->
                val expense = record.input as? RecordInput.Expense
                expense != null && record.claimState in setOf("PENDING", "PARTIAL") && expense.gross.currency == selectedCurrency
            }
    BackHandler(enabled = searchOpen || selectedIds.isNotEmpty()) {
        if (selectedIds.isNotEmpty()) selectedIds = emptyList()
        else { query = ""; appliedQuery = ""; closeSearch() }
    }
    val context = LocalContext.current
    val todayKey = remember(model.statisticsZone) { dateText(System.currentTimeMillis(), model.statisticsZone).substringBefore(' ') }
    var selectedMonth by rememberSaveable(snapshot.ledgerId) {
        mutableStateOf(java.time.YearMonth.now(java.time.ZoneId.of(model.statisticsZone)).toString())
    }
    var monthPickerOpen by rememberSaveable { mutableStateOf(false) }
    val month = java.time.YearMonth.parse(selectedMonth)
    val zone = java.time.ZoneId.of(model.statisticsZone)
    val hasDateFilter = appliedFilters.fromDate.isNotEmpty() || appliedFilters.throughDate.isNotEmpty()
    fun rangeFor(filters: RecordFilters): dev.icos.app.domain.PeriodRange {
        val from = filters.fromDate.takeIf { it.isNotEmpty() }
        val through = filters.throughDate.takeIf { it.isNotEmpty() }
        if (from == null && through == null) {
            return dev.icos.app.domain.PeriodRange(
                month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli(),
                month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli(),
            )
        }
        return dev.icos.app.domain.PeriodRange(
            from?.let { java.time.LocalDate.parse(it).atStartOfDay(zone).toInstant().toEpochMilli() } ?: Long.MIN_VALUE,
            through?.let { java.time.LocalDate.parse(it).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() } ?: Long.MAX_VALUE,
        )
    }
    val displayRange = remember(selectedMonth, appliedFilters, zone) { rangeFor(appliedFilters) }
    // Draft preview reuses the applied-query pipeline so the dialog count matches what applying would show.
    val draftPreviewCount =
        remember(draftFilters, appliedQuery, snapshot.records, snapshot.tags, names, categories, selectedMonth, zone) {
            // PeriodRange rejects inverted dates, so only count drafts that already pass validation.
            if (recordFiltersError(draftFilters) != null) {
                -1
            } else {
                val range = rangeFor(draftFilters)
                snapshot.records.count { record ->
                    recordMatchesSearch(
                        record,
                        appliedQuery,
                        names,
                        record.categoryId?.let(categories::get),
                        record.tagIds.mapNotNull { id -> snapshot.tags.firstOrNull { it.id == id }?.name },
                    ) && recordMatchesFilters(record, draftFilters, model.statisticsZone) && range.contains(record.occurredAtMillis)
                }
            }
        }
    var calendarDay by rememberSaveable { mutableStateOf(todayKey) }
    val recordsByDay =
        remember(snapshot.records, model.statisticsZone) {
            snapshot.records.groupBy { dateText(it.occurredAtMillis, model.statisticsZone).substringBefore(' ') }
        }
    val currencies =
        remember(snapshot.records) {
            snapshot.records
                .flatMap(::recordCurrencies)
                .distinct()
                .sorted()
        }

    fun pickDate(
        current: String,
        onPicked: (String) -> Unit,
    ) {
        val calendar = Calendar.getInstance()
        if (current.isNotEmpty()) {
            SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).parse(current)?.let { calendar.time = it }
        }
        DatePickerDialog(context, { _, year, month, day ->
            onPicked(String.format(Locale.ROOT, "%04d-%02d-%02d", year, month + 1, day))
        }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)).show()
    }
    LaunchedEffect(query) {
        delay(250)
        appliedQuery = query
    }

    fun rememberSearch() {
        val value = query.trim()
        if (value.isNotEmpty()) {
            history = (listOf(value) + history.filterNot { it.equals(value, ignoreCase = true) }).take(8)
            appliedQuery = query
        }
    }
    val activeFilterCount = appliedFilters.activeCount()
    val effectiveSort =
        if (selectedSort == SORT_AMOUNT_ASCENDING && appliedFilters.currency.isEmpty()) SORT_TIME_DESCENDING else selectedSort
    val matchingRecords =
        remember(snapshot.records, snapshot.tags, names, categories, appliedQuery, appliedFilters, displayRange, model.statisticsZone) {
            snapshot.records.filter { record ->
                recordMatchesSearch(
                    record,
                    appliedQuery,
                    names,
                    record.categoryId?.let(categories::get),
                    record.tagIds.mapNotNull { id -> snapshot.tags.firstOrNull { it.id == id }?.name },
                ) &&
                    recordMatchesFilters(record, appliedFilters, model.statisticsZone) && displayRange.contains(record.occurredAtMillis)
            }
        }
    val visibleRecords =
        remember(matchingRecords, effectiveSort, appliedFilters.currency) {
            sortSearchRecords(matchingRecords, effectiveSort, appliedFilters.currency)
        }
    val statistics = remember(visibleRecords) { searchStatistics(visibleRecords) }
    val timelineRows =
        remember(visibleRecords, effectiveSort, model.statisticsZone, appliedQuery, appliedFilters) {
            recordTimelineRows(
                visibleRecords,
                effectiveSort,
                model.statisticsZone,
                appliedQuery.isNotBlank() || appliedFilters.activeCount() > 0,
            )
        }
    LaunchedEffect(appliedQuery, appliedFilters) { selectedIds = emptyList() }
    LaunchedEffect(visibleRecords) {
        val visibleIds = visibleRecords.map { it.id.toString() }.toSet()
        selectedIds = selectedIds.filter(visibleIds::contains)
    }

    fun toggleSelection(id: String) {
        if (model.state.busy) return
        selectedIds =
            when {
                id in selectedIds -> {
                    selectedIds - id
                }

                selectedIds.size < 500 -> {
                    selectedIds + id
                }

                else -> {
                    model.showError("一次最多选择500笔账单")
                    selectedIds
                }
            }
    }
    val filterSummary =
        remember(appliedFilters, snapshot.accounts, categories, snapshot.tags) {
            buildList {
                when (appliedFilters.type) {
                    "EXPENSE" -> add("支出")
                    "INCOME" -> add("收入")
                    "TRANSFER" -> add("转账")
                }
                listOfNotNull(appliedFilters.fromDate.takeIf(String::isNotEmpty), appliedFilters.throughDate.takeIf(String::isNotEmpty))
                    .joinToString(" 至 ")
                    .takeIf(String::isNotEmpty)
                    ?.let(::add)
                snapshot.accounts
                    .firstOrNull { it.id.toString() == appliedFilters.accountId }
                    ?.name
                    ?.let(::add)
                categories.entries
                    .firstOrNull { it.key.toString() == appliedFilters.categoryId }
                    ?.value
                    ?.let(::add)
                snapshot.tags.filter { it.id.toString() in appliedFilters.tagIds }.map { it.name }
                    .takeIf { it.isNotEmpty() }?.let { names ->
                        add("${if (appliedFilters.tagMatch == "ALL") "所有标签" else "任一标签"}：${names.joinToString("、")}")
                    }
                appliedFilters.currency.takeIf(String::isNotEmpty)?.let(::add)
                val amountRange =
                    listOfNotNull(
                        appliedFilters.minimumAmount.takeIf(String::isNotEmpty)?.let { "≥$it" },
                        appliedFilters.maximumAmount.takeIf(String::isNotEmpty)?.let { "≤$it" },
                    ).joinToString(" ")
                if (amountRange.isNotEmpty()) add("$amountRange ${appliedFilters.currency}")
            }
        }
    Column(Modifier.fillMaxSize()) {
        if (selectedIds.isNotEmpty()) {
            FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (model.state.accountOwner != null) {
                    Text("批量删除暂不支持账号账本写入。报销收款入队后会关闭表单；确认前不会预览修改后的余额或贡献。", Modifier.align(Alignment.CenterVertically), style = MaterialTheme.typography.bodySmall)
                }
                Text("已选 ${selectedIds.size} 笔", Modifier.align(Alignment.CenterVertically))
                TextButton(enabled = !model.state.busy, onClick = {
                    if (visibleRecords.size > 500) {
                        model.showError("一次最多选择500笔账单")
                    } else {
                        selectedIds = visibleRecords.map { it.id.toString() }
                    }
                }) { Text("全选结果") }
                TextButton(enabled = !model.state.busy, onClick = { selectedIds = emptyList() }) { Text("取消多选") }
                Button(enabled = !model.state.busy && model.state.accountOwner == null, onClick = {
                    val targets = snapshot.records.filter { it.id.toString() in selectedIds }
                    if (targets.size != selectedIds.size) {
                        model.showError("选中的账单已变更，请刷新后重试")
                    } else {
                        model.clearError()
                        batchDeleteTargets = targets
                    }
                }) { Text("批量删除") }
                Button(enabled = !model.state.busy && !model.state.accountClearPending && !model.state.restoreBusy && canSettleSelection, onClick = {
                    claimSettlementTargets = selectedTargets
                    model.clearError()
                }) { Text("批量报销") }
            }
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            item {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LedgerOverview(snapshot, model.statisticsZone, displayRange,
                        if (hasDateFilter) "自定义日期" else "${month.year} 年 ${month.monthValue} 月",
                        { monthPickerOpen = true }, appliedQuery.isNotBlank() || activeFilterCount > 0)
                    BudgetPanel(model, compact = true)
                    if (snapshot.records.isNotEmpty() || searchOpen || activeFilterCount > 0) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("收支明细", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
                        TextButton(onClick = { draftFilters = appliedFilters; filtersOpen = true }) {
                            AppIcon(AppSymbol.FILTER)
                            Text(if (activeFilterCount == 0) "筛选" else "筛选 ($activeFilterCount)")
                        }
                    }
                    if (searchOpen || query.isNotEmpty()) OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("搜索账单、分类、账户…", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingIcon = { AppIcon(AppSymbol.SEARCH) },
                        shape = RoundedCornerShape(14.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { rememberSearch() }),
                        trailingIcon = {
                            TextButton(onClick = { query = ""; appliedQuery = ""; closeSearch() }) { Text("收起") }
                        },
                    )
                    if (activeFilterCount > 0) TextButton(onClick = { resetSearchFilters() }) { Text("重置筛选 ($activeFilterCount)") }
                    if (searchOpen && history.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            history.forEach { term ->
                                FilterChip(
                                    selected = false,
                                    onClick = {
                                        query = term
                                        appliedQuery = term
                                    },
                                    label = { Text(term) },
                                )
                            }
                            TextButton(onClick = { history = emptyList() }) { Text("清空历史") }
                        }
                    }
                    if (appliedQuery.isNotBlank() || activeFilterCount > 0) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = effectiveSort == SORT_TIME_DESCENDING,
                            onClick = { setSearchFilters(appliedFilters, SORT_TIME_DESCENDING) },
                            label = { Text("时间降序") },
                        )
                        FilterChip(
                            selected = effectiveSort == SORT_AMOUNT_ASCENDING,
                            onClick = { setSearchFilters(appliedFilters, SORT_AMOUNT_ASCENDING) },
                            enabled = appliedFilters.currency.isNotEmpty(),
                            label = { Text("金额升序") },
                        )
                    }

                    if (filterSummary.isNotEmpty()) {
                        Text("筛选：${filterSummary.joinToString(" · ")}", style = MaterialTheme.typography.bodySmall)
                    }
                    if (appliedQuery.isNotBlank() || activeFilterCount > 0) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(statistics.summaryText(), style = MaterialTheme.typography.bodySmall)
                            Text("收支汇总按币种分列；转账本金不计，手续费/返现分别计入支出/收入，不计统计的账单排除。", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            if (snapshot.records.isEmpty()) {
                item {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        androidx.compose.foundation.Image(
                            painter = androidx.compose.ui.res.painterResource(R.drawable.empty_ledger),
                            contentDescription = null,
                            modifier = Modifier.fillMaxWidth().height(120.dp),
                            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                        )
                        Text("从第一笔，开始记录生活", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("记录每一笔收支，让美好生活更清晰", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = { model.editRecord() }, enabled = !model.state.busy,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), colors = primaryButtonColors()) {
                            AppIcon(AppSymbol.ADD); Text("记一笔", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        }
                        OutlinedButton(onClick = onImport, enabled = !model.state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            AppIcon(AppSymbol.UPLOAD); Text("导入账单", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            } else if (visibleRecords.isEmpty()) {
                item {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("没有匹配的账单")
                        if (query.isNotBlank()) TextButton(onClick = { query = "" }) { Text("清除关键词") }
                        if (activeFilterCount > 0) TextButton(onClick = { resetSearchFilters() }) { Text("重置筛选") }
                    }
                }
            }
            itemsIndexed(timelineRows, key = { _, row -> row.key }) { rowIndex, timelineRow ->
                when (timelineRow) {
                    is RecordTimelineRow.DayHeader -> {
                        Surface(
                            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp),
                            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                            color = MaterialTheme.colorScheme.surface,
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(dayHeaderLabel(timelineRow.day, todayKey), style = MaterialTheme.typography.titleSmall, maxLines = 1)
                                Text(
                                    timelineRow.statistics.dailySummaryText(),
                                    Modifier.weight(1f),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.End,
                                )
                            }
                        }
                    }

                    is RecordTimelineRow.Bill -> {
                val record = timelineRow.record
                val isSelected = record.id.toString() in selectedIds
                val canSelectRecord = record.localRevision > 0 &&
                    (model.state.accountOwner == null || selectedIds.isNotEmpty() || record.accountMutationSupported)
                var refundHistoryExpanded by rememberSaveable(record.id) { mutableStateOf(false) }
                var recordActionsOpen by rememberSaveable(record.id) { mutableStateOf(false) }
                val recordRefunds = snapshot.refunds.filter { it.originRecordId == record.id }
                if (recordRefunds.isNotEmpty() && snapshot.businessPreferences?.showRefundsInRecordList == false) {
                    TextButton(onClick = { refundHistoryExpanded = !refundHistoryExpanded }, enabled = !model.state.busy) {
                        Text(if (refundHistoryExpanded) "收起退款记录" else "查看退款记录（${recordRefunds.size}）")
                    }
                }
                Card(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .semantics { selected = isSelected }
                            .combinedClickable(
                                enabled = !model.state.busy,
                                onClickLabel = if (selectedIds.isEmpty()) "查看账单详情" else "切换选择",
                                onLongClickLabel = if (canSelectRecord) "选择账单" else null,
                                onClick = {
                                    if (selectedIds.isEmpty()) {
                                        model.clearError()
                                        detailRecordId = record.id.toString()
                                    } else if (canSelectRecord) toggleSelection(record.id.toString())
                                },
                                onLongClick = if (canSelectRecord) { { toggleSelection(record.id.toString()) } } else null,
                            ),
                    shape = if (effectiveSort == SORT_AMOUNT_ASCENDING) RoundedCornerShape(12.dp)
                        else if (timelineRows.getOrNull(rowIndex + 1) !is RecordTimelineRow.Bill) RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp)
                        else RoundedCornerShape(0.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        val input = record.input
                        val heading =
                            when (input) {
                                is RecordInput.Expense -> {
                                    Triple(
                                        "支出",
                                        ledgerRecordAmount(input.discount?.let(input.gross::checkedSubtract) ?: input.gross, income = false),
                                        false,
                                    )
                                }

                                is RecordInput.Income -> {
                                    Triple("收入", ledgerRecordAmount(input.amount, income = true), true)
                                }

                                is RecordInput.Transfer -> {
                                    Triple("转账", "${display(input.from.amount)} → ${display(input.to.amount)}", null)
                                }
                            }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (selectedIds.isNotEmpty()) {
                                Checkbox(isSelected, onCheckedChange = { toggleSelection(record.id.toString()) }, enabled = !model.state.busy && canSelectRecord)
                            }
                            record.categoryId?.let { id -> snapshot.categories.firstOrNull { it.id == id } }?.let { category ->
                                val style =
                                    rememberCategoryStyle(context, snapshot.ownerId, category, model.categoryStyleRevision)
                                CategoryStyleBadge(style)
                            }
                            Text(record.remark.ifBlank { record.categoryId?.let(categories::get) ?: heading.first }, Modifier.weight(1f),
                                maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                            AmountText(heading.second, income = heading.third, style = MaterialTheme.typography.titleMedium)
                            IconButton(onClick = { recordActionsOpen = !recordActionsOpen }) {
                                AppIcon(if (recordActionsOpen) AppSymbol.DOWN else AppSymbol.MORE, if (recordActionsOpen) "收起账单操作" else "账单操作")
                            }
                        }
                        val accountLabel = when (input) {
                            is RecordInput.Transfer -> "${names[input.from.accountId] ?: "转出账户"} → ${names[input.to.accountId] ?: "转入账户"}"
                            is RecordInput.Expense -> input.allocations.joinToString("、") { names[it.accountId] ?: "账户" }.ifBlank { "不计入账户" }
                            is RecordInput.Income -> input.allocations.joinToString("、") { names[it.accountId] ?: "账户" }.ifBlank { "不计入账户" }
                        }
                        Text("$accountLabel · ${dateText(record.occurredAtMillis, model.statisticsZone).substringAfter(' ')}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (recordActionsOpen && input is RecordInput.Transfer) {
                            Text("${names[input.from.accountId] ?: "转出账户"} → ${names[input.to.accountId] ?: "转入账户"}")
                            input.fee?.let { Text("手续费 ${display(it.amount)} · ${names[it.accountId].orEmpty()}") }
                            input.bonus?.let { Text("返现 ${display(it.amount)} · ${names[it.accountId].orEmpty()}") }
                        } else if (recordActionsOpen) {
                            val allocations =
                                when (input) {
                                    is RecordInput.Expense -> input.allocations
                                    is RecordInput.Income -> input.allocations
                                    else -> emptyList()
                                }
                            Text(if (allocations.isEmpty()) "不计入账户" else allocations.joinToString("、") { names[it.accountId] ?: "账户" })
                        }
                        if (recordActionsOpen && record.remark.isNotBlank()) Text(record.remark)
                        val recordTagNames = record.tagIds.mapNotNull { id -> snapshot.tags.firstOrNull { it.id == id }?.name }
                        if (recordTagNames.isNotEmpty()) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                recordTagNames.forEach { AssistChip(onClick = {}, label = { Text(it) }) }
                            }
                        }
                        if (!recordActionsOpen && record.claimState != "NONE") Text(when (record.claimState) {
                            "PENDING" -> "待报销"
                            "PARTIAL" -> "部分报销"
                            "SETTLED" -> "已报销"
                            else -> "报销处理中"
                        }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        if (input is RecordInput.Expense && recordActionsOpen) {
                            val hasPostedClaim =
                                snapshot.claimSettlements.any { settlement ->
                                    settlement.status == "POSTED" && settlement.allocations.any { it.originRecordId == record.id }
                                }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                when (record.claimState) {
                                    "PENDING" -> Text("待报销", style = MaterialTheme.typography.bodySmall)
                                    "PARTIAL" -> Text("部分报销", style = MaterialTheme.typography.bodySmall)
                                    "SETTLED" -> Text("已报销", style = MaterialTheme.typography.bodySmall)
                                }
                                if (record.claimState in setOf("NONE", "PENDING")) {
                                    TextButton(onClick = { model.toggleClaim(record) }, enabled = !model.state.busy && !model.state.accountClearPending && !model.state.restoreBusy) {
                                        Text(if (record.claimState == "PENDING") "取消待报销" else "标记待报销")
                                    }
                                }
                                TextButton(
                                    onClick = {
                                        model.clearError()
                                        refundTarget = record
                                    },
                                    enabled = !model.state.busy && !hasPostedClaim && !model.state.accountClearPending && !model.state.restoreBusy,
                                ) { Text("登记退款") }
                            }
                            if (hasPostedClaim) Text("登记退款前请先撤销已入账报销", style = MaterialTheme.typography.bodySmall)
                        }
                        if (snapshot.businessPreferences?.showRefundsInRecordList != false || refundHistoryExpanded) recordRefunds.forEach { receipt ->
                            Text(
                                "${if (receipt.status == "POSTED") "退款已入账" else "退款已撤销"} · ${display(receipt.amount)}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                "${names[receipt.accountId] ?: "收款账户"} · ${dateText(receipt.refundAtMillis, receipt.timezone)}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (receipt.status == "POSTED") {
                                Text(
                                    "冲减支出 ${display(receipt.expenseReduction)} · 保留超额 ${display(receipt.excessAmount)}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                if (recordActionsOpen) TextButton(
                                    onClick = {
                                        model.clearError()
                                        cancelRefundTarget = receipt to record
                                    },
                                    enabled = !model.state.busy && !model.state.accountClearPending && !model.state.restoreBusy,
                                ) { Text("撤销退款") }
                            }
                        }
                        if (recordActionsOpen) snapshot.claimSettlements
                            .filter { settlement ->
                                settlement.status == "POSTED" &&
                                    settlement.allocations.any { it.originRecordId == record.id }
                            }.forEach { settlement ->
                                TextButton(onClick = { cancelClaimTarget = settlement }, enabled = !model.state.busy && !model.state.accountClearPending && !model.state.restoreBusy) {
                                    Text("撤销报销批次 ${settlement.id.toString().take(8)} · ${display(settlement.actualAmount)}")
                                }
                            }
                        val omitted =
                            buildList {
                                if (input is RecordInput.Expense && !input.countInStatistics ||
                                    input is RecordInput.Income && !input.countInStatistics
                                ) {
                                    add("不计统计")
                                }
                                if (input is RecordInput.Expense && !input.countInBudget) add("单笔不计预算")
                                if (input is RecordInput.Expense && categoryExcludesBudget(record.categoryId, snapshot.allCategories)) add("分类不计预算")
                            }
                        if (omitted.isNotEmpty()) Text(omitted.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                    }
                }
                    }
                }
            }
            item { Spacer(Modifier.height(90.dp)) }
        }
    }
    detailRecord?.let { record ->
        RecordDetailRoute(snapshot, record, model, onDismiss = { detailRecordId = null }, onRefund = { refundTarget = it })
    }
    if (monthPickerOpen) {
        LedgerMonthPicker(month, onDismiss = { monthPickerOpen = false }) { chosen ->
            selectedMonth = chosen.toString()
            setSearchFilters(appliedFilters.copy(fromDate = "", throughDate = ""), selectedSort)
            monthPickerOpen = false
        }
    }
    if (calendarOpen) {
        RecordCalendarDialog(
            selectedDay = calendarDay,
            recordsByDay = recordsByDay,
            onSelectDay = { calendarDay = it },
            onToday = { calendarDay = todayKey },
            onDismiss = closeCalendar,
            onEdit = { record ->
                closeCalendar()
                model.editRecord(record)
            },
        )
    }
    batchDeleteTargets?.let { targets ->
        AlertDialog(
            onDismissRequest = { if (!model.state.busy) batchDeleteTargets = null },
            title = { Text("删除 ${targets.size} 笔账单？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("将一并撤回这 ${targets.size} 笔账单对应的账户变动、统计和预算计入。此操作无法在页面中撤销。")
                    model.state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                Button(enabled = !model.state.busy, onClick = {
                    model.deleteRecords(targets) {
                        selectedIds = emptyList()
                        batchDeleteTargets = null
                    }
                }) { Text(if (model.state.busy) "删除中…" else "删除 ${targets.size} 笔") }
            },
            dismissButton = {
                TextButton(enabled = !model.state.busy, onClick = { batchDeleteTargets = null }) { Text("取消") }
            },
        )
    }
    claimSettlementTargets?.let { targets ->
        ClaimSettlementDialog(
            snapshot = snapshot,
            targets = targets,
            timezone = model.statisticsZone,
            model = model,
            onDismiss = { if (!model.state.busy) claimSettlementTargets = null },
            onCommitted = {
                claimSettlementTargets = null
                selectedIds = emptyList()
            },
        )
    }
    cancelClaimTarget?.let { settlement ->
        var reason by remember(settlement.id) { mutableStateOf("用户取消报销收款") }
        AlertDialog(
            onDismissRequest = { if (!model.state.busy) cancelClaimTarget = null },
            title = { Text("撤销报销批次？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("将撤回 ${display(settlement.actualAmount)} 收款，并重新计算该批次涉及账单的报销金额。")
                    OutlinedTextField(value = reason, onValueChange = { if (it.length <= 1000) reason = it }, label = { Text("撤销原因") })
                    model.state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                Button(enabled = !model.state.busy && reason.isNotBlank(), onClick = {
                    model.cancelClaimSettlement(settlement.id, settlement.localRevision, reason,
                        onCommitted = { cancelClaimTarget = null }, onEnqueued = { cancelClaimTarget = null })
                }) { Text(if (model.state.busy) "撤销中…" else "确认撤销") }
            },
            dismissButton = { TextButton(enabled = !model.state.busy, onClick = { cancelClaimTarget = null }) { Text("返回") } },
        )
    }
    refundTarget?.let { target ->
        RefundDialog(
            snapshot = snapshot,
            record = target,
            model = model,
            onDismiss = { refundTarget = null },
            onCommitted = { refundTarget = null },
        )
    }
    cancelRefundTarget?.let { (receipt, source) ->
        var reason by remember(receipt.id) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { if (!model.state.busy) cancelRefundTarget = null },
            title = { Text("撤销退款？") },
            text = {
                Column(
                    Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("将撤回 ${display(receipt.amount)} 退款收款，并重新计算原账单净支出及其余退款的差额。")
                    OutlinedTextField(
                        value = reason,
                        onValueChange = { if (it.length <= 1000) reason = it },
                        label = { Text("撤销原因") },
                        enabled = !model.state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    model.state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                Button(enabled = !model.state.busy && reason.isNotBlank(), onClick = {
                    model.cancelRefund(receipt.id, receipt.localRevision, source.localRevision, reason,
                        onCommitted = { cancelRefundTarget = null }, onEnqueued = { cancelRefundTarget = null })
                }) { Text(if (model.state.busy) "撤销中…" else "确认撤销") }
            },
            dismissButton = {
                TextButton(enabled = !model.state.busy, onClick = { cancelRefundTarget = null }) { Text("返回") }
            },
        )
    }
    if (filtersOpen) {
        val error = recordFiltersError(draftFilters)
        AlertDialog(
            onDismissRequest = { filtersOpen = false },
            title = { Text("更多筛选") },
            text = {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("账单类型")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("" to "全部", "EXPENSE" to "支出", "INCOME" to "收入", "TRANSFER" to "转账").forEach { (type, label) ->
                            FilterChip(
                                selected = draftFilters.type == type,
                                onClick = { draftFilters = draftFilters.copy(type = type) },
                                label = { Text(label) },
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = { pickDate(draftFilters.fromDate) { draftFilters = draftFilters.copy(fromDate = it) } }) {
                            Text(if (draftFilters.fromDate.isEmpty()) "开始日期" else "从 ${draftFilters.fromDate}")
                        }
                        if (draftFilters.fromDate.isNotEmpty()) {
                            TextButton(
                                onClick = { draftFilters = draftFilters.copy(fromDate = "") },
                            ) { Text("清除") }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(
                            onClick = { pickDate(draftFilters.throughDate) { draftFilters = draftFilters.copy(throughDate = it) } },
                        ) {
                            Text(if (draftFilters.throughDate.isEmpty()) "结束日期" else "至 ${draftFilters.throughDate}")
                        }
                        if (draftFilters.throughDate.isNotEmpty()) {
                            TextButton(
                                onClick = { draftFilters = draftFilters.copy(throughDate = "") },
                            ) { Text("清除") }
                        }
                    }
                    Choice(
                        "资金账户",
                        snapshot.accounts.map { account ->
                            account.id to "${snapshot.accountGroups.firstOrNull { it.id == account.groupId }?.name.orEmpty()} · ${account.name} (${account.currency})"
                        },
                        snapshot.accounts.find { it.id.toString() == draftFilters.accountId }?.id,
                        emptyLabel = "全部账户",
                        enabled = true,
                    ) { draftFilters = draftFilters.copy(accountId = it?.toString().orEmpty()) }
                    Choice(
                        "分类",
                        snapshot.categories.map { it.id to it.name },
                        snapshot.categories.find { it.id.toString() == draftFilters.categoryId }?.id,
                        emptyLabel = "全部分类",
                        enabled = true,
                    ) { draftFilters = draftFilters.copy(categoryId = it?.toString().orEmpty()) }
                    Text("标签")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = draftFilters.tagMatch == "ANY",
                            onClick = { draftFilters = draftFilters.copy(tagMatch = "ANY") },
                            label = { Text("任一标签") },
                        )
                        FilterChip(
                            selected = draftFilters.tagMatch == "ALL",
                            onClick = { draftFilters = draftFilters.copy(tagMatch = "ALL") },
                            label = { Text("所有标签") },
                        )
                        snapshot.tags.forEach { tag ->
                            FilterChip(
                                selected = tag.id.toString() in draftFilters.tagIds,
                                onClick = {
                                    val id = tag.id.toString()
                                    draftFilters = draftFilters.copy(tagIds = if (id in draftFilters.tagIds) draftFilters.tagIds - id else draftFilters.tagIds + id)
                                },
                                label = { Text(if (tag.archivedAt == null) tag.name else "${tag.name} · 已归档") },
                            )
                        }
                    }
                    Text("币种")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = draftFilters.currency.isEmpty(),
                            onClick = { draftFilters = draftFilters.copy(currency = "") },
                            label = { Text("全部") },
                        )
                        currencies.forEach { currency ->
                            FilterChip(
                                selected = draftFilters.currency == currency,
                                onClick = { draftFilters = draftFilters.copy(currency = currency) },
                                label = { Text(currency) },
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = draftFilters.minimumAmount,
                            onValueChange = { draftFilters = draftFilters.copy(minimumAmount = it) },
                            label = { Text("最低金额") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = draftFilters.maximumAmount,
                            onValueChange = { draftFilters = draftFilters.copy(maximumAmount = it) },
                            label = { Text("最高金额") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Text("金额区间按所选币种筛选；日期以账单时区的本地日计算。", style = MaterialTheme.typography.bodySmall)
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                Button(onClick = {
                    val sort =
                        if (draftFilters.currency.isEmpty() &&
                            selectedSort == SORT_AMOUNT_ASCENDING
                        ) {
                            SORT_TIME_DESCENDING
                        } else {
                            selectedSort
                        }
                    setSearchFilters(draftFilters, sort)
                    filtersOpen = false
                }, enabled = error == null) { Text(if (draftPreviewCount < 0) "应用" else "显示 $draftPreviewCount 笔结果") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        resetSearchFilters()
                    }) { Text("重置") }
                    TextButton(onClick = { filtersOpen = false }) { Text("取消") }
                }
            },
        )
    }
}

private val searchHistorySaver = listSaver<List<String>, String>(save = { it }, restore = { it })
private val recordFiltersSaver =
    listSaver<RecordFilters, String>(
        save = {
            listOf(
                it.type,
                it.fromDate,
                it.throughDate,
                it.currency,
                it.accountId,
                it.categoryId,
                it.minimumAmount,
                it.maximumAmount,
                it.tagIds.sorted().joinToString(","),
                it.tagMatch,
            )
        },
        restore = { values ->
            RecordFilters(
                values[0], values[1], values[2], values[3], values[4], values[5], values[6], values[7],
                values.getOrNull(8).orEmpty().split(',').filter(String::isNotBlank).toSet(),
                values.getOrNull(9)?.takeIf { it in setOf("ANY", "ALL") } ?: "ANY",
            )
        },
    )

@Composable
private fun RecordCalendarDialog(
    selectedDay: String,
    recordsByDay: Map<String, List<FinanceRecord>>,
    onSelectDay: (String) -> Unit,
    onToday: () -> Unit,
    onDismiss: () -> Unit,
    onEdit: (FinanceRecord) -> Unit,
) {
    val monthKey = selectedDay.substring(0, 7)
    val year = monthKey.substringBefore('-').toInt()
    val month = monthKey.substringAfter('-').toInt()
    val dayRecords = recordsByDay[selectedDay].orEmpty()
    val statistics = searchStatistics(dayRecords)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss) { Text("返回") }
                    Text("收支日历", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onToday) { Text("今天") }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = { onSelectDay(shiftCalendarMonth(selectedDay, -1)) },
                        enabled = monthKey != "0001-01",
                    ) { Text("上月") }
                    Text("${year}年${month}月", Modifier.weight(1f), textAlign = TextAlign.Center)
                    TextButton(
                        onClick = { onSelectDay(shiftCalendarMonth(selectedDay, 1)) },
                        enabled = monthKey != "9999-12",
                    ) { Text("下月") }
                }
                Row(Modifier.fillMaxWidth()) {
                    listOf("一", "二", "三", "四", "五", "六", "日").forEach { Text(it, Modifier.weight(1f), textAlign = TextAlign.Center) }
                }
                calendarMonthCells(monthKey).chunked(7).forEach { week ->
                    Row(Modifier.fillMaxWidth()) {
                        week.forEach { dateKey ->
                            if (dateKey == null) {
                                Spacer(Modifier.weight(1f).height(48.dp))
                            } else {
                                val count = recordsByDay[dateKey].orEmpty().size
                                Column(
                                    Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .semantics {
                                            contentDescription = "$dateKey，${count}笔账单"
                                            selected = dateKey == selectedDay
                                        }.selectable(selected = dateKey == selectedDay, role = Role.Button) { onSelectDay(dateKey) },
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center,
                                ) {
                                    Text(dateKey.takeLast(2).toInt().toString())
                                    if (count >
                                        0
                                    ) {
                                        Text(
                                            "${count}笔",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                Text(selectedDay, style = MaterialTheme.typography.titleMedium)
                Text(statistics.summaryText(), style = MaterialTheme.typography.bodyMedium)
                if (dayRecords.isEmpty()) {
                    Text("当天没有账单", Modifier.padding(vertical = 12.dp))
                } else {
                    LazyColumn(Modifier.weight(1f)) {
                        items(dayRecords, key = { it.id.toString() }) { record ->
                            val detail =
                                when (val input = record.input) {
                                    is RecordInput.Expense -> {
                                        "支出 ${display(input.discount?.let(input.gross::checkedSubtract) ?: input.gross)}"
                                    }

                                    is RecordInput.Income -> {
                                        "收入 ${display(input.amount)}"
                                    }

                                    is RecordInput.Transfer -> {
                                        buildString {
                                            append("转账 ${display(input.from.amount)} → ${display(input.to.amount)}")
                                            input.fee?.let { append(" · 手续费 ${display(it.amount)}") }
                                            input.bonus?.let { append(" · 返现 ${display(it.amount)}") }
                                        }
                                    }
                                }
                            TextButton(onClick = { onEdit(record) }, modifier = Modifier.fillMaxWidth()) {
                                Text("$detail · ${record.remark.ifBlank { "无备注" }}", Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Statistics(
    snapshot: FinanceSnapshot,
    model: FinanceViewModel,
    receiptExport: ReceiptExport,
    appliedSession: BoundSession,
) {
    val context = LocalContext.current
    val today =
        remember {
            val now = System.currentTimeMillis()
            now + TimeZone.getTimeZone(model.statisticsZone).getOffset(now)
        }
    var customOpen by rememberSaveable { mutableStateOf(false) }
    var startDayUtc by rememberSaveable { mutableStateOf(today) }
    var endDayUtc by rememberSaveable { mutableStateOf(today) }
    var customError by remember { mutableStateOf<String?>(null) }
    var selectedCategoryKey by rememberSaveable { mutableStateOf<String?>(null) }
    var chartView by rememberSaveable { mutableStateOf(false) }
    val categoryEntries =
        remember(snapshot.statisticEntries, model.statisticsRange) {
            snapshot.statisticEntries.filter { model.statisticsRange?.contains(it.occurredAtMillis) != false }
        }
    val categoryBreakdown = remember(categoryEntries, snapshot.categories) { categoryStatistics(categoryEntries, snapshot.categories) }
    val categoryBarRatios = remember(categoryBreakdown) { statisticsBarRatios(categoryBreakdown) }
    val selectedCategory = categoryBreakdown.firstOrNull { it.key == selectedCategoryKey }
    val annualReportOpen = receiptExport.annualOpen == receiptKey(snapshot)
    var receiptPickerOpen by rememberSaveable(snapshot.ownerId.toString(), snapshot.epochId.toString(), snapshot.ledgerId.toString()) { mutableStateOf(false) }
    var receiptSearch by remember(snapshot.ownerId, snapshot.epochId, snapshot.ledgerId) { mutableStateOf("") }
    var receiptPage by remember(snapshot.ownerId, snapshot.epochId, snapshot.ledgerId) { mutableIntStateOf(0) }
    LaunchedEffect(model.statisticsRange) { selectedCategoryKey = null }
    BackHandler(enabled = annualReportOpen) { receiptExport.closeAnnual() }
    if (annualReportOpen) {
        AnnualReportPanel(snapshot, model.statisticsZone, model::loadAnnualReport, receiptExport, receiptExport::closeAnnual)
        return
    }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${model.statisticsLabel}收支 · 按币种", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = model.statisticsLabel == "全部",
                        enabled = !model.state.busy,
                        onClick = { model.filterStatistics("全部", null) },
                        label = { Text("全部") },
                    )
                    listOf("本周" to ReportPeriod.WEEK, "本月" to ReportPeriod.MONTH, "本年" to ReportPeriod.YEAR).forEach { (label, period) ->
                        FilterChip(
                            selected = model.statisticsLabel == label,
                            enabled = !model.state.busy,
                            onClick = {
                                model.filterStatistics(
                                    label,
                                    model.periodRange(period),
                                    period,
                                )
                            },
                            label = { Text(label) },
                        )
                    }
                    FilterChip(
                        selected = model.statisticsLabel == "自定义",
                        enabled = !model.state.busy,
                        onClick = {
                            customError = null
                            customOpen = true
                        },
                        label = { Text("自定义") },
                    )
                }
                Text("统计时区：${model.statisticsZone}", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { receiptExport.openAnnual(receiptKey(snapshot), java.time.Year.now(java.time.ZoneId.of(model.statisticsZone)).value) }, enabled = !model.state.busy && !receiptExport.busy) { Text("年度报告") }
                    OutlinedButton(onClick = { receiptPickerOpen = true }, enabled = !model.state.busy && snapshot.records.isNotEmpty()) { Text("记账小票") }
                }
                model.statisticsRange?.let { range ->
                    Text(
                        dateText(range.startInclusiveMillis, model.statisticsZone).substringBefore(' ') + " 至 " +
                            dateText(range.endExclusiveMillis - 1, model.statisticsZone).substringBefore(' '),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        if (snapshot.summaries.isEmpty()) item { Text("所选范围还没有可统计的账单", Modifier.padding(24.dp)) }
        items(snapshot.summaries, key = { it.currency }) { value ->
            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = summaryCardColor())) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("支出 · ${value.currency}", style = MaterialTheme.typography.bodySmall)
                    AmountText(display(value.expense), income = false, style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold))
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text("收入", style = MaterialTheme.typography.bodySmall)
                            AmountText(display(value.income), income = true, style = MaterialTheme.typography.titleMedium)
                        }
                        Column(Modifier.weight(1f)) {
                            Text("结余", style = MaterialTheme.typography.bodySmall)
                            AmountText(display(value.balance), style = MaterialTheme.typography.titleMedium)
                        }
                    }
                    Text("计入预算 ${display(value.budgetExpense)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item { Column(Modifier.padding(horizontal = 16.dp)) { BudgetPanel(model, compact = true) } }
        item {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("分类统计", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !chartView, onClick = { chartView = false }, label = { Text("排行") })
                    FilterChip(selected = chartView, onClick = { chartView = true }, label = { Text("图表") })
                }
            }
        }
        if (categoryBreakdown.isEmpty()) {
            item {
                Text(
                    if (categoryEntries.isEmpty()) "所选范围还没有可统计的分类账单" else "分类收支相抵，净额为零",
                    Modifier.padding(horizontal = 16.dp),
                )
            }
        }
        if (chartView && categoryBreakdown.isNotEmpty()) {
            categoryBreakdown.groupBy { it.direction to it.currency }.forEach { (group, statistics) ->
                val directionLabel = if (group.first == Direction.EXPENSE) "支出" else "收入"
                item(key = "statistics-chart:${group.first}:${group.second}") {
                    Text(
                        "${group.second} · $directionLabel",
                        Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                items(statistics, key = { "chart:${it.key}" }) { statistic ->
                    StatisticsChartRow(statistic, categoryBarRatios[statistic.key] ?: 0f, selectedCategoryKey == statistic.key) {
                        selectedCategoryKey = if (selectedCategoryKey == statistic.key) null else statistic.key
                    }
                }
            }
        } else {
            items(categoryBreakdown, key = { it.key }) { value ->
                Card(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .clickable { selectedCategoryKey = if (selectedCategoryKey == value.key) null else value.key },
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(value.name, style = MaterialTheme.typography.titleSmall)
                            Text("${if (value.direction == Direction.EXPENSE) "支出" else "收入"} · ${value.entries.size} 条", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            AmountText("${ledgerAmount(value.amount, value.currency)} ${value.currency}", income = value.direction == Direction.INCOME)
                            Text(value.percent?.let { "$it%" } ?: "含抵扣", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        AppIcon(AppSymbol.CHEVRON, "查看分类明细")
                    }
                }
            }
        }
        selectedCategory?.let { category ->
            item {
                TextButton(onClick = { selectedCategoryKey = null }, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text("${category.name} · ${category.currency} 明细 · 收起")
                }
            }
            items(category.entries) { entry ->
                val record = snapshot.records.firstOrNull { it.id == entry.recordId }
                val line = "${dateText(
                    entry.occurredAtMillis,
                    entry.timezone,
                ).substringBefore(' ')} · ${display(entry.amount)} · ${entry.remark.ifBlank { "关联收支" }}"
                if (record == null) {
                    Text(line, Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp))
                } else {
                    TextButton(
                        onClick = { model.editRecord(record) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    ) { Text(line) }
                }
            }
        }
        item { Text("分类按实际统计贡献汇总，包含报销抵扣和手续费/返现；转账本金不计入。", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall) }
    }
    if (receiptPickerOpen) {
        AlertDialog(
            onDismissRequest = { receiptPickerOpen = false },
            title = { Text("选择账单生成小票") },
            text = {
                Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(receiptSearch, { receiptSearch = it.take(120); receiptPage = 0 }, modifier = Modifier.fillMaxWidth(), label = { Text("搜索账单") }, singleLine = true)
                    val receiptMatches = remember(snapshot.ownerId, snapshot.epochId, snapshot.ledgerId, snapshot.records, snapshot.categories, receiptSearch) {
                        val categoryNames = snapshot.categories.associate { it.id to it.name }
                        snapshot.records.filter { record ->
                            val query = receiptSearch.trim()
                            query.isBlank() || record.remark.contains(query, true) || categoryNames[record.categoryId].orEmpty().contains(query, true) || dateText(record.occurredAtMillis, record.timezone).contains(query, true) || recordCurrencies(record).any { it.contains(query, true) }
                        }
                    }
                    val pageCount = ((receiptMatches.size + 49) / 50).coerceAtLeast(1)
                    receiptPage = receiptPage.coerceIn(0, pageCount - 1)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { receiptPage = (receiptPage - 1).coerceAtLeast(0) }, enabled = receiptPage > 0) { Text("上一页") }
                        Text("${receiptPage + 1}/$pageCount", Modifier.padding(top = 12.dp))
                        TextButton(onClick = { receiptPage = (receiptPage + 1).coerceAtMost(pageCount - 1) }, enabled = receiptPage + 1 < pageCount) { Text("下一页") }
                    }
                    if (receiptMatches.isEmpty()) Text("当前账本没有匹配账单。")
                    receiptMatches.drop(receiptPage * 50).take(50).forEach { record ->
                        val category = snapshot.categories.firstOrNull { it.id == record.categoryId }?.name ?: "账单"
                        TextButton(onClick = { receiptExport.begin(receiptKey(snapshot), appliedSession, record.id); receiptPickerOpen = false }, modifier = Modifier.fillMaxWidth()) {
                            Text("${dateText(record.occurredAtMillis, record.timezone)} · $category · ${record.remark.take(48)}", modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { receiptPickerOpen = false }) { Text("关闭") } },
        )
    }
    if (customOpen) {
        AlertDialog(
            onDismissRequest = { if (!model.state.busy) customOpen = false },
            title = { Text("自定义统计日期") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Date-only picker values use UTC; the resolver applies the statistics timezone.
                    OutlinedButton(onClick = {
                        pickDate(context, startDayUtc, "UTC", { customError = it }) {
                            startDayUtc = it
                            customError =
                                null
                        }
                    }) {
                        Text("开始：" + dateText(startDayUtc, "UTC").substringBefore(' '))
                    }
                    OutlinedButton(onClick = {
                        pickDate(context, endDayUtc, "UTC", { customError = it }) {
                            endDayUtc = it
                            customError = null
                        }
                    }) {
                        Text("结束（含当天）：" + dateText(endDayUtc, "UTC").substringBefore(' '))
                    }
                    customError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(enabled = !model.state.busy, onClick = {
                    try {
                        val range = PeriodResolver.custom(startDayUtc, endDayUtc, model.statisticsZone)
                        model.filterStatistics("自定义", range)
                        customOpen = false
                    } catch (_: IllegalArgumentException) {
                        customError = "请选择有效范围，结束日期不能早于开始日期"
                    }
                }) { Text("应用") }
            },
            dismissButton = { TextButton(onClick = { customOpen = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun AssetSections(section: String, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("accounts" to "账户", "loans" to "贷款", "credit" to "信用与分期", "history" to "历史趋势").forEach { (value, label) ->
            FilterChip(section == value, onClick = { onSelect(value) }, label = { Text(label) })
        }
    }
}

@Composable
private fun Assets(
    snapshot: FinanceSnapshot,
    model: FinanceViewModel,
    section: String,
    onSectionChange: (String) -> Unit,
    amountsHidden: Boolean,
    onToggleAmountsHidden: () -> Unit,
) {
    BackHandler(enabled = section != "accounts") { onSectionChange("accounts") }
    Column(Modifier.fillMaxSize()) {
        if (section != "accounts") AssetSections(section, onSectionChange)
        when (section) {
            "accounts" -> Accounts(snapshot, model, Modifier.weight(1f), onSectionChange, amountsHidden, onToggleAmountsHidden)
            "loans" -> LoanPanel(snapshot, model, Modifier.weight(1f))
            "credit" -> CreditInstallmentPanel(snapshot, model, Modifier.weight(1f))
            else -> AssetHistoryPanel(model)
        }
    }
}

@Composable
private fun Accounts(
    snapshot: FinanceSnapshot,
    model: FinanceViewModel,
    modifier: Modifier,
    onSection: (String) -> Unit,
    amountsHidden: Boolean,
    onToggleAmountsHidden: () -> Unit,
) {
    var managing by rememberSaveable { mutableStateOf(false) }
    var detailAccountId by remember(snapshot.ownerId, snapshot.epochId, snapshot.ledgerId) { mutableStateOf<UUID?>(null) }
    var detailRecordId by remember(snapshot.ownerId, snapshot.epochId, snapshot.ledgerId) { mutableStateOf<UUID?>(null) }
    var recordRefundTarget by remember(snapshot.ownerId, snapshot.epochId, snapshot.ledgerId) { mutableStateOf<FinanceRecord?>(null) }
    LaunchedEffect(detailRecordId, snapshot.records) {
        if (snapshot.records.none { it.id == detailRecordId }) detailRecordId = null
        if (snapshot.records.none { it.id == recordRefundTarget?.id }) recordRefundTarget = null
    }
    LaunchedEffect(detailAccountId, snapshot.allAccounts) {
        if (snapshot.allAccounts.none { it.id == detailAccountId }) detailAccountId = null
    }
    BackHandler(enabled = detailAccountId != null || managing) {
        if (detailAccountId != null) detailAccountId = null else managing = false
    }
    var editingGroup by remember { mutableStateOf<dev.icos.app.data.local.FinanceAccountGroup?>(null) }
    var creatingGroup by remember { mutableStateOf(false) }
    var groupName by remember { mutableStateOf("") }
    var deletingGroup by remember { mutableStateOf<dev.icos.app.data.local.FinanceAccountGroup?>(null) }
    var deletingGroupTarget by remember { mutableStateOf<dev.icos.app.data.local.FinanceAccountGroup?>(null) }
    var deletingGroupMembers by remember { mutableStateOf<Map<UUID, Long>>(emptyMap()) }
    var deletingAccount by remember { mutableStateOf<dev.icos.app.data.local.AccountDeletionCommands.AccountDeletePreview?>(null) }
    var deleteAccountName by remember { mutableStateOf("") }
    fun openAccountDelete(account: dev.icos.app.data.local.FinanceAccount) {
        model.clearError()
        model.previewAccountDelete(account) { deletingAccount = it; deleteAccountName = "" }
    }
    var visibilityAccount by remember { mutableStateOf<dev.icos.app.data.local.FinanceAccount?>(null) }
    var visibilityMode by remember { mutableStateOf("ALL") }
    var visibilityLedgers by remember { mutableStateOf<Map<UUID, Long>>(emptyMap()) }
    fun openVisibility(account: dev.icos.app.data.local.FinanceAccount) {
        visibilityMode = account.visibilityMode
        visibilityLedgers = snapshot.ledgers.filter { it.id in account.visibleLedgerIds }.associate { it.id to it.localRevision }
        visibilityAccount = account
    }
    LaunchedEffect(model.state.accountOwner, snapshot.ownerId, snapshot.epochId, snapshot.ledgerId) {
        detailAccountId = null
        visibilityAccount = null
        creatingGroup = false
        editingGroup = null
        deletingGroup = null
        deletingGroupTarget = null
        deletingGroupMembers = emptyMap()
        deletingAccount = null
        deleteAccountName = ""
    }
    var familyAccount by remember { mutableStateOf<dev.icos.app.data.local.FinanceAccount?>(null) }
    var familyDirection by remember { mutableStateOf("GIVEN_TO_RELATIVE") }
    var familyRelation by remember { mutableStateOf("") }
    var familyLimit by remember { mutableStateOf("0") }
    val visibleAccounts = model.visibleAccounts(snapshot)
    val accountManagementEnabled = !model.state.busy && (model.state.accountOwner == null ||
        !model.state.accountMutationPending && !model.state.syncPaused && !model.state.accountClearPending && !model.state.restoreBusy)
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        snapshot.allAccounts.groupBy { it.currency }.forEach { (currency, accounts) ->
            item(key = "total:$currency") {
                val position = accountPosition(accounts, currency)
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = summaryCardColor())) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("净资产 · $currency", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            IconButton(onClick = onToggleAmountsHidden) { AppIcon(if (amountsHidden) AppSymbol.EYE_OFF else AppSymbol.EYE, if (amountsHidden) "显示资产金额" else "隐藏资产金额") }
                        }
                        if (position == null) Text("金额超出可显示范围", color = MaterialTheme.colorScheme.error)
                        else {
                            AmountText(if (amountsHidden) "••••" else display(position.net), style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold))
                            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                                Text("总资产 ${if (amountsHidden) "••••" else display(position.assets)}", style = MaterialTheme.typography.bodySmall)
                                AmountText("总负债 ${if (amountsHidden) "••••" else display(position.liabilities)}", income = false, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
        item { AssetSections("accounts", onSection) }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("我的账户", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton(
                    onClick = { managing = !managing },
                    modifier = Modifier.heightIn(min = 48.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(AppSymbol.SETTINGS, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (managing) "完成管理" else "管理")
                    }
                }
            }
        }
        if (managing) item { Text("账户显示范围按账本保存；资产与负债合计按全部账户计算。", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall) }
        if (managing && model.state.accountOwner != null) {
            item { Text("账号支持账户资料、分组新建和改名、账本范围、归档恢复及账户开关，更改等待服务器确认。排序保存后等待同步确认；删除分组会将账户移入默认组。仅允许删除余额为零且没有计划等业务引用的账户，历史账单保留。负债始终计入负债合计。", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall) }
        }
        if (managing) item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("账户分组", style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = { editingGroup = null; groupName = ""; creatingGroup = true; model.clearError() }, enabled = accountManagementEnabled) { AppIcon(AppSymbol.ADD); Text("新建分组") }
            }
        }
        val orderedGroups = snapshot.accountGroups.sortedWith(compareBy({ it.sortOrder }, { it.id.toString() }))
        orderedGroups.forEachIndexed { index, group ->
            item(key = "account-group:${group.id}") {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Column {
                        Text(group.name + if (group.isDefault) " · 默认" else "", style = MaterialTheme.typography.titleMedium)
                        if (managing) FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = {
                            val ordered = orderedGroups.toMutableList()
                            if (index > 0) { val item = ordered.removeAt(index); ordered.add(index - 1, item); model.reorderAccountGroups(ordered) }
                        }, enabled = accountManagementEnabled && index > 0) { Text("分组上移") }
                        TextButton(onClick = {
                            val ordered = orderedGroups.toMutableList()
                            if (index < ordered.lastIndex) { val item = ordered.removeAt(index); ordered.add(index + 1, item); model.reorderAccountGroups(ordered) }
                        }, enabled = accountManagementEnabled && index < orderedGroups.lastIndex) { Text("分组下移") }
                        TextButton(onClick = { editingGroup = group; groupName = group.name; model.clearError() },
                            enabled = accountManagementEnabled && (model.state.accountOwner == null || group.localRevision > 0)) { Text("编辑") }
                        if (!group.isDefault) TextButton(onClick = {
                            deletingGroupTarget = snapshot.accountGroups.singleOrNull { it.isDefault }
                            deletingGroupMembers = snapshot.allAccounts.filter { it.groupId == group.id }.associate { it.id to it.localRevision }
                            deletingGroup = group
                            model.clearError()
                        }, enabled = accountManagementEnabled && (model.state.accountOwner == null || group.localRevision > 0)) { Text("删除") }
                        }
                    }
                }
            }
            val groupAccounts = visibleAccounts.filter { it.groupId == group.id }.sortedWith(compareBy({ it.sortOrder }, { it.id.toString() }))
            val sortableAccounts = snapshot.allAccounts.filter { it.groupId == group.id }.sortedWith(compareBy({ it.sortOrder }, { it.id.toString() }))
            items(groupAccounts.filter { it.archivedAt == null }, key = { it.id.toString() }) { account ->
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clickable(onClickLabel = "查看账户详情") { detailAccountId = account.id }) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            AppIcon(AppSymbol.WALLET, tint = MaterialTheme.colorScheme.primary)
                            Column(Modifier.weight(1f)) {
                                Text(account.name, style = MaterialTheme.typography.titleMedium)
                                Text("${if (account.type == "LIABILITY") "负债账户" else "资产账户"} · ${account.currency}", style = MaterialTheme.typography.bodySmall)
                            }
                            AmountText(if (amountsHidden) "••••" else display(account.balance))
                            AppIcon(AppSymbol.CHEVRON, "查看账户详情")
                        }
                        snapshot.relatedPersonProfiles.firstOrNull { it.accountId == account.id }?.let { profile ->
                            Text("亲情资料 · ${if (profile.direction == "GIVEN_TO_RELATIVE") "送给亲友" else "亲友赠予"} · ${profile.relation} · 总授权 ${if (amountsHidden) "••••" else display(profile.authorizedLimit)}")
                        }
                        if (managing) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (model.state.accountOwner == null) TextButton(onClick = { familyAccount = account
                                snapshot.relatedPersonProfiles.firstOrNull { it.accountId == account.id }?.let { profile ->
                                    familyDirection = profile.direction
                                    familyRelation = profile.relation
                                    familyLimit = moneyText(profile.authorizedLimit)
                                } ?: run { familyDirection = "GIVEN_TO_RELATIVE"; familyRelation = ""; familyLimit = "0" }
                            }, enabled = !model.state.busy) { Text("亲情资料") }
                            TextButton(onClick = { model.editAccount(account) }, enabled = !model.state.busy &&
                                account.localRevision > 0 && !model.state.accountMutationPending && !model.state.syncPaused &&
                                !model.state.accountClearPending && !model.state.restoreBusy) { Text("编辑资料") }
                            TextButton(onClick = { openVisibility(account) }, enabled = !model.state.busy &&
                                (model.state.accountOwner == null || account.localRevision > 0 && !model.state.accountMutationPending &&
                                    !model.state.syncPaused && !model.state.accountClearPending && !model.state.restoreBusy)) { Text("账本显示范围") }
                            TextButton(onClick = { model.setAccountIncludedInAssets(account, !account.includeInAssets) }, enabled = !model.state.busy &&
                                (model.state.accountOwner == null || account.localRevision > 0 && !model.state.accountMutationPending &&
                                    !model.state.syncPaused && !model.state.accountClearPending && !model.state.restoreBusy)) {
                                Text(if (account.includeInAssets) "不计入资产合计" else "计入资产合计")
                            }
                            TextButton(onClick = { model.setAccountSelectable(account, !account.selectable) }, enabled = !model.state.busy &&
                                (model.state.accountOwner == null || account.localRevision > 0 && !model.state.accountMutationPending &&
                                    !model.state.syncPaused && !model.state.accountClearPending && !model.state.restoreBusy)) {
                                Text(if (account.selectable) "关闭新选" else "允许新选")
                            }
                            TextButton(onClick = { model.requestAccountArchive(account) }, enabled = !model.state.busy &&
                                (model.state.accountOwner == null || account.localRevision > 0 && !model.state.accountMutationPending &&
                                    !model.state.syncPaused && !model.state.accountClearPending && !model.state.restoreBusy)) { Text("归档账户") }
                            if (model.state.accountOwner != null) TextButton(onClick = { openAccountDelete(account) },
                                enabled = accountManagementEnabled && account.localRevision > 0) { Text("删除账户") }
                            TextButton(onClick = {
                                val ordered = sortableAccounts.toMutableList()
                                val indexInGroup = ordered.indexOfFirst { it.id == account.id }
                                if (indexInGroup > 0) { val item = ordered.removeAt(indexInGroup); ordered.add(indexInGroup - 1, item); model.reorderAccounts(group.id, ordered) }
                            }, enabled = accountManagementEnabled && sortableAccounts.indexOfFirst { it.id == account.id } > 0) { Text("上移") }
                            TextButton(onClick = {
                                val ordered = sortableAccounts.toMutableList()
                                val indexInGroup = ordered.indexOfFirst { it.id == account.id }
                                if (indexInGroup in 0 until ordered.lastIndex) { val item = ordered.removeAt(indexInGroup); ordered.add(indexInGroup + 1, item); model.reorderAccounts(group.id, ordered) }
                            }, enabled = accountManagementEnabled && sortableAccounts.indexOfFirst { it.id == account.id } < sortableAccounts.lastIndex) { Text("下移") }
                            snapshot.accountGroups.filter { it.id != group.id }.forEach { target ->
                                TextButton(onClick = { model.moveAccount(account, target.id) }, enabled = !model.state.busy && model.state.accountOwner == null) { Text("移至${target.name}") }
                            }
                        }
                    }
                }
            }
        }
        val hiddenAccounts = snapshot.allAccounts.filter { it !in visibleAccounts }
        if (managing && hiddenAccounts.isNotEmpty()) {
            item { Text("此账本隐藏的账户", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleSmall) }
            items(hiddenAccounts, key = { "hidden:${it.id}" }) { account ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${account.name} · ${account.currency}", Modifier.weight(1f))
                    TextButton(onClick = { openVisibility(account) }, enabled = !model.state.busy &&
                        (model.state.accountOwner == null || account.localRevision > 0 && !model.state.accountMutationPending &&
                            !model.state.syncPaused && !model.state.accountClearPending && !model.state.restoreBusy)) { Text("更改范围") }
                    if (model.state.accountOwner != null) TextButton(onClick = { openAccountDelete(account) },
                        enabled = accountManagementEnabled && account.localRevision > 0) { Text("删除") }
                }
            }
        }
        if (visibleAccounts.isEmpty()) item {
            Text(if (snapshot.accounts.isEmpty()) "添加账户后，可以记录期初余额和账户间转账" else "当前账本没有可显示的账户。可切换账本，或查看全局资产合计。", Modifier.padding(24.dp))
        }
        val archivedAccounts = visibleAccounts.filter { it.archivedAt != null }
        if (managing && archivedAccounts.isNotEmpty()) {
            item(key = "archived-header") { Text("已归档账户", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleMedium) }
            items(archivedAccounts, key = { it.id.toString() }) { account ->
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${account.name} · ${account.currency}")
                            AmountText(if (amountsHidden) "••••" else display(account.balance))
                        }
                        Text("历史和余额保留；恢复后仍关闭新选。", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { model.setAccountIncludedInAssets(account, !account.includeInAssets) }, enabled = !model.state.busy &&
                                (model.state.accountOwner == null || account.localRevision > 0 && !model.state.accountMutationPending &&
                                    !model.state.syncPaused && !model.state.accountClearPending && !model.state.restoreBusy)) {
                                Text(if (account.includeInAssets) "不计入资产合计" else "计入资产合计")
                            }
                            TextButton(onClick = { model.requestAccountArchive(account) }, enabled = !model.state.busy &&
                                (model.state.accountOwner == null || account.localRevision > 0 && !model.state.accountMutationPending &&
                                    !model.state.syncPaused && !model.state.accountClearPending && !model.state.restoreBusy)) { Text("恢复账户") }
                            if (model.state.accountOwner != null) TextButton(onClick = { openAccountDelete(account) },
                                enabled = accountManagementEnabled && account.localRevision > 0) { Text("删除账户") }
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(90.dp)) }
    }
    detailAccountId?.let { id ->
        snapshot.allAccounts.singleOrNull { it.id == id }?.let { account ->
            val transferAccounts = model.visibleAccounts(snapshot).filter { it.selectable && it.archivedAt == null }
            AccountDetailPage(
                account = account,
                group = snapshot.accountGroups.firstOrNull { it.id == account.groupId },
                snapshot = snapshot,
                statisticsZone = model.statisticsZone,
                amountsHidden = amountsHidden,
                onToggleAmountsHidden = onToggleAmountsHidden,
                onRecordClick = { record -> if (!model.state.busy) { model.clearError(); detailRecordId = record.id } },
                canEdit = !model.state.busy && !model.state.accountMutationPending &&
                    !model.state.syncPaused && !model.state.accountClearPending && !model.state.restoreBusy && account.localRevision > 0,
                canTransfer = !model.state.busy && !model.state.restoreBusy &&
                    (model.state.accountOwner == null || !model.state.accountClearPending && account.localRevision > 0) &&
                    transferAccounts.any { it.id == account.id } && transferAccounts.any { it.id != account.id },
                onClose = { detailAccountId = null },
                onEdit = { model.editAccount(account) },
                onTransfer = {
                    val previous = model.recordDraft
                    model.editRecord()
                    model.recordDraft?.takeIf { it !== previous }?.apply {
                        selectKind(EntryKind.TRANSFER, snapshot)
                        currency = account.currency
                        accountId = account.id
                        fromId = account.id
                        toId = transferAccounts.firstOrNull { it.id != account.id }?.id
                    }
                },
            )
        }
    }
    detailRecordId?.let { id ->
        snapshot.records.singleOrNull { it.id == id }?.let { record ->
            RecordDetailRoute(snapshot, record, model, onDismiss = { detailRecordId = null }, onRefund = { recordRefundTarget = it })
        }
    }
    recordRefundTarget?.let { target ->
        RefundDialog(snapshot = snapshot, record = target, model = model,
            onDismiss = { recordRefundTarget = null }, onCommitted = { recordRefundTarget = null })
    }
    deletingAccount?.let { preview ->
        val stale = preview.ownerId != snapshot.ownerId.toString() || preview.epochId != snapshot.epochId.toString() ||
            snapshot.allAccounts.none { it.id == preview.account.id && it.localRevision == preview.account.localRevision }
        AlertDialog(
            onDismissRequest = { if (!model.state.busy) deletingAccount = null },
            title = { Text("删除账户 · ${preview.account.name}") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("已核对账户余额为零及当前业务引用。删除后退出账户列表和账本显示范围；历史账单、账户名称及全部资金分录保留，不作冲销。")
                Text("保留 ${preview.postingCount} 条资金分录；移除 ${preview.visibilityCount} 条账本显示关联。")
                Text("请输入完整账户名称确认。")
                OutlinedTextField(deleteAccountName, { deleteAccountName = it.take(100) },
                    enabled = accountManagementEnabled && !stale, label = { Text("账户名称") }, singleLine = true)
                if (stale) Text("账户或数据集已变化，请关闭后重新预览。", color = MaterialTheme.colorScheme.error)
                model.state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } },
            confirmButton = { TextButton(enabled = accountManagementEnabled && !stale && deleteAccountName == preview.account.name,
                onClick = { model.deleteAccountKeepingRecords(preview, deleteAccountName) { deletingAccount = null } }) { Text("确认删除") } },
            dismissButton = { TextButton(enabled = !model.state.busy, onClick = { deletingAccount = null }) { Text("取消") } },
        )
    }
    val existingFamilyProfile = familyAccount?.let { account -> snapshot.relatedPersonProfiles.firstOrNull { it.accountId == account.id } }
    if (familyAccount != null) AlertDialog(
        onDismissRequest = { familyAccount = null },
        title = { Text("亲情账户资料 · ${familyAccount!!.name}") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("仅保存在本机加密账本；记录总授权额度，不自动改变余额、收支、预算或扣款。", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(familyDirection == "GIVEN_TO_RELATIVE", onClick = { familyDirection = "GIVEN_TO_RELATIVE" }, label = { Text("送给亲友") })
                    FilterChip(familyDirection == "RECEIVED_FROM_RELATIVE", onClick = { familyDirection = "RECEIVED_FROM_RELATIVE" }, label = { Text("亲友赠予") })
                }
                Input("亲友关系（1–80 字）", familyRelation, !model.state.busy) { if (it.codePointCount(0, it.length) <= 80) familyRelation = it }
                Input("总授权额度（${familyAccount!!.currency}）", familyLimit, !model.state.busy) { if (it.length <= 32) familyLimit = it }
                Text("统计口径尚未确定，本地首版不提供统计开关。", style = MaterialTheme.typography.bodySmall)
                model.state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(enabled = !model.state.busy && model.state.accountOwner == null, onClick = {
                val account = familyAccount ?: return@Button
                model.saveRelatedPersonProfile(account, familyDirection, familyRelation, familyLimit)
                familyAccount = null
            }) { Text(if (existingFamilyProfile == null) "保存资料" else "保存修改") }
        },
        dismissButton = {
            Row {
                if (existingFamilyProfile != null) TextButton(enabled = !model.state.busy && model.state.accountOwner == null, onClick = {
                    familyAccount?.let(model::clearRelatedPersonProfile)
                    familyAccount = null
                }) { Text("移除资料") }
                TextButton(onClick = { familyAccount = null }, enabled = !model.state.busy) { Text("取消") }
            }
        },
    )
    val formGroup = if (creatingGroup) null else editingGroup
    if (creatingGroup || editingGroup != null) {
        val groupChanged = formGroup != null && snapshot.accountGroups.none { it.id == formGroup.id && it.localRevision == formGroup.localRevision }
        AlertDialog(
            onDismissRequest = { if (!model.state.busy) { creatingGroup = false; editingGroup = null } },
            title = { Text(if (formGroup == null) "新建账户分组" else "编辑账户分组") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Input("分组名称", groupName, accountManagementEnabled && !groupChanged) { if (it.length <= 100) groupName = it }
                    if (groupChanged) Text("分组版本已变化，请关闭后重新编辑。", color = MaterialTheme.colorScheme.error)
                    model.state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                Button(onClick = {
                    val old = formGroup
                    val lastOrder = snapshot.accountGroups.maxOfOrNull { it.sortOrder } ?: -1L
                    if (old == null && lastOrder == Long.MAX_VALUE) { model.showError("分组排序值已达到上限"); return@Button }
                    val order = old?.sortOrder ?: lastOrder + 1
                    model.saveAccountGroup(old?.id, groupName, order, old?.localRevision) {
                        creatingGroup = false
                        editingGroup = null
                    }
                }, enabled = accountManagementEnabled && !groupChanged && groupName.isNotBlank()) { Text("保存") }
            },
            dismissButton = { TextButton(enabled = !model.state.busy, onClick = { creatingGroup = false; editingGroup = null }) { Text("取消") } },
        )
    }
    visibilityAccount?.takeIf { model.state.accountOwner != null }?.let { account ->
        val sourceChanged = snapshot.allAccounts.none { it.id == account.id && it.localRevision == account.localRevision }
        val ledgersChanged = visibilityLedgers.any { (id, revision) ->
            snapshot.ledgers.none { it.id == id && it.localRevision == revision && it.archivedAt == null }
        }
        val enabled = !model.state.busy && !model.state.accountMutationPending && !model.state.syncPaused &&
            !model.state.accountClearPending && !model.state.restoreBusy && !sourceChanged
        AlertDialog(
            onDismissRequest = { if (!model.state.busy) visibilityAccount = null },
            title = { Text("${account.name} 的账本显示范围") },
            text = {
                Column(Modifier.heightIn(max = 450.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("此范围同步到账号，只影响账户显示和新选；历史账单、余额及全局资产合计保留。")
                    FilterChip(selected = visibilityMode == "ALL", enabled = enabled, onClick = {
                        visibilityMode = "ALL"; visibilityLedgers = emptyMap()
                    }, label = { Text("所有账本") })
                    FilterChip(selected = visibilityMode == "SELECTED", enabled = enabled, onClick = {
                        visibilityMode = "SELECTED"
                    }, label = { Text("指定账本") })
                    if (visibilityMode == "SELECTED") {
                        snapshot.ledgers.filter { it.archivedAt == null }.forEach { ledger ->
                            FilterChip(selected = ledger.id in visibilityLedgers, enabled = enabled && ledger.localRevision > 0,
                                onClick = {
                                    visibilityLedgers = if (ledger.id in visibilityLedgers) visibilityLedgers - ledger.id
                                        else visibilityLedgers + (ledger.id to ledger.localRevision)
                                }, label = { Text(ledger.name) })
                        }
                        if (visibilityLedgers.isEmpty()) Text("未选择账本时，账户会从所有账本的新选列表隐藏；可在此页面更改范围。")
                    }
                    if (sourceChanged) Text("账户版本已变化，请关闭后重新编辑。", color = MaterialTheme.colorScheme.error)
                    if (ledgersChanged) Text("所选账本已变化，请重新选择或切换为所有账本。", color = MaterialTheme.colorScheme.error)
                    model.state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                Button(enabled = enabled && !ledgersChanged && visibilityLedgers.size <= 100, onClick = {
                    model.saveAccountVisibility(account, visibilityMode, visibilityLedgers) {
                        if (visibilityAccount?.id == account.id) visibilityAccount = null
                    }
                }) { Text("保存范围") }
            },
            dismissButton = { TextButton(enabled = !model.state.busy, onClick = { visibilityAccount = null }) { Text("取消") } },
        )
    }
    visibilityAccount?.takeIf { model.state.accountOwner == null }?.let { account ->
        val scope = model.state.guestAccountLedgerVisibility[account.id] ?: "ALL"
        AlertDialog(
            onDismissRequest = { visibilityAccount = null },
            title = { Text("${account.name} 的账本显示范围") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("此设置只保存在本机访客偏好，不同步服务器，也不影响余额、历史账单或全局资产合计。")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = scope == "ALL", onClick = { model.setAccountLedgerDisplayScope(account.id, null) }, label = { Text("所有账本") })
                        snapshot.ledgers.filter { it.archivedAt == null }.forEach { ledger ->
                            FilterChip(selected = scope == ledger.id.toString(), onClick = { model.setAccountLedgerDisplayScope(account.id, ledger.id) }, label = { Text(ledger.name) })
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { visibilityAccount = null }) { Text("完成") } },
        )
    }
    deletingGroup?.let { group ->
        val target = deletingGroupTarget
        val currentMembers = snapshot.allAccounts.filter { it.groupId == group.id }.associate { it.id to it.localRevision }
        val changed = model.state.accountOwner != null && (currentMembers != deletingGroupMembers || target == null ||
            snapshot.accountGroups.none { it.id == group.id && it.localRevision == group.localRevision } ||
            snapshot.accountGroups.none { it.id == target.id && it.localRevision == target.localRevision && it.isDefault })
        AlertDialog(
            onDismissRequest = { if (!model.state.busy) deletingGroup = null },
            title = { Text("删除账户分组？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("该组的 ${deletingGroupMembers.size} 个账户（包括已归档账户）会移入默认组，账户和历史记录会保留。")
                    if (changed) Text("分组或成员版本已变化，请关闭后重新确认。", color = MaterialTheme.colorScheme.error)
                    model.state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                Button(onClick = { model.deleteAccountGroup(group, target, deletingGroupMembers) { deletingGroup = null } },
                    enabled = accountManagementEnabled && !changed) { Text("移入默认组并删除") }
            },
            dismissButton = { TextButton(enabled = !model.state.busy, onClick = { deletingGroup = null }) { Text("取消") } },
        )
    }
}

internal fun recordCategoryChoices(snapshot: FinanceSnapshot, direction: Direction): List<FinanceCategory> {
    val active = snapshot.categories.filter {
        it.ledgerId == snapshot.ledgerId && it.direction == direction && it.archivedAt == null
    }
    val children = active.filter { it.parentId != null }.groupBy { it.parentId }
    val order = compareBy<FinanceCategory> { it.sortOrder }.thenBy { it.name }.thenBy { it.id.toString() }
    return active.filter { it.parentId == null }.sortedWith(order).flatMap { root ->
        listOf(root) + children[root.id].orEmpty().sortedWith(order)
    }
}

internal data class RecordCategorySuggestion(val name: String, val symbol: AppSymbol)

private val expenseRecordCategorySuggestionKeys =
    setOf("FOOD", "TRANSPORT", "HOME", "HEALTH", "SHOPPING", "LEISURE", "EDUCATION", "TRAVEL", "BILL", "OTHER")
private val incomeRecordCategorySuggestionKeys = setOf("SALARY", "GIFT", "INVESTMENT", "REFUND", "OTHER")

internal fun recordCategorySuggestions(direction: Direction, categories: List<FinanceCategory>): List<RecordCategorySuggestion> {
    val existingRoots = categories.filter { it.direction == direction && it.parentId == null }.mapTo(HashSet()) { it.name.trim() }
    val keys = if (direction == Direction.INCOME) incomeRecordCategorySuggestionKeys else expenseRecordCategorySuggestionKeys
    return CategoryStyles.icons().filter { it.key in keys && it.label !in existingRoots }
        .map { RecordCategorySuggestion(it.label, it.symbol) }
}

@Composable
private fun RecordCategoryPicker(
    snapshot: FinanceSnapshot,
    model: FinanceViewModel,
    direction: Direction,
    choices: List<FinanceCategory>,
    selected: UUID?,
    enabled: Boolean,
    settings: RecordEfficiencySettings,
    onSelect: (UUID?) -> Unit,
) {
    key(snapshot.ownerId, snapshot.epochId, snapshot.ledgerId, direction) {
        val pendingCategories = remember(model.state.pendingCategories, snapshot.ledgerId) {
            model.state.pendingCategories.filter { it.ledgerId == snapshot.ledgerId }
        }
        val knownCategories = remember(snapshot.categories, snapshot.allCategories, snapshot.ledgerId) {
            (snapshot.categories + snapshot.allCategories).filter { it.ledgerId == snapshot.ledgerId }.distinctBy { it.id }
        }
        val suggestions = remember(knownCategories, pendingCategories, direction) {
            recordCategorySuggestions(direction, knownCategories + pendingCategories)
        }
        val accountMode = model.state.accountOwner != null
        val ledger = snapshot.ledgers.firstOrNull { it.id == snapshot.ledgerId && it.archivedAt == null }
        var awaitingId by remember { mutableStateOf<UUID?>(null) }
        var awaitingName by remember { mutableStateOf<String?>(null) }
        var submitting by remember { mutableStateOf(false) }
        var cancelGeneration by remember { mutableStateOf(0) }
        var creating by remember { mutableStateOf(false) }
        var name by remember { mutableStateOf("") }
        var ledgerRevision by remember { mutableStateOf<Long?>(null) }
        val saving = model.categorySaving
        val creationEnabled = enabled && awaitingId == null && !submitting && !saving && ledger != null &&
            (!accountMode || !model.state.accountClearPending && !model.state.restoreBusy)
        val latestOnSelect = rememberUpdatedState(onSelect)
        val selectCategory =
            remember {
                { id: UUID? ->
                    cancelGeneration++
                    submitting = false
                    awaitingId = null
                    awaitingName = null
                    latestOnSelect.value(id)
                }
            }
        LaunchedEffect(awaitingId, choices) {
            val pending = awaitingId ?: return@LaunchedEffect
            val created = choices.firstOrNull {
                it.id == pending && it.ledgerId == snapshot.ledgerId && it.direction == direction
            }
            if (created != null) {
                awaitingId = null
                awaitingName = null
                latestOnSelect.value(created.id)
            }
        }
        LaunchedEffect(submitting, saving) {
            if (!submitting || saving) return@LaunchedEffect
            submitting = false
            awaitingId = null
            awaitingName = null
        }
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (choices.isEmpty()) {
                Text("当前账本没有可用的${if (direction == Direction.INCOME) "收入" else "支出"}分类，可从下方添加。", style = MaterialTheme.typography.bodySmall)
            } else {
                CategorySelector(
                    owner = snapshot.ownerId,
                    categories = choices,
                    selected = selected,
                    revision = model.categoryStyleRevision,
                    emptyLabel = "请选择分类",
                    enabled = enabled,
                    pickerStyle = settings.categoryPickerStyle,
                    display = settings.categoryDisplay,
                    onSelect = selectCategory,
                )
            }
            if (awaitingId != null) {
                Text(
                    "「${awaitingName.orEmpty()}」已保存到本机，分类列表更新后自动选择；也可选择其他分类。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = {
                        cancelGeneration++
                        awaitingId = null
                        awaitingName = null
                    }, enabled = enabled) { Text("取消自动选择") }
                }
            }
            if (accountMode && choices.any { it.id == selected && it.localRevision == 0L }) {
                Text(
                    "此分类已保存到本机，可立即记账；分类和账单会在后台同步。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            if (suggestions.isNotEmpty()) {
                Text("推荐分类", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    suggestions.forEach { suggestion ->
                        FilterChip(
                            selected = false,
                            enabled = creationEnabled,
                            onClick = {
                                name = suggestion.name
                                ledgerRevision = ledger?.localRevision
                                model.clearError()
                                creating = true
                            },
                            leadingIcon = { AppIcon(suggestion.symbol) },
                            label = { Text(suggestion.name) },
                        )
                    }
                }
            }
            TextButton(enabled = creationEnabled, onClick = {
                name = ""
                ledgerRevision = ledger?.localRevision
                model.clearError()
                creating = true
            }) { AppIcon(AppSymbol.ADD); Text("自定义分类") }
        }
        if (creating) {
            val trimmedName = name.trim()
            val duplicate = (knownCategories + pendingCategories).any {
                it.direction == direction && it.parentId == null && it.name.trim() == trimmedName
            }
            val changed = ledger == null || ledger.localRevision != ledgerRevision
            val validName = trimmedName.codePointCount(0, trimmedName.length) in 1..100 && trimmedName.none { Character.isISOControl(it) }
            val busy = submitting || saving
            AlertDialog(
                onDismissRequest = {
                    if (!model.state.busy) {
                        cancelGeneration++
                        submitting = false
                        creating = false
                    }
                },
                title = { Text("添加${if (direction == Direction.INCOME) "收入" else "支出"}分类") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(name, { if (it.length <= 200) name = it }, enabled = creationEnabled && !changed,
                            label = { Text("分类名称") }, singleLine = true)
                        if (duplicate) Text("当前账本已有同名分类（含归档及待同步的分类），请选择已有分类或在分类管理中恢复。", color = MaterialTheme.colorScheme.error)
                        if (changed) Text("账本已变化，请关闭后重新确认。", color = MaterialTheme.colorScheme.error)
                        if (accountMode) Text("保存到本机后立即可用，后台同步无需等待。", style = MaterialTheme.typography.bodySmall)
                        model.state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                },
                confirmButton = {
                    TextButton(enabled = creationEnabled && validName && !duplicate && !changed, onClick = {
                        val generation = cancelGeneration
                        submitting = true
                        model.saveCategory(
                            name = trimmedName,
                            direction = direction,
                            parentId = null,
                            expectedLedgerRevision = ledgerRevision,
                        ) { id ->
                            if (generation == cancelGeneration) {
                                submitting = false
                                creating = false
                                awaitingId = id
                                awaitingName = trimmedName
                            }
                        }
                    }) { Text(if (busy) "保存中…" else "创建并选择") }
                },
                dismissButton = {
                    TextButton(enabled = !model.state.busy, onClick = {
                        cancelGeneration++
                        submitting = false
                        creating = false
                    }) { Text("取消") }
                },
            )
        }
    }
}

@Composable
private fun RecordEditor(
    draft: RecordDraft,
    snapshot: FinanceSnapshot,
    model: FinanceViewModel,
    template: RecordTemplateDraft? = null,
    efficiencySettings: RecordEfficiencySettings,
) {
    val categoryStyleContext = LocalContext.current.applicationContext
    val enabled = !model.state.busy && template?.mutationQueued != true
    val inputValidation = runCatching { draft.input(snapshot) }
    val nameValid = template == null || templateNameIsValid(template.name)
    val canSave = enabled && inputValidation.isSuccess && nameValid && (model.state.accountOwner == null || !draft.saveQueued)
    val validationError = if (!nameValid) "请输入有效的模板名称" else
        (inputValidation.exceptionOrNull() as? FinanceValidationException)?.message
            ?: if (inputValidation.isFailure) "请检查金额、优惠、费用、账户与分摊信息" else null
    val formError = model.state.error ?: validationError?.takeIf { template?.hasChanges ?: draft.hasChanges }
    val saveRecord: () -> Unit = { if (template == null) model.saveRecord() else model.saveRecordTemplate() }
    val saveLabel = if (!enabled) "保存中…" else if (template != null) "保存模板" else "保存"
    val editorTitle = if (template != null) {
        if (template.original == null) "新建模板" else "编辑模板"
    } else if (draft.id == null) "记一笔" else "编辑账单"
    val dismiss = rememberEditorDismiss(template?.hasChanges ?: draft.hasChanges, enabled) {
        if (template == null) model.dismissRecord() else model.dismissTemplate()
    }
    var moreProperties by remember(draft) { mutableStateOf(draft.split || draft.discount.isNotBlank() || !draft.countStatistics || !draft.countBudget || draft.tagIds.isNotEmpty()) }
    var activeAmountKey by remember(draft) { mutableStateOf<Any>("amount") }
    val selectedAccountIds =
        buildSet {
            draft.accountId?.let(::add)
            draft.fromId?.let(::add)
            draft.toId?.let(::add)
            draft.feeAccountId?.let(::add)
            draft.bonusAccountId?.let(::add)
            draft.allocations.mapNotNullTo(this) { it.accountId }
        }
    val visibleAccounts = model.visibleAccounts(snapshot)
    val visibleAccountIds = visibleAccounts.mapTo(mutableSetOf()) { it.id }
    val selectableAccounts = visibleAccounts.filter { it.selectable && it.archivedAt == null }
    val accounts =
        (selectableAccounts + snapshot.accounts.filter { it.id in selectedAccountIds && (it.archivedAt != null || !it.selectable || it.id !in visibleAccountIds) })
            .distinctBy { it.id }
            .map {
                val group = snapshot.accountGroups.firstOrNull { group -> group.id == it.groupId }?.name.orEmpty()
                val unavailableReason = when {
                    it.id !in visibleAccountIds -> " · 其他账本范围，不可新选"
                    it.archivedAt != null || !it.selectable -> " · 不可新选"
                    else -> ""
                }
                it.id to "$group · ${it.name} (${it.currency})$unavailableReason"
            }
    val matchingAccounts =
        accounts.filter { pair -> snapshot.accounts.first { it.id == pair.first }.currency == draft.currency }
            .map { it.first to it.second }
    val amountFields = buildMap<Any, Triple<String, String, (String) -> Unit>> {
        put("amount", Triple(if (draft.kind == EntryKind.TRANSFER) "转出金额" else "金额", draft.amount) { value: String -> draft.amount = value })
        if (draft.kind == EntryKind.TRANSFER) {
            if (snapshot.accounts.find { it.id == draft.fromId }?.currency != snapshot.accounts.find { it.id == draft.toId }?.currency) {
                put("to", Triple("实际到账金额", draft.toAmount) { value: String -> draft.toAmount = value })
            }
            put("fee", Triple("手续费", draft.fee) { value: String -> draft.fee = value })
            put("bonus", Triple("返现", draft.bonus) { value: String -> draft.bonus = value })
        } else {
            if (moreProperties && draft.kind == EntryKind.EXPENSE) {
                put("discount", Triple("优惠金额", draft.discount) { value: String -> draft.discount = value })
            }
            if (draft.split) draft.allocations.forEach { line ->
                val account = snapshot.accounts.find { it.id == line.accountId }
                put(line, Triple("${account?.name ?: "未选择账户"} · 实际金额", line.amount) { value: String -> line.amount = value })
                if (draft.kind == EntryKind.EXPENSE && account?.currency != draft.currency) {
                    put(line to "record", Triple("${account?.name ?: "未选择账户"} · 账单分摊金额", line.recordCurrencyAmount) { value: String -> line.recordCurrencyAmount = value })
                }
            }
        }
    }
    val effectiveAmountKey = activeAmountKey.takeIf { it in amountFields } ?: "amount"
    val (amountLabel, amountValue, changeAmount) = amountFields.getValue(effectiveAmountKey)
    val amountKeypad: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("输入：$amountLabel", style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            AmountKeyboard(amountValue, enabled, efficiencySettings, confirmButton = {
                Button(onClick = saveRecord, enabled = canSave, colors = primaryButtonColors(),
                    modifier = Modifier.fillMaxSize(), shape = RoundedCornerShape(8.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp)) {
                    Text(saveLabel, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
            }, onChange = changeAmount)
        }
    }
    EditorPage(
        onDismissRequest = dismiss,
        error = formError,
        topBarContent = {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp).semantics { contentDescription = editorTitle },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                IconButton(onClick = dismiss, enabled = enabled, modifier = Modifier.semantics { contentDescription = "返回" }) { Text("×", style = MaterialTheme.typography.headlineSmall) }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    EntryKind.entries.forEach { value ->
                        FilterChip(
                            selected = draft.kind == value,
                            onClick = { draft.selectKind(value, snapshot); activeAmountKey = "amount" },
                            enabled = enabled,
                            modifier = Modifier.weight(1f),
                            label = { Text(value.label, maxLines = 1) },
                        )
                    }
                }
                IconButton(onClick = saveRecord, enabled = canSave, modifier = Modifier.semantics { contentDescription = saveLabel }) {
                    Text("✓", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                }
            }
        },
        bottomContent = if (efficiencySettings.amountInputMode == AmountInputMode.NUMERIC) null else amountKeypad,
        title = { Text(editorTitle) },
        text = {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                template?.original?.invalidReason?.let { Text("模板失效：$it", color = MaterialTheme.colorScheme.error) }
                if (selectedAccountIds.any { id -> snapshot.accounts.any { it.id == id && (it.archivedAt != null || !it.selectable || it.id !in visibleAccountIds) } }) {
                    Text("草稿保留了不可新选或其他账本范围的账户引用；选择当前账本可用账户后才能保存。", color = MaterialTheme.colorScheme.error)
                }
                if (template != null) Input("模板名称", template.name, enabled) { template.name = it }
                if (model.state.accountOwner != null) {
                    Text("账号模板支持普通支出/收入、同币种账户分摊及同币种转账；不支持FX分摊和关联业务。", style = MaterialTheme.typography.bodySmall)
                }
                if (draft.kind == EntryKind.TRANSFER) {
                    val from = snapshot.accounts.find { it.id == draft.fromId }
                    val to = snapshot.accounts.find { it.id == draft.toId }
                    Choice("转出账户", accounts, draft.fromId, enabled = enabled) { draft.fromId = it }
                    Choice("转入账户", accounts, draft.toId, enabled = enabled) { draft.toId = it }
                    RecordAmountInput("转出金额（${from?.currency.orEmpty()}）", draft.amount, enabled, efficiencySettings, showKeyboard = false, active = effectiveAmountKey == "amount", onActivate = { activeAmountKey = "amount" }) { draft.amount = it }
                    if (from?.currency != to?.currency) {
                        RecordAmountInput("实际到账金额（${to?.currency.orEmpty()}）", draft.toAmount, enabled, efficiencySettings, showKeyboard = false, active = effectiveAmountKey == "to", onActivate = { activeAmountKey = "to" }) { draft.toAmount = it }
                    }
                    Choice("手续费账户", accounts, draft.feeAccountId ?: draft.fromId, enabled = enabled) { draft.feeAccountId = it }
                    val feeCurrency =
                        snapshot.accounts
                            .find { it.id == (draft.feeAccountId ?: draft.fromId) }
                            ?.currency
                            .orEmpty()
                    RecordAmountInput("手续费（$feeCurrency，选填）", draft.fee, enabled, efficiencySettings, showKeyboard = false, income = false, active = effectiveAmountKey == "fee", onActivate = { activeAmountKey = "fee" }) { draft.fee = it }
                    Choice("返现账户", accounts, draft.bonusAccountId ?: draft.toId, enabled = enabled) { draft.bonusAccountId = it }
                    val bonusCurrency =
                        snapshot.accounts
                            .find { it.id == (draft.bonusAccountId ?: draft.toId) }
                            ?.currency
                            .orEmpty()
                    RecordAmountInput("返现（$bonusCurrency，选填）", draft.bonus, enabled, efficiencySettings, showKeyboard = false, income = true, active = effectiveAmountKey == "bonus", onActivate = { activeAmountKey = "bonus" }) { draft.bonus = it }
                } else {
                    val direction = if (draft.kind == EntryKind.INCOME) Direction.INCOME else Direction.EXPENSE
                    val categoryItems =
                        remember(snapshot.categories, snapshot.ledgerId, direction) {
                            recordCategoryChoices(snapshot, direction)
                        }
                    val selectedCategory = remember(categoryItems, draft.categoryId) {
                        categoryItems.firstOrNull { it.id == draft.categoryId }
                    }
                    val onCategorySelected =
                        remember(draft) {
                            { id: UUID? -> draft.categoryId = id }
                        }
                    val selectedCategoryStyle =
                        selectedCategory?.let { category ->
                            rememberCategoryStyle(categoryStyleContext, snapshot.ownerId, category, model.categoryStyleRevision)
                        }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        RecordAmountInput(
                            "金额 · ${draft.currency}", draft.amount, enabled, efficiencySettings, showKeyboard = false,
                            income = draft.kind == EntryKind.INCOME, active = effectiveAmountKey == "amount",
                            onActivate = { activeAmountKey = "amount" }, modifier = Modifier.weight(1f),
                        ) { draft.amount = it }
                        Column(Modifier.width(72.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            selectedCategoryStyle?.let {
                                CategoryStyleBadge(it, Modifier.size(56.dp))
                            }
                            Text(selectedCategory?.name ?: if (draft.categoryId == null) "请选择分类" else "原分类不可用", textAlign = TextAlign.Center, maxLines = 2)
                        }
                    }
                    Card(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    ) {
                        key(draft, direction) {
                            RecordCategoryPicker(
                                snapshot = snapshot,
                                model = model,
                                direction = direction,
                                choices = categoryItems,
                                selected = draft.categoryId,
                                enabled = enabled,
                                settings = efficiencySettings,
                                onSelect = onCategorySelected,
                            )
                        }
                    }
                    if (moreProperties) Input("币种（如 CNY）", draft.currency, enabled) {
                        if (it.length <= 3) {
                            draft.currency = it.uppercase(Locale.ROOT)
                            if (snapshot.accounts.find { account -> account.id == draft.accountId }?.currency !=
                                draft.currency
                            ) {
                                draft.accountId = null
                            }
                        }
                    }
                    if (moreProperties && draft.kind == EntryKind.EXPENSE) RecordAmountInput("优惠金额（选填）", draft.discount, enabled, efficiencySettings, showKeyboard = false, active = effectiveAmountKey == "discount", onActivate = { activeAmountKey = "discount" }) { draft.discount = it }
                    if (moreProperties) Toggle("组合账户", draft.split, enabled) {
                        draft.split = it
                        activeAmountKey = "amount"
                        if (it && draft.allocations.isEmpty()) draft.allocations.add(AllocationDraft(draft.accountId, ""))
                    }
                val allocationAccounts = if (draft.kind == EntryKind.EXPENSE) accounts else matchingAccounts
                if (draft.split) {
                    draft.allocations.forEach { line ->
                        key(line) {
                            Choice("组合账户", allocationAccounts, line.accountId, enabled = enabled) { line.accountId = it }
                            val accountCurrency = snapshot.accounts.firstOrNull { it.id == line.accountId }?.currency ?: draft.currency
                            RecordAmountInput("账户实际金额（$accountCurrency）", line.amount, enabled, efficiencySettings, showKeyboard = false, active = effectiveAmountKey == line, onActivate = { activeAmountKey = line }) { line.amount = it }
                            if (draft.kind == EntryKind.EXPENSE && accountCurrency != draft.currency) {
                                RecordAmountInput("账单分摊金额（${draft.currency}）", line.recordCurrencyAmount, enabled, efficiencySettings, showKeyboard = false, active = effectiveAmountKey == (line to "record"), onActivate = { activeAmountKey = (line to "record") }) { line.recordCurrencyAmount = it }
                            }
                            TextButton(onClick = { draft.allocations.remove(line); activeAmountKey = "amount" }, enabled = enabled) { Text("移除该账户") }
                        }
                    }
                    TextButton(
                        onClick = { draft.allocations.add(AllocationDraft(null, "")) },
                        enabled = enabled && draft.allocations.size < selectableAccounts.size,
                    ) { AppIcon(AppSymbol.ADD); Text("添加组合账户") }
                    } else {
                        Card(
                            Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        ) {
                            Choice("资金账户", matchingAccounts, draft.accountId, emptyLabel = "不计入账户", enabled = enabled && model.state.accountOwner == null, row = true) { draft.accountId = it }
                        }
                    }
                    if (moreProperties) {
                    Toggle("计入统计", draft.countStatistics, enabled) { draft.countStatistics = it }
                    if (draft.kind == EntryKind.EXPENSE) {
                        Toggle("计入预算", draft.countBudget, enabled) { draft.countBudget = it }
                        if (categoryExcludesBudget(draft.categoryId, snapshot.allCategories)) {
                            Text("当前分类或其上级不计预算；本笔不会占用预算，单笔选择会保留。", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                }
                TextButton(onClick = { moreProperties = !moreProperties; activeAmountKey = "amount" }) {
                    AppIcon(if (moreProperties) AppSymbol.DOWN else AppSymbol.MORE)
                    Text(if (moreProperties) "收起更多属性" else "更多属性（币种、优惠、组合账户、统计、标签）")
                }
                if (moreProperties) TagSelector(
                    snapshot = snapshot,
                    selected = draft.tagIds.toList(),
                    enabled = enabled,
                    onConfirm = { draft.tagIds.clear(); draft.tagIds.addAll(it) },
                    onQuickCreate = model::quickCreateTag,
                )
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        DateTimeInput(draft.occurredAtMillis, draft.timezone, enabled, model::showError, compact = true) { draft.occurredAtMillis = it }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        OutlinedTextField(value = draft.remark, onValueChange = {
                            if (it.length <= 2000) draft.remark = it
                        }, label = { Text("备注") }, enabled = enabled, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    }
                }
                if (efficiencySettings.remarkSuggestions) {
                    RecordRemarkSuggestions(
                        snapshot = snapshot,
                        currentRemark = draft.remark,
                        categoryId = draft.categoryId,
                        kind = draft.kind,
                        enabled = enabled,
                        onSelect = { draft.remark = it },
                    )
                }
                if (moreProperties && template == null) {
                    RecordLocationPanel(
                        record = draft.originalRecord,
                        ownerId = snapshot.ownerId,
                        epochId = snapshot.epochId,
                        enabled = enabled && model.state.accountOwner == null,
                        operationError = model.state.locationOperationStatus,
                        refreshKey = model.state.locationRevision,
                        cleanupFailed = model.state.pendingLocationCleanupId != null,
                        onSave = model::saveRecordLocation,
                        onDelete = model::deleteRecordLocation,
                        onRetryCleanup = model::retryRecordLocationCleanup,
                    )
                }
                if (draft.originalRecord !=
                    null
                ) {
                    if (template == null) {
                        if (model.state.accountOwner == null || (draft.originalRecord.input is RecordInput.Expense || draft.originalRecord.input is RecordInput.Income) && draft.originalRecord.accountMutationSupported) {
                            TextButton(onClick = { model.copyRecord(draft.originalRecord) }, enabled = enabled) { Text("复制账单") }
                        }
                        if (model.state.accountOwner == null || (draft.originalRecord.input is RecordInput.Expense || draft.originalRecord.input is RecordInput.Income || draft.originalRecord.input is RecordInput.Transfer) && draft.originalRecord.accountMutationSupported) {
                            TextButton(onClick = { model.createTemplateFromRecord() }, enabled = enabled) { Text("另存为模板") }
                        }
                    }
                    if (model.state.accountOwner == null || draft.originalRecord.localRevision > 0 && draft.originalRecord.accountMutationSupported) {
                        TextButton(onClick = { model.requestDelete(draft.originalRecord) }, enabled = enabled) {
                            Text("删除账单", color = MaterialTheme.colorScheme.error)
                        }
                    } else {
                        Text("当前账单暂不可删除。", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = saveRecord, enabled = canSave, colors = primaryButtonColors()) { Text(saveLabel) }
        },
        dismissButton = {
            TextButton(onClick = dismiss, enabled = enabled) { Text("取消") }
        },
    )
}

@Composable
private fun AccountEditor(
    draft: AccountDraft,
    snapshot: FinanceSnapshot,
    model: FinanceViewModel,
) {
    val original = draft.original
    val sourceChanged = original != null && snapshot.allAccounts.none { it.id == original.id && it.localRevision == original.localRevision }
    val groupChanged = original != null && snapshot.accountGroups.none { it.id == draft.groupId && it.localRevision == draft.groupRevision }
    val enabled = !model.state.busy && !draft.mutationQueued && (original == null || !sourceChanged &&
        !model.state.accountMutationPending && !model.state.syncPaused && !model.state.accountClearPending && !model.state.restoreBusy)
    val dismiss = rememberEditorDismiss(draft.hasChanges, !model.state.busy, model::dismissAccount)
    EditorPage(
        onDismissRequest = dismiss,
        error = model.state.error,
        title = { Text(if (original == null) "新增账户" else "编辑账户资料") },
        text = {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Input("账户名称", draft.name, enabled) { if (it.length <= 80) draft.name = it }
                Choice(
                    "账户分组",
                    snapshot.accountGroups.map { it.id to it.name },
                    draft.groupId ?: snapshot.accountGroups.firstOrNull { it.isDefault }?.id,
                    enabled = enabled,
                ) {
                    draft.groupId = it
                    draft.groupRevision = snapshot.accountGroups.firstOrNull { group -> group.id == it }?.localRevision
                }
                if (original == null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("ASSET" to "资产账户", "LIABILITY" to "负债账户").forEach { (type, label) ->
                            FilterChip(selected = draft.accountType == type, onClick = { draft.accountType = type }, label = { Text(label) }, enabled = enabled)
                        }
                    }
                    Input("币种（如 CNY）", draft.currency, enabled) { if (it.length <= 3) draft.currency = it.uppercase(Locale.ROOT) }
                    Input("期初余额（可为负数）", draft.opening, enabled) { draft.opening = it }
                    Text("期初余额计入账户，不计作统计收入或预算收入。", style = MaterialTheme.typography.bodySmall)
                    DateTimeInput(draft.openingDateMillis, TimeZone.getDefault().id, enabled, model::showError) { draft.openingDateMillis = it }
                } else {
                    Text("币种 ${original.currency} · 余额 ${display(original.balance)}", style = MaterialTheme.typography.bodySmall)
                    Text("修改名称与分组后，历史账单、余额和账本显示范围会保留。", style = MaterialTheme.typography.bodySmall)
                    if (sourceChanged) Text("账户版本已变化，请关闭后重新编辑。", color = MaterialTheme.colorScheme.error)
                    if (groupChanged) Text("分组版本已变化，请重新选择分组。", color = MaterialTheme.colorScheme.error)
                }
                if (draft.mutationQueued) {
                    Text("账户创建仍在本机等待同步，服务器尚未确认。请使用页面上的刷新同步继续；草稿会复用原操作标识。", color = MaterialTheme.colorScheme.tertiary)
                }
            }
        },
        confirmButton = { Button(onClick = model::saveAccount, enabled = enabled && !groupChanged && draft.name.isNotBlank(), colors = primaryButtonColors()) {
            Text(if (original != null) "保存修改" else if (model.state.busy) "创建中…" else "创建")
        } },
        dismissButton = { TextButton(onClick = dismiss, enabled = !model.state.busy) { Text("取消") } },
    )
}

internal fun refundRecovery(
    snapshot: FinanceSnapshot,
    record: FinanceRecord,
    additionalReceipt: RefundReceipt? = null,
): RefundRecovery {
    val expense = record.input as RecordInput.Expense
    val original = expense.discount?.let(expense.gross::checkedSubtract) ?: expense.gross
    val receipts =
        snapshot.refunds
            .filter { it.originRecordId == record.id && it.status == "POSTED" && it.amount.currency == original.currency }
            .sortedBy { it.createdSequence }
            .map { RefundReceipt(it.id, it.amount, false) }
    return calculateRefundRecovery(original, if (additionalReceipt == null) receipts else receipts + additionalReceipt)
}

@Composable
private fun RefundDialog(
    snapshot: FinanceSnapshot,
    record: FinanceRecord,
    model: FinanceViewModel,
    onDismiss: () -> Unit,
    onCommitted: () -> Unit,
) {
    val expense = record.input as RecordInput.Expense
    val original = expense.discount?.let(expense.gross::checkedSubtract) ?: expense.gross
    val accountOwner = model.state.accountOwner
    val current = if (accountOwner != null) null else refundRecovery(snapshot, record)
    var amountText by remember(record.id) { mutableStateOf(if ((current?.netExpense ?: original).unscaled > 0) moneyText(current?.netExpense ?: original) else "") }
    val accounts = model.visibleAccounts(snapshot).filter { it.currency == original.currency && it.selectable && it.archivedAt == null }.map { account ->
        account.id to "${snapshot.accountGroups.firstOrNull { it.id == account.groupId }?.name.orEmpty()} · ${account.name}"
    }
    var accountId by remember(record.id) { mutableStateOf(accounts.firstOrNull()?.first) }
    var refundAtMillis by remember(record.id) { mutableStateOf(System.currentTimeMillis()) }
    val previewId = remember(record.id) { UUID.randomUUID() }
    val amount = runCatching { moneyInput(amountText, original.currency).also { it.requirePositive() } }.getOrNull()
    val preview = amount?.takeIf { accountOwner == null }?.let { runCatching { refundRecovery(snapshot, record, RefundReceipt(previewId, it, false)) }.getOrNull() }
    val sourceChanged = snapshot.records.none { it.id == record.id && it.localRevision == record.localRevision }
    val timeValid = refundAtMillis in record.occurredAtMillis..System.currentTimeMillis()
    val enabled = !model.state.busy
    AlertDialog(
        onDismissRequest = { if (enabled) onDismiss() },
        title = { Text("登记退款 · ${original.currency}") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (record.remark.isNotBlank()) Text(record.remark)
                Text("原实付金额 ${display(original)}")
                if (current != null) {
                    Text("已入账退款 ${display(current.refunded)}")
                    Text("当前净支出 ${display(current.netExpense)}")
                } else {
                    Text("退款历史贡献正在等待服务器规范；此处不会预测多笔退款的差额，确认后请同步查看结果。", style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { if (it.length <= 32) amountText = it },
                    label = { Text("本次退款金额 · ${original.currency}") },
                    singleLine = true,
                    enabled = enabled,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                    if (accountOwner == null && preview != null) {
                        val effect = preview.effects.last()
                        Text("本次冲减支出 ${display(effect.expenseReduction)}")
                        Text("退款后净支出 ${display(preview.netExpense)}")
                        Text("本次超额 ${display(effect.excessAmount)} · 累计保留超额 ${display(preview.excessAmount)}")
                    } else {
                        Text(if (accountOwner != null) "账号账本退款效果待服务器确认" else "请输入有效的正数金额以预览退款差额", style = MaterialTheme.typography.bodySmall)
                    }
                Text("退款全额进入所选账户；冲减支出以原实付金额为限。超出部分保留，不自动计为收入。", style = MaterialTheme.typography.bodySmall)
                Choice("退款收款账户 · ${original.currency}", accounts, accountId, enabled = enabled) { accountId = it }
                if (accounts.isEmpty()) Text("请先添加 ${original.currency} 账户", color = MaterialTheme.colorScheme.error)
                Text("退款时间（不得早于原消费 ${dateText(record.occurredAtMillis, record.timezone)}）")
                DateTimeInput(refundAtMillis, record.timezone, enabled, model::showError) { refundAtMillis = it }
                if (!timeValid) Text("退款时间不能早于原消费或晚于现在", color = MaterialTheme.colorScheme.error)
                if (original.unscaled <= 0) Text("原实付金额为零，不能登记退款", color = MaterialTheme.colorScheme.error)
                if (sourceChanged) Text("原账单已变更，请关闭后重新打开退款", color = MaterialTheme.colorScheme.error)
                model.state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(
                enabled = enabled && (if (accountOwner != null) amount != null else preview != null) && original.unscaled > 0 &&
                    accounts.any { it.first == accountId } && timeValid && !sourceChanged,
                onClick = {
                    model.createRefund(
                        record,
                        requireNotNull(amount),
                        requireNotNull(accountId),
                        refundAtMillis,
                        record.timezone,
                        onCommitted = onCommitted,
                        onEnqueued = onDismiss,
                    )
                },
            ) { Text(if (enabled) "确认退款" else "退款中…") }
        },
        dismissButton = { TextButton(enabled = enabled, onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun ClaimSettlementDialog(
    snapshot: FinanceSnapshot,
    targets: List<FinanceRecord>,
    timezone: String,
    model: FinanceViewModel,
    onDismiss: () -> Unit,
    onCommitted: () -> Unit,
) {
    val currency = (targets.first().input as RecordInput.Expense).gross.currency
    val initialAmounts =
        remember(targets, snapshot.claimSettlements, model.state.accountOwner) {
            targets.associate { record ->
                record.id to if (model.state.accountOwner != null) "" else moneyText(claimRemainingEligible(snapshot, record))
            }
        }
    var amounts by remember(targets, snapshot.claimSettlements, model.state.accountOwner) { mutableStateOf(initialAmounts) }
    var accountId by remember(targets, snapshot.accounts, model.state.guestAccountLedgerVisibility, snapshot.ledgerId) {
        mutableStateOf(model.visibleAccounts(snapshot).firstOrNull { it.currency == currency && it.selectable && it.archivedAt == null }?.id)
    }
    var incomeCategoryId by remember(snapshot.categories) {
        mutableStateOf(snapshot.categories.firstOrNull { it.direction == Direction.INCOME }?.id)
    }
    var remainderAction by remember { mutableStateOf(RemainderAction.PARTIAL_KEEP) }
    var settledAtMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    val previewId = remember { UUID.randomUUID() }
    val preview = if (model.state.accountOwner == null) {
        targets
            .mapNotNull { record ->
                val amount = runCatching { moneyInput(amounts[record.id].orEmpty(), currency) }.getOrNull() ?: return@mapNotNull null
                val expense = record.input as RecordInput.Expense
                val original = expense.discount?.let(expense.gross::checkedSubtract) ?: expense.gross
                val receipts = snapshot.claimSettlements.mapNotNull { settlement ->
                    settlement.allocations.singleOrNull { it.originRecordId == record.id }?.let {
                        ClaimReceipt(settlement.id, it.actualAmount, settlement.remainderAction, settlement.status == "CANCELLED")
                    }
                } + ClaimReceipt(previewId, amount, remainderAction, false)
                runCatching { calculateClaimRecovery(original, receipts) }.getOrNull()?.let { record.id to it }
            }.toMap()
    } else emptyMap()
    val accounts = model.visibleAccounts(snapshot).filter { it.currency == currency && it.selectable && it.archivedAt == null }.map { account ->
        account.id to "${snapshot.accountGroups.firstOrNull { it.id == account.groupId }?.name.orEmpty()} · ${account.name}"
    }
    val incomeCategories = snapshot.categories.filter { it.direction == Direction.INCOME }.map { it.id to it.name }
    AlertDialog(
        onDismissRequest = { if (!model.state.busy) onDismiss() },
        title = { Text("报销收款 · $currency") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                targets.forEach { record ->
                    Text(record.remark.ifBlank { "账单 ${record.id.toString().take(8)}" })
                    OutlinedTextField(
                        value = amounts[record.id].orEmpty(),
                        onValueChange = { value -> if (value.length <= 32) amounts = amounts + (record.id to value) },
                        label = { Text("本笔实收 · $currency") },
                        singleLine = true,
                        enabled = !model.state.busy,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    preview[record.id]?.let { recovery ->
                        Text(
                            "预计净支出 ${display(recovery.netExpense)} · 待报 ${display(recovery.remainingEligible)} · 超额收入 ${display(recovery.excessIncome)}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } ?: Text(if (model.state.accountOwner != null) "历史报销贡献待服务器确认；此处不预测差额" else "输入有效的正数金额后显示差额预览", style = MaterialTheme.typography.bodySmall)
                }
                Choice("入账账户", accounts, accountId, enabled = !model.state.busy) { accountId = it }
                Choice("超额收入分类", incomeCategories, incomeCategoryId, enabled = !model.state.busy) { incomeCategoryId = it }
                Text("剩余未报销金额")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = remainderAction == RemainderAction.PARTIAL_KEEP,
                        onClick = { remainderAction = RemainderAction.PARTIAL_KEEP },
                        enabled = !model.state.busy,
                        label = { Text("保留待报") },
                    )
                    FilterChip(
                        selected = remainderAction == RemainderAction.COMPLETE,
                        onClick = { remainderAction = RemainderAction.COMPLETE },
                        enabled = !model.state.busy,
                        label = { Text("直接完成") },
                    )
                }
                DateTimeInput(
                    settledAtMillis,
                    timezone,
                    enabled = !model.state.busy,
                    onError = model::showError,
                    onChange = { settledAtMillis = it },
                )
                model.state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(enabled = !model.state.busy && accountId != null, onClick = {
                try {
                    val allocations =
                        targets.map { record ->
                            FinanceClaimAllocation(record.id, moneyInput(amounts[record.id].orEmpty(), currency), record.localRevision)
                        }
                    model.settleClaims(
                        allocations,
                        requireNotNull(accountId),
                        incomeCategoryId,
                        remainderAction,
                        settledAtMillis,
                        timezone,
                        onCommitted = onCommitted,
                        onEnqueued = onDismiss,
                    )
                } catch (failure: Exception) {
                    model.showError(failure.message ?: "请检查报销金额")
                }
            }) { Text(if (model.state.busy) "收款中…" else "确认收款") }
        },
        dismissButton = { TextButton(enabled = !model.state.busy, onClick = onDismiss) { Text("取消") } },
    )
}

private fun claimRemainingEligible(
    snapshot: FinanceSnapshot,
    record: FinanceRecord,
): Money {
    val expense = record.input as RecordInput.Expense
    val original = expense.discount?.let(expense.gross::checkedSubtract) ?: expense.gross
    val receipts =
        snapshot.claimSettlements.mapNotNull { settlement ->
            settlement.allocations.singleOrNull { it.originRecordId == record.id }?.let { allocation ->
                ClaimReceipt(settlement.id, allocation.actualAmount, settlement.remainderAction, settlement.status == "CANCELLED")
            }
        }
    return calculateClaimRecovery(original, receipts).remainingEligible
}

@Composable
private fun Input(
    label: String,
    value: String,
    enabled: Boolean,
    decimal: Boolean = false,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { if (it.length <= 80) onChange(it) },
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Text),
    )
}

@Composable
private fun Toggle(
    label: String,
    value: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = value, onCheckedChange = onChange, enabled = enabled)
        Text(label)
    }
}

@Composable
internal fun Choice(
    label: String,
    items: List<Pair<UUID, String>>,
    selected: UUID?,
    emptyLabel: String? = null,
    enabled: Boolean,
    pickerStyle: CategoryPickerStyle = CategoryPickerStyle.POPUP,
    row: Boolean = false,
    onSelect: (UUID?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val value = items.find { it.first == selected }?.second ?: if (selected == null) emptyLabel ?: "请选择" else "请选择"
    if (pickerStyle == CategoryPickerStyle.POPUP) {
        Box(Modifier.fillMaxWidth()) {
            if (row) {
                EditorFieldRow(
                    label = label,
                    value = value,
                    enabled = enabled,
                    onClick = { expanded = !expanded },
                    leading = { AppIcon(AppSymbol.WALLET, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                )
            } else {
                OutlinedButton(onClick = { expanded = !expanded }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                    Text("$label：$value")
                }
            }
            DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
                if (emptyLabel != null) DropdownMenuItem(text = { Text(emptyLabel) }, onClick = { expanded = false; onSelect(null) })
                items.forEach { (id, name) -> DropdownMenuItem(text = { Text(name) }, onClick = { expanded = false; onSelect(id) }) }
            }
        }
    } else {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (row) {
                EditorFieldRow(
                    label = label,
                    value = value,
                    enabled = enabled,
                    onClick = { expanded = !expanded },
                    leading = { AppIcon(AppSymbol.WALLET, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                )
            } else {
                OutlinedButton(onClick = { expanded = !expanded }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                    Text("$label：$value")
                }
            }
            if (expanded) {
                Column(Modifier.fillMaxWidth().padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (emptyLabel != null) FilterChip(selected == null, { expanded = false; onSelect(null) }, label = { Text(emptyLabel) }, enabled = enabled)
                    items.forEach { (id, name) -> FilterChip(selected == id, { expanded = false; onSelect(id) }, label = { Text(name) }, enabled = enabled) }
                }
            }
        }
    }
}

@Composable
internal fun DateTimeInput(
    value: Long,
    zone: String,
    enabled: Boolean,
    onError: (String) -> Unit,
    compact: Boolean = false,
    onChange: (Long) -> Unit,
) {
    val context = LocalContext.current
    if (compact) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(dateText(value, zone), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            AssistChip(onClick = { pickDate(context, value, zone, onError, onChange) }, enabled = enabled, label = { Text("日期") })
            AssistChip(onClick = { pickTime(context, value, zone, onError, onChange) }, enabled = enabled, label = { Text("时间") })
        }
    } else {
        Text(dateText(value, zone), style = MaterialTheme.typography.bodyLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { pickDate(context, value, zone, onError, onChange) }, enabled = enabled) { Text("日期") }
            OutlinedButton(onClick = { pickTime(context, value, zone, onError, onChange) }, enabled = enabled) { Text("时间") }
        }
        Text("时区：$zone", style = MaterialTheme.typography.bodySmall)
    }
}

private fun pickDate(
    context: Context,
    value: Long,
    zone: String,
    onError: (String) -> Unit,
    onChange: (Long) -> Unit,
) {
    val calendar =
        Calendar.getInstance(TimeZone.getTimeZone(zone), Locale.ROOT).apply {
            timeInMillis = value
            isLenient = false
        }
    DatePickerDialog(context, { _, year, month, day ->
        try {
            calendar.set(year, month, day)
            onChange(calendar.timeInMillis)
        } catch (
            _: IllegalArgumentException,
        ) {
            onError("所选日期时间在该时区不存在，请重新选择")
        }
    }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)).show()
}

private fun pickTime(
    context: Context,
    value: Long,
    zone: String,
    onError: (String) -> Unit,
    onChange: (Long) -> Unit,
) {
    val calendar =
        Calendar.getInstance(TimeZone.getTimeZone(zone)).apply {
            timeInMillis = value
            isLenient = false
        }
    TimePickerDialog(context, { _, hour, minute ->
        try {
            calendar.set(Calendar.HOUR_OF_DAY, hour)
            calendar.set(Calendar.MINUTE, minute)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)
            onChange(calendar.timeInMillis)
        } catch (_: IllegalArgumentException) {
            onError("所选日期时间在该时区不存在，请重新选择")
        }
    }, calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE), DateFormat.is24HourFormat(context)).show()
}

private fun dateText(
    value: Long,
    zone: String,
): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).apply { timeZone = TimeZone.getTimeZone(zone) }.format(Date(value))

internal fun display(value: Money): String = "${ledgerAmount(java.math.BigDecimal.valueOf(value.unscaled, value.scale), value.currency)} ${value.currency}"

/** Home timeline day header; the ISO day stays the grouping key, only the label is localized. */
private fun dayHeaderLabel(
    day: String,
    todayKey: String,
): String {
    val date = runCatching { java.time.LocalDate.parse(day) }.getOrNull() ?: return day
    val today = runCatching { java.time.LocalDate.parse(todayKey) }.getOrNull()
    val label =
        if (today?.year == date.year) {
            "${date.monthValue}月${date.dayOfMonth}日"
        } else {
            "${date.year}年${date.monthValue}月${date.dayOfMonth}日"
        }
    return when (date) {
        today -> "今天 · $label"
        today?.minusDays(1) -> "昨天 · $label"
        else -> label
    }
}
