package app.fluff.ui.components

import android.provider.Settings
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import app.fluff.ui.theme.Fluff

/** True when the user turned animations off in system settings; ambient motion then stops. */
@Composable
fun rememberReducedMotion(): Boolean {
    val ctx = LocalContext.current
    val inspection = LocalInspectionMode.current
    return remember(ctx) {
        inspection || runCatching { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    }
}

/** Springy press feedback: the element sinks slightly while held. */
fun Modifier.pressScale(source: MutableInteractionSource, pressed: Float = 0.96f): Modifier = this.then(
    Modifier.graphicsLayerPress(source, pressed),
)

@Composable
private fun pressedScale(source: MutableInteractionSource, pressed: Float): Float {
    val isPressed by source.collectIsPressedAsState()
    val s by animateFloatAsState(if (isPressed) pressed else 1f, Fluff.bouncy(), label = "press")
    return s
}

private fun Modifier.graphicsLayerPress(source: MutableInteractionSource, pressed: Float): Modifier =
    composed {
        val s = pressedScale(source, pressed)
        graphicsLayer { scaleX = s; scaleY = s }
    }
