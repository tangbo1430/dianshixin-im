package dev.icos.app.fileio

import java.security.MessageDigest
import java.util.Locale

data class SimilarityHint(val row: Int, val existingRecordId: String, val reason: String)

/** Similarity is a user prompt only; only externalId is used for definite business de-duplication. */
object ImportDuplicateHints {
    fun find(rows: List<ImportCandidate>, existing: Sequence<ImportCandidate>, threshold: Int = 3): List<SimilarityHint> {
        require(threshold in 1..5)
        val index = existing.take(200_000).map { record ->
            val key = listOf(record.occurredAtMillis?.toString().orEmpty(), record.kind.orEmpty(), record.amount?.unscaled?.toString().orEmpty(),
                record.amount?.scale?.toString().orEmpty(), record.amount?.currency.orEmpty(), record.remark.trim().lowercase(Locale.ROOT),
                record.primaryCategory, record.account1, record.account2).joinToString("\u0000")
            MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) } to record
        }.toList().groupBy({ it.first }, { it.second })
        return rows.mapNotNull { row ->
            val key = listOf(row.occurredAtMillis?.toString().orEmpty(), row.kind.orEmpty(), row.amount?.unscaled?.toString().orEmpty(),
                row.amount?.scale?.toString().orEmpty(), row.amount?.currency.orEmpty(), row.remark.trim().lowercase(Locale.ROOT),
                row.primaryCategory, row.account1, row.account2).joinToString("\u0000")
            val matches = index[MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }].orEmpty()
            if (matches.size >= threshold && row.externalId.isNullOrBlank()) SimilarityHint(row.sourceRow, matches.first().externalId ?: "", "SIMILAR_RECORD_REVIEW") else null
        }
    }
}
