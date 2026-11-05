package dev.icos.app.ui

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncControlPolicyTest {
    @Test fun pausePreferenceIsScopedByOwner() {
        val first = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val second = UUID.fromString("00000000-0000-0000-0000-000000000002")
        assertEquals("account-sync-controls-$first", SyncControlPolicy.preferenceName(first.toString()))
        assertEquals("account-sync-controls-$second", SyncControlPolicy.preferenceName(second.toString()))
        assertFalse(SyncControlPolicy.shouldSync(ownerPresent = false, paused = false))
        assertFalse(SyncControlPolicy.shouldSync(ownerPresent = true, paused = true))
        assertTrue(SyncControlPolicy.shouldSync(ownerPresent = true, paused = false))
    }

    @Test fun failureCodeIsStructuredWithoutSurfacingRawIdentifiers() {
        assertEquals(SyncErrorCode.EPOCH_CHANGED, SyncControlPolicy.errorCode("DATASET_EPOCH_CHANGED", false))
        assertEquals(SyncErrorCode.CONFLICT, SyncControlPolicy.errorCode("CONFLICT", false))
        assertEquals(SyncErrorCode.STORAGE, SyncControlPolicy.errorCode(null, true))
        assertEquals(SyncErrorCode.UNKNOWN, SyncControlPolicy.errorCode(null, false))
    }

    @Test fun initializedLocalReplicaCanQueueWithoutAnOnlineSyncButRejectsForeignOrIncompleteDatasets() {
        val owner = UUID.fromString("00000000-0000-0000-0000-000000000101")
        val epoch = UUID.fromString("00000000-0000-0000-0000-000000000102")
        assertTrue(SyncControlPolicy.localMutationReady(epoch, owner.toString(), epoch.toString(), true, owner))
        assertFalse(SyncControlPolicy.localMutationReady(null, owner.toString(), epoch.toString(), true, owner))
        assertFalse(SyncControlPolicy.localMutationReady(epoch, UUID.randomUUID().toString(), epoch.toString(), true, owner))
        assertFalse(SyncControlPolicy.localMutationReady(epoch, owner.toString(), UUID.randomUUID().toString(), true, owner))
        assertFalse(SyncControlPolicy.localMutationReady(epoch, owner.toString(), epoch.toString(), false, owner))
    }

    @Test fun acceptedMutationStaysPendingUntilCanonicalPull() {
        assertEquals(1 to 0, SyncControlPolicy.mutationCounts(listOf("QUEUED")))
        assertEquals(1 to 0, SyncControlPolicy.mutationCounts(listOf("AMBIGUOUS")))
        assertEquals(1 to 0, SyncControlPolicy.mutationCounts(listOf("ACKED")))
        assertEquals(0 to 0, SyncControlPolicy.mutationCounts(listOf("PULLED")))
        assertEquals(1 to 3, SyncControlPolicy.mutationCounts(
            listOf("ACKED", "CONFLICT", "REJECTED", "BLOCKED_DEPENDENCY", "PULLED"),
        ))
    }

    @Test fun metadataConflictsRequireFreshFormsWithoutBlockingFinancialOrUncertainReplay() {
        listOf("ledger", "category", "tag", "tag_group", "account", "account_group").forEach { aggregate ->
            listOf("metadata.create", "metadata.update", "metadata.archive", "metadata.delete", "metadata.reorder").forEach { command ->
                assertTrue(SyncControlPolicy.requiresMetadataReconfirmation(aggregate, command))
            }
        }
        assertTrue(SyncControlPolicy.requiresMetadataReconfirmation("category", "category.move"))
        assertTrue(SyncControlPolicy.requiresMetadataReconfirmation("ledger", "ledger.copy"))
        assertTrue(SyncControlPolicy.requiresMetadataReconfirmation("budget", "budget.setEnabled"))
        assertFalse(SyncControlPolicy.requiresMetadataReconfirmation("budget", "budget.configure"))
        assertFalse(SyncControlPolicy.requiresMetadataReconfirmation("record", "record.update"))
        assertFalse(SyncControlPolicy.requiresMetadataReconfirmation("record_template", "metadata.update"))
        assertFalse(SyncControlPolicy.requiresMetadataReconfirmation("account", "credit.configure"))
        assertEquals(1 to 0, SyncControlPolicy.mutationCounts(listOf("AMBIGUOUS")))
    }

    @Test fun onlyARealDifferentPreviousEpochIsReportedAsArchived() {
        assertTrue(SyncControlPolicy.previousEpochArchived(UUID.randomUUID(), UUID.randomUUID()))
        assertFalse(SyncControlPolicy.previousEpochArchived(null, UUID.randomUUID()))
        val same = UUID.randomUUID()
        assertFalse(SyncControlPolicy.previousEpochArchived(same, same))
    }

    @Test fun continuousLocalOperationsCoalesceButCannotPostponeUploadsIndefinitely() {
        val firstRequest = 1_000L
        assertEquals(2_000L, SyncControlPolicy.automaticSyncDelay(firstRequest, firstRequest))
        assertEquals(3_500L, firstRequest + 500L + SyncControlPolicy.automaticSyncDelay(firstRequest, firstRequest + 500L))
        assertEquals(11_000L, 9_500L + SyncControlPolicy.automaticSyncDelay(firstRequest, 9_500L))
        assertEquals(0L, SyncControlPolicy.automaticSyncDelay(firstRequest, firstRequest + 10_000L))
        assertEquals(0L, SyncControlPolicy.automaticSyncDelay(firstRequest, firstRequest + 60_000L))
    }

    @Test fun transientNetworkFailuresBackOffWithABoundedDelay() {
        assertTrue(SyncControlPolicy.automaticSyncRetryDelay(1) > SyncControlPolicy.automaticSyncRetryDelay(0))
        assertEquals(30_000L, SyncControlPolicy.automaticSyncRetryDelay(3))
        assertEquals(SyncControlPolicy.automaticSyncRetryDelay(3), SyncControlPolicy.automaticSyncRetryDelay(Int.MAX_VALUE))
    }
}
