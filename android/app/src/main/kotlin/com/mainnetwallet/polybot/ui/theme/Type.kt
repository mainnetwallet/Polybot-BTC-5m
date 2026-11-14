package com.mainnetwallet.polybot.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val PolyTypography = Typography()

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
