package dev.icos.app.data.local

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName = "file_import_batch", indices = [Index(value = ["ownerId", "epochId"]), Index(value = ["fileDigest"])])
data class FileImportBatchEntity(
    @PrimaryKey val id: String,
    val ownerId: String,
    val epochId: String,
    val fileDigest: String,
    val formatVersion: String,
    val state: String,
    val rowCount: Int,
    val createdAt: Long,
    val committedAt: Long?,
)

@Entity(tableName = "file_import_row", primaryKeys = ["batchId", "rowNumber"],
    foreignKeys = [ForeignKey(entity = FileImportBatchEntity::class, parentColumns = ["id"], childColumns = ["batchId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("batchId"), Index(value = ["recordId"], unique = true), Index("contentFingerprint"), Index("externalId")])
data class FileImportRowEntity(
    val batchId: String,
    val rowNumber: Int,
    val payloadJson: String,
    val contentFingerprint: String,
    val externalId: String?,
    val recordId: String?,
    val state: String,
)

@Dao
interface FileImportDao {
    @Insert fun insert(batch: FileImportBatchEntity)
    @Insert fun insertRows(rows: List<FileImportRowEntity>)
    @Query("SELECT * FROM file_import_batch WHERE id=:id") fun batch(id: String): FileImportBatchEntity?
    @Query("SELECT * FROM file_import_batch WHERE ownerId=:ownerId AND epochId=:epochId ORDER BY createdAt DESC") fun batches(ownerId: String, epochId: String): List<FileImportBatchEntity>
    @Query("SELECT COUNT(*) FROM file_import_batch WHERE ownerId=:ownerId AND epochId=:epochId AND state='COMMITTED'") fun committedCount(ownerId: String, epochId: String): Int
    @Query("SELECT * FROM file_import_row WHERE batchId=:id ORDER BY rowNumber") fun rows(id: String): List<FileImportRowEntity>
    @Query("SELECT * FROM file_import_row WHERE batchId=:batchId AND rowNumber=:row") fun row(batchId: String, row: Int): FileImportRowEntity?
    @Query("SELECT r.* FROM file_import_row r JOIN file_import_batch b ON b.id=r.batchId WHERE r.externalId=:externalId AND b.state='COMMITTED' LIMIT 1") fun byExternalId(externalId: String): FileImportRowEntity?
    @Query("UPDATE file_import_batch SET formatVersion=:formatVersion, state=:state, committedAt=:at WHERE id=:id") fun updateBatchIntent(id: String, formatVersion: String, state: String, at: Long?)
    @Query("UPDATE file_import_batch SET state=:state, committedAt=:at WHERE id=:id") fun setBatchState(id: String, state: String, at: Long?)
    @Query("UPDATE file_import_row SET recordId=:recordId, state='COMMITTED' WHERE batchId=:batchId AND rowNumber=:row") fun setRowCommitted(batchId: String, row: Int, recordId: String)
    @Query("SELECT COUNT(*) FROM file_import_row WHERE batchId=:batchId AND externalId=:externalId") fun batchExternalCount(batchId: String, externalId: String): Int
    @Query("SELECT id FROM file_import_batch WHERE fileDigest=:digest AND state='COMMITTED' LIMIT 1") fun committedDigest(digest: String): String?
    @Query("SELECT * FROM file_import_row WHERE contentFingerprint=:fingerprint AND state='COMMITTED' LIMIT 1") fun byFingerprint(fingerprint: String): FileImportRowEntity?
}

@Entity(tableName = "finance_ledger")
internal data class LedgerEntity(
    @PrimaryKey val id: String,
    val ownerId: String,
    val epochId: String,
    val name: String,
    val baseCurrency: String,
    val createdAt: Long,
    val updatedAt: Long,
    val localRevision: Long,
    val deletedAt: Long?,
    @ColumnInfo(defaultValue = "0") val sortOrder: Long = 0,
    @ColumnInfo(defaultValue = "0") val isDefault: Boolean = false,
    val archivedAt: Long? = null,
)

@Entity(tableName = "finance_group")
internal data class FinanceGroupEntity(
    @PrimaryKey val id: String,
    val ownerId: String,
    val epochId: String,
    val kind: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val localRevision: Long,
    val deletedAt: Long?,
    @ColumnInfo(defaultValue = "0") val sortOrder: Long = 0,
    @ColumnInfo(defaultValue = "0") val isDefault: Boolean = false,
)

@Entity(
    tableName = "finance_category",
    foreignKeys = [
        ForeignKey(entity = LedgerEntity::class, parentColumns = ["id"], childColumns = ["ledgerId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [
        Index(value = ["ledgerId"], unique = false),
    ],
)
internal data class CategoryEntity(
    @PrimaryKey val id: String,
    val ownerId: String,
    val epochId: String,
    val ledgerId: String,
    val name: String,
    val direction: String,
    val purpose: String,
    val createdAt: Long,
    val updatedAt: Long,
    val localRevision: Long,
    val deletedAt: Long?,
    @ColumnInfo(defaultValue = "0") val sortOrder: Long = 0,
    val parentId: String? = null,
    val archivedAt: Long? = null,
    @ColumnInfo(defaultValue = "0") val excludeBudget: Boolean = false,
)

@Entity(
    tableName = "finance_account",
    foreignKeys = [
        ForeignKey(entity = FinanceGroupEntity::class, parentColumns = ["id"], childColumns = ["groupId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = EventEntity::class, parentColumns = ["id"], childColumns = ["openingEventId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [
        Index(value = ["groupId"], unique = false),
        Index(value = ["openingEventId"], unique = true),
    ],
)
internal data class AccountEntity(
    @PrimaryKey val id: String,
    val ownerId: String,
    val epochId: String,
    val groupId: String,
    val openingEventId: String,
    val name: String,
    val currency: String,
    val type: String,
    val createdAt: Long,
    val updatedAt: Long,
    val localRevision: Long,
    val deletedAt: Long?,
    val archivedAt: Long? = null,
    @ColumnInfo(defaultValue = "1") val includeInAssets: Boolean = true,
    @ColumnInfo(defaultValue = "1") val selectable: Boolean = true,
    @ColumnInfo(defaultValue = "0") val sortOrder: Long = 0,
)

@Entity(tableName = "finance_record")
internal data class RecordEntity(
    @PrimaryKey val id: String,
    val ownerId: String,
    val epochId: String,
    val createdAt: Long,
    val updatedAt: Long,
    val localRevision: Long,
    val deletedAt: Long?,
    @ColumnInfo(defaultValue = "'NONE'") val claimState: String = "NONE",
)

@Entity(
    tableName = "finance_event",
    foreignKeys = [
        ForeignKey(entity = LedgerEntity::class, parentColumns = ["id"], childColumns = ["ledgerId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = RecordEntity::class, parentColumns = ["id"], childColumns = ["recordId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = CategoryEntity::class, parentColumns = ["id"], childColumns = ["categoryId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = EventEntity::class, parentColumns = ["id"], childColumns = ["reversalOf"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [
        Index(value = ["ledgerId"], unique = false),
        Index(value = ["recordId", "localRevision", "kind"], unique = true),
        Index(value = ["categoryId"], unique = false),
        Index(value = ["reversalOf"], unique = true),
    ],
)
internal data class EventEntity(
    @PrimaryKey val id: String,
    val ownerId: String,
    val epochId: String,
    val ledgerId: String,
    val recordId: String?,
    val localRevision: Long,
    val kind: String,
    val occurredAtMillis: Long,
    val timezone: String,
    val localDate: String,
    val remark: String,
    val categoryId: String?,
    val countInStatistics: Boolean,
    val countInBudget: Boolean,
    val amountUnscaled: Long?,
    val amountScale: Int?,
    val amountCurrency: String?,
    val discountUnscaled: Long?,
    val discountScale: Int?,
    val discountCurrency: String?,
    val reversalOf: String?,
    val createdAt: Long,
    val origin: String,
)

@Entity(
    tableName = "finance_allocation",
    foreignKeys = [
        ForeignKey(entity = EventEntity::class, parentColumns = ["id"], childColumns = ["eventId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = AccountEntity::class, parentColumns = ["id"], childColumns = ["accountId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [
        Index(value = ["eventId", "position"], unique = true),
        Index(value = ["accountId"], unique = false),
    ],
)
internal data class AllocationEntity(
    @PrimaryKey val id: String,
    val eventId: String,
    val position: Int,
    val role: String,
    val accountId: String,
    val amountUnscaled: Long,
    val amountScale: Int,
    val amountCurrency: String,
)

@Entity(
    tableName = "finance_posting",
    foreignKeys = [
        ForeignKey(entity = EventEntity::class, parentColumns = ["id"], childColumns = ["eventId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = AccountEntity::class, parentColumns = ["id"], childColumns = ["accountId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = PostingEntity::class, parentColumns = ["id"], childColumns = ["reversalOf"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [
        Index(value = ["eventId", "position"], unique = true),
        Index(value = ["accountId"], unique = false),
        Index(value = ["reversalOf"], unique = true),
    ],
)
internal data class PostingEntity(
    @PrimaryKey val id: String,
    val eventId: String,
    val position: Int,
    val accountId: String,
    val amountUnscaled: Long,
    val amountScale: Int,
    val amountCurrency: String,
    val reversalOf: String?,
)

@Entity(
    tableName = "finance_statistic",
    foreignKeys = [
        ForeignKey(entity = EventEntity::class, parentColumns = ["id"], childColumns = ["eventId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = CategoryEntity::class, parentColumns = ["id"], childColumns = ["categoryId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = StatisticEntity::class, parentColumns = ["id"], childColumns = ["reversalOf"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [
        Index(value = ["eventId", "position"], unique = true),
        Index(value = ["categoryId"], unique = false),
        Index(value = ["reversalOf"], unique = true),
    ],
)
internal data class StatisticEntity(
    @PrimaryKey val id: String,
    val eventId: String,
    val position: Int,
    val categoryId: String,
    val direction: String,
    val amountUnscaled: Long,
    val amountScale: Int,
    val amountCurrency: String,
    val reversalOf: String?,
)

@Entity(
    tableName = "finance_budget",
    foreignKeys = [
        ForeignKey(entity = EventEntity::class, parentColumns = ["id"], childColumns = ["eventId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = CategoryEntity::class, parentColumns = ["id"], childColumns = ["categoryId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = BudgetEntity::class, parentColumns = ["id"], childColumns = ["reversalOf"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [
        Index(value = ["eventId", "position"], unique = true),
        Index(value = ["categoryId"], unique = false),
        Index(value = ["reversalOf"], unique = true),
    ],
)
internal data class BudgetEntity(
    @PrimaryKey val id: String,
    val eventId: String,
    val position: Int,
    val categoryId: String,
    val amountUnscaled: Long,
    val amountScale: Int,
    val amountCurrency: String,
    val reversalOf: String?,
)

@Entity(
    tableName = "finance_mutation",
    indices = [
        Index(value = ["opId"], unique = true),
        Index(value = ["aggregateId", "localRevision"], unique = true),
    ],
)
internal data class MutationEntity(
    @PrimaryKey(autoGenerate = true) val sequence: Long = 0,
    val opId: String,
    val ownerId: String,
    val epochId: String,
    val aggregateId: String,
    val localRevision: Long,
    val baseLocalRevision: Long,
    val intentVersion: Int,
    val kind: String,
    val payload: String,
    val state: String,
    val createdAt: Long,
)

@Dao
internal interface FinanceDao {
    @Insert fun insert(value: LedgerEntity)

    @Query("SELECT * FROM finance_ledger")
    fun ledgers(): List<LedgerEntity>

    @Query("UPDATE finance_ledger SET isDefault=0,localRevision=localRevision+1,updatedAt=:now WHERE ownerId=:owner AND epochId=:epoch AND isDefault=1 AND id!=:id")
    fun unsetOtherDefaults(owner: String, epoch: String, id: String, now: Long): Int

    @Update fun update(value: LedgerEntity)

    @Insert fun insert(value: FinanceGroupEntity)

    @Query("SELECT * FROM finance_group")
    fun groups(): List<FinanceGroupEntity>

    @Update fun update(value: FinanceGroupEntity)

    @Insert fun insert(value: CategoryEntity)

    @Query("SELECT * FROM finance_category")
    fun categories(): List<CategoryEntity>

    @Update fun update(value: CategoryEntity)

    @Query("UPDATE finance_event SET categoryId=:targetId WHERE categoryId=:sourceId") fun migrateEventCategory(sourceId: String, targetId: String)

    @Query("UPDATE finance_statistic SET categoryId=:targetId WHERE categoryId=:sourceId") fun migrateStatisticCategory(sourceId: String, targetId: String)

    @Query("UPDATE finance_budget SET categoryId=:targetId WHERE categoryId=:sourceId") fun migrateBudgetCategory(sourceId: String, targetId: String)

    @Query("UPDATE finance_record_template SET categoryId=:targetId WHERE categoryId=:sourceId") fun migrateTemplateCategory(sourceId: String, targetId: String)

    @Query("UPDATE finance_budget_preference SET ledgerId=:targetId WHERE ledgerId=:sourceId") fun moveBudgetPreferences(sourceId: String, targetId: String)

    @Query("UPDATE finance_budget_template SET ledgerId=:targetId WHERE ledgerId=:sourceId") fun moveBudgetTemplates(sourceId: String, targetId: String)

    @Query("UPDATE finance_budget_override SET ledgerId=:targetId WHERE ledgerId=:sourceId") fun moveBudgetOverrides(sourceId: String, targetId: String)

    @Insert fun insert(value: AccountEntity)

    @Update fun update(value: AccountEntity)

    @Query("SELECT * FROM finance_account")
    fun accounts(): List<AccountEntity>

    @Insert fun insert(value: RecordEntity)

    @Query("SELECT * FROM finance_record")
    fun records(): List<RecordEntity>

    @Update fun update(value: RecordEntity)

    @Insert fun insert(value: EventEntity)

    @Query("SELECT * FROM finance_event")
    fun events(): List<EventEntity>

    @Insert fun insert(value: AllocationEntity)

    @Query("SELECT * FROM finance_allocation")
    fun allocations(): List<AllocationEntity>

    @Insert fun insert(value: PostingEntity)

    @Query("SELECT * FROM finance_posting")
    fun postings(): List<PostingEntity>

    @Insert fun insert(value: StatisticEntity)

    @Query("SELECT * FROM finance_statistic")
    fun statistics(): List<StatisticEntity>

    @Insert fun insert(value: BudgetEntity)

    @Query("SELECT * FROM finance_budget")
    fun budgets(): List<BudgetEntity>

    @Insert fun insert(value: MutationEntity)

    @Query("SELECT * FROM finance_mutation")
    fun mutations(): List<MutationEntity>

    @Query("SELECT COUNT(*) FROM finance_event WHERE recordId=:recordId AND kind NOT IN ('EXPENSE','INCOME','REVERSAL')")
    fun unsupportedLinkedEventCount(recordId: String): Int

    @Query("SELECT COUNT(*) FROM finance_event WHERE recordId=:recordId AND kind='REVERSAL'")
    fun reversalEventCount(recordId: String): Int

    @Query("SELECT COUNT(*) FROM finance_event WHERE recordId=:recordId AND kind='TRANSFER'")
    fun transferEventCount(recordId: String): Int

    @Query("SELECT COUNT(*) FROM finance_event WHERE ledgerId=:ledgerId AND recordId IS NULL AND kind IN ('EXPENSE','INCOME','TRANSFER')")
    fun orphanEventCount(ledgerId: String): Int

    @Query("SELECT COUNT(*) FROM finance_record r LEFT JOIN finance_event e ON e.recordId=r.id WHERE r.ownerId=:ownerId AND r.epochId=:epochId AND r.deletedAt IS NULL AND e.id IS NULL")
    fun activeRecordWithoutEventCount(ownerId: String, epochId: String): Int
}

internal fun ledgerCategoryMigration15To16(
    owner: String,
    epoch: String,
) = object : Migration(15, 16) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.query("SELECT ownerId,epochId FROM dataset_identity").use { cursor ->
            check(cursor.count == 1 && cursor.moveToFirst() && cursor.getString(0) == owner && cursor.getString(1) == epoch)
        }
        db.query("SELECT COUNT(*) FROM finance_ledger WHERE ownerId!=? OR epochId!=?", arrayOf(owner, epoch)).use {
            check(it.moveToFirst() && it.getLong(0) == 0L)
        }
        db.query("SELECT COUNT(*) FROM finance_category WHERE ownerId!=? OR epochId!=?", arrayOf(owner, epoch)).use {
            check(it.moveToFirst() && it.getLong(0) == 0L)
        }
        db.execSQL("ALTER TABLE `finance_ledger` ADD COLUMN `sortOrder` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `finance_ledger` ADD COLUMN `isDefault` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `finance_ledger` ADD COLUMN `archivedAt` INTEGER")
        db.execSQL("UPDATE finance_ledger SET isDefault=1 WHERE deletedAt IS NULL AND id=(SELECT id FROM finance_ledger WHERE deletedAt IS NULL ORDER BY createdAt,id LIMIT 1)")
        db.execSQL("ALTER TABLE `finance_category` ADD COLUMN `sortOrder` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `finance_category` ADD COLUMN `parentId` TEXT")
        db.execSQL("ALTER TABLE `finance_category` ADD COLUMN `archivedAt` INTEGER")
        db.execSQL("ALTER TABLE `finance_category` ADD COLUMN `excludeBudget` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("DROP INDEX IF EXISTS `index_finance_category_ledgerId_purpose`")
    }
}

internal fun recordClaimMigration5To6(
    owner: String,
    epoch: String,
) = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.query("SELECT ownerId,epochId FROM dataset_identity").use { cursor ->
            check(cursor.count == 1 && cursor.moveToFirst() && cursor.getString(0) == owner && cursor.getString(1) == epoch)
        }
        db.execSQL("ALTER TABLE `finance_record` ADD COLUMN `claimState` TEXT NOT NULL DEFAULT 'NONE'")
    }
}

internal fun accountLifecycleMigration8To9(
    owner: String,
    epoch: String,
) = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.query("SELECT ownerId,epochId FROM dataset_identity").use { cursor ->
            check(cursor.count == 1 && cursor.moveToFirst() && cursor.getString(0) == owner && cursor.getString(1) == epoch)
        }
        db.execSQL("ALTER TABLE `finance_account` ADD COLUMN `archivedAt` INTEGER")
        db.execSQL("ALTER TABLE `finance_account` ADD COLUMN `includeInAssets` INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE `finance_account` ADD COLUMN `selectable` INTEGER NOT NULL DEFAULT 1")
    }
}

internal fun accountGroupsMigration9To10(
    owner: String,
    epoch: String,
) = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.query("SELECT ownerId,epochId FROM dataset_identity").use { cursor ->
            check(cursor.count == 1 && cursor.moveToFirst() && cursor.getString(0) == owner && cursor.getString(1) == epoch)
        }
        db.query("SELECT COUNT(*) FROM finance_group").use { cursor ->
            check(cursor.moveToFirst())
            val groupCount = cursor.getLong(0)
            if (groupCount == 0L) {
                db.query(
                    """
                    SELECT (SELECT COUNT(*) FROM finance_account)
                         + (SELECT COUNT(*) FROM finance_ledger)
                         + (SELECT COUNT(*) FROM finance_category)
                         + (SELECT COUNT(*) FROM finance_record)
                         + (SELECT COUNT(*) FROM finance_event)
                         + (SELECT COUNT(*) FROM finance_mutation)
                    """.trimIndent(),
                ).use { empty ->
                    check(empty.moveToFirst() && empty.getLong(0) == 0L)
                }
            } else {
                db.query("SELECT kind,deletedAt FROM finance_group WHERE ownerId=? AND epochId=?", arrayOf(owner, epoch)).use {
                    check(it.count == 2)
                    var accountGroups = 0
                    var legacyTagGroups = 0
                    while (it.moveToNext()) {
                        check(it.isNull(1))
                        when (it.getString(0)) {
                            "ACCOUNT" -> accountGroups++
                            "TAG" -> legacyTagGroups++
                            else -> error("Unexpected finance group kind")
                        }
                    }
                    check(accountGroups == 1 && legacyTagGroups == 1)
                }
            }
        }
        db.query("SELECT COUNT(*) FROM finance_group WHERE ownerId!=? OR epochId!=?", arrayOf(owner, epoch)).use {
            check(it.moveToFirst() && it.getLong(0) == 0L)
        }
        db.query("SELECT COUNT(*) FROM finance_account a LEFT JOIN finance_group g ON g.id=a.groupId WHERE g.id IS NULL OR g.kind!='ACCOUNT' OR (a.deletedAt IS NULL AND g.deletedAt IS NOT NULL)").use {
            check(it.moveToFirst() && it.getLong(0) == 0L)
        }
        db.query("SELECT COUNT(*) FROM finance_account WHERE ownerId!=? OR epochId!=? OR localRevision<=0", arrayOf(owner, epoch)).use {
            check(it.moveToFirst() && it.getLong(0) == 0L)
        }
        db.execSQL("ALTER TABLE `finance_group` ADD COLUMN `sortOrder` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `finance_group` ADD COLUMN `isDefault` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `finance_account` ADD COLUMN `sortOrder` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("UPDATE finance_group SET isDefault=1 WHERE id=(SELECT id FROM finance_group WHERE kind='ACCOUNT' AND deletedAt IS NULL)")
        db.query("SELECT id FROM finance_account WHERE deletedAt IS NULL ORDER BY groupId,createdAt,id").use { cursor ->
            var position = 0L
            var groupId: String? = null
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                db.query("SELECT groupId FROM finance_account WHERE id=?", arrayOf(id)).use { row ->
                    check(row.moveToFirst())
                    val nextGroupId = row.getString(0)
                    if (nextGroupId != groupId) {
                        groupId = nextGroupId
                        position = 0
                    }
                    db.execSQL("UPDATE finance_account SET sortOrder=? WHERE id=?", arrayOf(position, id))
                    position = Math.addExact(position, 1)
                }
            }
        }
    }
}

/** Adds financial facts without touching the format-1 identity or key envelope. */
internal fun financeMigration1To2(
    owner: String,
    epoch: String,
) = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.query("SELECT * FROM dataset_identity").use { cursor ->
            check(cursor.count == 1 && cursor.moveToFirst())
            check(cursor.getInt(cursor.getColumnIndexOrThrow("singleton")) == 1)
            check(cursor.getString(cursor.getColumnIndexOrThrow("ownerId")) == owner)
            check(cursor.getString(cursor.getColumnIndexOrThrow("epochId")) == epoch)
            check(cursor.getInt(cursor.getColumnIndexOrThrow("schemaVersion")) == 1)
            check(cursor.getLong(cursor.getColumnIndexOrThrow("lastAppliedSeq")) >= 0)
        }
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `finance_ledger` (`id` TEXT NOT NULL, `ownerId` TEXT NOT NULL, `epochId` TEXT NOT NULL, `name` TEXT NOT NULL, `baseCurrency` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `localRevision` INTEGER NOT NULL, `deletedAt` INTEGER, PRIMARY KEY(`id`))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `finance_group` (`id` TEXT NOT NULL, `ownerId` TEXT NOT NULL, `epochId` TEXT NOT NULL, `kind` TEXT NOT NULL, `name` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `localRevision` INTEGER NOT NULL, `deletedAt` INTEGER, PRIMARY KEY(`id`))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `finance_category` (`id` TEXT NOT NULL, `ownerId` TEXT NOT NULL, `epochId` TEXT NOT NULL, `ledgerId` TEXT NOT NULL, `name` TEXT NOT NULL, `direction` TEXT NOT NULL, `purpose` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `localRevision` INTEGER NOT NULL, `deletedAt` INTEGER, PRIMARY KEY(`id`), FOREIGN KEY(`ledgerId`) REFERENCES `finance_ledger`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_finance_category_ledgerId` ON `finance_category` (`ledgerId`)")
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_finance_category_ledgerId_purpose` ON `finance_category` (`ledgerId`, `purpose`)",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `finance_account` (`id` TEXT NOT NULL, `ownerId` TEXT NOT NULL, `epochId` TEXT NOT NULL, `groupId` TEXT NOT NULL, `openingEventId` TEXT NOT NULL, `name` TEXT NOT NULL, `currency` TEXT NOT NULL, `type` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `localRevision` INTEGER NOT NULL, `deletedAt` INTEGER, PRIMARY KEY(`id`), FOREIGN KEY(`groupId`) REFERENCES `finance_group`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT, FOREIGN KEY(`openingEventId`) REFERENCES `finance_event`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_finance_account_groupId` ON `finance_account` (`groupId`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_finance_account_openingEventId` ON `finance_account` (`openingEventId`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `finance_record` (`id` TEXT NOT NULL, `ownerId` TEXT NOT NULL, `epochId` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `localRevision` INTEGER NOT NULL, `deletedAt` INTEGER, PRIMARY KEY(`id`))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `finance_event` (`id` TEXT NOT NULL, `ownerId` TEXT NOT NULL, `epochId` TEXT NOT NULL, `ledgerId` TEXT NOT NULL, `recordId` TEXT, `localRevision` INTEGER NOT NULL, `kind` TEXT NOT NULL, `occurredAtMillis` INTEGER NOT NULL, `timezone` TEXT NOT NULL, `localDate` TEXT NOT NULL, `remark` TEXT NOT NULL, `categoryId` TEXT, `countInStatistics` INTEGER NOT NULL, `countInBudget` INTEGER NOT NULL, `amountUnscaled` INTEGER, `amountScale` INTEGER, `amountCurrency` TEXT, `discountUnscaled` INTEGER, `discountScale` INTEGER, `discountCurrency` TEXT, `reversalOf` TEXT, `createdAt` INTEGER NOT NULL, `origin` TEXT NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`ledgerId`) REFERENCES `finance_ledger`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT, FOREIGN KEY(`recordId`) REFERENCES `finance_record`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT, FOREIGN KEY(`categoryId`) REFERENCES `finance_category`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT, FOREIGN KEY(`reversalOf`) REFERENCES `finance_event`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_finance_event_ledgerId` ON `finance_event` (`ledgerId`)")
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_finance_event_recordId_localRevision_kind` ON `finance_event` (`recordId`, `localRevision`, `kind`)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_finance_event_categoryId` ON `finance_event` (`categoryId`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_finance_event_reversalOf` ON `finance_event` (`reversalOf`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `finance_allocation` (`id` TEXT NOT NULL, `eventId` TEXT NOT NULL, `position` INTEGER NOT NULL, `role` TEXT NOT NULL, `accountId` TEXT NOT NULL, `amountUnscaled` INTEGER NOT NULL, `amountScale` INTEGER NOT NULL, `amountCurrency` TEXT NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`eventId`) REFERENCES `finance_event`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT, FOREIGN KEY(`accountId`) REFERENCES `finance_account`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT)",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_finance_allocation_eventId_position` ON `finance_allocation` (`eventId`, `position`)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_finance_allocation_accountId` ON `finance_allocation` (`accountId`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `finance_posting` (`id` TEXT NOT NULL, `eventId` TEXT NOT NULL, `position` INTEGER NOT NULL, `accountId` TEXT NOT NULL, `amountUnscaled` INTEGER NOT NULL, `amountScale` INTEGER NOT NULL, `amountCurrency` TEXT NOT NULL, `reversalOf` TEXT, PRIMARY KEY(`id`), FOREIGN KEY(`eventId`) REFERENCES `finance_event`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT, FOREIGN KEY(`accountId`) REFERENCES `finance_account`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT, FOREIGN KEY(`reversalOf`) REFERENCES `finance_posting`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT)",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_finance_posting_eventId_position` ON `finance_posting` (`eventId`, `position`)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_finance_posting_accountId` ON `finance_posting` (`accountId`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_finance_posting_reversalOf` ON `finance_posting` (`reversalOf`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `finance_statistic` (`id` TEXT NOT NULL, `eventId` TEXT NOT NULL, `position` INTEGER NOT NULL, `categoryId` TEXT NOT NULL, `direction` TEXT NOT NULL, `amountUnscaled` INTEGER NOT NULL, `amountScale` INTEGER NOT NULL, `amountCurrency` TEXT NOT NULL, `reversalOf` TEXT, PRIMARY KEY(`id`), FOREIGN KEY(`eventId`) REFERENCES `finance_event`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT, FOREIGN KEY(`categoryId`) REFERENCES `finance_category`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT, FOREIGN KEY(`reversalOf`) REFERENCES `finance_statistic`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT)",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_finance_statistic_eventId_position` ON `finance_statistic` (`eventId`, `position`)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_finance_statistic_categoryId` ON `finance_statistic` (`categoryId`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_finance_statistic_reversalOf` ON `finance_statistic` (`reversalOf`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `finance_budget` (`id` TEXT NOT NULL, `eventId` TEXT NOT NULL, `position` INTEGER NOT NULL, `categoryId` TEXT NOT NULL, `amountUnscaled` INTEGER NOT NULL, `amountScale` INTEGER NOT NULL, `amountCurrency` TEXT NOT NULL, `reversalOf` TEXT, PRIMARY KEY(`id`), FOREIGN KEY(`eventId`) REFERENCES `finance_event`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT, FOREIGN KEY(`categoryId`) REFERENCES `finance_category`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT, FOREIGN KEY(`reversalOf`) REFERENCES `finance_budget`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT)",
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_finance_budget_eventId_position` ON `finance_budget` (`eventId`, `position`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_finance_budget_categoryId` ON `finance_budget` (`categoryId`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_finance_budget_reversalOf` ON `finance_budget` (`reversalOf`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `finance_mutation` (`sequence` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, `opId` TEXT NOT NULL, `ownerId` TEXT NOT NULL, `epochId` TEXT NOT NULL, `aggregateId` TEXT NOT NULL, `localRevision` INTEGER NOT NULL, `baseLocalRevision` INTEGER NOT NULL, `intentVersion` INTEGER NOT NULL, `kind` TEXT NOT NULL, `payload` TEXT NOT NULL, `state` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)",
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_finance_mutation_opId` ON `finance_mutation` (`opId`)")
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_finance_mutation_aggregateId_localRevision` ON `finance_mutation` (`aggregateId`, `localRevision`)",
        )
    }
}
