package dev.icos.app.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.icos.app.data.local.FinanceSnapshot
import dev.icos.app.data.remote.DeviceIdentity
import dev.icos.app.domain.FxValuation
import dev.icos.app.domain.Money
import dev.icos.app.domain.ValuationBalance
import dev.icos.app.domain.ValuationQuote
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Currency
import java.util.Locale

private data class CurrencyOption(
    val code: String,
    val name: String,
    val symbol: String,
) {
    val searchText: String get() = "$code $name $symbol"
}

private class FxPreferences(
    context: Context,
    owner: String,
) {
    private val preferences = context.getSharedPreferences("asset-valuation-$owner", Context.MODE_PRIVATE)

    fun base(): String = preferences.getString("base", "CNY").orEmpty().takeIf { runCatching { Money(0, 0, it) }.isSuccess } ?: "CNY"

    fun quotes(base: String): List<ValuationQuote> =
        preferences.all.keys
            .filter { it.startsWith("rate:$base:") }
            .sorted()
            .mapNotNull { key ->
                runCatching {
                    ValuationQuote(
                        key.substringAfterLast(':'),
                        base,
                        checkNotNull(preferences.getString(key, null)),
                        preferences.getLong("time:$key", 0),
                    )
                }.getOrNull()
            }.take(32)

    fun save(
        base: String,
        quote: ValuationQuote?,
    ): Boolean {
        Money(0, 0, base)
        val editor = preferences.edit().putString("base", base)
        quote?.let {
            val key = "rate:$base:${it.sourceCurrency}"
            require(preferences.contains(key) || quotes(base).size < 32)
            editor.putString(key, it.rate).putLong("time:$key", it.capturedAtMillis)
        }
        return editor.commit()
    }

    fun delete(
        base: String,
        source: String,
    ): Boolean {
        val key = "rate:$base:$source"
        return preferences
            .edit()
            .remove(key)
            .remove("time:$key")
            .commit()
    }
}

@Composable
internal fun FxSettingsPanel(
    snapshot: FinanceSnapshot,
    model: FxQuotesViewModel,
    sessionIdentity: DeviceIdentity?,
) {
    val context = LocalContext.current
    val owner = snapshot.ownerId
    val store = remember(owner) { FxPreferences(context.applicationContext, owner.toString()) }
    var base by remember(owner) { mutableStateOf(store.base()) }
    var quotes by remember(owner, base) { mutableStateOf(store.quotes(base)) }
    var baseInput by remember(owner) { mutableStateOf(base) }
    var source by remember(owner) { mutableStateOf("") }
    var rate by remember(owner) { mutableStateOf("") }
    var error by remember(owner) { mutableStateOf<String?>(null) }
    var showCurrencyPicker by remember(owner) { mutableStateOf(false) }
    var currencyQuery by remember(owner) { mutableStateOf("") }
    val currencyOptions =
        remember {
            Money.supportedCurrencies.sorted().map { code ->
                val currency = runCatching { Currency.getInstance(code) }.getOrNull()
                CurrencyOption(
                    code = code,
                    name = currency?.getDisplayName(Locale.SIMPLIFIED_CHINESE) ?: code,
                    symbol = currency?.getSymbol(Locale.SIMPLIFIED_CHINESE).orEmpty(),
                )
            }
        }
    val filteredCurrencies =
        currencyOptions
            .filter {
                currencyQuery.isBlank() ||
                    it.searchText.contains(currencyQuery.trim(), ignoreCase = true)
            }.sortedBy { if (it.code == baseInput) "" else it.code }
    val selectedCurrency = currencyOptions.firstOrNull { it.code == baseInput }
    val automatic =
        model.state
            .takeIf { it.owner == owner.toString() && it.base == base }
            ?.quotes
            .orEmpty()
    val balances =
        snapshot.accounts.filter { it.type == "LIABILITY" || it.includeInAssets }.map {
            ValuationBalance(
                it.balance,
                it.type == "LIABILITY",
            )
        }
    val valuation = remember(balances, base, quotes) { FxValuation.calculate(balances, base, quotes) }
    LaunchedEffect(owner, base, sessionIdentity?.sessionId) { model.bind(owner, base, sessionIdentity) }
    val currencies = balances.map { it.amount.currency }.filter { it != base }.toSet()

    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("应用语言", Modifier.weight(1f))
                    Text("简体中文", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("当前版本仅提供简体中文。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("默认货币", style = MaterialTheme.typography.titleMedium)
                Text("记账、统计和报表使用的默认货币。更改不会修改已有原币余额或账单。", style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("本位币")
                        Text(
                            "${selectedCurrency?.name ?: baseInput}（${selectedCurrency?.code ?: baseInput}） ${selectedCurrency?.symbol.orEmpty()}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    TextButton(onClick = {
                        currencyQuery = ""
                        showCurrencyPicker = true
                    }) { Text("选择货币") }
                }
                Button(onClick = {
                    error =
                        runCatching {
                            val value = baseInput.trim().uppercase(Locale.ROOT)
                            check(store.save(value, null))
                            base = value
                            quotes = store.quotes(base)
                        }.exceptionOrNull()?.let { "无法保存本位币，请检查币种代码。" }
                }) { Text("保存本位币") }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("资产估值", style = MaterialTheme.typography.titleMedium)
                when {
                    valuation.missingCurrencies.isNotEmpty() -> {
                        Text(
                            "总额尚未完整换算：缺少 ${valuation.missingCurrencies.joinToString("、")} → $base 汇率",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    valuation.overflow -> {
                        Text("估值超出金额表示范围，请检查汇率和余额。", color = MaterialTheme.colorScheme.error)
                    }

                    else -> {
                        Text("资产 ${display(checkNotNull(valuation.assets))}")
                        Text("负债 ${display(checkNotNull(valuation.liabilities))}")
                        Text("净资产 ${display(checkNotNull(valuation.net))}")
                    }
                }
                Text(
                    "不同币种分别展示，不同货币的收支和资产将单独统计，不会合并计算。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("汇率参考", style = MaterialTheme.typography.titleMedium)
                Text("手工汇率用于资产估值；公开报价只供参考，需明确选择后才用于估值。", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(source, {
                        if (it.length <=
                            3
                        ) {
                            source = it.uppercase(Locale.ROOT)
                        }
                    }, modifier = Modifier.weight(1f), label = { Text("原币") }, singleLine = true)
                    OutlinedTextField(rate, {
                        if (it.length <=
                            32
                        ) {
                            rate = it
                        }
                    }, modifier = Modifier.weight(2f), label = { Text("1 原币 = 多少 $base") }, singleLine = true)
                }
                Button(onClick = {
                    error =
                        runCatching {
                            val quote = ValuationQuote(source.trim().uppercase(Locale.ROOT), base, rate.trim(), System.currentTimeMillis())
                            check(store.save(base, quote))
                            quotes = store.quotes(base)
                            source = ""
                            rate = ""
                        }.exceptionOrNull()?.let { "无法保存汇率：请填写不同的有效币种与正数汇率，最多保留 32 个币种。" }
                }) { Text("保存手工汇率") }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !model.state.busy, onClick = {
                        model.refresh(owner, base, currencies, sessionIdentity)
                    }) { Text(if (model.state.busy) "正在读取公开报价…" else "刷新公开报价") }
                    Text("最多 ${currencies.size.coerceAtMost(32)} 个余额币种；逐币种读取。", style = MaterialTheme.typography.bodySmall)
                }
                model.state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                automatic.values.sortedBy { it.quote.sourceCurrency }.forEach { auto ->
                    val quote = auto.quote
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("1 ${quote.sourceCurrency} = ${quote.rate} $base · ${auto.provider}${if (auto.stale) " · 过期" else " · 最新快照"}")
                        Text("asOf ${auto.asOf} · 未自动用于估值", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = {
                            error =
                                runCatching {
                                    check(store.save(base, quote))
                                    quotes = store.quotes(base)
                                }.exceptionOrNull()?.let { "无法将报价保存为手工估值汇率。" }
                        }) { Text("${quote.sourceCurrency} → $base 用于估值") }
                    }
                }
                if (quotes.isEmpty()) Text("尚未设置手工汇率；非本位币余额继续显示为缺少汇率，不使用零值。")
                quotes.forEach { quote ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val automaticQuote = automatic[quote.sourceCurrency]
                        Text(
                            "1 ${quote.sourceCurrency} = ${quote.rate} $base · 手工估值${if (automaticQuote != null && automaticQuote.quote.rate == quote.rate && automaticQuote.stale) " · 自动来源已过期" else ""}",
                        )
                        Text(
                            "取值时间 ${DateTimeFormatter.ofPattern(
                                "yyyy-MM-dd HH:mm",
                            ).withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(quote.capturedAtMillis))}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = {
                            if (store.delete(base, quote.sourceCurrency)) {
                                quotes = store.quotes(base)
                            } else {
                                error =
                                    "无法移除汇率。"
                            }
                        }) { Text("移除 ${quote.sourceCurrency} 汇率") }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
    }

    if (showCurrencyPicker) {
        AlertDialog(
            onDismissRequest = { showCurrencyPicker = false },
            title = { Text("选择本位币") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        currencyQuery,
                        { currencyQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("搜索币种名称或代码") },
                        singleLine = true,
                    )
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(filteredCurrencies, key = { it.code }) { option ->
                            TextButton(
                                onClick = {
                                    baseInput = option.code
                                    currencyQuery = ""
                                    showCurrencyPicker = false
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("${option.code} · ${option.name}")
                                        Text(
                                            option.symbol,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    if (option.code == baseInput) Text("✓", color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                    if (filteredCurrencies.isEmpty()) Text("没有匹配的货币。", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { showCurrencyPicker = false }) { Text("关闭") } },
        )
    }
}
