package com.mainnetwallet.polybot.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mainnetwallet.polybot.ui.theme.Mono
import com.mainnetwallet.polybot.ui.theme.Poly
import com.mainnetwallet.polybot.ui.theme.Space

// Building blocks of the "pro" look: hairline-bordered cards, an optional colour accent strip, a gradient hero,
// segmented tabs, round icon badges and PnL badges with an arrow. Used by the Trading tab.

private val HairlineAlpha = 0.55f

/** Bordered card. [accent] draws a 4dp strip down the left edge; [onClick] makes the whole card tappable. */
@Composable
fun ProCard(
    modifier: Modifier = Modifier,
    accent: Color? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = scheme.surfaceContainer,
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = HairlineAlpha)),
    ) {
        val accentStrip = if (accent != null) {
            Modifier.drawBehind { drawRect(color = accent, size = Size(4.dp.toPx(), size.height)) }
        } else {
            Modifier
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .then(accentStrip)
                .padding(
                    start = if (accent != null) Space.lg + 4.dp else Space.lg,
                    top = Space.lg,
                    end = Space.lg,
                    bottom = Space.lg,
                ),
            verticalArrangement = Arrangement.spacedBy(Space.md),
            content = content,
        )
    }
}

/** Card with a soft gradient from the primary container into the normal card colour. */
@Composable
fun ProHero(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = scheme.surfaceContainer,
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = HairlineAlpha)),
    ) {
        Box(
            Modifier.background(
                Brush.linearGradient(
                    listOf(
                        scheme.primaryContainer.copy(alpha = 0.55f),
                        scheme.surfaceContainer,
                    ),
                ),
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Space.lg),
                verticalArrangement = Arrangement.spacedBy(Space.md),
                content = content,
            )
        }
    }
}

/** Section title with an optional grey subtitle and a trailing slot. */
@Composable
fun ProSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(top = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing()
    }
}

/** Pill-shaped tab switcher. [counts] is optional; a count above zero shows as a small bubble. */
@Composable
fun SegmentedTabs(
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    counts: List<Int> = emptyList(),
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = scheme.surfaceContainerLow,
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = HairlineAlpha)),
    ) {
        Row(
            Modifier.padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            labels.forEachIndexed { index, label ->
                val on = index == selected
                val count = counts.getOrElse(index) { 0 }
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (on) scheme.primaryContainer else Color.Transparent)
                        .selectable(selected = on, onClick = { onSelect(index) }, role = Role.Tab)
                        .padding(vertical = 10.dp, horizontal = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = if (on) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (count > 0) {
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = if (on) scheme.primary else scheme.surfaceVariant,
                            ) {
                                Text(
                                    count.toString(),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (on) scheme.onPrimary else scheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Round icon on a tinted background. */
@Composable
fun IconBadge(
    icon: ImageVector,
    tint: Color,
    container: Color,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
) {
    Box(
        modifier
            .size(size)
            .background(container, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}

/** Green / red pill with an up / down arrow for a profit or loss. [text] is already formatted. */
@Composable
fun PnlBadge(text: String, value: Double?, modifier: Modifier = Modifier) {
    val c = Poly.colors
    val scheme = MaterialTheme.colorScheme
    val fg: Color
    val bg: Color
    when {
        value == null || value == 0.0 -> {
            fg = scheme.onSurfaceVariant
            bg = scheme.surfaceVariant
        }
        value > 0 -> {
            fg = c.up
            bg = c.upContainer
        }
        else -> {
            fg = c.down
            bg = c.downContainer
        }
    }
    Surface(modifier = modifier, shape = RoundedCornerShape(50), color = bg) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (value != null && value != 0.0) {
                Icon(
                    if (value > 0) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward,
                    contentDescription = null,
                    tint = fg,
                    modifier = Modifier.size(14.dp),
                )
            }
            Text(text, style = Mono.small.copy(fontWeight = FontWeight.SemiBold), color = fg, maxLines = 1)
        }
    }
}

/** Small caps label over a one-line mono value. Three of them fit in a row on a phone. */
@Composable
fun ProStat(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = Color.Unspecified,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Label(label)
        Text(
            value,
            style = Mono.body.copy(fontWeight = FontWeight.Medium),
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
