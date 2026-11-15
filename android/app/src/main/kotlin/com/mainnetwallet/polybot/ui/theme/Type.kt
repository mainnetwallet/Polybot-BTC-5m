package com.mainnetwallet.polybot.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Base = Typography()

/**
 * Material 3 scale with a clearer hierarchy: titles are semi-bold, body text has a little more line height so
 * long hints stay readable, and nothing is smaller than 12sp (accessibility bar).
 */
val PolyTypography = Typography(
    headlineMedium = Base.headlineMedium.copy(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = Base.titleLarge.copy(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = Base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = Base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = Base.bodyLarge.copy(lineHeight = 22.sp),
    bodyMedium = Base.bodyMedium.copy(lineHeight = 20.sp),
    bodySmall = Base.bodySmall.copy(fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = Base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    labelMedium = Base.labelMedium.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium),
    labelSmall = Base.labelSmall.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium),
)

/** Monospace + tabular digits: prices and PnL keep a fixed width while they tick. */
object Mono {
    val body = TextStyle(fontFamily = FontFamily.Monospace, fontFeatureSettings = "tnum", fontSize = 14.sp)
    val small = TextStyle(fontFamily = FontFamily.Monospace, fontFeatureSettings = "tnum", fontSize = 12.sp)
    val medium = TextStyle(
        fontFamily = FontFamily.Monospace, fontFeatureSettings = "tnum", fontSize = 16.sp, fontWeight = FontWeight.Medium,
    )
    val large = TextStyle(
        fontFamily = FontFamily.Monospace, fontFeatureSettings = "tnum", fontSize = 28.sp, fontWeight = FontWeight.SemiBold,
    )
    val xlarge = TextStyle(
        fontFamily = FontFamily.Monospace, fontFeatureSettings = "tnum", fontSize = 36.sp, fontWeight = FontWeight.Bold,
    )
}
