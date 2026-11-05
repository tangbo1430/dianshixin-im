package dev.icos.app.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update

@Entity(
    tableName = "saving_plan",
    indices = [Index(value = ["ownerId", "epochId", "state", "deletedAt"])],
)
internal data class SavingPlanEntity(
    @PrimaryKey val id: String,
    val ownerId: String,
    val epochId: String,
    val kind: String,
    val name: String,
    val iconRef: String?,
    val currency: String,
    val targetUnscaled: Long,
    val targetScale: Int,
    val initialSavedUnscaled: Long,
    val initialSavedScale: Int,
    val firstAmountUnscaled: Long?,
    val firstAmountScale: Int?,
    val incrementUnscaled: Long?,
    val incrementScale: Int?,
    val intervalUnit: String?,
    val intervalStep: Int?,
    val startDate: String?,
    val endDate: String?,
    val state: String,
    val allowManualEntries: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val localRevision: Long,
    val deletedAt: Long?,
)

@Entity(
    tableName = "saving_entry",
    foreignKeys = [
        ForeignKey(entity = SavingPlanEntity::class, parentColumns = ["id"], childColumns = ["planId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = RecordEntity::class, parentColumns = ["id"], childColumns = ["linkedRecordId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = EventEntity::class, parentColumns = ["id"], childColumns = ["linkedTransferEventId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [
        Index(value = ["planId"]),
        Index(value = ["linkedTransferEventId"]),
        Index(value = ["ownerId", "epochId", "planId", "occurredAtMillis"]),
        Index(value = ["ownerId", "epochId", "planId", "cardIndex", "cancelledAt"], unique = true),
        Index(value = ["linkedRecordId"], unique = true),
    ],
)
internal data class SavingEntryEntity(
    @PrimaryKey val id: String,
    val ownerId: String,
    val epochId: String,
    val planId: String,
    val kind: String,
    val amountUnscaled: Long,
    val amountScale: Int,
    val currency: String,
    val occurredAtMillis: Long,
    val cardIndex: Int?,
    val remark: String?,
    val linkedRecordId: String?,
    val linkedTransferEventId: String?,
    val createdAt: Long,
    val localRevision: Long,
    val cancelledAt: Long?,
)

@Dao
internal interface SavingDao {
    @Query("SELECT * FROM saving_plan")
    fun plans(): List<SavingPlanEntity>

    @Query("SELECT * FROM saving_entry")
    fun entries(): List<SavingEntryEntity>

    @Insert
    fun insert(plan: SavingPlanEntity)

    @Insert
    fun insert(entry: SavingEntryEntity)

    @Update
    fun update(plan: SavingPlanEntity)

    @Update
    fun update(entry: SavingEntryEntity)
}

internal fun savingMigration10To11(owner: String, epoch: String) =
    object : androidx.room.migration.Migration(10, 11) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.query("SELECT * FROM dataset_identity").use { cursor ->
                check(cursor.count == 1 && cursor.moveToFirst())
                check(cursor.getString(cursor.getColumnIndexOrThrow("ownerId")) == owner)
                check(cursor.getString(cursor.getColumnIndexOrThrow("epochId")) == epoch)
                check(cursor.getInt(cursor.getColumnIndexOrThrow("schemaVersion")) == 1)
                check(cursor.getLong(cursor.getColumnIndexOrThrow("lastAppliedSeq")) >= 0)
            }
            db.execSQL(
                "CREATE TABLE `saving_plan` (`id` TEXT NOT NULL, `ownerId` TEXT NOT NULL, `epochId` TEXT NOT NULL, `kind` TEXT NOT NULL, `name` TEXT NOT NULL, `iconRef` TEXT, `currency` TEXT NOT NULL, `targetUnscaled` INTEGER NOT NULL, `targetScale` INTEGER NOT NULL, `initialSavedUnscaled` INTEGER NOT NULL, `initialSavedScale` INTEGER NOT NULL, `firstAmountUnscaled` INTEGER, `firstAmountScale` INTEGER, `incrementUnscaled` INTEGER, `incrementScale` INTEGER, `intervalUnit` TEXT, `intervalStep` INTEGER, `startDate` TEXT, `endDate` TEXT, `state` TEXT NOT NULL, `allowManualEntries` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `localRevision` INTEGER NOT NULL, `deletedAt` INTEGER, PRIMARY KEY(`id`), CHECK((`firstAmountUnscaled` IS NULL) = (`firstAmountScale` IS NULL)), CHECK((`incrementUnscaled` IS NULL) = (`incrementScale` IS NULL)), CHECK((`intervalUnit` IS NULL) = (`intervalStep` IS NULL)))",
            )
            db.execSQL(
                "CREATE TABLE `saving_entry` (`id` TEXT NOT NULL, `ownerId` TEXT NOT NULL, `epochId` TEXT NOT NULL, `planId` TEXT NOT NULL, `kind` TEXT NOT NULL, `amountUnscaled` INTEGER NOT NULL, `amountScale` INTEGER NOT NULL, `currency` TEXT NOT NULL, `occurredAtMillis` INTEGER NOT NULL, `cardIndex` INTEGER, `remark` TEXT, `linkedRecordId` TEXT, `linkedTransferEventId` TEXT, `createdAt` INTEGER NOT NULL, `localRevision` INTEGER NOT NULL, `cancelledAt` INTEGER, PRIMARY KEY(`id`), FOREIGN KEY(`planId`) REFERENCES `saving_plan`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT, FOREIGN KEY(`linkedRecordId`) REFERENCES `finance_record`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT, FOREIGN KEY(`linkedTransferEventId`) REFERENCES `finance_event`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT, CHECK((`linkedRecordId` IS NULL) = (`linkedTransferEventId` IS NULL)), CHECK(`amountUnscaled` > 0), CHECK(`amountScale` BETWEEN 0 AND 6))",
            )
            db.execSQL("CREATE INDEX `index_saving_plan_ownerId_epochId_state_deletedAt` ON `saving_plan` (`ownerId`, `epochId`, `state`, `deletedAt`)")
            db.execSQL("CREATE INDEX `index_saving_entry_ownerId_epochId_planId_occurredAtMillis` ON `saving_entry` (`ownerId`, `epochId`, `planId`, `occurredAtMillis`)")
            db.execSQL("CREATE INDEX `index_saving_entry_planId` ON `saving_entry` (`planId`)")
            db.execSQL("CREATE INDEX `index_saving_entry_linkedTransferEventId` ON `saving_entry` (`linkedTransferEventId`)")
            db.execSQL("CREATE UNIQUE INDEX `index_saving_entry_ownerId_epochId_planId_cardIndex_cancelledAt` ON `saving_entry` (`ownerId`, `epochId`, `planId`, `cardIndex`, `cancelledAt`)")
            db.execSQL("CREATE UNIQUE INDEX `index_saving_entry_linkedRecordId` ON `saving_entry` (`linkedRecordId`)")
        }
    }
