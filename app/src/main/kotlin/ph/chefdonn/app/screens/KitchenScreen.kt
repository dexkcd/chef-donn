package ph.chefdonn.app.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.clickable
import androidx.compose.ui.unit.dp
import ph.chefdonn.app.AppViewModel
import ph.chefdonn.app.DeviceConfig
import ph.chefdonn.client.ChefDonnClient
import ph.chefdonn.designsystem.ButtonSize
import ph.chefdonn.designsystem.ButtonVariant
import ph.chefdonn.designsystem.DonnButton
import ph.chefdonn.designsystem.DonnDimens
import ph.chefdonn.designsystem.DonnSurface
import ph.chefdonn.designsystem.DonnTabs
import ph.chefdonn.designsystem.DonnText
import ph.chefdonn.designsystem.DonnType
import ph.chefdonn.designsystem.StatusBadge
import ph.chefdonn.designsystem.TicketCard
import ph.chefdonn.designsystem.donnColors
import ph.chefdonn.domain.LineStatus
import ph.chefdonn.domain.Order
import ph.chefdonn.domain.OrderLine
import ph.chefdonn.domain.Role
import ph.chefdonn.domain.SetLineStatus
import ph.chefdonn.domain.VoidLine
import java.util.UUID

/** KDS: dark, station-tabbed, tap a line to advance it, Bump All per ticket. */
@Composable
fun KitchenScreen(cfg: DeviceConfig, client: ChefDonnClient, vm: AppViewModel) {
    val state by client.state.collectAsState()

    val stations = remember(state.menu) {
        listOf("ALL") + state.menu.map { it.station }.distinct().sorted()
    }
    var tabIndex by remember { mutableIntStateOf(0) }
    val station = stations.getOrElse(tabIndex) { "ALL" }
    var voidTarget by remember { mutableStateOf<Pair<String, OrderLine>?>(null) }

    voidTarget?.let { (orderId, line) ->
        VoidDialog(
            line = line,
            quickReasons = listOf("Out of stock", "Remake"),
            onConfirm = { reason ->
                vm.submit(
                    VoidLine(
                        UUID.randomUUID().toString(), cfg.deviceId, cfg.deviceName,
                        orderId, line.spec.lineId, reason, Role.KITCHEN,
                    )
                )
                voidTarget = null
            },
            onDismiss = { voidTarget = null },
        )
    }

    // A ticket stays on screen while any line still needs kitchen attention.
    fun Order.kitchenLines(): List<OrderLine> =
        lines.filter { station == "ALL" || it.spec.station == station }

    fun Order.activeKitchenLines(): List<OrderLine> =
        kitchenLines().filter { it.status == LineStatus.QUEUED || it.status == LineStatus.COOKING }

    val tickets = state.orders.values
        .filter { !it.billed && it.activeKitchenLines().isNotEmpty() }
        .sortedBy { it.openedAt }

    val perStationCounts = stations.mapIndexed { i, s ->
        i to state.orders.values.filter { !it.billed }.sumOf { o ->
            o.lines.count { (s == "ALL" || it.spec.station == s) && (it.status == LineStatus.QUEUED || it.status == LineStatus.COOKING) }
        }
    }.toMap()

    DonnSurface(night = true) {
        Column(Modifier.fillMaxSize()) {
            StaffHeader("Kitchen", client)
            DonnTabs(
                stations,
                tabIndex,
                { tabIndex = it },
                badges = perStationCounts,
                modifier = Modifier.padding(horizontal = DonnDimens.space5),
            )

            if (tickets.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    DonnText("No open tickets", style = DonnType.display, color = donnColors.textSecondary)
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(340.dp),
                    modifier = Modifier.fillMaxSize().padding(DonnDimens.space5),
                    horizontalArrangement = Arrangement.spacedBy(DonnDimens.space4),
                    verticalArrangement = Arrangement.spacedBy(DonnDimens.space4),
                ) {
                    items(tickets, key = { it.orderId }) { order ->
                        val visible = order.kitchenLines()
                        val tableLabel = state.tables.firstOrNull { it.id == order.tableId }?.label ?: order.tableId
                        Column {
                            TicketCard(
                                tableLabel = tableLabel,
                                startedAt = order.openedAt,
                                lines = visible,
                                lineRow = { line ->
                                    KitchenLineRow(line) {
                                        val next = when (line.status) {
                                            LineStatus.QUEUED -> LineStatus.COOKING
                                            LineStatus.COOKING -> LineStatus.READY
                                            else -> null
                                        }
                                        if (next != null) {
                                            vm.submit(
                                                SetLineStatus(
                                                    UUID.randomUUID().toString(), cfg.deviceId, cfg.deviceName,
                                                    order.orderId, listOf(line.spec.lineId), next,
                                                )
                                            )
                                        }
                                    }
                                },
                            )
                            val bumpable = order.activeKitchenLines()
                            if (bumpable.isNotEmpty()) {
                                DonnButton(
                                    "Bump All",
                                    size = ButtonSize.MD,
                                    variant = ButtonVariant.PRIMARY,
                                    modifier = Modifier.fillMaxWidth().padding(top = DonnDimens.space2),
                                    onClick = {
                                        vm.submit(
                                            SetLineStatus(
                                                UUID.randomUUID().toString(), cfg.deviceId, cfg.deviceName,
                                                order.orderId, bumpable.map { it.spec.lineId }, LineStatus.READY,
                                            )
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun KitchenLineRow(line: OrderLine, onAdvance: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = line.status == LineStatus.QUEUED || line.status == LineStatus.COOKING, onClick = onAdvance)
            .padding(vertical = DonnDimens.space2),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            DonnText(
                "${line.spec.qty}× ${line.spec.name}",
                style = DonnType.bodyMd,
                color = if (line.isVoided) donnColors.textSecondary else donnColors.textPrimary,
            )
            line.spec.modifiers.forEach { DonnText("  • $it", style = DonnType.caption, color = donnColors.textSecondary) }
            if (line.spec.note.isNotBlank()) {
                DonnText("  “${line.spec.note}”", style = DonnType.caption, color = donnColors.accentGold)
            }
            if (line.isVoided) {
                DonnText("  ${line.voidReason ?: ""}", style = DonnType.caption, color = donnColors.accentRed)
            }
        }
        StatusBadge(line.status)
    }
}
