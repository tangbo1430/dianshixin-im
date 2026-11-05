package dev.icos.app.data.remote

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import dev.icos.app.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.net.URLEncoder
import java.net.URL
import java.util.UUID
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

/** Only the configured HTTPS origin is used; there is no runtime URL or cleartext override. */
internal class AuthClient(
    context: Context,
) {
    private val origin = BuildConfig.AUTH_API_ORIGIN.removeSuffix("/")
    private val appContext = context.applicationContext
    private val store = AuthStore(context, origin)
    val configured: Boolean get() = origin.isNotEmpty()

    @Volatile private var syncRequestFence: (() -> Unit)? = null
    @Volatile private var scopedSyncRequestFence: (() -> Unit)? = null
    @Volatile private var syncRequestGate: Any? = null
    @Volatile private var activeSyncRequest: SyncRequestHandle? = null
    @Volatile private var syncDispatchPaused: Boolean = false
    @Volatile private var syncControlGeneration: Long = 0L
    @Volatile private var syncBoundIdentity: DeviceIdentity? = null
    private val syncControlLock = Any()
    private val syncRequestFenceLock = Any()
    private val syncRequestFenceSequence = java.util.concurrent.atomic.AtomicLong(0L)
    @Volatile private var syncRequestFenceOwner: Long = 0L
    private data class DatasetClearAdmission(val identity: DeviceIdentity, val gate: Any, val fence: () -> Unit, val allowsClear: Boolean)
    private val datasetClearAdmission = ThreadLocal<DatasetClearAdmission?>()

    internal fun <T> withDatasetClearAdmission(
        identity: DeviceIdentity, gate: Any, fence: () -> Unit, allowsClear: Boolean, block: () -> T,
    ): T {
        val previous = datasetClearAdmission.get()
        if (previous != null && previous.identity != identity) throw AuthFailure("STALE_CREDENTIALS")
        datasetClearAdmission.set(DatasetClearAdmission(identity, gate, fence, allowsClear))
        return try { block() } finally { datasetClearAdmission.set(previous) }
    }

    private class SyncRequestHandle(
        val connection: HttpsURLConnection,
        val admissionGeneration: Long,
        val admissionIdentity: DeviceIdentity?,
        val operationIdentity: DeviceIdentity?,
        val operationFence: (() -> Unit)?,
        val gate: Any?,
    ) {
        val state = java.util.concurrent.atomic.AtomicInteger(0)
    }

    internal fun hasSyncRequestFence(): Boolean = syncRequestFence != null || scopedSyncRequestFence != null

    internal fun installSyncRequestFence(gate: Any?, fence: (() -> Unit)? = null): Long = synchronized(syncRequestFenceLock) {
        syncRequestGate = gate
        syncRequestFence = fence
        syncRequestFenceOwner = syncRequestFenceSequence.incrementAndGet()
        syncRequestFenceOwner
    }

    internal fun clearSyncRequestFence(owner: Long) {
        synchronized(syncRequestFenceLock) {
            if (syncRequestFenceOwner == owner) {
                syncRequestFence = null
                syncRequestGate = null
                syncRequestFenceOwner = 0L
            }
        }
    }

    internal fun bindSyncControlIdentity(
        identity: DeviceIdentity?,
        initiallyPaused: Boolean,
        gate: Any,
        onComplete: (Long, Result<Unit>) -> Unit,
    ) {
        val generation = synchronized(syncControlLock) {
            syncRequestGate = gate
            scopedSyncRequestFence = identity?.let { expected ->
                { DeviceSessions.requireSameIdentity(expected, snapshot()) }
            }
            syncControlGeneration += 1
            syncBoundIdentity = identity
            syncDispatchPaused = true
            activeSyncRequest?.also { it.state.set(2) }
            syncControlGeneration
        }
        activeSyncRequest?.let { cancelSyncRequestAsync(it) }
        pauseExecutor.execute {
            val result = runCatching {
                val handle = synchronized(gate) {
                    synchronized(syncControlLock) {
                        check(generation == syncControlGeneration && syncBoundIdentity == identity) { "STALE_CREDENTIALS" }
                        syncDispatchPaused = initiallyPaused
                    }
                    activeSyncRequest?.also { it.state.set(2) }
                }
                handle?.let { runCatching { it.connection.disconnect() } }
                Unit
            }
            onComplete(generation, result)
        }
    }

    internal fun syncControlIdentityGeneration(): Long = syncControlGeneration

    internal fun cancelActiveSyncRequest() {
        activeSyncRequest?.let { cancelSyncRequestAsync(it) }
    }

    internal fun pauseSyncRequests(gate: Any, controlGeneration: Long, onPause: () -> Unit, onComplete: (Result<Unit>) -> Unit) {
        val handle = synchronized(syncControlLock) {
            if (controlGeneration != syncControlGeneration) {
                onComplete(Result.failure(AuthFailure("STALE_CREDENTIALS")))
                return
            }
            syncDispatchPaused = true
            activeSyncRequest?.also { it.state.set(2) }
        }
        handle?.let { cancelSyncRequestAsync(it) }
        pauseExecutor.execute {
            val result = runCatching {
                val active = synchronized(gate) {
                    synchronized(syncControlLock) { check(controlGeneration == syncControlGeneration) { "STALE_CREDENTIALS" } }
                    onPause()
                    activeSyncRequest?.also { it.state.set(2) }
                }
                active?.let { runCatching { it.connection.disconnect() } }
                Unit
            }
            onComplete(result)
        }
    }

    private fun cancelSyncRequestAsync(handle: SyncRequestHandle) {
        handle.state.set(2)
        pauseExecutor.execute { runCatching { handle.connection.disconnect() } }
    }

    internal fun resumeSyncRequests(gate: Any, controlGeneration: Long, identity: DeviceIdentity?, onResume: () -> Unit, onComplete: (Result<Unit>) -> Unit) {
        pauseExecutor.execute {
            val result = runCatching {
                synchronized(syncControlLock) { check(controlGeneration == syncControlGeneration && syncBoundIdentity == identity) { "STALE_CREDENTIALS" } }
                onResume()
                synchronized(syncControlLock) {
                    check(controlGeneration == syncControlGeneration && syncBoundIdentity == identity) { "STALE_CREDENTIALS" }
                    syncDispatchPaused = false
                }
            }
            onComplete(result)
        }
    }

    private fun runSyncRequestFences(operationFence: (() -> Unit)? = null) {
        syncRequestFence?.invoke()
        scopedSyncRequestFence?.invoke()
        datasetClearAdmission.get()?.fence?.invoke()
        operationFence?.invoke()
    }

    private fun beginSyncRequest(
        connection: HttpsURLConnection,
        bytes: ByteArray?,
        operationIdentity: DeviceIdentity? = null,
        operationFence: (() -> Unit)? = null,
        operationGate: Any? = null,
    ): SyncRequestHandle {
        val gate = operationGate ?: datasetClearAdmission.get()?.gate ?: syncRequestGate
        val effectiveIdentity = operationIdentity ?: datasetClearAdmission.get()?.identity
        val effectiveFence = operationFence ?: datasetClearAdmission.get()?.fence
        val admission = synchronized(syncControlLock) {
            if (syncDispatchPaused) throw AuthFailure("SNAPSHOT_CANCELLED")
            syncControlGeneration to syncBoundIdentity
        }
        val handle = SyncRequestHandle(connection, admission.first, admission.second, effectiveIdentity, effectiveFence, gate)
        val dispatch = {
            runSyncRequestFences(effectiveFence)
            effectiveIdentity?.let { DeviceSessions.requireSameIdentity(it, snapshot()) }
            synchronized(syncControlLock) {
                if (syncDispatchPaused || syncControlGeneration != handle.admissionGeneration ||
                    syncBoundIdentity != handle.admissionIdentity || handle.state.get() != 0
                ) throw AuthFailure("SNAPSHOT_CANCELLED")
                if (!handle.state.compareAndSet(0, 1)) throw AuthFailure("SNAPSHOT_CANCELLED")
                activeSyncRequest = handle
            }
            try {
                connection.connect()
                if (handle.state.get() != 1) throw AuthFailure("SNAPSHOT_CANCELLED")
                bytes?.let { payload -> connection.outputStream.use { it.write(payload) } }
                if (handle.state.get() != 1) throw AuthFailure("SNAPSHOT_CANCELLED")
                runSyncRequestFences(effectiveFence)
                effectiveIdentity?.let { DeviceSessions.requireSameIdentity(it, snapshot()) }
                if (handle.state.get() != 1) throw AuthFailure("SNAPSHOT_CANCELLED")
                handle
            } catch (failure: Exception) {
                endSyncRequest(handle)
                throw failure
            }
        }
        return if (gate == null) dispatch() else synchronized(gate) { dispatch() }
    }

    private fun validateSyncRequest(handle: SyncRequestHandle) {
        val checkCurrent = {
            synchronized(syncControlLock) {
                if (syncDispatchPaused || syncControlGeneration != handle.admissionGeneration ||
                    syncBoundIdentity != handle.admissionIdentity || handle.state.get() != 1 || activeSyncRequest !== handle
                ) throw AuthFailure("SNAPSHOT_CANCELLED")
            }
        }
        checkCurrent()
        handle.operationFence?.invoke()
        handle.operationIdentity?.let { DeviceSessions.requireSameIdentity(it, snapshot()) }
        checkCurrent()
    }

    private fun syncResponseCode(handle: SyncRequestHandle): Int {
        validateSyncRequest(handle)
        val status = handle.connection.responseCode
        validateSyncRequest(handle)
        return status
    }

    private fun endSyncRequest(handle: SyncRequestHandle) {
        handle.state.compareAndSet(1, 2)
        val end = { if (activeSyncRequest === handle) activeSyncRequest = null }
        val gate = syncRequestGate
        if (gate == null) end() else synchronized(gate) { end() }
    }

    private fun requireNetworkConsent() {
        if (!appContext.getSharedPreferences("legal_consent", Context.MODE_PRIVATE).getBoolean("network_allowed", false)) {
            throw AuthFailure("CURRENT_LEGAL_CONSENT_REQUIRED", retryable = true)
        }
    }

    private fun enforceRestoreBarrier(allowRestoreBarrier: Boolean) {
        requireNetworkConsent()
        if (!allowRestoreBarrier && store.read().hasRestoreBarrierForCurrentSession()) throw AuthFailure("RESTORE_UNCONFIRMED")
        if (store.read().datasetClear != null && datasetClearAdmission.get() == null) throw AuthFailure("CLEAR_REAUTH_UNCONFIRMED")
    }

    fun snapshot(): AuthState = store.read()

    fun challenge(
        email: String,
        purpose: String,
        opId: UUID,
    ): AuthChallenge {
        requireNetworkConsent()
        if (purpose != "RESET") throw AuthFailure("INVALID_INPUT")
        return AuthJson.challenge(
            request(
                "/v1/auth/challenges",
                201,
                JSONObject().put("email", email).put("purpose", purpose),
                opId,
            ),
        )
    }

    fun signIn(
        snapshot: AuthState,
        email: String,
        secret: String,
        opId: UUID,
        register: Boolean = false,
    ): AuthState {
        requireNetworkConsent()
        snapshot.requireSignIn()
        val current = store.read()
        if (!current.matches(snapshot)) throw AuthFailure("STALE_CREDENTIALS")
        current.requireSignIn()
        validateSecret(secret)
        val body =
            JSONObject()
                .put("email", email)
                .put("password", secret)
                .put("device", JSONObject().put("id", snapshot.deviceId.toString()).put("name", "Android"))
        val path = if (register) "/v1/auth/register" else "/v1/auth/login"
        val session = AuthJson.session(request(path, 201, body, opId), snapshot.deviceId)
        return store.replace(snapshot, session)
    }

    fun prepareReset(email: String, challengeId: UUID, opId: UUID): AuthState = synchronized(refreshMonitor) {
        requireNetworkConsent()
        val snapshot = store.read()
        if (snapshot.session != null) throw AuthFailure("STALE_CREDENTIALS")
        store.beginReset(snapshot, PendingReset(opId, challengeId, email))
    }

    fun reset(snapshot: AuthState, code: String, newSecret: String) {
        requireNetworkConsent()
        validateCode(code)
        validateSecret(newSecret)
        if (!store.read().matches(snapshot)) throw AuthFailure("STALE_CREDENTIALS")
        ResetAttempt(snapshot).run(
            send = { operation ->
                request(
                    "/v1/auth/" + "pass" + "word-reset",
                    204,
                    JSONObject().put("challengeId", operation.challengeId.toString()).put("code", code).put("newPassword", newSecret),
                    operation.opId,
                )
            },
            complete = { store.finishReset(it) },
        )
    }

    internal fun authorizeAvatarUpload(
        identity: DeviceIdentity,
        bytes: ByteArray,
        digest: String,
        opId: UUID,
    ): AvatarUploadGrant {
        if (bytes.isEmpty() || bytes.size > 20 * 1024 * 1024 || !digest.matches(Regex("[0-9a-f]{64}"))) throw AuthFailure("INVALID_INPUT")
        val data = authenticatedSyncRequest(
            "/v1/attachments/uploads",
            JSONObject().put("sha256", digest).put("bytes", bytes.size).put("mime", "image/bmp").put("purpose", "AVATAR"),
            opId, expectedStatus = 201, maxBytes = 4096, responseMaxBytes = 4096,
            expectedIdentity = identity,
        )
        AuthJson.keys(data, setOf("attachmentId", "uploadAuthorization", "uploadPath", "expiresAt", "maxBytes", "acceptedMime"))
        val id = AuthJson.uuid(data, "attachmentId")
        val path = AuthJson.string(data, "uploadPath", 128)
        if (path != "/v1/attachments/uploads/$id" || AuthJson.string(data, "acceptedMime", 32) != "image/bmp" || AuthJson.integer(data, "maxBytes") !in bytes.size.toLong()..20L * 1024 * 1024) throw AuthFailure("INVALID_RESPONSE")
        AuthJson.attachmentTimestamp(AuthJson.string(data, "expiresAt", 40))
        return AvatarUploadGrant(id, path, AuthJson.string(data, "uploadAuthorization", 64))
    }

    internal fun putAvatarUpload(identity: DeviceIdentity, grant: AvatarUploadGrant, bytes: ByteArray) {
        authenticatedBinaryRequest(grant.path, "PUT", identity, bytes, "image/bmp", "X-Upload-Authorization" to grant.authorization, expectedStatus = 204)
    }

    internal fun completeAvatarUpload(identity: DeviceIdentity, attachment: UUID, digest: String, opId: UUID): AvatarStatus {
        val data = authenticatedSyncRequest("/v1/attachments/$attachment/complete", JSONObject().put("declaredDigest", digest), opId,
            expectedStatus = 200, maxBytes = 4096, responseMaxBytes = 4096, expectedIdentity = identity)
        return avatarStatus(data, attachment).also { if (it.state != "READY" || it.purpose != "AVATAR" || it.mime != "image/bmp" || it.sha256 != digest) throw AuthFailure("INVALID_RESPONSE", retryable = true) }
    }

    internal fun avatarStatus(identity: DeviceIdentity, attachment: UUID): AvatarStatus {
        val data = authenticatedSyncRequest("/v1/attachments/$attachment/status", null, null,
            expectedStatus = 200, maxBytes = 4096, responseMaxBytes = 4096, expectedIdentity = identity)
        return avatarStatus(data, attachment)
    }

    private fun avatarStatus(data: JSONObject, expectedId: UUID): AvatarStatus {
        AuthJson.keys(data, setOf("attachmentId", "state", "purpose", "bytes", "sha256", "mime", "width", "height", "expiresAt"))
        if (AuthJson.uuid(data, "attachmentId") != expectedId) throw AuthFailure("INVALID_RESPONSE")
        val bytes = if (data.isNull("bytes")) null else AuthJson.integer(data, "bytes")
        val digest = if (data.isNull("sha256")) null else AuthJson.string(data, "sha256", 64)
        val mime = if (data.isNull("mime")) null else AuthJson.string(data, "mime", 32)
        AuthJson.attachmentTimestamp(AuthJson.string(data, "expiresAt", 40))
        return AvatarStatus(expectedId, AuthJson.string(data, "state", 32), AuthJson.string(data, "purpose", 32), mime, bytes, digest)
    }

    internal fun setAvatar(identity: DeviceIdentity, attachment: UUID?, opId: UUID): AuthUser {
        val body = JSONObject().put("avatarId", attachment?.toString() ?: JSONObject.NULL)
        val data = authenticatedSyncRequest("/v1/me", body, opId, expectedStatus = 200, method = "PATCH", expectedIdentity = identity)
        val user = AuthJson.user(data)
        if (user.id != identity.owner || user.avatarId != attachment || DeviceSessions.identity(store.read()) != identity) throw AuthFailure("STALE_CREDENTIALS")
        return user
    }

    internal fun authorizeAvatarDownload(identity: DeviceIdentity, attachment: UUID): String {
        val data = authenticatedSyncRequest("/v1/attachments/$attachment/download", null, null,
            expectedStatus = 200, maxBytes = 4096, responseMaxBytes = 4096, method = "POST", expectedIdentity = identity)
        AuthJson.keys(data, setOf("downloadAuthorization", "downloadPath", "expiresAt"))
        if (AuthJson.string(data, "downloadPath", 128) != "/v1/attachments/$attachment/download") throw AuthFailure("INVALID_RESPONSE")
        AuthJson.attachmentTimestamp(AuthJson.string(data, "expiresAt", 40))
        return AuthJson.string(data, "downloadAuthorization", 64)
    }

    internal fun downloadAvatar(identity: DeviceIdentity, attachment: UUID, authorization: String): ByteArray =
        authenticatedBinaryRequest("/v1/attachments/$attachment/download", "GET", identity, null, null,
            "X-Download-Authorization" to authorization, expectedStatus = 200)

    private fun authenticatedBinaryRequest(
        path: String,
        method: String,
        identity: DeviceIdentity,
        payload: ByteArray?,
        contentType: String?,
        extraHeader: Pair<String, String>,
        expectedStatus: Int,
    ): ByteArray {
        requireNetworkConsent()
        enforceRestoreBarrier(allowRestoreBarrier = false)
        if (store.read().datasetClear != null) throw AuthFailure("CLEAR_REAUTH_UNCONFIRMED")
        val actor = validSession()
        DeviceSessions.requireSameIdentity(identity, actor)
        if (!path.matches(Regex("/v1/attachments/(uploads/[0-9a-f-]{36}|[0-9a-f-]{36}/(download|complete|status))"))) throw AuthFailure("INVALID_INPUT")
        if (extraHeader.second.length !in 32..128 || !extraHeader.second.all { it in '0'..'9' || it in 'a'..'z' || it in 'A'..'Z' || it == '-' || it == '_' }) throw AuthFailure("INVALID_INPUT")
        val session = actor.session ?: throw AuthFailure("SESSION_EXPIRED")
        val connection = try {
            val uri = URI(origin)
            require(
                uri.scheme == "https" && uri.host != null && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
                    uri.rawPath.orEmpty().isEmpty(),
            )
            URL(origin + path).openConnection() as HttpsURLConnection
        } catch (_: Exception) { throw AuthFailure("UNCONFIGURED") }
        val timerTask = timer.schedule({ connection.disconnect() }, 60, TimeUnit.SECONDS)
        var handle: SyncRequestHandle? = null
        try {
            connection.apply {
                requestMethod = method
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = false
                useCaches = false
                setRequestProperty("Accept", if (payload == null) "image/bmp" else "application/json")
                setRequestProperty("Cache-Control", "no-store")
                setRequestProperty("Authorization", "Bearer ${session.accessToken}")
                setRequestProperty("X-Device-Id", actor.deviceId.toString())
                setRequestProperty(extraHeader.first, extraHeader.second)
                contentType?.let { setRequestProperty("Content-Type", it) }
                if (payload != null && payload.isNotEmpty()) {
                    doOutput = true
                    setFixedLengthStreamingMode(payload.size)
                }
            }
            val operationFence = {
                enforceRestoreBarrier(allowRestoreBarrier = false)
                if (store.read().datasetClear != null) throw AuthFailure("CLEAR_REAUTH_UNCONFIRMED")
                DeviceSessions.requireSameIdentity(identity, validSession())
            }
            handle = beginSyncRequest(connection, payload?.takeIf { it.isNotEmpty() }, operationIdentity = identity, operationFence = operationFence)
            val responseCode = syncResponseCode(handle)
            validateSyncRequest(handle)
            if (expectedStatus == 204) {
                if (responseCode != 204) throw AuthFailure("INVALID_RESPONSE", retryable = responseCode >= 500)
                val requestId = AuthJson.checked { AuthJson.uuid(connection.getHeaderField("X-Request-Id") ?: error("missing")) }
                if (!connection.getHeaderField("Cache-Control").orEmpty().split(',').any { it.trim().equals("no-store", true) }) throw AuthFailure("INVALID_RESPONSE", requestId, retryable = true)
                return ByteArray(0)
            }
            if (responseCode != expectedStatus) throw AuthFailure("INVALID_RESPONSE", retryable = responseCode >= 500)
            val bytes = connection.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (out.size() + n > 20 * 1024 * 1024) throw AuthFailure("INVALID_RESPONSE")
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
            validateSyncRequest(handle)
            if (connection.contentType.orEmpty().substringBefore(';').trim() != "image/bmp" || bytes.isEmpty()) throw AuthFailure("INVALID_RESPONSE", retryable = true)
            return bytes
        } catch (failure: AuthFailure) {
            throw failure
        } catch (_: Exception) {
            throw AuthFailure("NETWORK", retryable = true)
        } finally {
            handle?.let(::endSyncRequest)
            timerTask.cancel(false)
            connection.disconnect()
        }
    }

    fun updateProfile(expectedIdentity: DeviceIdentity, nickname: String, opId: UUID): AuthUser {
        requireNetworkConsent()
        if (nickname.any { Character.isISOControl(it) }) throw AuthFailure("INVALID_INPUT")
        val normalized = nickname.trim()
        if (normalized.isEmpty() || normalized.codePointCount(0, normalized.length) !in 1..32) {
            throw AuthFailure("INVALID_INPUT")
        }
        val body = JSONObject().put("nickname", normalized)
        val data = authenticatedSyncRequest(
            "/v1/me", body, opId, expectedStatus = 200, method = "PATCH",
            expectedIdentity = expectedIdentity,
        )
        val user = AuthJson.user(data)
        if (user.id != expectedIdentity.owner || DeviceSessions.identity(store.read()) != expectedIdentity) throw AuthFailure("STALE_CREDENTIALS")
        return user
    }

    fun profile(): AuthUser {
        requireNetworkConsent()
        var snapshot = validSession()
        val data = try {
            try {
                request("/v1/me", 200, actor = snapshot)
            } catch (failure: AuthFailure) {
                if (failure.code != "SESSION_EXPIRED") throw failure
                snapshot = refresh(snapshot)
                request("/v1/me", 200, actor = snapshot)
            }
        } catch (failure: AuthFailure) {
            invalidateTerminal(snapshot, failure)
            throw failure
        }
        val user = AuthJson.user(data)
        if (user.id != snapshot.session!!.user.id || !store.read().matches(snapshot)) throw AuthFailure("STALE_CREDENTIALS")
        return user
    }

    /** One explicit device page; the caller appends or replaces it and never reconstructs the cursor. */
    fun devices(cursor: String?): DevicePage {
        requireNetworkConsent()
        var snapshot = validSession()
        val expected = DeviceSessions.identity(snapshot)
        val path = "/v1/me/devices" + (cursor?.let { "?cursor=" + URLEncoder.encode(it, Charsets.UTF_8.name()) } ?: "")
        val data =
            try {
                try {
                    request(path, 200, actor = snapshot, maxBytes = AuthJson.MAX_DEVICE_PAGE_BYTES)
                } catch (failure: AuthFailure) {
                    if (failure.code != "SESSION_EXPIRED") throw failure
                    snapshot = refresh(snapshot)
                    request(path, 200, actor = snapshot, maxBytes = AuthJson.MAX_DEVICE_PAGE_BYTES)
                }
            } catch (failure: AuthFailure) {
                invalidateTerminal(snapshot, failure)
                throw failure
            }
        val page = AuthJson.devicePage(data, expected.sessionId)
        if (!store.read().matches(snapshot)) throw AuthFailure("STALE_CREDENTIALS")
        DeviceSessions.requireSameIdentity(expected, snapshot)
        return page
    }

    internal fun financeSummary(epoch: UUID): JSONObject = authenticatedSyncRequest("/v1/finance/summary?epoch=$epoch", null, null)

    internal fun pushTypedGroups(
        epoch: UUID,
        groups: JSONArray,
        expectedIdentity: DeviceIdentity? = null,
        requestGate: Any? = null,
        requestFence: (() -> Unit)? = null,
        allowDatasetClear: Boolean = false,
    ): JSONObject {
        val body = JSONObject()
            .put("epoch", epoch.toString())
            .put("deviceId", store.read().deviceId.toString())
            .put("groups", groups)
        return authenticatedSyncRequest("/v1/sync/push", body, groups.optJSONObject(0)?.optString("opId")?.let(UUID::fromString), maxBytes = MAX_SYNC_REQUEST_BYTES, responseMaxBytes = MAX_SYNC_RESPONSE_BYTES, expectedIdentity = expectedIdentity, operationGate = requestGate, operationFence = requestFence, allowDatasetClear = allowDatasetClear)
    }

    internal fun syncConflicts(
        cursor: String? = null,
        limit: Int = 100,
        expectedIdentity: DeviceIdentity? = null,
        requestGate: Any? = null,
        requestFence: (() -> Unit)? = null,
    ): SyncConflictPage {
        require(limit in 1..100)
        val query = buildString {
            append("?limit=").append(limit)
            cursor?.let { append("&cursor=").append(URLEncoder.encode(it, Charsets.UTF_8.name())) }
        }
        val data = authenticatedSyncRequest("/v1/sync/conflicts$query", null, null, responseMaxBytes = MAX_SYNC_RESPONSE_BYTES,
            expectedIdentity = expectedIdentity, operationGate = requestGate, operationFence = requestFence)
        AuthJson.keys(data, setOf("items", "nextCursor", "hasMore"))
        val rows = data.getJSONArray("items")
        if (rows.length() > limit) throw AuthFailure("INVALID_RESPONSE", retryable = true)
        val items = List(rows.length()) { index -> decodeSyncConflict(rows.getJSONObject(index)) }
        if (items.map { it.conflictId }.distinct().size != items.size) throw AuthFailure("INVALID_RESPONSE", retryable = true)
        val hasMore = data.getBoolean("hasMore")
        val nextCursor = if (data.isNull("nextCursor")) null else AuthJson.string(data, "nextCursor", 2048)
        if (hasMore != (nextCursor != null)) throw AuthFailure("INVALID_RESPONSE", retryable = true)
        return SyncConflictPage(items, nextCursor, hasMore)
    }

    internal fun syncConflict(
        conflictId: UUID,
        reviewKeys: Set<String> = emptySet(),
        expectedIdentity: DeviceIdentity? = null,
        requestGate: Any? = null,
        requestFence: (() -> Unit)? = null,
    ): SyncConflict {
        if (conflictId == UUID(0, 0) || reviewKeys.size > 100 || reviewKeys.any { !CONFLICT_KEY.matches(it) }) throw AuthFailure("INVALID_INPUT")
        val path = buildString {
            append("/v1/sync/conflicts/").append(conflictId)
            if (reviewKeys.isNotEmpty()) append("?reviewKeys=").append(URLEncoder.encode(reviewKeys.sorted().joinToString(","), Charsets.UTF_8.name()))
        }
        return decodeSyncConflict(authenticatedSyncRequest(path, null, null, responseMaxBytes = MAX_SYNC_RESPONSE_BYTES,
            expectedIdentity = expectedIdentity, operationGate = requestGate, operationFence = requestFence))
    }

    internal fun conflictResolveRequest(
        choice: String,
        reviewed: SyncConflict,
        newOpId: UUID,
        editedCommand: JSONObject? = null,
    ): String {
        val revision = reviewed.revision
        val head = reviewed.headSeq
        if (choice !in setOf("LOCAL", "SERVER", "EDITED") || revision <= 0 || head < 0 || newOpId == UUID(0, 0) ||
            (choice == "EDITED") != (editedCommand != null)) throw AuthFailure("INVALID_INPUT")
        val allVersions = reviewed.reviewedServerVersions
        val versions = if (choice == "EDITED") JSONObject(allVersions.toString()) else {
            val originalKeys = reviewed.localCommand.optJSONObject("baseVersions")?.keys()?.asSequence()?.toSet()
                ?: throw AuthFailure("INVALID_CONFLICT_REVIEW")
            if (originalKeys.isEmpty() || originalKeys.any { !allVersions.has(it) }) throw AuthFailure("INVALID_CONFLICT_REVIEW")
            JSONObject().also { selected -> originalKeys.sorted().forEach { key -> selected.put(key, allVersions.get(key)) } }
        }
        val body = JSONObject().put("choice", choice).put("expectedRevision", revision).put("newOpId", newOpId.toString())
            .put("expectedHeadSeq", head).put("reviewedServerVersions", versions)
        editedCommand?.let {
            val group = JSONObject(it.toString())
            validateConflictGroup(group)
            val submittedVersions = group.getJSONObject("baseVersions")
            val reviewedKeys = versions.keys().asSequence().toSet()
            val editedKeys = submittedVersions.keys().asSequence().toSet()
            val conflictKeys = reviewed.localCommand.optJSONObject("baseVersions")?.keys()?.asSequence()?.toSet()
                ?: throw AuthFailure("INVALID_CONFLICT_REVIEW")
            if (reviewedKeys.size > 2048 || editedKeys != reviewedKeys || !conflictKeys.all { it in editedKeys } ||
                reviewedKeys.any { key -> submittedVersions.optLong(key, -1) != versions.optLong(key, -2) }) {
                throw AuthFailure("INVALID_CONFLICT_REVIEW")
            }
            group.put("opId", newOpId.toString()).put("groupId", UUID.randomUUID().toString())
                .put("baseVersions", JSONObject(versions.toString()))
            body.put("editedCommand", group)
        }
        return body.toString()
    }

    internal fun resolveSyncConflict(
        conflictId: UUID,
        exactRequestBody: String,
        expectedIdentity: DeviceIdentity? = null,
        requestGate: Any? = null,
        requestFence: (() -> Unit)? = null,
    ): SyncConflictResolution {
        if (conflictId == UUID(0, 0)) throw AuthFailure("INVALID_INPUT")
        val body = try { JSONObject(exactRequestBody) } catch (_: Exception) { throw AuthFailure("INVALID_CONFLICT_WIRE") }
        AuthJson.keys(body, setOf("choice", "expectedRevision", "newOpId", "expectedHeadSeq", "reviewedServerVersions"), setOf("editedCommand"))
        val opId = runCatching { UUID.fromString(body.getString("newOpId")) }.getOrNull()?.takeUnless { it == UUID(0, 0) }
            ?: throw AuthFailure("INVALID_CONFLICT_WIRE")
        val revision = AuthJson.integer(body, "expectedRevision")
        val expectedHead = try { AuthJson.integer(body, "expectedHeadSeq") } catch (_: Exception) { throw AuthFailure("INVALID_CONFLICT_WIRE") }
        val reviewedVersions = try { body.getJSONObject("reviewedServerVersions") } catch (_: Exception) { throw AuthFailure("INVALID_CONFLICT_WIRE") }
        validateConflictVersions(reviewedVersions)
        if (body.optString("choice") !in setOf("LOCAL", "SERVER", "EDITED") || revision <= 0 || expectedHead < 0 || reviewedVersions.length() == 0 || reviewedVersions.length() > 2048) {
            throw AuthFailure("INVALID_CONFLICT_WIRE")
        }
        if (body.optString("choice") == "EDITED") {
            val edited = body.optJSONObject("editedCommand") ?: throw AuthFailure("INVALID_CONFLICT_WIRE")
            validateConflictGroup(edited)
            val editedVersions = edited.optJSONObject("baseVersions") ?: throw AuthFailure("INVALID_CONFLICT_WIRE")
            if (edited.optString("opId") != opId.toString() || !sameJsonNumberMap(editedVersions, reviewedVersions)) throw AuthFailure("INVALID_CONFLICT_WIRE")
        }
        val data = authenticatedSyncRequest("/v1/sync/conflicts/$conflictId/resolve", body, opId, responseMaxBytes = MAX_SYNC_RESPONSE_BYTES,
            expectedIdentity = expectedIdentity, operationGate = requestGate, operationFence = requestFence)
        AuthJson.keys(data, setOf("conflictId", "state", "resolutionOpId", "discardedOpId", "commandResult", "headSeq"))
        val state = AuthJson.string(data, "state", 32)
        if (state !in setOf("RESOLVED_LOCAL", "RESOLVED_SERVER", "RESOLVED_EDITED")) throw AuthFailure("INVALID_RESPONSE", retryable = true)
        val result = if (data.isNull("commandResult")) null else data.getJSONObject("commandResult")
        if (state == "RESOLVED_SERVER" && result != null || state != "RESOLVED_SERVER" && result == null) throw AuthFailure("INVALID_RESPONSE", retryable = true)
        return SyncConflictResolution(
            conflictId = AuthJson.uuid(data, "conflictId"), state = state,
            resolutionOpId = AuthJson.uuid(data, "resolutionOpId"), discardedOpId = AuthJson.uuid(data, "discardedOpId"),
            commandResult = result,
            headSeq = AuthJson.integer(data, "headSeq"),
        ).also {
            if (it.conflictId != conflictId || it.resolutionOpId != opId || it.headSeq < 0) throw AuthFailure("INVALID_RESPONSE", retryable = true)
            if (result != null && (result.optString("opId") != opId.toString() || result.optString("status") !in setOf("APPLIED", "CONFLICT")))
                throw AuthFailure("INVALID_RESPONSE", retryable = true)
        }
    }

    private fun decodeSyncConflict(row: JSONObject): SyncConflict {
        AuthJson.keys(row, setOf("conflictId", "opId", "revision", "state", "localCommand", "serverVersions", "createdAt", "headSeq", "reviewedServerVersions"))
        val revision = AuthJson.integer(row, "revision").also { if (it <= 0) throw AuthFailure("INVALID_RESPONSE", retryable = true) }
        val state = AuthJson.string(row, "state", 32)
        if (state != "OPEN") throw AuthFailure("INVALID_RESPONSE", retryable = true)
        val local = row.getJSONObject("localCommand")
        validateConflictGroup(local)
        val versions = row.getJSONObject("serverVersions")
        val reviewedVersions = row.getJSONObject("reviewedServerVersions")
        validateConflictVersions(versions)
        validateConflictVersions(reviewedVersions)
        val originalVersions = local.getJSONObject("baseVersions")
        val originalKeys = originalVersions.keys().asSequence().toSet()
        if (originalKeys.isEmpty() || versions.length() == 0 || versions.keys().asSequence().toSet() != originalKeys ||
            reviewedVersions.length() < originalKeys.size || originalKeys.any { !reviewedVersions.has(it) }) throw AuthFailure("INVALID_RESPONSE", retryable = true)
        return SyncConflict(
            AuthJson.uuid(row, "conflictId"), AuthJson.uuid(row, "opId"), revision, state, local, versions,
            AuthJson.string(row, "createdAt", 40).also { AuthJson.timestamp(it) },
            AuthJson.integer(row, "headSeq").also { if (it < 0) throw AuthFailure("INVALID_RESPONSE", retryable = true) },
            reviewedVersions,
        )
    }

    private val CONFLICT_ROOTS = setOf(
        "ledger", "category", "tag_group", "tag", "account_group", "account", "account_ledger_visibility",
        "credit_capacity_event", "recurrence_rule_segment", "recurrence_occurrence", "receivable_payable_operation",
        "record", "record_template", "budget", "claim_allocation", "claim_settlement", "refund", "preferences",
        "saving", "saving_entry", "loan", "loan_term", "loan_rate_change", "installment", "installment_term",
        "credit_payment_allocation", "recurrence", "import_batch",
    )
    private val CONFLICT_KEY = Regex("([a-z][a-z0-9_]*):([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})")

    private fun validConflictKey(key: String): Boolean =
        CONFLICT_KEY.matchEntire(key)?.groupValues?.get(1) in CONFLICT_ROOTS &&
            CONFLICT_KEY.matchEntire(key)?.groupValues?.get(2)?.let { runCatching { UUID.fromString(it) }.getOrNull() != UUID(0, 0) } == true

    private fun validateConflictVersions(versions: JSONObject) {
        if (versions.length() > 2048 || versions.keys().asSequence().any { key ->
                !validConflictKey(key) || versions.opt(key) !is Number || versions.optLong(key, -1) < 0
            }) throw AuthFailure("INVALID_RESPONSE", retryable = true)
    }

    private fun sameJsonNumberMap(left: JSONObject, right: JSONObject): Boolean {
        val leftKeys = left.keys().asSequence().toSet()
        val rightKeys = right.keys().asSequence().toSet()
        return leftKeys == rightKeys && leftKeys.all { left.optLong(it, -1) == right.optLong(it, -2) }
    }

    private fun validateConflictGroup(group: JSONObject) {
        AuthJson.keys(group, setOf("opId", "groupId", "aggregateType", "aggregateId", "baseVersions", "commandType", "schemaVersion", "payload"))
        listOf("opId", "groupId", "aggregateId").forEach { key ->
            val value = runCatching { UUID.fromString(group.getString(key)) }.getOrNull()
            if (value == null || value == UUID(0, 0)) throw AuthFailure("INVALID_CONFLICT_WIRE")
        }
        if (!group.getString("aggregateType").matches(Regex("[a-z][a-z0-9_]{0,63}")) ||
            !group.getString("commandType").matches(Regex("[a-z][a-zA-Z0-9_.]{0,127}")) || group.getInt("schemaVersion") != 1)
            throw AuthFailure("INVALID_CONFLICT_WIRE")
        val versions = group.getJSONObject("baseVersions")
        validateConflictVersions(versions)
        group.getJSONObject("payload")
    }

    internal fun pullTypedChanges(
        cursor: String?, limit: Int = 200, allowRestoreRecovery: Boolean = false,
        recoveryIdentity: DeviceIdentity? = null,
        restoreBootstrapWire: RestoreBootstrapWire? = null,
        expectedIdentity: DeviceIdentity? = null,
        requestGate: Any? = null,
        requestFence: (() -> Unit)? = null,
        allowDatasetClear: Boolean = false,
    ): JSONObject {
        if (allowDatasetClear && datasetClearAdmission.get()?.allowsClear != true) throw AuthFailure("CLEAR_REAUTH_UNCONFIRMED")
        require(limit in 1..500)
        val query = buildString {
            append("?limit=").append(limit)
            cursor?.let { append("&cursor=").append(URLEncoder.encode(it, Charsets.UTF_8.name())) }
        }
        return authenticatedSyncRequest(
            "/v1/sync/pull$query", null, null, maxBytes = MAX_SYNC_REQUEST_BYTES,
            responseMaxBytes = MAX_SYNC_RESPONSE_BYTES, allowRestoreBarrier = allowRestoreRecovery,
            restoreRecoveryIdentity = recoveryIdentity, restoreBootstrapWire = restoreBootstrapWire,
            expectedIdentity = expectedIdentity, operationGate = requestGate, operationFence = requestFence,
            allowDatasetClear = allowDatasetClear,
        )
    }

    internal fun currentDatasetHead(
        owner: UUID,
        expectedEpoch: UUID,
        expectedIdentity: DeviceIdentity? = null,
        requestGate: Any? = null,
        requestFence: (() -> Unit)? = null,
    ): Long {
        val session = snapshot().session ?: throw AuthFailure("SESSION_EXPIRED")
        if (session.user.id != owner) throw AuthFailure("DATASET_IDENTITY_MISMATCH")
        val result = withDatasetClearAdmission(expectedIdentity ?: DeviceSessions.identity(snapshot()), requestGate ?: Any(), requestFence ?: {}, allowsClear = false) {
            pullTypedChanges(null, 1, expectedIdentity = expectedIdentity, requestGate = requestGate, requestFence = requestFence)
        }
        if (UUID.fromString(result.getString("epoch")) != expectedEpoch) throw AuthFailure("DATASET_EPOCH_CHANGED")
        return result.optLong("headSeq", -1).also { if (it < 0) throw AuthFailure("INVALID_RESPONSE", retryable = true) }
    }

    internal fun bootstrapIdentity(
        owner: UUID, restoreBootstrapWire: RestoreBootstrapWire? = null,
        expectedIdentity: DeviceIdentity? = null, requestGate: Any? = null, requestFence: (() -> Unit)? = null,
        allowDatasetClear: Boolean = false,
    ): Pair<UUID, Long> {
        val identity = if (restoreBootstrapWire != null) {
            validateRestoreBootstrapWire(restoreBootstrapWire, requireJob = restoreBootstrapWire.bootstrapJobId != null)
            if (restoreBootstrapWire.owner != owner) throw AuthFailure("RESTORE_UNCONFIRMED")
            DeviceIdentity(restoreBootstrapWire.owner, restoreBootstrapWire.sessionId)
        } else {
            val current = store.read()
            val pending = current.restoreBarrierFor(owner)
            if (pending?.committedEpoch != null && current.session?.user?.id == owner && current.session.sessionId == pending.sessionId) {
                throw AuthFailure("RESTORE_UNCONFIRMED")
            }
            enforceRestoreBarrier(allowRestoreBarrier = expectedIdentity != null)
            if (expectedIdentity != null && !allowDatasetClear && store.read().datasetClear != null) throw AuthFailure("CLEAR_REAUTH_UNCONFIRMED")
            expectedIdentity
        }
        val snapshot = store.read()
        val currentSession = snapshot.session ?: throw AuthFailure("SESSION_EXPIRED")
        if (currentSession.user.id != owner) throw AuthFailure("DATASET_IDENTITY_MISMATCH")
        return try {
            withDatasetClearAdmission(expectedIdentity ?: DeviceSessions.identity(snapshot), requestGate ?: Any(), requestFence ?: {}, allowsClear = allowDatasetClear) {
                pullTypedChanges(null, 1, allowRestoreRecovery = restoreBootstrapWire != null, recoveryIdentity = identity,
                    restoreBootstrapWire = restoreBootstrapWire, expectedIdentity = expectedIdentity,
                    requestGate = requestGate, requestFence = requestFence, allowDatasetClear = allowDatasetClear)
            }
            throw AuthFailure("INVALID_RESPONSE")
        } catch (failure: AuthFailure) {
            if (failure.code != "BOOTSTRAP_REQUIRED") throw failure
            val epoch = runCatching { UUID.fromString(failure.fields["epoch"]) }.getOrNull()
                ?: throw AuthFailure("INVALID_RESPONSE")
            val head = failure.fields["headSeq"]?.toLongOrNull()?.takeIf { it >= 0 }
                ?: throw AuthFailure("INVALID_RESPONSE")
            if (epoch != restoreBootstrapWire?.committedEpoch && restoreBootstrapWire != null) throw AuthFailure("DATASET_EPOCH_CHANGED")
            if (identity != null) DeviceSessions.requireSameIdentity(identity, store.read())
            epoch to head
        }
    }

    internal fun bootstrapEpoch(
        owner: UUID, expectedIdentity: DeviceIdentity? = null, requestGate: Any? = null,
        requestFence: (() -> Unit)? = null, allowDatasetClear: Boolean = false,
    ): UUID = bootstrapIdentity(owner, expectedIdentity = expectedIdentity, requestGate = requestGate,
        requestFence = requestFence, allowDatasetClear = allowDatasetClear).first

    internal fun reauthenticateRestore(
        password: String,
        backupId: UUID,
        epoch: UUID,
        headSeq: Long,
        opId: UUID,
    ): String {
        requireNetworkConsent()
        validateSecret(password)
        val operation = JSONObject()
            .put("type", "RESTORE")
            .put("backupId", backupId.toString())
            .put("expectedEpoch", epoch.toString())
            .put("expectedHeadSeq", headSeq)
        val data = authenticatedSyncRequest(
            "/v1/auth/reauth",
            JSONObject().put("password", password).put("operation", operation),
            opId,
            responseMaxBytes = AuthJson.MAX_BYTES,
        )
        AuthJson.keys(data, setOf("actionToken", "expiresAt"))
        AuthJson.timestamp(AuthJson.string(data, "expiresAt", 24))
        return AuthJson.string(data, "actionToken", 4096)
    }

    internal fun listRestoreBackups(): List<RemoteBackup> {
        val result = ArrayList<RemoteBackup>()
        val seenCursors = HashSet<String>()
        var before: String? = null
        repeat(100) {
            val path = "/v1/backups?limit=100" + (before?.let { "&before=$it" } ?: "")
            val data = authenticatedSyncRequest(path, null, null, responseMaxBytes = MAX_SYNC_RESPONSE_BYTES)
            AuthJson.keys(data, setOf("items", "nextBefore"))
            val items = data.getJSONArray("items")
            if (items.length() > 100 || result.size + items.length() > 10_000) throw AuthFailure("INVALID_RESPONSE", retryable = true)
            for (index in 0 until items.length()) {
                val item = items.getJSONObject(index)
                AuthJson.keys(item, setOf("id", "epoch", "kind", "state", "capturedSeq", "schemaVersion", "manifest", "manifestDigest", "objectDigest", "storedBytes", "plainBytes", "failureCode", "createdAt", "protectedUntil", "deleteAfter"))
                val kind = if (item.isNull("kind")) null else AuthJson.string(item, "kind", 32)
                val manifest = if (item.isNull("manifest")) null else item.getJSONObject("manifest")
                val domains = manifest?.optJSONObject("entityCounts")?.let { counts -> counts.keys().asSequence().toList().sorted() } ?: emptyList()
                require(domains.size <= 128 && domains.all { it.matches(Regex("[a-z][a-z0-9_]{0,63}")) })
                result += RemoteBackup(
                    AuthJson.uuid(item, "id"), AuthJson.uuid(item, "epoch"), kind,
                    AuthJson.string(item, "state", 32),
                    if (item.isNull("capturedSeq")) null else AuthJson.integer(item, "capturedSeq").also { require(it >= 0) },
                    if (item.isNull("schemaVersion")) null else AuthJson.integer(item, "schemaVersion").toInt().also { require(it in 1..100) },
                    domains,
                    if (item.isNull("createdAt")) "" else AuthJson.string(item, "createdAt", 40),
                )
            }
            if (data.isNull("nextBefore")) return result
            val next = AuthJson.string(data, "nextBefore", 36)
            AuthJson.uuid(next)
            if (!seenCursors.add(next) || next == before || items.length() == 0) throw AuthFailure("INVALID_RESPONSE", retryable = true)
            before = next
        }
        throw AuthFailure("INVALID_RESPONSE", retryable = true)
    }

    internal fun pendingRestore(owner: UUID): PendingRestore? {
        val snapshot = store.read()
        val pending = snapshot.restoreBarrierFor(owner) ?: return null
        if (pending.owner != owner) throw AuthFailure("RESTORE_UNCONFIRMED")
        if (snapshot.session?.user?.id != owner) return null
        if (snapshot.session.sessionId != pending.sessionId) throw AuthFailure("RESTORE_UNCONFIRMED")
        return pending
    }

    internal fun beginRestore(operation: RestoreOperation) {
        val snapshot = store.read()
        operation.retainedFor(snapshot)
        store.beginRestore(snapshot, PendingRestore(
            operation.identity.owner, operation.identity.sessionId, operation.backupId,
            operation.epoch, operation.headSeq, operation.opId, operation.actionToken, operation.requestBody,
        ))
    }

    internal fun markRestoreCommitted(operation: RestoreOperation, status: RestoreStatus) {
        val before = store.read()
        operation.retainedFor(before)
        if (status.opId != operation.opId || status.backupId != operation.backupId ||
            status.expectedEpoch != operation.epoch || status.expectedHeadSeq != operation.headSeq || status.state != "COMMITTED") {
            throw AuthFailure("INVALID_RESPONSE")
        }
        val pending = before.restore ?: throw AuthFailure("RESTORE_UNCONFIRMED")
        store.updateRestore(before, pending.copyWithCommittedEpoch(
            status.committedEpoch ?: throw AuthFailure("INVALID_RESPONSE"), pending.bootstrapOpId ?: UUID.randomUUID(),
        ))
    }

    internal fun finishRestore(operation: RestoreOperation, status: RestoreStatus) {
        val snapshot = store.read()
        operation.retainedFor(snapshot)
        val pending = snapshot.restore ?: throw AuthFailure("STALE_CREDENTIALS")
        val matches = status.opId == operation.opId && status.backupId == operation.backupId &&
            status.expectedEpoch == operation.epoch && status.expectedHeadSeq == operation.headSeq
        val completed = when (status.state) {
            "FAILED", "CANCELLED" -> matches && pending.jobId == status.jobId
            "COMMITTED" -> matches && pending.committedEpoch == status.committedEpoch && status.committedEpoch != operation.epoch
            else -> false
        }
        if (!completed) throw AuthFailure("RESTORE_UNCONFIRMED")
        store.finishRestore(snapshot, pending)
    }

    internal fun restoreOperation(pending: PendingRestore): RestoreOperation = RestoreOperation(
        DeviceIdentity(pending.owner, pending.sessionId), pending.backupId, pending.epoch, pending.headSeq,
        pending.opId, pending.actionToken, pending.requestBody,
    )

    internal fun recordRestoreJob(operation: RestoreOperation, jobId: UUID) {
        val snapshot = store.read()
        operation.retainedFor(snapshot)
        val pending = snapshot.restore ?: throw AuthFailure("RESTORE_UNCONFIRMED")
        store.updateRestore(snapshot, pending.copyWithJob(jobId))
    }

    internal fun createRestore(bodyJson: String, opId: UUID): RestoreAccepted {
        val current = store.read()
        val pending = current.restore ?: throw AuthFailure("RESTORE_UNCONFIRMED")
        if (pending.opId != opId || pending.requestBody != bodyJson || pending.jobId != null) throw AuthFailure("STALE_CREDENTIALS")
        if (current.session?.user?.id != pending.owner || current.session.sessionId != pending.sessionId) throw AuthFailure("RESTORE_UNCONFIRMED")
        val operation = restoreOperation(pending)
        operation.retainedFor(current)
        return try {
            RestoreProtocol.accepted(
                restoreAuthenticatedRequest("/v1/restores", JSONObject(bodyJson), opId, 202), opId,
            ).also { accepted ->
                if (accepted.state !in setOf("QUEUED", "PROTECTING", "STAGING", "VALIDATING", "COMMITTING", "COMMITTED")) {
                    throw AuthFailure("INVALID_RESPONSE", retryable = true)
                }
                val after = store.read()
                operation.retainedFor(after)
                store.updateRestore(after, pending.copyWithJob(accepted.jobId))
            }
        } catch (failure: AuthFailure) {
            // An expired session without a restore receipt remains unknown; retain the original wire.
            throw failure
        }
    }

    internal fun restoreStatus(jobId: UUID): RestoreStatus {
        val current = store.read()
        val pending = current.restore ?: throw AuthFailure("RESTORE_UNCONFIRMED")
        val operation = restoreOperation(pending)
        operation.retainedFor(current)
        if (pending.jobId != jobId) throw AuthFailure("STALE_CREDENTIALS")
        return RestoreProtocol.status(
            restoreAuthenticatedRequest("/v1/restores/$jobId", null, null, 200), jobId,
        )
    }

    private fun restoreAuthenticatedRequest(path: String, body: JSONObject?, opId: UUID?, expected: Int): JSONObject =
        authenticatedSyncRequest(path, body, opId, expected, responseMaxBytes = MAX_SYNC_RESPONSE_BYTES, allowRestoreBarrier = true)

    internal fun restoreBootstrapWire(jobId: UUID? = null): RestoreBootstrapWire {
        val initial = store.read()
        val pending = initial.restore ?: throw AuthFailure("RESTORE_UNCONFIRMED")
        if (pending.bootstrapOpId == null && pending.committedEpoch != null) {
            val current = initial.restore ?: throw AuthFailure("RESTORE_UNCONFIRMED")
            if (current.jobId == null || current.committedEpoch == null || initial.session?.user?.id != current.owner || initial.session.sessionId != current.sessionId)
                throw AuthFailure("RESTORE_UNCONFIRMED")
            store.updateRestore(initial, current.copyWithCommittedEpoch(current.committedEpoch, UUID.randomUUID()))
        }
        val state = store.read()
        val resumed = state.restore ?: throw AuthFailure("RESTORE_UNCONFIRMED")
        val session = state.session ?: throw AuthFailure("SESSION_EXPIRED")
        if (session.user.id != resumed.owner || session.sessionId != resumed.sessionId || resumed.jobId == null || resumed.committedEpoch == null || resumed.bootstrapOpId == null)
            throw AuthFailure("RESTORE_UNCONFIRMED")
        if (jobId != null && resumed.bootstrapJobId != jobId) throw AuthFailure("RESTORE_UNCONFIRMED")
        return RestoreBootstrapWire(resumed.owner, resumed.sessionId, resumed.jobId, resumed.committedEpoch, resumed.bootstrapOpId, resumed.bootstrapJobId)
    }

    internal fun createBootstrapSnapshot(
        epoch: UUID, opId: UUID, expectedIdentity: DeviceIdentity, requestGate: Any, requestFence: () -> Unit,
        allowDatasetClear: Boolean = false,
    ): JSONObject {
        val body = JSONObject().put("epoch", epoch.toString()).put("reason", "NEW_DEVICE").put("knownHeadSeq", JSONObject.NULL)
        if (allowDatasetClear && store.read().datasetClear == null) throw AuthFailure("CLEAR_REAUTH_UNCONFIRMED")
        return withDatasetClearAdmission(expectedIdentity, requestGate, requestFence, allowsClear = allowDatasetClear) {
            authenticatedSyncRequest("/v1/sync/snapshots", body, opId, 202,
                maxBytes = MAX_SYNC_REQUEST_BYTES, responseMaxBytes = MAX_SYNC_RESPONSE_BYTES,
                expectedIdentity = expectedIdentity, operationGate = requestGate, operationFence = requestFence, allowDatasetClear = allowDatasetClear)
        }
    }

    internal fun bootstrapSnapshotStatus(
        jobId: UUID, expectedIdentity: DeviceIdentity, requestGate: Any, requestFence: () -> Unit, allowDatasetClear: Boolean,
    ): JSONObject = withDatasetClearAdmission(expectedIdentity, requestGate, requestFence, allowsClear = allowDatasetClear) {
        authenticatedSyncRequest("/v1/sync/snapshots/$jobId", null, null,
            maxBytes = MAX_SYNC_REQUEST_BYTES, responseMaxBytes = MAX_SYNC_RESPONSE_BYTES,
            expectedIdentity = expectedIdentity, operationGate = requestGate, operationFence = requestFence, allowDatasetClear = allowDatasetClear)
    }

    internal fun createBootstrapSnapshot(epoch: UUID, wire: RestoreBootstrapWire): JSONObject {
        validateRestoreBootstrapWire(wire, requireJob = false)
        if (epoch != wire.committedEpoch || wire.bootstrapJobId != null) throw AuthFailure("RESTORE_UNCONFIRMED")
        val body = JSONObject().put("epoch", epoch.toString()).put("reason", "NEW_DEVICE").put("knownHeadSeq", JSONObject.NULL)
        val data = authenticatedSyncRequest("/v1/sync/snapshots", body, wire.bootstrapOpId, 202,
            maxBytes = MAX_SYNC_REQUEST_BYTES, responseMaxBytes = MAX_SYNC_RESPONSE_BYTES,
            restoreBootstrapWire = wire)
        val jobId = AuthJson.uuid(data.getString("jobId"))
        val after = store.read()
        val current = after.restore ?: throw AuthFailure("RESTORE_UNCONFIRMED")
        store.bindRestoreBootstrap(after, current, wire.copy(bootstrapJobId = jobId))
        val bound = restoreBootstrapWire(jobId)
        if (bound.owner != wire.owner || bound.sessionId != wire.sessionId || bound.restoreJobId != wire.restoreJobId ||
            bound.committedEpoch != wire.committedEpoch || bound.bootstrapOpId != wire.bootstrapOpId || bound.bootstrapJobId != jobId)
            throw AuthFailure("RESTORE_UNCONFIRMED")
        return data
    }

    internal fun bootstrapSnapshotStatus(jobId: UUID, wire: RestoreBootstrapWire): JSONObject {
        validateRestoreBootstrapWire(wire, requireJob = true)
        if (wire.bootstrapJobId != jobId) throw AuthFailure("RESTORE_UNCONFIRMED")
        return authenticatedSyncRequest("/v1/sync/snapshots/$jobId", null, null,
            maxBytes = MAX_SYNC_REQUEST_BYTES, responseMaxBytes = MAX_SYNC_RESPONSE_BYTES,
            restoreBootstrapWire = wire)
    }

    private fun validateRestoreBootstrapWire(wire: RestoreBootstrapWire, requireJob: Boolean) {
        val state = store.read()
        val pending = state.restore ?: throw AuthFailure("RESTORE_UNCONFIRMED")
        val session = state.session ?: throw AuthFailure("SESSION_EXPIRED")
        if (pending.owner != wire.owner || pending.sessionId != wire.sessionId || pending.jobId != wire.restoreJobId ||
            pending.committedEpoch != wire.committedEpoch || pending.bootstrapOpId != wire.bootstrapOpId ||
            (if (requireJob) pending.bootstrapJobId != wire.bootstrapJobId || wire.bootstrapJobId == null else pending.bootstrapJobId != null) ||
            session.user.id != wire.owner || session.sessionId != wire.sessionId) throw AuthFailure("RESTORE_UNCONFIRMED")
    }

    internal fun downloadBootstrapSnapshot(
        jobId: UUID,
        cursor: String,
        destination: File,
        cancelled: () -> Boolean = { false },
        restoreBootstrapWire: RestoreBootstrapWire? = null,
        expectedIdentity: DeviceIdentity? = null,
        requestGate: Any? = null,
        requestFence: (() -> Unit)? = null,
        allowDatasetClear: Boolean = false,
    ): SnapshotDownload {
        if (restoreBootstrapWire == null) {
            if (expectedIdentity == null || requestGate == null || requestFence == null) throw AuthFailure("CLEAR_REAUTH_UNCONFIRMED")
            requireNetworkConsent()
            val identity = expectedIdentity
            val gate = requestGate
            val fence = requestFence
            var actor = validSession()
            DeviceSessions.requireSameIdentity(identity, actor)
            try {
                return withDatasetClearAdmission(identity, gate, fence, allowsClear = allowDatasetClear) {
                    try {
                        downloadSnapshotContent(jobId, cursor, destination, actor, cancelled, expectedIdentity = identity,
                            requestGate = gate, requestFence = fence, allowDatasetClear = allowDatasetClear).also {
                            DeviceSessions.requireSameIdentity(identity, store.read())
                        }
                    } catch (failure: AuthFailure) {
                        if (failure.code != "SESSION_EXPIRED") throw failure
                        actor = refresh(actor)
                        DeviceSessions.requireSameIdentity(identity, actor)
                        downloadSnapshotContent(jobId, cursor, destination, actor, cancelled, expectedIdentity = identity,
                            requestGate = gate, requestFence = fence, allowDatasetClear = allowDatasetClear).also {
                            DeviceSessions.requireSameIdentity(identity, store.read())
                        }
                    }
                }
            } catch (failure: AuthFailure) {
                invalidateTerminal(actor, failure)
                destination.delete()
                throw failure
            }
        }
        val wire = restoreBootstrapWire
        validateRestoreBootstrapWire(wire, requireJob = true)
        if (wire.bootstrapJobId != jobId) throw AuthFailure("RESTORE_UNCONFIRMED")
        val current = store.read()
        val actorSession = current.session ?: throw AuthFailure("SESSION_EXPIRED")
        val expected = DeviceIdentity(wire.owner, wire.sessionId)
        var actor = current
        try {
            return try {
                downloadSnapshotContent(jobId, cursor, destination, actor, cancelled, wire).also {
                    DeviceSessions.requireSameIdentity(expected, store.read())
                    validateRestoreBootstrapWire(wire, requireJob = true)
                }
            } catch (failure: AuthFailure) {
                if (failure.code != "SESSION_EXPIRED") throw failure
                actor = refresh(actor)
                DeviceSessions.requireSameIdentity(expected, actor)
                downloadSnapshotContent(jobId, cursor, destination, actor, cancelled, wire).also {
                    validateRestoreBootstrapWire(wire, requireJob = true)
                }
            }
        } catch (failure: AuthFailure) {
            invalidateTerminal(actor, failure)
            destination.delete()
            throw failure
        }
    }

    private fun downloadSnapshotContent(
        jobId: UUID,
        cursor: String,
        destination: File,
        actor: AuthState,
        cancelled: () -> Boolean,
        restoreBootstrapWire: RestoreBootstrapWire? = null,
        expectedIdentity: DeviceIdentity? = null,
        requestGate: Any? = null,
        requestFence: (() -> Unit)? = null,
        allowDatasetClear: Boolean = false,
    ): SnapshotDownload {
        requireNetworkConsent()
        restoreBootstrapWire?.let { validateRestoreBootstrapWire(it, requireJob = true) }
        check(Looper.myLooper() != Looper.getMainLooper()) { "Snapshot downloads require a background thread" }
        if (!configured) throw AuthFailure("UNCONFIGURED")
        val session = actor.session ?: throw AuthFailure("SESSION_EXPIRED")
        if (restoreBootstrapWire == null && (expectedIdentity == null || requestGate == null || requestFence == null)) throw AuthFailure("CLEAR_REAUTH_UNCONFIRMED")
        if (restoreBootstrapWire == null && store.read().hasRestoreBarrierForCurrentSession()) throw AuthFailure("RESTORE_UNCONFIRMED")
        if (restoreBootstrapWire != null && (actor.session?.user?.id != restoreBootstrapWire.owner || actor.session.sessionId != restoreBootstrapWire.sessionId))
            throw AuthFailure("RESTORE_UNCONFIRMED")
        val connection = try {
            URL("$origin/v1/sync/snapshots/$jobId/content").openConnection() as HttpsURLConnection
        } catch (_: Exception) {
            throw AuthFailure("UNCONFIGURED")
        }
        val timeout = timer.schedule({ connection.disconnect() }, 10, TimeUnit.MINUTES)
        val deadline = SystemClock.elapsedRealtime() + TimeUnit.MINUTES.toMillis(10)
        var requestHandle: SyncRequestHandle? = null
        try {
            connection.apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = false
                useCaches = false
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Cache-Control", "no-store")
                setRequestProperty("Authorization", "Bearer ${session.accessToken}")
                setRequestProperty("X-Device-Id", actor.deviceId.toString())
            }
            requestHandle = if (restoreBootstrapWire == null) {
                withDatasetClearAdmission(expectedIdentity!!, requestGate!!, requestFence!!, allowsClear = allowDatasetClear) {
                    beginSyncRequest(connection, null, operationIdentity = expectedIdentity, operationFence = requestFence, operationGate = requestGate)
                }
            } else beginSyncRequest(connection, null,
                operationIdentity = DeviceIdentity(restoreBootstrapWire.owner, restoreBootstrapWire.sessionId))
            if (syncResponseCode(requestHandle ?: throw AuthFailure("SNAPSHOT_CANCELLED")) != 200) {
                val bytes = connection.errorStream?.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(2048)
                    while (output.size() <= AuthJson.MAX_BYTES) {
                        val count = input.read(buffer, 0, minOf(buffer.size, AuthJson.MAX_BYTES + 1 - output.size()))
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                    ?: throw AuthFailure("INVALID_RESPONSE", retryable = true)
                val requestId = AuthJson.uuid(connection.getHeaderField("X-Request-Id") ?: error("missing request ID"))
                throw AuthJson.error(AuthJson.parse(bytes), requestId, 0)
            }
            if (!connection.contentType.orEmpty().substringBefore(';').trim().equals("application/json", true)) {
                throw AuthFailure("INVALID_RESPONSE", retryable = true)
            }
            if (!connection.getHeaderField("Cache-Control").orEmpty().split(',')
                    .any { it.trim().equals("no-store", true) }
            ) throw AuthFailure("INVALID_RESPONSE", retryable = true)
            val manifestHeader = connection.getHeaderField("x-snapshot-manifest")
                ?: throw AuthFailure("INVALID_RESPONSE", retryable = true)
            if (manifestHeader.toByteArray(Charsets.UTF_8).size > AuthJson.MAX_BYTES) {
                throw AuthFailure("INVALID_RESPONSE")
            }
            val envelope = AuthJson.parse(manifestHeader.toByteArray(Charsets.UTF_8))
            var total = 0L
            FileOutputStream(destination, false).use { output ->
                connection.inputStream.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (cancelled() || Thread.currentThread().isInterrupted || SystemClock.elapsedRealtime() >= deadline) {
                            throw if (cancelled()) AuthFailure("SNAPSHOT_CANCELLED")
                            else AuthFailure("NETWORK", retryable = true)
                        }
                        val count = input.read(buffer)
                        if (count < 0) break
                        total = Math.addExact(total, count.toLong())
                        if (total > 2L * 1024 * 1024 * 1024) throw AuthFailure("INVALID_RESPONSE")
                        output.write(buffer, 0, count)
                    }
                }
                output.fd.sync()
            }
            return SnapshotDownload(envelope, total, cursor)
        } catch (failure: AuthFailure) {
            destination.delete()
            throw failure
        } catch (_: Exception) {
            destination.delete()
            throw AuthFailure("NETWORK", retryable = true)
        } finally {
            requestHandle?.let(::endSyncRequest)
            timeout.cancel(false)
            connection.disconnect()
        }
    }

    internal fun listBackups(state: String? = null, before: UUID? = null): JSONObject {
        val query = buildString {
            append("/v1/backups?limit=50")
            state?.let { append("&state=").append(URLEncoder.encode(it, Charsets.UTF_8.name())) }
            before?.let { append("&before=").append(it) }
        }
        return authenticatedSyncRequest(query, null, null)
    }

    internal fun backupDetail(id: UUID): JSONObject = authenticatedSyncRequest("/v1/backups/$id", null, null)
    internal fun backupPolicy(): JSONObject = authenticatedSyncRequest("/v1/backup-policy", null, null)
    internal fun createManualBackup(epoch: UUID, minAcceptedSeq: Long, opId: UUID): JSONObject =
        authenticatedSyncRequest("/v1/backups", JSONObject().put("expectedEpoch", epoch.toString()).put("minAcceptedSeq", minAcceptedSeq).put("reason", "MANUAL"), opId, 202)
    internal fun updateBackupPolicy(body: JSONObject, opId: UUID): JSONObject =
        authenticatedSyncRequest("/v1/backup-policy", body, opId, method = "PATCH")
    internal fun reauthorizeBackupDelete(backupId: UUID, password: String, opId: UUID): JSONObject =
        authenticatedSyncRequest("/v1/auth/reauth", JSONObject().put("password", password).put("operation", JSONObject().put("type", "BACKUP_DELETE").put("backupId", backupId.toString())), opId)
    internal fun deleteBackup(id: UUID, actionToken: String, opId: UUID): JSONObject =
        authenticatedSyncRequest("/v1/backups/$id", JSONObject().put("actionToken", actionToken), opId, 202, method = "DELETE")

    internal fun listFeedback(cursor: String?, limit: Int = 25): JSONObject {
        require(limit in 1..100)
        val query = buildString {
            append("/v1/feedback?limit=").append(limit)
            cursor?.let { append("&cursor=").append(URLEncoder.encode(it, Charsets.UTF_8.name())) }
        }
        return authenticatedSyncRequest(query, null, null, responseMaxBytes = MAX_FEEDBACK_RESPONSE_BYTES)
    }

    internal fun feedbackDetail(id: UUID): JSONObject =
        authenticatedSyncRequest("/v1/feedback/$id", null, null, responseMaxBytes = MAX_FEEDBACK_RESPONSE_BYTES)

    internal fun createFeedback(body: JSONObject, opId: UUID): JSONObject {
        require(body.toString().toByteArray(Charsets.UTF_8).size <= MAX_FEEDBACK_REQUEST_BYTES)
        return authenticatedSyncRequest("/v1/feedback", body, opId, expectedStatus = 201,
            maxBytes = MAX_FEEDBACK_REQUEST_BYTES, responseMaxBytes = 4096)
    }

    internal fun createCategoryShare(body: JSONObject, opId: UUID, expectedIdentity: DeviceIdentity): JSONObject {
        require(body.toString().toByteArray(Charsets.UTF_8).size <= 64 * 1024)
        return authenticatedSyncRequest("/v1/category-shares", body, opId, expectedStatus = 201,
            maxBytes = 64 * 1024, responseMaxBytes = 16 * 1024, expectedIdentity = expectedIdentity)
    }

    internal fun previewCategoryShare(token: String): JSONObject {
        require(token.length == 64 && token.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' })
        return request("/v1/category-shares/$token", 200, responseMaxBytes = 64 * 1024)
    }

    private fun authenticatedSyncRequest(
        path: String,
        body: JSONObject?,
        opId: UUID?,
        expectedStatus: Int = 200,
        maxBytes: Int = MAX_SYNC_REQUEST_BYTES,
        responseMaxBytes: Int = MAX_SYNC_RESPONSE_BYTES,
        method: String? = null,
        allowRestoreBarrier: Boolean = false,
        restoreRecoveryIdentity: DeviceIdentity? = null,
        restoreBootstrapWire: RestoreBootstrapWire? = null,
        expectedIdentity: DeviceIdentity? = null,
        operationGate: Any? = null,
        operationFence: (() -> Unit)? = null,
        allowDatasetClear: Boolean = false,
    ): JSONObject {
        val suppliedBootstrapWire = restoreBootstrapWire
        val clearAdmission = datasetClearAdmission.get()
        if (allowDatasetClear && clearAdmission?.allowsClear != true) throw AuthFailure("CLEAR_REAUTH_UNCONFIRMED")
        if (clearAdmission != null && expectedIdentity != null && clearAdmission.identity != expectedIdentity) throw AuthFailure("STALE_CREDENTIALS")
        val allowClearForRequest = clearAdmission?.allowsClear == true || (clearAdmission != null &&
            path == "/v1/auth/reauth" && body?.optJSONObject("operation")?.optString("type") == "DATASET_CLEAR_RECORDS" &&
            store.read().datasetClear?.let { pending ->
                pending.phase == "AUTHORIZED" && pending.opId == opId && pending.password == body.optString("password") &&
                    pending.ownerId == clearAdmission.identity.owner && pending.sessionId == clearAdmission.identity.sessionId
            } == true)
        if (suppliedBootstrapWire != null) validateRestoreBootstrapWire(suppliedBootstrapWire, requireJob = suppliedBootstrapWire.bootstrapJobId != null)
        enforceRestoreBarrier(allowRestoreBarrier || allowClearForRequest || suppliedBootstrapWire != null)
        var snapshot = validSession()
        val expected = expectedIdentity ?: DeviceSessions.identity(snapshot)
        DeviceSessions.requireSameIdentity(expected, snapshot)
        if (clearAdmission != null && clearAdmission.identity != expected) throw AuthFailure("STALE_CREDENTIALS")
        if (restoreBootstrapWire == null && !allowRestoreBarrier && !allowClearForRequest && store.read().hasRestoreBarrierForCurrentSession()) throw AuthFailure("RESTORE_UNCONFIRMED")
        if (store.read().datasetClear != null && !allowClearForRequest) throw AuthFailure("CLEAR_REAUTH_UNCONFIRMED")
        val effectiveGate = operationGate ?: clearAdmission?.gate
        val effectiveFence = operationFence ?: clearAdmission?.fence
        val data = try {
            try {
                request(path, expectedStatus, body, opId, snapshot, method = method, maxBytes = maxBytes,
                    responseMaxBytes = responseMaxBytes, allowRestoreRecovery = allowRestoreBarrier,
                    restoreRecoveryIdentity = restoreRecoveryIdentity, restoreBootstrapWire = restoreBootstrapWire,
                    operationIdentity = expected, operationGate = effectiveGate, operationFence = effectiveFence)
            } catch (failure: AuthFailure) {
                if (failure.code != "SESSION_EXPIRED") throw failure
                snapshot = refresh(snapshot)
                request(path, expectedStatus, body, opId, snapshot, method = method, maxBytes = maxBytes,
                    responseMaxBytes = responseMaxBytes, allowRestoreRecovery = allowRestoreBarrier,
                    restoreRecoveryIdentity = restoreRecoveryIdentity, restoreBootstrapWire = restoreBootstrapWire,
                    operationIdentity = expected, operationGate = effectiveGate, operationFence = effectiveFence)
            }
        } catch (failure: AuthFailure) {
            invalidateTerminal(snapshot, failure)
            throw failure
        }
        if (!store.read().matches(snapshot)) throw AuthFailure("STALE_CREDENTIALS")
        DeviceSessions.requireSameIdentity(expected, snapshot)
        return data
    }

    fun operation(
        target: UUID,
        opId: UUID = UUID.randomUUID(),
    ): RevokeOperation = DeviceSessions.operation(store.read(), target, opId)

    /** 204 is the only accepted proof; the pinned identity is re-checked before sending and after. */
    fun revokeDevice(operation: RevokeOperation) {
        var snapshot = store.read()
        operation.retainedFor(snapshot)
        try {
            try {
                revoke(snapshot, operation)
            } catch (failure: AuthFailure) {
                if (failure.code != "SESSION_EXPIRED") throw failure
                val before = snapshot
                snapshot = refresh(before)
                operation.acceptRotation(before, snapshot)
                revoke(snapshot, operation)
            }
        } catch (failure: AuthFailure) {
            invalidateTerminal(snapshot, failure)
            throw failure
        }
        operation.retainedFor(store.read())
    }

    private fun revoke(snapshot: AuthState, operation: RevokeOperation) {
        operation.retainedFor(snapshot)
        request(
            "/v1/me/devices/" + operation.target,
            204,
            opId = operation.opId,
            actor = snapshot,
            method = "DELETE",
        )
    }

    internal fun beginDatasetClear(snapshot: AuthState, operation: PendingDatasetClear): PendingDatasetClear {
        requireNetworkConsent()
        return checkNotNull(store.beginDatasetClear(snapshot, operation).datasetClear)
    }

    internal fun resumeDatasetClearReauth(snapshot: AuthState, password: String): PendingDatasetClear =
        checkNotNull(store.resumeDatasetClearReauth(snapshot, password).datasetClear)

    internal fun discardDatasetClear(operation: PendingDatasetClear) = store.discardDatasetClear(store.read(), operation.opId)

    internal fun pendingDatasetClear(): PendingDatasetClear? = store.read().datasetClear

    internal fun datasetClearOperationId(): UUID = UUID.randomUUID()

    internal fun reauthenticateDatasetClear(
        operation: PendingDatasetClear,
        expectedIdentity: DeviceIdentity,
        requestGate: Any,
        requestFence: () -> Unit,
    ): PendingDatasetClear {
        requireNetworkConsent()
        val snapshot = store.read()
        DeviceSessions.requireSameIdentity(expectedIdentity, snapshot)
        val pending = snapshot.datasetClear ?: throw AuthFailure("CLEAR_REAUTH_UNCONFIRMED")
        if (pending.opId != operation.opId || pending.ownerId != operation.ownerId || pending.epochId != operation.epochId ||
            pending.sessionId != operation.sessionId || pending.groupId != operation.groupId || pending.ledgerIdsJson != operation.ledgerIdsJson ||
            pending.expectedHeadSeq != operation.expectedHeadSeq || pending.phase !in setOf("REAUTH", "AUTHORIZED") || pending.password == null) {
            throw AuthFailure("STALE_CREDENTIALS")
        }
        val target = JSONObject().put("type", "DATASET_CLEAR_RECORDS").put("epoch", pending.epochId.toString())
            .put("expectedHeadSeq", pending.expectedHeadSeq).put("ledgerIds", JSONArray(pending.ledgerIdsJson))
            .put("dependencyStrategy", "REJECT")
        val authorized = PendingDatasetClear(pending.ownerId, pending.epochId, pending.sessionId, pending.opId, pending.groupId,
            pending.ledgerIdsJson, pending.expectedHeadSeq, "AUTHORIZED", pending.password, null, null, null, null)
        val authorizedSnapshot = store.authorizeDatasetClear(snapshot, authorized)
        val response = withDatasetClearAdmission(expectedIdentity, requestGate, requestFence, allowsClear = false) {
            request("/v1/auth/reauth", 200, JSONObject().put("password", pending.password).put("operation", target),
                pending.opId, authorizedSnapshot, operationIdentity = expectedIdentity, operationGate = requestGate, operationFence = requestFence)
        }
        AuthJson.keys(response, setOf("actionToken", "expiresAt"))
        if (AuthJson.timestamp(AuthJson.string(response, "expiresAt", 24)) <= System.currentTimeMillis()) throw AuthFailure("STALE_CREDENTIALS")
        requestFence()
        DeviceSessions.requireSameIdentity(expectedIdentity, store.read())
        val token = AuthJson.string(response, "actionToken", 4096)
        val payload = JSONObject().put("ledgerIds", JSONArray(pending.ledgerIdsJson)).put("dependencyStrategy", "REJECT").put("confirmationToken", token)
        val group = JSONObject().put("opId", pending.opId.toString()).put("groupId", pending.groupId.toString())
            .put("aggregateType", "dataset").put("aggregateId", pending.ownerId.toString()).put("baseVersions", JSONObject())
            .put("commandType", "dataset.clearRecords").put("schemaVersion", 1).put("payload", payload)
        val ready = PendingDatasetClear(pending.ownerId, pending.epochId, pending.sessionId, pending.opId, pending.groupId,
            pending.ledgerIdsJson, pending.expectedHeadSeq, "READY", null, token, group.toString(), null, null)
        return checkNotNull(store.updateDatasetClear(authorizedSnapshot, ready).datasetClear)
    }

    internal fun pushDatasetClear(
        operation: PendingDatasetClear,
        expectedIdentity: DeviceIdentity,
        requestGate: Any,
        requestFence: () -> Unit,
    ): JSONObject {
        requireNetworkConsent()
        val snapshot = store.read()
        DeviceSessions.requireSameIdentity(expectedIdentity, snapshot)
        val pending = snapshot.datasetClear ?: throw AuthFailure("CLEAR_REAUTH_UNCONFIRMED")
        if (pending.opId != operation.opId || pending.ownerId != operation.ownerId || pending.epochId != operation.epochId ||
            pending.sessionId != operation.sessionId || pending.groupId != operation.groupId || pending.ledgerIdsJson != operation.ledgerIdsJson ||
            pending.expectedHeadSeq != operation.expectedHeadSeq || pending.phase !in setOf("READY", "ACKED") ||
            pending.frozenGroupJson != operation.frozenGroupJson || pending.actionToken != operation.actionToken) throw AuthFailure("STALE_CREDENTIALS")
        if (pending.phase == "ACKED") return JSONObject().put("opId", pending.opId.toString()).put("status", "ALREADY_APPLIED")
            .put("seq", pending.ackSeq).put("snapshotRequired", true).put("requiredCapturedSeq", pending.requiredCapturedSeq)
        val group = AuthJson.parse(operation.frozenGroupJson!!.toByteArray(Charsets.UTF_8), 64 * 1024)
        if (group.getString("opId") != operation.opId.toString() || group.getString("groupId") != operation.groupId.toString() ||
            group.getString("commandType") != "dataset.clearRecords" || group.getJSONObject("payload").getString("confirmationToken") != operation.actionToken) {
            throw AuthFailure("CLEAR_REAUTH_UNCONFIRMED")
        }
        val response = withDatasetClearAdmission(expectedIdentity, requestGate, requestFence, allowsClear = true) {
            pushTypedGroups(operation.epochId, JSONArray().put(group), expectedIdentity, requestGate, requestFence, allowDatasetClear = true)
        }
        val results = response.optJSONArray("results") ?: throw AuthFailure("INVALID_RESPONSE", retryable = true)
        if (results.length() != 1) throw AuthFailure("INVALID_RESPONSE", retryable = true)
        val result = results.getJSONObject(0)
        if (result.optString("opId") != operation.opId.toString()) throw AuthFailure("INVALID_RESPONSE", retryable = true)
        val status = result.optString("status")
        if (status !in setOf("APPLIED", "ALREADY_APPLIED")) throw AuthFailure(result.optJSONObject("error")?.optString("code") ?: "CLEAR_REJECTED")
        val seq = result.optLong("seq", -1)
        val required = result.optLong("requiredCapturedSeq", -1)
        if (!result.optBoolean("snapshotRequired") || seq <= 0 || required < seq) throw AuthFailure("INVALID_RESPONSE", retryable = true)
        return result
    }

    internal fun markDatasetClearAck(snapshot: AuthState, operation: PendingDatasetClear, seq: Long, requiredSeq: Long): PendingDatasetClear {
        val acked = PendingDatasetClear(operation.ownerId, operation.epochId, operation.sessionId, operation.opId, operation.groupId,
            operation.ledgerIdsJson, operation.expectedHeadSeq, "ACKED", null, operation.actionToken, operation.frozenGroupJson, seq, requiredSeq)
        return checkNotNull(store.updateDatasetClear(snapshot, acked).datasetClear)
    }

    internal fun markDatasetClearAmbiguous(snapshot: AuthState, operation: PendingDatasetClear) {
        if (operation.phase == "READY") store.updateDatasetClear(snapshot, operation)
    }

    internal fun finishDatasetClear(snapshot: AuthState, opId: UUID) = store.finishDatasetClear(snapshot, opId)

    internal fun reauthenticateForAccountDeletion(
        snapshot: AuthState,
        password: String,
        opId: UUID,
    ): String {
        requireNetworkConsent()
        val session = snapshot.session ?: throw AuthFailure("SESSION_EXPIRED")
        if (!store.read().matches(snapshot)) throw AuthFailure("STALE_CREDENTIALS")
        val body = JSONObject().put("password", password).put("operation", JSONObject().put("type", "ACCOUNT_DELETE"))
        val result = request("/v1/auth/reauth", 200, body, opId, snapshot)
        AuthJson.keys(result, setOf("actionToken", "expiresAt"))
        AuthJson.timestamp(AuthJson.string(result, "expiresAt", 24))
        if (!store.read().matches(snapshot) || store.read().session?.sessionId != session.sessionId) {
            throw AuthFailure("STALE_CREDENTIALS")
        }
        return AuthJson.string(result, "actionToken", 128)
    }

    internal fun submitAccountDeletion(snapshot: AuthState, actionToken: String, opId: UUID): AccountDeletionSubmission {
        requireNetworkConsent()
        val session = snapshot.session ?: throw AuthFailure("SESSION_EXPIRED")
        if (!store.read().matches(snapshot)) throw AuthFailure("STALE_CREDENTIALS")
        val result = try {
            request(
                "/v1/me/deletion",
                202,
                JSONObject().put("actionToken", actionToken).put("confirmation", "DELETE"),
                opId,
                snapshot,
            )
        } catch (failure: AuthFailure) {
            if (failure.code == "SESSION_EXPIRED" || failure.code == "SESSION_REVOKED") {
                throw AuthFailure(failure.code, failure.requestId, retryable = true)
            }
            throw failure
        }
        val receipt = AuthJson.accountDeletionReceipt(result)
        val retired = try {
            val current = store.read()
            if (current.matches(snapshot)) {
                store.replace(current, null)
                true
            } else {
                false
            }
        } catch (_: AuthFailure) {
            false
        }
        return AccountDeletionSubmission(receipt, retired)
    }

    fun prepareLogout(opId: UUID): LogoutAttempt = synchronized(refreshMonitor) {
        // Capture the latest committed rotation, then sign out before any logout/refresh network IO.
        val snapshot = store.read()
        if (snapshot.session == null) throw AuthFailure("SESSION_EXPIRED")
        store.replace(snapshot, null)
        LogoutAttempt(snapshot, opId)
    }

    fun logout(attempt: LogoutAttempt) = attempt.run(
        System.currentTimeMillis(),
        refresh = ::requestRefresh,
        revoke = { snapshot, operation ->
            val session = checkNotNull(snapshot.session)
            request("/v1/auth/logout", 204, JSONObject().put("sessionId", session.sessionId.toString()), operation, snapshot)
        },
    )

    private fun requestRefresh(snapshot: AuthState, pending: PendingRefresh): AuthSession {
        val previous = checkNotNull(snapshot.session)
        val body = JSONObject().put("refreshToken", pending.originalRefresh).put("deviceId", snapshot.deviceId.toString())
            .put("refreshRequestId", pending.opId.toString())
        return AuthJson.session(request("/v1/auth/refresh", 200, body, pending.opId), snapshot.deviceId, previous.user.id, previous.sessionId)
    }

    private fun validSession(): AuthState {
        val snapshot = store.read()
        val session = snapshot.session ?: throw AuthFailure("SESSION_EXPIRED")
        return if (snapshot.pending != null ||
            session.expiresAtMillis <= System.currentTimeMillis() + 30_000
        ) {
            refresh(snapshot)
        } else {
            snapshot
        }
    }

    private fun refresh(expected: AuthState): AuthState =
        synchronized(refreshMonitor) {
            val current = store.read()
            if (!current.matches(expected)) {
                val previous = expected.session
                val next = current.session
                if (previous != null && next != null && next.user.id == previous.user.id &&
                    next.sessionId == previous.sessionId
                ) {
                    return@synchronized current
                }
                throw AuthFailure("STALE_CREDENTIALS")
            }
            val snapshot = store.beginRefresh(current)
            val pending = checkNotNull(snapshot.pending)
            try {
                // The same UUID is used for both receipt keys, including after restart or a lost response.
                val result = requestRefresh(snapshot, pending)
                store.finishRefresh(snapshot, pending.opId, result)
            } catch (failure: AuthFailure) {
                invalidateTerminal(snapshot, failure)
                throw failure
            }
        }

    private fun invalidateTerminal(
        snapshot: AuthState,
        failure: AuthFailure,
    ) {
        if (failure.code in
            setOf("SESSION_EXPIRED", "SESSION_REVOKED", "ACCOUNT_DISABLED", "IDEMPOTENCY_RECEIPT_EXPIRED", "IDEMPOTENCY_KEY_REUSED")
        ) {
            try {
                store.replace(snapshot, null)
            } catch (stale: AuthFailure) {
                if (stale.code != "STALE_CREDENTIALS") throw stale
            }
        }
    }

    private fun validateSecret(value: String) {
        if (value.codePointCount(0, value.length) !in 12..128) throw AuthFailure("INVALID_INPUT")
    }

    private fun validateCode(value: String) {
        if (!value.matches(Regex("[0-9]{6}"))) throw AuthFailure("INVALID_INPUT")
    }

    private fun request(
        path: String,
        statusExpected: Int,
        body: JSONObject? = null,
        opId: UUID? = null,
        actor: AuthState? = null,
        method: String? = null,
        maxBytes: Int = AuthJson.MAX_BYTES,
        responseMaxBytes: Int = maxBytes,
        allowRestoreRecovery: Boolean = false,
        restoreRecoveryIdentity: DeviceIdentity? = null,
        restoreBootstrapWire: RestoreBootstrapWire? = null,
        operationIdentity: DeviceIdentity? = null,
        operationGate: Any? = null,
        operationFence: (() -> Unit)? = null,
    ): JSONObject {
        requireNetworkConsent()
        val state = store.read()
        val pendingClear = state.datasetClear
        val pending = state.restore
        val session = state.session
        val suppliedBootstrapWire = restoreBootstrapWire
        val isDatasetClearReauth = path == "/v1/auth/reauth" && body?.optJSONObject("operation")?.optString("type") == "DATASET_CLEAR_RECORDS" &&
            pendingClear != null && body.optJSONObject("operation")?.let { target ->
                target.optString("epoch") == pendingClear.epochId.toString() && target.optLong("expectedHeadSeq", -1) == pendingClear.expectedHeadSeq &&
                    target.optJSONArray("ledgerIds")?.toString() == JSONArray(pendingClear.ledgerIdsJson).toString() && target.optString("dependencyStrategy") == "REJECT" &&
                    opId == pendingClear.opId && pendingClear.phase == "AUTHORIZED" && pendingClear.password == body.optString("password")
            } == true && actor?.let { it.session?.user?.id == pendingClear.ownerId && it.session.sessionId == pendingClear.sessionId } == true
        if (pendingClear != null && !isDatasetClearReauth && datasetClearAdmission.get() == null) {
            throw AuthFailure("CLEAR_REAUTH_UNCONFIRMED")
        }
        if (suppliedBootstrapWire != null) validateRestoreBootstrapWire(suppliedBootstrapWire, requireJob = suppliedBootstrapWire.bootstrapJobId != null)
        val isRestoreRecoveryPull = allowRestoreRecovery && restoreRecoveryIdentity != null && restoreBootstrapWire != null &&
            path.startsWith("/v1/sync/pull?") && !path.contains("cursor=") &&
            pending?.owner == restoreRecoveryIdentity.owner && pending.sessionId == restoreRecoveryIdentity.sessionId &&
            pending.jobId == restoreBootstrapWire.restoreJobId && pending.committedEpoch == restoreBootstrapWire.committedEpoch &&
            pending.bootstrapOpId == restoreBootstrapWire.bootstrapOpId && pending.bootstrapJobId == restoreBootstrapWire.bootstrapJobId &&
            session?.user?.id == restoreRecoveryIdentity.owner && session.sessionId == restoreRecoveryIdentity.sessionId &&
            restoreRecoveryIdentity.owner == restoreBootstrapWire.owner && restoreRecoveryIdentity.sessionId == restoreBootstrapWire.sessionId
        val isBoundSnapshotCreate = restoreBootstrapWire != null && path == "/v1/sync/snapshots" && method == null &&
            opId == restoreBootstrapWire.bootstrapOpId && body?.optString("epoch") == restoreBootstrapWire.committedEpoch.toString() &&
            body?.optString("reason") == "NEW_DEVICE" && body?.isNull("knownHeadSeq") == true && restoreBootstrapWire.bootstrapJobId == null
        val isBoundSnapshotStatus = restoreBootstrapWire != null && path == "/v1/sync/snapshots/${restoreBootstrapWire.bootstrapJobId}" &&
            restoreBootstrapWire.bootstrapJobId != null && method == null && body == null
        val isBoundSnapshotContent = restoreBootstrapWire != null &&
            path == "/v1/sync/snapshots/${restoreBootstrapWire.bootstrapJobId}/content" && restoreBootstrapWire.bootstrapJobId != null && method == "GET" && body == null
        val restoreRequestAllowed = pending?.let { operation ->
            path == "/v1/auth/refresh" || isRestoreRecoveryPull ||
                (restoreBootstrapWire != null && actor?.let { it.session?.user?.id == restoreBootstrapWire.owner && it.session.sessionId == restoreBootstrapWire.sessionId } == true &&
                    operation.owner == restoreBootstrapWire.owner && operation.sessionId == restoreBootstrapWire.sessionId &&
                    operation.jobId == restoreBootstrapWire.restoreJobId && operation.committedEpoch == restoreBootstrapWire.committedEpoch &&
                    operation.bootstrapOpId == restoreBootstrapWire.bootstrapOpId && operation.bootstrapJobId == restoreBootstrapWire.bootstrapJobId &&
                    (isBoundSnapshotCreate || isBoundSnapshotStatus || isBoundSnapshotContent)) ||
                (actor?.let { actorState -> actorState.session?.user?.id == operation.owner && actorState.session.sessionId == operation.sessionId } == true &&
                    ((path == "/v1/restores" && method != "GET" && opId == operation.opId && body?.toString() == operation.requestBody && operation.jobId == null) ||
                        (path == "/v1/restores/${operation.jobId}" && operation.jobId != null && method == null && body == null)))
        }
        if (pending != null && restoreRequestAllowed != true &&
            !(path == "/v1/auth/login" || path == "/v1/auth/register")) {
            throw AuthFailure("RESTORE_UNCONFIRMED")
        }
        check(Looper.myLooper() != Looper.getMainLooper()) { "Authentication requests require a background thread" }
        if (!configured) throw AuthFailure("UNCONFIGURED")
        if (opId == UUID(0, 0)) throw AuthFailure("INVALID_INPUT")
        val bytes = body?.toString()?.toByteArray(Charsets.UTF_8)
        if (bytes != null && (bytes.size > maxBytes || opId == null)) throw AuthFailure("INVALID_INPUT")
        val connection =
            try {
                val uri = URI(origin)
                require(
                    uri.scheme == "https" && uri.host != null && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
                        uri.rawPath.orEmpty().isEmpty(),
                )
                URL(origin + path).openConnection() as HttpsURLConnection
            } catch (_: Exception) {
                throw AuthFailure("UNCONFIGURED")
            }
        val timeout = timer.schedule({ connection.disconnect() }, 10, TimeUnit.SECONDS)
        val deadline = SystemClock.elapsedRealtime() + 10_000
        var requestHandle: SyncRequestHandle? = null
        try {
            connection.apply {
                requestMethod = method ?: if (bytes == null) "GET" else "POST"
                connectTimeout = 10_000
                readTimeout = 10_000
                instanceFollowRedirects = false
                useCaches = false
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Cache-Control", "no-store")
                actor?.let {
                    val session = it.session ?: throw AuthFailure("SESSION_EXPIRED")
                    setRequestProperty("Authorization", "Bearer ${session.accessToken}")
                    setRequestProperty("X-Device-Id", it.deviceId.toString())
                }
                // A DELETE has no JSON body but still needs a non-zero receipt key.
                if (opId != null) setRequestProperty("Idempotency-Key", opId.toString())
                if (bytes != null) {
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    doOutput = true
                    // Streaming mode prevents HttpURLConnection from replaying a buffered POST for auth/redirect handling.
                    setFixedLengthStreamingMode(bytes.size)
                }
            }
            if (actor != null) requestHandle = beginSyncRequest(connection, bytes, operationIdentity, operationFence, operationGate)
            else {
                connection.connect()
                bytes?.let { payload -> connection.outputStream.use { it.write(payload) } }
            }
            val status = requestHandle?.let(::syncResponseCode) ?: connection.responseCode
            if (SystemClock.elapsedRealtime() >= deadline) throw AuthFailure("NETWORK", retryable = true)
            val requestId = AuthJson.checked { AuthJson.uuid(connection.getHeaderField("X-Request-Id") ?: error("missing request ID")) }
            AuthJson.checked {
                require(
                    connection
                        .getHeaderField("Cache-Control")
                        .orEmpty()
                        .split(',')
                        .any { it.trim().equals("no-store", true) },
                )
            }
                if (status == 204 && statusExpected == 204) {
                    requestHandle?.let(::endSyncRequest)
                    return JSONObject()
                }
            val contentType =
                connection.contentType
                    .orEmpty()
                    .substringBefore(';')
                    .trim()
            if (!contentType.equals("application/json", true)) throw AuthFailure("INVALID_RESPONSE", requestId, retryable = true)
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(2048)
            (stream ?: throw AuthFailure("INVALID_RESPONSE", requestId, retryable = true)).use {
                while (true) {
                    val remaining = deadline - SystemClock.elapsedRealtime()
                    if (remaining <= 0) throw AuthFailure("NETWORK", retryable = true)
                    connection.readTimeout = remaining.toInt()
                    val count = it.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > responseMaxBytes) throw AuthFailure("INVALID_RESPONSE", requestId, retryable = true)
                    output.write(buffer, 0, count)
                }
            }
            val json = AuthJson.parse(output.toByteArray(), responseMaxBytes)
            if (status !in 200..299) {
                val retry = connection.getHeaderField("Retry-After")
                val seconds =
                    if (retry ==
                        null
                    ) {
                        0
                    } else {
                        retry
                            .takeIf { it.matches(Regex("[0-9]{1,10}")) }
                            ?.toLongOrNull()
                            ?.coerceAtMost(86400)
                            ?.toInt()
                            ?: throw AuthFailure("INVALID_RESPONSE", requestId, retryable = true)
                    }
                throw AuthJson.error(json, requestId, seconds)
            }
            return AuthJson.checked {
                require(status == statusExpected)
                AuthJson.keys(json, setOf("data", "requestId"))
                require(AuthJson.uuid(json, "requestId") == requestId)
                json.getJSONObject("data")
            }
        } catch (
            failure: AuthFailure,
        ) {
            throw failure
        } catch (
            _: Exception,
        ) {
            throw AuthFailure("NETWORK", retryable = true)
        } finally {
            timeout.cancel(false)
            bytes?.fill(0)
            requestHandle?.let(::endSyncRequest)
            connection.disconnect()
        }
    }

    companion object {
        private const val MAX_FEEDBACK_REQUEST_BYTES = 64 * 1024
        private const val MAX_FEEDBACK_RESPONSE_BYTES = 512 * 1024
        private const val MAX_SYNC_REQUEST_BYTES = 1024 * 1024
        private const val MAX_SYNC_RESPONSE_BYTES = 16 * 1024 * 1024
        private val refreshMonitor = Any()
        private val pauseExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "sync-pause-control").apply { isDaemon = true }
        }
        private val timer =
            ScheduledThreadPoolExecutor(1) { runnable -> Thread(runnable, "auth-timeout").apply { isDaemon = true } }
                .apply { removeOnCancelPolicy = true }
    }
}

internal data class SnapshotDownload(val manifest: JSONObject, val bytesWritten: Long, val cursor: String)
