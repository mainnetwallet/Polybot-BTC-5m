package com.mainnetwallet.polybot.ui.main

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mainnetwallet.polybot.data.net.FeedState
import com.mainnetwallet.polybot.ui.components.Dot
import com.mainnetwallet.polybot.ui.components.Pill
import com.mainnetwallet.polybot.ui.theme.Poly

/** Top-bar pill: is the live WebSocket feed running? */
@Composable
fun ConnectionPill(state: FeedState, modifier: Modifier = Modifier) {
    val c = Poly.colors
    val label: String
    val color: Color
    when (state) {
        FeedState.Live -> {
            label = "Live feed"
            color = c.up
        }
        FeedState.Connecting, FeedState.Reconnecting -> {
            label = "Reconnecting"
            color = c.warn
        }
        FeedState.AuthFailed -> {
            label = "Token rejected"
            color = c.down
        }
        FeedState.Offline, FeedState.Idle -> {
            label = "Offline"
            color = c.down
        }
    }
    Surface(
        modifier = modifier.semantics { contentDescription = "Connection status: $label" },
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, color.copy(alpha = 0.45f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Dot(color)
            Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

/** PAPER (calm) or LIVE (solid red, impossible to miss). */
@Composable
fun ModeBadge(live: Boolean, modifier: Modifier = Modifier) {
    val c = Poly.colors
    if (live) {
        Pill("LIVE", Color.White, c.live, modifier)
    } else {
        Pill("PAPER", MaterialTheme.colorScheme.onPrimaryContainer, MaterialTheme.colorScheme.primaryContainer, modifier)
    }
}
