package dev.icos.app.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(
    tableName = "related_person_profile",
    foreignKeys = [ForeignKey(entity = AccountEntity::class, parentColumns = ["id"], childColumns = ["accountId"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index(value = ["accountId"], unique = true)],
)
data class RelatedPersonProfileEntity(
    @PrimaryKey val accountId: String,
    val ownerId: String,
    val epochId: String,
    val direction: String,
    val relation: String,
    val authorizedLimitUnscaled: Long,
    val authorizedLimitScale: Int,
    val currency: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Dao
internal interface RelatedPersonDao {
    @Query("SELECT * FROM related_person_profile") fun profiles(): List<RelatedPersonProfileEntity>
    @Query("SELECT * FROM related_person_profile WHERE accountId=:accountId") fun profile(accountId: String): RelatedPersonProfileEntity?
    @androidx.room.Query("DELETE FROM related_person_profile WHERE accountId=:accountId") fun delete(accountId: String): Int
    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE) fun upsert(profile: RelatedPersonProfileEntity)
}


internal fun relatedPersonMigration17To18(owner: String, epoch: String) = object : Migration(17, 18) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.query("SELECT ownerId,epochId,schemaVersion,lastAppliedSeq FROM dataset_identity").use { c ->
            check(c.count == 1 && c.moveToFirst())
            check(c.getString(0) == owner && c.getString(1) == epoch && c.getInt(2) == 1 && c.getLong(3) >= 0)
        }
        db.execSQL("CREATE TABLE `related_person_profile` (`accountId` TEXT NOT NULL, `ownerId` TEXT NOT NULL, `epochId` TEXT NOT NULL, `direction` TEXT NOT NULL, `relation` TEXT NOT NULL, `authorizedLimitUnscaled` INTEGER NOT NULL, `authorizedLimitScale` INTEGER NOT NULL, `currency` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`accountId`), FOREIGN KEY(`accountId`) REFERENCES `finance_account`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT)")
        db.execSQL("CREATE UNIQUE INDEX `index_related_person_profile_accountId` ON `related_person_profile` (`accountId`)")
    }
}
