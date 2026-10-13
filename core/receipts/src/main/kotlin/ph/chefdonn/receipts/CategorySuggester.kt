package ph.chefdonn.receipts

import ph.chefdonn.domain.AppState
import ph.chefdonn.receipts.DefaultCategories.BEVERAGES
import ph.chefdonn.receipts.DefaultCategories.DRY_GOODS
import ph.chefdonn.receipts.DefaultCategories.LPG_FUEL
import ph.chefdonn.receipts.DefaultCategories.MEAT_SEAFOOD
import ph.chefdonn.receipts.DefaultCategories.PRODUCE
import ph.chefdonn.receipts.DefaultCategories.REPAIRS
import ph.chefdonn.receipts.DefaultCategories.SUPPLIES
import ph.chefdonn.receipts.DefaultCategories.UTILITIES

/**
 * Picks the category to pre-select on the review form. The same vendor's last
 * receipt is the strongest hint; failing that, keywords on the receipt (Taglish
 * included) vote for one of the default categories.
 */
object CategorySuggester {

    private val KEYWORDS: Map<String, List<String>> = mapOf(
        PRODUCE to listOf(
            "GULAY", "VEGETABLE", "VEGETABLES", "FRUIT", "FRUITS", "PRUTAS", "TOMATO", "KAMATIS", "ONION",
            "SIBUYAS", "GARLIC", "BAWANG", "CABBAGE", "REPOLYO", "KANGKONG", "CALAMANSI", "KALAMANSI",
            "POTATO", "PATATAS", "CARROT", "SAGING", "BANANA", "PECHAY", "TALONG", "EGGPLANT", "LETTUCE", "GINGER", "LUYA",
        ),
        MEAT_SEAFOOD to listOf(
            "PORK", "BABOY", "LIEMPO", "CHICKEN", "MANOK", "BEEF", "BAKA", "FISH", "ISDA", "BANGUS", "TILAPIA",
            "SHRIMP", "HIPON", "SQUID", "PUSIT", "MEAT", "KARNE", "SEAFOOD", "SEAFOODS", "PATA",
        ),
        DRY_GOODS to listOf(
            "RICE", "BIGAS", "SUGAR", "ASUKAL", "FLOUR", "HARINA", "SOY SAUCE", "TOYO", "VINEGAR", "SUKA",
            "COOKING OIL", "MANTIKA", "SALT", "ASIN", "PEPPER", "PAMINTA", "NOODLES", "CANNED", "PUREGOLD",
            "S&R", "SNR", "LANDERS", "GROCERY", "SUPERMARKET",
        ),
        BEVERAGES to listOf(
            "COKE", "COCA-COLA", "COCA COLA", "PEPSI", "SPRITE", "ROYAL", "SOFTDRINKS", "SOFTDRINK", "SAN MIGUEL",
            "PALE PILSEN", "RED HORSE", "BEER", "WATER", "JUICE", "YELO", "ICE", "COFFEE", "KAPE",
        ),
        LPG_FUEL to listOf(
            "LPG", "GASUL", "SOLANE", "PRYCEGAS", "PETRON", "SHELL", "CALTEX", "PHOENIX", "SEAOIL", "CLEANFUEL",
            "DIESEL", "GASOLINE", "UNLEADED", "FUEL", "LITERS", "LITRES",
        ),
        UTILITIES to listOf(
            "MERALCO", "MAYNILAD", "MANILA WATER", "PLDT", "GLOBE", "SMART", "CONVERGE", "SKY FIBER",
            "ELECTRIC", "KWH", "WATER BILL", "INTERNET", "BILLING PERIOD",
        ),
        SUPPLIES to listOf(
            "PACKAGING", "CONTAINER", "CONTAINERS", "STYRO", "PLASTIC", "TISSUE", "NAPKIN", "NAPKINS", "CUPS",
            "LIDS", "STRAW", "STRAWS", "FOIL", "CLING", "GLOVES", "DETERGENT", "SOAP", "SABON", "ZONROX",
            "BLEACH", "DISHWASHING", "TRASH BAG", "GARBAGE BAG",
        ),
        REPAIRS to listOf(
            "REPAIR", "REPAIRS", "HARDWARE", "WILCON", "PLUMBING", "ELECTRICAL", "LABOR", "PAINT", "CEMENT",
            "PIPE", "FAUCET", "BULB", "SERVICE FEE", "MAINTENANCE",
        ),
    )

    fun suggest(vendor: String, lines: List<String>, state: AppState): String? {
        val active = state.expenseCategories.filter { !it.archived }.map { it.id }.toSet()

        val key = vendorKey(vendor)
        if (key.isNotEmpty()) {
            state.receipts.values
                .filter { !it.deleted && vendorKey(it.spec.vendor) == key && it.spec.categoryId in active }
                .maxByOrNull { it.updatedAt }
                ?.let { return it.spec.categoryId }
        }

        // Longer keywords are more specific ("MANILA WATER" beats "WATER"); the vendor
        // line counts double because item lists on a grocery receipt are noisy.
        val vendorText = vendor.uppercase()
        val bodyText = lines.joinToString("\n").uppercase()
        return KEYWORDS
            .filterKeys { it in active }
            .mapValues { (_, words) ->
                words.sumOf { w ->
                    (if (ReceiptTextParser.hasWord(vendorText, w)) 2 * w.length else 0) +
                        (if (ReceiptTextParser.hasWord(bodyText, w)) w.length else 0)
                }
            }
            .filterValues { it > 0 }
            .maxByOrNull { it.value }
            ?.key
    }

    /** "Petron  Banawe, Inc." and "PETRON BANAWE INC" are the same supplier. */
    fun vendorKey(vendor: String): String =
        vendor.uppercase().filter { it.isLetterOrDigit() || it == ' ' }
            .split(' ').filter { it.isNotEmpty() && it !in setOf("INC", "CORP", "CO", "THE") }
            .joinToString(" ")
}
