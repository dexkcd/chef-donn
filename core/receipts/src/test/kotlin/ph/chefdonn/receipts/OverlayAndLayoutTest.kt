package ph.chefdonn.receipts

import ph.chefdonn.domain.AppState
import ph.chefdonn.domain.BillOrder
import ph.chefdonn.domain.DeleteReceipt
import ph.chefdonn.domain.EditReceipt
import ph.chefdonn.domain.ExpenseCategoriesUpdated
import ph.chefdonn.domain.ReceiptRecorded
import ph.chefdonn.domain.ReceiptSpec
import ph.chefdonn.domain.RecordReceipt
import ph.chefdonn.domain.reduceAll
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PendingOverlayTest {

    private val hub = reduceAll(
        AppState(),
        listOf(
            ExpenseCategoriesUpdated(DefaultCategories.all, 1),
            ReceiptRecorded(ReceiptSpec("old", "Meralco", 481260, "2026-09-05", DefaultCategories.UTILITIES), "Boss", 2),
        ),
    )

    @Test
    fun `queued receipt commands show up before the hub acks them`() {
        val queued = listOf(
            RecordReceipt("c1", "tab", "Marites", ReceiptSpec("new", "Aling Nena", 35000, "2026-10-12", DefaultCategories.MEAT_SEAFOOD)),
            EditReceipt("c2", "tab", "Marites", ReceiptSpec("old", "Meralco", 481260, "2026-09-05", DefaultCategories.OTHER)),
            BillOrder("c3", "tab", "Marites", "o-unknown"), // not a receipt command: ignored
        )
        val overlay = hub.withPendingReceipts(queued, now = 99)
        assertEquals(35000, overlay.receipts.getValue("new").spec.totalCents)
        assertEquals(DefaultCategories.OTHER, overlay.receipts.getValue("old").spec.categoryId)
        assertEquals(setOf("new", "old"), pendingReceiptIds(queued))
    }

    @Test
    fun `a queued command the hub would reject never shows`() {
        val bad = RecordReceipt("c1", "tab", "Marites", ReceiptSpec("bad", "", 0, "2026-10-12", DefaultCategories.OTHER))
        assertFalse(hub.withPendingReceipts(listOf(bad), now = 99).receipts.containsKey("bad"))
        assertTrue(hub.withPendingReceipts(listOf(DeleteReceipt("c2", "tab", "M", "old", "dup")), 99).receipts.getValue("old").deleted)
    }
}

class OcrLayoutTest {

    @Test
    fun `label and amount columns are rejoined into printed rows`() {
        // How a recognizer reports a two-column receipt: whole label column first.
        val lines = listOf(
            OcrLine("PUREGOLD PRICE CLUB", 40, 10, 400, 40),
            OcrLine("SUBTOTAL", 20, 300, 140, 330),
            OcrLine("TOTAL", 20, 340, 100, 370),
            OcrLine("CASH", 20, 380, 90, 410),
            OcrLine("1,678.75", 300, 302, 400, 328),
            OcrLine("1,678.75", 300, 343, 400, 371),
            OcrLine("2,000.00", 300, 381, 400, 409),
        )
        val rows = OcrLayout.rows(lines)
        assertEquals(
            listOf("PUREGOLD PRICE CLUB", "SUBTOTAL  1,678.75", "TOTAL  1,678.75", "CASH  2,000.00"),
            rows,
        )
        val guess = ReceiptTextParser.parse(rows, LocalDate.of(2026, 10, 12))
        assertEquals(167875, guess.totalCents)
    }
}
