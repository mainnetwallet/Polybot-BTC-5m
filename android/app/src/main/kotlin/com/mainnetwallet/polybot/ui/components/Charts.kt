package com.mainnetwallet.polybot.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mainnetwallet.polybot.ui.Fmt
import com.mainnetwallet.polybot.ui.theme.Mono

/** Line chart without axes. [reference] draws a dashed horizontal line (e.g. the price to beat). */
@Composable
fun Sparkline(
    values: List<Float>,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 56.dp,
    reference: Float? = null,
    description: String = "Price chart",
) {
    val track = MaterialTheme.colorScheme.outlineVariant
    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .semantics { contentDescription = description },
    ) {
        if (values.size < 2) {
            drawLine(
                track,
                Offset(0f, size.height / 2),
                Offset(size.width, size.height / 2),
                strokeWidth = 2.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)),
            )
            return@Canvas
        }
        var lo = values.min()
        var hi = values.max()
        if (reference != null) {
            lo = minOf(lo, reference)
            hi = maxOf(hi, reference)
        }
        val span = if (hi - lo < 1e-6f) 1f else hi - lo
        val pad = 4.dp.toPx()
        val usable = size.height - pad * 2
        fun y(v: Float) = pad + usable * (1f - (v - lo) / span)
        val stepX = size.width / (values.size - 1)

        if (reference != null) {
            drawLine(
                track,
                Offset(0f, y(reference)),
                Offset(size.width, y(reference)),
                strokeWidth = 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
            )
        }
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = stepX * i
            if (i == 0) path.moveTo(x, y(v)) else path.lineTo(x, y(v))
        }
        drawPath(path, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(color, radius = 3.dp.toPx(), center = Offset(size.width, y(values.last())))
    }
}

/** Circular countdown: the arc is the share of the 5-minute window that is left. */
@Composable
fun CountdownRing(
    remainingSeconds: Double,
    totalSeconds: Double,
    modifier: Modifier = Modifier,
    ringSize: Dp = 148.dp,
    color: Color = MaterialTheme.colorScheme.primary,
    caption: String = "left in this market",
) {
    val fraction = if (totalSeconds <= 0) 0f else (remainingSeconds / totalSeconds).coerceIn(0.0, 1.0).toFloat()
    val animated by animateFloatAsState(fraction, tween(250), label = "ring")
    val track = MaterialTheme.colorScheme.surfaceVariant
    Box(
        modifier
            .size(ringSize)
            .semantics { contentDescription = "${Fmt.clock(remainingSeconds)} remaining" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(ringSize)) {
            val stroke = 10.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            drawArc(color, -90f, 360f * animated, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(Fmt.clock(remainingSeconds), style = Mono.large)
            Text(caption, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
