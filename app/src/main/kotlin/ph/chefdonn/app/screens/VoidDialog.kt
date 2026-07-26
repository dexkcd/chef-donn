package ph.chefdonn.app.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import ph.chefdonn.designsystem.ButtonSize
import ph.chefdonn.designsystem.ButtonVariant
import ph.chefdonn.designsystem.DonnButton
import ph.chefdonn.designsystem.DonnDialog
import ph.chefdonn.designsystem.DonnDimens
import ph.chefdonn.designsystem.DonnTextField
import ph.chefdonn.designsystem.DonnType
import ph.chefdonn.domain.OrderLine

/**
 * The canonical destructive confirm. Copy is factual, not apologetic — it states
 * the consequence. A reason is required; quick-picks cover the common cases.
 */
@Composable
fun VoidDialog(
    line: OrderLine,
    quickReasons: List<String>,
    onConfirm: (reason: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var reason by remember { mutableStateOf("") }
    DonnDialog(
        title = "Void ${line.spec.qty}× ${line.spec.name}",
        body = "This removes the item from all three apps immediately. " +
            "The kitchen stops making it and it drops off the bill.",
        confirmText = "Void Item",
        destructive = true,
        onConfirm = { if (reason.isNotBlank()) onConfirm(reason.trim()) },
        onDismiss = onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(DonnDimens.space3)) {
            Row(horizontalArrangement = Arrangement.spacedBy(DonnDimens.space2)) {
                quickReasons.forEach { r ->
                    DonnButton(
                        r,
                        size = ButtonSize.SM,
                        variant = if (reason == r) ButtonVariant.PRIMARY else ButtonVariant.SECONDARY,
                        onClick = { reason = r },
                    )
                }
            }
            DonnTextField(
                reason,
                { reason = it },
                placeholder = "Reason (required)",
                modifier = Modifier.fillMaxWidth(),
                textStyle = DonnType.body,
            )
        }
    }
}
