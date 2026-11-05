package dev.icos.app.data.remote

import android.content.Context
import android.os.Looper
import dev.icos.app.BuildConfig
import dev.icos.app.domain.Money
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale
import javax.net.ssl.HttpsURLConnection

internal data class FxSnapshot(val base: String, val rates: Map<String, String>, val asOf: String, val provider: String, val stale: Boolean)
internal sealed interface FxFetchResult {
    data class Available(val snapshot: FxSnapshot) : FxFetchResult
    data class Unavailable(val code: String) : FxFetchResult
}

internal object FxQuoteJson {
    const val MAX_BYTES = 64 * 1024
    private val currency = Regex("[A-Z]{3}")

    fun parse(bytes: ByteArray, expectedBase: String, requested: Set<String>): FxSnapshot {
        require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES)
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        val envelope = StrictJson.parseObject(text)
        require(envelope.keys == setOf("data", "requestId"))
        val requestId = envelope["requestId"] as? String ?: error("requestId string required")
        java.util.UUID.fromString(requestId)
        val data = envelope["data"] as? Map<*, *> ?: error("data object required")
        require(data.keys == setOf("base", "rates", "asOf", "provider", "stale"))
        val base = data["base"] as? String ?: error("base string required")
        Money(0, 0, base)
        require(base == expectedBase && currency.matches(base) && requested.size in 1..32)
        val rawRates = data["rates"] as? Map<*, *> ?: error("rates object required")
        val rates = linkedMapOf<String, String>()
        rawRates.forEach { (rawKey, rawValue) ->
            val symbol = rawKey as? String ?: error("currency key required")
            require(currency.matches(symbol) && symbol != base && symbol in requested)
            val rate = rawValue as? String ?: error("rate string required")
            require(rate.length in 1..32 && rate.matches(Regex("[0-9]+(?:\\.[0-9]{1,18})?")))
            val decimal = java.math.BigDecimal(rate)
            require(decimal > java.math.BigDecimal.ZERO && decimal <= java.math.BigDecimal("1000000000000"))
            rates[symbol] = rate
        }
        val asOf = data["asOf"] as? String ?: error("asOf string required")
        require(asOf.length in 1..64 && asOf.matches(Regex("\\d{4}-\\d{2}-\\d{2}T.*(?:Z|[+-]\\d{2}:\\d{2})")))
        java.time.Instant.parse(asOf.replace(Regex("\\+00:00$"), "Z"))
        val provider = data["provider"] as? String ?: error("provider string required")
        require(provider.length in 1..64 && provider.all { it.isLetterOrDigit() || it in "._-" })
        val stale = data["stale"] as? Boolean ?: error("stale boolean required")
        return FxSnapshot(base, rates, asOf, provider, stale)
    }
}

internal class FxQuoteClient(context: Context) {
    private val app = context.applicationContext
    private val origin = BuildConfig.AUTH_API_ORIGIN.removeSuffix("/")

    fun fetch(source: String, target: String): FxFetchResult {
        check(Looper.myLooper() != Looper.getMainLooper()) { "FX quotes must be fetched off the main thread" }
        if (!ConsentRecord.hasNetworkConsent(app)) return FxFetchResult.Unavailable("CONSENT_REQUIRED")
        val from = source.uppercase(Locale.ROOT)
        val to = target.uppercase(Locale.ROOT)
        return try {
            Money(0, 0, from); Money(0, 0, to)
            require(from != to && from.matches(Regex("[A-Z]{3}")) && to.matches(Regex("[A-Z]{3}")))
            val baseUri = URI(origin)
            require(baseUri.scheme == "https" && baseUri.host != null && baseUri.rawUserInfo == null && baseUri.rawQuery == null &&
                baseUri.rawFragment == null && baseUri.rawPath.orEmpty().isEmpty())
            val connection = URL("$origin/v1/exchange-rates?base=$from&symbols%5B%5D=$to").openConnection() as HttpsURLConnection
            try {
                connection.requestMethod = "GET"
                connection.connectTimeout = 8_000
                connection.readTimeout = 8_000
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("Cache-Control", "no-cache")
                val status = connection.responseCode
                if (status !in 200..299) return FxFetchResult.Unavailable(if (status == 503) "EXCHANGE_RATES_UNAVAILABLE" else "NETWORK")
                require(connection.contentType.orEmpty().substringBefore(';').trim().equals("application/json", true))
                val output = ByteArrayOutputStream()
                connection.inputStream.use { stream ->
                    val buffer = ByteArray(2048)
                    while (true) {
                        val read = stream.read(buffer)
                        if (read < 0) break
                        require(output.size() + read <= FxQuoteJson.MAX_BYTES)
                        output.write(buffer, 0, read)
                    }
                }
                val snapshot = FxQuoteJson.parse(output.toByteArray(), from, setOf(to))
                if (to !in snapshot.rates) FxFetchResult.Unavailable("QUOTE_MISSING")
                else FxFetchResult.Available(snapshot.copy(rates = mapOf(to to snapshot.rates.getValue(to))))
            } finally { connection.disconnect() }
        } catch (_: Exception) { FxFetchResult.Unavailable("UNAVAILABLE") }
    }
}
