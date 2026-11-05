package dev.icos.app.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName = "dataset_business_preferences")
data class BusinessPreferencesEntity(
    @PrimaryKey val singleton: Int = 1,
    val ownerId: String,
    val epochId: String,
    val localRevision: Long = 1,
    val refundExcessAsIncome: Boolean = false,
    val showRefundsInRecordList: Boolean = true,
    @ColumnInfo(defaultValue = "0") val loanPrincipalAsIncome: Boolean = false,
    @ColumnInfo(defaultValue = "0") val loanRepaymentAsExpense: Boolean = false,
    @ColumnInfo(defaultValue = "1") val loanInterestAsExpense: Boolean = true,
)

@Dao
internal interface BusinessPreferencesDao {
    @Query("SELECT * FROM dataset_business_preferences WHERE singleton = 1") fun read(): BusinessPreferencesEntity?
    @Insert fun insert(value: BusinessPreferencesEntity)
    @Update fun update(value: BusinessPreferencesEntity)
}

internal fun businessPreferencesMigration6To7(owner: String, epoch: String) = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.query("SELECT ownerId,epochId FROM dataset_identity").use {
            check(it.count == 1 && it.moveToFirst() && it.getString(0) == owner && it.getString(1) == epoch)
        }
        db.execSQL("CREATE TABLE `dataset_business_preferences` (`singleton` INTEGER NOT NULL, `ownerId` TEXT NOT NULL, `epochId` TEXT NOT NULL, `localRevision` INTEGER NOT NULL, `refundExcessAsIncome` INTEGER NOT NULL, `showRefundsInRecordList` INTEGER NOT NULL, PRIMARY KEY(`singleton`))")
        db.execSQL(
            "INSERT INTO `dataset_business_preferences` VALUES (1, ?, ?, 1, 0, 1)",
            arrayOf(owner, epoch),
        )
    }
}
