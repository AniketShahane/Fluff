package app.fluff.ui.workout

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.fluff.ui.Fmt
import app.fluff.ui.PlanText
import app.fluff.ui.components.ConfettiBurst
import app.fluff.ui.components.Eyebrow
import app.fluff.ui.components.FluffCard
import app.fluff.ui.components.Meter
import app.fluff.ui.components.PrimaryButton
import app.fluff.ui.components.StatTile
import app.fluff.ui.theme.Fluff
import kotlinx.coroutines.delay

@Composable
fun SummaryScreen(state: SummaryState, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val s = state.summary
    val unit = state.profile.unit
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(80); appeared = true }
    Box(modifier.fillMaxSize().background(Fluff.Night)) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 110.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(top = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    val pulse = rememberInfiniteTransition(label = "trophy")
                    val sc by pulse.animateFloat(1f, 1.08f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "trophyScale")
                    Text(if (s?.records?.isNotEmpty() == true) "🏆" else "💪", style = MaterialTheme.typography.displayLarge, modifier = Modifier.graphicsLayer { scaleX = sc; scaleY = sc })
                    Spacer(Modifier.height(8.dp))
                    Text("Session complete", style = MaterialTheme.typography.headlineLarge)
                    Text(s?.title.orEmpty(), style = MaterialTheme.typography.titleMedium, color = Fluff.TextDim, textAlign = TextAlign.Center)
                }
            }
            if (s != null) {
                item {
                    AnimatedVisibility(appeared, enter = fadeIn(tween(400)) + slideInVertically(Fluff.gentle()) { it / 3 }) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            StatTile("Time", "${s.durationMinutes}", Modifier.weight(1f), unit = "min")
                            StatTile("Sets", "${s.workSets}", Modifier.weight(1f))
                            StatTile("Volume", Fmt.bigWeight(s.volumeKg, unit), Modifier.weight(1f), unit = unit.label)
                        }
                    }
                }
                item { DebriefCard(state) }
                if (s.records.isNotEmpty()) {
                    item {
                        FluffCard(color = Fluff.Amber.copy(alpha = 0.10f), border = Fluff.Amber.copy(alpha = 0.3f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.EmojiEvents, null, tint = Fluff.Amber)
                                Spacer(Modifier.width(8.dp))
                                Text("${s.records.size} personal record${if (s.records.size > 1) "s" else ""}", style = MaterialTheme.typography.titleMedium, color = Fluff.Amber)
                            }
                            Spacer(Modifier.height(8.dp))
                            s.records.forEach { pr ->
                                val value = when (pr.kind) {
                                    app.fluff.core.progress.PersonalRecord.Kind.REPS -> "${pr.newValue.toInt()} reps"
                                    else -> Fmt.weightWithUnit(pr.newValue, unit)
                                }
                                Row(Modifier.padding(vertical = 4.dp)) {
                                    Column(Modifier.weight(1f)) {
                                        Text(PlanText.name(pr.exerciseId), style = MaterialTheme.typography.bodyLarge)
                                        Text(pr.kind.label, style = MaterialTheme.typography.bodySmall, color = Fluff.TextFaint)
                                    }
                                    Text(value, style = MaterialTheme.typography.titleMedium)
                                }
                            }
                        }
                    }
                }
                if (s.musclesWorked.isNotEmpty()) {
                    item {
                        FluffCard {
                            Eyebrow("Muscles worked")
                            Spacer(Modifier.height(6.dp))
                            val max = s.musclesWorked.values.max()
                            s.musclesWorked.entries.sortedByDescending { it.value }.take(6).forEach { (m, v) ->
                                Meter(m.label, (v / max).toFloat(), "${"%.1f".format(v).removeSuffix(".0")} sets", color = Fluff.Lime)
                            }
                        }
                    }
                }
            }
        }
        ConfettiBurst(if (s?.records?.isNotEmpty() == true) s.sessionId else null, originFraction = androidx.compose.ui.geometry.Offset(0.5f, 0.15f))
        Box(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(20.dp)) {
            PrimaryButton("Done", onDone, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun DebriefCard(state: SummaryState) {
    if (!state.debriefLoading && state.debrief == null) return
    val shimmer = rememberInfiniteTransition(label = "debrief")
    val a by shimmer.animateFloat(0.3f, 0.8f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "debriefAlpha")
    FluffCard(color = Fluff.Violet.copy(alpha = 0.10f), border = Fluff.Violet.copy(alpha = 0.25f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.AutoAwesome, null, tint = Fluff.Violet, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Eyebrow("Coach debrief", color = Fluff.Violet)
        }
        Spacer(Modifier.height(8.dp))
        if (state.debrief != null) {
            Text(state.debrief, style = MaterialTheme.typography.bodyLarge)
        } else {
            repeat(3) { i ->
                Box(Modifier.fillMaxWidth(if (i == 2) 0.6f else 1f).padding(vertical = 4.dp).height(12.dp).clip(RoundedCornerShape(6.dp)).background(Fluff.Violet.copy(alpha = a * 0.3f)))
            }
        }
    }
}
