package com.mainnetwallet.polybot.ui.strategy

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mainnetwallet.polybot.ui.Fmt
import com.mainnetwallet.polybot.ui.components.Label
import com.mainnetwallet.polybot.ui.components.Pill
import com.mainnetwallet.polybot.ui.components.ProCard
import com.mainnetwallet.polybot.ui.components.ProStat
import com.mainnetwallet.polybot.ui.components.ToneChip
import com.mainnetwallet.polybot.domain.Tone
import com.mainnetwallet.polybot.ui.theme.Mono
import com.mainnetwallet.polybot.ui.theme.Poly
import com.mainnetwallet.polybot.ui.theme.Space

private const val HairlineAlpha = 0.55f

/** A dot that breathes, used next to "armed" and "active" states so they read as live. */
@Composable
internal fun PulseDot(color: Color, modifier: Modifier = Modifier, size: Dp = 8.dp) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val alpha by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "pulse-alpha",
    )
    Box(
        modifier
            .size(size)
            .alpha(alpha)
            .background(color, CircleShape),
    )
}

/** One lane of [StageTimeline]: stage [label] with the part of the market (time left, from to) it trades in. */
internal data class StageLane(
    val label: String,
    val fromRemaining: Double,
    val toRemaining: Double,
    val enabled: Boolean,
    val active: Boolean,
)

/**
 * Gantt-style overview: one lane per stage across the whole market, left = market start, right = market end.
 * Enabled stages are filled, a stage that is trading right now is amber and the vertical tick is "now".
 */
@Composable
internal fun StageTimeline(duration: Double, remaining: Double?, lanes: List<StageLane>, modifier: Modifier = Modifier) {
    val c = Poly.colors
    val scheme = MaterialTheme.colorScheme
    val enabledColor = scheme.primary
    val activeColor = c.warn
    val disabledColor = scheme.outline.copy(alpha = 0.35f)
    val track = scheme.surfaceVariant
    val nowColor = scheme.onSurface
    val labelWidth = 28.dp

    fun at(timeLeft: Double): Float = ((duration - timeLeft) / duration).toFloat().coerceIn(0f, 1f)

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        lanes.forEach { lane ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                Text(
                    lane.label,
                    style = Mono.small.copy(fontWeight = FontWeight.SemiBold),
                    color = if (lane.enabled) scheme.onSurface else scheme.onSurfaceVariant,
                    modifier = Modifier.width(labelWidth),
                    maxLines = 1,
                )
                Canvas(
                    Modifier
                        .weight(1f)
                        .height(16.dp),
                ) {
                    val h = size.height
                    val radius = CornerRadius(h / 2f, h / 2f)
                    drawRoundRect(color = track, size = Size(size.width, h), cornerRadius = radius)
                    val start = at(lane.fromRemaining)
                    val end = at(lane.toRemaining)
                    if (end > start) {
                        drawRoundRect(
                            color = when {
                                !lane.enabled -> disabledColor
                                lane.active -> activeColor
                                else -> enabledColor
                            },
                            topLeft = Offset(size.width * start, 0f),
                            size = Size(size.width * (end - start), h),
                            cornerRadius = radius,
                        )
                    }
                    if (remaining != null) {
                        val x = size.width * at(remaining)
                        drawLine(color = nowColor, start = Offset(x, 0f), end = Offset(x, h), strokeWidth = 2.dp.toPx())
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.width(labelWidth + Space.sm))
            Text(Fmt.clock(duration), style = Mono.small, color = scheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text("now", style = Mono.small, color = scheme.onSurface)
            Spacer(Modifier.weight(1f))
            Text(Fmt.clock(0.0), style = Mono.small, color = scheme.onSurfaceVariant)
        }
    }
}

/** Small legend that explains the lane colours. */
@Composable
internal fun TimelineLegend(modifier: Modifier = Modifier) {
    val c = Poly.colors
    val scheme = MaterialTheme.colorScheme
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(Space.lg), verticalAlignment = Alignment.CenterVertically) {
        LegendItem(scheme.primary, "On")
        LegendItem(c.warn, "Trading now")
        LegendItem(scheme.outline.copy(alpha = 0.35f), "Off")
    }
}

@Composable
private fun LegendItem(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier
                .size(10.dp)
                .background(color, CircleShape),
        )
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Key facts of a stage card. [entry] and [exit] are the full parameter lists shown when the card is opened. */
internal data class StageSpec(
    val index: Int,
    val title: String,
    val window: String,
    val enabled: Boolean,
    val active: Boolean,
    val highlights: List<Pair<String, String>>,
    val entry: List<Pair<String, String>>,
    val exit: List<Pair<String, String>>,
)

/**
 * Expandable stage card: numbered badge, name, window and ON/OFF/ACTIVE chip; three key numbers when closed,
 * every parameter (grouped into Entry and Exit) when tapped open.
 */
@Composable
internal fun StageCard(spec: StageSpec, modifier: Modifier = Modifier) {
    val c = Poly.colors
    val scheme = MaterialTheme.colorScheme
    var expanded by rememberSaveable(spec.index) { mutableStateOf(false) }
    val chevron by animateFloatAsState(if (expanded) 180f else 0f, label = "stage-chevron")
    val accent = when {
        spec.active -> c.warn
        spec.enabled -> scheme.primary
        else -> null
    }
    val badgeBg = when {
        spec.active -> c.warnContainer
        spec.enabled -> scheme.primaryContainer
        else -> scheme.surfaceVariant
    }
    val badgeFg = when {
        spec.active -> c.warn
        spec.enabled -> scheme.onPrimaryContainer
        else -> scheme.onSurfaceVariant
    }
    ProCard(
        modifier = modifier
            .alpha(if (spec.enabled) 1f else 0.72f)
            .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" },
        accent = accent,
        onClick = { expanded = !expanded },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.md)) {
            Box(
                Modifier
                    .size(36.dp)
                    .background(badgeBg, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    spec.index.toString(),
                    style = Mono.medium,
                    color = badgeFg,
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(spec.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    spec.window,
                    style = Mono.small,
                    color = scheme.onSurfaceVariant,
                )
            }
            when {
                spec.active -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PulseDot(c.warn)
                    Pill("ACTIVE", c.warn, c.warnContainer)
                }
                spec.enabled -> ToneChip("ON", Tone.Up)
                else -> ToneChip("OFF", Tone.Neutral)
            }
            Icon(
                Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = scheme.onSurfaceVariant,
                modifier = Modifier.rotate(chevron),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.md)) {
            spec.highlights.forEach { (key, value) ->
                ProStat(key, value, Modifier.weight(1f))
            }
        }
        AnimatedVisibility(visible = expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                HorizontalDivider(color = scheme.outlineVariant.copy(alpha = HairlineAlpha))
                ParameterGroup("Entry", spec.entry)
                ParameterGroup("Exit", spec.exit)
            }
        }
    }
}

@Composable
private fun ParameterGroup(title: String, rows: List<Pair<String, String>>) {
    if (rows.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Label(title)
        rows.forEach { (key, value) -> ParameterRow(key, value) }
    }
}

@Composable
private fun ParameterRow(key: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(Space.md)) {
        Text(
            key,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = Mono.body,
            modifier = Modifier.weight(1f),
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
        )
    }
}
