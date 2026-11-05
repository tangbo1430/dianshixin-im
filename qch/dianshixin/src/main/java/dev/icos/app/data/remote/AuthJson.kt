package dev.icos.app.data.remote

import android.util.Base64
import org.json.JSONObject
import org.json.JSONTokener
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/** Strict types at the HTTPS/storage boundary; JWT parsing checks consistency, not offline authority. */
internal object AuthJson {
    const val MAX_BYTES = 16 * 1024

    // Endpoint-specific read/parse cap for the device page only; the credential/challenge default above
    // is deliberately unchanged. 50 rows of a 100-scalar name plus an opaque cursor fit inside this.
    const val MAX_DEVICE_PAGE_BYTES = DeviceSessions.MAX_RESPONSE_BYTES

    fun <T> checked(block: () -> T): T =
        try {
            block()
        } catch (failure: AuthFailure) {
            throw failure
        } catch (_: Exception) {
            throw AuthFailure("INVALID_RESPONSE", retryable = true)
        }

    fun parse(
        bytes: ByteArray,
        maxBytes: Int = MAX_BYTES,
    ): JSONObject =
        checked {
            require(bytes.size <= maxBytes)
            val text =
                Charsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            val tokener = JSONTokener(text)
            val result = tokener.nextValue() as? JSONObject ?: error("object required")
            require(tokener.nextClean() == '\u0000')
            result
        }

    fun keys(
        json: JSONObject,
        required: Set<String>,
        optional: Set<String> = emptySet(),
    ) {
        val actual = json.keys().asSequence().toSet()
        require(actual.containsAll(required) && (actual - required - optional).isEmpty())
    }

    fun string(
        json: JSONObject,
        name: String,
        max: Int,
        min: Int = 1,
    ): String {
        val value = json.get(name) as? String ?: error("string required")
        require(value.codePointCount(0, value.length) in min..max && value.none { Character.isISOControl(it) })
        return value
    }

    fun uuid(value: String): UUID {
        require(value.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
        return UUID.fromString(value).also { require(it != UUID(0, 0)) }
    }

    fun uuid(
        json: JSONObject,
        name: String,
    ) = uuid(string(json, name, 36))

    fun integer(
        json: JSONObject,
        name: String,
    ): Long =
        when (val value = json.get(name)) {
            is Int -> value.toLong()
            is Long -> value
            else -> error("integer required")
        }

    fun timestamp(value: String): Long {
        // The auth service emits this fixed RFC3339 UTC representation, including milliseconds.
        require(value.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}Z")))
        val format =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply {
                timeZone = TimeZone.getTimeZone("UTC")
                isLenient =
                    false
            }
        val position = ParsePosition(0)
        val date = format.parse(value, position) ?: error("date required")
        require(position.index == value.length && format.format(date) == value && date.time > 0)
        return date.time
    }

    fun attachmentTimestamp(value: String): Long = checked {
        // Attachment handlers use Rust time's RFC3339 formatter, not auth's fixed milliseconds.
        require(value.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,9})?(Z|[+-][0-9]{2}:[0-9]{2})")))
        java.time.OffsetDateTime.parse(value).toInstant().toEpochMilli().also { require(it > 0) }
    }

    fun user(json: JSONObject): AuthUser =
        checked {
            keys(json, setOf("id", "nickname", "verifiedEmail"), setOf("avatarId"))
            val avatar = if (!json.has("avatarId") || json.isNull("avatarId")) null else uuid(json, "avatarId")
            val email = string(json, "verifiedEmail", 254)
            require(
                email.toByteArray().size <= 254 && email.substringBeforeLast('@').isNotEmpty() &&
                    email.substringAfterLast('@', "").isNotEmpty(),
            )
            AuthUser(uuid(json, "id"), string(json, "nickname", 32), avatar, email)
        }

    fun session(
        json: JSONObject,
        device: UUID,
        owner: UUID? = null,
        sessionId: UUID? = null,
    ): AuthSession =
        checked {
            keys(json, setOf("user", "sessionId", "accessToken", "refreshToken", "expiresAt"))
            val user = user(json.getJSONObject("user"))
            val sid = uuid(json, "sessionId")
            require(owner == null || user.id == owner)
            require(sessionId == null || sid == sessionId)
            val access = string(json, "accessToken", 4096)
            val refresh = string(json, "refreshToken", 80)
            require(refresh.length == 80 && refresh.substringBefore('.') == sid.toString())
            require(base64(refresh.substringAfter('.')).size == 32)
            val expires = string(json, "expiresAt", 24)
            val millis = timestamp(expires)
            val parts = access.split('.')
            require(parts.size == 3)
            val header = parse(base64(parts[0]))
            keys(header, setOf("alg", "kid"), setOf("typ"))
            require(string(header, "alg", 16) == "EdDSA" && (!header.has("typ") || string(header, "typ", 16) == "JWT"))
            require(base64(parts[2]).size == 64)
            val claims = parse(base64(parts[1]))
            keys(claims, setOf("iss", "aud", "sub", "sessionId", "deviceId", "authVersion", "kid", "iat", "nbf", "exp"))
            require(uuid(claims, "sub") == user.id && uuid(claims, "sessionId") == sid && uuid(claims, "deviceId") == device)
            require(string(claims, "kid", 256) == string(header, "kid", 256))
            string(claims, "iss", 512)
            string(claims, "aud", 512)
            val issued = integer(claims, "iat")
            val exp = integer(claims, "exp")
            require(issued > 0 && issued <= Long.MAX_VALUE / 1000 - 900 && integer(claims, "nbf") == issued)
            require(exp == issued + 900 && exp * 1000 == millis && integer(claims, "authVersion") > 0)
            AuthSession(user, sid, access, refresh, expires, millis)
        }

    /** Strict parser for one device page: exact keys, typed values, bounded rows and cursor linkage. */
    fun devicePage(
        json: JSONObject,
        authenticatedSessionId: UUID? = null,
    ): DevicePage =
        checked {
            keys(json, setOf("items", "nextCursor", "hasMore"))
            val array = json.optJSONArray("items") ?: error("array required")
            require(array.length() <= DeviceSessions.MAX_PAGE_ITEMS)
            val items = (0 until array.length()).map { deviceSession(array.getJSONObject(it)) }
            require(items.map { it.sessionId }.toSet().size == items.size)
            if (authenticatedSessionId != null) {
                require(items.all { (it.sessionId == authenticatedSessionId) == it.current })
                require(items.count { it.current } <= 1)
            }
            val hasMore = json.get("hasMore") as? Boolean ?: error("boolean required")
            val nextCursor = if (json.isNull("nextCursor")) null else cursor(json.get("nextCursor") as? String ?: error("string required"))
            require((nextCursor != null) == hasMore)
            DevicePage(items, nextCursor, hasMore)
        }

    fun deviceSession(json: JSONObject): DeviceSession =
        checked {
            keys(json, setOf("sessionId", "deviceName", "lastSeenAt", "current"))
            val lastSeen = string(json, "lastSeenAt", 24)
            DeviceSession(
                sessionId = uuid(json, "sessionId"),
                deviceName = deviceName(json, "deviceName"),
                lastSeenAt = lastSeen,
                lastSeenAtMillis = timestamp(lastSeen),
                current = json.get("current") as? Boolean ?: error("boolean required"),
            )
        }

    private fun deviceName(
        json: JSONObject,
        name: String,
    ): String {
        // 1..100 Unicode scalars, non-blank and free of control characters, as accepted by the server.
        val value = json.get(name) as? String ?: error("string required")
        require(value.isNotBlank() && value.codePointCount(0, value.length) in 1..100 && value.none { Character.isISOControl(it) })
        return value
    }

    private fun cursor(value: String): String {
        // Opaque signed cursor: kept verbatim, never decoded or reconstructed, only bounded and quoted.
        require(value.length in 1..1024)
        require(value.none { it.isISOControl() || it.isWhitespace() })
        require(value.matches(Regex("[A-Za-z0-9_.-]+")))
        return value
    }

    private fun base64(value: String): ByteArray {
        require(value.isNotEmpty() && value.matches(Regex("[A-Za-z0-9_-]+")))
        val flags = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        return Base64.decode(value, flags).also { require(Base64.encodeToString(it, flags) == value) }
    }

    fun encode(session: AuthSession): JSONObject =
        JSONObject()
            .put(
                "user",
                JSONObject()
                    .put("id", session.user.id.toString())
                    .put("nickname", session.user.nickname)
                    .put("avatarId", session.user.avatarId?.toString() ?: JSONObject.NULL)
                    .put("verifiedEmail", session.user.verifiedEmail),
            ).put("sessionId", session.sessionId.toString())
            .put("accessToken", session.accessToken)
            .put("refreshToken", session.refreshToken)
            .put("expiresAt", session.expiresAt)

    fun challenge(json: JSONObject): AuthChallenge =
        checked {
            keys(json, setOf("challengeId", "expiresAt", "retryAfter"))
            val retry = integer(json, "retryAfter")
            require(retry in 0..86400)
            AuthChallenge(uuid(json, "challengeId"), timestamp(string(json, "expiresAt", 24)), retry.toInt())
        }

    fun accountDeletionReceipt(json: JSONObject): AccountDeletionReceipt = checked {
        keys(json, setOf("jobId", "type", "state", "progress", "errorCode", "createdAt", "finishedAt"))
        require(string(json, "type", 32) == "ACCOUNT_DELETE")
        val state = string(json, "state", 32)
        require(state in setOf("QUEUED", "PENDING", "RETRY_WAIT", "RUNNING", "COMPLETED", "FAILED", "CANCELLED"))
        val progress = integer(json, "progress")
        require(progress in 0..100)
        val error = if (json.isNull("errorCode")) null else string(json, "errorCode", 64)
        require(error == null || error in setOf("ONLINE_STORAGE_UNAVAILABLE", "OBJECT_ADAPTER_UNAVAILABLE", "INFRASTRUCTURE_EXPIRY_PENDING"))
        val created = timestamp(string(json, "createdAt", 24))
        val finished = if (json.isNull("finishedAt")) null else timestamp(string(json, "finishedAt", 24))
        require((state == "COMPLETED" || state == "FAILED" || state == "CANCELLED") == (finished != null))
        AccountDeletionReceipt(uuid(json, "jobId"), state, progress.toInt(), error, created, finished)
    }

    fun error(
        json: JSONObject,
        request: UUID,
        retryAfter: Int,
    ): AuthFailure =
        checked {
            keys(json, setOf("error"))
            val body = json.getJSONObject("error")
            keys(body, setOf("code", "message", "fields", "requestId", "retryable"))
            require(uuid(body, "requestId") == request)
            val code = string(body, "code", 80)
            require(code.matches(Regex("[A-Z][A-Z0-9_]*")))
            require(body.get("message") is String && body.get("retryable") is Boolean)
            val fields = body.getJSONObject("fields")
            fields.keys().forEach { require(fields.get(it) is String) }
            AuthFailure(
                code,
                request,
                retryAfter,
                body.get("retryable") as Boolean,
                fields.keys().asSequence().associateWith { fields.getString(it) },
            )
        }
}
