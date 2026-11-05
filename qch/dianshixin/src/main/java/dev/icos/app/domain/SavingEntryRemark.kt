package dev.icos.app.domain

import dev.icos.app.data.local.FinanceValidationException

/**
 * New-entry boundary only (addSavingEntry). Replay and snapshot import keep stored remarks
 * verbatim, so they must not call this.
 */
internal fun normalizeNewSavingEntryRemark(raw: String?): String? {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return null
    if (text.codePointCount(0, text.length) > 1000) throw FinanceValidationException("备注不能超过一千个字符")
    if ('\u0000' in text) throw FinanceValidationException("备注包含无效字符")
    val utf8 = text.toByteArray(Charsets.UTF_8)
    if (String(utf8, Charsets.UTF_8) != text) throw FinanceValidationException("备注包含无效字符")
    return text
}
