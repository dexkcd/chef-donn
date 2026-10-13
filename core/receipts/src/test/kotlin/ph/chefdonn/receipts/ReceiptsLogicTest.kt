package ph.chefdonn.receipts

import ph.chefdonn.domain.AppState
import ph.chefdonn.domain.ExpenseCategoriesUpdated
import ph.chefdonn.domain.ReceiptDeleted
import ph.chefdonn.domain.ReceiptRecorded
import ph.chefdonn.domain.ReceiptSpec
import ph.chefdonn.domain.reduceAll
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private fun spec(id: String, vendor: String, cents: Long, date: String, category: String) =
    ReceiptSpec(id, vendor, cents, date, category)

private val withDefaults = reduceAll(AppState(), listOf(ExpenseCategoriesUpdated(DefaultCategories.all, 1)))

class PesosTest {

    @Test
    fun `typed amounts become centavos`() {
        assertEquals(123450, parsePesosToCents("₱1,234.50"))
        assertEquals(123450, parsePesosToCents("1234.5"))
        assertEquals(7500, parsePesosToCents("75"))
        assertEquals(2005, parsePesosToCents("PHP 20.05"))
        assertEquals(300, parsePesosToCents("p 3"))
        assertEquals(50, parsePesosToCents(".50"))
    }

    @Test
    fun `non-amounts are rejected rather than guessed`() {
        assertNull(parsePesosToCents(""))
        assertNull(parsePesosToCents("."))
        assertNull(parsePesosToCents("abc"))
        assertNull(parsePesosToCents("12.345"))
        assertNull(parsePesosToCents("-5"))
    }

    @Test
    fun `cents render back into an editable amount`() {
        assertEquals("1234.05", centsToInput(123405))
        assertEquals("0.50", centsToInput(50))
    }
}

class CategorySuggesterTest {

    @Test
    fun `same vendor's last category wins over keywords`() {
        val state = reduceAll(
            withDefaults,
            listOf(ReceiptRecorded(spec("r1", "Petron Banawe, Inc.", 100, "2026-10-01", DefaultCategories.REPAIRS), "Boss", 2)),
        )
        assertEquals(DefaultCategories.REPAIRS, CategorySuggester.suggest("PETRON  BANAWE INC", listOf("DIESEL"), state))
    }

    @Test
    fun `keywords pick a category, specific beats generic`() {
        assertEquals(DefaultCategories.LPG_FUEL, CategorySuggester.suggest("PETRON", listOf("DIESEL", "TOTAL 1,874.35"), withDefaults))
        assertEquals(DefaultCategories.UTILITIES, CategorySuggester.suggest("Manila Water Company", emptyList(), withDefaults))
        assertEquals(DefaultCategories.MEAT_SEAFOOD, CategorySuggester.suggest("Aling Nena", listOf("5 kls bangus"), withDefaults))
        assertEquals(DefaultCategories.DRY_GOODS, CategorySuggester.suggest("PUREGOLD PRICE CLUB", listOf("SOY SAUCE 58.75", "RICE 25KG"), withDefaults))
    }

    @Test
    fun `no hint means no suggestion`() {
        assertNull(CategorySuggester.suggest("", listOf("THANK YOU"), withDefaults))
        // "ICE" must not fire inside "SERVICE" or "PRICE".
        assertNull(CategorySuggester.suggest("ABC Service Center", listOf("PRICE 100.00"), withDefaults))
    }

    @Test
    fun `archived categories are never suggested`() {
        val archived = reduceAll(
            withDefaults,
            listOf(ExpenseCategoriesUpdated(DefaultCategories.all.map {
                if (it.id == DefaultCategories.LPG_FUEL) it.copy(archived = true) else it
            }, 2)),
        )
        assertNull(CategorySuggester.suggest("PETRON", listOf("DIESEL"), archived))
    }
}

class ReceiptBookTest {

    @Test
    fun `month view filters, sorts, and totals by category`() {
        val state = reduceAll(
            withDefaults,
            listOf(
                ReceiptRecorded(spec("a", "Market", 50000, "2026-10-03", DefaultCategories.PRODUCE), "M", 10),
                ReceiptRecorded(spec("b", "Petron", 187435, "2026-10-09", DefaultCategories.LPG_FUEL), "M", 11),
                ReceiptRecorded(spec("c", "Market", 25000, "2026-10-09", DefaultCategories.PRODUCE), "M", 12),
                ReceiptRecorded(spec("d", "Meralco", 481260, "2026-09-05", DefaultCategories.UTILITIES), "M", 13),
                ReceiptRecorded(spec("e", "Dup", 99999, "2026-10-04", DefaultCategories.OTHER), "M", 14),
                ReceiptDeleted("e", "duplicate", "Boss", 15),
            ),
        )
        val oct = state.receiptMonth(YearMonth.of(2026, 10))
        assertEquals(listOf("c", "b", "a"), oct.receipts.map { it.spec.receiptId })
        assertEquals(262435, oct.totalCents)
        assertEquals(
            listOf(DefaultCategories.LPG_FUEL to 187435L, DefaultCategories.PRODUCE to 75000L),
            oct.byCategory.map { it.category.id to it.totalCents },
        )

        val produceOnly = state.receiptMonth(YearMonth.of(2026, 10), DefaultCategories.PRODUCE)
        assertEquals(listOf("c", "a"), produceOnly.receipts.map { it.spec.receiptId })
        assertEquals(262435, produceOnly.totalCents) // month total ignores the filter
    }
}
