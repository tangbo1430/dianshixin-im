package dev.icos.app.data.local

import org.json.JSONObject

internal fun JSONObject.canonicalBoolean(key: String, fallback: Boolean = false): Boolean = when (val value = opt(key)) {
    true, 1, 1L, "true", "1" -> true
    false, 0, 0L, "false", "0" -> false
    null, JSONObject.NULL -> fallback
    else -> error("Invalid canonical boolean: $key")
}
