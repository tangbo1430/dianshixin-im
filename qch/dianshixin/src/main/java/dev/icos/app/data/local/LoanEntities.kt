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
import dev.icos.app.domain.LoanTerm
import java.util.UUID

@Entity(
    tableName = "loan_plan",
    foreignKeys = [
        ForeignKey(entity = AccountEntity::class, parentColumns = ["id"], childColumns = ["accountId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = EventEntity::class, parentColumns = ["id"], childColumns = ["disbursementEventId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index(value = ["activeAccountKey"], unique = true), Index(value = ["accountId"]), Index(value = ["disbursementEventId"])],
)
internal data class LoanPlanEntity(
    @PrimaryKey val id: String,
    val ownerId: String,
    val epochId: String,
    val accountId: String,
    val activeAccountKey: String?,
    val disbursementEventId: String?,
    val currency: String,
    val principalUnscaled: Long,
    val principalScale: Int,
    val annualRateNumerator: Long,
    val annualRateDenominator: Long,
    val termCount: Int,
    val paidTermCount: Int,
    val repaymentType: String,
    val firstDueDate: String,
    val state: String,
    val localRevision: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
)

internal fun LoanTerm.toEntity(planId: String, revision: Long, historicalPaid: Boolean, owner: String, epoch: String) =
    LoanTermEntity(
        id = UUID.randomUUID().toString(), ownerId = owner, epochId = epoch, planId = planId,
        activeTermKey = "$planId:$termIndex", termIndex = termIndex, dueDate = dueDate,
        openingPrincipalUnscaled = openingPrincipal.unscaled, openingPrincipalScale = openingPrincipal.scale,
        principalUnscaled = principal.unscaled, principalScale = principal.scale,
        interestUnscaled = interest.unscaled, interestScale = interest.scale,
        closingPrincipalUnscaled = closingPrincipal.unscaled, closingPrincipalScale = closingPrincipal.scale,
        planRevision = revision, postedEventId = null, paidEventId = null,
        paidPrincipalUnscaled = if (historicalPaid) principal.unscaled else null,
        paidPrincipalScale = if (historicalPaid) principal.scale else null,
        paidInterestUnscaled = if (historicalPaid) interest.unscaled else null,
        paidInterestScale = if (historicalPaid) interest.scale else null,
        paidAtMillis = null, dueState = if (historicalPaid) "PAID" else dueState.name, localRevision = 1,
    )

@Entity(
    tableName = "loan_term",
    foreignKeys = [
        ForeignKey(entity = LoanPlanEntity::class, parentColumns = ["id"], childColumns = ["planId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = EventEntity::class, parentColumns = ["id"], childColumns = ["postedEventId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = EventEntity::class, parentColumns = ["id"], childColumns = ["paidEventId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [
        Index(value = ["planId"]),
        Index(value = ["activeTermKey"], unique = true),
        Index(value = ["postedEventId"], unique = true),
        Index(value = ["paidEventId"], unique = true),
    ],
)
internal data class LoanTermEntity(
    @PrimaryKey val id: String,
    val ownerId: String,
    val epochId: String,
    val planId: String,
    val activeTermKey: String?,
    val termIndex: Int,
    val dueDate: String,
    val openingPrincipalUnscaled: Long,
    val openingPrincipalScale: Int,
    val principalUnscaled: Long,
    val principalScale: Int,
    val interestUnscaled: Long,
    val interestScale: Int,
    val closingPrincipalUnscaled: Long,
    val closingPrincipalScale: Int,
    val planRevision: Long,
    val postedEventId: String?,
    val paidEventId: String?,
    val paidPrincipalUnscaled: Long?,
    val paidPrincipalScale: Int?,
    val paidInterestUnscaled: Long?,
    val paidInterestScale: Int?,
    val paidAtMillis: Long?,
    val dueState: String,
    val localRevision: Long,
)

@Entity(
    tableName = "loan_rate_change",
    foreignKeys = [ForeignKey(entity = LoanPlanEntity::class, parentColumns = ["id"], childColumns = ["planId"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index(value = ["planId", "toRevision"], unique = true)],
)
internal data class LoanRateChangeEntity(
    @PrimaryKey val id: String,
    val ownerId: String,
    val epochId: String,
    val planId: String,
    val effectiveTerm: Int,
    val oldRateNumerator: Long,
    val oldRateDenominator: Long,
    val newRateNumerator: Long,
    val newRateDenominator: Long,
    val fromRevision: Long,
    val toRevision: Long,
    val createdAt: Long,
)

@Dao
internal interface LoanDao {
    @Query("SELECT * FROM loan_plan") fun plans(): List<LoanPlanEntity>
    @Query("SELECT * FROM loan_term") fun terms(): List<LoanTermEntity>
    @Query("SELECT * FROM loan_rate_change") fun rateChanges(): List<LoanRateChangeEntity>
    @Insert fun insert(value: LoanPlanEntity)
    @Insert fun insert(value: LoanTermEntity)
    @Insert fun insert(value: LoanRateChangeEntity)
    @Update fun update(value: LoanPlanEntity)
    @Update fun update(value: LoanTermEntity)
}

internal fun loanMigration11To12(owner: String, epoch: String) = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.query("SELECT ownerId,epochId,schemaVersion,lastAppliedSeq FROM dataset_identity").use { c ->
            check(c.count == 1 && c.moveToFirst())
            check(c.getString(0) == owner && c.getString(1) == epoch && c.getInt(2) == 1 && c.getLong(3) >= 0)
        }
        val tables = listOf("loan_plan", "loan_term", "loan_rate_change")
        tables.forEach { name ->
            db.query("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name=?", arrayOf(name)).use { c ->
                check(c.moveToFirst() && c.getLong(0) == 0L)
            }
        }
        db.query("PRAGMA table_info(dataset_business_preferences)").use { c ->
            val names = buildSet { while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("name"))) }
            check("loanPrincipalAsIncome" !in names && "loanRepaymentAsExpense" !in names && "loanInterestAsExpense" !in names)
            check("ownerId" in names && "epochId" in names && "localRevision" in names)
        }
        db.query("SELECT ownerId,epochId FROM dataset_business_preferences").use { c ->
            check(c.count <= 1)
            if (c.moveToFirst()) check(c.getString(0) == owner && c.getString(1) == epoch)
        }
        db.execSQL("CREATE TABLE `loan_plan` (`id` TEXT NOT NULL, `ownerId` TEXT NOT NULL, `epochId` TEXT NOT NULL, `accountId` TEXT NOT NULL, `activeAccountKey` TEXT, `disbursementEventId` TEXT, `currency` TEXT NOT NULL, `principalUnscaled` INTEGER NOT NULL, `principalScale` INTEGER NOT NULL, `annualRateNumerator` INTEGER NOT NULL, `annualRateDenominator` INTEGER NOT NULL, `termCount` INTEGER NOT NULL, `paidTermCount` INTEGER NOT NULL, `repaymentType` TEXT NOT NULL, `firstDueDate` TEXT NOT NULL, `state` TEXT NOT NULL, `localRevision` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `deletedAt` INTEGER, PRIMARY KEY(`id`), FOREIGN KEY(`accountId`) REFERENCES `finance_account`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT, FOREIGN KEY(`disbursementEventId`) REFERENCES `finance_event`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT)")
        db.execSQL("CREATE UNIQUE INDEX `index_loan_plan_activeAccountKey` ON `loan_plan` (`activeAccountKey`)")
        db.execSQL("CREATE INDEX `index_loan_plan_accountId` ON `loan_plan` (`accountId`)")
        db.execSQL("CREATE INDEX `index_loan_plan_disbursementEventId` ON `loan_plan` (`disbursementEventId`)")
        db.execSQL("CREATE TABLE `loan_term` (`id` TEXT NOT NULL, `ownerId` TEXT NOT NULL, `epochId` TEXT NOT NULL, `planId` TEXT NOT NULL, `activeTermKey` TEXT, `termIndex` INTEGER NOT NULL, `dueDate` TEXT NOT NULL, `openingPrincipalUnscaled` INTEGER NOT NULL, `openingPrincipalScale` INTEGER NOT NULL, `principalUnscaled` INTEGER NOT NULL, `principalScale` INTEGER NOT NULL, `interestUnscaled` INTEGER NOT NULL, `interestScale` INTEGER NOT NULL, `closingPrincipalUnscaled` INTEGER NOT NULL, `closingPrincipalScale` INTEGER NOT NULL, `planRevision` INTEGER NOT NULL, `postedEventId` TEXT, `paidEventId` TEXT, `paidPrincipalUnscaled` INTEGER, `paidPrincipalScale` INTEGER, `paidInterestUnscaled` INTEGER, `paidInterestScale` INTEGER, `paidAtMillis` INTEGER, `dueState` TEXT NOT NULL, `localRevision` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`planId`) REFERENCES `loan_plan`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT, FOREIGN KEY(`postedEventId`) REFERENCES `finance_event`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT, FOREIGN KEY(`paidEventId`) REFERENCES `finance_event`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT)")
        db.execSQL("CREATE INDEX `index_loan_term_planId` ON `loan_term` (`planId`)")
        db.execSQL("CREATE UNIQUE INDEX `index_loan_term_activeTermKey` ON `loan_term` (`activeTermKey`)")
        db.execSQL("CREATE UNIQUE INDEX `index_loan_term_postedEventId` ON `loan_term` (`postedEventId`)")
        db.execSQL("CREATE UNIQUE INDEX `index_loan_term_paidEventId` ON `loan_term` (`paidEventId`)")
        db.execSQL("CREATE TABLE `loan_rate_change` (`id` TEXT NOT NULL, `ownerId` TEXT NOT NULL, `epochId` TEXT NOT NULL, `planId` TEXT NOT NULL, `effectiveTerm` INTEGER NOT NULL, `oldRateNumerator` INTEGER NOT NULL, `oldRateDenominator` INTEGER NOT NULL, `newRateNumerator` INTEGER NOT NULL, `newRateDenominator` INTEGER NOT NULL, `fromRevision` INTEGER NOT NULL, `toRevision` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`planId`) REFERENCES `loan_plan`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT)")
        db.execSQL("CREATE UNIQUE INDEX `index_loan_rate_change_planId_toRevision` ON `loan_rate_change` (`planId`,`toRevision`)")
        db.execSQL("ALTER TABLE `dataset_business_preferences` ADD COLUMN `loanPrincipalAsIncome` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `dataset_business_preferences` ADD COLUMN `loanRepaymentAsExpense` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `dataset_business_preferences` ADD COLUMN `loanInterestAsExpense` INTEGER NOT NULL DEFAULT 1")
    }
}
