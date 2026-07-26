package ph.chefdonn.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import ph.chefdonn.domain.OrderLine

/**
 * components/data/Timer — mm:ss since [startedAt], ticking. Goes red past
 * [warnAfterMs]: legible from a meter away, IBM Plex Mono only.
 */
@Composable
fun TicketTimer(startedAt: Long, modifier: Modifier = Modifier, warnAfterMs: Long = 10 * 60_000L) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startedAt) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val elapsed = (now - startedAt).coerceAtLeast(0)
    val overdue = elapsed >= warnAfterMs
    val mm = (elapsed / 60_000).toString().padStart(2, '0')
    val ss = ((elapsed / 1000) % 60).toString().padStart(2, '0')
    DonnText(
        "$mm:$ss",
        modifier = modifier,
        style = DonnType.monoLg,
        color = if (overdue) donnColors.accentRed else donnColors.textPrimary,
    )
}

/**
 * components/data/TicketCard — the one shared order unit. Same card on all three
 * apps; gold border = priority, line rows show qty × name + status badge.
 */
@Composable
fun TicketCard(
    tableLabel: String,
    lines: List<OrderLine>,
    modifier: Modifier = Modifier,
    startedAt: Long? = null,
    priority: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    lineRow: @Composable (OrderLine) -> Unit = { DefaultTicketLine(it) },
) {
    val c = donnColors
    Column(
        modifier
            .background(c.surface, RoundedCornerShape(DonnDimens.radiusLg))
            .border(
                DonnDimens.borderThick,
                if (priority) c.accentGold else c.borderStrong,
                RoundedCornerShape(DonnDimens.radiusLg),
            )
            .padding(DonnDimens.space5),
        verticalArrangement = Arrangement.spacedBy(DonnDimens.space2),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DonnDimens.space3)) {
                DonnText("Table $tableLabel", style = DonnType.display)
                if (priority) PriorityTag("Rush")
            }
            if (startedAt != null) TicketTimer(startedAt) else trailing?.invoke()
        }
        lines.forEachIndexed { i, line ->
            Column {
                lineRow(line)
                if (i < lines.lastIndex) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = DonnDimens.space2)
                            .height(1.dp)
                            .background(c.border)
                    )
                }
            }
        }
    }
}

@Composable
fun DefaultTicketLine(line: OrderLine) {
    val voided = line.isVoided
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            DonnText(
                "${line.spec.qty}× ${line.spec.name}",
                style = DonnType.bodyMd,
                textDecoration = if (voided) TextDecoration.LineThrough else null,
                color = if (voided) donnColors.textSecondary else donnColors.textPrimary,
            )
            line.spec.modifiers.forEach { m ->
                DonnText("  • $m", style = DonnType.caption, color = donnColors.textSecondary)
            }
            if (line.spec.note.isNotBlank()) {
                DonnText("  “${line.spec.note}”", style = DonnType.caption, color = donnColors.textSecondary)
            }
        }
        StatusBadge(line.status)
    }
}
