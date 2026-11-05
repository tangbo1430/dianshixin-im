package dev.icos.app.data.local

import android.content.Context
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import net.zetetic.database.Logger
import net.zetetic.database.NoopTarget
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.KeyStore
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class StorageOpenException(cause: Throwable? = null) : Exception("Encrypted dataset unavailable; explicit recovery required", cause)
class SelectorSyncException(cause: Throwable) : Exception("Account dataset selector durability is unconfirmed", cause)

data class StagedDataset internal constructor(
    val ownerId: UUID,
    val epochId: UUID,
    internal val token: String,
    internal val directory: File,
    val database: DatasetDatabase,
)

/** Call off the main thread with the persisted owner and epoch; caller owns and closes the result. */
object EncryptedDatasetStore {
    fun open(
        context: Context,
        ownerId: UUID,
        epochId: UUID,
    ): DatasetDatabase = openAt(context, ownerId, epochId, directory(context, ownerId, epochId))

    fun openAccount(context: Context, ownerId: UUID, epochId: UUID): DatasetDatabase {
        val directory = accountDirectory(context, ownerId, epochId)
        val databaseFile = File(directory, "dataset.db")
        val envelopeFile = File(directory, "passphrase.envelope")
        val databaseMode = android.system.Os.lstat(databaseFile.absolutePath).st_mode
        val envelopeMode = android.system.Os.lstat(envelopeFile.absolutePath).st_mode
        check((databaseMode and android.system.OsConstants.S_IFMT) == android.system.OsConstants.S_IFREG && databaseFile.length() > 0L &&
            (envelopeMode and android.system.OsConstants.S_IFMT) == android.system.OsConstants.S_IFREG && envelopeFile.length() > 0L) {
            "Existing account database is incomplete"
        }
        return openAt(context, ownerId, epochId, directory)
    }

    fun createAccountStaging(context: Context, ownerId: UUID, epochId: UUID): StagedDataset {
        val token = UUID.randomUUID().toString()
        val staging = File(File(accountRoot(context, ownerId), "staging/$epochId"), token)
        return StagedDataset(ownerId, epochId, token, staging, openAt(context, ownerId, epochId, staging, reuseWrappingKey = true))
    }

    fun activateAccountStaging(
        context: Context,
        staged: StagedDataset,
        capturedSeq: Long,
        cursor: String,
    ) {
        require(capturedSeq >= 0 && cursor.isNotBlank())
        val identity = staged.database.identity().read() ?: error("Dataset identity missing")
        check(identity.ownerId == staged.ownerId.toString() && identity.epochId == staged.epochId.toString())
        check(identity.lastAppliedSeq == capturedSeq && identity.serverCursor == cursor)
        val sqlite = staged.database.openHelper.readableDatabase
        val mirrored = sqlite.query(
            "SELECT name FROM sqlite_master WHERE type='table' AND name GLOB 'server_*'",
        ).use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(0).removePrefix("server_")) }
        } == dev.icos.app.data.remote.SnapshotProtocol.tablesFor(identity.snapshotSchemaVersion)
        check(mirrored) { "Canonical snapshot mirror is incomplete" }
        staged.database.close()
        val selector = File(accountRoot(context, staged.ownerId), "active")
        val parent = selector.parentFile!!
        check(parent.isDirectory || parent.mkdirs())
        val temporary = File(parent, "active.new")
        FileOutputStream(temporary).use {
            it.write("${staged.epochId}\n${staged.token}".toByteArray(Charsets.US_ASCII))
            it.fd.sync()
        }
        android.system.Os.rename(temporary.absolutePath, selector.absolutePath)
        try {
            val directoryFd = android.system.Os.open(parent.absolutePath, android.system.OsConstants.O_RDONLY, 0)
            try {
                android.system.Os.fsync(directoryFd)
            } finally {
                android.system.Os.close(directoryFd)
            }
        } catch (failure: Exception) {
            // The rename is already visible. Keep the selected staging database even if durability
            // could not be confirmed; the previous selector cannot be restored safely here.
            throw SelectorSyncException(failure)
        }
    }

    internal fun accountDirectory(context: Context, owner: UUID, epoch: UUID): File {
        val root = accountRoot(context, owner)
        val selector = File(root, "active")
        if (!selector.isFile) throw StorageOpenException()
        val parts = selector.readText(Charsets.US_ASCII).split('\n')
        require(parts.size == 2 && UUID.fromString(parts[0]) == epoch)
        require(parts[1].matches(Regex("[0-9a-f-]{36}")))
        return File(File(root, "staging/$epoch"), parts[1])
    }

    /** The single activated epoch for this owner, or null when no account dataset has been activated yet. */
    internal fun activeAccountEpoch(context: Context, owner: UUID): UUID? {
        val root = accountRoot(context, owner)
        val selector = File(root, "active")
        if (!selector.isFile) return null
        val parts = selector.readText(Charsets.US_ASCII).split('\n')
        require(parts.size == 2)
        val epoch = UUID.fromString(parts[0])
        require(parts[1].matches(Regex("[0-9a-f-]{36}")))
        check(File(File(root, "staging/$epoch"), parts[1]).isDirectory)
        return epoch
    }

    private fun accountRoot(context: Context, owner: UUID) =
        File(context.noBackupFilesDir, "datasets/accounts/$owner")

    private fun openAt(
        context: Context,
        ownerId: UUID,
        epochId: UUID,
        directory: File,
        reuseWrappingKey: Boolean = false,
    ): DatasetDatabase {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Open storage off the main thread" }
        return synchronized(this) {
            try {
                check(directory.isDirectory || directory.mkdirs())
                RandomAccessFile(File(directory, "open.lock"), "rw").use { lockFile ->
                    lockFile.channel.lock().use {
                        openLocked(context.applicationContext, directory, ownerId, epochId, reuseWrappingKey)
                    }
                }
            } catch (failure: Exception) {
                throw StorageOpenException(failure)
            }
        }
    }

    internal fun directory(
        context: Context,
        owner: UUID,
        epoch: UUID,
    ) = File(context.noBackupFilesDir, "datasets/$owner/$epoch")

    internal fun alias(
        owner: UUID,
        epoch: UUID,
    ) = "icos.dataset.v1.$owner.$epoch"

    internal fun aad(
        owner: UUID,
        epoch: UUID,
    ) = "icos.dataset.envelope.v1:$owner:$epoch".toByteArray(Charsets.UTF_8)

    private fun openLocked(
        context: Context,
        directory: File,
        owner: UUID,
        epoch: UUID,
        reuseWrappingKey: Boolean,
    ): DatasetDatabase {
        val databaseFile = File(directory, "dataset.db")
        val envelopeFile = File(directory, "passphrase.envelope")
        val pendingFile = File(directory, "passphrase.pending")
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val keyAlias = alias(owner, epoch)
        val fresh = directory.list()?.all { it == "open.lock" } == true
        val passphrase: ByteArray
        if (fresh) {
            val wrappingKey = if (reuseWrappingKey && keyStore.containsAlias(keyAlias)) {
                // Same-epoch snapshot retries use new passphrases without replacing the shared wrapping key.
                keyStore.getKey(keyAlias, null) as? SecretKey ?: throw StorageOpenException()
            } else {
                // A leftover guest alias represents interrupted creation, never permission to replace it.
                check(!keyStore.containsAlias(keyAlias))
                KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
                    init(
                        KeyGenParameterSpec
                            .Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                            .setKeySize(256)
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .setRandomizedEncryptionRequired(true)
                            .build(),
                    )
                    generateKey()
                }
            }
            passphrase = ByteArray(32).also { SecureRandom().nextBytes(it) }
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, wrappingKey)
                cipher.updateAAD(aad(owner, epoch))
                check(cipher.iv.size == 12)
                val envelope = byteArrayOf(1) + cipher.iv + cipher.doFinal(passphrase)
                check(pendingFile.createNewFile())
                FileOutputStream(pendingFile).use { output ->
                    output.write(envelope)
                    output.fd.sync()
                }
                check(pendingFile.renameTo(envelopeFile))
            } catch (failure: Exception) {
                passphrase.fill(0)
                throw failure
            }
        } else {
            check(databaseFile.isFile && databaseFile.length() > 0 && !pendingFile.exists())
            check(envelopeFile.isFile && envelopeFile.length() == 61L)
            val key = keyStore.getKey(keyAlias, null) as? SecretKey ?: throw StorageOpenException()
            val envelope = envelopeFile.readBytes()
            check(envelope.size == 61 && envelope[0] == 1.toByte())
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, envelope, 1, 12))
            cipher.updateAAD(aad(owner, epoch))
            passphrase = cipher.doFinal(envelope, 13, 48)
            check(passphrase.size == 32)
        }

        var database: DatasetDatabase? = null
        try {
            System.loadLibrary("sqlcipher")
            Logger.setTarget(NoopTarget())
            database =
                Room
                    .databaseBuilder(context, DatasetDatabase::class.java, databaseFile.absolutePath)
                    .openHelperFactory(SupportOpenHelperFactory(passphrase))
                    .addMigrations(
                        financeMigration1To2(owner.toString(), epoch.toString()),
                        budgetMigration2To3(owner.toString(), epoch.toString()),
                        searchPreferencesMigration3To4(owner.toString(), epoch.toString()),
                        recordTemplateMigration4To5(owner.toString(), epoch.toString()),
                        recordClaimMigration5To6(owner.toString(), epoch.toString()),
                        businessPreferencesMigration6To7(owner.toString(), epoch.toString()),
                        tagMigration7To8(owner.toString(), epoch.toString()),
                        accountLifecycleMigration8To9(owner.toString(), epoch.toString()),
                        accountGroupsMigration9To10(owner.toString(), epoch.toString()),
                        savingMigration10To11(owner.toString(), epoch.toString()),
                        loanMigration11To12(owner.toString(), epoch.toString()),
                        typedSyncMigration12To13(owner.toString(), epoch.toString()),
                        snapshotSchemaVersion13To14(owner.toString(), epoch.toString()),
                        accountMutationAndFileImportMigration14To15(owner.toString(), epoch.toString()),
                        ledgerCategoryMigration15To16(owner.toString(), epoch.toString()),
                        creditInstallmentMigration16To17(owner.toString(), epoch.toString()),
                        relatedPersonMigration17To18(owner.toString(), epoch.toString()),
                    ).addCallback(
                        object : RoomDatabase.Callback() {
                            override fun onCreate(db: SupportSQLiteDatabase) {
                                check(fresh) { "Existing datasets require an explicit schema migration" }
                            }
                        },
                    ).build()
            database.passphrase = passphrase
            database.runInTransaction {
                if (fresh) database.identity().insert(DatasetIdentity(ownerId = owner.toString(), epochId = epoch.toString()))
                check(database.identity().count() == 1)
                val identity = database.identity().read()
                check(identity != null && identity.ownerId == owner.toString() && identity.epochId == epoch.toString())
                check(identity.singleton == 1 && identity.schemaVersion == 1 && identity.lastAppliedSeq >= 0)
            }
            return database
        } catch (failure: Exception) {
            database?.close()
            passphrase.fill(0)
            throw failure
        }
    }
}
