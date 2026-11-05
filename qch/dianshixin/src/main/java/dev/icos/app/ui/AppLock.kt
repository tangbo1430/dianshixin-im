package dev.icos.app.ui

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.AtomicFile
import android.util.Base64
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import java.security.SecureRandom
import java.util.concurrent.Executors
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

internal data class AppLockState(
    val configured: Boolean = false,
    val unlocked: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val biometricEnabled: Boolean = false,
    val timeoutMinutes: Int = 0,
    val privacyScreen: Boolean = true,
    val integrityError: Boolean = false,
)

/** The PIN only ever lives in local state; dots and their count are shown, never the digits. */
@Composable
internal fun AppLockScreen(
    state: AppLockState,
    lock: AppLockViewModel,
    onUnlocked: () -> Unit,
    onBiometric: () -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    var firstPin by remember { mutableStateOf(CharArray(0)) }
    var confirming by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { firstPin.fill('\u0000') } }
    val setup = !state.configured
    val submit: () -> Unit = {
        if (!state.busy && pin.length == AppLockViewModel.PIN_LENGTH) {
            val entered = pin.toCharArray()
            pin = ""
            if (setup) {
                if (!confirming) {
                    firstPin = entered
                    confirming = true
                } else {
                    val same = firstPin.contentEquals(entered)
                    val setupPin = firstPin
                    firstPin = CharArray(0)
                    entered.fill('\u0000')
                    confirming = false
                    if (same) {
                        lock.configure(setupPin) { if (it) onUnlocked() }
                    } else {
                        setupPin.fill('\u0000')
                        lock.setError("两次输入不一致，请重新设置。")
                    }
                }
            } else {
                lock.unlock(entered) { if (it) onUnlocked() }
            }
        }
    }
    val append: (Char) -> Unit = { digit ->
        if (!state.busy && pin.length < AppLockViewModel.PIN_LENGTH) {
            pin += digit
            if (pin.length == AppLockViewModel.PIN_LENGTH) submit()
        }
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val compact = maxHeight < 600.dp
            val keySize = if (compact) 56.dp else 76.dp
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(
                        rememberScrollState(),
                    ).safeDrawingPadding()
                    .padding(horizontal = 24.dp, vertical = if (compact) 4.dp else 16.dp),
                verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(if (compact) 4.dp else 16.dp))
                Box(
                    Modifier.size(if (compact) 56.dp else 88.dp).background(summaryCardColor(), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    AuthIcon(
                        if (setup) AuthSymbol.SHIELD_CHECK else AuthSymbol.SHIELD_LOCK,
                        description = if (setup) "设置数字锁" else "应用已锁定",
                        modifier = Modifier.size(if (compact) 32.dp else 48.dp),
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                    )
                }
                Text(
                    when {
                        setup && confirming -> "再次输入"
                        setup -> "创建数字锁"
                        else -> "欢迎回来"
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    when {
                        setup && confirming -> "再次输入相同的 6 位数字密码"
                        setup -> "设置 6 位数字密码，保护此设备上的财务信息"
                        else -> "验证后继续使用账本"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 14.dp),
                    modifier = Modifier.semantics { contentDescription = "已输入 ${pin.length} 位数字" },
                ) {
                    repeat(AppLockViewModel.PIN_LENGTH) { index ->
                        Box(
                            Modifier.size(if (compact) 10.dp else 14.dp).background(
                                if (index < pin.length) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                CircleShape,
                            ),
                        )
                    }
                }
                Column(Modifier.heightIn(min = 22.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    state.error?.let {
                        Text(
                            it,
                            Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                        )
                    }
                    if (state.busy) {
                        CircularProgressIndicator(Modifier.size(18.dp).semantics { contentDescription = "正在验证数字密码" }, strokeWidth = 2.dp)
                    }
                }
                LockKeypad(
                    keySize = keySize,
                    compact = compact,
                    enabled = !state.busy,
                    onDigit = append,
                    onBackspace = { if (pin.isNotEmpty()) pin = pin.dropLast(1) },
                    biometric = !setup && state.biometricEnabled,
                    onBiometric = onBiometric,
                )
                if (setup && confirming) {
                    TextButton(onClick = {
                        firstPin.fill('\u0000')
                        firstPin = CharArray(0)
                        confirming = false
                        pin = ""
                    }) { Text("重新设置") }
                }
                Spacer(Modifier.height(if (compact) 4.dp else 16.dp))
            }
        }
    }
}

@Composable
private fun LockKeypad(
    keySize: androidx.compose.ui.unit.Dp,
    compact: Boolean,
    enabled: Boolean,
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    biometric: Boolean,
    onBiometric: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        listOf("123", "456", "789").forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 16.dp)) {
                row.forEach { digit -> DigitKey(digit, enabled, keySize) { onDigit(digit) } }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (biometric) {
                LockKey("使用系统生物识别解锁", enabled, keySize, onBiometric) {
                    AuthIcon(AuthSymbol.FINGERPRINT, modifier = Modifier.size(if (compact) 24.dp else 30.dp))
                }
            } else {
                Spacer(Modifier.size(keySize))
            }
            DigitKey('0', enabled, keySize) { onDigit('0') }
            LockKey("删除一位数字", enabled, keySize, onBackspace) {
                AuthIcon(AuthSymbol.BACKSPACE, modifier = Modifier.size(if (compact) 22.dp else 28.dp))
            }
        }
    }
}

@Composable
private fun DigitKey(
    digit: Char,
    enabled: Boolean,
    keySize: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
) {
    LockKey(digit.toString(), enabled, keySize, onClick) {
        Text(digit.toString(), style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Medium))
    }
}

@Composable
private fun LockKey(
    description: String,
    enabled: Boolean,
    keySize: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(keySize).semantics { contentDescription = description },
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}

internal class AppLockViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("device_app_lock", Context.MODE_PRIVATE)
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val markerFile = AtomicFile(java.io.File(application.noBackupFilesDir, "device_app_lock.initialized"))
    private val preferenceFile = java.io.File(application.applicationInfo.dataDir, "shared_prefs/device_app_lock.xml")

    @Volatile private var closed = false

    @Volatile private var activeRevision = 0
    var state by androidx.compose.runtime.mutableStateOf(load())
        private set

    private fun load(): AppLockState {
        val keys = runCatching { prefs.all }.getOrElse { emptyMap<String, Any>() }
        val backupFile = java.io.File(preferenceFile.path + ".bak")
        val hasAnyPreference = preferenceFile.exists() || backupFile.exists() || keys.isNotEmpty()
        val marker = readInitializationMarker()
        val classification = classifyLockPreferences(marker, keys, hasAnyPreference, KEY_HASH, KEY_SALT, KEY_INITIALIZED)
        val integrityError = classification == LockPreferenceState.CORRUPT
        val configured = classification == LockPreferenceState.VALID_CONFIGURED || integrityError
        return AppLockState(
            configured = configured,
            unlocked = classification == LockPreferenceState.NEVER_CONFIGURED,
            biometricEnabled = keys[KEY_BIOMETRIC] as? Boolean ?: false,
            timeoutMinutes = (keys[KEY_TIMEOUT] as? Int ?: 0).coerceIn(0, 60),
            privacyScreen = keys[KEY_PRIVACY] as? Boolean ?: true,
            integrityError = integrityError,
            error = if (integrityError) "安全设置完整性校验失败。为保护数据，应用保持锁定；请先完成恢复。" else null,
        )
    }

    private fun readInitializationMarker(): MarkerRecord? {
        val base = java.io.File(getApplication<Application>().noBackupFilesDir, "device_app_lock.initialized")
        if (!base.exists() && !java.io.File(base.path + ".bak").exists()) return null
        return try {
            markerFile.openRead().use { MarkerRecord.decode(it.readBytes()) } ?: MarkerRecord.INVALID
        } catch (
            _: Exception,
        ) {
            MarkerRecord.INVALID
        }
    }

    private fun persistInitializationMarker(values: Map<String, *>): Boolean {
        val stream =
            try {
                markerFile.startWrite()
            } catch (_: Exception) {
                return false
            }
        return try {
            stream.write(MarkerRecord.fromPreferences(values).encode())
            markerFile.finishWrite(stream)
            true
        } catch (_: Exception) {
            markerFile.failWrite(stream)
            false
        }
    }

    private fun commitPreferences(
        editor: android.content.SharedPreferences.Editor,
        pinConfigured: Boolean = state.configured,
    ): Boolean {
        editor.putBoolean(KEY_INITIALIZED, !pinConfigured)
        if (!editor.commit()) return false
        return persistInitializationMarker(prefs.all)
    }

    fun unlock(
        pin: CharArray,
        completion: (Boolean) -> Unit = {},
    ) {
        if (state.busy) {
            pin.fill('\u0000')
            return
        }
        val revision = ++activeRevision
        state = state.copy(busy = true, error = null)
        executor.execute {
            var accepted = false
            var error: String? = null
            try {
                val remaining = throttledForMillis()
                if (remaining > 0) {
                    error = "尝试过多，请等待 ${((remaining + 999) / 1000)} 秒后再试。"
                } else {
                    val stored = decode(KEY_HASH)
                    val salt = decode(KEY_SALT)
                    val derived = derive(pin, salt)
                    accepted = AppLockCrypto.matches(stored, derived)
                    derived.fill(0)
                    stored.fill(0)
                    salt.fill(0)
                    if (accepted) {
                        check(
                            commitPreferences(
                                prefs
                                    .edit()
                                    .remove(KEY_FAILURES)
                                    .remove(KEY_DEADLINE)
                                    .remove(KEY_BOOT),
                            ),
                        )
                    } else {
                        recordFailure()
                        error = "密码不正确。"
                    }
                }
            } catch (_: Exception) {
                accepted = false
                error = "安全设置读取失败，请稍后重试。"
            } finally {
                pin.fill('\u0000')
            }
            main.post {
                if (!closed && revision == activeRevision) {
                    state = state.copy(unlocked = accepted, busy = false, error = error)
                    completion(accepted)
                }
            }
        }
    }

    fun configure(
        pin: CharArray,
        completion: (Boolean) -> Unit,
    ) {
        require(pin.size == PIN_LENGTH && pin.all { it.isDigit() })
        val revision = ++activeRevision
        state = state.copy(busy = true, error = null)
        executor.execute {
            var saved = false
            try {
                val salt = ByteArray(SALT_LENGTH).also(SecureRandom()::nextBytes)
                val hash = derive(pin, salt)
                saved =
                    commitPreferences(
                        prefs.edit().putString(KEY_SALT, encode(salt)).putString(KEY_HASH, encode(hash)),
                        pinConfigured = true,
                    )
                salt.fill(0)
                hash.fill(0)
                if (saved) {
                    saved =
                        commitPreferences(
                            prefs
                                .edit()
                                .remove(KEY_FAILURES)
                                .remove(KEY_DEADLINE)
                                .remove(KEY_BOOT),
                        )
                }
            } catch (_: Exception) {
                saved = false
            } finally {
                pin.fill('\u0000')
            }
            main.post {
                if (!closed && revision == activeRevision) {
                    state =
                        state.copy(
                            configured = saved,
                            unlocked = saved,
                            busy = false,
                            error = if (saved) null else "保存安全设置失败，请重试。",
                        )
                    completion(saved)
                }
            }
        }
    }

    fun verifyThenConfigure(
        oldPin: CharArray,
        newPin: CharArray?,
        completion: (Boolean) -> Unit,
    ) {
        if (state.busy) {
            oldPin.fill('\u0000')
            newPin?.fill('\u0000')
            return
        }
        val revision = ++activeRevision
        state = state.copy(busy = true, error = null)
        executor.execute {
            var accepted = false
            var saved = false
            var error: String? = null
            try {
                val remaining = throttledForMillis()
                if (remaining > 0) {
                    error = "尝试过多，请等待 ${((remaining + 999) / 1000)} 秒后再试。"
                } else {
                    val hash = decode(KEY_HASH)
                    val salt = decode(KEY_SALT)
                    val actual = derive(oldPin, salt)
                    accepted = AppLockCrypto.matches(hash, actual)
                    hash.fill(0)
                    salt.fill(0)
                    actual.fill(0)
                    if (accepted) {
                        if (newPin ==
                            null
                        ) {
                            saved =
                                commitPreferences(
                                    prefs
                                        .edit()
                                        .remove(KEY_HASH)
                                        .remove(KEY_SALT)
                                        .remove(KEY_BIOMETRIC),
                                    pinConfigured = false,
                                )
                        } else {
                            val freshSalt = ByteArray(SALT_LENGTH).also(SecureRandom()::nextBytes)
                            val freshHash = derive(newPin, freshSalt)
                            saved =
                                commitPreferences(
                                    prefs.edit().putString(KEY_SALT, encode(freshSalt)).putString(KEY_HASH, encode(freshHash)),
                                    pinConfigured = true,
                                )
                            freshSalt.fill(0)
                            freshHash.fill(0)
                        }
                        if (!saved) {
                            error = "保存安全设置失败，请重试。"
                        } else {
                            saved =
                                commitPreferences(
                                    prefs
                                        .edit()
                                        .remove(KEY_FAILURES)
                                        .remove(KEY_DEADLINE)
                                        .remove(KEY_BOOT),
                                pinConfigured = newPin != null,
                            )
                        }
                    } else {
                        recordFailure()
                        error = "当前密码不正确。"
                    }
                }
            } catch (_: Exception) {
                error = "安全设置读取或保存失败。"
            } finally {
                oldPin.fill('\u0000')
                newPin?.fill('\u0000')
            }
            main.post {
                if (!closed && revision == activeRevision) {
                    state =
                        state.copy(
                            configured = if (accepted && saved) newPin != null else state.configured,
                            unlocked = state.unlocked,
                            busy = false,
                            error = error,
                        )
                    completion(accepted && saved)
                }
            }
        }
    }

    fun setBiometric(
        enabled: Boolean,
        completion: (Boolean) -> Unit,
    ) {
        executor.execute {
            val saved =
                runCatching {
                    if (enabled) check(state.configured)
                    commitPreferences(prefs.edit().putBoolean(KEY_BIOMETRIC, enabled))
                }.getOrDefault(false)
            main.post {
                if (!closed) {
                    if (saved) {
                        state = state.copy(biometricEnabled = enabled, error = null)
                    } else {
                        state = state.copy(error = "保存生物识别设置失败。")
                    }
                }
                completion(saved)
            }
        }
    }

    fun setTimeout(minutes: Int) {
        require(minutes in TIMEOUTS)
        executor.execute {
            val saved = runCatching { commitPreferences(prefs.edit().putInt(KEY_TIMEOUT, minutes)) }.getOrDefault(false)
            main.post { if (!closed && saved) state = state.copy(timeoutMinutes = minutes) }
        }
    }

    fun setPrivacyScreen(enabled: Boolean) {
        executor.execute {
            val saved = runCatching { commitPreferences(prefs.edit().putBoolean(KEY_PRIVACY, enabled)) }.getOrDefault(false)
            main.post { if (!closed && saved) state = state.copy(privacyScreen = enabled) }
        }
    }

    fun requestSetup() {
        state = state.copy(error = null)
    }

    fun setError(message: String) {
        state = state.copy(error = message)
    }

    fun lock() {
        ++activeRevision
        state = state.copy(unlocked = false, busy = false, error = null)
    }

    fun unlockWithBiometric() {
        state = state.copy(unlocked = true, error = null)
    }

    private fun throttledForMillis(): Long {
        val failures = prefs.getInt(KEY_FAILURES, 0)
        if (failures <= 0) return 0
        val nowBoot = bootCount()
        val deadline = prefs.getLong(KEY_DEADLINE, 0)
        val savedBoot = prefs.getInt(KEY_BOOT, nowBoot)
        if (savedBoot != nowBoot) {
            val duration = AppLockCrypto.lockoutDuration(failures)
            val newDeadline = android.os.SystemClock.elapsedRealtime() + duration
            check(commitPreferences(prefs.edit().putLong(KEY_DEADLINE, newDeadline).putInt(KEY_BOOT, nowBoot)))
            return duration
        }
        return (deadline - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0)
    }

    private fun recordFailure() {
        val failures = (prefs.getInt(KEY_FAILURES, 0) + 1).coerceAtMost(30)
        val duration = AppLockCrypto.lockoutDuration(failures)
        check(
            commitPreferences(
                prefs
                    .edit()
                    .putInt(KEY_FAILURES, failures)
                    .putLong(KEY_DEADLINE, android.os.SystemClock.elapsedRealtime() + duration)
                    .putInt(KEY_BOOT, bootCount()),
            ),
        )
    }

    private fun bootCount() =
        runCatching {
            Settings.Global.getInt(getApplication<Application>().contentResolver, Settings.Global.BOOT_COUNT)
        }.getOrDefault(0)

    private fun decode(key: String) = Base64.decode(requireNotNull(prefs.getString(key, null)), Base64.NO_WRAP)

    private fun derive(
        pin: CharArray,
        salt: ByteArray,
    ): ByteArray {
        val spec = PBEKeySpec(pin, salt, ITERATIONS, HASH_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)

    override fun onCleared() {
        closed = true
        executor.shutdown()
        super.onCleared()
    }

    companion object {
        const val PIN_LENGTH = 6
        const val ITERATIONS = 310_000
        const val SALT_LENGTH = 16
        const val HASH_BITS = 256
        val TIMEOUTS = listOf(0, 1, 5, 15, 30, 60)
        private const val KEY_INITIALIZED = "lock_initialized"
        private const val KEY_HASH = "pin_hash"
        private const val KEY_SALT = "pin_salt"
        private const val KEY_FAILURES = "failed_attempts"
        private const val KEY_DEADLINE = "lockout_elapsed_deadline"
        private const val KEY_BOOT = "lockout_boot_count"
        private const val KEY_BIOMETRIC = "biometric_enabled"
        private const val KEY_TIMEOUT = "timeout_minutes"
        private const val KEY_PRIVACY = "privacy_screen"
    }
}
