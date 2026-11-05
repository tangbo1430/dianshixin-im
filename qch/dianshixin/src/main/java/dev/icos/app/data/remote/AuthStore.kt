package dev.icos.app.data.remote

import android.content.Context
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.system.Os
import android.system.OsConstants
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** One atomic envelope contains the identity, credential generation and original pending refresh. */
internal class AuthStore(
    context: Context,
    private val origin: String,
) {
    private val directory = File(context.applicationContext.noBackupFilesDir, "auth")
    private val file = File(directory, "state.bin")
    private val pendingFile = File(directory, "state.new")

    fun read(): AuthState = locked { readLocked() }

    fun replace(
        snapshot: AuthState,
        session: AuthSession?,
    ): AuthState = update(snapshot) { it.replace(session) }

    fun beginReset(snapshot: AuthState, operation: PendingReset): AuthState = update(snapshot) { it.beginReset(operation) }

    fun finishReset(snapshot: AuthState): AuthState = update(snapshot) { it.finishReset(snapshot) }

    fun beginRefresh(snapshot: AuthState): AuthState = update(snapshot) { it.beginRefresh() }

    fun finishRefresh(
        snapshot: AuthState,
        operation: UUID,
        session: AuthSession,
    ): AuthState =
        update(snapshot) {
            if (!it.acceptsRefresh(snapshot, operation)) throw AuthFailure("STALE_CREDENTIALS")
            it.replace(session)
        }

    fun beginRestore(snapshot: AuthState, operation: PendingRestore): AuthState = update(snapshot) { it.beginRestore(operation) }

    fun updateRestore(snapshot: AuthState, operation: PendingRestore): AuthState = update(snapshot) { it.updateRestore(operation) }

    fun bindRestoreBootstrap(snapshot: AuthState, operation: PendingRestore, wire: RestoreBootstrapWire): AuthState = update(snapshot) {
        it.bindRestoreBootstrap(operation, wire)
    }

    fun finishRestore(snapshot: AuthState, operation: PendingRestore): AuthState = update(snapshot) { it.finishRestore(operation) }

    fun beginDatasetClear(snapshot: AuthState, operation: PendingDatasetClear): AuthState = update(snapshot) { it.beginDatasetClear(operation) }

    fun updateDatasetClear(snapshot: AuthState, operation: PendingDatasetClear): AuthState = update(snapshot) { it.updateDatasetClear(snapshot, operation) }

    fun authorizeDatasetClear(snapshot: AuthState, operation: PendingDatasetClear): AuthState = update(snapshot) { it.authorizeDatasetClear(snapshot, operation) }

    fun resumeDatasetClearReauth(snapshot: AuthState, password: String): AuthState = update(snapshot) { current ->
        val pending = current.datasetClear ?: throw AuthFailure("CLEAR_REAUTH_UNCONFIRMED")
        val captured = snapshot.datasetClear
        if (!current.matches(snapshot) || current.session?.user?.id != pending.ownerId || current.session.sessionId != pending.sessionId ||
            captured?.opId != pending.opId || captured.phase != "REAUTH" || pending.phase != "REAUTH" || captured.password != pending.password ||
            password.codePointCount(0, password.length) !in 12..128) throw AuthFailure("STALE_CREDENTIALS")
        current.updateDatasetClear(snapshot, PendingDatasetClear(pending.ownerId, pending.epochId, pending.sessionId, pending.opId,
            pending.groupId, pending.ledgerIdsJson, pending.expectedHeadSeq, "REAUTH", password, null, null, null, null))
    }

    fun discardDatasetClear(snapshot: AuthState, opId: UUID): AuthState = update(snapshot) { current ->
        val pending = current.datasetClear ?: throw AuthFailure("CLEAR_REAUTH_UNCONFIRMED")
        if (!current.matches(snapshot) || pending.opId != opId || pending.phase != "REAUTH" ||
            snapshot.datasetClear?.let { it.opId == pending.opId && it.phase == "REAUTH" && it.password == pending.password } != true) {
            throw AuthFailure("STALE_CREDENTIALS")
        }
        AuthState(current.deviceId, current.generation, current.session, current.pending, current.reset, current.restore, current.archivedRestores, null)
    }

    fun finishDatasetClear(snapshot: AuthState, opId: UUID): AuthState = update(snapshot) { it.finishDatasetClear(snapshot, opId) }

    private fun update(
        snapshot: AuthState,
        change: (AuthState) -> AuthState,
    ): AuthState =
        locked {
            val current = readLocked()
            if (!current.matches(snapshot)) throw AuthFailure("STALE_CREDENTIALS")
            val next = change(current)
            if (next !== current) writeLocked(next, existingKey())
            next
        }

    private fun readLocked(): AuthState {
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!file.exists()) {
            // A surviving key or staging file represents interrupted creation, not permission to overwrite it.
            check(!pendingFile.exists() && !keys.containsAlias(ALIAS))
            val key =
                KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
                    init(
                        KeyGenParameterSpec
                            .Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                            .setKeySize(256)
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .setRandomizedEncryptionRequired(true)
                            .build(),
                    )
                    generateKey()
                }
            return AuthState(UUID.randomUUID()).also { writeLocked(it, key) }
        }
        check(file.isFile && file.length() in 74..(MAX_STORAGE_BYTES + 100).toLong())
        val raw = file.readBytes()
        check(raw.size in 74..MAX_STORAGE_BYTES + 100 && raw[0] == 1.toByte())
        val key = existingKey()
        val deviceBytes = decrypt(raw.copyOfRange(1, 45), key, "icos.auth.device.v1")
        check(deviceBytes.size == 16)
        val device = ByteBuffer.wrap(deviceBytes).let { UUID(it.long, it.long) }
        check(device != UUID(0, 0))
        val clear = decrypt(raw.copyOfRange(45, raw.size), key, "icos.auth.credentials.v1:$device")
        try {
            val json = AuthJson.parse(clear, MAX_STORAGE_BYTES)
            AuthJson.keys(json, setOf("version", "deviceId", "generation", "origin", "session", "pendingRefresh"), setOf("pendingReset", "pendingRestore", "archivedRestores", "pendingDatasetClear"))
            check(AuthJson.integer(json, "version") == 1L && AuthJson.uuid(json, "deviceId") == device)
            val session = if (json.isNull("session")) null else AuthJson.session(json.getJSONObject("session"), device)
            val pending =
                if (json.isNull("pendingRefresh")) {
                    null
                } else {
                    val value = json.getJSONObject("pendingRefresh")
                    AuthJson.keys(value, setOf("opId", "originalRefresh"))
                    val token = AuthJson.string(value, "originalRefresh", 80)
                    check(session != null && token == session.refreshToken)
                    PendingRefresh(AuthJson.uuid(value, "opId"), token)
                }
            val reset = if (!json.has("pendingReset") || json.isNull("pendingReset")) null else {
                val value = json.getJSONObject("pendingReset")
                AuthJson.keys(value, setOf("opId", "challengeId", "email"))
                check(session == null && pending == null)
                PendingReset(AuthJson.uuid(value, "opId"), AuthJson.uuid(value, "challengeId"), AuthJson.string(value, "email", AuthJson.MAX_BYTES))
            }
            val restore = if (!json.has("pendingRestore") || json.isNull("pendingRestore")) null else decodeRestore(json.getJSONObject("pendingRestore"))
            val archived = if (!json.has("archivedRestores") || json.isNull("archivedRestores")) emptyList() else {
                val array = json.getJSONArray("archivedRestores")
                check(array.length() <= 4)
                (0 until array.length()).map { decodeRestore(array.getJSONObject(it)) }
            }
            check((restore == null || reset == null) && (restore == null || restore.owner == session?.user?.id && restore.sessionId == session.sessionId))
            check(archived.all { archivedRestore -> archivedRestore.owner != session?.user?.id || archivedRestore.sessionId != session.sessionId })
            val clearOperation = if (!json.has("pendingDatasetClear") || json.isNull("pendingDatasetClear")) null else {
                val value = json.getJSONObject("pendingDatasetClear")
                AuthJson.keys(value, setOf("ownerId", "epochId", "sessionId", "opId", "groupId", "ledgerIds", "expectedHeadSeq", "phase"), setOf("password", "actionToken", "frozenGroup", "ackSeq", "requiredCapturedSeq"))
                val owner = AuthJson.uuid(value, "ownerId")
                val epoch = AuthJson.uuid(value, "epochId")
                val sessionId = AuthJson.uuid(value, "sessionId")
                val opId = AuthJson.uuid(value, "opId")
                val groupId = AuthJson.uuid(value, "groupId")
                check(listOf(owner, epoch, sessionId, opId, groupId).none { it == UUID(0, 0) })
                val ids = value.getJSONArray("ledgerIds")
                check(ids.length() in 1..100)
                val ledgerIds = (0 until ids.length()).map { AuthJson.uuid(ids.getString(it)) }
                check(ledgerIds.none { it == UUID(0, 0) } && ledgerIds.distinct().size == ledgerIds.size && ledgerIds == ledgerIds.sortedBy(UUID::toString))
                val head = AuthJson.integer(value, "expectedHeadSeq").also { check(it >= 0) }
                val phase = AuthJson.string(value, "phase", 16).also { check(it in setOf("REAUTH", "AUTHORIZED", "READY", "ACKED")) }
                val password = if (!value.has("password") || value.isNull("password")) null else AuthJson.string(value, "password", 128, 12)
                val token = if (!value.has("actionToken") || value.isNull("actionToken")) null else AuthJson.string(value, "actionToken", 4096)
                val frozen = if (!value.has("frozenGroup") || value.isNull("frozenGroup")) null else AuthJson.string(value, "frozenGroup", 65536)
                if (frozen != null) {
                    val wire = AuthJson.parse(frozen.toByteArray(Charsets.UTF_8), 65536)
                    AuthJson.keys(wire, setOf("opId", "groupId", "aggregateType", "aggregateId", "baseVersions", "commandType", "schemaVersion", "payload"))
                    val payload = wire.getJSONObject("payload")
                    AuthJson.keys(payload, setOf("ledgerIds", "dependencyStrategy", "confirmationToken"))
                    check(wire.getString("opId") == opId.toString() && wire.getString("groupId") == groupId.toString() &&
                        wire.getString("aggregateType") == "dataset" && wire.getString("aggregateId") == owner.toString() &&
                        wire.getJSONObject("baseVersions").length() == 0 && wire.getString("commandType") == "dataset.clearRecords" &&
                        wire.getInt("schemaVersion") == 1 && payload.getJSONArray("ledgerIds").toString() == JSONArray(ledgerIds.map(UUID::toString)).toString() &&
                        payload.getString("dependencyStrategy") == "REJECT" && payload.getString("confirmationToken") == token)
                }
                val ackSeq = if (!value.has("ackSeq") || value.isNull("ackSeq")) null else AuthJson.integer(value, "ackSeq")
                val requiredSeq = if (!value.has("requiredCapturedSeq") || value.isNull("requiredCapturedSeq")) null else AuthJson.integer(value, "requiredCapturedSeq")
                check(session?.user?.id == owner && session.sessionId == sessionId &&
                    restore?.let { it.owner == owner && it.sessionId == sessionId } != true &&
                    archived.none { it.owner == owner && it.sessionId == sessionId } &&
                    if (phase in setOf("REAUTH", "AUTHORIZED")) password != null else password == null)
                check(phase == "REAUTH" || phase == "AUTHORIZED" || password == null)
                check(phase !in setOf("READY", "ACKED") || token != null && frozen != null)
                check(phase !in setOf("REAUTH", "AUTHORIZED") || token == null && frozen == null && ackSeq == null && requiredSeq == null)
                check(phase != "ACKED" || ackSeq != null && ackSeq > 0 && requiredSeq != null && requiredSeq >= ackSeq)
                PendingDatasetClear(owner, epoch, sessionId, opId, groupId, JSONArray(ledgerIds.map(UUID::toString)).toString(), head, phase, password, token, frozen, ackSeq, requiredSeq)
            }
            check(clearOperation == null || restore?.let { it.owner == clearOperation.ownerId && it.sessionId == clearOperation.sessionId } != true &&
                archived.none { it.owner == clearOperation.ownerId && it.sessionId == clearOperation.sessionId })
            if (session != null || reset != null || restore != null || archived.isNotEmpty() || clearOperation != null) check(AuthJson.string(json, "origin", 2048) == origin)
            return AuthState(device, AuthJson.uuid(json, "generation"), session, pending, reset, restore, archived, clearOperation)
        } finally {
            clear.fill(0)
            deviceBytes.fill(0)
        }
    }

    private fun encodeRestore(value: PendingRestore) = JSONObject()
        .put("owner", value.owner.toString()).put("sessionId", value.sessionId.toString())
        .put("backupId", value.backupId.toString()).put("epoch", value.epoch.toString())
        .put("headSeq", value.headSeq).put("opId", value.opId.toString())
        .put("actionToken", value.actionToken).put("requestBody", value.requestBody)
        .put("jobId", value.jobId?.toString() ?: JSONObject.NULL)
        .put("committedEpoch", value.committedEpoch?.toString() ?: JSONObject.NULL)
        .put("bootstrapOpId", value.bootstrapOpId?.toString() ?: JSONObject.NULL)
        .put("bootstrapJobId", value.bootstrapJobId?.toString() ?: JSONObject.NULL)

    private fun decodeRestore(value: JSONObject): PendingRestore {
        AuthJson.keys(value, setOf("owner", "sessionId", "backupId", "epoch", "headSeq", "opId", "actionToken", "requestBody", "jobId", "committedEpoch"), setOf("bootstrapOpId", "bootstrapJobId"))
        val token = AuthJson.string(value, "actionToken", 4096)
        val body = AuthJson.string(value, "requestBody", MAX_RESTORE_BODY_BYTES)
        val opId = AuthJson.uuid(value, "opId")
        val backupId = AuthJson.uuid(value, "backupId")
        val epoch = AuthJson.uuid(value, "epoch")
        val head = AuthJson.integer(value, "headSeq").also { require(it >= 0) }
        val jobId = if (value.isNull("jobId")) null else AuthJson.uuid(value, "jobId")
        val committedEpoch = if (value.isNull("committedEpoch")) null else AuthJson.uuid(value, "committedEpoch")
        val bootstrapOpId = if (value.isNull("bootstrapOpId")) null else AuthJson.uuid(value, "bootstrapOpId")
        val bootstrapJobId = if (value.isNull("bootstrapJobId")) null else AuthJson.uuid(value, "bootstrapJobId")
        if (committedEpoch != null) check(jobId != null && committedEpoch != epoch)
        if (bootstrapJobId != null) check(bootstrapOpId != null && committedEpoch != null)
        val json = JSONObject(body)
        AuthJson.keys(json, setOf("backupId", "expectedEpoch", "expectedHeadSeq", "actionToken"))
        check(AuthJson.uuid(json, "backupId") == backupId && AuthJson.uuid(json, "expectedEpoch") == epoch &&
            AuthJson.integer(json, "expectedHeadSeq") == head && AuthJson.string(json, "actionToken", 4096) == token)
        return PendingRestore(AuthJson.uuid(value, "owner"), AuthJson.uuid(value, "sessionId"), backupId, epoch, head, opId, token, body, jobId, committedEpoch, bootstrapOpId, bootstrapJobId)
    }

    private fun existingKey(): SecretKey =
        KeyStore
            .getInstance("AndroidKeyStore")
            .apply { load(null) }
            .getKey(ALIAS, null) as? SecretKey ?: error("key unavailable")

    private fun writeLocked(
        state: AuthState,
        key: SecretKey,
    ) {
        val pending = state.pending?.let { JSONObject().put("opId", it.opId.toString()).put("originalRefresh", it.originalRefresh) }
        val reset = state.reset?.let { JSONObject().put("opId", it.opId.toString()).put("challengeId", it.challengeId.toString()).put("email", it.email) }
        val json =
            JSONObject()
                .put("version", 1)
                .put("deviceId", state.deviceId.toString())
                .put("generation", state.generation.toString())
                .put("origin", origin)
                .put("session", state.session?.let(AuthJson::encode) ?: JSONObject.NULL)
                .put("pendingRefresh", pending ?: JSONObject.NULL)
                .put("pendingReset", reset ?: JSONObject.NULL)
                .put("pendingRestore", state.restore?.let(::encodeRestore) ?: JSONObject.NULL)
                .put("archivedRestores", org.json.JSONArray().apply { state.archivedRestores.forEach { put(encodeRestore(it)) } })
                .put("pendingDatasetClear", state.datasetClear?.let { operation ->
                    JSONObject().put("ownerId", operation.ownerId.toString()).put("epochId", operation.epochId.toString())
                        .put("sessionId", operation.sessionId.toString()).put("opId", operation.opId.toString())
                        .put("groupId", operation.groupId.toString()).put("ledgerIds", org.json.JSONArray(operation.ledgerIdsJson))
                        .put("expectedHeadSeq", operation.expectedHeadSeq).put("phase", operation.phase)
                        .put("password", operation.password ?: JSONObject.NULL).put("actionToken", operation.actionToken ?: JSONObject.NULL)
                        .put("frozenGroup", operation.frozenGroupJson ?: JSONObject.NULL)
                        .put("ackSeq", operation.ackSeq ?: JSONObject.NULL).put("requiredCapturedSeq", operation.requiredCapturedSeq ?: JSONObject.NULL)
                } ?: JSONObject.NULL)
        val clear = json.toString().toByteArray(Charsets.UTF_8)
        val device =
            ByteBuffer
                .allocate(
                    16,
                ).putLong(state.deviceId.mostSignificantBits)
                .putLong(state.deviceId.leastSignificantBits)
                .array()
        val encrypted =
            try {
                check(clear.size <= MAX_STORAGE_BYTES)
                byteArrayOf(1) + encrypt(device, key, "icos.auth.device.v1") +
                    encrypt(clear, key, "icos.auth.credentials.v1:${state.deviceId}")
            } finally {
                clear.fill(0)
                device.fill(0)
            }
        FileOutputStream(pendingFile).use {
            it.write(encrypted)
            it.fd.sync()
        }
        Os.rename(pendingFile.absolutePath, file.absolutePath)
        val fd = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
        try {
            Os.fsync(fd)
        } finally {
            Os.close(fd)
        }
    }

    private fun encrypt(
        clear: ByteArray,
        key: SecretKey,
        aad: String,
    ): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
        check(cipher.iv.size == 12)
        return cipher.iv + cipher.doFinal(clear)
    }

    private fun decrypt(
        raw: ByteArray,
        key: SecretKey,
        aad: String,
    ): ByteArray {
        check(raw.size >= 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, raw, 0, 12))
        cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(raw, 12, raw.size - 12)
    }

    private fun <T> locked(block: () -> T): T {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Authentication storage requires a background thread" }
        return synchronized(monitor) {
            try {
                check(directory.isDirectory || directory.mkdirs())
                RandomAccessFile(File(directory, "state.lock"), "rw").use { lock -> lock.channel.lock().use { block() } }
            } catch (failure: AuthFailure) {
                if (failure.code in setOf("STALE_CREDENTIALS", "RESET_UNCONFIRMED", "RESTORE_UNCONFIRMED", "CLEAR_REAUTH_UNCONFIRMED")) throw failure
                throw AuthFailure("LOCAL_STORAGE_UNAVAILABLE")
            } catch (_: Exception) {
                throw AuthFailure("LOCAL_STORAGE_UNAVAILABLE")
            }
        }
    }

    companion object {
        private const val ALIAS = "icos.auth.v1"
        private const val MAX_RESTORE_BODY_BYTES = 8 * 1024
        private const val MAX_STORAGE_BYTES = 64 * 1024
        private val monitor = Any()
    }
}
