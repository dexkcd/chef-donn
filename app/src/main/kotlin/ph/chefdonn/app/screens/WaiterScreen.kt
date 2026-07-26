package ph.chefdonn.app.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import ph.chefdonn.app.AppViewModel
import ph.chefdonn.app.DeviceConfig
import ph.chefdonn.client.ChefDonnClient
import ph.chefdonn.designsystem.ButtonSize
import ph.chefdonn.designsystem.ButtonVariant
import ph.chefdonn.designsystem.DonnButton
import ph.chefdonn.designsystem.DonnDimens
import ph.chefdonn.designsystem.DonnSurface
import ph.chefdonn.designsystem.DonnText
import ph.chefdonn.designsystem.DonnTextField
import ph.chefdonn.designsystem.DonnType
import ph.chefdonn.designsystem.QtyStepper
import ph.chefdonn.designsystem.StatusBadge
import ph.chefdonn.designsystem.TableChip
import ph.chefdonn.designsystem.donnColors
import ph.chefdonn.domain.AddLines
import ph.chefdonn.domain.FireOrder
import ph.chefdonn.domain.LineSpec
import ph.chefdonn.domain.LineStatus
import ph.chefdonn.domain.MenuItem
import ph.chefdonn.domain.Role
import ph.chefdonn.domain.SetLineStatus
import ph.chefdonn.domain.TableInfo
import ph.chefdonn.domain.VoidLine
import ph.chefdonn.domain.canBeVoided
import java.util.UUID

private sealed interface WaiterNav {
    data object Floor : WaiterNav
    data class Table(val tableId: String) : WaiterNav
    data class Compose(val tableId: String) : WaiterNav
}

@Composable
fun WaiterScreen(cfg: DeviceConfig, client: ChefDonnClient, vm: AppViewModel) {
    val state by client.state.collectAsState()
    var nav by remember { mutableStateOf<WaiterNav>(WaiterNav.Floor) }

    DonnSurface(night = false) {
        Column(Modifier.fillMaxSize()) {
            when (val n = nav) {
                is WaiterNav.Floor -> {
                    StaffHeader("Tables", client)
                    FloorView(
                        tables = state.tables,
                        occupied = state.orders.values.filter { !it.billed }.map { it.tableId }.toSet(),
                        onPick = { nav = WaiterNav.Table(it) },
                    )
                }

                is WaiterNav.Table -> {
                    StaffHeader(
                        state.tables.firstOrNull { it.id == n.tableId }?.label ?: n.tableId,
                        client,
                        actions = {
                            DonnButton("Back", size = ButtonSize.SM, variant = ButtonVariant.GHOST, onClick = { nav = WaiterNav.Floor })
                        },
                    )
                    TableDetail(
                        tableId = n.tableId,
                        client = client,
                        cfg = cfg,
                        vm = vm,
                        onAddItems = { nav = WaiterNav.Compose(n.tableId) },
                    )
                }

                is WaiterNav.Compose -> {
                    StaffHeader(
                        "Order — " + (state.tables.firstOrNull { it.id == n.tableId }?.label ?: n.tableId),
                        client,
                        actions = {
                            DonnButton("Cancel", size = ButtonSize.SM, variant = ButtonVariant.GHOST, onClick = { nav = WaiterNav.Table(n.tableId) })
                        },
                    )
                    OrderComposer(
                        tableId = n.tableId,
                        cfg = cfg,
                        client = client,
                        vm = vm,
                        onFired = { nav = WaiterNav.Table(n.tableId) },
                    )
                }
            }
        }
    }
}

@Composable
private fun FloorView(tables: List<TableInfo>, occupied: Set<String>, onPick: (String) -> Unit) {
    if (tables.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            DonnText("Waiting for hub — no tables yet", style = DonnType.body, color = donnColors.textSecondary)
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(DonnDimens.tapTargetComfortable + DonnDimens.space4),
        modifier = Modifier.fillMaxSize().padding(DonnDimens.space5),
        horizontalArrangement = Arrangement.spacedBy(DonnDimens.space4),
        verticalArrangement = Arrangement.spacedBy(DonnDimens.space4),
    ) {
        items(tables, key = { it.id }) { t ->
            TableChip(t.label, occupied = t.id in occupied, onClick = { onPick(t.id) })
        }
    }
}

@Composable
private fun TableDetail(
    tableId: String,
    client: ChefDonnClient,
    cfg: DeviceConfig,
    vm: AppViewModel,
    onAddItems: () -> Unit,
) {
    val state by client.state.collectAsState()
    val order = state.openOrderForTable(tableId)
    var voidTarget by remember { mutableStateOf<ph.chefdonn.domain.OrderLine?>(null) }

    voidTarget?.let { line ->
        VoidDialog(
            line = line,
            quickReasons = listOf("Guest changed mind", "Wrong entry", "Out of stock"),
            onConfirm = { reason ->
                order?.let {
                    vm.submit(
                        VoidLine(
                            UUID.randomUUID().toString(), cfg.deviceId, cfg.deviceName,
                            it.orderId, line.spec.lineId, reason, Role.WAITER,
                        )
                    )
                }
                voidTarget = null
            },
            onDismiss = { voidTarget = null },
        )
    }

    Column(Modifier.fillMaxSize().padding(DonnDimens.space5), verticalArrangement = Arrangement.spacedBy(DonnDimens.space4)) {
        if (order == null) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                DonnText("No open order", style = DonnType.body, color = donnColors.textSecondary)
            }
        } else {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(DonnDimens.space3)) {
                items(order.lines, key = { it.spec.lineId }) { line ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            DonnText(
                                "${line.spec.qty}× ${line.spec.name}",
                                style = DonnType.bodyMd,
                                color = if (line.isVoided) donnColors.textSecondary else donnColors.textPrimary,
                            )
                            if (line.spec.note.isNotBlank()) {
                                DonnText("“${line.spec.note}”", style = DonnType.caption, color = donnColors.textSecondary)
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DonnDimens.space2)) {
                            StatusBadge(line.status)
                            if (line.status == LineStatus.READY) {
                                DonnButton("Serve", size = ButtonSize.SM, onClick = {
                                    vm.submit(
                                        SetLineStatus(
                                            UUID.randomUUID().toString(), cfg.deviceId, cfg.deviceName,
                                            order.orderId, listOf(line.spec.lineId), LineStatus.SERVED,
                                        )
                                    )
                                })
                            }
                            if (line.status.canBeVoided) {
                                DonnButton(
                                    "Void",
                                    size = ButtonSize.SM,
                                    variant = ButtonVariant.GHOST,
                                    onClick = { voidTarget = line },
                                )
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                DonnText("Total", style = DonnType.label, color = donnColors.textSecondary)
                DonnText(formatPesos(order.totalCents), style = DonnType.monoLg)
            }
        }
        DonnButton(
            if (order == null) "Take Order" else "Add Items",
            onClick = onAddItems,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private data class CartLine(val item: MenuItem, val qty: Int, val note: String)

@Composable
private fun OrderComposer(
    tableId: String,
    cfg: DeviceConfig,
    client: ChefDonnClient,
    vm: AppViewModel,
    onFired: () -> Unit,
) {
    val state by client.state.collectAsState()
    val menu = state.menu.filter { it.active }
    val cart = remember { mutableStateMapOf<String, CartLine>() }
    var covers by remember { mutableStateOf(2) }
    val existingOrder = state.openOrderForTable(tableId)

    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).padding(horizontal = DonnDimens.space5), verticalArrangement = Arrangement.spacedBy(DonnDimens.space2)) {
            val grouped = menu.groupBy { it.category }
            grouped.forEach { (category, items) ->
                item(key = "hdr-$category") {
                    DonnText(
                        category.uppercase(),
                        style = DonnType.labelLg,
                        color = donnColors.textSecondary,
                        modifier = Modifier.padding(top = DonnDimens.space4),
                    )
                }
                items(items, key = { it.id }) { mi ->
                    val inCart = cart[mi.id]
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            DonnText(mi.name, style = DonnType.bodyMd)
                            DonnText(formatPesos(mi.priceCents), style = DonnType.mono, color = donnColors.textSecondary)
                        }
                        if (inCart == null) {
                            DonnButton("Add", size = ButtonSize.SM, variant = ButtonVariant.SECONDARY, onClick = {
                                cart[mi.id] = CartLine(mi, 1, "")
                            })
                        } else {
                            QtyStepper(inCart.qty, { q ->
                                if (q <= 0) cart.remove(mi.id) else cart[mi.id] = inCart.copy(qty = q)
                            })
                        }
                    }
                }
            }
        }

        // Cart summary + fire
        Column(
            Modifier.fillMaxWidth().padding(DonnDimens.space5),
            verticalArrangement = Arrangement.spacedBy(DonnDimens.space3),
        ) {
            if (existingOrder == null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DonnDimens.space4)) {
                    DonnText("Covers", style = DonnType.label, color = donnColors.textSecondary)
                    QtyStepper(covers, { covers = it }, min = 1)
                }
            }
            cart.values.sortedBy { it.item.name }.forEach { cl ->
                var note by remember(cl.item.id) { mutableStateOf(cl.note) }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DonnDimens.space3)) {
                    DonnText("${cl.qty}×", style = DonnType.monoBold)
                    DonnText(cl.item.name, style = DonnType.bodySemibold, modifier = Modifier.weight(1f))
                    DonnTextField(
                        note,
                        {
                            note = it
                            cart[cl.item.id] = cl.copy(note = it)
                        },
                        placeholder = "Note to kitchen",
                        modifier = Modifier.weight(1.2f),
                        textStyle = DonnType.caption,
                    )
                }
            }
            val total = cart.values.sumOf { it.item.priceCents * it.qty }
            DonnButton(
                if (cart.isEmpty()) "Fire to Kitchen" else "Fire to Kitchen — ${formatPesos(total)}",
                enabled = cart.isNotEmpty(),
                onClick = {
                    val lines = cart.values.map { cl ->
                        LineSpec(
                            lineId = UUID.randomUUID().toString(),
                            menuItemId = cl.item.id,
                            name = cl.item.name,
                            priceCents = cl.item.priceCents,
                            qty = cl.qty,
                            station = cl.item.station,
                            note = cl.note.trim(),
                        )
                    }
                    if (existingOrder == null) {
                        vm.submit(
                            FireOrder(
                                UUID.randomUUID().toString(), cfg.deviceId, cfg.deviceName,
                                UUID.randomUUID().toString(), tableId, covers, lines,
                            )
                        )
                    } else {
                        vm.submit(
                            AddLines(UUID.randomUUID().toString(), cfg.deviceId, cfg.deviceName, existingOrder.orderId, lines)
                        )
                    }
                    onFired()
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
