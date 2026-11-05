package dev.icos.app.data.remote

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import dev.icos.app.BuildConfig
import dev.icos.app.ui.AvatarImages
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.URL
import java.util.UUID
import javax.net.ssl.HttpsURLConnection

class LiveEndpointTest {
    @Test
    fun configuredEndpointSupportsAccountSession() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val expectedOrigin = InstrumentationRegistry.getArguments().getString("icosLiveOrigin")
        assumeTrue(
            "Live registration requires an explicitly selected endpoint",
            !expectedOrigin.isNullOrBlank() && expectedOrigin == BuildConfig.AUTH_API_ORIGIN,
        )
        val connection = URL("$expectedOrigin/health/ready").openConnection() as HttpsURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            assertEquals(200, connection.responseCode)
            val health = connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
            assertEquals("ready", health.getJSONObject("data").getString("status"))
        } finally {
            connection.disconnect()
        }

        val context = instrumentation.targetContext
        val consent = context.getSharedPreferences("legal_consent", Context.MODE_PRIVATE)
        val hadConsent = consent.contains("network_allowed")
        val previousConsent = consent.getBoolean("network_allowed", false)
        assertTrue(consent.edit().putBoolean("network_allowed", true).commit())
        val client = AuthClient(context)
        val email = "icos-smoke-${UUID.randomUUID()}@example.invalid"
        val password = UUID.randomUUID().toString()
        try {
            assertNull(client.snapshot().session)
            val registered = client.signIn(client.snapshot(), email, password, UUID.randomUUID(), register = true)
            val user = checkNotNull(registered.session).user
            assertEquals(email, user.verifiedEmail)
            assertEquals(user.id, client.profile().id)
            val bitmap = Bitmap.createBitmap(9, 5, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.CYAN) }
            val image = try { AvatarImages.encodeUpload(bitmap) } finally { bitmap.recycle() }
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(image).joinToString("") { "%02x".format(it) }
            val identity = DeviceSessions.identity(client.snapshot())
            val avatarOperation = UUID.randomUUID()
            val upload = client.authorizeAvatarUpload(identity, image, digest, avatarOperation)
            client.putAvatarUpload(identity, upload, image)
            val ready = client.completeAvatarUpload(identity, upload.attachmentId, digest, avatarOperation)
            assertEquals("READY", ready.state)
            assertEquals(digest, ready.sha256)
            assertEquals(upload.attachmentId, client.setAvatar(identity, upload.attachmentId, avatarOperation).avatarId)
            val download = client.authorizeAvatarDownload(identity, upload.attachmentId)
            org.junit.Assert.assertArrayEquals(image, client.downloadAvatar(identity, upload.attachmentId, download))
            assertNull(client.setAvatar(identity, null, UUID.randomUUID()).avatarId)
            client.logout(client.prepareLogout(UUID.randomUUID()))
            assertNull(client.snapshot().session)

            val signedIn = client.signIn(client.snapshot(), email, password, UUID.randomUUID())
            assertNotNull(signedIn.session)
            assertEquals(user.id, client.profile().id)
        } finally {
            try {
                if (client.snapshot().session != null) {
                    client.logout(client.prepareLogout(UUID.randomUUID()))
                }
            } finally {
                val editor = consent.edit()
                if (hadConsent) editor.putBoolean("network_allowed", previousConsent) else editor.remove("network_allowed")
                assertTrue(editor.commit())
            }
        }
    }
}
