package ph.chefdonn.receipts

import ph.chefdonn.domain.AppState
import ph.chefdonn.domain.Command
import ph.chefdonn.domain.Decision
import ph.chefdonn.domain.DeleteReceipt
import ph.chefdonn.domain.EditReceipt
import ph.chefdonn.domain.RecordReceipt
import ph.chefdonn.domain.UpdateExpenseCategories
import ph.chefdonn.domain.decide
import ph.chefdonn.domain.reduceAll

/**
 * Hub state plus this device's not-yet-acked receipt commands, folded through the
 * same decide/reduce the hub will run. A receipt filed at the market shows up in
 * the list right away (tagged QUEUED); one the hub would reject never appears.
 */
fun AppState.withPendingReceipts(pending: List<Command>, now: Long): AppState =
    pending.filter { it.receiptId() != null || it is UpdateExpenseCategories }
        .fold(this) { s, cmd ->
            when (val d = decide(s, cmd, now)) {
                is Decision.Accepted -> reduceAll(s, d.events)
                is Decision.Rejected -> s
            }
        }

/** Receipts with a change still waiting for the hub. */
fun pendingReceiptIds(pending: List<Command>): Set<String> =
    pending.mapNotNull { it.receiptId() }.toSet()

private fun Command.receiptId(): String? = when (this) {
    is RecordReceipt -> receipt.receiptId
    is EditReceipt -> receipt.receiptId
    is DeleteReceipt -> receiptId
    else -> null
}
