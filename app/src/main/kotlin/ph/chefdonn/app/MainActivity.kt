package ph.chefdonn.app

import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.combine
import ph.chefdonn.app.screens.CashierScreen
import ph.chefdonn.app.screens.KitchenScreen
import ph.chefdonn.app.screens.SetupScreen
import ph.chefdonn.app.screens.WaiterScreen
import ph.chefdonn.client.SyncState
import ph.chefdonn.designsystem.DonnSurface
import ph.chefdonn.designsystem.DonnText
import ph.chefdonn.designsystem.DonnType
import ph.chefdonn.designsystem.DsGallery
import ph.chefdonn.domain.Role

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A service tool, not a phone app: the screen never sleeps mid-service.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { AppRoot() }
    }
}

@Composable
fun AppRoot(vm: AppViewModel = viewModel()) {
    val loaded by vm.loaded.collectAsState()
    val config by vm.config.collectAsState()
    val client by vm.client.collectAsState()
    val context = LocalContext.current

    // Rejections must be seen, not swallowed.
    LaunchedEffect(client) {
        client?.acks?.collect { ack ->
            if (!ack.accepted) {
                Toast.makeText(context, ack.reason ?: "Action rejected by hub", Toast.LENGTH_LONG).show()
            }
        }
    }

    // Back online after queuing: say how many actions made it through.
    LaunchedEffect(client) {
        val c = client ?: return@LaunchedEffect
        var lastSync = SyncState.OFFLINE
        var lastPending = 0
        combine(c.sync, c.pendingCount) { s, p -> s to p }.collect { (s, p) ->
            if (s == SyncState.ONLINE && lastSync == SyncState.QUEUING && lastPending > 0) {
                val n = lastPending
                Toast.makeText(context, "Back on mesh — $n action${if (n == 1) "" else "s"} synced", Toast.LENGTH_LONG).show()
            }
            lastSync = s
            if (p > 0) lastPending = p
        }
    }

    var showGallery by remember { mutableStateOf(false) }
    var galleryNight by remember { mutableStateOf(false) }
    if (showGallery && BuildConfig.DEBUG) {
        DsGallery(night = galleryNight, onToggleNight = { galleryNight = !galleryNight })
        return
    }

    when {
        !loaded -> DonnSurface {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                DonnText("Chef Donn's", style = DonnType.displayXl)
            }
        }

        config == null -> SetupScreen(
            vm = vm,
            onOpenGallery = if (BuildConfig.DEBUG) ({ showGallery = true }) else null,
        )

        else -> {
            val cfg = config!!
            val c = client ?: return
            when (cfg.role) {
                Role.WAITER -> WaiterScreen(cfg, c, vm)
                Role.KITCHEN -> KitchenScreen(cfg, c, vm)
                Role.CASHIER -> CashierScreen(cfg, c, vm)
                Role.HUB -> SetupScreen(vm = vm, onOpenGallery = null)
            }
        }
    }
}
