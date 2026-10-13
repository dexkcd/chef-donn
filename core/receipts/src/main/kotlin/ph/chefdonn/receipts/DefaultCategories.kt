package ph.chefdonn.receipts

import ph.chefdonn.domain.ExpenseCategory

/** Seeded by the hub on first boot; owners rename, add or archive from the Office app. */
object DefaultCategories {
    const val PRODUCE = "cat-produce"
    const val MEAT_SEAFOOD = "cat-meat-seafood"
    const val DRY_GOODS = "cat-dry-goods"
    const val BEVERAGES = "cat-beverages"
    const val LPG_FUEL = "cat-lpg-fuel"
    const val UTILITIES = "cat-utilities"
    const val SUPPLIES = "cat-supplies"
    const val REPAIRS = "cat-repairs"
    const val OTHER = "cat-other"

    val all: List<ExpenseCategory> = listOf(
        ExpenseCategory(PRODUCE, "Produce"),
        ExpenseCategory(MEAT_SEAFOOD, "Meat & Seafood"),
        ExpenseCategory(DRY_GOODS, "Dry Goods"),
        ExpenseCategory(BEVERAGES, "Beverages"),
        ExpenseCategory(LPG_FUEL, "LPG & Fuel"),
        ExpenseCategory(UTILITIES, "Utilities"),
        ExpenseCategory(SUPPLIES, "Supplies & Packaging"),
        ExpenseCategory(REPAIRS, "Repairs & Maintenance"),
        ExpenseCategory(OTHER, "Other"),
    )
}
