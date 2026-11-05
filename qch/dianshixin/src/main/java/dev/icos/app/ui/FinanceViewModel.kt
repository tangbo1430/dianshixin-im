package dev.icos.app.ui

import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import dev.icos.app.data.local.BudgetScope
import dev.icos.app.data.local.FinanceBudgetOverview
import dev.icos.app.data.local.FinanceClaimAllocation
import dev.icos.app.data.local.FinanceAccount
import dev.icos.app.data.local.FinanceLedger
import dev.icos.app.data.local.FinanceCategory
import dev.icos.app.data.local.FinanceRecord
import dev.icos.app.data.local.FinanceRecordTemplate
import dev.icos.app.data.local.FinanceSnapshot
import dev.icos.app.data.local.FinanceTag
import dev.icos.app.data.local.FinanceTagGroup
import dev.icos.app.data.local.FinanceValidationException
import dev.icos.app.data.local.LocalFinanceRepository
import dev.icos.app.data.local.SearchPreferencesEntity
import dev.icos.app.data.local.SnapshotImporter
import dev.icos.app.data.local.StorageOpenException
import dev.icos.app.data.remote.AuthClient
import dev.icos.app.data.local.AccountMutationRepository
import dev.icos.app.data.local.AccountSyncCoordinator
import dev.icos.app.data.local.CanonicalAccountRepository
import dev.icos.app.data.local.DatasetDatabase
import dev.icos.app.data.local.EncryptedDatasetStore
import dev.icos.app.fileio.AccountFileImportCoordinator
import dev.icos.app.fileio.AndroidFileIoAdapter
import dev.icos.app.fileio.ImportPreview
import dev.icos.app.data.remote.AuthFailure
import dev.icos.app.data.remote.DeviceIdentity
import dev.icos.app.data.remote.DeviceSessions
import dev.icos.app.data.remote.PendingDatasetClear
import dev.icos.app.data.remote.RemoteBackup
import dev.icos.app.data.remote.RestoreOperation
import dev.icos.app.data.remote.RestoreStatus
import dev.icos.app.data.remote.SyncConflict
import dev.icos.app.data.remote.SyncConflictResolution
import dev.icos.app.data.remote.TypedSyncClient
import org.json.JSONObject
import dev.icos.app.fileio.AccountExportFilter
import dev.icos.app.fileio.AccountExportFormat
import dev.icos.app.fileio.AccountFileExport
import dev.icos.app.fileio.FileExportSnapshot
import dev.icos.app.fileio.AccountExportTicket
import dev.icos.app.fileio.AccountExportRequest
import dev.icos.app.fileio.FinanceFileExportMapper
import dev.icos.app.fileio.GuestFileImportCoordinator
import dev.icos.app.fileio.TabularFileCore
import dev.icos.app.domain.Money
import dev.icos.app.domain.MoneyException
import dev.icos.app.domain.PeriodRange
import dev.icos.app.domain.PeriodResolver
import dev.icos.app.domain.RecordException
import dev.icos.app.domain.RecordInput
import dev.icos.app.domain.RemainderAction
import dev.icos.app.domain.ReportPeriod
import dev.icos.app.domain.AccountAmount
import dev.icos.app.domain.LoanDeleteStrategy
import dev.icos.app.domain.LoanFuturePolicy
import dev.icos.app.domain.LoanPlanInput
import dev.icos.app.domain.LoanPreview
import dev.icos.app.domain.LoanCalculator
import dev.icos.app.domain.LoanTerm
import dev.icos.app.domain.RepaymentMethod
import dev.icos.app.domain.SavingEntryInput
import dev.icos.app.domain.SavingEntryKind
import dev.icos.app.domain.SavingLinkedTransfer
import dev.icos.app.domain.SavingPlanPatch
import dev.icos.app.domain.SavingPatchValue
import dev.icos.app.domain.SavingTransferStrategy
import java.io.File
import java.time.ZoneId
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal data class FinanceUiState(
    val snapshot: FinanceSnapshot? = null,
    val budget: FinanceBudgetOverview? = null,
    val templates: List<FinanceRecordTemplate> = emptyList(),
    val busy: Boolean = false,
    val pendingCategories: List<FinanceCategory> = emptyList(),
    val error: String? = null,
    val bootstrapProgress: String? = null,
    val bootstrapError: String? = null,
    val accountOwner: String? = null,
    val guestAccountLedgerVisibility: Map<UUID, String> = emptyMap(),
    val accountSummary: String? = null,
    val accountLedgers: List<CanonicalAccountRepository.LedgerOption> = emptyList(),
    val accountRecurrences: List<CanonicalAccountRepository.RecurrenceSummary> = emptyList(),
    val accountMutationPending: Boolean = false,
    val accountMutationRetryOnly: Boolean = false,
    val assetHistory: List<dev.icos.app.data.local.AssetHistoryPoint> = emptyList(),
    val assetHistoryBusy: Boolean = false,
    val assetHistoryError: String? = null,
    val assetHistoryMonths: Int = 12,
    val assetHistoryIncludeFuture: Boolean = false,
    val syncPaused: Boolean = false,
    val syncLastSuccessAt: Long? = null,
    val syncPendingCount: Int = 0,
    val syncError: SyncError? = null,
    val syncEpochArchived: Boolean = false,
    val syncPausedRequestUncertain: Boolean = false,
    val syncRecoveryRequired: Boolean = false,
    val clearPreview: dev.icos.app.data.local.ClearRecordsPolicy.Preview? = null,
    val accountClearPreview: String? = null,
    val accountClearPending: Boolean = false,
    val accountClearReauthRequired: Boolean = false,
    val restoreBusy: Boolean = false,
    val restoreProgress: String? = null,
    val restoreError: String? = null,
    val restoreSubmissionRetryAvailable: Boolean = false,
    val restoreJobId: UUID? = null,
    val restoreStatus: RestoreStatus? = null,
    val backups: List<RemoteBackup> = emptyList(),
    val backupsBusy: Boolean = false,
    val selectedRestoreBackup: RemoteBackup? = null,
    val conflicts: List<SyncConflict> = emptyList(),
    val conflictsBusy: Boolean = false,
    val conflictDetail: SyncConflict? = null,
    val conflictError: String? = null,
    val conflictResolutionBusy: Boolean = false,
    val conflictResolutionResult: SyncConflictResolution? = null,
    val conflictEditedGroupJson: String = "",
    val conflictReviewKeys: String = "",
    val guestMultiLedgerEnabled: Boolean = true,
    val locationRevision: Long = 0,
    val locationOperationStatus: String? = null,
    val pendingLocationCleanupId: UUID? = null,
)

/** The owner and device session the ViewModel has actually bound; the identity separates two sessions of one account. */
internal data class BoundSession(
    val owner: UUID?,
    val identity: DeviceIdentity?,
    val guestMode: Boolean = owner == null,
)

internal data class FailedRecordSave(val save: PendingRecordSave, val message: String)

internal class FinanceViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val executor = Executors.newSingleThreadExecutor()
    private val accountLocalExecutor = Executors.newSingleThreadExecutor()
    private val accountUploadExecutor = Executors.newSingleThreadExecutor { task ->
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            task.run()
        }, "account-upload")
    }
    private val accountMutationVersion = AtomicLong()
    private var accountSyncRunning = false
    private val main = Handler(Looper.getMainLooper())
    private val auth = AuthClient(application)
    @Volatile private var conflictRequestGeneration: Long = 0
    @Volatile private var syncControlIdentity: DeviceIdentity? = null
    private var repository: LocalFinanceRepository? = null
    // Owned exclusively by accountLocalExecutor; the selected directory changes on rebootstrap.
    private var accountDatabase: DatasetDatabase? = null
    private var accountDatabaseDirectory: File? = null
    @Volatile private var activeOwner: UUID? = null
    @Volatile private var syncRecoveryRequired = false
    @Volatile private var accountSummary: String? = null
    @Volatile private var selectedAccountLedgerId: UUID? = null
    @Volatile private var selectedGuestLedgerId: UUID? = null
    @Volatile private var selectedClearLedgerId: UUID? = null
    @Volatile private var selectedClearOwnerId: UUID? = null
    @Volatile private var selectedClearEpochId: UUID? = null
    @Volatile private var selectedClearGeneration: Long? = null
    @Volatile private var syncedAccountEpoch: UUID? = null
    @Volatile private var loadedAccountEpoch: UUID? = null
    @Volatile private var generation: Long = 0
    private val reminderFenceLock = Any()
    private var reminderGeneration = 0L
    private var reminderOwner: String? = null
    @Volatile private var cachedReminderIdentity: DeviceIdentity? = null
    private var locationRevision: Long = 0
    private val cancelAccountSync = AtomicBoolean(false)
    private val cancelAccountImport = AtomicBoolean(false)
    private val syncPauseFlag = AtomicBoolean(false)
    @Volatile private var authenticatedSessionIdentity: DeviceIdentity? = null

    /**
     * Compose-visible copy of [activeOwner] and [authenticatedSessionIdentity]. Screens wait for it to
     * match the auth state, so a rebind never paints the previous session's content first.
     */
    var boundSession by mutableStateOf<BoundSession?>(null)
        private set
    @Volatile private var syncPausePending = false
    @Volatile private var syncPausePersistenceFailed = false
    @Volatile private var syncPauseControlGeneration = 0L
    internal val accountSyncPausePending: Boolean get() = syncPausePending
    internal val accountSyncPauseRecoveryAvailable: Boolean get() = syncPausePersistenceFailed
    internal val accountImportNetworkGate = Any()
    internal val accountFileIdentity: String get() {
        val owner = activeOwner ?: return "guest:$generation"
        return "$owner:$syncedAccountEpoch:${authenticatedSessionIdentity?.sessionId}:$generation"
    }

    private fun accountFileWorkCurrent(owner: UUID, expectedGeneration: Long, expectedEpoch: UUID, identity: DeviceIdentity): Boolean =
        ownsWork(owner, expectedGeneration) && !syncPauseFlag.get() && authenticatedSessionIdentity == identity &&
            runCatching { EncryptedDatasetStore.activeAccountEpoch(getApplication(), owner) == expectedEpoch }.getOrDefault(false)
    private val restoreBarrier = AtomicBoolean(false)
    private val accountClearPassword = AtomicReference("")
    @Volatile private var activeClearOpId: UUID? = null
    @Volatile private var activeClearFrozenWire: String? = null
    @Volatile private var clearRequestFenceOwner: Long? = null
    @Volatile private var lastSyncSuccessAt: Long? = null
    @Volatile private var syncEpochArchived = false
    @Volatile private var syncError: SyncError? = null
    @Volatile private var syncPausedRequestUncertain = false
    @Volatile private var restoreOperation: RestoreOperation? = null
    @Volatile private var restoreJobId: UUID? = null

    private fun syncPreferences(owner: UUID) = getApplication<Application>()
        .getSharedPreferences(SyncControlPolicy.preferenceName(owner.toString()), Application.MODE_PRIVATE)
    private fun guestLedgerPreferences(owner: String) = getApplication<Application>()
        .getSharedPreferences("guest-ledger-preferences-$owner", Application.MODE_PRIVATE)
    private fun loadGuestMultiLedgerEnabled(owner: String): Boolean = guestLedgerPreferences(owner).getBoolean("multiLedgerEnabled", true)
    private fun loadSyncPaused(owner: UUID) = syncPreferences(owner).getBoolean("paused", false)

    fun setGuestMultiLedgerEnabled(enabled: Boolean) {
        if (activeOwner != null || closed || state.busy || categorySaving) return
        val snapshot = state.snapshot ?: return
        val ownerId = snapshot.ownerId.toString()
        if (!enabled) {
            val defaultLedger = snapshot.ledgers.singleOrNull { it.isDefault && it.archivedAt == null }
            if (defaultLedger == null) { showError("找不到有效的默认账本，未关闭多账本"); return }
            if (defaultLedger.id != snapshot.ledgerId) {
                work(change = { it.selectLedger(defaultLedger.id) }, after = {
                    selectedGuestLedgerId = defaultLedger.id
                    getApplication<Application>().getSharedPreferences("guest-ledger-selection-$ownerId", Application.MODE_PRIVATE)
                        .edit().putString("ledgerId", defaultLedger.id.toString()).apply()
                    if (!guestLedgerPreferences(ownerId).edit().putBoolean("multiLedgerEnabled", false).commit()) {
                        showError("已切换到默认账本，但无法保存多账本设置"); return@work
                    }
                    state = state.copy(guestMultiLedgerEnabled = false)
                })
                return
            }
        }
        if (!guestLedgerPreferences(ownerId).edit().putBoolean("multiLedgerEnabled", enabled).commit()) {
            showError("无法保存多账本设置"); return
        }
        state = state.copy(guestMultiLedgerEnabled = enabled)
    }

    fun applyReminderOwner(ownerId: UUID, accountMode: Boolean, identity: DeviceIdentity? = null) {
        val app = getApplication<Application>()
        val owner = ownerId.toString()
        val cachedIdentity = identity?.takeIf { accountMode && it.owner == ownerId }
        if (accountMode) cachedReminderIdentity = cachedIdentity else cachedReminderIdentity = null
        val fence = synchronized(reminderFenceLock) {
            val next = ++reminderGeneration
            val registry = app.getSharedPreferences("local_reminder_owners", android.content.Context.MODE_PRIVATE)
            val previous = reminderOwner ?: registry.getString("active", null)
            reminderOwner = owner
            if (previous != null && previous != owner) ReminderScheduler.cancelOwner(app, previous)
            registry.edit().putString("active", owner).commit()
            ReminderFence(owner, accountMode, cachedIdentity, next)
        }
        synchronized(reminderFenceLock) {
            if (!ownsReminderFence(fence)) return
            reminderPreferences = ReminderPreferences.load(app, owner)
            statisticsPreferences = reminderPreferences
            statisticsRange = statisticsPeriod?.let(::periodRange)
            ReminderScheduler.rememberOwner(app, owner)
            ReminderScheduler.scheduleDaily(app, owner, reminderPreferences)
        }
        if (accountMode || reminderPreferences.creditEnabled) executor.execute { scheduleCreditReminders(fence) }
        else synchronized(reminderFenceLock) {
            if (ownsReminderFence(fence)) ReminderScheduler.cancelCredit(app, owner)
        }
    }

    private data class ReminderFence(val owner: String, val accountMode: Boolean, val identity: DeviceIdentity?, val generation: Long)

    private fun ownsReminderFence(fence: ReminderFence): Boolean =
        !closed && reminderGeneration == fence.generation && reminderOwner == fence.owner &&
            getApplication<Application>().getSharedPreferences("local_reminder_owners", android.content.Context.MODE_PRIVATE)
                .getString("active", null) == fence.owner &&
            if (fence.accountMode) {
                val cached = cachedReminderIdentity
                fence.identity != null && cached == fence.identity && cached.owner.toString() == fence.owner
            } else activeOwner == null

    fun updateReminderPreferences(value: ReminderPreferences) {
        val owner = state.snapshot?.ownerId?.toString() ?: activeOwner?.toString() ?: return
        ReminderPreferences.save(getApplication(), owner, value)
        reminderPreferences = value
        statisticsPreferences = value
        statisticsRange = statisticsPeriod?.let(::periodRange)
        if (statisticsPeriod != null) work(range = statisticsRange)
        val fence = synchronized(reminderFenceLock) {
            if (reminderOwner != owner) return
            ++reminderGeneration
            val identity = if (activeOwner != null) cachedReminderIdentity?.takeIf { it.owner.toString() == owner } else null
            ReminderFence(owner, activeOwner != null, identity, reminderGeneration)
        }
        ReminderScheduler.rememberOwner(getApplication(), owner)
        ReminderScheduler.scheduleDaily(getApplication(), owner, value)
        if (!value.creditEnabled) synchronized(reminderFenceLock) {
            if (ownsReminderFence(fence)) ReminderScheduler.cancelCredit(getApplication(), owner)
        } else executor.execute { scheduleCreditReminders(fence) }
    }

    private data class CreditReminderProfile(val id: String, val accountId: String, val enabled: Boolean, val statementDay: Int,
        val includeStatementDay: Boolean, val repaymentRule: String, val repaymentValue: Int, val timezone: String)

    private fun scheduleCreditReminders(fence: ReminderFence) {
        val app = getApplication<Application>()
        if (!ownsReminderFence(fence)) return
        if (fence.accountMode) {
            val currentIdentity = runCatching { DeviceSessions.identity(auth.snapshot()) }.getOrNull()
            if (currentIdentity == null || currentIdentity != fence.identity || currentIdentity.owner.toString() != fence.owner) return
        }
        val loaded = runCatching {
            if (fence.accountMode) {
                val owner = UUID.fromString(fence.owner)
                val epoch = EncryptedDatasetStore.activeAccountEpoch(app, owner) ?: throw StorageOpenException()
                requireAccountDatasetFiles(owner, epoch)
                val db = EncryptedDatasetStore.openAccount(app, owner, epoch)
                try {
                    check(ownsReminderFence(fence))
                    val identity = db.identity().read() ?: throw StorageOpenException()
                    check(identity.singleton == 1 && identity.schemaVersion == 1 && identity.ownerId == owner.toString() && identity.epochId == epoch.toString())
                    val tables = SnapshotImporter.canonicalTables(db)
                    val prefs = ReminderPreferences.load(app, fence.owner)
                    val rows = tables.getJSONArray("credit_profiles").objectsById().values.mapNotNull { row ->
                        if (!row.optBoolean("enabled", true) || row.opt("deletedAt")?.let { it != JSONObject.NULL } == true) return@mapNotNull null
                        CreditReminderProfile(row.getString("id"), row.getString("accountId"), true, row.optInt("statementDay"),
                            row.optBoolean("includeStatementDay"), row.optString("repaymentRule"), row.optInt("repaymentValue"), row.optString("timezone"))
                    }
                    prefs to rows
                } finally { db.close() }
            } else {
                val repo = repository ?: LocalFinanceRepository.openGuest(app).also { repository = it }
                val snapshot = repo.snapshot()
                check(snapshot.ownerId.toString() == fence.owner)
                val prefs = ReminderPreferences.load(app, fence.owner)
                val rows = snapshot.creditProfiles.map { p ->
                    CreditReminderProfile(p.id.toString(), p.accountId.toString(), p.enabled, p.statementDay, p.includeStatementDay,
                        p.repaymentRule, p.repaymentValue, p.timezone)
                }.filter { it.enabled }
                prefs to rows
            }
        }
        val result = loaded.getOrElse { return }
        if (!ownsReminderFence(fence)) return
        if (fence.accountMode) {
            val currentIdentity = runCatching { DeviceSessions.identity(auth.snapshot()) }.getOrNull()
            if (currentIdentity == null || currentIdentity != fence.identity || currentIdentity.owner.toString() != fence.owner) return
        }
        main.post {
            synchronized(reminderFenceLock) {
                if (!ownsReminderFence(fence)) return@synchronized
                reminderPreferences = result.first
                ReminderScheduler.scheduleDaily(app, fence.owner, result.first)
                scheduleCreditReminderRows(fence.owner, result.second, result.first)
            }
        }
    }

    private fun scheduleCreditReminderRows(owner: String, rows: List<CreditReminderProfile>, preferences: ReminderPreferences) {
        synchronized(reminderFenceLock) {
            if (reminderOwner != owner || getApplication<Application>()
                    .getSharedPreferences("local_reminder_owners", android.content.Context.MODE_PRIVATE).getString("active", null) != owner) return
            ReminderScheduler.cancelCredit(getApplication(), owner)
            if (!preferences.creditEnabled) return
            rows.forEach { profile ->
                val zone = runCatching { ZoneId.of(profile.timezone) }.getOrNull() ?: return@forEach
                val today = java.time.Instant.now().atZone(zone).toLocalDate()
                val dates = CreditReminderPolicy.dates(today, profile.statementDay, profile.includeStatementDay, profile.repaymentRule, profile.repaymentValue) ?: return@forEach
                fun at(date: java.time.LocalDate) = date.atTime(preferences.hour, preferences.minute).atZone(zone).toInstant().toEpochMilli()
                val statementAt = at(dates.statementDate)
                if (!dates.statementDate.isBefore(today) && statementAt > System.currentTimeMillis()) ReminderScheduler.scheduleCredit(getApplication(), owner, "credit-statement:${profile.id}", statementAt)
                val lead = dates.dueDate.minusDays(preferences.creditLeadDays.toLong()); val leadAt = at(lead)
                if (!lead.isBefore(today) && leadAt > System.currentTimeMillis()) ReminderScheduler.scheduleCredit(getApplication(), owner, "credit-lead:${profile.id}", leadAt)
                val dueAt = at(dates.dueDate)
                if (!dates.dueDate.isBefore(today) && dueAt > System.currentTimeMillis()) ReminderScheduler.scheduleCredit(getApplication(), owner, "credit-due:${profile.id}", dueAt)
            }
        }
    }

    private fun clearFence(owner: UUID, expectedGeneration: Long, epoch: UUID, identity: DeviceIdentity): () -> Unit = {
        val current = auth.snapshot()
        DeviceSessions.requireSameIdentity(identity, current)
        if (closed || activeOwner != owner || generation != expectedGeneration || syncPauseFlag.get() ||
            EncryptedDatasetStore.activeAccountEpoch(getApplication(), owner) != epoch) throw AuthFailure("SNAPSHOT_CANCELLED")
        val protected = current.datasetClear ?: throw AuthFailure("CLEAR_REAUTH_UNCONFIRMED")
        val expectedOp = activeClearOpId
        if (protected.ownerId != owner || protected.epochId != epoch || protected.sessionId != identity.sessionId ||
            expectedOp == null || protected.opId != expectedOp || activeClearFrozenWire != null && protected.frozenGroupJson != activeClearFrozenWire) {
            throw AuthFailure("STALE_CREDENTIALS")
        }
    }

    fun previewAccountClearRecords() {
        val owner = activeOwner ?: return
        if (savingRecordCount > 0) { showError("账单正在写入本机，完成后再预览清账。"); return }
        if (closed || state.busy || state.restoreBusy || restoreBarrier.get() || syncPauseFlag.get() || state.accountClearPending) return
        val expectedGeneration = generation
        state = state.copy(busy = true, error = null, accountClearPreview = null)
        executor.execute {
            val result = runCatching {
                val protected = auth.pendingDatasetClear()
                if (protected != null) {
                    main.post { if (ownsWork(owner, expectedGeneration)) state = state.copy(busy = false, accountClearPending = true,
                        accountClearReauthRequired = protected.phase in setOf("REAUTH", "AUTHORIZED"), error = "存在尚未完成的清账屏障；请继续原清账操作。") }
                    return@execute
                }
                check(ownsWork(owner, expectedGeneration))
                val epoch = EncryptedDatasetStore.activeAccountEpoch(getApplication(), owner) ?: throw StorageOpenException()
                val identity = DeviceSessions.identity(auth.snapshot())
                check(identity.owner == owner)
                val fence: () -> Unit = {
                    check(ownsWork(owner, expectedGeneration) && !syncPauseFlag.get() &&
                        EncryptedDatasetStore.activeAccountEpoch(getApplication(), owner) == epoch)
                    DeviceSessions.requireSameIdentity(identity, auth.snapshot())
                }
                val head = auth.currentDatasetHead(owner, epoch, identity, accountImportNetworkGate, fence)
                val database = EncryptedDatasetStore.openAccount(getApplication(), owner, epoch)
                try {
                    val local = database.identity().read() ?: throw StorageOpenException()
                    check(local.ownerId == owner.toString() && local.epochId == epoch.toString() && local.lastAppliedSeq == head) { "请先完成账号同步再预览清账范围" }
                    val tables = SnapshotImporter.canonicalTables(database)
                    val ledgers = (0 until tables.getJSONArray("ledgers").length()).map { tables.getJSONArray("ledgers").getJSONObject(it) }
                        .filter { it.isNull("deletedAt") && (!it.has("archivedAt") || it.isNull("archivedAt")) }
                    check(ledgers.size in 1..100) { "服务端清账支持1至100个账本" }
                    val ids = ledgers.mapTo(HashSet()) { it.getString("id") }
                    val records = tables.getJSONArray("records")
                    val count = (0 until records.length()).count { i -> records.getJSONObject(i).isNull("deletedAt") && records.getJSONObject(i).optString("ledgerId") in ids }
                    "将清除服务器上的 ${ledgers.size} 个活动账本、${count} 条活动记录。任何不支持的关联由服务器整组拒绝；成功后需要完成全量快照屏障。当前水位：$head。"
                } finally { database.close() }
            }
            main.post { if (ownsWork(owner, expectedGeneration)) state = state.copy(busy = false, accountClearPreview = result.getOrNull(), error = result.exceptionOrNull()?.let { message(it as? Exception ?: IllegalStateException(it)) }) }
        }
    }

    fun setAccountClearPassword(password: String) { accountClearPassword.set(password.take(128)) }

    fun confirmAccountClearRecords() {
        val owner = activeOwner ?: return
        if (savingRecordCount > 0) { showError("账单正在写入本机，完成后再清账。"); return }
        if (closed || state.busy || state.restoreBusy || restoreBarrier.get() || syncPauseFlag.get() || state.accountClearPreview == null) return
        val password = accountClearPassword.getAndSet("")
        if (password.codePointCount(0, password.length) !in 12..128) { state = state.copy(error = "请输入有效账号密码"); return }
        val expectedGeneration = generation
        state = state.copy(busy = true, accountClearPending = true, error = null)
        executor.execute {
            var database: DatasetDatabase? = null
            try {
                check(ownsWork(owner, expectedGeneration))
                val epoch = EncryptedDatasetStore.activeAccountEpoch(getApplication(), owner) ?: throw StorageOpenException()
                val snapshot = auth.snapshot()
                val session = snapshot.session ?: throw AuthFailure("SESSION_EXPIRED")
                val identity = DeviceSessions.identity(snapshot)
                if (identity.owner != owner) throw AuthFailure("DATASET_IDENTITY_MISMATCH")
                val fence: () -> Unit = {
                    check(ownsWork(owner, expectedGeneration) && !syncPauseFlag.get() &&
                        EncryptedDatasetStore.activeAccountEpoch(getApplication(), owner) == epoch)
                    DeviceSessions.requireSameIdentity(identity, auth.snapshot())
                    auth.snapshot().datasetClear?.let { operation ->
                        if (operation.opId != activeClearOpId && activeClearOpId != null) throw AuthFailure("STALE_CREDENTIALS")
                    }
                }
                val head = auth.currentDatasetHead(owner, epoch, identity, accountImportNetworkGate, fence)
                val protectedSnapshot = auth.snapshot()
                DeviceSessions.requireSameIdentity(identity, protectedSnapshot)
                database = EncryptedDatasetStore.openAccount(getApplication(), owner, epoch)
                val stored = database.identity().read() ?: throw StorageOpenException()
                check(stored.ownerId == owner.toString() && stored.epochId == epoch.toString() && stored.lastAppliedSeq == head) { "清账前需先完成最新服务器同步" }
                activeClearOpId = UUID.randomUUID()
                activeClearFrozenWire = null
                val operation = AccountMutationRepository(database).stageDatasetClearRecords(
                    protectedSnapshot.deviceId, session.sessionId, activeClearOpId!!, UUID.randomUUID(), head, password,
                    persistProtected = { auth.beginDatasetClear(protectedSnapshot, it) }, discardProtected = { auth.discardDatasetClear(it) },
                )
                val pinnedOperation = operation
                val operationFence = clearFence(owner, expectedGeneration, epoch, identity)
                auth.installSyncRequestFence(accountImportNetworkGate, operationFence).also { clearRequestFenceOwner = it }
                val ready = auth.reauthenticateDatasetClear(pinnedOperation, identity, accountImportNetworkGate, operationFence)
                activeClearFrozenWire = ready.frozenGroupJson
                val result = try { auth.pushDatasetClear(ready, identity, accountImportNetworkGate, operationFence) } catch (failure: AuthFailure) {
                    auth.markDatasetClearAmbiguous(auth.snapshot(), ready)
                    AccountMutationRepository(database).markDatasetClearAmbiguous(ready)
                    throw failure
                }
                val acked = auth.markDatasetClearAck(auth.snapshot(), ready, result.getLong("seq"), result.getLong("requiredCapturedSeq"))
                AccountMutationRepository(database).markDatasetClearAck(acked, result)
                database.close(); database = null
                performDatasetClearBarrier(owner, expectedGeneration, identity)
                refreshAccountState(owner, expectedGeneration) {
                    state = state.copy(busy = false, accountClearPending = false, accountClearPreview = null, accountClearReauthRequired = false, error = null)
                }
            } catch (failure: Exception) {
                database?.close()
                val pending = runCatching { auth.pendingDatasetClear() }.getOrNull()
                main.post { if (ownsWork(owner, expectedGeneration)) state = state.copy(busy = false, accountClearPending = pending != null,
                    accountClearReauthRequired = pending?.phase in setOf("REAUTH", "AUTHORIZED"),
                    error = if (pending?.phase == "ACKED") "清账已获确认；完成覆盖确认水位的完整快照前不会刷新账本。" else message(failure)) }
            } finally {
                password.toCharArray().fill('\u0000')
                clearRequestFenceOwner?.let(auth::clearSyncRequestFence)
                clearRequestFenceOwner = null
            }
        }
    }

    fun resumeAccountClearReauth() {
        val owner = activeOwner ?: return
        val password = accountClearPassword.getAndSet("")
        if (password.codePointCount(0, password.length) !in 12..128) { state = state.copy(error = "请输入有效账号密码"); return }
        val expectedGeneration = generation
        if (state.busy || syncPauseFlag.get()) return
        state = state.copy(busy = true, error = null)
        executor.execute {
            val pending = runCatching { auth.pendingDatasetClear() }.getOrElse { failure ->
                main.post { if (ownsWork(owner, expectedGeneration)) state = state.copy(busy = false, error = message(failure as? Exception ?: IllegalStateException(failure))) }
                return@execute
            }
            if (pending == null || pending.ownerId != owner || pending.phase !in setOf("REAUTH", "AUTHORIZED") || !ownsWork(owner, expectedGeneration)) {
                main.post { if (ownsWork(owner, expectedGeneration)) state = state.copy(busy = false, accountClearPending = pending != null,
                    accountClearReauthRequired = pending?.phase in setOf("REAUTH", "AUTHORIZED")) }
                return@execute
            }
            try {
                val snapshot = auth.snapshot()
                val identity = DeviceSessions.identity(snapshot)
                val epoch = EncryptedDatasetStore.activeAccountEpoch(getApplication(), owner) ?: throw StorageOpenException()
                if (epoch != pending.epochId || identity.sessionId != pending.sessionId) throw AuthFailure("DATASET_EPOCH_CHANGED")
                activeClearOpId = pending.opId
                activeClearFrozenWire = pending.frozenGroupJson
                val fence = clearFence(owner, expectedGeneration, epoch, identity)
                auth.installSyncRequestFence(accountImportNetworkGate, fence).also { clearRequestFenceOwner = it }
                val resumed = if (pending.phase == "REAUTH") auth.resumeDatasetClearReauth(snapshot, password) else pending
                val ready = auth.reauthenticateDatasetClear(resumed, identity, accountImportNetworkGate, fence)
                activeClearFrozenWire = ready.frozenGroupJson
                val database = EncryptedDatasetStore.openAccount(getApplication(), owner, epoch)
                try {
                    val repository = AccountMutationRepository(database)
                    repository.restoreDatasetClearIntent(ready, snapshot.deviceId)
                    val result = auth.pushDatasetClear(ready, identity, accountImportNetworkGate, fence)
                    val acked = auth.markDatasetClearAck(auth.snapshot(), ready, result.getLong("seq"), result.getLong("requiredCapturedSeq"))
                    repository.markDatasetClearAck(acked, result)
                } finally { database.close() }
                performDatasetClearBarrier(owner, expectedGeneration, identity)
                refreshAccountState(owner, expectedGeneration) { state = state.copy(busy = false, accountClearPending = false, accountClearReauthRequired = false, accountClearPreview = null, error = null) }
            } catch (failure: Exception) {
                main.post { if (ownsWork(owner, expectedGeneration)) state = state.copy(busy = false, error = message(failure)) }
            } finally {
                password.toCharArray().fill('\u0000')
                clearRequestFenceOwner?.let(auth::clearSyncRequestFence)
                clearRequestFenceOwner = null
            }
        }
    }

    fun retryAccountClearRecords() {
        val owner = activeOwner ?: return
        if (closed || state.busy || state.restoreBusy || restoreBarrier.get() || syncPauseFlag.get()) return
        val expectedGeneration = generation
        state = state.copy(busy = true, accountClearPending = true, error = null)
        executor.execute {
            val pending = runCatching { auth.pendingDatasetClear() }.getOrElse { failure ->
                main.post { if (ownsWork(owner, expectedGeneration)) state = state.copy(busy = false, accountClearPending = true, error = message(failure as? Exception ?: IllegalStateException(failure))) }
                return@execute
            }
            if (pending == null || pending.ownerId != owner) {
                main.post { if (ownsWork(owner, expectedGeneration)) state = state.copy(busy = false, accountClearPending = pending != null) }
                return@execute
            }
            var database: DatasetDatabase? = null
            try {
                val snapshot = auth.snapshot()
                val identity = DeviceSessions.identity(snapshot)
                val epoch = EncryptedDatasetStore.activeAccountEpoch(getApplication(), owner) ?: throw StorageOpenException()
                if (pending.epochId != epoch || pending.sessionId != identity.sessionId) throw AuthFailure("DATASET_EPOCH_CHANGED")
                activeClearOpId = pending.opId
                activeClearFrozenWire = pending.frozenGroupJson
                val operationFence = clearFence(owner, expectedGeneration, epoch, identity)
                auth.installSyncRequestFence(accountImportNetworkGate, operationFence).also { clearRequestFenceOwner = it }
                database = EncryptedDatasetStore.openAccount(getApplication(), owner, epoch)
                val repository = AccountMutationRepository(database)
                repository.restoreDatasetClearIntent(pending, snapshot.deviceId)
                if (pending.phase in setOf("REAUTH", "AUTHORIZED")) throw AuthFailure("CLEAR_REAUTH_UNCONFIRMED")
                var acked = pending
                if (pending.phase == "READY") {
                    val result = try { auth.pushDatasetClear(pending, identity, accountImportNetworkGate, operationFence) } catch (failure: AuthFailure) {
                        auth.markDatasetClearAmbiguous(auth.snapshot(), pending)
                        repository.markDatasetClearAmbiguous(pending)
                        throw failure
                    }
                    acked = auth.markDatasetClearAck(auth.snapshot(), pending, result.getLong("seq"), result.getLong("requiredCapturedSeq"))
                    repository.markDatasetClearAck(acked, result)
                }
                database.close(); database = null
                performDatasetClearBarrier(owner, expectedGeneration, identity)
                refreshAccountState(owner, expectedGeneration) { state = state.copy(busy = false, accountClearPending = false, accountClearReauthRequired = false, error = null) }
            } catch (failure: Exception) {
                database?.close()
                val latest = runCatching { auth.pendingDatasetClear() }.getOrNull()
                main.post { if (ownsWork(owner, expectedGeneration)) state = state.copy(busy = false, accountClearPending = latest != null,
                    accountClearReauthRequired = latest?.phase in setOf("REAUTH", "AUTHORIZED"),
                    error = if (latest?.phase == "ACKED") "等待完整快照覆盖清账回执水位。" else message(failure)) }
            } finally {
                clearRequestFenceOwner?.let(auth::clearSyncRequestFence)
                clearRequestFenceOwner = null
            }
        }
    }

    private fun performDatasetClearBarrier(owner: UUID, expectedGeneration: Long, identity: DeviceIdentity) {
        val operation = auth.pendingDatasetClear() ?: throw AuthFailure("CLEAR_REAUTH_UNCONFIRMED")
        if (operation.phase != "ACKED" || operation.ownerId != owner || !ownsWork(owner, expectedGeneration) || syncPauseFlag.get()) throw AuthFailure("SNAPSHOT_CANCELLED")
        activeClearOpId = operation.opId
        activeClearFrozenWire = operation.frozenGroupJson
        val fence = clearFence(owner, expectedGeneration, operation.epochId, identity)
        val syncResult = AccountSyncCoordinator.syncAccount(getApplication(), auth, owner, onProgress = {}, cancelled = {
            syncPauseFlag.get() || !ownsWork(owner, expectedGeneration)
        }, requestFence = fence, requestGate = accountImportNetworkGate, expectedIdentity = identity,
            allowDatasetClear = true, forceSnapshot = true)
        if (syncResult.snapshotCapturedSeq == null || syncResult.snapshotCapturedSeq < (operation.requiredCapturedSeq ?: throw AuthFailure("INVALID_RESPONSE"))) {
            throw AuthFailure("SNAPSHOT_REQUIRED", retryable = true)
        }
        val epoch = EncryptedDatasetStore.activeAccountEpoch(getApplication(), owner) ?: throw StorageOpenException()
        if (epoch != operation.epochId) throw AuthFailure("DATASET_EPOCH_CHANGED")
        val database = EncryptedDatasetStore.openAccount(getApplication(), owner, epoch)
        try {
            val stored = database.identity().read() ?: throw StorageOpenException()
            if (stored.ownerId != owner.toString() || stored.epochId != epoch.toString() || stored.snapshotSchemaVersion < 5 ||
                stored.lastAppliedSeq < (operation.requiredCapturedSeq ?: throw AuthFailure("INVALID_RESPONSE"))) throw AuthFailure("SNAPSHOT_REQUIRED", retryable = true)
            val tables = SnapshotImporter.canonicalTables(database)
            val records = tables.getJSONArray("records")
            val ledgers = org.json.JSONArray(operation.ledgerIdsJson)
            for (index in 0 until ledgers.length()) {
                val ledgerId = ledgers.getString(index)
                if ((0 until records.length()).any { i -> records.getJSONObject(i).optString("ledgerId") == ledgerId && records.getJSONObject(i).isNull("deletedAt") }) {
                    throw AuthFailure("SNAPSHOT_REQUIRED", retryable = true)
                }
            }
            CanonicalAccountRepository.read(database)
        } finally { database.close() }
        auth.finishDatasetClear(auth.snapshot(), operation.opId)
        activeClearOpId = null
        activeClearFrozenWire = null
    }

    fun cancelAccountClearPreview() {
        if (!state.busy && !state.accountClearPending) state = state.copy(accountClearPreview = null)
    }

    private fun loadLastSuccess(owner: UUID): Long? = syncPreferences(owner).getLong("lastSuccessAt", 0L).takeIf { it > 0L }

    fun loadBackups() {
        val owner = activeOwner ?: return
        if (closed || state.backupsBusy || state.restoreBusy || restoreBarrier.get()) return
        val expectedGeneration = generation
        state = state.copy(backupsBusy = true, restoreError = null)
        executor.execute {
            val result = runCatching {
                check(ownsWork(owner, expectedGeneration)) { "账号已切换" }
                auth.listRestoreBackups().filter { it.kind != null }
            }
            main.post {
                if (ownsWork(owner, expectedGeneration)) {
                    state = state.copy(backupsBusy = false, backups = result.getOrDefault(emptyList()),
                        restoreError = result.exceptionOrNull()?.let(::restoreMessage))
                }
            }
        }
    }

    fun selectRestoreBackup(backup: RemoteBackup?) {
        if (state.restoreBusy || restoreBarrier.get()) return
        if (backup == null) {
            state = state.copy(selectedRestoreBackup = null, restoreError = null)
            return
        }
        val currentOperation = restoreOperation
        val currentJob = restoreJobId
        if (currentOperation != null && currentJob == null && currentOperation.backupId != backup.id) {
            state = state.copy(restoreError = "恢复提交结果未确认；请先用原操作编号重试同一请求。")
            return
        }
        if (currentOperation != null && currentOperation.backupId != backup.id && currentJob == null) {
            restoreOperation = null
        }
        if (currentJob != null && state.restoreStatus?.state !in setOf("FAILED", "CANCELLED", "COMMITTED")) {
            state = state.copy(restoreError = "账号恢复任务仍在运行；请先查看当前任务状态。")
            return
        }
        if (currentJob != null && state.restoreStatus?.state in setOf("FAILED", "CANCELLED")) {
            restoreOperation = null
            restoreJobId = null
            state = state.copy(restoreStatus = null)
        }
        state = state.copy(selectedRestoreBackup = backup, restoreError = null)
    }

    fun restoreSelectedBackup(password: String) {
        val owner = activeOwner ?: return
        if (savingRecordCount > 0) { state = state.copy(restoreError = "账单正在写入本机，完成后再恢复备份。"); return }
        if (state.accountClearPending) {
            state = state.copy(restoreError = "清账状态尚未完成；请先继续同一清账操作。")
            return
        }
        val backup = state.selectedRestoreBackup ?: return
        if (restoreBarrier.get() || state.busy || state.restoreBusy || state.restoreSubmissionRetryAvailable || password.isBlank()) return
        if (syncPauseFlag.get()) {
            state = state.copy(restoreError = "请先恢复账号网络同步，再开始云端恢复。")
            return
        }
        val priorEpoch = EncryptedDatasetStore.activeAccountEpoch(getApplication(), owner)
        if (priorEpoch == null || backup.state != "READY" || backup.schemaVersion == null ||
            runCatching { dev.icos.app.data.remote.SnapshotProtocol.tablesFor(backup.schemaVersion) }.isFailure) {
            state = state.copy(restoreError = if (backup.schemaVersion == dev.icos.app.data.remote.SnapshotProtocol.DATA5_SCHEMA)
                "此备份使用 DATA5 快照协议；服务器会要求完整 import proof 与业务域验证，缺失 proof 或不支持的业务域会拒绝恢复。"
                else "所选备份尚未就绪，或此版本不支持其快照域；服务端将保留完整验证与校验。")
            return
        }
        val expectedGeneration = ++generation
        cancelAccountSync.set(true)
        state = state.copy(restoreBusy = true, restoreProgress = "正在读取当前服务器 epoch 与 head", restoreError = null)
        val passwordCopy = password.toCharArray()
        executor.execute {
            val result = runCatching {
                check(ownsWork(owner, expectedGeneration)) { "账号已切换" }
                val priorEpoch = EncryptedDatasetStore.activeAccountEpoch(getApplication(), owner)
                    ?: throw StorageOpenException()
                val priorDatabase = EncryptedDatasetStore.openAccount(getApplication(), owner, priorEpoch)
                try {
                    val identity = priorDatabase.identity().read() ?: throw StorageOpenException()
                    check(identity.ownerId == owner.toString() && identity.epochId == priorEpoch.toString()) { "账号数据集身份不匹配" }
                } finally {
                    priorDatabase.close()
                }
                val (epoch, head) = auth.bootstrapIdentity(owner)
                check(ownsWork(owner, expectedGeneration)) { "账号已切换" }
                if (epoch != priorEpoch) throw AuthFailure("DATASET_EPOCH_CHANGED")
                val reauthId = UUID.randomUUID()
                val token = try {
                    auth.reauthenticateRestore(String(passwordCopy), backup.id, epoch, head, reauthId)
                } finally {
                    passwordCopy.fill('\u0000')
                }
                check(ownsWork(owner, expectedGeneration)) { "账号已切换" }
                val opId = UUID.randomUUID()
                val request = JSONObject().put("backupId", backup.id.toString())
                    .put("expectedEpoch", epoch.toString()).put("expectedHeadSeq", head).put("actionToken", token)
                val operation = RestoreOperation(DeviceSessions.identity(auth.snapshot()), backup.id, epoch, head, opId, token, request.toString())
                restoreOperation = operation
                auth.beginRestore(operation)
                restoreBarrier.set(true)
                main.post { if (ownsWork(owner, expectedGeneration)) state = state.copy(restoreProgress = "正在提交恢复请求") }
                val accepted = auth.createRestore(operation.requestBody, operation.opId)
                operation.retainedFor(auth.snapshot())
                if (accepted.opId != opId || accepted.state !in setOf("QUEUED", "PROTECTING", "STAGING", "VALIDATING", "COMMITTING", "COMMITTED"))
                    throw AuthFailure("INVALID_RESPONSE", retryable = true)
                restoreJobId = accepted.jobId
                pollRestore(owner, expectedGeneration, priorEpoch, operation, accepted.jobId)
            }
            passwordCopy.fill('\u0000')
            main.post {
                if (ownsWork(owner, expectedGeneration)) {
                    state = state.copy(restoreBusy = false, restoreProgress = null,
                        restoreError = result.exceptionOrNull()?.let(::restoreMessage),
                        restoreSubmissionRetryAvailable = result.isFailure && restoreJobId == null && restoreOperation != null &&
                            (result.exceptionOrNull() as? AuthFailure)?.retryable == true,
                    restoreJobId = restoreJobId,
                    restoreStatus = result.getOrNull() ?: state.restoreStatus)
                    if (result.getOrNull()?.state == "COMMITTED") {
                        restoreOperation = null
                        restoreJobId = null
                        restoreBarrier.set(false)
                        syncEpochArchived = true
                        syncedAccountEpoch = result.getOrNull()?.committedEpoch
                        state = state.copy(restoreStatus = result.getOrNull(), syncEpochArchived = true)
                        work(sync = false)
                    }
                }
            }
        }
    }

    fun retryRestoreSubmission() {
        val owner = activeOwner ?: return
        val operation = restoreOperation ?: return
        if (state.restoreBusy || state.busy) return
        val expectedGeneration = generation
        val existingJobId = restoreJobId
        if (!ownsWork(owner, expectedGeneration) || operation.identity.owner != owner) return
        state = state.copy(restoreBusy = true, restoreError = null, restoreProgress = if (existingJobId == null) "使用原操作编号核对恢复提交" else "继续查询已提交的恢复任务")
        executor.execute {
            val result = runCatching {
                check(ownsWork(owner, expectedGeneration)) { "账号已切换" }
                operation.retainedFor(auth.snapshot())
                val pending = auth.pendingRestore(owner) ?: throw AuthFailure("RESTORE_UNCONFIRMED")
                if (pending.opId != operation.opId || pending.sessionId != operation.identity.sessionId) {
                    throw AuthFailure("STALE_CREDENTIALS")
                }
                check(ownsWork(owner, expectedGeneration)) { "账号已切换" }
                val jobId = existingJobId ?: auth.createRestore(operation.requestBody, operation.opId).jobId.also { acceptedJobId ->
                    operation.retainedFor(auth.snapshot())
                    restoreJobId = acceptedJobId
                    auth.recordRestoreJob(operation, acceptedJobId)
                }
                pollRestore(owner, expectedGeneration, operation.epoch, operation, jobId)
            }
            main.post {
                if (ownsWork(owner, expectedGeneration)) state = state.copy(restoreBusy = false,
                    restoreProgress = null, restoreError = result.exceptionOrNull()?.let(::restoreMessage),
                    restoreSubmissionRetryAvailable = result.isFailure && restoreJobId == null && restoreOperation != null &&
                        (result.exceptionOrNull() as? AuthFailure)?.retryable == true,
                    restoreJobId = restoreJobId,
                    restoreStatus = result.getOrNull() ?: state.restoreStatus)
                if (ownsWork(owner, expectedGeneration) && result.getOrNull()?.state == "COMMITTED") {
                    restoreOperation = null
                    restoreJobId = null
                    restoreBarrier.set(false)
                    syncEpochArchived = true
                    syncedAccountEpoch = result.getOrNull()?.committedEpoch
                    state = state.copy(syncEpochArchived = true, restoreStatus = result.getOrNull())
                    work(sync = false)
                }
            }
        }
    }

    private fun pollRestore(owner: UUID, expectedGeneration: Long, oldEpoch: UUID, operation: RestoreOperation, jobId: UUID): RestoreStatus {
        repeat(240) {
            check(ownsWork(owner, expectedGeneration)) { "账号已切换" }
            operation.retainedFor(auth.snapshot())
            val status = auth.restoreStatus(jobId)
            if (status.opId != operation.opId || status.backupId != operation.backupId ||
                status.expectedEpoch != operation.epoch || status.expectedHeadSeq != operation.headSeq) throw AuthFailure("INVALID_RESPONSE")
            main.post { if (ownsWork(owner, expectedGeneration)) state = state.copy(restoreStatus = status,
                restoreProgress = "恢复 ${status.progress}% · ${status.state}") }
            when (status.state) {
                "COMMITTED" -> {
                    val newEpoch = status.committedEpoch ?: throw AuthFailure("INVALID_RESPONSE")
                    if (newEpoch == oldEpoch) throw AuthFailure("INVALID_RESPONSE")
                    check(ownsWork(owner, expectedGeneration)) { "账号已切换" }
                    cancelAccountSync.set(true)
                    auth.markRestoreCommitted(operation, status)
                    AccountSyncCoordinator.synchronizeCommittedRestore(
                        getApplication(), auth, owner, oldEpoch, newEpoch,
                        onProgress = { text -> main.post { if (ownsWork(owner, expectedGeneration)) state = state.copy(restoreProgress = text) } },
                    )
                    auth.finishRestore(operation, status)
                    restoreBarrier.set(false)
                    restoreOperation = null
                    restoreJobId = null
                    syncEpochArchived = true
                    syncedAccountEpoch = newEpoch
                    return status
                }
                "FAILED", "CANCELLED" -> {
                    auth.finishRestore(operation, status)
                    restoreBarrier.set(false)
                    restoreOperation = null
                    restoreJobId = null
                    throw AuthFailure(status.errorCode ?: "RESTORE_FAILED")
                }
            }
            Thread.sleep(1500)
        }
        throw AuthFailure("NETWORK", retryable = true)
    }

    private fun restoreMessage(failure: Throwable): String = when ((failure as? AuthFailure)?.code) {
        "UNSUPPORTED_SNAPSHOT_SCHEMA", "SNAPSHOT_SCHEMA_UNSUPPORTED" -> "服务器不支持此快照协议。"
        "RESTORE_STALE", "DATASET_EPOCH_CHANGED" -> "服务器数据已变化，请重新载入备份并重新确认恢复。"
        "RESTORE_IN_PROGRESS" -> "账号已有恢复任务，请查看其进度后再操作。"
        "INVALID_ACTION_TOKEN" -> "重新验证已过期，请重新确认恢复操作。"
        "INVALID_CREDENTIALS" -> "密码不正确，恢复尚未提交。"
        "NETWORK" -> if (restoreJobId != null) "恢复已提交，状态查询中断；可继续查询同一任务。" else "恢复请求结果未确认；可使用原操作编号重试，不要重新发起。"
        "SNAPSHOT_INVALID", "STAGING_COUNT_MISMATCH", "STAGING_VALIDATION_FAILED" -> "服务器完整快照校验未通过；当前数据集没有切换。"
        "VALIDATION_UNSUPPORTED_CLAIM", "VALIDATION_UNSUPPORTED_REFUND", "VALIDATION_UNSUPPORTED_LOAN",
        "VALIDATION_UNSUPPORTED_CREDIT_INSTALLMENT", "VALIDATION_UNSUPPORTED_EVENT_KIND", "VALIDATION_UNSUPPORTED_FX",
        "VALIDATION_UNSUPPORTED_FEE_DISCOUNT", "VALIDATION_UNSUPPORTED_SAVING", "VALIDATION_UNSUPPORTED_FINANCIAL_MODE",
        "VALIDATION_UNSUPPORTED_REVERSAL_HISTORY", "ATTACHMENTS_UNSUPPORTED", "UNSUPPORTED_SCHEMA" -> "此备份包含当前版本不支持的业务域（${(failure as AuthFailure).code}）；服务器拒绝了恢复，原数据集保持不变。"
        else -> (failure as? AuthFailure)?.userMessage ?: "恢复未完成：${failure.message ?: "未知错误"}"
    }

    fun pauseAccountSync() {
        val owner = activeOwner ?: return
        if (closed || syncPausePending || syncPauseFlag.get() && !syncPausePersistenceFailed) return
        val pauseGeneration = generation
        val pauseIdentity = authenticatedSessionIdentity
        val controlGeneration = syncPauseControlGeneration
        syncPausePending = true
        syncPauseFlag.set(true)
        cancelAccountSync.set(true)
        cancelAccountImport.set(true)
        val uncertain = state.bootstrapProgress != null || state.busy
        syncPausedRequestUncertain = uncertain
        syncError = if (uncertain) SyncError(SyncErrorCode.PAUSED, "可能有请求已在服务器处理；继续后会使用原操作标识核对") else null
        state = state.copy(syncPaused = false, syncPausedRequestUncertain = uncertain,
            bootstrapProgress = if (state.bootstrapProgress != null) "正在停止本地网络同步；已发出的请求结果可能待确认" else state.bootstrapProgress,
            syncError = syncError, error = "正在停止账号网络同步…")
        auth.pauseSyncRequests(accountImportNetworkGate, controlGeneration, onPause = {
            check(activeOwner == owner && generation == pauseGeneration && authenticatedSessionIdentity == pauseIdentity && syncPauseControlGeneration == controlGeneration) { "STALE_CREDENTIALS" }
            check(syncPreferences(owner).edit().putBoolean("paused", true).commit()) { "Could not persist sync pause" }
        }, onComplete = { outcome ->
            main.post {
                if (generation == pauseGeneration && activeOwner == owner && authenticatedSessionIdentity == pauseIdentity && syncPauseControlGeneration == controlGeneration) {
                    syncPausePending = false
                    syncPausePersistenceFailed = outcome.isFailure
                    if (outcome.isSuccess) syncPauseFlag.set(true)
                    if (!closed) {
                        if (outcome.isFailure) {
                            syncPauseFlag.set(true)
                            syncError = SyncError(SyncErrorCode.STORAGE, "无法保存暂停状态，请重试暂停或恢复")
                            state = state.copy(syncPaused = false, error = syncError?.message, syncError = syncError)
                        } else {
                            syncError = if (uncertain) SyncError(SyncErrorCode.PAUSED, "可能有请求已在服务器处理；继续后会使用原操作标识核对") else null
                            state = state.copy(syncPaused = true, syncPausedRequestUncertain = uncertain,
                                error = null, syncError = syncError)
                        }
                    }
                }
            }
        })
    }

    fun resumeAccountSync() {
        val owner = activeOwner ?: return
        if (closed || syncPausePending) return
        val operationGeneration = generation
        val operationIdentity = authenticatedSessionIdentity
        val controlGeneration = syncPauseControlGeneration
        syncPausePending = true
        state = state.copy(error = "正在恢复账号网络同步…")
        auth.resumeSyncRequests(accountImportNetworkGate, controlGeneration, operationIdentity, onResume = {
            check(activeOwner == owner && generation == operationGeneration && authenticatedSessionIdentity == operationIdentity && syncPauseControlGeneration == controlGeneration && auth.syncControlIdentityGeneration() == controlGeneration) { "STALE_CREDENTIALS" }
            check(syncPreferences(owner).edit().putBoolean("paused", false).commit()) { "Could not persist sync resume" }
            check(activeOwner == owner && generation == operationGeneration && authenticatedSessionIdentity == operationIdentity && syncPauseControlGeneration == controlGeneration && auth.syncControlIdentityGeneration() == controlGeneration) { "STALE_CREDENTIALS" }
        }, onComplete = { outcome ->
            main.post {
                if (generation == operationGeneration && activeOwner == owner && authenticatedSessionIdentity == operationIdentity && syncPauseControlGeneration == controlGeneration) {
                    syncPausePending = false
                    if (outcome.isSuccess) {
                        syncPausePersistenceFailed = false
                        syncPauseFlag.set(false)
                        cancelAccountSync.set(false)
                        cancelAccountImport.set(false)
                        state = state.copy(syncPaused = false, error = null)
                        requestAutomaticSync()
                    } else {
                        syncPausePersistenceFailed = true
                        syncPauseFlag.set(true)
                        syncError = SyncError(SyncErrorCode.STORAGE, "无法保存恢复状态，请重试")
                        state = state.copy(syncPaused = true, error = syncError?.message, syncError = syncError)
                    }
                }
            }
        })
    }

    private var searchPreferencesLoaded = false
    var rememberedSearchFilters by mutableStateOf(RecordFilters())
        private set
    var rememberedSearchSort by mutableStateOf(SORT_TIME_DESCENDING)
        private set

    @Volatile private var closed = false
    private fun recordEfficiencyPreferences(owner: UUID?) = getApplication<Application>()
        .getSharedPreferences("record-efficiency-${owner?.toString() ?: "guest"}", Application.MODE_PRIVATE)

    private fun loadRecordEfficiencySettings(owner: UUID?): RecordEfficiencySettings {
        val preferences = recordEfficiencyPreferences(owner)
        return RecordEfficiencySettings(
            amountInputMode = runCatching { AmountInputMode.valueOf(preferences.getString("amountInputMode", null) ?: "CALCULATOR") }.getOrDefault(AmountInputMode.CALCULATOR),
            rememberSearchFilters = preferences.getBoolean("rememberSearchFilters", true),
            decimalPlaces = preferences.getInt("decimalPlaces", 6).coerceIn(0, 6),
            decimalKeyPosition = runCatching { DecimalKeyPosition.valueOf(preferences.getString("decimalKeyPosition", null) ?: "BEFORE_ZERO") }.getOrDefault(DecimalKeyPosition.BEFORE_ZERO),
            deleteKeyPosition = runCatching { DeleteKeyPosition.valueOf(preferences.getString("deleteKeyPosition", null) ?: "RIGHT") }.getOrDefault(DeleteKeyPosition.RIGHT),
            categoryPickerStyle = runCatching { CategoryPickerStyle.valueOf(preferences.getString("categoryPickerStyle", null) ?: "POPUP") }.getOrDefault(CategoryPickerStyle.POPUP),
            categoryDisplay = runCatching { CategoryDisplay.valueOf(preferences.getString("categoryDisplay", null) ?: "GRID") }.getOrDefault(CategoryDisplay.GRID),
            remarkSuggestions = preferences.getBoolean("remarkSuggestions", true),
            defaultKind = runCatching { EntryKind.valueOf(preferences.getString("defaultKind", null) ?: "EXPENSE") }.getOrDefault(EntryKind.EXPENSE),
            defaultAccountId = runCatching { preferences.getString("defaultAccountId", null)?.let(UUID::fromString) }.getOrNull(),
        )
    }

    fun updateRecordEfficiencySettings(settings: RecordEfficiencySettings) {
        if (closed) return
        val owner = activeOwner
        val normalized = settings.normalized()
        val saved = recordEfficiencyPreferences(owner).edit()
            .putString("amountInputMode", normalized.amountInputMode.name)
            .putBoolean("rememberSearchFilters", normalized.rememberSearchFilters)
            .putInt("decimalPlaces", normalized.decimalPlaces)
            .putString("decimalKeyPosition", normalized.decimalKeyPosition.name)
            .putString("deleteKeyPosition", normalized.deleteKeyPosition.name)
            .putString("categoryPickerStyle", normalized.categoryPickerStyle.name)
            .putString("categoryDisplay", normalized.categoryDisplay.name)
            .putBoolean("remarkSuggestions", normalized.remarkSuggestions)
            .putString("defaultKind", normalized.defaultKind.takeUnless { it == EntryKind.EXPENSE }?.name)
            .putString("defaultAccountId", normalized.defaultAccountId?.toString())
            .commit()
        if (saved && activeOwner == owner) recordEfficiencySettings = normalized
        else if (!saved) state = state.copy(error = "记账效率设置保存失败，请重试")
    }

    var recordEfficiencySettings by mutableStateOf(loadRecordEfficiencySettings(null))
        private set
    var state by mutableStateOf(FinanceUiState())
        private set
    var categorySaving by mutableStateOf(false)
        private set
    private data class RecordSaveRequest(
        val save: PendingRecordSave,
        val session: DeviceIdentity?,
        val generation: Long,
        val templateId: UUID? = null,
        val templateRevision: Long? = null,
        val durable: Boolean = false,
        val error: String? = null,
    )
    private var recordSaveRequests by mutableStateOf<List<RecordSaveRequest>>(emptyList())
    private var recoveredRecordDraft: Pair<UUID, RecordDraft>? = null
    private fun visibleRecordSave(request: RecordSaveRequest): Boolean =
        state.snapshot?.ownerId == request.save.ownerId && (state.accountOwner != null) == (request.session != null)
    val pendingRecordSaves: List<PendingRecordSave>
        get() = recordSaveRequests.filter { it.error == null && visibleRecordSave(it) }.map { it.save }
    val failedRecordSaves: List<FailedRecordSave>
        get() = recordSaveRequests.filter { it.error != null && visibleRecordSave(it) }
            .map { FailedRecordSave(it.save, it.error!!) }
    val savingRecordCount: Int
        get() = recordSaveRequests.count { !it.durable && it.error == null && visibleRecordSave(it) }
    private val projectedState = derivedStateOf {
        val base = state
        val snapshot = base.snapshot
        val pending = pendingRecordSaves
        if (snapshot == null || pending.isEmpty()) base else base.copy(
            snapshot = projectPendingRecords(snapshot, pending, statisticsPeriod?.let { periodRange(it) } ?: statisticsRange),
            budget = base.budget?.let { projectPendingBudget(it, snapshot, pending, statisticsZone, useRecordTimezone = base.accountOwner != null) },
        )
    }
    val displayState: FinanceUiState get() = projectedState.value
    var recordDraft by mutableStateOf<RecordDraft?>(null)
        private set
    var templateDraft by mutableStateOf<RecordTemplateDraft?>(null)
        private set
    var templatesOpen by mutableStateOf(false)
        private set
    var deleteTemplateTarget by mutableStateOf<FinanceRecordTemplate?>(null)
        private set
    private var deleteTemplateQueued = false
    var accountDraft by mutableStateOf<AccountDraft?>(null)
        private set
    var recurrenceDraft by mutableStateOf<AccountRecurrenceDraft?>(null)
        private set
    var recurrenceDeleteTarget by mutableStateOf<CanonicalAccountRepository.RecurrenceSummary?>(null)
        private set
    var accountArchiveTarget by mutableStateOf<dev.icos.app.data.local.FinanceAccount?>(null)
        private set
    var deleteTarget by mutableStateOf<FinanceRecord?>(null)
        private set
    var ledgerDialogOpen by mutableStateOf(false)
        private set
    var categoryDraftName by mutableStateOf("")
    var categoryDraftDirection by mutableStateOf(dev.icos.app.domain.Direction.EXPENSE)
    var categoryDraftParent by mutableStateOf<UUID?>(null)
    var categoryDeleteTarget by mutableStateOf<FinanceCategory?>(null)
    var categoryDeleteTargetId by mutableStateOf<UUID?>(null)
    var categoryDeleteStrategy by mutableStateOf("MIGRATE_RECORDS")
    var ledgerDeleteTarget by mutableStateOf<FinanceLedger?>(null)
        private set
    private var ledgerDeleteConfirmation by mutableStateOf("")
        private set
    var undoNewRecordId by mutableStateOf<UUID?>(null)
        private set
    val statisticsZone: String = TimeZone.getDefault().id
    var reminderPreferences by mutableStateOf(ReminderPreferences())
        private set
    var statisticsPreferences by mutableStateOf(ReminderPreferences())
        private set
    var statisticsRange by mutableStateOf<PeriodRange?>(null)
        private set
    var statisticsLabel by mutableStateOf("全部")
        private set
    private var statisticsPeriod: ReportPeriod? = null
    var budgetPeriod by mutableStateOf(ReportPeriod.MONTH)
        private set
    var budgetDraft by mutableStateOf<BudgetDraft?>(null)
        private set
    var budgetCategoryDraft by mutableStateOf<BudgetCategoryDraft?>(null)
        private set
    var savingPlanDraft by mutableStateOf<SavingPlanDraft?>(null)
        private set
    var savingEntryDraft by mutableStateOf<SavingEntryDraft?>(null)
        private set
    var selectedSavingId by mutableStateOf<UUID?>(null)
        private set

    /** Main tab kept in memory only, so unlocking returns to the page the user left without persisting it. */
    var selectedTab by mutableStateOf(0)
        private set

    fun selectTab(index: Int) {
        selectedTab = index
    }
    var savingDeleteTarget by mutableStateOf<dev.icos.app.domain.SavingPlanSummary?>(null)
        private set
    var loanPreview by mutableStateOf<LoanPreview?>(null)
        private set
    var loanPreviewError by mutableStateOf<String?>(null)
        private set
    var loanPreviewBusy by mutableStateOf(false)
        private set
    var loanPreviewRate by mutableStateOf<Pair<Long, Long>?>(null)
        private set
    private var loanPreviewGeneration = 0

    init {
        refresh()
    }

    fun refresh() = work(sync = activeOwner != null && !syncPauseFlag.get())

    fun reloadAccountData() {
        if (activeOwner == null || !syncRecoveryRequired || accountSyncRunning || syncPauseFlag.get() || state.accountClearPending) return
        work(sync = true, reloadAccount = true)
    }

    private var automaticSyncRequested = false
    private var automaticSyncActive = false
    private var automaticSyncFirstRequestedAt: Long? = null
    private var automaticSyncRetryAt = 0L
    private var automaticSyncFailures = 0
    private val automaticSync = object : Runnable {
        override fun run() {
            if (!automaticSyncRequested) return
            if (closed || !automaticSyncActive || activeOwner == null || authenticatedSessionIdentity == null) {
                cancelAutomaticSyncRequest()
                return
            }
            if (syncPausePending || state.busy || categorySaving || accountSyncRunning || savingRecordCount > 0) {
                main.postDelayed(this, 250)
                return
            }
            cancelAutomaticSyncRequest()
            if (syncPauseFlag.get() || restoreBarrier.get() || state.accountClearPending || !networkAvailable()) return
            work(sync = true)
        }
    }

    fun requestAutomaticSync() {
        if (closed || !automaticSyncActive || activeOwner == null || authenticatedSessionIdentity == null) return
        val now = SystemClock.uptimeMillis()
        val first = automaticSyncFirstRequestedAt ?: now.also { automaticSyncFirstRequestedAt = it }
        val delay = if (state.snapshot == null) 300L else SyncControlPolicy.automaticSyncDelay(first, now)
        automaticSyncRequested = true
        main.removeCallbacks(automaticSync)
        main.postDelayed(automaticSync, maxOf(delay, automaticSyncRetryAt - now))
    }

    fun setAutomaticSyncActive(active: Boolean) {
        automaticSyncActive = active
        if (active) requestAutomaticSync() else {
            cancelAutomaticSyncRequest()
            automaticSyncFailures = 0
            automaticSyncRetryAt = 0L
        }
    }

    fun cancelAutomaticSyncRequest() {
        automaticSyncRequested = false
        automaticSyncFirstRequestedAt = null
        main.removeCallbacks(automaticSync)
    }

    private fun scheduleAutomaticSyncRetry() {
        if (syncError?.code == SyncErrorCode.NETWORK) {
            automaticSyncRetryAt = SystemClock.uptimeMillis() + SyncControlPolicy.automaticSyncRetryDelay(automaticSyncFailures)
            automaticSyncFailures = (automaticSyncFailures + 1).coerceAtMost(3)
            requestAutomaticSync()
        } else if (syncError == null) {
            automaticSyncFailures = 0
            automaticSyncRetryAt = 0L
        }
    }

    private fun networkAvailable(): Boolean {
        val manager = getApplication<Application>().getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    fun selectGuestLedger(id: UUID) {
        if (activeOwner != null || state.busy || categorySaving || state.snapshot?.ledgers?.none { it.id == id && it.archivedAt == null } != false) return
        selectedGuestLedgerId = id
        selectedClearLedgerId = null
        selectedClearOwnerId = null
        selectedClearEpochId = null
        selectedClearGeneration = null
        state = state.copy(clearPreview = null)
        val snapshot = state.snapshot ?: return
        val owner = snapshot.ownerId
        val activeLedgerIds = snapshot.ledgers.filter { it.archivedAt == null }.map { it.id.toString() }.toSet()
        val visibilityPrefs = getApplication<Application>().getSharedPreferences("guest-account-ledger-visibility-$owner", Application.MODE_PRIVATE)
        val validScopes = state.guestAccountLedgerVisibility.filterValues { it == "ALL" || it in activeLedgerIds }
        if (validScopes.size != state.guestAccountLedgerVisibility.size) {
            val editor = visibilityPrefs.edit().clear()
            validScopes.forEach { (accountId, scope) -> editor.putString(accountId.toString(), scope) }
            check(editor.commit()) { "无法清理失效账户账本范围" }
            state = state.copy(guestAccountLedgerVisibility = validScopes)
        }
        getApplication<Application>().getSharedPreferences("guest-ledger-selection-$owner", Application.MODE_PRIVATE)
            .edit().putString("ledgerId", id.toString()).apply()
        work(change = { repo -> repo.selectLedger(id) })
    }

    fun createLedger(name: String, currency: String, onEnqueued: () -> Unit = {}) {
        val ownerAtRequest = activeOwner
        if (ownerAtRequest != null) {
            if (state.busy || state.accountMutationPending || state.syncPaused) return
            workAccountMutation(mutate = { repository, device ->
                check(activeOwner == ownerAtRequest) { "账号已切换" }
                repository.accountLedgerCommands().create(device, name, currency)
            }, onEnqueued = onEnqueued)
        } else work(change = { it.createLedger(name, currency) }, after = onEnqueued)
    }

    fun copyLedger(sourceId: UUID, name: String, expectedRevision: Long? = null, onEnqueued: () -> Unit = {}) {
        val ownerAtRequest = activeOwner
        if (ownerAtRequest != null) {
            if (state.busy || state.accountMutationPending || state.syncPaused) return
            val revision = expectedRevision ?: return showError("请重新打开账本复制确认")
            workAccountMutation(mutate = { repository, device ->
                check(activeOwner == ownerAtRequest) { "账号已切换" }
                repository.accountLedgerCommands().copy(device, sourceId, revision, name)
            }, onEnqueued = onEnqueued)
        } else work(change = { it.copyLedger(sourceId, name) }, after = onEnqueued)
    }

    fun updateLedger(id: UUID, revision: Long, name: String, order: Long, makeDefault: Boolean, onEnqueued: () -> Unit = {}) {
        val ownerAtRequest = activeOwner
        if (ownerAtRequest != null) {
            if (state.busy || state.accountMutationPending || state.syncPaused) return
            val defaultRevisions = if (makeDefault) state.snapshot?.ledgers.orEmpty()
                .filter { it.isDefault && it.id != id }.associate { it.id to it.localRevision } else emptyMap()
            workAccountMutation(mutate = { repository, device ->
                check(activeOwner == ownerAtRequest) { "账号已切换" }
                repository.accountLedgerCommands().update(device, id, revision, name, order, makeDefault, defaultRevisions)
            }, onEnqueued = onEnqueued)
        } else work(change = { it.updateLedger(id, revision, name, order, makeDefault) }, after = onEnqueued)
    }

    fun reorderAccountLedgers(ledgers: List<dev.icos.app.data.local.FinanceLedger>) {
        val ownerAtRequest = activeOwner ?: return
        if (state.busy || state.accountMutationPending || state.syncPaused) return
        workAccountMutation(mutate = { repository, device ->
            check(activeOwner == ownerAtRequest) { "账号已切换" }
            repository.accountLedgerCommands().reorder(device, ledgers)
        })
    }

    fun archiveLedger(id: UUID, revision: Long, archived: Boolean) {
        val ownerAtRequest = activeOwner
        if (ownerAtRequest != null) {
            if (state.busy || state.accountMutationPending || state.syncPaused) return
            workAccountMutation(mutate = { repository, device ->
                check(activeOwner == ownerAtRequest) { "账号已切换" }
                repository.accountLedgerCommands().archive(device, id, revision, archived)
            })
        } else work(change = { it.archiveLedger(id, revision, archived) })
    }
    fun deleteLedger(ledger: FinanceLedger, confirmation: String, onEnqueued: () -> Unit = {}) {
        if (confirmation != ledger.name) { showError("请输入账本名称确认删除"); return }
        val ownerAtRequest = activeOwner
        if (ownerAtRequest != null) {
            if (state.busy || state.accountMutationPending || state.syncPaused) return
            workAccountMutation(mutate = { repository, device ->
                check(activeOwner == ownerAtRequest) { "账号已切换" }
                repository.accountLedgerCommands().delete(device, ledger, confirmation)
            }, onEnqueued = onEnqueued)
        } else work(change = { it.deleteLedger(ledger.id, ledger.localRevision, "DELETE_LEDGER") }, after = onEnqueued)
    }

    var categoryStyleRevision by mutableStateOf(0)
        private set

    private fun accountVisibleInCurrentLedger(accountId: UUID, ledgerId: UUID): Boolean =
        activeOwner != null || state.guestAccountLedgerVisibility[accountId].let { it == null || it == "ALL" || it == ledgerId.toString() }

    private fun RecordInput.accountIds(): Set<UUID> = when (this) {
        is RecordInput.Expense -> allocations.mapTo(mutableSetOf()) { it.accountId }
        is RecordInput.Income -> allocations.mapTo(mutableSetOf()) { it.accountId }
        is RecordInput.Transfer -> buildSet {
            add(from.accountId); add(to.accountId); fee?.let { add(it.accountId) }; bonus?.let { add(it.accountId) }
        }
    }

    fun templateVisibleInCurrentLedger(template: FinanceRecordTemplate): Boolean {
        val snapshot = state.snapshot ?: return false
        return template.input.accountIds().all { accountVisibleInCurrentLedger(it, snapshot.ledgerId) }
    }

    fun visibleAccounts(snapshot: FinanceSnapshot): List<FinanceAccount> =
        if (activeOwner != null) snapshot.accounts
        else snapshot.accounts.filter { accountVisibleInCurrentLedger(it.id, snapshot.ledgerId) }

    fun saveAccountVisibility(account: FinanceAccount, visibilityMode: String, ledgerRevisions: Map<UUID, Long>, onEnqueued: () -> Unit) {
        val ownerAtRequest = activeOwner ?: return
        if (state.busy || state.accountMutationPending || state.syncPaused || account.localRevision <= 0) return
        workAccountMutation(mutate = { repository, device ->
            check(activeOwner == ownerAtRequest) { "账号已切换" }
            repository.setAccountVisibility(device, account.id, account.localRevision, visibilityMode, ledgerRevisions)
        }, onEnqueued = onEnqueued)
    }

    fun setAccountLedgerDisplayScope(accountId: UUID, ledgerId: UUID?) {
        if (activeOwner != null || state.busy) return
        val snapshot = state.snapshot ?: return
        if (snapshot.accounts.none { it.id == accountId } || (ledgerId != null && snapshot.ledgers.none { it.id == ledgerId && it.archivedAt == null })) return
        val prefs = getApplication<Application>().getSharedPreferences("guest-account-ledger-visibility-${snapshot.ownerId}", Application.MODE_PRIVATE)
        if (ledgerId == null) {
            if (!prefs.edit().remove(accountId.toString()).commit()) { showError("无法保存账户账本显示范围"); return }
            state = state.copy(guestAccountLedgerVisibility = state.guestAccountLedgerVisibility - accountId)
            return
        }
        val activeLedgerCount = snapshot.ledgers.count { it.archivedAt == null }
        val value = if (activeLedgerCount <= 1) "ALL" else ledgerId.toString()
        if (!prefs.edit().putString(accountId.toString(), value).commit()) { showError("无法保存账户账本显示范围"); return }
        val scopes = state.guestAccountLedgerVisibility.toMutableMap()
        if (value == "ALL") scopes.remove(accountId) else scopes[accountId] = value
        state = state.copy(guestAccountLedgerVisibility = scopes)
    }

    fun removeAccountLedgerDisplayScope(accountId: UUID, ledgerId: UUID) {
        if (activeOwner != null || state.busy) return
        val snapshot = state.snapshot ?: return
        if (snapshot.ledgers.none { it.id == ledgerId && it.archivedAt == null }) return
        if (state.guestAccountLedgerVisibility[accountId] != ledgerId.toString()) return
        val prefs = getApplication<Application>().getSharedPreferences("guest-account-ledger-visibility-${snapshot.ownerId}", Application.MODE_PRIVATE)
        if (!prefs.edit().remove(accountId.toString()).commit()) { showError("无法保存账户账本显示范围"); return }
        state = state.copy(guestAccountLedgerVisibility = state.guestAccountLedgerVisibility - accountId)
    }

    private fun loadGuestAccountLedgerVisibility(owner: UUID, accounts: List<FinanceAccount>, ledgers: List<FinanceLedger>): Map<UUID, String> {
        val prefs = getApplication<Application>().getSharedPreferences("guest-account-ledger-visibility-$owner", Application.MODE_PRIVATE)
        val accountIds = accounts.map { it.id.toString() }.toSet()
        val activeLedgerIds = ledgers.filter { it.archivedAt == null }.map { it.id.toString() }.toSet()
        val valid = prefs.all.mapNotNull { (key, value) ->
            val ledgerId = value as? String ?: return@mapNotNull null
            if (key !in accountIds || ledgerId != "ALL" && (runCatching { UUID.fromString(ledgerId) }.isFailure || ledgerId !in activeLedgerIds)) null
            else runCatching { UUID.fromString(key) }.getOrNull()?.let { it to ledgerId }
        }.toMap()
        if (valid.size != prefs.all.size) {
            val editor = prefs.edit().clear()
            valid.forEach { (accountId, ledgerId) -> editor.putString(accountId.toString(), ledgerId) }
            check(editor.commit()) { "无法清理失效账户账本范围" }
        }
        return valid
    }

    fun saveAccountCategoryColor(
        category: FinanceCategory, ledger: FinanceLedger, parent: FinanceCategory?, color: String?,
        onEnqueued: () -> Unit = {},
    ) {
        val ownerAtRequest = activeOwner ?: return
        if (state.busy || state.accountMutationPending || state.syncPaused) return
        val snapshot = state.snapshot ?: return
        if (snapshot.ownerId != ownerAtRequest || snapshot.ledgerId != ledger.id ||
            snapshot.ledgers.none { it.id == ledger.id && it.localRevision == ledger.localRevision && it.archivedAt == null } ||
            snapshot.allCategories.none { it.id == category.id && it.localRevision == category.localRevision && it.archivedAt == null && it.ledgerId == ledger.id } ||
            category.parentId != parent?.id || parent != null && snapshot.allCategories.none {
                it.id == parent.id && it.localRevision == parent.localRevision && it.archivedAt == null
            }) return showError("分类或账本已变化，请重新打开颜色设置")
        workAccountMutation(mutate = { repository, device ->
            check(activeOwner == ownerAtRequest) { "账号已切换" }
            repository.accountCategoryCommands().setColor(device, category, color, ledger, parent)
        }, onEnqueued = onEnqueued)
    }

    fun saveCategoryStyle(owner: UUID, category: FinanceCategory, style: CategoryStyle) {
        val snapshot = state.snapshot ?: return
        if (closed || state.busy || snapshot.ownerId != owner || category.ledgerId != snapshot.ledgerId || snapshot.allCategories.none { it.id == category.id && it.ledgerId == category.ledgerId }) return
        runCatching { CategoryStyles.write(getApplication<Application>(), owner, category, style) }
            .onSuccess { categoryStyleRevision++ }
            .onFailure { state = state.copy(error = "分类样式保存失败") }
    }

    fun resetCategoryStyle(owner: UUID, category: FinanceCategory) {
        val snapshot = state.snapshot ?: return
        if (closed || state.busy || snapshot.ownerId != owner || category.ledgerId != snapshot.ledgerId || snapshot.allCategories.none { it.id == category.id && it.ledgerId == category.ledgerId }) return
        runCatching { CategoryStyles.reset(getApplication<Application>(), owner, category) }
            .onSuccess { categoryStyleRevision++ }
            .onFailure { state = state.copy(error = "分类样式重置失败") }
    }

    fun saveCategory(
        id: UUID? = null, expectedRevision: Long? = null, name: String = categoryDraftName,
        direction: dev.icos.app.domain.Direction = categoryDraftDirection, parentId: UUID? = categoryDraftParent,
        expectedLedgerRevision: Long? = null, expectedParentRevision: Long? = null, excludeBudget: Boolean? = null,
        onEnqueued: (UUID) -> Unit = {},
    ) {
        if (id == null) {
            createCategory(name, direction, parentId, expectedLedgerRevision, expectedParentRevision,
                excludeBudget ?: false, onEnqueued)
            return
        }
        if (categorySaving) return
        val ownerAtRequest = activeOwner
        if (ownerAtRequest != null) {
            if (state.busy) return
            val snapshot = state.snapshot ?: return
            val ledger = snapshot.ledgers.singleOrNull { it.id == snapshot.ledgerId } ?: return showError("账本已不可用")
            if (expectedLedgerRevision != null && ledger.localRevision != expectedLedgerRevision) return showError("账本已变化，请重新确认")
            val category = snapshot.allCategories.singleOrNull { it.id == id }
            if (category == null || category.localRevision != expectedRevision || category.ledgerId != ledger.id ||
                category.parentId != parentId || category.direction != direction)
                return showError("分类已变化，请重新编辑")
            val parent = parentId?.let { selected -> snapshot.allCategories.singleOrNull { it.id == selected } }
            if (parentId != null && (parent == null || expectedParentRevision != null && parent.localRevision != expectedParentRevision))
                return showError("上级分类已变化，请重新选择")
            workAccountMutation(categoryOnly = true, mutate = { repository, device ->
                check(activeOwner == ownerAtRequest) { "账号已切换" }
                check(excludeBudget == null || excludeBudget == category.excludeBudget) { "请通过分类预算设置修改排除规则。" }
                repository.accountCategoryCommands().rename(device, category, name, ledger, parent)
            }, onEnqueued = { categoryDraftName = ""; onEnqueued(id) })
        } else work(change = { it.saveCategory(id, expectedRevision, name, direction, parentId, excludeBudget = excludeBudget) },
            onCommitted = { categoryDraftName = ""; onEnqueued(id) })
    }

    private fun createCategory(
        name: String,
        direction: dev.icos.app.domain.Direction,
        parentId: UUID?,
        expectedLedgerRevision: Long?,
        expectedParentRevision: Long?,
        excludeBudget: Boolean,
        onEnqueued: (UUID) -> Unit,
    ) {
        if (closed || categorySaving || state.busy || state.restoreBusy || restoreBarrier.get() || state.accountClearPending) return
        val snapshot = state.snapshot ?: return
        val owner = activeOwner
        val expectedGeneration = generation
        val ledger = snapshot.ledgers.singleOrNull { it.id == snapshot.ledgerId && it.archivedAt == null }
            ?: return showError("账本已不可用")
        if (expectedLedgerRevision != null && ledger.localRevision != expectedLedgerRevision) return showError("账本已变化，请重新确认")
        val parent = parentId?.let { id -> snapshot.allCategories.singleOrNull { it.id == id } }
        if (parentId != null && (parent == null || expectedParentRevision != null && parent.localRevision != expectedParentRevision))
            return showError("上级分类已变化，请重新选择")
        if (owner != null && (expectedLedgerRevision == null || parentId != null && expectedParentRevision == null))
            return showError("请重新打开分类创建表单")
        val session = if (owner == null) null else authenticatedSessionIdentity ?: return showError("账号会话身份尚未就绪")
        categorySaving = true
        state = state.copy(error = null)
        if (owner != null) accountMutationVersion.incrementAndGet()
        val worker = if (owner == null) executor else accountLocalExecutor
        worker.execute {
            var createdId: UUID? = null
            var guestRepository: LocalFinanceRepository? = null
            val mutation = runCatching {
                check(ownsWork(owner, expectedGeneration)) { "账号已切换" }
                if (owner == null) {
                    check(selectedGuestLedgerId == snapshot.ledgerId) { "账本已切换，请重新确认" }
                    val repo = repository ?: LocalFinanceRepository.openGuest(getApplication()).also { repository = it }
                    guestRepository = repo
                    createdId = repo.saveCategory(null, null, name, direction, parentId, excludeBudget = excludeBudget)
                } else {
                    withAccountMutation(owner, snapshot.epochId, requireNotNull(session)) { mutations, device ->
                        check(ownsWork(owner, expectedGeneration)) { "账号已切换" }
                        createdId = mutations.accountCategoryCommands().create(device, ledger, name, direction, parent, excludeBudget)
                    }
                }
            }
            // The local transaction returned its durable id: acknowledge the create before the list
            // read-back so the editor never waits on projection work.
            val committedId = createdId
            if (committedId != null) {
                main.post {
                    if (!ownsWork(owner, expectedGeneration)) return@post
                    categorySaving = false
                    categoryDraftName = ""
                    if (owner != null) {
                        state = state.copy(
                            error = null,
                            accountMutationPending = true,
                            accountMutationRetryOnly = false,
                            syncPendingCount = state.syncPendingCount + 1,
                        )
                    }
                    onEnqueued(committedId)
                    if (owner != null) requestAutomaticSync()
                }
            }
            val readBack: Result<Pair<List<FinanceCategory>, List<FinanceCategory>>> =
                if (mutation.isSuccess) {
                    runCatching {
                        if (owner == null) {
                            check(ownsWork(owner, expectedGeneration)) { "账号已切换" }
                            val repo = guestRepository ?: error("Guest repository missing")
                            repo.readCategories() to emptyList<FinanceCategory>()
                        } else {
                            val database = localAccountDatabase(owner, snapshot.epochId)
                            val projection = CanonicalAccountRepository.readCategoryProjection(database, owner, snapshot.epochId)
                            projection.categories to projection.pendingCreations
                        }
                    }
                } else {
                    Result.failure(mutation.exceptionOrNull() ?: StorageOpenException())
                }
            main.post {
                if (!ownsWork(owner, expectedGeneration)) return@post
                if (committedId == null) {
                    categorySaving = false
                    state = state.copy(error = mutation.exceptionOrNull()?.let { message(it) } ?: "分类创建未完成，请重试")
                    return@post
                }
                readBack.getOrNull()?.let { (categories, pending) ->
                    mergeCategories(snapshot.ownerId, snapshot.epochId, categories, pending)
                }
                // A committed create keeps its durable id and queue entry even when the read-back fails.
                if (readBack.isFailure) state = state.copy(error = "分类已保存，但列表暂时无法刷新，请重试刷新。")
            }
        }
    }

    private fun mergeCategories(owner: UUID, epoch: UUID, categories: List<FinanceCategory>, pending: List<FinanceCategory>) {
        val current = state.snapshot ?: return
        if (current.ownerId != owner || current.epochId != epoch) return
        state = state.copy(
            snapshot = current.copy(
                categories = categories.filter { it.ledgerId == current.ledgerId && it.archivedAt == null }.let { selected ->
                    if (activeOwner != null) selected else selected.sortedWith(
                        compareBy<FinanceCategory> { it.sortOrder }.thenBy { it.parentId?.toString().orEmpty() }.thenBy { it.id.toString() })
                },
                allCategories = categories,
            ),
            pendingCategories = pending,
        )
    }

    fun setCategoryBudgetExcluded(
        category: FinanceCategory, ledger: FinanceLedger, parent: FinanceCategory?, excluded: Boolean,
        onEnqueued: () -> Unit = {},
    ) {
        if (state.busy || state.accountMutationPending || state.syncPaused || category.excludeBudget == excluded) return
        val snapshot = state.snapshot ?: return
        val ownerAtRequest = activeOwner
        if (snapshot.ledgerId != ledger.id || category.ledgerId != ledger.id ||
            snapshot.ledgers.none { it.id == ledger.id && it.localRevision == ledger.localRevision && it.archivedAt == null } ||
            snapshot.allCategories.none { it.id == category.id && it.localRevision == category.localRevision && it.archivedAt == null } ||
            category.parentId != parent?.id || parent != null && snapshot.allCategories.none {
                it.id == parent.id && it.localRevision == parent.localRevision && it.archivedAt == null
            }) return showError("分类、上级或账本已变化，请重新打开预算设置。")
        if (ownerAtRequest != null) {
            val epoch = snapshot.epochId
            workAccountMutation(mutate = { repository, device ->
                check(activeOwner == ownerAtRequest && loadedAccountEpoch == epoch) { "账号或数据集已变化，请重新操作。" }
                repository.accountCategoryCommands().setBudgetExcluded(device, category, excluded, ledger, parent)
            }, onEnqueued = onEnqueued)
        } else work(change = { it.setBudgetExcluded(category.id, category.localRevision, excluded) }, after = onEnqueued)
    }

    fun archiveCategory(category: FinanceCategory, archived: Boolean) {
        val ownerAtRequest = activeOwner
        if (ownerAtRequest != null) {
            if (state.busy || state.accountMutationPending || state.syncPaused) return
            val snapshot = state.snapshot ?: return
            val ledger = snapshot.ledgers.singleOrNull { it.id == category.ledgerId } ?: return showError("账本已不可用")
            val parent = category.parentId?.let { parentId -> snapshot.allCategories.singleOrNull { it.id == parentId } }
            if (category.parentId != null && parent == null) return showError("上级分类已不可用")
            val children = snapshot.allCategories.filter { it.parentId == category.id }
            workAccountMutation(mutate = { repository, device ->
                check(activeOwner == ownerAtRequest) { "账号已切换" }
                repository.accountCategoryCommands().archive(device, category, archived, ledger, parent, children)
            })
        } else work(change = { it.archiveCategory(category.id, category.localRevision, archived) })
    }
    fun reorderAccountCategories(categories: List<FinanceCategory>) {
        val ownerAtRequest = activeOwner ?: return
        if (state.busy || state.accountMutationPending || state.syncPaused) return
        workAccountMutation(mutate = { repository, device ->
            check(activeOwner == ownerAtRequest) { "账号已切换" }
            repository.accountCategoryCommands().reorder(device, categories)
        })
    }

    fun moveCategory(category: FinanceCategory, targetLedgerId: UUID, direction: dev.icos.app.domain.Direction, parentId: UUID?,
        expectedLedgerRevision: Long? = null, expectedParentRevision: Long? = null, expectedSourceParentRevision: Long? = null,
        onEnqueued: () -> Unit = {}) {
        val ownerAtRequest = activeOwner
        if (ownerAtRequest != null) {
            if (state.busy || state.accountMutationPending || state.syncPaused) return
            val snapshot = state.snapshot ?: return
            val target = snapshot.ledgers.singleOrNull { it.id == targetLedgerId && it.localRevision == expectedLedgerRevision }
                ?: return showError("目标账本已变化，请重新确认")
            val parent = parentId?.let { selected -> snapshot.allCategories.singleOrNull { it.id == selected && it.localRevision == expectedParentRevision } }
            if (parentId != null && parent == null) return showError("目标上级分类已变化，请重新选择")
            val sourceParent = category.parentId?.let { selected -> snapshot.allCategories.singleOrNull { it.id == selected } }
            if (category.parentId != null && (sourceParent == null || sourceParent.localRevision != expectedSourceParentRevision))
                return showError("原上级分类已变化，请重新打开移动表单")
            workAccountMutation(mutate = { repository, device ->
                check(activeOwner == ownerAtRequest) { "账号已切换" }
                repository.accountCategoryCommands().move(device, category, target, direction, parent, sourceParent)
            }, onEnqueued = onEnqueued)
        } else work(change = { it.moveCategory(category.id, category.localRevision, targetLedgerId, direction, parentId) }, after = onEnqueued)
    }
    fun previewAccountCategoryMigration(source: FinanceCategory, target: FinanceCategory,
        onReady: (dev.icos.app.data.local.AccountCategoryCommands.CategoryDeletePreview) -> Unit) {
        readAccountMetadataPreview({ it.accountCategoryCommands().previewMigrateDelete(source, target) }, onReady)
    }

    fun deleteAccountCategoryMigrating(preview: dev.icos.app.data.local.AccountCategoryCommands.CategoryDeletePreview,
        onEnqueued: () -> Unit = {}) {
        val ownerAtRequest = activeOwner ?: return
        if (state.busy || state.accountMutationPending || state.syncPaused) return
        workAccountMutation(mutate = { repository, device ->
            check(activeOwner == ownerAtRequest) { "账号已切换" }
            repository.accountCategoryCommands().deleteMigrating(device, preview)
        }, onEnqueued = onEnqueued)
    }

    fun previewAccountCategoryRecordDeletion(source: FinanceCategory,
        onReady: (dev.icos.app.data.local.AccountCategoryDeletionCommands.CategoryRecordDeletePreview) -> Unit) {
        readAccountMetadataPreview({ it.accountCategoryDeletionCommands().preview(source) }, onReady)
    }

    fun deleteAccountCategoryWithRecords(
        preview: dev.icos.app.data.local.AccountCategoryDeletionCommands.CategoryRecordDeletePreview,
        confirmation: String,
        onEnqueued: () -> Unit = {},
    ) {
        val ownerAtRequest = activeOwner ?: return
        if (state.busy || state.accountMutationPending || state.syncPaused) return
        workAccountMutation(mutate = { repository, device ->
            check(activeOwner == ownerAtRequest) { "账号已切换" }
            repository.accountCategoryDeletionCommands().delete(device, preview, confirmation)
        }, onEnqueued = onEnqueued)
    }

    fun deleteCategory(category: FinanceCategory, strategy: String, target: UUID? = null) {
        if (activeOwner != null) return showError("账号分类删除尚未开放")
        val categoryStyleOwner = state.snapshot?.ownerId ?: return
        work(
            change = { it.deleteCategory(category.id, category.localRevision, strategy, target) },
            after = { removeDeletedCategoryStyle(categoryStyleOwner, category) },
        )
    }

    private fun removeDeletedCategoryStyle(owner: UUID, category: FinanceCategory) {
        if (closed || state.busy || state.snapshot?.ownerId != owner) return
        runCatching { CategoryStyles.reset(getApplication<Application>(), owner, category) }
            .onSuccess { categoryStyleRevision++ }
            .onFailure { state = state.copy(error = "分类已删除，但本地样式清理失败") }
    }

    fun loadAssetHistory(months: Int = 12, includeFuture: Boolean = false) {
        if (closed || state.busy || state.assetHistoryBusy || months !in 1..60) return
        state = state.copy(assetHistoryMonths = months, assetHistoryIncludeFuture = includeFuture)
        val ownerAtEnqueue = activeOwner
        val generationAtEnqueue = generation
        val asOfMillis = System.currentTimeMillis()
        state = state.copy(assetHistoryBusy = true, assetHistoryError = null)
        executor.execute {
            val result = runCatching {
                check(ownsWork(ownerAtEnqueue, generationAtEnqueue)) { "账本已切换" }
                if (ownerAtEnqueue == null) {
                    val repo = repository ?: LocalFinanceRepository.openGuest(getApplication<Application>()).also { repository = it }
                    repo.assetHistory(months, statisticsZone, asOfMillis, includeFuture)
                } else {
                    val epoch = EncryptedDatasetStore.activeAccountEpoch(getApplication(), ownerAtEnqueue) ?: throw StorageOpenException()
                    if (loadedAccountEpoch != epoch) throw IllegalStateException("账号账本尚未加载")
                    requireAccountDatasetFiles(ownerAtEnqueue, epoch)
                    val database = EncryptedDatasetStore.openAccount(getApplication(), ownerAtEnqueue, epoch)
                    try {
                        val identity = database.identity().read() ?: throw StorageOpenException()
                        check(identity.singleton == 1 && identity.schemaVersion == 1 && identity.ownerId == ownerAtEnqueue.toString() && identity.epochId == epoch.toString())
                        CanonicalAccountRepository.assetHistory(database, months, statisticsZone, asOfMillis, includeFuture)
                    } finally { database.close() }
                }
            }
            main.post {
                if (ownsWork(ownerAtEnqueue, generationAtEnqueue)) {
                    state = state.copy(assetHistory = result.getOrDefault(emptyList()), assetHistoryBusy = false,
                        assetHistoryError = result.exceptionOrNull()?.message, assetHistoryMonths = months,
                        assetHistoryIncludeFuture = includeFuture)
                }
            }
        }
    }

    fun selectAccountLedger(id: UUID) {
        if (activeOwner == null || state.busy || categorySaving || state.accountLedgers.none { it.id == id }) return
        selectedAccountLedgerId = id
        getApplication<Application>()
            .getSharedPreferences("account-ledger-selection-${activeOwner}", Application.MODE_PRIVATE)
            .edit()
            .putString("ledgerId", id.toString())
            .apply()
        work()
    }

    fun cancelAccountBootstrap() {
        if (activeOwner != null && state.bootstrapProgress != null) pauseAccountSync()
    }

    /**
     * Reacts to the authenticated server account changing. A null owner returns to the
     * permanently separate guest dataset. The canonical account replica lives in its own encrypted
     * space; its queue and cursor are opened only for the current owner and closed again after each
     * sync, so they can never leak across owners.
     */
    @Volatile private var exportSessionIdentity: DeviceIdentity? = null
    @Volatile private var exportTicket: AccountExportTicket? = null
    @Volatile private var pendingExportRequest: AccountExportRequest? = null
    private val exportRequestLock = Any()
    private val exportPickerPrefs = application.getSharedPreferences("account-export-picker", Application.MODE_PRIVATE)
    var accountExportRequest by mutableStateOf<AccountExportRequest?>(null)
        private set

    fun pendingAccountExportRequest(identity: DeviceIdentity?): AccountExportRequest? = synchronized(exportRequestLock) {
        accountExportRequest?.takeIf { it.ticket.identity == identity && identity != null }
    }

    fun markAccountExportPickerLaunch(request: AccountExportRequest): Boolean = synchronized(exportRequestLock) {
        val current = accountExportRequest
        if (current?.nonce != request.nonce || current.pickerLaunched || exportSessionIdentity != current.ticket.identity ||
            !ownsWork(current.ticket.owner, current.ticket.generation) || exportPickerPrefs.getString("nonce", null) != null) return@synchronized false
        val launched = current.copy(pickerLaunched = true)
        if (!exportPickerPrefs.edit().putString("nonce", launched.nonce.toString())
                .putString("identity", "${launched.ticket.identity.owner}/${launched.ticket.identity.sessionId}")
                .putString("format", launched.format.name).commit()) return@synchronized false
        accountExportRequest = launched
        pendingExportRequest = launched
        true
    }

    fun finishAccountExportPicker(format: AccountExportFormat): AccountExportRequest? = synchronized(exportRequestLock) {
        val markerNonce = exportPickerPrefs.getString("nonce", null) ?: return@synchronized null
        val current = pendingExportRequest
        if (current == null) {
            // Process recreation lost the in-memory ticket; consume only the matching orphan result.
            val storedFormat = exportPickerPrefs.getString("format", null)
            if (storedFormat != format.name) return@synchronized null
            exportPickerPrefs.edit().clear().commit()
            return@synchronized null
        }
        val markerFormat = exportPickerPrefs.getString("format", null)
        if (current.format != format || markerFormat != format.name || !current.pickerLaunched || accountExportRequest?.nonce != current.nonce || markerNonce != current.nonce.toString()) return@synchronized null
        exportPickerPrefs.edit().clear().commit()
        pendingExportRequest = null
        accountExportRequest = null
        current
    }

    fun restoreAccountExportRequest(identity: DeviceIdentity?): AccountExportRequest? = synchronized(exportRequestLock) {
        accountExportRequest?.takeIf { it.ticket.identity == identity && identity != null }?.let { return@synchronized it }
        val nonce = exportPickerPrefs.getString("nonce", null) ?: return@synchronized null
        val storedIdentity = exportPickerPrefs.getString("identity", null)
        if (identity == null || storedIdentity != "${identity.owner}/${identity.sessionId}") {
            pendingExportRequest = null
            accountExportRequest = null
            return@synchronized null
        }
        // A still-live Activity Result may deliver after process recreation; never reconstruct its ticket.
        null
    }

    fun clearLostAccountExportPicker(uri: Uri?) = synchronized(exportRequestLock) {
        if (uri != null && uri.scheme == "content") runCatching { getApplication<Application>().contentResolver.delete(uri, null, null) }
        exportPickerPrefs.edit().clear().commit()
        pendingExportRequest = null
        accountExportRequest = null
    }

    fun hasUnresolvedAccountExportPicker() = synchronized(exportRequestLock) {
        exportPickerPrefs.getString("nonce", null) != null || pendingExportRequest?.pickerLaunched == true
    }

    fun abandonAccountExportPicker(nonce: UUID) = synchronized(exportRequestLock) {
        if (pendingExportRequest?.nonce == nonce || accountExportRequest?.nonce == nonce) {
            if (exportPickerPrefs.getString("nonce", null) == nonce.toString()) exportPickerPrefs.edit().clear().commit()
            pendingExportRequest = null
            accountExportRequest = null
        }
    }

    fun onSessionChanged(owner: UUID?, sessionIdentity: DeviceIdentity? = null, guestMode: Boolean = owner == null) {
        val currentIdentity = sessionIdentity?.takeIf { it.owner == owner }
        val identityChanged = currentIdentity != authenticatedSessionIdentity
        // A cleared ViewModel must not keep claiming a valid binding; readers would resume work for it.
        boundSession = if (closed) null else BoundSession(owner, currentIdentity, guestMode)
        if (closed || owner == activeOwner && !identityChanged) return
        setAutomaticSyncActive(false)
        conflictRequestGeneration++
        syncControlIdentity = currentIdentity
        val previousOwner = activeOwner
        exportSessionIdentity = currentIdentity
        synchronized(exportRequestLock) {
            if (accountExportRequest?.ticket?.identity != exportSessionIdentity) accountExportRequest = null
            if (pendingExportRequest?.ticket?.identity != exportSessionIdentity) pendingExportRequest = null
            val markerNonce = exportPickerPrefs.getString("nonce", null)
            val markerIdentity = exportPickerPrefs.getString("identity", null)
            if (markerNonce != null && markerIdentity != null && markerIdentity != exportSessionIdentity?.let { "${it.owner}/${it.sessionId}" }) {
                exportPickerPrefs.edit().putString("identity", "orphan:$markerNonce").commit()
            }
        }
        exportTicket = null
        val ownerGeneration = ++generation
        categorySaving = false
        cancelAutomaticSyncRequest()
        automaticSyncFailures = 0
        automaticSyncRetryAt = 0L
        val pausedPreference = owner?.let(::loadSyncPaused) ?: false
        syncPausePending = true
        cancelAccountImport.set(true)
        // A picker may still be out; its late result must not bind to the session that replaced this one.
        clearFileIoForSessionChange()
        authenticatedSessionIdentity = currentIdentity
        activeOwner = owner
        accountLocalExecutor.execute { closeAccountDatabase() }
        syncRecoveryRequired = false
        syncPauseFlag.set(true)
        cancelAccountSync.set(true)
        state = state.copy(accountOwner = owner?.toString(), syncPaused = false)
        auth.bindSyncControlIdentity(currentIdentity, pausedPreference, accountImportNetworkGate) { controlGeneration, outcome ->
            main.post {
                if (generation == ownerGeneration && activeOwner == owner && authenticatedSessionIdentity == currentIdentity) {
                    syncPauseControlGeneration = controlGeneration
                    syncPausePending = false
                    syncPausePersistenceFailed = outcome.isFailure
                    if (outcome.isSuccess) {
                        cancelAccountImport.set(false)
                        cancelAccountSync.set(false)
                    }
                    syncPauseFlag.set(pausedPreference || outcome.isFailure)
                    if (outcome.isFailure) {
                        syncError = SyncError(SyncErrorCode.STORAGE, "无法切换当前账号同步门控，请重试")
                        state = state.copy(syncPaused = false, error = syncError?.message, syncError = syncError)
                    } else {
                        syncError = null
                        state = state.copy(syncPaused = pausedPreference, error = null, syncError = null)
                        requestAutomaticSync()
                    }
                }
            }
        }
        restoreBarrier.set(owner != null)
        if (owner != previousOwner) {
            recordDraft = null
            templateDraft = null
            templatesOpen = false
            deleteTemplateTarget = null
            deleteTemplateQueued = false
            deleteTarget = null
            accountDraft = null
            recurrenceDraft = null
            recurrenceDeleteTarget = null
        }
        activeOwner = owner
        restoreOperation = null
        restoreJobId = null
        recordEfficiencySettings = loadRecordEfficiencySettings(owner)
        cancelAccountSync.set(true)
        lastSyncSuccessAt = owner?.let(::loadLastSuccess)
        cancelAccountSync.set(true)
        syncEpochArchived = false
        syncError = null
        syncPausedRequestUncertain = false
        accountSummary = null
        activeClearOpId = null
        activeClearFrozenWire = null
        selectedAccountLedgerId = null
        selectedClearLedgerId = null
        selectedClearOwnerId = null
        selectedClearEpochId = null
        selectedClearGeneration = null
        syncedAccountEpoch = null
        loadedAccountEpoch = null
        recordDraft = null
        templateDraft = null
        templatesOpen = false
        deleteTemplateTarget = null
        deleteTemplateQueued = false
        accountDraft = null
        recurrenceDraft = null
        recurrenceDeleteTarget = null
        accountArchiveTarget = null
        deleteTarget = null
        undoNewRecordId = null
        budgetDraft = null
        budgetCategoryDraft = null
        savingPlanDraft = null
        savingEntryDraft = null
        savingDeleteTarget = null
        selectedSavingId = null
        selectedTab = 0
        loanPreview = null
        loanPreviewError = null
        loanPreviewBusy = false
        searchPreferencesLoaded = false
        rememberedSearchFilters = RecordFilters()
        rememberedSearchSort = SORT_TIME_DESCENDING
        val range = statisticsRange
        val guestOwner = state.snapshot?.ownerId?.takeIf { owner == null && state.accountOwner == null }
        state = FinanceUiState(accountOwner = owner?.toString(), syncPaused = syncPauseFlag.get(),
            syncLastSuccessAt = lastSyncSuccessAt,
            guestMultiLedgerEnabled = guestOwner?.let { loadGuestMultiLedgerEnabled(it.toString()) } ?: true,
            restoreBusy = restoreBarrier.get(),
            restoreProgress = if (restoreBarrier.get()) "正在确认已保存的恢复状态" else null)
        executor.execute {
            if (owner != null) {
                val pendingClear = runCatching { auth.pendingDatasetClear() }.getOrElse { failure ->
                    main.post { if (ownsWork(owner, ownerGeneration)) state = state.copy(accountClearPending = true, error = "清账状态无法安全读取；账号操作已暂停：${message(failure as? Exception ?: IllegalStateException(failure))}") }
                    return@execute
                }
                if (pendingClear != null) {
                    if (pendingClear.ownerId != owner) {
                        main.post { if (ownsWork(owner, ownerGeneration)) state = state.copy(accountClearPending = true, error = "存在其他账号的未完成清账屏障；请切回原账号继续。") }
                        return@execute
                    }
                    activeClearOpId = pendingClear.opId
                    activeClearFrozenWire = pendingClear.frozenGroupJson
                    main.post { if (ownsWork(owner, ownerGeneration)) state = state.copy(accountClearPending = true,
                        accountClearReauthRequired = pendingClear.phase in setOf("REAUTH", "AUTHORIZED")) }
                    return@execute
                }
                val pending = try { auth.pendingRestore(owner) } catch (failure: Exception) {
                    main.post { if (ownsWork(owner, ownerGeneration)) state = state.copy(restoreBusy = true, restoreSubmissionRetryAvailable = false, restoreError = "恢复状态无法安全读取，账号操作已暂停：${message(failure)}") }
                    return@execute
                }
                if (pending != null) {
                    val operation = auth.restoreOperation(pending)
                    restoreOperation = operation
                    restoreJobId = pending.jobId
                    restoreBarrier.set(true)
                    if (pending.committedEpoch != null) {
                        try {
                            AccountSyncCoordinator.synchronizeCommittedRestore(
                                getApplication(), auth, owner, pending.epoch, pending.committedEpoch, onProgress = {},
                            )
                            auth.finishRestore(operation, RestoreStatus(
                                pending.jobId ?: throw AuthFailure("RESTORE_UNCONFIRMED"), pending.opId, pending.backupId,
                                "COMMITTED", 100, null, pending.epoch, pending.headSeq, pending.committedEpoch,
                            ))
                            restoreBarrier.set(false)
                            restoreOperation = null
                            restoreJobId = null
                            runWork(null, {}, range, ownerAtEnqueue = owner, generationAtEnqueue = ownerGeneration)
                        } catch (failure: Exception) {
                            main.post { if (ownsWork(owner, ownerGeneration)) state = state.copy(
                                restoreBusy = false, restoreError = "恢复已提交；新 epoch 本地完整导入仍未完成：${restoreMessage(failure)}",
                            ) }
                        }
                    } else if (pending.jobId != null) {
                        runCatching { pollRestore(owner, ownerGeneration, pending.epoch, operation, pending.jobId) }
                            .onFailure { failure -> main.post { if (ownsWork(owner, ownerGeneration)) state = state.copy(
                                restoreBusy = false, restoreJobId = pending.jobId, restoreProgress = "已恢复原任务查询；状态仍待确认",
                                restoreError = restoreMessage(failure),
                            ) } }
                    } else {
                        main.post { if (ownsWork(owner, ownerGeneration)) state = state.copy(
                            restoreBusy = false, restoreProgress = "检测到上次恢复提交结果未知",
                            restoreSubmissionRetryAvailable = true,
                            restoreError = "恢复请求已加密保留；请显式使用同一 opId、请求体与 action token 重试。不会自动发起新请求。",
                        ) }
                    }
                    return@execute
                }
                restoreBarrier.set(false)
                main.post {
                    if (ownsWork(owner, ownerGeneration)) {
                        state = state.copy(restoreBusy = false, restoreProgress = null)
                    }
                }
            }
            runWork(null, {}, range, ownerAtEnqueue = owner, generationAtEnqueue = ownerGeneration)
            main.post { if (ownsWork(owner, ownerGeneration)) requestAutomaticSync() }
        }
    }

    fun saveSearchPreferences(
        filters: RecordFilters,
        sort: String,
    ) {
        if (closed || activeOwner != null) return
        rememberedSearchFilters = filters
        rememberedSearchSort = sort
        val value =
            SearchPreferencesEntity(
                type = filters.type,
                fromDate = filters.fromDate,
                throughDate = filters.throughDate,
                currency = filters.currency,
                accountId = filters.accountId,
                categoryId = filters.categoryId,
                tagIds = filters.tagIds.sorted().joinToString(","),
                tagMatch = filters.tagMatch,
                minimumAmount = filters.minimumAmount,
                maximumAmount = filters.maximumAmount,
                sort = sort,
            )
        val ownerAtEnqueue = activeOwner
        val generationAtEnqueue = generation
        executor.execute {
            try {
                val repo = repository ?: LocalFinanceRepository.openGuest(getApplication<Application>()).also { repository = it }
                if (ownsWork(ownerAtEnqueue, generationAtEnqueue) && ownerAtEnqueue == null) repo.saveSearchPreferences(value)
            } catch (failure: Exception) {
                main.post { if (ownsWork(ownerAtEnqueue, generationAtEnqueue)) showError(message(failure)) }
            }
        }
    }

    fun setRefundPreferences(expectedRevision: Long, refundExcessAsIncome: Boolean, showRefundsInRecordList: Boolean) {
        if (activeOwner != null) {
            if (state.accountMutationPending) {
                showError("更改尚待服务器确认，请先重试同步原操作。")
                return
            }
            workAccountMutation(mutate = { repository, device ->
                repository.setRefundPreferences(device, expectedRevision, refundExcessAsIncome, showRefundsInRecordList)
            })
        } else work(change = { it.saveBusinessPreferences(expectedRevision, refundExcessAsIncome, showRefundsInRecordList) })
    }

    fun selectBudgetPeriod(period: ReportPeriod) {
        if (state.busy) return
        budgetPeriod = period
        work(sync = activeOwner != null)
    }

    fun setBudgetEnabled(enabled: Boolean) {
        val expected = state.budget ?: return
        if (expected.enabled == enabled) return
        if (activeOwner != null) {
            if (state.accountMutationPending) {
                showError("预算更改尚待服务器确认，请先重试同步原操作。")
                return
            }
            val snapshot = state.snapshot ?: return
            val owner = activeOwner ?: return
            val epoch = snapshot.epochId
            val ledgerId = snapshot.ledgerId
            workAccountMutation(mutate = { mutations, device ->
                check(activeOwner == owner && loadedAccountEpoch == epoch) { "账号或账本已变化，请重新操作。" }
                mutations.setBudgetEnabled(device, ledgerId, expected.preferenceRevision, enabled)
            })
        } else work(change = { it.setBudgetEnabled(expected, enabled, System.currentTimeMillis(), statisticsZone) })
    }

    private fun saveAccountBudget(
        expected: FinanceBudgetOverview,
        scope: BudgetScope,
        amount: Money,
        categories: Map<UUID, Money> = when {
            scope == BudgetScope.DEFAULT -> expected.defaultCategoryAmounts
            else -> expected.overrideCategoryAmounts
        },
    ) {
        val ledgerId = state.snapshot?.ledgerId ?: return
        val budgetAmountDraft = budgetDraft?.takeIf { it.expected == expected && it.scope == scope }
        val categoryDraft = budgetCategoryDraft?.takeIf { it.expected == expected && it.scope == scope }
        if (budgetAmountDraft?.saveQueued == true || categoryDraft?.saveQueued == true) {
            showError("预算更改已在本机队列；请通过重试同步继续原操作。")
            return
        }
        workAccountMutation(
            mutate = { mutations, device ->
                check(!mutations.hasPendingBudgetMutation(ledgerId)) { "预算更改已在本机队列；请通过重试同步继续原操作。" }
                mutations.configureBudget(device, ledgerId, expected.period, scope, expected.periodStart,
                    listOf(null to amount) + categories.toList(), expected.preferenceRevision)
            },
            onEnqueued = {
                if (budgetDraft === budgetAmountDraft) budgetDraft = null
                if (budgetCategoryDraft === categoryDraft) budgetCategoryDraft = null
            },
        )
    }

    fun editBudgetCategories(scope: BudgetScope) {
        if (activeOwner == null || state.busy) return
        val expected = state.budget ?: return
        val snapshot = state.snapshot ?: return
        val categories = snapshot.categories.filter {
            it.direction == dev.icos.app.domain.Direction.EXPENSE && !categoryExcludesBudget(it.id, snapshot.allCategories)
        }
        budgetCategoryDraft = BudgetCategoryDraft(expected, scope, categories)
    }

    fun dismissBudgetCategories() {
        val draft = budgetCategoryDraft ?: return
        if (!state.busy && !draft.saveQueued) budgetCategoryDraft = null
    }

    fun saveBudgetCategories() {
        val draft = budgetCategoryDraft ?: return
        if (draft.saveQueued) {
            showError("预算更改已在本机队列；请通过重试同步继续原操作。")
            return
        }
        try {
            val amounts = draft.amounts.mapNotNull { (id, text) ->
                if (text.isBlank()) null else id to moneyInput(text, draft.expected.currency)
            }.toMap()
            val total = if (draft.scope == BudgetScope.DEFAULT) draft.expected.defaultAmount else draft.expected.overrideAmount
            if (total == null) throw IllegalArgumentException("先设置预算总额度，再配置类别额度")
            saveAccountBudget(draft.expected, draft.scope, total, amounts)
        } catch (failure: Exception) { showError(message(failure)) }
    }

    fun editBudget(scope: BudgetScope) {
        val expected = state.budget ?: return
        if (!state.busy) {
            budgetDraft = BudgetDraft(expected, scope)
            clearError()
        }
    }

    fun dismissBudget() {
        val draft = budgetDraft ?: return
        if (!state.busy && !draft.saveQueued) budgetDraft = null
    }

    fun saveBudget() {
        val draft = budgetDraft ?: return
        try {
            val amount = moneyInput(draft.amount, draft.expected.currency)
            if (activeOwner != null) saveAccountBudget(draft.expected, draft.scope, amount)
            else work(change = { it.configureBudget(draft.expected, draft.scope, amount, System.currentTimeMillis(), statisticsZone) }, after = {
                budgetDraft = null
            })
        } catch (failure: Exception) {
            showError(message(failure))
        }
    }

    fun clearBudget(expected: FinanceBudgetOverview) {
        if (activeOwner != null) {
            val ledgerId = state.snapshot?.ledgerId ?: return
            workAccountMutation(mutate = { mutations, device ->
                check(!mutations.hasPendingBudgetMutation(ledgerId)) { "预算更改已在本机队列；请通过重试同步继续原操作。" }
                mutations.resetBudget(device, ledgerId, expected.period, expected.periodStart, clearCurrent = true,
                    expectedBudgetRevision = expected.preferenceRevision)
            })
        } else work(change = {
            it.configureBudget(expected, BudgetScope.CURRENT, Money(0, 0, expected.currency), System.currentTimeMillis(), statisticsZone)
        })
    }

    fun resetBudget(expected: FinanceBudgetOverview) {
        if (activeOwner != null) {
            val ledgerId = state.snapshot?.ledgerId ?: return
            workAccountMutation(mutate = { mutations, device ->
                check(!mutations.hasPendingBudgetMutation(ledgerId)) { "预算更改已在本机队列；请通过重试同步继续原操作。" }
                mutations.resetBudget(device, ledgerId, expected.period, expected.periodStart, clearCurrent = false,
                    expectedBudgetRevision = expected.preferenceRevision)
            })
        } else work(change = { it.resetBudgetToDefault(expected, System.currentTimeMillis(), statisticsZone) })
    }

    fun createRecurrence() {
        if (activeOwner == null || state.busy || state.syncPaused || state.accountClearPending || state.restoreBusy) return
        val templates = state.templates
        recurrenceDraft = AccountRecurrenceDraft(templates = templates, defaultZone = TimeZone.getDefault().id).also {
            it.startLocalDateTime = java.time.LocalDateTime.now().withSecond(0).withNano(0)
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.ROOT))
        }
        clearError()
    }

    fun editRecurrence(plan: CanonicalAccountRepository.RecurrenceSummary) {
        if (activeOwner == null || state.busy || plan.state !in setOf("RUNNING", "PAUSED")) return
        recurrenceDraft = AccountRecurrenceDraft(plan, state.templates, TimeZone.getDefault().id)
        clearError()
    }

    fun dismissRecurrence() { if (!state.busy) recurrenceDraft = null }

    fun saveRecurrence() {
        if (activeOwner == null || state.busy) return
        val draft = recurrenceDraft ?: return
        if (draft.mutationQueued) {
            showError("周期计划更改仍在等待服务器确认；请先重试同步原操作。")
            return
        }
        try {
            val templateId = draft.templateId ?: throw IllegalArgumentException("请选择服务器确认的账单模板")
            val template = state.templates.firstOrNull { it.id == templateId && it.invalidReason == null }
                ?: throw IllegalArgumentException("所选模板当前不可用；同步后重新选择")
            require(template.serverRevision != null && template.serverRevision > 0) { "模板尚未获得服务器确认；请先同步" }
            val original = draft.original
            if (original != null && original.templateId == templateId && original.templateRevision != template.serverRevision && !draft.confirmTemplateRevision) {
                throw IllegalArgumentException("模板内容在计划创建后已有更新。请确认将当前模板版本用于后续槽位。")
            }
            require(runCatching { ZoneId.of(draft.timezone) }.isSuccess) { "时区无效" }
            val start = java.time.LocalDateTime.parse(draft.startLocalDateTime).withSecond(0).withNano(0)
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.ROOT))
            val frequency = draft.frequency
            val interval = draft.interval.toInt()
            fun days(text: String): List<Int> = if (text.isBlank()) emptyList() else text.split(',').map { it.trim().toInt() }
            val weekDays = if (frequency == "WEEKLY") days(draft.weekDays) else emptyList()
            val monthDays = if (frequency in setOf("MONTHLY", "YEARLY")) days(draft.monthDays) else emptyList()
            val endAt = draft.endAt.trim().ifEmpty { null }
            val maxCount = draft.maxCount.trim().ifEmpty { null }?.toLong()
            if (draft.original == null) {
                val id = UUID.randomUUID()
                workAccountMutation(
                    onEnqueued = { if (recurrenceDraft === draft) recurrenceDraft = null },
                    mutate = { repository, device -> repository.createRecurrence(device, id, templateId, start, draft.timezone,
                        frequency, interval, weekDays, monthDays, endAt, maxCount, draft.state) },
                )
            } else {
                val plan = draft.original
                val slot = draft.effectiveFromSlot.toLong()
                require(slot == plan.nextDueSlot) { "规则只能从服务器当前的下一待执行槽位（${plan.nextDueSlot}）生效" }
                workAccountMutation(
                    onEnqueued = { if (recurrenceDraft === draft) recurrenceDraft = null },
                    mutate = { repository, device -> repository.updateRecurrence(device, plan, slot, templateId, draft.confirmTemplateRevision,
                        frequency, interval, weekDays, monthDays, endAt, maxCount) },
                )
            }
        } catch (failure: Exception) { showError(message(failure)) }
    }

    fun setRecurrenceState(plan: CanonicalAccountRepository.RecurrenceSummary, stateValue: String) {
        if (activeOwner == null || state.busy) return
        workAccountMutation(mutate = { repository, device -> repository.setRecurrenceState(device, plan, stateValue) })
    }

    fun requestDeleteRecurrence(plan: CanonicalAccountRepository.RecurrenceSummary) {
        if (activeOwner != null && !state.busy && plan.state !in setOf("ENDED", "DELETED")) recurrenceDeleteTarget = plan
    }

    fun dismissDeleteRecurrence() { if (!state.busy) recurrenceDeleteTarget = null }

    fun deleteRecurrence() {
        if (activeOwner == null || state.busy) return
        val plan = recurrenceDeleteTarget ?: return
        workAccountMutation(
            onEnqueued = { if (recurrenceDeleteTarget?.id == plan.id) recurrenceDeleteTarget = null },
            mutate = { repository, device -> repository.deleteRecurrence(device, plan) },
            after = { if (!state.accountMutationPending) recurrenceDeleteTarget = null },
        )
    }

    fun createSaving() {
        if (!state.busy && state.snapshot != null) {
            clearError()
            savingPlanDraft = SavingPlanDraft()
        }
    }

    fun editSaving(plan: dev.icos.app.domain.SavingPlanSummary) {
        if (!state.busy) { clearError(); savingPlanDraft = SavingPlanDraft(plan) }
    }

    fun dismissSavingPlan() { if (!state.busy && savingPlanDraft != null) { savingPlanDraft = null; clearError() } }
    fun openSaving(plan: dev.icos.app.domain.SavingPlanSummary) { selectedSavingId = plan.id }
    fun closeSaving() { selectedSavingId = null; savingEntryDraft = null }

    fun saveSavingPlan() {
        if (state.busy) return
        val draft = savingPlanDraft ?: return
        try {
            val existing = draft.original
            val input = draft.toValidatedInput()
            if (activeOwner != null) {
                val id = existing?.id ?: UUID.randomUUID()
                workAccountMutation(
                    mutate = { repo, device -> if (existing == null) repo.createSaving(device, id, input)
                        else repo.updateSaving(device, id, existing.localRevision, SavingPlanPatch(
                            name = SavingPatchValue.Set(input.name), target = SavingPatchValue.Set(input.target),
                            endDate = SavingPatchValue.Set(input.endDate), allowManualEntries = SavingPatchValue.Set(input.allowManualEntries))) },
                    onEnqueued = { if (savingPlanDraft === draft) savingPlanDraft = null },
                )
            } else work(change = { repo ->
                if (existing == null) repo.createSaving(input)
                else repo.updateSaving(existing.id, existing.localRevision, SavingPlanPatch(
                    name = SavingPatchValue.Set(input.name), target = SavingPatchValue.Set(input.target),
                    endDate = SavingPatchValue.Set(input.endDate), allowManualEntries = SavingPatchValue.Set(input.allowManualEntries)))
            }, after = { savingPlanDraft = null })
        } catch (failure: Exception) { showError(message(failure)) }
    }

    fun addSavingEntry(withdrawal: Boolean, cardIndex: Int? = null, amount: Money? = null) {
        val plan = state.snapshot?.savingPlans?.firstOrNull { it.id == selectedSavingId } ?: return
        if (!state.busy) {
            clearError()
            savingEntryDraft = SavingEntryDraft(plan, withdrawal, cardIndex, amount)
        }
    }

    fun dismissSavingEntry() { if (!state.busy && savingEntryDraft != null) { savingEntryDraft = null; clearError() } }

    fun saveSavingEntry() {
        if (state.busy) return
        val draft = savingEntryDraft ?: return
        val snapshot = state.snapshot ?: return
        try {
            val savingEntry = draft.toValidatedInput(visibleAccounts(snapshot))
            if (activeOwner != null) {
                val ledgerId = snapshot.ledgerId
                workAccountMutation(
                    mutate = { repo, device -> repo.addSavingEntry(device, draft.plan.id, draft.plan.localRevision, ledgerId, savingEntry) },
                    onEnqueued = { if (savingEntryDraft === draft) savingEntryDraft = null },
                )
            } else work(change = { it.addSavingEntry(draft.plan.id, draft.plan.localRevision, savingEntry) }, after = { savingEntryDraft = null })
        } catch (failure: Exception) { showError(message(failure)) }
    }

    fun cancelSavingEntry(entry: dev.icos.app.domain.SavingEntrySummary) {
        val plan = state.snapshot?.savingPlans?.firstOrNull { it.id == entry.planId } ?: return
        if (activeOwner != null) workAccountMutation(
            mutate = { repo, device -> repo.cancelSavingEntry(device, plan.id, plan.localRevision, entry.id, entry.localRevision) },
        ) else work(change = { it.cancelSavingEntry(plan.id, entry.id, plan.localRevision, entry.localRevision) })
    }

    fun archiveSaving(plan: dev.icos.app.domain.SavingPlanSummary, archived: Boolean) {
        if (activeOwner != null) workAccountMutation(mutate = { repo, device -> repo.archiveSaving(device, plan.id, plan.localRevision, archived) })
        else work(change = { it.archiveSaving(plan.id, plan.localRevision, archived) })
    }

    fun requestDeleteSaving(plan: dev.icos.app.domain.SavingPlanSummary) { if (!state.busy) savingDeleteTarget = plan }
    fun dismissDeleteSaving() { if (!state.busy) savingDeleteTarget = null }
    fun deleteSaving(strategy: SavingTransferStrategy) {
        val plan = savingDeleteTarget ?: return
        if (activeOwner != null) workAccountMutation(
            mutate = { repo, device -> repo.deleteSaving(device, plan.id, plan.localRevision, strategy) },
            after = { savingDeleteTarget = null; selectedSavingId = null },
        ) else work(change = { it.deleteSaving(plan.id, plan.localRevision, strategy) }, after = { savingDeleteTarget = null; selectedSavingId = null })
    }

    fun periodRange(period: ReportPeriod, atMillis: Long = System.currentTimeMillis()) =
        PeriodResolver.range(period, atMillis, statisticsZone, statisticsPreferences.weekStart,
            statisticsPreferences.monthStart, statisticsPreferences.yearStartMonth)

    fun filterStatistics(
        label: String,
        range: PeriodRange?,
        period: ReportPeriod? = null,
    ) {
        work(range = range, after = {
            statisticsRange = range
            statisticsPeriod = period
            statisticsLabel = label
        })
    }

    fun newRecordDraft(snapshot: FinanceSnapshot): RecordDraft = RecordDraft(
        snapshot,
        defaultCurrency = state.budget?.currency ?: snapshot.accounts.firstOrNull()?.currency ?: "CNY",
        allowedAccountIds = visibleAccounts(snapshot).mapTo(mutableSetOf()) { it.id },
        defaultKind = recordEfficiencySettings.defaultKind,
        defaultAccountId = recordEfficiencySettings.defaultAccountId,
    )

    fun editRecord(record: FinanceRecord? = null) {
        if (state.busy) return
        val snapshot = state.snapshot ?: return
        if (record != null && snapshot.records.none { it.id == record.id }) {
            showError("这笔账单正在写入本机，请稍后再编辑。")
            return
        }
        if (activeOwner == null && record != null && record.input.accountIds().any { !accountVisibleInCurrentLedger(it, snapshot.ledgerId) }) {
            showError("该历史账单引用了当前账本不可见的账户；保留原记录，不支持在当前账本修改。")
            return
        }
        if (activeOwner != null && record != null && !writableCanonicalRecord(snapshot, record)) {
            showError(canonicalRecordWriteBlockReason(snapshot, record))
            return
        }
        undoNewRecordId = null
        recordDraft = if (record == null) newRecordDraft(snapshot) else RecordDraft(snapshot, record, defaultCurrency = state.budget?.currency ?: snapshot.accounts.firstOrNull()?.currency ?: "CNY",
            allowedAccountIds = visibleAccounts(snapshot).mapTo(mutableSetOf()) { it.id })
        clearError()
    }

    fun copyRecord(record: FinanceRecord) {
        if (state.busy) return
        val snapshot = state.snapshot ?: return
        if (activeOwner == null && record.input.accountIds().any { !accountVisibleInCurrentLedger(it, snapshot.ledgerId) }) {
            showError("该账单引用了当前账本不可见的账户，不能在当前账本复制")
            return
        }
        if (activeOwner != null && (record.localRevision <= 0 || !record.accountMutationSupported ||
                record.input !is RecordInput.Expense && record.input !is RecordInput.Income)) {
            showError("账号账本暂不支持复制尚未确认、转账或特殊关联账单。")
            return
        }
        undoNewRecordId = null
        recordDraft = RecordDraft(snapshot, record = record, copy = true,
            defaultCurrency = snapshot.accounts.firstOrNull()?.currency ?: "CNY",
            allowedAccountIds = visibleAccounts(snapshot).mapTo(mutableSetOf()) { it.id })
        clearError()
    }

    private fun canonicalRecordHasDeletedAccount(snapshot: FinanceSnapshot, record: FinanceRecord): Boolean {
        val accountIds = when (val input = record.input) {
            is RecordInput.Expense -> input.allocations.map { it.accountId }
            is RecordInput.Income -> input.allocations.map { it.accountId }
            is RecordInput.Transfer -> listOfNotNull(input.from.accountId, input.to.accountId, input.fee?.accountId, input.bonus?.accountId)
        }
        return accountIds.any { id -> snapshot.allAccounts.none { it.id == id } }
    }

    private fun isCanonicalRecordWritable(snapshot: FinanceSnapshot, record: FinanceRecord): Boolean =
        record.localRevision > 0 && record.accountMutationSupported && !canonicalRecordHasDeletedAccount(snapshot, record) &&
            (record.input is RecordInput.Expense || record.input is RecordInput.Income || record.input is RecordInput.Transfer) &&
            !canonicalRecordHasUnsupportedRelations(snapshot, record)

    private fun canonicalRecordWriteBlockReason(snapshot: FinanceSnapshot, record: FinanceRecord): String = when {
        record.localRevision <= 0 -> "这笔账单尚未获得服务器确认，暂不能编辑或删除；请先同步完成。"
        canonicalRecordHasDeletedAccount(snapshot, record) -> "这笔历史账单使用的账户已删除，保留只读，不能编辑或冲销。"
        canonicalRecordHasUnsupportedRelations(snapshot, record) -> "这笔账单关联了报销、退款或其他业务记录，暂不能编辑或删除。"
        else -> "这类账单暂不支持编辑或删除。"
    }

    private fun canonicalRecordHasUnsupportedRelations(snapshot: FinanceSnapshot, record: FinanceRecord): Boolean {
        val id = record.id
        return !record.accountMutationSupported || record.claimState != "NONE" || snapshot.refunds.any { it.originRecordId == id } ||
            snapshot.claimSettlements.any { it.allocations.any { allocation -> allocation.originRecordId == id } } ||
            snapshot.savingEntries.any { it.linkedRecordId == id && it.cancelledAt == null } ||
            snapshot.loanContributions.any { it.eventId == id }
    }

    private fun writableCanonicalRecord(snapshot: FinanceSnapshot, record: FinanceRecord): Boolean {
        if (!isCanonicalRecordWritable(snapshot, record) || canonicalRecordHasUnsupportedRelations(snapshot, record)) return false
        return true
    }

    fun createTemplateFromRecord() {
        if (state.busy) return
        val snapshot = state.snapshot ?: return
        val source = recordDraft?.originalRecord ?: return
        if (activeOwner != null && source.input is RecordInput.Expense && source.input.recordCurrencyAmounts != null) {
            showError("FX支出分摊不能保存到账单模板。")
            return
        }
        if (activeOwner != null && (source.input !is RecordInput.Expense && source.input !is RecordInput.Income && source.input !is RecordInput.Transfer ||
                canonicalRecordHasUnsupportedRelations(snapshot, source))) {
            showError("该账单含暂不支持的业务关联，不能另存为模板。")
            return
        }
        templateDraft =
            RecordTemplateDraft(
                snapshot,
                initial =
                    FinanceRecordTemplate(
                        UUID.randomUUID(),
                        "账单模板",
                        source.input,
                        source.categoryId,
                        source.occurredAtMillis,
                        source.timezone,
                        source.remark,
                        tagIds = source.tagIds,
                    ),
                allowedAccountIds = visibleAccounts(snapshot).mapTo(mutableSetOf()) { it.id },
            )
        templatesOpen = false
        recordDraft = null
        clearError()
    }

    fun openTemplates() {
        if (!state.busy) templatesOpen = true
    }

    fun dismissTemplates() {
        if (!state.busy) templatesOpen = false
    }

    fun editTemplate(template: FinanceRecordTemplate? = null) {
        if (state.busy) return
        val snapshot = state.snapshot ?: return
        if (activeOwner != null && template?.let { it.serverRevision == null || it.serverRevision <= 0 || it.invalidReason != null } == true) {
            showError("模板尚未获得服务器确认或模板已失效；同步后再编辑。")
            return
        }
        if (template?.input?.accountIds()?.any { !accountVisibleInCurrentLedger(it, snapshot.ledgerId) } == true) {
            showError("模板引用了当前账本不可见的账户，不能在此编辑")
            return
        }
        templatesOpen = false
        templateDraft = RecordTemplateDraft(snapshot, template, allowedAccountIds = visibleAccounts(snapshot).mapTo(mutableSetOf()) { it.id })
        clearError()
    }

    fun useTemplate(template: FinanceRecordTemplate) {
        if (state.busy) return
        val snapshot = state.snapshot ?: return
        if (template.input.accountIds().any { !accountVisibleInCurrentLedger(it, snapshot.ledgerId) }) {
            showError("模板引用了当前账本不可见的账户，不能使用")
            return
        }
        if (template.invalidReason != null) {
            showError("模板失效：${template.invalidReason}")
            return
        }
        if (activeOwner != null && (template.serverRevision == null || template.serverRevision <= 0)) {
            showError("模板尚未获得服务器确认；同步完成后再使用。")
            return
        }
        templatesOpen = false
        recordDraft = RecordDraft(snapshot, sourceTemplate = template, allowedAccountIds = visibleAccounts(snapshot).mapTo(mutableSetOf()) { it.id })
        clearError()
    }

    fun dismissTemplate() {
        if (!state.busy) {
            templateDraft = null
            templatesOpen = true
        }
    }

    fun saveRecordTemplate() {
        if (state.busy) return
        val draft = templateDraft ?: return
        if (activeOwner != null && draft.mutationQueued) {
            showError("模板更改仍在等待服务器确认，请先重试同步；暂不能创建另一条模板操作。")
            return
        }
        val snapshot = state.snapshot ?: return
        try {
            val record = draft.record
            val value = FinanceRecordTemplate(
                draft.id, draft.name, record.input(snapshot),
                if (record.kind == EntryKind.TRANSFER) null else record.categoryId,
                record.occurredAtMillis, record.timezone, record.remark,
                tagIds = record.tagIds.toList(), serverRevision = draft.original?.serverRevision,
            )
            if (activeOwner != null) {
                workAccountMutation(
                    onEnqueued = { if (templateDraft === draft) { templateDraft = null; templatesOpen = true } },
                    mutate = { repository, device -> repository.saveRecordTemplate(device, value, snapshot.ledgerId) },
                )
            } else {
                work(change = { it.saveRecordTemplate(value) }, after = { templateDraft = null; templatesOpen = true })
            }
        } catch (failure: Exception) {
            showError(message(failure))
        }
    }

    fun requestDeleteTemplate(template: FinanceRecordTemplate) {
        if (state.busy || deleteTemplateQueued) return
        if (activeOwner != null && (template.serverRevision == null || template.serverRevision <= 0)) {
            showError("模板尚未获得服务器确认；同步完成后再删除。")
            return
        }
        deleteTemplateTarget = template
        deleteTemplateQueued = false
    }

    fun dismissDeleteTemplate() {
        if (!state.busy && !deleteTemplateQueued) { deleteTemplateTarget = null; deleteTemplateQueued = false }
    }

    fun deleteTemplate() {
        if (state.busy || deleteTemplateQueued) return
        val target = deleteTemplateTarget ?: return
        if (activeOwner != null) {
            val revision = target.serverRevision ?: return
            workAccountMutation(
                onEnqueued = { if (deleteTemplateTarget?.id == target.id) deleteTemplateTarget = null },
                mutate = { repository, device -> repository.deleteRecordTemplate(device, target.id, revision) },
                after = {
                    deleteTemplateQueued = state.accountMutationPending
                    if (!state.accountMutationPending) deleteTemplateTarget = null
                },
            )
        } else {
            work(change = { it.deleteRecordTemplate(target.id) }, after = { deleteTemplateTarget = null })
        }
    }

    fun addAccount() {
        if (!state.busy && state.snapshot != null) {
            accountDraft = AccountDraft(defaultCurrency = state.budget?.currency ?: "CNY")
            clearError()
        }
    }

    fun editAccount(account: FinanceAccount) {
        if (state.busy || state.accountMutationPending || state.syncPaused ||
            state.accountClearPending || state.restoreBusy || account.localRevision <= 0) return
        val snapshot = state.snapshot ?: return
        if (snapshot.allAccounts.none { it.id == account.id && it.localRevision == account.localRevision }) return
        accountDraft = AccountDraft(account).also { draft ->
            draft.groupRevision = snapshot.accountGroups.firstOrNull { it.id == account.groupId }?.localRevision
        }
        clearError()
    }

    fun dismissRecord() {
        if (!state.busy) recordDraft = null
    }

    fun dismissAccount() {
        if (!state.busy) accountDraft = null
    }

    fun dismissDelete() {
        if (!state.busy) deleteTarget = null
    }

    fun clearError() {
        state = state.copy(error = null)
    }

    fun showError(message: String) {
        state = state.copy(error = message)
    }

    fun requestDelete(record: FinanceRecord) {
        if (state.busy) return
        if (activeOwner != null) {
            val snapshot = state.snapshot ?: return
            if (!writableCanonicalRecord(snapshot, record)) {
                showError(canonicalRecordWriteBlockReason(snapshot, record))
                return
            }
        }
        deleteTarget = record
        clearError()
    }

    fun saveRecord() {
        if (state.busy) return
        val draft = recordDraft ?: return
        if (activeOwner != null && draft.saveQueued) {
            showError("这笔更改仍在等待服务器确认，请先点击“重试同步”；暂不能创建另一条操作。")
            return
        }
        val snapshot = state.snapshot ?: return
        if (activeOwner != null && draft.sourceTemplate?.let { it.serverRevision == null || it.serverRevision <= 0 || it.invalidReason != null } == true) {
            showError("模板尚未确认或已失效；同步后选择有效模板再使用。")
            return
        }
        if (draft.sourceTemplate?.input?.accountIds()?.any { !accountVisibleInCurrentLedger(it, snapshot.ledgerId) } == true) {
            showError("模板引用了当前账本不可见的账户，不能保存账单")
            return
        }
        try {
            val input = draft.input(snapshot)
            if (activeOwner == null && input is RecordInput.Expense && input.recordCurrencyAmounts != null && input.allocations.any { it.amount.currency != input.gross.currency }) {
                showError("Guest 本地账本尚不支持跨币支出分摊；请选择同币种账户。")
                return
            }
            if (draft.id == null) {
                enqueueNewRecord(snapshot, draft, input)
                return
            }
            if (activeOwner != null) {
                if (input !is RecordInput.Expense && input !is RecordInput.Income && input !is RecordInput.Transfer) {
                    showError("账号账本目前仅支持普通支出/收入及同币种转账。")
                    return
                }
                draft.originalRecord?.let { original ->
                    if (!writableCanonicalRecord(snapshot, original)) {
                        showError(canonicalRecordWriteBlockReason(snapshot, original))
                        return
                    }
                }
                recordDraft?.sourceTemplate?.let { source ->
                    if (activeOwner != null && (source.serverRevision == null || source.serverRevision <= 0)) {
                        showError("模板尚未获得服务器确认；同步完成后再保存账单。")
                        return
                    }
                    if (activeOwner != null && source.invalidReason != null) {
                        showError("模板已失效：${source.invalidReason}")
                        return
                    }
                }
                val id = draft.id ?: UUID.randomUUID()
                val ledgerId = snapshot.ledgerId
                val categoryId = if (input is RecordInput.Transfer) null else draft.categoryId
                if (input !is RecordInput.Transfer && categoryId == null) {
                    showError("账号账单需要有效分类，请先选择分类。")
                    return
                }
                workAccountMutation(
                    mutate = { repository, device ->
                        val template = draft.sourceTemplate
                        repository.saveRecord(device, id, draft.expectedRevision, ledgerId, input,
                            draft.occurredAtMillis, draft.timezone, draft.remark, categoryId, draft.tagIds.toList(),
                            sourceTemplateId = template?.id, sourceTemplateRevision = template?.serverRevision)
                    },
                    onEnqueued = { if (recordDraft === draft) recordDraft = null },
                )
                return
            }
            val at = draft.occurredAtMillis
            val timezone = draft.timezone
            val remark = draft.remark
            val category = if (draft.kind == EntryKind.TRANSFER) null else draft.categoryId
            undoNewRecordId = null
            work(
                change = {
                    it.saveRecord(
                        id = draft.id, input = input, occurredAtMillis = at, timezone = timezone, remark = remark,
                        categoryId = category, expectedLocalRevision = draft.expectedRevision, tagIds = draft.tagIds.toList(),
                    )
                },
                after = { if (recordDraft === draft) recordDraft = null },
            )
        } catch (failure: Exception) { showError(message(failure)) }
    }

    private fun enqueueNewRecord(snapshot: FinanceSnapshot, draft: RecordDraft, input: RecordInput) {
        check(!closed && !state.restoreBusy && !restoreBarrier.get() && !state.accountClearPending) { "账本正在恢复或清账，请稍后重试" }
        require(draft.remark.length <= 2000 && '\u0000' !in draft.remark) { "备注不能超过两千个字符" }
        ZoneId.of(draft.timezone)
        if (activeOwner != null && input is RecordInput.Transfer) {
            require(input.from.amount.currency == input.to.amount.currency) { "账号转账需要相同币种" }
        }
        val category = if (input is RecordInput.Transfer) null else draft.categoryId
        require(input is RecordInput.Transfer || category != null) { "请选择有效分类" }
        val session = if (activeOwner == null) null else authenticatedSessionIdentity
            ?: throw FinanceValidationException("账号会话身份尚未就绪")
        val recoveredId = recoveredRecordDraft?.takeIf { it.second === draft }?.first
        val record = FinanceRecord(recoveredId ?: UUID.randomUUID(), input, draft.occurredAtMillis, draft.timezone,
            draft.remark, category, 0, tagIds = draft.tagIds.toList(), accountMutationSupported = false)
        val save = PendingRecordSave(snapshot.ownerId, snapshot.epochId, snapshot.ledgerId, record)
        val request = RecordSaveRequest(save, session, generation, draft.sourceTemplate?.id, draft.sourceTemplate?.serverRevision)
        stageRecordSave(request)
        recoveredRecordDraft = null
        recordDraft = null
        undoNewRecordId = null
        dispatchRecordSave(request)
    }

    private fun stageRecordSave(request: RecordSaveRequest) {
        val previous = recordSaveRequests
        state = state.copy(error = null, clearPreview = null, accountClearPreview = null)
        recordSaveRequests = previous.filterNot { it.save.record.id == request.save.record.id } + request
        try {
            // Compute once before closing the form; Compose reuses the cached display projection.
            projectedState.value
        } catch (failure: Exception) {
            recordSaveRequests = previous
            throw failure
        }
    }

    fun retryRecordSave(id: UUID) {
        if (closed || state.busy || state.restoreBusy || restoreBarrier.get() || state.accountClearPending) return
        val previous = recordSaveRequests.firstOrNull { it.save.record.id == id && it.error != null && visibleRecordSave(it) } ?: return
        val snapshot = state.snapshot ?: return
        if (snapshot.epochId != previous.save.epochId || snapshot.ledgerId != previous.save.ledgerId) {
            showError("账本已变化，请先返回原账本重试，或恢复草稿后重新确认。")
            return
        }
        val session = if (activeOwner == null) null else authenticatedSessionIdentity ?: return
        val retry = previous.copy(session = session, generation = generation, error = null)
        try { stageRecordSave(retry) } catch (failure: Exception) { showError(message(failure)); return }
        dispatchRecordSave(retry)
    }

    fun restoreFailedRecordSave(id: UUID) {
        if (closed || state.busy || recordDraft != null) return
        val request = recordSaveRequests.firstOrNull { it.save.record.id == id && it.error != null && visibleRecordSave(it) } ?: return
        val snapshot = state.snapshot ?: return
        recordDraft = RecordDraft(snapshot, request.save.record, copy = true,
            allowedAccountIds = visibleAccounts(snapshot).mapTo(mutableSetOf()) { it.id })
        recoveredRecordDraft = id to recordDraft!!
        // Keep the failed item until a retry actually commits; closing its recovered form never loses it.
    }

    private fun reconcileRecordSaves() {
        val snapshot = state.snapshot ?: return
        if (recordSaveRequests.none { it.durable && it.save.ownerId == snapshot.ownerId && it.save.epochId == snapshot.epochId }) return
        val ids = snapshot.records.mapTo(mutableSetOf()) { it.id }
        recordSaveRequests = recordSaveRequests.filterNot {
            it.durable && it.save.ownerId == snapshot.ownerId && it.save.epochId == snapshot.epochId && it.save.record.id in ids
        }
    }

    private fun dispatchRecordSave(request: RecordSaveRequest) {
        val save = request.save
        val record = save.record
        if (request.session != null) accountMutationVersion.incrementAndGet()
        val worker = if (request.session == null) executor else accountLocalExecutor
        worker.execute {
            var committed = false
            val result = runCatching {
                if (request.session == null) {
                    val repo = repository ?: LocalFinanceRepository.openGuest(getApplication()).also { repository = it }
                    val current = repo.snapshot()
                    check(current.ownerId == save.ownerId && current.epochId == save.epochId && current.ledgerId == save.ledgerId) { "原本地账本已切换，请恢复草稿后重新确认" }
                    val existing = current.records.firstOrNull { it.id == record.id }
                    if (existing == null) repo.saveRecord(null, record.input, record.occurredAtMillis, record.timezone,
                        record.remark, record.categoryId, tagIds = record.tagIds, createId = record.id)
                    else check(existing.input == record.input && existing.remark == record.remark && existing.categoryId == record.categoryId &&
                        existing.occurredAtMillis == record.occurredAtMillis && existing.timezone == record.timezone && existing.tagIds.toSet() == record.tagIds.toSet())
                    committed = true
                } else {
                    withAccountMutation(save.ownerId, save.epochId, request.session) { mutations, device ->
                        mutations.saveRecord(device, record.id, null, save.ledgerId, record.input, record.occurredAtMillis,
                            record.timezone, record.remark, record.categoryId, record.tagIds, request.templateId, request.templateRevision)
                        committed = true
                    }
                }
            }
            main.post {
                if (closed) return@post
                recordSaveRequests = recordSaveRequests.map {
                    if (it.save.record.id != record.id) it else it.copy(durable = committed,
                        error = if (committed) null else "账单未写入本机：${message(result.exceptionOrNull()!!)}")
                }
            }
            if (committed && ownsWork(request.session?.owner, request.generation)) {
                if (request.session == null) {
                    runWork(null, { reconcileRecordSaves(); undoNewRecordId = record.id },
                        statisticsPeriod?.let { periodRange(it) } ?: statisticsRange, null, request.generation, background = true)
                } else {
                    main.post {
                        if (ownsWork(save.ownerId, request.generation)) {
                            main.removeCallbacks(recordRefresh)
                            main.postDelayed(recordRefresh, 100)
                            requestAutomaticSync()
                        }
                    }
                }
            }
        }
    }

    private val recordRefresh = Runnable {
        val owner = activeOwner
        if (!closed && owner != null) refreshAccountState(owner, generation, background = true)
    }

    fun saveRecordLocation(record: FinanceRecord, latitude: String, longitude: String, label: String) {
        if (activeOwner != null) {
            showError("账号账单位置当前为只读；位置只保存在本机 Guest 账本。")
            return
        }
        workLocation(record) { snapshot ->
            val eventId = record.currentEventId?.toString() ?: throw IllegalStateException("账单当前事件不可用")
            dev.icos.app.data.local.RecordLocationAttachmentStore.save(
                getApplication(),
                dev.icos.app.data.local.RecordLocationAttachment(
                    snapshot.ownerId.toString(), snapshot.epochId.toString(), record.id.toString(), eventId,
                    record.localRevision, latitude, longitude, label,
                ),
            )
        }
    }

    fun deleteRecordLocation(record: FinanceRecord) {
        if (activeOwner != null) {
            showError("账号账单位置当前为只读。")
            return
        }
        workLocation(record) { snapshot ->
            dev.icos.app.data.local.RecordLocationAttachmentStore.delete(
                getApplication(), snapshot.ownerId.toString(), snapshot.epochId.toString(), record.id.toString(),
            )
        }
    }

    fun retryRecordLocationCleanup() {
        if (activeOwner != null || state.busy) return
        val recordId = state.pendingLocationCleanupId ?: return
        val snapshot = state.snapshot ?: return
        cleanupDeletedRecordLocation(recordId, snapshot.ownerId, snapshot.epochId)
    }

    private fun workLocation(record: FinanceRecord, action: (FinanceSnapshot) -> Unit) {
        if (state.busy || activeOwner != null || closed) return
        val generationAtEnqueue = generation
        val expectedOwner = state.snapshot?.ownerId ?: return
        val expectedEpoch = state.snapshot?.epochId ?: return
        state = state.copy(busy = true, error = null)
        executor.execute {
            val result = runCatching {
                check(activeOwner == null && generation == generationAtEnqueue) { "账本已切换，位置操作已取消" }
                val repo = repository ?: LocalFinanceRepository.openGuest(getApplication<Application>()).also { repository = it }
                val snapshot = repo.snapshot()
                check(snapshot.ownerId == expectedOwner && snapshot.epochId == expectedEpoch) { "账本已切换，位置操作已取消" }
                val current = snapshot.records.singleOrNull {
                    it.id == record.id && it.localRevision == record.localRevision && it.currentEventId == record.currentEventId
                } ?: throw IllegalStateException("账单已变化或已删除，位置操作已取消")
                check(current.currentEventId != null) { "账单当前事件不可用" }
                action(snapshot)
            }
            main.post {
                if (!closed && generation == generationAtEnqueue && activeOwner == null) {
                    state = state.copy(
                        busy = false,
                        error = result.exceptionOrNull()?.message?.let { "位置操作失败：$it" },
                        locationOperationStatus = result.exceptionOrNull()?.message?.let { "位置操作失败：$it；账单财务记录未更改。" },
                    )
                    result.onSuccess {
                        locationRevision = Math.addExact(locationRevision, 1)
                        state = state.copy(locationRevision = locationRevision, locationOperationStatus = "位置已更新；该操作不更改账单金额或备注。")
                    }
                    result.onFailure { showError("位置操作失败：${it.message ?: "未知错误"}；账单财务记录未更改。") }
                }
            }
        }
    }

    fun quickCreateTag(name: String) {
        if (state.busy) return
        val snapshot = state.snapshot ?: return
        val group = snapshot.tagGroups.singleOrNull { it.isDefault } ?: return
        val order = snapshot.tags.maxOfOrNull { it.sortOrder } ?: -1L
        if (activeOwner == null && order == Long.MAX_VALUE) return showError("标签排序值超出范围")
        saveTag(null, group.id, name, null, if (activeOwner == null) order + 1 else 0,
            expectedGroupRevision = group.localRevision)
    }

    fun saveTag(id: UUID?, groupId: UUID, name: String, color: String?, sortOrder: Long, revision: Long? = null,
        expectedGroupRevision: Long? = null, expectedSourceGroupRevision: Long? = null, onEnqueued: () -> Unit = {}) {
        val ownerAtRequest = activeOwner
        if (ownerAtRequest != null) {
            if (state.busy || state.accountMutationPending || state.syncPaused) return
            val snapshot = state.snapshot ?: return
            val group = snapshot.tagGroups.singleOrNull { it.id == groupId && it.localRevision == expectedGroupRevision }
                ?: return showError("标签分组已变化，请重新打开表单")
            val tag = id?.let { selected -> snapshot.tags.singleOrNull { it.id == selected && it.localRevision == revision } }
            if (id != null && tag == null) return showError("标签已变化，请重新编辑")
            val sourceGroup = tag?.let { original -> snapshot.tagGroups.singleOrNull {
                it.id == original.groupId && it.localRevision == expectedSourceGroupRevision
            } }
            if (tag != null && sourceGroup == null) return showError("原标签分组已变化，请重新打开表单")
            workAccountMutation(mutate = { repository, device ->
                check(activeOwner == ownerAtRequest) { "账号已切换" }
                if (tag == null) repository.accountTagCommands().createTag(device, group, name, color)
                else repository.accountTagCommands().updateTag(device, tag, requireNotNull(sourceGroup), group, name, color)
            }, onEnqueued = onEnqueued)
        } else work(change = { it.saveTag(id, groupId, name, color, sortOrder, revision) }, after = onEnqueued)
    }

    fun saveTagGroup(id: UUID?, name: String, sortOrder: Long, revision: Long? = null, onEnqueued: () -> Unit = {}) {
        val ownerAtRequest = activeOwner
        if (ownerAtRequest != null) {
            if (state.busy || state.accountMutationPending || state.syncPaused) return
            val group = id?.let { selected -> state.snapshot?.tagGroups?.singleOrNull { it.id == selected && it.localRevision == revision } }
            if (id != null && group == null) return showError("标签分组已变化，请重新编辑")
            workAccountMutation(mutate = { repository, device ->
                check(activeOwner == ownerAtRequest) { "账号已切换" }
                if (group == null) repository.accountTagCommands().createGroup(device, name)
                else repository.accountTagCommands().renameGroup(device, group, name)
            }, onEnqueued = onEnqueued)
        } else work(change = { it.saveTagGroup(id, name, sortOrder, revision) }, after = onEnqueued)
    }

    fun reorderAccountTagGroups(groups: List<FinanceTagGroup>) {
        val ownerAtRequest = activeOwner ?: return
        if (state.busy || state.accountMutationPending || state.syncPaused) return
        workAccountMutation(mutate = { repository, device ->
            check(activeOwner == ownerAtRequest) { "账号已切换" }
            repository.accountTagCommands().reorderGroups(device, groups)
        })
    }

    fun reorderAccountTags(groupId: UUID, tags: List<FinanceTag>) {
        val ownerAtRequest = activeOwner ?: return
        if (state.busy || state.accountMutationPending || state.syncPaused) return
        workAccountMutation(mutate = { repository, device ->
            check(activeOwner == ownerAtRequest) { "账号已切换" }
            repository.accountTagCommands().reorderTags(device, groupId, tags)
        })
    }

    fun archiveTag(tag: FinanceTag) {
        val ownerAtRequest = activeOwner
        if (ownerAtRequest != null) {
            if (state.busy || state.accountMutationPending || state.syncPaused) return
            workAccountMutation(mutate = { repository, device ->
                check(activeOwner == ownerAtRequest) { "账号已切换" }
                repository.accountTagCommands().setTagArchived(device, tag, tag.archivedAt == null)
            })
        } else work(change = { it.setTagArchived(tag.id, tag.archivedAt == null, tag.localRevision) })
    }

    private fun <T> readAccountMetadataPreview(read: (AccountMutationRepository) -> T, onReady: (T) -> Unit) {
        val owner = activeOwner ?: return
        val snapshot = state.snapshot ?: return
        if (closed || state.busy || state.accountMutationPending || state.syncPaused || state.accountClearPending || restoreBarrier.get()) return
        val expectedGeneration = generation
        val epoch = snapshot.epochId
        val session = authenticatedSessionIdentity ?: return showError("账号会话身份尚未就绪")
        state = state.copy(busy = true, error = null)
        executor.execute {
            val result = runCatching {
                check(ownsWork(owner, expectedGeneration) && !syncPauseFlag.get() && !restoreBarrier.get()) { "账号状态已变化" }
                DeviceSessions.requireSameIdentity(session, auth.snapshot())
                check(EncryptedDatasetStore.activeAccountEpoch(getApplication(), owner) == epoch && loadedAccountEpoch == epoch) { "账本已变化，请重新打开" }
                requireAccountDatasetFiles(owner, epoch)
                EncryptedDatasetStore.openAccount(getApplication(), owner, epoch).use { database ->
                    val identity = database.identity().read() ?: throw StorageOpenException()
                    check(identity.ownerId == owner.toString() && identity.epochId == epoch.toString()) { "账本身份不匹配" }
                    read(AccountMutationRepository(database))
                }.also {
                    DeviceSessions.requireSameIdentity(session, auth.snapshot())
                    check(ownsWork(owner, expectedGeneration) && !restoreBarrier.get() && !syncPauseFlag.get() &&
                        EncryptedDatasetStore.activeAccountEpoch(getApplication(), owner) == epoch) { "账号状态已变化" }
                }
            }
            main.post {
                if (ownsWork(owner, expectedGeneration)) {
                    state = state.copy(busy = false)
                    if (authenticatedSessionIdentity != session || state.snapshot?.epochId != epoch || loadedAccountEpoch != epoch ||
                        state.syncPaused || state.accountClearPending || restoreBarrier.get()) {
                        showError("账号状态已变化，请重新打开确认")
                    } else result.onSuccess(onReady).onFailure { showError(message(it)) }
                }
            }
        }
    }

    fun previewAccountTagDelete(tag: FinanceTag, onReady: (dev.icos.app.data.local.AccountTagCommands.TagDeletePreview) -> Unit) {
        readAccountMetadataPreview({ it.accountTagCommands().previewDeleteTag(tag) }, onReady)
    }

    fun previewAccountTagGroupDelete(group: FinanceTagGroup,
        onReady: (dev.icos.app.data.local.AccountTagCommands.TagGroupDeletePreview) -> Unit) {
        val snapshot = state.snapshot ?: return
        val target = snapshot.tagGroups.singleOrNull { it.isDefault } ?: return showError("默认标签分组不可用，请先同步")
        if (group.isDefault || group.id == target.id) return showError("默认标签分组不能删除")
        readAccountMetadataPreview({ repository ->
            repository.accountTagCommands().previewDeleteTagGroup(group.id, target.id).also {
                require(it.source.localRevision == group.localRevision && it.target.localRevision == target.localRevision) {
                    "标签分组已变化，请重新确认"
                }
            }
        }, onReady)
    }

    fun deleteAccountTag(preview: dev.icos.app.data.local.AccountTagCommands.TagDeletePreview, onEnqueued: () -> Unit = {}) {
        val ownerAtRequest = activeOwner ?: return
        if (state.busy || state.accountMutationPending || state.syncPaused) return
        workAccountMutation(mutate = { repository, device ->
            check(activeOwner == ownerAtRequest) { "账号已切换" }
            repository.accountTagCommands().deleteTag(device, preview)
        }, onEnqueued = onEnqueued)
    }

    fun deleteAccountTagGroup(preview: dev.icos.app.data.local.AccountTagCommands.TagGroupDeletePreview, onEnqueued: () -> Unit = {}) {
        val ownerAtRequest = activeOwner ?: return
        if (state.busy || state.accountMutationPending || state.syncPaused) return
        workAccountMutation(mutate = { repository, device ->
            check(activeOwner == ownerAtRequest) { "账号已切换" }
            repository.accountTagCommands().deleteTagGroup(device, preview)
        }, onEnqueued = onEnqueued)
    }

    fun deleteTag(tag: FinanceTag) {
        if (activeOwner != null) return showError("账号标签删除尚未开放")
        val revisions = state.snapshot?.records.orEmpty()
            .filter { tag.id in it.tagIds }
            .associate { it.id to it.localRevision }
        work(change = { it.deleteTag(tag.id, tag.localRevision, revisions) })
    }

    fun deleteTagGroup(group: FinanceTagGroup) {
        if (activeOwner != null) return showError("账号标签分组删除尚未开放")
        val snapshot = state.snapshot ?: return
        if (group.isDefault) return
        val childRevisions = snapshot.tags.filter { it.groupId == group.id }.associate { it.id to it.localRevision }
        val target = snapshot.tagGroups.singleOrNull { it.isDefault } ?: return
        work(change = { it.moveTagGroup(group.id, target.id, group.localRevision, childRevisions) })
    }

    fun saveAccount() {
        if (state.busy) return
        val draft = accountDraft ?: return
        if (activeOwner != null && draft.mutationQueued) {
            showError("账户创建仍在等待服务器确认，请先点击页面上的刷新同步；暂不能创建另一条操作。")
            return
        }
        val original = draft.original
        if (original != null) {
            if (state.accountMutationPending || state.syncPaused) return
            val groupId = draft.groupId ?: return showError("请选择账户分组")
            val groupRevision = draft.groupRevision ?: return showError("分组版本不可用，请重新打开编辑。")
            val name = draft.name.trim()
            val ownerAtRequest = activeOwner
            if (ownerAtRequest == null) {
                work(
                    change = { it.updateAccountDetails(original.id, original.localRevision, name, groupId, groupRevision) },
                    after = { accountDraft = null },
                )
            } else {
                workAccountMutation(mutate = { repository, device ->
                    check(activeOwner == ownerAtRequest) { "账号已切换" }
                    repository.updateAccountDetails(device, original.id, original.localRevision, name, groupId, groupRevision)
                }, onEnqueued = { if (accountDraft === draft) accountDraft = null })
            }
            return
        }
        try {
            val opening = moneyInput(draft.opening, draft.currency, signed = true)
            val name = draft.name.trim()
            val at = draft.openingDateMillis
            val groupId = draft.groupId
            if (activeOwner != null) {
                val id = draft.createId
                workAccountMutation(mutate = { repository, device ->
                    repository.createAccount(device, id, name, opening.currency, opening, at, groupId, draft.accountType)
                }, onEnqueued = { if (accountDraft === draft) accountDraft = null })
                return
            }
            work(
                change = { it.createAccount(name, opening.currency, opening, at, groupId, draft.accountType) },
                after = { accountDraft = null },
            )
        } catch (failure: Exception) {
            showError(message(failure))
        }
    }

    fun createLoan(input: LoanPlanInput, disbursement: dev.icos.app.data.local.LoanDisbursement?, opId: UUID = UUID.randomUUID(), after: () -> Unit = {}) {
        if (state.busy) return
        val ownerAtRequest = activeOwner
        if (ownerAtRequest != null) {
            workAccountMutation(mutate = { repository, device ->
                if (activeOwner != ownerAtRequest) throw IllegalStateException("账号已切换")
                repository.createLoan(device, input, disbursement, opId)
            }, onEnqueued = after)
            return
        }
        work(change = { it.createLoan(input, disbursement) }, after = after)
    }

    fun previewLoanRate(plan: dev.icos.app.data.local.FinanceLoanPlan, term: Int, numerator: Long, denominator: Long) {
        loanPreviewRate = numerator to denominator
        if (activeOwner != null) {
            previewCanonicalLoan(plan) { terms ->
                LoanCalculator.changeRate(terms, term, numerator, denominator, RepaymentMethod.valueOf(plan.repaymentType), plan.localRevision)
            }
            return
        }
        previewLoan { it.previewLoanRate(plan.id, plan.localRevision, term, numerator, denominator) }
    }

    fun previewLoanTerm(
        plan: dev.icos.app.data.local.FinanceLoanPlan,
        term: Int,
        principal: Money,
        interest: Money,
        policy: LoanFuturePolicy,
    ) {
        if (activeOwner != null) {
            previewCanonicalLoan(plan) { terms ->
                LoanCalculator.adjustTerm(terms, term, principal, interest, policy, plan.annualRateNumerator,
                    plan.annualRateDenominator, plan.localRevision)
            }
            return
        }
        previewLoan { it.previewLoanTerm(plan.id, plan.localRevision, term, principal, interest, policy) }
    }

    private fun previewCanonicalLoan(plan: dev.icos.app.data.local.FinanceLoanPlan, calculate: (List<LoanTerm>) -> LoanPreview) {
        if (closed || state.busy || loanPreviewBusy) return
        val ownerAtRequest = activeOwner ?: return
        val expectedGeneration = generation
        val generation = ++loanPreviewGeneration
        loanPreviewBusy = true
        loanPreview = null
        loanPreviewError = null
        executor.execute {
            val result = runCatching {
                check(ownsWork(ownerAtRequest, expectedGeneration)) { "账号已切换" }
                require(LoanCalculator.isValidLoanDate(plan.firstDueDate, fourDigitYearOnly = true))
                calculate(plan.terms.sortedBy { it.termIndex })
            }
            main.post {
                if (ownsWork(ownerAtRequest, expectedGeneration) && generation == loanPreviewGeneration) {
                    loanPreview = result.getOrNull()
                    loanPreviewError = result.exceptionOrNull()?.let(::message)
                    loanPreviewBusy = false
                }
            }
        }
    }

    private fun previewLoan(action: (LocalFinanceRepository) -> LoanPreview) {
        if (activeOwner != null) {
            showError("服务器账号账本当前只读；贷款预览将在 typed producer 阶段开放")
            return
        }
        if (closed || state.busy || loanPreviewBusy) return
        val generation = ++loanPreviewGeneration
        loanPreviewBusy = true
        loanPreview = null
        loanPreviewError = null
        executor.execute {
            try {
                val repo = repository ?: LocalFinanceRepository.openGuest(getApplication<Application>()).also { repository = it }
                val preview = action(repo)
                main.post { if (!closed && generation == loanPreviewGeneration) { loanPreview = preview; loanPreviewBusy = false } }
            } catch (failure: Exception) {
                main.post { if (!closed && generation == loanPreviewGeneration) { loanPreviewError = message(failure); loanPreviewBusy = false } }
            }
        }
    }

    fun clearLoanPreview() {
        loanPreviewGeneration++
        loanPreview = null
        loanPreviewError = null
        loanPreviewBusy = false
        loanPreviewRate = null
    }

    fun commitLoanRate(
        plan: dev.icos.app.data.local.FinanceLoanPlan,
        preview: LoanPreview,
        numerator: Long,
        denominator: Long,
        after: () -> Unit = {},
    ) {
        if (state.busy || preview.expectedVersion != plan.localRevision) return
        val ownerAtRequest = activeOwner
        if (ownerAtRequest != null) {
            workAccountMutation(mutate = { repository, device ->
                if (activeOwner != ownerAtRequest) throw IllegalStateException("账号已切换")
                repository.adjustLoanRate(device, plan.id, preview.expectedVersion, preview.affectedFrom, numerator, denominator)
            }, onEnqueued = { clearLoanPreview(); after() })
            return
        }
        work(change = { it.adjustLoanRate(plan.id, preview.expectedVersion, preview.affectedFrom, numerator, denominator) },
            after = { clearLoanPreview(); after() })
    }

    fun commitLoanRate(plan: dev.icos.app.data.local.FinanceLoanPlan, preview: LoanPreview, after: () -> Unit = {}) {
        val rate = loanPreviewRate ?: return
        commitLoanRate(plan, preview, rate.first, rate.second, after)
    }

    fun commitLoanTerm(
        plan: dev.icos.app.data.local.FinanceLoanPlan,
        preview: LoanPreview,
        policy: LoanFuturePolicy,
        after: () -> Unit = {},
    ) {
        if (state.busy || preview.expectedVersion != plan.localRevision) return
        val changed = preview.terms.firstOrNull { it.termIndex == preview.affectedFrom } ?: return
        val ownerAtRequest = activeOwner
        if (ownerAtRequest != null) {
            workAccountMutation(mutate = { repository, device ->
                if (activeOwner != ownerAtRequest) throw IllegalStateException("账号已切换")
                repository.adjustLoanTerm(device, plan.id, preview.expectedVersion, preview.affectedFrom,
                    changed.principal, changed.interest, policy)
            }, onEnqueued = { clearLoanPreview(); after() })
            return
        }
        work(change = { it.adjustLoanTerm(plan.id, preview.expectedVersion, preview.affectedFrom,
            changed.principal, changed.interest, policy) }, after = { clearLoanPreview(); after() })
    }

    fun postLoanPayment(
        plan: dev.icos.app.data.local.FinanceLoanPlan,
        termIndex: Int,
        accountId: UUID,
        atMillis: Long,
        amount: Money,
        opId: UUID,
        after: () -> Unit = {},
    ) {
        if (state.busy) return
        val ownerAtRequest = activeOwner
        if (ownerAtRequest != null) {
            workAccountMutation(mutate = { repository, device ->
                if (activeOwner != ownerAtRequest) throw IllegalStateException("账号已切换")
                repository.postLoanPayment(device, plan.id, plan.localRevision, termIndex, accountId, atMillis, amount, opId)
            }, onEnqueued = after)
            return
        }
        work(change = { it.postLoanPayment(plan.id, plan.localRevision, termIndex, accountId, atMillis, amount, opId) }, after = after)
    }

    fun archiveLoan(plan: dev.icos.app.data.local.FinanceLoanPlan, archived: Boolean) {
        if (activeOwner != null) {
            showError("账号贷款归档尚未提供，计划和历史仍保留。")
            return
        }
        work(change = { it.setLoanArchived(plan.id, plan.localRevision, archived) })
    }

    fun deleteLoan(plan: dev.icos.app.data.local.FinanceLoanPlan, strategy: LoanDeleteStrategy, after: () -> Unit = {}) {
        if (state.busy) return
        val ownerAtRequest = activeOwner
        if (ownerAtRequest != null) {
            if (strategy != LoanDeleteStrategy.KEEP_HISTORY) {
                showError("账号贷款删除目前只支持保留历史。")
                return
            }
            workAccountMutation(mutate = { repository, device ->
                if (activeOwner != ownerAtRequest) throw IllegalStateException("账号已切换")
                repository.deleteLoan(device, plan.id, plan.localRevision)
            }, onEnqueued = after)
            return
        }
        work(change = { it.deleteLoan(plan.id, plan.localRevision, strategy) }, after = after)
    }

    fun setLoanPreferences(expectedRevision: Long, principalAsIncome: Boolean, repaymentAsExpense: Boolean, interestAsExpense: Boolean) {
        val prefs = state.snapshot?.businessPreferences ?: return
        if (state.busy || prefs.localRevision != expectedRevision) return
        val ownerAtRequest = activeOwner
        if (ownerAtRequest != null) {
            workAccountMutation(mutate = { repository, device ->
                if (activeOwner != ownerAtRequest) throw IllegalStateException("账号已切换")
                repository.setLoanPreferences(device, expectedRevision, principalAsIncome, repaymentAsExpense, interestAsExpense)
            })
            return
        }
        work(change = { it.saveBusinessPreferences(expectedRevision, prefs.refundExcessAsIncome, prefs.showRefundsInRecordList,
            principalAsIncome, repaymentAsExpense, interestAsExpense) })
    }

    fun configureCredit(
        account: dev.icos.app.data.local.FinanceAccount, expectedAccountRevision: Long, limit: Money,
        statementDay: Int, includeStatementDay: Boolean, rule: String, repaymentValue: Int, zone: String,
        onEnqueued: () -> Unit = {},
    ) {
        if (state.busy) return
        val ownerAtRequest = activeOwner
        if (ownerAtRequest != null) {
            workAccountMutation(mutate = { repository, device ->
                check(activeOwner == ownerAtRequest) { "账号已切换" }
                repository.configureCredit(device, account.id, expectedAccountRevision, limit, statementDay, includeStatementDay, rule, repaymentValue, zone)
            }, onEnqueued = onEnqueued)
        } else work(change = { it.configureCredit(account.id, expectedAccountRevision, limit, statementDay, includeStatementDay, rule, repaymentValue, zone) }, after = onEnqueued)
    }

    fun adjustCreditCapacity(
        account: dev.icos.app.data.local.FinanceAccount, expectedAccountRevision: Long, delta: Money, reason: String,
        onEnqueued: () -> Unit = {},
    ) {
        if (state.busy) return
        val ownerAtRequest = activeOwner
        if (ownerAtRequest != null) {
            workAccountMutation(mutate = { repository, device ->
                check(activeOwner == ownerAtRequest) { "账号已切换" }
                repository.adjustCreditCapacity(device, account.id, expectedAccountRevision, delta, reason)
            }, onEnqueued = onEnqueued)
        } else work(change = { it.adjustCreditCapacity(account.id, expectedAccountRevision, delta, reason) }, after = onEnqueued)
    }

    fun createInstallment(
        account: dev.icos.app.data.local.FinanceAccount, expectedAccountRevision: Long, ledger: FinanceLedger,
        principal: Money, interest: Money, terms: Int, firstDate: String, zone: String, posting: String,
        interestMode: String, remainder: String, onEnqueued: () -> Unit = {},
    ) {
        if (state.busy) return
        val ownerAtRequest = activeOwner
        if (ownerAtRequest != null) {
            workAccountMutation(mutate = { repository, device ->
                check(activeOwner == ownerAtRequest) { "账号已切换" }
                repository.installmentCommands().create(device,
                    account.id, expectedAccountRevision, ledger.id, ledger.localRevision, principal, interest, terms,
                    firstDate, zone, posting, interestMode, remainder)
            }, onEnqueued = onEnqueued)
        } else work(change = { it.createInstallment(account.id, expectedAccountRevision, principal, interest, terms,
            firstDate, zone, posting, interestMode, remainder) }, after = onEnqueued)
    }

    fun updateInstallment(plan: dev.icos.app.data.local.FinanceInstallmentPlan, interest: Money, terms: Int, firstDate: String, onEnqueued: () -> Unit = {}) {
        if (state.busy) return
        val ownerAtRequest = activeOwner
        if (ownerAtRequest != null) {
            workAccountMutation(mutate = { repository, device ->
                check(activeOwner == ownerAtRequest) { "账号已切换" }
                repository.installmentCommands()
                    .update(device, plan.id, plan.localRevision, interest, terms, firstDate)
            }, onEnqueued = onEnqueued)
        } else work(change = { it.updateInstallment(plan.id, plan.localRevision, interest, terms, firstDate) }, after = onEnqueued)
    }

    fun deleteInstallment(plan: dev.icos.app.data.local.FinanceInstallmentPlan) {
        if (state.busy) return
        val ownerAtRequest = activeOwner
        if (ownerAtRequest != null) workAccountMutation(mutate = { repository, device ->
            check(activeOwner == ownerAtRequest) { "账号已切换" }
            repository.installmentCommands().delete(device, plan.id, plan.localRevision)
        }) else work(change = { it.deleteInstallment(plan.id, plan.localRevision) })
    }

    fun postInstallmentTerm(term: dev.icos.app.data.local.FinanceInstallmentTerm, plan: dev.icos.app.data.local.FinanceInstallmentPlan) {
        if (state.busy || activeOwner != null) return
        work(change = { it.postInstallmentTerm(term.id, plan.localRevision) })
    }

    fun payCredit(
        account: FinanceAccount, expectedAccountRevision: Long, asset: FinanceAccount, ledger: FinanceLedger,
        amount: Money, timezone: String, allocations: Map<UUID, Money>, onEnqueued: () -> Unit = {},
    ) {
        if (state.busy) return
        val ownerAtRequest = activeOwner
        if (ownerAtRequest != null) {
            if (allocations.isNotEmpty()) {
                showError("请在还款确认后单独分配期次")
                return
            }
            workAccountMutation(mutate = { repository, device ->
                check(activeOwner == ownerAtRequest) { "账号已切换" }
                repository.creditPaymentCommands().create(device, account.id, expectedAccountRevision,
                    asset.id, asset.localRevision, ledger.id, ledger.localRevision, amount, timezone)
            }, onEnqueued = onEnqueued)
        } else work(change = { it.allocateCreditPayment(account.id, expectedAccountRevision, asset.id, amount,
            System.currentTimeMillis(), allocations) }, after = onEnqueued)
    }

    fun allocateCreditPayment(
        account: FinanceAccount, expectedAccountRevision: Long, paymentEventId: UUID,
        allocations: Map<UUID, Money>, onEnqueued: () -> Unit = {},
    ) {
        if (state.busy) return
        val ownerAtRequest = activeOwner ?: return
        workAccountMutation(mutate = { repository, device ->
            check(activeOwner == ownerAtRequest) { "账号已切换" }
            repository.creditPaymentCommands().allocate(device, account.id, expectedAccountRevision, paymentEventId, allocations)
        }, onEnqueued = onEnqueued)
    }

    fun saveRelatedPersonProfile(account: FinanceAccount, direction: String, relation: String, limitText: String) {
        if (state.busy || activeOwner != null) return
        try {
            val limit = dev.icos.app.ui.moneyInput(limitText, account.currency)
            work(change = { it.saveRelatedPersonProfile(account.id, direction, relation, limit) })
        } catch (failure: Exception) { showError(message(failure)) }
    }

    fun clearRelatedPersonProfile(account: FinanceAccount) {
        if (state.busy || activeOwner != null) return
        work(change = { it.clearRelatedPersonProfile(account.id) })
    }

    fun saveAccountGroup(id: UUID?, name: String, order: Long, revision: Long? = null, onEnqueued: () -> Unit = {}) {
        val ownerAtRequest = activeOwner
        if (ownerAtRequest != null) {
            if (state.busy || state.accountMutationPending || state.syncPaused) return
            workAccountMutation(mutate = { repository, device ->
                check(activeOwner == ownerAtRequest) { "账号已切换" }
                repository.accountGroupCommands().save(device, id, name, order, revision)
            }, onEnqueued = onEnqueued)
        } else work(change = { it.saveAccountGroup(id, name, order, revision) }, after = onEnqueued)
    }

    fun reorderAccountGroups(groups: List<dev.icos.app.data.local.FinanceAccountGroup>) {
        val ownerAtRequest = activeOwner
        if (ownerAtRequest != null) {
            if (state.busy || state.accountMutationPending || state.syncPaused) return
            workAccountMutation(mutate = { repository, device ->
                check(activeOwner == ownerAtRequest) { "账号已切换" }
                repository.accountOrderCommands().reorderGroups(device, groups)
            })
        } else work(change = { it.reorderAccountGroups(groups.map { group -> group.id }, groups.associate { it.id to it.localRevision }) })
    }

    fun reorderAccounts(groupId: UUID, accounts: List<dev.icos.app.data.local.FinanceAccount>) {
        val ownerAtRequest = activeOwner
        if (ownerAtRequest != null) {
            if (state.busy || state.accountMutationPending || state.syncPaused) return
            workAccountMutation(mutate = { repository, device ->
                check(activeOwner == ownerAtRequest) { "账号已切换" }
                repository.accountOrderCommands().reorderAccounts(device, groupId, accounts)
            })
        } else work(change = { it.reorderAccounts(groupId, accounts.map { account -> account.id }, accounts.associate { it.id to it.localRevision }) })
    }

    fun moveAccount(account: dev.icos.app.data.local.FinanceAccount, targetGroupId: UUID) {
        work(change = { it.moveAccount(account.id, targetGroupId, account.localRevision) })
    }

    fun deleteAccountGroup(
        group: dev.icos.app.data.local.FinanceAccountGroup,
        target: dev.icos.app.data.local.FinanceAccountGroup? = null,
        accountRevisions: Map<UUID, Long>? = null,
        onEnqueued: () -> Unit = {},
    ) {
        val snapshot = state.snapshot ?: return
        if (group.isDefault) return
        val ownerAtRequest = activeOwner
        if (ownerAtRequest != null) {
            if (state.busy || state.accountMutationPending || state.syncPaused) return
            val defaultGroup = target ?: return showError("请重新打开分组删除确认")
            val members = accountRevisions ?: return showError("分组成员版本缺失，请重新确认")
            workAccountMutation(mutate = { repository, device ->
                check(activeOwner == ownerAtRequest) { "账号已切换" }
                repository.accountGroupCommands().delete(device, group.id, group.localRevision,
                    defaultGroup.id, defaultGroup.localRevision, members)
            }, onEnqueued = onEnqueued)
        } else {
            val members = snapshot.accounts.filter { it.groupId == group.id }
            work(change = { it.deleteAccountGroup(group.id, group.localRevision,
                members.associate { account -> account.id to account.localRevision }) }, after = onEnqueued)
        }
    }

    fun previewAccountDelete(
        account: FinanceAccount,
        onReady: (dev.icos.app.data.local.AccountDeletionCommands.AccountDeletePreview) -> Unit,
    ) {
        readAccountMetadataPreview({ it.accountDeletionCommands().preview(account) }, onReady)
    }

    fun deleteAccountKeepingRecords(
        preview: dev.icos.app.data.local.AccountDeletionCommands.AccountDeletePreview,
        confirmation: String,
        onEnqueued: () -> Unit = {},
    ) {
        val ownerAtRequest = activeOwner ?: return
        if (state.busy || state.accountMutationPending || state.syncPaused) return
        workAccountMutation(mutate = { repository, device ->
            check(activeOwner == ownerAtRequest) { "账号已切换" }
            repository.accountDeletionCommands().delete(device, preview, confirmation)
        }, onEnqueued = onEnqueued)
    }

    fun requestAccountArchive(account: dev.icos.app.data.local.FinanceAccount) {
        if (state.busy || activeOwner != null && (state.accountMutationPending || state.syncPaused ||
                state.accountClearPending || state.restoreBusy || account.localRevision <= 0)) return
        accountArchiveTarget = account
    }

    fun dismissAccountArchive() {
        if (!state.busy) accountArchiveTarget = null
    }

    fun confirmAccountArchive() {
        val account = accountArchiveTarget ?: return
        if (state.busy) return
        val ownerAtRequest = activeOwner
        if (ownerAtRequest != null) {
            if (state.accountMutationPending || state.syncPaused || account.localRevision <= 0) return
            workAccountMutation(mutate = { repository, device ->
                check(activeOwner == ownerAtRequest) { "账号已切换" }
                repository.setAccountArchived(device, account.id, account.localRevision, account.archivedAt == null)
            }, onEnqueued = { if (accountArchiveTarget?.id == account.id) accountArchiveTarget = null })
        } else work(
            change = { it.setAccountArchived(account.id, account.localRevision, account.archivedAt == null) },
            after = { accountArchiveTarget = null },
        )
    }

    fun setAccountSelectable(account: dev.icos.app.data.local.FinanceAccount, selectable: Boolean) {
        if (state.busy) return
        val ownerAtRequest = activeOwner
        if (ownerAtRequest != null) {
            if (state.accountMutationPending || state.syncPaused || account.localRevision <= 0) return
            workAccountMutation(mutate = { repository, device ->
                check(activeOwner == ownerAtRequest) { "账号已切换" }
                repository.setAccountOptions(device, account.id, account.localRevision, selectable = selectable)
            })
        } else work(change = { it.setAccountSelectable(account.id, account.localRevision, selectable) })
    }

    fun setAccountIncludedInAssets(account: dev.icos.app.data.local.FinanceAccount, included: Boolean) {
        if (state.busy) return
        val ownerAtRequest = activeOwner
        if (ownerAtRequest != null) {
            if (state.accountMutationPending || state.syncPaused || account.localRevision <= 0) return
            workAccountMutation(mutate = { repository, device ->
                check(activeOwner == ownerAtRequest) { "账号已切换" }
                repository.setAccountOptions(device, account.id, account.localRevision, includeInAssets = included)
            })
        } else work(change = { it.setAccountIncludedInAssets(account.id, account.localRevision, included) })
    }

    fun dismissUndoNewRecord(id: UUID) {
        if (undoNewRecordId == id) undoNewRecordId = null
    }

    fun undoNewRecord(id: UUID) {
        if (state.busy || undoNewRecordId != id) return
        val ownerAtRequest = activeOwner
        val generationAtRequest = generation
        if (ownerAtRequest != null) {
            val snapshot = state.snapshot ?: return
            val record = snapshot.records.firstOrNull { it.id == id } ?: return
            if (record.localRevision <= 0 || !writableCanonicalRecord(snapshot, record)) {
                undoNewRecordId = null
                showError("账号账本中的这笔账单尚未确认或存在特殊关联，不能撤销删除。")
                return
            }
            undoNewRecordId = null
            workAccountMutation(
                mutate = { repository, device ->
                    if (!ownsWork(ownerAtRequest, generationAtRequest) || activeOwner != ownerAtRequest) {
                        throw IllegalStateException("账号已切换")
                    }
                    repository.deleteRecord(device, id, record.localRevision)
                },
            )
            return
        }
        undoNewRecordId = null
        work(change = { it.deleteRecord(id, expectedLocalRevision = 1) })
    }

    fun toggleClaim(record: FinanceRecord) {
        if (state.busy || record.input !is RecordInput.Expense || record.claimState !in setOf("NONE", "PENDING")) return
        val next = if (record.claimState == "PENDING") "NONE" else "PENDING"
        val owner = activeOwner
        if (owner != null) {
            if (state.accountMutationPending || record.localRevision <= 0) {
                showError("账单尚未确认或另有更改待确认；请先同步后重试。")
                return
            }
            val expectedGeneration = generation
            workAccountMutation(mutate = { repository, device ->
                if (!ownsWork(owner, expectedGeneration) || activeOwner != owner) throw IllegalStateException("账号已切换")
                repository.markClaim(device, record.id, record.localRevision, next)
            })
            return
        }
        work(change = { it.markClaim(record.id, next, record.localRevision) })
    }

    fun settleClaims(
        allocations: List<FinanceClaimAllocation>,
        accountId: UUID,
        incomeCategoryId: UUID?,
        remainderAction: RemainderAction,
        settledAtMillis: Long,
        timezone: String,
        onCommitted: () -> Unit,
        onEnqueued: () -> Unit = {},
    ) {
        val snapshot = state.snapshot ?: return
        val owner = activeOwner
        if (owner != null) {
            if (state.accountMutationPending || allocations.any { it.expectedLocalRevision <= 0 }) {
                showError("报销账单或其他更改尚未获得服务器确认；请先同步后重试。")
                return
            }
            val expectedGeneration = generation
            workAccountMutation(mutate = { repository, device ->
                if (!ownsWork(owner, expectedGeneration) || activeOwner != owner) throw IllegalStateException("账号已切换")
                repository.settleClaims(device, UUID.randomUUID(), snapshot.ledgerId, allocations, accountId,
                    incomeCategoryId, remainderAction, settledAtMillis, timezone)
            }, after = onCommitted, onEnqueued = onEnqueued)
            return
        }
        if (!accountVisibleInCurrentLedger(accountId, snapshot.ledgerId)) {
            showError("当前账本不可使用所选账户")
            return
        }
        work(
            change = { it.settleClaims(allocations, accountId, incomeCategoryId, remainderAction, settledAtMillis, timezone) },
            after = onCommitted,
        )
    }

    fun cancelClaimSettlement(
        id: UUID,
        expectedLocalRevision: Long,
        reason: String,
        onCommitted: () -> Unit,
        onEnqueued: () -> Unit = {},
    ) {
        val owner = activeOwner
        if (owner != null) {
            if (state.accountMutationPending || expectedLocalRevision <= 0) {
                showError("报销批次或其他更改尚未获得服务器确认；请先同步后重试。")
                return
            }
            val expectedGeneration = generation
            workAccountMutation(mutate = { repository, device ->
                if (!ownsWork(owner, expectedGeneration) || activeOwner != owner) throw IllegalStateException("账号已切换")
                repository.cancelClaimSettlement(device, id, expectedLocalRevision, reason)
            }, after = onCommitted, onEnqueued = onEnqueued)
            return
        }
        work(change = { it.cancelClaimSettlement(id, expectedLocalRevision, reason) }, after = onCommitted)
    }

    fun createRefund(
        record: FinanceRecord,
        amount: Money,
        accountId: UUID,
        refundAtMillis: Long,
        timezone: String,
        onCommitted: () -> Unit,
        onEnqueued: () -> Unit = {},
    ) {
        val snapshot = state.snapshot ?: return
        val owner = activeOwner
        if (owner != null) {
            if (state.accountMutationPending || record.localRevision <= 0) {
                showError("原账单或其他更改尚未获得服务器确认；请先同步后重试。")
                return
            }
            val expectedGeneration = generation
            workAccountMutation(mutate = { repository, device ->
                if (!ownsWork(owner, expectedGeneration) || activeOwner != owner) throw IllegalStateException("账号已切换")
                repository.createRefund(device, UUID.randomUUID(), record.id, record.localRevision, amount, accountId,
                    refundAtMillis, timezone, snapshot.ledgerId)
            }, after = onCommitted, onEnqueued = onEnqueued)
            return
        }
        if (!accountVisibleInCurrentLedger(accountId, snapshot.ledgerId)) {
            showError("当前账本不可使用所选账户")
            return
        }
        work(
            change = { it.createRefund(record.id, record.localRevision, amount, accountId, refundAtMillis, timezone) },
            after = onCommitted,
        )
    }

    fun cancelRefund(
        id: UUID,
        expectedLocalRevision: Long,
        expectedOriginRevision: Long,
        reason: String,
        onCommitted: () -> Unit,
        onEnqueued: () -> Unit = {},
    ) {
        val owner = activeOwner
        if (owner != null) {
            if (state.accountMutationPending || expectedLocalRevision <= 0 || expectedOriginRevision <= 0) {
                showError("退款或原账单尚未获得服务器确认；请先同步后重试。")
                return
            }
            val expectedGeneration = generation
            workAccountMutation(mutate = { repository, device ->
                if (!ownsWork(owner, expectedGeneration) || activeOwner != owner) throw IllegalStateException("账号已切换")
                repository.cancelRefund(device, id, expectedLocalRevision, expectedOriginRevision, reason)
            }, after = onCommitted, onEnqueued = onEnqueued)
            return
        }
        work(
            change = { it.cancelRefund(id, expectedLocalRevision, expectedOriginRevision, reason) },
            after = onCommitted,
        )
    }

    fun deleteRecord() {
        val target = deleteTarget ?: return
        if (activeOwner != null) {
            if (target.localRevision <= 0) {
                showError("这笔账单尚未获得服务器确认，暂不能删除；请先同步完成。")
                return
            }
            workAccountMutation(
                mutate = { repository, device -> repository.deleteRecord(device, target.id, target.localRevision) },
                after = { deleteTarget = null; recordDraft = null },
            )
            return
        }
        val guestSnapshot = state.snapshot
        work(
            change = { it.deleteRecord(target.id, target.localRevision) },
            after = {
                deleteTarget = null
                recordDraft = null
                if (guestSnapshot != null) cleanupDeletedRecordLocation(target.id, guestSnapshot.ownerId, guestSnapshot.epochId)
            },
        )
    }

    private fun cleanupDeletedRecordLocation(recordId: UUID, ownerId: UUID, epochId: UUID) {
        val generationAtEnqueue = generation
        executor.execute {
            val result = runCatching {
                check(activeOwner == null && generation == generationAtEnqueue) { "账本已切换；稍后可重试位置清理" }
                dev.icos.app.data.local.RecordLocationAttachmentStore.delete(
                    getApplication(), ownerId.toString(), epochId.toString(), recordId.toString(),
                )
            }
            main.post {
                if (!closed && generation == generationAtEnqueue && activeOwner == null && state.snapshot?.ownerId == ownerId && state.snapshot?.epochId == epochId) {
                    if (result.isSuccess) state = state.copy(pendingLocationCleanupId = null, locationOperationStatus = "")
                    else state = state.copy(pendingLocationCleanupId = recordId, locationOperationStatus = "账单已删除；本机位置文件清理失败，可在账单列表页重试。")
                }
            }
        }
    }

    fun previewClearRecords() {
        if (closed || state.busy || activeOwner != null) return
        if (savingRecordCount > 0) { showError("账单正在写入本机，完成后再预览清账。"); return }
        val current = state.snapshot ?: return
        val ledgerId = current.ledgerId
        val ownerId = current.ownerId
        val epochId = current.epochId
        val generationAtEnqueue = generation
        selectedClearLedgerId = ledgerId
        selectedClearOwnerId = ownerId
        selectedClearEpochId = epochId
        selectedClearGeneration = generationAtEnqueue
        state = state.copy(busy = true, clearPreview = null, error = null)
        executor.execute {
            try {
                check(ownsWork(null, generationAtEnqueue) && state.snapshot?.let { it.ownerId == ownerId && it.epochId == epochId && it.ledgerId == ledgerId } == true) {
                    "账本已切换，请重新预览清账范围"
                }
                val repo = repository ?: LocalFinanceRepository.openGuest(getApplication<Application>()).also { repository = it }
                repo.selectLedger(ledgerId)
                val live = repo.snapshot()
                check(live.ownerId == ownerId && live.epochId == epochId && live.ledgerId == ledgerId && ownsWork(null, generationAtEnqueue)) {
                    "账本已切换，请重新预览清账范围"
                }
                val preview = repo.clearRecordsPreview()
                main.post {
                    if (ownsWork(null, generationAtEnqueue) && state.snapshot?.let { it.ownerId == ownerId && it.epochId == epochId && it.ledgerId == ledgerId } == true) {
                        state = state.copy(busy = false, clearPreview = preview)
                    }
                }
            } catch (failure: Exception) {
                main.post {
                    if (ownsWork(null, generationAtEnqueue) && state.snapshot?.let { it.ownerId == ownerId && it.epochId == epochId && it.ledgerId == ledgerId } == true) {
                        state = state.copy(busy = false, clearPreview = null, error = message(failure))
                    }
                }
            }
        }
    }

    fun clearGuestRecords(onCommitted: () -> Unit) {
        if (activeOwner != null) return
        val preview = state.clearPreview ?: return
        if (preview.recordCount == 0 || preview.supportedCount != preview.recordCount || preview.rejectedReasons.isNotEmpty()) {
            showError("当前账本含有关联或不支持的账单；为避免部分清账，已拒绝整批操作。详情见清账预览。")
            return
        }
        if (state.busy) return
        val ledgerId = selectedClearLedgerId ?: return
        val ownerId = selectedClearOwnerId ?: return
        val epochId = selectedClearEpochId ?: return
        val previewGeneration = selectedClearGeneration ?: return
        val snapshotAtRequest = state.snapshot ?: return
        if (generation != previewGeneration || activeOwner != null || snapshotAtRequest.ownerId != ownerId || snapshotAtRequest.epochId != epochId || snapshotAtRequest.ledgerId != ledgerId) {
            state = state.copy(clearPreview = null)
            showError("账本已切换，请重新预览清账范围")
            return
        }
        state = state.copy(busy = true, error = null)
        val generationAtEnqueue = generation
        executor.execute {
            try {
                check(ownsWork(null, generationAtEnqueue) && generationAtEnqueue == previewGeneration && activeOwner == null) {
                    "账本已切换，清账操作已取消"
                }
                val repo = repository ?: LocalFinanceRepository.openGuest(getApplication<Application>()).also { repository = it }
                repo.selectLedger(ledgerId)
                val live = repo.snapshot()
                check(live.ownerId == ownerId && live.epochId == epochId && live.ledgerId == ledgerId &&
                    ownsWork(null, generationAtEnqueue) && activeOwner == null) { "账本已切换，清账操作已取消" }
                val snapshot = repo.clearRecords(preview.recordRevisions)
                check(snapshot.ownerId == ownerId && snapshot.epochId == epochId && snapshot.ledgerId == ledgerId &&
                    ownsWork(null, generationAtEnqueue) && activeOwner == null) { "账本已切换，清账结果已隔离，请刷新当前账本" }
                val budget = repo.budgetOverview(budgetPeriod, System.currentTimeMillis(), statisticsZone)
                val templates = repo.recordTemplates()
                main.post { if (ownsWork(null, generationAtEnqueue) && state.snapshot?.let { it.ownerId == ownerId && it.epochId == epochId && it.ledgerId == ledgerId } == true) {
                    state = state.copy(snapshot = snapshot, budget = budget, templates = templates, busy = false, clearPreview = null,
                        guestAccountLedgerVisibility = loadGuestAccountLedgerVisibility(snapshot.ownerId, snapshot.accounts, snapshot.ledgers),
                        locationRevision = Math.addExact(state.locationRevision, 1), locationOperationStatus = "清账完成；账单本地位置附加信息不再随财务记录显示。")
                    selectedClearLedgerId = null
                    selectedClearOwnerId = null
                    selectedClearEpochId = null
                    selectedClearGeneration = null
                    onCommitted()
                } }
            } catch (failure: Exception) {
                main.post { if (ownsWork(null, generationAtEnqueue) && activeOwner == null) state = state.copy(busy = false, clearPreview = null, error = message(failure)) }
            }
        }
    }

    fun importSharedAccountCategories(
        snapshot: FinanceSnapshot, categories: List<dev.icos.app.data.remote.SharedCategoryConfig>,
        onEnqueued: (Map<UUID, UUID>) -> Unit,
    ) {
        if (activeOwner != snapshot.ownerId || state.snapshot?.epochId != snapshot.epochId || state.snapshot?.ledgerId != snapshot.ledgerId) {
            showError("账号账本已切换，请重新预览")
            return
        }
        var mapping: Map<UUID, UUID> = emptyMap()
        workAccountMutation(mutate = { repository, device ->
            mapping = repository.accountCategoryCommands().importSharedCategories(device, snapshot, categories)
        }, onEnqueued = { onEnqueued(mapping) })
    }

    fun deleteRecords(
        records: List<FinanceRecord>,
        onCommitted: () -> Unit,
    ) {
        if (state.busy) return
        val revisions = records.associate { it.id to it.localRevision }
        if (activeOwner != null) {
            if (records.size != revisions.size || revisions.size !in 1..500 || revisions.values.any { it <= 0 }) {
                showError("请选择 1–500 笔不重复且已同步的账单。")
                return
            }
            undoNewRecordId = null
            workAccountMutation(mutate = { repository, device -> repository.deleteRecords(device, revisions) }, after = onCommitted)
            return
        }
        undoNewRecordId = null
        work(change = { it.deleteRecords(revisions) }, after = onCommitted)
    }

    private fun accountFileCoordinator(owner: UUID, epoch: UUID, generationAtRequest: Long, identity: DeviceIdentity) =
        AccountFileImportCoordinator(
            getApplication(), auth, owner, epoch, identity, accountImportNetworkGate,
        ) {
            check(accountFileWorkCurrent(owner, generationAtRequest, epoch, identity) && !cancelAccountImport.get() && !cancelAccountSync.get()) {
                "账号身份或同步状态已变化"
            }
        }

    fun previewAccountFileImport(uri: android.net.Uri, expectedLedger: UUID, zone: String, permissionAlreadyGranted: Boolean, token: String, stillCurrent: () -> Boolean, onComplete: (Result<Pair<AndroidFileIoAdapter.Staged, AccountFileImportCoordinator.Preview>>) -> Unit) {
        val owner = activeOwner
        if (owner == null || closed || state.busy) { main.post { onComplete(Result.failure(IllegalStateException("账号账本当前不可导入"))) }; return }
        val expectedGeneration = generation
        val expectedIdentityKey = accountFileIdentity
        val expectedSession = authenticatedSessionIdentity ?: run {
            main.post { onComplete(Result.failure(IllegalStateException("账号会话身份尚未就绪"))) }; return
        }
        val expectedEpoch = runCatching { EncryptedDatasetStore.activeAccountEpoch(getApplication(), owner) }.getOrNull()
        if (expectedEpoch == null) { main.post { onComplete(Result.failure(StorageOpenException())) }; return }
        state = state.copy(busy = true, error = null)
        val adapter = AndroidFileIoAdapter(getApplication())
        executor.execute {
            var staged: AndroidFileIoAdapter.Staged? = null
            val result = runCatching {
                check(stillCurrent() && accountFileWorkCurrent(owner, expectedGeneration, expectedEpoch, expectedSession) && accountFileIdentity == expectedIdentityKey) { "账号身份或同步状态已变化" }
                check(syncedAccountEpoch == expectedEpoch) { "账号账本需先同步" }
                staged = adapter.stage(uri, token, permissionAlreadyGranted = permissionAlreadyGranted)
                val copied = staged!!
                check(stillCurrent()) { "应用已锁定或会话已变化，导入已取消" }
                val database = EncryptedDatasetStore.openAccount(getApplication(), owner, expectedEpoch)
                val value = try {
                    val identity = database.identity().read() ?: throw StorageOpenException()
                    check(identity.serverCursor != null && identity.snapshotSchemaVersion >= 5) { "服务端尚未提供 DATA5 导入支持" }
                    check(identity.ownerId == owner.toString() && identity.epochId == expectedEpoch.toString()) { "DATASET_IDENTITY_MISMATCH" }
                    val snapshot = CanonicalAccountRepository.read(database, expectedLedger).snapshot
                    check(snapshot.ledgerId == expectedLedger) { "当前账本已切换，导入已取消" }
                    check(stillCurrent()) { "应用已锁定或会话已变化，导入已取消" }
                    AccountFileImportCoordinator(getApplication(), auth, owner, expectedEpoch)
                        .preview(copied.file, java.time.ZoneId.of(zone), snapshot, expectedGeneration)
                } finally { database.close() }
                DeviceSessions.requireSameIdentity(expectedSession, auth.snapshot())
                check(stillCurrent() && accountFileWorkCurrent(owner, expectedGeneration, expectedEpoch, expectedSession) && accountFileIdentity == expectedIdentityKey) { "账号身份或同步状态已变化" }
                copied to value
            }
            val currentAfterWork = accountFileIdentity == expectedIdentityKey && stillCurrent() && accountFileWorkCurrent(owner, expectedGeneration, expectedEpoch, expectedSession)
            if (!currentAfterWork || result.isFailure) staged?.let { runCatching { adapter.remove(it) } }
            main.post {
                // Re-check on the publishing thread: a worker's stillCurrent is stale the moment it is computed.
                val live = accountFileIdentity == expectedIdentityKey && stillCurrent() && accountFileWorkCurrent(owner, expectedGeneration, expectedEpoch, expectedSession)
                if (ownsWork(owner, expectedGeneration)) state = state.copy(busy = false)
                if (live && result.isSuccess) {
                    fileIoProgress = "账号文件预览已准备；尚未向服务端写入。"
                    onComplete(result)
                } else {
                    if (!live) staged?.let { runCatching { adapter.remove(it) } }
                    onComplete(Result.failure(result.exceptionOrNull() ?: IllegalStateException("账号会话或账本已变化，预览已取消")))
                }
            }
        }
    }

    fun stageAccountFileImport(preview: AccountFileImportCoordinator.Preview, staged: AndroidFileIoAdapter.Staged, accountBindings: Map<String, UUID>, categoryBindings: Map<String, UUID>, tagBindings: Map<String, UUID>, onComplete: (Result<UUID>) -> Unit) {
        val owner = activeOwner ?: return
        if (closed || state.busy || syncPauseFlag.get()) { main.post { onComplete(Result.failure(IllegalStateException("账号同步暂停或账本正在处理"))) }; return }
        val expectedGeneration = generation
        val expectedIdentityKey = accountFileIdentity
        val expectedSession = authenticatedSessionIdentity ?: run {
            main.post { onComplete(Result.failure(IllegalStateException("账号会话身份尚未就绪"))) }; return
        }
        val expectedEpoch = preview.epochId
        if (preview.ownerId != owner || preview.sessionIdentity != expectedSession || preview.generation != expectedGeneration ||
            preview.epochId != runCatching { EncryptedDatasetStore.activeAccountEpoch(getApplication(), owner) }.getOrNull() ||
            !expectedIdentityKey.startsWith("$owner:$expectedEpoch:")) {
            AndroidFileIoAdapter(getApplication()).remove(staged)
            main.post { onComplete(Result.failure(IllegalStateException("账号预览身份已失效，请重新选择文件"))) }
            return
        }
        cancelAccountImport.set(false)
        cancelAccountSync.set(false)
        val ledger = fileIo.ledger ?: selectedAccountLedgerId
        val zone = fileImportZone
        state = state.copy(busy = true, error = null)
        executor.execute {
            val result = runCatching {
                check(accountFileWorkCurrent(owner, expectedGeneration, expectedEpoch, expectedSession) && accountFileIdentity == expectedIdentityKey) { "账号身份或同步状态已变化" }
                check(syncedAccountEpoch == expectedEpoch) { "账号账本需先同步" }
                val database = EncryptedDatasetStore.openAccount(getApplication(), owner, expectedEpoch)
                try {
                    val localIdentity = database.identity().read() ?: throw StorageOpenException()
                    check(SyncControlPolicy.localMutationReady(expectedEpoch, localIdentity.ownerId, localIdentity.epochId,
                        localIdentity.serverCursor != null && localIdentity.lastAppliedSeq >= 0, owner)) { "Account dataset must be initialized before importing" }
                    val view = CanonicalAccountRepository.read(database, ledger)
                    check(view.selectedLedgerId == ledger) { "当前账本已切换，导入已取消" }
                    accountFileCoordinator(owner, expectedEpoch, expectedGeneration, expectedSession).stage(
                        preview, java.time.ZoneId.of(zone), view.snapshot, accountBindings, categoryBindings, tagBindings,
                        cancelled = { cancelAccountImport.get() || cancelAccountSync.get() || !accountFileWorkCurrent(owner, expectedGeneration, expectedEpoch, expectedSession) || accountFileIdentity != expectedIdentityKey },
                        progress = { publishFileIoProgress(it) }, expectedGeneration = expectedGeneration,
                    )
                    DeviceSessions.requireSameIdentity(expectedSession, auth.snapshot())
                    check(accountFileWorkCurrent(owner, expectedGeneration, expectedEpoch, expectedSession) && accountFileIdentity == expectedIdentityKey) { "账号身份或同步状态已变化" }
                    staged.id
                } finally { database.close() }
            }
            main.post {
                if (accountFileIdentity == expectedIdentityKey && accountFileWorkCurrent(owner, expectedGeneration, expectedEpoch, expectedSession)) {
                    state = state.copy(busy = false, error = result.exceptionOrNull()?.message)
                    if (result.isSuccess) fileIoProgress = "服务端批次已暂存；尚未提交到账本。"
                    onComplete(result)
                }
            }
        }
    }

    fun cancelCurrentAccountFileImport() {
        cancelAccountImport.set(true)
    }

    fun commitAccountFileImport(batchId: UUID, onComplete: (Result<Unit>) -> Unit) {
        val owner = activeOwner ?: return
        if (closed || state.busy || syncPauseFlag.get()) { main.post { onComplete(Result.failure(IllegalStateException("账号同步暂停或账本正在处理"))) }; return }
        val expectedGeneration = generation
        val expectedSession = authenticatedSessionIdentity ?: run { main.post { onComplete(Result.failure(IllegalStateException("账号会话身份尚未就绪"))) }; return }
        val expectedEpoch = runCatching { EncryptedDatasetStore.activeAccountEpoch(getApplication(), owner) }.getOrNull()
            ?: run { main.post { onComplete(Result.failure(StorageOpenException())) }; return }
        cancelAccountImport.set(false)
        state = state.copy(busy = true, error = null)
        executor.execute {
            val result = runCatching {
                check(syncedAccountEpoch == expectedEpoch && accountFileWorkCurrent(owner, expectedGeneration, expectedEpoch, expectedSession)) { "账号账本需先同步" }
                accountFileCoordinator(owner, expectedEpoch, expectedGeneration, expectedSession).commit(batchId,
                    cancelled = { cancelAccountImport.get() || cancelAccountSync.get() || !accountFileWorkCurrent(owner, expectedGeneration, expectedEpoch, expectedSession) },
                    progress = { publishFileIoProgress(it) })
            }
            main.post { if (accountFileWorkCurrent(owner, expectedGeneration, expectedEpoch, expectedSession)) { state = state.copy(busy = false, error = result.exceptionOrNull()?.message); onComplete(result); if (result.isSuccess) refresh() } }
        }
    }

    fun resumeAccountFileImport(batchId: UUID, onComplete: (Result<Unit>) -> Unit) {
        val owner = activeOwner ?: return
        if (closed || state.busy || syncPauseFlag.get()) { main.post { onComplete(Result.failure(IllegalStateException("账号同步暂停或账本正在处理"))) }; return }
        val expectedGeneration = generation
        val expectedSession = authenticatedSessionIdentity ?: run { main.post { onComplete(Result.failure(IllegalStateException("账号会话身份尚未就绪"))) }; return }
        val expectedEpoch = runCatching { EncryptedDatasetStore.activeAccountEpoch(getApplication(), owner) }.getOrNull()
            ?: run { main.post { onComplete(Result.failure(StorageOpenException())) }; return }
        cancelAccountImport.set(false)
        state = state.copy(busy = true, error = null)
        executor.execute {
            val result = runCatching {
                check(accountFileWorkCurrent(owner, expectedGeneration, expectedEpoch, expectedSession)) { "账号身份或同步状态已变化" }
                accountFileCoordinator(owner, expectedEpoch, expectedGeneration, expectedSession).resume(batchId,
                    cancelled = { cancelAccountImport.get() || cancelAccountSync.get() || !accountFileWorkCurrent(owner, expectedGeneration, expectedEpoch, expectedSession) },
                    progress = { publishFileIoProgress(it) })
            }
            main.post { if (accountFileWorkCurrent(owner, expectedGeneration, expectedEpoch, expectedSession)) { state = state.copy(busy = false, error = result.exceptionOrNull()?.message); onComplete(result); if (result.isSuccess) refresh() } }
        }
    }

    fun cancelAccountFileImport(batchId: UUID, onComplete: (Result<Unit>) -> Unit) {
        val owner = activeOwner ?: return
        if (closed || state.busy || syncPauseFlag.get()) { main.post { onComplete(Result.failure(IllegalStateException(if (syncPauseFlag.get()) "账号网络同步已暂停；取消暂存需恢复同步后执行" else "账本正在处理中"))) }; return }
        val expectedGeneration = generation
        val expectedSession = authenticatedSessionIdentity ?: run { main.post { onComplete(Result.failure(IllegalStateException("账号会话身份尚未就绪"))) }; return }
        val expectedEpoch = runCatching { EncryptedDatasetStore.activeAccountEpoch(getApplication(), owner) }.getOrNull()
            ?: run { main.post { onComplete(Result.failure(StorageOpenException())) }; return }
        state = state.copy(busy = true, error = null)
        executor.execute {
            val result = runCatching {
                check(accountFileWorkCurrent(owner, expectedGeneration, expectedEpoch, expectedSession)) { "账号身份或同步状态已变化" }
                accountFileCoordinator(owner, expectedEpoch, expectedGeneration, expectedSession).cancel(batchId,
                    cancelled = { cancelAccountImport.get() || cancelAccountSync.get() || !accountFileWorkCurrent(owner, expectedGeneration, expectedEpoch, expectedSession) },
                    progress = { publishFileIoProgress(it) })
            }
            main.post { if (accountFileWorkCurrent(owner, expectedGeneration, expectedEpoch, expectedSession)) { state = state.copy(busy = false, error = result.exceptionOrNull()?.message); onComplete(result); if (result.isSuccess) refresh() } }
        }
    }

    fun rollbackAccountFileImport(batchId: UUID, onComplete: (Result<Unit>) -> Unit) {
        val owner = activeOwner ?: return
        if (closed || state.busy || syncPauseFlag.get()) { main.post { onComplete(Result.failure(IllegalStateException("账号同步暂停或账本正在处理"))) }; return }
        val expectedGeneration = generation
        val expectedSession = authenticatedSessionIdentity ?: run { main.post { onComplete(Result.failure(IllegalStateException("账号会话身份尚未就绪"))) }; return }
        val expectedEpoch = runCatching { EncryptedDatasetStore.activeAccountEpoch(getApplication(), owner) }.getOrNull()
            ?: run { main.post { onComplete(Result.failure(StorageOpenException())) }; return }
        state = state.copy(busy = true, error = null)
        executor.execute {
            val result = runCatching {
                check(accountFileWorkCurrent(owner, expectedGeneration, expectedEpoch, expectedSession)) { "账号身份或同步状态已变化" }
                accountFileCoordinator(owner, expectedEpoch, expectedGeneration, expectedSession).rollback(batchId,
                    cancelled = { cancelAccountSync.get() || !accountFileWorkCurrent(owner, expectedGeneration, expectedEpoch, expectedSession) },
                    progress = { publishFileIoProgress(it) })
            }
            main.post { if (accountFileWorkCurrent(owner, expectedGeneration, expectedEpoch, expectedSession)) { state = state.copy(busy = false, error = result.exceptionOrNull()?.message); onComplete(result); if (result.isSuccess) refresh() } }
        }
    }

    fun accountImportHistory(onComplete: (List<dev.icos.app.data.local.FileImportBatchEntity>) -> Unit) {
        val owner = activeOwner ?: run { main.post { onComplete(emptyList()) }; return }
        val expectedGeneration = generation
        val expectedSession = authenticatedSessionIdentity
        val expectedEpoch = runCatching { EncryptedDatasetStore.activeAccountEpoch(getApplication(), owner) }.getOrNull()
        if (expectedSession == null || expectedEpoch == null) { main.post { onComplete(emptyList()) }; return }
        executor.execute {
            val history = runCatching {
                if (!accountFileWorkCurrent(owner, expectedGeneration, expectedEpoch, expectedSession)) emptyList()
                else AccountFileImportCoordinator(getApplication(), auth, owner, expectedEpoch).history()
            }.getOrDefault(emptyList())
            main.post { if (accountFileWorkCurrent(owner, expectedGeneration, expectedEpoch, expectedSession)) onComplete(history) }
        }
    }

    private fun workAccountMutation(
        mutate: (AccountMutationRepository, UUID) -> Unit,
        after: () -> Unit = {},
        categoryOnly: Boolean = false,
        onEnqueued: () -> Unit = {},
    ) {
        val ownerAtEnqueue = activeOwner ?: return
        if (state.accountClearPending) {
            state = state.copy(error = "账号存在尚未完成的清账屏障；请先继续原清账操作。")
            return
        }
        if (restoreBarrier.get()) {
            showError("账号存在尚未确认的恢复请求；请先继续同一恢复操作，再进行旧 epoch 写入。")
            return
        }
        if (closed || state.busy || state.restoreBusy || categorySaving) return
        if (categoryOnly) {
            categorySaving = true
            state = state.copy(error = null)
        } else state = state.copy(busy = true, error = null)
        val generationAtEnqueue = generation
        val expectedSession = authenticatedSessionIdentity ?: run {
            if (categoryOnly) categorySaving = false
            state = state.copy(busy = false, error = "账号会话身份尚未就绪")
            return
        }
        accountLocalExecutor.execute {
            var committed = false
            try {
                accountMutationVersion.incrementAndGet()
                if (!ownsWork(ownerAtEnqueue, generationAtEnqueue)) return@execute
                val epoch = EncryptedDatasetStore.activeAccountEpoch(getApplication(), ownerAtEnqueue) ?: throw StorageOpenException()
                withAccountMutation(ownerAtEnqueue, epoch, expectedSession) { mutations, deviceId ->
                    check(ownsWork(ownerAtEnqueue, generationAtEnqueue)) { "Account session changed" }
                    mutate(mutations, deviceId)
                    committed = true
                    main.post {
                        if (ownsWork(ownerAtEnqueue, generationAtEnqueue)) {
                            if (categoryOnly) {
                                categorySaving = false
                                state = state.copy(accountMutationPending = true, accountMutationRetryOnly = false,
                                    syncPendingCount = state.syncPendingCount + 1)
                            }
                            onEnqueued()
                        }
                    }
                }
                if (categoryOnly) {
                    refreshAccountCategories(ownerAtEnqueue, generationAtEnqueue) {
                        after()
                        requestAutomaticSync()
                    }
                } else refreshAccountState(ownerAtEnqueue, generationAtEnqueue, after = {
                    after()
                    requestAutomaticSync()
                })
            } catch (failure: Exception) {
                if (committed && ownsWork(ownerAtEnqueue, generationAtEnqueue)) {
                    syncError = structuredSyncError(failure)
                    syncPausedRequestUncertain = syncPausedRequestUncertain || failure is AuthFailure && (failure.retryable || failure.code == "NETWORK")
                    main.post {
                        if (ownsWork(ownerAtEnqueue, generationAtEnqueue)) {
                            if (categoryOnly) categorySaving = false
                            state = state.copy(accountMutationPending = true, accountMutationRetryOnly = false,
                                syncPendingCount = maxOf(1, state.syncPendingCount))
                            if (!categoryOnly) {
                                templateDraft?.mutationQueued = true
                                if (deleteTemplateTarget != null) deleteTemplateQueued = true
                            }
                        }
                    }
                    refreshAccountState(ownerAtEnqueue, generationAtEnqueue) {
                        state = state.copy(error = "更改已写入本机队列，服务器结果尚未确认；请点击重试同步，系统会沿用原操作标识。")
                    }
                    return@execute
                }
                main.post {
                    if (ownsWork(ownerAtEnqueue, generationAtEnqueue)) {
                        if (categoryOnly) categorySaving = false
                        state = state.copy(busy = false, error = message(failure))
                    }
                }
            }
        }
    }

    private fun withAccountMutation(
        owner: UUID,
        epoch: UUID,
        expectedSession: DeviceIdentity,
        mutate: (AccountMutationRepository, UUID) -> Unit,
    ) {
        val database = localAccountDatabase(owner, epoch)
        run {
            val localIdentity = database.identity().read() ?: throw StorageOpenException()
            check(localIdentity.singleton == 1 && localIdentity.schemaVersion == 1 && localIdentity.ownerId == owner.toString() && localIdentity.epochId == epoch.toString())
            if (!SyncControlPolicy.localMutationReady(
                activeEpoch = epoch, identityOwnerId = localIdentity.ownerId, identityEpochId = localIdentity.epochId,
                initialized = localIdentity.serverCursor != null && localIdentity.lastAppliedSeq >= 0, ownerId = owner,
            )) throw FinanceValidationException(syncError?.message ?: "账号账本尚未同步完成，请刷新后重试保存。")
            val authState = auth.snapshot()
            DeviceSessions.requireSameIdentity(expectedSession, authState)
            if (authState.datasetClear != null || activeOwner == owner && state.accountClearPending) throw AuthFailure("CLEAR_REAUTH_UNCONFIRMED")
            if (auth.pendingRestore(owner) != null || activeOwner == owner && restoreBarrier.get()) throw AuthFailure("RESTORE_UNCONFIRMED")
            check(EncryptedDatasetStore.activeAccountEpoch(getApplication(), owner) == epoch) { "Account epoch changed" }
            mutate(AccountMutationRepository(database), authState.deviceId)
            DeviceSessions.requireSameIdentity(expectedSession, auth.snapshot())
        }
    }

    private fun requireAccountDatasetFiles(owner: UUID, epoch: UUID) {
        val directory = EncryptedDatasetStore.accountDirectory(getApplication(), owner, epoch)
        check(File(directory, "dataset.db").let { it.isFile && it.length() > 0L }) { "Account database missing" }
        check(File(directory, "passphrase.envelope").isFile) { "Account passphrase envelope missing" }
    }

    private fun localAccountDatabase(owner: UUID, epoch: UUID): DatasetDatabase {
        check(Looper.myLooper() != Looper.getMainLooper())
        requireAccountDatasetFiles(owner, epoch)
        val directory = EncryptedDatasetStore.accountDirectory(getApplication(), owner, epoch)
        if (accountDatabaseDirectory != directory) closeAccountDatabase()
        return accountDatabase ?: EncryptedDatasetStore.openAccount(getApplication(), owner, epoch).also {
            accountDatabase = it
            accountDatabaseDirectory = directory
        }
    }

    private fun closeAccountDatabase() {
        accountDatabase?.close()
        accountDatabase = null
        accountDatabaseDirectory = null
    }

    private fun refreshAccountState(owner: UUID, expectedGeneration: Long, background: Boolean = false, after: () -> Unit = {}) {
        accountLocalExecutor.execute { loadAccountState(owner, expectedGeneration, background, after) }
    }

    private fun loadAccountState(owner: UUID, expectedGeneration: Long, background: Boolean, after: () -> Unit) {
        val result = runCatching {
            if (!ownsWork(owner, expectedGeneration)) return
            val epoch = EncryptedDatasetStore.activeAccountEpoch(getApplication(), owner)
            if (epoch == null) {
                // A first login has no local replica until bootstrap activates its snapshot.
                // Losing an already loaded selection is still a storage failure.
                if (loadedAccountEpoch != null) throw StorageOpenException()
                main.post {
                    if (ownsWork(owner, expectedGeneration) && !accountSyncRunning) {
                        state = state.copy(busy = if (background) state.busy else false, error = null,
                            bootstrapProgress = null,
                            bootstrapError = if (syncPauseFlag.get()) "账号账本尚未初始化，请在设置中恢复同步后联网重试。"
                                else "账号账本尚未初始化，请联网后重新打开账本。")
                        requestAutomaticSync()
                    }
                }
                return
            }
            val database = localAccountDatabase(owner, epoch)
            if (selectedAccountLedgerId == null) {
                selectedAccountLedgerId = getApplication<Application>()
                    .getSharedPreferences("account-ledger-selection-$owner", Application.MODE_PRIVATE)
                    .getString("ledgerId", null)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            }
            val snapshotAndCounts = run {
                database.runInTransaction<Triple<CanonicalAccountRepository.View, Triple<Int, Int, Set<String>>, List<FinanceCategory>>> {
                    val identity = database.identity().read() ?: throw StorageOpenException()
                    check(identity.singleton == 1 && identity.schemaVersion == 1 && identity.ownerId == owner.toString() && identity.epochId == epoch.toString())
                    val view = CanonicalAccountRepository.read(database, selectedAccountLedgerId,
                        budgetPeriod = budgetPeriod, statisticsZone = statisticsZone)
                    val intents = database.accountMutations().intentsInOrder(owner.toString(), identity.epochId)
                    val counts = SyncControlPolicy.mutationCounts(intents.map { it.state })
                    val pendingRecurrenceIds = intents.filter { it.aggregateType == "recurrence" && it.state in setOf("QUEUED", "AMBIGUOUS", "ACKED") }
                        .mapTo(mutableSetOf()) { if (it.commandType == "recurrence.create") "create" else it.aggregateId }
                    Triple(view, Triple(counts.first, counts.second, pendingRecurrenceIds),
                        view.snapshot.allCategories.filter { it.localRevision == 0L })
                }
            }
            val (view, counts, pendingCategories) = snapshotAndCounts
            val hasPending = counts.first > 0
            val retryOnly = !hasPending && counts.second > 0
            val pendingRecurrenceIds = counts.third
            check(EncryptedDatasetStore.activeAccountEpoch(getApplication(), owner) == epoch) { "账号数据集已切换" }
            selectedAccountLedgerId = view.selectedLedgerId
            main.post {
                if (ownsWork(owner, expectedGeneration)) {
                    loadedAccountEpoch = epoch
                    recurrenceDraft?.let { draft ->
                        draft.mutationQueued = if (draft.original == null) "create" in pendingRecurrenceIds
                            else draft.original.id.toString() in pendingRecurrenceIds
                    }
                    if (retryOnly) {
                        recordDraft?.saveQueued = false
                        accountDraft?.mutationQueued = false
                        templateDraft?.mutationQueued = false
                        deleteTemplateQueued = false
                        budgetDraft?.saveQueued = false
                        budgetCategoryDraft?.saveQueued = false
                    } else if (!hasPending) {
                        if (recordDraft?.saveQueued == true) recordDraft = null
                        if (accountDraft?.mutationQueued == true) accountDraft = null
                        if (templateDraft?.mutationQueued == true) templateDraft = null
                        if (deleteTemplateQueued) { deleteTemplateTarget = null; deleteTemplateQueued = false }
                        if (budgetDraft?.saveQueued == true) budgetDraft = null
                        if (budgetCategoryDraft?.saveQueued == true) budgetCategoryDraft = null
                    }
                    state = FinanceUiState(snapshot = view.snapshot, budget = view.budget, templates = view.templates,
                        busy = if (background) state.busy else false,
                        pendingCategories = pendingCategories,
                        accountOwner = owner.toString(),
                        accountSummary = when {
                            hasPending -> "更改已保存到本机队列，等待服务器确认"
                            retryOnly -> "上次更改未获服务器接受；可打开账单后修正并重新保存"
                            syncError != null -> syncError!!.message
                            else -> accountSummary ?: "已加载本机账号账本"
                        },
                        accountLedgers = view.ledgers, accountRecurrences = view.recurrences,
                        accountMutationPending = hasPending, accountMutationRetryOnly = retryOnly,
                        syncPaused = syncPauseFlag.get(), syncLastSuccessAt = lastSyncSuccessAt,
                        syncPendingCount = counts.first + counts.second, syncError = syncError,
                        syncEpochArchived = syncEpochArchived, syncPausedRequestUncertain = syncPausedRequestUncertain,
                        syncRecoveryRequired = syncRecoveryRequired,
                        conflicts = state.conflicts, conflictsBusy = state.conflictsBusy, conflictDetail = state.conflictDetail,
                        conflictError = state.conflictError, conflictResolutionBusy = state.conflictResolutionBusy,
                        conflictResolutionResult = state.conflictResolutionResult, conflictEditedGroupJson = state.conflictEditedGroupJson,
                        conflictReviewKeys = state.conflictReviewKeys)
                    reconcileRecordSaves()
                    after()
                }
            }
        }
        result.exceptionOrNull()?.let { failure ->
            main.post {
                if (ownsWork(owner, expectedGeneration)) {
                    state = state.copy(busy = if (background) state.busy else false,
                        error = "账号账本刷新失败；本机更改队列仍保留，请重试同步。${message(failure)}")
                }
            }
        }
    }

    private fun refreshAccountCategories(owner: UUID, expectedGeneration: Long, after: () -> Unit) {
        val epoch = state.snapshot?.epochId ?: return
        accountLocalExecutor.execute {
            val result = runCatching {
                check(ownsWork(owner, expectedGeneration)) { "账号已切换" }
                check(EncryptedDatasetStore.activeAccountEpoch(getApplication(), owner) == epoch) { "账号数据集已切换" }
                // Upload receipts can add canonical mirror columns through another connection.
                closeAccountDatabase()
                val database = localAccountDatabase(owner, epoch)
                database.runInTransaction<Triple<CanonicalAccountRepository.CategoryProjection, Int, Int>> {
                    val mutations = database.accountMutations()
                    Triple(
                        CanonicalAccountRepository.readCategoryProjection(database, owner, epoch),
                        mutations.pendingCount(owner.toString(), epoch.toString()),
                        mutations.retryCount(owner.toString(), epoch.toString()),
                    )
                }
            }
            main.post {
                if (!ownsWork(owner, expectedGeneration) || state.snapshot?.epochId != epoch) return@post
                result.onSuccess { (projection, pendingCount, retryCount) ->
                    mergeCategories(owner, epoch, projection.categories, projection.pendingCreations)
                    state = state.copy(
                        accountMutationPending = pendingCount > 0,
                        accountMutationRetryOnly = pendingCount == 0 && retryCount > 0,
                        syncPendingCount = pendingCount + retryCount,
                        accountSummary = when {
                            pendingCount > 0 -> "更改已保存到本机队列，等待服务器确认"
                            retryCount > 0 -> "上次更改未获服务器接受；请检查同步状态"
                            syncError != null -> syncError!!.message
                            else -> accountSummary ?: "已加载本机账号账本"
                        },
                        syncError = syncError, syncPausedRequestUncertain = syncPausedRequestUncertain,
                        syncLastSuccessAt = lastSyncSuccessAt, syncRecoveryRequired = syncRecoveryRequired,
                    )
                    after()
                }.onFailure {
                    state = state.copy(error = "分类刷新失败；本机更改队列仍保留，请重试同步。${message(it)}")
                }
            }
        }
    }

    fun loadSyncConflicts() {
        val owner = activeOwner ?: return
        if (closed || state.busy || syncPauseFlag.get() || state.restoreBusy || restoreBarrier.get()) return
        val epochId = EncryptedDatasetStore.activeAccountEpoch(getApplication(), owner) ?: return
        val expectedGeneration = generation
        val controlGeneration = syncPauseControlGeneration
        val expectedIdentity = syncControlIdentity ?: return
        val requestGeneration = ++conflictRequestGeneration
        state = state.copy(conflictsBusy = true, conflictError = null)
        executor.execute {
            val result = runCatching {
                check(ownsWork(owner, expectedGeneration) && !syncPauseFlag.get() && !restoreBarrier.get())
                check(auth.syncControlIdentityGeneration() == controlGeneration)
                DeviceSessions.requireSameIdentity(expectedIdentity, auth.snapshot())
                val epoch = EncryptedDatasetStore.activeAccountEpoch(getApplication(), owner) ?: throw StorageOpenException()
                check(epoch == epochId)
                requireAccountDatasetFiles(owner, epoch)
                val database = EncryptedDatasetStore.openAccount(getApplication(), owner, epoch)
                try {
                    val stored = database.identity().read() ?: throw StorageOpenException()
                    check(stored.ownerId == owner.toString() && stored.epochId == epoch.toString())
                    database.accountMutations().intentsInOrder(owner.toString(), epoch.toString())
                        .filter { it.state == "CONFLICT" && it.aggregateType != "sync_conflict" }.forEach { intent ->
                            val receipt = database.accountMutations().receipt(intent.opId) ?: throw AuthFailure("CONFLICT_RECEIPT_MISSING")
                            val wire = JSONObject(receipt.resultJson)
                            if (wire.optString("status") != "CONFLICT" || wire.optString("opId") != intent.opId) throw AuthFailure("CONFLICT_RECEIPT_INVALID")
                        }
                } finally { database.close() }
                val visible = buildList {
                    var cursor: String? = null
                    var pages = 0
                    do {
                        val page = auth.syncConflicts(cursor, limit = 100, expectedIdentity = expectedIdentity, requestGate = accountImportNetworkGate) {
                            check(ownsWork(owner, expectedGeneration) && !syncPauseFlag.get() && !restoreBarrier.get() &&
                                EncryptedDatasetStore.activeAccountEpoch(getApplication(), owner) == epochId &&
                                auth.syncControlIdentityGeneration() == controlGeneration) { "STALE_CREDENTIALS" }
                        }
                        addAll(page.items)
                        pages++
                        cursor = page.nextCursor
                    } while (cursor != null && pages < 50)
                    if (cursor != null) throw AuthFailure("CONFLICT_LIST_TOO_LARGE", retryable = true)
                }
                check(ownsWork(owner, expectedGeneration) && !syncPauseFlag.get() && !restoreBarrier.get() &&
                    auth.syncControlIdentityGeneration() == controlGeneration && EncryptedDatasetStore.activeAccountEpoch(getApplication(), owner) == epochId)
                DeviceSessions.requireSameIdentity(expectedIdentity, auth.snapshot())
                visible
            }
            main.post {
                if (ownsWork(owner, expectedGeneration) && requestGeneration == conflictRequestGeneration) {
                    state = if (result.isSuccess) state.copy(conflicts = result.getOrThrow(), conflictsBusy = false, conflictError = null)
                    else state.copy(conflictsBusy = false, conflictError = message(result.exceptionOrNull()!!))
                }
            }
        }
    }

    fun openSyncConflict(conflictId: UUID, reviewKeys: Set<String> = emptySet()) {
        val owner = activeOwner ?: return
        if (closed || state.busy || syncPauseFlag.get() || restoreBarrier.get()) return
        val listedConflict = state.conflicts.firstOrNull { it.conflictId == conflictId }
        val originalKeys = listedConflict?.localCommand?.optJSONObject("baseVersions")?.keys()?.asSequence()?.toSet()
        if (listedConflict == null || originalKeys == null || reviewKeys.size > 100 || reviewKeys.any { it in originalKeys } || originalKeys.size + reviewKeys.size > 2048) {
            state = state.copy(conflictError = "冲突已变化或依赖键超过限制，请重新载入冲突")
            return
        }
        val expectedGeneration = generation
        val expectedIdentity = syncControlIdentity ?: return
        val expectedEpoch = EncryptedDatasetStore.activeAccountEpoch(getApplication(), owner) ?: return
        val controlGeneration = syncPauseControlGeneration
        val requestGeneration = ++conflictRequestGeneration
        state = state.copy(conflictsBusy = true, conflictError = null)
        executor.execute {
            val result = runCatching {
                check(ownsWork(owner, expectedGeneration) && !syncPauseFlag.get() && !restoreBarrier.get())
                check(auth.syncControlIdentityGeneration() == controlGeneration)
                DeviceSessions.requireSameIdentity(expectedIdentity, auth.snapshot())
                check(EncryptedDatasetStore.activeAccountEpoch(getApplication(), owner) == expectedEpoch)
                requireAccountDatasetFiles(owner, expectedEpoch)
                val account = EncryptedDatasetStore.openAccount(getApplication(), owner, expectedEpoch)
                try {
                    val stored = account.identity().read() ?: throw StorageOpenException()
                    check(stored.singleton == 1 && stored.schemaVersion == 1 && stored.ownerId == owner.toString() && stored.epochId == expectedEpoch.toString())
                } finally { account.close() }
                val detail = auth.syncConflict(conflictId, reviewKeys, expectedIdentity, accountImportNetworkGate) {
                    check(ownsWork(owner, expectedGeneration) && !syncPauseFlag.get() && !restoreBarrier.get() &&
                        auth.syncControlIdentityGeneration() == controlGeneration && EncryptedDatasetStore.activeAccountEpoch(getApplication(), owner) == expectedEpoch) { "STALE_CREDENTIALS" }
                }
                check(detail.opId == listedConflict.opId && detail.localCommand.optJSONObject("baseVersions")?.keys()?.asSequence()?.toSet() == originalKeys) {
                    "CONFLICT_DETAIL_MISMATCH"
                }
                detail.also {
                    check(ownsWork(owner, expectedGeneration) && !syncPauseFlag.get() && !restoreBarrier.get() &&
                        auth.syncControlIdentityGeneration() == controlGeneration && EncryptedDatasetStore.activeAccountEpoch(getApplication(), owner) == expectedEpoch)
                    DeviceSessions.requireSameIdentity(expectedIdentity, auth.snapshot())
                }
            }
            main.post {
                if (ownsWork(owner, expectedGeneration) && requestGeneration == conflictRequestGeneration) {
                    state = if (result.isSuccess) state.copy(conflictDetail = result.getOrThrow(), conflictsBusy = false, conflictError = null, conflictReviewKeys = reviewKeys.sorted().joinToString(","))
                    else state.copy(conflictsBusy = false, conflictError = message(result.exceptionOrNull()!!))
                }
            }
        }
    }

    fun setConflictReviewKeys(value: String) {
        if (value.length > 8192 || state.conflictsBusy || state.conflictResolutionBusy) return
        state = state.copy(conflictReviewKeys = value, conflictError = null)
    }

    fun reviewConflictDependencies() {
        val conflict = state.conflictDetail ?: return
        val values = state.conflictReviewKeys.split(',').map(String::trim).filter(String::isNotEmpty)
        if (values.size > 100 || values.distinct().size != values.size || values.any { !it.matches(Regex("[a-z][a-z0-9_]*:[0-9a-fA-F-]{36}")) }) {
            state = state.copy(conflictError = "依赖键无效或超过100项")
            return
        }
        openSyncConflict(conflict.conflictId, values.toSet())
    }

    fun setConflictEditedGroupJson(value: String) {
        if (value.length > 64 * 1024 || state.conflictResolutionBusy) return
        state = state.copy(conflictEditedGroupJson = value, conflictError = null)
    }

    fun resolveSyncConflict(choice: String) {
        val conflict = state.conflictDetail ?: return
        val owner = activeOwner ?: return
        if (closed || state.busy || state.conflictResolutionBusy || syncPauseFlag.get() || restoreBarrier.get()) return
        if (choice !in setOf("LOCAL", "SERVER", "EDITED") || choice == "EDITED" && state.conflictEditedGroupJson.isBlank()) return
        val expectedGeneration = generation
        val requestGeneration = ++conflictRequestGeneration
        val selected = conflict
        val identity = syncControlIdentity ?: return
        val controlGeneration = syncPauseControlGeneration
        val epochId = EncryptedDatasetStore.activeAccountEpoch(getApplication(), owner) ?: return
        val editedText = state.conflictEditedGroupJson
        state = state.copy(conflictResolutionBusy = true, conflictError = null, conflictResolutionResult = null)
        executor.execute {
            val edited = if (choice == "EDITED") runCatching { JSONObject(editedText) }.getOrElse {
                main.post { if (ownsWork(owner, expectedGeneration)) state = state.copy(conflictResolutionBusy = false, conflictError = "编辑后的命令不是有效 JSON") }
                return@execute
            } else null
            val outcome = runCatching {
                check(ownsWork(owner, expectedGeneration) && !syncPauseFlag.get() && !restoreBarrier.get())
                check(auth.syncControlIdentityGeneration() == controlGeneration)
                DeviceSessions.requireSameIdentity(identity, auth.snapshot())
                check(EncryptedDatasetStore.activeAccountEpoch(getApplication(), owner) == epochId)
                requireAccountDatasetFiles(owner, epochId)
                val database = EncryptedDatasetStore.openAccount(getApplication(), owner, epochId)
                try {
                    val stored = database.identity().read() ?: throw StorageOpenException()
                    check(stored.ownerId == owner.toString() && stored.epochId == epochId.toString())
                    val mutations = AccountMutationRepository(database)
                    val existing = mutations.pendingConflictResolution(owner, epochId, selected.conflictId)
                    val operationId = existing?.let { UUID.fromString(it.opId) } ?: UUID.randomUUID()
                    val body = existing?.payloadJson ?: auth.conflictResolveRequest(choice, selected, operationId, edited)
                    val frozen = JSONObject(body)
                    if (existing != null) {
                        if (frozen.optString("newOpId") != operationId.toString() || frozen.optString("choice") != choice ||
                            frozen.optLong("expectedRevision", -1) <= 0 || frozen.optLong("expectedHeadSeq", -1) < 0) {
                            throw FinanceValidationException("存在尚未确认的原决议请求；请按原选择重试同一请求。")
                        }
                    } else {
                        if (choice != "SERVER" && (SyncControlPolicy.requiresMetadataReconfirmation(
                                selected.localCommand.optString("aggregateType"), selected.localCommand.optString("commandType")) ||
                                edited?.let { SyncControlPolicy.requiresMetadataReconfirmation(it.optString("aggregateType"), it.optString("commandType")) } == true)) {
                            throw FinanceValidationException("此更改需重新确认。请先采用服务器版本，再返回预算或管理页面重新操作。")
                        }
                        val previous = mutations.latestConflictResolution(owner, epochId, selected.conflictId)
                        if (previous?.state == "CONFLICT_REVIEW_STALE") {
                            val staleWire = JSONObject(previous.payloadJson)
                            if (staleWire.optLong("expectedHeadSeq", -1) == selected.headSeq &&
                                sameJsonObjects(staleWire.optJSONObject("reviewedServerVersions"), selected.reviewedServerVersions)) {
                                throw AuthFailure("CONFLICT_REVIEW_STALE")
                            }
                        }
                    }
                    val receipt = database.accountMutations().receipt(operationId.toString())
                    val resolution = if (receipt != null) requireFrozenConflictResolution(receipt.resultJson, selected, operationId)
                    else {
                        mutations.persistConflictResolution(selected.conflictId, selected.opId, owner, epochId, operationId, body)
                        try {
                            val response = auth.resolveSyncConflict(selected.conflictId, body, identity, accountImportNetworkGate) {
                                check(ownsWork(owner, expectedGeneration) && !syncPauseFlag.get() && !restoreBarrier.get() &&
                                    auth.syncControlIdentityGeneration() == controlGeneration && EncryptedDatasetStore.activeAccountEpoch(getApplication(), owner) == epochId) { "STALE_CREDENTIALS" }
                            }
                            val result = JSONObject().put("conflictId", response.conflictId.toString()).put("state", response.state)
                                .put("resolutionOpId", response.resolutionOpId.toString()).put("discardedOpId", response.discardedOpId.toString())
                                .put("commandResult", response.commandResult ?: JSONObject.NULL).put("headSeq", response.headSeq).toString()
                            val checked = requireFrozenConflictResolution(result, selected, operationId)
                            check(ownsWork(owner, expectedGeneration) && !syncPauseFlag.get() && !restoreBarrier.get())
                            DeviceSessions.requireSameIdentity(identity, auth.snapshot())
                            check(auth.syncControlIdentityGeneration() == controlGeneration && EncryptedDatasetStore.activeAccountEpoch(getApplication(), owner) == epochId)
                            mutations.recordConflictResolution(selected.conflictId, selected.opId, owner, epochId, operationId, result, response.state)
                            checked
                        } catch (failure: AuthFailure) {
                            if (failure.code == "CONFLICT_REVIEW_STALE") mutations.markConflictReviewStale(owner, epochId, selected.conflictId, operationId)
                            throw failure
                        }
                    }
                    DeviceSessions.requireSameIdentity(identity, auth.snapshot())
                    check(auth.syncControlIdentityGeneration() == controlGeneration && EncryptedDatasetStore.activeAccountEpoch(getApplication(), owner) == epochId)
                    resolution
                } finally { database.close() }
            }
            main.post {
                if (ownsWork(owner, expectedGeneration) && requestGeneration == conflictRequestGeneration) {
                    val failure = outcome.exceptionOrNull()
                    val stale = failure is AuthFailure && failure.code == "CONFLICT_REVIEW_STALE"
                    state = if (outcome.isSuccess) state.copy(conflictResolutionBusy = false, conflictResolutionResult = outcome.getOrThrow(),
                        conflictError = null, conflictDetail = null)
                    else state.copy(conflictResolutionBusy = false, conflictError = if (stale) "服务器数据在审阅后已变化。请重新载入冲突详情，并再次选择决议。" else message(failure!!),
                        conflictDetail = if (stale) null else state.conflictDetail)
                    if (outcome.isSuccess) work(sync = true)
                }
            }
        }
    }

    private fun sameJsonObjects(left: JSONObject?, right: JSONObject): Boolean {
        if (left == null || left.length() != right.length()) return false
        val keys = left.keys().asSequence().toSet()
        return keys == right.keys().asSequence().toSet() && keys.all { left.optLong(it, -1) == right.optLong(it, -2) }
    }

    private fun requireFrozenConflictResolution(resultJson: String, conflict: SyncConflict, operationId: UUID): SyncConflictResolution {
        val result = JSONObject(resultJson)
        if (result.optString("conflictId") != conflict.conflictId.toString() || result.optString("resolutionOpId") != operationId.toString() ||
            result.optString("discardedOpId") != conflict.opId.toString()) throw AuthFailure("CONFLICT_RESOLUTION_UNCERTAIN", retryable = true)
        val state = result.optString("state")
        if (state !in setOf("RESOLVED_LOCAL", "RESOLVED_SERVER", "RESOLVED_EDITED")) throw AuthFailure("CONFLICT_RESOLUTION_UNCERTAIN", retryable = true)
        val commandResult = if (result.isNull("commandResult")) null else result.getJSONObject("commandResult")
        if ((state == "RESOLVED_SERVER") != (commandResult == null)) throw AuthFailure("CONFLICT_RESOLUTION_UNCERTAIN", retryable = true)
        return SyncConflictResolution(conflict.conflictId, state, operationId, conflict.opId, commandResult,
            result.optLong("headSeq", -1).also { if (it < 0) throw AuthFailure("CONFLICT_RESOLUTION_UNCERTAIN", retryable = true) })
    }

    fun dismissSyncConflictDetail() {
        if (state.conflictResolutionBusy) return
        state = state.copy(conflictDetail = null, conflictError = null, conflictResolutionResult = null, conflictEditedGroupJson = "")
    }

    fun loadSyncConflictsAfterAccountRefresh() {
        if (activeOwner != null && !syncPauseFlag.get() && !restoreBarrier.get()) loadSyncConflicts()
    }

    fun loadAnnualReport(year: Int, callback: (Result<FinanceSnapshot>) -> Unit) {
        fun fail(message: String) {
            main.post { callback(Result.failure(IllegalStateException(message))) }
        }
        if (closed) { fail("年度报告读取已取消"); return }
        if (year !in 1900..2200) { fail("年度无效"); return }
        if (state.busy) { fail("账本正在处理中，请重试"); return }
        val ownerAtEnqueue = activeOwner
        val generationAtEnqueue = generation
        val ledgerAtEnqueue = state.snapshot?.ledgerId
        if (ledgerAtEnqueue == null) { fail("当前账本尚未就绪，请重试"); return }
        executor.execute {
            val result = runCatching {
                check(ownsWork(ownerAtEnqueue, generationAtEnqueue)) { "账本已切换" }
                val snapshot = if (ownerAtEnqueue == null) {
                    val repo = repository ?: LocalFinanceRepository.openGuest(getApplication<Application>()).also { repository = it }
                    repo.snapshot()
                } else {
                    val epoch = EncryptedDatasetStore.activeAccountEpoch(getApplication(), ownerAtEnqueue) ?: throw StorageOpenException()
                    check(syncedAccountEpoch == epoch) { "账号账本需先同步" }
                    requireAccountDatasetFiles(ownerAtEnqueue, epoch)
                    val database = EncryptedDatasetStore.openAccount(getApplication(), ownerAtEnqueue, epoch)
                    try {
                        val identity = database.identity().read() ?: throw StorageOpenException()
                        check(identity.singleton == 1 && identity.schemaVersion == 1 && identity.ownerId == ownerAtEnqueue.toString() && identity.epochId == epoch.toString())
                        CanonicalAccountRepository.read(database, ledgerAtEnqueue).snapshot
                    } finally { database.close() }
                }
                check(ownsWork(ownerAtEnqueue, generationAtEnqueue) && snapshot.ledgerId == ledgerAtEnqueue) { "账本已切换" }
                snapshot
            }
            main.post {
                val delivered = if (ownsWork(ownerAtEnqueue, generationAtEnqueue)) result else Result.failure(IllegalStateException("账本已切换，请重试"))
                callback(delivered)
            }
        }
    }

    fun exportStorageDiagnostics(
        report: dev.icos.app.data.local.StorageDiagnosticReport,
        uri: android.net.Uri,
        onComplete: (Boolean) -> Unit,
    ) {
        if (closed || state.busy) return
        val ownerAtEnqueue = activeOwner
        val generationAtEnqueue = generation
        state = state.copy(busy = true)
        executor.execute {
            val saved = runCatching {
                check(ownsWork(ownerAtEnqueue, generationAtEnqueue))
                check(uri.scheme == "content")
                val bytes = report.exportJson().toByteArray(Charsets.UTF_8)
                check(bytes.size <= 4096)
                getApplication<Application>().contentResolver.openOutputStream(uri, "w")?.use { output ->
                    output.write(bytes)
                    output.flush()
                } ?: error("Diagnostic destination unavailable")
            }.isSuccess
            main.post {
                if (ownsWork(ownerAtEnqueue, generationAtEnqueue)) {
                    state = state.copy(busy = false)
                    onComplete(saved)
                }
            }
        }
    }

    fun runStorageDiagnostics(onComplete: (Result<dev.icos.app.data.local.StorageDiagnosticReport>) -> Unit) {
        if (closed || state.busy) return
        val ownerAtEnqueue = activeOwner
        val generationAtEnqueue = generation
        state = state.copy(busy = true)
        executor.execute {
            val result = runCatching {
                check(ownsWork(ownerAtEnqueue, generationAtEnqueue))
                if (ownerAtEnqueue == null) {
                    val repo = repository ?: throw StorageOpenException()
                    repo.storageDiagnostics()
                } else {
                    val context = getApplication<Application>()
                    val epoch = EncryptedDatasetStore.activeAccountEpoch(context, ownerAtEnqueue)
                        ?: throw StorageOpenException()
                    requireAccountDatasetFiles(ownerAtEnqueue, epoch)
                    val directory = EncryptedDatasetStore.accountDirectory(context, ownerAtEnqueue, epoch)
                    EncryptedDatasetStore.openAccount(context, ownerAtEnqueue, epoch).use { database ->
                        val identity = database.identity().read() ?: throw StorageOpenException()
                        check(identity.singleton == 1 && identity.schemaVersion == 1 && identity.ownerId == ownerAtEnqueue.toString() && identity.epochId == epoch.toString())
                        dev.icos.app.data.local.StorageDiagnostics.inspect(database, accountMode = true)
                    }
                }
            }.recoverCatching {
                throw IllegalStateException("无法完成完整性检查，请保留应用数据并使用已验证的服务器备份恢复。")
            }
            main.post {
                if (ownsWork(ownerAtEnqueue, generationAtEnqueue)) {
                    state = state.copy(busy = false)
                    onComplete(result)
                }
            }
        }
    }

    @Volatile private var fileIoCancelled = false
    var fileIoProgress by mutableStateOf<String?>(null)
        private set
    var incomingFile by mutableStateOf<Triple<String, Boolean, String>?>(null)
        private set

    fun cancelFileIo() { fileIoCancelled = true }
    fun publishFileIoProgress(value: String) { main.post { if (!closed) fileIoProgress = value } }
    fun acceptIncomingFile(uri: String?, permissionAlreadyGranted: Boolean, intentToken: String) {
        incomingFile = uri?.takeIf { it.startsWith("content://") }?.let { Triple(it, permissionAlreadyGranted, intentToken) }
    }
    fun consumeIncomingFile() { incomingFile = null }

    fun beginAccountExport(
        expectedLedger: UUID?, cachedIdentity: DeviceIdentity?, filter: AccountExportFilter, format: AccountExportFormat,
        onReady: (Result<AccountExportRequest>) -> Unit,
    ) {
        if (synchronized(exportRequestLock) { accountExportRequest != null || exportPickerPrefs.getString("nonce", null) != null }) {
            main.post { onReady(Result.failure(IllegalStateException("已有文件选择请求尚未完成"))) }
            return
        }
        val owner = activeOwner
        if (owner == null || cachedIdentity?.owner != owner || exportSessionIdentity != cachedIdentity) {
            main.post { onReady(Result.failure(IllegalStateException("账号账本尚未就绪或当前会话未确认"))) }
            return
        }
        val generationAtEnqueue = generation
        if (expectedLedger == null || state.snapshot?.ledgerId != expectedLedger) {
            main.post { onReady(Result.failure(IllegalStateException("当前账本已变化，请重新选择导出账本"))) }
            return
        }
        if (closed || state.busy || state.restoreBusy || state.clearPreview != null || restoreBarrier.get()) {
            main.post { onReady(Result.failure(IllegalStateException("账本正在变更；恢复或同步未确认期间不能导出"))) }
            return
        }
        val initialIdentity = cachedIdentity
        if (initialIdentity == null || initialIdentity.owner != owner) {
            main.post { onReady(Result.failure(IllegalStateException("当前登录账号与账本不一致"))) }
            return
        }
        state = state.copy(busy = true, error = null)
        executor.execute {
            val result = runCatching {
                check(ownsWork(owner, generationAtEnqueue)) { "账号已切换，导出已取消" }
                DeviceSessions.requireSameIdentity(initialIdentity, auth.snapshot())
                check(!auth.snapshot().hasRestoreBarrierForCurrentSession() && !restoreBarrier.get() && !state.restoreBusy) {
                    "账号恢复状态未确认，不能导出"
                }
                val epoch = EncryptedDatasetStore.activeAccountEpoch(getApplication(), owner) ?: throw StorageOpenException()
                requireAccountDatasetFiles(owner, epoch)
                val database = EncryptedDatasetStore.openAccount(getApplication(), owner, epoch)
                try {
                    val view = database.runInTransaction<CanonicalAccountRepository.View> {
                        val identity = database.identity().read() ?: throw StorageOpenException()
                        check(identity.singleton == 1 && identity.ownerId == owner.toString() && identity.epochId == epoch.toString() &&
                            identity.schemaVersion == 1 && !identity.serverCursor.isNullOrBlank() && identity.lastAppliedSeq >= 0L) {
                            "账号规范数据集身份或同步游标无效，导出已取消"
                        }
                        val current = CanonicalAccountRepository.read(database, expectedLedger, summary = null, includePredictions = false)
                        val mutations = database.accountMutations()
                        val unresolvedIntents = mutations.intentsInOrder(owner.toString(), epoch.toString())
                            .filter { it.state !in setOf("PULLED", "ARCHIVED") }
                        check(unresolvedIntents.isEmpty() && mutations.predictions(owner.toString(), epoch.toString()).isEmpty()) {
                            "账号存在尚未确认的本地更改；请同步后再导出，避免将待确认数据误认为服务器事实"
                        }
                        current
                    }
                    check(view.snapshot.ownerId == owner && view.snapshot.epochId == epoch && view.snapshot.ledgerId == view.selectedLedgerId)
                    check(view.selectedLedgerId == expectedLedger) { "当前账本已切换，导出已取消" }
                    DeviceSessions.requireSameIdentity(initialIdentity, auth.snapshot())
                    check(EncryptedDatasetStore.activeAccountEpoch(getApplication(), owner) == epoch && ownsWork(owner, generationAtEnqueue)) {
                        "账号或数据集已切换，导出已取消"
                    }
                    check(!auth.snapshot().hasRestoreBarrierForCurrentSession() && !restoreBarrier.get() && !state.restoreBusy) {
                        "账号恢复状态未确认，不能导出"
                    }
                    AccountExportTicket(owner, epoch, view.selectedLedgerId, generationAtEnqueue, initialIdentity)
                } finally { database.close() }
            }
            main.post {
                if (ownsWork(owner, generationAtEnqueue) && exportSessionIdentity == initialIdentity) {
                    result.fold(
                        onSuccess = { ticket ->
                            val request = AccountExportRequest(ticket, filter, format, UUID.randomUUID())
                            synchronized(exportRequestLock) {
                                check(accountExportRequest == null) { "已有文件选择请求尚未完成" }
                                accountExportRequest = request
                            }
                            state = state.copy(busy = false, error = null)
                            onReady(Result.success(request))
                        },
                        onFailure = { failure ->
                            state = state.copy(busy = false, error = failure.message)
                            onReady(Result.failure(failure))
                        },
                    )
                } else {
                    if (ownsWork(owner, generationAtEnqueue)) state = state.copy(busy = false, error = result.exceptionOrNull()?.message)
                    onReady(Result.failure(IllegalStateException("账号会话已切换或导出快照失效，未创建文件")))
                }
            }
        }
    }

    fun exportAccountFile(
        ticket: AccountExportTicket,
        filter: AccountExportFilter,
        format: AccountExportFormat,
        uri: Uri,
        stillCurrent: () -> Boolean,
        onProgress: (Long) -> Unit,
        onComplete: (Result<Long>) -> Unit,
    ) {
        if (uri.scheme != "content") {
            main.post { onComplete(Result.failure(IllegalArgumentException("SAF目标无效"))) }
            return
        }
        val owner = ticket.owner
        if (!stillCurrent() || exportSessionIdentity != ticket.identity || !ownsWork(owner, ticket.generation) || closed || state.busy) {
            runCatching { getApplication<Application>().contentResolver.delete(uri, null, null) }
            main.post { onComplete(Result.failure(IllegalStateException("账号已切换或正在变更，导出已取消"))) }
            return
        }
        val generationAtEnqueue = ticket.generation
        state = state.copy(busy = true, error = null)
        fileIoCancelled = false
        fileIoProgress = null
        executor.execute {
            var exportSucceeded = false
            val result = runCatching {
                check(!fileIoCancelled && stillCurrent()) { "应用已锁定或会话已变化，导出已取消" }
                check(ownsWork(owner, ticket.generation)) { "账号已切换，导出已取消" }
                DeviceSessions.requireSameIdentity(ticket.identity, auth.snapshot())
                check(!auth.snapshot().hasRestoreBarrierForCurrentSession() && !restoreBarrier.get() && !state.restoreBusy) {
                    "账号恢复状态未确认，不能导出"
                }
                check(EncryptedDatasetStore.activeAccountEpoch(getApplication(), owner) == ticket.epoch) {
                    "账号数据集epoch已切换，导出已取消"
                }
                requireAccountDatasetFiles(owner, ticket.epoch)
                val database = EncryptedDatasetStore.openAccount(getApplication(), owner, ticket.epoch)
                try {
                    val rows = database.runInTransaction<FileExportSnapshot> {
                        val identity = database.identity().read() ?: throw StorageOpenException()
                        check(identity.singleton == 1 && identity.ownerId == owner.toString() && identity.epochId == ticket.epoch.toString() &&
                            identity.schemaVersion == 1 && !identity.serverCursor.isNullOrBlank() && identity.lastAppliedSeq >= 0L) {
                            "账号规范数据集身份或同步游标无效，导出已取消"
                        }
                        val view = CanonicalAccountRepository.read(database, ticket.ledger, summary = null, includePredictions = false)
                        check(view.snapshot.ownerId == owner && view.snapshot.epochId == ticket.epoch && view.snapshot.ledgerId == ticket.ledger) {
                            "固定账本快照已失效，导出已取消"
                        }
                        val mutations = database.accountMutations()
                        val unresolvedIntents = mutations.intentsInOrder(owner.toString(), ticket.epoch.toString())
                            .filter { it.state !in setOf("PULLED", "ARCHIVED") }
                        check(unresolvedIntents.isEmpty() && mutations.predictions(owner.toString(), ticket.epoch.toString()).isEmpty()) {
                            "账号存在尚未确认的本地更改；请同步后再导出"
                        }
                        val rawTables = SnapshotImporter.canonicalTables(database)
                        AccountFileExport.rows(view.snapshot, filter, rawTables)
                    }
                    check(!fileIoCancelled && stillCurrent()) { "应用已锁定或会话已变化，未写入目标文件" }
                    val output = getApplication<Application>().contentResolver.openOutputStream(uri, "w")
                        ?: throw IllegalStateException("无法写入SAF目标")
                    try {
                        output.use {
                            if (format == AccountExportFormat.XLSX) {
                                TabularFileCore.exportXlsx(rows.rows, it, omittedDetails = rows.omittedDetails,
                                    cancelled = { fileIoCancelled || !stillCurrent() || !ownsWork(owner, ticket.generation) || runCatching { DeviceSessions.requireSameIdentity(ticket.identity, auth.snapshot()) }.isFailure },
                                    progress = { done -> publishFileIoProgress("正在生成第 ${done + 1} 行"); onProgress(done) },
                                    cleanupOnFailure = { runCatching { getApplication<Application>().contentResolver.delete(uri, null, null) } })
                            } else {
                                TabularFileCore.exportCsv(rows.rows, it, omittedDetails = rows.omittedDetails,
                                    cancelled = { fileIoCancelled || !stillCurrent() || !ownsWork(owner, ticket.generation) || runCatching { DeviceSessions.requireSameIdentity(ticket.identity, auth.snapshot()) }.isFailure },
                                    progress = { done -> publishFileIoProgress("正在生成第 ${done + 1} 行"); onProgress(done) })
                            }
                        }
                    } catch (failure: Exception) {
                        runCatching { getApplication<Application>().contentResolver.delete(uri, null, null) }
                        throw failure
                    }
                    check(!fileIoCancelled && stillCurrent()) { "应用已锁定或会话已变化；目标文件已清理" }
                    DeviceSessions.requireSameIdentity(ticket.identity, auth.snapshot())
                    check(ownsWork(owner, ticket.generation) && EncryptedDatasetStore.activeAccountEpoch(getApplication(), owner) == ticket.epoch) {
                        "导出完成前账号或数据集已切换；目标文件已清理"
                    }
                    check(!auth.snapshot().hasRestoreBarrierForCurrentSession() && !restoreBarrier.get() && !state.restoreBusy && state.clearPreview == null) {
                        "账号恢复或清账状态未确认，不能导出；目标文件已清理"
                    }
                    exportSucceeded = true
                    rows.revision
                } finally { database.close() }
            }
            if (result.isFailure || !exportSucceeded) runCatching { getApplication<Application>().contentResolver.delete(uri, null, null) }
            main.post {
                if (!fileIoCancelled && stillCurrent() && ownsWork(owner, ticket.generation) && exportSessionIdentity == ticket.identity) {
                    state = state.copy(busy = false, error = result.exceptionOrNull()?.message)
                    onComplete(if (exportSucceeded) result else Result.failure(result.exceptionOrNull() ?: IllegalStateException("导出未完成")))
                } else {
                    runCatching { getApplication<Application>().contentResolver.delete(uri, null, null) }
                    if (ownsWork(owner, ticket.generation)) state = state.copy(busy = false)
                    onComplete(Result.failure(IllegalStateException("账号会话已切换，导出已取消")))
                }
            }
        }
    }

    fun cancelAccountFileExport() { fileIoCancelled = true }

    fun <T> runFileIo(action: (LocalFinanceRepository, FinanceSnapshot) -> T, onComplete: (Result<T>) -> Unit) {
        val ownerAtEnqueue = activeOwner
        val generationAtEnqueue = generation
        if (ownerAtEnqueue != null) {
            main.post { onComplete(Result.failure(IllegalStateException("账号模式暂不支持文件批量导入导出"))) }
            return
        }
        if (closed || state.busy) {
            main.post { onComplete(Result.failure(IllegalStateException("账本正在处理中"))) }
            return
        }
        state = state.copy(busy = true, error = null)
        fileIoCancelled = false
        fileIoProgress = null
        executor.execute {
            val result = runCatching {
                check(activeOwner == null && generation == generationAtEnqueue) { "账本已切换" }
                val repo = repository ?: LocalFinanceRepository.openGuest(getApplication<Application>()).also { repository = it }
                action(repo, repo.snapshot())
            }
            main.post {
                if (!closed && generation == generationAtEnqueue && activeOwner == ownerAtEnqueue) {
                    state = state.copy(busy = false, error = result.exceptionOrNull()?.message)
                    onComplete(result)
                }
            }
        }
    }

    // File picker session: the Activity owns the launchers and transports raw results; this model owns the
    // one business request a result may act on. Nothing here is rebuilt after process death.
    @Volatile private var fileIoHost: FileIoHost? = null
    private val fileIoOrder = ReceiptSaveOrder()
    private var fileIoRequest: FileIoRequest? = null
    private var fileIoReturned = false
    private var fileIoReturnedUri: String? = null
    @Volatile private var activeFileRun: UUID? = null
    var fileIo by mutableStateOf(FileIoState())
        private set
    var fileIoPickerOutstanding by mutableStateOf(false)
        private set

    /** True while a picker, a queued result, a retained preview or a write still belongs on the file page. */
    val fileIoRequestActive: Boolean
        get() =
            activeFileRun != null || fileIoPickerOutstanding || fileIoReturned || fileIo.hasPreview || fileIo.exporting ||
                fileIo.exportPreparing || accountExportRequest != null

    fun bindFileIoHost(host: FileIoHost) {
        fileIoHost = host
    }

    fun unbindFileIoHost(owner: Any) {
        if (fileIoHost?.owner === owner) fileIoHost = null
    }

    private fun fileIoAdapter() = AndroidFileIoAdapter(getApplication())

    /** Parse zone frozen when the import was requested; a later zone change must not re-date parsed rows. */
    val fileImportZone: String get() = fileIo.zone ?: statisticsZone

    fun updateFileIo(change: (FileIoState) -> FileIoState) {
        fileIo = change(fileIo)
    }

    fun showFileIoPage(page: FileIoPage) {
        if (fileIo.page != page) fileIo = fileIo.copy(page = page)
    }

    fun setFileIoStatus(message: String?) {
        fileIo = fileIo.copy(status = message)
    }

    /** Cancel in-flight I/O; keep its run ownership until cleanup releases busy. A waiting picker stays queued. */
    fun onAppLocked() {
        fileIoCancelled = true
        if (fileIo.exporting) fileIo = fileIo.copy(exporting = false, exportDone = 0, exportTotal = 0)
    }

    fun discardFileIoStaged() {
        fileIo.staged?.let { staged -> runCatching { fileIoAdapter().remove(staged) } }
        fileIo = fileIo.copy(staged = null)
    }

    fun clearFileIoPreview(message: String? = null) {
        discardFileIoStaged()
        fileIo =
            fileIo.copy(
                guestPreview = null,
                accountPreview = null,
                batchId = null,
                zone = null,
                ledger = null,
                accountBindings = emptyMap(),
                categoryBindings = emptyMap(),
                tagBindings = emptyMap(),
                page = FileIoPage.SOURCE,
                status = message ?: fileIo.status,
            )
    }

    fun refreshAccountFileImportHistory() {
        accountImportHistory { history -> main.post { fileIo = fileIo.copy(accountHistory = history) } }
    }

    fun refreshGuestFileImportHistory() {
        runFileIo({ repo, _ -> GuestFileImportCoordinator(repo).history() }) { result ->
            result.onSuccess { history -> fileIo = fileIo.copy(guestHistory = history) }
        }
    }

    /**
     * Freezes the import target (ledger, owner/epoch, generation, parse zone) before the picker opens, so
     * the file can never be parsed into whatever ledger happens to be selected when the URI returns.
     */
    fun beginFileImport() {
        val host = fileIoHost ?: run { setFileIoStatus("系统文件选择暂不可用。"); return }
        val session = boundSession ?: run { setFileIoStatus("当前会话尚未确认，请稍后再试。"); return }
        val snapshot = state.snapshot ?: run { setFileIoStatus("账本尚未打开。"); return }
        if (!host.unlocked()) {
            setFileIoStatus("应用已锁定，请解锁后再选择文件。")
            return
        }
        if (state.busy) return
        if (!filePickerIdle()) return
        if (activeOwner != null && (state.syncPaused || state.restoreBusy || restoreBarrier.get())) {
            setFileIoStatus("账号同步或恢复尚未确认，暂不能导入。")
            return
        }
        if (fileIo.hasPreview) clearFileIoPreview()
        reserveFilePicker(FilePickerKind.OPEN_DOCUMENT, FileIoOperation.IMPORT, session, snapshot, null, null, null, "")
    }

    /** Guest export freezes the ledger snapshot now; the picker may only write those rows. */
    fun beginGuestFileExport(format: GuestExportFormat) {
        val host = fileIoHost ?: run { setFileIoStatus("系统文件选择暂不可用。"); return }
        val session = boundSession ?: run { setFileIoStatus("当前会话尚未确认。"); return }
        val snapshot = state.snapshot ?: run { setFileIoStatus("账本尚未打开。"); return }
        if (activeOwner != null) {
            setFileIoStatus("账号模式请从导出页导出账号账单。")
            return
        }
        if (!host.unlocked()) {
            setFileIoStatus("应用已锁定，请解锁后再生成文件。")
            return
        }
        if (state.busy || state.restoreBusy || restoreBarrier.get()) return
        if (!filePickerIdle()) return
        fileIo = fileIo.copy(exportMime = format.mime, accountExportError = null, exportDone = 0, exportTotal = 0)
        reserveFilePicker(
            if (format == GuestExportFormat.CSV) FilePickerKind.CREATE_CSV else FilePickerKind.CREATE_XLSX,
            FileIoOperation.GUEST_EXPORT, session, snapshot, null, format, null, "icos-ledger.${format.extension}",
        )
    }

    fun beginAccountFileExport(format: AccountExportFormat) {
        val host = fileIoHost ?: run { setFileIoStatus("系统文件选择暂不可用。"); return }
        val session = boundSession ?: run { setFileIoStatus("当前会话尚未确认。"); return }
        val snapshot = state.snapshot ?: run { setFileIoStatus("账本尚未打开。"); return }
        val owner = activeOwner ?: run { setFileIoStatus("账号账本尚未就绪。"); return }
        val identity = authenticatedSessionIdentity ?: run { setFileIoStatus("账号会话身份尚未就绪。"); return }
        if (!host.unlocked()) {
            setFileIoStatus("应用已锁定，请解锁后再生成文件。")
            return
        }
        if (state.busy || state.restoreBusy || restoreBarrier.get() || state.accountMutationPending) return
        if (!filePickerIdle()) return
        val from =
            fileIo.accountFromDate.takeIf(String::isNotBlank)?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
        if (fileIo.accountFromDate.isNotBlank() && from == null) {
            fileIo = fileIo.copy(accountExportError = "起始日期无效，请用YYYY-MM-DD。")
            return
        }
        val through =
            fileIo.accountThroughDate.takeIf(String::isNotBlank)?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
        if (fileIo.accountThroughDate.isNotBlank() && through == null) {
            fileIo = fileIo.copy(accountExportError = "结束日期无效，请用YYYY-MM-DD。")
            return
        }
        val filter =
            runCatching { AccountExportFilter(from, through, fileIo.accountExportKinds) }.getOrElse { failure ->
                fileIo =
                    fileIo.copy(
                        accountExportError =
                            when (failure.message) {
                                "EXPORT_INVALID_DATE_RANGE" -> "起始日期不能晚于结束日期。"
                                "EXPORT_EMPTY_TYPE_FILTER" -> "请至少选择一种账单类型。"
                                else -> failure.message ?: "筛选条件无效。"
                            },
                    )
                return
            }
        val ledger = snapshot.ledgerId
        val generationAtLaunch = generation
        fileIo = fileIo.copy(accountExportFormat = format, accountExportError = null, exportPreparing = true, exportDone = 0, exportTotal = 0)
        beginAccountExport(ledger, identity, filter, format) { result ->
            fileIo = fileIo.copy(exportPreparing = false)
            result
                .onSuccess { request ->
                    if (generation != generationAtLaunch || activeOwner != owner || request.ticket.ledger != ledger || fileIoHost?.unlocked() != true || fileIoHost?.session() != session) {
                        abandonAccountExportPicker(request.nonce)
                        setFileIoStatus("账本或会话已变化，未生成文件。")
                        return@beginAccountExport
                    }
                    reserveFilePicker(
                        if (format == AccountExportFormat.CSV) FilePickerKind.CREATE_CSV else FilePickerKind.CREATE_XLSX,
                        FileIoOperation.ACCOUNT_EXPORT, session, snapshot, format, null, request,
                        "icos-account-${request.ticket.ledger}.${format.extension}",
                    )
                }.onFailure { failure -> fileIo = fileIo.copy(accountExportError = fileIoErrorMessage(failure)) }
        }
    }

    /** ACTION_SEND keeps its own path: it is a guest staging input, not a SAF session result. */
    fun previewIncomingGuestFile(uriText: String, permissionAlreadyGranted: Boolean, token: String) {
        val session = boundSession ?: return
        val snapshot = state.snapshot ?: return
        val host = fileIoHost ?: return
        if (!session.guestMode || activeOwner != null || state.busy || fileIoPickerOutstanding ||
            !host.unlocked() || host.session() != session) return
        val request = FileIoRequest(
            token = 0, nonce = "share:$token", kind = FilePickerKind.OPEN_DOCUMENT,
            operation = FileIoOperation.IMPORT, session = session, identity = accountFileIdentity,
            ownerId = snapshot.ownerId, epochId = snapshot.epochId, ledgerId = snapshot.ledgerId,
            generation = generation, zone = statisticsZone,
        )
        clearFileIoPreview()
        startFileImport(request, uriText, permissionAlreadyGranted)
    }

    private fun filePickerIdle(): Boolean {
        if (!fileIoOrder.pickerOutstanding) return true
        setFileIoStatus("上一个文件选择尚未返回，请先完成或取消。")
        return false
    }

    private fun reserveFilePicker(
        kind: FilePickerKind,
        operation: FileIoOperation,
        session: BoundSession,
        snapshot: FinanceSnapshot,
        format: AccountExportFormat?,
        guestFormat: GuestExportFormat?,
        accountRequest: AccountExportRequest?,
        fileName: String,
    ) {
        val host = fileIoHost ?: run { setFileIoStatus("系统文件选择暂不可用。"); return }
        if (!host.unlocked() || host.session() != session || boundSession != session ||
            state.snapshot?.let { it.ownerId == snapshot.ownerId && it.epochId == snapshot.epochId && it.ledgerId == snapshot.ledgerId } != true) {
            accountRequest?.let { abandonAccountExportPicker(it.nonce) }
            setFileIoStatus("会话或账本已变化，未打开文件选择。")
            return
        }
        val token =
            fileIoOrder.begin() ?: run {
                setFileIoStatus("上一个文件选择尚未返回，请先完成或取消。")
                return
            }
        if (accountRequest != null && !markAccountExportPickerLaunch(accountRequest)) {
            fileIoOrder.abandon()
            setFileIoStatus("导出票据已失效，请重新生成。")
            return
        }
        val request =
            FileIoRequest(
                token = token,
                nonce = accountRequest?.nonce?.toString() ?: UUID.randomUUID().toString(),
                kind = kind,
                operation = operation,
                session = session,
                identity = accountFileIdentity,
                ownerId = snapshot.ownerId,
                epochId = snapshot.epochId,
                ledgerId = snapshot.ledgerId,
                generation = generation,
                zone = statisticsZone,
                format = format,
                guestFormat = guestFormat,
                accountRequest = accountRequest,
                snapshot = snapshot.takeIf { operation == FileIoOperation.GUEST_EXPORT },
            )
        fileIoRequest = request
        fileIoReturned = false
        fileIoReturnedUri = null
        fileIo = fileIo.copy(identity = request.identity)
        val launched =
            runCatching {
                when (kind) {
                    FilePickerKind.OPEN_DOCUMENT -> host.launchOpenDocument(request.nonce)
                    FilePickerKind.CREATE_CSV -> host.launchCreateCsv(request.nonce, fileName)
                    FilePickerKind.CREATE_XLSX -> host.launchCreateXlsx(request.nonce, fileName)
                }
            }.isSuccess
        if (!launched) {
            fileIoOrder.abandon()
            fileIoRequest = null
            accountRequest?.let { abandonAccountExportPicker(it.nonce) }
            refreshFileIoPicker()
            setFileIoStatus("无法打开系统文件选择界面。")
            return
        }
        fileIoOrder.pickerLaunched(token)
        refreshFileIoPicker()
        fileIo =
            fileIo.copy(
                status =
                    if (operation == FileIoOperation.IMPORT) {
                        "已打开系统文件选择；返回前不会读取文件。"
                    } else {
                        "已固定导出快照，等待系统文件选择；请完成保存位置选择。"
                    },
            )
    }

    private fun refreshFileIoPicker() {
        fileIoPickerOutstanding = fileIoOrder.pickerOutstanding
    }

    /** A raw picker result (including its cancellation) from the Activity mailbox; duplicates never bind. */
    fun deliverFilePickerResult(
        nonce: String,
        kind: FilePickerKind,
        uri: String?,
        session: BoundSession?,
    ) {
        val token = fileIoOrder.consumePickerResult()
        refreshFileIoPicker()
        if (token == null) {
            discardOrphanFilePickerResult(kind, uri)
            return
        }
        val pending = fileIoRequest
        if (pending == null || pending.token != token || pending.nonce != nonce || pending.kind != kind) {
            discardOrphanFilePickerResult(kind, uri)
            return
        }
        fileIoReturned = true
        fileIoReturnedUri = uri
        resumeFileIo(session)
    }

    /** Dispatches a queued result once the real session is ready, and retires requests the session outlived. */
    fun resumeFileIo(session: BoundSession?) {
        if (fileIo.hasPreview && (fileIo.identity != accountFileIdentity || fileIo.ledger != state.snapshot?.ledgerId)) {
            clearFileIoPreview("会话或账本已变化，预览已取消。")
        }
        val request = fileIoRequest
        if (request == null && !fileIoReturned) {
            // A picker marker left by a previous process has no request behind it; drop it so export is not blocked.
            synchronized(exportRequestLock) {
                if (accountExportRequest == null && pendingExportRequest == null && exportPickerPrefs.getString("nonce", null) != null) {
                    exportPickerPrefs.edit().clear().commit()
                }
            }
        }
        if (!fileIoReturned) {
            if (request != null && (session == null || request.session != session || !requestCurrent(request))) {
                retireFileIo("会话或账本已变化，文件操作已取消。", request)
            }
            return
        }
        val uri = fileIoReturnedUri
        fileIoReturned = false
        fileIoReturnedUri = null
        if (request == null || session == null || request.session != session || !requestCurrent(request)) {
            if (request != null) {
                // A matched save target is cleaned by the existing rule; a user source document never is.
                discardFilePickerTarget(request.kind, uri)
                retireFileIo(
                    if (request.operation == FileIoOperation.IMPORT) "会话已变化，已丢弃文件选择。" else "会话已变化，未生成文件。",
                    request,
                )
            }
            return
        }
        fileIoRequest = null
        when (request.operation) {
            FileIoOperation.IMPORT -> if (uri == null) setFileIoStatus("未选择文件。") else startFileImport(request, uri)
            FileIoOperation.GUEST_EXPORT -> if (uri == null) setFileIoStatus("未选择保存位置，未生成文件。") else startGuestFileExport(request, uri)
            FileIoOperation.ACCOUNT_EXPORT -> if (uri == null) cancelAccountFileExportPicker(request) else startAccountFileExport(request, uri)
        }
    }

    /** The frozen request still describes the live mode, ledger and applied session. */
    private fun requestCurrent(request: FileIoRequest): Boolean {
        if (!fileIoWorkCurrent(request)) return false
        if (boundSession != request.session || accountFileIdentity != request.identity) return false
        val snapshot = state.snapshot ?: return false
        return snapshot.ownerId == request.ownerId && snapshot.epochId == request.epochId && snapshot.ledgerId == request.ledgerId
    }

    private fun fileIoWorkCurrent(request: FileIoRequest): Boolean {
        val host = fileIoHost ?: return false
        if (closed || generation != request.generation || !host.unlocked() || host.session() != request.session ||
            boundSession != request.session || accountFileIdentity != request.identity) return false
        val snapshot = state.snapshot ?: return false
        if (snapshot.ownerId != request.ownerId || snapshot.epochId != request.epochId || snapshot.ledgerId != request.ledgerId) return false
        return if (request.session.guestMode) {
            activeOwner == null
        } else {
            activeOwner == request.ownerId &&
                runCatching { EncryptedDatasetStore.activeAccountEpoch(getApplication(), request.ownerId) == request.epochId }.getOrDefault(false)
        }
    }

    private fun retireFileIo(
        message: String?,
        request: FileIoRequest? = fileIoRequest,
    ) {
        fileIoRequest = null
        fileIoReturned = false
        fileIoReturnedUri = null
        fileIoOrder.abandon()
        refreshFileIoPicker()
        request?.accountRequest?.nonce?.let { abandonAccountExportPicker(it) }
        activeFileRun = null
        discardFileIoStaged()
        fileIo =
            fileIo.copy(
                page = FileIoPage.SOURCE,
                status = message,
                guestPreview = null,
                accountPreview = null,
                batchId = null,
                zone = null,
                ledger = null,
                guestHistory = emptyList(),
                accountHistory = emptyList(),
                accountBindings = emptyMap(),
                categoryBindings = emptyMap(),
                tagBindings = emptyMap(),
                exporting = false,
                exportPreparing = false,
                exportDone = 0,
                exportTotal = 0,
                accountExportError = null,
            )
    }

    /** An orphan callback: consume it without parsing, writing, or deleting a user source document. */
    private fun discardOrphanFilePickerResult(kind: FilePickerKind, uri: String?) {
        discardFilePickerTarget(kind, uri)
        clearLostAccountExportPicker(null)
        setFileIoStatus("文件选择结果已过期，已忽略。")
    }

    private fun discardFilePickerTarget(kind: FilePickerKind, uri: String?) {
        if (kind == FilePickerKind.OPEN_DOCUMENT || uri == null) return
        val target = runCatching { Uri.parse(uri) }.getOrNull() ?: return
        if (target.scheme == "content") runCatching { getApplication<Application>().contentResolver.delete(target, null, null) }
    }

    private fun clearFileIoForSessionChange() {
        val pending = fileIoRequest
        if (pending != null) {
            retireFileIo("会话已变化，文件操作已取消。", pending)
        } else {
            activeFileRun = null
            discardFileIoStaged()
            fileIo =
                fileIo.copy(
                    page = FileIoPage.SOURCE,
                    guestPreview = null,
                    accountPreview = null,
                    batchId = null,
                    zone = null,
                    ledger = null,
                    guestHistory = emptyList(),
                    accountHistory = emptyList(),
                    accountBindings = emptyMap(),
                    categoryBindings = emptyMap(),
                    tagBindings = emptyMap(),
                    exporting = false,
                    exportPreparing = false,
                    exportDone = 0,
                    exportTotal = 0,
                    accountExportError = null,
                )
        }
    }

    private fun startFileImport(request: FileIoRequest, uri: String, permissionAlreadyGranted: Boolean = false) {
        val parsed = runCatching { Uri.parse(uri) }.getOrNull()
        if (parsed == null || parsed.scheme != "content") {
            setFileIoStatus("文件选择无效，未读取文件。")
            return
        }
        if (!request.session.guestMode) {
            startAccountFileImport(request, parsed)
            return
        }
        if (state.busy) {
            setFileIoStatus("账本正在处理中，请稍后重新选择文件。")
            return
        }
        val run = UUID.randomUUID()
        activeFileRun = run
        fileIoCancelled = false
        state = state.copy(busy = true, error = null)
        executor.execute {
            var staged: AndroidFileIoAdapter.Staged? = null
            val result =
                runCatching {
                    check(activeFileRun == run && !fileIoCancelled && fileIoWorkCurrent(request)) { "账本已切换，导入已取消" }
                    val copied = fileIoAdapter().stage(parsed, "file:${request.nonce}", permissionAlreadyGranted = permissionAlreadyGranted).also { staged = it }
                    check(activeFileRun == run && !fileIoCancelled && fileIoWorkCurrent(request)) { "账本已切换，导入已取消" }
                    val repo = repository ?: LocalFinanceRepository.openGuest(getApplication<Application>()).also { repository = it }
                    val snapshot = repo.snapshot()
                    check(snapshot.ownerId == request.ownerId && snapshot.ledgerId == request.ledgerId) { "目标账本已切换，导入已取消" }
                    copied to GuestFileImportCoordinator(repo).preview(copied.file, ZoneId.of(request.zone), snapshot)
                }
            if (result.isFailure) staged?.let { copy -> runCatching { fileIoAdapter().remove(copy) } }
            main.post {
                if (activeFileRun != run) {
                    staged?.let { copy -> runCatching { fileIoAdapter().remove(copy) } }
                    return@post
                }
                activeFileRun = null
                state = state.copy(busy = false)
                if (closed || fileIoCancelled || !fileIoWorkCurrent(request) || accountFileIdentity != request.identity) {
                    staged?.let { copy -> runCatching { fileIoAdapter().remove(copy) } }
                    setFileIoStatus("会话或账本已变化，导入已取消。")
                    return@post
                }
                result.fold(
                    onSuccess = { (copied, preview) ->
                        fileIo =
                            fileIo.copy(
                                identity = request.identity,
                                zone = request.zone,
                                ledger = request.ledgerId,
                                staged = copied,
                                guestPreview = preview,
                                accountPreview = null,
                                batchId = null,
                                accountBindings = emptyMap(),
                                categoryBindings = emptyMap(),
                                tagBindings = emptyMap(),
                                page = FileIoPage.PREVIEW,
                                status = "预览已就绪。文件已复制到应用私有暂存区，尚未写入账本。",
                            )
                    },
                    onFailure = { failure -> setFileIoStatus(fileIoErrorMessage(failure)) },
                )
            }
        }
    }

    private fun startAccountFileImport(request: FileIoRequest, uri: Uri) {
        val run = UUID.randomUUID()
        activeFileRun = run
        fileIoCancelled = false
        previewAccountFileImport(uri, request.ledgerId, request.zone, permissionAlreadyGranted = false, token = request.nonce,
            stillCurrent = { activeFileRun == run && !fileIoCancelled && fileIoWorkCurrent(request) }) { result ->
            if (activeFileRun != run) {
                result.getOrNull()?.first?.let { copied -> runCatching { fileIoAdapter().remove(copied) } }
                return@previewAccountFileImport
            }
            activeFileRun = null
            result.fold(
                onSuccess = { (copied, preview) ->
                    if (closed || fileIoCancelled || !fileIoWorkCurrent(request) || preview.ownerId != request.ownerId || preview.epochId != request.epochId ||
                        preview.sessionIdentity != request.session.identity || preview.generation != request.generation ||
                        accountFileIdentity != request.identity
                    ) {
                        runCatching { fileIoAdapter().remove(copied) }
                        setFileIoStatus("账号预览与当前请求不匹配，已取消。")
                        return@previewAccountFileImport
                    }
                    fileIo =
                        fileIo.copy(
                            identity = request.identity,
                            zone = request.zone,
                            ledger = request.ledgerId,
                            staged = copied,
                            accountPreview = preview,
                            guestPreview = null,
                            batchId = null,
                            accountBindings = emptyMap(),
                            categoryBindings = emptyMap(),
                            tagBindings = emptyMap(),
                            page = FileIoPage.PREVIEW,
                            status = "账号文件预览已准备；选择映射并明确确认前不会提交。",
                        )
                },
                onFailure = { failure -> setFileIoStatus(fileIoErrorMessage(failure)) },
            )
        }
    }

    private fun startGuestFileExport(request: FileIoRequest, uri: String) {
        val format = request.guestFormat ?: GuestExportFormat.CSV
        val frozen = request.snapshot
        if (frozen == null) {
            setFileIoStatus("导出快照已丢失，请重新生成。")
            return
        }
        val target = runCatching { Uri.parse(uri) }.getOrNull()
        if (target == null || target.scheme != "content") {
            setFileIoStatus("SAF目标无效，未生成文件。")
            return
        }
        val app = getApplication<Application>()
        if (state.busy) {
            setFileIoStatus("账本正在处理中，请稍后重新生成。")
            return
        }
        state = state.copy(busy = true, error = null)
        val run = UUID.randomUUID()
        activeFileRun = run
        fileIoCancelled = false
        fileIo = fileIo.copy(exporting = true, exportDone = 0, exportTotal = 0, accountExportError = null)
        executor.execute {
            var wrote = false
            val result =
                runCatching {
                    check(activeFileRun == run && !fileIoCancelled && fileIoWorkCurrent(request)) { "账本已切换，导出已取消" }
                    val accounts = frozen.accounts.associate { it.id to it.name }
                    val categories = frozen.categories.associate { it.id to it.name }
                    val summaryCurrencies = frozen.summaries.map { it.currency }.toSet()
                    require(summaryCurrencies.size <= 1) { "EXPORT_MULTIPLE_CURRENCIES_UNSUPPORTED" }
                    val rows =
                        FinanceFileExportMapper.snapshot(
                            frozen,
                            frozen.records.maxOfOrNull { it.localRevision } ?: 0L,
                            accounts,
                            categories,
                        )
                    val total = rows.rows.count()
                    if (total > TabularFileCore.MAX_ROWS) error("TOO_MANY_ROWS")
                    publishFileExportProgress(run, request, 0, total)
                    check(activeFileRun == run && !fileIoCancelled && fileIoWorkCurrent(request)) { "账本已切换，导出已取消" }
                    app.contentResolver.openOutputStream(target, "w")?.use { output ->
                        val progress: (Long) -> Unit = { done -> publishFileExportProgress(run, request, done.toInt(), null) }
                        val cancelled = { activeFileRun != run || fileIoCancelled || !fileIoWorkCurrent(request) }
                        if (format == GuestExportFormat.XLSX) {
                            TabularFileCore.exportXlsx(
                                rows.rows,
                                output,
                                omittedDetails = rows.omittedDetails,
                                cancelled = cancelled,
                                progress = progress,
                                cleanupOnFailure = { runCatching { app.contentResolver.delete(target, null, null) } },
                            )
                        } else {
                            TabularFileCore.exportCsv(rows.rows, output, omittedDetails = rows.omittedDetails, cancelled = cancelled, progress = progress)
                        }
                    } ?: error("无法写入目标文件")
                    check(activeFileRun == run && !fileIoCancelled && fileIoWorkCurrent(request)) { "导出完成前账本已切换；目标文件已清理" }
                    wrote = true
                    rows.revision
                }
            if (!wrote) runCatching { app.contentResolver.delete(target, null, null) }
            main.post {
                if (activeFileRun != run) {
                    runCatching { app.contentResolver.delete(target, null, null) }
                    return@post
                }
                activeFileRun = null
                if (!closed) state = state.copy(busy = false)
                fileIo = fileIo.copy(exporting = false, exportDone = 0, exportTotal = 0)
                val live = !closed && !fileIoCancelled && fileIoWorkCurrent(request) && boundSession == request.session && accountFileIdentity == request.identity
                if (!live && wrote) runCatching { app.contentResolver.delete(target, null, null) }
                val failure = result.exceptionOrNull()
                fileIo =
                    fileIo.copy(
                        status =
                            when {
                                live && wrote -> "已导出固定账本快照（修订 ${result.getOrNull()}）。"
                                !live -> "会话或账本已变化，未生成文件。"
                                fileIoCancelled || failure is java.util.concurrent.CancellationException -> "导出已取消。"
                                else -> guestExportErrorMessage(failure)
                            },
                    )
            }
        }
    }

    private fun startAccountFileExport(request: FileIoRequest, uri: String) {
        val accountRequest = request.accountRequest
        val format = accountRequest?.format
        if (accountRequest == null || format == null) {
            clearLostAccountExportPicker(null)
            setFileIoStatus("导出请求已失效，请重新生成文件。")
            return
        }
        val target = runCatching { Uri.parse(uri) }.getOrNull()
        if (target == null || target.scheme != "content") {
            clearLostAccountExportPicker(null)
            setFileIoStatus("SAF目标无效，未生成文件。")
            return
        }
        // Consume the account picker marker only here, after the lock/protocol gate and the session check.
        val finished = finishAccountExportPicker(format)
        if (finished == null || finished.nonce != accountRequest.nonce) {
            clearLostAccountExportPicker(target)
            setFileIoStatus("导出请求已失效，请重新生成文件。")
            return
        }
        val run = UUID.randomUUID()
        activeFileRun = run
        fileIoCancelled = false
        fileIo = fileIo.copy(exporting = true, exportDone = 0, exportTotal = 0, accountExportError = null)
        exportAccountFile(
            finished.ticket,
            finished.filter,
            finished.format,
            target,
            stillCurrent = { activeFileRun == run && !fileIoCancelled && fileIoWorkCurrent(request) },
            onProgress = { done -> publishFileExportProgress(run, request, done.toInt(), null) },
        ) { result ->
            if (activeFileRun != run) return@exportAccountFile
            activeFileRun = null
            fileIo = fileIo.copy(exporting = false, exportDone = 0, exportTotal = 0)
            if (accountFileIdentity != request.identity) return@exportAccountFile
            fileIo =
                fileIo.copy(
                    accountExportError =
                        result.fold(
                            { revision -> "已导出当前账号账本的已同步事实（修订 $revision）。" },
                            { failure ->
                                if (fileIoCancelled || failure is java.util.concurrent.CancellationException) {
                                    "账号导出已取消。"
                                } else {
                                    fileIoErrorMessage(failure)
                                }
                            },
                        ),
                )
        }
    }

    private fun cancelAccountFileExportPicker(request: FileIoRequest) {
        val format = request.accountRequest?.format
        if (format == null || finishAccountExportPicker(format) == null) clearLostAccountExportPicker(null)
        setFileIoStatus("未选择SAF目标，未生成文件。")
    }

    private fun publishFileExportProgress(
        run: UUID,
        request: FileIoRequest,
        done: Int,
        total: Int?,
    ) {
        main.post {
            if (activeFileRun != run || closed) return@post
            if (!fileIoWorkCurrent(request) || accountFileIdentity != request.identity) return@post
            fileIo = fileIo.copy(exportDone = done, exportTotal = total ?: fileIo.exportTotal)
        }
    }

    private fun guestExportErrorMessage(failure: Throwable?): String =
        when {
            failure == null -> "导出未完成"
            failure is java.io.IOException || failure.message?.contains("space", true) == true -> "存储空间不足或目标不可写。"
            else -> fileIoErrorMessage(failure)
        }

    private fun work(
        change: ((LocalFinanceRepository) -> Unit)? = null,
        after: () -> Unit = {},
        sync: Boolean = false,
        range: PeriodRange? = statisticsPeriod?.let { periodRange(it) } ?: statisticsRange,
        reloadAccount: Boolean = false,
        onCommitted: () -> Unit = {},
    ) {
        if (activeOwner != null && change != null) {
            showError("服务器账号账本当前只读；同步写入将在 typed producer 阶段开放")
            return
        }
        if (sync && activeOwner != null && syncPauseFlag.get()) return
        if (closed || state.busy || state.restoreBusy || restoreBarrier.get()) return
        val ownerAtEnqueue = activeOwner
        val generationAtEnqueue = generation
        if (sync && ownerAtEnqueue != null) {
            if (accountSyncRunning) { requestAutomaticSync(); return }
            accountSyncRunning = true
            val initializing = state.snapshot == null
            if (initializing) state = state.copy(busy = true, error = null, bootstrapError = null)
            accountUploadExecutor.execute {
                val result = runCatching { syncAccountReplica(reloadAccount) }
                main.post {
                    accountSyncRunning = false
                    if (!ownsWork(ownerAtEnqueue, generationAtEnqueue)) return@post
                    if (initializing) state = state.copy(busy = false)
                    if (result.isFailure) {
                        state = state.copy(bootstrapProgress = null, bootstrapError = message(result.exceptionOrNull()!!))
                        scheduleAutomaticSyncRetry()
                    } else if (result.getOrDefault(AccountRefresh.NONE) != AccountRefresh.NONE) {
                        refreshAfterAccountSync(ownerAtEnqueue, generationAtEnqueue, { after(); scheduleAutomaticSyncRetry() }, range,
                            categoriesOnly = result.getOrThrow() == AccountRefresh.CATEGORIES)
                    } else {
                        state = state.copy(syncError = syncError, syncPausedRequestUncertain = syncPausedRequestUncertain,
                            syncLastSuccessAt = lastSyncSuccessAt, syncRecoveryRequired = syncRecoveryRequired)
                        after()
                        scheduleAutomaticSyncRetry()
                    }
                }
            }
            return
        }
        state = state.copy(busy = true, error = null)
        val worker = if (ownerAtEnqueue == null) executor else accountLocalExecutor
        worker.execute { runWork(change, after, range, ownerAtEnqueue, generationAtEnqueue, onCommitted = onCommitted) }
    }

    private fun refreshAfterAccountSync(owner: UUID, expectedGeneration: Long, after: () -> Unit, range: PeriodRange?, categoriesOnly: Boolean = false) {
        if (!ownsWork(owner, expectedGeneration)) return
        if (state.busy || categorySaving || state.restoreBusy || restoreBarrier.get()) {
            main.postDelayed({ refreshAfterAccountSync(owner, expectedGeneration, after, range, categoriesOnly) }, 100)
            return
        }
        if (categoriesOnly) {
            refreshAccountCategories(owner, expectedGeneration, after)
            return
        }
        refreshAccountState(owner, expectedGeneration, background = true) {
            statisticsRange = range
            after()
        }
    }

    private fun ownsWork(owner: UUID?, expectedGeneration: Long): Boolean =
        !closed && generation == expectedGeneration && activeOwner == owner

    private fun runWork(
        change: ((LocalFinanceRepository) -> Unit)?,
        after: () -> Unit,
        range: PeriodRange?,
        ownerAtEnqueue: UUID?,
        generationAtEnqueue: Long,
        background: Boolean = false,
        onCommitted: () -> Unit = {},
    ) {
        fun ownsWork(): Boolean = !closed && generation == generationAtEnqueue && activeOwner == ownerAtEnqueue
        run {
            if (!ownsWork()) return
            var committed = false
            try {
                if (ownerAtEnqueue != null) {
                    accountLocalExecutor.execute {
                        if (ownsWork()) refreshAccountState(ownerAtEnqueue, generationAtEnqueue, background) {
                            statisticsRange = range
                            after()
                        }
                    }
                    return
                }
                val repo = repository ?: LocalFinanceRepository.openGuest(getApplication<Application>()).also { repository = it }
                if (!ownsWork()) return
                if (selectedGuestLedgerId == null) {
                    val initial = repo.snapshot()
                    val savedId = getApplication<Application>()
                        .getSharedPreferences("guest-ledger-selection-${initial.ownerId}", Application.MODE_PRIVATE)
                        .getString("ledgerId", null)
                        ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    if (savedId != null && initial.ledgers.any { it.id == savedId && it.archivedAt == null }) {
                        repo.selectLedger(savedId)
                        selectedGuestLedgerId = savedId
                    } else {
                        selectedGuestLedgerId = initial.ledgerId
                    }
                }
                val savedSearchPreferences =
                    if (!searchPreferencesLoaded && recordEfficiencySettings.rememberSearchFilters) repo.searchPreferences() else null
                if (!ownsWork()) return
                change?.invoke(repo)
                committed = change != null
                if (committed) main.post { if (ownsWork()) onCommitted() }
                val snapshot = repo.snapshot(range)
                val budget = repo.budgetOverview(budgetPeriod, System.currentTimeMillis(), statisticsZone)
                val templates = repo.recordTemplates()
                val multiLedger = loadGuestMultiLedgerEnabled(snapshot.ownerId.toString())
                val creditProfiles = snapshot.creditProfiles.map { profile ->
                    CreditReminderProfile(profile.id.toString(), profile.accountId.toString(), profile.enabled, profile.statementDay,
                        profile.includeStatementDay, profile.repaymentRule, profile.repaymentValue, profile.timezone)
                }
                main.post {
                    if (ownsWork()) {
                        if (!searchPreferencesLoaded) {
                            savedSearchPreferences?.let {
                                rememberedSearchFilters =
                                    RecordFilters(
                                        it.type,
                                        it.fromDate,
                                        it.throughDate,
                                        it.currency,
                                        it.accountId,
                                        it.categoryId,
                                        it.minimumAmount,
                                        it.maximumAmount,
                                        it.tagIds.split(',').filter(String::isNotBlank).toSet(),
                                        it.tagMatch.takeIf { mode -> mode in setOf("ANY", "ALL") } ?: "ANY",
                                    )
                                rememberedSearchSort = it.sort
                            }
                            searchPreferencesLoaded = true
                        }
                        state =
                            FinanceUiState(
                                busy = if (background) state.busy else false,
                                snapshot = snapshot,
                                budget = budget,
                                templates = templates,
                                accountOwner = activeOwner?.toString(),
                                guestAccountLedgerVisibility = if (activeOwner == null) loadGuestAccountLedgerVisibility(snapshot.ownerId, snapshot.accounts, snapshot.ledgers) else emptyMap(),
                                guestMultiLedgerEnabled = if (activeOwner == null) multiLedger else true,
                                clearPreview = null,
                                restoreBusy = state.restoreBusy,
                                restoreProgress = state.restoreProgress,
                                restoreError = state.restoreError,
                                restoreSubmissionRetryAvailable = state.restoreSubmissionRetryAvailable,
                                restoreJobId = state.restoreJobId,
                                restoreStatus = state.restoreStatus,
                                backups = state.backups,
                                backupsBusy = state.backupsBusy,
                                selectedRestoreBackup = state.selectedRestoreBackup,
                                accountSummary = accountSummary,
                                syncPaused = syncPauseFlag.get(), syncLastSuccessAt = lastSyncSuccessAt,
                                syncError = syncError, syncEpochArchived = syncEpochArchived,
                                syncPausedRequestUncertain = syncPausedRequestUncertain,
                            )
                        statisticsRange = range
                        reconcileRecordSaves()
                        after()
                    }
                }
            } catch (failure: Exception) {
                val error = if (committed) "操作已完成，但列表暂时无法刷新，请点击刷新" else message(failure)
                main.post {
                    if (ownsWork()) {
                        state =
                            state.copy(
                                busy = if (background) state.busy else false,
                                restoreBusy = restoreBarrier.get(),
                                restoreProgress = if (restoreBarrier.get()) state.restoreProgress else null,
                                error = error,
                                bootstrapProgress = null,
                                bootstrapError = if (activeOwner != null) error else null,
                            )
                        // A committed write must not be offered again just because its refresh failed.
                        if (committed) after()
                    }
                }
            }
        }
    }

    private enum class AccountRefresh { NONE, CATEGORIES, ALL }

    /** Existing datasets upload only; downloading an existing dataset requires explicit recovery. */
    private fun syncAccountReplica(forceSnapshot: Boolean = false): AccountRefresh {
        val owner = activeOwner ?: return AccountRefresh.NONE
        val generationAtStart = generation
        val expectedSession = authenticatedSessionIdentity ?: throw AuthFailure("STALE_CREDENTIALS")
        val deviceId = auth.snapshot().deviceId
        fun current() = ownsWork(owner, generationAtStart) && authenticatedSessionIdentity == expectedSession
        if (syncPauseFlag.get()) return AccountRefresh.NONE
        if (!networkAvailable()) {
            syncError = SyncError(SyncErrorCode.NETWORK, "当前离线；更改已保存在本机，联网后自动上传")
            return AccountRefresh.NONE
        }
        cancelAccountSync.set(false)
        var changed = false
        var categoriesOnly = true
        try {
            var epoch = EncryptedDatasetStore.activeAccountEpoch(getApplication(), owner)
            if (epoch == null || forceSnapshot) {
                val mutationVersion = accountMutationVersion.get()
                val result = AccountSyncCoordinator.syncAccount(
                    getApplication<Application>(), auth, owner,
                    onProgress = { text -> main.post { if (current() && state.snapshot == null) state = state.copy(bootstrapProgress = text) } },
                    cancelled = { cancelAccountSync.get() || syncPauseFlag.get() || !current() },
                    requestFence = { if (!current()) throw AuthFailure("SNAPSHOT_CANCELLED") },
                    commitReplica = { commit ->
                        val committed = accountLocalExecutor.submit {
                            if (!current() || accountMutationVersion.get() != mutationVersion) throw AuthFailure("SNAPSHOT_CANCELLED")
                            closeAccountDatabase()
                            commit()
                        }
                        try { committed.get() } catch (failure: java.util.concurrent.ExecutionException) {
                            throw failure.cause ?: failure
                        }
                    },
                    requestGate = accountImportNetworkGate,
                    expectedIdentity = expectedSession,
                    forceSnapshot = forceSnapshot,
                )
                if (!current()) return AccountRefresh.NONE
                epoch = result.epoch
                changed = true
                categoriesOnly = false
                accountSummary = "账号账本已初始化"
            } else {
                requireAccountDatasetFiles(owner, epoch)
                // One encrypted connection per upload round, rather than opening it for each command.
                val database = EncryptedDatasetStore.openAccount(getApplication(), owner, epoch)
                try {
                    val local = database.identity().read() ?: throw StorageOpenException()
                    check(local.singleton == 1 && local.schemaVersion == 1 && local.ownerId == owner.toString() && local.epochId == epoch.toString())
                    val client = TypedSyncClient(auth, database)
                    var sent: Int
                    do {
                        if (!current() || syncPauseFlag.get() || cancelAccountSync.get()) throw AuthFailure("SNAPSHOT_CANCELLED")
                        DeviceSessions.requireSameIdentity(expectedSession, auth.snapshot())
                        sent = client.pushPending(deviceId, 50, expectedSession, accountImportNetworkGate) {
                            if (!current() || syncPauseFlag.get() || cancelAccountSync.get() || restoreBarrier.get() || state.accountClearPending) throw AuthFailure("SNAPSHOT_CANCELLED")
                            if (EncryptedDatasetStore.activeAccountEpoch(getApplication(), owner) != epoch) throw AuthFailure("DATASET_EPOCH_CHANGED")
                        }
                        changed = changed || sent > 0
                    } while (sent > 0)
                    categoriesOnly = client.categoryCreationsOnly
                } catch (failure: Exception) {
                    changed = true // A response may have committed some results before an error.
                    categoriesOnly = false
                    throw failure
                } finally { database.close() }
                if (!changed) return AccountRefresh.NONE
                accountSummary = "本机更改已上传"
            }
            if (!current()) return AccountRefresh.NONE
            lastSyncSuccessAt = System.currentTimeMillis()
            check(syncPreferences(owner).edit().putLong("lastSuccessAt", lastSyncSuccessAt!!).commit())
            syncError = null
            syncPausedRequestUncertain = false
            syncRecoveryRequired = false
            syncedAccountEpoch = epoch
        } catch (failure: Exception) {
            if (current()) {
                if (failure is AuthFailure && (failure.retryable || failure.code in setOf("NETWORK", "SNAPSHOT_CANCELLED"))) syncPausedRequestUncertain = true
                if (failure is AuthFailure && failure.code in setOf("SNAPSHOT_REQUIRED", "DATASET_EPOCH_CHANGED")) syncRecoveryRequired = true
                syncError = structuredSyncError(failure)
            }
            if (EncryptedDatasetStore.activeAccountEpoch(getApplication(), owner) == null) throw failure
        }
        return when {
            !changed -> AccountRefresh.NONE
            categoriesOnly -> AccountRefresh.CATEGORIES
            else -> AccountRefresh.ALL
        }
    }

    private fun structuredSyncError(failure: Exception): SyncError {
        val code = (failure as? AuthFailure)?.code
        val kind = SyncControlPolicy.errorCode(code, failure is StorageOpenException)
        val text = when (kind) {
            SyncErrorCode.NETWORK -> "网络请求未确认；继续后将沿用原操作标识核对"
            SyncErrorCode.SESSION -> "账号会话需要重新确认后才能同步"
            SyncErrorCode.EPOCH_CHANGED -> if (code == "SNAPSHOT_REQUIRED") "上传回执需要完整数据确认；请点击重新获取账号数据，本机操作仍保留"
                else "服务器数据集已切换；本机操作仍保留，请在设置中重新获取账号数据"
            SyncErrorCode.CONFLICT -> "存在服务器冲突；需要人工检查并重新提交，本版本不自动解决冲突"
            SyncErrorCode.INVALID_RESPONSE -> "服务器响应未通过校验；本地数据未标记为已同步"
            SyncErrorCode.STORAGE -> "本地账号数据暂不可用；请保留应用数据后重试"
            SyncErrorCode.PAUSED -> "同步已暂停；未确认的本地操作仍保留"
            SyncErrorCode.UNKNOWN -> "上传未完成；本机操作仍保留，可重试"
        }
        return SyncError(kind, text)
    }

    override fun onCleared() {
        closed = true
        cancelAutomaticSyncRequest()
        cancelAccountSync.set(true)
        auth.cancelActiveSyncRequest()
        main.removeCallbacks(recordRefresh)
        accountUploadExecutor.shutdown()
        accountLocalExecutor.execute { closeAccountDatabase() }
        accountLocalExecutor.shutdown()
        executor.execute {
            try {
                repository?.close()
            } finally {
                repository = null
            }
        }
        executor.shutdown()
        super.onCleared()
    }

    private fun message(failure: Throwable): String =
        when (failure) {
            is AuthFailure -> failure.userMessage
            is Exception -> message(failure)
            else -> "操作未完成，请保留应用数据并重试"
        }

    private fun message(failure: Exception): String =
        when (failure) {
            is AuthFailure -> failure.userMessage
            is FinanceValidationException -> failure.userMessage
            is MoneyException -> if (failure.code == "AMOUNT_OVERFLOW") "金额超过可支持范围" else "请检查金额精度和币种"
            is RecordException -> "请检查组合金额、优惠和转账账户是否匹配"
            is StorageOpenException -> "无法打开本地账本，请保留应用数据并重试"
            else -> "操作未完成，请保留应用数据并重试"
        }
}
