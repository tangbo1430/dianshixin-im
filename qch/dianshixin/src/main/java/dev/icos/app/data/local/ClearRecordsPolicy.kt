package dev.icos.app.data.local

/** Fail-closed eligibility policy for guest record clearing. */
internal object ClearRecordsPolicy {
    data class Candidate(
        val kind: String,
        val hasChildEvents: Boolean,
        val hasReversal: Boolean,
        val hasImport: Boolean,
        val hasBusinessDependency: Boolean,
    )
    data class Decision(val supported: Boolean, val reason: String?)
    data class Preview(
        val ledgerName: String,
        val recordCount: Int,
        val supportedCount: Int,
        val rejectedReasons: List<String>,
        val recordRevisions: Map<java.util.UUID, Long> = emptyMap(),
    )

    fun evaluate(candidate: Candidate): Decision {
        val reason = when {
            candidate.kind !in setOf("EXPENSE", "INCOME") -> "仅支持普通支出和收入"
            candidate.hasChildEvents -> "账单有子事件或特殊关联"
            candidate.hasReversal -> "账单已有冲正记录"
            candidate.hasImport -> "导入账单暂不支持清账"
            candidate.hasBusinessDependency -> "账单关联报销、退款、存钱或贷款业务"
            else -> null
        }
        return Decision(reason == null, reason)
    }
}
