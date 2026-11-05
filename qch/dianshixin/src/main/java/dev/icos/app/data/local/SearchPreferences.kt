package dev.icos.app.data.local

import androidx.room.Dao
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName = "dataset_search_preferences")
internal data class SearchPreferencesEntity(
    @PrimaryKey val singleton: Int = 1,
    val type: String,
    val fromDate: String,
    val throughDate: String,
    val currency: String,
    val accountId: String,
    val categoryId: String,
    @ColumnInfo(defaultValue = "''") val tagIds: String = "",
    @ColumnInfo(defaultValue = "'ANY'") val tagMatch: String = "ANY",
    val minimumAmount: String,
    val maximumAmount: String,
    val sort: String,
)

@Dao
internal interface SearchPreferencesDao {
    @Query("SELECT * FROM dataset_search_preferences WHERE singleton = 1")
    fun read(): SearchPreferencesEntity?

    @Insert
    fun insert(value: SearchPreferencesEntity)

    @Update
    fun update(value: SearchPreferencesEntity)
}

internal fun searchPreferencesMigration3To4(
    owner: String,
    epoch: String,
) = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.query("SELECT ownerId,epochId FROM dataset_identity").use {
            check(it.count == 1 && it.moveToFirst() && it.getString(0) == owner && it.getString(1) == epoch)
        }
        db.execSQL(
            "CREATE TABLE `dataset_search_preferences` (`singleton` INTEGER NOT NULL, `type` TEXT NOT NULL, `fromDate` TEXT NOT NULL, `throughDate` TEXT NOT NULL, `currency` TEXT NOT NULL, `accountId` TEXT NOT NULL, `categoryId` TEXT NOT NULL, `minimumAmount` TEXT NOT NULL, `maximumAmount` TEXT NOT NULL, `sort` TEXT NOT NULL, PRIMARY KEY(`singleton`))",
        )
    }
}
