package dev.icos.app.data.remote

import android.content.Context
import android.os.Looper
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

internal data class LegalDocument(val kind: String, val locale: String, val version: Long, val content: String, val changeSummary: String, val contentDigest: String)
internal data class AndroidRelease(val versionCode: Long, val versionName: String, val minimumSupported: Long, val notes: String, val downloadUrl: String, val sha256: String, val certificateSha256: String, val channel: String)
internal data class PublicConfiguration(val version: Long, val values: Map<String, Any?>) {
    val feedbackEnabled: Boolean get() = (values["features"] as? Map<*, *>)?.get("feedbackEnabled") as? Boolean ?: true
    val androidUpdatesEnabled: Boolean get() = (values["features"] as? Map<*, *>)?.get("androidUpdatesEnabled") as? Boolean ?: true
}
internal sealed interface PublicationResult<out T> {
    data class Available<T>(val value: T, val cached: Boolean = false) : PublicationResult<T>
    data class Unavailable(val code: String) : PublicationResult<Nothing>
}

internal object PublicationJson {
    const val LEGAL_MAX_BYTES = 96 * 1024
    const val UPDATE_MAX_BYTES = 12 * 1024
    const val CONFIG_MAX_BYTES = 16 * 1024
    const val ENVELOPE_MAX_BYTES = 128
    private const val MAX_TEXT = 64 * 1024

    fun successData(raw: ByteArray, max: Int): ByteArray {
        val envelope = parse(raw, max + ENVELOPE_MAX_BYTES)
        keys(envelope, setOf("data", "requestId"))
        val requestId = text(envelope, "requestId", 36)
        require(java.util.UUID.fromString(requestId).toString() == requestId.lowercase())
        require(envelope["data"] is Map<*, *>)
        return StrictJson.encode(envelope["data"]).toByteArray(Charsets.UTF_8).also { require(it.size <= max) }
    }

    fun consentMatches(savedVersion: Long?, savedDigest: String?, document: LegalDocument): Boolean =
        savedVersion == document.version && savedDigest == document.contentDigest

    fun needsAgreement(savedPrivacy: StoredConsent?, savedTerms: StoredConsent?, privacy: LegalDocument?, terms: LegalDocument?, networkAllowed: Boolean): Boolean {
        if (privacy == null || terms == null) return true
        val current = consentMatches(savedPrivacy?.version, savedPrivacy?.contentDigest, privacy) &&
            consentMatches(savedTerms?.version, savedTerms?.contentDigest, terms)
        return !current
    }

    fun hasValidNetworkConsent(networkAllowed: Boolean, privacy: StoredConsent?, terms: StoredConsent?): Boolean =
        networkAllowed && privacy.isValid() && terms.isValid()

    /** Null needs a choice; false is a previously chosen guest mode, not missing consent. */
    fun startupNetworkAllowed(networkAllowed: Boolean?, privacy: StoredConsent?, terms: StoredConsent?): Boolean? =
        when (networkAllowed) {
            null -> null
            false -> false
            true -> true.takeIf { hasValidNetworkConsent(true, privacy, terms) }
        }

    fun mayFetchPublicRoute(requiresConsent: Boolean, hasNetworkConsent: Boolean): Boolean =
        !requiresConsent || hasNetworkConsent

    fun mayAcceptAgreement(documentsValid: Boolean, loading: Boolean, cached: Boolean, error: String?): Boolean =
        documentsValid && !loading && !cached && error == null

    fun shouldAutoContinueAgreement(
        alreadyAccepted: Boolean,
        documentsValid: Boolean,
        loading: Boolean,
        cached: Boolean,
        error: String?,
        networkAllowed: Boolean,
    ): Boolean = alreadyAccepted && networkAllowed && mayAcceptAgreement(documentsValid, loading, cached, error)

    private fun StoredConsent?.isValid() = this != null && version > 0 && contentDigest.matches(Regex("[0-9a-f]{64}"))

    fun legal(raw: ByteArray, expectedKind: String, locale: String): LegalDocument {
        val json = parse(raw, LEGAL_MAX_BYTES)
        keys(json, setOf("kind", "locale", "version", "content", "changeSummary", "publishedAt"))
        require(text(json, "kind", 16) == expectedKind && text(json, "locale", 12) == locale)
        val version = integer(json, "version").also { require(it > 0) }
        text(json, "publishedAt", 64)
        val content = text(json, "content", MAX_TEXT)
        val summary = text(json, "changeSummary", 2_000)
        require(content.isNotBlank() && summary.isNotBlank())
        val digest = MessageDigest.getInstance("SHA-256").digest(content.toByteArray(Charsets.UTF_8))
        return LegalDocument(expectedKind, locale, version, content, summary, digest.joinToString("") { "%02x".format(it) })
    }

    fun update(raw: ByteArray, channel: String): AndroidRelease {
        val json = parse(raw, UPDATE_MAX_BYTES)
        keys(json, setOf("versionCode", "versionName", "minimumSupported", "notes", "downloadUrl", "sha256", "certificateSha256", "channel"))
        val url = text(json, "downloadUrl", 2048)
        require(URI(url).let { it.scheme == "https" && it.host != null && it.rawUserInfo == null && it.rawFragment == null })
        val sha = text(json, "sha256", 64).lowercase()
        val cert = text(json, "certificateSha256", 64).lowercase()
        require(sha.matches(Regex("[0-9a-f]{64}")) && cert.matches(Regex("[0-9a-f]{64}")))
        val result = AndroidRelease(integer(json, "versionCode"), text(json, "versionName", 64), integer(json, "minimumSupported"), text(json, "notes", 4_000), url, sha, cert, text(json, "channel", 16))
        require(result.versionCode > 0 && result.minimumSupported in 1..result.versionCode && result.channel == channel)
        return result
    }

    fun configuration(raw: ByteArray): PublicConfiguration {
        val json = parse(raw, CONFIG_MAX_BYTES)
        keys(json, setOf("version", "values", "publishedAt"))
        val version = integer(json, "version").also { require(it > 0) }
        text(json, "publishedAt", 64)
        val values = json["values"] as? Map<*, *> ?: error("configuration values object required")
        require(StrictJson.encodedSize(values) <= CONFIG_MAX_BYTES)
        val features = values["features"]
        require(features == null || features is Map<*, *> && features.keys.all {
            it in setOf("feedbackEnabled", "androidUpdatesEnabled", "legalUpdatesRequired")
        } && features.values.all { it is Boolean })
        return PublicConfiguration(version, values.entries.associate { (key, value) ->
            (key as? String ?: error("configuration key must be a string")) to value
        })
    }

    private fun parse(bytes: ByteArray, max: Int): Map<String, Any?> {
        require(bytes.isNotEmpty() && bytes.size <= max)
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        return StrictJson.parseObject(text)
    }
    private fun keys(json: Map<String, Any?>, expected: Set<String>) = require(json.keys == expected)
    private fun text(json: Map<String, Any?>, key: String, max: Int): String {
        val value = json[key] as? String ?: error("string required")
        require(value.length <= max && value.none { Character.isISOControl(it) && it !in "\n\r\t" })
        return value
    }
    private fun integer(json: Map<String, Any?>, key: String): Long {
        val value = json[key]
        require(value is Long || value is Int)
        return (value as Number).toLong()
    }
}

internal class PublicPublicationClient(context: Context) {
    private val appContext = context.applicationContext
    private val origin = dev.icos.app.BuildConfig.AUTH_API_ORIGIN.removeSuffix("/")
    private val preferences = context.getSharedPreferences("public_publications_cache", Context.MODE_PRIVATE)

    private fun hasNetworkConsent(): Boolean = ConsentRecord.hasNetworkConsent(appContext)

    fun legal(kind: String, locale: String = "zh-CN"): PublicationResult<LegalDocument> = fetch("/v1/legal/$kind?locale=$locale", PublicationJson.LEGAL_MAX_BYTES, requiresConsent = false,
        { PublicationJson.legal(it, kind, locale) }, { cacheLegal(it) }, { readLegalCache(kind, locale) })
    fun update(channel: String = "stable"): PublicationResult<AndroidRelease> = fetch("/v1/updates/android?channel=$channel", PublicationJson.UPDATE_MAX_BYTES, requiresConsent = true,
        { PublicationJson.update(it, channel) }, { cacheUpdate(it) }, { readUpdateCache(channel) })
    fun configuration(): PublicationResult<PublicConfiguration> = fetch("/v1/configuration", PublicationJson.CONFIG_MAX_BYTES, requiresConsent = true,
        PublicationJson::configuration, { cacheConfiguration(it) }, { readConfigurationCache() })

    private fun <T> fetch(path: String, maxBytes: Int, requiresConsent: Boolean, parse: (ByteArray) -> T, save: (T) -> Unit, cached: () -> T?): PublicationResult<T> {
        if (!PublicationJson.mayFetchPublicRoute(requiresConsent, hasNetworkConsent())) return PublicationResult.Unavailable("CONSENT_REQUIRED")
        check(Looper.myLooper() != Looper.getMainLooper()) { "Public API requests require a background thread" }
        if (origin.isEmpty()) return cached()?.let { PublicationResult.Available(it, true) } ?: PublicationResult.Unavailable("UNCONFIGURED")
        val connection = try {
            val base = URI(origin)
            require(base.scheme == "https" && base.host != null && base.rawUserInfo == null && base.rawQuery == null && base.rawFragment == null && base.rawPath.orEmpty().isEmpty())
            URL(origin + path).openConnection() as HttpsURLConnection
        } catch (_: Exception) { return cached()?.let { PublicationResult.Available(it, true) } ?: PublicationResult.Unavailable("UNCONFIGURED") }
        return try {
            connection.requestMethod = "GET"; connection.connectTimeout = 8_000; connection.readTimeout = 8_000
            connection.instanceFollowRedirects = false; connection.useCaches = false
            connection.setRequestProperty("Accept", "application/json"); connection.setRequestProperty("Cache-Control", "no-cache")
            val status = connection.responseCode
            if (status !in 200..299) return cached()?.let { PublicationResult.Available(it, true) } ?: PublicationResult.Unavailable(if (status == 404) "NOT_PUBLISHED" else "NETWORK")
            require(connection.contentType.orEmpty().substringBefore(';').trim().equals("application/json", true))
            val output = ByteArrayOutputStream()
            connection.inputStream.use { stream ->
                val buffer = ByteArray(2048)
                while (true) { val n = stream.read(buffer); if (n < 0) break; require(output.size() + n <= maxBytes + PublicationJson.ENVELOPE_MAX_BYTES); output.write(buffer, 0, n) }
            }
            val value = parse(PublicationJson.successData(output.toByteArray(), maxBytes)); runCatching { save(value) }
            PublicationResult.Available(value)
        } catch (_: Exception) { cached()?.let { PublicationResult.Available(it, true) } ?: PublicationResult.Unavailable("UNAVAILABLE") }
        finally { connection.disconnect() }
    }

    private fun cacheLegal(doc: LegalDocument) {
        val k = "legal:${doc.kind}:${doc.locale}"
        preferences.edit().putString("$k:json", JSONObject().put("kind", doc.kind).put("locale", doc.locale).put("version", doc.version).put("content", doc.content).put("changeSummary", doc.changeSummary).put("publishedAt", "cache").toString()).commit()
    }
    private fun readLegalCache(kind: String, locale: String): LegalDocument? = runCatching { PublicationJson.legal(preferences.getString("legal:$kind:$locale:json", null)!!.toByteArray(), kind, locale) }.getOrNull()
    private fun cacheUpdate(v: AndroidRelease) {
        preferences.edit().putString("update:${v.channel}:json", JSONObject().put("versionCode", v.versionCode).put("versionName", v.versionName).put("minimumSupported", v.minimumSupported).put("notes", v.notes).put("downloadUrl", v.downloadUrl).put("sha256", v.sha256).put("certificateSha256", v.certificateSha256).put("channel", v.channel).toString()).commit()
    }
    private fun readUpdateCache(channel: String): AndroidRelease? = runCatching { PublicationJson.update(preferences.getString("update:$channel:json", null)!!.toByteArray(), channel) }.getOrNull()
    private fun cacheConfiguration(v: PublicConfiguration) {
        preferences.edit().putString("configuration:json", JSONObject().put("version", v.version).put("values", v.values).put("publishedAt", "cache").toString()).commit()
    }
    private fun readConfigurationCache(): PublicConfiguration? = runCatching { PublicationJson.configuration(preferences.getString("configuration:json", null)!!.toByteArray()) }.getOrNull()
}

internal data class StoredConsent(val version: Long, val contentDigest: String)
internal object ConsentRecord {
    /** Restore only an explicit saved choice and valid recorded agreements; no network is required. */
    fun startupNetworkAllowed(context: Context): Boolean? {
        val p = context.getSharedPreferences("legal_consent", Context.MODE_PRIVATE)
        val allowed = if (p.contains("network_allowed")) p.getBoolean("network_allowed", false) else null
        return PublicationJson.startupNetworkAllowed(allowed, read(context, "privacy"), read(context, "terms"))
    }

    fun hasNetworkConsent(context: Context): Boolean {
        val p = context.getSharedPreferences("legal_consent", Context.MODE_PRIVATE)
        return PublicationJson.hasValidNetworkConsent(
            p.getBoolean("network_allowed", false),
            read(context, "privacy"),
            read(context, "terms"),
        )
    }

    fun read(context: Context, kind: String): StoredConsent? {
        val p = context.getSharedPreferences("legal_consent", Context.MODE_PRIVATE)
        val version = p.getLong("${kind}_version", 0)
        val digest = p.getString("${kind}_digest", null) ?: return null
        return if (version > 0 && digest.matches(Regex("[0-9a-f]{64}"))) StoredConsent(version, digest) else null
    }
    fun matches(context: Context, doc: LegalDocument) = read(context, doc.kind) == StoredConsent(doc.version, doc.contentDigest)
    fun record(context: Context, docs: List<LegalDocument>): Boolean {
        if (docs.map { it.kind }.toSet() != setOf("privacy", "terms")) return false
        val e = context.getSharedPreferences("legal_consent", Context.MODE_PRIVATE).edit()
        docs.forEach { e.putLong("${it.kind}_version", it.version).putString("${it.kind}_digest", it.contentDigest) }
        e.putBoolean("network_allowed", true)
        return e.commit()
    }

    fun setNetworkAllowed(context: Context, allowed: Boolean): Boolean =
        context.getSharedPreferences("legal_consent", Context.MODE_PRIVATE).edit().putBoolean("network_allowed", allowed).commit()
}
