package dev.icos.app.ui

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import dev.icos.app.data.local.EncryptedDatasetStore
import dev.icos.app.data.remote.AuthClient
import dev.icos.app.data.remote.AuthFailure
import dev.icos.app.data.remote.AuthJson
import dev.icos.app.data.remote.AuthState
import dev.icos.app.data.remote.DeviceIdentity
import dev.icos.app.data.remote.DeviceSessions
import org.json.JSONObject
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.Executors

internal data class BackupRow(val id: UUID, val epoch: UUID, val kind: String?, val state: String, val capturedSeq: Long?, val schemaVersion: Int?, val storedBytes: Long?, val plainBytes: Long?, val failureCode: String?, val createdAt: String, val protectedUntil: String?, val deleteAfter: String?)
internal data class BackupPolicy(val enabled: Boolean, val dailyTime: String, val zoneId: String, val dailyRetention: Int, val manualRetention: Int, val version: Long, val updatedAt: String, val lastScheduledLocalDate: String?, val lastAutoCreatedAt: String?, val lastAutoReused: Boolean?, val lastAutoReusedSnapshotId: UUID?, val lastAutoReusedAt: String?)
internal data class BackupSettingsState(val ownerId: UUID? = null, val rows: List<BackupRow> = emptyList(), val nextBefore: UUID? = null, val selected: BackupRow? = null, val policy: BackupPolicy? = null, val busy: Boolean = false, val error: String? = null, val message: String? = null, val retryAvailable: Boolean = false)

internal object BackupWire {
    private val backupKeys = setOf("id", "epoch", "kind", "state", "capturedSeq", "schemaVersion", "manifest", "manifestDigest", "objectDigest", "storedBytes", "plainBytes", "failureCode", "createdAt", "protectedUntil", "deleteAfter")
    private val backupStates = setOf("QUEUED", "CAPTURING", "VERIFYING", "READY", "FAILED", "DELETING", "DELETED")
    fun backup(value: JSONObject): BackupRow {
        require(value.keys().asSequence().toSet() == backupKeys)
        val rawKind = value.get("kind")
        val kind = if (rawKind == JSONObject.NULL) null else (rawKind as? String)?.also { require(it in setOf("MANUAL", "DAILY", "RESTORE_PROTECTION")) } ?: error("kind")
        val status = AuthJson.string(value, "state", 32); require(status in backupStates)
        return BackupRow(AuthJson.uuid(value, "id"), AuthJson.uuid(value, "epoch"), kind, status, nullableLong(value, "capturedSeq"), nullableInt(value, "schemaVersion"), nullableLong(value, "storedBytes"), nullableLong(value, "plainBytes"), nullableText(value, "failureCode", 80), timestamp(value, "createdAt"), nullableTimestamp(value, "protectedUntil"), nullableTimestamp(value, "deleteAfter"))
    }
    fun policy(value: JSONObject): BackupPolicy {
        val required = setOf("enabled", "dailyTime", "zoneId", "dailyRetention", "manualRetention", "version", "updatedAt")
        val schedule = setOf("lastScheduledLocalDate", "lastAutoCreatedAt", "lastAutoReused", "lastAutoReusedSnapshotId", "lastAutoReusedAt")
        val keys = value.keys().asSequence().toSet(); require(keys == required || keys == required + schedule)
        val time = AuthJson.string(value, "dailyTime", 5); require(time.matches(Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]")))
        val zone = AuthJson.string(value, "zoneId", 128); java.time.ZoneId.of(zone)
        val version: Long = AuthJson.integer(value, "version")
        require(version > 0L)
        return BackupPolicy(value.get("enabled") as? Boolean ?: error("enabled"), time, zone, bounded(value, "dailyRetention", 1, 365), bounded(value, "manualRetention", 1, 365), version, timestamp(value, "updatedAt"), optionalDate(value, "lastScheduledLocalDate"), optionalTimestamp(value, "lastAutoCreatedAt"), if (value.has("lastAutoReused")) value.get("lastAutoReused") as? Boolean ?: error("lastAutoReused") else null, if (value.has("lastAutoReusedSnapshotId")) nullableUuid(value, "lastAutoReusedSnapshotId") else null, optionalTimestamp(value, "lastAutoReusedAt"))
    }
    fun list(value: JSONObject): Pair<List<BackupRow>, UUID?> {
        AuthJson.keys(value, setOf("items", "nextBefore")); val array = value.getJSONArray("items"); require(array.length() <= 50)
        val rows = (0 until array.length()).map { backup(array.getJSONObject(it)) }; require(rows.map { it.id }.distinct().size == rows.size)
        return rows to nullableUuid(value, "nextBefore")
    }
    private fun bounded(v: JSONObject, key: String, low: Long, high: Long) = AuthJson.integer(v, key).also { require(it in low..high) }.toInt()
    private fun timestamp(v: JSONObject, key: String) = AuthJson.string(v, key, 64).also { OffsetDateTime.parse(it) }
    private fun nullableTimestamp(v: JSONObject, key: String): String? = if (v.isNull(key)) null else timestamp(v, key)
    private fun optionalTimestamp(v: JSONObject, key: String): String? = if (!v.has(key)) null else nullableTimestamp(v, key)
    private fun optionalDate(v: JSONObject, key: String): String? = if (!v.has(key)) null else nullableText(v, key, 10)?.also { java.time.LocalDate.parse(it) }
    private fun nullableLong(v: JSONObject, key: String): Long? = if (v.isNull(key)) null else AuthJson.integer(v, key).also { require(it >= 0) }
    private fun nullableInt(v: JSONObject, key: String): Int? = nullableLong(v, key)?.also { require(it <= Int.MAX_VALUE) }?.toInt()
    private fun nullableText(v: JSONObject, key: String, max: Int): String? = if (v.isNull(key)) null else AuthJson.string(v, key, max)
    private fun nullableUuid(v: JSONObject, key: String): UUID? = if (v.isNull(key)) null else AuthJson.uuid(v, key)
}

internal data class BackupRequestIdentity(
    val identity: DeviceIdentity,
    val uiSessionGeneration: UUID,
    val opId: UUID,
    val bodyJson: String,
) {
    fun requireCurrent(currentIdentity: DeviceIdentity?, currentUiGeneration: UUID, currentOpId: UUID, currentBodyJson: String) {
        if (identity != currentIdentity || uiSessionGeneration != currentUiGeneration || opId != currentOpId || bodyJson != currentBodyJson) throw AuthFailure("STALE_CREDENTIALS")
    }

    override fun toString() = "BackupRequestIdentity(redacted)"
}

internal class BackupSettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val client = AuthClient(application); private val executor = Executors.newSingleThreadExecutor(); private val main = Handler(Looper.getMainLooper())
    @Volatile private var generation = 0L; @Volatile private var owner: UUID? = null; @Volatile private var closed = false
    @Volatile private var uiSessionGeneration = UUID.randomUUID()
    @Volatile private var observedIdentity: dev.icos.app.data.remote.DeviceIdentity? = null
    @Volatile private var observedSessionGeneration = 0L
    private sealed class Pending { abstract val owner: UUID; abstract val actor: AuthState; abstract val opId: UUID; abstract val body: JSONObject; abstract val requestIdentity: BackupRequestIdentity
        class Create(override val owner: UUID, override val actor: AuthState, uiGeneration: UUID, override val opId: UUID, override val body: JSONObject) : Pending() { override val requestIdentity = BackupRequestIdentity(DeviceSessions.identity(actor), uiGeneration, opId, body.toString()); override fun toString() = "BackupCreatePending(redacted)" }
        class Policy(override val owner: UUID, override val actor: AuthState, uiGeneration: UUID, override val opId: UUID, override val body: JSONObject) : Pending() { override val requestIdentity = BackupRequestIdentity(DeviceSessions.identity(actor), uiGeneration, opId, body.toString()); override fun toString() = "BackupPolicyPending(redacted)" }
        class DeleteReauth(override val owner: UUID, override val actor: AuthState, uiGeneration: UUID, override val opId: UUID, override val body: JSONObject, val backupId: UUID, val deleteOpId: UUID) : Pending() { override val requestIdentity = BackupRequestIdentity(DeviceSessions.identity(actor), uiGeneration, opId, body.toString()); override fun toString() = "BackupDeleteReauthPending(redacted)" }
        class Delete(override val owner: UUID, override val actor: AuthState, uiGeneration: UUID, override val opId: UUID, override val body: JSONObject, val backupId: UUID) : Pending() { override val requestIdentity = BackupRequestIdentity(DeviceSessions.identity(actor), uiGeneration, opId, body.toString()); override fun toString() = "BackupDeletePending(redacted)" }
    }
    @Volatile private var pending: Pending? = null
    var state by mutableStateOf(BackupSettingsState()); private set
    private data class ReadResult(val rows: List<BackupRow>, val cursor: UUID?, val selected: BackupRow?, val policy: BackupPolicy?, val message: String? = null)

    fun sessionChanged(userId: UUID?, sessionIdentity: DeviceIdentity?) {
        val nextIdentity = sessionIdentity?.takeIf { it.owner == userId }
        if (owner != userId || observedIdentity != nextIdentity) {
            owner = userId
            observedIdentity = nextIdentity
            observedSessionGeneration++
            generation++
            uiSessionGeneration = UUID.randomUUID()
            pending = null
            state = BackupSettingsState(ownerId = userId)
        }
    }
    fun load() {
        val who = owner ?: return
        if (state.busy || closed) return
        val gen = generation
        val requestSessionGeneration = observedSessionGeneration
        val requestUiGeneration = uiSessionGeneration
        val selectedAtEnqueue = state.selected
        state = state.copy(busy = true, error = null, message = null)
        executor.execute {
            try {
                val actor = client.snapshot()
                if (actor.session?.user?.id != who) throw AuthFailure("STALE_CREDENTIALS")
                val (rows, cursor) = BackupWire.list(client.listBackups())
                val selected = selectedAtEnqueue?.let { s -> rows.firstOrNull { it.id == s.id } ?: BackupWire.backup(client.backupDetail(s.id)) }
                val policy = BackupWire.policy(client.backupPolicy())
                requireCurrent(who, actor, expectedGeneration = gen, expectedSessionGeneration = requestSessionGeneration, expectedUiGeneration = requestUiGeneration)
                val result = ReadResult(rows, cursor, selected, policy)
                main.post { if (current(who, gen)) state = BackupSettingsState(who, result.rows, result.cursor, result.selected, result.policy) }
            } catch (e: Exception) { report(who, gen, e) }
        }
    }
    fun refresh() = load()
    fun select(id: UUID) {
        val who = owner ?: return
        if (state.busy || closed) return
        val gen = generation
        val requestSessionGeneration = observedSessionGeneration
        val requestUiGeneration = uiSessionGeneration
        state = state.copy(busy = true, error = null)
        executor.execute { try {
            val actor = client.snapshot()
            if (actor.session?.user?.id != who) throw AuthFailure("STALE_CREDENTIALS")
            val selected = BackupWire.backup(client.backupDetail(id))
            requireCurrent(who, actor, expectedGeneration = gen, expectedSessionGeneration = requestSessionGeneration, expectedUiGeneration = requestUiGeneration)
            main.post { if (current(who, gen)) state = state.copy(selected = selected, busy = false, error = null) }
        } catch (e: Exception) { report(who, gen, e) } }
    }
    fun loadMore() {
        val who = owner ?: return
        val cursor = state.nextBefore ?: return
        if (state.busy || closed) return
        val gen = generation
        val requestSessionGeneration = observedSessionGeneration
        val requestUiGeneration = uiSessionGeneration
        val rowsAtEnqueue = state.rows
        state = state.copy(busy = true, error = null)
        executor.execute { try {
            val actor = client.snapshot()
            if (actor.session?.user?.id != who) throw AuthFailure("STALE_CREDENTIALS")
            val (more, next) = BackupWire.list(client.listBackups(before = cursor))
            requireCurrent(who, actor, expectedGeneration = gen, expectedSessionGeneration = requestSessionGeneration, expectedUiGeneration = requestUiGeneration)
            main.post { if (current(who, gen)) state = state.copy(rows = (rowsAtEnqueue + more).distinctBy { it.id }, nextBefore = next, busy = false) }
        } catch (e: Exception) { report(who, gen, e) } }
    }
    fun createManualBackup() {
        val who = owner ?: return; if (state.busy || closed) return
        pending?.let { if (it is Pending.Create && it.owner == who && it.requestIdentity.uiSessionGeneration == uiSessionGeneration) retryOriginal() else state = state.copy(error = "请先完成当前备份操作的原请求重试"); return }
        val gen = generation; val requestSessionGeneration = observedSessionGeneration; val requestUiGeneration = uiSessionGeneration
        state = state.copy(busy = true, error = null, message = null)
        executor.execute { try {
            val actor = client.snapshot()
            if (actor.session?.user?.id != who) throw AuthFailure("STALE_CREDENTIALS")
            val epoch = EncryptedDatasetStore.activeAccountEpoch(getApplication(), who) ?: throw AuthFailure("DATASET_NOT_READY")
            val accountDirectory = EncryptedDatasetStore.accountDirectory(getApplication(), who, epoch)
            val datasetFile = java.io.File(accountDirectory, "dataset.db")
            val envelopeFile = java.io.File(accountDirectory, "passphrase.envelope")
            if (!datasetFile.isFile || datasetFile.length() <= 0L || !envelopeFile.isFile) throw AuthFailure("DATASET_NOT_READY")
            val db = EncryptedDatasetStore.openAccount(getApplication(), who, epoch); val identity = try { db.identity().read() ?: throw AuthFailure("DATASET_NOT_READY") } finally { db.close() }
            if (owner != who || generation != gen || observedSessionGeneration != requestSessionGeneration || uiSessionGeneration != requestUiGeneration) throw AuthFailure("STALE_CREDENTIALS")
            require(identity.ownerId == who.toString() && identity.epochId == epoch.toString() && identity.lastAppliedSeq >= 0)
            requireCurrent(who, actor, expectedGeneration = gen, expectedSessionGeneration = requestSessionGeneration, expectedUiGeneration = requestUiGeneration)
            val body = JSONObject().put("expectedEpoch", epoch.toString()).put("minAcceptedSeq", identity.lastAppliedSeq).put("reason", "MANUAL")
            val p = Pending.Create(who, actor, requestUiGeneration, UUID.randomUUID(), body); pending = p; replayWorker(p, gen, requestSessionGeneration)
        } catch (e: Exception) { report(who, gen, e) } }
    }
    fun updatePolicy(enabled: Boolean, dailyTime: String, zone: String, dailyRetention: Int, manualRetention: Int) {
        val who = owner ?: return; if (state.busy || closed) return
        val body = BackupPolicyInput.create(enabled, dailyTime, zone, dailyRetention, manualRetention) ?: run { state = state.copy(error = "请检查每日时间、时区和 1–365 天保留期限"); return }
        val gen = generation; val requestSessionGeneration = observedSessionGeneration; val requestUiGeneration = uiSessionGeneration
        state = state.copy(busy = true, error = null, message = null)
        executor.execute { try {
            val actor = client.snapshot()
            if (actor.session?.user?.id != who) throw AuthFailure("STALE_CREDENTIALS")
            if (pending != null) throw AuthFailure("BACKUP_OPERATION_PENDING")
            if (owner != who || generation != gen || observedSessionGeneration != requestSessionGeneration || uiSessionGeneration != requestUiGeneration) throw AuthFailure("STALE_CREDENTIALS")
            requireCurrent(who, actor, expectedGeneration = gen, expectedSessionGeneration = requestSessionGeneration, expectedUiGeneration = requestUiGeneration)
            val created = Pending.Policy(who, actor, requestUiGeneration, UUID.randomUUID(), body)
            pending = created
            replayWorker(created, gen, requestSessionGeneration)
        } catch (e: Exception) { report(who, gen, e) } }
    }
    fun deleteBackup(id: UUID, password: String) {
        val who = owner ?: return; if (state.busy || closed || password.isEmpty()) return
        val gen = generation; val requestSessionGeneration = observedSessionGeneration; val requestUiGeneration = uiSessionGeneration
        state = state.copy(busy = true, error = null, message = null)
        executor.execute { try {
            val actor = client.snapshot()
            if (actor.session?.user?.id != who) throw AuthFailure("STALE_CREDENTIALS")
            if (pending != null) throw AuthFailure("BACKUP_OPERATION_PENDING")
            requireCurrent(who, actor, expectedGeneration = gen, expectedSessionGeneration = requestSessionGeneration, expectedUiGeneration = requestUiGeneration)
            val body = JSONObject().put("password", password).put("operation", JSONObject().put("type", "BACKUP_DELETE").put("backupId", id.toString()))
            val deletion = Pending.DeleteReauth(who, actor, requestUiGeneration, UUID.randomUUID(), body, id, UUID.randomUUID())
            pending = deletion
            replayWorker(deletion, gen, requestSessionGeneration)
        } catch (e: Exception) { report(who, gen, e) } }
    }
    fun retryOriginal() {
        val p = pending ?: return
        if (state.busy || closed) return
        val gen = generation
        state = state.copy(busy = true, error = null, message = null)
        executor.execute { try {
            val currentActor = client.snapshot()
            val currentIdentity = runCatching { DeviceSessions.identity(currentActor) }.getOrNull()
            val requestSessionGeneration = observedSessionGeneration
            p.requestIdentity.requireCurrent(currentIdentity, p.requestIdentity.uiSessionGeneration, p.opId, p.body.toString())
            if (p.owner != owner || currentActor.session?.user?.id != p.owner || observedIdentity != currentIdentity || requestSessionGeneration != observedSessionGeneration || generation != gen) throw AuthFailure("STALE_CREDENTIALS")
            replayWorker(p, gen, requestSessionGeneration)
        } catch (e: Exception) {
            if (e is AuthFailure && e.code == "STALE_CREDENTIALS") pending = null
            report(p.owner, gen, e)
        } }
    }
    fun dismissError() { state = state.copy(error = null) }
    private fun replayWorker(p: Pending, gen: Long, expectedSessionGeneration: Long) {
        try {
            requireCurrent(p.owner, p.actor, p.requestIdentity, gen, expectedSessionGeneration, p.requestIdentity.uiSessionGeneration)
            when (p) {
                is Pending.Create -> {
                    val response = client.createManualBackup(UUID.fromString(p.body.getString("expectedEpoch")), p.body.getLong("minAcceptedSeq"), p.opId)
                    AuthJson.keys(response, setOf("backupId", "jobId", "state"), setOf("acceptedHeadSeq"))
                    val id = AuthJson.uuid(response, "backupId")
                    require(AuthJson.uuid(response, "jobId") == id && response.getString("state") in createStates)
                    requireCurrent(p.owner, p.actor, p.requestIdentity, gen, expectedSessionGeneration, p.requestIdentity.uiSessionGeneration)
                    val base = readAfterMutation()
                    val detail = BackupWire.backup(client.backupDetail(id))
                    require(detail.id == id)
                    requireCurrent(p.owner, p.actor, p.requestIdentity, gen, expectedSessionGeneration, p.requestIdentity.uiSessionGeneration)
                    finish(p, gen, base.copy(selected = detail, message = "手动备份任务已确认；当前状态：${detail.state}"))
                }
                is Pending.Policy -> {
                    val policy = BackupWire.policy(client.updateBackupPolicy(p.body, p.opId))
                    requireCurrent(p.owner, p.actor, p.requestIdentity, gen, expectedSessionGeneration, p.requestIdentity.uiSessionGeneration)
                    finish(p, gen, readAfterMutation().copy(policy = policy, message = "本人备份策略已保存"))
                }
                is Pending.DeleteReauth -> {
                    val response = client.reauthorizeBackupDelete(p.backupId, p.body.getString("password"), p.opId)
                    AuthJson.keys(response, setOf("actionToken", "expiresAt"))
                    val expiry = OffsetDateTime.parse(AuthJson.string(response, "expiresAt", 64))
                    require(expiry.isAfter(OffsetDateTime.now()) && expiry.isBefore(OffsetDateTime.now().plusMinutes(6)))
                    requireCurrent(p.owner, p.actor, p.requestIdentity, gen, expectedSessionGeneration, p.requestIdentity.uiSessionGeneration)
                    val deleteBody = JSONObject().put("actionToken", AuthJson.string(response, "actionToken", 4096))
                    val deletion = Pending.Delete(p.owner, p.actor, p.requestIdentity.uiSessionGeneration, p.deleteOpId, deleteBody, p.backupId)
                    pending = deletion
                    replayWorker(deletion, gen, expectedSessionGeneration)
                }
                is Pending.Delete -> {
                    val response = client.deleteBackup(p.backupId, p.body.getString("actionToken"), p.opId)
                    AuthJson.keys(response, setOf("backupId", "state"), setOf("deleteAfterHours"))
                    require(AuthJson.uuid(response, "backupId") == p.backupId)
                    val status = AuthJson.string(response, "state", 16)
                    require(status == "DELETING" || status == "DELETED")
                    if (status == "DELETING") require(AuthJson.integer(response, "deleteAfterHours") == 24L)
                    requireCurrent(p.owner, p.actor, p.requestIdentity, gen, expectedSessionGeneration, p.requestIdentity.uiSessionGeneration)
                    val message = if (status == "DELETING") "备份进入 24 小时延迟清理；状态以刷新结果为准" else "备份已删除"
                    finish(p, gen, readAfterMutation().copy(selected = null, message = message))
                }
            }
        } catch (e: Exception) {
            if (e is AuthFailure && e.code == "STALE_CREDENTIALS") pending = null
            report(p.owner, gen, e)
        }
    }

    private fun readAfterMutation(): ReadResult {
        val (rows, cursor) = BackupWire.list(client.listBackups())
        return ReadResult(rows, cursor, null, BackupWire.policy(client.backupPolicy()))
    }
    private fun requireCurrent(
        who: UUID,
        actor: AuthState,
        request: BackupRequestIdentity? = null,
        expectedGeneration: Long = generation,
        expectedSessionGeneration: Long = observedSessionGeneration,
        expectedUiGeneration: UUID = uiSessionGeneration,
    ) {
        val snapshot = client.snapshot()
        val currentIdentity = runCatching { DeviceSessions.identity(snapshot) }.getOrNull()
        if (owner != who || snapshot.session?.user?.id != who || currentIdentity != observedIdentity || currentIdentity != DeviceSessions.identity(actor)) throw AuthFailure("STALE_CREDENTIALS")
        request?.requireCurrent(currentIdentity, expectedUiGeneration, request.opId, request.bodyJson)
        if (generation != expectedGeneration || observedSessionGeneration != expectedSessionGeneration || uiSessionGeneration != expectedUiGeneration) throw AuthFailure("STALE_CREDENTIALS")
    }
    private fun current(who: UUID, gen: Long) = !closed && owner == who && generation == gen
    private fun finish(p: Pending, gen: Long, result: ReadResult) { main.post { if (current(p.owner, gen) && pending?.requestIdentity == p.requestIdentity) { pending = null; state = BackupSettingsState(p.owner, result.rows, result.cursor, result.selected, result.policy, message = result.message) } } }
    private fun report(who: UUID, gen: Long, e: Exception) { val failure = e as? AuthFailure; main.post { if (current(who, gen)) { if (failure?.retryable == false) pending = null; state = state.copy(busy = false, error = failure?.userMessage ?: "备份请求未完成", retryAvailable = pending?.owner == who) } } }
    fun close() { if (!closed) { generation++; closed = true; pending = null; executor.shutdownNow(); state = BackupSettingsState() } }
    override fun onCleared() = close()
    private companion object {
        val createStates = setOf("QUEUED", "CAPTURING", "VERIFYING", "READY", "FAILED", "DELETING", "DELETED")
    }
}

internal object BackupPolicyInput {
    fun create(enabled: Boolean, dailyTime: String, zoneId: String, dailyRetention: Int, manualRetention: Int): JSONObject? {
        if (!dailyTime.matches(Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]")) || zoneId.isBlank() || zoneId.length > 128 || zoneId.any(Character::isISOControl) || dailyRetention !in 1..365 || manualRetention !in 1..365 || runCatching { java.time.ZoneId.of(zoneId) }.isFailure) return null
        return JSONObject().put("enabled", enabled).put("dailyTime", dailyTime).put("zoneId", zoneId).put("dailyRetention", dailyRetention).put("manualRetention", manualRetention)
    }
}
