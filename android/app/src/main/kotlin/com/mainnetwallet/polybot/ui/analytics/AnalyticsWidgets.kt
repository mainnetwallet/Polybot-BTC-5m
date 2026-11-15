package com.mainnetwallet.polybot.ui.analytics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mainnetwallet.polybot.ui.Fmt
import com.mainnetwallet.polybot.ui.components.Dot
import com.mainnetwallet.polybot.ui.components.DivergingBar
import com.mainnetwallet.polybot.ui.components.pnlColor
import com.mainnetwallet.polybot.ui.theme.Mono
import com.mainnetwallet.polybot.ui.theme.Poly
import com.mainnetwallet.polybot.ui.theme.Space

/** Wins and losses as one rounded bar split in green and red, with a thin gap between the two. */
@Composable
internal fun WinLossBar(wins: Int, losses: Int, modifier: Modifier = Modifier) {
    val c = Poly.colors
    Row(
        modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(RoundedCornerShape(50)),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (wins > 0) Box(Modifier.weight(wins.toFloat()).fillMaxHeight().background(c.up))
        if (losses > 0) Box(Modifier.weight(losses.toFloat()).fillMaxHeight().background(c.down))
    }
}

/** Dot, label and a mono value on one line. */
@Composable
internal fun LegendRow(color: Color, label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Dot(color)
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(value, style = Mono.body.copy(fontWeight = FontWeight.SemiBold))
    }
}

/**
 * A 0..100% track filled up to [winRate], with a tick at [breakEven]. The fill reaching past the tick means the win
 * rate beats the average entry price.
 */
@Composable
internal fun BreakEvenTrack(
    winRate: Double?,
    breakEven: Double?,
    fill: Color,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val track = scheme.surfaceVariant
    val tick = scheme.onSurface
    val win = ((winRate ?: 0.0) / 100.0).coerceIn(0.0, 1.0).toFloat()
    val be = breakEven?.let { (it / 100.0).coerceIn(0.0, 1.0).toFloat() }
    Canvas(
        modifier
            .fillMaxWidth()
            .height(20.dp)
            .semantics {
                contentDescription = "Win rate ${Fmt.percent(winRate)}, break-even ${Fmt.percent(breakEven)}"
            },
    ) {
        val trackH = 10.dp.toPx()
        val top = (size.height - trackH) / 2f
        val round = CornerRadius(trackH / 2f, trackH / 2f)
        drawRoundRect(color = track, topLeft = Offset(0f, top), size = Size(size.width, trackH), cornerRadius = round)
        if (win > 0f) {
            drawRoundRect(
                color = fill,
                topLeft = Offset(0f, top),
                size = Size(maxOf(size.width * win, trackH), trackH),
                cornerRadius = round,
            )
        }
        if (be != null) {
            val w = 3.dp.toPx()
            val x = (size.width * be).coerceIn(w / 2f, size.width - w / 2f)
            drawRoundRect(
                color = tick,
                topLeft = Offset(x - w / 2f, 0f),
                size = Size(w, size.height),
                cornerRadius = CornerRadius(w / 2f, w / 2f),
            )
        }
    }
}

/** One PnL line: label and signed value on top, a [DivergingBar] below it. Bars that share [maxAbs] share a scale. */
@Composable
internal fun PnlBreakdownRow(
    label: String,
    value: Double,
    maxAbs: Double,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
) {
    val c = Poly.colors
    val scheme = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = if (emphasized) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                fontWeight = if (emphasized) FontWeight.SemiBold else null,
                color = if (emphasized) scheme.onSurface else scheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                Fmt.signedUsd(value),
                style = Mono.body.copy(fontWeight = FontWeight.SemiBold),
                color = pnlColor(value),
            )
        }
        DivergingBar(value = value, maxAbs = maxAbs, positive = c.up, negative = c.down)
    }
}
