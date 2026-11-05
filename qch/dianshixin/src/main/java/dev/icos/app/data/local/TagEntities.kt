package dev.icos.app.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName = "finance_tag_group")
internal data class TagGroupEntity(
    @PrimaryKey val id: String,
    val ownerId: String,
    val epochId: String,
    val name: String,
    val sortOrder: Long,
    val isDefault: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val localRevision: Long,
    val deletedAt: Long?,
)

@Entity(
    tableName = "finance_tag",
    indices = [Index("groupId")],
)
internal data class TagEntity(
    @PrimaryKey val id: String,
    val ownerId: String,
    val epochId: String,
    val groupId: String,
    val name: String,
    val color: String?,
    val sortOrder: Long,
    val archivedAt: Long?,
    val createdAt: Long,
    val updatedAt: Long,
    val localRevision: Long,
    val deletedAt: Long?,
)

@Entity(
    tableName = "finance_record_tag",
    primaryKeys = ["recordId", "tagId"],
    indices = [Index("tagId")],
)
internal data class RecordTagEntity(
    val ownerId: String,
    val epochId: String,
    val recordId: String,
    val tagId: String,
    val createdAt: Long,
)

@Dao
internal interface TagDao {
    @Query("SELECT * FROM finance_tag_group ORDER BY sortOrder, id")
    fun groups(): List<TagGroupEntity>

    @Query("SELECT * FROM finance_tag ORDER BY sortOrder, name COLLATE NOCASE, id")
    fun tags(): List<TagEntity>

    @Query("SELECT * FROM finance_record_tag")
    fun recordTags(): List<RecordTagEntity>

    @Insert fun insert(value: TagGroupEntity)
    @Update fun update(value: TagGroupEntity)
    @Insert fun insert(value: TagEntity)
    @Update fun update(value: TagEntity)
    @Insert fun insert(value: RecordTagEntity)

    @Query("DELETE FROM finance_record_tag WHERE recordId = :recordId")
    fun deleteRecordTags(recordId: String)

    @Query("DELETE FROM finance_record_tag WHERE tagId = :tagId")
    fun deleteTagLinks(tagId: String)
}

internal fun tagMigration7To8(
    owner: String,
    epoch: String,
) = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.query("SELECT ownerId,epochId,schemaVersion,lastAppliedSeq FROM dataset_identity").use {
            check(it.count == 1 && it.moveToFirst())
            check(it.getString(0) == owner && it.getString(1) == epoch)
            check(it.getInt(2) == 1 && it.getLong(3) >= 0)
        }
        db.execSQL(
            "CREATE TABLE `finance_tag_group` (`id` TEXT NOT NULL, `ownerId` TEXT NOT NULL, `epochId` TEXT NOT NULL, `name` TEXT NOT NULL, `sortOrder` INTEGER NOT NULL, `isDefault` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `localRevision` INTEGER NOT NULL, `deletedAt` INTEGER, PRIMARY KEY(`id`))",
        )
        db.execSQL(
            "CREATE TABLE `finance_tag` (`id` TEXT NOT NULL, `ownerId` TEXT NOT NULL, `epochId` TEXT NOT NULL, `groupId` TEXT NOT NULL, `name` TEXT NOT NULL, `color` TEXT, `sortOrder` INTEGER NOT NULL, `archivedAt` INTEGER, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `localRevision` INTEGER NOT NULL, `deletedAt` INTEGER, PRIMARY KEY(`id`))",
        )
        db.execSQL("CREATE INDEX `index_finance_tag_groupId` ON `finance_tag` (`groupId`)")
        db.execSQL(
            "CREATE TABLE `finance_record_tag` (`ownerId` TEXT NOT NULL, `epochId` TEXT NOT NULL, `recordId` TEXT NOT NULL, `tagId` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`recordId`, `tagId`))",
        )
        db.execSQL("CREATE INDEX `index_finance_record_tag_tagId` ON `finance_record_tag` (`tagId`)")
        db.execSQL("ALTER TABLE `dataset_search_preferences` ADD COLUMN `tagIds` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `dataset_search_preferences` ADD COLUMN `tagMatch` TEXT NOT NULL DEFAULT 'ANY'")
        db.execSQL("ALTER TABLE `finance_record_template` ADD COLUMN `tagIdsJson` TEXT NOT NULL DEFAULT '[]'")
    }
}
