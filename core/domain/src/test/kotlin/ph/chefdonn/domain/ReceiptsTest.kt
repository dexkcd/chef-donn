package ph.chefdonn.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private val categories = listOf(
    ExpenseCategory("cat-produce", "Produce"),
    ExpenseCategory("cat-lpg", "LPG & Fuel"),
    ExpenseCategory("cat-old", "Old Bucket", archived = true),
)

private fun receipt(
    id: String = "r1",
    vendor: String = "Farmers Market Stall 12",
    totalCents: Long = 125050,
    purchasedOn: String = "2026-10-12",
    categoryId: String = "cat-produce",
) = ReceiptSpec(id, vendor, totalCents, purchasedOn, categoryId, photoIds = listOf("p1"))

private fun record(spec: ReceiptSpec, commandId: String = "c-${spec.receiptId}") =
    RecordReceipt(commandId, "tab1", "Marites", spec)

/** Runs a command through decide and folds the accepted events, like the hub does. */
private fun AppState.apply(cmd: Command, now: Long = 5000): AppState =
    when (val d = decide(this, cmd, now)) {
        is Decision.Accepted -> reduceAll(this, d.events)
        is Decision.Rejected -> error("unexpected rejection: ${d.reason}")
    }

private fun AppState.rejection(cmd: Command): String =
    assertIs<Decision.Rejected>(decide(this, cmd, 5000)).reason

private val base = reduce(AppState(), ExpenseCategoriesUpdated(categories, 1000))

class ReceiptsTest {

    @Test
    fun `recording a receipt files it under its category`() {
        val state = base.apply(record(receipt()))
        val r = state.receipts.getValue("r1")
        assertEquals("cat-produce", r.spec.categoryId)
        assertEquals(125050, r.spec.totalCents)
        assertEquals("Marites", r.recordedBy)
        assertEquals(5000, r.recordedAt)
        assertFalse(r.deleted)
    }

    @Test
    fun `receipt validation is surfaced as a rejection`() {
        assertEquals("Vendor is required", base.rejection(record(receipt(vendor = "  "))))
        assertEquals("Total must be more than ₱0.00", base.rejection(record(receipt(totalCents = 0))))
        assertTrue(base.rejection(record(receipt(purchasedOn = "10/12/2026"))).startsWith("Date"))
        assertTrue(base.rejection(record(receipt(purchasedOn = "2026-02-30"))).startsWith("Date"))
        assertEquals("Category not found", base.rejection(record(receipt(categoryId = "cat-nope"))))
        assertTrue(base.rejection(record(receipt(categoryId = "cat-old"))).contains("archived"))
    }

    @Test
    fun `a second command reusing a receipt id is rejected, not merged`() {
        val state = base.apply(record(receipt()))
        assertEquals("Receipt already recorded", state.rejection(record(receipt(vendor = "Other"), commandId = "c-other")))
    }

    @Test
    fun `replayed ReceiptRecorded never clobbers a later edit`() {
        var state = base.apply(record(receipt()))
        state = state.apply(EditReceipt("c2", "tab1", "Marites", receipt(totalCents = 99900)))
        state = reduce(state, ReceiptRecorded(receipt(), "Marites", 9000))
        assertEquals(99900, state.receipts.getValue("r1").spec.totalCents)
    }

    @Test
    fun `edit re-categorizes and fixes an OCR misread`() {
        var state = base.apply(record(receipt()))
        state = state.apply(EditReceipt("c2", "tab1", "Boss", receipt(totalCents = 120000, categoryId = "cat-lpg")), now = 7000)
        val r = state.receipts.getValue("r1")
        assertEquals("cat-lpg", r.spec.categoryId)
        assertEquals(120000, r.spec.totalCents)
        assertEquals(5000, r.recordedAt)
        assertEquals(7000, r.updatedAt)
    }

    @Test
    fun `edit may keep a category archived after recording but not switch into one`() {
        var state = base.apply(record(receipt(categoryId = "cat-lpg")))
        state = state.apply(UpdateExpenseCategories("c2", "tab1", "Boss", categories.map {
            if (it.id == "cat-lpg") it.copy(archived = true) else it
        }))
        state = state.apply(EditReceipt("c3", "tab1", "Boss", receipt(categoryId = "cat-lpg", vendor = "Petron Banawe")))
        assertEquals("Petron Banawe", state.receipts.getValue("r1").spec.vendor)
        assertTrue(state.rejection(EditReceipt("c4", "tab1", "Boss", receipt(categoryId = "cat-old"))).contains("archived"))
    }

    @Test
    fun `edit and delete need an existing receipt`() {
        assertEquals("Receipt not found", base.rejection(EditReceipt("c1", "tab1", "Boss", receipt())))
        assertEquals("Receipt not found", base.rejection(DeleteReceipt("c1", "tab1", "Boss", "r1", "dup")))
    }

    @Test
    fun `delete is soft, idempotent, and blocks later edits`() {
        var state = base.apply(record(receipt()))
        state = state.apply(DeleteReceipt("c2", "tab1", "Boss", "r1", "Duplicate photo"))
        val r = state.receipts.getValue("r1")
        assertTrue(r.deleted)
        assertEquals("Duplicate photo", r.deletedReason)
        assertEquals(Decision.Accepted(emptyList()), decide(state, DeleteReceipt("c3", "tab1", "Boss", "r1", "again"), 6000))
        assertEquals("Receipt was deleted", state.rejection(EditReceipt("c4", "tab1", "Boss", receipt())))
    }

    @Test
    fun `categories can be renamed and archived but not dropped while in use`() {
        val state = base.apply(record(receipt()))
        val renamed = categories.map { if (it.id == "cat-produce") it.copy(name = "Gulay & Prutas") else it }
        assertEquals("Gulay & Prutas", state.apply(UpdateExpenseCategories("c2", "tab1", "Boss", renamed)).expenseCategories.first().name)

        val dropped = categories.filter { it.id != "cat-produce" }
        assertEquals("Produce has receipts — archive it instead", state.rejection(UpdateExpenseCategories("c3", "tab1", "Boss", dropped)))

        // An unused category may simply go away.
        val withoutLpg = categories.filter { it.id != "cat-lpg" }
        assertEquals(2, state.apply(UpdateExpenseCategories("c4", "tab1", "Boss", withoutLpg)).expenseCategories.size)
    }

    @Test
    fun `category lists need names and unique ids and active names`() {
        assertEquals("Every category needs a name", base.rejection(UpdateExpenseCategories("c1", "t", "B", categories + ExpenseCategory("cat-x", " "))))
        assertEquals("Duplicate category id", base.rejection(UpdateExpenseCategories("c1", "t", "B", categories + ExpenseCategory("cat-lpg", "Gas"))))
        assertEquals("Two categories have the same name", base.rejection(UpdateExpenseCategories("c1", "t", "B", categories + ExpenseCategory("cat-x", "produce "))))
        // An archived duplicate name is fine: it's out of the picker.
        base.apply(UpdateExpenseCategories("c1", "t", "B", categories + ExpenseCategory("cat-x", "Old bucket")))
    }

    @Test
    fun `receipt events stream only to office and hub`() {
        val recorded = ReceiptRecorded(receipt(), "Marites", 1)
        assertTrue(recorded.visibleTo(Role.OFFICE))
        assertTrue(recorded.visibleTo(Role.HUB))
        Role.entries.filter { it != Role.OFFICE && it != Role.HUB }.forEach { role ->
            assertFalse(recorded.visibleTo(role))
            assertFalse(ExpenseCategoriesUpdated(categories, 1).visibleTo(role))
            assertTrue(OrderBilled("o1", "Caja", 1).visibleTo(role))
        }
        assertTrue(MenuUpdated(emptyList(), 1).visibleTo(Role.OFFICE))
    }

    @Test
    fun `receipt events round-trip through the wire json`() {
        val events: List<DomainEvent> = listOf(
            ReceiptRecorded(receipt(), "Marites", 1),
            ReceiptEdited(receipt(totalCents = 1), "Boss", 2),
            ReceiptDeleted("r1", "dup", "Boss", 3),
            ExpenseCategoriesUpdated(categories, 4),
        )
        events.forEach { e ->
            val json = DomainJson.encodeToString(DomainEvent.serializer(), e)
            assertEquals(e, DomainJson.decodeFromString(DomainEvent.serializer(), json))
        }
    }
}
