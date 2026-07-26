package ph.chefdonn.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import ph.chefdonn.domain.LineStatus

// tokens/colors.css — brand scales
object Brand {
    val Cream = Color(0xFFF4F1EA)
    val CreamDeep = Color(0xFFE8E2D3)
    val Green = Color(0xFF0F4D3A)
    val GreenDeep = Color(0xFF0A3427)
    val GreenBright = Color(0xFF1E8F5E)
    val Red = Color(0xFFC8102E)
    val RedDeep = Color(0xFF8F0B21)
    val RedBright = Color(0xFFE6394F)
    val Gold = Color(0xFFD9A441)
    val GoldDeep = Color(0xFFB3822B)
    val GoldBright = Color(0xFFF0BD5E)
    val Black = Color(0xFF1A1A1A)
    val White = Color(0xFFFFFFFF)

    val Ink70 = Color(0xFF3C3C3A)
    val Ink50 = Color(0xFF6B6963)
    val Ink30 = Color(0xFFA19D92)
    val Ink15 = Color(0xFFD8D3C4)
    val Ink05 = Color(0xFFEFEBE0)

    val Night95 = Color(0xFF0C0F0D)
    val Night85 = Color(0xFF14201B)
    val Night70 = Color(0xFF1E2E27)
    val Night50 = Color(0xFF31463C)
    val Night30 = Color(0xFF4C665A)
    val Night10 = Color(0xFF8FA89B)
    val NightText = Color(0xFFF3F1E8)

    val CookingDay = Color(0xFFE0762D)
    val CookingNight = Color(0xFFFF8A3D)
    val ReadyNight = Color(0xFF35C281)
}

/**
 * Day (cream — waiter/cashier) vs Night (KDS) is chosen by device role, never by
 * the system theme: a kitchen tablet is always dark, a waiter phone always light.
 */
data class DonnColorScheme(
    val night: Boolean,
    val bg: Color,
    val surface: Color,
    val surfaceSunken: Color,
    val border: Color,
    val borderStrong: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textInverse: Color,
    val accentGreen: Color,
    val accentRed: Color,
    val accentGold: Color,
    val scrim: Color = Color(0x8C141414),
)

val DayColors = DonnColorScheme(
    night = false,
    bg = Brand.Cream,
    surface = Brand.White,
    surfaceSunken = Brand.CreamDeep,
    border = Brand.Ink15,
    borderStrong = Brand.Black,
    textPrimary = Brand.Black,
    textSecondary = Brand.Ink50,
    textInverse = Brand.Cream,
    accentGreen = Brand.Green,
    accentRed = Brand.Red,
    accentGold = Brand.Gold,
)

val NightColors = DonnColorScheme(
    night = true,
    bg = Brand.Night95,
    surface = Brand.Night85,
    surfaceSunken = Brand.Night70,
    border = Brand.Night50,
    borderStrong = Brand.Night50,
    textPrimary = Brand.NightText,
    textSecondary = Brand.Night10,
    textInverse = Brand.Night95,
    accentGreen = Brand.GreenBright,
    accentRed = Brand.RedBright,
    accentGold = Brand.GoldBright,
)

data class StatusStyle(val bg: Color, val fg: Color, val glyph: String, val label: String)

/** Status is never color-alone: fixed glyph + color + label, day and night variants. */
fun statusStyle(status: LineStatus, night: Boolean): StatusStyle = when (status) {
    LineStatus.QUEUED -> if (night) StatusStyle(Brand.GoldBright, Brand.Night95, "●", "QUEUED")
    else StatusStyle(Brand.Gold, Brand.Black, "●", "QUEUED")
    LineStatus.COOKING -> if (night) StatusStyle(Brand.CookingNight, Brand.Night95, "▲", "COOKING")
    else StatusStyle(Brand.CookingDay, Brand.White, "▲", "COOKING")
    LineStatus.READY -> if (night) StatusStyle(Brand.ReadyNight, Brand.Night95, "✓", "READY")
    else StatusStyle(Brand.GreenBright, Brand.White, "✓", "READY")
    LineStatus.SERVED -> if (night) StatusStyle(Brand.Night30, Brand.NightText, "■", "SERVED")
    else StatusStyle(Brand.Ink30, Brand.White, "■", "SERVED")
    LineStatus.VOIDED -> if (night) StatusStyle(Brand.RedBright, Brand.Night95, "✕", "VOID")
    else StatusStyle(Brand.Red, Brand.White, "✕", "VOID")
}

object SyncColors {
    val Online = Brand.GreenBright
    val Queuing = Brand.Gold
    val Offline = Brand.Red
}

val LocalDonnColors = staticCompositionLocalOf { DayColors }

@Composable
fun DonnTheme(night: Boolean = false, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalDonnColors provides if (night) NightColors else DayColors) {
        content()
    }
}

/** Root surface: fills the screen with the scheme background. */
@Composable
fun DonnSurface(night: Boolean = false, content: @Composable () -> Unit) {
    DonnTheme(night) {
        androidx.compose.foundation.layout.Box(
            Modifier
                .fillMaxSize()
                .background(LocalDonnColors.current.bg)
        ) { content() }
    }
}
