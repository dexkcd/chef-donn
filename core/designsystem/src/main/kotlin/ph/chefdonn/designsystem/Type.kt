@file:OptIn(ExperimentalTextApi::class)

package ph.chefdonn.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

// tokens/fonts.css + typography.css.
// Archivo ships as a variable font; wght is pinned per weight via FontVariation.

private val archivoVariable = R.font.archivo_variable

val Archivo = FontFamily(
    Font(archivoVariable, weight = FontWeight.ExtraBold, variationSettings = FontVariation.Settings(FontVariation.weight(800))),
    Font(archivoVariable, weight = FontWeight.Black, variationSettings = FontVariation.Settings(FontVariation.weight(900))),
)

val Barlow = FontFamily(
    Font(R.font.barlow_regular, weight = FontWeight.Normal),
    Font(R.font.barlow_semibold, weight = FontWeight.SemiBold),
    Font(R.font.barlow_bold, weight = FontWeight.Bold),
)

val BarlowCondensed = FontFamily(
    Font(R.font.barlowcondensed_semibold, weight = FontWeight.SemiBold),
    Font(R.font.barlowcondensed_bold, weight = FontWeight.Bold),
)

val PlexMono = FontFamily(
    Font(R.font.ibmplexmono_regular, weight = FontWeight.Normal),
    Font(R.font.ibmplexmono_semibold, weight = FontWeight.SemiBold),
    Font(R.font.ibmplexmono_bold, weight = FontWeight.Bold),
)

object DonnType {
    /** Archivo Black — headlines, table numbers, the wordmark. */
    val display = TextStyle(fontFamily = Archivo, fontWeight = FontWeight.Black, fontSize = 28.sp, lineHeight = 1.05.em, letterSpacing = (-0.01).em)
    val displayLg = display.copy(fontSize = 36.sp)
    val displayXl = display.copy(fontSize = 48.sp)

    /** Archivo 800 — every button label. */
    val button = TextStyle(fontFamily = Archivo, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, letterSpacing = (-0.01).em)
    val buttonSm = button.copy(fontSize = 14.sp)

    /** Barlow — body/UI copy. */
    val body = TextStyle(fontFamily = Barlow, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 1.4.em)
    val bodyMd = body.copy(fontSize = 18.sp)
    val bodySemibold = body.copy(fontWeight = FontWeight.SemiBold)
    val bodyBold = body.copy(fontWeight = FontWeight.Bold)
    val caption = body.copy(fontSize = 14.sp)

    /** Barlow Condensed, uppercase at call sites — tabs, station names, status words. */
    val label = TextStyle(fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold, fontSize = 14.sp, letterSpacing = 0.04.em)
    val labelLg = label.copy(fontSize = 18.sp)

    /** IBM Plex Mono — anything numeric that must not misread: timers, totals, qty. */
    val mono = TextStyle(fontFamily = PlexMono, fontWeight = FontWeight.Normal, fontSize = 16.sp)
    val monoBold = mono.copy(fontWeight = FontWeight.Bold)
    val monoLg = mono.copy(fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
}

object DonnDimens {
    val space1 = 4.dp
    val space2 = 8.dp
    val space3 = 12.dp
    val space4 = 16.dp
    val space5 = 20.dp
    val space6 = 24.dp
    val space8 = 32.dp
    val space10 = 40.dp
    val space12 = 48.dp

    val tapTargetMin = 56.dp
    val tapTargetComfortable = 72.dp

    val borderThin = 1.5.dp
    val borderThick = 3.dp
    val borderHeavy = 5.dp

    val radiusSm = 4.dp
    val radiusMd = 8.dp
    val radiusLg = 14.dp
}

val donnColors: DonnColorScheme
    @Composable
    @ReadOnlyComposable
    get() = LocalDonnColors.current
