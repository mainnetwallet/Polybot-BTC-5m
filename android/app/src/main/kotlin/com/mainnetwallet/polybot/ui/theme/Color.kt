package com.mainnetwallet.polybot.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Trading semantics that Material 3 has no role for: UP/profit, DOWN/loss, warning, LIVE. */
@Immutable
class PolyColors(
    val up: Color,
    val upContainer: Color,
    val down: Color,
    val downContainer: Color,
    val warn: Color,
    val warnContainer: Color,
    val live: Color,
    val liveContainer: Color,
    val info: Color,
)

val DarkPolyColors = PolyColors(
    up = Color(0xFF34D17A),
    upContainer = Color(0xFF0F2A1E),
    down = Color(0xFFFF6B6B),
    downContainer = Color(0xFF331519),
    warn = Color(0xFFFFB84D),
    warnContainer = Color(0xFF33260D),
    live = Color(0xFFFF4D4D),
    liveContainer = Color(0xFF3A1216),
    info = Color(0xFF5AB0FF),
)

val LightPolyColors = PolyColors(
    up = Color(0xFF138A4B),
    upContainer = Color(0xFFD9F5E5),
    down = Color(0xFFC62E2E),
    downContainer = Color(0xFFFCE1E1),
    warn = Color(0xFF9A5B00),
    warnContainer = Color(0xFFFFEFCF),
    live = Color(0xFFC62828),
    liveContainer = Color(0xFFFFE0E0),
    info = Color(0xFF0B63B5),
)

val LocalPolyColors = staticCompositionLocalOf { DarkPolyColors }

val DarkScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFF5AB0FF),
    onPrimary = Color(0xFF00213D),
    primaryContainer = Color(0xFF123556),
    onPrimaryContainer = Color(0xFFD3E8FF),
    secondary = Color(0xFF9DB4D3),
    onSecondary = Color(0xFF14233A),
    secondaryContainer = Color(0xFF1C2D47),
    onSecondaryContainer = Color(0xFFD6E3F7),
    tertiary = Color(0xFFFFB84D),
    onTertiary = Color(0xFF3A2400),
    background = Color(0xFF0B1220),
    onBackground = Color(0xFFE6EBF5),
    surface = Color(0xFF0B1220),
    onSurface = Color(0xFFE6EBF5),
    surfaceVariant = Color(0xFF1A2438),
    onSurfaceVariant = Color(0xFF9AA8C0),
    surfaceContainerLowest = Color(0xFF080D18),
    surfaceContainerLow = Color(0xFF0F1727),
    surfaceContainer = Color(0xFF131C2F),
    surfaceContainerHigh = Color(0xFF18233A),
    surfaceContainerHighest = Color(0xFF1E2B45),
    outline = Color(0xFF58657E),
    outlineVariant = Color(0xFF28344D),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF3B0A0A),
    errorContainer = Color(0xFF4A1519),
    onErrorContainer = Color(0xFFFFD9D9),
)

val LightScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF0B63B5),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD3E8FF),
    onPrimaryContainer = Color(0xFF00305A),
    secondary = Color(0xFF4A5F7D),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDCE7F7),
    onSecondaryContainer = Color(0xFF0E2038),
    tertiary = Color(0xFF9A5B00),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFF5F7FB),
    onBackground = Color(0xFF0E1626),
    surface = Color(0xFFF5F7FB),
    onSurface = Color(0xFF0E1626),
    surfaceVariant = Color(0xFFE3E9F4),
    onSurfaceVariant = Color(0xFF4A566C),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF0F3F9),
    surfaceContainer = Color(0xFFEBEFF7),
    surfaceContainerHigh = Color(0xFFE5EAF4),
    surfaceContainerHighest = Color(0xFFDFE5F1),
    outline = Color(0xFF71809A),
    outlineVariant = Color(0xFFC9D2E3),
    error = Color(0xFFC62E2E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFCE1E1),
    onErrorContainer = Color(0xFF410002),
)
