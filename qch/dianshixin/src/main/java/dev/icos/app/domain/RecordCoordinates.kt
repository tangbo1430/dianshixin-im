package dev.icos.app.domain

import java.math.BigDecimal

object RecordCoordinates {
    fun validate(latitude: String, longitude: String): Pair<String, String> {
        fun normalize(raw: String, min: BigDecimal, max: BigDecimal, name: String): String {
            val text = raw.trim()
            require(text.isNotEmpty() && text.length <= 64) { "$name 不能为空且不能超过 64 个字符" }
            val value = runCatching { BigDecimal(text) }.getOrElse { throw IllegalArgumentException("$name 必须是十进制数", it) }
            require(value.scale() in -18..18) { "$name 小数精度过高" }
            require(value >= min && value <= max) { "$name 超出有效范围" }
            return value.stripTrailingZeros().toPlainString().let { if (it == "-0") "0" else it }
        }
        return normalize(latitude, BigDecimal("-90"), BigDecimal("90"), "纬度") to
            normalize(longitude, BigDecimal("-180"), BigDecimal("180"), "经度")
    }
}
