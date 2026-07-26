package ph.chefdonn.domain

import kotlinx.serialization.Serializable

@Serializable
enum class Role { WAITER, KITCHEN, CASHIER, HUB }

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

@Serializable
data class AppState(
    val menu: List<MenuItem> = emptyList(),
    val tables: List<TableInfo> = emptyList(),
    val orders: Map<String, Order> = emptyMap(),
) {
    fun openOrderForTable(tableId: String): Order? =
        orders.values.firstOrNull { it.tableId == tableId && !it.billed }

    fun line(orderId: String, lineId: String): OrderLine? =
        orders[orderId]?.lines?.firstOrNull { it.spec.lineId == lineId }
}
