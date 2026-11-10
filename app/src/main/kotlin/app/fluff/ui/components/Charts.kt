package app.fluff.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.fluff.ui.theme.Fluff
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

data class ChartPoint(val date: LocalDate, val value: Double)

private val axisStyle = TextStyle(color = Fluff.TextFaint, fontSize = 10.sp, fontWeight = FontWeight.Medium)
private val shortDate = DateTimeFormatter.ofPattern("MMM d")

/**
 * Smooth trend line with a gradient wash, an optional dashed goal line and a dotted projection.
 * Draws itself in from left to right, and can be scrubbed with a finger to read any point.
 */
@Composable
fun TrendChart(
    points: List<ChartPoint>,
    modifier: Modifier = Modifier,
    color: Color = Fluff.Lime,
    height: Dp = 180.dp,
    goal: Double? = null,
    projection: ChartPoint? = null,
    format: (Double) -> String = { "%.0f".format(it) },
    description: String = "Trend chart",
) {
    val measurer = rememberTextMeasurer()
    val haptics = LocalHapticFeedback.current
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(points) { reveal.snapTo(0f); reveal.animateTo(1f, tween(1100, easing = Fluff.Emphasized)) }
    var scrub by remember { mutableStateOf<Int?>(null) }

    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .semantics { contentDescription = description + (points.lastOrNull()?.let { ", latest ${format(it.value)}" } ?: "") }
            .pointerInput(points) {
                fun indexAt(x: Float): Int? {
                    if (points.isEmpty()) return null
                    val left = 8.dp.toPx(); val right = size.width - 36.dp.toPx()
                    val f = ((x - left) / (right - left)).coerceIn(0f, 1f)
                    val start = points.first().date
                    val span = spanDays(points, projection).toFloat()
                    val target = f * span
                    return points.indices.minByOrNull { kotlin.math.abs(ChronoUnit.DAYS.between(start, points[it].date) - target) }
                }
                detectDragGestures(
                    onDragStart = { scrub = indexAt(it.x) },
                    onDragEnd = { scrub = null },
                    onDragCancel = { scrub = null },
                ) { change, _ ->
                    val i = indexAt(change.position.x)
                    if (i != scrub) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    scrub = i
                }
            }
            .pointerInput(points) {
                detectTapGestures(onPress = {
                    tryAwaitRelease(); scrub = null
                })
            },
    ) {
        if (points.isEmpty()) return@Canvas
        val left = 8.dp.toPx(); val right = size.width - 36.dp.toPx()
        val top = 14.dp.toPx(); val bottom = size.height - 20.dp.toPx()
        val values = points.map { it.value } + listOfNotNull(goal, projection?.value)
        var lo = values.min(); var hi = values.max()
        if (hi - lo < 1e-6) { lo -= 1; hi += 1 }
        val pad = (hi - lo) * 0.15; lo -= pad; hi += pad
        val start = points.first().date
        val span = spanDays(points, projection).toFloat().coerceAtLeast(1f)
        fun x(d: LocalDate) = left + (right - left) * (ChronoUnit.DAYS.between(start, d) / span)
        fun y(v: Double) = (bottom - (bottom - top) * ((v - lo) / (hi - lo))).toFloat()

        // Grid: three quiet lines with labels on the right.
        for (i in 0..2) {
            val v = lo + (hi - lo) * (0.2 + 0.3 * i)
            val yy = y(v)
            drawLine(Fluff.Line.copy(alpha = 0.5f), Offset(left, yy), Offset(right, yy), 1.dp.toPx())
            drawLabel(measurer, format(v), Offset(right + 6.dp.toPx(), yy - 6.dp.toPx()))
        }
        drawLabel(measurer, start.format(shortDate), Offset(left, bottom + 5.dp.toPx()))
        val endDate = projection?.date ?: points.last().date
        val endLabel = endDate.format(shortDate)
        val endW = measurer.measure(endLabel, axisStyle).size.width
        drawLabel(measurer, endLabel, Offset(right - endW, bottom + 5.dp.toPx()))

        goal?.let {
            val gy = y(it)
            drawLine(Fluff.Amber.copy(alpha = 0.8f), Offset(left, gy), Offset(right, gy), 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f)))
            drawLabel(measurer, "Goal", Offset(left + 2.dp.toPx(), gy - 14.dp.toPx()), Fluff.Amber)
        }

        val pts = points.map { Offset(x(it.date), y(it.value)) }
        val line = smoothPath(pts)
        val fill = Path().apply {
            addPath(line)
            lineTo(pts.last().x, bottom); lineTo(pts.first().x, bottom); close()
        }
        val revealX = left + (right - left) * reveal.value + 2
        clipRect(right = revealX) {
            drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.28f), color.copy(alpha = 0f)), top, bottom))
            drawPath(line, color, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        projection?.let { p ->
            if (reveal.value >= 1f) {
                val a = pts.last(); val b = Offset(x(p.date), y(p.value))
                drawLine(color.copy(alpha = 0.6f), a, b, 2.dp.toPx(), StrokeCap.Round, PathEffect.dashPathEffect(floatArrayOf(2f, 12f)))
                drawCircle(color.copy(alpha = 0.25f), 7.dp.toPx(), b)
                drawCircle(color, 3.dp.toPx(), b, style = Stroke(1.5.dp.toPx()))
            }
        }
        if (reveal.value >= 1f) {
            val last = pts.last()
            drawCircle(color.copy(alpha = 0.25f), 8.dp.toPx(), last)
            drawCircle(color, 4.dp.toPx(), last)
            drawCircle(Fluff.Night, 1.5.dp.toPx(), last)
        }
        scrub?.let { i ->
            val p = pts[i]
            drawLine(Fluff.TextDim.copy(alpha = 0.5f), Offset(p.x, top), Offset(p.x, bottom), 1.dp.toPx())
            drawCircle(Fluff.Text, 5.dp.toPx(), p)
            drawCircle(color, 3.dp.toPx(), p)
            val label = "${format(points[i].value)} · ${points[i].date.format(shortDate)}"
            val m = measurer.measure(label, axisStyle.copy(color = Fluff.Night, fontSize = 11.sp, fontWeight = FontWeight.Bold))
            val w = m.size.width + 16.dp.toPx(); val h = m.size.height + 8.dp.toPx()
            val bx = (p.x - w / 2).coerceIn(0f, size.width - w)
            val by = max(0f, p.y - h - 10.dp.toPx())
            drawRoundRect(Fluff.Text, Offset(bx, by), Size(w, h), CornerRadius(h / 2))
            drawText(m, topLeft = Offset(bx + 8.dp.toPx(), by + 4.dp.toPx()))
        }
    }
}

private fun spanDays(points: List<ChartPoint>, projection: ChartPoint?): Long {
    val start = points.first().date
    val end = projection?.date ?: points.last().date
    return ChronoUnit.DAYS.between(start, end).coerceAtLeast(1)
}

/** Monotone-ish smoothing: horizontal control points never overshoot between samples. */
private fun smoothPath(pts: List<Offset>): Path = Path().apply {
    if (pts.isEmpty()) return@apply
    moveTo(pts[0].x, pts[0].y)
    if (pts.size == 1) { lineTo(pts[0].x + 0.1f, pts[0].y); return@apply }
    for (i in 1 until pts.size) {
        val a = pts[i - 1]; val b = pts[i]
        val dx = (b.x - a.x) / 2f
        cubicTo(a.x + dx, a.y, b.x - dx, b.y, b.x, b.y)
    }
}

private fun DrawScope.drawLabel(m: TextMeasurer, text: String, at: Offset, color: Color = Fluff.TextFaint) {
    drawText(m, text, at, axisStyle.copy(color = color))
}

/** Rounded bars that grow in with a stagger; the highlighted bar glows. */
@Composable
fun BarChart(
    values: List<Float>,
    labels: List<String>,
    modifier: Modifier = Modifier,
    color: Color = Fluff.Lime,
    height: Dp = 140.dp,
    highlight: Int? = values.lastIndex,
    target: Float? = null,
    format: (Float) -> String = { "%.0f".format(it) },
    description: String = "Bar chart",
) {
    val measurer = rememberTextMeasurer()
    val grow = remember { Animatable(0f) }
    LaunchedEffect(values) { grow.snapTo(0f); grow.animateTo(1f, tween(900, easing = Fluff.Emphasized)) }
    Canvas(modifier.fillMaxWidth().height(height).semantics { contentDescription = description }) {
        if (values.isEmpty()) return@Canvas
        val top = 18.dp.toPx(); val bottom = size.height - 18.dp.toPx()
        val maxV = max(values.max(), target ?: 0f).coerceAtLeast(1f)
        val slot = size.width / values.size
        val barW = min(slot * 0.56f, 28.dp.toPx())
        target?.let {
            val ty = bottom - (bottom - top) * (it / maxV)
            drawLine(Fluff.TextFaint, Offset(0f, ty), Offset(size.width, ty), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 8f)))
        }
        values.forEachIndexed { i, v ->
            // Each bar starts a little after the previous one.
            val local = ((grow.value * (1f + values.size * 0.08f)) - i * 0.08f).coerceIn(0f, 1f)
            val h = (bottom - top) * (v / maxV) * local
            val cx = slot * i + slot / 2
            val hi = i == highlight
            val c = if (hi) color else color.copy(alpha = 0.35f)
            if (v > 0f) {
                drawRoundRect(
                    brush = Brush.verticalGradient(listOf(c, c.copy(alpha = c.alpha * 0.6f)), bottom - h, bottom),
                    topLeft = Offset(cx - barW / 2, bottom - h), size = Size(barW, max(h, 2f)), cornerRadius = CornerRadius(barW / 3),
                )
            } else {
                drawRoundRect(Fluff.Line, Offset(cx - barW / 2, bottom - 3f), Size(barW, 3f), CornerRadius(2f))
            }
            if (hi && v > 0f && local >= 1f) {
                val t = measurer.measure(format(v), axisStyle.copy(color = Fluff.Text, fontWeight = FontWeight.Bold))
                drawText(t, topLeft = Offset(cx - t.size.width / 2, bottom - h - t.size.height - 2.dp.toPx()))
            }
            labels.getOrNull(i)?.let { l ->
                val t = measurer.measure(l, axisStyle.copy(color = if (hi) Fluff.TextDim else Fluff.TextFaint))
                drawText(t, topLeft = Offset(cx - t.size.width / 2, bottom + 4.dp.toPx()))
            }
        }
    }
}

/** Movement-pattern radar: grid rings, spokes and an animated filled polygon. */
@Composable
fun RadarChart(
    axes: List<String>,
    values: List<Float>,
    modifier: Modifier = Modifier,
    color: Color = Fluff.Violet,
    description: String = "Strength by movement pattern",
) {
    val measurer = rememberTextMeasurer()
    val grow = remember { Animatable(0f) }
    LaunchedEffect(values) { grow.snapTo(0f); grow.animateTo(1f, Fluff.bouncy()) }
    Canvas(modifier.semantics { contentDescription = description }) {
        val n = axes.size
        if (n < 3) return@Canvas
        val c = center
        val r = size.minDimension / 2f - 30.dp.toPx()
        fun at(i: Int, f: Float): Offset {
            val a = -PI / 2 + 2 * PI * i / n
            return Offset(c.x + (r * f * cos(a)).toFloat(), c.y + (r * f * sin(a)).toFloat())
        }
        for (ring in 1..4) {
            val f = ring / 4f
            val p = Path().apply { for (i in 0 until n) at(i, f).let { if (i == 0) moveTo(it.x, it.y) else lineTo(it.x, it.y) }; close() }
            drawPath(p, Fluff.Line.copy(alpha = if (ring == 4) 0.8f else 0.4f), style = Stroke(1.dp.toPx()))
        }
        for (i in 0 until n) drawLine(Fluff.Line.copy(alpha = 0.4f), c, at(i, 1f), 1.dp.toPx())
        val shape = Path().apply {
            for (i in 0 until n) at(i, (values.getOrElse(i) { 0f } * grow.value).coerceIn(0.02f, 1.1f)).let { if (i == 0) moveTo(it.x, it.y) else lineTo(it.x, it.y) }
            close()
        }
        drawPath(shape, Brush.radialGradient(listOf(color.copy(alpha = 0.15f), color.copy(alpha = 0.45f)), c, r))
        drawPath(shape, color, style = Stroke(2.dp.toPx(), join = StrokeJoin.Round))
        for (i in 0 until n) {
            drawCircle(color, 3.dp.toPx(), at(i, (values.getOrElse(i) { 0f } * grow.value).coerceIn(0.02f, 1.1f)))
            val t = measurer.measure(axes[i], axisStyle.copy(color = Fluff.TextDim, fontSize = 10.sp))
            val p = at(i, 1f)
            val dx = p.x - c.x; val dy = p.y - c.y
            val ox = when { dx > 4 -> 6.dp.toPx(); dx < -4 -> -t.size.width - 6.dp.toPx(); else -> -t.size.width / 2f }
            val oy = when { dy > 4 -> 4.dp.toPx(); dy < -4 -> -t.size.height - 4.dp.toPx(); else -> -t.size.height / 2f }
            drawText(t, topLeft = Offset(p.x + ox, p.y + oy))
        }
    }
}

/**
 * Training-consistency heatmap: one column per week (oldest left), Monday on top. Intensity is
 * how much happened that day; today gets an outline.
 */
@Composable
fun CalendarHeatmap(
    intensity: Map<LocalDate, Float>,
    today: LocalDate,
    modifier: Modifier = Modifier,
    weeks: Int = 18,
    color: Color = Fluff.Lime,
    description: String = "Training calendar",
) {
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(900)) }
    val measurer = rememberTextMeasurer()
    Canvas(modifier.fillMaxWidth().height((7 * 16 + 14).dp).semantics { contentDescription = description }) {
        val labelW = 18.dp.toPx()
        val gap = 3.dp.toPx()
        val cell = min((size.width - labelW - gap * (weeks - 1)) / weeks, (size.height - 14.dp.toPx() - gap * 6) / 7)
        val startMonday = today.minusDays((today.dayOfWeek.value - 1).toLong()).minusWeeks((weeks - 1).toLong())
        listOf(0 to "M", 2 to "W", 4 to "F").forEach { (row, l) ->
            drawText(measurer, l, Offset(0f, row * (cell + gap) + cell / 2 - 6.dp.toPx()), axisStyle)
        }
        var lastMonth = -1
        for (w in 0 until weeks) {
            val monday = startMonday.plusWeeks(w.toLong())
            if (monday.monthValue != lastMonth) {
                lastMonth = monday.monthValue
                if (w < weeks - 1) drawText(measurer, monday.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }, Offset(labelW + w * (cell + gap), 7 * (cell + gap)), axisStyle)
            }
            for (d in 0 until 7) {
                val day = monday.plusDays(d.toLong())
                if (day.isAfter(today)) continue
                val v = intensity[day] ?: 0f
                // Cells fade in diagonally.
                val local = ((appear.value * 1.6f) - (w + d) / (weeks + 7f) * 0.6f).coerceIn(0f, 1f)
                val c = if (v <= 0f) Fluff.Card else color.copy(alpha = (0.25f + 0.75f * v.coerceIn(0f, 1f)))
                val tl = Offset(labelW + w * (cell + gap), d * (cell + gap))
                drawRoundRect(c.copy(alpha = c.alpha * local), tl, Size(cell, cell), CornerRadius(cell * 0.28f))
                if (day == today) drawRoundRect(Fluff.Text.copy(alpha = 0.8f), tl, Size(cell, cell), CornerRadius(cell * 0.28f), style = Stroke(1.dp.toPx()))
            }
        }
    }
}

/** A tiny line for inline trends (readiness over the last two weeks). */
@Composable
fun Sparkline(values: List<Float?>, modifier: Modifier = Modifier, color: Color = Fluff.Lime, colorFor: ((Float) -> Color)? = null) {
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(values) { reveal.animateTo(1f, tween(800)) }
    Canvas(modifier) {
        val present = values.withIndex().filter { it.value != null }
        if (present.isEmpty()) return@Canvas
        val lo = 0f; val hi = 100f
        val step = if (values.size > 1) size.width / (values.size - 1) else 0f
        val pts = present.map { Offset(it.index * step, size.height - size.height * ((it.value!! - lo) / (hi - lo))) }
        clipRect(right = size.width * reveal.value + 4) {
            drawPath(smoothPath(pts), color.copy(alpha = 0.6f), style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
            present.forEachIndexed { i, iv ->
                drawCircle(colorFor?.invoke(iv.value!!) ?: color, if (i == present.lastIndex) 4.dp.toPx() else 2.5.dp.toPx(), pts[i])
            }
        }
    }
}
