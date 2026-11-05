package dev.icos.app.data.local

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import dev.icos.app.data.remote.AuthClient
import dev.icos.app.data.remote.DeviceIdentity
import dev.icos.app.data.remote.SnapshotProtocol
import dev.icos.app.domain.Direction
import org.json.JSONObject
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import dev.icos.app.data.remote.AuthFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountSyncReconciliationTest {
    @Test
    fun unreadableSameEpochReplicaAbortsBootstrapWithoutReplacingSelectorOrQueuedOperation() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val fixtureRoot = File(target.cacheDir, "account-bootstrap-preservation-${UUID.randomUUID()}")
        val context = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = fixtureRoot
        }
        val owner = UUID.randomUUID()
        val epoch = UUID.randomUUID()
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        try {
            // If bootstrap incorrectly reaches authentication, reject locally before touching any shared auth key or network.
            File(fixtureRoot, "auth").mkdirs()
            File(fixtureRoot, "auth/state.bin").writeBytes(byteArrayOf(0))
            val staged = EncryptedDatasetStore.createAccountStaging(context, owner, epoch)
            val command = TypedOutboxEntity(
                sequence = 1, opId = UUID.randomUUID().toString(), groupId = UUID.randomUUID().toString(),
                ownerId = owner.toString(), epochId = epoch.toString(), deviceId = UUID.randomUUID().toString(),
                aggregateType = "record", aggregateId = UUID.randomUUID().toString(), baseVersionsJson = "{}",
                commandType = "record.create", schemaVersion = 1, payloadJson = "{}", state = "AMBIGUOUS", createdAt = 10,
            )
            staged.database.use { it.typedOutbox().insert(command) }
            val accountRoot = File(fixtureRoot, "datasets/accounts/$owner")
            // Select directly: this regression targets the bootstrap open boundary before snapshot download/validation.
            File(accountRoot, "active").writeText("$epoch\n${staged.token}")
            val envelope = File(staged.directory, "passphrase.envelope")
            val originalEnvelope = envelope.readBytes()
            envelope.writeBytes(originalEnvelope.clone().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })
            fun hashes() = accountRoot.walkTopDown().filter { it.isFile && it.name != "open.lock" }
                .associate { it.relativeTo(accountRoot).path to MessageDigest.getInstance("SHA-256").digest(it.readBytes()).toList() }
            val before = hashes()
            var committed = false
            // The public entry point performs authentication/network admission first; exercise the private bootstrap boundary directly.
            val bootstrap = AccountSyncCoordinator::class.java.declaredMethods.single { it.name == "bootstrapFromSnapshot" }
                .apply { isAccessible = true }
            val progress: (String) -> Unit = {}
            val cancelled: () -> Boolean = { false }
            val commit: (() -> Unit) -> Unit = { activate -> committed = true; activate() }
            val failure = assertThrows(InvocationTargetException::class.java) {
                bootstrap.invoke(AccountSyncCoordinator, context, AuthClient(context), owner, epoch,
                    DeviceIdentity(owner, UUID.randomUUID()), progress, cancelled, false, null, null, null, commit)
            }
            assertTrue("Propagate the unreadable selected database instead of continuing bootstrap", failure.cause is StorageOpenException)
            assertFalse(committed)
            assertEquals(before, hashes())
            assertEquals(epoch, EncryptedDatasetStore.activeAccountEpoch(context, owner))
            assertTrue(keyStore.containsAlias(EncryptedDatasetStore.alias(owner, epoch)))
            // Repair only the deliberately damaged test envelope to prove the original key and frozen command survived.
            envelope.writeBytes(originalEnvelope)
            EncryptedDatasetStore.openAccount(context, owner, epoch).use {
                assertEquals(listOf(command), it.typedOutbox().all(owner.toString(), epoch.toString()))
            }
        } finally {
            fixtureRoot.deleteRecursively()
            keyStore.deleteEntry(EncryptedDatasetStore.alias(owner, epoch))
        }
    }

    @Test
    fun legacyAttachmentClearingUpdateIsBlockedWithoutRewritingTheFrozenCommand() {
        val command =
            TypedOutboxEntity(
                opId = "op",
                groupId = "group",
                ownerId = "owner",
                epochId = "epoch",
                deviceId = "device",
                aggregateType = "record",
                aggregateId = "record",
                baseVersionsJson = "{}",
                commandType = "record.update",
                schemaVersion = 1,
                payloadJson = "{\"changes\":{\"attachmentIds\":[]}}",
                state = "AMBIGUOUS",
                createdAt = 10,
            )
        val frozen = command.copy()
        assertThrows(IllegalStateException::class.java) {
            dev.icos.app.data.remote
                .requireSafeRecordAttachmentUpdate(command)
        }
        assertEquals(frozen, command)
        dev.icos.app.data.remote
            .requireSafeRecordAttachmentUpdate(command.copy(payloadJson = "{\"changes\":{}}"))
        dev.icos.app.data.remote.requireSafeRecordAttachmentUpdate(
            command.copy(commandType = "record.create", payloadJson = "{\"record\":{\"attachmentIds\":[]}}"),
        )
    }

    @Test
    fun sameEpochAckLostCommandIsSelectedForReplayWithOriginalOperationId() {
        val op = "00000000-0000-0000-0000-000000000001"
        val command =
            TypedOutboxEntity(
                opId = op,
                groupId = "00000000-0000-0000-0000-000000000002",
                ownerId = "00000000-0000-0000-0000-000000000003",
                epochId = "00000000-0000-0000-0000-000000000004",
                deviceId = "00000000-0000-0000-0000-000000000005",
                aggregateType = "record",
                aggregateId = "00000000-0000-0000-0000-000000000006",
                baseVersionsJson = "{}",
                commandType = "record.create",
                schemaVersion = 1,
                payloadJson = "{}",
                state = "AMBIGUOUS",
                createdAt = 10,
            )
        val intent =
            AccountMutationIntentEntity(
                op,
                command.ownerId,
                command.epochId,
                "record",
                command.aggregateId,
                command.commandType,
                command.payloadJson,
                "AMBIGUOUS",
                10,
            )
        val prediction = AccountPredictionEntity(op, command.ownerId, command.epochId, "record", command.aggregateId, "{}", true)
        assertEquals(listOf(command), AccountSyncCoordinator.uncertainMutationCommands(listOf(command), listOf(intent), listOf(prediction)))
    }

    @Test
    fun bootstrapReplaysOnlyFrozenRootsAndPreservesValidatedUnsentDependencyChain() {
        val owner = UUID.randomUUID().toString()
        val epoch = UUID.randomUUID().toString()
        val device = UUID.randomUUID().toString()
        fun command(sequence: Long, kind: String, versions: String) = TypedOutboxEntity(
            sequence = sequence, opId = UUID.randomUUID().toString(), groupId = UUID.randomUUID().toString(),
            ownerId = owner, epochId = epoch, deviceId = device, aggregateType = kind, aggregateId = UUID.randomUUID().toString(),
            baseVersionsJson = versions, commandType = if (kind == "category") "metadata.create" else "record.create",
            schemaVersion = 1, payloadJson = "{}", state = "QUEUED", createdAt = sequence)
        val root = command(1, "category", "{}")
        val child = command(2, "category", JSONObject().put("category:${root.aggregateId}", -root.sequence).toString())
        val record = command(3, "record", JSONObject().put("category:${child.aggregateId}", -child.sequence).toString())
        fun select(rows: List<TypedOutboxEntity>): List<TypedOutboxEntity> {
            val intents = rows.map { row -> AccountMutationIntentEntity(row.opId, row.ownerId, row.epochId,
                row.aggregateType, row.aggregateId, row.commandType, row.payloadJson, row.state, row.createdAt) }
            val predictions = rows.map { row -> AccountPredictionEntity(row.opId, row.ownerId, row.epochId,
                row.aggregateType, row.aggregateId, row.payloadJson, true) }
            return AccountSyncCoordinator.uncertainMutationCommands(rows, intents, predictions)
        }
        assertEquals(listOf(root), select(listOf(root, child, record)))
        assertThrows(IllegalStateException::class.java) { select(listOf(root, child.copy(state = "AMBIGUOUS"), record)) }
        assertThrows(IllegalStateException::class.java) { select(listOf(child, record)) }
        assertThrows(IllegalStateException::class.java) { select(listOf(root.copy(ownerId = UUID.randomUUID().toString()), child, record)) }
    }

    @Test
    fun inactiveAndMalformedMutationRowsAreNotReplayedAsUncertainPredictions() {
        val command =
            TypedOutboxEntity(
                opId = "op",
                groupId = "group",
                ownerId = "owner",
                epochId = "epoch",
                deviceId = "device",
                aggregateType = "record",
                aggregateId = "record",
                baseVersionsJson = "{}",
                commandType = "record.create",
                schemaVersion = 1,
                payloadJson = "{}",
                state = "QUEUED",
                createdAt = 10,
            )
        val intent = AccountMutationIntentEntity("op", "owner", "epoch", "record", "record", "record.create", "{}", "QUEUED", 10)
        val inactive = AccountPredictionEntity("op", "owner", "epoch", "record", "record", "{}", false)
        assertEquals(
            emptyList<TypedOutboxEntity>(),
            AccountSyncCoordinator.uncertainMutationCommands(listOf(command), listOf(intent), listOf(inactive)),
        )
        assertEquals(
            emptyList<TypedOutboxEntity>(),
            AccountSyncCoordinator.uncertainMutationCommands(listOf(command), emptyList(), listOf(inactive.copy(active = true))),
        )
    }

    @Test
    fun legacyPendingRecoveryCommandsReceiveOnlyActiveMarkersForReplay() {
        val owner = "00000000-0000-0000-0000-000000000003"
        val epoch = "00000000-0000-0000-0000-000000000004"
        val recovery = listOf("claim.mark", "claim.settle", "claim.cancel", "refund.create", "refund.cancel")
        val states = listOf("QUEUED", "AMBIGUOUS", "ACKED")
        val cases = recovery.flatMap { type -> states.map { state -> type to state } }
        val commands =
            cases.mapIndexed { index, (type, state) ->
                val op = "00000000-0000-0000-0000-${(index + 1).toString().padStart(12, '0')}"
                TypedOutboxEntity(
                    opId = op,
                    groupId = "00000000-0000-0000-0000-000000000020",
                    ownerId = owner,
                    epochId = epoch,
                    deviceId = "00000000-0000-0000-0000-000000000005",
                    aggregateType = "record",
                    aggregateId = "00000000-0000-0000-0000-000000000006",
                    baseVersionsJson = "{}",
                    commandType = type,
                    schemaVersion = 1,
                    payloadJson = "{\"n\":$index}",
                    state = if (state == "ACKED") "APPLIED" else state,
                    createdAt = 10,
                )
            }
        val intents =
            commands.zip(cases).map { (command, stateCase) ->
                val state = stateCase.second
                AccountMutationIntentEntity(
                    command.opId,
                    owner,
                    epoch,
                    command.aggregateType,
                    command.aggregateId,
                    command.commandType,
                    command.payloadJson,
                    state,
                    command.createdAt,
                )
            }
        val markers = AccountSyncCoordinator.recoveryMarkersToBackfill(owner, epoch, commands, intents, emptyList())
        assertEquals(commands.map { it.opId }, markers.map { it.opId })
        assertTrue(markers.all { it.active && it.payloadJson == commands.single { command -> command.opId == it.opId }.payloadJson })
        assertEquals(commands, AccountSyncCoordinator.uncertainMutationCommands(commands, intents, markers))
    }

    @Test
    fun recoveryMarkerBackfillIgnoresTerminalAndNonRecoveryCommands() {
        val command =
            TypedOutboxEntity(
                opId = "00000000-0000-0000-0000-000000000001",
                groupId = "00000000-0000-0000-0000-000000000002",
                ownerId = "00000000-0000-0000-0000-000000000003",
                epochId = "00000000-0000-0000-0000-000000000004",
                deviceId = "00000000-0000-0000-0000-000000000005",
                aggregateType = "refund",
                aggregateId = "00000000-0000-0000-0000-000000000006",
                baseVersionsJson = "{}",
                commandType = "refund.create",
                schemaVersion = 1,
                payloadJson = "{}",
                state = "CONFLICT",
                createdAt = 10,
            )
        val terminalIntent =
            AccountMutationIntentEntity(
                command.opId,
                command.ownerId,
                command.epochId,
                command.aggregateType,
                command.aggregateId,
                command.commandType,
                command.payloadJson,
                "CONFLICT",
                command.createdAt,
            )
        assertTrue(
            AccountSyncCoordinator
                .recoveryMarkersToBackfill(
                    command.ownerId,
                    command.epochId,
                    listOf(command),
                    listOf(terminalIntent),
                    emptyList(),
                ).isEmpty(),
        )
        val canonical = command.copy(state = "APPLIED")
        val pulled = terminalIntent.copy(state = "PULLED")
        assertTrue(
            AccountSyncCoordinator
                .recoveryMarkersToBackfill(
                    command.ownerId,
                    command.epochId,
                    listOf(canonical),
                    listOf(pulled),
                    emptyList(),
                ).isEmpty(),
        )
        assertThrows(IllegalStateException::class.java) {
            AccountSyncCoordinator.recoveryMarkersToBackfill(
                command.ownerId,
                command.epochId,
                listOf(canonical.copy(state = "AMBIGUOUS")),
                listOf(pulled),
                emptyList(),
            )
        }
        val ordinary = command.copy(commandType = "record.create", state = "QUEUED")
        val ordinaryIntent = terminalIntent.copy(commandType = ordinary.commandType, state = "QUEUED")
        assertTrue(
            AccountSyncCoordinator
                .recoveryMarkersToBackfill(
                    ordinary.ownerId,
                    ordinary.epochId,
                    listOf(ordinary),
                    listOf(ordinaryIntent),
                    emptyList(),
                ).isEmpty(),
        )
    }

    @Test
    fun recoveryMarkerBackfillFailsClosedForMissingOrMismatchedOutboxIdentity() {
        val command =
            TypedOutboxEntity(
                opId = "00000000-0000-0000-0000-000000000001",
                groupId = "00000000-0000-0000-0000-000000000002",
                ownerId = "00000000-0000-0000-0000-000000000003",
                epochId = "00000000-0000-0000-0000-000000000004",
                deviceId = "00000000-0000-0000-0000-000000000005",
                aggregateType = "refund",
                aggregateId = "00000000-0000-0000-0000-000000000006",
                baseVersionsJson = "{}",
                commandType = "refund.create",
                schemaVersion = 1,
                payloadJson = "{}",
                state = "AMBIGUOUS",
                createdAt = 10,
            )
        val intent =
            AccountMutationIntentEntity(
                command.opId,
                command.ownerId,
                command.epochId,
                command.aggregateType,
                command.aggregateId,
                command.commandType,
                command.payloadJson,
                "AMBIGUOUS",
                command.createdAt,
            )
        assertThrows(IllegalStateException::class.java) {
            AccountSyncCoordinator.recoveryMarkersToBackfill(command.ownerId, command.epochId, emptyList(), listOf(intent), emptyList())
        }
        assertThrows(IllegalStateException::class.java) {
            AccountSyncCoordinator.recoveryMarkersToBackfill(
                command.ownerId,
                command.epochId,
                listOf(command),
                listOf(intent.copy(payloadJson = "{\"changed\":true}")),
                emptyList(),
            )
        }
        assertThrows(IllegalStateException::class.java) {
            AccountSyncCoordinator.recoveryMarkersToBackfill(
                command.ownerId,
                command.epochId,
                listOf(command),
                listOf(intent),
                listOf(
                    AccountPredictionEntity(
                        command.opId,
                        command.ownerId,
                        command.epochId,
                        command.aggregateType,
                        command.aggregateId,
                        "{\"changed\":true}",
                        true,
                    ),
                ),
            )
        }
    }

    @Test
    fun receiptSequenceMustBeCoveredByCurrentWatermark() {
        assertTrue(AccountSyncCoordinator.receiptCoveredByWatermark("{\"status\":\"APPLIED\",\"seq\":8}", 8))
        assertFalse(AccountSyncCoordinator.receiptCoveredByWatermark("{\"status\":\"ALREADY_APPLIED\",\"seq\":9}", 8))
        assertThrows(AuthFailure::class.java) {
            AccountSyncCoordinator.receiptCoveredByWatermark("{\"status\":\"APPLIED\"}", 8)
        }
        assertThrows(AuthFailure::class.java) {
            AccountSyncCoordinator.receiptCoveredByWatermark("{\"status\":\"REJECTED\",\"seq\":8}", 8)
        }
    }

    @Test
    fun replayedEquivalentSuccessStatusesWithSameOperationAndSequenceRemainValid() {
        assertTrue(
            AccountSyncCoordinator.matchingSuccessfulReceipts(
                "APPLIED",
                "{\"opId\":\"op-1\",\"status\":\"APPLIED\",\"seq\":12}",
                "ALREADY_APPLIED",
                "{\"opId\":\"op-1\",\"status\":\"ALREADY_APPLIED\",\"seq\":12}",
                "op-1",
            ),
        )
        assertFalse(
            AccountSyncCoordinator.matchingSuccessfulReceipts(
                "APPLIED",
                "{\"opId\":\"op-1\",\"status\":\"APPLIED\",\"seq\":12}",
                "ALREADY_APPLIED",
                "{\"opId\":\"op-1\",\"status\":\"ALREADY_APPLIED\",\"seq\":13}",
                "op-1",
            ),
        )
    }

    @Test
    fun overlappingMutationIdentityMismatchFailsClosed() {
        val command =
            TypedOutboxEntity(
                opId = "00000000-0000-0000-0000-000000000001",
                groupId = "00000000-0000-0000-0000-000000000002",
                ownerId = "00000000-0000-0000-0000-000000000003",
                epochId = "00000000-0000-0000-0000-000000000004",
                deviceId = "00000000-0000-0000-0000-000000000005",
                aggregateType = "record",
                aggregateId = "00000000-0000-0000-0000-000000000006",
                baseVersionsJson = "{}",
                commandType = "record.create",
                schemaVersion = 1,
                payloadJson = "{}",
                state = "AMBIGUOUS",
                createdAt = 10,
            )
        val intent =
            AccountMutationIntentEntity(
                command.opId,
                command.ownerId,
                command.epochId,
                "record",
                command.aggregateId,
                command.commandType,
                "{\"wrong\":true}",
                "AMBIGUOUS",
                10,
            )
        val prediction = AccountPredictionEntity(command.opId, command.ownerId, command.epochId, "record", command.aggregateId, "{}", true)
        assertThrows(IllegalStateException::class.java) {
            AccountSyncCoordinator.uncertainMutationCommands(listOf(command), listOf(intent), listOf(prediction))
        }
    }

    @Test
    fun pendingCategoryCreationsStayDurableAndSelectableWithoutBecomingCanonical() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val owner = UUID.randomUUID()
        val epoch = UUID.randomUUID()
        val directory = EncryptedDatasetStore.directory(context, owner, epoch)
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val ledger = UUID.randomUUID()
        val confirmed = UUID.randomUUID()
        val archived = UUID.randomUUID()
        val deleted = UUID.randomUUID()
        val pending = UUID.randomUUID()
        val acknowledged = UUID.randomUUID()
        val settled = UUID.randomUUID()
        val rejected = UUID.randomUUID()
        val conflicted = UUID.randomUUID()
        val renamed = UUID.randomUUID()

        fun categoryIntent(
            id: UUID,
            state: String,
            createdAt: Long,
            name: String,
            sortOrder: Long,
            color: String?,
            excludeBudget: Boolean,
            parentId: UUID? = null,
            command: String = "metadata.create",
        ) = AccountMutationIntentEntity(
            opId = UUID.randomUUID().toString(),
            ownerId = owner.toString(),
            epochId = epoch.toString(),
            aggregateType = "category",
            aggregateId = id.toString(),
            commandType = command,
            payloadJson =
                JSONObject().put(
                    "entity",
                    JSONObject()
                        .put("ledgerId", ledger.toString())
                        .put("direction", "EXPENSE")
                        .put("parentId", parentId?.toString() ?: JSONObject.NULL)
                        .put("name", name)
                        .put("iconRef", JSONObject.NULL)
                        .put("color", color ?: JSONObject.NULL)
                        .put("sortOrder", sortOrder)
                        .put("archivedAt", JSONObject.NULL)
                        .put("excludeBudget", excludeBudget),
                ).toString(),
            state = state,
            createdAt = createdAt,
        )

        val expectedPending =
            listOf(
                FinanceCategory(acknowledged, "待确认乙", Direction.EXPENSE, null, null, 2, 0, ledger,
                    color = null, usesCanonicalColor = true, excludeBudget = false),
                FinanceCategory(pending, "待确认甲已改名", Direction.EXPENSE, acknowledged, null, 5, 0, ledger,
                    color = "#12AB34", usesCanonicalColor = true, excludeBudget = true),
            )
        try {
            EncryptedDatasetStore.open(context, owner, epoch).use { database ->
                database.identity().update(database.identity().read()!!.copy(snapshotSchemaVersion = SnapshotProtocol.DATA4_SCHEMA))
                val sqlite = database.openHelper.writableDatabase
                sqlite.execSQL(
                    "CREATE TABLE `server_ledgers` (`id` TEXT NOT NULL PRIMARY KEY, `owner_id` TEXT NOT NULL, `epoch` TEXT NOT NULL, " +
                        "`name` TEXT, `base_currency` TEXT, `is_default` INTEGER, `sort_order` INTEGER, `archived_at` TEXT, `deleted_at` TEXT, `server_revision` INTEGER)",
                )
                sqlite.execSQL("INSERT INTO `server_ledgers` VALUES ('$ledger', '$owner', '$epoch', '账本', 'CNY', 1, 0, NULL, NULL, 1)")
                sqlite.execSQL(
                    "CREATE TABLE `server_categories` (`id` TEXT NOT NULL PRIMARY KEY, `owner_id` TEXT NOT NULL, `epoch` TEXT NOT NULL, " +
                        "`ledger_id` TEXT, `name` TEXT, `direction` TEXT, `parent_id` TEXT, `archived_at` TEXT, `sort_order` INTEGER, " +
                        "`color` TEXT, `exclude_budget` INTEGER, `purpose` TEXT, `server_revision` INTEGER, `deleted_at` TEXT)",
                )
                sqlite.execSQL("INSERT INTO `server_categories` VALUES ('$confirmed', '$owner', '$epoch', '$ledger', '已确认分类', 'EXPENSE', NULL, NULL, 0, NULL, 0, 'CUSTOM', 3, NULL)")
                sqlite.execSQL("INSERT INTO `server_categories` VALUES ('$archived', '$owner', '$epoch', '$ledger', '已归档分类', 'EXPENSE', NULL, '2026-01-01T00:00:00Z', 1, NULL, 0, 'CUSTOM', 2, NULL)")
                sqlite.execSQL("INSERT INTO `server_categories` VALUES ('$deleted', '$owner', '$epoch', '$ledger', '已删除分类', 'EXPENSE', NULL, NULL, 2, NULL, 0, 'CUSTOM', 2, '2026-01-01T00:00:00Z')")
                val intents = database.accountMutations()
                intents.insertIntent(categoryIntent(pending, "QUEUED", 1, "待确认甲", 5, "#12AB34", true, parentId = acknowledged))
                intents.insertIntent(categoryIntent(acknowledged, "ACKED", 2, "待确认乙", 2, null, false))
                intents.insertIntent(categoryIntent(settled, "PULLED", 3, "已结算分类", 3, null, false))
                intents.insertIntent(categoryIntent(rejected, "REJECTED", 4, "已拒绝分类", 4, null, false))
                intents.insertIntent(categoryIntent(conflicted, "CONFLICT", 5, "冲突分类", 6, null, false))
                intents.insertIntent(categoryIntent(confirmed, "QUEUED", 6, "已确认待同步分类", 7, null, false))
                intents.insertIntent(categoryIntent(renamed, "QUEUED", 7, "已改名分类", 8, null, false, command = "metadata.update"))
                intents.pendingCategoryCreations(owner.toString(), epoch.toString()).forEach { intent ->
                    intents.insertPrediction(AccountPredictionEntity(intent.opId, intent.ownerId, intent.epochId,
                        intent.aggregateType, intent.aggregateId, intent.payloadJson, true))
                }
                val rename = categoryIntent(pending, "QUEUED", 8, "待确认甲已改名", 5, "#12AB34", true, parentId = acknowledged)
                    .let { intent -> intent.copy(commandType = "metadata.update", payloadJson = JSONObject()
                        .put("changes", JSONObject(intent.payloadJson).getJSONObject("entity")).toString()) }
                intents.insertIntent(rename)
                intents.insertPrediction(AccountPredictionEntity(rename.opId, rename.ownerId, rename.epochId,
                    rename.aggregateType, rename.aggregateId, rename.payloadJson, true))

                val projection = CanonicalAccountRepository.readCategoryProjection(database, owner, epoch)
                val read = projection.categories
                assertEquals(listOf(confirmed, archived, acknowledged, pending), read.map { it.id })
                assertTrue(read.none { it.id in setOf(settled, rejected, conflicted, renamed) })
                assertEquals(2, SnapshotImporter.canonicalTables(database, setOf("categories")).getJSONArray("categories")
                    .let { rows -> (0 until rows.length()).count { rows.getJSONObject(it).isNull("deletedAt") } })
                assertThrows(IllegalStateException::class.java) {
                    CanonicalAccountRepository.readCategories(database, UUID.randomUUID(), epoch)
                }
                assertEquals(expectedPending, projection.pendingCreations)
                assertEquals(5, intents.pendingCount(owner.toString(), epoch.toString()))
                assertEquals(2, intents.retryCount(owner.toString(), epoch.toString()))
            }
            // Pending creations are durable: reopening the dataset must not lose or promote them.
            EncryptedDatasetStore.open(context, owner, epoch).use { database ->
                assertEquals(expectedPending, CanonicalAccountRepository.readCategoryProjection(database, owner, epoch).pendingCreations)
            }
        } finally {
            keyStore.deleteEntry(EncryptedDatasetStore.alias(owner, epoch))
            directory.deleteRecursively()
            directory.parentFile?.delete()
        }
    }
}
