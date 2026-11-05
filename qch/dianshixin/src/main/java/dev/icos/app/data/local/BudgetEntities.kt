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
    tableName = "finance_budget_preference",
    foreignKeys = [
        ForeignKey(entity = LedgerEntity::class, parentColumns = ["id"], childColumns = ["ledgerId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index(value = ["ledgerId"], unique = true)],
)
internal data class BudgetPreferenceEntity(
    @PrimaryKey val id: String,
    val ownerId: String,
    val epochId: String,
    val ledgerId: String,
    val enabled: Boolean,
    val localRevision: Long,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "finance_budget_template",
    foreignKeys = [
        ForeignKey(entity = LedgerEntity::class, parentColumns = ["id"], childColumns = ["ledgerId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index(value = ["ledgerId", "periodType", "effectiveFrom"], unique = true)],
)
internal data class BudgetTemplateEntity(
    @PrimaryKey val id: String,
    val ownerId: String,
    val epochId: String,
    val ledgerId: String,
    val periodType: String,
    val effectiveFrom: String,
    val amountUnscaled: Long,
    val amountScale: Int,
    val amountCurrency: String,
    val localRevision: Long,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "finance_budget_override",
    foreignKeys = [
        ForeignKey(entity = LedgerEntity::class, parentColumns = ["id"], childColumns = ["ledgerId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index(value = ["ledgerId", "periodType", "periodStart"], unique = true)],
)
internal data class BudgetOverrideEntity(
    @PrimaryKey val id: String,
    val ownerId: String,
    val epochId: String,
    val ledgerId: String,
    val periodType: String,
    val periodStart: String,
    val amountUnscaled: Long,
    val amountScale: Int,
    val amountCurrency: String,
    val localRevision: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
)

@Dao
internal interface BudgetDao {
    @Query("SELECT * FROM finance_budget_preference")
    fun preferences(): List<BudgetPreferenceEntity>

    @Query("SELECT * FROM finance_budget_template")
    fun templates(): List<BudgetTemplateEntity>

    @Query("SELECT * FROM finance_budget_override")
    fun overrides(): List<BudgetOverrideEntity>

    @Insert fun insert(value: BudgetPreferenceEntity)

    @Update fun update(value: BudgetPreferenceEntity)

    @Insert fun insert(value: BudgetTemplateEntity)

    @Update fun update(value: BudgetTemplateEntity)

    @Insert fun insert(value: BudgetOverrideEntity)

    @Update fun update(value: BudgetOverrideEntity)
}

internal fun budgetMigration2To3(
    owner: String,
    epoch: String,
) = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.query("SELECT ownerId,epochId FROM dataset_identity").use {
            check(it.count == 1 && it.moveToFirst() && it.getString(0) == owner && it.getString(1) == epoch)
        }
        db.execSQL(
            "CREATE TABLE `finance_budget_preference` (`id` TEXT NOT NULL, `ownerId` TEXT NOT NULL, `epochId` TEXT NOT NULL, `ledgerId` TEXT NOT NULL, `enabled` INTEGER NOT NULL, `localRevision` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`ledgerId`) REFERENCES `finance_ledger`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT)",
        )
        db.execSQL("CREATE UNIQUE INDEX `index_finance_budget_preference_ledgerId` ON `finance_budget_preference` (`ledgerId`)")
        for ((table, date) in listOf("finance_budget_template" to "effectiveFrom", "finance_budget_override" to "periodStart")) {
            val tombstone = if (table == "finance_budget_override") ", `deletedAt` INTEGER" else ""
            db.execSQL(
                "CREATE TABLE `$table` (`id` TEXT NOT NULL, `ownerId` TEXT NOT NULL, `epochId` TEXT NOT NULL, `ledgerId` TEXT NOT NULL, `periodType` TEXT NOT NULL, `$date` TEXT NOT NULL, `amountUnscaled` INTEGER NOT NULL, `amountScale` INTEGER NOT NULL, `amountCurrency` TEXT NOT NULL, `localRevision` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL$tombstone, PRIMARY KEY(`id`), FOREIGN KEY(`ledgerId`) REFERENCES `finance_ledger`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT)",
            )
            db.execSQL("CREATE UNIQUE INDEX `index_${table}_ledgerId_periodType_$date` ON `$table` (`ledgerId`, `periodType`, `$date`)")
        }
    }
}
