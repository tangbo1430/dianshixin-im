package dev.icos.app.data.remote

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AuthJsonTest {
    private val device = UUID.fromString("103b5a03-8adc-40b9-9c4c-a6d1250e59e1")
    private val owner = UUID.fromString("e5daa9d2-3b4a-4c8d-91f1-e0c58473cc57")
    private val session = UUID.fromString("74dc8113-8a2c-490f-b8b1-d68f6741b3dd")
    private val request = UUID.fromString("3329988e-19ac-443b-a0a7-47e17e1e8869")

    private fun encoded(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun result(change: (JSONObject) -> Unit = {}): JSONObject {
        val claims =
            JSONObject()
                .put("iss", "test-issuer")
                .put("aud", "test-audience")
                .put("sub", owner.toString())
                .put("sessionId", session.toString())
                .put("deviceId", device.toString())
                .put("authVersion", 1)
                .put("kid", "test-key")
                .put("iat", 1767225600)
                .put("nbf", 1767225600)
                .put("exp", 1767226500)
        change(claims)
        val header = JSONObject().put("alg", "EdDSA").put("typ", "JWT").put("kid", "test-key")
        val access =
            encoded(header.toString().toByteArray()) + "." + encoded(claims.toString().toByteArray()) + "." + encoded(ByteArray(64))
        return JSONObject()
            .put(
                "user",
                JSONObject()
                    .put(
                        "id",
                        owner.toString(),
                    ).put("nickname", "测试")
                    .put("avatarId", JSONObject.NULL)
                    .put("verifiedEmail", "A.b+tag@example.com"),
            ).put("sessionId", session.toString())
            .put("accessToken", access)
            .put("refreshToken", "$session.${encoded(ByteArray(32))}")
            .put("expiresAt", "2026-01-01T00:15:00.000Z")
    }

    @Test
    fun checksDeviceOwnerSessionAndStrictTypesWithoutTreatingClaimsAsOfflineAuthority() {
        assertEquals(owner, AuthJson.session(result(), device).user.id)
        val mutations: List<(JSONObject) -> Unit> =
            listOf(
                { it.put("deviceId", UUID.randomUUID().toString()) },
                { it.put("sub", UUID.randomUUID().toString()) },
                { it.put("sessionId", UUID.randomUUID().toString()) },
                { it.put("authVersion", "1") },
                { it.put("exp", 1767226500.5) },
                { it.put("nbf", 1) },
            )
        mutations.forEach { change -> assertThrows(AuthFailure::class.java) { AuthJson.session(result(change), device) } }
        listOf("2026-02-30T00:15:00.000Z", "2026-01-01T00:15:00Z", "2026-01-01T00:15:00.000+00:00").forEach {
            assertThrows(AuthFailure::class.java) { AuthJson.session(result().put("expiresAt", it), device) }
        }
        assertThrows(AuthFailure::class.java) { AuthJson.session(result().put("refreshToken", "$session.${"A".repeat(42)}B"), device) }
        assertThrows(AuthFailure::class.java) { AuthJson.session(result(), device, UUID.randomUUID(), session) }
        assertThrows(AuthFailure::class.java) { AuthJson.session(result().put("user", JSONObject().put("id", true)), device) }
        assertThrows(AuthFailure::class.java) { AuthJson.parse(ByteArray(AuthJson.MAX_BYTES + 1)) }
        assertThrows(AuthFailure::class.java) { AuthJson.parse("{} trailing".toByteArray()) }
        assertThrows(AuthFailure::class.java) {
            AuthJson.challenge(
                JSONObject().put("challengeId", request.toString()).put("expiresAt", "2026-01-01T00:15:00.000Z").put("retryAfter", "60"),
            )
        }
    }

    @Test
    fun bmpAvatarReaderMatchesServerRgbBmpBoundsAndRejectsOtherEncodings() {
        fun bmp(width: Int, height: Int, bits: Int, compression: Int = 0): ByteArray {
            val row = ((width * bits + 31) / 32) * 4
            val bytes = ByteArray(54 + row * height)
            val b = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            b.put(0, 'B'.code.toByte()); b.put(1, 'M'.code.toByte()); b.putInt(2, bytes.size); b.putInt(10, 54)
            b.putInt(14, 40); b.putInt(18, width); b.putInt(22, height); b.putShort(26, 1); b.putShort(28, bits.toShort()); b.putInt(30, compression); b.putInt(34, row * height)
            return bytes
        }
        assertTrue(AvatarBmp.isSupported(bmp(1, 1, 24)))
        assertTrue(AvatarBmp.isSupported(bmp(1, 1, 32)))
        assertFalse(AvatarBmp.isSupported(bmp(1, 1, 24, compression = 1)))
        assertFalse(AvatarBmp.isSupported(bmp(1, 1, 16)))
        assertFalse(AvatarBmp.isSupported(bmp(8193, 1, 24)))
        assertFalse(AvatarBmp.isSupported(bmp(1, 1, 24).copyOf(57)))
    }

    @Test
    fun errorEnvelopeUsesValidatedRequestIdAndLocalMessages() {
        val body =
            JSONObject()
                .put("code", "INVALID_CREDENTIALS")
                .put("message", "untrusted server text")
                .put("fields", JSONObject().put("email", "untrusted field text"))
                .put("requestId", request.toString())
                .put("retryable", false)
        val envelope = JSONObject().put("error", body)
        val failure = AuthJson.error(envelope, request, 60)
        assertEquals(request, failure.requestId)
        assertEquals(60, failure.retryAfter)
        assertFalse(failure.userMessage.contains("untrusted"))
        assertThrows(AuthFailure::class.java) { AuthJson.error(envelope, UUID.randomUUID(), 0) }
        body.put("retryable", "false")
        assertThrows(AuthFailure::class.java) { AuthJson.error(envelope, request, 0) }
    }

    private fun deviceRow(
        session: UUID = UUID.randomUUID(),
        name: Any = "Pixel",
        lastSeen: String = "2026-01-01T00:15:00.000Z",
        current: Any = false,
    ) = JSONObject()
        .put("sessionId", session.toString())
        .put("deviceName", name)
        .put("lastSeenAt", lastSeen)
        .put("current", current)

    private fun devicePage(
        items: List<JSONObject>,
        nextCursor: Any = JSONObject.NULL,
        hasMore: Any = false,
    ) = JSONObject()
        .put("items", JSONArray(items))
        .put("nextCursor", nextCursor)
        .put("hasMore", hasMore)

    @Test
    fun devicePageAcceptsFiftyMaxUnicodeNamesBeyondTheCredentialBoundButNotUnlimitedBodies() {
        val name = "\uD83D\uDE00".repeat(100) // 100 supplementary scalars, 400 UTF-8 bytes
        assertEquals(100, name.codePointCount(0, name.length))
        val page = devicePage((0 until 50).map { deviceRow(UUID.randomUUID(), name) }, nextCursor = "AAAA.BBBB-_", hasMore = true)
        val bytes = page.toString().toByteArray(Charsets.UTF_8)
        assertTrue(bytes.size > AuthJson.MAX_BYTES)
        assertTrue(bytes.size <= AuthJson.MAX_DEVICE_PAGE_BYTES)
        // The credential/challenge default is unchanged; only the device endpoint uses the larger cap.
        assertThrows(AuthFailure::class.java) { AuthJson.parse(bytes) }
        val parsed = AuthJson.devicePage(AuthJson.parse(bytes, AuthJson.MAX_DEVICE_PAGE_BYTES))
        assertEquals(50, parsed.items.size)
        assertTrue(parsed.hasMore)
        assertEquals("AAAA.BBBB-_", parsed.nextCursor)
        assertEquals(name, parsed.items.first().deviceName)
        assertFalse(parsed.items.first().current)
    }

    @Test
    fun devicePageRejectsWrongTypesNamesUuidsDuplicatesAndCursorMismatches() {
        val session = UUID.randomUUID()
        assertEquals(session, AuthJson.deviceSession(deviceRow(session)).sessionId)
        assertThrows(AuthFailure::class.java) { AuthJson.deviceSession(deviceRow(session, name = "   ")) }
        assertThrows(AuthFailure::class.java) { AuthJson.deviceSession(deviceRow(session, name = "\uD83D\uDE00".repeat(101))) }
        assertThrows(AuthFailure::class.java) { AuthJson.deviceSession(deviceRow(session, name = "bad\nname")) }
        assertThrows(AuthFailure::class.java) { AuthJson.deviceSession(deviceRow(session, name = 7)) }
        assertThrows(AuthFailure::class.java) { AuthJson.deviceSession(deviceRow(session, lastSeen = "2026-01-01T00:15:00Z")) }
        assertThrows(AuthFailure::class.java) { AuthJson.deviceSession(deviceRow(session, current = "false")) }
        assertThrows(AuthFailure::class.java) { AuthJson.deviceSession(deviceRow(UUID(0, 0))) }
        assertThrows(AuthFailure::class.java) { AuthJson.deviceSession(deviceRow(session).put("extra", 1)) }
        assertThrows(AuthFailure::class.java) { AuthJson.deviceSession(deviceRow(session).apply { remove("current") }) }

        assertThrows(AuthFailure::class.java) { AuthJson.devicePage(devicePage(listOf(deviceRow(session), deviceRow(session)))) }
        assertThrows(AuthFailure::class.java) { AuthJson.devicePage(devicePage(listOf(deviceRow()), hasMore = "true")) }
        assertThrows(AuthFailure::class.java) { AuthJson.devicePage(devicePage(listOf(deviceRow()), hasMore = true)) }
        assertThrows(AuthFailure::class.java) { AuthJson.devicePage(devicePage(listOf(deviceRow()), nextCursor = "c", hasMore = false)) }
        assertThrows(AuthFailure::class.java) { AuthJson.devicePage(devicePage(listOf(deviceRow()), nextCursor = true, hasMore = true)) }
        assertThrows(AuthFailure::class.java) { AuthJson.devicePage(devicePage(listOf(deviceRow()), nextCursor = 7, hasMore = true)) }
        assertThrows(AuthFailure::class.java) { AuthJson.devicePage(devicePage(listOf(deviceRow()), nextCursor = "a b", hasMore = true)) }
        assertThrows(AuthFailure::class.java) { AuthJson.devicePage(devicePage(List(51) { deviceRow() }, nextCursor = "c", hasMore = true)) }
        assertThrows(AuthFailure::class.java) { AuthJson.devicePage(JSONObject().put("items", JSONObject()).put("nextCursor", JSONObject.NULL).put("hasMore", false)) }
        val currentSession = UUID.randomUUID()
        assertThrows(AuthFailure::class.java) {
            AuthJson.devicePage(devicePage(listOf(deviceRow(currentSession, current = false))), currentSession)
        }
        assertThrows(AuthFailure::class.java) {
            AuthJson.devicePage(devicePage(listOf(deviceRow(UUID.randomUUID(), current = true))), currentSession)
        }
    }
}
