package dev.icos.app.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.icos.app.data.local.FinanceSnapshot
import dev.icos.app.data.remote.DeviceIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** Owner, epoch and ledger the receipt belongs to; any change retires the preview and its pending save. */
internal data class ReceiptKey(
    val ownerId: UUID,
    val epochId: UUID,
    val ledgerId: UUID,
)

internal fun receiptKey(snapshot: FinanceSnapshot) = ReceiptKey(snapshot.ownerId, snapshot.epochId, snapshot.ledgerId)

/**
 * The local session a receipt may act in: auth must be loaded and settled and the finance model must
 * have applied exactly that mode and device session. A cached account may export its own local data;
 * missing or replaced identities cannot inherit a previous session.
 */
internal fun verifiedReceiptSession(
    bound: BoundSession?,
    settled: Boolean,
    guest: Boolean,
    userId: UUID?,
    identity: DeviceIdentity?,
): BoundSession? {
    if (!settled || bound == null) return null
    val expected =
        when {
            guest -> BoundSession(null, null, guestMode = true)
            userId == null && identity == null -> BoundSession(null, null, guestMode = false)
            userId != null && identity?.owner == userId -> BoundSession(userId, identity, guestMode = false)
            else -> return null
        }
    return bound.takeIf { it == expected }
}

/** Preview configuration retained in memory across locking and Activity recreation. */
internal data class ReceiptDraft(
    val key: ReceiptKey,
    val session: BoundSession,
    val recordId: UUID,
    val scope: ReceiptScope = ReceiptScope.RECORD,
    val style: ReceiptPaperStyle = ReceiptPaperStyle.PLAIN,
    val title: String = "记账小票",
    val subtitle: String = "",
)

/** The report and image frozen when the user pressed save; a picker result may only write this pair. */
internal data class ReceiptSaveRequest(
    val token: Long,
    val payload: ExportPayload,
    val bitmap: Bitmap,
    val session: BoundSession,
)

/**
 * The single export slot's closed dual payload. The receipt branch keeps every existing guard: the
 * draft's key and session are still checked in [ReceiptExport.activeSession], and the record-existence
 * rules stay in [ReceiptReport.isCurrent] via [receiptSaveAllowed]. The annual branch binds the page's
 * selected year, so a frozen image is accepted only when it is exactly the report on screen.
 */
internal sealed interface ExportPayload {
    /** Re-checked against the live snapshot before any write or share is published. */
    fun isCurrent(snapshot: FinanceSnapshot): Boolean

    data class Receipt(
        val report: ReceiptReport,
        val key: ReceiptKey,
    ) : ExportPayload {
        // The key is part of the payload so the frozen request itself is bound to the ledger it was
        // opened in, independent of what the live snapshot happens to be at write time.
        override fun isCurrent(snapshot: FinanceSnapshot): Boolean =
            key.ownerId == snapshot.ownerId && key.epochId == snapshot.epochId &&
                key.ledgerId == snapshot.ledgerId && report.isCurrent(snapshot)
    }

    data class Annual(
        val report: AnnualReport,
        val year: Int,
    ) : ExportPayload {
        override fun isCurrent(snapshot: FinanceSnapshot): Boolean =
            year == report.year && report.ownerId == snapshot.ownerId &&
                report.epochId == snapshot.epochId && report.ledgerId == snapshot.ledgerId
    }
}

/** A frozen receipt is writable only while its own report and device session are both still current. */
internal fun receiptSaveAllowed(
    report: ReceiptReport,
    session: BoundSession,
    snapshot: FinanceSnapshot?,
    currentSession: BoundSession?,
): Boolean = snapshot != null && session == currentSession && report.isCurrent(snapshot)

/** Dispatches to the payload's own guard; each branch keeps its existing invariants. */
internal fun exportSaveAllowed(
    payload: ExportPayload,
    session: BoundSession,
    snapshot: FinanceSnapshot?,
    currentSession: BoundSession?,
): Boolean {
    if (snapshot == null || session != currentSession) return false
    // Receipt: ledger key + every referenced record still present; annual: ledger identity + bound year.
    return payload.isCurrent(snapshot)
}

/**
 * Ordering of the system picker and of the writes it starts: one picker at a time, its result consumed
 * once, and a request retired while the picker was out leaves a tombstone so the late result can never
 * bind to a later save. Kept free of Android types so the ordering is asserted directly.
 */
internal class ReceiptSaveOrder {
    /** Token of the newest save attempt; a task holding an older token must not publish or release. */
    @Volatile var operation = 0L
        private set

    var pickerOutstanding = false
        private set

    /** Reserves the next attempt; null while an earlier picker still has to deliver its result. */
    fun begin(): Long? {
        if (pickerOutstanding) return null
        return ++operation
    }

    /** The picker of [token] is out; only its own delivery may consume the slot. */
    fun pickerLaunched(token: Long) {
        if (token == operation) pickerOutstanding = true
    }

    /** Consumes the single outstanding delivery; null for a duplicate or an unattributed callback. */
    fun consumePickerResult(): Long? {
        if (!pickerOutstanding) return null
        pickerOutstanding = false
        return operation
    }

    /** Retires the newest attempt so a task holding its token can no longer publish or release. */
    fun abandon() {
        ++operation
    }
}

/** The live Activity endpoints; detached on destroy so retained state never owns a stale Activity. */
internal data class ReceiptExportHost(
    val owner: Any,
    val launchPicker: (String) -> Unit,
    val snapshot: () -> FinanceSnapshot?,
    val session: () -> BoundSession?,
    val settled: () -> Boolean,
    val unlocked: () -> Boolean,
)

/** Memory-only preview and request; process death deliberately discards both and any orphan result. */
internal class ReceiptExport(
    application: Application,
) : AndroidViewModel(application) {
    private val context = application.applicationContext
    private val scope get() = viewModelScope

    @Volatile private var host: ReceiptExportHost? = null

    fun bindHost(host: ReceiptExportHost) {
        this.host = host
    }

    fun unbindHost(owner: Any) {
        if (host?.owner === owner) host = null
    }

    private fun snapshot() = host?.snapshot?.invoke()

    private fun session() = host?.session?.invoke()

    private fun settled() = host?.settled?.invoke() == true

    private fun unlocked() = host?.unlocked?.invoke() == true

    private fun launchPicker(fileName: String) = checkNotNull(host).launchPicker(fileName)

    private val order = ReceiptSaveOrder()

    var draft by mutableStateOf<ReceiptDraft?>(null)
        private set
    var frozen by mutableStateOf<Pair<ExportPayload, Bitmap>?>(null)
        private set
    var status by mutableStateOf<String?>(null)
        private set

    /**
     * Annual page state, memory-only like every other export state: it survives the lock screen
     * removing the composition and Activity recreation, and process death deliberately discards it.
     * [annualOpen] is the ledger the page was opened in; a key change retires the page.
     */
    var annualOpen by mutableStateOf<ReceiptKey?>(null)
        private set

    @Volatile private var annualSession: BoundSession? = null
    var annualYear by mutableStateOf(0)
        private set

    /** A picker round trip, a queued result, a write or a share holds the export slot until it settles. */
    var busy by mutableStateOf(false)
        private set

    private var slotHeld = false
    private var writing = false
    private var request: ReceiptSaveRequest? = null
    private var returned = false
    private var returnedUri: Uri? = null

    private fun refreshBusy() {
        busy = slotHeld || order.pickerOutstanding
    }

    fun begin(
        key: ReceiptKey,
        session: BoundSession,
        recordId: UUID,
    ) {
        if (draft?.key == key && draft?.recordId == recordId && draft?.session == session) return
        draft = ReceiptDraft(key, session, recordId)
        frozen = null
        status = null
    }

    fun update(change: (ReceiptDraft) -> ReceiptDraft) {
        draft = draft?.let(change)
    }

    fun showStatus(message: String?) {
        status = message
    }

    fun freeze(
        payload: ExportPayload,
        bitmap: Bitmap,
    ) {
        if (activeSession(payload) == null) {
            bitmap.recycle()
            return
        }
        frozen = payload to bitmap
        status = null
    }

    /** Opens the annual page bound to the ledger key the caller verified; reopening keeps the year. */
    fun openAnnual(
        key: ReceiptKey,
        currentYear: Int,
    ) {
        val current = session() ?: return
        if (!unlocked() || busy || snapshot()?.let(::receiptKey) != key) return
        if (annualOpen == key && annualSession == current) return
        closeAnnual()
        draft = null
        frozen = null
        annualSession = current
        annualOpen = key
        annualYear = currentYear.coerceIn(1900, 2200)
        status = null
    }

    /** Switching years retires the old year's frozen image and status so neither shows under the new year. */
    fun switchAnnualYear(year: Int) {
        if (busy || annualOpen == null || year !in 1900..2200 || year == annualYear) return
        annualYear = year
        if (frozen?.first is ExportPayload.Annual) frozen = null
        status = null
    }

    /** Closes the annual page and its pending request; an in-flight picker or write keeps the slot. */
    fun closeAnnual() {
        if (annualOpen == null) return
        annualOpen = null
        annualSession = null
        annualYear = 0
        if (frozen?.first is ExportPayload.Annual) frozen = null
        if (request?.payload is ExportPayload.Annual) {
            order.abandon()
            request = null
            returned = false
            returnedUri = null
        }
        status = null
        if (!writing && !order.pickerOutstanding) endBusy()
    }

    /**
     * Drops the page and its request. The slot stays held while a write or a picker is still live, so
     * an abandoned task can neither run concurrently with a new one nor release it.
     */
    fun close() {
        order.abandon()
        draft = null
        frozen = null
        status = null
        request = null
        returned = false
        returnedUri = null
        if (!writing && !order.pickerOutstanding) endBusy()
    }

    /**
     * Retires stale state. `settled == false` is not evidence of a session change: auth still has an
     * operation in flight, so the preview and its request must survive it.
     */
    fun invalidateIfStale(
        currentKey: ReceiptKey?,
        currentSession: BoundSession?,
        settled: Boolean,
    ) {
        if (!settled) return
        val preview = draft
        if (preview != null && (preview.key != currentKey || preview.session != currentSession)) {
            close()
        }
        val pending = request
        if (pending != null && pending.session != currentSession) {
            order.abandon()
            request = null
            returned = false
            returnedUri = null
            if (!writing && !order.pickerOutstanding) endBusy()
        }
        // The annual page belongs to the ledger it was opened in; a key change retires it wholesale.
        if (annualOpen != null && (annualOpen != currentKey || annualSession != currentSession)) closeAnnual()
    }

    /** Reserves the export slot for a share; false while a picker, a queued result or a write holds it. */
    fun beginBusy(): Boolean {
        if (slotHeld || order.pickerOutstanding) return false
        slotHeld = true
        refreshBusy()
        return true
    }

    fun endBusy() {
        slotHeld = false
        refreshBusy()
    }

    private fun activeSession(payload: ExportPayload): BoundSession? {
        if (!unlocked()) return null
        val current = session() ?: return null
        val source = snapshot() ?: return null
        return when (payload) {
            // Receipt guards kept verbatim: the draft's session and key must be the live ones.
            is ExportPayload.Receipt -> {
                current.takeIf { draft?.session == it && draft?.key == receiptKey(source) && payload.report.isCurrent(source) }
            }

            is ExportPayload.Annual -> {
                current.takeIf {
                    annualSession == it && annualOpen == receiptKey(source) && annualYear == payload.year &&
                        payload.isCurrent(source)
                }
            }
        }
    }

    /** Status may only reach the export still on screen: the receipt needs its draft, annual its page year. */
    private fun canPublish(payload: ExportPayload): Boolean =
        when (payload) {
            is ExportPayload.Receipt -> draft != null
            is ExportPayload.Annual -> annualOpen != null && annualYear == payload.year
        }

    private fun notReadyMessage(payload: ExportPayload): String =
        when (payload) {
            is ExportPayload.Receipt -> "当前会话尚未就绪，请重新打开小票后重试。"
            is ExportPayload.Annual -> "当前会话尚未就绪，请重新打开年度报告后重试。"
        }

    private fun staleMessage(payload: ExportPayload): String =
        when (payload) {
            is ExportPayload.Receipt -> "账单已失效，已取消保存。"
            is ExportPayload.Annual -> "年度数据已失效，已取消保存。"
        }

    /** Freezes the report on screen and opens the system document picker for that exact image. */
    fun requestSave(
        payload: ExportPayload,
        bitmap: Bitmap,
        fileName: String,
    ) {
        val current =
            activeSession(payload) ?: run {
                status = notReadyMessage(payload)
                return
            }
        if (!beginBusy()) return
        val token =
            order.begin() ?: run {
                endBusy()
                return
            }
        request = ReceiptSaveRequest(token, payload, bitmap, current)
        if (runCatching { launchPicker(fileName) }.isFailure) {
            request = null
            endBusy()
            status = "无法打开系统保存界面。"
            return
        }
        order.pickerLaunched(token)
        refreshBusy()
    }

    /** The Activity owns sharing too: dismissing or locking its UI cannot release an active export slot. */
    fun requestShare(
        payload: ExportPayload,
        bitmap: Bitmap,
    ) {
        val current = activeSession(payload) ?: return
        if (!beginBusy()) return
        val token =
            order.begin() ?: run {
                endBusy()
                return
            }
        writing = true
        scope.launch {
            try {
                val shareName =
                    when (payload) {
                        is ExportPayload.Receipt -> "receipt"
                        is ExportPayload.Annual -> "annual-${payload.report.year}"
                    }
                val uri = withContext(Dispatchers.IO) { writeShareBitmap(context, bitmap, shareName) }
                if (token != order.operation || !canPublish(payload)) return@launch
                status =
                    when {
                        !unlocked() || !exportSaveAllowed(payload, current, snapshot(), session()) -> "会话已变化或应用已锁定，请重新分享。"
                        uri != null && launchReportShare(context, uri) -> "已打开系统分享。"
                        else -> "无法打开系统分享。"
                    }
            } finally {
                writing = false
                endBusy()
            }
        }
    }

    /** A picker result is kept until the app is unlocked again, then consumed exactly once. */
    fun deliverPickerResult(uri: Uri?) {
        // A duplicate callback or one from a request retired while its picker was out never binds.
        val token = order.consumePickerResult() ?: return
        val pending = request
        if (pending == null || pending.token != token) {
            request = null
            returned = false
            returnedUri = null
            endBusy()
            return
        }
        returnedUri = uri
        returned = true
        resume()
    }

    /** Writes the frozen receipt once the picker returned and the app is unlocked; until then it waits. */
    fun resume() {
        if (!returned || !unlocked()) return
        val uri = returnedUri
        val pending = request
        returned = false
        returnedUri = null
        request = null
        if (pending == null || uri == null) {
            // Cancelled (or nothing was pending): keep the preview exactly as it is.
            endBusy()
            return
        }
        val current = session()
        if (current == null) {
            if (!settled()) {
                // Auth or the finance binding is still resolving; keep the result instead of deciding.
                request = pending
                returned = true
                returnedUri = uri
                return
            }
            retire("当前会话已变更，已取消保存。")
            return
        }
        if (pending.session != current) {
            retire("当前会话已变更，已取消保存。")
            return
        }
        val currentSnapshot = snapshot()
        if (currentSnapshot == null || !pending.payload.isCurrent(currentSnapshot)) {
            endBusy()
            if (pending.token == order.operation && canPublish(pending.payload)) status = staleMessage(pending.payload)
            return
        }
        writing = true
        scope.launch {
            try {
                var allowed = false
                val saved =
                    withContext(Dispatchers.IO) {
                        val fresh = activeSession(pending.payload)
                        allowed = pending.token == order.operation && fresh == pending.session &&
                            exportSaveAllowed(pending.payload, pending.session, snapshot(), fresh)
                        allowed && saveReportBitmap(context, pending.bitmap, uri)
                    }
                if (pending.token == order.operation && canPublish(pending.payload)) {
                    status =
                        when {
                            !allowed -> staleMessage(pending.payload)
                            saved -> "图片已保存。"
                            else -> "无法保存图片，请检查存储位置。"
                        }
                }
            } finally {
                writing = false
                endBusy()
            }
        }
    }

    /** Retires the page and the request because the confirmed session no longer matches. */
    private fun retire(message: String) {
        order.abandon()
        draft = null
        frozen = null
        request = null
        returned = false
        returnedUri = null
        annualOpen = null
        annualSession = null
        annualYear = 0
        status = message
        endBusy()
    }
}
