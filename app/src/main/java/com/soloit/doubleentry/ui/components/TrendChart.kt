package com.soloit.doubleentry.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.soloit.doubleentry.domain.Money
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Line chart of monthly amounts with a soft gradient fill. Tapping selects the nearest month,
 * which is highlighted with a guide line and its value.
 */
@Composable
fun TrendLineChart(
    values: List<Long>,
    labels: List<String>,
    color: Color,
    selectedIndex: Int?,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val surface = MaterialTheme.colorScheme.surfaceContainerLow
    val bubble = MaterialTheme.colorScheme.inverseSurface
    val onBubble = MaterialTheme.colorScheme.inverseOnSurface
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = labelColor)
    val bubbleStyle = MaterialTheme.typography.labelMedium.copy(color = onBubble, fontFeatureSettings = "tnum")

    val progress = remember { Animatable(0f) }
    LaunchedEffect(values) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(600))
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(220.dp)
            .pointerInput(values) {
                detectTapGestures { offset ->
                    if (values.isEmpty()) return@detectTapGestures
                    val left = 44.dp.toPx()
                    val right = size.width - 12.dp.toPx()
                    val step = if (values.size > 1) (right - left) / (values.size - 1) else 0f
                    val index = if (step == 0f) 0 else ((offset.x - left) / step).let { Math.round(it) }.coerceIn(0, values.lastIndex)
                    onSelect(index)
                }
            },
    ) {
        if (values.isEmpty()) return@Canvas
        val left = 44.dp.toPx()
        val right = size.width - 12.dp.toPx()
        val top = 28.dp.toPx()
        val bottom = size.height - 24.dp.toPx()

        val rawMax = values.max().coerceAtLeast(0)
        val rawMin = values.min().coerceAtMost(0)
        val (axisMin, axisMax, tick) = niceScale(rawMin, rawMax)
        fun y(v: Long) = bottom - (v - axisMin).toFloat() / (axisMax - axisMin).toFloat() * (bottom - top)
        val step = if (values.size > 1) (right - left) / (values.size - 1) else 0f
        fun x(i: Int) = if (values.size == 1) (left + right) / 2 else left + i * step

        // Grid and y-axis labels.
        var gridValue = axisMin
        while (gridValue <= axisMax) {
            val gy = y(gridValue)
            drawLine(gridColor, Offset(left, gy), Offset(right, gy), strokeWidth = 1f, pathEffect = if (gridValue == 0L) null else PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
            val text = measurer.measure(Money.formatCompact(gridValue), labelStyle)
            drawText(text, topLeft = Offset(left - text.size.width - 8.dp.toPx(), gy - text.size.height / 2))
            gridValue += tick
        }

        // X-axis labels, thinned out so they never overlap.
        val labelEvery = max(1, ceil(values.size / 6.0).toInt())
        labels.forEachIndexed { i, label ->
            if (i % labelEvery != 0 && i != labels.lastIndex) return@forEachIndexed
            val text = measurer.measure(label, labelStyle)
            val lx = (x(i) - text.size.width / 2).coerceIn(left - 8.dp.toPx(), size.width - text.size.width.toFloat())
            drawText(text, topLeft = Offset(lx, bottom + 6.dp.toPx()))
        }

        // Line and gradient fill, revealed left to right.
        val visible = max(1, ceil(values.size * progress.value).toInt())
        val line = Path()
        values.take(visible).forEachIndexed { i, v -> if (i == 0) line.moveTo(x(i), y(v)) else line.lineTo(x(i), y(v)) }
        val fill = Path().apply {
            addPath(line)
            lineTo(x(visible - 1), bottom)
            lineTo(x(0), bottom)
            close()
        }
        drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.28f), color.copy(alpha = 0.02f)), startY = top, endY = bottom))
        drawPath(line, color, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))

        values.take(visible).forEachIndexed { i, v ->
            drawCircle(surface, radius = 5.dp.toPx(), center = Offset(x(i), y(v)))
            drawCircle(color, radius = 3.5.dp.toPx(), center = Offset(x(i), y(v)))
        }

        // Selection guide and value bubble.
        selectedIndex?.takeIf { it in values.indices && progress.value >= 1f }?.let { i ->
            val sx = x(i)
            val sy = y(values[i])
            drawLine(color.copy(alpha = 0.5f), Offset(sx, top), Offset(sx, bottom), strokeWidth = 2f)
            drawCircle(color, radius = 7.dp.toPx(), center = Offset(sx, sy))
            drawCircle(surface, radius = 3.dp.toPx(), center = Offset(sx, sy))
            val text = measurer.measure(Money.formatWhole(values[i]), bubbleStyle)
            val padH = 8.dp.toPx()
            val padV = 4.dp.toPx()
            val w = text.size.width + padH * 2
            val h = text.size.height + padV * 2
            val bx = (sx - w / 2).coerceIn(0f, size.width - w)
            val by = max(0f, min(sy - h - 10.dp.toPx(), top - h + 20.dp.toPx()))
            drawRoundRect(bubble, topLeft = Offset(bx, by), size = Size(w, h), cornerRadius = CornerRadius(h / 2))
            drawText(text, topLeft = Offset(bx + padH, by + padV))
        }
    }
}

/** Rounds the axis to "nice" steps (1, 2, 5 × 10ⁿ) with about four intervals. */
private fun niceScale(min: Long, rawMax: Long): Triple<Long, Long, Long> {
    // A flat series (e.g. all zero) still gets a visible range.
    val max = if (rawMax == min) min + max(abs(min), 10_000L) else rawMax
    val range = (max - min).toDouble()
    val rough = range / 4
    val magnitude = Math.pow(10.0, Math.floor(Math.log10(rough)))
    val residual = rough / magnitude
    val nice = when {
        residual <= 1 -> 1.0
        residual <= 2 -> 2.0
        residual <= 5 -> 5.0
        else -> 10.0
    } * magnitude
    val tick = max(1L, nice.toLong())
    val axisMin = Math.floorDiv(min, tick) * tick
    val axisMax = -Math.floorDiv(-max, tick) * tick
    return Triple(axisMin, if (axisMax == axisMin) axisMin + tick else axisMax, tick)
}
