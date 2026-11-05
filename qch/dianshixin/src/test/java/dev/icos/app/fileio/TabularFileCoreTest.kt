package dev.icos.app.fileio

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ByteArrayOutputStream as Bytes
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TabularFileCoreTest {
    @Test fun accountExportUsesLocalDateInclusiveFiltersAndKeepsExactMoneyText() {
        val at = java.time.LocalDate.parse("2026-06-01").atStartOfDay(java.time.ZoneId.of("Pacific/Auckland")).toInstant().toEpochMilli()
        val record = dev.icos.app.data.local.FinanceRecord(
            id = java.util.UUID.randomUUID(),
            input = dev.icos.app.domain.RecordInput.Expense(
                dev.icos.app.domain.Money(123456789012345L, 4, "CNY"), null, emptyList(), true, true,
            ),
            occurredAtMillis = at,
            timezone = "Pacific/Auckland",
            remark = "=formula",
            categoryId = null,
            localRevision = 9,
        )
        val ledger = java.util.UUID.randomUUID()
        val snapshot = dev.icos.app.data.local.FinanceSnapshot(
            ownerId = java.util.UUID.randomUUID(), epochId = java.util.UUID.randomUUID(), ledgerId = ledger,
            accounts = emptyList(), categories = emptyList(), records = listOf(record), summaries = emptyList(),
        )
        val filteredRecords = AccountFileExport.filterRecords(
            snapshot,
            AccountExportFilter(java.time.LocalDate.parse("2026-06-01"), java.time.LocalDate.parse("2026-06-01"), setOf(AccountExportKind.EXPENSE)),
        )
        val filtered = FinanceFileExportMapper.snapshot(snapshot.copy(records = filteredRecords), 9L, emptyMap(), emptyMap())

        val output = ByteArrayOutputStream()
        TabularFileCore.exportCsv(filtered.rows, output)
        val parsed = TabularFileCore.parse("account.csv", ByteArrayInputStream(output.toByteArray()))
        assertEquals("12345678901.2345", parsed.rows.single().fields[2])
        assertEquals("2026-06-01 00:00:00", parsed.rows.single().fields[0])
        assertEquals("'=formula", parsed.rows.single().fields[7])
        assertEquals(0, AccountFileExport.filterRecords(snapshot,
            AccountExportFilter(java.time.LocalDate.parse("2026-06-02"), null, setOf(AccountExportKind.EXPENSE))).count())
        assertThrows(IllegalArgumentException::class.java) {
            AccountFileExport.validateRecordFacts(record.id.toString(), "OPEN", false, "STANDARD", "EXPENSE", true, true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AccountFileExport.validateRecordFacts(record.id.toString(), "NONE", true, "STANDARD", "EXPENSE", true, true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AccountFileExport.validateRecordFacts(record.id.toString(), "NONE", false, "STANDARD", "EXPENSE", false, true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AccountExportFilter(java.time.LocalDate.parse("2026-06-02"), java.time.LocalDate.parse("2026-06-01"))
        }
    }

    @Test fun csvHandlesQuotedCommasQuotesAndNewlinesAndFormulaEscapes() {
        val output = ByteArrayOutputStream()
        val fields = listOf("2026-01-01 12:00:00", "支出", "12.30", "餐饮", "午餐", "现金", "", "=SUM(1,2)\n\"备注\"", "CNY", "#午餐")
        TabularFileCore.exportCsv(sequenceOf(fields), output)
        val parsed = TabularFileCore.parse("ledger.csv", ByteArrayInputStream(output.toByteArray()))
        assertEquals(1, parsed.rows.size)
        assertEquals("'=SUM(1,2)\n\"备注\"", parsed.rows.single().fields[7])
        assertEquals("=SUM(1,2)\n\"备注\"", TabularFileCore.unescapeSpreadsheetText(parsed.rows.single().fields[7]))
    }

    @Test fun malformedUtf8AndUnclosedQuotesFailClosed() {
        assertThrows(java.nio.charset.CharacterCodingException::class.java) {
            TabularFileCore.parse("x.csv", ByteArrayInputStream(byteArrayOf(0xC3.toByte(), 0x28)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TabularFileCore.parse("x.csv", ByteArrayInputStream("\"unterminated".toByteArray()))
        }
    }

    @Test fun ole2IsReportedUnsupportedEvenWithXlsxExtension() {
        val ole = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte())
        assertThrows(UnsupportedOperationException::class.java) {
            TabularFileCore.parse("renamed.xlsx", ByteArrayInputStream(ole))
        }
    }

    @Test fun expandedZipLimitIsEnforced() {
        val zip = ByteArrayOutputStream()
        ZipOutputStream(zip).use { out ->
            out.putNextEntry(ZipEntry("xl/workbook.xml")); out.write("<x/>".toByteArray()); out.closeEntry()
            out.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml")); out.write(ByteArray(1024) { 65 }); out.closeEntry()
        }
        assertThrows(IllegalArgumentException::class.java) {
            TabularFileCore.parseXlsx(zip.toByteArray(), expandedLimit = 64)
        }
    }

    @Test fun xlsxTextRoundTripsWithoutFormulaCells() {
        val output = ByteArrayOutputStream()
        val fields = listOf("2026-01-01 12:00:00", "支出", "12.30", "餐饮", "午餐", "现金", "", "=SUM(1,2)", "CNY", "#午餐")
        TabularFileCore.exportXlsx(sequenceOf(fields), output)
        val parsed = TabularFileCore.parse("ledger.xlsx", ByteArrayInputStream(output.toByteArray()))
        assertEquals(1, parsed.rows.size)
        assertEquals("'=SUM(1,2)", parsed.rows.single().fields[7])
    }

    @Test fun xlsxAcceptsPlainDecimalAmountCellsAndRejectsExponentAndFormula() {
        fun withAmountCell(value: String, formula: Boolean = false): ByteArray {
            val base = ByteArrayOutputStream()
            TabularFileCore.exportXlsx(sequenceOf(listOf("2026-01-01 12:00:00", "支出", "12.30", "餐饮", "", "现金", "", "备注", "CNY", "")), base)
            val rewritten = ByteArrayOutputStream()
            java.util.zip.ZipInputStream(ByteArrayInputStream(base.toByteArray())).use { input ->
                ZipOutputStream(rewritten).use { output ->
                    while (true) {
                        val entry = input.nextEntry ?: break
                        val content = input.readBytes()
                        val text = content.toString(Charsets.UTF_8)
                        val replacement = when {
                            "xl/worksheets/sheet1.xml" != entry.name -> content
                            formula -> text.replace("<c r=\"C2\" t=\"inlineStr\"><is><t xml:space=\"preserve\">12.30</t></is></c>", "<c r=\"C2\"><f>1E2</f><v>100</v></c>").toByteArray()
                            else -> text.replace("<c r=\"C2\" t=\"inlineStr\"><is><t xml:space=\"preserve\">12.30</t></is></c>", "<c r=\"C2\" t=\"n\"><v>$value</v></c>").toByteArray()
                        }
                        output.putNextEntry(ZipEntry(entry.name)); output.write(replacement); output.closeEntry()
                    }
                }
            }
            return rewritten.toByteArray()
        }
        val parsed = TabularFileCore.parse("numeric.xlsx", ByteArrayInputStream(withAmountCell("12.30")))
        assertEquals("12.30", parsed.rows.single().fields[2])
        assertThrows(IllegalArgumentException::class.java) { TabularFileCore.parseXlsx(withAmountCell("1.23E2")) }
        assertThrows(IllegalArgumentException::class.java) { TabularFileCore.parseXlsx(withAmountCell("100", formula = true)) }
    }

    @Test fun positiveDecimalRejectsSignPrecisionAndOverflow() {
        assertEquals(1, ImportRowMapper.map(csv("-1"), java.time.ZoneId.of("UTC"), setOf("CNY"), emptyMap(), emptyMap()).errors.count { it.code == "INVALID_AMOUNT" })
        assertEquals(1, ImportRowMapper.map(csv("1.0000001"), java.time.ZoneId.of("UTC"), setOf("CNY"), emptyMap(), emptyMap()).errors.count { it.code == "INVALID_AMOUNT" })
    }

    private fun csv(amount: String): TabularFileCore.ParseResult = TabularFileCore.parse(
        "x.csv", ByteArrayInputStream((TabularFileCore.headers.joinToString(",") + "\n2026-01-01 12:00:00,支出,$amount,餐饮,,现金,,备注,CNY,#标签\n").toByteArray()),
    )
}
