package dev.icos.app.data.local

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

@Entity(
    tableName = "credit_profile",
    foreignKeys = [ForeignKey(entity = AccountEntity::class, parentColumns = ["id"], childColumns = ["accountId"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index(value = ["accountId"], unique = true)],
)
internal data class CreditProfileEntity(
    @PrimaryKey val id: String,
    val ownerId: String,
    val epochId: String,
    val accountId: String,
    val limitUnscaled: Long,
    val limitScale: Int,
    val currency: String,
    val statementDay: Int,
    val includeStatementDay: Boolean,
    val repaymentRule: String,
    val repaymentValue: Int,
    val timezone: String,
    val enabled: Boolean,
    val localRevision: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
)

@Entity(
    tableName = "credit_capacity_event",
    foreignKeys = [ForeignKey(entity = AccountEntity::class, parentColumns = ["id"], childColumns = ["accountId"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index(value = ["accountId", "occurredAtMillis"]), Index(value = ["sourceType", "sourceId"], unique = true)],
)
internal data class CreditCapacityEventEntity(
    @PrimaryKey val id: String,
    val ownerId: String,
    val epochId: String,
    val accountId: String,
    val sourceType: String,
    val sourceId: String,
    val deltaUnscaled: Long,
    val deltaScale: Int,
    val currency: String,
    val occurredAtMillis: Long,
    val reason: String,
    val localRevision: Long,
)

@Entity(
    tableName = "installment_plan",
    foreignKeys = [
        ForeignKey(entity = AccountEntity::class, parentColumns = ["id"], childColumns = ["accountId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = LedgerEntity::class, parentColumns = ["id"], childColumns = ["ledgerId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = RecordEntity::class, parentColumns = ["id"], childColumns = ["sourceRecordId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = EventEntity::class, parentColumns = ["id"], childColumns = ["creationEventId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index(value = ["accountId"]), Index(value = ["ledgerId"]), Index(value = ["sourceRecordId"], unique = true), Index(value = ["creationEventId"], unique = true)],
)
internal data class InstallmentPlanEntity(
    @PrimaryKey val id: String,
    val ownerId: String,
    val epochId: String,
    val sourceType: String,
    val sourceRecordId: String?,
    val accountId: String,
    val ledgerId: String,
    val creationEventId: String?,
    val principalUnscaled: Long,
    val principalScale: Int,
    val interestUnscaled: Long,
    val interestScale: Int,
    val currency: String,
    val termCount: Int,
    val interestMode: String,
    val remainderMode: String,
    val principalPosting: String,
    val capacityRestoreUnscaled: Long,
    val firstPostingDate: String,
    val timezone: String,
    val executor: String,
    val state: String,
    val scheduleRevision: Long,
    val localRevision: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
)

@Entity(
    tableName = "installment_term",
    foreignKeys = [
        ForeignKey(entity = InstallmentPlanEntity::class, parentColumns = ["id"], childColumns = ["planId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = EventEntity::class, parentColumns = ["id"], childColumns = ["postingEventId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = EventEntity::class, parentColumns = ["id"], childColumns = ["paidEventId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index(value = ["planId", "termIndex", "scheduleRevision"], unique = true), Index(value = ["postingEventId"], unique = true), Index(value = ["paidEventId"])],
)
internal data class InstallmentTermEntity(
    @PrimaryKey val id: String,
    val ownerId: String,
    val epochId: String,
    val planId: String,
    val termIndex: Int,
    val scheduleRevision: Long,
    val dueDate: String,
    val principalUnscaled: Long,
    val principalScale: Int,
    val interestUnscaled: Long,
    val interestScale: Int,
    val currency: String,
    val state: String,
    val postingEventId: String?,
    val paidEventId: String?,
    val paidUnscaled: Long?,
    val paidScale: Int?,
    val localRevision: Long,
)

@Entity(
    tableName = "credit_payment_allocation",
    foreignKeys = [
        ForeignKey(entity = AccountEntity::class, parentColumns = ["id"], childColumns = ["accountId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = EventEntity::class, parentColumns = ["id"], childColumns = ["paymentEventId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = InstallmentTermEntity::class, parentColumns = ["id"], childColumns = ["termId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index(value = ["paymentEventId", "termId"], unique = true), Index(value = ["accountId"]), Index(value = ["termId"])],
)
internal data class CreditPaymentAllocationEntity(
    @PrimaryKey val id: String,
    val ownerId: String,
    val epochId: String,
    val accountId: String,
    val paymentEventId: String,
    val termId: String,
    val amountUnscaled: Long,
    val amountScale: Int,
    val currency: String,
)

@Dao
internal interface CreditDao {
    @Query("SELECT * FROM credit_profile") fun profiles(): List<CreditProfileEntity>
    @Query("SELECT * FROM credit_capacity_event") fun capacityEvents(): List<CreditCapacityEventEntity>
    @Query("SELECT * FROM installment_plan") fun plans(): List<InstallmentPlanEntity>
    @Query("SELECT * FROM installment_term") fun terms(): List<InstallmentTermEntity>
    @Query("SELECT * FROM credit_payment_allocation") fun paymentAllocations(): List<CreditPaymentAllocationEntity>
    @Insert fun insert(value: CreditProfileEntity)
    @Insert fun insert(value: CreditCapacityEventEntity)
    @Insert fun insert(value: InstallmentPlanEntity)
    @Insert fun insert(value: InstallmentTermEntity)
    @Insert fun insert(value: CreditPaymentAllocationEntity)
    @Update fun update(value: CreditProfileEntity)
    @Update fun update(value: InstallmentPlanEntity)
    @Update fun update(value: InstallmentTermEntity)
}

internal fun creditInstallmentMigration16To17(owner: String, epoch: String) = object : Migration(16, 17) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.query("SELECT ownerId,epochId,schemaVersion,lastAppliedSeq FROM dataset_identity").use { c ->
            check(c.count == 1 && c.moveToFirst())
            check(c.getString(0) == owner && c.getString(1) == epoch && c.getInt(2) == 1 && c.getLong(3) >= 0)
        }
        listOf("credit_profile", "credit_capacity_event", "installment_plan", "installment_term", "credit_payment_allocation").forEach { table ->
            db.query("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name=?", arrayOf(table)).use { c ->
                check(c.moveToFirst() && c.getLong(0) == 0L) { "Room 17 table already exists: $table" }
            }
        }
        db.execSQL("CREATE TABLE `credit_profile` (`id` TEXT NOT NULL, `ownerId` TEXT NOT NULL, `epochId` TEXT NOT NULL, `accountId` TEXT NOT NULL, `limitUnscaled` INTEGER NOT NULL, `limitScale` INTEGER NOT NULL, `currency` TEXT NOT NULL, `statementDay` INTEGER NOT NULL, `includeStatementDay` INTEGER NOT NULL, `repaymentRule` TEXT NOT NULL, `repaymentValue` INTEGER NOT NULL, `timezone` TEXT NOT NULL, `enabled` INTEGER NOT NULL, `localRevision` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `deletedAt` INTEGER, PRIMARY KEY(`id`), FOREIGN KEY(`accountId`) REFERENCES `finance_account`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT)")
        db.execSQL("CREATE UNIQUE INDEX `index_credit_profile_accountId` ON `credit_profile` (`accountId`)")
        db.execSQL("CREATE TABLE `credit_capacity_event` (`id` TEXT NOT NULL, `ownerId` TEXT NOT NULL, `epochId` TEXT NOT NULL, `accountId` TEXT NOT NULL, `sourceType` TEXT NOT NULL, `sourceId` TEXT NOT NULL, `deltaUnscaled` INTEGER NOT NULL, `deltaScale` INTEGER NOT NULL, `currency` TEXT NOT NULL, `occurredAtMillis` INTEGER NOT NULL, `reason` TEXT NOT NULL, `localRevision` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`accountId`) REFERENCES `finance_account`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT)")
        db.execSQL("CREATE INDEX `index_credit_capacity_event_accountId_occurredAtMillis` ON `credit_capacity_event` (`accountId`, `occurredAtMillis`)")
        db.execSQL("CREATE UNIQUE INDEX `index_credit_capacity_event_sourceType_sourceId` ON `credit_capacity_event` (`sourceType`, `sourceId`)")
        db.execSQL("CREATE TABLE `installment_plan` (`id` TEXT NOT NULL, `ownerId` TEXT NOT NULL, `epochId` TEXT NOT NULL, `sourceType` TEXT NOT NULL, `sourceRecordId` TEXT, `accountId` TEXT NOT NULL, `ledgerId` TEXT NOT NULL, `creationEventId` TEXT, `principalUnscaled` INTEGER NOT NULL, `principalScale` INTEGER NOT NULL, `interestUnscaled` INTEGER NOT NULL, `interestScale` INTEGER NOT NULL, `currency` TEXT NOT NULL, `termCount` INTEGER NOT NULL, `interestMode` TEXT NOT NULL, `remainderMode` TEXT NOT NULL, `principalPosting` TEXT NOT NULL, `capacityRestoreUnscaled` INTEGER NOT NULL, `firstPostingDate` TEXT NOT NULL, `timezone` TEXT NOT NULL, `executor` TEXT NOT NULL, `state` TEXT NOT NULL, `scheduleRevision` INTEGER NOT NULL, `localRevision` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `deletedAt` INTEGER, PRIMARY KEY(`id`), FOREIGN KEY(`accountId`) REFERENCES `finance_account`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT, FOREIGN KEY(`ledgerId`) REFERENCES `finance_ledger`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT, FOREIGN KEY(`sourceRecordId`) REFERENCES `finance_record`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT, FOREIGN KEY(`creationEventId`) REFERENCES `finance_event`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT)")
        db.execSQL("CREATE INDEX `index_installment_plan_accountId` ON `installment_plan` (`accountId`)")
        db.execSQL("CREATE INDEX `index_installment_plan_ledgerId` ON `installment_plan` (`ledgerId`)")
        db.execSQL("CREATE UNIQUE INDEX `index_installment_plan_sourceRecordId` ON `installment_plan` (`sourceRecordId`)")
        db.execSQL("CREATE UNIQUE INDEX `index_installment_plan_creationEventId` ON `installment_plan` (`creationEventId`)")
        db.execSQL("CREATE TABLE `installment_term` (`id` TEXT NOT NULL, `ownerId` TEXT NOT NULL, `epochId` TEXT NOT NULL, `planId` TEXT NOT NULL, `termIndex` INTEGER NOT NULL, `scheduleRevision` INTEGER NOT NULL, `dueDate` TEXT NOT NULL, `principalUnscaled` INTEGER NOT NULL, `principalScale` INTEGER NOT NULL, `interestUnscaled` INTEGER NOT NULL, `interestScale` INTEGER NOT NULL, `currency` TEXT NOT NULL, `state` TEXT NOT NULL, `postingEventId` TEXT, `paidEventId` TEXT, `paidUnscaled` INTEGER, `paidScale` INTEGER, `localRevision` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`planId`) REFERENCES `installment_plan`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT, FOREIGN KEY(`postingEventId`) REFERENCES `finance_event`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT, FOREIGN KEY(`paidEventId`) REFERENCES `finance_event`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT)")
        db.execSQL("CREATE UNIQUE INDEX `index_installment_term_planId_termIndex_scheduleRevision` ON `installment_term` (`planId`, `termIndex`, `scheduleRevision`)")
        db.execSQL("CREATE UNIQUE INDEX `index_installment_term_postingEventId` ON `installment_term` (`postingEventId`)")
        db.execSQL("CREATE INDEX `index_installment_term_paidEventId` ON `installment_term` (`paidEventId`)")
        db.execSQL("CREATE TABLE `credit_payment_allocation` (`id` TEXT NOT NULL, `ownerId` TEXT NOT NULL, `epochId` TEXT NOT NULL, `accountId` TEXT NOT NULL, `paymentEventId` TEXT NOT NULL, `termId` TEXT NOT NULL, `amountUnscaled` INTEGER NOT NULL, `amountScale` INTEGER NOT NULL, `currency` TEXT NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`accountId`) REFERENCES `finance_account`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT, FOREIGN KEY(`paymentEventId`) REFERENCES `finance_event`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT, FOREIGN KEY(`termId`) REFERENCES `installment_term`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT)")
        db.execSQL("CREATE UNIQUE INDEX `index_credit_payment_allocation_paymentEventId_termId` ON `credit_payment_allocation` (`paymentEventId`, `termId`)")
        db.execSQL("CREATE INDEX `index_credit_payment_allocation_accountId` ON `credit_payment_allocation` (`accountId`)")
        db.execSQL("CREATE INDEX `index_credit_payment_allocation_termId` ON `credit_payment_allocation` (`termId`)")
    }
}
