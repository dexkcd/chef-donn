package ph.chefdonn.domain

import kotlinx.serialization.Serializable

/** OFFICE is the back-office role (expense receipts); it never touches orders. */
@Serializable
enum class Role { WAITER, KITCHEN, CASHIER, HUB, OFFICE }

/**
 * QUEUED → COOKING → READY → SERVED, forward-only (jumps allowed, e.g. a direct
 * bump QUEUED → READY). VOIDED is terminal and reachable from any pre-SERVED state.
 */
@Serializable
enum class LineStatus { QUEUED, COOKING, READY, SERVED, VOIDED }

@Serializable
data class MenuItem(
    val id: String,
    val name: String,
    /** Centavos, never floating-point pesos. */
    val priceCents: Long,
    val station: String,
    val category: String,
    val active: Boolean = true,
)

@Serializable
data class TableInfo(
    val id: String,
    val label: String,
)

/**
 * Immutable snapshot of what was ordered: name/price are copied from the menu at
 * order time so later menu edits never change a fired ticket or a running tab.
 */
@Serializable
data class LineSpec(
    val lineId: String,
    val menuItemId: String,
    val name: String,
    val priceCents: Long,
    val qty: Int,
    val station: String,
    val modifiers: List<String> = emptyList(),
    val note: String = "",
    val course: Int = 1,
)

@Serializable
data class OrderLine(
    val spec: LineSpec,
    val status: LineStatus = LineStatus.QUEUED,
    val statusAt: Long = 0,
    val voidReason: String? = null,
    val voidedBy: String? = null,
) {
    val isVoided: Boolean get() = status == LineStatus.VOIDED
    val amountCents: Long get() = if (isVoided) 0 else spec.priceCents * spec.qty
}

@Serializable
data class Order(
    val orderId: String,
    val tableId: String,
    val covers: Int,
    val openedBy: String,
    val openedAt: Long,
    val lines: List<OrderLine> = emptyList(),
    val billed: Boolean = false,
    val billedAt: Long? = null,
) {
    val totalCents: Long get() = lines.sumOf { it.amountCents }
}

/**
 * Bucket for expense receipts. Receipts reference a category by id, so a rename
 * applies everywhere; a category with receipts is archived, never removed.
 */
@Serializable
data class ExpenseCategory(
    val id: String,
    val name: String,
    val archived: Boolean = false,
)

/**
 * What the user filed for one expense receipt. receiptId and photoIds are
 * client-generated UUIDs; the photos themselves live in the hub's photo store,
 * not in the event log.
 */
@Serializable
data class ReceiptSpec(
    val receiptId: String,
    val vendor: String,
    /** Centavos, never floating-point pesos. */
    val totalCents: Long,
    /** Calendar date printed on the receipt, ISO-8601 (yyyy-MM-dd). */
    val purchasedOn: String,
    val categoryId: String,
    val photoIds: List<String> = emptyList(),
    val note: String = "",
)

@Serializable
data class Receipt(
    val spec: ReceiptSpec,
    val recordedBy: String,
    val recordedAt: Long,
    val updatedAt: Long,
    val deleted: Boolean = false,
    val deletedReason: String? = null,
)

@Serializable
data class AppState(
    val menu: List<MenuItem> = emptyList(),
    val tables: List<TableInfo> = emptyList(),
    val orders: Map<String, Order> = emptyMap(),
    val expenseCategories: List<ExpenseCategory> = emptyList(),
    val receipts: Map<String, Receipt> = emptyMap(),
) {
    fun openOrderForTable(tableId: String): Order? =
        orders.values.firstOrNull { it.tableId == tableId && !it.billed }

    fun line(orderId: String, lineId: String): OrderLine? =
        orders[orderId]?.lines?.firstOrNull { it.spec.lineId == lineId }

    fun expenseCategory(id: String): ExpenseCategory? =
        expenseCategories.firstOrNull { it.id == id }
}
