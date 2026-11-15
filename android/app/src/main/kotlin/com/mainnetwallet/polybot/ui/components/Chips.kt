package com.mainnetwallet.polybot.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.mainnetwallet.polybot.domain.Tone
import com.mainnetwallet.polybot.ui.theme.Poly

/** A [Pill] coloured by a semantic [Tone] (status of an order, a trigger decision, a log level...). */
@Composable
fun ToneChip(text: String, tone: Tone, modifier: Modifier = Modifier) {
    val c = Poly.colors
    val scheme = MaterialTheme.colorScheme
    when (tone) {
        Tone.Up -> Pill(text, c.up, c.upContainer, modifier)
        Tone.Warn -> Pill(text, c.warn, c.warnContainer, modifier)
        Tone.Info -> Pill(text, scheme.onPrimaryContainer, scheme.primaryContainer, modifier)
        Tone.Down -> Pill(text, c.down, c.downContainer, modifier)
        Tone.Neutral -> Pill(text, scheme.onSurfaceVariant, scheme.surfaceVariant, modifier)
    }
}
