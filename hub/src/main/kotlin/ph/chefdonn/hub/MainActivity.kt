package ph.chefdonn.hub

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import ph.chefdonn.designsystem.ButtonSize
import ph.chefdonn.designsystem.ButtonVariant
import ph.chefdonn.designsystem.DonnButton
import ph.chefdonn.designsystem.DonnDimens
import ph.chefdonn.designsystem.DonnSurface
import ph.chefdonn.designsystem.DonnText
import ph.chefdonn.designsystem.DonnTextField
import ph.chefdonn.designsystem.DonnType
import ph.chefdonn.designsystem.donnColors
import ph.chefdonn.domain.MenuItem
import ph.chefdonn.domain.UpdateMenu
import ph.chefdonn.domain.UpdateTables
import ph.chefdonn.protocol.DEFAULT_HUB_PORT
import java.net.NetworkInterface
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        HubRuntime.ensureStarted(this)
        setContent { HubAdminScreen() }
    }
}

private fun localIps(): List<String> =
    NetworkInterface.getNetworkInterfaces().toList()
        .flatMap { it.inetAddresses.toList() }
        .filter { !it.isLoopbackAddress && it.address.size == 4 }
        .map { it.hostAddress ?: "" }
        .filter { it.isNotBlank() }

@Composable
fun HubAdminScreen() {
    // Engine appears once the foreground service is up; poll briefly at boot.
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (HubRuntime.engine == null) {
            delay(200)
            tick++
        }
    }
    val engine = HubRuntime.engine
    DonnSurface(night = false) {
        if (engine == null) {
            DonnText("Starting hub…", style = DonnType.display, modifier = Modifier.padding(DonnDimens.space8))
            return@DonnSurface
        }
        val scope = rememberCoroutineScope()
        val state by engine.state.collectAsState()
        val devices by (HubRuntime.registry?.devices ?: MutableStateFlow(emptyList())).collectAsState()

        LaunchedEffect(state.menu.isEmpty(), state.tables.isEmpty()) {
            // First boot: seed so a demo works out of the box.
            if (state.tables.isEmpty()) {
                engine.submit(UpdateTables(UUID.randomUUID().toString(), "hub", "Hub", SeedData.tables))
            }
            if (state.menu.isEmpty()) {
                engine.submit(UpdateMenu(UUID.randomUUID().toString(), "hub", "Hub", SeedData.menu))
            }
        }

        Row(Modifier.fillMaxSize().padding(DonnDimens.space6), horizontalArrangement = Arrangement.spacedBy(DonnDimens.space8)) {
            // Left: status
            Column(Modifier.width(360.dp), verticalArrangement = Arrangement.spacedBy(DonnDimens.space4)) {
                DonnText("Chef Donn's Hub", style = DonnType.displayLg)
                StatusRow("Port", DEFAULT_HUB_PORT.toString())
                StatusRow("Address", localIps().joinToString("  "))
                StatusRow("Events", engine.currentSeq().toString())
                StatusRow("Open tables", state.orders.values.count { !it.billed }.toString())

                DonnText("Connected Devices", style = DonnType.display)
                if (devices.isEmpty()) DonnText("None yet", style = DonnType.body, color = donnColors.textSecondary)
                devices.forEach { d ->
                    Row(horizontalArrangement = Arrangement.spacedBy(DonnDimens.space3), verticalAlignment = Alignment.CenterVertically) {
                        DonnText(d.role.name, style = DonnType.label, color = donnColors.accentGreen)
                        DonnText(d.deviceName, style = DonnType.body)
                    }
                }
            }

            // Right: menu admin
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(DonnDimens.space3)) {
                DonnText("Menu (${state.menu.size} items)", style = DonnType.display)
                MenuEditor(
                    menu = state.menu,
                    onSave = { items ->
                        scope.launch { engine.submit(UpdateMenu(UUID.randomUUID().toString(), "hub", "Hub", items)) }
                    },
                )
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        DonnText(label, style = DonnType.label, color = donnColors.textSecondary)
        DonnText(value, style = DonnType.monoBold)
    }
}

@Composable
private fun MenuEditor(menu: List<MenuItem>, onSave: (List<MenuItem>) -> Unit) {
    var name by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }
    var station by remember { mutableStateOf("MAINS") }

    Row(horizontalArrangement = Arrangement.spacedBy(DonnDimens.space3), verticalAlignment = Alignment.CenterVertically) {
        DonnTextField(name, { name = it }, placeholder = "Item name", modifier = Modifier.weight(1f))
        DonnTextField(price, { price = it.filter { ch -> ch.isDigit() } }, placeholder = "₱", modifier = Modifier.width(110.dp))
        DonnTextField(station, { station = it.uppercase() }, placeholder = "Station", modifier = Modifier.width(140.dp))
        DonnButton("Add", size = ButtonSize.MD, onClick = {
            val pesos = price.toLongOrNull() ?: return@DonnButton
            if (name.isBlank()) return@DonnButton
            onSave(
                menu + MenuItem(
                    id = "m-" + UUID.randomUUID().toString().take(8),
                    name = name.trim(),
                    priceCents = pesos * 100,
                    station = station.ifBlank { "MAINS" },
                    category = "Mains",
                )
            )
            name = ""; price = ""
        })
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(DonnDimens.space2)) {
        items(menu, key = { it.id }) { item ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    DonnText(item.name, style = if (item.active) DonnType.bodySemibold else DonnType.body,
                        color = if (item.active) donnColors.textPrimary else donnColors.textSecondary)
                    DonnText("${item.station} · ₱%,.2f".format(item.priceCents / 100.0), style = DonnType.caption, color = donnColors.textSecondary)
                }
                DonnButton(
                    if (item.active) "86 It" else "Restore",
                    size = ButtonSize.SM,
                    variant = if (item.active) ButtonVariant.SECONDARY else ButtonVariant.PRIMARY,
                    onClick = { onSave(menu.map { if (it.id == item.id) it.copy(active = !it.active) else it }) },
                )
            }
        }
    }
}
