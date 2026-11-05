package dev.icos.app.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicPublicationJsonTest {
    private val digest = "a".repeat(64)
    private val cert = "b".repeat(64)

    @Test fun apiEnvelopesUnwrapAllPublicationsWithoutChangingCachedDtos() {
        val legal = """{"kind":"privacy","locale":"zh-CN","version":4,"content":"正文","changeSummary":"摘要","publishedAt":"now"}"""
        val update = """{"versionCode":18,"versionName":"1.8","minimumSupported":12,"notes":"修复","downloadUrl":"https://downloads.example/app.apk","sha256":"$digest","certificateSha256":"$cert","channel":"stable"}"""
        val config = """{"version":2,"values":{"features":{"feedbackEnabled":false,"androidUpdatesEnabled":false}},"publishedAt":"now"}"""
        fun unwrap(dto: String, max: Int) = PublicationJson.successData(
            """{"data":$dto,"requestId":"12345678-1234-4234-8234-123456789abc"}""".toByteArray(), max,
        )
        assertEquals(PublicationJson.legal(legal.toByteArray(), "privacy", "zh-CN"), PublicationJson.legal(unwrap(legal, PublicationJson.LEGAL_MAX_BYTES), "privacy", "zh-CN"))
        assertEquals(PublicationJson.update(update.toByteArray(), "stable"), PublicationJson.update(unwrap(update, PublicationJson.UPDATE_MAX_BYTES), "stable"))
        assertEquals(PublicationJson.configuration(config.toByteArray()), PublicationJson.configuration(unwrap(config, PublicationJson.CONFIG_MAX_BYTES)))
    }

    @Test fun featureFlagsUsePublishedBooleansAndDefaultMissingFlagsToEnabled() {
        fun config(features: String) = PublicationJson.configuration(
            """{"version":1,"values":{"features":$features},"publishedAt":"now"}""".toByteArray(),
        )
        assertFalse(config("""{"feedbackEnabled":false,"androidUpdatesEnabled":false}""").feedbackEnabled)
        assertFalse(config("""{"feedbackEnabled":false,"androidUpdatesEnabled":false}""").androidUpdatesEnabled)
        assertTrue(config("{}").feedbackEnabled)
        assertTrue(config("{}").androidUpdatesEnabled)
        for (invalid in listOf("[]", """{"feedbackEnabled":"false"}""", """{"unknown":true}""")) {
            org.junit.Assert.assertThrows(RuntimeException::class.java) { config(invalid) }
        }
    }

    @Test fun apiEnvelopeRejectsMalformedUnknownDuplicateAndOversizedFields() {
        val id = "12345678-1234-4234-8234-123456789abc"
        for (raw in listOf(
            """{"data":{},"requestId":"invalid"}""",
            """{"data":{},"requestId":"$id","extra":true}""",
            """{"data":{},"data":{},"requestId":"$id"}""",
            """{"data":[],"requestId":"$id"}""",
            """{"data":{},"requestId":"$id","requestId":"$id"}""",
            """{"version":1}""",
        )) org.junit.Assert.assertThrows(RuntimeException::class.java) { PublicationJson.successData(raw.toByteArray(), 1024) }
        org.junit.Assert.assertThrows(RuntimeException::class.java) {
            PublicationJson.successData("""{"data":{"text":"${"x".repeat(100)}"},"requestId":"$id"}""".toByteArray(), 100)
        }
        org.junit.Assert.assertThrows(java.nio.charset.MalformedInputException::class.java) {
            PublicationJson.successData(byteArrayOf(0xc3.toByte(), 0x28), 1024)
        }
    }

    @Test fun categoryShareRejectsUnsupportedIconAndColor() {
        CategoryShareJson.requireSupportedStyle(null, null)
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { CategoryShareJson.requireSupportedStyle("some-icon", null) }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { CategoryShareJson.requireSupportedStyle(null, "#AABBCC") }
    }

    @Test fun categoryShareManualUrlMustMatchExactFixedOriginAndPath() {
        val token = "a".repeat(64)
        assertEquals(token, CategoryShareJson.tokenFromManualInput("https://api.example/v1/category-shares/$token", "https://api.example"))
        org.junit.Assert.assertThrows(RuntimeException::class.java) { CategoryShareJson.tokenFromManualInput("https://other.example/v1/category-shares/$token", "https://api.example") }
        org.junit.Assert.assertThrows(RuntimeException::class.java) { CategoryShareJson.tokenFromManualInput("https://api.example/v1/category-shares/$token/extra", "https://api.example") }
    }

    @Test fun publishedLegalContentHasStableVersionAndUtf8Digest() {
        val content = "真实发布内容：账单与隐私"
        val raw = """{"kind":"privacy","locale":"zh-CN","version":4,"content":"$content","changeSummary":"更新数据说明","publishedAt":"2026-10-18T12:00:00.000Z"}""".toByteArray()
        val result = PublicationJson.legal(raw, "privacy", "zh-CN")
        assertEquals(4L, result.version)
        assertEquals("privacy", result.kind)
        assertEquals(java.security.MessageDigest.getInstance("SHA-256").digest(content.toByteArray()).joinToString("") { "%02x".format(it) }, result.contentDigest)
    }

    @Test fun legalRejectsWrongRouteKindExtraFieldsAndOversizedBody() {
        val wrongKind = """{"kind":"terms","locale":"zh-CN","version":1,"content":"正文","changeSummary":"摘要","publishedAt":"now"}""".toByteArray()
        org.junit.Assert.assertThrows(RuntimeException::class.java) { PublicationJson.legal(wrongKind, "privacy", "zh-CN") }
        val extra = """{"kind":"privacy","locale":"zh-CN","version":1,"content":"正文","changeSummary":"摘要","publishedAt":"now","html":"<script>"}""".toByteArray()
        org.junit.Assert.assertThrows(RuntimeException::class.java) { PublicationJson.legal(extra, "privacy", "zh-CN") }
        org.junit.Assert.assertThrows(RuntimeException::class.java) { PublicationJson.legal(ByteArray(PublicationJson.LEGAL_MAX_BYTES + 1), "privacy", "zh-CN") }
        val duplicate = """{"kind":"privacy","kind":"terms","locale":"zh-CN","version":1,"content":"正文","changeSummary":"摘要","publishedAt":"now"}""".toByteArray()
        org.junit.Assert.assertThrows(RuntimeException::class.java) { PublicationJson.legal(duplicate, "privacy", "zh-CN") }
    }

    @Test fun releaseRequiresHttpsAndBothSha256Fingerprints() {
        fun raw(url: String, sha: String = digest, certificate: String = cert) =
            """{"versionCode":18,"versionName":"1.8","minimumSupported":12,"notes":"修复与变更","downloadUrl":"$url","sha256":"$sha","certificateSha256":"$certificate","channel":"stable"}""".toByteArray()
        assertEquals(18L, PublicationJson.update(raw("https://downloads.example/app.apk"), "stable").versionCode)
        org.junit.Assert.assertThrows(RuntimeException::class.java) { PublicationJson.update(raw("http://downloads.example/app.apk"), "stable") }
        org.junit.Assert.assertThrows(RuntimeException::class.java) { PublicationJson.update(raw("https://downloads.example/app.apk", "invalid"), "stable") }
        org.junit.Assert.assertThrows(RuntimeException::class.java) { PublicationJson.update(raw("https://downloads.example/app.apk"), "beta") }
    }

    @Test fun nonLegalPublicationsRequireBothValidConsents() {
        val privacy = StoredConsent(4, digest)
        val terms = StoredConsent(7, cert)
        assertTrue(PublicationJson.hasValidNetworkConsent(true, privacy, terms))
        assertFalse(PublicationJson.hasValidNetworkConsent(false, privacy, terms))
        assertFalse(PublicationJson.hasValidNetworkConsent(true, privacy, null))
        assertFalse(PublicationJson.hasValidNetworkConsent(true, privacy, terms.copy(version = 0)))
        assertFalse(PublicationJson.hasValidNetworkConsent(true, privacy, terms.copy(contentDigest = "invalid")))
        assertTrue(PublicationJson.mayFetchPublicRoute(false, false))
        assertFalse(PublicationJson.mayFetchPublicRoute(true, false))
        assertTrue(PublicationJson.mayFetchPublicRoute(true, true))
    }

    @Test fun startupRestoresExplicitGuestOrValidRecordedNetworkConsent() {
        val privacy = StoredConsent(4, digest)
        val terms = StoredConsent(7, cert)
        assertNull(PublicationJson.startupNetworkAllowed(null, null, null))
        assertNull(PublicationJson.startupNetworkAllowed(null, privacy, terms))
        assertEquals(false, PublicationJson.startupNetworkAllowed(false, null, null))
        assertEquals(false, PublicationJson.startupNetworkAllowed(false, privacy, terms))
        assertEquals(true, PublicationJson.startupNetworkAllowed(true, privacy, terms))
        assertNull(PublicationJson.startupNetworkAllowed(true, null, terms))
        assertNull(PublicationJson.startupNetworkAllowed(true, privacy, null))
        assertNull(PublicationJson.startupNetworkAllowed(true, privacy.copy(version = 0), terms))
        assertNull(PublicationJson.startupNetworkAllowed(true, privacy, terms.copy(contentDigest = "invalid")))
    }

    @Test fun networkAgreementRequiresFreshCompleteDocuments() {
        assertTrue(PublicationJson.mayAcceptAgreement(true, loading = false, cached = false, error = null))
        assertFalse(PublicationJson.mayAcceptAgreement(true, loading = false, cached = true, error = null))
        assertFalse(PublicationJson.mayAcceptAgreement(true, loading = false, cached = false, error = "NETWORK"))
        assertFalse(PublicationJson.mayAcceptAgreement(false, loading = false, cached = false, error = null))
        assertFalse(PublicationJson.mayAcceptAgreement(true, loading = true, cached = false, error = null))
    }

    @Test fun cachedOrGuestConsentNeverAutoContinuesNetworkAgreement() {
        assertTrue(PublicationJson.shouldAutoContinueAgreement(true, true, loading = false, cached = false, error = null, networkAllowed = true))
        assertFalse(PublicationJson.shouldAutoContinueAgreement(true, true, loading = false, cached = true, error = null, networkAllowed = true))
        assertFalse(PublicationJson.shouldAutoContinueAgreement(true, true, loading = false, cached = false, error = "NETWORK", networkAllowed = true))
        assertFalse(PublicationJson.shouldAutoContinueAgreement(true, false, loading = false, cached = false, error = null, networkAllowed = true))
        assertFalse(PublicationJson.shouldAutoContinueAgreement(true, true, loading = true, cached = false, error = null, networkAllowed = true))
        assertFalse(PublicationJson.shouldAutoContinueAgreement(true, true, loading = false, cached = false, error = null, networkAllowed = false))
        assertFalse(PublicationJson.shouldAutoContinueAgreement(false, true, loading = false, cached = false, error = null, networkAllowed = true))
    }

    @Test fun agreementRequiresBothCurrentVersionsAndDigests() {
        val privacy = LegalDocument("privacy", "zh-CN", 2, "privacy text", "privacy summary", digest)
        val terms = LegalDocument("terms", "zh-CN", 7, "terms text", "terms summary", cert)
        val savedPrivacy = StoredConsent(2, digest)
        val savedTerms = StoredConsent(7, cert)
        assertFalse(PublicationJson.needsAgreement(savedPrivacy, savedTerms, privacy, terms, true))
        assertTrue(PublicationJson.needsAgreement(savedPrivacy, savedTerms.copy(version = 6), privacy, terms, true))
        assertTrue(PublicationJson.needsAgreement(savedPrivacy, savedTerms.copy(contentDigest = digest), privacy, terms, true))
        assertTrue(PublicationJson.needsAgreement(savedPrivacy, savedTerms, privacy.copy(version = 3), terms, true))
        assertTrue(PublicationJson.needsAgreement(savedPrivacy, savedTerms, null, terms, false))
        assertTrue(PublicationJson.needsAgreement(savedPrivacy, savedTerms, null, terms, true))
    }
}
