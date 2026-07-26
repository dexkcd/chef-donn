package ph.chefdonn.designsystem

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ph.chefdonn.client.SyncState

/**
 * components/feedback/SyncIndicator — the honest mesh dot. The one recurring motion
 * in the system: a steady breathing pulse while not ONLINE.
 */
@Composable
fun SyncIndicator(state: SyncState, modifier: Modifier = Modifier, pendingCount: Int = 0) {
    val (color, label) = when (state) {
        SyncState.ONLINE -> SyncColors.Online to "SYNCED"
        SyncState.QUEUING -> SyncColors.Queuing to "QUEUING — OFF MESH"
        SyncState.OFFLINE -> SyncColors.Offline to "OFFLINE"
    }
    val pulse = rememberInfiniteTransition(label = "sync-pulse")
    val alpha by pulse.animateFloat(
        initialValue = 1f,
        targetValue = if (state == SyncState.ONLINE) 1f else 0.35f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "sync-alpha",
    )
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DonnDimens.space2)) {
        Box(
            Modifier
                .size(12.dp)
                .graphicsLayer { this.alpha = alpha }
                .background(color, CircleShape)
        )
        DonnText(
            if (state == SyncState.QUEUING && pendingCount > 0) "$label ($pendingCount)" else label,
            style = DonnType.label,
            color = donnColors.textPrimary,
        )
    }
}

enum class BannerKind { QUEUING, OFFLINE, INFO }

/**
 * components/feedback/RecoveryBanner — full-width strip for degraded states.
 * Gold: off mesh, actions queued. Red: no connection to hub. Copy is factual.
 */
@Composable
fun RecoveryBanner(kind: BannerKind, message: String, modifier: Modifier = Modifier) {
    val c = donnColors
    val (bg, fg) = when (kind) {
        BannerKind.QUEUING -> c.accentGold to (if (c.night) Brand.Night95 else Brand.Black)
        BannerKind.OFFLINE -> c.accentRed to (if (c.night) Brand.Night95 else Brand.White)
        BannerKind.INFO -> c.accentGreen to (if (c.night) Brand.Night95 else Brand.Cream)
    }
    Row(
        modifier
            .fillMaxWidth()
            .background(bg)
            .padding(horizontal = DonnDimens.space4, vertical = DonnDimens.space3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DonnDimens.space3),
    ) {
        DonnText(if (kind == BannerKind.OFFLINE) "✕" else "●", style = DonnType.labelLg, color = fg)
        DonnText(message, style = DonnType.bodySemibold, color = fg)
    }
}

/**
 * components/overlay/Dialog — destructive confirms. States the consequence,
 * never apologizes. Confirm is DANGER when [destructive].
 */
@Composable
fun DonnDialog(
    title: String,
    body: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
    content: (@Composable () -> Unit)? = null,
) {
    val c = donnColors
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .widthIn(min = 320.dp, max = 480.dp)
                .padding(DonnDimens.space6)
                .background(c.surface, RoundedCornerShape(DonnDimens.radiusLg))
                .border(DonnDimens.borderHeavy, if (destructive) c.accentRed else c.borderStrong, RoundedCornerShape(DonnDimens.radiusLg))
                .padding(DonnDimens.space6),
            verticalArrangement = Arrangement.spacedBy(DonnDimens.space4),
        ) {
            DonnText(title, style = DonnType.display)
            DonnText(body, style = DonnType.body, color = c.textSecondary)
            content?.invoke()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(DonnDimens.space3)) {
                DonnButton(
                    "Cancel",
                    onClick = onDismiss,
                    variant = ButtonVariant.SECONDARY,
                    size = ButtonSize.MD,
                    modifier = Modifier.weight(1f),
                )
                DonnButton(
                    confirmText,
                    onClick = onConfirm,
                    variant = if (destructive) ButtonVariant.DANGER else ButtonVariant.PRIMARY,
                    size = ButtonSize.MD,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
