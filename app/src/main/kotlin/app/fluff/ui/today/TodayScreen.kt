package app.fluff.ui.today

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.TrendingDown
import androidx.compose.material.icons.rounded.TrendingUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material3.rememberModalBottomSheetState
import app.fluff.core.library.ExerciseLibrary
import app.fluff.core.library.ExerciseVideos
import app.fluff.core.planner.Insight
import app.fluff.ui.exercise.ExerciseDetail
import app.fluff.ui.exercise.InsightLine
import app.fluff.ui.exercise.MovementTag
import app.fluff.core.model.BlockType
import app.fluff.core.model.CheckIn
import app.fluff.core.model.Focus
import app.fluff.core.model.PlanSource
import app.fluff.core.model.WorkoutPlan
import app.fluff.core.readiness.Confidence
import app.fluff.core.readiness.Readiness
import app.fluff.core.readiness.ReadinessBand
import app.fluff.core.readiness.ReadinessFactor
import app.fluff.domain.CoachStatus
import app.fluff.ui.Fmt
import app.fluff.ui.PlanText
import app.fluff.ui.components.Eyebrow
import app.fluff.ui.components.FluffCard
import app.fluff.ui.components.Meter
import app.fluff.ui.components.Pill
import app.fluff.ui.components.PrimaryButton
import app.fluff.ui.components.ReadinessOrb
import app.fluff.ui.components.Sparkline
import app.fluff.ui.theme.Fluff
import app.fluff.ui.theme.FluffTheme
import java.time.LocalDate
import kotlin.math.abs

data class TodayActions(
    val refresh: () -> Unit = {},
    val saveCheckIn: (CheckIn) -> Unit = {},
    val start: () -> Unit = {},
    val resume: () -> Unit = {},
    val openSummary: (Long) -> Unit = {},
    val shorter: () -> Unit = {},
    val longer: () -> Unit = {},
    val easier: () -> Unit = {},
    val harder: () -> Unit = {},
    val shuffle: () -> Unit = {},
    val focus: (Focus?) -> Unit = {},
    val reset: () -> Unit = {},
    val openProfile: () -> Unit = {},
    val startFreestyle: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(state: TodayState, actions: TodayActions, modifier: Modifier = Modifier) {
    var showCheckIn by rememberSaveable { mutableStateOf(false) }
    var showReadiness by rememberSaveable { mutableStateOf(false) }
    var showFocus by rememberSaveable { mutableStateOf(false) }
    var openExercise by rememberSaveable { mutableStateOf<String?>(null) }

    Box(modifier.fillMaxSize()) {
        PullToRefreshBox(isRefreshing = state.syncing, onRefresh = actions.refresh, modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 210.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item(key = "header") { Header(state, actions.refresh) }
                item(key = "orb") { Hero(state) { showReadiness = true } }
                if (state.readiness != null) item(key = "factors") { FactorRow(state.readiness.factors) { showReadiness = true } }
                if (state.coach !is CoachStatus.Idle || state.plan?.fallbackReason != null) item(key = "coach") { CoachBanner(state.coach) }
                state.completed?.let { c -> item(key = "done") { DoneCard(c, state) { actions.openSummary(c.sessionId) } } }
                if (state.checkIn == null && state.completed == null && state.activeSessionId == null) {
                    item(key = "checkin") { CheckInPrompt { showCheckIn = true } }
                }
                state.plan?.let { plan ->
                    item(key = "plan") { PlanCard(plan, state, state.replanning) { openExercise = it } }
                    if (state.activeSessionId == null && state.completed == null) {
                        item(key = "adjust") { AdjustRow(state, actions) { showFocus = true } }
                    }
                    if (state.activeSessionId == null) {
                        item(key = "freestyle") {
                            Text(
                                "Doing your own thing? Log a workout from scratch",
                                style = MaterialTheme.typography.labelLarge, color = Fluff.TextDim, textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().clip(CircleShape).clickable(role = Role.Button, onClick = actions.startFreestyle).padding(12.dp),
                            )
                        }
                    }
                }
                if (state.plan == null && !state.loading) item(key = "noplan") { NoPlan(actions.refresh) }
                if (state.history.size >= 2) item(key = "trend") { ReadinessTrend(state.history, state.today) }
            }
        }
        StartBar(state, actions, Modifier.align(Alignment.BottomCenter))
    }

    if (showCheckIn) {
        ModalBottomSheet(onDismissRequest = { showCheckIn = false }, containerColor = Fluff.Ink) {
            CheckInSheet(initial = state.checkIn) { c -> actions.saveCheckIn(c); showCheckIn = false }
        }
    }
    if (showReadiness && state.readiness != null) {
        ModalBottomSheet(onDismissRequest = { showReadiness = false }, containerColor = Fluff.Ink) {
            ReadinessSheet(state.readiness, onCheckIn = { showReadiness = false; showCheckIn = true })
        }
    }
    if (showFocus) {
        ModalBottomSheet(onDismissRequest = { showFocus = false }, containerColor = Fluff.Ink) {
            FocusSheet(state.adjustments.focusOverride) { actions.focus(it); showFocus = false }
        }
    }
    openExercise?.let { id ->
        ModalBottomSheet(onDismissRequest = { openExercise = null }, containerColor = Fluff.Ink, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            ExerciseDetail(id, state.insights[id].orEmpty())
        }
    }
}

@Composable
private fun Header(state: TodayState, onRefresh: () -> Unit) {
    Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Eyebrow(Fmt.day(state.today))
            val name = state.profile.name.trim().split(" ").firstOrNull().orEmpty()
            Text(
                if (name.isBlank()) Fmt.greeting() else "${Fmt.greeting()}, $name",
                style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        val spin = rememberInfiniteTransition(label = "sync")
        val angle by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "syncAngle")
        IconButton(onClick = onRefresh, enabled = !state.syncing) {
            Icon(Icons.Rounded.Refresh, "Sync wearables", tint = Fluff.TextDim, modifier = Modifier.rotate(if (state.syncing) angle else 0f))
        }
    }
}

@Composable
private fun Hero(state: TodayState, onClick: () -> Unit) {
    val r = state.readiness
    Column(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.clip(CircleShape).clickable(role = Role.Button, onClickLabel = "Explain readiness", onClick = onClick)) {
            ReadinessOrb(score = r?.score, band = r?.band, size = 250.dp)
        }
        Spacer(Modifier.height(10.dp))
        AnimatedContent(r?.band, transitionSpec = { fadeIn(tween(400)) togetherWith fadeOut(tween(200)) }, label = "headline") { band ->
            Text(
                band?.headline ?: "Connect a wearable or do a quick check-in to unlock readiness.",
                style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, color = if (band == null) Fluff.TextDim else Fluff.Text,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
        }
        if (r != null && r.confidence != Confidence.HIGH) {
            Spacer(Modifier.height(4.dp))
            Text(
                if (r.confidence == Confidence.LOW) "Low confidence: not much data yet" else "Building your baseline",
                style = MaterialTheme.typography.bodySmall, color = Fluff.TextFaint,
            )
        }
    }
}

@Composable
private fun FactorRow(factors: List<ReadinessFactor>, onClick: () -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 2.dp)) {
        items(factors, key = { it.kind.name }) { f ->
            val c = when { f.impact >= 3 -> Fluff.Mint; f.impact <= -3 -> Fluff.Rose; else -> Fluff.TextDim }
            Pill(
                text = f.title, icon = if (f.impact >= 0) Icons.Rounded.TrendingUp else Icons.Rounded.TrendingDown,
                content = c, onClick = onClick,
            )
        }
    }
}

@Composable
private fun CoachBanner(status: CoachStatus) {
    val (icon, text, color) = when (status) {
        CoachStatus.Thinking -> Triple(Icons.Rounded.AutoAwesome, "Claude is tailoring today's session…", Fluff.Violet)
        is CoachStatus.Refined -> Triple(Icons.Rounded.AutoAwesome, "Session refined by your Claude coach", Fluff.Violet)
        is CoachStatus.Fallback -> Triple(Icons.Rounded.Bolt, "Using the on-device plan. ${status.reason}", Fluff.Amber)
        CoachStatus.Idle -> return
    }
    val pulse = rememberInfiniteTransition(label = "coach")
    val a by pulse.animateFloat(0.4f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "coachPulse")
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(color.copy(alpha = 0.12f)).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = color.copy(alpha = if (status is CoachStatus.Thinking) a else 1f), modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

@Composable
private fun CheckInPrompt(onClick: () -> Unit) {
    FluffCard(onClick = onClick, color = Fluff.CardHigh) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("☀️", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("How do you feel?", style = MaterialTheme.typography.titleMedium)
                Text("A 10-second check-in sharpens today's plan.", style = MaterialTheme.typography.bodyMedium, color = Fluff.TextDim)
            }
            Icon(Icons.Rounded.KeyboardArrowDown, null, tint = Fluff.TextFaint, modifier = Modifier.rotate(-90f))
        }
    }
}

@Composable
private fun DoneCard(c: CompletedToday, state: TodayState, onClick: () -> Unit) {
    FluffCard(onClick = onClick, color = Fluff.Mint.copy(alpha = 0.12f), border = Fluff.Mint.copy(alpha = 0.3f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.CheckCircle, null, tint = Fluff.Mint, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Done for today", style = MaterialTheme.typography.titleMedium)
                Text(
                    "${c.title} · ${Fmt.minutes(c.minutes)} · ${c.sets} sets · ${Fmt.bigWeight(c.volumeKg, state.profile.unit)} ${state.profile.unit.label}",
                    style = MaterialTheme.typography.bodyMedium, color = Fluff.TextDim,
                )
            }
        }
    }
}

@Composable
private fun PlanCard(plan: WorkoutPlan, state: TodayState, replanning: Boolean, onOpen: (String) -> Unit) {
    var expanded by rememberSaveable(plan.epochDay) { mutableStateOf(false) }
    val shimmer = rememberInfiniteTransition(label = "replan")
    val alpha by shimmer.animateFloat(0.45f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "replanAlpha")
    val bandColor = Fluff.band(state.readiness?.band ?: ReadinessBand.STEADY)
    FluffCard(Modifier.animateContentSize(Fluff.gentle())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Eyebrow("Today's session", Modifier.weight(1f))
            SourceBadge(plan.source)
        }
        Spacer(Modifier.height(8.dp))
        AnimatedContent(plan.title, transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(150)) }, label = "title") {
            Text(it, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.then(if (replanning) Modifier.semantics { contentDescription = "Updating plan" } else Modifier))
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(Fmt.minutes(plan.estimatedMinutes), icon = Icons.Rounded.Timer)
            Pill(plan.focus.label)
            Pill("${plan.mainExercises.size} " + if (plan.focus == Focus.RECOVERY) "moves" else "lifts")
        }
        plan.coachNote?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(12.dp))
            Text("“$it”", style = MaterialTheme.typography.bodyMedium, color = Fluff.Violet)
        }
        if (plan.rationale.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Fluff.Ink).padding(horizontal = 14.dp, vertical = 12.dp)) {
                Eyebrow("Why this plan", color = bandColor)
                Spacer(Modifier.height(6.dp))
                plan.rationale.take(3).forEach { line ->
                    Row(Modifier.padding(vertical = 3.dp)) {
                        Box(Modifier.padding(top = 7.dp).size(5.dp).clip(CircleShape).background(bandColor))
                        Spacer(Modifier.width(10.dp))
                        Text(line, style = MaterialTheme.typography.bodyMedium, color = Fluff.TextDim)
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Column(Modifier.then(if (replanning) Modifier.graphicsAlpha(alpha) else Modifier)) {
            BlockType.entries.forEach { bt ->
                val block = plan.block(bt) ?: return@forEach
                if (block.exercises.isEmpty()) return@forEach
                TimelineBlock(bt, expanded || bt == BlockType.MAIN, last = bt == BlockType.COOLDOWN, count = block.exercises.size) {
                    block.exercises.forEach { pe ->
                        if (bt == BlockType.MAIN) {
                            MainExerciseRow(pe.exerciseId, PlanText.summary(pe, state.profile.unit), state.insights[pe.exerciseId].orEmpty()) { onOpen(pe.exerciseId) }
                        } else {
                            CompactRow(pe.exerciseId, PlanText.summary(pe, state.profile.unit)) { onOpen(pe.exerciseId) }
                        }
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { expanded = !expanded }.padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (expanded) "Hide warm-up & cool-down" else "Show warm-up & cool-down", style = MaterialTheme.typography.labelLarge, color = Fluff.TextDim)
            Icon(Icons.Rounded.KeyboardArrowDown, null, tint = Fluff.TextDim, modifier = Modifier.rotate(if (expanded) 180f else 0f))
        }
    }
}

private fun Modifier.graphicsAlpha(a: Float) = graphicsLayer { alpha = a }

@Composable
private fun SourceBadge(source: PlanSource) {
    val ai = source == PlanSource.AI
    Row(
        Modifier.clip(CircleShape).background(if (ai) Fluff.Violet.copy(alpha = 0.16f) else Fluff.CardHigh).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (ai) Icons.Rounded.AutoAwesome else Icons.Rounded.Bolt, null, tint = if (ai) Fluff.Violet else Fluff.TextDim, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(source.label, style = MaterialTheme.typography.labelMedium, color = if (ai) Fluff.Violet else Fluff.TextDim)
    }
}

@Composable
private fun TimelineBlock(type: BlockType, expanded: Boolean, last: Boolean, count: Int, rows: @Composable () -> Unit) {
    val color = when (type) { BlockType.WARMUP -> Fluff.Amber; BlockType.MAIN -> Fluff.Lime; BlockType.COOLDOWN -> Fluff.Sky }
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(18.dp).fillMaxHeight()) {
            Box(Modifier.padding(top = 5.dp).size(10.dp).clip(CircleShape).background(color))
            if (!last) Box(Modifier.width(2.dp).weight(1f).background(Brush.verticalGradient(listOf(color.copy(alpha = 0.6f), Fluff.Line))))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f).padding(bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(type.label, style = MaterialTheme.typography.titleSmall, color = color, modifier = Modifier.weight(1f))
                Text("$count", style = MaterialTheme.typography.labelMedium, color = Fluff.TextFaint)
            }
            AnimatedVisibility(expanded, enter = expandVertically(Fluff.gentle()) + fadeIn(), exit = shrinkVertically(Fluff.gentle()) + fadeOut()) {
                Column(Modifier.padding(top = 4.dp)) { rows() }
            }
        }
    }
}

/** A main lift: what to do, what kind of movement it is, and the reasons that matter most. */
@Composable
private fun MainExerciseRow(exerciseId: String, summary: String, insights: List<Insight>, onClick: () -> Unit) {
    val ex = ExerciseLibrary[exerciseId]
    Column(
        Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(14.dp)).clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(PlanText.name(exerciseId), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(8.dp))
            ex?.let { MovementTag(it.movementType) }
            Spacer(Modifier.weight(1f))
            if (ExerciseVideos[exerciseId] != null) Icon(Icons.Rounded.PlayCircle, "Form video", tint = Fluff.TextFaint, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.height(2.dp))
        Text(summary, style = MaterialTheme.typography.labelMedium, color = Fluff.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (insights.isNotEmpty()) Spacer(Modifier.height(4.dp))
        insights.take(2).forEach { InsightLine(it, maxLines = 1) }
    }
}

@Composable
private fun CompactRow(exerciseId: String, summary: String, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 4.dp, vertical = 6.dp),
    ) {
        Text(PlanText.name(exerciseId), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(summary, style = MaterialTheme.typography.bodySmall, color = Fluff.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun AdjustRow(state: TodayState, actions: TodayActions, onFocus: () -> Unit) {
    val a = state.adjustments
    Column {
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Eyebrow("Not feeling it? Adjust", Modifier.weight(1f))
            if (a != app.fluff.core.model.PlanAdjustments()) {
                Text("Reset", style = MaterialTheme.typography.labelLarge, color = Fluff.Lime, modifier = Modifier.clip(CircleShape).clickable(onClick = actions.reset).padding(horizontal = 8.dp, vertical = 4.dp))
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Pill("Shorter", icon = Icons.Rounded.Remove, onClick = actions.shorter) }
            item { Pill("Longer", icon = Icons.Rounded.Add, onClick = actions.longer) }
            item { Pill("Easier", icon = Icons.Rounded.TrendingDown, selected = a.intensityShift < 0, onClick = actions.easier) }
            item { Pill("Harder", icon = Icons.Rounded.TrendingUp, selected = a.intensityShift > 0, onClick = actions.harder) }
            item { Pill(a.focusOverride?.label ?: "Change focus", selected = a.focusOverride != null, onClick = onFocus) }
            item { Pill("Shuffle", icon = Icons.Rounded.Shuffle, onClick = actions.shuffle) }
        }
    }
}

@Composable
private fun NoPlan(onRetry: () -> Unit) {
    app.fluff.ui.components.EmptyState("No plan yet", "Pull down to sync and build today's session.", action = "Build plan", onAction = onRetry)
}

@Composable
private fun ReadinessTrend(history: List<Readiness>, today: LocalDate) {
    FluffCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Eyebrow("Last 2 weeks")
                val avg = history.map { it.score }.average().toInt()
                Text("Average readiness $avg", style = MaterialTheme.typography.titleMedium)
            }
        }
        Spacer(Modifier.height(12.dp))
        val byDay = history.associateBy { it.epochDay }
        val values = (13 downTo 0).map { byDay[today.toEpochDay() - it]?.score?.toFloat() }
        Sparkline(values, Modifier.fillMaxWidth().height(56.dp), color = Fluff.TextDim, colorFor = { Fluff.band(ReadinessBand.forScore(it.toInt())) })
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Text(Fmt.weekday(today.minusDays(13)), style = MaterialTheme.typography.labelSmall, color = Fluff.TextFaint, modifier = Modifier.weight(1f))
            Text("Today", style = MaterialTheme.typography.labelSmall, color = Fluff.TextFaint)
        }
    }
}

@Composable
private fun StartBar(state: TodayState, actions: TodayActions, modifier: Modifier) {
    val plan = state.plan ?: return
    if (state.completed != null && state.activeSessionId == null) return
    Box(
        modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Fluff.Night.copy(alpha = 0.92f), Fluff.Night)))
            .navigationBarsPadding()
            .padding(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 12.dp),
    ) {
        if (state.activeSessionId != null) {
            PrimaryButton("Resume workout", actions.resume, Modifier.fillMaxWidth(), icon = Icons.Rounded.PlayArrow)
        } else {
            val color = state.readiness?.band?.let(Fluff::band) ?: Fluff.Lime
            PrimaryButton(
                if (plan.focus == Focus.RECOVERY) "Start recovery · ${plan.estimatedMinutes} min" else "Start workout · ${plan.estimatedMinutes} min",
                actions.start, Modifier.fillMaxWidth(), icon = Icons.Rounded.PlayArrow, color = color, enabled = !state.replanning,
            )
        }
    }
}

// ───────── Sheets ─────────

@Composable
fun CheckInSheet(initial: CheckIn?, onSave: (CheckIn) -> Unit) {
    var sleep by remember { mutableIntStateOf(initial?.sleepQuality ?: 3) }
    var energy by remember { mutableIntStateOf(initial?.energy ?: 3) }
    var soreness by remember { mutableIntStateOf(initial?.soreness ?: 2) }
    var stress by remember { mutableIntStateOf(initial?.stress ?: 2) }
    Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding()) {
        Text("Morning check-in", style = MaterialTheme.typography.headlineSmall)
        Text("Your answers blend with wearable data.", style = MaterialTheme.typography.bodyMedium, color = Fluff.TextDim)
        Spacer(Modifier.height(18.dp))
        Scale("Sleep quality", listOf("😫", "😕", "😐", "🙂", "😴"), listOf("Awful", "Poor", "Okay", "Good", "Great"), sleep) { sleep = it }
        Scale("Energy", listOf("🪫", "😮‍💨", "😐", "⚡", "🚀"), listOf("Drained", "Low", "Normal", "Good", "Buzzing"), energy) { energy = it }
        Scale("Soreness", listOf("✨", "🙂", "😬", "🥴", "🤕"), listOf("None", "A little", "Some", "Very", "Wrecked"), soreness) { soreness = it }
        Scale("Stress", listOf("😌", "🙂", "😐", "😣", "🤯"), listOf("Calm", "Fine", "Some", "High", "Maxed"), stress) { stress = it }
        Spacer(Modifier.height(10.dp))
        PrimaryButton("Save check-in", { onSave(CheckIn(sleep, energy, soreness, stress)) }, Modifier.fillMaxWidth())
    }
}

@Composable
private fun Scale(title: String, emoji: List<String>, labels: List<String>, value: Int, onChange: (Int) -> Unit) {
    Column(Modifier.padding(bottom = 16.dp)) {
        Row {
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(labels[value - 1], style = MaterialTheme.typography.labelLarge, color = Fluff.Lime)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            emoji.forEachIndexed { i, e ->
                val selected = value == i + 1
                val scale by androidx.compose.animation.core.animateFloatAsState(if (selected) 1.12f else 1f, Fluff.bouncy(), label = "emoji")
                Box(
                    Modifier.weight(1f).height(52.dp)
                        .androidxScale(scale)
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (selected) Fluff.Lime.copy(alpha = 0.18f) else Fluff.Card)
                        .clickable(role = Role.RadioButton, onClickLabel = labels[i]) { onChange(i + 1) }
                        .semantics { contentDescription = "$title: ${labels[i]}" },
                    contentAlignment = Alignment.Center,
                ) { Text(e, style = MaterialTheme.typography.titleLarge) }
            }
        }
    }
}

private fun Modifier.androidxScale(s: Float) = graphicsLayer { scaleX = s; scaleY = s }

@Composable
private fun ReadinessSheet(r: Readiness, onCheckIn: () -> Unit) {
    Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ReadinessOrb(r.score, r.band, size = 96.dp, showLabel = false)
            Spacer(Modifier.width(16.dp))
            Column {
                Text("${r.band.label} · ${r.score}", style = MaterialTheme.typography.headlineSmall, color = Fluff.band(r.band))
                Text(r.band.headline, style = MaterialTheme.typography.bodyMedium, color = Fluff.TextDim)
            }
        }
        Spacer(Modifier.height(18.dp))
        Eyebrow("What moved your score")
        Spacer(Modifier.height(6.dp))
        r.factors.sortedByDescending { abs(it.impact) }.forEach { f ->
            val c = if (f.impact >= 0) Fluff.Mint else Fluff.Rose
            Meter(f.title, abs(f.impact) / 20f, (if (f.impact >= 0) "+" else "−") + abs(f.impact), color = c, sub = f.detail)
        }
        Spacer(Modifier.height(12.dp))
        Eyebrow("What that means today")
        Spacer(Modifier.height(6.dp))
        val p = r.prescription
        Text(
            buildString {
                append(if (p.recoverySession) "A recovery session: mobility, easy cardio and breathing. " else "Volume at ${(p.volumeMultiplier * 100).toInt()}% with effort capped at RPE ${Fmt.rpe(p.rpeCap)}. ")
                if (!p.recoverySession) append(if (p.allowTopSets) "Heavy top sets are on the table. " else "No heavy top sets today. ")
                append("Warm-up of about ${p.warmupMinutes} minutes.")
                r.acwr?.let { append(" Training load ratio ${"%.2f".format(it)} (sweet spot 0.8–1.3).") }
            },
            style = MaterialTheme.typography.bodyMedium, color = Fluff.TextDim,
        )
        Spacer(Modifier.height(18.dp))
        app.fluff.ui.components.GhostButton("Update check-in", onCheckIn, Modifier.fillMaxWidth())
    }
}

@Composable
private fun FocusSheet(current: Focus?, onPick: (Focus?) -> Unit) {
    Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding()) {
        Text("Change focus", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(14.dp))
        (listOf<Focus?>(null) + Focus.entries).forEach { f ->
            val selected = f == current
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                    .background(if (selected) Fluff.Lime.copy(alpha = 0.14f) else Color.Transparent)
                    .clickable(role = Role.RadioButton) { onPick(f) }
                    .padding(horizontal = 14.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(f?.label ?: "Let Fluff decide", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                if (selected) Icon(Icons.Rounded.CheckCircle, null, tint = Fluff.Lime)
            }
        }
    }
}
