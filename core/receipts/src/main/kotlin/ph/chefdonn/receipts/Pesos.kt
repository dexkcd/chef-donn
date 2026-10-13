package ph.chefdonn.receipts

private val PESO_PREFIX = Regex("""^(₱|PHP|P)\s*""", RegexOption.IGNORE_CASE)
private val PESO_AMOUNT = Regex("""^(\d*)(?:\.(\d{0,2}))?$""")

/**
 * Parses what someone types into a money field ("₱1,234.50", "1234.5", "75") into
 * centavos. Null when it isn't a peso amount — never a guessed value.
 */
fun parsePesosToCents(input: String): Long? {
    val s = input.trim().replace(PESO_PREFIX, "").replace(",", "").replace(" ", "")
    val m = PESO_AMOUNT.matchEntire(s) ?: return null
    val whole = m.groupValues[1]
    val frac = m.groupValues[2]
    if (whole.isEmpty() && frac.isEmpty()) return null
    if (whole.length > 11) return null
    return whole.ifEmpty { "0" }.toLong() * 100 + frac.padEnd(2, '0').toLong()
}

/** The editable form of an amount: "1250.50", no symbol or grouping. */
fun centsToInput(cents: Long): String = "%d.%02d".format(cents / 100, cents % 100)
