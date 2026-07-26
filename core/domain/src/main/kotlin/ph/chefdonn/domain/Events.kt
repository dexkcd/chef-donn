package ph.chefdonn.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The append-only order stream. The hub is the only writer; every app's state is a
 * fold of these events via [reduce]. Timestamps are hub wall-clock millis — each
 * LineStatusSet is also the depletion signal the future inventory module consumes.
 */
@Serializable
sealed interface DomainEvent {
    val at: Long
}

@Serializable
@SerialName("OrderOpened")
data class OrderOpened(
    val orderId: String,
    val tableId: String,
    val covers: Int,
    val openedBy: String,
    override val at: Long,
) : DomainEvent

@Serializable
@SerialName("LinesAdded")
data class LinesAdded(
    val orderId: String,
    val lines: List<LineSpec>,
    val addedBy: String,
    override val at: Long,
) : DomainEvent

@Serializable
@SerialName("LineStatusSet")
data class LineStatusSet(
    val orderId: String,
    val lineIds: List<String>,
    val status: LineStatus,
    val setBy: String,
    override val at: Long,
) : DomainEvent

@Serializable
@SerialName("LineVoided")
data class LineVoided(
    val orderId: String,
    val lineId: String,
    val reason: String,
    val voidedBy: String,
    val role: Role,
    override val at: Long,
) : DomainEvent

@Serializable
@SerialName("OrderBilled")
data class OrderBilled(
    val orderId: String,
    val billedBy: String,
    override val at: Long,
) : DomainEvent

@Serializable
@SerialName("MenuUpdated")
data class MenuUpdated(
    val items: List<MenuItem>,
    override val at: Long,
) : DomainEvent

@Serializable
@SerialName("TablesUpdated")
data class TablesUpdated(
    val tables: List<TableInfo>,
    override val at: Long,
) : DomainEvent

/** Pure fold. Must stay deterministic: hub and every client run the same function. */
fun reduce(state: AppState, event: DomainEvent): AppState = when (event) {
    is OrderOpened -> state.copy(
        orders = state.orders + (event.orderId to Order(
            orderId = event.orderId,
            tableId = event.tableId,
            covers = event.covers,
            openedBy = event.openedBy,
            openedAt = event.at,
        )),
    )

    is LinesAdded -> state.updateOrder(event.orderId) { order ->
        val existing = order.lines.map { it.spec.lineId }.toSet()
        val newLines = event.lines
            .filter { it.lineId !in existing }
            .map { OrderLine(spec = it, status = LineStatus.QUEUED, statusAt = event.at) }
        order.copy(lines = order.lines + newLines)
    }

    is LineStatusSet -> state.updateOrder(event.orderId) { order ->
        order.copy(lines = order.lines.map { line ->
            if (line.spec.lineId in event.lineIds && line.status.canAdvanceTo(event.status)) {
                line.copy(status = event.status, statusAt = event.at)
            } else line
        })
    }

    is LineVoided -> state.updateOrder(event.orderId) { order ->
        order.copy(lines = order.lines.map { line ->
            if (line.spec.lineId == event.lineId && line.status.canBeVoided) {
                line.copy(
                    status = LineStatus.VOIDED,
                    statusAt = event.at,
                    voidReason = event.reason,
                    voidedBy = event.voidedBy,
                )
            } else line
        })
    }

    is OrderBilled -> state.updateOrder(event.orderId) { order ->
        order.copy(billed = true, billedAt = event.at)
    }

    is MenuUpdated -> state.copy(menu = event.items)

    is TablesUpdated -> state.copy(tables = event.tables)
}

fun reduceAll(state: AppState, events: List<DomainEvent>): AppState =
    events.fold(state, ::reduce)

/** Forward-only, jumps allowed; VOIDED and SERVED are terminal. */
fun LineStatus.canAdvanceTo(target: LineStatus): Boolean =
    this != LineStatus.VOIDED && this != LineStatus.SERVED &&
        target != LineStatus.VOIDED && target.ordinal > ordinal

/** Void allowed any time before the plate reaches the guest. */
val LineStatus.canBeVoided: Boolean
    get() = this != LineStatus.SERVED && this != LineStatus.VOIDED

private inline fun AppState.updateOrder(orderId: String, f: (Order) -> Order): AppState {
    val order = orders[orderId] ?: return this
    return copy(orders = orders + (orderId to f(order)))
}
