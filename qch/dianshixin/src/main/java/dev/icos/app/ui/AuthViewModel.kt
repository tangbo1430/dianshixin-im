package dev.icos.app.ui

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import dev.icos.app.data.remote.AuthChallenge
import dev.icos.app.data.remote.AuthClient
import dev.icos.app.data.remote.AuthFailure
import dev.icos.app.data.remote.AuthUser
import dev.icos.app.data.remote.AvatarBmp
import dev.icos.app.data.remote.AvatarStatus
import dev.icos.app.data.remote.AvatarUploadGrant
import dev.icos.app.data.remote.AccountDeletionReceipt
import dev.icos.app.data.remote.DeviceIdentity
import dev.icos.app.data.remote.DeviceSession
import dev.icos.app.data.remote.DeviceSessions
import dev.icos.app.data.remote.DeviceSessionsState
import dev.icos.app.data.remote.PendingReset
import java.util.UUID
import java.util.concurrent.Executors

internal enum class AuthForm(
    val label: String,
) {
    LOGIN("登录"),
    REGISTER("注册"),
    RESET("重置密码"),
}

internal data class AuthUiState(
    val user: AuthUser? = null,
    val sessionIdentity: DeviceIdentity? = null,
    val confirmed: Boolean = false,
    val ready: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val challenge: AuthChallenge? = null,
    val reset: PendingReset? = null,
    val waitUntil: Long = 0,
    val retryAvailable: Boolean = false,
    val retryIsDevice: Boolean = false,
    val devices: DeviceSessionsState? = null,
    val deviceBusy: Boolean = false,
    val revokeConfirm: DeviceSession? = null,
    val deletionReceipt: AccountDeletionReceipt? = null,
    val deletionReady: Boolean = false,
    val deletionConfirmationChecked: Boolean = false,
)

/** Form secrets and retry closures live only in this ViewModel, never SavedStateHandle or Bundles. */
internal class AuthViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val client = AuthClient(application)
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var closed = false
    @Volatile private var guestMode = false
    private var attempt: Attempt? = null
    private var devicesIdentity: DeviceIdentity? = null
    private var deletionActionToken: String? = null
    private var deletionReauthOpId: UUID? = null
    private var deletionRequestOpId: UUID? = null
    private var deletionRequestSnapshot: dev.icos.app.data.remote.AuthState? = null
    private var deletionReauthCredential: Attempt? = null
    private var deletionSubmission: Attempt? = null
    var accountDeletionOpen by mutableStateOf(false)
        private set
    val configured: Boolean get() = client.configured
    val isGuestMode: Boolean get() = guestMode
    // Cached credentials identify local account data; confirmed remains server verification only.
    val cachedDeviceIdentity: DeviceIdentity?
        get() = state.sessionIdentity?.takeIf { !guestMode && state.ready && it.owner == state.user?.id }
    val sessionIdentity: DeviceIdentity? get() = state.sessionIdentity
    val canDiscardDeletionRetry: Boolean get() = deletionSubmission == null && deletionRequestOpId == null
    var state by mutableStateOf(AuthUiState())
        private set
    var visible by mutableStateOf(false)
        private set
    var form by mutableStateOf(AuthForm.LOGIN)
        private set
    var email by mutableStateOf("")
        private set
    var secret by mutableStateOf("")
        private set
    var code by mutableStateOf("")
        private set
    private var nicknameDraftIdentity: DeviceIdentity? = null
    var nicknameDraft by mutableStateOf("")
        private set
    private var avatarAttemptRef: Attempt? = null
    private var avatarMediaHostOwner: Any? = null
    private var avatarMediaHost: (() -> Boolean)? = null
    val avatarMediaAllowed: Boolean get() = avatarMediaHost?.invoke() == true
    var avatarBitmap by mutableStateOf<android.graphics.Bitmap?>(null)
        private set

    fun bindAvatarMediaHost(owner: Any, allowed: () -> Boolean) {
        avatarMediaHostOwner = owner
        avatarMediaHost = allowed
    }

    fun unbindAvatarMediaHost(owner: Any) {
        if (avatarMediaHostOwner === owner) {
            avatarMediaHostOwner = null
            avatarMediaHost = null
        }
    }

    init {
        reload()
    }

    fun useGuestMode() {
        guestMode = true
        state = AuthUiState(ready = true)
        visible = false
        clearForm()
        attempt = null
        devicesIdentity = null
        nicknameDraftIdentity = null
        nicknameDraft = ""
        avatarAttemptRef = null
        avatarBitmap = null
    }

    fun enableAccountMode() {
        guestMode = false
        refreshSessionIdentityAsync()
        reload()
    }

    fun open() {
        if (guestMode) return
        visible = true
        if (!state.busy && state.ready && state.user != null && configured) refreshProfile()
    }

    fun dismiss() {
        if (state.busy || accountDeletionOpen) return
        visible = false
        clearForm()
        val discardedDeviceAttempt = state.retryAvailable && attempt?.kind?.isDevice() == true
        if (attempt?.kind?.isDevice() == true) {
            // An ambiguous revoke may already have completed; reopen with a fresh server list.
            devicesIdentity = null
            state = state.copy(devices = null)
        }
        attempt = null
        state =
            state.copy(
                error = null,
                message =
                if (discardedDeviceAttempt) {
                    "已关闭撤销重试；该请求可能已在服务器完成，请重新加载设备列表确认状态。"
                } else {
                    null
                },
                retryAvailable = false,
                retryIsDevice = false,
                challenge = null,
                revokeConfirm = null,
                deviceBusy = false,
            )
    }

    fun chooseForm(value: AuthForm) {
        if (!editable() || state.reset != null) return
        clearForm()
        form = value
        state = state.copy(error = null, message = null, challenge = null)
    }

    fun updateEmail(value: String) {
        if (editable() && state.challenge == null && state.reset == null) email = value
    }

    fun updateSecret(value: String) {
        if (editable()) secret = value
    }

    fun updateCode(value: String) {
        if (editable()) code = value
    }

    fun reload() =
        start {
            Attempt(Kind.LOAD) { Outcome() }
        }

    private fun refreshSessionIdentityAsync() {
        if (closed) return
        executor.execute {
            val identity = runCatching { client.snapshot().let { snapshot -> snapshot.session?.let { DeviceSessions.identity(snapshot) } } }.getOrNull()
            main.post {
                if (!closed && !guestMode && identity != state.sessionIdentity) {
                    nicknameDraftIdentity = null
                    nicknameDraft = ""
                    val invalidatedAvatarRetry = attempt?.kind == Kind.AVATAR_UPLOAD
                    avatarBitmap = null
                    avatarAttemptRef = null
                    attempt = attempt?.takeUnless { it.kind == Kind.AVATAR_UPLOAD }
                    state = state.copy(
                        sessionIdentity = identity,
                        retryAvailable = if (invalidatedAvatarRetry) false else state.retryAvailable,
                        retryIsDevice = if (invalidatedAvatarRetry) false else state.retryIsDevice,
                    )
                }
            }
        }
    }

    fun requestChallenge() {
        if (form != AuthForm.RESET || email.isBlank() || SystemClock.elapsedRealtime() < state.waitUntil) return
        val address = state.reset?.email ?: email
        val opId = UUID.randomUUID()
        start {
            Attempt(
                Kind.CHALLENGE,
            ) { Outcome(challenge = client.challenge(address, "RESET", opId), message = "若邮箱符合条件，验证码将发送至该邮箱。验证码5分钟有效。") }
        }
    }

    fun newChallenge() {
        if (!editable()) return
        if (state.reset != null) secret = ""
        code = ""
        state = state.copy(challenge = null, message = null, error = null)
    }

    fun submit() {
        if (!editable() || !state.ready) return
        val pendingReset = state.reset
        val selected = if (pendingReset == null) form else AuthForm.RESET
        val challenge = state.challenge ?: pendingReset?.let { AuthChallenge(it.challengeId, 0, 0) }
        if (email.isBlank() || secret.codePointCount(0, secret.length) !in 12..128 ||
            selected == AuthForm.RESET && (challenge == null || !code.matches(Regex("[0-9]{6}")))
        ) {
            state = state.copy(error = AuthFailure("INVALID_INPUT").userMessage)
            return
        }
        val recoveringOriginal = selected == AuthForm.RESET && pendingReset != null && challenge?.id == pendingReset.challengeId
        if (selected == AuthForm.RESET && !recoveringOriginal && challenge!!.expiresAtMillis <= System.currentTimeMillis()) {
            state = state.copy(error = "验证码已过期，请重新申请", challenge = null)
            code = ""
            return
        }
        val address = pendingReset?.email ?: email
        val passwordInput = secret
        val otp = code
        val opId = if (recoveringOriginal) pendingReset!!.opId else UUID.randomUUID()
        start {
            if (selected == AuthForm.RESET) {
                val fenced = client.prepareReset(address, challenge!!.id, opId)
                Attempt(Kind.RESET) {
                    client.reset(fenced, otp, passwordInput)
                    Outcome(message = "密码已重置，请重新登录")
                }
            } else {
                val snapshot = client.snapshot()
                snapshot.requireSignIn()
                Attempt(Kind.SIGN_IN) {
                    val result =
                        client.signIn(
                            snapshot,
                            address,
                            passwordInput,
                            opId,
                            register = selected == AuthForm.REGISTER,
                        )
                    Outcome(user = result.session!!.user, confirmed = true, message = "已登录服务器账号；正在准备账号独立账本并从服务器快照同步。本机访客账本保持独立。")
                }
            }
        }
    }

    fun uploadAvatar(uri: android.net.Uri) {
        if (!editable() || !state.confirmed || !avatarMediaAllowed) {
            state = state.copy(error = "账号未就绪或应用已锁定，请重新打开头像后重试")
            return
        }
        val identity = state.sessionIdentity ?: return
        val opId = UUID.randomUUID()
        var payload: ByteArray? = null
        var digest: String? = null
        var uploadGrant: dev.icos.app.data.remote.AvatarUploadGrant? = null
        val operation = Attempt(Kind.AVATAR_UPLOAD) {
            DeviceSessions.requireSameIdentity(identity, client.snapshot())
            if (!avatarMediaAllowed) throw AuthFailure("STALE_CREDENTIALS")
            if (payload == null) {
                val collected = try {
                    AvatarImages.readUpload(getApplication(), uri)
                } catch (_: java.io.IOException) {
                    throw AuthFailure("AVATAR_INPUT_INVALID")
                } catch (_: IllegalArgumentException) {
                    throw AuthFailure("AVATAR_INPUT_INVALID")
                } catch (_: SecurityException) {
                    throw AuthFailure("AVATAR_INPUT_INVALID")
                }
                payload = collected
                digest = java.security.MessageDigest.getInstance("SHA-256").digest(collected).joinToString("") { "%02x".format(it) }
            }
            val imageBytes = checkNotNull(payload)
            val imageDigest = checkNotNull(digest)
            val grant = uploadGrant ?: client.authorizeAvatarUpload(identity, imageBytes, imageDigest, opId).also { uploadGrant = it }
            var status = client.avatarStatus(identity, grant.attachmentId)
            if (status.state == "UPLOADING") {
                client.putAvatarUpload(identity, grant, imageBytes)
                status = client.avatarStatus(identity, grant.attachmentId)
            }
            if (status.state != "READY") status = client.completeAvatarUpload(identity, grant.attachmentId, imageDigest, opId)
            if (status.state != "READY" || status.sha256 != imageDigest) throw AuthFailure("INVALID_RESPONSE", retryable = true)
            val user = client.setAvatar(identity, grant.attachmentId, opId)
            DeviceSessions.requireSameIdentity(identity, client.snapshot())
            Outcome(user = user, confirmed = true, message = "本人头像已上传并设置", profileIdentity = identity, avatarBitmap = AvatarBmp.decode(imageBytes))
        }
        avatarAttemptRef = operation
        start { operation }
    }

    fun reportAvatarInputError() {
        state = state.copy(error = "无法读取图片或图片不符合格式与尺寸限制，请重新选择不超过20MB的图片")
    }

    fun retryAvatarUpload() {
        val current = avatarAttemptRef ?: return
        start(retrying = true) { current }
    }

    fun clearAvatar() {
        if (!editable() || !state.confirmed || state.user?.avatarId == null) return
        val identity = state.sessionIdentity ?: return
        val opId = UUID.randomUUID()
        val operation = Attempt(Kind.AVATAR_CLEAR) {
            DeviceSessions.requireSameIdentity(identity, client.snapshot())
            val user = client.setAvatar(identity, null, opId)
            DeviceSessions.requireSameIdentity(identity, client.snapshot())
            Outcome(user = user, confirmed = true, message = "本人头像已清除", profileIdentity = identity)
        }
        start { operation }
    }

    fun loadAvatar() {
        if (!state.confirmed || state.user?.avatarId == null || state.busy) return
        val identity = state.sessionIdentity ?: return
        val avatarId = state.user?.avatarId ?: return
        start {
            Attempt(Kind.AVATAR_LOAD) {
                DeviceSessions.requireSameIdentity(identity, client.snapshot())
                val grant = client.authorizeAvatarDownload(identity, avatarId)
                val bytes = client.downloadAvatar(identity, avatarId, grant)
                if (!AvatarBmp.isSupported(bytes)) throw AuthFailure("INVALID_RESPONSE")
                val bitmap = AvatarBmp.decode(bytes) ?: throw AuthFailure("INVALID_RESPONSE")
                DeviceSessions.requireSameIdentity(identity, client.snapshot())
                Outcome(avatarBitmap = bitmap, profileIdentity = identity)
            }
        }
    }

    fun refreshProfile() = start { Attempt(Kind.PROFILE) { Outcome(user = client.profile(), confirmed = true, message = "已从服务器更新本人资料") } }

    fun updateNickname(value: String) {
        val confirmedUser = state.user ?: return
        if (!editable() || !state.confirmed) return
        val identity = state.sessionIdentity ?: return
        if (nicknameDraftIdentity != identity) {
            nicknameDraftIdentity = identity
            nicknameDraft = confirmedUser.nickname
        }
        nicknameDraft = value
    }

    fun saveNickname() {
        if (!editable() || !state.confirmed) return
        val confirmedUser = state.user ?: return
        val identity = state.sessionIdentity ?: return
        if (nicknameDraftIdentity != identity) nicknameDraft = confirmedUser.nickname
        nicknameDraftIdentity = identity
        val rawNickname = nicknameDraft
        if (rawNickname.any { Character.isISOControl(it) }) {
            state = state.copy(error = "昵称需为1–32个字符，且不能含控制字符")
            return
        }
        val nickname = rawNickname.trim()
        if (nickname.isEmpty() || nickname.codePointCount(0, nickname.length) !in 1..32) {
            state = state.copy(error = "昵称需为1–32个字符，且不能含控制字符")
            return
        }
        val opId = UUID.randomUUID()
        val operation = Attempt(Kind.PROFILE_UPDATE) {
            DeviceSessions.requireSameIdentity(identity, client.snapshot())
            val user = client.updateProfile(identity, nickname, opId)
            DeviceSessions.requireSameIdentity(identity, client.snapshot())
            Outcome(user = user, confirmed = true, message = "本人昵称已更新", profileIdentity = identity)
        }
        start { operation }
    }

    fun logout() {
        val opId = UUID.randomUUID()
        start {
            val logout = client.prepareLogout(opId)
            Attempt(Kind.LOGOUT) {
                client.logout(logout)
                Outcome(message = "已退出，服务器会话已撤销。账号账本已封存，本机访客账本恢复。")
            }
        }
    }

    fun openAccountDeletion() {
        if (state.user == null || !state.confirmed || state.busy || state.retryAvailable || deletionRequestOpId != null) return
        accountDeletionOpen = true
        deletionActionToken = null
        deletionReauthOpId = null
        deletionRequestOpId = null
        deletionReauthCredential = null
        deletionSubmission = null
        state = state.copy(retryAvailable = false, deletionReady = false, deletionConfirmationChecked = false, deletionReceipt = null, error = null, message = null)
    }

    fun closeAccountDeletion() {
        if (state.busy || state.retryAvailable && deletionRequestOpId != null) return
        accountDeletionOpen = false
        deletionReauthCredential = null
        deletionSubmission = null
        deletionActionToken = null
        deletionReauthOpId = null
        deletionRequestOpId = null
        deletionRequestSnapshot = null
        attempt = null
        clearForm()
        state = state.copy(
            retryAvailable = false,
            retryIsDevice = false,
            deletionReady = false,
            deletionConfirmationChecked = false,
            error = null,
            message = null,
        )
    }

    fun setDeletionReady(ready: Boolean) {
        if (!accountDeletionOpen || state.busy || state.retryAvailable) return
        if (!ready) {
            deletionActionToken = null
            deletionReauthCredential = null
            deletionReauthOpId = null
        }
        state = state.copy(
            deletionConfirmationChecked = ready,
            deletionReady = if (ready) state.deletionReady else false,
            error = null,
        )
    }

    fun confirmDeletionReauth() {
        if (!accountDeletionOpen || state.busy || !state.deletionConfirmationChecked || state.deletionReady || secret.codePointCount(0, secret.length) !in 12..128) return
        val ownerId = state.user?.id ?: return
        val opId = deletionReauthOpId ?: UUID.randomUUID().also { deletionReauthOpId = it }
        val password = secret
        clearForm()
        var pinnedSnapshot: dev.icos.app.data.remote.AuthState? = null
        val operation = Attempt(Kind.ACCOUNT_DELETE_REAUTH) {
            val pinned = pinnedSnapshot ?: client.snapshot().also {
                if (it.session?.user?.id != ownerId) throw AuthFailure("STALE_CREDENTIALS")
                pinnedSnapshot = it
            }
            deletionActionToken = client.reauthenticateForAccountDeletion(pinned, password, opId)
            Outcome(deletionReady = true, message = "已完成当前会话的注销重新验证；再次确认后提交不可撤销的注销请求。")
        }
        deletionReauthCredential = operation
        start { operation }
    }

    fun confirmAccountDeletion() {
        if (!accountDeletionOpen || state.busy || !state.deletionReady || !state.deletionConfirmationChecked) return
        val token = deletionActionToken ?: return
        val opId = deletionRequestOpId ?: UUID.randomUUID().also { deletionRequestOpId = it }
        var pinnedSnapshot = deletionRequestSnapshot
        val operation = Attempt(Kind.ACCOUNT_DELETE_SUBMIT) {
            val original = pinnedSnapshot ?: client.snapshot().also {
                if (it.session?.user?.id != state.user?.id) throw AuthFailure("STALE_CREDENTIALS")
                pinnedSnapshot = it
                deletionRequestSnapshot = it
            }
            val current = client.snapshot()
            if (!current.matches(original)) throw AuthFailure("STALE_CREDENTIALS")
            val result = client.submitAccountDeletion(original, token, opId)
            Outcome(
                deletionReceipt = result.receipt,
                message = "服务器已接受注销请求并吊销账号会话；后台清理尚未完成。",
                deletionSessionRetired = result.sessionRetired,
            )
        }
        deletionSubmission = operation
        start { operation }
    }

    fun requestAccountDeletion() {
        openAccountDeletion()
    }

    fun retryAccountDeletion() {
        val submission = deletionSubmission
        val reauth = deletionReauthCredential
        when {
            submission != null -> start(retrying = true) { submission }
            reauth != null -> start(retrying = true) { reauth }
        }
    }

    fun cancelAccountDeletionRetry() {
        if (state.busy) return
        if (deletionSubmission != null || deletionRequestOpId != null) {
            state = state.copy(error = null, message = "本次注销请求仍未确认；请保留原请求并手动重试。")
            return
        }
        deletionReauthCredential = null
        deletionActionToken = null
        deletionReauthOpId = null
        attempt = null
        state = state.copy(retryAvailable = false, deletionReady = false, error = null, message = "已清除本次重新验证材料；服务器操作尚未提交。")
    }

    /** Fresh load replaces the page list; the previous cursor is never reused after this. */
    fun loadDevices() =
        start(device = true) {
            Attempt(Kind.DEVICES_REFRESH) {
                val page = client.devices(null)
                Outcome(devices = DeviceSessions.fresh(page), message = if (page.items.isEmpty()) "当前账号没有活动的服务器会话。" else null)
            }
        }

    /** Exactly one explicit next-page load; the accumulated list rejects overlaps and cursor loops. */
    fun loadMoreDevices() {
        val loaded = state.devices ?: return
        val cursor = loaded.nextCursor ?: return
        if (!loaded.hasMore) return
        start(device = true) {
            Attempt(Kind.DEVICES_MORE) { Outcome(devices = loaded.append(client.devices(cursor))) }
        }
    }

    fun requestRevoke(row: DeviceSession) {
        if (state.busy || DeviceSessions.revokeTarget(row) == null) return
        state = state.copy(revokeConfirm = row, error = null)
    }

    fun cancelRevoke() {
        if (state.busy) return
        state = state.copy(revokeConfirm = null)
    }

    /** Current-session rows never reach here: they keep the existing logout flow instead. */
    fun revoke(row: DeviceSession) {
        val target = DeviceSessions.revokeTarget(row) ?: return
        if (state.busy) return
        state = state.copy(revokeConfirm = null)
        val loaded = state.devices
        start(device = true) {
            // One pinned operation: a retry reuses this exact UUID and target against the same session.
            val operation = client.operation(target)
            Attempt(Kind.REVOKE) {
                client.revokeDevice(operation)
                val refreshed = try {
                    DeviceSessions.fresh(client.devices(null))
                } catch (_: AuthFailure) {
                    null
                }
                Outcome(
                    devices = refreshed ?: loaded?.without(target),
                    message =
                    if (refreshed == null) {
                        "已撤销该服务器会话；列表可能未刷新，请重新加载。本机访客账本保留。"
                    } else {
                        "已撤销该服务器会话。本机访客账本保留。"
                    },
                )
            }
        }
    }

    fun retry() {
        val current = attempt ?: return
        if (SystemClock.elapsedRealtime() < state.waitUntil) return
        start(retrying = true, device = current.kind.isDevice()) { current }
    }

    fun discardRetry() {
        if (state.busy) return
        val wasDevice = attempt?.kind?.isDevice() == true
        attempt = null
        clearForm()
        if (wasDevice) devicesIdentity = null
        state =
            state.copy(
                retryAvailable = false,
                retryIsDevice = false,
                devices = if (wasDevice) null else state.devices,
                error = null,
                message =
                if (state.reset != null) {
                    "已清除内存中的重试内容；重置尚未确认，请恢复原请求或完成同一邮箱的新重置"
                } else if (wasDevice) {
                    "已放弃本次重试；该撤销可能已在服务器完成，请重新加载服务器设备列表"
                } else {
                    "已放弃本次重试；未确认请求可能已在服务器完成"
                },
                challenge = null,
            )
    }

    private fun editable() = !state.busy && !state.retryAvailable

    private fun clearForm() {
        secret = ""
        code = ""
        email = state.reset?.email.orEmpty()
    }

    private fun start(
        retrying: Boolean = false,
        device: Boolean = false,
        prepare: () -> Attempt,
    ) {
        if (closed || state.busy || !retrying && state.retryAvailable) return
        state = state.copy(busy = true, deviceBusy = device, error = null, message = null)
        executor.execute {
            var current: Attempt? = null
            try {
                val executed = prepare()
                current = executed
                if (executed.kind == Kind.ACCOUNT_DELETE_REAUTH) deletionReauthCredential = executed
                val outcome = executed.run()
                if (executed.kind == Kind.ACCOUNT_DELETE_SUBMIT) {
                    val receipt = outcome.deletionReceipt ?: throw AuthFailure("INVALID_RESPONSE", retryable = true)
                    val retired = outcome.deletionSessionRetired
                    main.post {
                        if (!closed) {
                            accountDeletionOpen = true
                            deletionActionToken = null
                            deletionReauthOpId = null
                            deletionReauthCredential = null
                            deletionSubmission = null
                            deletionRequestOpId = null
                            deletionRequestSnapshot = null
                            devicesIdentity = null
                            avatarBitmap = null
                            state = state.copy(
                                user = null,
                                sessionIdentity = null,
                                confirmed = false,
                                ready = true,
                                busy = false,
                                retryAvailable = false,
                                retryIsDevice = false,
                                deletionReceipt = receipt,
                                deletionReady = false,
                                message = if (retired) "服务器已接受注销请求并吊销账号会话；后台清理尚未完成。" else "服务器已接受注销请求；本机凭据状态需重新确认。",
                            )
                            attempt = null
                        }
                    }
                    return@execute
                }
                val snapshot = client.snapshot()
                main.post {
                    if (!closed && !guestMode) {
                        attempt = null
                        avatarAttemptRef = null
                        val session = snapshot.session
                        val identity = session?.let { DeviceSessions.identity(snapshot) }
                        if (identity != nicknameDraftIdentity) {
                            nicknameDraftIdentity = identity
                            nicknameDraft = session?.user?.nickname.orEmpty()
                            avatarBitmap = null
                        }
                        val profileOwnerMatches = outcome.profileIdentity == null || identity == outcome.profileIdentity
                        val previousAvatarId = state.user?.avatarId
                        val resolvedUser = if (profileOwnerMatches) outcome.user ?: session?.user else session?.user
                        if (identity != state.sessionIdentity || resolvedUser?.avatarId != previousAvatarId) avatarBitmap = null
                        val sameIdentity = identity != null && identity == devicesIdentity
                        devicesIdentity = identity
                        val deviceKind = executed.kind.isDevice()
                        val nextChallenge = outcome.challenge ?: state.challenge
                        state =
                            state.copy(
                                user = resolvedUser,
                                sessionIdentity = identity,
                                confirmed = if (deviceKind) state.confirmed else if (outcome.profileIdentity != null) profileOwnerMatches else outcome.confirmed,
                                ready = true,
                                busy = false,
                                deviceBusy = false,
                                retryAvailable = false,
                                retryIsDevice = false,
                                error = null,
                                message = outcome.message,
                                challenge = nextChallenge,
                                deletionReady = state.deletionReady || outcome.deletionReady || executed.kind == Kind.ACCOUNT_DELETE_REAUTH && deletionActionToken != null,
                                deletionReceipt = outcome.deletionReceipt ?: state.deletionReceipt,
                                reset = snapshot.reset,
                                devices =
                                    when {
                                        identity == null -> null
                                        outcome.devices != null -> outcome.devices
                                        sameIdentity -> state.devices
                                        else -> null
                                    },
                                revokeConfirm = null,
                                waitUntil =
                                    outcome.challenge?.let { SystemClock.elapsedRealtime() + it.retryAfter * 1000L } ?: state.waitUntil,
                            )
                        snapshot.reset?.let { form = AuthForm.RESET; email = it.email }
                        if (executed.kind in setOf(Kind.AVATAR_LOAD, Kind.AVATAR_UPLOAD) && profileOwnerMatches) avatarBitmap = outcome.avatarBitmap
                        if (executed.kind == Kind.AVATAR_CLEAR && profileOwnerMatches) avatarBitmap = null
                        if (!profileOwnerMatches || identity == null || identity != state.sessionIdentity) avatarBitmap = null
                        if (profileOwnerMatches && outcome.user != null && identity != null && executed.kind in setOf(Kind.SIGN_IN, Kind.PROFILE, Kind.PROFILE_UPDATE, Kind.AVATAR_UPLOAD, Kind.AVATAR_CLEAR)) {
                            nicknameDraftIdentity = identity
                            nicknameDraft = outcome.user.nickname
                        }
                        if (executed.kind in setOf(Kind.RESET, Kind.LOGOUT)) {
                            nicknameDraftIdentity = null
                            nicknameDraft = ""
                            avatarBitmap = null
                            clearForm()
                            state = state.copy(challenge = null)
                            if (executed.kind == Kind.RESET) form = if (snapshot.reset == null) AuthForm.LOGIN else AuthForm.RESET
                        }
                    }
                }
            } catch (failure: Exception) {
                val safe = failure as? AuthFailure ?: AuthFailure("INVALID_RESPONSE", retryable = true)
                val snapshot =
                    try {
                        client.snapshot()
                    } catch (_: Exception) {
                        null
                    }
                val keep = current != null && (safe.retryable || current.kind == Kind.ACCOUNT_DELETE_SUBMIT)
                if (current?.kind == Kind.ACCOUNT_DELETE_REAUTH) {
                    if (keep) deletionReauthCredential = current
                    else {
                        deletionReauthCredential = null
                        deletionActionToken = null
                        deletionReauthOpId = null
                    }
                }
                if (current?.kind == Kind.ACCOUNT_DELETE_SUBMIT) deletionSubmission = current
                main.post {
                    if (!closed && !guestMode) {
                        if (current?.kind == Kind.ACCOUNT_DELETE_REAUTH && !keep) deletionReauthCredential = null
                        val previousIdentity = state.sessionIdentity
                        val identity = snapshot?.let { if (it.session != null) DeviceSessions.identity(it) else null }
                        val avatarRetryValid = keep && current?.kind == Kind.AVATAR_UPLOAD && identity == previousIdentity
                        if (identity != previousIdentity) {
                            avatarBitmap = null
                            avatarAttemptRef = null
                        } else {
                            avatarAttemptRef = if (avatarRetryValid) current else null
                        }
                        val retryCurrent = keep && !(current?.kind == Kind.AVATAR_UPLOAD && !avatarRetryValid)
                        attempt = if (retryCurrent) current else null
                        val localLogout = current?.kind == Kind.LOGOUT && snapshot?.session == null
                        if (identity != nicknameDraftIdentity) {
                            nicknameDraftIdentity = identity
                            nicknameDraft = snapshot?.session?.user?.nickname.orEmpty()
                        }
                        val sameIdentity = identity != null && identity == devicesIdentity
                        devicesIdentity = identity
                        state =
                                state.copy(
                                    user = snapshot?.session?.user,
                                    sessionIdentity = identity,
                                    reset = if (snapshot != null) snapshot.reset else state.reset,
                                challenge = if (current?.kind == Kind.RESET && snapshot?.reset != null) null else state.challenge,
                                confirmed = if (current?.kind?.isDevice() == true ||
                                    current?.kind == Kind.AVATAR_UPLOAD && safe.code == "AVATAR_INPUT_INVALID" && identity == previousIdentity) state.confirmed else false,
                                ready = snapshot != null,
                                busy = false,
                                deviceBusy = false,
                                retryAvailable = retryCurrent,
                                retryIsDevice = retryCurrent && current?.kind?.isDevice() == true,
                                deletionReady = when (current?.kind) {
                                    Kind.ACCOUNT_DELETE_REAUTH -> state.deletionReady
                                    Kind.ACCOUNT_DELETE_SUBMIT -> deletionActionToken != null
                                    else -> state.deletionReady
                                },
                                deletionReceipt = state.deletionReceipt,
                                devices = if (sameIdentity) state.devices else null,
                                revokeConfirm = null,
                                error = (if (localLogout) "本机已退出；服务器撤销尚未确认。\n" else "") + safe.userMessage,
                                waitUntil =
                                    if (safe.retryAfter >
                                        0
                                    ) {
                                        SystemClock.elapsedRealtime() + safe.retryAfter * 1000L
                                    } else {
                                        state.waitUntil
                                    },
                            )
                        snapshot?.reset?.let { form = AuthForm.RESET; email = it.email }
                        if (snapshot?.session != null && current?.kind == Kind.SIGN_IN) {
                            clearForm()
                            attempt = null
                            state = state.copy(retryAvailable = false, challenge = null)
                        }
                    }
                }
            }
        }
    }

    override fun onCleared() {
        closed = true
        clearForm()
        attempt = null
        executor.shutdown()
        super.onCleared()
    }

    private enum class Kind {
        LOAD,
        CHALLENGE,
        SIGN_IN,
        RESET,
        PROFILE,
        PROFILE_UPDATE,
        AVATAR_UPLOAD,
        AVATAR_CLEAR,
        AVATAR_LOAD,
        LOGOUT,
        DEVICES_REFRESH,
        DEVICES_MORE,
        REVOKE,
        ACCOUNT_DELETE_REAUTH,
        ACCOUNT_DELETE_SUBMIT,
        ;

        fun isDevice() = this == DEVICES_REFRESH || this == DEVICES_MORE || this == REVOKE
    }

    private class Attempt(
        val kind: Kind,
        val run: () -> Outcome,
    )

    private class Outcome(
        val user: AuthUser? = null,
        val confirmed: Boolean = false,
        val challenge: AuthChallenge? = null,
        val message: String? = null,
        val devices: DeviceSessionsState? = null,
        val deletionReceipt: AccountDeletionReceipt? = null,
        val deletionReady: Boolean = false,
        val deletionSessionRetired: Boolean = false,
        val avatarBitmap: android.graphics.Bitmap? = null,
        val profileIdentity: DeviceIdentity? = null,
    )
}
