package ph.chefdonn.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Commands are client intents. Ids (commandId, orderId, lineId) are client-generated
 * UUIDs so a command queued offline is replayable and idempotent: the hub drops any
 * commandId it has already applied.
 */
@Serializable
sealed interface Command {
    val commandId: String
    val deviceId: String
    val actor: String
}

@Serializable
@SerialName("FireOrder")
data class FireOrder(
    override val commandId: String,
    override val deviceId: String,
    override val actor: String,
    /** Client-proposed id; if the table already has an open order the lines join it instead. */
    val orderId: String,
    val tableId: String,
    val covers: Int,
    val lines: List<LineSpec>,
) : Command

@Serializable
@SerialName("AddLines")
data class AddLines(
    override val commandId: String,
    override val deviceId: String,
    override val actor: String,
    val orderId: String,
    val lines: List<LineSpec>,
) : Command

@Serializable
@SerialName("SetLineStatus")
data class SetLineStatus(
    override val commandId: String,
    override val deviceId: String,
    override val actor: String,
    val orderId: String,
    val lineIds: List<String>,
    val status: LineStatus,
) : Command

@Serializable
@SerialName("VoidLine")
data class VoidLine(
    override val commandId: String,
    override val deviceId: String,
    override val actor: String,
    val orderId: String,
    val lineId: String,
    val reason: String,
    val role: Role,
) : Command

@Serializable
@SerialName("BillOrder")
data class BillOrder(
    override val commandId: String,
    override val deviceId: String,
    override val actor: String,
    val orderId: String,
) : Command

@Serializable
@SerialName("UpdateMenu")
data class UpdateMenu(
    override val commandId: String,
    override val deviceId: String,
    override val actor: String,
    val items: List<MenuItem>,
) : Command

@Serializable
@SerialName("UpdateTables")
data class UpdateTables(
    override val commandId: String,
    override val deviceId: String,
    override val actor: String,
    val tables: List<TableInfo>,
) : Command

sealed interface Decision {
    data class Accepted(val events: List<DomainEvent>) : Decision
    /** The command could not apply. Never silent: the hub reports this back to the sender. */
    data class Rejected(val reason: String) : Decision
}

/**
 * The hub's single authority point: validates a command against current state and
 * produces the events to append. Deterministic — same state + command → same decision.
 */
fun decide(state: AppState, cmd: Command, now: Long): Decision = when (cmd) {
    is FireOrder -> {
        if (cmd.lines.isEmpty()) {
            Decision.Rejected("Nothing to fire — the order has no items")
        } else when (val open = state.openOrderForTable(cmd.tableId)) {
            // Deterministic merge: one open order per table. A concurrent FireOrder
            // from another device joins the existing tab instead of forking it.
            null -> Decision.Accepted(
                listOf(
                    OrderOpened(cmd.orderId, cmd.tableId, cmd.covers, cmd.actor, now),
                    LinesAdded(cmd.orderId, cmd.lines, cmd.actor, now),
                )
            )
            else -> Decision.Accepted(listOf(LinesAdded(open.orderId, cmd.lines, cmd.actor, now)))
        }
    }

    is AddLines -> {
        val order = state.orders[cmd.orderId]
        when {
            order == null -> Decision.Rejected("Order not found")
            order.billed -> Decision.Rejected("Table already billed — open a new order")
            cmd.lines.isEmpty() -> Decision.Rejected("No items to add")
            else -> Decision.Accepted(listOf(LinesAdded(cmd.orderId, cmd.lines, cmd.actor, now)))
        }
    }

    is SetLineStatus -> {
        val order = state.orders[cmd.orderId]
        if (order == null) {
            Decision.Rejected("Order not found")
        } else if (cmd.status == LineStatus.VOIDED) {
            Decision.Rejected("Use Void Item, not a status change")
        } else {
            val movable = order.lines.filter {
                it.spec.lineId in cmd.lineIds && it.status.canAdvanceTo(cmd.status)
            }
            val voidedHit = order.lines.any { it.spec.lineId in cmd.lineIds && it.isVoided }
            when {
                movable.isNotEmpty() -> Decision.Accepted(
                    listOf(LineStatusSet(cmd.orderId, movable.map { it.spec.lineId }, cmd.status, cmd.actor, now))
                )
                // Void beats a concurrent bump — surfaced, never silently dropped.
                voidedHit -> Decision.Rejected("Item was voided — status not changed")
                else -> Decision.Rejected("Item already past that status")
            }
        }
    }

    is VoidLine -> {
        val line = state.line(cmd.orderId, cmd.lineId)
        when {
            line == null -> Decision.Rejected("Item not found")
            line.isVoided -> Decision.Accepted(emptyList()) // idempotent: already void
            line.status == LineStatus.SERVED -> Decision.Rejected("Item already served — cannot void")
            else -> Decision.Accepted(
                listOf(LineVoided(cmd.orderId, cmd.lineId, cmd.reason, cmd.actor, cmd.role, now))
            )
        }
    }

    is BillOrder -> {
        val order = state.orders[cmd.orderId]
        when {
            order == null -> Decision.Rejected("Order not found")
            order.billed -> Decision.Accepted(emptyList()) // idempotent: already billed
            else -> Decision.Accepted(listOf(OrderBilled(cmd.orderId, cmd.actor, now)))
        }
    }

    is UpdateMenu -> Decision.Accepted(listOf(MenuUpdated(cmd.items, now)))

    is UpdateTables -> Decision.Accepted(listOf(TablesUpdated(cmd.tables, now)))
}
