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
import androidx.compose.ui.text.TextStyle
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
    strokeWidth: Dp = 10.dp,
    valueStyle: TextStyle = Mono.large,
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
            val stroke = strokeWidth.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            drawArc(color, -90f, 360f * animated, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(Fmt.clock(remainingSeconds), style = valueStyle)
            Text(caption, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Ring that fills clockwise from the top up to [fraction] (0..1). [content] is centred inside it. */
@Composable
fun RingGauge(
    fraction: Float,
    color: Color,
    modifier: Modifier = Modifier,
    ringSize: Dp = 112.dp,
    strokeWidth: Dp = 12.dp,
    description: String = "Gauge",
    content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {},
) {
    val target = if (fraction.isNaN()) 0f else fraction.coerceIn(0f, 1f)
    val animated by animateFloatAsState(target, tween(400), label = "gauge")
    val track = MaterialTheme.colorScheme.surfaceVariant
    Box(
        modifier
            .size(ringSize)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(ringSize)) {
            val stroke = strokeWidth.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            if (animated > 0f) {
                drawArc(
                    color,
                    -90f,
                    360f * animated,
                    false,
                    Offset(inset, inset),
                    arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
        }
        content()
    }
}

/**
 * Horizontal bar around a centre line: a positive [value] grows to the right in [positive], a negative one to the
 * left in [negative]. [maxAbs] is the value that fills one half, so several bars can share one scale.
 */
@Composable
fun DivergingBar(
    value: Double,
    maxAbs: Double,
    positive: Color,
    negative: Color,
    modifier: Modifier = Modifier,
    height: Dp = 10.dp,
) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val centre = MaterialTheme.colorScheme.outline
    Canvas(
        modifier
            .fillMaxWidth()
            .height(height),
    ) {
        val round = androidx.compose.ui.geometry.CornerRadius(size.height / 2f, size.height / 2f)
        drawRoundRect(color = track, size = size, cornerRadius = round)
        val half = size.width / 2f
        if (maxAbs > 0.0 && value != 0.0 && !value.isNaN()) {
            val share = (kotlin.math.abs(value) / maxAbs).coerceIn(0.0, 1.0).toFloat()
            val w = maxOf(half * share, 3.dp.toPx())
            drawRoundRect(
                color = if (value > 0) positive else negative,
                topLeft = Offset(if (value > 0) half else half - w, 0f),
                size = Size(w, size.height),
                cornerRadius = round,
            )
        }
        val line = 1.5.dp.toPx()
        drawRect(color = centre, topLeft = Offset(half - line / 2f, 0f), size = Size(line, size.height))
    }
}
