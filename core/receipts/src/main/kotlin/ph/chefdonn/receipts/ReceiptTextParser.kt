package ph.chefdonn.receipts

import java.time.DateTimeException
import java.time.LocalDate

/** Best guesses read off a receipt photo. Every field is a suggestion the user confirms. */
data class ReceiptGuess(
    val vendor: String? = null,
    val totalCents: Long? = null,
    val purchasedOn: LocalDate? = null,
)

/**
 * Turns OCR lines (in reading order) from a Philippine receipt into a [ReceiptGuess].
 * Heuristic by nature: anything it can't read with confidence comes back null so the
 * form stays empty rather than wrong.
 */
object ReceiptTextParser {

    fun parse(lines: List<String>, today: LocalDate): ReceiptGuess {
        val clean = lines.map { it.replace(Regex("""\s+"""), " ").trim() }.filter { it.isNotEmpty() }
        return ReceiptGuess(
            vendor = findVendor(clean),
            totalCents = findTotal(clean),
            purchasedOn = findDate(clean, today),
        )
    }

    // ---- total ----

    // A peso amount always has two decimals on a receipt; bare integers are qty/codes.
    private val AMOUNT = Regex("""(?<![\d.,])(\d{1,3}(?:,\d{3})+|\d+)\.(\d{2})(?!\d)""")

    /** Strongest signal first; within a tier the largest amount wins. */
    private val TOTAL_TIERS = listOf(
        listOf("AMOUNT DUE", "TOTAL DUE", "AMOUNT PAYABLE", "TOTAL AMOUNT", "BALANCE DUE"),
        listOf("GRAND TOTAL"),
        listOf("TOTAL"),
    )

    private val NOT_THE_TOTAL = listOf(
        "SUBTOTAL", "SUB TOTAL", "SUB-TOTAL", "VATABLE", "VAT AMOUNT", "VAT-EXEMPT", "VAT EXEMPT",
        "ZERO RATED", "ZERO-RATED", "QTY", "ITEM", "DISCOUNT", "SAVINGS", "POINTS",
    )

    private val PAYMENT_LINE = listOf("CASH", "CHANGE", "TENDER", "GCASH", "CARD")

    private fun amountsIn(line: String): List<Long> =
        AMOUNT.findAll(line).map { m ->
            m.groupValues[1].replace(",", "").toLong() * 100 + m.groupValues[2].toLong()
        }.toList()

    private fun findTotal(lines: List<String>): Long? {
        for (tier in TOTAL_TIERS) {
            val candidates = lines.indices.mapNotNull { i ->
                val upper = lines[i].uppercase()
                val isTotalLine = tier.any { kw -> hasWord(upper, kw) } &&
                    NOT_THE_TOTAL.none { upper.contains(it) } &&
                    PAYMENT_LINE.none { hasWord(upper, it) }
                if (!isTotalLine) return@mapNotNull null
                // Amount on the same line (rightmost), else the line right under the label.
                amountsIn(lines[i]).lastOrNull()
                    ?: lines.getOrNull(i + 1)?.takeIf { looksLikeBareAmount(it) }?.let { amountsIn(it).lastOrNull() }
            }
            candidates.maxOrNull()?.let { return it }
        }
        // No label survived OCR: the largest amount not printed beside cash/change.
        return lines
            .filter { line -> PAYMENT_LINE.none { hasWord(line.uppercase(), it) } }
            .flatMap(::amountsIn)
            .maxOrNull()
    }

    private fun looksLikeBareAmount(line: String): Boolean =
        AMOUNT.containsMatchIn(line) && line.count { it.isLetter() } <= 3

    // ---- date ----

    private val MONTHS = listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")

    // Whole month names or their abbreviations only, so item lines like
    // "1 MARGARINE 25.00" never read as a date.
    private const val MON = """(JAN(?:UARY)?|FEB(?:RUARY)?|MAR(?:CH)?|APR(?:IL)?|MAY|JUNE?|JULY?|AUG(?:UST)?""" +
        """|SEP(?:T(?:EMBER)?)?|OCT(?:OBER)?|NOV(?:EMBER)?|DEC(?:EMBER)?)(?![A-Z])\.?"""

    /** A date part can't run into more digits or a decimal ("25.00" is money, not '25). */
    private const val END = """(?!\d)(?![.,]\d)"""

    private val ISO_DATE = Regex("""(?<![\d.])(20\d{2})[-/.](\d{1,2})[-/.](\d{1,2})$END""")
    private val NUMERIC_DATE = Regex("""(?<![\d.])(\d{1,2})[-/.](\d{1,2})[-/.](\d{4}|\d{2})$END""")
    private val MONTH_FIRST = Regex("""(?<![A-Z])$MON\s*(\d{1,2}),?\s*(\d{4})$END""")
    private val DAY_FIRST = Regex("""(?<![\d.])(\d{1,2})[\s-]*$MON[\s-]*(\d{4}|\d{2})$END""")

    private fun findDate(lines: List<String>, today: LocalDate): LocalDate? {
        for (line in lines) {
            val upper = line.uppercase()
            val candidates = buildList {
                ISO_DATE.findAll(upper).forEach { m ->
                    add(dateOf(m.groupValues[1], m.groupValues[2], m.groupValues[3]))
                }
                NUMERIC_DATE.findAll(upper).forEach { m ->
                    val (a, b, y) = m.destructured
                    // PH receipts print MM/DD; a first part over 12 can only be a day.
                    add(if (a.toInt() > 12) dateOf(y, b, a) else dateOf(y, a, b))
                }
                MONTH_FIRST.findAll(upper).forEach { m ->
                    add(dateOf(m.groupValues[3], monthNumber(m.groupValues[1]), m.groupValues[2]))
                }
                DAY_FIRST.findAll(upper).forEach { m ->
                    add(dateOf(m.groupValues[3], monthNumber(m.groupValues[2]), m.groupValues[1]))
                }
            }
            candidates.filterNotNull()
                .firstOrNull { !it.isAfter(today) && it.year >= 2000 }
                ?.let { return it }
        }
        return null
    }

    private fun monthNumber(abbrev: String): String = (MONTHS.indexOf(abbrev.take(3)) + 1).toString()

    private fun dateOf(year: String, month: String, day: String): LocalDate? = try {
        val y = year.toInt().let { if (year.length == 2) 2000 + it else it }
        LocalDate.of(y, month.toInt(), day.toInt())
    } catch (_: DateTimeException) {
        null
    } catch (_: NumberFormatException) {
        null
    }

    // ---- vendor ----

    private val NOT_A_VENDOR = listOf(
        "OFFICIAL RECEIPT", "SALES INVOICE", "CASH INVOICE", "RECEIPT", "INVOICE", "TIN", "VAT REG",
        "NON-VAT", "NON VAT", "VAT", "MIN", "S/N", "SN:", "PERMIT", "ACCRED", "POS", "TEL", "CEL",
        "WELCOME", "THANK", "OPERATED BY", "OWNED BY", "PROP", "ST.", "STREET", "AVE", "BRGY",
        "BARANGAY", "CITY", "ROAD", "RD.", "BLDG", "PHILIPPINES", "CASHIER", "DATE", "TIME",
    )

    private fun findVendor(lines: List<String>): String? =
        lines.take(6).firstOrNull { line ->
            val upper = line.uppercase()
            val letters = line.count { it.isLetter() }
            val visible = line.count { !it.isWhitespace() }
            letters >= 3 &&
                letters * 2 >= visible &&
                !AMOUNT.containsMatchIn(line) &&
                NOT_A_VENDOR.none { hasWord(upper, it) }
        }

    /** Keyword match that won't fire inside a longer word ("TIN" in "MARTINEZ"). */
    internal fun hasWord(upperText: String, keyword: String): Boolean {
        var from = 0
        while (true) {
            val i = upperText.indexOf(keyword, from)
            if (i < 0) return false
            val before = upperText.getOrNull(i - 1)
            val after = upperText.getOrNull(i + keyword.length)
            val startOk = before == null || !before.isLetterOrDigit() || !keyword.first().isLetterOrDigit()
            val endOk = after == null || !after.isLetterOrDigit() || !keyword.last().isLetterOrDigit()
            if (startOk && endOk) return true
            from = i + 1
        }
    }
}
