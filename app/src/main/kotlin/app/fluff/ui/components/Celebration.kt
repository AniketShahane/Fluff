package app.fluff.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import app.fluff.ui.theme.Fluff
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

private data class Particle(val angle: Float, val speed: Float, val color: Color, val size: Float, val spin: Float, val wide: Boolean)

/** A one-shot confetti burst from [originFraction] of the canvas. Re-fires whenever [key] changes. */
@Composable
fun ConfettiBurst(key: Any?, modifier: Modifier = Modifier, originFraction: Offset = Offset(0.5f, 0.35f), count: Int = 90) {
    if (key == null) return
    val reduced = rememberReducedMotion()
    if (reduced) return
    val particles = remember(key) {
        val rnd = Random(key.hashCode())
        val colors = listOf(Fluff.Lime, Fluff.Mint, Fluff.Amber, Fluff.Violet, Fluff.Sky, Fluff.Rose)
        List(count) {
            Particle(
                angle = (rnd.nextFloat() * 2f * Math.PI).toFloat(),
                speed = 0.35f + rnd.nextFloat() * 0.75f,
                color = colors[rnd.nextInt(colors.size)],
                size = 6f + rnd.nextFloat() * 8f,
                spin = rnd.nextFloat() * 720f - 360f,
                wide = rnd.nextBoolean(),
            )
        }
    }
    val t = remember(key) { Animatable(0f) }
    LaunchedEffect(key) { t.animateTo(1f, tween(2200, easing = LinearEasing)) }
    Canvas(modifier.fillMaxSize()) {
        val p = t.value
        if (p >= 1f) return@Canvas
        val o = Offset(size.width * originFraction.x, size.height * originFraction.y)
        val scale = size.minDimension
        particles.forEach { pt ->
            // Ballistic motion with drag and gravity, fading out near the end.
            val drag = 1f - (1f - p) * (1f - p)
            val x = o.x + cos(pt.angle) * pt.speed * scale * 0.55f * drag
            val y = o.y + sin(pt.angle) * pt.speed * scale * 0.45f * drag + p * p * scale * 0.7f
            val alpha = (1f - ((p - 0.7f) / 0.3f).coerceIn(0f, 1f))
            rotate(pt.spin * p, Offset(x, y)) {
                drawRect(pt.color.copy(alpha = alpha), Offset(x, y), if (pt.wide) Size(pt.size * 1.6f, pt.size * 0.6f) else Size(pt.size * 0.7f, pt.size * 0.7f))
            }
        }
    }
}
