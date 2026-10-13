package ph.chefdonn.receipts

import ph.chefdonn.domain.AppState
import ph.chefdonn.domain.ExpenseCategory
import ph.chefdonn.domain.Receipt
import java.time.YearMonth

data class CategoryTotal(val category: ExpenseCategory, val totalCents: Long, val count: Int)

data class MonthSummary(
    val month: YearMonth,
    val receipts: List<Receipt>,
    val totalCents: Long,
    val byCategory: List<CategoryTotal>,
)

/**
 * The month view of the receipt book: live (not deleted) receipts dated in [month],
 * newest first, with per-category totals biggest first.
 */
fun AppState.receiptMonth(month: YearMonth, categoryId: String? = null): MonthSummary {
    val prefix = month.toString() // yyyy-MM, a prefix of the ISO purchasedOn
    val inMonth = receipts.values
        .filter { !it.deleted && it.spec.purchasedOn.startsWith(prefix) }
    val shown = inMonth
        .filter { categoryId == null || it.spec.categoryId == categoryId }
        .sortedWith(compareByDescending<Receipt> { it.spec.purchasedOn }.thenByDescending { it.recordedAt })
    val byCategory = inMonth.groupBy { it.spec.categoryId }
        .mapNotNull { (id, rs) ->
            val category = expenseCategory(id) ?: return@mapNotNull null
            CategoryTotal(category, rs.sumOf { it.spec.totalCents }, rs.size)
        }
        .sortedByDescending { it.totalCents }
    return MonthSummary(month, shown, inMonth.sumOf { it.spec.totalCents }, byCategory)
}
