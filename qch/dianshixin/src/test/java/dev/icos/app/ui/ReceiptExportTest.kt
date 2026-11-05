package dev.icos.app.ui

import dev.icos.app.data.local.FinanceRecord
import dev.icos.app.data.local.FinanceSnapshot
import dev.icos.app.data.remote.DeviceIdentity
import dev.icos.app.domain.AccountAmount
import dev.icos.app.domain.Money
import dev.icos.app.domain.RecordInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

class ReceiptExportTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val owner = UUID.randomUUID()
    private val epoch = UUID.randomUUID()
    private val ledger = UUID.randomUUID()
    private val accountId = UUID.randomUUID()
    private val record =
        FinanceRecord(
            id = UUID.randomUUID(),
            input =
                RecordInput.Expense(
                    gross = money("78.50"),
                    discount = null,
                    allocations = listOf(AccountAmount(accountId, money("78.50"))),
                    countInStatistics = true,
                    countInBudget = true,
                ),
            occurredAtMillis =
                LocalDate
                    .of(2026, 5, 18)
                    .atTime(10, 0)
                    .atZone(zone)
                    .toInstant()
                    .toEpochMilli(),
            timezone = zone.id,
            remark = "",
            categoryId = null,
            localRevision = 1,
        )
    private val snapshot = FinanceSnapshot(owner, epoch, ledger, emptyList(), emptyList(), listOf(record), emptyList())
    private val report = receiptReport(snapshot, record, ReceiptScope.DAY, "", "", zone, ReceiptPaperStyle.PLAIN)
    private val session = BoundSession(owner, DeviceIdentity(owner, UUID.randomUUID()))

    @Test
    fun frozenReceiptWritesOnlyForItsOwnReportAndDeviceSession() {
        assertTrue(receiptSaveAllowed(report, session, snapshot, session))
        assertFalse(receiptSaveAllowed(report, session, null, session))
        // A second device session of the same account, or the guest mode it replaced, may not reuse the request.
        assertFalse(receiptSaveAllowed(report, session, snapshot, BoundSession(owner, DeviceIdentity(owner, UUID.randomUUID()))))
        assertFalse(receiptSaveAllowed(report, session, snapshot, BoundSession(null, null)))
        assertFalse(receiptSaveAllowed(report, session, snapshot.copy(epochId = UUID.randomUUID()), session))
        assertFalse(receiptSaveAllowed(report, session, snapshot.copy(ledgerId = UUID.randomUUID()), session))
        assertFalse(receiptSaveAllowed(report, session, snapshot.copy(records = emptyList()), session))
    }

    @Test
    fun receiptSessionRequiresSettledAuthAndTheAppliedBinding() {
        val guestBound = BoundSession(null, null)
        val anonymousBound = BoundSession(null, null, guestMode = false)
        assertNull(verifiedReceiptSession(guestBound, false, true, null, null))
        assertEquals(guestBound, verifiedReceiptSession(guestBound, true, true, null, null))
        // Accepting legal terms without logging in still uses the local ledger, with its own mode fence.
        assertEquals(anonymousBound, verifiedReceiptSession(anonymousBound, true, false, null, null))
        assertNull(verifiedReceiptSession(guestBound, true, false, null, null))
        assertNull(verifiedReceiptSession(anonymousBound, true, true, null, null))
        // Cached identities authorize only the exact local binding; absent, busy or replaced sessions fail.
        assertNull(verifiedReceiptSession(anonymousBound, true, false, owner, null))
        assertNull(verifiedReceiptSession(null, true, false, owner, session.identity))
        assertNull(verifiedReceiptSession(BoundSession(owner, null), true, false, owner, null))
        assertNull(verifiedReceiptSession(session, false, false, owner, session.identity))
        assertNull(verifiedReceiptSession(session, true, false, owner, DeviceIdentity(UUID.randomUUID(), UUID.randomUUID())))
        assertNull(verifiedReceiptSession(session, true, false, owner, DeviceIdentity(owner, UUID.randomUUID())))
        assertEquals(session, verifiedReceiptSession(session, true, false, owner, session.identity))
    }

    @Test
    fun pickerResultNeverBindsToALaterSave() {
        val order = ReceiptSaveOrder()
        val first = requireNotNull(order.begin())
        order.pickerLaunched(first)
        assertTrue(order.pickerOutstanding)
        assertNull(order.begin()) // no second picker while one is still out
        // The request is retired (close or session change) before its picker returned.
        order.abandon()
        assertTrue(order.pickerOutstanding)
        assertNull(order.begin()) // the unreturned callback still blocks a new picker
        val late = order.consumePickerResult()
        assertTrue(late != null && late != first) // the late result no longer matches the retired request token
        assertNull(order.consumePickerResult()) // a duplicate callback binds nothing
        val second = requireNotNull(order.begin())
        assertTrue(second != first) // an abandoned task can neither publish nor release the new one
    }

    @Test
    fun annualPayloadRefusesForeignLedgerLockedSessionAndYearMismatch() {
        val annual =
            ExportPayload.Annual(
                AnnualReport(
                    ownerId = owner,
                    epochId = epoch,
                    ledgerId = ledger,
                    year = 2026,
                    income = emptyMap(),
                    expense = emptyMap(),
                    incomeExpenseRatio = emptyMap(),
                    largest = emptyMap(),
                    categories = emptyList(),
                    months = emptyMap(),
                    omittedCategoryCount = 0,
                    daysRecorded = 0,
                    recordCount = 0,
                    entryCount = 0,
                    empty = true,
                ),
                2026,
            )
        // The report itself must be bound to the year it was frozen for.
        assertFalse(annual.copy(year = 2025).isCurrent(snapshot))
        assertTrue(exportSaveAllowed(annual, session, snapshot, session))
        assertFalse(exportSaveAllowed(annual, session, null, session))
        assertFalse(exportSaveAllowed(annual, session, snapshot, null))
        assertFalse(exportSaveAllowed(annual, session, snapshot, BoundSession(owner, DeviceIdentity(owner, UUID.randomUUID()))))
        assertFalse(exportSaveAllowed(annual, session, snapshot, BoundSession(null, null, guestMode = false)))
        assertFalse(exportSaveAllowed(annual, session, snapshot, BoundSession(null, null)))
        // Owner, epoch and ledger changes each refuse the frozen image; the report must match the live snapshot.
        assertFalse(exportSaveAllowed(annual, session, snapshot.copy(ownerId = UUID.randomUUID()), session))
        assertFalse(exportSaveAllowed(annual, session, snapshot.copy(epochId = UUID.randomUUID()), session))
        assertFalse(exportSaveAllowed(annual, session, snapshot.copy(ledgerId = UUID.randomUUID()), session))
        // The receipt branch keeps its stricter record-existence and draft-key guards on the same slot.
        val receiptPayload = ExportPayload.Receipt(report, receiptKey(snapshot))
        assertTrue(exportSaveAllowed(receiptPayload, session, snapshot, session))
        assertFalse(exportSaveAllowed(receiptPayload, session, snapshot.copy(records = emptyList()), session))
        // A receipt payload keyed to another ledger can never pass, even with a matching report.
        val wrongKey = receiptPayload.copy(key = receiptKey(snapshot).copy(ledgerId = UUID.randomUUID()))
        assertFalse(exportSaveAllowed(wrongKey, session, snapshot, session))
    }

    private fun money(value: String): Money {
        val decimal = BigDecimal(value)
        return Money(decimal.unscaledValue().longValueExact(), decimal.scale(), "CNY")
    }
}
