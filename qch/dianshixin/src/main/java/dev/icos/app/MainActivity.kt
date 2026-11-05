package dev.icos.app

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.icos.app.data.remote.ConsentRecord
import dev.icos.app.ui.AppLockViewModel
import dev.icos.app.ui.AppearanceSettingsViewModel
import dev.icos.app.ui.AuthViewModel
import dev.icos.app.ui.BackupSettingsViewModel
import dev.icos.app.ui.BoundSession
import dev.icos.app.ui.FILE_PICKER_MIME_TYPES
import dev.icos.app.ui.FeedbackViewModel
import dev.icos.app.ui.FileIoHost
import dev.icos.app.ui.FilePickerKind
import dev.icos.app.ui.FilePickerLaunch
import dev.icos.app.ui.FilePickerResult
import dev.icos.app.ui.FinanceApp
import dev.icos.app.ui.FinanceViewModel
import dev.icos.app.ui.ReceiptExport
import dev.icos.app.ui.ReceiptExportHost
import dev.icos.app.ui.ReminderScheduler
import dev.icos.app.ui.verifiedReceiptSession
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var lock: AppLockViewModel
    private var model: FinanceViewModel? = null
    private var auth: AuthViewModel? = null
    private var appearance: AppearanceSettingsViewModel? = null
    private var feedback: FeedbackViewModel? = null
    private var backups: BackupSettingsViewModel? = null
    private var pendingIntent: Intent? = null
    private lateinit var receiptExport: ReceiptExport
    private lateinit var fileIoHost: FileIoHost
    private var lastBackgroundElapsed = 0L
    private var startedOnce = false
    private var cancellationSignal: CancellationSignal? = null
    private lateinit var gate: MainActivityGate
    private var agreementsChecked: Boolean
        get() = gate.agreementsChecked
        set(value) {
            gate.agreementsChecked = value
        }
    private var networkAllowed: Boolean
        get() = gate.networkAllowed
        set(value) {
            gate.networkAllowed = value
        }
    private var showLegalGate: Boolean
        get() = gate.showLegalGate
        set(value) {
            gate.showLegalGate = value
        }
    private var notificationPermissionGranted by mutableStateOf(Build.VERSION.SDK_INT < 33)
    private var notificationPermissionDenied by mutableStateOf(false)
    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            notificationPermissionGranted = granted
            notificationPermissionDenied = !granted
        }

    // Keep the existing image contract registration ahead of the new file contracts.
    private val saveReceipt =
        registerForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
            if (::receiptExport.isInitialized) receiptExport.deliverPickerResult(uri)
        }

    // All file pickers are registered once, outside the lock/agreement gate. A callback only records the
    // raw result in [MainActivityGate]; no file is read or written and no FinanceViewModel is created here.
    private val openFileDocument =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            gate.deliverFilePickerResult(FilePickerKind.OPEN_DOCUMENT, uri)
        }
    private val createFileCsv =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
            gate.deliverFilePickerResult(FilePickerKind.CREATE_CSV, uri)
        }
    private val createFileXlsx =
        registerForActivityResult(
            ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
        ) { uri ->
            gate.deliverFilePickerResult(FilePickerKind.CREATE_XLSX, uri)
        }

    fun requestReminderNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun reminderPermissionStatus(): String =
        when {
            Build.VERSION.SDK_INT < 33 -> "系统不需要通知授权"
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED -> "已授权"
            notificationPermissionDenied -> "未授权；提醒计划已保存，但系统不会显示通知"
            else -> "尚未授权"
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        gate = ViewModelProvider(this)[MainActivityGate::class.java]
        if (!agreementsChecked) {
            ConsentRecord.startupNetworkAllowed(applicationContext)?.let { allowed ->
                networkAllowed = allowed
                agreementsChecked = true
            }
        }
        appearance = ViewModelProvider(this)[AppearanceSettingsViewModel::class.java]
        lock = ViewModelProvider(this)[AppLockViewModel::class.java]
        if (lock.state.configured) lock.lock()
        // Registered outside the lock/agreement gate so a picker result survives both and is never re-bound.
        receiptExport = ViewModelProvider(this)[ReceiptExport::class.java]
        receiptExport.bindHost(
            ReceiptExportHost(
                owner = this,
                launchPicker = { name -> saveReceipt.launch(name) },
                snapshot = { model?.state?.snapshot },
                session = ::receiptExportSession,
                settled = ::receiptSessionSettled,
                unlocked = { lock.state.unlocked && agreementsChecked && !showLegalGate },
            ),
        )
        pendingIntent = intent
        fileIoHost =
            FileIoHost(
                owner = this,
                launchOpenDocument = { nonce ->
                    gate.beginPicker(FilePickerKind.OPEN_DOCUMENT, nonce)
                    openFileDocument.launch(FILE_PICKER_MIME_TYPES)
                },
                launchCreateCsv = { nonce, fileName ->
                    gate.beginPicker(FilePickerKind.CREATE_CSV, nonce)
                    createFileCsv.launch(fileName)
                },
                launchCreateXlsx = { nonce, fileName ->
                    gate.beginPicker(FilePickerKind.CREATE_XLSX, nonce)
                    createFileXlsx.launch(fileName)
                },
                unlocked = { lock.state.unlocked && agreementsChecked && !showLegalGate && receiptSessionSettled() },
                session = ::receiptExportSession,
            )
        ReminderScheduler.restore(applicationContext)
        setContent {
            dev.icos.app.ui.IcosTheme(
                appearance?.state?.settings ?: dev.icos.app.ui
                    .AppearanceSettings(),
            ) {
                LaunchedEffect(lock.state.unlocked, showLegalGate, agreementsChecked) { continueAfterGate() }
                LaunchedEffect(lock.state.unlocked) { if (!lock.state.unlocked) model?.onAppLocked() }
                LaunchedEffect(lock.state.unlocked, agreementsChecked, showLegalGate, networkAllowed) {
                    if (!lock.state.unlocked || !agreementsChecked || showLegalGate || !networkAllowed) return@LaunchedEffect
                    gate.checkLegalUpdates(applicationContext)
                }
                if (!lock.state.unlocked) {
                    dev.icos.app.ui.AppLockScreen(
                        lock.state,
                        lock,
                        // Unlocking (PIN, first PIN, biometric) keeps the agreement state; only that state may open the gate.
                        onUnlocked = {},
                        onBiometric = ::authenticateBiometric,
                    )
                } else if (showLegalGate || !agreementsChecked) {
                    dev.icos.app.ui.FirstRunAgreementGate { allowed ->
                        networkAllowed = allowed
                        agreementsChecked = true
                        showLegalGate = false
                        if (!allowed) {
                            model = null
                            auth = null
                        }
                        ensureFinanceModels()
                        if (allowed) auth!!.enableAccountMode()
                        continueAfterGate()
                    }
                } else {
                    val finance = ensureFinanceModels()
                    FinanceApp(
                        finance,
                        auth!!,
                        appearance!!,
                        lock,
                        receiptExport,
                        onSessionReady = ::continueAfterGate,
                        notificationPermissionStatus = ::reminderPermissionStatus,
                        requestNotificationPermission = ::requestReminderNotificationPermission,
                    )
                }
            }
        }
        applyPrivacyFlag()
    }

    private fun ensureFinanceModels(): FinanceViewModel {
        model?.let {
            it.bindFileIoHost(fileIoHost)
            return it
        }
        val finance = ViewModelProvider(this)[FinanceViewModel::class.java]
        model = finance
        finance.bindFileIoHost(fileIoHost)
        auth = ViewModelProvider(this)[AuthViewModel::class.java]
        auth!!.bindAvatarMediaHost(this) { lock.state.unlocked && agreementsChecked && !showLegalGate && networkAllowed }
        if (!networkAllowed) auth!!.useGuestMode()
        appearance = ViewModelProvider(this)[AppearanceSettingsViewModel::class.java]
        feedback = ViewModelProvider(this)[FeedbackViewModel::class.java]
        backups = ViewModelProvider(this)[BackupSettingsViewModel::class.java]
        return finance
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingIntent = intent
        continueAfterGate()
    }

    override fun onStart() {
        super.onStart()
        if (startedOnce && lock.state.unlocked) {
            val timeout = lock.state.timeoutMinutes
            if (timeout == 0 || SystemClock.elapsedRealtime() - lastBackgroundElapsed >= timeout * 60_000L) lock.lock()
        }
        startedOnce = true
    }

    override fun onStop() {
        cancellationSignal?.cancel()
        cancellationSignal = null
        if (lock.state.unlocked) lastBackgroundElapsed = SystemClock.elapsedRealtime()
        super.onStop()
    }

    /**
     * Local exports resume only when the loaded auth mode and device session equal the finance binding.
     * Recheck after unlocking so a cached export cannot write data for a replaced owner or session.
     */
    private fun receiptExportSession(): BoundSession? {
        val authModel = auth ?: return null
        val authState = authModel.state
        return verifiedReceiptSession(
            bound = model?.boundSession,
            settled = authState.ready && !authState.busy,
            guest = authModel.isGuestMode,
            userId = authState.user?.id,
            identity = authModel.cachedDeviceIdentity,
        )
    }

    /** True once auth has no operation in flight, so a null verified session is a real mismatch and not a wait. */
    private fun receiptSessionSettled(): Boolean = auth?.state?.let { it.ready && !it.busy } == true

    private fun continueAfterGate() {
        if (!lock.state.unlocked || !agreementsChecked || showLegalGate) return
        receiptExport.resume()
        val finance = model ?: return
        // Results wait in the gate while locked, while the agreement gate is open, and until auth settles.
        if (!receiptSessionSettled()) return
        val session = receiptExportSession()
        gate.takeFilePickerResults().forEach { result ->
            finance.deliverFilePickerResult(result.nonce, result.kind, result.uri, session)
        }
        finance.resumeFileIo(session)
        if (session != null) forwardPendingIntent(finance)
    }

    override fun onDestroy() {
        auth?.unbindAvatarMediaHost(this)
        if (::receiptExport.isInitialized) receiptExport.unbindHost(this)
        model?.unbindFileIoHost(this)
        super.onDestroy()
    }

    private fun forwardPendingIntent(model: FinanceViewModel) {
        val target = pendingIntent ?: return
        model.acceptIncomingFile(target.fileIoUri(), target.hasFileIoReadGrant(), incomingIntentToken(target))
        pendingIntent = null
    }

    private fun authenticateBiometric() {
        if (Build.VERSION.SDK_INT < 28) return
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (!keyguard.isDeviceSecure) return
        val prompt =
            BiometricPrompt
                .Builder(this)
                .setTitle("解锁 ICOS")
                .setSubtitle("使用设备生物识别验证")
                .setNegativeButton("使用数字密码", mainExecutor) { _, _ -> }
                .build()
        cancellationSignal = CancellationSignal()
        prompt.authenticate(
            cancellationSignal!!,
            mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    lock.unlockWithBiometric()
                }
            },
        )
    }

    private fun applyPrivacyFlag() {
        // Temporarily allow screenshots while retaining the saved privacy preference.
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    override fun onResume() {
        super.onResume()
        if (::lock.isInitialized) applyPrivacyFlag()
    }

    private fun incomingIntentToken(intent: Intent?): String =
        intent?.let {
            val uri = it.fileIoUri().orEmpty()
            val action = it.action.orEmpty()
            val sender = it.`package`.orEmpty()
            java.security.MessageDigest
                .getInstance(
                    "SHA-256",
                ).digest("$action\u0000$sender\u0000$uri".toByteArray())
                .joinToString("") { byte -> "%02x".format(byte) }
        } ?: "none"

    private fun Intent?.fileIoUri(): String? =
        this
            ?.takeIf { action == Intent.ACTION_SEND }
            ?.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
            ?.toString()

    private fun Intent.hasFileIoReadGrant(): Boolean = flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0
}

/** Agreement UI and picker transport survive rotation; cold starts restore the persisted consent choice. */
internal class MainActivityGate : ViewModel() {
    var agreementsChecked by mutableStateOf(false)
    var networkAllowed by mutableStateOf(false)
    var showLegalGate by mutableStateOf(false)
    private var legalUpdateCheckStarted = false

    /** One read per retained gate, even across rotation or re-locking; it does not authorize networking. */
    fun checkLegalUpdates(context: Context) {
        if (legalUpdateCheckStarted || !agreementsChecked || !networkAllowed || showLegalGate) return
        val app = context.applicationContext
        val savedPrivacy = ConsentRecord.read(app, "privacy")
        val savedTerms = ConsentRecord.read(app, "terms")
        legalUpdateCheckStarted = true
        viewModelScope.launch {
            // Existing consent opens local data immediately. Offline/cache failures preserve that choice.
            val latest = dev.icos.app.ui.loadPublishedLegal(app)
            if (!networkAllowed || !agreementsChecked || showLegalGate ||
                ConsentRecord.startupNetworkAllowed(app) != true ||
                ConsentRecord.read(app, "privacy") != savedPrivacy || ConsentRecord.read(app, "terms") != savedTerms
            ) return@launch
            val privacy = latest.privacy
            val terms = latest.terms
            if (!latest.cached && latest.errorCode == null && privacy != null && terms != null &&
                (!ConsentRecord.matches(app, privacy) || !ConsentRecord.matches(app, terms))
            ) {
                showLegalGate = true
            }
        }
    }

    // Picker transport only: what was launched and what came back. No file content, URI or request here.
    private var pendingPicker: FilePickerLaunch? = null
    private val pickerResults = mutableStateListOf<FilePickerResult>()

    fun beginPicker(
        kind: FilePickerKind,
        nonce: String,
    ) {
        pendingPicker = FilePickerLaunch(nonce, kind)
    }

    fun deliverFilePickerResult(
        kind: FilePickerKind,
        uri: android.net.Uri?,
    ) {
        val launch = pendingPicker?.takeIf { it.kind == kind }
        pendingPicker = null
        pickerResults += FilePickerResult(launch?.nonce.orEmpty(), kind, uri?.toString())
    }

    fun takeFilePickerResults(): List<FilePickerResult> {
        if (pickerResults.isEmpty()) return emptyList()
        val taken = pickerResults.toList()
        pickerResults.clear()
        return taken
    }
}
