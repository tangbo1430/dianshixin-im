package dev.icos.app.data.local

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import androidx.room.RoomDatabase

@Entity(tableName = "dataset_identity")
data class DatasetIdentity(
    @PrimaryKey val singleton: Int = 1,
    val ownerId: String,
    val epochId: String,
    val schemaVersion: Int = 1,
    val serverCursor: String? = null,
    val lastAppliedSeq: Long = 0,
    val snapshotSchemaVersion: Int = 0,
)

@Dao
interface DatasetIdentityDao {
    @Query("SELECT * FROM dataset_identity WHERE singleton = 1")
    fun read(): DatasetIdentity?

    @Query("SELECT COUNT(*) FROM dataset_identity")
    fun count(): Int

    @Insert
    fun insert(identity: DatasetIdentity)

    @Update
    fun update(identity: DatasetIdentity)
}

@Entity(tableName = "account_mutation_intent")
internal data class AccountMutationIntentEntity(
    @PrimaryKey val opId: String,
    val ownerId: String,
    val epochId: String,
    val aggregateType: String,
    val aggregateId: String,
    val commandType: String,
    val payloadJson: String,
    val state: String,
    val createdAt: Long,
)

@Entity(tableName = "account_prediction")
internal data class AccountPredictionEntity(
    @PrimaryKey val opId: String,
    val ownerId: String,
    val epochId: String,
    val aggregateType: String,
    val aggregateId: String,
    val payloadJson: String,
    val active: Boolean,
)

@Entity(tableName = "account_write_receipt")
internal data class AccountWriteReceiptEntity(
    @PrimaryKey val opId: String,
    val ownerId: String,
    val epochId: String,
    val status: String,
    val resultJson: String,
    val receivedAt: Long,
)

@Entity(tableName = "account_epoch_archive")
internal data class AccountEpochArchiveEntity(
    @PrimaryKey val epochId: String,
    val ownerId: String,
    val archivedAt: Long,
    val reason: String,
)

@Entity(tableName = "account_applied_group")
internal data class AccountAppliedGroupEntity(
    @PrimaryKey val groupId: String,
    val ownerId: String,
    val epochId: String,
    val seq: Long,
)

@Database(
    entities = [DatasetIdentity::class, LedgerEntity::class, FinanceGroupEntity::class, CategoryEntity::class, AccountEntity::class, RecordEntity::class, EventEntity::class, AllocationEntity::class, PostingEntity::class, StatisticEntity::class, BudgetEntity::class, MutationEntity::class, BudgetPreferenceEntity::class, BudgetTemplateEntity::class, BudgetOverrideEntity::class, SearchPreferencesEntity::class, RecordTemplateEntity::class, BusinessPreferencesEntity::class, TagGroupEntity::class, TagEntity::class, RecordTagEntity::class, SavingPlanEntity::class, SavingEntryEntity::class, LoanPlanEntity::class, LoanTermEntity::class, LoanRateChangeEntity::class, TypedOutboxEntity::class, AccountMutationIntentEntity::class, AccountPredictionEntity::class, AccountWriteReceiptEntity::class, AccountEpochArchiveEntity::class, AccountAppliedGroupEntity::class, FileImportBatchEntity::class, FileImportRowEntity::class, CreditProfileEntity::class, CreditCapacityEventEntity::class, InstallmentPlanEntity::class, InstallmentTermEntity::class, CreditPaymentAllocationEntity::class, RelatedPersonProfileEntity::class],
    version = 18,
    exportSchema = true,
)
abstract class DatasetDatabase :
    RoomDatabase(),
    java.io.Closeable {
    abstract fun identity(): DatasetIdentityDao

    internal abstract fun relatedPersons(): RelatedPersonDao

    internal abstract fun finance(): FinanceDao

    internal abstract fun budget(): BudgetDao

    internal abstract fun searchPreferences(): SearchPreferencesDao

    internal abstract fun businessPreferences(): BusinessPreferencesDao

    internal abstract fun recordTemplates(): RecordTemplateDao

    internal abstract fun tags(): TagDao

    internal abstract fun savings(): SavingDao

    internal abstract fun loans(): LoanDao

    internal abstract fun typedOutbox(): TypedOutboxDao

    internal abstract fun accountMutations(): AccountMutationDao

    internal abstract fun fileImports(): FileImportDao

    internal abstract fun credit(): CreditDao

    internal val fileImportTransaction = ThreadLocal<Boolean>()

    // SQLCipher's helper retains these bytes for connection reopen until close.
    internal var passphrase: ByteArray? = null

    override fun close() {
        try {
            super.close()
        } finally {
            passphrase?.fill(0)
            passphrase = null
        }
    }
}
