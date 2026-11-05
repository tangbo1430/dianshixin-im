package dev.icos.app.ui

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import dev.icos.app.data.remote.AuthClient
import dev.icos.app.data.remote.AuthFailure
import dev.icos.app.data.remote.AuthJson
import dev.icos.app.data.remote.AuthState
import dev.icos.app.data.remote.DeviceIdentity
import dev.icos.app.data.remote.DeviceSessions
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors

internal data class FeedbackRow(val id: UUID, val type: String, val status: String, val createdAt: String, val updatedAt: String)
internal data class FeedbackReply(val id: UUID, val message: String, val createdAt: String)
internal data class FeedbackDetail(
    val row: FeedbackRow,
    val message: String,
    val appVersion: String,
    val diagnosticConsent: Boolean,
    val replies: List<FeedbackReply>,
)
internal data class FeedbackState(
    val ownerId: UUID? = null,
    val rows: List<FeedbackRow> = emptyList(),
    val cursor: String? = null,
    val hasMore: Boolean = false,
    val selected: FeedbackDetail? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val retryAvailable: Boolean = false,
)

internal class FeedbackViewModel(application: Application) : AndroidViewModel(application) {
    private val client = AuthClient(application)
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var cachedIdentity: DeviceIdentity? = null
    @Volatile private var owner: UUID? = null
    @Volatile private var identity: DeviceIdentity? = null
    @Volatile private var generation = 0L
    @Volatile private var closed = false
    private data class PendingCreate(val owner: UUID, val identity: DeviceIdentity, val opId: UUID, val body: JSONObject) {
        override fun toString() = "FeedbackCreatePending(redacted)"
    }
    @Volatile private var pending: PendingCreate? = null
    var state by mutableStateOf(FeedbackState())
        private set

    fun sessionChanged(userId: UUID?, expectedIdentity: DeviceIdentity?) {
        if (closed) return
        val nextIdentity = expectedIdentity?.takeIf { it.owner == userId }
        if (owner == userId && cachedIdentity == nextIdentity) return
        val gen = ++generation
        owner = userId
        cachedIdentity = nextIdentity
        identity = nextIdentity
        pending = null
        state = FeedbackState(ownerId = userId)
        if (userId == null || nextIdentity == null) return
        executor.execute {
            val valid = runCatching { requireCurrent(userId, nextIdentity); true }.getOrDefault(false)
            main.post {
                if (!closed && generation == gen && owner == userId && identity == nextIdentity) {
                    if (!valid) state = state.copy(error = "账号会话尚未确认，请重新登录后使用反馈。")
                    else refresh()
                }
            }
        }
    }

    fun refresh() {
        val who = owner ?: return
        val expected = identity ?: return
        if (state.busy || closed) return
        val gen = generation
        state = state.copy(busy = true, error = null, message = null)
        executor.execute {
            try {
                requireCurrent(who, expected)
                val parsed = parseList(client.listFeedback(null))
                requireCurrent(who, expected)
                main.post {
                    if (current(who, expected, gen)) state = state.copy(rows = parsed.first, cursor = parsed.second.first,
                        hasMore = parsed.second.second, selected = null, busy = false, error = null)
                }
            } catch (e: Exception) { report(who, expected, gen, e) }
        }
    }

    fun loadMore() {
        val who = owner ?: return
        val expected = identity ?: return
        val cursor = state.cursor ?: return
        if (!state.hasMore || state.busy || closed) return
        val gen = generation
        state = state.copy(busy = true, error = null)
        executor.execute {
            try {
                requireCurrent(who, expected)
                val parsed = parseList(client.listFeedback(cursor))
                requireCurrent(who, expected)
                main.post {
                    if (current(who, expected, gen)) state = state.copy(rows = (state.rows + parsed.first).distinctBy { it.id },
                        cursor = parsed.second.first, hasMore = parsed.second.second, busy = false)
                }
            } catch (e: Exception) { report(who, expected, gen, e) }
        }
    }

    fun open(id: UUID) {
        val who = owner ?: return
        val expected = identity ?: return
        if (state.busy || closed) return
        val gen = generation
        state = state.copy(busy = true, error = null)
        executor.execute {
            try {
                requireCurrent(who, expected)
                val detail = parseDetail(id, client.feedbackDetail(id))
                requireCurrent(who, expected)
                main.post { if (current(who, expected, gen)) state = state.copy(selected = detail, busy = false) }
            } catch (e: Exception) { report(who, expected, gen, e) }
        }
    }

    fun closeDetail() { state = state.copy(selected = null) }

    fun submit(type: String, message: String, diagnosticConsent: Boolean) {
        val who = owner ?: return
        val expected = identity ?: return
        if (state.busy || closed || pending != null) return
        if (type !in setOf("BUG", "SUGGESTION", "OTHER") || message.trim().isEmpty() || message.codePointCount(0, message.length) > 10000 || message.any { it == '\u0000' }) {
            state = state.copy(error = "请选择反馈类型并输入不超过 10000 字的内容。")
            return
        }
        val body = JSONObject().put("type", type).put("message", message.trim())
            .put("appVersion", dev.icos.app.BuildConfig.VERSION_NAME.take(80))
            .put("diagnosticConsent", diagnosticConsent).put("attachmentIds", JSONArray())
        val request = PendingCreate(who, expected, UUID.randomUUID(), body)
        pending = request
        send(request)
    }

    fun retryOriginal() {
        val request = pending ?: return
        val who = owner ?: return
        val expected = identity ?: return
        if (request.owner != who || request.identity != expected) {
            pending = null
            state = state.copy(error = "账号会话已变化，未确认的反馈请求已清除；请刷新后重新提交。", retryAvailable = false)
            return
        }
        send(request)
    }

    private fun send(request: PendingCreate) {
        if (closed || state.busy || pending !== request) return
        val gen = generation
        state = state.copy(busy = true, error = null, message = null, retryAvailable = false)
        executor.execute {
            try {
                requireCurrent(request.owner, request.identity)
                val response = client.createFeedback(request.body, request.opId)
                AuthJson.keys(response, setOf("feedbackId"))
                val id = AuthJson.uuid(response, "feedbackId")
                requireCurrent(request.owner, request.identity)
                val detail = parseDetail(id, client.feedbackDetail(id))
                requireCurrent(request.owner, request.identity)
                val parsed = parseList(client.listFeedback(null))
                requireCurrent(request.owner, request.identity)
                main.post {
                    if (current(request.owner, request.identity, gen) && pending === request) {
                        pending = null
                        state = state.copy(rows = parsed.first, cursor = parsed.second.first, hasMore = parsed.second.second,
                            selected = detail, busy = false, retryAvailable = false, message = "反馈已提交并从服务器确认。")
                    }
                }
            } catch (e: Exception) { report(request.owner, request.identity, gen, e, keepPending = true) }
        }
    }

    private fun parseList(value: JSONObject): Pair<List<FeedbackRow>, Pair<String?, Boolean>> {
        AuthJson.keys(value, setOf("items", "nextCursor", "hasMore"))
        val array = value.getJSONArray("items")
        require(array.length() <= 100)
        val rows = (0 until array.length()).map { index ->
            val row = array.getJSONObject(index)
            AuthJson.keys(row, setOf("id", "type", "status", "createdAt", "updatedAt"))
            FeedbackRow(AuthJson.uuid(row, "id"), AuthJson.string(row, "type", 16).also { require(it in feedbackTypes) },
                AuthJson.string(row, "status", 16).also { require(it in feedbackStatuses) }, checkedTimestamp(AuthJson.string(row, "createdAt", 24)), checkedTimestamp(AuthJson.string(row, "updatedAt", 24)))
        }
        require(rows.map { it.id }.distinct().size == rows.size)
        val hasMore = value.get("hasMore") as? Boolean ?: error("hasMore")
        val cursor = if (value.isNull("nextCursor")) null else AuthJson.string(value, "nextCursor", 256)
        require((cursor != null) == hasMore)
        return rows to (cursor to hasMore)
    }

    private fun parseDetail(id: UUID, value: JSONObject): FeedbackDetail {
        AuthJson.keys(value, setOf("id", "type", "message", "appVersion", "diagnosticConsent", "status", "createdAt", "updatedAt", "replies"))
        require(AuthJson.uuid(value, "id") == id)
        val type = AuthJson.string(value, "type", 16).also { require(it in feedbackTypes) }
        val row = FeedbackRow(id, type, AuthJson.string(value, "status", 16).also { require(it in feedbackStatuses) }, checkedTimestamp(AuthJson.string(value, "createdAt", 24)), checkedTimestamp(AuthJson.string(value, "updatedAt", 24)))
        val array = value.getJSONArray("replies")
        require(array.length() <= 1000)
        val replies = (0 until array.length()).map { index ->
            val reply = array.getJSONObject(index)
            AuthJson.keys(reply, setOf("id", "message", "createdAt"))
            FeedbackReply(AuthJson.uuid(reply, "id"), feedbackText(reply, "message", 10000), checkedTimestamp(AuthJson.string(reply, "createdAt", 24)))
        }
        return FeedbackDetail(row, feedbackText(value, "message", 10000), AuthJson.string(value, "appVersion", 80),
            value.get("diagnosticConsent") as? Boolean ?: error("diagnosticConsent"), replies)
    }

    private fun requireCurrent(who: UUID, expected: DeviceIdentity) {
        val snapshot: AuthState = client.snapshot()
        if (owner != who || snapshot.session?.user?.id != who || DeviceSessions.identity(snapshot) != expected) throw AuthFailure("STALE_CREDENTIALS")
    }

    private fun current(who: UUID, expected: DeviceIdentity, gen: Long) = !closed && generation == gen && owner == who && identity == expected

    private fun feedbackText(value: JSONObject, key: String, max: Int): String {
        val text = value.get(key) as? String ?: error("$key")
        require(text.codePointCount(0, text.length) in 1..max && text.none { it.code == 0 })
        return text
    }

    private fun checkedTimestamp(value: String): String {
        AuthJson.timestamp(value)
        return value
    }

    private fun report(who: UUID, expected: DeviceIdentity, gen: Long, failure: Exception, keepPending: Boolean = false) {
        val authFailure = failure as? AuthFailure
        main.post {
            if (current(who, expected, gen)) {
                if (!keepPending || authFailure?.code in setOf("STALE_CREDENTIALS", "SESSION_REVOKED", "IDEMPOTENCY_RECEIPT_EXPIRED", "IDEMPOTENCY_KEY_REUSED") || authFailure?.retryable == false && authFailure?.code != "SESSION_EXPIRED") pending = null
                state = state.copy(busy = false, error = authFailure?.userMessage ?: "反馈请求未完成", retryAvailable = pending?.owner == who)
            }
        }
    }

    fun close() {
        if (closed) return
        closed = true
        generation++
        pending = null
        executor.shutdownNow()
    }

    override fun onCleared() = close()

    private companion object {
        val feedbackTypes = setOf("BUG", "SUGGESTION", "OTHER")
        val feedbackStatuses = setOf("OPEN", "IN_PROGRESS", "RESOLVED", "CLOSED")
    }
}
