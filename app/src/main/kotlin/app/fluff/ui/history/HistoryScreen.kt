package app.fluff.ui.history

import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.fluff.core.model.ActivityType
import app.fluff.core.model.BlockType
import app.fluff.core.model.SetKind
import app.fluff.core.model.UserProfile
import app.fluff.core.readiness.ReadinessBand
import app.fluff.data.db.SessionWithSets
import app.fluff.data.repo.WorkoutSummary
import app.fluff.ui.Fmt
import app.fluff.ui.PlanText
import app.fluff.ui.components.EmptyState
import app.fluff.ui.components.Eyebrow
import app.fluff.ui.components.FluffCard
import app.fluff.ui.components.Meter
import app.fluff.ui.components.Pill
import app.fluff.ui.components.PrimaryButton
import app.fluff.ui.components.StatTile
import app.fluff.ui.theme.Fluff
import java.time.Instant
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HistoryScreen(
    state: HistoryState,
    onOpen: (Long) -> Unit,
    onLog: (ActivityType, Int, Int, Int) -> Unit,
    onDeleteActivity: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var logging by rememberSaveable { mutableStateOf(false) }
    Box(modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 180.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Column(Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(top = 12.dp, bottom = 4.dp)) {
                    Eyebrow("Everything you've done")
                    Text("History", style = MaterialTheme.typography.headlineLarge)
                }
            }
            if (!state.loading && state.items.isEmpty()) {
                item { FluffCard { EmptyState("Nothing here yet", "Finished workouts and activities from your wearables show up here. You can also log a run, ride or match by hand.") } }
            }
            val byMonth = state.items.groupBy { it.date.withDayOfMonth(1) }
            byMonth.forEach { (month, items) ->
                stickyHeader(key = "m$month") {
                    Text(
                        Fmt.month(month), style = MaterialTheme.typography.titleMedium, color = Fluff.TextDim,
                        modifier = Modifier.fillMaxWidth().background(Fluff.Night).padding(vertical = 8.dp),
                    )
                }
                items(items, key = { it.key }) { item ->
                    when (item) {
                        is TimelineItem.Lift -> LiftRow(item, state, Modifier.animateItem()) { onOpen(item.summary.sessionId) }
                        is TimelineItem.Activity -> ActivityRow(item, state, Modifier.animateItem(), onDeleteActivity)
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { logging = true },
            icon = { Icon(Icons.Rounded.Add, null) },
            text = { Text("Log activity") },
            containerColor = Fluff.CardHigh, contentColor = Fluff.Text,
            modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 20.dp, bottom = 96.dp),
        )
    }
    if (logging) {
        ModalBottomSheet(onDismissRequest = { logging = false }, containerColor = Fluff.Ink) {
            LogActivitySheet(state.profile) { t, m, i, d -> onLog(t, m, i, d); logging = false }
        }
    }
}

@Composable
private fun LiftRow(item: TimelineItem.Lift, state: HistoryState, modifier: Modifier, onClick: () -> Unit) {
    val s = item.summary
    val unit = state.profile.unit
    FluffCard(modifier, onClick = onClick, padding = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DateBadge(item.date.dayOfMonth, Fmt.weekday(item.date), item.readiness?.let { Fluff.band(ReadinessBand.forScore(it)) } ?: Fluff.Line)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (item.source == "AI") { Spacer(Modifier.width(6.dp)); Icon(Icons.Rounded.AutoAwesome, "Planned by Claude", tint = Fluff.Violet, modifier = Modifier.size(14.dp)) }
                }
                Text("${Fmt.minutes(s.durationMinutes)} · ${s.workSets} sets · ${Fmt.bigWeight(s.volumeKg, unit)} ${unit.label}", style = MaterialTheme.typography.bodySmall, color = Fluff.TextDim)
            }
            item.readiness?.let { Text("$it", style = MaterialTheme.typography.labelLarge, color = Fluff.band(ReadinessBand.forScore(it))) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActivityRow(item: TimelineItem.Activity, state: HistoryState, modifier: Modifier, onDelete: (String) -> Unit) {
    val a = item.record
    val content = @Composable {
        Row(
            // Same padding and badge size as LiftRow so text columns line up down the timeline.
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Fluff.Ink).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(Fluff.Sky.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                Text(a.type.emoji, style = MaterialTheme.typography.titleLarge)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(a.name?.takeIf { it.isNotBlank() } ?: a.type.label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${Fmt.relativeDay(item.date, state.today)} · ${Fmt.minutes(a.durationMinutes.toInt())} · ${a.source.label}", style = MaterialTheme.typography.bodySmall, color = Fluff.TextFaint)
            }
            Spacer(Modifier.width(8.dp))
            Text("${a.effectiveIntensity.toInt()}/10", style = MaterialTheme.typography.labelMedium, color = Fluff.Sky)
        }
    }
    if (a.source != app.fluff.core.model.DataSource.MANUAL) {
        Box(modifier) { content() }
        return
    }
    val dismiss = rememberSwipeToDismissBoxState()
    SwipeToDismissBox(
        dismiss, modifier = modifier, enableDismissFromStartToEnd = false,
        onDismiss = { if (it == SwipeToDismissBoxValue.EndToStart) onDelete(a.id) },
        backgroundContent = {
            Box(Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)).background(Fluff.Rose.copy(alpha = 0.25f)).padding(end = 20.dp), contentAlignment = Alignment.CenterEnd) {
                Icon(Icons.Rounded.Delete, "Delete", tint = Fluff.Rose)
            }
        },
    ) { content() }
}

@Composable
private fun DateBadge(day: Int, weekday: String, color: androidx.compose.ui.graphics.Color) {
    Column(
        Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(Fluff.CardHigh),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Text(weekday.uppercase(), style = MaterialTheme.typography.labelSmall, color = Fluff.TextFaint)
        Text("$day", style = MaterialTheme.typography.titleMedium)
        Box(Modifier.width(16.dp).height(3.dp).clip(CircleShape).background(color))
    }
}

@Composable
private fun LogActivitySheet(profile: UserProfile, onSave: (ActivityType, Int, Int, Int) -> Unit) {
    val preferred = profile.activities.map { it.type }
    val types = (preferred + ActivityType.SELECTABLE).distinct()
    var type by remember { mutableStateOf(types.first()) }
    var minutes by remember { mutableFloatStateOf(45f) }
    var intensity by remember(type) { mutableFloatStateOf(type.defaultIntensity.toFloat()) }
    var daysAgo by remember { mutableIntStateOf(0) }
    Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding()) {
        Text("Log an activity", style = MaterialTheme.typography.headlineSmall)
        Text("It counts toward training load, so tomorrow's plan accounts for it.", style = MaterialTheme.typography.bodyMedium, color = Fluff.TextDim)
        Spacer(Modifier.height(14.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(types) { t -> Pill("${t.emoji} ${t.label}", selected = t == type, onClick = { type = t }) }
        }
        Spacer(Modifier.height(18.dp))
        Row { Text("Duration", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f)); Text(Fmt.minutes(minutes.toInt()), color = Fluff.Lime) }
        Slider(minutes, { minutes = it }, valueRange = 10f..240f, steps = 45)
        Row { Text("Effort", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f)); Text("${intensity.toInt()}/10 · ${effortLabel(intensity.toInt())}", color = Fluff.Lime) }
        Slider(intensity, { intensity = it }, valueRange = 1f..10f, steps = 8)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0 to "Today", 1 to "Yesterday", 2 to "2 days ago").forEach { (d, l) -> Pill(l, selected = daysAgo == d, onClick = { daysAgo = d }) }
        }
        Spacer(Modifier.height(20.dp))
        PrimaryButton("Save", { onSave(type, minutes.toInt(), intensity.toInt(), daysAgo) }, Modifier.fillMaxWidth())
    }
}

private fun effortLabel(i: Int) = when { i <= 3 -> "easy"; i <= 5 -> "moderate"; i <= 7 -> "hard"; else -> "all-out" }

@Composable
fun SessionDetailScreen(session: SessionWithSets?, summary: WorkoutSummary?, profile: UserProfile, onBack: () -> Unit, onDelete: () -> Unit, modifier: Modifier = Modifier) {
    var confirm by remember { mutableStateOf(false) }
    val unit = profile.unit
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { confirm = true }) { Icon(Icons.Rounded.Delete, "Delete session", tint = Fluff.TextFaint) }
            }
        }
        if (session == null) return@LazyColumn
        item {
            val start = Instant.ofEpochMilli(session.session.startedAt).atZone(ZoneId.systemDefault())
            Eyebrow(Fmt.day(start.toLocalDate()))
            Text(session.session.title, style = MaterialTheme.typography.headlineMedium)
            session.session.notes?.let { Text("“$it”", style = MaterialTheme.typography.bodyMedium, color = Fluff.TextDim, modifier = Modifier.padding(top = 4.dp)) }
        }
        summary?.let { s ->
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile("Time", "${s.durationMinutes}", Modifier.weight(1f), unit = "min")
                    StatTile("Sets", "${s.workSets}", Modifier.weight(1f))
                    StatTile("Volume", Fmt.bigWeight(s.volumeKg, unit), Modifier.weight(1f), unit = unit.label)
                }
            }
        }
        val done = session.sets.filter { it.completedAt != null }
        val groups = done.groupBy { it.exerciseOrder }.toSortedMap().values
        BlockType.entries.forEach { bt ->
            val inBlock = groups.filter { it.first().block == bt.name }
            if (inBlock.isEmpty()) return@forEach
            item(key = bt.name) {
                FluffCard(Modifier.fillMaxWidth()) {
                    Eyebrow(bt.label)
                    inBlock.forEach { sets ->
                        Spacer(Modifier.height(10.dp))
                        Text(PlanText.name(sets.first().exerciseId), style = MaterialTheme.typography.titleSmall)
                        var n = 0
                        sets.sortedBy { it.setIndex }.forEach { st ->
                            val label = if (st.kind == SetKind.WARMUP.name) "W" else "${++n}"
                            Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(label, style = MaterialTheme.typography.labelMedium, color = Fluff.TextFaint, modifier = Modifier.width(22.dp))
                                Text(
                                    listOfNotNull(
                                        st.weightKg?.takeIf { it > 0 }?.let { Fmt.weightWithUnit(it, unit) },
                                        st.reps?.let { "× $it" },
                                        st.durationSec?.takeIf { st.reps == null }?.let { Fmt.duration(it) },
                                        st.rpe?.let { "RPE ${Fmt.rpe(it)}" },
                                    ).joinToString("  "),
                                    style = MaterialTheme.typography.bodyMedium, color = Fluff.TextDim,
                                )
                            }
                        }
                    }
                }
            }
        }
        summary?.takeIf { it.musclesWorked.isNotEmpty() }?.let { s ->
            item {
                FluffCard {
                    Eyebrow("Muscles worked")
                    val max = s.musclesWorked.values.max()
                    s.musclesWorked.entries.sortedByDescending { it.value }.take(6).forEach { (m, v) -> Meter(m.label, (v / max).toFloat(), "${"%.1f".format(v).removeSuffix(".0")} sets") }
                }
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false }, containerColor = Fluff.Ink,
            title = { Text("Delete this session?") },
            text = { Text("Its sets will no longer count toward your progress. This can't be undone.", color = Fluff.TextDim) },
            confirmButton = { TextButton(onClick = { confirm = false; onDelete() }) { Text("Delete", color = Fluff.Rose) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}
