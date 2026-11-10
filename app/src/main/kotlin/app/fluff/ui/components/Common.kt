package app.fluff.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.fluff.ui.theme.Fluff
import app.fluff.ui.theme.FluffTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun FluffCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    color: Color = Fluff.Card,
    border: Color? = null,
    padding: PaddingValues = PaddingValues(18.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val shape = MaterialTheme.shapes.large
    Surface(
        modifier = modifier
            .then(if (onClick != null) Modifier.pressScale(source, 0.98f) else Modifier)
            .clip(shape)
            .then(if (onClick != null) Modifier.clickable(source, null, role = Role.Button, onClick = onClick) else Modifier),
        shape = shape,
        color = color,
        contentColor = Fluff.Text,
        border = border?.let { BorderStroke(1.dp, it) },
    ) {
        Column(Modifier.padding(padding), content = content)
    }
}

@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier, color: Color = Fluff.TextFaint) {
    Text(text.uppercase(), modifier = modifier, style = FluffTheme.type.eyebrow, color = color)
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 8.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) {
            Text(
                action, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clip(CircleShape).clickable(onClick = onAction).padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
fun Pill(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Fluff.CardHigh,
    content: Color = Fluff.TextDim,
    icon: ImageVector? = null,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val source = remember { MutableInteractionSource() }
    val bg = if (selected) MaterialTheme.colorScheme.primary else color
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else content
    Row(
        modifier
            .then(if (onClick != null) Modifier.pressScale(source) else Modifier)
            .clip(CircleShape)
            .background(bg)
            .then(if (onClick != null) Modifier.clickable(source, null, role = Role.Button, onClick = onClick) else Modifier)
            .heightIn(min = if (onClick != null) 40.dp else 28.dp)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(16.dp), tint = fg)
        // Pills never wrap: a wrapped chip reads as broken. Callers that may run out of room use FlowRow.
        Text(text, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1, softWrap = false)
    }
}

@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier, unit: String? = null, accent: Color = Fluff.Text, caption: String? = null) {
    FluffCard(modifier, padding = PaddingValues(16.dp)) {
        Eyebrow(label)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            AnimatedNumber(value, FluffTheme.type.number, color = accent)
            if (unit != null) Text(" $unit", style = MaterialTheme.typography.labelLarge, color = Fluff.TextDim, modifier = Modifier.padding(bottom = 4.dp))
        }
        if (caption != null) Text(caption, style = MaterialTheme.typography.bodySmall, color = Fluff.TextFaint)
    }
}

/** Text that rolls vertically when its value changes, like an odometer. */
@Composable
fun AnimatedNumber(value: String, style: androidx.compose.ui.text.TextStyle, modifier: Modifier = Modifier, color: Color = Fluff.Text, shrinkToFit: Boolean = false) {
    AnimatedContent(
        targetState = value,
        transitionSpec = {
            val up = (targetState.filter(Char::isDigit).toLongOrNull() ?: 0) >= (initialState.filter(Char::isDigit).toLongOrNull() ?: 0)
            (slideInVertically(tween(260)) { if (up) it else -it } + fadeIn(tween(260))) togetherWith
                (slideOutVertically(tween(200)) { if (up) -it else it } + fadeOut(tween(200)))
        },
        label = "number",
        modifier = modifier,
    ) {
        if (shrinkToFit) {
            // Shrinks long values (e.g. "132.5" at large font sizes) instead of overflowing into neighbours.
            Text(it, style = style, color = color, maxLines = 1, softWrap = false, autoSize = TextAutoSize.StepBased(minFontSize = 14.sp, maxFontSize = style.fontSize))
        } else {
            Text(it, style = style, color = color)
        }
    }
}

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    color: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary,
    height: Dp = 58.dp,
) {
    val source = remember { MutableInteractionSource() }
    val haptics = LocalHapticFeedback.current
    Row(
        modifier
            .pressScale(source)
            .height(height)
            .clip(CircleShape)
            .background(if (enabled) Brush.horizontalGradient(listOf(color, color.copy(alpha = 0.85f))) else Brush.linearGradient(listOf(Fluff.CardHigh, Fluff.CardHigh)))
            .clickable(source, null, enabled = enabled, role = Role.Button) {
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                onClick()
            }
            .padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = if (enabled) contentColor else Fluff.TextFaint, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.titleMedium, color = if (enabled) contentColor else Fluff.TextFaint)
    }
}

@Composable
fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, color: Color = Fluff.Text) {
    val source = remember { MutableInteractionSource() }
    Row(
        modifier
            .pressScale(source)
            .heightIn(min = 48.dp)
            .clip(CircleShape)
            .border(1.dp, Fluff.Line, CircleShape)
            .clickable(source, null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Icon(icon, null, tint = color, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)) }
        Text(text, style = MaterialTheme.typography.labelLarge, color = color)
    }
}

/** A big − value + control. Holding a button repeats, accelerating. */
@Composable
fun Stepper(
    value: String,
    label: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = Fluff.Text,
    /** Fill the width it is given (e.g. two steppers sharing a row) instead of a fixed 160dp. */
    fill: Boolean = false,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Eyebrow(label)
        Spacer(Modifier.height(6.dp))
        Row(if (fill) Modifier.fillMaxWidth() else Modifier, verticalAlignment = Alignment.CenterVertically) {
            RepeatButton(Icons.Rounded.Remove, "Decrease $label", onMinus)
            AnimatedNumber(
                value, FluffTheme.type.number.copy(textAlign = TextAlign.Center),
                (if (fill) Modifier.weight(1f).widthIn(min = 48.dp) else Modifier.width(72.dp)).padding(horizontal = 4.dp), accent, shrinkToFit = true,
            )
            RepeatButton(Icons.Rounded.Add, "Increase $label", onPlus)
        }
    }
}

@Composable
private fun RepeatButton(icon: ImageVector, description: String, onStep: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val step by rememberUpdatedState(onStep)
    var pressed by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val scale by animateFloatAsState(if (pressed) 0.88f else 1f, Fluff.bouncy(), label = "repeat")
    Box(
        Modifier
            .size(44.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape)
            .background(if (pressed) Fluff.Line else Fluff.CardHigh)
            .semantics {
                contentDescription = description
                role = Role.Button
                onClick { step(); true }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    pressed = true
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    step()
                    // Holding repeats after a short pause, speeding up the longer it is held.
                    val job = scope.launch {
                        delay(450)
                        var d = 120L
                        while (true) { step(); haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick); delay(d); d = (d * 0.88).toLong().coerceAtLeast(40) }
                    }
                    waitForUpOrCancellation()
                    job.cancel()
                    pressed = false
                }
            },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = Fluff.Text) }
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(vertical = 28.dp, horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(18.dp)).background(Fluff.CardHigh), contentAlignment = Alignment.Center) {
            Text("✦", style = MaterialTheme.typography.headlineSmall, color = Fluff.Lime)
        }
        Spacer(Modifier.height(14.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = Fluff.TextDim, textAlign = TextAlign.Center)
        if (action != null && onAction != null) {
            Spacer(Modifier.height(14.dp))
            GhostButton(action, onAction)
        }
    }
}

/** Horizontal meter with a label and value, used for muscle volume and factor impact. */
@Composable
fun Meter(label: String, fraction: Float, valueText: String, modifier: Modifier = Modifier, color: Color = Fluff.Lime, sub: String? = null) {
    val anim = androidx.compose.animation.core.animateFloatAsState(fraction.coerceIn(0f, 1f), Fluff.gentle(), label = "meter")
    Column(modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(valueText, style = MaterialTheme.typography.labelLarge, color = Fluff.TextDim)
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(Fluff.CardHigh)) {
            Box(Modifier.fillMaxWidth(anim.value).height(8.dp).clip(CircleShape).background(Brush.horizontalGradient(listOf(color.copy(alpha = 0.6f), color))))
        }
        if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = Fluff.TextFaint, modifier = Modifier.padding(top = 4.dp))
    }
}

/**
 * Lets a horizontally scrolling row inside a padded column run to the screen edges, so chips scroll under the edge
 * instead of being cut at the content padding. Pair with the same horizontal contentPadding on the row.
 */
fun Modifier.bleed(horizontal: Dp): Modifier = layout { measurable, constraints ->
    val extra = (horizontal * 2).roundToPx()
    val placeable = measurable.measure(constraints.copy(minWidth = constraints.minWidth + extra, maxWidth = constraints.maxWidth + extra))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-horizontal.roundToPx(), 0) }
}

@Composable
fun RowScope.Weighted(content: @Composable () -> Unit) { Box(Modifier.weight(1f)) { content() } }
