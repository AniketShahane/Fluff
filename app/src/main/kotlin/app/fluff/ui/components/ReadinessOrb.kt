package app.fluff.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.circle
import androidx.graphics.shapes.star
import androidx.graphics.shapes.toPath
import app.fluff.core.readiness.ReadinessBand
import app.fluff.ui.theme.Fluff
import app.fluff.ui.theme.FluffTheme

/**
 * The heart of the Today screen: a living blob whose shape, colour and breathing pace follow
 * readiness. Higher readiness → more points, a sharper silhouette and a quicker pulse; recovery
 * days relax into a soft, slow round form. A ring around it fills to the score.
 */
@Composable
fun ReadinessOrb(
    score: Int?,
    band: ReadinessBand?,
    modifier: Modifier = Modifier,
    size: Dp = 260.dp,
    showLabel: Boolean = true,
) {
    val reduced = rememberReducedMotion()
    val s = (score ?: 50).coerceIn(0, 100)
    val color by animateColorAsState(band?.let(Fluff::band) ?: Fluff.TextFaint, tween(900), label = "orbColor")
    val deep by animateColorAsState(band?.let(Fluff::bandDeep) ?: Fluff.Line, tween(900), label = "orbDeep")

    // A new morph whenever the band changes the "personality" of the shape.
    val points = 5 + (s / 14)
    val morph = remember(points) {
        val calm = RoundedPolygon.circle(numVertices = points * 2, radius = 1f)
        val lively = RoundedPolygon.star(
            numVerticesPerRadius = points,
            radius = 1f,
            innerRadius = 0.80f,
            rounding = CornerRounding(radius = 0.32f, smoothing = 0.9f),
            innerRounding = CornerRounding(radius = 0.28f, smoothing = 0.9f),
        )
        Morph(calm, lively)
    }
    val path = remember { android.graphics.Path() }

    val infinite = rememberInfiniteTransition(label = "orb")
    val periodMs = (3600 - s * 18).coerceIn(1600, 3600)
    val breath by infinite.animateFloat(
        initialValue = 0.15f, targetValue = 0.85f,
        animationSpec = infiniteRepeatable(tween(periodMs, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breath",
    )
    val spin by infinite.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(48_000, easing = LinearEasing)), label = "spin",
    )
    val glow by infinite.animateFloat(
        initialValue = 0.55f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(periodMs, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "glow",
    )

    val ring = remember { Animatable(0f) }
    LaunchedEffect(score) { ring.animateTo((score ?: 0) / 100f, Fluff.gentle()) }
    val shown by animateIntAsState(score ?: 0, tween(1100, easing = Fluff.Emphasized), label = "count")

    Box(
        modifier = modifier.size(size).semantics { contentDescription = if (score == null) "Readiness not available yet" else "Readiness $score of 100, ${band?.label}" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val r = this.size.minDimension / 2f
            val c = center
            val g = if (reduced) 0.8f else glow
            // Soft halo.
            drawCircle(
                brush = Brush.radialGradient(listOf(color.copy(alpha = 0.28f * g), color.copy(alpha = 0.06f * g), Color.Transparent), c, r),
                radius = r,
            )
            // Score ring.
            val stroke = r * 0.045f
            val ringR = r * 0.94f - stroke
            drawCircle(color = Fluff.Line.copy(alpha = 0.6f), radius = ringR, style = Stroke(stroke))
            if (ring.value > 0f) {
                rotate(-90f, c) {
                    drawArc(
                        // Stops follow the arc and wrap back to `deep`, so the round start cap (which pokes just
                        // behind 0°) isn't painted in the end colour.
                        brush = Brush.sweepGradient(0f to deep, ring.value.coerceIn(0.01f, 1f) to color, 1f to deep, center = c),
                        startAngle = 0f, sweepAngle = 360f * ring.value, useCenter = false,
                        topLeft = Offset(c.x - ringR, c.y - ringR), size = androidx.compose.ui.geometry.Size(ringR * 2, ringR * 2),
                        style = Stroke(stroke, cap = StrokeCap.Round),
                    )
                }
            }
            // The blob.
            val blobR = r * 0.68f
            path.rewind()
            morph.toPath(if (reduced) 0.5f else breath, path)
            val p = path.asComposePath()
            translate(c.x, c.y) {
                rotate(if (reduced) 0f else spin, Offset.Zero) {
                    scale(blobR, blobR, Offset.Zero) {
                        drawPath(p, Brush.radialGradient(listOf(color, deep), Offset(-0.35f, -0.45f), 1.6f))
                    }
                }
            }
            // Glassy highlight.
            drawCircle(
                brush = Brush.radialGradient(listOf(Color.White.copy(alpha = 0.22f), Color.Transparent), Offset(c.x - blobR * 0.3f, c.y - blobR * 0.4f), blobR * 0.7f),
                radius = blobR * 0.7f, center = Offset(c.x - blobR * 0.3f, c.y - blobR * 0.4f),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // No placeholder glyph without a score: at hero size a dash renders as a heavy black bar.
            if (score != null) {
                Text(
                    text = "$shown",
                    style = FluffTheme.type.hero.copy(fontSize = FluffTheme.type.hero.fontSize * (size.value / 260f)),
                    color = Fluff.Night,
                )
            }
            if (showLabel && band != null) {
                Text(band.label.uppercase(), style = FluffTheme.type.eyebrow, color = Fluff.Night.copy(alpha = 0.75f))
            }
        }
    }
}

/** A thin progress ring with a value in the middle; used for goals, rest timer and summaries. */
@Composable
fun ProgressRing(
    fraction: Float,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    track: Color = Fluff.Line,
    strokeWidth: Dp = 8.dp,
    content: @Composable () -> Unit = {},
) {
    val anim = remember { Animatable(0f) }
    LaunchedEffect(fraction) { anim.animateTo(fraction.coerceIn(0f, 1f), Fluff.gentle()) }
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val sw = strokeWidth.toPx()
            val d = this.size.minDimension - sw
            val tl = Offset((this.size.width - d) / 2, (this.size.height - d) / 2)
            val sz = androidx.compose.ui.geometry.Size(d, d)
            drawArc(track, 0f, 360f, false, tl, sz, style = Stroke(sw))
            drawArc(color, -90f, 360f * anim.value, false, tl, sz, style = Stroke(sw, cap = StrokeCap.Round))
        }
        content()
    }
}
