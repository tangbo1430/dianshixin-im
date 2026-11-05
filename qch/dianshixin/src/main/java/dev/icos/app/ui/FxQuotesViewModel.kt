package dev.icos.app.ui

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import dev.icos.app.data.remote.AuthClient
import dev.icos.app.data.remote.DeviceIdentity
import dev.icos.app.data.remote.DeviceSessions
import dev.icos.app.data.remote.FxFetchResult
import dev.icos.app.data.remote.FxQuoteClient
import dev.icos.app.data.remote.FxSnapshot
import dev.icos.app.domain.Money
import dev.icos.app.domain.ValuationQuote
import org.json.JSONObject
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors

internal data class AutomaticFxQuote(val quote: ValuationQuote, val provider: String, val asOf: String, val stale: Boolean)
internal data class FxQuotesState(
    val owner: String? = null,
    val session: String? = null,
    val accountMode: Boolean = false,
    val base: String = "CNY",
    val quotes: Map<String, AutomaticFxQuote> = emptyMap(),
    val busy: Boolean = false,
    val error: String? = null,
)

internal class FxQuotesViewModel(application: Application) : AndroidViewModel(application) {
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val client = FxQuoteClient(application)
    private val auth = AuthClient(application)
    private var generation = 0L
    private var boundKey: String? = null
    private var requestKey: String? = null
    @Volatile private var closed = false
    var state by mutableStateOf(FxQuotesState())
        private set

    fun sessionChanged(ownerId: UUID?, identity: DeviceIdentity?) {
        if (ownerId == null) {
            generation++
            boundKey = null
            requestKey = null
            state = FxQuotesState()
            return
        }
        bind(ownerId, state.base, identity)
    }

    fun bind(ownerId: UUID, base: String, sessionIdentity: DeviceIdentity?) {
        val owner = ownerId.toString()
        val session = sessionIdentity?.let { "${it.owner}:${it.sessionId}" }
        val accountMode = sessionIdentity != null
        val key = "$owner|$session|$base"
        if (boundKey == key) return
        boundKey = key
        generation++
        requestKey = null
        val captured = generation
        executor.execute {
            val cached = runCatching { load(owner, base) }.getOrDefault(emptyMap())
            main.post {
                if (!closed && generation == captured && boundKey == key)
                    state = FxQuotesState(owner = owner, session = session, accountMode = accountMode, base = base, quotes = cached)
            }
        }
    }


    fun refresh(ownerId: UUID, base: String, balancesCurrencies: Set<String>, identity: DeviceIdentity?) {
        if (state.accountMode && identity == null) {
            state = state.copy(busy = false, error = "账号公开报价缺少当前会话身份；请重新载入账号状态。")
            return
        }
        val owner = ownerId.toString()
        val session = identity?.let { "${it.owner}:${it.sessionId}" }
        val key = "$owner|$session|$base"
        if (boundKey != key) bind(ownerId, base, identity)
        if (identity != null && identity.owner != ownerId) return
        val sources = balancesCurrencies.filter { it != base }.distinct().sorted().take(32)
        if (sources.isEmpty()) {
            state = state.copy(busy = false, error = null)
            return
        }
        val request = "$key|${sources.joinToString(",")}"
        if (requestKey == request && state.busy) return
        requestKey = request
        val captured = generation
        state = state.copy(owner = owner, session = session, accountMode = identity != null, base = base, busy = true, error = null)
        executor.execute {
            val updated = load(owner, base).toMutableMap()
            var error: String? = null
            var sessionValid = identity == null || identityStillMatches(identity)
            if (sessionValid) for (source in sources) {
                if (closed || generation != captured) return@execute
                val result = client.fetch(source, base)
                if (closed || generation != captured) return@execute
                when (result) {
                    is FxFetchResult.Available -> {
                        val rate = result.snapshot.rates[base]
                        if (rate != null) {
                            val capturedAt = runCatching { Instant.parse(result.snapshot.asOf).toEpochMilli() }.getOrDefault(0L)
                            if (capturedAt > 0) {
                                val quote = ValuationQuote(source, base, rate, capturedAt)
                                updated[source] = AutomaticFxQuote(quote, result.snapshot.provider, result.snapshot.asOf, result.snapshot.stale)
                            } else error = "报价时间无效；保留原有缓存。"
                        } else {
                            updated[source]?.let { updated[source] = it.copy(stale = true) }
                            error = "服务器未提供 ${source} → $base 报价；保留原有缓存并标记为过期。"
                        }
                    }
                    is FxFetchResult.Unavailable -> {
                        updated[source]?.let { updated[source] = it.copy(stale = true) }
                        error = "${source} → $base 报价暂不可用；保留原有缓存并标记为过期。"
                    }
                }
                sessionValid = identity == null || identityStillMatches(identity)
                if (!sessionValid) break
            }
            if (sessionValid && generation == captured && !closed) runCatching { save(owner, base, updated) }
            main.post {
                if (!closed && generation == captured && boundKey == key && sessionValid) {
                    state = state.copy(quotes = updated.toMap(), busy = false, error = error)
                    requestKey = null
                } else if (!closed && generation == captured) {
                    state = state.copy(busy = false, error = "账号会话已变化；已丢弃此次报价。")
                    requestKey = null
                }
            }
        }
    }

    private fun identityStillMatches(expected: DeviceIdentity): Boolean = runCatching {
        DeviceSessions.identity(auth.snapshot()) == expected
    }.getOrDefault(false)

    private fun prefs(owner: String) = getApplication<Application>()
        .getSharedPreferences("asset-valuation-$owner", Context.MODE_PRIVATE)

    private fun load(owner: String, base: String): Map<String, AutomaticFxQuote> {
        val p = prefs(owner)
        return p.all.keys.filter { it.startsWith("auto:$base:") }.sorted().take(32).mapNotNull { key ->
            runCatching {
                val source = key.substringAfterLast(':')
                val json = JSONObject(checkNotNull(p.getString(key, null)))
                val quote = ValuationQuote(source, base, json.getString("rate"), json.getLong("capturedAt"))
                source to AutomaticFxQuote(quote, json.getString("provider"), json.getString("asOf"), true)
            }.getOrNull()
        }.toMap()
    }

    private fun save(owner: String, base: String, quotes: Map<String, AutomaticFxQuote>) {
        Money(0, 0, base)
        val p = prefs(owner)
        val editor = p.edit()
        quotes.forEach { (source, auto) ->
            val key = "auto:$base:$source"
            editor.putString(key, JSONObject().put("rate", auto.quote.rate).put("capturedAt", auto.quote.capturedAtMillis)
                .put("provider", auto.provider).put("asOf", auto.asOf).put("stale", auto.stale).toString())
        }
        check(editor.commit()) { "Unable to save owner-scoped automatic FX quotes" }
    }

    override fun onCleared() {
        closed = true
        generation++
        executor.shutdownNow()
        super.onCleared()
    }
}
