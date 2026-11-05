package dev.icos.app.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.icos.app.domain.AccountAmount
import dev.icos.app.domain.Money
import dev.icos.app.domain.RecordInput
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

@Entity(tableName = "finance_record_template")
internal data class RecordTemplateEntity(
    @PrimaryKey val id: String,
    val ownerId: String,
    val epochId: String,
    val name: String,
    val inputJson: String,
    val categoryId: String?,
    val occurredAtMillis: Long,
    val timezone: String,
    val remark: String,
    @ColumnInfo(defaultValue = "'[]'") val tagIdsJson: String = "[]",
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
)

@Dao
internal interface RecordTemplateDao {
    @Query("SELECT * FROM finance_record_template WHERE deletedAt IS NULL ORDER BY name COLLATE NOCASE, id")
    fun active(): List<RecordTemplateEntity>

    @Insert fun insert(value: RecordTemplateEntity)

    @Update fun update(value: RecordTemplateEntity)
}

internal fun recordTemplateMigration4To5(
    owner: String,
    epoch: String,
) = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.query("SELECT ownerId,epochId FROM dataset_identity").use {
            check(it.count == 1 && it.moveToFirst() && it.getString(0) == owner && it.getString(1) == epoch)
        }
        db.execSQL(
            "CREATE TABLE `finance_record_template` (`id` TEXT NOT NULL, `ownerId` TEXT NOT NULL, `epochId` TEXT NOT NULL, `name` TEXT NOT NULL, `inputJson` TEXT NOT NULL, `categoryId` TEXT, `occurredAtMillis` INTEGER NOT NULL, `timezone` TEXT NOT NULL, `remark` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `deletedAt` INTEGER, PRIMARY KEY(`id`))",
        )
    }
}

internal data class FinanceRecordTemplate(
    val id: UUID,
    val name: String,
    val input: RecordInput,
    val categoryId: UUID?,
    val occurredAtMillis: Long,
    val timezone: String,
    val remark: String,
    val invalidReason: String? = null,
    val tagIds: List<UUID> = emptyList(),
    val serverRevision: Long? = null,
)

internal fun templateStored(
    value: FinanceRecordTemplate,
    ownerId: UUID,
    epochId: UUID,
    now: Long,
    old: RecordTemplateEntity? = null,
) = RecordTemplateEntity(
    id = value.id.toString(),
    ownerId = ownerId.toString(),
    epochId = epochId.toString(),
    name = value.name.trim(),
    inputJson = encodeRecordInput(value.input),
    categoryId = value.categoryId?.toString(),
    occurredAtMillis = value.occurredAtMillis,
    timezone = value.timezone,
    remark = value.remark,
    tagIdsJson = JSONArray().also { array -> value.tagIds.forEach { array.put(it.toString()) } }.toString(),
    createdAt = old?.createdAt ?: now,
    updatedAt = now,
    deletedAt = null,
)

internal fun encodeRecordInput(input: RecordInput): String {
    fun money(value: Money) =
        JSONObject().put("unscaled", value.unscaled.toString()).put("scale", value.scale).put("currency", value.currency)

    fun allocation(value: AccountAmount) = JSONObject().put("accountId", value.accountId.toString()).put("amount", money(value.amount))
    return when (input) {
        is RecordInput.Expense -> {
            JSONObject()
                .put("kind", "EXPENSE")
                .put("gross", money(input.gross))
                .put(
                    "discount",
                    input.discount?.let(::money) ?: JSONObject.NULL,
                ).put("allocations", JSONArray().also { array -> input.allocations.forEach { array.put(allocation(it)) } })
                .put("countInStatistics", input.countInStatistics)
                .put("countInBudget", input.countInBudget)
                .toString()
        }

        is RecordInput.Income -> {
            JSONObject()
                .put("kind", "INCOME")
                .put("amount", money(input.amount))
                .put("allocations", JSONArray().also { array -> input.allocations.forEach { array.put(allocation(it)) } })
                .put("countInStatistics", input.countInStatistics)
                .toString()
        }

        is RecordInput.Transfer -> {
            JSONObject()
                .put("kind", "TRANSFER")
                .put("from", allocation(input.from))
                .put("to", allocation(input.to))
                .put(
                    "fee",
                    input.fee?.let(::allocation) ?: JSONObject.NULL,
                ).put("bonus", input.bonus?.let(::allocation) ?: JSONObject.NULL)
                .toString()
        }
    }
}

internal fun decodeRecordInput(json: String): RecordInput =
    dev.icos.app.data.remote.RecordInputJson
        .decode(JSONObject(json))

internal fun templateNameIsValid(value: String): Boolean =
    value.trim().let { it.isNotEmpty() && it.length <= 80 && it.none { character -> character == '\u0000' } }

internal fun templateInputAccountIds(input: RecordInput): List<UUID> =
    when (input) {
        is RecordInput.Expense -> input.allocations.map { it.accountId }
        is RecordInput.Income -> input.allocations.map { it.accountId }
        is RecordInput.Transfer -> listOfNotNull(input.from.accountId, input.to.accountId, input.fee?.accountId, input.bonus?.accountId)
    }

internal fun templateAccountsInvalid(
    input: RecordInput,
    accounts: Map<String, AccountEntity>,
): Boolean =
    templateInputAccountIds(input).any { id ->
        accounts[id.toString()]?.let { it.deletedAt != null || it.archivedAt != null || !it.selectable } != false
    }

internal fun templateInputDirection(input: RecordInput): String? =
    when (input) {
        is RecordInput.Expense -> "EXPENSE"
        is RecordInput.Income -> "INCOME"
        is RecordInput.Transfer -> null
    }
