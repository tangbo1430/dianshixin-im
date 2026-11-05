package dev.icos.app.data.remote

internal object StrictJson {
    fun parseObject(text: String): Map<String, Any?> {
        val parser = Parser(text)
        val value = parser.value(0)
        parser.whitespace()
        require(parser.atEnd())
        return (value as? Map<*, *>)?.entries?.associate { (k, v) -> (k as? String ?: error("key")) to v } ?: error("object required")
    }

    fun encodedSize(value: Any?): Int = encode(value).toByteArray(Charsets.UTF_8).size

    fun encode(value: Any?): String = when (value) {
        null -> "null"
        is String -> buildString {
            append('"')
            value.forEach { c -> when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
            } }
            append('"')
        }
        is Boolean -> value.toString()
        is Byte, is Short, is Int, is Long -> value.toString()
        is Double -> value.toString().also { require(value.isFinite()) }
        is Map<*, *> -> value.entries.joinToString(prefix = "{", postfix = "}") { (key, item) ->
            require(key is String)
            "${encode(key)}:${encode(item)}"
        }
        is List<*> -> value.joinToString(prefix = "[", postfix = "]") { encode(it) }
        else -> error("unsupported JSON value")
    }

    private class Parser(private val source: String) {
        private var index = 0
        fun atEnd() = index == source.length
        fun whitespace() { while (index < source.length && source[index] in " \n\r\t") index++ }
        fun value(depth: Int): Any? {
            require(depth <= 32); whitespace(); require(index < source.length)
            return when (source[index]) {
                '{' -> objectValue(depth + 1)
                '[' -> arrayValue(depth + 1)
                '"' -> stringValue()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                '-', in '0'..'9' -> numberValue()
                else -> error("invalid JSON value")
            }
        }
        private fun objectValue(depth: Int): Map<String, Any?> {
            index++; whitespace(); val result = linkedMapOf<String, Any?>()
            if (consume('}')) return result
            while (true) {
                whitespace(); require(index < source.length && source[index] == '"')
                val key = stringValue(); whitespace(); require(consume(':')); require(!result.containsKey(key))
                result[key] = value(depth); whitespace()
                if (consume('}')) return result
                require(consume(','))
            }
        }
        private fun arrayValue(depth: Int): List<Any?> {
            index++; whitespace(); val result = mutableListOf<Any?>()
            if (consume(']')) return result
            while (true) { result += value(depth); whitespace(); if (consume(']')) return result; require(consume(',')) }
        }
        private fun stringValue(): String {
            require(consume('"')); val out = StringBuilder()
            while (index < source.length) {
                val c = source[index++]
                when {
                    c == '"' -> return out.toString()
                    c == '\\' -> {
                        require(index < source.length)
                        when (val e = source[index++]) {
                            '"', '\\', '/' -> out.append(e)
                            'b' -> out.append('\b'); 'f' -> out.append('\u000c'); 'n' -> out.append('\n')
                            'r' -> out.append('\r'); 't' -> out.append('\t')
                            'u' -> { require(index + 4 <= source.length); val n = source.substring(index, index + 4).toInt(16); index += 4; out.append(n.toChar()) }
                            else -> error("invalid escape")
                        }
                    }
                    c.code < 0x20 -> error("control in string")
                    else -> out.append(c)
                }
            }
            error("unterminated string")
        }
        private fun numberValue(): Number {
            val start = index
            if (source[index] == '-') index++
            require(index < source.length)
            if (source[index] == '0') index++ else { require(source[index] in '1'..'9'); while (index < source.length && source[index] in '0'..'9') index++ }
            if (index < source.length && source[index] == '.') { index++; require(index < source.length && source[index] in '0'..'9'); while (index < source.length && source[index] in '0'..'9') index++ }
            if (index < source.length && source[index] in "eE") { index++; if (index < source.length && source[index] in "+-") index++; require(index < source.length && source[index] in '0'..'9'); while (index < source.length && source[index] in '0'..'9') index++ }
            val token = source.substring(start, index)
            return token.toLongOrNull() ?: token.toDouble().also { require(it.isFinite()) }
        }
        private fun <T> literal(token: String, result: T): T { require(source.regionMatches(index, token, 0, token.length)); index += token.length; return result }
        private fun consume(expected: Char): Boolean { if (index >= source.length || source[index] != expected) return false; index++; return true }
    }
}
