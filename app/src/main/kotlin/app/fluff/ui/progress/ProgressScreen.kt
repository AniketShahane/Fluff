package app.fluff.ui.progress

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.layout.FlowRow
import app.fluff.ui.components.bleed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.fluff.core.model.Muscle
import app.fluff.core.model.MuscleSize
import app.fluff.core.progress.GoalProgress
import app.fluff.core.progress.Loads
import app.fluff.core.progress.StrengthLevel
import app.fluff.core.readiness.ReadinessBand
import app.fluff.ui.Fmt
import app.fluff.ui.PlanText
import app.fluff.ui.components.BarChart
import app.fluff.ui.components.CalendarHeatmap
import app.fluff.ui.components.ChartPoint
import app.fluff.ui.components.EmptyState
import app.fluff.ui.components.Eyebrow
import app.fluff.ui.components.FluffCard
import app.fluff.ui.components.Meter
import app.fluff.ui.components.Pill
import app.fluff.ui.components.PrimaryButton
import app.fluff.ui.components.ProgressRing
import app.fluff.ui.components.RadarChart
import app.fluff.ui.components.Stepper
import app.fluff.ui.components.TrendChart
import app.fluff.ui.theme.Fluff
import app.fluff.ui.theme.FluffTheme
import java.time.LocalDate

data class ProgressActions(
    val selectLift: (String) -> Unit = {},
    val addGoal: (String, Double, Int?) -> Unit = { _, _, _ -> },
    val deleteGoal: (Long) -> Unit = {},
)

private fun levelColor(l: StrengthLevel) = when (l) {
    StrengthLevel.UNTRAINED -> Fluff.TextDim
    StrengthLevel.NOVICE -> Fluff.Sky
    StrengthLevel.INTERMEDIATE -> Fluff.Lime
    StrengthLevel.ADVANCED -> Fluff.Amber
    StrengthLevel.ELITE -> Fluff.Rose
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgressScreen(state: ProgressState, actions: ProgressActions, modifier: Modifier = Modifier) {
    var addGoal by rememberSaveable { mutableStateOf(false) }
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column(Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(top = 12.dp)) {
                Eyebrow(if (state.loading) "" else "${state.totalSessions} sessions logged")
                Text("Progress", style = MaterialTheme.typography.headlineLarge)
            }
        }
        if (!state.loading && state.totalSessions == 0) {
            item { FluffCard { EmptyState("Your story starts with one session", "Finish a workout and your strength score, lift trends and consistency map appear here.") } }
        }
        state.strength?.takeIf { state.totalSessions > 0 }?.let { s ->
            item(key = "strength") { StrengthCard(state) }
            if (state.activityStrength.isNotEmpty()) item(key = "sport") { SportStrength(state) }
        }
        if (state.lifts.isNotEmpty()) item(key = "lifts") { LiftTrend(state, actions) }
        if (!state.loading) item(key = "goals") { Goals(state, actions) { addGoal = true } }
        if (state.totalSessions > 0) {
            item(key = "volume") { Volume(state) }
            item(key = "consistency") { Consistency(state) }
        }
        if (state.readiness.size >= 3) item(key = "readiness") { ReadinessHistory(state) }
    }
    if (addGoal) {
        ModalBottomSheet(onDismissRequest = { addGoal = false }, containerColor = Fluff.Ink) {
            AddGoalSheet(state) { id, kg, weeks -> actions.addGoal(id, kg, weeks); addGoal = false }
        }
    }
}

@Composable
private fun StrengthCard(state: ProgressState) {
    val s = state.strength ?: return
    FluffCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProgressRing(s.overall / 120f, Modifier.size(96.dp), color = levelColor(s.level), strokeWidth = 9.dp) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${s.overall}", style = FluffTheme.type.number)
                    Text("/ 120", style = MaterialTheme.typography.labelSmall, color = Fluff.TextFaint)
                }
            }
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Eyebrow("Strength score")
                Text(s.level.label, style = MaterialTheme.typography.headlineSmall, color = levelColor(s.level))
                Text(
                    if (s.coverage < 0.5) "Based on ${(s.coverage * 100).toInt()}% of movement patterns. Train more patterns for a fuller picture."
                    else "Relative to your bodyweight across the big movement patterns.",
                    style = MaterialTheme.typography.bodySmall, color = Fluff.TextDim,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        val scored = s.patterns
        RadarChart(
            axes = scored.map { it.pattern.label.replace("Horizontal", "Horiz.").replace("Vertical", "Vert.") },
            values = scored.map { (it.score / 100f).coerceAtMost(1.1f) },
            modifier = Modifier.fillMaxWidth().aspectRatio(1.15f),
        )
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            StrengthLevel.entries.drop(1).forEach { l ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(levelColor(l)))
                    Spacer(Modifier.width(4.dp))
                    Text(l.label, style = MaterialTheme.typography.labelSmall, color = Fluff.TextFaint)
                }
            }
        }
    }
}

@Composable
private fun SportStrength(state: ProgressState) {
    Column {
        Eyebrow("Strength for your sports", Modifier.padding(start = 4.dp, bottom = 8.dp))
        // Bleeds to the screen edges so a third card scrolls in from the edge rather than being cut at the padding.
        LazyRow(Modifier.bleed(20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(horizontal = 20.dp)) {
            items(state.activityStrength, key = { it.activity.name }) { a ->
                FluffCard(Modifier.width(168.dp)) {
                    Text(a.activity.emoji, style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(a.activity.label, style = MaterialTheme.typography.titleSmall)
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("${a.score}", style = FluffTheme.type.number, color = levelColor(StrengthLevel.forScore(a.score)))
                        Text(" / 120", style = MaterialTheme.typography.labelSmall, color = Fluff.TextFaint, modifier = Modifier.padding(bottom = 5.dp))
                    }
                    a.limiter?.let { Text("Work on: ${it.label.lowercase()}", style = MaterialTheme.typography.bodySmall, color = Fluff.Amber, maxLines = 2) }
                }
            }
        }
    }
}

@Composable
private fun LiftTrend(state: ProgressState, actions: ProgressActions) {
    val unit = state.profile.unit
    val goal = state.goals.firstOrNull { it.goal.exerciseId == state.selectedLift && !it.achieved }
    FluffCard {
        Eyebrow("Estimated 1-rep max")
        Spacer(Modifier.height(4.dp))
        val current = state.liftSeries.lastOrNull()?.value
        val first = state.liftSeries.firstOrNull()?.value
        Row(verticalAlignment = Alignment.Bottom) {
            AnimatedContent(current, transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(150)) }, label = "e1rm") {
                Text(it?.let { v -> Fmt.weight(v, unit) } ?: "–", style = FluffTheme.type.number)
            }
            Text(" ${unit.label}", style = MaterialTheme.typography.labelLarge, color = Fluff.TextDim, modifier = Modifier.padding(bottom = 4.dp))
            Spacer(Modifier.weight(1f))
            if (current != null && first != null && current - first > 0.1) {
                Pill("+${Fmt.weight(current - first, unit)} ${unit.label}", content = Fluff.Mint, color = Fluff.Mint.copy(alpha = 0.12f))
            }
        }
        Spacer(Modifier.height(10.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.lifts, key = { it.exerciseId }) { l ->
                Pill(l.name, selected = l.exerciseId == state.selectedLift, onClick = { actions.selectLift(l.exerciseId) })
            }
        }
        Spacer(Modifier.height(14.dp))
        TrendChart(
            points = state.liftSeries,
            goal = goal?.goal?.targetKg,
            projection = goal?.projectedDate?.let { ChartPoint(it, goal.goal.targetKg) },
            format = { Fmt.weight(it, unit) },
            description = "Estimated max for ${PlanText.name(state.selectedLift.orEmpty())}",
        )
        goal?.let {
            Spacer(Modifier.height(8.dp))
            Text(goalLine(it, unit), style = MaterialTheme.typography.bodySmall, color = Fluff.Amber)
        }
    }
}

private fun goalLine(g: GoalProgress, unit: app.fluff.core.model.WeightUnit): String = when {
    g.achieved -> "Goal reached. Time for a new one."
    g.projectedDate != null -> "At +${Fmt.weight(g.weeklyGainKg ?: 0.0, unit)} ${unit.label}/week you'll hit ${Fmt.weight(g.goal.targetKg, unit)} around ${Fmt.day(g.projectedDate!!)}." +
        when (g.onTrack) { true -> " On track."; false -> " Behind your target date."; null -> "" }
    else -> "Log a few more sessions to project when you'll hit ${Fmt.weight(g.goal.targetKg, unit)} ${unit.label}."
}

@Composable
private fun Goals(state: ProgressState, actions: ProgressActions, onAdd: () -> Unit) {
    val unit = state.profile.unit
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)) {
            Eyebrow("Goals", Modifier.weight(1f))
            Pill("New goal", icon = Icons.Rounded.Add, onClick = onAdd)
        }
        if (state.goals.isEmpty()) {
            FluffCard { Text("Set a target for a lift and Fluff projects when you'll get there.", style = MaterialTheme.typography.bodyMedium, color = Fluff.TextDim) }
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            state.goals.forEach { g ->
                FluffCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val c = if (g.achieved) Fluff.Mint else Fluff.Amber
                        ProgressRing(g.fraction.toFloat(), Modifier.size(56.dp), color = c, strokeWidth = 6.dp) {
                            Text(if (g.achieved) "✓" else "${(g.fraction * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text("${PlanText.name(g.goal.exerciseId)} · ${Fmt.weightWithUnit(g.goal.targetKg, unit)}", style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(goalLine(g, unit), style = MaterialTheme.typography.bodySmall, color = Fluff.TextDim, maxLines = 3)
                        }
                        IconButton(onClick = { actions.deleteGoal(g.goal.id) }) { Icon(Icons.Rounded.Delete, "Delete goal", tint = Fluff.TextFaint) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Volume(state: ProgressState) {
    var mode by rememberSaveable { mutableIntStateOf(0) }
    val unit = state.profile.unit
    FluffCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Eyebrow("Weekly volume", Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf("Hard sets", "Tonnage").forEachIndexed { i, label ->
                SegmentedButton(selected = mode == i, onClick = { mode = i }, shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(label) }
            }
        }
        Spacer(Modifier.height(12.dp))
        val labels = state.weekly.mapIndexed { i, w -> if (i == state.weekly.lastIndex) "Now" else "${w.weekStart.monthValue}/${w.weekStart.dayOfMonth}" }
        AnimatedContent(mode, label = "volumeMode") { m ->
            if (m == 0) BarChart(state.weekly.map { it.hardSets.toFloat() }, labels, description = "Hard sets per week")
            else BarChart(state.weekly.map { unit.fromKg(it.tonnageKg).toFloat() }, labels, color = Fluff.Sky, format = { Fmt.bigWeight(unit.toKg(it.toDouble()), unit) }, description = "Tonnage per week")
        }
        val week = state.weekly.lastOrNull()
        if (week != null && week.setsByMuscle.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Eyebrow("This week by muscle")
            Text("Most people grow well on 10–20 hard sets per muscle each week.", style = MaterialTheme.typography.bodySmall, color = Fluff.TextFaint)
            Spacer(Modifier.height(4.dp))
            Muscle.entries.filter { it.size == MuscleSize.LARGE || (week.setsByMuscle[it] ?: 0.0) > 0 }
                .sortedByDescending { week.setsByMuscle[it] ?: 0.0 }.take(8).forEach { m ->
                    val sets = week.setsByMuscle[m] ?: 0.0
                    val c = when { sets >= 10 -> Fluff.Mint; sets >= 5 -> Fluff.Lime; else -> Fluff.Amber }
                    Meter(m.label, (sets / 20.0).toFloat(), "${"%.1f".format(sets).removeSuffix(".0")} sets", color = c)
                }
        }
    }
}

@Composable
private fun Consistency(state: ProgressState) {
    FluffCard {
        // FlowRow so the streak pill drops to its own line at large text instead of breaking the title mid-word.
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            Eyebrow("Consistency")
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clip(CircleShape).background(Fluff.Coral.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 6.dp)) {
                Icon(Icons.Rounded.LocalFireDepartment, null, tint = Fluff.Coral, modifier = Modifier.size(16.dp))
                Text(" ${state.streak} week streak", style = MaterialTheme.typography.labelLarge, color = Fluff.Coral, maxLines = 1)
            }
        }
        Text("Strength + other activities", style = MaterialTheme.typography.bodySmall, color = Fluff.TextFaint)
        Spacer(Modifier.height(14.dp))
        CalendarHeatmap(state.heat, state.today)
    }
}

@Composable
private fun ReadinessHistory(state: ProgressState) {
    FluffCard {
        Eyebrow("Readiness, 30 days")
        Spacer(Modifier.height(10.dp))
        val avg = state.readiness.map { it.score }.average()
        TrendChart(
            points = state.readiness.map { ChartPoint(LocalDate.ofEpochDay(it.epochDay), it.score.toDouble()) },
            color = Fluff.band(ReadinessBand.forScore(avg.toInt())),
            height = 150.dp,
            description = "Readiness over 30 days",
        )
    }
}

@Composable
private fun AddGoalSheet(state: ProgressState, onSave: (String, Double, Int?) -> Unit) {
    val unit = state.profile.unit
    var lift by remember { mutableStateOf(state.goalChoices.firstOrNull()) }
    var target by remember(lift) {
        val start = lift?.currentE1rm?.let { unit.fromKg(it) * 1.1 } ?: unit.fromKg(state.profile.bodyWeightKg)
        mutableDoubleStateOf((start / 5).toInt() * 5.0)
    }
    var weeks by remember { mutableStateOf<Int?>(12) }
    Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding()) {
        Text("New goal", style = MaterialTheme.typography.headlineSmall)
        Text("Targets are estimated 1-rep maxes, so you never have to test a true max.", style = MaterialTheme.typography.bodyMedium, color = Fluff.TextDim)
        Spacer(Modifier.height(14.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.goalChoices, key = { it.exerciseId }) { l -> Pill(l.name, selected = l == lift, onClick = { lift = l }) }
        }
        Spacer(Modifier.height(18.dp))
        lift?.currentE1rm?.let { Text("Current estimate: ${Fmt.weightWithUnit(it, unit)}", style = MaterialTheme.typography.bodyMedium, color = Fluff.TextDim, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) }
        Stepper("${target.toInt()}", "target ${unit.label}", { target = (target - 2.5).coerceAtLeast(5.0) }, { target += 2.5 }, Modifier.fillMaxWidth())
        Spacer(Modifier.height(18.dp))
        Eyebrow("By when")
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf<Int?>(8, 12, 16, 24, null).forEach { w -> Pill(w?.let { "$it wk" } ?: "No date", selected = weeks == w, onClick = { weeks = w }) }
        }
        Spacer(Modifier.height(22.dp))
        PrimaryButton("Save goal", { lift?.let { onSave(it.exerciseId, unit.toKg(target), weeks) } }, Modifier.fillMaxWidth(), enabled = lift != null)
    }
}
