package com.shinobisondre.stockanalyzer.ui.theme

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.shinobisondre.stockanalyzer.engine.Tone

object Palette {
    val bg = Color(0xFF0E1117)
    val surface = Color(0xFF161B22)
    val surface2 = Color(0xFF1F2630)
    val outline = Color(0xFF2D3540)
    val text = Color(0xFFE6EDF3)
    val muted = Color(0xFF8B949E)
    val accent = Color(0xFF00D4AA)
    val strongBuy = Color(0xFF10B981)
    val buy = Color(0xFF22C55E)
    val neutral = Color(0xFF9CA3AF)
    val sell = Color(0xFFF97316)
    val strongSell = Color(0xFFEF4444)
    val up = Color(0xFF22C55E)
    val down = Color(0xFFEF4444)
    val warn = Color(0xFFF59E0B)
    val sma20 = Color(0xFFFBBF24)
    val sma50 = Color(0xFF60A5FA)
    val sma200 = Color(0xFFE879F9)
    val band = Color(0x5500D4AA)
}

fun toneColor(t: Tone): Color = when (t) {
    Tone.STRONG_BUY -> Palette.strongBuy
    Tone.BUY -> Palette.buy
    Tone.NEUTRAL -> Palette.neutral
    Tone.SELL -> Palette.sell
    Tone.STRONG_SELL -> Palette.strongSell
}

fun changeColor(v: Double?): Color = when {
    v == null -> Palette.muted
    v > 0 -> Palette.up
    v < 0 -> Palette.down
    else -> Palette.muted
}

private val scheme = darkColorScheme(
    primary = Palette.accent,
    onPrimary = Color.Black,
    secondary = Palette.sma50,
    background = Palette.bg,
    onBackground = Palette.text,
    surface = Palette.surface,
    onSurface = Palette.text,
    surfaceVariant = Palette.surface2,
    onSurfaceVariant = Palette.muted,
    surfaceContainer = Palette.surface,
    surfaceContainerHigh = Palette.surface2,
    surfaceContainerHighest = Palette.surface2,
    surfaceContainerLow = Palette.surface,
    outline = Palette.outline,
    outlineVariant = Palette.outline,
    error = Palette.strongSell,
    secondaryContainer = Color(0xFF0F3D35),
    onSecondaryContainer = Palette.accent,
)

private val base = Typography()
val Mono = FontFamily.Monospace
private val typography = base.copy(
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    labelSmall = base.labelSmall.copy(fontSize = 11.sp),
)

val Num = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold)

@Composable
fun StockTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography) {
        // Every Text outside a Card/Scaffold falls back to LocalContentColor, which is
        // black by default — on the unfolded two-pane layout that made headings,
        // symbols and prices dark-on-dark. A root Surface sets it to the light text colour.
        Surface(color = Palette.bg, contentColor = Palette.text) {
            CompositionLocalProvider(LocalContentColor provides Palette.text, content = content)
        }
    }
}
