package ph.chefdonn.app.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ph.chefdonn.app.AppViewModel
import ph.chefdonn.designsystem.ButtonSize
import ph.chefdonn.designsystem.ButtonVariant
import ph.chefdonn.designsystem.DonnButton
import ph.chefdonn.designsystem.DonnDimens
import ph.chefdonn.designsystem.DonnSurface
import ph.chefdonn.designsystem.DonnText
import ph.chefdonn.designsystem.DonnTextField
import ph.chefdonn.designsystem.DonnType
import ph.chefdonn.designsystem.donnColors
import ph.chefdonn.domain.Role

/** First-launch: pick this device's role, name it, point it at the hub. */
@Composable
fun SetupScreen(vm: AppViewModel, onOpenGallery: (() -> Unit)?) {
    val hubs by vm.discoveredHubs.collectAsState()
    DisposableEffect(Unit) {
        vm.startDiscovery()
        onDispose { vm.stopDiscovery() }
    }

    var role by remember { mutableStateOf<Role?>(null) }
    var deviceName by remember { mutableStateOf("") }
    var manualHost by remember { mutableStateOf("") }

    DonnSurface {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(DonnDimens.space8),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(DonnDimens.space5),
        ) {
            DonnText("Chef Donn's", style = DonnType.displayXl)
            DonnText("SET UP THIS DEVICE", style = DonnType.labelLg, color = donnColors.textSecondary)

            Column(Modifier.widthIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(DonnDimens.space4)) {
                DonnText("1 · This device is the…", style = DonnType.display)
                Row(horizontalArrangement = Arrangement.spacedBy(DonnDimens.space3)) {
                    RoleButton("Waiter", role == Role.WAITER, Modifier.weight(1f)) { role = Role.WAITER }
                    RoleButton("Kitchen", role == Role.KITCHEN, Modifier.weight(1f)) { role = Role.KITCHEN }
                    RoleButton("Cashier", role == Role.CASHIER, Modifier.weight(1f)) { role = Role.CASHIER }
                }

                DonnText("2 · Name it", style = DonnType.display)
                DonnTextField(
                    deviceName,
                    { deviceName = it },
                    placeholder = when (role) {
                        Role.WAITER -> "e.g. Ana"
                        Role.KITCHEN -> "e.g. Grill Station"
                        Role.CASHIER -> "e.g. Front Counter"
                        else -> "Device name"
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                DonnText("3 · Connect to the hub", style = DonnType.display)
                if (hubs.isEmpty()) {
                    DonnText("Searching this WiFi for the hub…", style = DonnType.body, color = donnColors.textSecondary)
                }
                hubs.forEach { hub ->
                    DonnButton(
                        "${hub.name} — ${hub.host}",
                        size = ButtonSize.MD,
                        variant = if (manualHost == hub.host) ButtonVariant.PRIMARY else ButtonVariant.SECONDARY,
                        onClick = { manualHost = hub.host },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                DonnTextField(
                    manualHost,
                    { manualHost = it.trim() },
                    placeholder = "…or hub address (e.g. 192.168.1.10)",
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = DonnType.mono,
                )

                DonnButton(
                    "Start Working",
                    enabled = role != null && deviceName.isNotBlank() && manualHost.isNotBlank(),
                    onClick = { vm.saveConfig(role!!, deviceName.trim(), manualHost) },
                    modifier = Modifier.fillMaxWidth(),
                )

                if (onOpenGallery != null) {
                    DonnButton("Design Gallery", size = ButtonSize.SM, variant = ButtonVariant.GHOST, onClick = onOpenGallery)
                }
            }
        }
    }
}

@Composable
private fun RoleButton(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    DonnButton(
        label,
        onClick = onClick,
        variant = if (selected) ButtonVariant.PRIMARY else ButtonVariant.SECONDARY,
        size = ButtonSize.MD,
        modifier = modifier,
    )
}
