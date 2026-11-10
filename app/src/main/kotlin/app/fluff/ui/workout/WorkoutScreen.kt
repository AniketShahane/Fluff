package app.fluff.ui.workout

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material3.rememberModalBottomSheetState
import app.fluff.ui.exercise.ExerciseDetail
import app.fluff.ui.exercise.ExercisePicker
import app.fluff.ui.exercise.InsightLine
import app.fluff.ui.exercise.MovementTag
import app.fluff.core.model.BlockType
import app.fluff.core.model.Exercise
import app.fluff.core.model.SetKind
import app.fluff.data.db.SetEntity
import app.fluff.ui.Fmt
import app.fluff.ui.components.ConfettiBurst
import app.fluff.ui.components.Eyebrow
import app.fluff.ui.components.FluffCard
import app.fluff.ui.components.GhostButton
import app.fluff.ui.components.Pill
import app.fluff.ui.components.PrimaryButton
import app.fluff.ui.components.ProgressRing
import app.fluff.ui.components.Stepper
import app.fluff.ui.theme.Fluff
import app.fluff.ui.theme.FluffTheme
import kotlinx.coroutines.delay

data class WorkoutActions(
    val minimize: () -> Unit = {},
    val select: (Long) -> Unit = {},
    val weight: (Int) -> Unit = {},
    val reps: (Int) -> Unit = {},
    val duration: (Int) -> Unit = {},
    val rpe: (Double?) -> Unit = {},
    val log: () -> Unit = {},
    val undo: (SetEntity) -> Unit = {},
    val skip: (SetEntity) -> Unit = {},
    val skipExercise: (ExerciseGroup) -> Unit = {},
    val addSet: (ExerciseGroup) -> Unit = {},
    val alternatives: (ExerciseGroup) -> List<Exercise> = { emptyList() },
    val swap: (ExerciseGroup, Exercise) -> Unit = { _, _ -> },
    val restAdd: (Int) -> Unit = {},
    val restSkip: () -> Unit = {},
    val finish: (Double?, String?) -> Unit = { _, _ -> },
    val discard: () -> Unit = {},
    val consumePr: () -> Unit = {},
    val consumeToast: () -> Unit = {},
    val edit: (Long) -> Unit = {},
    val cancelEdit: () -> Unit = {},
    val library: () -> List<Exercise> = { emptyList() },
    val addExercise: (Exercise) -> Unit = {},
)

private fun blockColor(b: BlockType) = when (b) { BlockType.WARMUP -> Fluff.Amber; BlockType.MAIN -> Fluff.Lime; BlockType.COOLDOWN -> Fluff.Sky }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutScreen(state: WorkoutState, actions: WorkoutActions, modifier: Modifier = Modifier) {
    val view = LocalView.current
    DisposableEffect(Unit) { view.keepScreenOn = true; onDispose { view.keepScreenOn = false } }
    BackHandler(onBack = actions.minimize)

    var showFinish by rememberSaveable { mutableStateOf(false) }
    var swapFor by remember { mutableStateOf<ExerciseGroup?>(null) }
    var formFor by rememberSaveable { mutableStateOf<String?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.toast) {
        state.toast?.let { snackbar.showSnackbar(it); actions.consumeToast() }
    }

    Box(modifier.fillMaxSize().background(Fluff.Night)) {
        Column(Modifier.fillMaxSize()) {
            TopBar(state, actions.minimize) { showFinish = true }
            val listState = rememberLazyListState()
            // Keep the focus card in view whenever the user moves on to a new exercise.
            val currentOrder = state.currentGroup?.order
            LaunchedEffect(currentOrder) { if (currentOrder != null && listState.firstVisibleItemIndex > 0) listState.animateScrollToItem(0) }
            LazyColumn(
                Modifier.weight(1f),
                state = listState,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "focus") {
                    val g = state.currentGroup
                    if (g == null && state.groups.isEmpty() && !state.loading) EmptyWorkout { adding = true } else if (g == null) AllDone(state) { showFinish = true } else FocusCard(state, g, actions, onForm = { formFor = g.exerciseId }) { swapFor = g }
                }
                state.coachNote?.takeIf { it.isNotBlank() }?.let { note ->
                    item(key = "note") { Text("Coach: $note", style = MaterialTheme.typography.bodyMedium, color = Fluff.Violet, modifier = Modifier.padding(horizontal = 6.dp)) }
                }
                items(state.groups, key = { "g${it.order}" }) { g ->
                    GroupRow(g, state, actions, Modifier.animateItem()) { swapFor = g }
                }
                item(key = "add") {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 4.dp).clip(RoundedCornerShape(18.dp)).border(1.dp, Fluff.Line, RoundedCornerShape(18.dp))
                            .clickable(role = Role.Button) { adding = true }.padding(vertical = 14.dp),
                        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Add, null, tint = Fluff.Lime, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Add an exercise", style = MaterialTheme.typography.labelLarge, color = Fluff.Lime)
                    }
                }
            }
        }

        // Rest timer overlay.
        AnimatedVisibility(
            visible = state.rest != null,
            enter = fadeIn(tween(220)) + scaleIn(initialScale = 0.92f),
            exit = fadeOut(tween(180)) + scaleOut(targetScale = 0.96f),
        ) {
            state.rest?.let { RestOverlay(it, state.settings.hapticsEnabled, actions) }
        }

        ConfettiBurst(state.pr?.id)
        AnimatedVisibility(
            visible = state.pr != null,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            state.pr?.let { PrBanner(it, state) }
        }
        LaunchedEffect(state.pr?.id) { if (state.pr != null) { delay(3200); actions.consumePr() } }

        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp))
    }

    if (showFinish) FinishDialog(state, onDismiss = { showFinish = false }, onFinish = { r, n -> showFinish = false; actions.finish(r, n) }, onDiscard = { showFinish = false; actions.discard() })
    swapFor?.let { g ->
        ModalBottomSheet(onDismissRequest = { swapFor = null }, containerColor = Fluff.Ink) {
            SwapSheet(g, actions.alternatives(g)) { ex -> actions.swap(g, ex); swapFor = null }
        }
    }
    formFor?.let { id ->
        ModalBottomSheet(onDismissRequest = { formFor = null }, containerColor = Fluff.Ink, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            ExerciseDetail(id, state.insights[id].orEmpty())
        }
    }
    if (adding) {
        val options = remember { actions.library() }
        ModalBottomSheet(onDismissRequest = { adding = false }, containerColor = Fluff.Ink, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            ExercisePicker("Add an exercise", "It goes after your main work, with a starting weight from your history.", options, onPick = { actions.addExercise(it); adding = false })
        }
    }
}

@Composable
private fun TopBar(state: WorkoutState, onMinimize: () -> Unit, onFinish: () -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    val progress by animateFloatAsState(if (state.totalCount == 0) 0f else state.doneCount / state.totalCount.toFloat(), Fluff.gentle(), label = "progress")
    Column(Modifier.windowInsetsPadding(WindowInsets.statusBars)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onMinimize) { Icon(Icons.Rounded.KeyboardArrowDown, "Minimise workout") }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(state.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (state.startedAtMs > 0) Fmt.clock((now - state.startedAtMs) / 1000) else "",
                    style = FluffTheme.type.numberSmall, color = Fluff.Lime,
                )
            }
            TextButton(onClick = onFinish, enabled = !state.finishing) { Text("Finish", color = Fluff.Lime, fontWeight = FontWeight.Bold) }
        }
        LinearProgressIndicator(
            progress = { progress }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(4.dp).clip(CircleShape),
            color = Fluff.Lime, trackColor = Fluff.Card, strokeCap = StrokeCap.Round, gapSize = 0.dp, drawStopIndicator = {},
        )
    }
}

@Composable
private fun FocusCard(state: WorkoutState, g: ExerciseGroup, actions: WorkoutActions, onForm: () -> Unit, onSwap: () -> Unit) {
    val set = state.current ?: return
    val d = state.draft
    val unit = state.profile.unit
    val color = blockColor(g.block)
    val warm = set.kind == SetKind.WARMUP.name
    val timed = set.targetDurationSec != null && set.targetReps == null
    var menu by remember { mutableStateOf(false) }

    FluffCard(color = Fluff.Card, border = color.copy(alpha = 0.25f), padding = PaddingValues(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val idx = g.sets.indexOfFirst { it.id == set.id } + 1
            Eyebrow(
                if (state.editing) "Editing set $idx of ${g.sets.size}" else "${g.block.label} · Set $idx of ${g.sets.size}" + if (warm && g.block == BlockType.MAIN) " · ramp" else "",
                Modifier.weight(1f), color = if (state.editing) Fluff.Amber else color,
            )
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(32.dp)) { Icon(Icons.Rounded.MoreHoriz, "More options", tint = Fluff.TextDim) }
                DropdownMenu(menu, onDismissRequest = { menu = false }, containerColor = Fluff.CardHigh) {
                    DropdownMenuItem(text = { Text("Swap exercise") }, leadingIcon = { Icon(Icons.Rounded.SwapHoriz, null) }, onClick = { menu = false; onSwap() })
                    DropdownMenuItem(text = { Text("Add a set") }, leadingIcon = { Icon(Icons.Rounded.Add, null) }, onClick = { menu = false; actions.addSet(g) })
                    DropdownMenuItem(text = { Text("Skip this set") }, leadingIcon = { Icon(Icons.Rounded.SkipNext, null) }, onClick = { menu = false; actions.skip(set) })
                    DropdownMenuItem(text = { Text("Skip exercise") }, leadingIcon = { Icon(Icons.Rounded.Close, null) }, onClick = { menu = false; actions.skipExercise(g) })
                }
            }
        }
        AnimatedContent(
            g.name,
            transitionSpec = { (slideInHorizontally { it / 3 } + fadeIn()) togetherWith (slideOutHorizontally { -it / 3 } + fadeOut()) },
            label = "exercise",
        ) { name -> Text(name, style = MaterialTheme.typography.headlineMedium) }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            g.exercise?.let { MovementTag(it.movementType) }
            Spacer(Modifier.weight(1f))
            Row(
                Modifier.clip(CircleShape).background(Fluff.CardHigh).clickable(role = Role.Button, onClick = onForm).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.PlayCircle, null, tint = Fluff.Text, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Form", style = MaterialTheme.typography.labelLarge)
            }
        }
        g.exercise?.cue?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium, color = Fluff.TextDim)
        }
        Spacer(Modifier.height(14.dp))
        SetDots(g, set.id, color, actions.select, actions.edit)
        Spacer(Modifier.height(14.dp))
        Text(targetLine(set, unit), style = MaterialTheme.typography.labelLarge, color = Fluff.TextDim)
        val why = state.insights[g.exerciseId].orEmpty()
        if (why.isNotEmpty() && !warm && !state.editing) {
            Spacer(Modifier.height(6.dp))
            why.take(2).forEach { InsightLine(it, maxLines = 2) }
        }
        Spacer(Modifier.height(16.dp))

        if (timed) {
            TimedControl(d?.durationSec ?: set.targetDurationSec ?: 30, color, actions.duration, actions.log, state.settings.hapticsEnabled)
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (g.exercise?.isLoaded == true) {
                    val bodyweight = g.exercise.loadType == app.fluff.core.model.LoadType.BODYWEIGHT
                    Stepper(
                        d?.weight?.let { Fmt.weight(unit.toKg(it), unit) } ?: "0",
                        if (bodyweight) "+${unit.label}" else if (g.exercise.loadType == app.fluff.core.model.LoadType.PER_HAND) "${unit.label} each" else unit.label,
                        { actions.weight(-1) }, { actions.weight(1) }, Modifier.weight(1f), fill = true,
                    )
                }
                Stepper("${d?.reps ?: 0}", "reps", { actions.reps(-1) }, { actions.reps(1) }, Modifier.weight(1f), fill = true)
            }
            if (!warm && g.block == BlockType.MAIN) {
                Spacer(Modifier.height(16.dp))
                RpePicker(d?.rpe, actions.rpe)
            }
            Spacer(Modifier.height(18.dp))
            if (state.editing) {
                PrimaryButton("Save changes", actions.log, Modifier.fillMaxWidth(), icon = Icons.Rounded.Check, color = Fluff.Amber)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { actions.undo(set) }) { Text("Clear this set", color = Fluff.Rose) }
                    TextButton(onClick = actions.cancelEdit) { Text("Cancel", color = Fluff.TextDim) }
                }
            } else {
                PrimaryButton("Log set", actions.log, Modifier.fillMaxWidth(), icon = Icons.Rounded.Check, color = color)
            }
        }
    }
}

private fun targetLine(set: SetEntity, unit: app.fluff.core.model.WeightUnit): String = buildList {
    set.targetReps?.let { add("$it reps") }
    set.targetDurationSec?.let { add(Fmt.duration(it)) }
    set.targetWeightKg?.let { add(Fmt.weightWithUnit(it, unit)) }
    set.targetRpe?.let { add("RPE ${Fmt.rpe(it)}") }
    if (set.restSec > 0) add("rest ${Fmt.duration(set.restSec)}")
}.joinToString(" · ", prefix = "Target  ")

@Composable
private fun SetDots(g: ExerciseGroup, currentId: Long, color: Color, onSelect: (Long) -> Unit, onEdit: (Long) -> Unit) {
    val pulse = rememberInfiniteTransition(label = "dot")
    val s by pulse.animateFloat(1f, 1.18f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "dotScale")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        g.sets.forEachIndexed { i, set ->
            val warm = set.kind == SetKind.WARMUP.name
            val size = if (warm) 18.dp else 26.dp
            val current = set.id == currentId
            val fill by animateColorAsState(
                when { set.completedAt != null -> color; set.skipped -> Fluff.Line; else -> Color.Transparent }, label = "dotFill",
            )
            Box(
                Modifier.size(size).scale(if (current) s else 1f).clip(CircleShape).background(fill)
                    .border(2.dp, if (current) color else Fluff.Line, CircleShape)
                    .clickable(enabled = !set.skipped, role = Role.Button) { if (set.completedAt != null) onEdit(set.id) else onSelect(set.id) }
                    .semantics { contentDescription = if (set.completedAt != null) "Edit set ${i + 1}" else "Set ${i + 1}"; stateDescription = when { set.completedAt != null -> "done"; set.skipped -> "skipped"; current -> "current"; else -> "to do" } },
                contentAlignment = Alignment.Center,
            ) {
                if (set.completedAt != null) Icon(Icons.Rounded.Check, null, tint = Fluff.Night, modifier = Modifier.size(size * 0.6f))
            }
        }
    }
}

private val RPE_VALUES = listOf(6.0, 7.0, 7.5, 8.0, 8.5, 9.0, 9.5, 10.0)

@Composable
private fun RpePicker(value: Double?, onPick: (Double?) -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Eyebrow("How hard? RPE", Modifier.weight(1f))
            Text(value?.let { rirText(it) } ?: "Tap to rate", style = MaterialTheme.typography.labelMedium, color = Fluff.TextDim)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            RPE_VALUES.forEach { v ->
                val selected = value == v
                val c = when { v >= 9.5 -> Fluff.Rose; v >= 8.5 -> Fluff.Coral; v >= 7.5 -> Fluff.Amber; else -> Fluff.Mint }
                val bg by animateColorAsState(if (selected) c else Fluff.CardHigh, label = "rpe")
                Box(
                    Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(12.dp)).background(bg)
                        .clickable(role = Role.RadioButton) { onPick(if (selected) null else v) }
                        .semantics { contentDescription = "RPE ${Fmt.rpe(v)}, ${rirText(v)}" },
                    contentAlignment = Alignment.Center,
                ) { Text(Fmt.rpe(v), style = MaterialTheme.typography.titleSmall, color = if (selected) Fluff.Night else Fluff.Text) }
            }
        }
    }
}

private fun rirText(rpe: Double): String = when {
    rpe >= 10 -> "Nothing left"
    rpe >= 9.5 -> "Maybe 1 more"
    else -> "${(10 - rpe).let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }} left in the tank"
}

@Composable
private fun TimedControl(seconds: Int, color: Color, onStep: (Int) -> Unit, onDone: () -> Unit, haptics: Boolean) {
    var running by remember(seconds) { mutableStateOf(false) }
    var remaining by remember(seconds) { mutableFloatStateOf(seconds.toFloat()) }
    val hf = LocalHapticFeedback.current
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        val start = System.currentTimeMillis()
        while (remaining > 0f) {
            delay(100)
            remaining = (seconds - (System.currentTimeMillis() - start) / 1000f).coerceAtLeast(0f)
        }
        if (haptics) hf.performHapticFeedback(HapticFeedbackType.Confirm)
        running = false
        onDone()
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        ProgressRing(if (running) remaining / seconds else 1f, Modifier.size(150.dp), color = color, strokeWidth = 10.dp) {
            Text(Fmt.clock(kotlin.math.ceil(remaining).toLong()), style = FluffTheme.type.number)
        }
        Spacer(Modifier.height(14.dp))
        if (!running) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                GhostButton("−5s", { onStep(-1) })
                PrimaryButton("Start", { running = true }, icon = Icons.Rounded.PlayArrow, color = color)
                GhostButton("+5s", { onStep(1) })
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onDone) { Text("Mark done", color = Fluff.TextDim) }
        } else {
            GhostButton("Done early", { running = false; onDone() })
        }
    }
}

@Composable
private fun GroupRow(g: ExerciseGroup, state: WorkoutState, actions: WorkoutActions, modifier: Modifier, onSwap: () -> Unit) {
    val color = blockColor(g.block)
    val isCurrent = g == state.currentGroup
    val unit = state.profile.unit
    var open by remember(g.order) { mutableStateOf(false) }
    val showBlockHeader = state.groups.firstOrNull { it.block == g.block } == g
    Column(modifier) {
        if (showBlockHeader) Eyebrow(g.block.label, Modifier.padding(start = 6.dp, top = 10.dp, bottom = 6.dp), color = color)
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                .background(if (isCurrent) color.copy(alpha = 0.08f) else Fluff.Ink)
                .clickable { open = !open }
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val done = g.sets.count { it.completedAt != null }
                Box(Modifier.size(28.dp).clip(CircleShape).background(if (g.done) color else Fluff.CardHigh), contentAlignment = Alignment.Center) {
                    if (g.done) Icon(Icons.Rounded.Check, null, tint = Fluff.Night, modifier = Modifier.size(16.dp))
                    // Numbered within its block, so the first main lift is 1, not 7.
                    else Text("${state.groups.filter { it.block == g.block }.indexOf(g) + 1}", style = MaterialTheme.typography.labelMedium, color = Fluff.TextDim)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(g.name, style = MaterialTheme.typography.titleSmall, color = if (g.done) Fluff.TextDim else Fluff.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("$done of ${g.sets.size} " + if (g.sets.size == 1) "set" else "sets", style = MaterialTheme.typography.bodySmall, color = Fluff.TextFaint)
                }
                if (!g.done) IconButton(onClick = onSwap, modifier = Modifier.size(36.dp)) { Icon(Icons.Rounded.SwapHoriz, "Swap ${g.name}", tint = Fluff.TextFaint) }
            }
            AnimatedVisibility(open) {
                Column(Modifier.padding(top = 8.dp)) {
                    g.sets.forEachIndexed { i, s ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (s.kind == SetKind.WARMUP.name) "W" else "${i + 1}", style = MaterialTheme.typography.labelMedium, color = Fluff.TextFaint, modifier = Modifier.width(24.dp))
                            val text = if (s.completedAt != null) {
                                listOfNotNull(s.weightKg?.takeIf { it > 0 }?.let { Fmt.weightWithUnit(it, unit) }, s.reps?.let { "× $it" }, s.durationSec?.takeIf { s.reps == null }?.let { Fmt.duration(it) }, s.rpe?.let { "@ ${Fmt.rpe(it)}" }).joinToString(" ")
                            } else if (s.skipped) "Skipped" else targetLine(s, unit).removePrefix("Target  ")
                            Text(text, style = MaterialTheme.typography.bodyMedium, color = if (s.completedAt != null) Fluff.Text else Fluff.TextFaint, modifier = Modifier.weight(1f))
                            if (s.completedAt != null) {
                                IconButton(onClick = { actions.edit(s.id) }, modifier = Modifier.size(32.dp)) { Icon(Icons.Rounded.Edit, "Edit set ${i + 1}", tint = Fluff.TextFaint, modifier = Modifier.size(18.dp)) }
                            } else if (s.skipped) {
                                IconButton(onClick = { actions.undo(s) }, modifier = Modifier.size(32.dp)) { Icon(Icons.Rounded.Undo, "Bring back set ${i + 1}", tint = Fluff.TextFaint, modifier = Modifier.size(18.dp)) }
                            } else {
                                TextButton(onClick = { actions.select(s.id) }) { Text("Do", color = color) }
                            }
                        }
                    }
                    TextButton(onClick = { actions.addSet(g) }) { Icon(Icons.Rounded.Add, null, Modifier.size(16.dp)); Text(" Add set") }
                }
            }
        }
    }
}

@Composable
private fun EmptyWorkout(onAdd: () -> Unit) {
    FluffCard(padding = PaddingValues(24.dp)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Your workout, your way", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(6.dp))
            Text("Add exercises as you go. Fluff suggests a starting weight from your history and tracks every set.", style = MaterialTheme.typography.bodyMedium, color = Fluff.TextDim, textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            PrimaryButton("Add first exercise", onAdd, Modifier.fillMaxWidth(), icon = Icons.Rounded.Add)
        }
    }
}

@Composable
private fun AllDone(state: WorkoutState, onFinish: () -> Unit) {
    FluffCard(color = Fluff.Mint.copy(alpha = 0.10f), border = Fluff.Mint.copy(alpha = 0.3f), padding = PaddingValues(24.dp)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("🎉", style = MaterialTheme.typography.displayMedium)
            Text("Every set done", style = MaterialTheme.typography.headlineSmall)
            Text("${state.loggedCount} sets logged. Wrap it up to see your summary.", style = MaterialTheme.typography.bodyMedium, color = Fluff.TextDim, textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            PrimaryButton("Finish workout", onFinish, Modifier.fillMaxWidth(), color = Fluff.Mint)
        }
    }
}

@Composable
private fun RestOverlay(rest: RestTimer, haptics: Boolean, actions: WorkoutActions) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val hf = LocalHapticFeedback.current
    LaunchedEffect(rest.endsAtMs) {
        while (true) {
            now = System.currentTimeMillis()
            val left = rest.endsAtMs - now
            if (left in 2_500..3_400 && haptics) hf.performHapticFeedback(HapticFeedbackType.SegmentTick)
            if (left <= 0) {
                if (haptics) hf.performHapticFeedback(HapticFeedbackType.LongPress)
                actions.restSkip()
                break
            }
            delay(100)
        }
    }
    val leftSec = ((rest.endsAtMs - now) / 1000f).coerceAtLeast(0f)
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier.fillMaxSize().background(Fluff.Night.copy(alpha = 0.94f)).clickable(interaction, null) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
            Eyebrow("Rest", color = Fluff.Sky)
            Spacer(Modifier.height(16.dp))
            ProgressRing(leftSec / rest.totalSec, Modifier.size(240.dp), color = Fluff.Sky, strokeWidth = 14.dp) {
                Text(Fmt.clock(kotlin.math.ceil(leftSec).toLong()), style = FluffTheme.type.timer)
            }
            Spacer(Modifier.height(20.dp))
            rest.nextLabel?.let { Text("Up next: $it", style = MaterialTheme.typography.titleMedium, color = Fluff.TextDim) }
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GhostButton("+30s", { actions.restAdd(30) })
                PrimaryButton("Skip rest", actions.restSkip, color = Fluff.Sky, height = 48.dp)
            }
        }
    }
}

@Composable
private fun PrBanner(pr: PrEvent, state: WorkoutState) {
    val unit = state.profile.unit
    Row(
        Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(16.dp).fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.horizontalGradient(listOf(Fluff.Amber, Fluff.Coral)))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.EmojiEvents, null, tint = Fluff.Night, modifier = Modifier.size(32.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text("New personal record!", style = MaterialTheme.typography.titleMedium, color = Fluff.Night)
            Text(
                "${pr.exerciseName}: est. max ${Fmt.weightWithUnit(pr.e1rmKg, unit)}" + (pr.previousKg?.let { " (was ${Fmt.weight(it, unit)})" } ?: ""),
                style = MaterialTheme.typography.bodyMedium, color = Fluff.Night.copy(alpha = 0.8f),
            )
        }
    }
}

@Composable
private fun FinishDialog(state: WorkoutState, onDismiss: () -> Unit, onFinish: (Double?, String?) -> Unit, onDiscard: () -> Unit) {
    var rpe by remember { mutableFloatStateOf(7f) }
    var notes by remember { mutableStateOf("") }
    val nothingLogged = state.loggedCount == 0
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Fluff.Ink,
        title = { Text(if (nothingLogged) "Nothing logged yet" else "Finish workout?") },
        text = {
            if (nothingLogged) {
                Text("You haven't logged any sets. Discard this session, or keep going.", color = Fluff.TextDim)
            } else {
                Column {
                    Text("How hard was the whole session?", style = MaterialTheme.typography.bodyMedium, color = Fluff.TextDim)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Slider(rpe, { rpe = it }, valueRange = 1f..10f, steps = 8, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(10.dp))
                        Text("${rpe.toInt()}/10", style = FluffTheme.type.numberSmall)
                    }
                    if (state.doneCount < state.totalCount) {
                        Text("${state.totalCount - state.doneCount} sets left. That's fine, they won't count.", style = MaterialTheme.typography.bodySmall, color = Fluff.TextFaint)
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(notes, { notes = it }, label = { Text("Notes (optional)") }, modifier = Modifier.fillMaxWidth(), maxLines = 3)
                }
            }
        },
        confirmButton = {
            if (nothingLogged) TextButton(onClick = onDiscard) { Text("Discard", color = Fluff.Rose) }
            else TextButton(onClick = { onFinish(rpe.toDouble(), notes) }) { Text("Finish", color = Fluff.Lime, fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            Row {
                if (!nothingLogged) TextButton(onClick = onDiscard) { Text("Discard", color = Fluff.Rose) }
                TextButton(onClick = onDismiss) { Text("Keep going") }
            }
        },
    )
}

@Composable
private fun SwapSheet(g: ExerciseGroup, options: List<Exercise>, onPick: (Exercise) -> Unit) {
    Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding()) {
        Text("Swap ${g.name}", style = MaterialTheme.typography.headlineSmall)
        Text("Same movement, fits your equipment and limitations.", style = MaterialTheme.typography.bodyMedium, color = Fluff.TextDim)
        Spacer(Modifier.height(12.dp))
        if (options.isEmpty()) Text("No alternatives for this one.", color = Fluff.TextFaint)
        options.forEach { ex ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { onPick(ex) }.padding(vertical = 12.dp, horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(ex.name, style = MaterialTheme.typography.titleMedium)
                    Text(ex.primary.joinToString { it.label }, style = MaterialTheme.typography.bodySmall, color = Fluff.TextFaint)
                }
                MovementTag(ex.movementType)
                Spacer(Modifier.width(6.dp))
                Pill(if (ex.equipment.isEmpty()) "Bodyweight" else ex.equipment.first().label)
            }
        }
    }
}
