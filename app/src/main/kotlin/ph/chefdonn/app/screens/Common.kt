package ph.chefdonn.app.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import ph.chefdonn.client.ChefDonnClient
import ph.chefdonn.client.SyncState
import ph.chefdonn.designsystem.BannerKind
import ph.chefdonn.designsystem.DonnDimens
import ph.chefdonn.designsystem.DonnText
import ph.chefdonn.designsystem.DonnType
import ph.chefdonn.designsystem.RecoveryBanner
import ph.chefdonn.designsystem.SyncIndicator

fun formatPesos(cents: Long): String {
    val whole = cents / 100
    val frac = (cents % 100).toString().padStart(2, '0')
    val grouped = "%,d".format(whole)
    return "₱$grouped.$frac"
}

/**
 * Every staff screen's header: app identity, screen title, the honest sync dot,
 * and the degraded-state banner when off mesh.
 */
@Composable
fun StaffHeader(
    title: String,
    client: ChefDonnClient,
    modifier: Modifier = Modifier,
    extraBanner: (@Composable () -> Unit)? = null,
    actions: (@Composable () -> Unit)? = null,
) {
    val sync by client.sync.collectAsState()
    val pending by client.pendingCount.collectAsState()
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = DonnDimens.space5, vertical = DonnDimens.space3),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DonnText(title, style = DonnType.display)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DonnDimens.space4)) {
                actions?.invoke()
                SyncIndicator(sync, pendingCount = pending)
            }
        }
        when (sync) {
            SyncState.ONLINE -> Unit
            SyncState.QUEUING -> RecoveryBanner(
                BannerKind.QUEUING,
                "Off mesh — $pending action${if (pending == 1) "" else "s"} queued, will sync on reconnect",
            )
            SyncState.OFFLINE -> RecoveryBanner(BannerKind.OFFLINE, "No connection to hub")
        }
        extraBanner?.invoke()
    }
}
