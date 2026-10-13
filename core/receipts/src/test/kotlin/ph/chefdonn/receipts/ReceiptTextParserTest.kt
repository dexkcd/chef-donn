package ph.chefdonn.receipts

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private val TODAY = LocalDate.of(2026, 10, 12)

private fun parse(text: String) = ReceiptTextParser.parse(text.trimIndent().lines(), TODAY)

class ReceiptTextParserTest {

    @Test
    fun `supermarket receipt with labelled total`() {
        val g = parse(
            """
            PUREGOLD PRICE CLUB, INC.
            Puregold Jr. Banawe
            123 Banawe St., Quezon City
            VAT REG TIN: 000-123-456-00000
            MIN: 12345678901234567
            OFFICIAL RECEIPT
            10/11/2026 14:32:05
            SOY SAUCE 1L          58.75
            COOKING OIL 2L       245.00
            1 MARGARINE           25.00
            RICE 25KG          1,350.00
            SUBTOTAL           1,678.75
            VATABLE SALES      1,498.88
            VAT AMOUNT           179.87
            TOTAL              1,678.75
            CASH               2,000.00
            CHANGE               321.25
            """
        )
        assertEquals("PUREGOLD PRICE CLUB, INC.", g.vendor)
        assertEquals(167875, g.totalCents)
        assertEquals(LocalDate.of(2026, 10, 11), g.purchasedOn)
    }

    @Test
    fun `gas station receipt where OCR split label and amount onto separate lines`() {
        val g = parse(
            """
            PETRON
            SERVICE STATION
            Km 5 Aguinaldo Hwy
            OCT 09, 2026
            DIESEL
            32.150 L @ 58.30
            TOTAL
            P 1,874.35
            CASH
            2,000.00
            """
        )
        assertEquals("PETRON", g.vendor)
        assertEquals(187435, g.totalCents)
        assertEquals(LocalDate.of(2026, 10, 9), g.purchasedOn)
    }

    @Test
    fun `utility bill prefers amount due and skips the future due date`() {
        val g = parse(
            """
            MERALCO
            Manila Electric Company
            Billing Period: 09/05/2026 to 10/05/2026
            Total kWh 412
            Current Charges 4,812.60
            Total Amount Due 4,812.60
            Due Date: 10/20/2026
            """
        )
        assertEquals("MERALCO", g.vendor)
        assertEquals(481260, g.totalCents)
        assertEquals(LocalDate.of(2026, 9, 5), g.purchasedOn)
    }

    @Test
    fun `handwritten market chit leaves unreadable fields empty`() {
        val g = parse(
            """
            Aling Nena
            5 kls bangus
            350
            """
        )
        assertEquals("Aling Nena", g.vendor)
        assertNull(g.totalCents)
        assertNull(g.purchasedOn)
    }

    @Test
    fun `no total label falls back to the largest non-cash amount`() {
        val g = parse(
            """
            KUYA JUN'S ICE PLANT
            2 blocks          240.00
            delivery           60.00
            300.00
            CASH 500.00
            """
        )
        assertEquals(30000, g.totalCents)
    }

    @Test
    fun `date formats seen on PH receipts`() {
        assertEquals(LocalDate.of(2026, 9, 25), parse("Date: 25/09/2026").purchasedOn)
        assertEquals(LocalDate.of(2026, 10, 1), parse("2026-10-01 08:00").purchasedOn)
        assertEquals(LocalDate.of(2026, 10, 12), parse("12-OCT-2026").purchasedOn)
        assertEquals(LocalDate.of(2026, 8, 3), parse("08/03/26 09:15AM").purchasedOn)
        assertEquals(LocalDate.of(2026, 9, 30), parse("September 30, 2026").purchasedOn)
    }

    @Test
    fun `item lines and money never read as dates`() {
        assertNull(parse("1 MARGARINE 25.00").purchasedOn)
        assertNull(parse("2 MAY 25.00").purchasedOn)
        assertNull(parse("TOTAL 10.12.50").purchasedOn)
        assertNull(parse("Promo valid until 12/25/2026").purchasedOn)
    }

    @Test
    fun `vendor skips receipt boilerplate at the top`() {
        val g = parse(
            """
            OFFICIAL RECEIPT
            TIN 123-456-789-000
            Martinez Hardware & Construction Supply
            """
        )
        assertEquals("Martinez Hardware & Construction Supply", g.vendor)
    }
}
