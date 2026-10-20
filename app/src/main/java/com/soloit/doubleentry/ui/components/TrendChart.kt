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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.soloit.doubleentry.domain.Money
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

/**
 * Line chart of monthly amounts with a soft gradient fill. Tapping selects the nearest month,
 * which is highlighted with a guide line and its value. The axis fits the values rather than
 * starting at zero, so small changes on a large amount stay visible.
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
    val gutters = rememberAxisGutters(measurer, labelStyle, bubbleStyle)

    val progress = remember { Animatable(0f) }
    LaunchedEffect(values) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(600))
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(168.dp + gutters.top + gutters.bottom)
            .pointerInput(values, gutters) {
                detectTapGestures { offset ->
                    if (values.isEmpty()) return@detectTapGestures
                    val left = gutters.left.toPx()
                    val right = size.width - 12.dp.toPx()
                    val step = if (values.size > 1) (right - left) / (values.size - 1) else 0f
                    val index = if (step == 0f) 0 else ((offset.x - left) / step).let { Math.round(it) }.coerceIn(0, values.lastIndex)
                    onSelect(index)
                }
            },
    ) {
        if (values.isEmpty()) return@Canvas
        val left = gutters.left.toPx()
        val right = size.width - 12.dp.toPx()
        val top = gutters.top.toPx()
        val bottom = size.height - gutters.bottom.toPx()

        val (axisMin, axisMax, tick) = niceScale(values.min(), values.max())
        fun y(v: Long) = bottom - (v - axisMin).toFloat() / (axisMax - axisMin).toFloat() * (bottom - top)
        val step = if (values.size > 1) (right - left) / (values.size - 1) else 0f
        fun x(i: Int) = if (values.size == 1) (left + right) / 2 else left + i * step

        // Grid and y-axis labels.
        var gridValue = axisMin
        while (gridValue <= axisMax) {
            val gy = y(gridValue)
            drawLine(gridColor, Offset(left, gy), Offset(right, gy), strokeWidth = 1f, pathEffect = if (gridValue == 0L) null else PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
            val text = measurer.measure(axisLabel(gridValue, axisMin, axisMax, tick), labelStyle)
            drawText(text, topLeft = Offset(left - text.size.width - 8.dp.toPx(), gy - text.size.height / 2))
            gridValue += tick
        }

        // X-axis labels, thinned out so they never overlap.
        drawXAxisLabels(measurer, labels, labelStyle, labelEvery(labels, values.size, step, measurer, labelStyle), ::x, left, bottom)

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

/** Room around a chart's plot for the axis labels and the value bubble. */
private data class AxisGutters(val left: Dp, val top: Dp, val bottom: Dp)

/**
 * Measures the gutters from the label styles so labels still fit with a large system font. The
 * y-axis gutter fits a wide sample label (a zoomed axis can need two decimals) rather than the
 * actual ones, so stacked charts keep their columns lined up.
 */
@Composable
private fun rememberAxisGutters(measurer: TextMeasurer, labelStyle: TextStyle, bubbleStyle: TextStyle? = null): AxisGutters {
    val density = LocalDensity.current
    return remember(measurer, labelStyle, bubbleStyle, density) {
        with(density) {
            val label = measurer.measure("-8.88M", labelStyle).size
            val bubble = bubbleStyle?.let { measurer.measure("0", it).size.height.toDp() + 8.dp } ?: 0.dp
            AxisGutters(
                left = maxOf(44.dp, label.width.toDp() + 8.dp),
                top = maxOf(28.dp, bubble + 4.dp),
                bottom = maxOf(24.dp, label.height.toDp() + 8.dp),
            )
        }
    }
}

/**
 * Every how many points an x-axis label goes: at most about six labels, and far enough apart that
 * the widest one doesn't touch its neighbours. Counted back from the last point, which is always
 * labelled.
 */
private fun DrawScope.labelEvery(labels: List<String>, count: Int, step: Float, measurer: TextMeasurer, style: TextStyle): Int {
    val widest = labels.take(count).maxOfOrNull { measurer.measure(it, style).size.width } ?: 0
    val apart = if (step > 0f) ceil((widest + 8.dp.toPx()) / step).toInt() else 1
    return maxOf(1, ceil(count / 6.0).toInt(), apart)
}

/**
 * Draws every [every]th x-axis label, counted back from the last. A label pushed inwards to stay
 * inside the chart is skipped when it would touch its neighbour.
 */
private fun DrawScope.drawXAxisLabels(
    measurer: TextMeasurer,
    labels: List<String>,
    style: TextStyle,
    every: Int,
    x: (Int) -> Float,
    left: Float,
    bottom: Float,
) {
    val gap = 8.dp.toPx()
    var nextStart = Float.MAX_VALUE
    for (i in labels.lastIndex downTo 0 step every) {
        val text = measurer.measure(labels[i], style)
        val lx = (x(i) - text.size.width / 2).coerceIn(left - 8.dp.toPx(), size.width - text.size.width.toFloat())
        if (lx + text.size.width + gap > nextStart) continue
        drawText(text, topLeft = Offset(lx, bottom + 6.dp.toPx()))
        nextStart = lx
    }
}

/**
 * A compact axis label (`12k`, `2.45M`) with just enough decimals to tell neighbouring grid lines
 * apart, which matters once the axis no longer starts at zero.
 */
private fun axisLabel(value: Long, axisMin: Long, axisMax: Long, tick: Long): String {
    if (value == 0L) return "0"
    val (divisor, suffix) = when (max(abs(axisMin), abs(axisMax)) / 100) {
        in 1_000_000..Long.MAX_VALUE -> 100_000_000.0 to "M"
        in 1_000 until 1_000_000 -> 100_000.0 to "k"
        else -> return Money.formatCompact(value)
    }
    val decimals = ceil(-log10(tick / divisor)).toInt().coerceIn(0, 3)
    val text = BigDecimal.valueOf(value / divisor).setScale(decimals, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    return text + suffix
}

/** Rounds the axis to "nice" steps (1, 2, 5 × 10ⁿ) with about four intervals. */
private fun niceScale(rawMin: Long, rawMax: Long): Triple<Long, Long, Long> {
    // A flat series (e.g. an unchanged balance) gets a small band around it, so the line sits mid-chart.
    val pad = if (rawMax == rawMin) max(abs(rawMin) / 20, 5_000L) else 0L
    val min = rawMin - pad
    val max = rawMax + pad
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

/**
 * Diverging bars: [inflows] rise above the zero line and [outflows] hang below it. Columns line up
 * with a [TrendLineChart] over the same number of points, so the two can be stacked and share a
 * selection.
 */
@Composable
fun FlowBarChart(
    inflows: List<Long>,
    outflows: List<Long>,
    labels: List<String>,
    inColor: Color,
    outColor: Color,
    selectedIndex: Int?,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = labelColor)
    val gutters = rememberAxisGutters(measurer, labelStyle)
    val count = min(inflows.size, outflows.size)

    val progress = remember { Animatable(0f) }
    LaunchedEffect(inflows, outflows) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(600))
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(126.dp + gutters.bottom)
            .pointerInput(count, gutters) {
                detectTapGestures { offset ->
                    if (count == 0) return@detectTapGestures
                    val left = gutters.left.toPx()
                    val right = size.width - 12.dp.toPx()
                    val step = if (count > 1) (right - left) / (count - 1) else 0f
                    val index = if (step == 0f) 0 else ((offset.x - left) / step).let { Math.round(it) }.coerceIn(0, count - 1)
                    onSelect(index)
                }
            },
    ) {
        if (count == 0) return@Canvas
        val left = gutters.left.toPx()
        val right = size.width - 12.dp.toPx()
        val top = 8.dp.toPx()
        val bottom = size.height - gutters.bottom.toPx()

        val (axisMin, axisMax, tick) = niceScale(-outflows.take(count).max().coerceAtLeast(0), inflows.take(count).max().coerceAtLeast(0))
        fun y(v: Long) = bottom - (v - axisMin).toFloat() / (axisMax - axisMin).toFloat() * (bottom - top)
        val step = if (count > 1) (right - left) / (count - 1) else 0f
        fun x(i: Int) = if (count == 1) (left + right) / 2 else left + i * step

        var gridValue = axisMin
        while (gridValue <= axisMax) {
            val gy = y(gridValue)
            drawLine(gridColor, Offset(left, gy), Offset(right, gy), strokeWidth = 1f, pathEffect = if (gridValue == 0L) null else PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
            val text = measurer.measure(axisLabel(gridValue, axisMin, axisMax, tick), labelStyle)
            drawText(text, topLeft = Offset(left - text.size.width - 8.dp.toPx(), gy - text.size.height / 2))
            gridValue += tick
        }

        drawXAxisLabels(measurer, labels.take(count), labelStyle, labelEvery(labels, count, step, measurer, labelStyle), ::x, left, bottom)

        // Bars grow out of the zero line; the selected column stays strong while the rest fade back.
        val barWidth = if (count == 1) 24.dp.toPx() else min(step * 0.6f, 14.dp.toPx()).coerceAtLeast(2f)
        val zero = y(0)
        val radius = CornerRadius(min(barWidth / 2, 3.dp.toPx()))
        for (i in 0 until count) {
            val alpha = if (selectedIndex == null || selectedIndex == i) 1f else 0.45f
            val bx = x(i) - barWidth / 2
            val up = (zero - y(inflows[i])) * progress.value
            val down = (y(-outflows[i]) - zero) * progress.value
            if (up > 0f) drawRoundRect(inColor.copy(alpha = alpha), topLeft = Offset(bx, zero - up), size = Size(barWidth, up), cornerRadius = radius)
            if (down > 0f) drawRoundRect(outColor.copy(alpha = alpha), topLeft = Offset(bx, zero), size = Size(barWidth, down), cornerRadius = radius)
        }
    }
}
