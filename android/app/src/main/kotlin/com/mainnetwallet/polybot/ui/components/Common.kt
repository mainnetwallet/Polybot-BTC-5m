package com.mainnetwallet.polybot.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mainnetwallet.polybot.ui.theme.Mono
import com.mainnetwallet.polybot.ui.theme.Poly
import com.mainnetwallet.polybot.ui.theme.Space
import kotlinx.coroutines.delay

/** The standard card: 16dp radius, 16dp padding, 8dp between children. */
@Composable
fun PolyCard(
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.surfaceContainer,
    borderColor: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = container,
        border = borderColor?.let { BorderStroke(1.dp, it) },
    ) {
        Column(
            modifier = Modifier.padding(Space.lg),
            verticalArrangement = Arrangement.spacedBy(Space.sm),
            content = content,
        )
    }
}

@Composable
fun Label(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.8.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        trailing()
    }
}

/** Small rounded status label: WIN, LOSS, OPEN, BUY, SELL, PAPER... */
@Composable
fun Pill(text: String, color: Color, container: Color, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(50), color = container) {
        Text(
            text = text,
            color = color,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
        )
    }
}

@Composable
fun SidePill(side: String, modifier: Modifier = Modifier) {
    val c = Poly.colors
    if (side.equals("up", ignoreCase = true)) {
        Pill("UP", c.up, c.upContainer, modifier)
    } else {
        Pill("DOWN", c.down, c.downContainer, modifier)
    }
}

@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = Color.Unspecified, hint: String? = null) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Label(label)
        Text(value, style = Mono.medium, color = valueColor)
        if (hint != null) {
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun pnlColor(value: Double?): Color {
    val c = Poly.colors
    return when {
        value == null || value == 0.0 -> MaterialTheme.colorScheme.onSurface
        value > 0 -> c.up
        else -> c.down
    }
}

@Composable
fun EmptyState(title: String, hint: String? = null, icon: ImageVector = Icons.Filled.Inbox, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = Space.xl, horizontal = Space.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(36.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
        if (hint != null) {
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Grey bar that pulses while data loads. */
@Composable
fun SkeletonBox(modifier: Modifier = Modifier, height: androidx.compose.ui.unit.Dp = 16.dp) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "skeleton-alpha",
    )
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .alpha(alpha)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)),
    )
}

@Composable
fun SkeletonCard(lines: Int = 3, modifier: Modifier = Modifier) {
    PolyCard(modifier) {
        SkeletonBox(height = 18.dp)
        repeat(lines) { SkeletonBox(height = 14.dp) }
    }
}

/** A value that flashes green/red for a moment when it goes up/down. */
@Composable
fun FlashBackground(value: Double?, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val c = Poly.colors
    var last by remember { mutableStateOf(value) }
    var direction by remember { mutableIntStateOf(0) }
    LaunchedEffect(value) {
        val previous = last
        last = value
        if (value != null && previous != null && value != previous) {
            direction = if (value > previous) 1 else -1
            delay(450)
            direction = 0
        }
    }
    val bg by animateColorAsState(
        targetValue = when (direction) {
            1 -> c.up.copy(alpha = 0.28f)
            -1 -> c.down.copy(alpha = 0.28f)
            else -> Color.Transparent
        },
        animationSpec = tween(300),
        label = "flash",
    )
    Box(modifier.background(bg, RoundedCornerShape(8.dp))) { content() }
}

@Composable
fun VSpace(h: androidx.compose.ui.unit.Dp = Space.sm) = Spacer(Modifier.height(h))

@Composable
fun KeyValueRow(key: String, value: String, modifier: Modifier = Modifier, valueColor: Color = Color.Unspecified) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(key, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = Mono.body, color = valueColor)
    }
}

@Composable
fun Dot(color: Color, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 8.dp) {
    Box(modifier.size(size).background(color, CircleShape))
}
