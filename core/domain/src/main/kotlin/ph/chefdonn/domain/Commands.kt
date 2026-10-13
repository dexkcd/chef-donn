package ph.chefdonn.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.format.DateTimeParseException

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

@Serializable
@SerialName("RecordReceipt")
data class RecordReceipt(
    override val commandId: String,
    override val deviceId: String,
    override val actor: String,
    val receipt: ReceiptSpec,
) : Command

/** Replaces every field of an existing receipt (re-categorize, fix an OCR misread). */
@Serializable
@SerialName("EditReceipt")
data class EditReceipt(
    override val commandId: String,
    override val deviceId: String,
    override val actor: String,
    val receipt: ReceiptSpec,
) : Command

@Serializable
@SerialName("DeleteReceipt")
data class DeleteReceipt(
    override val commandId: String,
    override val deviceId: String,
    override val actor: String,
    val receiptId: String,
    val reason: String,
) : Command

/** Whole-list replace, like [UpdateMenu]. Archive a category instead of dropping it. */
@Serializable
@SerialName("UpdateExpenseCategories")
data class UpdateExpenseCategories(
    override val commandId: String,
    override val deviceId: String,
    override val actor: String,
    val categories: List<ExpenseCategory>,
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
    is RecordReceipt -> when {
        cmd.receipt.receiptId in state.receipts -> Decision.Rejected("Receipt already recorded")
        else -> receiptProblem(state, cmd.receipt, keepCategoryId = null)
            ?.let { Decision.Rejected(it) }
            ?: Decision.Accepted(listOf(ReceiptRecorded(cmd.receipt, cmd.actor, now)))
    }

    is EditReceipt -> {
        val existing = state.receipts[cmd.receipt.receiptId]
        when {
            existing == null -> Decision.Rejected("Receipt not found")
            existing.deleted -> Decision.Rejected("Receipt was deleted")
            else -> receiptProblem(state, cmd.receipt, keepCategoryId = existing.spec.categoryId)
                ?.let { Decision.Rejected(it) }
                ?: Decision.Accepted(listOf(ReceiptEdited(cmd.receipt, cmd.actor, now)))
        }
    }

    is DeleteReceipt -> {
        val existing = state.receipts[cmd.receiptId]
        when {
            existing == null -> Decision.Rejected("Receipt not found")
            existing.deleted -> Decision.Accepted(emptyList()) // idempotent: already deleted
            else -> Decision.Accepted(listOf(ReceiptDeleted(cmd.receiptId, cmd.reason, cmd.actor, now)))
        }
    }

    is UpdateExpenseCategories -> {
        val ids = cmd.categories.map { it.id }
        val activeNames = cmd.categories.filter { !it.archived }.map { it.name.trim().lowercase() }
        val inUse = state.receipts.values.map { it.spec.categoryId }.toSet()
        val dropped = state.expenseCategories.filter { it.id !in ids && it.id in inUse }
        when {
            cmd.categories.any { it.id.isBlank() || it.name.isBlank() } -> Decision.Rejected("Every category needs a name")
            ids.size != ids.toSet().size -> Decision.Rejected("Duplicate category id")
            activeNames.size != activeNames.toSet().size -> Decision.Rejected("Two categories have the same name")
            dropped.isNotEmpty() -> Decision.Rejected("${dropped.first().name} has receipts — archive it instead")
            else -> Decision.Accepted(listOf(ExpenseCategoriesUpdated(cmd.categories, now)))
        }
    }
}

/**
 * Why a receipt can't be filed, or null if it can. [keepCategoryId] lets an edit
 * keep a category that was archived after the receipt was recorded.
 */
private fun receiptProblem(state: AppState, spec: ReceiptSpec, keepCategoryId: String?): String? {
    val category = state.expenseCategory(spec.categoryId)
    return when {
        spec.vendor.isBlank() -> "Vendor is required"
        spec.totalCents <= 0 -> "Total must be more than ₱0.00"
        !isIsoDate(spec.purchasedOn) -> "Date must look like 2026-10-12"
        category == null -> "Category not found"
        category.archived && category.id != keepCategoryId -> "${category.name} is archived — pick another category"
        else -> null
    }
}

private fun isIsoDate(s: String): Boolean = try {
    LocalDate.parse(s)
    true
} catch (_: DateTimeParseException) {
    false
}
