package dev.icos.app.data.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.icos.app.domain.Money
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class FileImportBatchTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun failedBatchRollsBackFinancialFactsAndBatchState() {
        val owner = UUID.randomUUID()
        val epoch = UUID.randomUUID()
        val directory = EncryptedDatasetStore.directory(context, owner, epoch)
        try {
            LocalFinanceRepository.openDataset(context, owner, epoch).use { repo ->
                val account = repo.createAccount("batch", "CNY", Money(500, 0, "CNY"), 1768449600000L)
                val amount = JSONObject().put("unscaled", "10").put("scale", 0).put("currency", "CNY")
                val input = JSONObject().put("kind", "EXPENSE").put("gross", amount).put("discount", JSONObject.NULL)
                    .put("allocations", JSONArray().put(JSONObject().put("accountId", account.toString()).put("amount", amount)))
                    .put("countInStatistics", true).put("countInBudget", true)
                val goodId = UUID.randomUUID()
                val badId = UUID.randomUUID()
                fun payload(id: UUID, accountId: String) = JSONObject().put("recordId", id.toString()).put("input", JSONObject(input.toString())
                    .put("allocations", JSONArray().put(JSONObject().put("accountId", accountId).put("amount", amount))))
                    .put("occurredAtMillis", 1768449600000L).put("timezone", "UTC").put("remark", "")
                    .put("categoryId", JSONObject.NULL).put("tagIds", JSONArray()).toString()
                val digest = "c".repeat(64)
                val batchId = repo.stageFileImport(digest, listOf(
                    FileImportRowEntity("pending", 1, payload(goodId, account.toString()), "a".repeat(64), null, null, "PREVIEWED"),
                    FileImportRowEntity("pending", 2, payload(badId, UUID.randomUUID().toString()), "b".repeat(64), null, null, "PREVIEWED"),
                ))
                val before = repo.snapshot()
                assertThrows(FinanceValidationException::class.java) { repo.confirmFileImport(batchId, digest) }
                assertEquals(before, repo.snapshot())
                assertEquals("PREVIEWED", repo.fileImportBatches().single().first.state)
                val db = EncryptedDatasetStore.open(context, owner, epoch)
                try { assertTrue(db.fileImports().rows(batchId.toString()).all { it.state == "PREVIEWED" && it.recordId == null }) }
                finally { db.close() }
            }
        } finally {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(EncryptedDatasetStore.alias(owner, epoch)) }
            directory.deleteRecursively()
            directory.parentFile?.delete()
        }
    }
}
