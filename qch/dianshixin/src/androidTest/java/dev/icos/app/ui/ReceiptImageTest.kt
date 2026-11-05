package dev.icos.app.ui

import android.graphics.Color
import dev.icos.app.domain.Direction
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.util.UUID

class ReceiptImageTest {
    @Test fun receiptBodyStaysInsidePaperMarginsAfterCenteredHeaderAndTrailingAmounts() {
        val report =
            ReceiptReport(
                ownerId = UUID.randomUUID(),
                epochId = UUID.randomUUID(),
                ledgerId = UUID.randomUUID(),
                recordIds = setOf(UUID.randomUUID(), UUID.randomUUID()),
                scope = ReceiptScope.DAY,
                title = "Receipt",
                subtitle = "",
                dateRange = "2026-10-20",
                rows =
                    listOf(
                        ReceiptRow(
                            "Expense",
                            "12:00 · Wallet",
                            "-78.50 CNY",
                            Direction.EXPENSE,
                            listOf("Original 80.50 CNY · discount 2 CNY"),
                        ),
                        ReceiptRow("Income", "12:01 · Reserve", "+3 CNY", Direction.INCOME, emptyList()),
                    ),
                omittedRowCount = 0,
                omittedCurrencyCount = 0,
                recordCount = 2,
                transferCount = 0,
                totals = listOf(ReceiptCurrencyTotal("CNY", BigDecimal("3"), BigDecimal("78.50"), 1, 1)),
                style = ReceiptPaperStyle.PLAIN,
            )
        ReceiptPaperStyle.entries.forEach { style ->
            val bitmap = ReportImageRenderer.receipt(report.copy(style = style))
            try {
                fun ink(
                    x: Int,
                    y: Int,
                ): Boolean {
                    val color = bitmap.getPixel(x, y)
                    return minOf(Color.red(color), Color.green(color), Color.blue(color)) < 180
                }
                assertTrue("$style must show the first row label", (372 until 459).any { y -> (64 until 300).any { x -> ink(x, y) } })
                val marginsClear =
                    (372 until bitmap.height - 250).all { y ->
                        (24 until 62).all { x -> !ink(x, y) && !ink(bitmap.width - 1 - x, y) }
                    }
                assertTrue("$style body text must not bleed into paper margins", marginsClear)
            } finally {
                bitmap.recycle()
            }
        }
    }
}
