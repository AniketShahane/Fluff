package app.fluff.ui.progress

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.fluff.core.library.ExerciseLibrary
import app.fluff.core.model.ActivityRecord
import app.fluff.core.model.UserProfile
import app.fluff.core.progress.ActivityStrength
import app.fluff.core.progress.GoalProgress
import app.fluff.core.progress.GoalProjector
import app.fluff.core.progress.StrengthProfile
import app.fluff.core.progress.StrengthScorer
import app.fluff.core.progress.TrainingHistory
import app.fluff.core.progress.WeeklyVolume
import app.fluff.core.readiness.Readiness
import app.fluff.core.readiness.TrainingLoad
import app.fluff.data.prefs.PreferencesStore
import app.fluff.data.repo.TrainingRepository
import app.fluff.data.repo.toRecord
import app.fluff.domain.CoachEngine
import app.fluff.ui.components.ChartPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class LiftOption(val exerciseId: String, val name: String, val currentE1rm: Double?)

data class ProgressState(
    val loading: Boolean = true,
    val today: LocalDate = LocalDate.now(),
    val profile: UserProfile = UserProfile(),
    val strength: StrengthProfile? = null,
    val activityStrength: List<ActivityStrength> = emptyList(),
    val lifts: List<LiftOption> = emptyList(),
    val selectedLift: String? = null,
    val liftSeries: List<ChartPoint> = emptyList(),
    val goals: List<GoalProgress> = emptyList(),
    val weekly: List<WeeklyVolume> = emptyList(),
    val heat: Map<LocalDate, Float> = emptyMap(),
    val streak: Int = 0,
    val totalSessions: Int = 0,
    val readiness: List<Readiness> = emptyList(),
    val goalChoices: List<LiftOption> = emptyList(),
)

class ProgressViewModel(
    private val training: TrainingRepository,
    private val prefs: PreferencesStore,
    engine: CoachEngine,
) : ViewModel() {
    private val selected = MutableStateFlow<String?>(null)
    private val today = LocalDate.now()

    val state: StateFlow<ProgressState> = combine(
        training.completedSessions,
        prefs.profile,
        training.goals,
        combine(selected, engine.observeReadinessHistory(30), training.observeActivities(Instant.now().minus(Duration.ofDays(130))), ::Triple),
    ) { sessions, profile, goals, (sel, readiness, activities) ->
        val history = TrainingHistory(sessions, profile.bodyWeightKg)
        val strength = StrengthScorer.profile(history, profile.bodyWeightKg, profile.sex, today)
        val acts = profile.activities.map { it.type }.distinct().map { StrengthScorer.activityStrength(strength, it) }
        val lifts = ExerciseLibrary.all.filter { history.e1rmSeries(it.id).size >= 2 }
            .sortedByDescending { history.familiarity(it.id) }
            .map { LiftOption(it.id, it.name, history.currentE1rm(it.id, today)) }
        val pick = sel?.takeIf { s -> lifts.any { it.exerciseId == s } } ?: lifts.firstOrNull()?.exerciseId
        val series = pick?.let { id -> history.e1rmSeries(id).map { ChartPoint(it.date, it.e1rmKg) } }.orEmpty()
        val goalProgress = goals.map { GoalProjector.project(it, history.e1rmSeries(it.exerciseId), today) }
        val choices = (ExerciseLibrary.referenceLift.values.mapNotNull { ExerciseLibrary[it] } + lifts.mapNotNull { ExerciseLibrary[it.exerciseId] })
            .distinctBy { it.id }
            .filter { it.isAvailableWith(profile.equipment) && it.isLoaded }
            .map { LiftOption(it.id, it.name, history.currentE1rm(it.id, today)) }
        ProgressState(
            loading = false,
            today = today,
            profile = profile,
            strength = strength,
            activityStrength = acts,
            lifts = lifts,
            selectedLift = pick,
            liftSeries = series,
            goals = goalProgress,
            weekly = history.weeklyMuscleSets(today, 8),
            heat = heatmap(history, activities),
            streak = history.weekStreak(today),
            totalSessions = sessions.size,
            readiness = readiness,
            goalChoices = choices,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProgressState())

    private fun heatmap(history: TrainingHistory, activities: List<ActivityRecord>): Map<LocalDate, Float> {
        val zone = ZoneId.systemDefault()
        val load = mutableMapOf<LocalDate, Double>()
        history.sessions.forEach { load.merge(it.date, TrainingLoad.sessionLoad(it), Double::plus) }
        activities.forEach { load.merge(it.start.atZone(zone).toLocalDate(), it.load, Double::plus) }
        val max = load.values.maxOrNull()?.coerceAtLeast(1.0) ?: return emptyMap()
        // Square-root scaling keeps light days visible next to big ones.
        return load.mapValues { (_, v) -> kotlin.math.sqrt(v / max).toFloat().coerceIn(0.15f, 1f) }
    }

    fun selectLift(id: String) { selected.value = id }

    fun addGoal(exerciseId: String, targetKg: Double, weeks: Int?) = viewModelScope.launch {
        val profile = prefs.currentProfile()
        val current = training.history(profile.bodyWeightKg).currentE1rm(exerciseId, today)
        training.addGoal(exerciseId, targetKg, weeks?.let { today.plusWeeks(it.toLong()) }, current)
    }

    fun deleteGoal(id: Long) = viewModelScope.launch { training.deleteGoal(id) }
}
