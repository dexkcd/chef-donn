package ph.chefdonn.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ph.chefdonn.domain.LineStatus

enum class ButtonVariant { PRIMARY, DANGER, SECONDARY, GHOST }
enum class ButtonSize { LG, MD, SM }

/**
 * components/core/Button — Archivo 800, thick border, press = mechanical 0.97 snap.
 * LG is the 72dp wet-finger default; never go below SM (40dp, admin screens only).
 */
@Composable
fun DonnButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: ButtonVariant = ButtonVariant.PRIMARY,
    size: ButtonSize = ButtonSize.LG,
    enabled: Boolean = true,
) {
    val c = donnColors
    val (bg, fg, borderColor) = when (variant) {
        ButtonVariant.PRIMARY -> Triple(c.accentGreen, if (c.night) Brand.Night95 else Brand.Cream, c.accentGreen)
        ButtonVariant.DANGER -> Triple(c.accentRed, if (c.night) Brand.Night95 else Brand.White, c.accentRed)
        ButtonVariant.SECONDARY -> Triple(Color.Transparent, c.textPrimary, c.borderStrong)
        ButtonVariant.GHOST -> Triple(Color.Transparent, c.textPrimary, Color.Transparent)
    }
    val (minHeight, padH, textStyle) = when (size) {
        ButtonSize.LG -> Triple(DonnDimens.tapTargetComfortable, DonnDimens.space6, DonnType.button)
        ButtonSize.MD -> Triple(DonnDimens.tapTargetMin, DonnDimens.space5, DonnType.button.copy(fontSize = 16.sp))
        ButtonSize.SM -> Triple(40.dp, DonnDimens.space4, DonnType.buttonSm)
    }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier
            .graphicsLayer {
                val s = if (pressed && enabled) 0.97f else 1f
                scaleX = s; scaleY = s
                alpha = if (enabled) 1f else 0.4f
            }
            .defaultMinSize(minHeight = minHeight)
            .background(bg, RoundedCornerShape(DonnDimens.radiusMd))
            .border(DonnDimens.borderThick, borderColor, RoundedCornerShape(DonnDimens.radiusMd))
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = padH),
        contentAlignment = Alignment.Center,
    ) {
        DonnText(text, style = textStyle, color = fg)
    }
}

/** Thin wrapper fixing color defaults to the scheme. */
@Composable
fun DonnText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = DonnType.body,
    color: Color = Color.Unspecified,
    textAlign: TextAlign? = null,
    textDecoration: TextDecoration? = null,
    maxLines: Int = Int.MAX_VALUE,
) {
    androidx.compose.foundation.text.BasicText(
        text = text,
        modifier = modifier,
        style = style.copy(
            color = if (color == Color.Unspecified) donnColors.textPrimary else color,
            textAlign = textAlign ?: style.textAlign,
            textDecoration = textDecoration ?: style.textDecoration,
        ),
        maxLines = maxLines,
    )
}

/** components/core/Badge — status pill: fixed glyph + color + UPPERCASE label. */
@Composable
fun StatusBadge(status: LineStatus, modifier: Modifier = Modifier, large: Boolean = false) {
    val night = donnColors.night
    val s = statusStyle(status, night)
    Row(
        modifier
            .background(s.bg, RoundedCornerShape(999.dp))
            .padding(horizontal = if (large) 18.dp else 12.dp, vertical = if (large) 10.dp else 6.dp),
        horizontalArrangement = Arrangement.spacedBy(DonnDimens.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DonnText(s.glyph, style = if (large) DonnType.labelLg else DonnType.label, color = s.fg)
        DonnText(s.label, style = if (large) DonnType.labelLg else DonnType.label, color = s.fg)
    }
}

/** components/core/Tag — priority/course label (gold pill = RUSH). */
@Composable
fun PriorityTag(text: String, modifier: Modifier = Modifier) {
    val c = donnColors
    Box(
        modifier
            .background(c.accentGold, RoundedCornerShape(999.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        DonnText(text.uppercase(), style = DonnType.label, color = if (c.night) Brand.Night95 else Brand.Black)
    }
}

/** components/forms/Stepper — 56dp +/− targets, mono readout. */
@Composable
fun QtyStepper(qty: Int, onQtyChange: (Int) -> Unit, modifier: Modifier = Modifier, min: Int = 0, max: Int = 99) {
    val c = donnColors
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DonnDimens.space3)) {
        StepperKey("−", enabled = qty > min) { onQtyChange(qty - 1) }
        DonnText(
            qty.toString().padStart(2, '0'),
            style = DonnType.monoLg,
            modifier = Modifier.defaultMinSize(minWidth = 44.dp),
            textAlign = TextAlign.Center,
        )
        StepperKey("+", enabled = qty < max) { onQtyChange(qty + 1) }
    }
    // c intentionally unused beyond theme read; keeps recomposition scoped
}

@Composable
private fun StepperKey(glyph: String, enabled: Boolean, onClick: () -> Unit) {
    val c = donnColors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        Modifier
            .graphicsLayer {
                val s = if (pressed && enabled) 0.97f else 1f
                scaleX = s; scaleY = s
                alpha = if (enabled) 1f else 0.4f
            }
            .size(DonnDimens.tapTargetMin)
            .background(c.surface, RoundedCornerShape(DonnDimens.radiusMd))
            .border(DonnDimens.borderThick, c.borderStrong, RoundedCornerShape(DonnDimens.radiusMd))
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        DonnText(glyph, style = DonnType.display.copy(fontSize = 24.sp))
    }
}

/** components/navigation/Tabs — Barlow Condensed uppercase, heavy underline on active. */
@Composable
fun DonnTabs(
    tabs: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    badges: Map<Int, Int> = emptyMap(),
) {
    val c = donnColors
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(DonnDimens.space2)) {
        tabs.forEachIndexed { i, tab ->
            val active = i == selected
            Column(
                Modifier
                    .defaultMinSize(minHeight = DonnDimens.tapTargetMin)
                    .clickable { onSelect(i) }
                    .padding(horizontal = DonnDimens.space4, vertical = DonnDimens.space2),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DonnDimens.space2)) {
                    DonnText(
                        tab.uppercase(),
                        style = DonnType.labelLg,
                        color = if (active) c.textPrimary else c.textSecondary,
                    )
                    badges[i]?.takeIf { it > 0 }?.let { n ->
                        Box(
                            Modifier.background(c.accentGold, CircleShape).padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            DonnText(n.toString(), style = DonnType.label, color = if (c.night) Brand.Night95 else Brand.Black)
                        }
                    }
                }
                Box(
                    Modifier
                        .padding(top = DonnDimens.space1)
                        .size(width = 40.dp, height = 4.dp)
                        .background(if (active) c.accentGreen else Color.Transparent)
                )
            }
        }
    }
}

/** components/navigation/TableChip — floor-plan table selector. */
@Composable
fun TableChip(
    label: String,
    modifier: Modifier = Modifier,
    occupied: Boolean = false,
    selected: Boolean = false,
    onClick: () -> Unit = {},
) {
    val c = donnColors
    val bg = when {
        selected -> c.accentGreen
        occupied -> c.surfaceSunken
        else -> c.surface
    }
    val fg = if (selected) (if (c.night) Brand.Night95 else Brand.Cream) else c.textPrimary
    Box(
        modifier
            .size(DonnDimens.tapTargetComfortable)
            .background(bg, RoundedCornerShape(DonnDimens.radiusMd))
            .border(DonnDimens.borderThick, if (selected) c.accentGreen else c.borderStrong, RoundedCornerShape(DonnDimens.radiusMd))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        DonnText(label, style = DonnType.display.copy(fontSize = 22.sp), color = fg)
    }
}

/** components/forms/Input — bordered field, Barlow, no floating labels. */
@Composable
fun DonnTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = true,
    textStyle: TextStyle = DonnType.body,
) {
    val c = donnColors
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .defaultMinSize(minHeight = DonnDimens.tapTargetMin)
            .background(c.surface, RoundedCornerShape(DonnDimens.radiusMd))
            .border(DonnDimens.borderThick, c.borderStrong, RoundedCornerShape(DonnDimens.radiusMd))
            .padding(horizontal = DonnDimens.space4, vertical = DonnDimens.space3),
        textStyle = textStyle.copy(color = c.textPrimary),
        cursorBrush = SolidColor(c.accentGreen),
        singleLine = singleLine,
        decorationBox = { inner ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) DonnText(placeholder, style = textStyle, color = c.textSecondary)
                inner()
            }
        },
    )
}
