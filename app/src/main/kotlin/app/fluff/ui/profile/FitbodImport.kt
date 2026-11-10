package app.fluff.ui.profile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.fluff.AppContainer
import app.fluff.core.importer.FitbodCsv
import app.fluff.core.importer.FitbodExport
import app.fluff.core.importer.FitbodFormatException
import app.fluff.core.importer.FitbodImportPlan
import app.fluff.core.importer.FitbodImporter
import app.fluff.core.model.WeightUnit
import app.fluff.ui.Fmt
import app.fluff.ui.components.FluffCard
import app.fluff.ui.components.GhostButton
import app.fluff.ui.components.PrimaryButton
import app.fluff.ui.theme.Fluff
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface FitbodImportState {
    data object Idle : FitbodImportState
    data object Working : FitbodImportState
    data class Preview(val plan: FitbodImportPlan, val alreadyImported: Int) : FitbodImportState
    data class Failed(val message: String) : FitbodImportState
}

/** Reads a Fitbod export, shows what it contains, then saves it as history. Nothing is written before Import. */
class FitbodImportViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow<FitbodImportState>(FitbodImportState.Idle)
    val state: StateFlow<FitbodImportState> = _state
    private var export: FitbodExport? = null

    fun load(read: () -> String) = viewModelScope.launch {
        _state.value = FitbodImportState.Working
        _state.value = runCatching {
            val parsed = withContext(Dispatchers.IO) { FitbodCsv.parse(read()) }
            export = parsed
            preview(FitbodImporter.plan(parsed))
        }.getOrElse { e ->
            FitbodImportState.Failed(if (e is FitbodFormatException) e.message.orEmpty() else "Couldn't read that file.")
        }
    }

    /** Fitbod labels its weight column kg; this lets the user correct it if their numbers are clearly pounds. */
    fun setUnit(unit: WeightUnit) = viewModelScope.launch {
        val e = export ?: return@launch
        _state.value = preview(FitbodImporter.plan(e, unit))
    }

    private suspend fun preview(plan: FitbodImportPlan): FitbodImportState.Preview {
        val existing = c.db.sessions().startTimesFrom(app.fluff.data.repo.FITBOD_SOURCE).toHashSet()
        return FitbodImportState.Preview(plan, plan.workouts.count { it.start.toEpochMilli() in existing })
    }

    fun import(onDone: () -> Unit) = viewModelScope.launch {
        val p = (_state.value as? FitbodImportState.Preview)?.plan ?: return@launch
        _state.value = FitbodImportState.Working
        runCatching {
            val (added, skipped) = c.training.importWorkouts(p.workouts)
            val profile = c.prefs.currentProfile()
            runCatching { c.training.checkGoals(c.training.history(profile.bodyWeightKg)) }
            // New history changes what today should look like, unless a workout is already under way or done.
            if (c.db.sessions().active() == null && !c.training.completedOn(c.engine.today().toEpochDay())) {
                runCatching { c.engine.planToday(force = true) }
            }
            c.messages.tryEmit(
                when {
                    added == 0 -> "Nothing new: those workouts are already in Fluff"
                    skipped > 0 -> "Imported ${workouts(added)} (${if (skipped == 1) "1 was" else "$skipped were"} already here)"
                    else -> "Imported ${workouts(added)} from Fitbod"
                },
            )
        }.onFailure { c.messages.tryEmit("Import failed: ${it.message ?: "unknown error"}") }
        reset()
        onDone()
    }

    fun reset() { export = null; _state.value = FitbodImportState.Idle }
}

private fun workouts(n: Int) = if (n == 1) "1 workout" else "$n workouts"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FitbodImportSheet(vm: FitbodImportViewModel, unit: WeightUnit, onDismiss: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.load { ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: error("empty") }
    }
    val close = { vm.reset(); onDismiss() }
    ModalBottomSheet(onDismissRequest = close, containerColor = Fluff.Ink, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding()) {
            Text("Import from Fitbod", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 12.dp))
            AnimatedContent(state, contentKey = { it::class }, label = "fitbodImport") { s ->
                Column {
                    when (s) {
                        FitbodImportState.Idle, is FitbodImportState.Failed -> {
                            FitbodImportContent.Intro(failure = (s as? FitbodImportState.Failed)?.message)
                            Spacer(Modifier.height(18.dp))
                            PrimaryButton("Choose export file", { picker.launch(arrayOf("text/*", "application/csv", "application/vnd.ms-excel", "application/octet-stream")) }, Modifier.fillMaxWidth(), icon = Icons.Rounded.FileOpen)
                        }
                        FitbodImportState.Working -> Row(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalArrangement = Arrangement.Center) {
                            CircularProgressIndicator(color = Fluff.Lime)
                        }
                        is FitbodImportState.Preview -> FitbodImportContent.Preview(s, unit, vm::setUnit, { vm.import(onDismiss) }, close)
                    }
                }
            }
        }
    }
}

/** Stateless pieces of the sheet, split out so they can be previewed and screenshot-tested. */
object FitbodImportContent {
    @Composable
    fun Intro(failure: String?) {
        Text(
            "Bring your Fitbod history into Fluff so your charts, records and daily plans start from what you've already lifted.",
            style = MaterialTheme.typography.bodyMedium, color = Fluff.TextDim,
        )
        Spacer(Modifier.height(14.dp))
        FluffCard(Modifier.fillMaxWidth()) {
            Text("Get the file", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            Text(
                "In Fitbod open Log, tap the gear icon, then Export Workout Data, and save the CSV to your phone.\n\n" +
                    "Fitbod for Android has no export button: ask Fitbod support for your workout data export and save the file they send.",
                style = MaterialTheme.typography.bodySmall, color = Fluff.TextDim,
            )
        }
        if (failure != null) {
            Spacer(Modifier.height(12.dp))
            Text(failure, style = MaterialTheme.typography.bodyMedium, color = Fluff.Amber)
        }
    }

    @Composable
    fun Preview(s: FitbodImportState.Preview, displayUnit: WeightUnit, onUnit: (WeightUnit) -> Unit, onImport: () -> Unit, onCancel: () -> Unit) {
        val p = s.plan
        val newCount = p.workouts.size - s.alreadyImported
        val range = listOfNotNull(p.firstDate, p.lastDate).distinct().joinToString(" – ") { Fmt.month(it) }
        FluffCard(Modifier.fillMaxWidth()) {
            Text("${p.workouts.size} workouts · ${p.setCount} sets", style = MaterialTheme.typography.titleMedium)
            Text(range, style = MaterialTheme.typography.bodySmall, color = Fluff.TextFaint)
            if (s.alreadyImported > 0) {
                Spacer(Modifier.height(6.dp))
                Text(if (s.alreadyImported == 1) "1 is already in Fluff and will be skipped" else "${s.alreadyImported} are already in Fluff and will be skipped", style = MaterialTheme.typography.bodySmall, color = Fluff.TextDim)
            }
        }
        Spacer(Modifier.height(12.dp))
        FluffCard(Modifier.fillMaxWidth()) {
            Text("Weights in this file are in", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                WeightUnit.entries.forEachIndexed { i, u ->
                    SegmentedButton(selected = p.unit == u, onClick = { onUnit(u) }, shape = SegmentedButtonDefaults.itemShape(i, WeightUnit.entries.size)) { Text(u.label) }
                }
            }
            p.heaviest?.let { (name, kg) ->
                Spacer(Modifier.height(8.dp))
                Text("Heaviest set: $name at ${Fmt.weightWithUnit(kg, displayUnit)}. If that looks wrong, switch the unit.", style = MaterialTheme.typography.bodySmall, color = Fluff.TextDim)
            }
        }
        Spacer(Modifier.height(12.dp))
        FluffCard(Modifier.fillMaxWidth()) {
            Text("${p.matched.size} exercises matched", style = MaterialTheme.typography.titleSmall, color = Fluff.Lime)
            Text("These feed your strength charts, records and the planner.", style = MaterialTheme.typography.bodySmall, color = Fluff.TextDim)
            if (p.historyOnly.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text("${p.historyOnly.size} kept as history only", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Fluff has no exact match, so these show in your history by name without affecting plans: " +
                        p.historyOnly.take(8).joinToString { it.fitbodName } + if (p.historyOnly.size > 8) " and ${p.historyOnly.size - 8} more." else ".",
                    style = MaterialTheme.typography.bodySmall, color = Fluff.TextDim,
                )
            }
        }
        Spacer(Modifier.height(18.dp))
        PrimaryButton(if (newCount > 0) "Import ${workouts(newCount)}" else "Nothing new to import", onImport, Modifier.fillMaxWidth(), enabled = newCount > 0)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            GhostButton("Cancel", onCancel)
        }
    }
}
