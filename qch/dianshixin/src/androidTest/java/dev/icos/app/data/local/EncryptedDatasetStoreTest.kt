package dev.icos.app.data.local

import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.SecretKey
import android.database.sqlite.SQLiteDatabase as PlatformSQLiteDatabase

@RunWith(AndroidJUnit4::class)
class EncryptedDatasetStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val spaces = mutableListOf<Pair<UUID, UUID>>()
    private val keyStore get() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun space(
        owner: UUID = UUID.randomUUID(),
        epoch: UUID = UUID.randomUUID(),
    ): Pair<UUID, UUID> = (owner to epoch).also { spaces += it }

    private fun open(space: Pair<UUID, UUID>) = EncryptedDatasetStore.open(context, space.first, space.second)

    private fun directory(space: Pair<UUID, UUID>) = EncryptedDatasetStore.directory(context, space.first, space.second)

    private fun file(
        space: Pair<UUID, UUID>,
        name: String,
    ) = File(directory(space), name)

    private fun alias(space: Pair<UUID, UUID>) = EncryptedDatasetStore.alias(space.first, space.second)

    @After
    fun removeOnlyTestSpaces() {
        for (space in spaces) {
            keyStore.deleteEntry(alias(space))
            val directory = directory(space)
            directory.deleteRecursively()
            directory.parentFile?.delete()
        }
    }

    private fun snapshot(space: Pair<UUID, UUID>) =
        directory(space)
            .listFiles()!!
            .filter { it.name != "open.lock" }
            .associate { it.name to MessageDigest.getInstance("SHA-256").digest(it.readBytes()).toList() }

    private fun rejectsWithoutReplacement(space: Pair<UUID, UUID>) {
        val before = snapshot(space)
        val failure = assertThrows(StorageOpenException::class.java) { open(space).close() }
        assertNotNull("Keep the original storage failure available for diagnosis", failure.cause)
        assertEquals("Files must survive failed open unchanged", before, snapshot(space))
    }

    private fun replaceEnvelope(
        space: Pair<UUID, UUID>,
        aadSpace: Pair<UUID, UUID> = space,
    ) {
        val passphrase = ByteArray(32).also { SecureRandom().nextBytes(it) }
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, keyStore.getKey(alias(space), null) as SecretKey)
            cipher.updateAAD(EncryptedDatasetStore.aad(aadSpace.first, aadSpace.second))
            file(space, "passphrase.envelope").writeBytes(byteArrayOf(1) + cipher.iv + cipher.doFinal(passphrase))
        } finally {
            passphrase.fill(0)
        }
    }

    @Test
    fun mainThreadRejectionDoesNotWaitForInitializationMonitor() {
        val space = space()
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val rejected = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        val holder =
            Thread {
                synchronized(EncryptedDatasetStore) {
                    held.countDown()
                    release.await(10, TimeUnit.SECONDS)
                }
            }.apply {
                isDaemon = true
                start()
            }
        var posted = false
        try {
            assertTrue(held.await(5, TimeUnit.SECONDS))
            posted =
                Handler(Looper.getMainLooper()).post {
                    try {
                        open(space).close()
                    } catch (error: Throwable) {
                        failure.set(error)
                    } finally {
                        rejected.countDown()
                    }
                }
            assertTrue(posted)
            assertTrue("Main-thread misuse must reject while the initialization monitor remains held", rejected.await(2, TimeUnit.SECONDS))
            assertTrue(failure.get() is IllegalStateException)
            assertFalse(directory(space).exists())
            assertFalse(keyStore.containsAlias(alias(space)))
        } finally {
            release.countDown()
            holder.join(5000)
            if (posted) assertTrue(rejected.await(5, TimeUnit.SECONDS))
            assertFalse(holder.isAlive)
        }
    }

    @Test
    fun encryptedIdentityInsertReopensWithCursorAndInitialSchema() {
        val space = space()
        val cursorSentinel = "storage-test-cursor-${UUID.randomUUID()}"
        open(space).use { database ->
            assertEquals(DatasetIdentity(ownerId = space.first.toString(), epochId = space.second.toString()), database.identity().read())
            val sql = database.openHelper.writableDatabase
            sql.query("PRAGMA cipher_version").use {
                assertTrue(it.moveToFirst())
                // Android artifact 4.19.1 bundles SQLCipher core 4.19.0.
                val version = it.getString(0)
                InstrumentationRegistry.getInstrumentation().sendStatus(
                    2,
                    android.os.Bundle().apply { putString("sqlcipherCore", version) },
                )
                assertTrue("Native cipher_version: $version", version.startsWith("4.19.0"))
            }
            sql.query("SELECT identity_hash FROM room_master_table WHERE id = 42").use {
                assertTrue(it.moveToFirst())
                assertEquals(32, it.getString(0).length)
            }
            database.runInTransaction {
                sql.execSQL("UPDATE dataset_identity SET serverCursor = ?, lastAppliedSeq = 7 WHERE singleton = 1", arrayOf(cursorSentinel))
            }
        }
        open(space).use {
            assertEquals(cursorSentinel, it.identity().read()!!.serverCursor)
            assertEquals(7L, it.identity().read()!!.lastAppliedSeq)
        }
        val bytes = file(space, "dataset.db").readBytes()
        assertFalse(bytes.copyOfRange(0, 16).contentEquals("SQLite format 3\u0000".toByteArray()))
        val text = bytes.toString(Charsets.ISO_8859_1)
        assertFalse(text.contains(cursorSentinel))
        assertFalse(text.contains(space.first.toString()))
        assertFalse(text.contains("dataset_identity"))
        assertThrows(android.database.sqlite.SQLiteException::class.java) {
            PlatformSQLiteDatabase.openDatabase(file(space, "dataset.db").path, null, PlatformSQLiteDatabase.OPEN_READONLY).use {
                it.rawQuery("SELECT * FROM dataset_identity", null).use { cursor -> cursor.moveToFirst() }
            }
        }
        open(space).use { assertEquals(cursorSentinel, it.identity().read()!!.serverCursor) }
    }

    @Test
    fun ownersAndEpochsHaveSeparateFilesKeysAndPersistentIdentities() {
        val first = space()
        val otherOwner = space(epoch = first.second)
        val otherEpoch = space(owner = first.first)
        for (space in spaces) {
            open(space).use { assertEquals(space.first.toString(), it.identity().read()!!.ownerId) }
        }
        assertNotEquals(directory(first), directory(otherOwner))
        assertNotEquals(directory(first), directory(otherEpoch))
        assertFalse(file(first, "passphrase.envelope").readBytes().contentEquals(file(otherOwner, "passphrase.envelope").readBytes()))
        assertTrue(file(first, "dataset.db").copyTo(file(otherOwner, "dataset.db"), overwrite = true).exists())
        rejectsWithoutReplacement(otherOwner)
        open(first).use { assertEquals(first.second.toString(), it.identity().read()!!.epochId) }
        open(otherEpoch).use { assertEquals(otherEpoch.second.toString(), it.identity().read()!!.epochId) }
    }

    @Test
    fun snapshotImportCountsEmptyTablesAndRejectsIncorrectCounts() {
        for ((schema, includeImports) in listOf(1 to false, 6 to false, 6 to true)) {
            val space = space()
            val tables = org.json.JSONObject()
            val counts = dev.icos.app.data.remote.SnapshotProtocol.tablesFor(schema).associateWith { 0L }.toMutableMap()
            counts.keys.forEach { tables.put(it, org.json.JSONArray()) }
            tables.getJSONArray("ledgers").put(org.json.JSONObject()
                .put("id", UUID.randomUUID().toString()).put("ownerId", space.first.toString())
                .put("datasetEpoch", space.second.toString()).put("name", "Snapshot ledger").put("baseCurrency", "CNY"))
            counts["ledgers"] = 1L
            if (includeImports) {
                val batchId = UUID.randomUUID().toString()
                fun row(id: String = UUID.randomUUID().toString()) = org.json.JSONObject()
                    .put("id", id).put("ownerId", space.first.toString()).put("datasetEpoch", space.second.toString())
                    .put("deletedAt", "2026-10-23T00:00:00Z")
                tables.getJSONArray("import_batches").put(row(batchId).put("status", "CANCELLED")
                    .put("formatVersion", 1).put("totalChunks", 1).put("totalRows", 1).put("source", "CSV")
                    .put("fileDigest", "a".repeat(64)))
                tables.getJSONArray("import_rows").put(row().put("batchId", batchId).put("rowIndex", 0)
                    .put("disposition", "PENDING").put("externalId", org.json.JSONObject.NULL).put("payload", org.json.JSONObject.NULL))
                tables.getJSONArray("import_staging_chunks").put(row().put("batchId", batchId).put("chunkIndex", 0)
                    .put("rowCount", 1).put("digest", "b".repeat(64)).put("payload", org.json.JSONObject.NULL))
                listOf("import_batches", "import_rows", "import_staging_chunks").forEach { counts[it] = 1L }
            }
            val file = File.createTempFile("snapshot-empty-tables-", ".json", context.cacheDir)
            try {
                file.writeText(org.json.JSONObject().put("format", "icos-native-snapshot").put("formatVersion", 1)
                    .put("schemaVersion", schema).put("ownerId", space.first.toString()).put("epoch", space.second.toString())
                    .put("capturedSeq", 7).put("tables", tables).put("attachmentManifest", org.json.JSONArray()).toString())
                val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
                val snapshot = dev.icos.app.data.remote.SnapshotProtocol.VerifiedSnapshot(
                    space.first, space.second, 7, "snapshot-cursor", file, counts, schema, hash,
                )
                open(space).use { database ->
                    assertThrows(IllegalStateException::class.java) {
                        SnapshotImporter.importCanonicalMirror(database, snapshot.copy(entityCounts = counts + ("records" to 1L)),
                            space.first, space.second, snapshot.cursor)
                    }
                    assertNull(database.identity().read()!!.serverCursor)
                    SnapshotImporter.importCanonicalMirror(database, snapshot, space.first, space.second, snapshot.cursor)
                    val sql = database.openHelper.readableDatabase
                    counts.forEach { (table, count) ->
                        sql.query("SELECT COUNT(*) FROM `server_$table`").use {
                            assertTrue(it.moveToFirst())
                            assertEquals(count, it.getLong(0))
                        }
                    }
                }
                open(space).use { database ->
                    assertEquals("snapshot-cursor", database.identity().read()!!.serverCursor)
                    assertEquals(7L, database.identity().read()!!.lastAppliedSeq)
                }
            } finally {
                file.delete()
            }
        }
    }

    @Test
    fun snapshotRetryKeepsThePreviouslySelectedDatasetReadable() {
        val space = space()
        val root = File(context.noBackupFilesDir, "datasets/accounts/${space.first}")
        try {
            val first = EncryptedDatasetStore.createAccountStaging(context, space.first, space.second)
            first.database.use { database ->
                database.identity().update(database.identity().read()!!.copy(serverCursor = "selected-cursor", lastAppliedSeq = 7))
            }
            val envelope = File(first.directory, "passphrase.envelope").readBytes()
            // Select the fixture directly: this test exercises storage, not snapshot mirror validation.
            File(root, "active").writeText("${space.second}\n${first.token}")
            repeat(2) { attempt ->
                val retry = EncryptedDatasetStore.createAccountStaging(context, space.first, space.second)
                retry.database.use { database ->
                    assertEquals(space.first.toString(), database.identity().read()!!.ownerId)
                    assertNull(database.identity().read()!!.serverCursor)
                }
                assertNotEquals(first.directory, retry.directory)
                assertFalse(envelope.contentEquals(File(retry.directory, "passphrase.envelope").readBytes()))
                if (attempt == 0) assertTrue(retry.directory.deleteRecursively())
                EncryptedDatasetStore.openAccount(context, space.first, space.second).use { database ->
                    assertEquals("selected-cursor", database.identity().read()!!.serverCursor)
                    assertEquals(7L, database.identity().read()!!.lastAppliedSeq)
                }
                assertArrayEquals(envelope, File(first.directory, "passphrase.envelope").readBytes())
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun selectedAccountSchema14MigratesAndReopensWithoutReplacingEnvelopeOrOutbox() {
        val space = space()
        val owner = space.first.toString()
        val epoch = space.second.toString()
        val root = File(context.noBackupFilesDir, "datasets/accounts/$owner")
        try {
            val staged = EncryptedDatasetStore.createAccountStaging(context, space.first, space.second)
            val command = TypedOutboxEntity(
                sequence = 1, opId = UUID.randomUUID().toString(), groupId = UUID.randomUUID().toString(),
                ownerId = owner, epochId = epoch, deviceId = UUID.randomUUID().toString(),
                aggregateType = "record", aggregateId = UUID.randomUUID().toString(), baseVersionsJson = "{}",
                commandType = "record.create", schemaVersion = 1, payloadJson = "{}", state = "AMBIGUOUS", createdAt = 10,
            )
            staged.database.runInTransaction {
                val sql = staged.database.openHelper.writableDatabase
                dev.icos.app.data.remote.SnapshotProtocol.tablesFor(1).forEach { table ->
                    sql.execSQL("CREATE TABLE `server_$table` (`id` TEXT NOT NULL PRIMARY KEY, `owner_id` TEXT NOT NULL, `epoch` TEXT NOT NULL)")
                }
                sql.execSQL("INSERT INTO server_ledgers VALUES (?, ?, ?)", arrayOf("mirror-sentinel", owner, epoch))
                staged.database.identity().update(staged.database.identity().read()!!.copy(
                    serverCursor = "schema-14-cursor", lastAppliedSeq = 7, snapshotSchemaVersion = 1,
                ))
                staged.database.typedOutbox().insert(command)
                // Reconstruct the pre-upgrade physical schema, leaving its identity, mirrors and outbox intact.
                listOf("related_person_profile", "credit_payment_allocation", "installment_term", "installment_plan",
                    "credit_capacity_event", "credit_profile", "file_import_row", "file_import_batch",
                    "account_applied_group", "account_epoch_archive", "account_write_receipt", "account_prediction",
                    "account_mutation_intent").forEach { sql.execSQL("DROP TABLE `$it`") }
                listOf("sortOrder", "isDefault", "archivedAt").forEach {
                    sql.execSQL("ALTER TABLE `finance_ledger` DROP COLUMN `$it`")
                }
                listOf("sortOrder", "parentId", "archivedAt", "excludeBudget").forEach {
                    sql.execSQL("ALTER TABLE `finance_category` DROP COLUMN `$it`")
                }
                sql.execSQL("CREATE UNIQUE INDEX `index_finance_category_ledgerId_purpose` ON `finance_category` (`ledgerId`, `purpose`)")
            }
            staged.database.openHelper.writableDatabase.execSQL("PRAGMA user_version = 14")
            EncryptedDatasetStore.activateAccountStaging(context, staged, 7, "schema-14-cursor")
            val selector = File(root, "active").readBytes()
            val envelope = File(staged.directory, "passphrase.envelope").readBytes()
            repeat(2) {
                EncryptedDatasetStore.openAccount(context, space.first, space.second).use { database ->
                    assertEquals("schema-14-cursor", database.identity().read()!!.serverCursor)
                    assertEquals(7L, database.identity().read()!!.lastAppliedSeq)
                    assertEquals(listOf(command), database.typedOutbox().all(owner, epoch))
                    val sql = database.openHelper.readableDatabase
                    sql.query("PRAGMA user_version").use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        assertEquals(18, cursor.getInt(0))
                    }
                    sql.query("SELECT id FROM server_ledgers").use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        assertEquals("mirror-sentinel", cursor.getString(0))
                    }
                }
                assertArrayEquals(selector, File(root, "active").readBytes())
                assertArrayEquals(envelope, File(staged.directory, "passphrase.envelope").readBytes())
                assertTrue(keyStore.containsAlias(alias(space)))
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun freshGuestDirectoryCannotReuseAnExistingWrappingKey() {
        val space = space()
        open(space).close()
        assertTrue(directory(space).deleteRecursively())
        assertTrue(directory(space).mkdirs())
        rejectsWithoutReplacement(space)
        assertTrue(keyStore.containsAlias(alias(space)))
    }

    @Test
    fun wrongDatabasePassphraseFailsWithoutReplacingFiles() {
        val space = space()
        open(space).close()
        replaceEnvelope(space)
        rejectsWithoutReplacement(space)
    }

    @Test
    fun corruptAndTruncatedEnvelopesFailWithoutReplacingFiles() {
        val space = space()
        open(space).close()
        val envelope = file(space, "passphrase.envelope")
        val original = envelope.readBytes()
        envelope.writeBytes(original.clone().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })
        rejectsWithoutReplacement(space)
        envelope.writeBytes(original.copyOf(12))
        rejectsWithoutReplacement(space)
        envelope.writeBytes(original)
        open(space).close()
    }

    @Test
    fun missingEnvelopeDoesNotGenerateAnotherKey() {
        val space = space()
        open(space).close()
        assertTrue(file(space, "passphrase.envelope").delete())
        rejectsWithoutReplacement(space)
        assertTrue(keyStore.containsAlias(alias(space)))
        assertFalse(file(space, "passphrase.envelope").exists())
    }

    @Test
    fun missingWrappingKeyDoesNotRegenerateIt() {
        val space = space()
        open(space).close()
        keyStore.deleteEntry(alias(space))
        rejectsWithoutReplacement(space)
        assertFalse(keyStore.containsAlias(alias(space)))
    }

    @Test
    fun ownerAndEpochAreAuthenticatedAsEnvelopeAad() {
        val space = space()
        open(space).close()
        replaceEnvelope(space, UUID.randomUUID() to space.second)
        rejectsWithoutReplacement(space)
        replaceEnvelope(space, space.first to UUID.randomUUID())
        rejectsWithoutReplacement(space)
    }

    @Test
    fun identityOwnerAndEpochMismatchCannotBeChangedDuringOpen() {
        val space = space()
        open(space).use {
            it.openHelper.writableDatabase.execSQL("UPDATE dataset_identity SET ownerId = ?", arrayOf(UUID.randomUUID().toString()))
        }
        rejectsWithoutReplacement(space)
        val otherEpoch = space(owner = space.first)
        open(otherEpoch).use {
            it.openHelper.writableDatabase.execSQL("UPDATE dataset_identity SET epochId = ?", arrayOf(UUID.randomUUID().toString()))
        }
        rejectsWithoutReplacement(otherEpoch)
    }

    @Test
    fun damagedDatabaseIsPreserved() {
        val space = space()
        open(space).close()
        val database = file(space, "dataset.db")
        database.writeBytes(database.readBytes().also { it[0] = (it[0].toInt() xor 1).toByte() })
        rejectsWithoutReplacement(space)
    }

    @Test
    fun unsupportedSchemaVersionsAreRejectedWithoutDestruction() {
        for (version in listOf(0, 99)) {
            val space = space()
            open(space).use { it.openHelper.writableDatabase.execSQL("PRAGMA user_version = $version") }
            rejectsWithoutReplacement(space)
        }
    }

    @Test
    fun partialCreationAndMissingDatabaseRequireExplicitRecovery() {
        val space = space()
        open(space).close()
        assertTrue(file(space, "dataset.db").delete())
        rejectsWithoutReplacement(space)
        val partial = space()
        assertTrue(directory(partial).mkdirs())
        file(partial, "passphrase.pending").writeBytes(byteArrayOf(1))
        rejectsWithoutReplacement(partial)
        assertFalse(keyStore.containsAlias(alias(partial)))
    }
}
