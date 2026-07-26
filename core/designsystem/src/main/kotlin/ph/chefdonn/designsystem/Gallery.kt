package ph.chefdonn.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import ph.chefdonn.client.SyncState
import ph.chefdonn.domain.LineSpec
import ph.chefdonn.domain.LineStatus
import ph.chefdonn.domain.OrderLine

/**
 * Debug-only component gallery: every primitive in both Day and Night schemes.
 * Reached from the staff app's setup screen in debug builds.
 */
@Composable
fun DsGallery(night: Boolean, onToggleNight: () -> Unit) {
    DonnSurface(night = night) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(DonnDimens.space6),
            verticalArrangement = Arrangement.spacedBy(DonnDimens.space5),
        ) {
            DonnText("Chef Donn's", style = DonnType.displayXl)
            DonnText(if (night) "Night (kitchen)" else "Day (waiter/cashier)", style = DonnType.label, color = donnColors.textSecondary)
            DonnButton(if (night) "Switch to Day" else "Switch to Night", onClick = onToggleNight, variant = ButtonVariant.SECONDARY, size = ButtonSize.MD)

            DonnText("Buttons", style = DonnType.display)
            DonnButton("Fire to Kitchen", onClick = {})
            Row(horizontalArrangement = Arrangement.spacedBy(DonnDimens.space3)) {
                DonnButton("Void Item", onClick = {}, variant = ButtonVariant.DANGER, size = ButtonSize.MD)
                DonnButton("Mark Billed", onClick = {}, variant = ButtonVariant.SECONDARY, size = ButtonSize.MD)
            }

            DonnText("Status", style = DonnType.display)
            Row(horizontalArrangement = Arrangement.spacedBy(DonnDimens.space2)) {
                StatusBadge(LineStatus.QUEUED)
                StatusBadge(LineStatus.COOKING)
                StatusBadge(LineStatus.READY)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(DonnDimens.space2)) {
                StatusBadge(LineStatus.SERVED)
                StatusBadge(LineStatus.VOIDED)
                PriorityTag("Rush")
            }

            DonnText("Stepper", style = DonnType.display)
            var qty by remember { mutableIntStateOf(1) }
            QtyStepper(qty, { qty = it })

            DonnText("Tabs", style = DonnType.display)
            var tab by remember { mutableIntStateOf(0) }
            DonnTabs(listOf("Grill", "Fry", "Drinks"), tab, { tab = it }, badges = mapOf(0 to 3, 1 to 1))

            DonnText("Table Chips", style = DonnType.display)
            Row(horizontalArrangement = Arrangement.spacedBy(DonnDimens.space3)) {
                TableChip("T1")
                TableChip("T2", occupied = true)
                TableChip("T3", selected = true)
            }

            DonnText("Input", style = DonnType.display)
            var note by remember { mutableStateOf("") }
            DonnTextField(note, { note = it }, placeholder = "Note to kitchen", modifier = Modifier.fillMaxWidth())

            DonnText("Ticket", style = DonnType.display)
            TicketCard(
                tableLabel = "4",
                startedAt = System.currentTimeMillis() - 8 * 60_000,
                priority = true,
                lines = listOf(
                    OrderLine(LineSpec("l1", "m1", "Kare-Kare", 32000, 1, "MAINS"), LineStatus.COOKING),
                    OrderLine(LineSpec("l2", "m2", "Sinigang na Baboy", 28000, 2, "MAINS", note = "extra rice"), LineStatus.QUEUED),
                    OrderLine(LineSpec("l3", "m3", "Halo-Halo", 15000, 1, "DESSERT"), LineStatus.VOIDED, voidReason = "Out of stock"),
                ),
            )

            DonnText("Sync", style = DonnType.display)
            SyncIndicator(SyncState.ONLINE)
            SyncIndicator(SyncState.QUEUING, pendingCount = 3)
            SyncIndicator(SyncState.OFFLINE)
            RecoveryBanner(BannerKind.QUEUING, "Off mesh — 3 actions queued, will sync on reconnect")
            RecoveryBanner(BannerKind.OFFLINE, "No connection to hub")
        }
    }
}
