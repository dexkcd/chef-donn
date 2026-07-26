package ph.chefdonn.app.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ph.chefdonn.app.AppViewModel
import ph.chefdonn.app.DeviceConfig
import ph.chefdonn.client.ChefDonnClient
import ph.chefdonn.client.SyncState
import ph.chefdonn.designsystem.BannerKind
import ph.chefdonn.designsystem.DonnButton
import ph.chefdonn.designsystem.DonnDialog
import ph.chefdonn.designsystem.DonnDimens
import ph.chefdonn.designsystem.DonnSurface
import ph.chefdonn.designsystem.DonnText
import ph.chefdonn.designsystem.DonnType
import ph.chefdonn.designsystem.RecoveryBanner
import ph.chefdonn.designsystem.StatusBadge
import ph.chefdonn.designsystem.donnColors
import ph.chefdonn.domain.BillOrder
import ph.chefdonn.domain.Order
import java.util.UUID

/**
 * Read-only running tabs. One action only: Mark Billed. The cashier keys the
 * total into the BIR POS as today — this screen just replaces the paper chit.
 */
@Composable
fun CashierScreen(cfg: DeviceConfig, client: ChefDonnClient, vm: AppViewModel) {
    val state by client.state.collectAsState()
    val sync by client.sync.collectAsState()
    var selectedOrderId by remember { mutableStateOf<String?>(null) }
    var confirmBill by remember { mutableStateOf<Order?>(null) }

    val openOrders = state.orders.values.filter { !it.billed }.sortedBy { it.openedAt }
    val selected = openOrders.firstOrNull { it.orderId == selectedOrderId } ?: openOrders.firstOrNull()

    fun tableLabel(order: Order) = state.tables.firstOrNull { it.id == order.tableId }?.label ?: order.tableId

    DonnSurface(night = false) {
        Column(Modifier.fillMaxSize()) {
            StaffHeader(
                "Cashier",
                client,
                extraBanner = {
                    // A stale screen bills wrong — the one failure that costs real money.
                    if (sync != SyncState.ONLINE) {
                        RecoveryBanner(
                            BannerKind.OFFLINE,
                            "Do not bill while disconnected — totals may be missing recent voids or items",
                        )
                    }
                },
            )

            Row(Modifier.fillMaxSize().padding(DonnDimens.space5), horizontalArrangement = Arrangement.spacedBy(DonnDimens.space5)) {
                // Left: open tables
                LazyColumn(Modifier.width(300.dp), verticalArrangement = Arrangement.spacedBy(DonnDimens.space3)) {
                    if (openOrders.isEmpty()) {
                        item {
                            DonnText("No open tables", style = DonnType.body, color = donnColors.textSecondary)
                        }
                    }
                    items(openOrders, key = { it.orderId }) { order ->
                        val active = order.orderId == selected?.orderId
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .background(if (active) donnColors.surfaceSunken else donnColors.surface, RoundedCornerShape(DonnDimens.radiusMd))
                                .border(DonnDimens.borderThick, if (active) donnColors.accentGreen else donnColors.borderStrong, RoundedCornerShape(DonnDimens.radiusMd))
                                .clickable { selectedOrderId = order.orderId }
                                .padding(DonnDimens.space4),
                        ) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                DonnText("Table ${tableLabel(order)}", style = DonnType.display.copy(fontSize = 22.sp))
                                DonnText(formatPesos(order.totalCents), style = DonnType.monoBold)
                            }
                            val activeCount = order.lines.count { !it.isVoided }
                            val voidCount = order.lines.count { it.isVoided }
                            DonnText(
                                "$activeCount item${if (activeCount == 1) "" else "s"}" +
                                    if (voidCount > 0) " · $voidCount void" else "",
                                style = DonnType.caption,
                                color = donnColors.textSecondary,
                            )
                        }
                    }
                }

                // Right: the running tab
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(DonnDimens.space3)) {
                    if (selected == null) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            DonnText("Select a table", style = DonnType.body, color = donnColors.textSecondary)
                        }
                    } else {
                        DonnText("Table ${tableLabel(selected)}", style = DonnType.displayLg)
                        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(DonnDimens.space2)) {
                            items(selected.lines, key = { it.spec.lineId }) { line ->
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(DonnDimens.space3),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    DonnText("${line.spec.qty}×", style = DonnType.monoBold, modifier = Modifier.width(44.dp))
                                    DonnText(
                                        line.spec.name,
                                        style = DonnType.bodyMd,
                                        modifier = Modifier.weight(1f),
                                        textDecoration = if (line.isVoided) TextDecoration.LineThrough else null,
                                        color = if (line.isVoided) donnColors.textSecondary else donnColors.textPrimary,
                                    )
                                    StatusBadge(line.status)
                                    DonnText(
                                        formatPesos(line.spec.priceCents * line.spec.qty),
                                        style = DonnType.mono,
                                        textDecoration = if (line.isVoided) TextDecoration.LineThrough else null,
                                        color = if (line.isVoided) donnColors.textSecondary else donnColors.textPrimary,
                                        modifier = Modifier.width(110.dp),
                                    )
                                }
                            }
                        }
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            DonnText("TOTAL", style = DonnType.labelLg, color = donnColors.textSecondary)
                            DonnText(formatPesos(selected.totalCents), style = DonnType.monoLg.copy(fontSize = 36.sp))
                        }
                        DonnButton(
                            "Mark Billed",
                            enabled = sync == SyncState.ONLINE,
                            onClick = { confirmBill = selected },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }

        confirmBill?.let { order ->
            DonnDialog(
                title = "Mark Table ${tableLabel(order)} Billed",
                body = "Total ${formatPesos(order.totalCents)}. Key this amount into the POS. " +
                    "This closes the table on all three apps.",
                confirmText = "Mark Billed",
                onConfirm = {
                    vm.submit(BillOrder(UUID.randomUUID().toString(), cfg.deviceId, cfg.deviceName, order.orderId))
                    confirmBill = null
                },
                onDismiss = { confirmBill = null },
            )
        }
    }
}
