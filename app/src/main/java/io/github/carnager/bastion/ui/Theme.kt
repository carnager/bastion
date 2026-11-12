package io.github.carnager.bastion.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** Same design language as sesam: accent #2f5bd3 on graphite neutrals. */
object Palette {
    val Accent = Color(0xFF2F5BD3)
    val AccentDark = Color(0xFF1D43AA)
    val Good = Color(0xFF1F9D6B)
    val Warn = Color(0xFFC4660F)
    val Amber = Color(0xFFE0861A)
    val Bad = Color(0xFFC0392B)

    /** Host tiles: deterministic per host, all readable with white text. */
    val tiles = listOf(
        0xFF2F5BD3, 0xFF1F9D6B, 0xFFC4660F, 0xFF6D4AFF, 0xFFD50C2D, 0xFF0F62CC, 0xFF24292F,
        0xFF0E7C86, 0xFF9C2F8E, 0xFF7A5C3E, 0xFF3B414C, 0xFF2185D0, 0xFFB8326B, 0xFF4E7A1E,
    ).map { Color(it) }
}

/** Design tokens Material 3 has no slot for. */
@Immutable
data class Extra(
    val dark: Boolean,
    val bg: Color, val card: Color, val field: Color, val chip: Color, val sheet: Color,
    val line: Color, val fg: Color, val fg2: Color, val muted: Color,
    val sel: Color, val selText: Color, val red: Color, val link: Color,
    val warnBg: Color, val warnLine: Color, val warnFg: Color,
    val accent: Color, val toast: Color,
)

private val LightExtra = Extra(
    dark = false,
    bg = Color(0xFFF6F7F9), card = Color.White, field = Color.White, chip = Color(0xFFEEF1F6), sheet = Color.White,
    line = Color(0xFFE3E6EB), fg = Color(0xFF16181D), fg2 = Color(0xFF3B414C), muted = Color(0xFF636B79),
    sel = Color(0xFFE4EBFB), selText = Color(0xFF1D43AA), red = Color(0xFFC22A2A), link = Color(0xFF2F5BD3),
    warnBg = Color(0xFFFFF7EC), warnLine = Color(0xFFF3D8B0), warnFg = Color(0xFF6B3A07),
    accent = Palette.Accent, toast = Color(0xFF16181D),
)
private val DarkExtra = Extra(
    dark = true,
    bg = Color(0xFF16181D), card = Color(0xFF21252C), field = Color(0xFF262A32), chip = Color(0xFF2B3039), sheet = Color(0xFF1B1E24),
    line = Color(0xFF2E333C), fg = Color(0xFFEEF0F3), fg2 = Color(0xFFC9CED6), muted = Color(0xFF949CAB),
    sel = Color(0xFF26324F), selText = Color(0xFFA9C1FF), red = Color(0xFFFF7B72), link = Color(0xFF8FB0FF),
    warnBg = Color(0xFF33261A), warnLine = Color(0xFF5A3F1E), warnFg = Color(0xFFF3C98B),
    accent = Palette.Accent, toast = Color(0xFF2B3039),
)

val LocalExtra = staticCompositionLocalOf { LightExtra }
val extra: Extra @Composable get() = LocalExtra.current

private val LightScheme = lightColorScheme(
    primary = Palette.Accent, onPrimary = Color.White,
    primaryContainer = Color(0xFFE4EBFB), onPrimaryContainer = Palette.AccentDark,
    secondary = Color(0xFF3B414C), onSecondary = Color.White,
    background = LightExtra.bg, onBackground = LightExtra.fg,
    surface = LightExtra.bg, onSurface = LightExtra.fg,
    surfaceVariant = LightExtra.chip, onSurfaceVariant = LightExtra.muted,
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color.White,
    surfaceContainer = Color.White, surfaceContainerHigh = Color.White, surfaceContainerHighest = LightExtra.chip,
    outline = Color(0xFFD5D9E0), outlineVariant = LightExtra.line,
    error = Color(0xFFC22A2A), errorContainer = Color(0xFFFDE3DF), onErrorContainer = Color(0xFF9B2C1F),
)
private val DarkScheme = darkColorScheme(
    primary = Color(0xFF8FB0FF), onPrimary = Color(0xFF0F2A6B),
    primaryContainer = DarkExtra.sel, onPrimaryContainer = DarkExtra.selText,
    secondary = DarkExtra.fg2, onSecondary = DarkExtra.bg,
    background = DarkExtra.bg, onBackground = DarkExtra.fg,
    surface = DarkExtra.bg, onSurface = DarkExtra.fg,
    surfaceVariant = DarkExtra.field, onSurfaceVariant = DarkExtra.muted,
    surfaceContainerLowest = DarkExtra.sheet, surfaceContainerLow = DarkExtra.sheet,
    surfaceContainer = DarkExtra.card, surfaceContainerHigh = DarkExtra.card, surfaceContainerHighest = DarkExtra.chip,
    outline = Color(0xFF3A404A), outlineVariant = DarkExtra.line,
    error = Color(0xFFFF7B72), errorContainer = Color(0xFF3A1E1B), onErrorContainer = Color(0xFFF2A99F),
)

private val AppTypography = Typography().let { t ->
    t.copy(
        titleLarge = t.titleLarge.copy(fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
        titleMedium = t.titleMedium.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.sp),
        titleSmall = t.titleSmall.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
        bodyLarge = t.bodyLarge.copy(fontSize = 15.sp, letterSpacing = 0.sp, lineHeight = 1.3.em),
        bodyMedium = t.bodyMedium.copy(fontSize = 14.sp, letterSpacing = 0.sp, lineHeight = 1.3.em),
        bodySmall = t.bodySmall.copy(fontSize = 13.sp, letterSpacing = 0.sp, lineHeight = 1.3.em),
        labelLarge = t.labelLarge.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp, lineHeight = 1.3.em),
        labelMedium = t.labelMedium.copy(fontSize = 12.sp, letterSpacing = 0.sp, lineHeight = 1.3.em),
    )
}

val MonoStyle = TextStyle(fontFamily = FontFamily.Monospace, letterSpacing = 0.3.sp)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp), small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp), extraLarge = RoundedCornerShape(26.dp),
)

/** The terminal is always dark, whatever the system theme. */
object TermColors {
    val bg = Color(0xFF111317)
    val bar = Color(0xFF1B1E24)
    val line = Color(0xFF2E333C)
    val fg = Color(0xFFE6E8EC)
    val key = Color(0xFFC9CED6)
    val keyBg = Color(0xFF262A32)
    val accent = Color(0xFF8FB0FF)
}

@Composable
fun BastionTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    CompositionLocalProvider(LocalExtra provides if (dark) DarkExtra else LightExtra) {
        MaterialTheme(
            colorScheme = if (dark) DarkScheme else LightScheme,
            typography = AppTypography,
            shapes = AppShapes,
        ) {
            // Gives every Text a readable default color (fg on bg), as sesam's root Surface does.
            Surface(color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground, content = content)
        }
    }
}
