package dev.icos.app.fileio

import dev.icos.app.domain.Money
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Version 1 spreadsheet interchange format. Input is capped before parsing; no workbook formulas are evaluated. */
object TabularFileCore {
    const val MAX_INPUT_BYTES = 20L * 1024 * 1024
    const val MAX_EXPANDED_BYTES = 200L * 1024 * 1024
    const val MAX_ROWS = 100_000
    const val FORMAT_VERSION = "icos-ledger-v1"
    const val EXTENSION_VERSION = "icos-ledger-v1-ext1"
    val headers = listOf("日期", "类型", "金额", "一级分类", "二级分类", "账户1", "账户2", "备注", "货币", "标签")
    val headersWithExternalId = headers + "externalId"

    data class Row(
        val number: Int,
        val fields: List<String>,
    )

    data class Diagnostic(
        val row: Int?,
        val code: String,
        val detail: String,
    )

    data class ParseResult(
        val rows: List<Row>,
        val errors: List<Diagnostic>,
        val warnings: List<Diagnostic>,
        val sha256: String,
        val workbookSheets: List<String> = emptyList(),
    )

    enum class FileKind { CSV, XLSX, XLS }

    fun detect(
        name: String,
        bytes: ByteArray,
    ): FileKind {
        require(bytes.size <= MAX_INPUT_BYTES) { "INPUT_TOO_LARGE" }
        if (bytes.size >= 8 &&
            bytes
                .copyOfRange(
                    0,
                    8,
                ).contentEquals(
                    byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte()),
                )
        ) {
            return FileKind.XLS
        }
        if (bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4b.toByte()) return FileKind.XLSX
        if (name.substringAfterLast('.', "").equals("xls", true)) return FileKind.XLS
        require(name.substringAfterLast('.', "").equals("csv", true)) { "UNSUPPORTED_FORMAT" }
        return FileKind.CSV
    }

    fun parse(
        name: String,
        input: InputStream,
    ): ParseResult {
        val raw = readBounded(input, MAX_INPUT_BYTES)
        val digest = MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it) }
        return when (detect(name, raw)) {
            FileKind.XLS -> throw UnsupportedOperationException("UNSUPPORTED_XLS_OLE2_BIFF")
            FileKind.CSV -> parseCsv(raw, digest)
            FileKind.XLSX -> parseXlsx(raw, digest)
        }
    }

    private fun readBounded(
        input: InputStream,
        limit: Long,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var count = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            count += n
            require(count <= limit) { "INPUT_TOO_LARGE" }
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private fun strictUtf8(bytes: ByteArray): String {
        val offset = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) 3 else 0
        if (offset == 0 && bytes.size >= 2 && bytes[0] == 0xFE.toByte() &&
            bytes[1] == 0xFF.toByte()
        ) {
            throw IllegalArgumentException("UNSUPPORTED_ENCODING_UTF16_BE")
        }
        if (offset == 0 && bytes.size >= 2 && bytes[0] == 0xFF.toByte() &&
            bytes[1] == 0xFE.toByte()
        ) {
            throw IllegalArgumentException("UNSUPPORTED_ENCODING_UTF16_LE")
        }
        val decoder =
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(
                    CodingErrorAction.REPORT,
                ).onUnmappableCharacter(CodingErrorAction.REPORT)
        return decoder.decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset)).toString()
    }

    private fun strictXmlUtf8(bytes: ByteArray): String {
        val decoder =
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(
                    CodingErrorAction.REPORT,
                ).onUnmappableCharacter(CodingErrorAction.REPORT)
        return decoder.decode(ByteBuffer.wrap(bytes)).toString()
    }

    private fun parseCsv(
        raw: ByteArray,
        digest: String,
    ): ParseResult {
        val text = strictUtf8(raw)
        val records = mutableListOf<List<String>>()
        val row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var afterQuote = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (quoted) {
                when (c) {
                    '"' -> {
                        if (i + 1 < text.length &&
                            text[i + 1] == '"'
                        ) {
                            field.append('"')
                            i++
                        } else {
                            quoted = false
                            afterQuote = true
                        }
                    }

                    else -> {
                        field.append(c)
                    }
                }
            } else {
                when {
                    afterQuote && c == ',' -> {
                        row += field.toString()
                        field.setLength(0)
                        afterQuote = false
                    }

                    afterQuote && (c == '\r' || c == '\n') -> {
                        row += field.toString()
                        field.setLength(0)
                        records += row.toList()
                        row.clear()
                        afterQuote = false
                        if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    }

                    afterQuote -> {
                        throw IllegalArgumentException("CSV_TRAILING_CHARACTERS")
                    }

                    c == '"' && field.isEmpty() -> {
                        quoted = true
                    }

                    c == '"' -> {
                        throw IllegalArgumentException("CSV_QUOTE_IN_UNQUOTED_FIELD")
                    }

                    c == ',' -> {
                        row += field.toString()
                        field.setLength(0)
                    }

                    c == '\r' || c == '\n' -> {
                        row += field.toString()
                        field.setLength(0)
                        records += row.toList()
                        row.clear()
                        if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    }

                    else -> {
                        field.append(c)
                    }
                }
            }
            i++
        }
        require(!quoted) { "CSV_UNCLOSED_QUOTE" }
        if (afterQuote || field.isNotEmpty() || row.isNotEmpty()) {
            row += field.toString()
            records += row.toList()
        }
        require(records.size <= MAX_ROWS + 1) { "TOO_MANY_ROWS" }
        val diagnostics = mutableListOf<Diagnostic>()
        val headerIndex = records.indexOfFirst { it.isNotEmpty() }
        require(headerIndex >= 0) { "INVALID_HEADER" }
        val header = records[headerIndex]
        require(header == headers || header == headersWithExternalId) { "INVALID_HEADER" }
        val columnCount = header.size
        val data = records.drop(headerIndex + 1).filterNot { it.size == 1 && it[0].isEmpty() }
        val rows =
            data.mapIndexed { index, fields ->
                if (fields.size !=
                    columnCount
                ) {
                    diagnostics +=
                        Diagnostic(index + headerIndex + 2, "COLUMN_COUNT", "Expected $columnCount columns, got ${fields.size}")
                }
                Row(index + headerIndex + 2, fields.take(columnCount) + List((columnCount - fields.size).coerceAtLeast(0)) { "" })
            }
        return ParseResult(rows, diagnostics, emptyList(), digest)
    }

    internal fun parseXlsx(
        raw: ByteArray,
        digest: String = "",
        expandedLimit: Long = MAX_EXPANDED_BYTES,
    ): ParseResult {
        val entries = linkedMapOf<String, ByteArray>()
        var encryptedPackage = false
        val expandedBound = minOf(expandedLimit, MAX_EXPANDED_BYTES)
        require(expandedBound > 0)
        var expanded = 0L
        ZipInputStream(ByteArrayInputStream(raw)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == "EncryptionInfo" || entry.name == "EncryptedPackage") encryptedPackage = true
                require(entries.size < 128) { "XLSX_TOO_MANY_ENTRIES" }
                require(entry.method == ZipEntry.STORED || entry.method == ZipEntry.DEFLATED) { "XLSX_COMPRESSION_METHOD" }
                val path = entry.name.replace('\\', '/')
                require(
                    !path.startsWith('/') && path.split('/').none { it == ".." || (it.isEmpty() && !entry.isDirectory) },
                ) { "XLSX_INVALID_PATH" }
                require(!entry.name.startsWith("/") && !entry.name.contains('\\')) { "XLSX_INVALID_PATH" }
                require(
                    !path.contains("vbaProject", ignoreCase = true) && !path.contains("macrosheet", ignoreCase = true) &&
                        !path.startsWith("xl/activeX/", ignoreCase = true),
                ) { "XLSX_MACRO_FORBIDDEN" }
                require(path !in entries) { "XLSX_DUPLICATE_ENTRY" }
                if (entry.isDirectory) {
                    zip.closeEntry()
                    continue
                }
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                var entryBytes = 0L
                while (true) {
                    val n = zip.read(buf)
                    if (n < 0) break
                    entryBytes += n
                    expanded += n
                    require(entryBytes <= expandedBound && expanded <= expandedBound) { "XLSX_EXPANDED_TOO_LARGE" }
                    out.write(buf, 0, n)
                }
                entries[path] = out.toByteArray()
                zip.closeEntry()
            }
        }
        require(!encryptedPackage) { "XLSX_ENCRYPTED_UNSUPPORTED" }

        fun parser(bytes: ByteArray): XmlPullParser =
            XmlPullParserFactory.newInstance().newPullParser().apply {
                setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
                setInput(ByteArrayInputStream(bytes), "UTF-8")
            }

        fun scanXml(
            bytes: ByteArray,
            onStart: (XmlPullParser) -> Unit = {
            },
            onText: (XmlPullParser) -> Unit = {},
            onEnd: (XmlPullParser) -> Unit = {},
        ) {
            val p = parser(bytes)
            var xmlDepth = 0
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                when (p.eventType) {
                    XmlPullParser.DOCDECL -> {
                        throw IllegalArgumentException("XLSX_DTD_FORBIDDEN")
                    }

                    XmlPullParser.START_TAG -> {
                        xmlDepth++
                        require(xmlDepth <= 64) { "XLSX_XML_DEPTH" }
                        onStart(p)
                    }

                    XmlPullParser.TEXT, XmlPullParser.CDSECT -> {
                        onText(p)
                    }

                    XmlPullParser.END_TAG -> {
                        onEnd(p)
                        xmlDepth--
                    }
                }
            }
        }
        entries.filterKeys { it.endsWith(".xml") || it.endsWith(".rels") }.values.forEach { bytes ->
            scanXml(bytes) { p ->
                require(
                    p.name !in setOf("externalLink", "externalReference", "connection", "connections"),
                ) { "XLSX_EXTERNAL_LINK_FORBIDDEN" }
                if (p.name == "f") throw IllegalArgumentException("XLSX_FORMULA_FORBIDDEN")
                if (p.name == "hyperlink") throw IllegalArgumentException("XLSX_EXTERNAL_LINK_FORBIDDEN")
                if (p.name == "Relationship") {
                    val target = p.getAttributeValue(null, "Target") ?: ""
                    val mode = p.getAttributeValue(null, "TargetMode")
                    require(mode != "External" && !target.contains("://") && !target.startsWith("//")) { "XLSX_EXTERNAL_LINK_FORBIDDEN" }
                }
            }
        }
        val workbookXml = strictXmlUtf8(entries.getValue("xl/workbook.xml"))
        val declaredSheets = Regex("<(?:[A-Za-z0-9_]+:)?sheet\\b([^>]*)/?>").findAll(workbookXml).map { it.groupValues[1] }.toList()
        val sheetEntries =
            declaredSheets.associate { attrs ->
                val name = Regex("name=\"([^\"]+)\"").find(attrs)?.groupValues?.get(1) ?: throw IllegalArgumentException("XLSX_SHEET_NAME")
                val relId =
                    Regex("(?:r:)?id=\"([^\"]+)\"").find(attrs)?.groupValues?.get(1)
                        ?: throw IllegalArgumentException("XLSX_SHEET_RELATIONSHIP")
                name to relId
            }
        require(sheetEntries.keys == setOf("明细", "说明")) { "XLSX_MULTISHEET_UNSUPPORTED" }
        val relationships =
            Regex(
                "<(?:[A-Za-z0-9_]+:)?Relationship\\b([^>]*)/?>",
            ).findAll(strictXmlUtf8(entries.getValue("xl/_rels/workbook.xml.rels"))).associate { match ->
                val attrs = match.groupValues[1]
                val id =
                    Regex("Id=\"([^\"]+)\"").find(attrs)?.groupValues?.get(1) ?: throw IllegalArgumentException("XLSX_SHEET_RELATIONSHIP")
                val target =
                    Regex("Target=\"([^\"]+)\"").find(attrs)?.groupValues?.get(1)
                        ?: throw IllegalArgumentException("XLSX_SHEET_RELATIONSHIP")
                id to target
            }
        require(
            relationships[sheetEntries.getValue("明细")] == "worksheets/sheet1.xml" &&
                relationships[sheetEntries.getValue("说明")] == "worksheets/sheet2.xml",
        ) { "XLSX_SHEET_MAPPING_UNSUPPORTED" }
        require("xl/worksheets/sheet1.xml" in entries && "xl/worksheets/sheet2.xml" in entries) { "XLSX_SHEET_MISSING" }
        val allowedSheetNames = sheetEntries.keys.toList()
        val shared = mutableListOf<String>()
        entries["xl/sharedStrings.xml"]?.let { bytes ->
            var text = StringBuilder()
            var inItem = false
            var inText = false
            scanXml(
                bytes,
                onStart = { p ->
                    if (p.name ==
                        "si"
                    ) {
                        require(shared.size < 300_000) { "XLSX_SHARED_STRING_LIMIT" }
                        inItem = true
                        text = StringBuilder()
                    }
                    if (p.name == "t") inText = true
                },
                onText = { p ->
                    if (inItem &&
                        inText
                    ) {
                        require(text.length + p.text.length <= 1_000_000) { "XLSX_STRING_LENGTH" }
                        text.append(p.text)
                    }
                },
                onEnd = { p ->
                    if (p.name == "t") inText = false
                    if (p.name == "si") {
                        shared += text.toString()
                        inItem = false
                    }
                },
            )
        }
        val sheetRows = mutableListOf<MutableMap<Int, String>>()
        var currentRow = -1
        var column = 0
        var cellType = ""
        var cellValue = StringBuilder()
        var inCell = false
        var inText = false
        var cellCount = 0
        scanXml(
            entries.getValue("xl/worksheets/sheet1.xml"),
            onStart = { p ->
                when (p.name) {
                    "f" -> {
                        throw IllegalArgumentException("XLSX_FORMULA_FORBIDDEN")
                    }

                    "hyperlink" -> {
                        throw IllegalArgumentException("XLSX_EXTERNAL_LINK_FORBIDDEN")
                    }

                    "row" -> {
                        currentRow++
                        require(currentRow < MAX_ROWS + 1) { "TOO_MANY_ROWS" }
                        sheetRows += mutableMapOf()
                    }

                    "c" -> {
                        cellCount++
                        require(cellCount <= (MAX_ROWS + 1) * 11) { "XLSX_TOO_MANY_CELLS" }
                        val ref = p.getAttributeValue(null, "r") ?: throw IllegalArgumentException("XLSX_CELL_REFERENCE")
                        val match =
                            Regex("([A-Za-z]{1,3})([1-9][0-9]*)").matchEntire(ref) ?: throw IllegalArgumentException("XLSX_CELL_REFERENCE")
                        column = columnIndex(match.groupValues[1])
                        require(match.groupValues[2].toIntOrNull() == currentRow + 1) { "XLSX_CELL_ROW" }
                        cellType = p.getAttributeValue(null, "t") ?: ""
                        cellValue = StringBuilder()
                        inCell = true
                    }

                    "t" -> {
                        if (inCell) inText = true
                    }

                    "v" -> {
                        if (inCell) inText = true
                    }
                }
            },
            onText = { p ->
                if (inCell &&
                    inText
                ) {
                    require(cellValue.length + p.text.length <= 1_000_000) { "XLSX_STRING_LENGTH" }
                    cellValue.append(p.text)
                }
            },
            onEnd = { p ->
                if (p.name == "t" || p.name == "v") inText = false
                if (p.name == "c") {
                    val value = cellValue.toString()
                    val decoded =
                        when (cellType) {
                            "s" -> {
                                shared.getOrNull(value.toIntOrNull() ?: -1) ?: throw IllegalArgumentException("XLSX_SHARED_STRING_INDEX")
                            }

                            "inlineStr", "str" -> {
                                value
                            }

                            "n", "" -> {
                                if (value.isEmpty()) {
                                    ""
                                } else {
                                    require(Regex("[0-9]{1,15}(?:\\.[0-9]{1,6})?").matches(value)) { "XLSX_NUMERIC_CELL_UNSUPPORTED" }
                                    require(column == 2) { "XLSX_NUMERIC_CELL_REQUIRES_TEXT_MAPPING" }
                                    value
                                }
                            }

                            else -> {
                                throw IllegalArgumentException("XLSX_NON_TEXT_CELL")
                            }
                        }
                    require(currentRow >= 0 && column !in sheetRows[currentRow]) { "XLSX_CELL_POSITION" }
                    sheetRows[currentRow][column] = decoded
                    inCell = false
                }
            },
        )
        val rows =
            sheetRows
                .mapIndexed { index, cells ->
                    require(cells.keys.all { it in 0..10 }) { "XLSX_COLUMN_LIMIT" }
                    Row(index + 1, (0..9).map { cells[it] ?: "" } + if (10 in cells) listOf(cells[10] ?: "") else emptyList())
                }.filter { it.fields.any(String::isNotEmpty) }
        val firstRow = rows.firstOrNull() ?: throw IllegalArgumentException("INVALID_HEADER")
        require(
            firstRow.fields.take(10) == headers &&
                (firstRow.fields.size == 10 || firstRow.fields.size == 11 && firstRow.fields[10] == "externalId"),
        ) { "INVALID_HEADER" }
        require(rows.size <= MAX_ROWS + 1) { "TOO_MANY_ROWS" }
        return ParseResult(rows.drop(1), emptyList(), emptyList(), digest, allowedSheetNames)
    }

    private fun columnIndex(ref: String): Int {
        val letters = ref.takeWhile(Char::isLetter).uppercase(Locale.ROOT)
        require(letters.isNotEmpty() && letters.length <= 3) { "XLSX_CELL_REFERENCE" }
        var n = 0
        letters.forEach { n = Math.addExact(Math.multiplyExact(n, 26), it - 'A' + 1) }
        require(n in 1..11) { "XLSX_COLUMN_LIMIT" }
        return n - 1
    }

    /** RFC4180 v1 uses apostrophe-prefix escaping for formula and control-leading text fields. */
    fun exportCsv(
        rows: Sequence<List<String>>,
        output: OutputStream,
        bom: Boolean = true,
        includeExternalIdExtension: Boolean = false,
        omittedDetails: List<String> = emptyList(),
        cancelled: () -> Boolean = { false },
        progress: (Long) -> Unit = {},
    ) {
        require(omittedDetails.isEmpty()) { "LOSSY_EXPORT_UNSUPPORTED" }
        if (bom) output.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
        val writer = output.writer(StandardCharsets.UTF_8)

        fun emit(
            fields: List<String>,
            header: Boolean = false,
        ) {
            require(
                if (header) {
                    fields == headers || fields == headersWithExternalId
                } else {
                    fields.size ==
                        if (includeExternalIdExtension) 11 else 10
                },
            ) { "COLUMN_COUNT" }
            writer.write(
                fields
                    .mapIndexed { index, raw ->
                        val value =
                            if (header && index == 10) {
                                "externalId"
                            } else if (index in setOf(0, 1, 2, 8)) {
                                raw
                            } else {
                                escapeSpreadsheetText(raw)
                            }
                        "\"${value.replace("\"", "\"\"")}\""
                    }.joinToString(","),
            )
            writer.write("\r\n")
        }
        emit(if (includeExternalIdExtension) headersWithExternalId else headers, header = true)
        var rowNumber = 0L
        rows.forEach { fields ->
            if (cancelled()) throw java.util.concurrent.CancellationException("EXPORT_CANCELLED")
            emit(fields)
            rowNumber++
            progress(rowNumber)
        }
        writer.flush()
    }

    fun escapeSpreadsheetText(value: String): String {
        val first = value.firstOrNull() ?: return value
        return if (first in "=+-@" || first.isISOControl()) "'$value" else value
    }

    /** Writes a text-only XLSX package. Cells use inline strings, so untrusted values cannot become formulas. */
    fun exportXlsx(
        rows: Sequence<List<String>>,
        output: OutputStream,
        includeExternalIdExtension: Boolean = false,
        omittedDetails: List<String> = emptyList(),
        cancelled: () -> Boolean = { false },
        progress: (Long) -> Unit = {},
        cleanupOnFailure: () -> Unit = {},
    ) {
        require(omittedDetails.isEmpty()) { "LOSSY_EXPORT_UNSUPPORTED" }
        try {
            ZipOutputStream(output).use { zip ->
                fun entry(
                    name: String,
                    body: String,
                ) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(body.toByteArray(StandardCharsets.UTF_8))
                    zip.closeEntry()
                }
                entry(
                    "[Content_Types].xml",
                    """<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/worksheets/sheet2.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>""",
                )
                entry(
                    "_rels/.rels",
                    """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""",
                )
                entry(
                    "xl/workbook.xml",
                    """<?xml version="1.0" encoding="UTF-8"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="明细" sheetId="1" r:id="rId1"/><sheet name="说明" sheetId="2" r:id="rId2"/></sheets></workbook>""",
                )
                entry(
                    "xl/_rels/workbook.xml.rels",
                    """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet2.xml"/></Relationships>""",
                )

                fun xml(value: String) =
                    buildString(value.length) {
                        value.forEach { c ->
                            when (c) {
                                '&' -> {
                                    append("&amp;")
                                }

                                '<' -> {
                                    append("&lt;")
                                }

                                '>' -> {
                                    append("&gt;")
                                }

                                '"' -> {
                                    append("&quot;")
                                }

                                '\'' -> {
                                    append("&apos;")
                                }

                                else -> {
                                    if (c ==
                                        '\t' ||
                                        c == '\n' ||
                                        c == '\r' ||
                                        c >= ' '
                                    ) {
                                        append(c)
                                    }
                                }
                            }
                        }
                    }

                fun col(index: Int): String {
                    var n =
                        index + 1
                    val result = StringBuilder()
                    while (n >
                        0
                    ) {
                        val rem =
                            (n - 1) % 26
                        result.append(('A'.code + rem).toChar())
                        n = (n - 1) / 26
                    }
                    return result.reverse().toString()
                }
                zip.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
                val writer = zip.writer(StandardCharsets.UTF_8)
                writer.write(
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>",
                )
                var rowNumber = 0L

                fun row(fields: List<String>) {
                    require(fields.size == if (includeExternalIdExtension) 11 else 10) { "COLUMN_COUNT" }
                    rowNumber++
                    writer.write("<row r=\"$rowNumber\">")
                    fields.forEachIndexed { index, raw ->
                        var value = raw
                        if (rowNumber == 1L && index == 10 && includeExternalIdExtension) value = "externalId"
                        if (rowNumber > 1 && index !in setOf(0, 1, 2, 8)) value = escapeSpreadsheetText(value)
                        writer.write(
                            "<c r=\"${col(index)}$rowNumber\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${xml(value)}</t></is></c>",
                        )
                    }
                    writer.write("</row>")
                    progress(rowNumber - 1)
                }
                row(if (includeExternalIdExtension) headersWithExternalId else headers)
                rows.forEach { values ->
                    if (cancelled()) throw java.util.concurrent.CancellationException("EXPORT_CANCELLED")
                    require(rowNumber <= MAX_ROWS) { "TOO_MANY_ROWS" }
                    row(values)
                }
                writer.write("</sheetData></worksheet>")
                writer.flush()
                zip.closeEntry()
                entry(
                    "xl/worksheets/sheet2.xml",
                    """<?xml version="1.0" encoding="UTF-8"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData><row r="1"><c r="A1" t="inlineStr"><is><t>格式版本</t></is></c><c r="B1" t="inlineStr"><is><t>$FORMAT_VERSION</t></is></c></row><row r="2"><c r="A2" t="inlineStr"><is><t>金额规则</t></is></c><c r="B2" t="inlineStr"><is><t>正十进制；组合分摊、手续费及跨币种记录不属于十列格式</t></is></c></row><row r="3"><c r="A3" t="inlineStr"><is><t>文本转义</t></is></c><c r="B3" t="inlineStr"><is><t>版本1对 = + - @ 或控制字符开头文本添加单引号前缀</t></is></c></row></sheetData></worksheet>""",
                )
            }
        } catch (failure: Exception) {
            runCatching(cleanupOnFailure)
            throw failure
        }
    }

    /** Reverses only the unambiguous v1 apostrophe escape; other leading apostrophes are preserved. */
    fun unescapeSpreadsheetText(value: String): String =
        if (value.length > 1 && value[0] == '\'' && (value[1] in "=+-@" || value[1].isISOControl())) value.drop(1) else value
}

data class ImportCandidate(
    val sourceRow: Int,
    val occurredAtMillis: Long?,
    val timezone: String,
    val kind: String?,
    val amount: Money?,
    val primaryCategory: String,
    val secondaryCategory: String,
    val account1: String,
    val account2: String,
    val remark: String,
    val currency: String,
    val tags: List<String>,
    val externalId: String? = null,
)

data class MappingDiagnostic(
    val row: Int,
    val code: String,
    val message: String,
)

data class ImportPreview(
    val candidates: List<ImportCandidate>,
    val errors: List<MappingDiagnostic>,
    val warnings: List<MappingDiagnostic>,
)

/** Mapper deliberately requires explicit name bindings when an import has ambiguous categories or accounts. */
object ImportRowMapper {
    private val amountPattern = Regex("[0-9]{1,15}(?:\\.[0-9]{1,6})?")
    private val dateFormats =
        listOf(
            DateTimeFormatter.ofPattern("uuuu年MM月dd日 HH:mm:ss", Locale.ROOT),
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss", Locale.ROOT),
        )

    fun map(
        parsed: TabularFileCore.ParseResult,
        zoneId: ZoneId,
        allowedCurrencies: Set<String>,
        accountBindings: Map<String, UUID>,
        categoryBindings: Map<String, UUID>,
    ): ImportPreview = map(parsed, zoneId, allowedCurrencies, "CNY", accountBindings, categoryBindings)

    fun map(
        parsed: TabularFileCore.ParseResult,
        zoneId: ZoneId,
        allowedCurrencies: Set<String>,
        defaultCurrency: String,
        accountBindings: Map<String, UUID>,
        categoryBindings: Map<String, UUID>,
    ): ImportPreview {
        require(defaultCurrency in allowedCurrencies) { "INVALID_DEFAULT_CURRENCY" }
        val candidates = mutableListOf<ImportCandidate>()
        val errors = parsed.errors.map { MappingDiagnostic(it.row ?: 0, it.code, it.detail) }.toMutableList()
        val warnings = mutableListOf<MappingDiagnostic>()
        val seenExternal = mutableSetOf<String>()
        parsed.rows.forEach { row ->
            val f =
                row.fields.mapIndexed { index, value ->
                    if (index in 3..7 || index == 9 ||
                        index == 10
                    ) {
                        TabularFileCore.unescapeSpreadsheetText(value)
                    } else {
                        value
                    }
                }

            fun error(
                code: String,
                message: String,
            ) {
                errors += MappingDiagnostic(row.number, code, message)
            }
            val time = parseDate(f[0], zoneId)
            if (time == null) error("INVALID_DATE", "日期格式无效；仅支持指定格式或带时区 ISO8601")
            val kind =
                when (f[1].trim()) {
                    "支出", "EXPENSE" -> "EXPENSE"
                    "收入", "INCOME" -> "INCOME"
                    "转账", "TRANSFER" -> "TRANSFER"
                    else -> null
                }
            if (kind == null) error("INVALID_TYPE", "类型必须为支出、收入或转账")
            val currency = f[8].ifBlank { "" }
            val amount: Money? =
                try {
                    if (!amountPattern.matches(f[2])) throw IllegalArgumentException()
                    val c = currency.takeIf(String::isNotBlank) ?: defaultCurrency
                    if (c !in allowedCurrencies) throw IllegalArgumentException()
                    val decimal = BigDecimal(f[2])
                    Money(decimal.unscaledValue().longValueExact(), decimal.scale(), c).also { it.requirePositive() }
                } catch (_: Exception) {
                    null
                }
            if (amount == null) error("INVALID_AMOUNT", "金额必须是范围内的正十进制数，货币必须有效")
            if (kind == "TRANSFER" && (f[5].isBlank() || f[6].isBlank() || f[5] == f[6])) error("TRANSFER_ACCOUNTS", "转账需要两个不同账户")
            if (kind != "TRANSFER" && f[3].isBlank()) error("CATEGORY_REQUIRED", "收支记录必须提供一级分类")
            val categoryKey = "${f[3]}\u0000${f[4]}\u0000${kind ?: ""}"
            if (kind != "TRANSFER" && categoryKey !in categoryBindings) error("CATEGORY_BINDING_REQUIRED", "分类必须通过明确的父子分类绑定")
            if (kind == "TRANSFER" && f[5] !in accountBindings) error("ACCOUNT_BINDING_REQUIRED", "转出账户需要明确绑定")
            if (kind == "TRANSFER" && f[6] !in accountBindings) error("ACCOUNT_BINDING_REQUIRED", "转入账户需要明确绑定")
            if (kind != "TRANSFER" && f[5].isNotBlank() && f[5] !in accountBindings) error("ACCOUNT_BINDING_REQUIRED", "账户1需要明确绑定")
            if (kind != "TRANSFER" && f[6].isNotBlank()) error("UNSUPPORTED_SECOND_ACCOUNT", "普通收支的第二账户不可表示，不能忽略")
            if (f.size !in 10..11 || (
                    f.size == 11 && parsed.rows
                        .firstOrNull()
                        ?.fields
                        ?.size != 11
                )
            ) {
                error("COLUMN_COUNT", "列数无效")
            }
            val ext = f.getOrNull(10)
            if (!ext.isNullOrBlank() && !seenExternal.add(ext)) error("DUPLICATE_EXTERNAL_ID", "文件内externalId重复")
            candidates +=
                ImportCandidate(
                    row.number,
                    time,
                    zoneId.id,
                    kind,
                    amount,
                    f[3],
                    f[4],
                    f[5],
                    f[6],
                    f[7],
                    currency,
                    f[9].split('#').filter(String::isNotBlank),
                    ext,
                )
        }
        return ImportPreview(candidates, errors, warnings)
    }

    private fun parseDate(
        raw: String,
        zone: ZoneId,
    ): Long? {
        dateFormats.forEach { format ->
            try {
                return LocalDateTime
                    .parse(raw, format)
                    .atZone(zone)
                    .toInstant()
                    .toEpochMilli()
            } catch (
                _: DateTimeParseException,
            ) {
            }
        }
        try {
            return OffsetDateTime.parse(raw).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
        }
        try {
            return Instant.parse(raw).toEpochMilli()
        } catch (_: DateTimeParseException) {
        }
        return null
    }
}

/** SAF/share intents are passed as opaque content URIs; a caller owns permission and UUID staging. */
data class FileIoUiState(
    val phase: Phase,
    val completedRows: Int = 0,
    val totalRows: Int? = null,
    val messageCode: String? = null,
) {
    enum class Phase { IDLE, COPYING, PREVIEW, COMMITTING, EXPORTING, COMPLETE, CANCELLED, STORAGE_FULL, FAILED }
}

interface FileIoContentGateway {
    fun stageContentUri(
        uri: String,
        maxBytes: Long = TabularFileCore.MAX_INPUT_BYTES,
    ): UUID

    fun openPreview(stagingId: UUID): ImportPreview

    fun confirmGuestBatch(
        stagingId: UUID,
        confirmedDigest: String,
    ): UUID

    fun exportFixedRevisionSnapshot(
        destinationUri: String,
        cancel: () -> Boolean,
        progress: (Long, Long?) -> Unit,
    )
}

/** Revisioned snapshot encoder prevents callers from combining rows from different repository reads. */
data class FileExportSnapshot(
    val revision: Long,
    val rows: Sequence<List<String>>,
    val omittedDetails: List<String> = emptyList(),
) {
    init {
        require(revision >= 0)
    }
}
