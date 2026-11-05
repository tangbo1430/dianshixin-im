package dev.icos.app.ui

import dev.icos.app.data.local.FileImportBatchEntity
import dev.icos.app.data.local.FinanceSnapshot
import dev.icos.app.fileio.AccountExportFormat
import dev.icos.app.fileio.AccountExportKind
import dev.icos.app.fileio.AccountExportRequest
import dev.icos.app.fileio.AccountFileImportCoordinator
import dev.icos.app.fileio.AndroidFileIoAdapter
import dev.icos.app.fileio.GuestFileImportCoordinator
import java.util.UUID
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/** The system contract a picker result is delivered under; the Activity registers all three once. */
internal enum class FilePickerKind { OPEN_DOCUMENT, CREATE_CSV, CREATE_XLSX }

internal enum class FileIoOperation { IMPORT, GUEST_EXPORT, ACCOUNT_EXPORT }

internal enum class GuestExportFormat(
    val mime: String,
    val extension: String,
) {
    CSV("text/csv", "csv"),
    XLSX("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx"),
}

internal enum class FileIoPage { SOURCE, PREVIEW, HISTORY, EXPORT }

/** MIME filter of the import picker; CSV must stay accepted, including the spreadsheet aliases. */
internal val FILE_PICKER_MIME_TYPES =
    arrayOf(
        "text/*",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.ms-excel",
    )

/** Non-content transport state: what was launched, so one callback can be attributed to one request. */
internal data class FilePickerLaunch(
    val nonce: String,
    val kind: FilePickerKind,
)

/** A picker callback; a null uri is a cancellation and still has to reach the waiting request. */
internal data class FilePickerResult(
    val nonce: String,
    val kind: FilePickerKind,
    val uri: String?,
)

/**
 * A file operation frozen when the user pressed the button that opens the picker. Session, owner/epoch/
 * ledger, generation and format cannot be re-read from the live mode after the fact, so the result may
 * only act on exactly this request. [snapshot] is the guest ledger source the export writes and is kept
 * only while the request itself is pending; importing freezes the target ledger and parse zone instead.
 */
internal data class FileIoRequest(
    val token: Long,
    val nonce: String,
    val kind: FilePickerKind,
    val operation: FileIoOperation,
    val session: BoundSession,
    val identity: String,
    val ownerId: UUID,
    val epochId: UUID,
    val ledgerId: UUID,
    val generation: Long,
    val zone: String,
    val format: AccountExportFormat? = null,
    val guestFormat: GuestExportFormat? = null,
    val accountRequest: AccountExportRequest? = null,
    val snapshot: FinanceSnapshot? = null,
)

/**
 * Memory-only file page state. The lock screen removes the composition, so preview, staged copy,
 * mappings and page cannot live in `remember`; process death deliberately discards all of it and no
 * financial content is written to Bundle or disk.
 */
internal data class FileIoState(
    val page: FileIoPage = FileIoPage.SOURCE,
    val status: String? = null,
    val identity: String? = null,
    val zone: String? = null,
    val ledger: UUID? = null,
    val staged: AndroidFileIoAdapter.Staged? = null,
    val guestPreview: GuestFileImportCoordinator.Preview? = null,
    val accountPreview: AccountFileImportCoordinator.Preview? = null,
    val batchId: UUID? = null,
    val guestHistory: List<Pair<FileImportBatchEntity, Int>> = emptyList(),
    val accountHistory: List<FileImportBatchEntity> = emptyList(),
    val accountBindings: Map<String, UUID> = emptyMap(),
    val categoryBindings: Map<String, UUID> = emptyMap(),
    val tagBindings: Map<String, UUID> = emptyMap(),
    val exportMime: String = GuestExportFormat.CSV.mime,
    val accountExportFormat: AccountExportFormat = AccountExportFormat.CSV,
    val accountExportKinds: Set<AccountExportKind> = AccountExportKind.entries.toSet(),
    val accountFromDate: String = "",
    val accountThroughDate: String = "",
    val exportPreparing: Boolean = false,
    val exporting: Boolean = false,
    val exportDone: Int = 0,
    val exportTotal: Int = 0,
    val accountExportError: String? = null,
) {
    val hasPreview: Boolean get() = staged != null || guestPreview != null || accountPreview != null || batchId != null
}

/** The live Activity endpoints; detached on destroy so retained state never owns a stale Activity. */
internal data class FileIoHost(
    val owner: Any,
    val launchOpenDocument: (nonce: String) -> Unit,
    val launchCreateCsv: (nonce: String, fileName: String) -> Unit,
    val launchCreateXlsx: (nonce: String, fileName: String) -> Unit,
    val unlocked: () -> Boolean,
    val session: () -> BoundSession?,
)

/**
 * Two-way binding of one [FileIoState] field. The file page keeps plain assignments, but the value lives
 * in the model, so the lock screen removing the composition cannot drop a preview, mapping or status.
 */
internal class FileIoField<T>(
    private val model: FinanceViewModel,
    private val read: (FileIoState) -> T,
    private val write: (FileIoState, T) -> FileIoState,
) : ReadWriteProperty<Any?, T> {
    override fun getValue(
        thisRef: Any?,
        property: KProperty<*>,
    ): T = read(model.fileIo)

    override fun setValue(
        thisRef: Any?,
        property: KProperty<*>,
        value: T,
    ) {
        model.updateFileIo { write(it, value) }
    }
}

internal fun <T> FinanceViewModel.fileIoField(
    read: (FileIoState) -> T,
    write: (FileIoState, T) -> FileIoState,
) = FileIoField(this, read, write)
