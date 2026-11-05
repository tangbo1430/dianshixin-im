package dev.icos.app.data.remote

import dev.icos.app.domain.AccountAmount
import dev.icos.app.domain.Money
import dev.icos.app.domain.RecordException
import dev.icos.app.domain.RecordInput
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

// Pure calculation input; owner/epoch and account existence are validated by the persistence boundary.
object RecordInputJson {
    fun decode(value: JSONObject): RecordInput =
        when (value.string("kind")) {
            "EXPENSE" -> {
                value.fields("kind", "gross", "discount", "allocations", "countInStatistics", "countInBudget")
                RecordInput.Expense(
                    money(value.objectValue("gross")),
                    value.optionalObject("discount")?.let(::money),
                    allocations(value, allowRecordCurrencyAmount = true),
                    value.boolean("countInStatistics"),
                    value.boolean("countInBudget"),
                    allocationRecordAmounts(value),
                )
            }
            "INCOME" -> {
                value.fields("kind", "amount", "allocations", "countInStatistics")
                RecordInput.Income(
                    money(value.objectValue("amount")),
                    allocations(value, allowRecordCurrencyAmount = false),
                    value.boolean("countInStatistics"),
                )
            }
            "TRANSFER" -> {
                value.fields("kind", "from", "to", "fee", "bonus")
                RecordInput.Transfer(
                    accountAmount(value.objectValue("from")),
                    accountAmount(value.objectValue("to")),
                    value.optionalObject("fee")?.let(::accountAmount),
                    value.optionalObject("bonus")?.let(::accountAmount),
                )
            }
            else -> throw RecordException()
        }

    private fun money(value: JSONObject): Money {
        value.fields("unscaled", "scale", "currency")
        val scale = when (val raw = value.opt("scale")) {
            is Int -> raw.toLong()
            is Long -> raw
            else -> throw RecordException()
        }
        if (scale !in 0..255) throw RecordException()
        return Money.fromWire(value.string("unscaled"), scale.toInt(), value.string("currency"))
    }

    private fun accountAmount(value: JSONObject, allowRecordCurrencyAmount: Boolean = false): AccountAmount {
        if (allowRecordCurrencyAmount) value.fields("accountId", "amount", "recordCurrencyAmount")
        else value.fields("accountId", "amount")
        val raw = value.string("accountId")
        val simple = when {
            raw.length == 45 && raw.startsWith("urn:uuid:") -> raw.substring(9)
            raw.length == 38 && raw.startsWith("{") && raw.endsWith("}") -> raw.substring(1, raw.length - 1)
            raw.length == 32 || raw.length == 36 -> raw
            else -> throw RecordException()
        }
        val canonical = when {
            simple.matches(Regex("[0-9a-fA-F]{32}")) ->
                "${simple.substring(0, 8)}-${simple.substring(8, 12)}-${simple.substring(12, 16)}-${simple.substring(16, 20)}-${simple.substring(20)}"
            simple.matches(Regex("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")) -> simple
            else -> throw RecordException()
        }
        return AccountAmount(UUID.fromString(canonical), money(value.objectValue("amount")))
    }

    private fun allocations(value: JSONObject, allowRecordCurrencyAmount: Boolean): List<AccountAmount> {
        val array = value.opt("allocations") as? JSONArray ?: throw RecordException()
        return List(array.length()) { index ->
            val allocation = array.opt(index) as? JSONObject ?: throw RecordException()
            if (allowRecordCurrencyAmount) allocation.fields("accountId", "amount", "recordCurrencyAmount")
            else allocation.fields("accountId", "amount")
            accountAmount(allocation, allowRecordCurrencyAmount = allowRecordCurrencyAmount)
        }
    }

    private fun allocationRecordAmounts(value: JSONObject): List<Money>? {
        val array = value.opt("allocations") as? JSONArray ?: throw RecordException()
        val amounts = ArrayList<Money>(array.length())
        repeat(array.length()) { index ->
            val allocation = array.opt(index) as? JSONObject ?: throw RecordException()
            allocation.fields("accountId", "amount", "recordCurrencyAmount")
            val accountAmount = money(allocation.objectValue("amount"))
            val recordAmount = when (val raw = allocation.opt("recordCurrencyAmount")) {
                null, JSONObject.NULL -> accountAmount
                is JSONObject -> money(raw)
                else -> throw RecordException()
            }
            if (allocation.has("recordCurrencyAmount") && allocation.isNull("recordCurrencyAmount")) throw RecordException()
            if (allocation.has("recordCurrencyAmount") && accountAmount.currency == recordAmount.currency) throw RecordException()
            amounts += recordAmount
        }
        return amounts.takeIf { (0 until array.length()).any { index -> array.getJSONObject(index).has("recordCurrencyAmount") } }
    }

    private fun JSONObject.fields(vararg allowed: String) {
        if (keys().asSequence().any { it !in allowed }) throw RecordException()
    }

    private fun JSONObject.string(name: String): String = opt(name) as? String ?: throw RecordException()
    private fun JSONObject.boolean(name: String): Boolean = opt(name) as? Boolean ?: throw RecordException()
    private fun JSONObject.objectValue(name: String): JSONObject = opt(name) as? JSONObject ?: throw RecordException()
    private fun JSONObject.optionalObject(name: String): JSONObject? = when (val value = opt(name)) {
        null, JSONObject.NULL -> null
        is JSONObject -> value
        else -> throw RecordException()
    }
}
